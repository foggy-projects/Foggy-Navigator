# Claude Agent Worker - Stop Script
# Usage: powershell -ExecutionPolicy Bypass -File stop.ps1

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

$pids = (netstat -ano | Select-String ":$Port\s+.*LISTENING" | ForEach-Object {
    ($_ -split '\s+')[-1]
} | Sort-Object -Unique)

if ($pids) {
    foreach ($p in $pids) {
        Write-Host "Stopping Agent Worker on port $Port (PID: $p)..." -ForegroundColor Yellow
        taskkill /F /PID $p 2>$null
    }
    Write-Host "Agent Worker stopped." -ForegroundColor Green
} else {
    Write-Host "No Agent Worker running on port $Port." -ForegroundColor Gray
}
