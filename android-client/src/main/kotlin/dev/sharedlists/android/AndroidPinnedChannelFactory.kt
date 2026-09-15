package dev.sharedlists.android

import dev.sharedlists.client.ServerEndpoint
import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class AndroidPinnedChannelFactory {
    fun create(endpoint: ServerEndpoint): ManagedChannel {
        val trustManager = AndroidCertificatePinTrustManager(endpoint.certificatePin)
        val socketFactory = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), null)
        }.socketFactory
        return OkHttpChannelBuilder.forAddress(endpoint.host, endpoint.port)
            .sslSocketFactory(socketFactory)
            .build()
    }
}

private class AndroidCertificatePinTrustManager(
    private val certificatePin: String,
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val certificate = requireNotNull(chain.firstOrNull()) { "Server did not provide a TLS certificate." }
        certificate.checkValidity()
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { byte -> "%02X".format(byte) }
        require(fingerprint.equals(certificatePin.replace(":", ""), ignoreCase = true)) {
            "Server TLS certificate does not match the configured fingerprint."
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
