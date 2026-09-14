package dev.sharedlists.server

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

internal class SqliteCanonicalStore(
    databaseFile: Path,
) : AutoCloseable {
    private val connection: Connection

    init {
        Files.createDirectories(databaseFile.parent)
        connection = DriverManager.getConnection("jdbc:sqlite:${databaseFile.toAbsolutePath()}")
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
            statement.execute("PRAGMA journal_mode = DELETE")
            statement.execute("PRAGMA synchronous = FULL")
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS synchronization_metadata (
                    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
                    generation TEXT NOT NULL,
                    head_revision INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }
        connection.prepareStatement(
            "INSERT OR IGNORE INTO synchronization_metadata (singleton, generation, head_revision) VALUES (1, ?, 0)",
        ).use { statement ->
            statement.setString(1, UUID.randomUUID().toString())
            statement.executeUpdate()
        }
    }

    fun snapshot(): CanonicalSnapshot =
        connection.prepareStatement(
            "SELECT generation, head_revision FROM synchronization_metadata WHERE singleton = 1",
        ).use { statement ->
            statement.executeQuery().use { result ->
                check(result.next()) { "Synchronization metadata is missing." }
                CanonicalSnapshot(result.getString("generation"), result.getLong("head_revision"))
            }
        }

    override fun close() {
        connection.close()
    }
}

internal data class CanonicalSnapshot(
    val generation: String,
    val revision: Long,
)
