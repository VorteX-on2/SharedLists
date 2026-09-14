package dev.sharedlists.server

import java.net.InetAddress
import java.nio.file.Path
import kotlin.io.path.readLines

internal data class ServerConfiguration(
    val authorizedDevicesDirectory: Path,
    val bindAddress: String,
    val databaseFile: Path,
    val port: Int,
    val serverIp: String,
    val tlsCertificateFile: Path,
    val tlsPrivateKeyFile: Path,
) {
    val serviceUri: String
        get() = "https://${if (serverIp.contains(':')) "[$serverIp]" else serverIp}:$port"

    companion object {
        private val REQUIRED_KEYS = setOf(
            "bindAddress",
            "authorizedDevicesDirectory",
            "databaseFile",
            "port",
            "serverIp",
            "tlsCertificateFile",
            "tlsPrivateKeyFile",
        )

        fun load(file: Path): ServerConfiguration {
            val properties = file.readLines()
                .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
                .map { line ->
                    val separator = line.indexOf('=')
                    require(separator > 0) { "Invalid configuration line." }
                    line.substring(0, separator).trim() to line.substring(separator + 1).trim()
                }.associateStrictly()
            val unknown = properties.keys - REQUIRED_KEYS
            val missing = REQUIRED_KEYS - properties.keys
            require(unknown.isEmpty()) { "Unknown configuration settings: ${unknown.sorted().joinToString()}" }
            require(missing.isEmpty()) { "Missing configuration settings: ${missing.sorted().joinToString()}" }
            val baseDirectory = requireNotNull(file.toAbsolutePath().parent) { "Configuration file has no parent directory." }
            val bindAddress = parseIpAddress(properties.getValue("bindAddress"), "bindAddress")
            val serverIp = parseIpAddress(properties.getValue("serverIp"), "serverIp")
            return ServerConfiguration(
                authorizedDevicesDirectory = resolve(baseDirectory, properties.getValue("authorizedDevicesDirectory")),
                bindAddress = bindAddress,
                databaseFile = resolve(baseDirectory, properties.getValue("databaseFile")),
                port = properties.getValue("port").toIntOrNull()?.also { require(it in 1..65535) }
                    ?: error("port must be an integer from 1 to 65535."),
                serverIp = serverIp,
                tlsCertificateFile = resolve(baseDirectory, properties.getValue("tlsCertificateFile")),
                tlsPrivateKeyFile = resolve(baseDirectory, properties.getValue("tlsPrivateKeyFile")),
            )
        }

        private fun parseIpAddress(value: String, name: String): String {
            require(value.isNotBlank()) { "$name must not be blank." }
            require(value.contains(':') || value.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) {
                "$name must be a literal IP address."
            }
            val address = InetAddress.getByName(value)
            return address.hostAddress
        }

        private fun resolve(baseDirectory: Path, value: String): Path =
            require(value.isNotBlank()) { "Configuration path must not be blank." }
                .let { Path.of(value).let { path -> if (path.isAbsolute) path else baseDirectory.resolve(path) }.normalize() }

        private fun <K, V> Iterable<Pair<K, V>>.associateStrictly(): Map<K, V> =
            associate { pair ->
                require(count { it.first == pair.first } == 1) { "Duplicate configuration setting: ${pair.first}" }
                pair
            }
    }
}
