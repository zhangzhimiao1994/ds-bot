#Requires -Version 5.1
<#
.SYNOPSIS
    Finalize the GitHub publication: push main over the working SSH path and
    publish v0.1.0 with the APK assets via the REST API.
.DESCRIPTION
    Credentials: the token is read back from Git Credential Manager (it was stored
    by github-login3.ps1) only to call the REST API; it is never printed.
    Pushing uses SSH (already authenticated on this machine).
#>
[CmdletBinding()]
param(
    [string]$Owner = 'zhangzhimiao1994',
    [string]$Repo = 'ds-bot',
    [switch]$SkipRelease
)
$ErrorActionPreference = 'Continue'
$RepoRoot = Split-Path -Parent $PSScriptRoot
$Work = Join-Path $RepoRoot 'work'
New-Item -ItemType Directory -Force -Path $Work | Out-Null
Set-Location $RepoRoot
$repoSlug = "$Owner/$Repo"

# --- fetch the stored token from GCM (never printed) -----------------------
$fillIn = Join-Path $Work 'cred-fill-in.txt'
[IO.File]::WriteAllText($fillIn, "protocol=https`r`nhost=github.com`r`n`r`n", (New-Object System.Text.UTF8Encoding($false)))
$fill = cmd /c "git -c credential.helper=manager -c credential.https://github.com.helper=manager credential fill < `"$fillIn`" 2>&1"
Remove-Item $fillIn -Force -ErrorAction SilentlyContinue
$tokenLine = ($fill | Where-Object { $_ -like 'password=*' } | Select-Object -First 1)
if (-not $tokenLine) { Write-Host 'NO_STORED_TOKEN'; exit 4 }
$token = $tokenLine.Substring(9).Trim()
Write-Host "STORED_TOKEN_OK len=$($token.Length)"
$h = @{ Authorization = "Bearer $token"; 'User-Agent' = 'dsbot-publish'; Accept = 'application/vnd.github+json' }

# --- ensure repo ----------------------------------------------------------
try { $repo = Invoke-RestMethod -Uri "https://api.github.com/repos/$repoSlug" -Headers $h -TimeoutSec 60; Write-Host "REPO_EXISTS=$($repo.html_url)" }
catch {
    $body = @{ name = $Repo; description = 'Phone-side bot team for DeepSeek Harness: native Android client + security-scoped host bridge'; private = $false } | ConvertTo-Json
    $repo = Invoke-RestMethod -Method Post -Uri 'https://api.github.com/user/repos' -Headers $h -Body $body -ContentType 'application/json' -TimeoutSec 60
    Write-Host "REPO_CREATED=$($repo.html_url)"
}

# --- push over SSH --------------------------------------------------------
$env:GIT_SSH_COMMAND = 'ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=~/.ssh/known_hosts'
$sshUrl = "git@github.com:$repoSlug.git"
$hasOrigin = (& git remote 2>$null) -contains 'origin'
if ($hasOrigin) { & git remote set-url origin $sshUrl } else { & git remote add origin $sshUrl }
Write-Host '=== git push (ssh) ==='
& git push -u origin main 2>&1 | ForEach-Object { "  $_" }
Write-Host "PUSH_EXIT=$LASTEXITCODE"
& git ls-remote origin refs/heads/main 2>&1 | ForEach-Object { "  remote: $_" }
$headSha = (& git rev-parse HEAD) 2>$null
Write-Host "LOCAL_HEAD=$headSha"

# --- release --------------------------------------------------------------
if (-not $SkipRelease) {
    Write-Host '=== release v0.1.0 ==='
    $assets = @()
    foreach ($p in 'release\dsbot-0.1.0-release.apk', 'release\dsbot-0.1.0-debug.apk') {
        $full = Join-Path $RepoRoot $p
        if (Test-Path $full) { $assets += Get-Item $full }
    }
    $notes = "首个可用版本。`n`n- Android 客户端（Kotlin + Compose）：Bot 团队、1:1 流式聊天、群聊（管理员调度 + @ 接力）、审批卡（完整命令 + 指纹 + 危险二次确认）、Keystore 存 token、深浅色。`n- 主机桥接（Node，零依赖）：设备 token、方法白名单、WS 端点白名单（阻止 terminal/attach）、cookie 注入、审计日志。`n- 安全设计对应审批 RCE / 配对即管理员 / 密钥外泄三类风险，见 docs/SECURITY.md。`n- 验证：Android 单测 50/50，桥接测试 44/44 + 真实 DSH 冒烟 7/7，协议级 E2E 通过。详见 release/BUILD-INFO.txt。`n`n安装与配对：docs/INSTALL.md"
    $rel = $null
    try { $rel = Invoke-RestMethod -Uri "https://api.github.com/repos/$repoSlug/releases/tags/v0.1.0" -Headers $h -TimeoutSec 60; Write-Host "  release exists: $($rel.html_url)" }
    catch {
        $relBody = @{ tag_name = 'v0.1.0'; name = 'DS Bot v0.1.0 - 手机上的 DSH Bot 团队'; body = $notes; draft = $false; prerelease = $false } | ConvertTo-Json -Depth 5
        $rel = Invoke-RestMethod -Method Post -Uri "https://api.github.com/repos/$repoSlug/releases" -Headers $h -Body $relBody -ContentType 'application/json' -TimeoutSec 120
        Write-Host "  release created: $($rel.html_url)"
    }
    foreach ($asset in $assets) {
        try {
            $up = Invoke-RestMethod -Method Post -Uri "https://uploads.github.com/repos/$repoSlug/releases/$($rel.id)/assets?name=$($asset.Name)" -Headers $h -ContentType 'application/vnd.android.package-archive' -InFile $asset.FullName -TimeoutSec 900
            Write-Host "  asset uploaded: $($up.name) ($([math]::Round($up.size/1MB,2)) MB)"
        } catch {
            if ($_.Exception.Message -match 'already_exists') { Write-Host "  asset exists: $($asset.Name)" }
            else { Write-Host "  asset FAILED $($asset.Name): $($_.Exception.Message)" }
        }
    }
}
Write-Host "REPO_URL=https://github.com/$repoSlug"
Write-Host 'DONE'
