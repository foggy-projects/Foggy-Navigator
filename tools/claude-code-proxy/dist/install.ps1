# Claude Code Proxy - Installer (Windows)
# This script is run from INSIDE the extracted archive directory.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File install.ps1              # First install
#   powershell -ExecutionPolicy Bypass -File install.ps1 -Upgrade     # Upgrade
#
# Environment:
#   CLAUDE_PROXY_HOME   Install directory (default: ~\.claude-code-proxy)

param([switch]$Upgrade)

$ErrorActionPreference = "Stop"

# --- Paths ----------------------------------------------------------------
$InstallDir = if ($env:CLAUDE_PROXY_HOME) { $env:CLAUDE_PROXY_HOME } else { Join-Path $env:USERPROFILE ".claude-code-proxy" }
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Version = (Get-Content (Join-Path $ScriptDir "VERSION") -Raw).Trim()

# --- Detect install vs upgrade --------------------------------------------
$IsUpgrade = $Upgrade -or (Test-Path (Join-Path $InstallDir "VERSION"))

if ($IsUpgrade) {
    $OldVersionFile = Join-Path $InstallDir "VERSION"
    $OldVersion = if (Test-Path $OldVersionFile) { (Get-Content $OldVersionFile -Raw).Trim() } else { "unknown" }
    Write-Host "Upgrading Claude Code Proxy: $OldVersion -> $Version" -ForegroundColor Cyan
}
else {
    Write-Host "Installing Claude Code Proxy v$Version" -ForegroundColor Cyan
}
Write-Host "Install directory: $InstallDir" -ForegroundColor Cyan
Write-Host ""

# --- Prerequisite check: Python 3.9+ -------------------------------------
$PythonCmd = $null
foreach ($cmd in @("python3", "python")) {
    try {
        $pyVer = & $cmd -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')" 2>$null
        if ($pyVer) {
            $parts = $pyVer.Split('.')
            if ([int]$parts[0] -ge 3 -and [int]$parts[1] -ge 9) {
                $PythonCmd = $cmd
                break
            }
        }
    }
    catch { }
}

if (-not $PythonCmd) {
    Write-Host "ERROR: Python 3.9+ is required but not found." -ForegroundColor Red
    Write-Host "Please install Python 3.9 or later:" -ForegroundColor Yellow
    Write-Host "  https://www.python.org/downloads/"
    exit 1
}
$pyVersion = & $PythonCmd --version 2>&1
Write-Host "Python: $pyVersion" -ForegroundColor Green

# --- Backup .env if upgrading --------------------------------------------
$EnvPath = Join-Path $InstallDir ".env"
$EnvBackup = $null
if ($IsUpgrade -and (Test-Path $EnvPath)) {
    $EnvBackup = [System.IO.Path]::GetTempFileName()
    Copy-Item $EnvPath $EnvBackup
    Write-Host "Backed up existing .env" -ForegroundColor Cyan
}

# --- Stop running proxy if upgrading -------------------------------------
$StopScript = Join-Path $InstallDir "stop.ps1"
if ($IsUpgrade -and (Test-Path $StopScript)) {
    Write-Host "Stopping running proxy..." -ForegroundColor Yellow
    try { & powershell -ExecutionPolicy Bypass -File $StopScript }
    catch { }
    Write-Host ""
}

# --- Create install directory ---------------------------------------------
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null

# --- Copy source code (always overwrite) ----------------------------------
Write-Host "Copying files..." -ForegroundColor Cyan

# Remove old src to avoid stale .pyc files
$OldSrc = Join-Path $InstallDir "src"
if (Test-Path $OldSrc) { Remove-Item $OldSrc -Recurse -Force }

Copy-Item (Join-Path $ScriptDir "src") (Join-Path $InstallDir "src") -Recurse -Force
Copy-Item (Join-Path $ScriptDir "pyproject.toml") $InstallDir -Force
Copy-Item (Join-Path $ScriptDir "requirements.txt") $InstallDir -Force
Copy-Item (Join-Path $ScriptDir ".env.example") $InstallDir -Force
Copy-Item (Join-Path $ScriptDir "QUICKSTART.md") $InstallDir -Force
Copy-Item (Join-Path $ScriptDir "start_proxy.py") $InstallDir -Force
Copy-Item (Join-Path $ScriptDir "VERSION") $InstallDir -Force
# README.md is required by pyproject.toml (readme = "README.md") for pip install
$readmeSrc = Join-Path $ScriptDir "README.md"
if (Test-Path $readmeSrc) { Copy-Item $readmeSrc $InstallDir -Force }

# Start/stop scripts
foreach ($f in @("start.ps1", "stop.ps1", "start.sh", "stop.sh")) {
    $srcFile = Join-Path $ScriptDir $f
    if (Test-Path $srcFile) {
        Copy-Item $srcFile $InstallDir -Force
    }
}

