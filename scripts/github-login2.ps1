#Requires -Version 5.1
<#
.SYNOPSIS
    GitHub device login + persistent credential + repo bootstrap (v2, with fallbacks).

.DESCRIPTION
    1. OAuth device flow (official GitHub CLI client id) -> access token in memory.
    2. Verify the token against the REST API and print diagnostics (never the token).
    3. Persist it for the long term in Git Credential Manager (Windows Credential
       Manager) and point global git config at the manager helper, replacing the
       broken `gh.exe auth git-credential` helper that no longer exists.
    4. Try to register the token with GitHub CLI as well (best effort).
    5. Create zhangzhimiao1994/ds-bot if missing, push main over HTTPS via GCM,
       publish v0.1.0 and upload both APK assets through the REST API.
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
$Git = 'git'

Write-Host '=== GitHub device login (v2) ==='
$Device = Invoke-RestMethod -Method Post -Uri 'https://github.com/login/device/code' `
    -Body @{ client_id = $ClientId; scope = $Scopes } -Headers @{ Accept = 'application/json' } -TimeoutSec 60
Write-Host "VERIFY_URL=$($Device.verification_uri)"
Write-Host "USER_CODE=$($Device.user_code)"
Write-Host 'STATUS=WAITING_FOR_USER'
Write-Host "请在浏览器打开 $($Device.verification_uri) 并输入代码：$($Device.user_code)"

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
    if ($r.error -eq 'slow_down') { $interval += 5; Write-Host 'STATUS=WAITING_FOR_USER'; continue }
    if ($r.error -and $r.error -ne 'authorization_pending') { Write-Host "STATUS=ERROR $($r.error)"; exit 2 }
    Write-Host 'STATUS=WAITING_FOR_USER'
}
if (-not $token) { Write-Host 'STATUS=EXPIRED'; exit 3 }
Write-Host "STATUS=AUTHORIZED tokenLen=$($token.Length)"

# --- verify token ---------------------------------------------------------
$apiOk = $false; $login = $null
foreach ($scheme in 'Bearer', 'token') {
    try {
        $me = Invoke-RestMethod -Uri 'https://api.github.com/user' -Headers @{ Authorization = "$scheme $token"; 'User-Agent' = 'dsbot-setup'; Accept = 'application/vnd.github+json' } -TimeoutSec 60
        $apiOk = $true; $login = $me.login
        Write-Host "TOKEN_OK scheme=$scheme login=$login"
        break
    } catch {
        $code = $_.Exception.Response.StatusCode.value__
        Write-Host "TOKEN_CHECK scheme=$scheme HTTP=$code"
    }
}
if (-not $apiOk) { Write-Host 'TOKEN_REJECTED'; exit 4 }

# --- persist credential for git over HTTPS (long-term) --------------------
Write-Host '=== persist credential (Git Credential Manager) ==='
$cred = "protocol=https`nhost=github.com`nusername=$login`npassword=$token`n`n"
$cred | & $Git -c credential.helper=manager -c 'credential.https://github.com.helper=manager' credential approve 2>&1 | Out-Null
& $Git config --global --replace-all 'credential.https://github.com.helper' 'manager'
Write-Host 'GIT_HELPER_CONFIGURED=manager'
$verify = "protocol=https`nhost=github.com`n`n" | & $Git -c credential.helper=manager credential fill 2>$null
if ($verify | Where-Object { $_ -like 'password=*' }) { Write-Host 'CREDENTIAL_STORED=yes' } else { Write-Host 'CREDENTIAL_STORED=no' }

# --- also register with gh CLI (best effort) ------------------------------
Write-Host '=== gh CLI registration (best effort) ==='
if (Test-Path $Gh) {
    $token | & $Gh auth login --hostname github.com --git-protocol https --with-token 2>&1 | ForEach-Object { "  $_" }
    if ($LASTEXITCODE -eq 0) {
        & $Gh auth setup-git 2>&1 | Out-Null
        & $Git config --global --replace-all 'credential.https://github.com.helper' 'manager'
        Write-Host 'GH_LOGIN=ok'
    } else { Write-Host 'GH_LOGIN=failed (git credential manager is authoritative)' }
}

$h = @{ Authorization = "Bearer $token"; 'User-Agent' = 'dsbot-setup'; Accept = 'application/vnd.github+json' }
Set-Location $RepoRoot

# --- create repo ----------------------------------------------------------
Write-Host '=== ensure repository ==='
$repoSlug = "$Owner/$Repo"
$repo = $null
try { $repo = Invoke-RestMethod -Uri "https://api.github.com/repos/$repoSlug" -Headers $h -TimeoutSec 60; Write-Host '  exists' }
catch {
    $body = @{ name = $Repo; description = 'Phone-side bot team for DeepSeek Harness: native Android (Kotlin/Compose) client + security-scoped host bridge'; private = $false; has_issues = $true; has_wiki = $false; auto_init = $false } | ConvertTo-Json
    $repo = Invoke-RestMethod -Method Post -Uri 'https://api.github.com/user/repos' -Headers $h -Body $body -ContentType 'application/json' -TimeoutSec 60
    Write-Host "  created: $($repo.html_url)"
}

# --- push ----------------------------------------------------------------
Write-Host '=== push main ==='
$remoteUrl = "https://github.com/$repoSlug.git"
$hasOrigin = (& $Git remote 2>$null) -contains 'origin'
if ($hasOrigin) { & $Git remote set-url origin $remoteUrl } else { & $Git remote add origin $remoteUrl }
$env:GIT_TERMINAL_PROMPT = '0'
& $Git push -u origin main 2>&1 | ForEach-Object { "  $_" }
if ($LASTEXITCODE -ne 0) { Write-Host 'PUSH=failed'; exit 5 }
$headSha = (& $Git rev-parse HEAD) 2>$null
Write-Host "PUSHED=$headSha"

# --- release -------------------------------------------------------------
if (-not $SkipRelease) {
    Write-Host '=== publish release v0.1.0 ==='
    $assets = @()
    foreach ($apk in 'release\dsbot-0.1.0-release.apk', 'release\dsbot-0.1.0-debug.apk') {
        $full = Join-Path $RepoRoot $apk
        if (Test-Path $full) { $assets += Get-Item $full }
    }
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
