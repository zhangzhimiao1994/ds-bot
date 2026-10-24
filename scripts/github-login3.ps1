#Requires -Version 5.1
<#
.SYNOPSIS
    GitHub device login + repo bootstrap (v3, robust).

.DESCRIPTION
    One device-code authorization does everything:
      1. device flow -> access token (memory only; never printed)
      2. verify token via REST API
      3. try to persist it (gh CLI config, then GCM via a temp stdin file)
      4. create zhangzhimiao1994/ds-bot, push main (gh or extraheader fallback)
      5. publish v0.1.0 and upload both APK assets
    Every persistence step is best-effort: the publish step does not depend on it.
#>
[CmdletBinding()]
param(
    [string]$Owner = 'zhangzhimiao1994',
    [string]$Repo = 'ds-bot',
    [string]$ClientId = '178c6fc778ccc68e1d6a',
    [string]$Scopes = 'repo read:org workflow gist',
    [switch]$SkipRelease
)
$ErrorActionPreference = 'Stop'
$RepoRoot = Split-Path -Parent $PSScriptRoot
$Gh = Join-Path $env:LOCALAPPDATA 'Programs\GitHub CLI\bin\gh.exe'
$Work = Join-Path $RepoRoot 'work'
New-Item -ItemType Directory -Force -Path $Work | Out-Null
Set-Location $RepoRoot

