# Cross-platform transport and authentication spike

This branch is a disposable evidence spike for #13. It is not production application code.

The shared `.proto` is generated twice: JetBrains `kotlinx.rpc` generates the Android/iOS/Windows-JVM client API, while official protobuf/grpc-kotlin plugins generate the JVM server API. The Windows executable uses a non-exportable P-256 key in the Windows certificate store through SunMSCAPI/CNG. Android and iOS adapters use Android Keystore and Security.framework respectively.

Run host checks:

```powershell
.\gradlew.bat --no-daemon spikeCheck
```

The exact Windows provisioning and two-process integration commands, results, and remaining device checklist are in [`docs/spikes/issue-13-transport-auth.md`](docs/spikes/issue-13-transport-auth.md).
