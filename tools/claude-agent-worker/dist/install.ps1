# Claude Agent Worker - Installer (Windows)
# This script is run from INSIDE the extracted archive directory.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File install.ps1              # First install
#   powershell -ExecutionPolicy Bypass -File install.ps1 -Upgrade     # Upgrade
#
# Environment:
#   CLAUDE_WORKER_HOME   Install directory (default: ~\.claude-worker)

param([switch]$Upgrade)

$ErrorActionPreference = "Stop"

# --- Paths ----------------------------------------------------------------
$InstallDir = if ($env:CLAUDE_WORKER_HOME) { $env:CLAUDE_WORKER_HOME } else { Join-Path $env:USERPROFILE ".claude-worker" }
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Version = (Get-Content (Join-Path $ScriptDir "VERSION") -Raw).Trim()

# --- Detect install vs upgrade --------------------------------------------
$IsUpgrade = $Upgrade -or (Test-Path (Join-Path $InstallDir "VERSION"))

if ($IsUpgrade) {
    $OldVersionFile = Join-Path $InstallDir "VERSION"
    $OldVersion = if (Test-Path $OldVersionFile) { (Get-Content $OldVersionFile -Raw).Trim() } else { "unknown" }
    Write-Host "Upgrading Claude Agent Worker: $OldVersion -> $Version" -ForegroundColor Cyan
}
else {
    Write-Host "Installing Claude Agent Worker v$Version" -ForegroundColor Cyan
}
Write-Host "Install directory: $InstallDir" -ForegroundColor Cyan
Write-Host ""

# --- Prerequisite check: Python 3.10+ ------------------------------------
$PythonCmd = $null
foreach ($cmd in @("python3", "python")) {
    try {
        $pyVer = & $cmd -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')" 2>$null
        if ($pyVer) {
            $parts = $pyVer.Split('.')
            if ([int]$parts[0] -ge 3 -and [int]$parts[1] -ge 10) {
                $PythonCmd = $cmd
                break
            }
        }
    }
    catch { }
}

if (-not $PythonCmd) {
    Write-Host "ERROR: Python 3.10+ is required but not found." -ForegroundColor Red
    Write-Host "Please install Python 3.10 or later:" -ForegroundColor Yellow
    Write-Host "  https://www.python.org/downloads/"
    exit 1
}
$pyVersion = & $PythonCmd --version 2>&1
Write-Host "Python: $pyVersion" -ForegroundColor Green

# --- Prerequisite check: Claude Code CLI ----------------------------------
$claudeExists = Get-Command claude -ErrorAction SilentlyContinue
if (-not $claudeExists) {
    Write-Host "WARNING: Claude Code CLI not found in PATH." -ForegroundColor Yellow
    Write-Host "The worker requires 'claude' CLI to function." -ForegroundColor Yellow
    Write-Host "Install it with: npm install -g @anthropic-ai/claude-code" -ForegroundColor Yellow
    Write-Host ""
}

# --- Backup .env if upgrading --------------------------------------------
$EnvPath = Join-Path $InstallDir ".env"
$EnvBackup = $null
if ($IsUpgrade -and (Test-Path $EnvPath)) {
    $EnvBackup = [System.IO.Path]::GetTempFileName()
    Copy-Item $EnvPath $EnvBackup
    Write-Host "Backed up existing .env" -ForegroundColor Cyan
}

