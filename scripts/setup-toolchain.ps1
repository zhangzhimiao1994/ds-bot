#Requires -Version 5.1
<#
.SYNOPSIS
    DS Bot - fresh Android toolchain bootstrap.

.DESCRIPTION
    Installs everything needed to build the DS Bot Android app into
    <repo>\toolchain (and the signing keystore into <repo>\android\keystore):

      toolchain\jdk21              Temurin JDK 21 (Adoptium, Windows x64)
      toolchain\android-sdk        cmdline-tools 11076708 (classic sdkmanager)
                                   + platform-tools + platforms;android-35
                                   + build-tools;35.0.0
      toolchain\gradle-8.13        Gradle 8.13 distribution (called directly,
                                   no wrapper download at build time)
      toolchain\gradle-home        Gradle user home (dependency cache)
      android\keystore\dsbot.jks   release/debug signing key (PKCS12)
      android\keystore.properties  signing config read by app/build.gradle.kts

    Idempotent: existing components are skipped, so re-running only downloads
    what is missing. Requires network access (run unconfined when the shell
    sandbox blocks outbound traffic).

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\setup-toolchain.ps1
#>
[CmdletBinding()]
param(
    [switch]$ForceDownload
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$Repo = Split-Path -Parent $PSScriptRoot
$Tc = Join-Path $Repo 'toolchain'
$Dl = Join-Path $Tc 'downloads'
$Sdk = Join-Path $Tc 'android-sdk'
$Jdk = Join-Path $Tc 'jdk21'
$GradleHome = Join-Path $Tc 'gradle-home'
New-Item -ItemType Directory -Force -Path $Tc, $Dl, $GradleHome | Out-Null

function Write-Step($msg) { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }

function Get-Download {
    param([string]$Url, [string]$Dest, [int]$MinBytes = 4096)
    if ((Test-Path $Dest) -and -not $ForceDownload) {
        $len = (Get-Item $Dest).Length
        if ($len -ge $MinBytes) {
            Write-Host ("[skip] {0} ({1:N1} MB)" -f (Split-Path -Leaf $Dest), ($len / 1MB))
            return
        }
    }
    Write-Host ("[get ] {0}" -f $Url)
    $part = "$Dest.part"
    if (Test-Path $part) { Remove-Item $part -Force }
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    Invoke-WebRequest -Uri $Url -OutFile $part -UseBasicParsing -MaximumRedirection 10 -TimeoutSec 900
    Move-Item -Force $part $Dest
    $sw.Stop()
    Write-Host ("[ok  ] {0} ({1:N1} MB in {2:N0}s)" -f (Split-Path -Leaf $Dest), ((Get-Item $Dest).Length / 1MB), $sw.Elapsed.TotalSeconds)
}

function Expand-ZipInto {
    param([string]$Zip, [string]$TempDir)
    if (Test-Path $TempDir) { Remove-Item $TempDir -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $TempDir | Out-Null
    Expand-Archive -LiteralPath $Zip -DestinationPath $TempDir -Force
}

# ---------------------------------------------------------------------------
# 1. JDK 21 (Temurin)
# ---------------------------------------------------------------------------
Write-Step 'JDK 21 (Temurin)'
if (-not (Test-Path (Join-Path $Jdk 'bin\java.exe'))) {
    $jdkZip = Join-Path $Dl 'temurin21-jdk.zip'
    Get-Download -Url 'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse' -Dest $jdkZip -MinBytes 50MB
    $tmp = Join-Path $Tc 'jdk-extract'
    Expand-ZipInto -Zip $jdkZip -TempDir $tmp
    $inner = Get-ChildItem $tmp -Directory | Select-Object -First 1
    if (-not $inner) { throw 'JDK archive did not contain a directory' }
    if (Test-Path $Jdk) { Remove-Item $Jdk -Recurse -Force }
    Move-Item -LiteralPath $inner.FullName -Destination $Jdk
    Remove-Item $tmp -Recurse -Force
}
$env:JAVA_HOME = $Jdk
$env:PATH = "$Jdk\bin;$env:PATH"
cmd /c "`"$Jdk\bin\java.exe`" -version 2>&1" | ForEach-Object { Write-Host "  $_" }

# ---------------------------------------------------------------------------
# 2. Android SDK
# ---------------------------------------------------------------------------
Write-Step 'Android SDK command-line tools'
$Cmdtools = Join-Path $Sdk 'cmdline-tools\latest'
if (-not (Test-Path (Join-Path $Cmdtools 'bin\sdkmanager.bat'))) {
    $ctZip = Join-Path $Dl 'commandlinetools-win-11076708_latest.zip'
    # Classic 11076708: its sdkmanager splits package names correctly. Newer
    # cmdline-tools wrap `sdkmanager` in a shim that breaks "platforms;android-35".
    Get-Download -Url 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' -Dest $ctZip -MinBytes 20MB
    $tmp = Join-Path $Tc 'ct-extract'
    Expand-ZipInto -Zip $ctZip -TempDir $tmp
    $inner = Join-Path $tmp 'cmdline-tools'
    if (-not (Test-Path $inner)) { throw 'cmdline-tools archive layout unexpected' }
    New-Item -ItemType Directory -Force -Path (Join-Path $Sdk 'cmdline-tools') | Out-Null
    if (Test-Path $Cmdtools) { Remove-Item $Cmdtools -Recurse -Force }
    Move-Item -LiteralPath $inner -Destination $Cmdtools
    Remove-Item $tmp -Recurse -Force
}

Write-Step 'Android SDK licenses'
$LicenseDir = Join-Path $Sdk 'licenses'
New-Item -ItemType Directory -Force -Path $LicenseDir | Out-Null
# Pre-seed the well-known license hashes (equivalent to accepting them once in
# sdkmanager); the stdin "y" loop afterwards covers any new license.
$licenses = @{
    'android-sdk-license'         = @('24333f8a63b6825ea9c5514f83c2829b004d1fee', 'd56f5187479451eabf01fb78af6dfcb131a6481e', '8933bad161af4178b1185d1a37fbf41ea5269c55') -join "`r`n"
    'android-sdk-preview-license' = '84831b9409646a918e30573bab4c9c91346d8abd'
    'android-sdk-arm-dbt-license' = '859f317696f67ef3d7f30a50a5560e7834b43903'
}
foreach ($name in $licenses.Keys) {
    Set-Content -LiteralPath (Join-Path $LicenseDir $name) -Value $licenses[$name] -Encoding ASCII
}

Write-Step 'Android SDK packages'
$SdkManager = Join-Path $Cmdtools 'bin\sdkmanager.bat'
$sdkArgs = @("--sdk_root=$Sdk", 'platform-tools', 'platforms;android-35', 'build-tools;35.0.0')
Write-Host ("[run] sdkmanager {0}" -f ($sdkArgs -join ' '))
$yes = (1..40 | ForEach-Object { 'y' }) -join "`r`n"
$sdkCmd = '"' + $SdkManager + '" ' + ($sdkArgs -join ' ')
$yes | cmd /c "$sdkCmd 2>&1" | ForEach-Object { Write-Host "  $_" }
if ($LASTEXITCODE -ne 0 -and -not (Test-Path (Join-Path $Sdk 'platforms\android-35\android.jar'))) {
    throw "sdkmanager failed (exit $LASTEXITCODE)"
}
foreach ($p in 'platforms\android-35\android.jar', 'build-tools\35.0.0\aapt2.exe', 'platform-tools\adb.exe') {
    $full = Join-Path $Sdk $p
    if (-not (Test-Path $full)) { throw "missing SDK component: $p" }
    Write-Host "  [have] $p"
}

# ---------------------------------------------------------------------------
# 3. Gradle 8.13
# ---------------------------------------------------------------------------
Write-Step 'Gradle 8.13'
$GradleDir = Join-Path $Tc 'gradle-8.13'
if (-not (Test-Path (Join-Path $GradleDir 'bin\gradle.bat'))) {
    $gzZip = Join-Path $Dl 'gradle-8.13-bin.zip'
    Get-Download -Url 'https://services.gradle.org/distributions/gradle-8.13-bin.zip' -Dest $gzZip -MinBytes 50MB
    $tmp = Join-Path $Tc 'gradle-extract'
    Expand-ZipInto -Zip $gzZip -TempDir $tmp
    $inner = Get-ChildItem $tmp -Directory | Where-Object { $_.Name -like 'gradle-*' } | Select-Object -First 1
    if (-not $inner) { throw 'Gradle archive layout unexpected' }
    if (Test-Path $GradleDir) { Remove-Item $GradleDir -Recurse -Force }
    Move-Item -LiteralPath $inner.FullName -Destination $GradleDir
    Remove-Item $tmp -Recurse -Force
}
cmd /c "`"$GradleDir\bin\gradle.bat`" --version 2>&1" | Select-Object -First 6 | ForEach-Object { Write-Host "  $_" }

# ---------------------------------------------------------------------------
# 4. Keystore (same key signs debug + release so builds are verifiable)
# ---------------------------------------------------------------------------
Write-Step 'Signing keystore'
$KeyDir = Join-Path $Repo 'android\keystore'
$KeyStore = Join-Path $KeyDir 'dsbot.jks'
New-Item -ItemType Directory -Force -Path $KeyDir | Out-Null
if (-not (Test-Path $KeyStore)) {
    $kt = Join-Path $Jdk 'bin\keytool.exe'
    $ktCmd = '"' + $kt + '" -genkeypair -keystore "' + $KeyStore + '" -storetype PKCS12 -alias dsbot -keyalg RSA -keysize 2048 -validity 10000 -storepass dsbot-dev -keypass dsbot-dev -dname "CN=DS Bot, OU=Mobile, O=DS Bot Project, L=Local, ST=Local, C=CN"'
    cmd /c "$ktCmd 2>&1" | ForEach-Object { Write-Host "  $_" }
}
$props = @(
    'storeFile=keystore/dsbot.jks',
    'storePassword=dsbot-dev',
    'keyAlias=dsbot',
    'keyPassword=dsbot-dev',
    'storeType=PKCS12'
) -join "`r`n"
Set-Content -LiteralPath (Join-Path $Repo 'android\keystore.properties') -Value $props -Encoding ASCII

Write-Step 'Done'
Write-Host ("JAVA_HOME     : {0}" -f $Jdk)
Write-Host ("ANDROID_HOME  : {0}" -f $Sdk)
Write-Host ("GRADLE        : {0}" -f (Join-Path $GradleDir 'bin\gradle.bat'))
Write-Host ("GRADLE_HOME   : {0}" -f $GradleHome)
Write-Host ("KEYSTORE      : {0}" -f $KeyStore)
