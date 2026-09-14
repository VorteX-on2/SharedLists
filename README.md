# Shared Lists

Shared Lists synchronizes generic lists for one small trusted group across Android, iOS, and Windows.

## Development

The initial tracer establishes the Gradle modules and public shared-client contract. On Windows, run the complete host-supported verification:

```powershell
.\gradlew.bat --no-daemon hostCheck
```

`hostCheck` runs shared JVM tests, compiles the JVM modules, cross-compiles iOS simulator sources, and assembles the Android debug application.

The full product architecture, security contract, synchronization lifecycle, and server operations model are recorded in `CONTEXT.md`, `docs/adr`, and `docs/operations`.

## Windows synchronization tracer

The Windows/JVM application provides a native desktop view for the empty synchronization tracer. It persists a configured server address, port, and SHA-256 certificate fingerprint; shows connecting, synchronizing, disconnected, and live-empty presentation states; and keeps cached canonical lists read-only until the public shared-client facade reports enrolled and live.

Windows device-key custody and enrollment remain separate work. Until a Windows `DeviceSigner` is configured, the app clearly reports that device setup is required instead of attempting an unauthenticated connection.
