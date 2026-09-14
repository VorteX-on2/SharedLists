# Shared Lists

Shared Lists synchronizes generic lists for one small trusted group across Android, iOS, and Windows.

## Development

The initial tracer establishes the Gradle modules and public shared-client contract. On Windows, run the complete host-supported verification:

```powershell
.\gradlew.bat --no-daemon hostCheck
```

`hostCheck` runs shared JVM tests, compiles the JVM modules, cross-compiles iOS simulator sources, and assembles the Android debug application.

The full product architecture, security contract, synchronization lifecycle, and server operations model are recorded in `CONTEXT.md`, `docs/adr`, and `docs/operations`.
