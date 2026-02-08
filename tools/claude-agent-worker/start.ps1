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

# Start the worker
Set-Location $WorkerDir
Write-Host "Starting Agent Worker on port $Port..." -ForegroundColor Green
python -m uvicorn agent_worker.main:app --host 0.0.0.0 --port $Port
