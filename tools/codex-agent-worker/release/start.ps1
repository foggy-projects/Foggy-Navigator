# Codex Agent Worker start script (Windows)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

$PORT = 3051
if (Test-Path ".env") {
    $portLine = Get-Content ".env" | Where-Object { $_ -match "^CODEX_WORKER_PORT=" }
    if ($portLine) {
        $PORT = ($portLine -split "=", 2)[1].Trim()
    }
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Codex Agent Worker" -ForegroundColor Cyan
Write-Host "  Port: $PORT" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

Write-Host "`n[1/4] Checking existing processes on port $PORT..." -ForegroundColor Yellow
$existingPids = netstat -ano | Select-String ":$PORT\s" | ForEach-Object {
    ($_ -split "\s+")[-1]
} | Where-Object { $_ -ne "0" } | Sort-Object -Unique

if ($existingPids) {
    foreach ($procId in $existingPids) {
        Write-Host "  Killing existing process PID=$procId" -ForegroundColor Yellow
        try { Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue } catch {}
    }
    Start-Sleep -Seconds 2
}

Write-Host "`n[2/4] Checking dependencies..." -ForegroundColor Yellow
if (-not (Test-Path "node_modules")) {
    Write-Host "  Running npm ci --omit=dev..." -ForegroundColor Yellow
    npm ci --omit=dev 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  npm ci failed!" -ForegroundColor Red
        exit 1
    }
    Write-Host "  Dependencies installed." -ForegroundColor Green
}
else {
    Write-Host "  node_modules exists, skipping install." -ForegroundColor Green
}

if (-not (Test-Path "logs")) {
    New-Item -ItemType Directory -Path "logs" | Out-Null
}

Write-Host "`n[3/4] Starting Codex Worker..." -ForegroundColor Yellow
$logFile = "logs/worker.log"
$errFile = "logs/worker-error.log"

$process = Start-Process -FilePath "node" -ArgumentList "dist/index.js" `
    -WorkingDirectory $ScriptDir `
    -RedirectStandardOutput $logFile `
    -RedirectStandardError $errFile `
    -PassThru -WindowStyle Hidden

Write-Host "  PID: $($process.Id)" -ForegroundColor Green

Write-Host "`n[4/4] Waiting for worker to be ready..." -ForegroundColor Yellow
$maxWait = 30
$waited = 0
$ready = $false

while ($waited -lt $maxWait) {
    Start-Sleep -Seconds 1
    $waited++

    $listening = netstat -ano | Select-String ":$PORT\s.*LISTENING"
    if ($listening) {
        $ready = $true
        break
    }

    if ($process.HasExited) {
        Write-Host "`n  Worker process exited unexpectedly!" -ForegroundColor Red
        if (Test-Path $errFile) {
            Write-Host "`n  Error log:" -ForegroundColor Red
            Get-Content $errFile | Select-Object -Last 20 | ForEach-Object { Write-Host "    $_" -ForegroundColor Red }
        }
        exit 1
    }

    Write-Host "  Waiting... ($waited/$maxWait)" -ForegroundColor Gray
}

if ($ready) {
    Write-Host "`n========================================" -ForegroundColor Green
    Write-Host "  Codex Worker is READY!" -ForegroundColor Green
    Write-Host "  URL: http://localhost:$PORT" -ForegroundColor Green
    Write-Host "  Health: http://localhost:$PORT/health" -ForegroundColor Green
    Write-Host "  PID: $($process.Id)" -ForegroundColor Green
    Write-Host "========================================" -ForegroundColor Green
}
else {
    Write-Host "`n  Worker failed to start within ${maxWait}s!" -ForegroundColor Red
    if (Test-Path $errFile) {
        Write-Host "`n  Error log:" -ForegroundColor Red
        Get-Content $errFile | Select-Object -Last 20 | ForEach-Object { Write-Host "    $_" -ForegroundColor Red }
    }
    exit 1
}
