param(
    [switch]$SkipBuild
)

# Configuration
$JAVA_PATH = "C:\Program Files\Java\jdk-17.0.1\bin\java.exe"
$BACKEND_PORT = 8112
$LOG_DIR = "logs"
$JAR_PATH = "launcher\target\launcher-1.0.0-SNAPSHOT.jar"

# Check if Java exists
if (-not (Test-Path $JAVA_PATH)) {
    Write-Host "ERROR: Java not found at $JAVA_PATH" -ForegroundColor Red
    Write-Host "Please update JAVA_PATH in this script" -ForegroundColor Yellow
    exit 1
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Coding Agent Launcher" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Step 1: Stop existing service on port (only kill the specific process, not all Java)
Write-Host "[1/4] Checking port $BACKEND_PORT..." -ForegroundColor Yellow
$portConnection = Get-NetTCPConnection -LocalPort $BACKEND_PORT -State Listen -ErrorAction SilentlyContinue
if ($portConnection) {
    $procId = $portConnection.OwningProcess | Select-Object -First 1
    $process = Get-Process -Id $procId -ErrorAction SilentlyContinue
    Write-Host "  Port $BACKEND_PORT in use by $($process.ProcessName) (PID=$procId), stopping..." -ForegroundColor Yellow
    Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 3
} else {
    Write-Host "  Port $BACKEND_PORT is free" -ForegroundColor Gray
}

Write-Host ""

# Step 2: Build (skip clean to avoid locked jar issues)
if ($SkipBuild) {
    if (-not (Test-Path $JAR_PATH)) {
        Write-Host "[2/4] JAR not found, building anyway..." -ForegroundColor Yellow
        $SkipBuild = $false
    } else {
        Write-Host "[2/4] Build skipped (-SkipBuild)" -ForegroundColor Gray
    }
}

if (-not $SkipBuild) {
    Write-Host "[2/4] Building project (mvn package)..." -ForegroundColor Yellow
    Write-Host "  This may take 30-60 seconds..." -ForegroundColor Gray

    $buildOutput = & mvn package -pl launcher -am -DskipTests 2>&1

    if ($LASTEXITCODE -eq 0) {
        Write-Host "  Build successful!" -ForegroundColor Green
    } else {
        Write-Host "  Build failed!" -ForegroundColor Red
        Write-Host ""
        Write-Host "Build output:" -ForegroundColor Yellow
        Write-Host $buildOutput
        exit 1
    }
}

Write-Host ""

# Step 3: Create logs directory
if (-not (Test-Path $LOG_DIR)) {
    New-Item -ItemType Directory -Force -Path $LOG_DIR | Out-Null
    Write-Host "[3/4] Created logs directory" -ForegroundColor Yellow
} else {
    Write-Host "[3/4] Logs directory ready" -ForegroundColor Yellow
}

Write-Host ""

# Step 4: Start the service
Write-Host "[4/4] Starting backend service..." -ForegroundColor Yellow
Write-Host "  Java: $JAVA_PATH" -ForegroundColor Gray
Write-Host "  JAR: $JAR_PATH" -ForegroundColor Gray
Write-Host "  Profile: docker" -ForegroundColor Gray
Write-Host "  Port: $BACKEND_PORT" -ForegroundColor Gray
Write-Host ""

Start-Process $JAVA_PATH `
    -ArgumentList '-Dfile.encoding=UTF-8', '-jar', $JAR_PATH, '--spring.profiles.active=docker' `
    -RedirectStandardOutput "logs\backend.log" `
    -RedirectStandardError "logs\backend-error.log" `
    -NoNewWindow

Write-Host "  Waiting for service to be ready..." -ForegroundColor Gray

# Wait for service to start
$maxWait = 60
$waited = 0
$started = $false

while ($waited -lt $maxWait) {
    Start-Sleep -Seconds 2
    $waited += 2

    $connection = Get-NetTCPConnection -LocalPort $BACKEND_PORT -State Listen -ErrorAction SilentlyContinue
    if ($connection) {
        $started = $true
        break
    }

    Write-Host "." -NoNewline
}

Write-Host ""
Write-Host ""

if ($started) {
    Write-Host "========================================" -ForegroundColor Green
    Write-Host "  Service Started Successfully!" -ForegroundColor Green
    Write-Host "========================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "Service URL: http://localhost:$BACKEND_PORT" -ForegroundColor Cyan
    Write-Host "Health Check: http://localhost:$BACKEND_PORT/actuator/health" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "Logs:" -ForegroundColor Cyan
    Write-Host "  - Output: logs\backend.log" -ForegroundColor Gray
    Write-Host "  - Errors: logs\backend-error.log" -ForegroundColor Gray
    Write-Host ""

    # Test health endpoint
    Start-Sleep -Seconds 5
    try {
        $health = Invoke-RestMethod -Uri "http://localhost:$BACKEND_PORT/actuator/health" -TimeoutSec 5 -ErrorAction SilentlyContinue
        if ($health.status -eq "UP") {
            Write-Host "Health Check: " -NoNewline -ForegroundColor Cyan
            Write-Host "UP" -ForegroundColor Green
        }
    } catch {
        Write-Host "Health Check: " -NoNewline -ForegroundColor Cyan
        Write-Host "Checking..." -ForegroundColor Yellow
    }

    Write-Host ""
    Write-Host "========================================" -ForegroundColor Green

} else {
    Write-Host "========================================" -ForegroundColor Red
    Write-Host "  Service Startup Failed!" -ForegroundColor Red
    Write-Host "========================================" -ForegroundColor Red
    Write-Host ""
    Write-Host "Timeout after $maxWait seconds" -ForegroundColor Red
    Write-Host ""
    Write-Host "Check logs for details:" -ForegroundColor Yellow
    Write-Host "  logs\backend.log" -ForegroundColor Gray
    Write-Host "  logs\backend-error.log" -ForegroundColor Gray
    Write-Host ""

    if (Test-Path "logs\backend-error.log") {
        Write-Host "Last 20 lines of error log:" -ForegroundColor Yellow
        Get-Content "logs\backend-error.log" -Tail 20
    }

    exit 1
}
