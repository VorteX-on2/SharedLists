# Shared Lists

Shared Lists synchronizes generic lists for one small trusted group across Android, iOS, and Windows.

## Development

The project contains the Android client, standalone JVM server, shared client, protocol, and Windows client. On Windows, run the complete host-supported verification:

```powershell
.\gradlew.bat --no-daemon hostCheck
```

`hostCheck` runs shared JVM tests, compiles the JVM modules, cross-compiles iOS simulator sources, and assembles the Android debug application.

The full product architecture, security contract, synchronization lifecycle, and server operations model are recorded in `CONTEXT.md`, `docs/adr`, and `docs/operations`.

## First Android and server run

Use this manual flow to run the standalone JVM server and an Android client installed directly from Android Studio. It requires:

- Java 21 to build and run the server.
- Android Studio and either an Android device or an emulator.
- A server IP address reachable from that client, with any host and network firewalls permitting TCP port 8443.

### Build and install the server

From the repository root, build the supported runnable JAR:

```powershell
.\gradlew.bat --offline --no-daemon :server:jar
```

Create an installation directory, including the required empty allowlist directory, and copy the JAR:

```powershell
$install = "$HOME\sharedlists-server"
New-Item -ItemType Directory -Force -Path "$install\data\authorized-devices"
Copy-Item server\build\libs\sharedlists-server.jar "$install\sharedlists-server.jar"
```

Create `$install\sharedlists.properties` with these exact settings, replacing `192.168.1.50` with the private/LAN IP that Android clients will actually use to reach the server:

```properties
bindAddress=0.0.0.0
port=8443
serverIp=192.168.1.50
databaseFile=data/sharedlists.db
authorizedDevicesDirectory=data/authorized-devices
tlsCertificateFile=data/tls/server.pem
tlsPrivateKeyFile=data/tls/server-key.pem
```

`serverIp` must be a literal address clients can reach. It is the certificate's IP subject alternative name and the JWT audience (`https://<serverIp>:<port>`), not merely an address on which the server listens. The server resolves relative paths from the configuration file's directory and rejects missing, duplicate, unknown, or invalid settings.

Start it from the installation directory:

```powershell
Set-Location $install
java -jar sharedlists-server.jar --config sharedlists.properties
```

On this first start, the server generates `data\tls\server.pem` and `data\tls\server-key.pem`, then prints `STARTED ... tlsFingerprint=<SHA-256 fingerprint>`. Copy that fingerprint for Android setup. The empty `data\authorized-devices` directory is valid, but the server rejects every device until a public key is enrolled; that rejection is expected and does not create an enrollment automatically.

### Enroll the Android device

1. Open the repository in Android Studio and run the `android-client` app on the selected device or emulator.
2. In the initial form, enter the server address in **Server IP address**, `8443` in **Port**, and the printed value in **SHA-256 server fingerprint**. Tap **Save server configuration**. The client accepts the fingerprint with or without colons and stores it as uppercase SHA-256.
3. Tap **Create device key**, then **Export public key**. Share the exported text with the server administrator. It is a P-256 public SubjectPublicKeyInfo PEM:

   ```text
   -----BEGIN PUBLIC KEY-----
   ...
   -----END PUBLIC KEY-----
   ```

   Android supplies a name such as `sharedlists-Pixel-8-<fingerprint-prefix>.pem`. Save that exact PEM as a regular file in `$install\data\authorized-devices`; `.pem` is conventional but the server has no required allowlist extension. Do not put a certificate, private key, or more than one PEM object in that directory.
4. Stop the server cleanly with Ctrl+C and start it again using the same `java -jar` command. The server reads the allowlist only at startup, so this controlled restart activates the device.

Never copy or export the Android private key: it remains in Android Keystore. Adding, replacing, or revoking an allowlist entry likewise requires a controlled server restart.

### Addressing an emulator or physical device

For a physical device, configure both `serverIp` and **Server IP address** with the server host's reachable LAN IP (for example, `192.168.1.50`) and ensure the device can reach it through Wi-Fi and the host firewall.

For the standard Android emulator connecting to a server running on the development host, use `10.0.2.2` for both `serverIp` and **Server IP address**. Set it before the first server start so the generated certificate includes that address in its SAN. If the server was already started with another `serverIp`, follow the [server identity replacement procedure](docs/operations/mvp-server.md#server-identity-replacement) to generate a certificate for `10.0.2.2`, then update the fingerprint in **Change server configuration**.

### Confirm synchronization

After the restart and enrollment, the app transitions through **Connecting…** and **Synchronizing…** to **Synchronized**. Editing controls are enabled only in that live state. Tap **Create shared list**, enter a name in **New shared list**, and save it. Open the new list, tap **Add item**, enter an item, and save it; the list and item remaining visible confirms the basic create/list flow.

If the app stays disconnected, first confirm the configured address and port are reachable from the device or emulator, then verify the fingerprint against the server's current startup output. If the server reports an unenrolled device, confirm the exported public-key PEM is in the configured allowlist directory and restart the server. A changed certificate or `serverIp` requires replacing the client fingerprint; an empty or changed allowlist requires a restart. Back up and restore only a stopped, complete installation (JAR, configuration, database, TLS identity, and allowlist) as described in the [server operations guide](docs/operations/mvp-server.md#backup-restore-upgrade-and-rollback).

## Windows enrollment and synchronization

The Windows/JVM application persists a server address, port, and SHA-256 certificate fingerprint. It creates one non-exportable P-256 key in the Windows CNG Software Key Storage Provider, retains only its certificate-store thumbprint, and signs through SunMSCAPI without exposing private-key bytes. After confirmation, the native save dialog exports the public SPKI PEM for the server administrator to copy into the allowlist and activate by restarting the server.

Until that PEM is allowed, the client remains read-only and reports an unenrolled device; after allowlisting, Connect uses the existing challenge-bound JWT facade. Reset device setup is confirmed before deleting the CNG certificate and key; it retains cached canonical state.
