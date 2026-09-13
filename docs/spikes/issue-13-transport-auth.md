# Issue #13 transport and authentication spike

**Verdict:** partial success; keep #13 open. The Windows/JVM preferred-stack path is accepted. Android source generation, the Keystore integration, and instrumentation APKs compile, but no Android device test completed. The installed emulator remained offline and the accelerated launch crashed. iOS simulator sources cross-compile to a KLIB on Windows, but could not be linked into an Xcode app or run because this host has no Xcode/macOS runtime. The preferred stack therefore has not passed the required three-platform gate.

## What ran

On Windows 11 with Zulu OpenJDK `21.0.10`, a JetBrains `kotlinx.rpc` Windows/JVM client connected over TLS and standard protobuf/gRPC HTTP/2 to an official `grpc-kotlin`/`grpc-java` server. The run exercised:

- one shared proto3 schema generated independently by the preview KMP generator and official JVM generators;
- TLS-only challenge and bidirectional stream RPCs;
- a 32-byte `SecureRandom` challenge, server `iat`/`exp`, and an exact 60-second lifetime;
- a P-256 `BEGIN PUBLIC KEY` SPKI allowlist and full base64url SHA-256 SPKI fingerprint `kid`;
- compact ES256 JWTs with pinned `typ`, `kid`, matching key URN `iss`/`sub`, canonical audience, exact challenge timestamps, and nonce;
- DER ECDSA to fixed-width 64-byte JOSE `R || S` conversion;
- atomic one-time challenge consumption and replay rejection;
- newer-challenge invalidation and expiration in unit tests;
- a newer authenticated stream cancelling the prior stream for the same key;
- explicit stream/channel closure and foreground-to-background resource release;
- public-key removal plus controlled server restart rejecting the revoked device.

The disposable Windows key was requested first from `Microsoft Platform Crypto Provider`. This host could not create it there, so provisioning deliberately used `Microsoft Software Key Storage Provider`. The Java signer required `PrivateKey.encoded == null` before use, signed through `SunMSCAPI`, exported only SPKI public bytes, and deleted the test certificate after the run. This proves non-exportable Windows CNG software-KSP custody on this host; it does **not** prove TPM custody.

## Pinned versions

| Component | Version |
|---|---:|
| Gradle wrapper | 9.3.1 |
| Kotlin | 2.4.0 |
| Android Gradle Plugin | 9.1.0 |
| JetBrains kotlinx.rpc Gradle plugin and gRPC/protobuf artifacts | 0.11.0-grpc-189 |
| kotlinx.coroutines | 1.10.2 |
| grpc-java / grpc-netty / protoc-gen-grpc-java | 1.81.0 |
| grpc-kotlin | 1.5.0 |
| protobuf / protoc | 4.33.5 |
| protobuf Gradle plugin | 0.9.5 |
| Nimbus JOSE JWT (server verification only) | 10.8 |
| Android compile SDK | 36 |
| Android minimum SDK | 26 |

The researched stable `kotlinx.rpc` release is `0.10.3`, but the documented schema-first gRPC artifacts are currently published only from JetBrains' preview repository. The tested transport is therefore pinned to `0.11.0-grpc-189`, the version used by JetBrains' current gRPC sample, rather than misreporting stable `0.10.3` as the tested transport.

## Commands and outcomes

Repository checks:

```powershell
.\gradlew.bat --no-daemon :client-core:jvmTest :server:test :windows-client:test
# PASS

.\gradlew.bat --no-daemon :client-core:compileAndroidMain
# PASS: shared proto, common transport, and Android Keystore adapter compile

.\gradlew.bat --no-daemon :android-client:assembleDebug :android-client:assembleDebugAndroidTest
# PASS: Android app and instrumentation APKs compile

.\gradlew.bat --no-daemon :client-core:compileKotlinIosSimulatorArm64
# PASS: protocol and iOS adapter cross-compile to KLIB; no link/run occurred
```

Windows key provisioning used:

