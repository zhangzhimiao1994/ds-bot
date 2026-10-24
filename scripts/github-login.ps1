#Requires -Version 5.1
<#
.SYNOPSIS
    One-time GitHub login for this machine (device flow -> GitHub CLI -> persistent git credential).

.DESCRIPTION
    1. Uses the official GitHub CLI OAuth device flow (no PAT pasted into chat/files).
    2. Prints the one-time code for the user and polls until authorised.
    3. Stores the token through `gh auth login --with-token` (gh config + OS keyring).
    4. Runs `gh auth setup-git`, which (re)configures the persistent git credential
       helper for github.com - this machine's old helper pointed at a deleted gh.exe.
    5. Creates zhangzhimiao1994/ds-bot if needed, pushes main and publishes v0.1.0
       with the two APK assets.

    The access token is only kept in memory here and handed to gh over stdin; it is
    never printed or written to disk by this script.
#>
[CmdletBinding()]
param(
    [string]$Owner = 'zhangzhimiao1994',
    [string]$Repo = 'ds-bot',
    [string]$ClientId = '178c6fc778ccc68e1d6a',   # official GitHub CLI OAuth app (public client id)
    [string]$Scopes = 'repo read:org workflow gist',
    [switch]$SkipRelease
)
$ErrorActionPreference = 'Stop'
$RepoRoot = Split-Path -Parent $PSScriptRoot
$Gh = Join-Path $env:LOCALAPPDATA 'Programs\GitHub CLI\bin\gh.exe'
if (-not (Test-Path $Gh)) { throw "gh CLI not installed at $Gh (run scripts/install-gh.ps1 first)" }

Write-Host '=== GitHub device login ==='
$Device = Invoke-RestMethod -Method Post -Uri 'https://github.com/login/device/code' `
    -Body @{ client_id = $ClientId; scope = $Scopes } -Headers @{ Accept = 'application/json' } -TimeoutSec 60
Write-Host "VERIFY_URL=$($Device.verification_uri)"
Write-Host "USER_CODE=$($Device.user_code)"
Write-Host 'STATUS=WAITING_FOR_USER'
Write-Host "请在浏览器打开 $($Device.verification_uri) 并输入代码：$($Device.user_code)"

$interval = [Math]::Max(5, [int]$Device.interval)
$deadline = (Get-Date).AddSeconds([int]$Device.expires_in - 30)
$token = $null
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds $interval
    try {
        $r = Invoke-RestMethod -Method Post -Uri 'https://github.com/login/oauth/access_token' `
            -Body @{ client_id = $ClientId; device_code = $Device.device_code; grant_type = 'urn:ietf:params:oauth:grant-type:device_code' } `
            -Headers @{ Accept = 'application/json' } -TimeoutSec 60
    } catch { continue }
    if ($r.access_token) { $token = $r.access_token; break }
    switch ($r.error) {
        'authorization_pending' { Write-Host 'STATUS=WAITING_FOR_USER'; continue }
        'slow_down' { $interval += 5; Write-Host 'STATUS=WAITING_FOR_USER'; continue }
        default { if ($r.error) { Write-Host "STATUS=ERROR $($r.error)"; exit 2 } }
    }
}
if (-not $token) { Write-Host 'STATUS=EXPIRED'; exit 3 }
Write-Host 'STATUS=AUTHORIZED'
$token | & $Gh auth login --hostname github.com --git-protocol https --with-token 2>&1 | ForEach-Object { "  $_" }
if ($LASTEXITCODE -ne 0) { throw 'gh auth login failed' }
& $Gh auth setup-git 2>&1 | ForEach-Object { "  $_" }
Write-Host '=== gh auth status ==='
& $Gh auth status 2>&1 | ForEach-Object { "  $_" }

Set-Location $RepoRoot
Write-Host '=== ensure repository exists ==='
$repoSlug = "$Owner/$Repo"
& $Gh repo view $repoSlug --json name -q '.name' 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
    $desc = 'Phone-side bot team for DeepSeek Harness: native Android (Kotlin/Compose) client + security-scoped host bridge'
    & $Gh repo create $repoSlug --public --description $desc 2>&1 | ForEach-Object { "  $_" }
    if ($LASTEXITCODE -ne 0) { throw 'gh repo create failed' }
} else {
    Write-Host '  repository already exists, reusing'
}

Write-Host '=== push main ==='
$hasOrigin = (& git remote 2>$null) -contains 'origin'
if ($hasOrigin) { & git remote set-url origin "https://github.com/$repoSlug.git" } else { & git remote add origin "https://github.com/$repoSlug.git" }
& git push -u origin main 2>&1 | ForEach-Object { "  $_" }
if ($LASTEXITCODE -ne 0) { throw 'git push failed' }
Write-Host "PUSHED=$(git rev-parse HEAD)"

if (-not $SkipRelease) {
    Write-Host '=== publish release v0.1.0 ==='
    $assets = @()
    foreach ($apk in 'release\dsbot-0.1.0-release.apk', 'release\dsbot-0.1.0-debug.apk') {
        if (Test-Path (Join-Path $RepoRoot $apk)) { $assets += (Join-Path $RepoRoot $apk) }
    }
    $notes = @'
首个可用版本。

- Android 客户端（Kotlin + Compose）：Bot 团队、1:1 流式聊天、群聊（管理员调度 + @ 接力）、审批卡（完整命令 + 指纹 + 危险二次确认）、Keystore 存 token、深浅色。
- 主机桥接（Node，零依赖）：设备 token、方法白名单、WS 端点白名单（阻止 terminal/attach）、cookie 注入、审计日志。
- 安全设计对应审批 RCE / 配对即管理员 / 密钥外泄三类风险，见 docs/SECURITY.md。
- 验证：Android 单测 50/50，桥接测试 44/44 + 真实 DSH 冒烟 7/7，协议级 E2E 通过。详见 release/BUILD-INFO.txt。

安装与配对：docs/INSTALL.md
'@
    & $Gh release view v0.1.0 --repo $repoSlug 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Write-Host '  release exists, uploading assets only'
    } else {
        & $Gh release create v0.1.0 --repo $repoSlug --title 'DS Bot v0.1.0 - 手机上的 DSH Bot 团队' --notes $notes @assets 2>&1 | ForEach-Object { "  $_" }
        if ($LASTEXITCODE -ne 0) { Write-Host '  release create failed (non-fatal)' }
    }
}
Write-Host '=== done ==='
Write-Host "REPO_URL=https://github.com/$repoSlug"
