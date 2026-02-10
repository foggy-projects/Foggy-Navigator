# Navigator Frontend - Start Script
# Usage: powershell -ExecutionPolicy Bypass -File start-frontend.ps1

$FRONTEND_PORT = 5174
$FRONTEND_DIR = "packages\navigator-frontend"

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Navigator Frontend" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Check if port is already in use
$portConnection = Get-NetTCPConnection -LocalPort $FRONTEND_PORT -State Listen -ErrorAction SilentlyContinue
if ($portConnection) {
    $procId = $portConnection.OwningProcess | Select-Object -First 1
    $process = Get-Process -Id $procId -ErrorAction SilentlyContinue
    Write-Host "  Port $FRONTEND_PORT already in use by $($process.ProcessName) (PID=$procId)" -ForegroundColor Yellow
    Write-Host "  Frontend may already be running: http://localhost:$FRONTEND_PORT" -ForegroundColor Gray
    Write-Host ""
    $confirm = Read-Host "  Stop and restart? (y/N)"
    if ($confirm -ne 'y') {
        Write-Host "  Aborted." -ForegroundColor Gray
        exit 0
    }
    Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
}

# Check pnpm
if (-not (Get-Command pnpm -ErrorAction SilentlyContinue)) {
    Write-Host "  pnpm not found! Install: npm install -g pnpm" -ForegroundColor Red
    exit 1
}

# Install dependencies if needed
if (-not (Test-Path "$FRONTEND_DIR\node_modules")) {
    Write-Host "[1/2] Installing dependencies..." -ForegroundColor Yellow
    Set-Location $FRONTEND_DIR
    pnpm install
    Set-Location $PSScriptRoot
} else {
    Write-Host "[1/2] Dependencies ready" -ForegroundColor Gray
}

Write-Host "[2/2] Starting dev server..." -ForegroundColor Yellow
Write-Host ""
Write-Host "  URL: http://localhost:$FRONTEND_PORT" -ForegroundColor Cyan
Write-Host "  Login: root / root123" -ForegroundColor Cyan
Write-Host ""

Set-Location "$PSScriptRoot\$FRONTEND_DIR"
pnpm dev