Write-Host '=== GitHub device login (v3) ==='
$Device = Invoke-RestMethod -Method Post -Uri 'https://github.com/login/device/code' `
    -Body @{ client_id = $ClientId; scope = $Scopes } -Headers @{ Accept = 'application/json' } -TimeoutSec 60
Write-Host "VERIFY_URL=$($Device.verification_uri)"
Write-Host "USER_CODE=$($Device.user_code)"
Write-Host 'STATUS=WAITING_FOR_USER'

$interval = [Math]::Max(5, [int]$Device.interval)
$deadline = (Get-Date).AddSeconds([int]$Device.expires_in - 20)
$token = $null
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds $interval
    try {
        $r = Invoke-RestMethod -Method Post -Uri 'https://github.com/login/oauth/access_token' `
            -Body @{ client_id = $ClientId; device_code = $Device.device_code; grant_type = 'urn:ietf:params:oauth:grant-type:device_code' } `
            -Headers @{ Accept = 'application/json' } -TimeoutSec 60
    } catch { continue }
    if ($r.access_token) { $token = ([string]$r.access_token).Trim(); break }
    if ($r.error -eq 'slow_down') { $interval += 5; continue }
    if ($r.error -and $r.error -ne 'authorization_pending') { Write-Host "STATUS=ERROR $($r.error)"; exit 2 }
    Write-Host 'STATUS=WAITING_FOR_USER'
}
if (-not $token) { Write-Host 'STATUS=EXPIRED'; exit 3 }
Write-Host "STATUS=AUTHORIZED tokenLen=$($token.Length)"

$h = @{ Authorization = "Bearer $token"; 'User-Agent' = 'dsbot-setup'; Accept = 'application/vnd.github+json' }
$me = Invoke-RestMethod -Uri 'https://api.github.com/user' -Headers $h -TimeoutSec 60
$login = $me.login
Write-Host "TOKEN_OK login=$login"

# ---------------------------------------------------------------------------
# Persist the credential for the long term (best effort, several fallbacks).
# ---------------------------------------------------------------------------
$persisted = $false
Write-Host '=== persist credential ==='
try {
    $token | & $Gh auth login --hostname github.com --git-protocol https --with-token 2>&1 | ForEach-Object { "  gh: $_" }
    if ($LASTEXITCODE -eq 0) { $persisted = $true; Write-Host 'PERSIST=gh' }
} catch { Write-Host "  gh login failed: $($_.Exception.Message)" }
if (-not $persisted) {
    $credFile = Join-Path $Work 'gh-credential.txt'
    try {
        $content = "protocol=https`r`nhost=github.com`r`nusername=$login`r`npassword=$token`r`n`r`n"
        [IO.File]::WriteAllText($credFile, $content, (New-Object System.Text.UTF8Encoding($false)))
        cmd /c "git -c credential.helper=manager -c credential.https://github.com.helper=manager credential approve < `"$credFile`" 2>&1" | ForEach-Object { "  gcm: $_" }
        $checkFile = Join-Path $Work 'gh-credential-check.txt'
        [IO.File]::WriteAllText($checkFile, "protocol=https`r`nhost=github.com`r`n`r`n", (New-Object System.Text.UTF8Encoding($false)))
        $fill = cmd /c "git -c credential.helper=manager -c credential.https://github.com.helper=manager credential fill < `"$checkFile`" 2>&1"
        if (($fill | Out-String) -match 'password=') { $persisted = $true; Write-Host 'PERSIST=gcm' }
    } finally {
        Remove-Item $credFile, $checkFile -Force -ErrorAction SilentlyContinue
    }
}
& git config --global --replace-all 'credential.https://github.com.helper' 'manager' 2>$null
Write-Host "PERSISTENT_LOGIN=$persisted"

# ---------------------------------------------------------------------------
# Repo + push
# ---------------------------------------------------------------------------
$repoSlug = "$Owner/$Repo"
Write-Host '=== ensure repository ==='
try {
    $repo = Invoke-RestMethod -Uri "https://api.github.com/repos/$repoSlug" -Headers $h -TimeoutSec 60
    Write-Host '  exists'
} catch {
    $body = @{ name = $Repo; description = 'Phone-side bot team for DeepSeek Harness: native Android (Kotlin/Compose) client + security-scoped host bridge'; private = $false; has_issues = $true; has_wiki = $false; auto_init = $false } | ConvertTo-Json
    $repo = Invoke-RestMethod -Method Post -Uri 'https://api.github.com/user/repos' -Headers $h -Body $body -ContentType 'application/json' -TimeoutSec 60
    Write-Host "  created: $($repo.html_url)"
}

Write-Host '=== push main ==='
$remoteUrl = "https://github.com/$repoSlug.git"
$hasOrigin = (& git remote 2>$null) -contains 'origin'
if ($hasOrigin) { & git remote set-url origin $remoteUrl } else { & git remote add origin $remoteUrl }
$env:GIT_TERMINAL_PROMPT = '0'
if ($persisted) {
    & git push -u origin main 2>&1 | ForEach-Object { "  $_" }
} else {
    # Do not store anything: push with a one-shot Authorization header.
    & git -c "http.https://github.com/.extraheader=AUTHORIZATION: bearer $token" push -u origin main 2>&1 | ForEach-Object { "  $_" }
}
if ($LASTEXITCODE -ne 0) { Write-Host 'PUSH=failed'; exit 5 }
$headSha = (& git rev-parse HEAD) 2>$null
Write-Host "PUSHED=$headSha"

# ---------------------------------------------------------------------------
# Release
# ---------------------------------------------------------------------------
if (-not $SkipRelease) {
    Write-Host '=== publish release v0.1.0 ==='
    $assets = @(Get-Item (Join-Path $RepoRoot 'release\dsbot-0.1.0-release.apk') -ErrorAction SilentlyContinue) +
              @(Get-Item (Join-Path $RepoRoot 'release\dsbot-0.1.0-debug.apk') -ErrorAction SilentlyContinue)
    $notes = "首个可用版本。`n`n- Android 客户端（Kotlin + Compose）：Bot 团队、1:1 流式聊天、群聊（管理员调度 + @ 接力）、审批卡（完整命令 + 指纹 + 危险二次确认）、Keystore 存 token、深浅色。`n- 主机桥接（Node，零依赖）：设备 token、方法白名单、WS 端点白名单（阻止 terminal/attach）、cookie 注入、审计日志。`n- 安全设计对应审批 RCE / 配对即管理员 / 密钥外泄三类风险，见 docs/SECURITY.md。`n- 验证：Android 单测 50/50，桥接测试 44/44 + 真实 DSH 冒烟 7/7，协议级 E2E 通过。详见 release/BUILD-INFO.txt。`n`n安装与配对：docs/INSTALL.md"
    $rel = $null
    try { $rel = Invoke-RestMethod -Uri "https://api.github.com/repos/$repoSlug/releases/tags/v0.1.0" -Headers $h -TimeoutSec 60; Write-Host '  release exists' }
    catch {
        $relBody = @{ tag_name = 'v0.1.0'; name = 'DS Bot v0.1.0 - 手机上的 DSH Bot 团队'; body = $notes; draft = $false; prerelease = $false } | ConvertTo-Json -Depth 5
        $rel = Invoke-RestMethod -Method Post -Uri "https://api.github.com/repos/$repoSlug/releases" -Headers $h -Body $relBody -ContentType 'application/json' -TimeoutSec 120
        Write-Host "  release: $($rel.html_url)"
    }
    foreach ($asset in $assets) {
        try {
            $up = Invoke-RestMethod -Method Post -Uri "https://uploads.github.com/repos/$repoSlug/releases/$($rel.id)/assets?name=$($asset.Name)" -Headers $h -ContentType 'application/vnd.android.package-archive' -InFile $asset.FullName -TimeoutSec 900
            Write-Host "  asset: $($up.name) ($([math]::Round($up.size/1MB,2)) MB)"
        } catch { Write-Host "  asset FAILED $($asset.Name): $($_.Exception.Message)" }
    }
}

Write-Host '=== done ==='
Write-Host "REPO_URL=https://github.com/$repoSlug"
Write-Host "ACCOUNT=$login"
