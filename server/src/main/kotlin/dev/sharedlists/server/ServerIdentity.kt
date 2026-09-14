package dev.sharedlists.server

import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Date
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

internal data class ServerIdentity(
    val certificateFile: Path,
    val privateKeyFile: Path,
    val fingerprint: String,
)

internal object ServerIdentityManager {
    init {
        Security.addProvider(BouncyCastleProvider())
    }

    fun loadOrCreate(configuration: ServerConfiguration): ServerIdentity {
        val certificateExists = Files.exists(configuration.tlsCertificateFile)
        val privateKeyExists = Files.exists(configuration.tlsPrivateKeyFile)
        require(certificateExists == privateKeyExists) { "TLS certificate and private key must both exist or both be absent." }
        if (!certificateExists) {
            Files.createDirectories(configuration.tlsCertificateFile.parent)
            Files.createDirectories(configuration.tlsPrivateKeyFile.parent)
            create(configuration)
        }
        val certificate = readCertificate(configuration.tlsCertificateFile)
        certificate.checkValidity()
        require(certificate.notAfter.toInstant().isAfter(Instant.now())) { "TLS certificate is expired." }
        require(
            certificate.subjectAlternativeNames.orEmpty().any { names ->
                names[0] == GeneralName.iPAddress && names[1] == configuration.serverIp
            },
        ) { "TLS certificate does not contain the configured server IP." }
        require(privateKeyMatchesCertificate(configuration.tlsPrivateKeyFile, certificate)) {
            "TLS certificate and private key do not match."
        }
        return ServerIdentity(
            certificateFile = configuration.tlsCertificateFile,
            privateKeyFile = configuration.tlsPrivateKeyFile,
            fingerprint = fingerprint(certificate),
        )
    }

    private fun create(configuration: ServerConfiguration) {
        val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val now = Instant.now()
        val subject = X500Name("CN=${configuration.serverIp}")
        val certificateBuilder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(160, SecureRandom()),
            Date.from(now.minus(1, ChronoUnit.MINUTES)),
            Date.from(now.plus(3650, ChronoUnit.DAYS)),
            subject,
            keyPair.public,
        ).addExtension(
            Extension.subjectAlternativeName,
            false,
            GeneralNames(GeneralName(GeneralName.iPAddress, configuration.serverIp)),
        )
        val certificate = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(certificateBuilder.build(JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)))
        write(configuration.tlsCertificateFile, certificate)
        writePrivateKey(configuration.tlsPrivateKeyFile, keyPair.private)
        restrictPrivateKeyPermissions(configuration.tlsPrivateKeyFile)
    }

    private fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString(":") { "%02X".format(it) }

    private fun readCertificate(file: Path): X509Certificate =
        InputStreamReader(Files.newInputStream(file)).use { reader ->
            val holder = PEMParser(reader).use { parser ->
                parser.readObject() as? X509CertificateHolder ?: error("TLS certificate is invalid.")
            }
            JcaX509CertificateConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME).getCertificate(holder)
        }

    private fun write(file: Path, value: Any) {
        OutputStreamWriter(Files.newOutputStream(file)).use { writer ->
            JcaPEMWriter(writer).use { pemWriter ->
                pemWriter.writeObject(value)
            }
        }
    }

    private fun writePrivateKey(file: Path, key: PrivateKey) {
        val encoded = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(key.encoded)
        Files.writeString(file, "-----BEGIN PRIVATE KEY-----\n$encoded\n-----END PRIVATE KEY-----\n")
    }

    private fun privateKeyMatchesCertificate(file: Path, certificate: X509Certificate): Boolean {
        val privateKey = readPrivateKey(file)
        val challenge = ByteArray(32).also(SecureRandom()::nextBytes)
        val signature = java.security.Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(challenge)
        val verifier = java.security.Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(certificate.publicKey)
        verifier.update(challenge)
        return verifier.verify(signature.sign())
    }

    private fun readPrivateKey(file: Path): PrivateKey =
        InputStreamReader(Files.newInputStream(file)).use { reader ->
            PEMParser(reader).use { parser ->
                val key = parser.readObject() ?: error("TLS private key is invalid.")
                require(parser.readObject() == null) { "TLS private key contains multiple PEM objects." }
                JcaPEMKeyConverter().getPrivateKey(
                    PrivateKeyInfo.getInstance(key),
                )
            }
        }

    private fun restrictPrivateKeyPermissions(file: Path) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        } catch (_: UnsupportedOperationException) {
            file.toFile().setReadable(false, false)
            file.toFile().setReadable(true, true)
            file.toFile().setWritable(false, false)
            file.toFile().setWritable(true, true)
        }
    }
}