# --- Stop running worker if upgrading ------------------------------------
$StopScript = Join-Path $InstallDir "stop.ps1"
if ($IsUpgrade -and (Test-Path $StopScript)) {
    Write-Host "Stopping running worker..." -ForegroundColor Yellow
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
Copy-Item (Join-Path $ScriptDir ".env.example") $InstallDir -Force
Copy-Item (Join-Path $ScriptDir "SETUP.md") $InstallDir -Force
Copy-Item (Join-Path $ScriptDir "VERSION") $InstallDir -Force

# Start/stop scripts
foreach ($f in @(
    "start.ps1",
    "stop.ps1",
    "run-scheduled-task.ps1",
    "install-scheduled-task.ps1",
    "uninstall-scheduled-task.ps1",
    "start.sh",
    "start-mac.sh",
    "stop.sh"
)) {
    $srcFile = Join-Path $ScriptDir $f
    if (Test-Path $srcFile) {
        Copy-Item $srcFile $InstallDir -Force
    }
}

# CLI wrapper
$BinDir = Join-Path $InstallDir "bin"
New-Item -ItemType Directory -Force -Path $BinDir | Out-Null
Copy-Item (Join-Path $ScriptDir "bin\claude-worker.ps1") $BinDir -Force
if (Test-Path (Join-Path $ScriptDir "bin\claude-worker")) {
    Copy-Item (Join-Path $ScriptDir "bin\claude-worker") $BinDir -Force
}

# Create .cmd shim so `claude-worker` works in cmd.exe and PowerShell without .ps1 extension
$CmdShim = Join-Path $BinDir "claude-worker.cmd"
@"
@echo off
powershell -ExecutionPolicy Bypass -File "%~dp0claude-worker.ps1" %*
"@ | Out-File -Encoding ASCII $CmdShim

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

# --- Write CLAUDE_WORKER_URL to .env if provided --------------------------
if ($env:CLAUDE_WORKER_URL) {
    # IMPORTANT: Always read/write .env as UTF-8 no BOM to avoid encoding corruption
    # on Chinese Windows where PowerShell 5.x defaults to GBK (system locale).
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    $envContent = if (Test-Path $EnvPath) { [System.IO.File]::ReadAllText($EnvPath, $utf8NoBom) } else { "" }
    if ($envContent -match "(?m)^CLAUDE_WORKER_URL=") {
        # Update existing value
        $envContent = $envContent -replace "(?m)^CLAUDE_WORKER_URL=.*", "CLAUDE_WORKER_URL=$($env:CLAUDE_WORKER_URL)"
    }
    else {
        # Append
        $envContent = $envContent.TrimEnd() + "`n`n# Auto-upgrade URL (set by remote installer)`nCLAUDE_WORKER_URL=$($env:CLAUDE_WORKER_URL)`n"
    }
    [System.IO.File]::WriteAllText($EnvPath, $envContent, $utf8NoBom)
    Write-Host "Saved CLAUDE_WORKER_URL to .env" -ForegroundColor Green
}

# --- Clean __pycache__ and .egg-info --------------------------------------
Get-ChildItem -Path (Join-Path $InstallDir "src") -Directory -Recurse -Filter "__pycache__" -ErrorAction SilentlyContinue |
    Remove-Item -Recurse -Force 2>$null
Get-ChildItem -Path (Join-Path $InstallDir "src") -Directory -Recurse -Filter "*.egg-info" -ErrorAction SilentlyContinue |
    Remove-Item -Recurse -Force 2>$null

# --- Setup venv and install dependencies ---------------------------------
Write-Host ""
Write-Host "Setting up Python environment..." -ForegroundColor Cyan
Set-Location $InstallDir

$VenvDir = Join-Path $InstallDir ".venv"

# On upgrade, always recreate venv to avoid stale/broken pip or python
if ($IsUpgrade -and (Test-Path $VenvDir)) {
    Write-Host "Removing old venv (will recreate)..." -ForegroundColor Yellow
    Remove-Item $VenvDir -Recurse -Force
}

# Create venv if missing
if (-not (Test-Path (Join-Path $VenvDir "Scripts\python.exe"))) {
    Write-Host "Creating venv..." -ForegroundColor Cyan
    if (Test-Path $VenvDir) { Remove-Item $VenvDir -Recurse -Force }
    & $PythonCmd -m venv $VenvDir
    if ($LASTEXITCODE -ne 0) {
        Write-Host "ERROR: Failed to create venv." -ForegroundColor Red
        exit 1
    }
    Write-Host "venv created at $VenvDir" -ForegroundColor Green
}

# Use venv's pip to install dependencies
$VenvPip = Join-Path $VenvDir "Scripts\pip.exe"
$VenvPython = Join-Path $VenvDir "Scripts\python.exe"

Write-Host "Installing dependencies (using venv pip)..." -ForegroundColor Cyan
try {
    & $VenvPip install -e . -q
    if ($LASTEXITCODE -ne 0) { throw "pip failed" }
}
catch {
    Write-Host "WARNING: pip install failed. Try running manually:" -ForegroundColor Yellow
    Write-Host "  cd $InstallDir && .venv\Scripts\pip install -e ." -ForegroundColor Yellow
}

# --- Create logs directory ------------------------------------------------
$LogDir = Join-Path $InstallDir "logs"
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

# --- PATH guidance --------------------------------------------------------
Write-Host ""
$UserPath = [Environment]::GetEnvironmentVariable("PATH", "User")
if (-not $UserPath -or -not $UserPath.Contains($BinDir)) {
    Write-Host "To add 'claude-worker' to your PATH, run:" -ForegroundColor Yellow
    Write-Host "  [Environment]::SetEnvironmentVariable('PATH', `"$BinDir;`$env:PATH`", 'User')" -ForegroundColor White
    Write-Host "  Then restart your terminal." -ForegroundColor Yellow
    Write-Host ""
}

# --- Done -----------------------------------------------------------------
Write-Host "============================================" -ForegroundColor Green
if ($IsUpgrade) {
    Write-Host "Claude Agent Worker upgraded to v$Version" -ForegroundColor Green
}
else {
    Write-Host "Claude Agent Worker v$Version installed!" -ForegroundColor Green
}
Write-Host "============================================" -ForegroundColor Green
Write-Host ""
Write-Host "Commands:"
Write-Host "  claude-worker start     Start the worker" -ForegroundColor Cyan
Write-Host "  claude-worker stop      Stop the worker" -ForegroundColor Cyan
Write-Host "  claude-worker status    Check status & health" -ForegroundColor Cyan
Write-Host "  claude-worker version   Show version" -ForegroundColor Cyan
Write-Host "  claude-worker logs      Tail log output" -ForegroundColor Cyan
Write-Host "  claude-worker upgrade   Upgrade to new version" -ForegroundColor Cyan
Write-Host ""
Write-Host "Config:  $EnvPath"
Write-Host "Logs:    $LogDir\"
