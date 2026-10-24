#Requires -Version 5.1
<#
.SYNOPSIS
    DS Bot - sandbox-safe Android build.

.DESCRIPTION
    The DSH shell sandbox runs at low integrity, so Gradle cannot write inside
    the (medium integrity) android/ source tree. This script therefore:

      1. mirrors android/ (sources only, no build outputs) into
         work/build-<Tag>/ with robocopy /MIR;
      2. points GRADLE_USER_HOME at work/gradle-home (a writable directory
         created by this script), ANDROID_USER_HOME at work/android-user and
         writes local.properties with the real SDK path;
      3. runs the requested Gradle tasks (assembleDebug / assembleRelease /
         testDebugUnitTest) with the repo-local JDK 21 and Gradle 8.13;
      4. verifies the APK signature with apksigner and prints badging;
      5. copies the APKs to release/ (unless -NoReleaseCopy).

    Idempotent and incremental: the same -Tag reuses build outputs. Use
    -Offline after the dependency cache is warm (first build needs network).

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\build.ps1
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\build.ps1 -Variant all -Test
#>
[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9._-]+$')][string]$Tag = 'current',
    [ValidateSet('debug', 'release', 'all')][string]$Variant = 'debug',
    [switch]$Test,
    [switch]$Offline,
    [switch]$SkipVerify,
    [switch]$NoReleaseCopy,
    [switch]$Fresh
)

$ErrorActionPreference = 'Stop'
$Repo = Split-Path -Parent $PSScriptRoot
$Android = Join-Path $Repo 'android'
$Tc = Join-Path $Repo 'toolchain'
$Jdk = Join-Path $Tc 'jdk21'
$Sdk = Join-Path $Tc 'android-sdk'
$Gradle = Join-Path $Tc 'gradle-8.13\bin\gradle.bat'
$BuildRoot = Join-Path $Repo "work\build-$Tag"
$GradleHome = Join-Path $Repo 'work\gradle-home'
$AndroidUser = Join-Path $Repo 'work\android-user'
$Release = Join-Path $Repo 'release'
$LogDir = $BuildRoot

function Write-Step($m) { Write-Host "`n=== $m ===" -ForegroundColor Cyan }

foreach ($p in $Jdk, $Sdk, $Gradle) {
    if (-not (Test-Path $p)) { throw "toolchain missing: $p (run scripts\setup-toolchain.ps1)" }
}
New-Item -ItemType Directory -Force -Path $GradleHome, $AndroidUser, $Release, $LogDir | Out-Null

# ---------------------------------------------------------------------------
# 1. Mirror sources into the writable build tree
# ---------------------------------------------------------------------------
Write-Step "Mirror android/ -> $BuildRoot"
if ($Fresh -and (Test-Path $BuildRoot)) { Remove-Item $BuildRoot -Recurse -Force }
if (-not (Test-Path $BuildRoot)) { New-Item -ItemType Directory -Force -Path $BuildRoot | Out-Null }
$robocopyArgs = @($Android, $BuildRoot, '/MIR', '/NFL', '/NDL', '/NJH', '/NJS', '/NC', '/NS', '/NP',
    '/XD', 'build', '.gradle', '.kotlin', 'keystore')
& robocopy.exe @robocopyArgs | Out-Null
$roboExit = $LASTEXITCODE
if ($roboExit -ge 8) { throw "robocopy failed with exit code $roboExit" }# The keystore lives in the source tree; copy it separately (not mirrored).
$KeySrc = Join-Path $Android 'keystore'
if (Test-Path $KeySrc) {
    $KeyDst = Join-Path $BuildRoot 'keystore'
    if (-not (Test-Path $KeyDst)) { New-Item -ItemType Directory -Force -Path $KeyDst | Out-Null }
    Copy-Item (Join-Path $KeySrc '*') $KeyDst -Recurse -Force
}
$localProps = "sdk.dir=" + ($Sdk -replace '\\', '/')
Set-Content -LiteralPath (Join-Path $BuildRoot 'local.properties') -Value $localProps -Encoding ASCII

# ---------------------------------------------------------------------------
# 2. Environment
# ---------------------------------------------------------------------------
$env:JAVA_HOME = $Jdk
$env:ANDROID_HOME = $Sdk
$env:ANDROID_SDK_ROOT = $Sdk
$env:ANDROID_USER_HOME = $AndroidUser
$env:GRADLE_USER_HOME = $GradleHome
$env:PATH = "$Jdk\bin;$Sdk\platform-tools;$env:PATH"
# The Windows user-profile Temp directory is not writable for this project's
# sandboxed / cross-integrity builds (AGP mergeJavaRes crashed there), so every
# temp dir of the build stays inside the repo.
$TmpDir = Join-Path $Repo 'work\tmp'
New-Item -ItemType Directory -Force -Path $TmpDir | Out-Null
$env:TMP = $TmpDir
$env:TEMP = $TmpDir
$env:TMPDIR = $TmpDir