```powershell
New-SelfSignedCertificate `
  -Type Custom `
  -Subject "CN=SharedLists Spike <random>" `
  -KeyAlgorithm ECDSA_nistP256 `
  -HashAlgorithm SHA256 `
  -KeyExportPolicy NonExportable `
  -KeyUsage DigitalSignature `
  -CertStoreLocation Cert:\CurrentUser\My `
  -Provider "Microsoft Platform Crypto Provider"
# Failed: platform provider unavailable on this host

# The same command with:
-Provider "Microsoft Software Key Storage Provider"
# PASS
```

The `windows-client export` command loaded that opaque key from `Windows-MY`, rejected it if `PrivateKey.encoded` was non-null, and wrote only `BEGIN PUBLIC KEY` SPKI PEM. A one-day localhost TLS certificate was generated with Git for Windows OpenSSL `3.5.5`.

With the server running on `https://localhost:19443`:

```text
READY port=19443 enrolledKeys=1
PASS TLS protobuf bidi, replay rejection, stream supersession, and foreground resource closure
```

After deleting the allowlist PEM and restarting:

```text
READY port=19443 enrolledKeys=0
PASS revoked key rejected
```

Host capability probes:

```text
OpenJDK 21.0.10: available
Android SDK platforms 26, 29, 34, 35, 36, 36.1: available
Android build tools 34.0.0, 35.0.0, 36.0.0: available
Gradle command: absent; wrapper generated from cached Gradle 9.3.1
Xcode/xcodebuild: unavailable
macOS/iOS simulator or device: unavailable
Physical Android device: unavailable
Android emulator 36.4.10/API 35: accelerated launch crashed; software-emulated fallback stayed adb-offline
Windows TPM-backed Platform Crypto Provider for this test: unavailable
```

## Platform adapters and fallbacks

| Target | Spike adapter | Result |
|---|---|---|
| Windows/JVM | `WindowsCertificateStoreSigner`, SunMSCAPI over a named CNG key | Executed with non-exportable Software KSP key; TPM fallback path not exercised |
| Android | `AndroidKeystoreDeviceSigner`, P-256 `AndroidKeyStore`, StrongBox-first then ordinary Keystore | Adapter and instrumentation APK compiled; installed API 35 emulator could not reach online state, so custody, signing, TLS, and lifecycle were not executed |
| iOS | `IosKeychainDeviceSigner`, P-256 Security.framework key, Secure Enclave-first then Keychain fallback | Cross-compiled to an iOS Simulator ARM64 KLIB; no Xcode link, simulator, or physical-device execution |

Private-key bytes are never part of `DeviceSigner`. TLS test key files are unrelated server transport credentials and are not evidence for device-key custody.

## Unverified assumptions and minimum completion checklist

The following are blockers, not passes:

1. **Physical Android:** build and run the client on at least one StrongBox-capable device and one ordinary Keystore fallback device; record `KeyInfo` security level; verify the private key remains non-exportable; verify DER-to-JOSE signatures at the JVM server; run TLS challenge, bidi traffic, replay, supersession, background cancellation, reconnect, key deletion, locked-device behavior, and revoked-key restart.
2. **Physical iOS:** link the cross-compiled client into an Xcode app with the pinned Kotlin/plugin versions; run on a Secure Enclave device and a Keychain fallback environment; verify key persistence/accessibility, non-exportability, DER-to-JOSE signatures, TLS challenge, bidi traffic, replay, supersession, background cancellation, reconnect, deletion, locked-device behavior, and revocation.
3. **Windows hardware fallback:** repeat with a machine whose `Microsoft Platform Crypto Provider` can create the P-256 key and record TPM attestation/provider properties.
4. **Network edge:** run the three clients through the intended reverse proxy and verify HTTP/2 trailers, deadlines, maximum message sizes, malformed-call handling, cancellation, and server restart.
5. **Native build provenance:** capture Xcode, iOS SDK, Android device/API, StrongBox/TEE, Secure Enclave, and TPM/provider versions. iOS linkage and runtime behavior remain assumptions until this is done.

Do not select the TimOrtel native-Windows fallback based on this spike. Windows/JVM worked; the open decision is whether the same JetBrains preview client succeeds on physical Android and iOS.
