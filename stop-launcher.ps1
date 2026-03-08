# Configuration
$BACKEND_PORT = 8112

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Stopping Coding Agent Launcher" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Find and kill process on port
$portConnection = Get-NetTCPConnection -LocalPort $BACKEND_PORT -State Listen -ErrorAction SilentlyContinue
if ($portConnection) {
    $procId = $portConnection.OwningProcess | Select-Object -First 1
    $process = Get-Process -Id $procId -ErrorAction SilentlyContinue
    Write-Host "  Found process on port ${BACKEND_PORT}: PID=${pid} ($($process.ProcessName))" -ForegroundColor Yellow
    Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2

    # Verify
    $check = Get-NetTCPConnection -LocalPort $BACKEND_PORT -State Listen -ErrorAction SilentlyContinue
    if ($check) {
        Write-Host "  Failed to stop process!" -ForegroundColor Red
        exit 1
    } else {
        Write-Host "  Process stopped successfully" -ForegroundColor Green
    }
} else {
    Write-Host "  No process listening on port $BACKEND_PORT" -ForegroundColor Gray
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host "  Done" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
Write-Host ""
