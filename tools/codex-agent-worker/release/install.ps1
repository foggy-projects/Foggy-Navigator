# Codex Agent Worker - Installer (Windows)
# Run from inside the extracted release archive.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File install.ps1
#   powershell -ExecutionPolicy Bypass -File install.ps1 -Upgrade

param([switch]$Upgrade)

$ErrorActionPreference = "Stop"

function Write-Utf8File {
    param(
        [string]$Path,
        [string]$Content
    )
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Content, $utf8NoBom)
}

function Get-NodeVersion {
    try {
        return (& node --version 2>$null).Trim()
    }
    catch {
        return ""
    }
}

$InstallDir = if ($env:CODEX_WORKER_HOME) { $env:CODEX_WORKER_HOME } else { Join-Path $env:USERPROFILE ".codex-worker" }
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Version = (Get-Content (Join-Path $ScriptDir "VERSION") -Raw).Trim()

$IsUpgrade = $Upgrade -or (Test-Path (Join-Path $InstallDir "VERSION"))

if ($IsUpgrade) {
    $OldVersionFile = Join-Path $InstallDir "VERSION"
    $OldVersion = if (Test-Path $OldVersionFile) { (Get-Content $OldVersionFile -Raw).Trim() } else { "unknown" }
    Write-Host "Upgrading Codex Agent Worker: $OldVersion -> $Version" -ForegroundColor Cyan
}
else {
    Write-Host "Installing Codex Agent Worker v$Version" -ForegroundColor Cyan
}
Write-Host "Install directory: $InstallDir" -ForegroundColor Cyan
Write-Host ""

$nodeVersion = Get-NodeVersion
if (-not $nodeVersion) {
    Write-Host "ERROR: Node.js is required but was not found in PATH." -ForegroundColor Red
    Write-Host "Install Node.js 20+ from https://nodejs.org/" -ForegroundColor Yellow
    exit 1
}
Write-Host "Node: $nodeVersion" -ForegroundColor Green

$codexExists = Get-Command codex -ErrorAction SilentlyContinue
if (-not $codexExists) {
    Write-Host "WARNING: Codex CLI not found in PATH." -ForegroundColor Yellow
    Write-Host "The worker can still start, but subscription login mode requires 'codex'." -ForegroundColor Yellow
    Write-Host ""
}

$EnvPath = Join-Path $InstallDir ".env"
$EnvBackup = $null
if ($IsUpgrade -and (Test-Path $EnvPath)) {
    $EnvBackup = [System.IO.Path]::GetTempFileName()
    Copy-Item $EnvPath $EnvBackup
    Write-Host "Backed up existing .env" -ForegroundColor Cyan
}

$StopScript = Join-Path $InstallDir "stop.ps1"
if ($IsUpgrade -and (Test-Path $StopScript)) {
    Write-Host "Stopping running worker..." -ForegroundColor Yellow
    try { & powershell -ExecutionPolicy Bypass -File $StopScript }
    catch { }
    Write-Host ""
}

New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null

Write-Host "Copying files..." -ForegroundColor Cyan

foreach ($pathName in @("dist", "docs", "bin")) {
    $target = Join-Path $InstallDir $pathName
    if (Test-Path $target) {
        Remove-Item $target -Recurse -Force
    }
}

Copy-Item (Join-Path $ScriptDir "dist") (Join-Path $InstallDir "dist") -Recurse -Force
Copy-Item (Join-Path $ScriptDir "package.json") $InstallDir -Force
$InstalledPackageLock = Join-Path $InstallDir "package-lock.json"
if (Test-Path $InstalledPackageLock) {
    Remove-Item $InstalledPackageLock -Force
}
$PackageLock = Join-Path $ScriptDir "package-lock.json"
if (Test-Path $PackageLock) {
    Copy-Item $PackageLock $InstallDir -Force
}
Copy-Item (Join-Path $ScriptDir ".env.example") $InstallDir -Force
Copy-Item (Join-Path $ScriptDir "VERSION") $InstallDir -Force

$DocsDir = Join-Path $ScriptDir "docs"
if (Test-Path $DocsDir) {
    Copy-Item $DocsDir (Join-Path $InstallDir "docs") -Recurse -Force
}

foreach ($f in @("start.ps1", "stop.ps1", "start.sh", "stop.sh", "install.ps1", "install.sh", "update.ps1", "update.sh")) {
    $srcFile = Join-Path $ScriptDir $f
    if (Test-Path $srcFile) {
        Copy-Item $srcFile $InstallDir -Force
    }
}

