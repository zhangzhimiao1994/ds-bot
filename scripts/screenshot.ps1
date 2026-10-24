# DS Bot — 模拟器截图与端到端验收脚本
#
# 前置：模拟器已在跑（adb devices 有 emulator-5554）、桥接已启动（8788）、
#       配对串 token 已写入环境变量 DSBOT_TOKEN 或参数传入。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\screenshot.ps1 -Apk release\dsbot-0.1.0-debug.apk -Token <token> [-Url http://10.0.2.2:8788]
param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [Parameter(Mandatory = $true)][string]$Token,
    [string]$Url = 'http://10.0.2.2:8788',
    [string]$OutDir = 'work\screenshots',
    [string]$Avd = 'dsbot'
)
$ErrorActionPreference = 'Stop'
$Repo = Split-Path -Parent $PSScriptRoot
$Sdk = Join-Path $Repo 'toolchain\android-sdk'
$Adb = Join-Path $Sdk 'platform-tools\adb.exe'
$env:ANDROID_AVD_HOME = Join-Path $Repo 'work\avd'
$env:ANDROID_USER_HOME = Join-Path $Repo 'work\android-user'
$env:ANDROID_SDK_ROOT = $Sdk
New-Item -ItemType Directory -Force -Path (Join-Path $Repo $OutDir) | Out-Null

function Adb { param([string[]]$Args) & $Adb @Args 2>&1 | Out-String }

# --- wait for device -------------------------------------------------------
Write-Host '=== wait for emulator ==='
for ($i = 0; $i -lt 90; $i++) {
    $devices = (& $Adb devices 2>&1 | Out-String)
    if ($devices -match 'emulator-\d+\s+device') { break }
    Start-Sleep -Seconds 2
}
if (-not ((& $Adb devices 2>&1 | Out-String) -match 'emulator-\d+\s+device')) { throw 'no emulator device' }
for ($i = 0; $i -lt 90; $i++) {
    $boot = (& $Adb shell getprop sys.boot_completed 2>&1 | Out-String).Trim()
    if ($boot -eq '1') { break }
    Start-Sleep -Seconds 2
}
Write-Host '=== install APK ==='
& $Adb install -r (Resolve-Path $Apk) 2>&1 | ForEach-Object { Write-Host "  $_" }

# --- deep-link pairing -----------------------------------------------------
$link = "dsbot://pair?url=$Url&token=$Token"
Write-Host "=== launch with pairing link ==="
& $Adb shell am force-stop com.dsbot.mobile 2>&1 | Out-Null
& $Adb shell am start -W -a android.intent.action.VIEW -d "'$link'" -n com.dsbot.mobile/.MainActivity 2>&1 | ForEach-Object { Write-Host "  $_" }
Start-Sleep -Seconds 8

function Shot([string]$name) {
    $path = Join-Path $Repo "$OutDir\$name.png"
    $bytes = & $Adb exec-out screencap -p
    [System.IO.File]::WriteAllBytes($path, $bytes)
    Write-Host "  [shot] $path ($([math]::Round((Get-Item $path).Length/1KB,1)) KB)"
}

# UI 树 + 按文字/描述点击
function DumpUi {
    & $Adb shell uiautomator dump /sdcard/ui.xml 2>&1 | Out-Null
    $xml = (& $Adb shell cat /sdcard/ui.xml 2>&1 | Out-String)
    return $xml
}
function TapText([string]$text) {
    $xml = DumpUi
    $esc = [regex]::Escape($text)
    $m = [regex]::Match($xml, "text=`"$esc`"[^>]*bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`"")
    if (-not $m.Success) { $m = [regex]::Match($xml, "content-desc=`"$esc`"[^>]*bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`"") }
    if (-not $m.Success) { Write-Host "  [warn] text not found: $text"; return $false }
    $x = ([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2
    $y = ([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2
    & $Adb shell input tap ([int]$x) ([int]$y) 2>&1 | Out-Null
    Start-Sleep -Milliseconds 800
    return $true
}
function TypeText([string]$text) {
    $escaped = $text -replace ' ', '%s'
    & $Adb shell input text $escaped 2>&1 | Out-Null
    Start-Sleep -Milliseconds 400
}

Write-Host '=== capture ==='
Shot '01-team'
if (TapText '创建 Bot') {
    Start-Sleep -Milliseconds 1200
    Shot '02-create-bot'
    # name field: first EditText
    $xml = DumpUi
    $m = [regex]::Match($xml, 'class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if ($m.Success) {
        $x = ([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2
        $y = ([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2
        & $Adb shell input tap ([int]$x) ([int]$y) 2>&1 | Out-Null
        Start-Sleep -Milliseconds 500
        TypeText 'Coder'
        Start-Sleep -Milliseconds 500
        Shot '03-create-bot-typed'
        if (TapText '创建') {
            Start-Sleep -Seconds 2
            Shot '04-team-with-bot'
            if (TapText 'Coder') {
                Start-Sleep -Seconds 2
                Shot '05-bot-chat'
                TypeText 'hello'
                Start-Sleep -Milliseconds 500
                Shot '06-chat-typed'
            }
        }
    }
}
# Settings tab
& $Adb shell input keyevent 4 2>&1 | Out-Null
Start-Sleep -Milliseconds 500
if (TapText '设置') { Start-Sleep -Seconds 1; Shot '07-settings' }
Write-Host '=== done ==='
