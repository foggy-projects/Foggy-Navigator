# LangGraph Biz Worker - Start Script
# Usage: powershell -ExecutionPolicy Bypass -File start.ps1

$ErrorActionPreference = "Stop"
$WorkerDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Port = 3061

# Load port from .env if present
$EnvFile = Join-Path $WorkerDir ".env"
if (Test-Path $EnvFile) {
    $portLine = Get-Content $EnvFile | Where-Object { $_ -match "^BIZ_WORKER_PORT=(\d+)" }
    if ($portLine -and $portLine -match "=(\d+)") {
        $Port = [int]$Matches[1]
    }
}

Write-Host "=== LangGraph Biz Worker ===" -ForegroundColor Cyan
Write-Host "Port: $Port" -ForegroundColor Cyan

# Kill existing process on the port
$existingPid = (netstat -ano | Select-String ":$Port\s+.*LISTENING" | ForEach-Object {
    ($_ -split '\s+')[-1]
} | Select-Object -First 1)

if ($existingPid) {
    Write-Host "Stopping existing process on port $Port (PID: $existingPid)..." -ForegroundColor Yellow
    taskkill /F /PID $existingPid 2>$null
    Start-Sleep -Milliseconds 500
}

# --- Locate venv Python --------------------------------------------------
$VenvDir = Join-Path $WorkerDir ".venv"
$VenvPython = Join-Path $VenvDir "Scripts\python.exe"
$VenvPip = Join-Path $VenvDir "Scripts\pip.exe"

# Create venv if it doesn't exist
if (-not (Test-Path $VenvPython)) {
    Write-Host "Creating venv..." -ForegroundColor Cyan
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
        Write-Host "ERROR: Python 3.10+ not found. Cannot create venv." -ForegroundColor Red
        exit 1
    }
    if (Test-Path $VenvDir) { Remove-Item $VenvDir -Recurse -Force }
    & $PythonCmd -m venv $VenvDir
    if ($LASTEXITCODE -ne 0) {
        Write-Host "ERROR: Failed to create venv." -ForegroundColor Red
        exit 1
    }
}

Write-Host "Using venv: $VenvPython" -ForegroundColor Cyan

# Install / sync dependencies from pyproject.toml before starting
Set-Location $WorkerDir
Write-Host "Syncing Python dependencies..." -ForegroundColor Cyan
& $VenvPip install -e . -q
if ($LASTEXITCODE -ne 0) {
    Write-Host "WARNING: pip install failed, continuing with existing env..." -ForegroundColor Yellow
}

# Prepare logs directory
$LogDir = Join-Path $WorkerDir "logs"
if (-not (Test-Path $LogDir)) {
    New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
}

# Start the worker in background
$env:PYTHONPATH = Join-Path $WorkerDir "src"
Write-Host "Starting LangGraph Biz Worker on port $Port (background)..." -ForegroundColor Green

Start-Process $VenvPython `
    -ArgumentList "-m", "uvicorn", "langgraph_biz_worker.main:app", "--host", "0.0.0.0", "--port", "$Port" `
    -WorkingDirectory $WorkerDir `
    -RedirectStandardOutput (Join-Path $LogDir "worker.log") `
    -RedirectStandardError (Join-Path $LogDir "worker-error.log") `
    -WindowStyle Hidden

# Wait for service to be ready
$maxWait = 30
$waited = 0
$started = $false

while ($waited -lt $maxWait) {
    Start-Sleep -Seconds 1
    $waited += 1
    $conn = netstat -ano | Select-String ":$Port\s+.*LISTENING"
    if ($conn) {
        $started = $true
        break
    }
    Write-Host "." -NoNewline
}

Write-Host ""

if ($started) {
    Write-Host "LangGraph Biz Worker started on port $Port" -ForegroundColor Green
    Write-Host "Logs: $LogDir\worker.log" -ForegroundColor Gray
    Write-Host "Errors: $LogDir\worker-error.log" -ForegroundColor Gray
} else {
    Write-Host "LangGraph Biz Worker failed to start within $maxWait seconds" -ForegroundColor Red
    Write-Host "Check logs: $LogDir\worker-error.log" -ForegroundColor Yellow
    if (Test-Path (Join-Path $LogDir "worker-error.log")) {
        Get-Content (Join-Path $LogDir "worker-error.log") -Tail 20
    }
    exit 1
}
