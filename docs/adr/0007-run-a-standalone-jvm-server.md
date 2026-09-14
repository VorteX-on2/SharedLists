# Run a standalone JVM server

The Shared Lists MVP is distributed as one foreground runnable JAR for any Java 21 host, without containers, service integration, a reverse proxy, or plaintext transport. A small properties file configures its network and storage paths, while the process directly terminates TLS using one persistent self-generated P-256 certificate pinned by clients; this minimizes packaging and operations while preserving authenticated encryption for direct IP connections.

Startup validates the complete configuration, TLS identity, device-key allowlist, and SQLite database before serving, and runtime storage failure closes streams and exits nonzero. Operations use privacy-safe human-readable process logs without health or metrics endpoints, a 10-second graceful shutdown, fixed wire limits, and stopped whole-installation copies for backup, restore, upgrade, and rollback.
