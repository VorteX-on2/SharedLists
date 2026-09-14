# MVP server operations

## Runtime

Run the server as a foreground process on a Java 21 runtime:

```text
java -jar sharedlists-server.jar --config sharedlists.properties
```

The administrator may use any external process manager. The MVP does not include an installer, daemon mode, restart loop, container image, reverse proxy, or service-manager integration.

## Configuration

`sharedlists.properties` contains exactly these settings:

```properties
bindAddress=0.0.0.0
port=8443
serverIp=192.0.2.10
databaseFile=data/sharedlists.db
authorizedDevicesDirectory=data/authorized-devices
tlsCertificateFile=data/tls/server.pem
tlsPrivateKeyFile=data/tls/server-key.pem
```

Relative paths resolve from the configuration file's directory. The server rejects missing, duplicate, unknown, or invalid settings. Its canonical service URI and JWT audience are `https://<serverIp>:<port>`.

## First startup

When both TLS files are absent, the server creates a persistent P-256 private key with restrictive host-appropriate file permissions and a self-signed certificate containing `serverIp` as an IP subject alternative name. The certificate is valid for ten years. The server prints its SHA-256 certificate fingerprint for manual entry on each client.

The server never silently replaces its identity. Startup fails if only one TLS file exists, the pair is unreadable or mismatched, the certificate is expired, or its IP does not match `serverIp`.

A missing database initializes the current schema. An empty valid authorized-device directory starts successfully but rejects all clients. Enrollment changes take effect only after a controlled restart.

## Runtime behavior

Logs are human-readable on stdout and stderr. They include startup checks, enrollment-file failures, session termination reasons, storage failures, and shutdown, but exclude JWTs, private material, operation payloads, and list contents. There is no health or metrics endpoint.

The server uses these fixed limits:

- 1 MiB per protobuf message.
- 100 lists, items, or journal entries per synchronization batch.
- One active stream per enrolled device key.
- One submitted edit in flight per stream.

A fatal storage error closes active streams and exits nonzero. On Ctrl+C or a termination signal, the server stops accepting RPCs, closes streams with `UNAVAILABLE`, allows the current SQLite transaction to finish, closes the database, and exits within ten seconds; failure to shut down cleanly produces a nonzero exit.

## Backup, restore, upgrade, and rollback

The only supported backup is a stopped copy of the whole installation: JAR, configuration, database, TLS identity, and authorized-device directory.

To restore, stop the process, replace the complete installation with one matching backup, and start it. Startup validation decides whether the restored installation is usable.

To upgrade, stop the process, take a whole-installation backup, replace the JAR, and start it. If startup rejects the schema or configuration, restore both the previous JAR and its matching backup. The MVP provides no online backup, migration runner, partial restore, or automatic rollback.

## Server identity replacement

Replacing the certificate is an explicit administrative recovery:

1. Stop the server and take a whole-installation backup.
2. Change `serverIp` if required.
3. Delete both configured TLS files.
4. Start the server to generate a new identity.
5. Manually replace the pinned fingerprint on every client.

Deleting only one TLS file is an error and never triggers generation.
