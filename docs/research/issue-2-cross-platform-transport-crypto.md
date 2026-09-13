# Issue #2 research: cross-platform transport and crypto stack

**Status:** recommendation for planning, not an implementation decision  
**Researched:** 2026-09-13  
**Targets:** Android, iOS, and Windows; synchronization only while the app is in the foreground

## Executive conclusion

There is no mature, fully first-party Kotlin Multiplatform stack that simultaneously provides native Android/iOS/Windows gRPC, protobuf, JWT, and hardware-backed key storage behind one common API.

Two end-to-end stacks are viable:

1. **Preferred when Windows may run on the JVM:** use JetBrains `kotlinx.rpc`'s schema-first gRPC client on Android, iOS, and a Windows JVM client; use `grpc-kotlin`/`grpc-java` on a JVM server. This has the strongest ownership story, but its gRPC integration is explicitly experimental/preview and is published from a development repository, so adoption must be gated by a three-platform interoperability spike. The current platform matrix includes JVM (including Android) and Apple for gRPC, but not Windows/MinGW; Windows works here because it is a JVM target, not a Kotlin/Native target ([kotlinx.rpc README](https://github.com/Kotlin/kotlinx-rpc/blob/main/README.md), [platform matrix](https://kotlin.github.io/kotlinx-rpc/platforms.html), [gRPC configuration](https://kotlin.github.io/kotlinx-rpc/grpc-configuration.html)).
2. **Preferred when Windows must be Kotlin/Native:** use `TimOrtel/GRPC-Kotlin-Multiplatform` 2.x for all clients and `grpc-kotlin`/`grpc-java` on the server. It advertises Android, JVM, Native (including iOS), and all four RPC shapes on JVM/Android/Native; its build explicitly publishes `mingwX64`. It is client-only, uses gRPC Java on JVM and Rust `tonic` on Native, and classifies MinGW among its non-testable targets, so Windows carries meaningful integration and maintenance risk ([project README](https://github.com/TimOrtel/GRPC-Kotlin-Multiplatform/blob/main/readme.md), [target configuration](https://github.com/TimOrtel/GRPC-Kotlin-Multiplatform/blob/main/buildSrc/src/main/java/MultiplatformTargets.kt), [2.0.0 release](https://github.com/TimOrtel/GRPC-Kotlin-Multiplatform/releases/tag/2.0.0)).

For either transport choice, use **ES256 device credentials** and put the private key behind a small common `DeviceSigner` interface with platform implementations. Generate and retain a non-exportable P-256 key in Android Keystore, iOS Keychain/Secure Enclave, and Windows CNG (prefer the TPM provider; fall back deliberately to the software KSP). `jwt-kt` 1.3.2 is a useful maintained KMP JOSE layer and supports ES256 on Android, Apple, JVM, and Windows, but secure storage is not turnkey: its ECDSA signer either imports PEM/DER bytes or accepts a `cryptography-kotlin` key object. A platform adapter is therefore required to sign with an opaque OS key handle without exporting private material ([jwt-kt README](https://github.com/Appstractive/jwt-kt/blob/master/README.md), [ECDSA API/source](https://github.com/Appstractive/jwt-kt/blob/master/jwt-ecdsa/src/commonMain/kotlin/com/appstractive/jwt/signatures/ECDSA.kt), [1.3.2 release](https://github.com/Appstractive/jwt-kt/releases/tag/v1.3.2)).

## Required architecture

### Protocol and connection lifecycle

Define messages and services once in checked-in `.proto` files. A synchronization method can use the standard bidirectional shape:

```protobuf
rpc Synchronize(stream ClientEnvelope) returns (stream ServerEnvelope);
```

In gRPC bidirectional streaming, the request and response streams are independent and preserve ordering within each stream ([gRPC core concepts](https://grpc.io/docs/what-is-grpc/core-concepts/#bidirectional-streaming)). Native gRPC maps requests and responses to HTTP/2 headers, length-prefixed messages, and trailers; authentication can be carried as request metadata such as `authorization` ([gRPC over HTTP/2 protocol](https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md)).

The application, not the transport, must own offline correctness:

- Open one authenticated stream only while the app is foreground-active.
- Cancel the stream and release its channel when the app is suspended or closed.
- On foreground/resume, obtain a fresh short-lived assertion, reconnect, exchange checkpoints, and resend unacknowledged operations.
- Give every mutation a stable operation ID and make server application idempotent. The gRPC protocol does not assume calls are idempotent and provides no duplicate suppression ([gRPC protocol, “Idempotency and Retries”](https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md#idempotency-and-retries)).
- Set deadlines and treat cancellation, unavailable transport, and ambiguous completion as reconnection events; gRPC explicitly notes that client and server can disagree about whether a call completed ([gRPC lifecycle](https://grpc.io/docs/what-is-grpc/core-concepts/#rpc-life-cycle)).

This keeps foreground transport separate from durable synchronization state and avoids relying on a long-lived mobile connection for correctness.

### Protobuf compatibility rules

Use proto3 with explicit `optional` fields where presence matters. Once deployed, field numbers identify fields on the wire and must not be changed or reused; deleted numbers and names should be reserved ([official proto3 guide](https://protobuf.dev/programming-guides/proto3/#assigning-field-numbers), [deleting fields](https://protobuf.dev/programming-guides/proto3/#deleting-fields)). Do not use Google's official Kotlin generator as the shared KMP model solution: its Kotlin output is layered on generated Java classes and requires both `--java_out` and `--kotlin_out`, so it does not supply Kotlin/Native iOS models ([official Kotlin generated-code guide](https://protobuf.dev/reference/kotlin/kotlin-generated/#compiler-invocation)).

Keep the `.proto` schema as the interoperability boundary. The JVM server may use official Java protobuf messages while clients use a KMP generator; compatible protobuf wire encoding and the standard gRPC HTTP/2 mapping, rather than identical generated classes, are the contract ([gRPC core concepts](https://grpc.io/docs/what-is-grpc/core-concepts/#service-definition), [gRPC protocol](https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md)).

## Transport and model options

| Option | Android | iOS | Windows | Bidi streaming | Server integration | Assessment |
|---|---|---|---|---|---|---|
| **JetBrains `kotlinx.rpc` gRPC preview** | Yes | Yes | Yes on JVM; no MinGW gRPC artifact | gRPC service generation/runtime is present, but preview | Standard JVM gRPC dependencies and a Ktor server adapter are documented | Best ownership and one schema-first KMP client if Windows/JVM is acceptable; require a spike because the integration is experimental and comes from a dev repository ([README](https://github.com/Kotlin/kotlinx-rpc/blob/main/README.md), [platform matrix](https://kotlin.github.io/kotlinx-rpc/platforms.html), [configuration](https://kotlin.github.io/kotlinx-rpc/grpc-configuration.html), [0.10.3 release](https://github.com/Kotlin/kotlinx-rpc/releases/tag/0.10.3)). |
| **TimOrtel gRPC KMP 2.x** | Yes | Yes | Yes, `mingwX64` | Yes on JVM/Android and Native | Client-only; pair with any conforming gRPC server | Only reviewed option that directly covers native Windows plus iOS and Android. Main risks are single-community-project ownership, Rust/`tonic` native packaging, and MinGW being placed in the project's non-testable target group ([README](https://github.com/TimOrtel/GRPC-Kotlin-Multiplatform/blob/main/readme.md), [targets](https://github.com/TimOrtel/GRPC-Kotlin-Multiplatform/blob/main/buildSrc/src/main/java/MultiplatformTargets.kt)). |
| **Square Wire** | Models and gRPC | Models; generated gRPC API | Models and generated gRPC API include `mingwX64` | API models all streaming shapes | Separate `wire-grpc-server` exists | Strong protobuf-model option, but not a turnkey cross-platform transport. The non-JVM `GrpcClient` is only an abstract `actual` class, leaving transport construction to another implementation; the documented ready-to-use `GrpcClient.Builder` uses OkHttp on JVM ([gRPC docs](https://square.github.io/wire/wire_grpc/), [target build](https://github.com/square/wire/blob/master/wire-grpc-client/build.gradle.kts), [non-JVM `GrpcClient`](https://github.com/square/wire/blob/master/wire-grpc-client/src/nonJvmMain/kotlin/com/squareup/wire/GrpcClient.kt)). Do not select it alone to satisfy this issue. |
| **Official `grpc-kotlin`** | Yes (JVM/Android) | No | Yes on JVM | Yes via coroutine/Flow APIs | First-party client and server | Excellent server and JVM client, but explicitly a Kotlin/JVM implementation whose message types are generated as Java classes; it is not the common KMP client ([README](https://github.com/grpc/grpc-kotlin/blob/master/README.md), [streaming tutorial](https://grpc.io/docs/languages/kotlin/basics/#defining-the-service), [1.5.0 release](https://github.com/grpc/grpc-kotlin/releases/tag/v1.5.0)). |

### Maintenance signals

These signals indicate current activity, not a guarantee of future support:

- JetBrains labels `kotlinx.rpc` an official product but also labels the library experimental and gRPC “preview”; release 0.10.3 was published 2026-06-26 ([README](https://github.com/Kotlin/kotlinx-rpc/blob/main/README.md), [release](https://github.com/Kotlin/kotlinx-rpc/releases/tag/0.10.3)).
- `GRPC-Kotlin-Multiplatform` 2.0.0 was published 2026-03-07 and its current README documents protobuf Editions 2024 plus Android/iOS/JVM/JS/Wasm targets ([release](https://github.com/TimOrtel/GRPC-Kotlin-Multiplatform/releases/tag/2.0.0), [README](https://github.com/TimOrtel/GRPC-Kotlin-Multiplatform/blob/main/readme.md)).
- `grpc-kotlin` 1.5.0 was published 2025-09-16 and remains the official Kotlin/JVM implementation ([release](https://github.com/grpc/grpc-kotlin/releases/tag/v1.5.0), [repository](https://github.com/grpc/grpc-kotlin)).
- `cryptography-kotlin` 0.6.0 was published 2026-04-02 and wraps JCA, Apple/CryptoKit, OpenSSL, and WebCrypto rather than implementing primitives itself ([release](https://github.com/whyoleg/cryptography-kotlin/releases/tag/0.6.0), [README](https://github.com/whyoleg/cryptography-kotlin/blob/main/README.md)).
- `jwt-kt` 1.3.2 was published 2026-08-30 and documents RS256/384/512, PS256/384/512, and ES256/384/512 across Android, Apple, JVM, and Windows ([release](https://github.com/Appstractive/jwt-kt/releases/tag/v1.3.2), [README](https://github.com/Appstractive/jwt-kt/blob/master/README.md)).

## JWT and private-key design

### Algorithm choice

Use **ES256 (ECDSA P-256 with SHA-256)** as the sole accepted client-signing algorithm:

- Android StrongBox's required subset includes ECDSA P-256 ([Android Keystore documentation](https://developer.android.com/privacy-and-security/keystore#StrongBox-KeyMint-secure-element)).
- Apple's documented Secure Enclave key flow uses a key generated by `SecKeyCreateRandomKey` and selected with `kSecAttrTokenIDSecureEnclave` ([Apple Secure Enclave key documentation](https://developer.apple.com/documentation/security/protecting-keys-with-the-secure-enclave), [`SecKeyCreateRandomKey`](https://developer.apple.com/documentation/security/seckeycreaterandomkey(_:_:))).
- Windows CNG supports ECDSA P-256 in its software KSP, while the Microsoft Platform Crypto Provider uses the TPM for hardware-backed, non-extractable private keys ([CNG provider matrix](https://learn.microsoft.com/en-us/windows/win32/seccertenroll/cng-key-storage-providers)).
- JWA defines ES256 and requires a 64-byte JWS signature made by concatenating fixed-width 32-byte `R` and `S` values; platform APIs that return ASN.1 DER ECDSA signatures must be converted at the adapter boundary ([RFC 7518 §3.4](https://datatracker.ietf.org/doc/html/rfc7518#section-3.4)).

Do not permit the JWT header to choose arbitrary algorithms. Pin ES256 and validate issuer, audience, subject/device ID, expiry, not-before, issued-at, and a unique token ID/nonce. JWT Best Current Practice requires callers to specify supported algorithms and each key to be used with exactly one algorithm, and recommends audience validation and explicit typing to prevent substitution attacks ([RFC 8725 §§3.1, 3.8, 3.9, 3.11](https://www.rfc-editor.org/rfc/rfc8725.html#section-3)).

### Platform key custody

| Platform | Required adapter behavior | Constraint/fallback |
|---|---|---|
| Android | Generate an EC signing key with the `AndroidKeyStore` provider; retain only its alias and public key; sign through `java.security.Signature`. Android states that key material remains non-exportable and may be bound to TEE/StrongBox hardware ([Android Keystore](https://developer.android.com/privacy-and-security/keystore), [`Signature`](https://developer.android.com/reference/java/security/Signature)). | StrongBox is optional and can reject unsupported combinations, so prefer it and fall back to ordinary Android Keystore while recording the actual security level ([StrongBox guidance](https://developer.android.com/privacy-and-security/keystore#StrongBox-KeyMint-secure-element)). |
| iOS | Generate a permanent P-256 key through Security.framework, store/retrieve it from Keychain by application tag, and sign through `SecKeyCreateSignature`; request Secure Enclave when available ([Secure Enclave key flow](https://developer.apple.com/documentation/security/protecting-keys-with-the-secure-enclave), [`SecKeyCreateSignature`](https://developer.apple.com/documentation/security/seckeycreatesignature(_:_:_:_:))). | Secure Enclave availability must not be assumed; define a Keychain-backed software-key fallback and an explicit accessibility class. |
| Windows | Create a named per-user key with `NCryptCreatePersistedKey`, finalize it, and sign with `NCryptSignHash`. Prefer `MS_PLATFORM_CRYPTO_PROVIDER` for TPM protection; fall back to the Microsoft Software KSP when TPM/VBS is unavailable ([key creation](https://learn.microsoft.com/en-us/windows/win32/api/ncrypt/nf-ncrypt-ncryptcreatepersistedkey), [signing](https://learn.microsoft.com/en-us/windows/win32/api/ncrypt/nf-ncrypt-ncryptsignhash), [providers](https://learn.microsoft.com/en-us/windows/win32/seccertenroll/cng-key-storage-providers)). | CNG's storage router abstracts KSPs and key isolation; hardware availability varies. Never silently replace a lost key—create a new device identity and re-enroll it ([CNG storage architecture](https://learn.microsoft.com/en-us/windows/win32/seccng/key-storage-and-retrieval)). |

Expose only operations, not key bytes:

```text
DeviceSigner
  keyId(): String
  publicJwk(): Jwk
  signEs256(signingInput: ByteArray): ByteArray  // returns JOSE raw R || S
  delete()
```

`cryptography-kotlin` is suitable for portable hashing, verification, tests, and software-key fallbacks: it exposes RSA-PSS, RSA-PKCS1, and ECDSA and selects JCA on JVM, Apple providers on Apple, and OpenSSL for non-Apple Native targets ([algorithm matrix](https://github.com/whyoleg/cryptography-kotlin/blob/main/README.md), [optimal-provider wiring](https://github.com/whyoleg/cryptography-kotlin/blob/main/cryptography-providers/optimal/build.gradle.kts)). It is **not** a common secure-keystore abstraction: the provider wiring does not select Android Keystore or Windows CNG. The OS-handle adapters therefore remain required.

`jwt-kt` can build and validate claims and can accept a `cryptography-kotlin` `ECDSA.PrivateKey`; its built-in convenience path imports PEM/DER, while its source defaults ECDSA output to JOSE `RAW` format ([ECDSA signer source](https://github.com/Appstractive/jwt-kt/blob/master/jwt-ecdsa/src/commonMain/kotlin/com/appstractive/jwt/signatures/ECDSA.kt)). A custom `ECDSA.PrivateKey`/`SignatureGenerator` adapter is possible because those are public provider-extension interfaces, but it opts into `CryptographyProviderApi`; alternatively, keep compact-JWS assembly in a small audited common module and invoke `DeviceSigner`. Either route needs cross-platform RFC test vectors.

### Server verification and enrollment

Use a JVM server with official `grpc-kotlin` coroutine service stubs over `grpc-java` Netty. `grpc-kotlin` generates both client and server plumbing and publishes official artifacts to Maven Central ([grpc-kotlin README](https://github.com/grpc/grpc-kotlin/blob/master/README.md)). Accept the compact JWT in gRPC `authorization` metadata, look up the enrolled public key by a server-issued `kid`, pin ES256, validate claims, and reject a reused `jti`/challenge during its short validity window. JWT is a claims container protected by JWS; it is not encrypted unless JWE is used, so TLS remains mandatory and private data should not be placed in claims ([RFC 7519](https://datatracker.ietf.org/doc/html/rfc7519), [gRPC metadata](https://grpc.io/docs/what-is-grpc/core-concepts/#metadata)).

Enrollment must prove possession of the private key: the client submits its public JWK, signs a server nonce, and the server records a device identifier, key ID, public key, algorithm, creation time, and revocation state. Recovery/reinstall creates a new key and requires re-enrollment; non-exportable device keys are intentionally not backup credentials.

## Interoperability and operational gaps

1. **Kotlin/Native Windows is Tier 3.** Kotlin says `mingwX64` is not guaranteed CI-tested and does not promise source/binary compatibility across compiler releases ([Kotlin/Native target tiers](https://kotlinlang.org/docs/native-target-support.html#tier-3)). Prefer Windows/JVM unless native Windows is a product requirement.
2. **Preview transport risk.** `kotlinx.rpc` gRPC is explicitly experimental, and its setup currently requires JetBrains' gRPC development Maven repository ([configuration](https://kotlin.github.io/kotlinx-rpc/grpc-configuration.html#repositories)). Pin exact versions and do not allow dynamic versions.
3. **Community transport risk.** TimOrtel's library is client-only and its Native path adds Rust/`tonic` build and binary concerns; its own setup says Rust is needed for local native builds ([implementation/build notes](https://github.com/TimOrtel/GRPC-Kotlin-Multiplatform/blob/main/readme.md#implementation-details)).
4. **JWT library does not solve custody.** Passing exported PEM/DER to a common JWT library defeats the non-exportable-key objective. The signer must accept opaque OS handles and normalize ECDSA signatures to the JWA raw format.
5. **Proxy requirements.** Reverse proxies and load balancers must preserve HTTP/2 streaming and trailers. The native gRPC protocol relies on HTTP/2 HEADERS/DATA/trailers and `grpc-status` trailers ([gRPC HTTP/2 protocol](https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md)).
6. **Schema evolution is part of offline support.** Old clients may reconnect after schema changes; retain unknown fields where the selected generator supports them, never reuse field numbers, and include protocol-version/capability negotiation in the first stream exchange ([proto3 compatibility rules](https://protobuf.dev/programming-guides/proto3/#updating)).

## Decision gates before implementation

Run a disposable proof-of-capability, not production code, before locking dependencies:

1. Generate the same proto for all three clients and the JVM server.
2. On physical Android and iOS devices plus supported Windows, complete a TLS bidirectional stream, simultaneous sends, cancellation, foreground/background closure, reconnect, and server restart.
3. Exercise deadlines, HTTP/2 proxying, trailers, maximum message sizes, malformed frames, and authentication metadata.
4. Generate non-exportable ES256 keys, enroll public JWKs, sign identical fixed inputs, and verify on the JVM server using RFC/JWA vectors. Confirm the iOS/Android signature adapter converts DER to 64-byte raw `R || S` where necessary.
5. Verify key deletion/reinstall behavior, TPM/Secure Enclave/StrongBox absence, locked-device behavior, clock skew, token expiry, replay rejection, and revoked-device rejection.
6. Record dependency versions and native binary provenance; fail the spike if any target needs exported private-key bytes.

**Selection rule:** choose stack 1 if Windows/JVM meets the product requirement and the preview spike passes. Choose stack 2 only if native Windows is required and its MinGW package, TLS trust behavior, and bidirectional reconnection tests pass. If neither spike passes, keep protobuf and standard gRPC as the protocol contract but use platform-native gRPC clients behind an `expect`/`actual` transport seam rather than inventing a custom wire protocol.