$BinDir = Join-Path $InstallDir "bin"
New-Item -ItemType Directory -Force -Path $BinDir | Out-Null
Copy-Item (Join-Path $ScriptDir "bin\codex-worker.ps1") $BinDir -Force
if (Test-Path (Join-Path $ScriptDir "bin\codex-worker")) {
    Copy-Item (Join-Path $ScriptDir "bin\codex-worker") $BinDir -Force
}

$CmdShim = Join-Path $BinDir "codex-worker.cmd"
@"
@echo off
powershell -ExecutionPolicy Bypass -File "%~dp0codex-worker.ps1" %*
"@ | Out-File -Encoding ASCII $CmdShim

if ($EnvBackup -and (Test-Path $EnvBackup)) {
    Copy-Item $EnvBackup $EnvPath -Force
    Remove-Item $EnvBackup
    Write-Host "Restored .env from backup" -ForegroundColor Green
}
elseif (-not (Test-Path $EnvPath)) {
    Copy-Item (Join-Path $ScriptDir ".env.example") $EnvPath
    Write-Host "Created .env from template" -ForegroundColor Yellow
    Write-Host "  -> Please edit: $EnvPath" -ForegroundColor Yellow
}

if ($env:CODEX_WORKER_URL) {
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    $envContent = if (Test-Path $EnvPath) { [System.IO.File]::ReadAllText($EnvPath, $utf8NoBom) } else { "" }
    if ($envContent -match "(?m)^CODEX_WORKER_URL=") {
        $envContent = $envContent -replace "(?m)^CODEX_WORKER_URL=.*", "CODEX_WORKER_URL=$($env:CODEX_WORKER_URL)"
    }
    else {
        $envContent = $envContent.TrimEnd() + "`n`n# Auto-upgrade URL (set by remote installer)`nCODEX_WORKER_URL=$($env:CODEX_WORKER_URL)`n"
    }
    Write-Utf8File -Path $EnvPath -Content $envContent
    Write-Host "Saved CODEX_WORKER_URL to .env" -ForegroundColor Green
}

Write-Host ""
Write-Host "Installing runtime dependencies..." -ForegroundColor Cyan
Set-Location $InstallDir

if (Test-Path "node_modules") {
    Remove-Item "node_modules" -Recurse -Force
}

if (Test-Path "package-lock.json") {
    & npm ci --omit=dev
}
else {
    & npm install --omit=dev
}
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: npm install failed." -ForegroundColor Red
    exit 1
}

$LogDir = Join-Path $InstallDir "logs"
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

Write-Host ""
$UserPath = [Environment]::GetEnvironmentVariable("PATH", "User")
if (-not $UserPath -or -not $UserPath.Contains($BinDir)) {
    Write-Host "To add 'codex-worker' to your PATH, run:" -ForegroundColor Yellow
    Write-Host "  [Environment]::SetEnvironmentVariable('PATH', `"$BinDir;`$env:PATH`", 'User')" -ForegroundColor White
    Write-Host "  Then restart your terminal." -ForegroundColor Yellow
    Write-Host ""
}

Write-Host "============================================" -ForegroundColor Green
if ($IsUpgrade) {
    Write-Host "Codex Agent Worker upgraded to v$Version" -ForegroundColor Green
}
else {
    Write-Host "Codex Agent Worker v$Version installed!" -ForegroundColor Green
}
Write-Host "============================================" -ForegroundColor Green
Write-Host ""
Write-Host "Commands:"
Write-Host "  codex-worker start     Start the worker" -ForegroundColor Cyan
Write-Host "  codex-worker stop      Stop the worker" -ForegroundColor Cyan
Write-Host "  codex-worker status    Check status & health" -ForegroundColor Cyan
Write-Host "  codex-worker version   Show version" -ForegroundColor Cyan
Write-Host "  codex-worker logs      Tail log output" -ForegroundColor Cyan
Write-Host "  codex-worker upgrade   Upgrade to new version" -ForegroundColor Cyan
Write-Host "  codex-worker upgrade-sdk   Upgrade Codex SDK only" -ForegroundColor Cyan
Write-Host ""
Write-Host "Config:  $EnvPath"
Write-Host "Logs:    $LogDir\"
