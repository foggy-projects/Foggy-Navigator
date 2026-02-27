# Claude Code Proxy - Start Script
# Usage: powershell -ExecutionPolicy Bypass -File start.ps1

$ErrorActionPreference = "Stop"
$ProxyDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Port = 8082

# Load port from .env if present
$EnvFile = Join-Path $ProxyDir ".env"
if (Test-Path $EnvFile) {
    $portLine = Get-Content $EnvFile | Where-Object { $_ -match "^PORT=(\d+)" }
    if ($portLine -and $portLine -match "=(\d+)") {
        $Port = [int]$Matches[1]
    }
}

Write-Host "=== Claude Code Proxy ===" -ForegroundColor Cyan
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

# Ensure log directory exists
$LogDir = Join-Path $ProxyDir "logs"
if (-not (Test-Path $LogDir)) {
    New-Item -ItemType Directory -Path $LogDir | Out-Null
}

# Start the proxy
Set-Location $ProxyDir
$env:PYTHONPATH = Join-Path $ProxyDir "src"
Write-Host "Starting Claude Code Proxy on port $Port..." -ForegroundColor Green
Write-Host "Log file: $LogDir\proxy.log" -ForegroundColor Gray
python start_proxy.py