# CLI wrapper
$BinDir = Join-Path $InstallDir "bin"
New-Item -ItemType Directory -Force -Path $BinDir | Out-Null
Copy-Item (Join-Path $ScriptDir "bin\claude-code-proxy.ps1") $BinDir -Force
if (Test-Path (Join-Path $ScriptDir "bin\claude-code-proxy")) {
    Copy-Item (Join-Path $ScriptDir "bin\claude-code-proxy") $BinDir -Force
}

# Create .cmd shim so `claude-code-proxy` works in cmd.exe and PowerShell without .ps1 extension
$CmdShim = Join-Path $BinDir "claude-code-proxy.cmd"
@"
@echo off
powershell -ExecutionPolicy Bypass -File "%~dp0claude-code-proxy.ps1" %*
"@ | Out-File -Encoding ASCII $CmdShim

# Bundled docs
$DocsDir = Join-Path $ScriptDir "docs"
if (Test-Path $DocsDir) {
    $DestDocs = Join-Path $InstallDir "docs"
    if (Test-Path $DestDocs) { Remove-Item $DestDocs -Recurse -Force }
    Copy-Item $DocsDir $DestDocs -Recurse -Force
}

# --- Restore or create .env -----------------------------------------------
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

# --- Write CLAUDE_PROXY_URL to .env if provided ---------------------------
if ($env:CLAUDE_PROXY_URL) {
    # IMPORTANT: Always read/write .env as UTF-8 no BOM to avoid encoding corruption
    # on Chinese Windows where PowerShell 5.x defaults to GBK (system locale).
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    $envContent = if (Test-Path $EnvPath) { [System.IO.File]::ReadAllText($EnvPath, $utf8NoBom) } else { "" }
    if ($envContent -match "(?m)^CLAUDE_PROXY_URL=") {
        # Update existing value
        $envContent = $envContent -replace "(?m)^CLAUDE_PROXY_URL=.*", "CLAUDE_PROXY_URL=$($env:CLAUDE_PROXY_URL)"
    }
    else {
        # Append
        $envContent = $envContent.TrimEnd() + "`n`n# Auto-upgrade URL (set by remote installer)`nCLAUDE_PROXY_URL=$($env:CLAUDE_PROXY_URL)`n"
    }
    [System.IO.File]::WriteAllText($EnvPath, $envContent, $utf8NoBom)
    Write-Host "Saved CLAUDE_PROXY_URL to .env" -ForegroundColor Green
}

# --- Clean __pycache__ and .egg-info --------------------------------------
Get-ChildItem -Path (Join-Path $InstallDir "src") -Directory -Recurse -Filter "__pycache__" -ErrorAction SilentlyContinue |
    Remove-Item -Recurse -Force 2>$null
Get-ChildItem -Path (Join-Path $InstallDir "src") -Directory -Recurse -Filter "*.egg-info" -ErrorAction SilentlyContinue |
    Remove-Item -Recurse -Force 2>$null

# --- Install Python dependencies -----------------------------------------
Write-Host ""
Write-Host "Installing Python dependencies..." -ForegroundColor Cyan
Set-Location $InstallDir

try {
    & pip install -e . -q
    if ($LASTEXITCODE -ne 0) { throw "pip failed" }
}
catch {
    Write-Host "WARNING: pip install failed. Try running manually:" -ForegroundColor Yellow
    Write-Host "  cd $InstallDir && pip install -e ." -ForegroundColor Yellow
}

# --- Create logs directory ------------------------------------------------
$LogDir = Join-Path $InstallDir "logs"
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

# --- PATH guidance --------------------------------------------------------
Write-Host ""
$UserPath = [Environment]::GetEnvironmentVariable("PATH", "User")
if (-not $UserPath -or -not $UserPath.Contains($BinDir)) {
    Write-Host "To add 'claude-code-proxy' to your PATH, run:" -ForegroundColor Yellow
    Write-Host "  [Environment]::SetEnvironmentVariable('PATH', `"$BinDir;`$env:PATH`", 'User')" -ForegroundColor White
    Write-Host "  Then restart your terminal." -ForegroundColor Yellow
    Write-Host ""
}

# --- Done -----------------------------------------------------------------
Write-Host "============================================" -ForegroundColor Green
if ($IsUpgrade) {
    Write-Host "Claude Code Proxy upgraded to v$Version" -ForegroundColor Green
}
else {
    Write-Host "Claude Code Proxy v$Version installed!" -ForegroundColor Green
}
Write-Host "============================================" -ForegroundColor Green
Write-Host ""
Write-Host "Commands:"
Write-Host "  claude-code-proxy start     Start the proxy" -ForegroundColor Cyan
Write-Host "  claude-code-proxy stop      Stop the proxy" -ForegroundColor Cyan
Write-Host "  claude-code-proxy status    Check status & health" -ForegroundColor Cyan
Write-Host "  claude-code-proxy version   Show version" -ForegroundColor Cyan
Write-Host "  claude-code-proxy logs      Tail log output" -ForegroundColor Cyan
Write-Host "  claude-code-proxy upgrade   Upgrade to new version" -ForegroundColor Cyan
Write-Host ""
Write-Host "Config:  $EnvPath"
Write-Host "Logs:    $LogDir\"
