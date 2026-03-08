# Claude Agent Worker - Start Script
# Usage: powershell -ExecutionPolicy Bypass -File start.ps1

$ErrorActionPreference = "Stop"
$WorkerDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Port = 3031

# Load port from .env if present
$EnvFile = Join-Path $WorkerDir ".env"
if (Test-Path $EnvFile) {
    $portLine = Get-Content $EnvFile | Where-Object { $_ -match "^AGENT_WORKER_PORT=(\d+)" }
    if ($portLine -and $portLine -match "=(\d+)") {
        $Port = [int]$Matches[1]
    }
}

Write-Host "=== Claude Agent Worker ===" -ForegroundColor Cyan
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

# Install / sync dependencies from pyproject.toml before starting
Set-Location $WorkerDir
Write-Host "Syncing Python dependencies..." -ForegroundColor Cyan
pip install -e . -q
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
Write-Host "Starting Agent Worker on port $Port (background)..." -ForegroundColor Green

Start-Process python `
    -ArgumentList "-m", "uvicorn", "agent_worker.main:app", "--host", "0.0.0.0", "--port", "$Port" `
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
    Write-Host "Agent Worker started on port $Port" -ForegroundColor Green
    Write-Host "Logs: $LogDir\worker.log" -ForegroundColor Gray
    Write-Host "Errors: $LogDir\worker-error.log" -ForegroundColor Gray
} else {
    Write-Host "Agent Worker failed to start within $maxWait seconds" -ForegroundColor Red
    Write-Host "Check logs: $LogDir\worker-error.log" -ForegroundColor Yellow
    if (Test-Path (Join-Path $LogDir "worker-error.log")) {
        Get-Content (Join-Path $LogDir "worker-error.log") -Tail 20
    }
    exit 1
}
