# Shared Lists

Shared Lists synchronizes generic lists for one small trusted group across Android, iOS, and Windows.

## Development

The initial tracer establishes the Gradle modules and public shared-client contract. On Windows:

```powershell
.\gradlew.bat --no-daemon check
.\gradlew.bat --no-daemon :shared-client:compileKotlinIosSimulatorArm64
.\gradlew.bat --no-daemon :android-client:assembleDebug
```

The full product architecture, security contract, synchronization lifecycle, and server operations model are recorded in `CONTEXT.md`, `docs/adr`, and `docs/operations`.
