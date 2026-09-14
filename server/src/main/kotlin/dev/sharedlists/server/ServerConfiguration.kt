package dev.sharedlists.server

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
        get() = "https://$serverIp:$port"

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
            require(properties.keys == REQUIRED_KEYS) { "Configuration keys do not match the supported schema." }
            val baseDirectory = file.parent
            return ServerConfiguration(
                authorizedDevicesDirectory = resolve(baseDirectory, properties.getValue("authorizedDevicesDirectory")),
                bindAddress = properties.getValue("bindAddress"),
                databaseFile = resolve(baseDirectory, properties.getValue("databaseFile")),
                port = properties.getValue("port").toInt().also { require(it in 1..65535) },
                serverIp = properties.getValue("serverIp"),
                tlsCertificateFile = resolve(baseDirectory, properties.getValue("tlsCertificateFile")),
                tlsPrivateKeyFile = resolve(baseDirectory, properties.getValue("tlsPrivateKeyFile")),
            )
        }

        private fun resolve(baseDirectory: Path, value: String): Path =
            baseDirectory.resolve(value).normalize()

        private fun <K, V> Iterable<Pair<K, V>>.associateStrictly(): Map<K, V> =
            associate { pair ->
                require(count { it.first == pair.first } == 1) { "Duplicate configuration setting: ${pair.first}" }
                pair
            }
    }
}
