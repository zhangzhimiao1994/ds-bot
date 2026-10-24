#Requires -Version 5.1
<#
.SYNOPSIS
    Install the official GitHub CLI into %LOCALAPPDATA%\Programs\GitHub CLI.
.DESCRIPTION
    The machine previously had gh installed there (its git credential helper still
    points at that path), but the files were removed. This restores the CLI so
    `gh auth login` and the persistent credential helper work again.
#>
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$RepoRoot = Split-Path -Parent $PSScriptRoot
$Work = Join-Path $RepoRoot 'work'
New-Item -ItemType Directory -Force -Path $Work | Out-Null
$Dest = Join-Path $env:LOCALAPPDATA 'Programs\GitHub CLI'
$Gh = Join-Path $Dest 'bin\gh.exe'
if (Test-Path $Gh) {
    Write-Host "[skip] gh already installed: $(& $Gh --version 2>&1 | Select-Object -First 1)"
    exit 0
}
Write-Host '=== resolve latest GitHub CLI release ==='
$rel = Invoke-RestMethod -Uri 'https://api.github.com/repos/cli/cli/releases/latest' -Headers @{ 'User-Agent' = 'dsbot-setup' } -TimeoutSec 60
$asset = $rel.assets | Where-Object { $_.name -like 'gh_*_windows_amd64.zip' } | Select-Object -First 1
if (-not $asset) { throw 'no windows_amd64 asset found' }
$zip = Join-Path $Work 'gh-cli.zip'
Write-Host "=== download $($asset.name) ($([math]::Round($asset.size/1MB,1)) MB) ==="
Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $zip -UseBasicParsing -TimeoutSec 900
$extract = Join-Path $Work 'gh-extract'
if (Test-Path $extract) { Remove-Item $extract -Recurse -Force }
Expand-Archive -LiteralPath $zip -DestinationPath $extract -Force
$inner = Get-ChildItem $extract -Directory | Select-Object -First 1
if (-not $inner) { throw 'unexpected archive layout' }
New-Item -ItemType Directory -Force -Path $Dest | Out-Null
Copy-Item (Join-Path $inner.FullName '*') $Dest -Recurse -Force
Write-Host "[ok] gh => $(& $Gh --version 2>&1 | Select-Object -First 1)"
# Put gh on the user PATH for future shells (persistent).
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
$binDir = Join-Path $Dest 'bin'
if (($userPath -split ';') -notcontains $binDir) {
    [Environment]::SetEnvironmentVariable('Path', ($userPath.TrimEnd(';') + ';' + $binDir), 'User')
    Write-Host "[ok] added to user PATH: $binDir"
}
