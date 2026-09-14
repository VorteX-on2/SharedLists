# Shared Lists

Shared Lists synchronizes generic lists for one small trusted group across Android, iOS, and Windows.

## Development

The initial tracer establishes the Gradle modules and public shared-client contract. On Windows, run the complete host-supported verification:

```powershell
.\gradlew.bat --no-daemon hostCheck
```

`hostCheck` runs shared JVM tests, compiles the JVM modules, cross-compiles iOS simulator sources, and assembles the Android debug application.

The full product architecture, security contract, synchronization lifecycle, and server operations model are recorded in `CONTEXT.md`, `docs/adr`, and `docs/operations`.

## Windows enrollment and synchronization

The Windows/JVM application persists a server address, port, and SHA-256 certificate fingerprint. It creates one non-exportable P-256 key in the Windows CNG Software Key Storage Provider, retains only its certificate-store thumbprint, and signs through SunMSCAPI without exposing private-key bytes. After confirmation, the native save dialog exports the public SPKI PEM for the server administrator to copy into the allowlist and activate by restarting the server.

Until that PEM is allowed, the client remains read-only and reports an unenrolled device; after allowlisting, Connect uses the existing challenge-bound JWT facade. Reset device setup is confirmed before deleting the CNG certificate and key; it retains cached canonical state.
