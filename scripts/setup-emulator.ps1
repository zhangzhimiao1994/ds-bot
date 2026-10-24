#Requires -Version 5.1
<#
.SYNOPSIS
    DS Bot - install the Android emulator + a pixel_6 AVD inside the repo.

.DESCRIPTION
    Downloads the emulator and the android-35 google_apis x86_64 system image
    (about 1.5 GB) and creates an AVD under work\avd so it stays writable by the
    sandboxed shell (the user-profile .android directory is not).

    Requires network + a writable SDK, so run it unconfined when the shell
    sandbox blocks outbound traffic.
#>
[CmdletBinding()]
param([string]$AvdName = 'dsbot', [string]$Api = '35')

$ErrorActionPreference = 'Stop'
$Repo = Split-Path -Parent $PSScriptRoot
$Sdk = Join-Path $Repo 'toolchain\android-sdk'
$Jdk = Join-Path $Repo 'toolchain\jdk21'
$env:JAVA_HOME = $Jdk
$env:ANDROID_HOME = $Sdk
$env:ANDROID_SDK_ROOT = $Sdk
$AvdHome = Join-Path $Repo 'work\avd'
$UserHome = Join-Path $Repo 'work\android-user'
New-Item -ItemType Directory -Force -Path $AvdHome, $UserHome | Out-Null
$env:ANDROID_AVD_HOME = $AvdHome
$env:ANDROID_USER_HOME = $UserHome
$env:PATH = "$Jdk\bin;$Sdk\platform-tools;$Sdk\emulator;$env:PATH"

$Sm = Join-Path $Sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
$AvdMgr = Join-Path $Sdk 'cmdline-tools\latest\bin\avdmanager.bat'
$Image = "system-images;android-$Api;google_apis;x86_64"

Write-Host '=== install emulator + system image ==='
$yes = (1..40 | ForEach-Object { 'y' }) -join "`r`n"
$cmd = '"' + $Sm + '" --sdk_root="' + $Sdk + '" ' + '"emulator" "' + $Image + '"'
$yes | cmd /c "$cmd 2>&1" | ForEach-Object { Write-Host "  $_" }

foreach ($p in 'emulator\emulator.exe', "system-images\android-$Api\google_apis\x86_64\system.img") {
    $full = Join-Path $Sdk $p
    if (-not (Test-Path $full)) { throw "missing after install: $p" }
    Write-Host "  [have] $p"
}

Write-Host '=== create AVD ==='
$ini = Join-Path $AvdHome "$AvdName.ini"
if (Test-Path $ini) { Remove-Item $ini -Force }
$avdDir = Join-Path $AvdHome "$AvdName.avd"
if (Test-Path $avdDir) { Remove-Item $avdDir -Recurse -Force }
$avdCmd = '"' + $AvdMgr + '" create avd -n "' + $AvdName + '" -k "' + $Image + '" -d pixel_6 --force'
$yes | cmd /c "$avdCmd 2>&1" | ForEach-Object { Write-Host "  $_" }
if (-not (Test-Path $avdDir)) { throw "AVD not created at $avdDir" }

# Keep the AVD light and headless-friendly.
$config = Join-Path $avdDir 'config.ini'
if (Test-Path $config) {
    Add-Content -Path $config -Value 'hw.audioInput = no'
    Add-Content -Path $config -Value 'hw.audioOutput = no'
    Add-Content -Path $config -Value 'hw.camera.back = none'
    Add-Content -Path $config -Value 'hw.keyboard = yes'
    Add-Content -Path $config -Value 'disk.dataPartition.size = 4096M'
    Write-Host "  config updated: $config"
}

Write-Host '=== done ==='
Write-Host "ANDROID_AVD_HOME = $AvdHome"
Write-Host "AVD              = $AvdName"
Write-Host "emulator         = $(Join-Path $Sdk 'emulator\emulator.exe')"
