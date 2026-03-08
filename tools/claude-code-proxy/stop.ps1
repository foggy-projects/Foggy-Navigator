# Claude Code Proxy - Stop Script
# Usage: powershell -ExecutionPolicy Bypass -File stop.ps1

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

$pids = (netstat -ano | Select-String ":$Port\s+.*LISTENING" | ForEach-Object {
    ($_ -split '\s+')[-1]
} | Sort-Object -Unique)

if ($pids) {
    foreach ($pid in $pids) {
        Write-Host "Stopping Claude Code Proxy on port $Port (PID: $pid)..." -ForegroundColor Yellow
        taskkill /F /PID $pid 2>$null
    }
    Write-Host "Claude Code Proxy stopped." -ForegroundColor Green
} else {
    Write-Host "No Claude Code Proxy running on port $Port." -ForegroundColor Gray
}
