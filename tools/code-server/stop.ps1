# Code Server - Stop Script
# Usage: powershell -ExecutionPolicy Bypass -File stop.ps1

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# Load port from .env
$EnvFile = Join-Path $ScriptDir ".env"
$Port = 8443

if (Test-Path $EnvFile) {
    Get-Content $EnvFile | ForEach-Object {
        if ($_ -match "^CODE_SERVER_PORT=(\d+)") { $Port = [int]$Matches[1] }
    }
}

Write-Host "Stopping Code Server on port $Port..." -ForegroundColor Yellow

# Kill code-server processes in WSL
wsl -d Ubuntu -- bash -c "pkill -f 'code-server.*--config' 2>/dev/null; sleep 0.5; pkill -9 -f 'code-server' 2>/dev/null"

Start-Sleep -Seconds 1

$check = netstat -ano | Select-String ":$Port\s+.*LISTENING"
if ($check) {
    Write-Host "Port $Port still in use, force killing..." -ForegroundColor Yellow
    $pid = ($check -split '\s+')[-1]
    taskkill /F /PID $pid 2>$null
}

Write-Host "Code Server stopped." -ForegroundColor Green