$tasks = @()
switch ($Variant) {
    'debug' { $tasks += 'assembleDebug' }
    'release' { $tasks += 'assembleRelease' }
    'all' { $tasks += 'assembleDebug'; $tasks += 'assembleRelease' }
}
if ($Test) { $tasks += 'testDebugUnitTest' }

$gradleArgs = @('--console=plain', '--no-daemon', "-Pandroid.injected.signing.store.file=") | Where-Object { $_ -ne '-Pandroid.injected.signing.store.file=' }
$gradleArgs = @('--console=plain', '--no-daemon') + $tasks
if ($Offline) { $gradleArgs += '--offline' }

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$logFile = Join-Path $LogDir "build-$stamp.log"
Write-Step "Gradle: $($tasks -join ' ')"
$gradleCmd = '"' + $Gradle + '" ' + ($gradleArgs -join ' ')
Push-Location $BuildRoot
try {
    # cmd-level redirection keeps every Gradle line (including Kotlin/AGP error
    # blocks) inside the log; PowerShell's 2>&1 pipeline drops native stderr.
    cmd /c "$gradleCmd > `"$logFile`" 2>&1"
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}
Write-Host "log: $logFile"
Get-Content -LiteralPath $logFile -Tail 15
if ($code -ne 0) { throw "Gradle failed with exit code $code (see $logFile)" }

# ---------------------------------------------------------------------------
# 3. Collect + verify APKs
# ---------------------------------------------------------------------------
Write-Step 'Collect APKs'
$apkDir = Join-Path $BuildRoot 'app\build\outputs\apk'
$apks = @()
if (Test-Path $apkDir) {
    $apks = Get-ChildItem $apkDir -Recurse -Filter *.apk | Where-Object { $_.Name -notlike '*-unsigned.apk' }
}
if (-not $apks) { throw "no APK produced under $apkDir" }

$bt = Join-Path $Sdk 'build-tools\35.0.0'
$apksigner = Join-Path $bt 'apksigner.bat'
$aapt2 = Join-Path $bt 'aapt2.exe'
$versionName = '0.1.0'
$results = @()
foreach ($apk in $apks) {
    $variantName = if ($apk.FullName -match 'release') { 'release' } elseif ($apk.FullName -match 'debug') { 'debug' } else { 'other' }
    $target = Join-Path $Release ("dsbot-{0}-{1}.apk" -f $versionName, $variantName)
    Copy-Item $apk.FullName $target -Force
    $sha = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLower()
    $verified = $false
    if (-not $SkipVerify -and (Test-Path $apksigner)) {
        & $apksigner verify --print-certs $target 2>&1 | Out-Null
        $verified = ($LASTEXITCODE -eq 0)
    }
    $badging = ''
    if (Test-Path $aapt2) {
        $badging = (& $aapt2 dump badging $target 2>&1 | Select-Object -First 1) -as [string]
    }
    $results += [pscustomobject]@{
        File = $target; Size = (Get-Item $target).Length; Sha256 = $sha;
        Verified = $verified; Badging = $badging
    }
    Write-Host ("  {0}  {1:N1} MB  sha256={2}  verified={3}" -f (Split-Path -Leaf $target), ($results[-1].Size / 1MB), $sha.Substring(0, 16), $verified)
}

# ---------------------------------------------------------------------------
# 4. Unit tests summary
# ---------------------------------------------------------------------------
if ($Test) {
    Write-Step 'Unit tests'
    $xml = Join-Path $BuildRoot 'app\build\test-results\testDebugUnitTest'
    if (Test-Path $xml) {
        $files = Get-ChildItem $xml -Filter 'TEST-*.xml'
        $total = 0; $fail = 0; $err = 0; $skip = 0
        foreach ($f in $files) {
            [xml]$doc = Get-Content $f.FullName
            $s = $doc.testsuite
            $total += [int]$s.tests; $fail += [int]$s.failures; $err += [int]$s.errors; $skip += [int]$s.skipped
        }
        Write-Host ("  total={0} failed={1} errors={2} skipped={3}" -f $total, $fail, $err, $skip)
    }
}

Write-Step 'Done'
if (-not $NoReleaseCopy) { Write-Host "APKs in $Release" }
