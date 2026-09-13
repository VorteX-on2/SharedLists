package dev.sharedlists.spike.server

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name

internal object PemAllowlist {
    fun load(directory: Path): Map<String, ECPublicKey> {
        require(directory.isDirectory()) { "allowlist is not a readable directory: $directory" }
        val expectedParams = java.security.AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(java.security.spec.ECParameterSpec::class.java)
        }
        val loaded = linkedMapOf<String, ECPublicKey>()
        Files.list(directory).use { paths ->
            paths.filter { it.extension.equals("pem", ignoreCase = true) }
                .sorted()
                .forEach { path ->
                    val pem = Files.readString(path)
                    require(!pem.contains("PRIVATE KEY")) { "${path.name}: private-key content is forbidden" }
                    val match = PUBLIC_KEY_PEM.matchEntire(pem.trim())
                        ?: error("${path.name}: expected one P-256 BEGIN PUBLIC KEY PEM")
                    val der = runCatching { Base64.getMimeDecoder().decode(match.groupValues[1]) }
                        .getOrElse { error("${path.name}: malformed PEM base64") }
                    val key = runCatching {
                        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der)) as ECPublicKey
                    }.getOrElse { error("${path.name}: malformed or unsupported public key") }
                    require(
                        key.params.curve.field.fieldSize == 256 &&
                            key.params.order == expectedParams.order &&
                            key.params.generator == expectedParams.generator,
                    ) { "${path.name}: only P-256 SubjectPublicKeyInfo is supported" }
                    val kid = key.encoded.sha256Kid()
                    require(loaded.putIfAbsent(kid, key) == null) {
                        "${path.name}: duplicate enrolled fingerprint $kid"
                    }
                }
        }
        return loaded
    }

    private val PUBLIC_KEY_PEM = Regex(
        """-----BEGIN PUBLIC KEY-----\s+([A-Za-z0-9+/=\r\n]+)\s+-----END PUBLIC KEY-----""",
    )
}
