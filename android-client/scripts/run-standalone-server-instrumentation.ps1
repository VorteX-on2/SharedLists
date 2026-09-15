param(
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [int]$Port = 19443
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Resolve-Path "$PSScriptRoot\..\.."
$fixture = Join-Path $env:TEMP "sharedlists-android-instrumentation"
$serverProcess = $null

try {
    & "$repositoryRoot\gradlew.bat" --offline --no-daemon :server:installDist :android-client:assembleDebug :android-client:assembleDebugAndroidTest
    if ($LASTEXITCODE -ne 0) { throw "Android artifacts could not be built." }

    & $Adb install -r "$repositoryRoot\android-client\build\outputs\apk\debug\android-client-debug.apk"
    & $Adb install -r "$repositoryRoot\android-client\build\outputs\apk\androidTest\debug\android-client-debug-androidTest.apk"
    & $Adb shell am instrument -w -e class "dev.sharedlists.android.AndroidKeystoreDeviceEnrollmentTest#writesPublicKeyForStandaloneServerEnrollment" "dev.sharedlists.android.test/androidx.test.runner.AndroidJUnitRunner"
    if ($LASTEXITCODE -ne 0) { throw "Android enrollment key export failed." }

    Remove-Item -LiteralPath $fixture -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path "$fixture\data\authorized-devices", "$fixture\data\tls" | Out-Null
    & $Adb shell run-as dev.sharedlists.android cat files/android-device-public-key.pem |
        Out-File "$fixture\data\authorized-devices\android.pem" -Encoding ascii
    @"
bindAddress=0.0.0.0
authorizedDevicesDirectory=data/authorized-devices
port=$Port
serverIp=10.0.2.2
databaseFile=data/sharedlists.db
tlsCertificateFile=data/tls/server.pem
tlsPrivateKeyFile=data/tls/server-key.pem
"@ | Set-Content "$fixture\sharedlists.properties" -NoNewline

    $classpath = (Resolve-Path "$repositoryRoot\server\build\install\server\lib").Path + "\*"
    $serverProcess = Start-Process java -ArgumentList "-cp", $classpath, "dev.sharedlists.server.MainKt", "--config", "$fixture\sharedlists.properties" -WorkingDirectory $fixture -RedirectStandardOutput "$fixture\server.log" -RedirectStandardError "$fixture\server.err" -PassThru
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    do {
        Start-Sleep -Milliseconds 100
        $serverOutput = if (Test-Path "$fixture\server.log") {
            Get-Content "$fixture\server.log" -Raw
        } else {
            ""
        }
        $ready = $serverOutput -and $serverOutput.Contains("STARTED serviceUri=https://10.0.2.2:$Port")
    } while (!$ready -and [DateTime]::UtcNow -lt $deadline)
    if (!$ready) { throw "Standalone server did not become ready: $(Get-Content "$fixture\server.err" -Raw)" }

    $fingerprintLine = Get-Content "$fixture\server.log" | Where-Object { $_ -like "*tlsFingerprint=*" } | Select-Object -Last 1
    if ($null -eq $fingerprintLine) { throw "Standalone server did not report a TLS fingerprint." }
    $fingerprint = ($fingerprintLine -replace ".*tlsFingerprint=", "").Replace(":", "")
    & $Adb shell am instrument -w -e class dev.sharedlists.android.AndroidFacadeIntegrationTest -e serverHost 10.0.2.2 -e serverPort $Port -e serverFingerprint $fingerprint "dev.sharedlists.android.test/androidx.test.runner.AndroidJUnitRunner"
    if ($LASTEXITCODE -ne 0) { throw "Android production facade integration failed." }
    Stop-Process -Id $serverProcess.Id
    $serverProcess.WaitForExit()
    $serverProcess = Start-Process java -ArgumentList "-Dsharedlists.test.closeAfterDurableApply=true", "-cp", $classpath, "dev.sharedlists.server.MainKt", "--config", "$fixture\sharedlists.properties" -WorkingDirectory $fixture -RedirectStandardOutput "$fixture\lost-ack-server.log" -RedirectStandardError "$fixture\lost-ack-server.err" -PassThru
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    do {
        Start-Sleep -Milliseconds 100
        $serverOutput = if (Test-Path "$fixture\lost-ack-server.log") { Get-Content "$fixture\lost-ack-server.log" -Raw } else { "" }
        $ready = $serverOutput -and $serverOutput.Contains("STARTED serviceUri=https://10.0.2.2:$Port")
    } while (!$ready -and [DateTime]::UtcNow -lt $deadline)
    if (!$ready) { throw "Lost-ack standalone server did not become ready: $(Get-Content "$fixture\lost-ack-server.err" -Raw)" }
    & $Adb shell am instrument -w -e class "dev.sharedlists.android.AndroidFacadeIntegrationTest#reconcilesLostAcknowledgementWithTheSameDurableOperation" -e expectLostAcknowledgement true -e serverHost 10.0.2.2 -e serverPort $Port -e serverFingerprint $fingerprint "dev.sharedlists.android.test/androidx.test.runner.AndroidJUnitRunner"
    if ($LASTEXITCODE -ne 0) { throw "Android lost-acknowledgement integration failed." }

    Stop-Process -Id $serverProcess.Id
    $serverProcess.WaitForExit()
    Remove-Item -LiteralPath "$fixture\data\authorized-devices\android.pem"
    $serverProcess = Start-Process java -ArgumentList "-cp", $classpath, "dev.sharedlists.server.MainKt", "--config", "$fixture\sharedlists.properties" -WorkingDirectory $fixture -RedirectStandardOutput "$fixture\revoked-server.log" -RedirectStandardError "$fixture\revoked-server.err" -PassThru
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    do {
        Start-Sleep -Milliseconds 100
        $serverOutput = if (Test-Path "$fixture\revoked-server.log") {
            Get-Content "$fixture\revoked-server.log" -Raw
        } else {
            ""
        }
        $ready = $serverOutput -and $serverOutput.Contains("STARTED serviceUri=https://10.0.2.2:$Port")
    } while (!$ready -and [DateTime]::UtcNow -lt $deadline)
    if (!$ready) { throw "Revoked standalone server did not become ready: $(Get-Content "$fixture\revoked-server.err" -Raw)" }
    & $Adb shell am instrument -w -e class "dev.sharedlists.android.AndroidFacadeIntegrationTest#showsReadOnlyRecoveryAfterStandaloneServerRevokesEnrollment" -e expectRevoked true -e serverHost 10.0.2.2 -e serverPort $Port -e serverFingerprint $fingerprint "dev.sharedlists.android.test/androidx.test.runner.AndroidJUnitRunner"
    if ($LASTEXITCODE -ne 0) { throw "Android revoked-enrollment recovery integration failed." }
} finally {
    if ($null -ne $serverProcess -and !$serverProcess.HasExited) {
        Stop-Process -Id $serverProcess.Id
    }
}
