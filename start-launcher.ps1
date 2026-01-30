# Configuration
$JAVA_PATH = "C:\Program Files\Java\jdk-17.0.1\bin\java.exe"
$BACKEND_PORT = 8112
$LOG_DIR = "logs"
$JAR_PATH = "launcher\target\launcher-1.0.0-SNAPSHOT.jar"

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Coding Agent Launcher" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Step 1: Kill old processes
Write-Host "[1/4] Stopping old Java processes..." -ForegroundColor Yellow
$javaProcesses = Get-Process java -ErrorAction SilentlyContinue
if ($javaProcesses) {
    $javaProcesses | Stop-Process -Force -ErrorAction SilentlyContinue
    Write-Host "  Stopped $($javaProcesses.Count) Java process(es)" -ForegroundColor Green
    Start-Sleep -Seconds 3
} else {
    Write-Host "  No Java processes running" -ForegroundColor Gray
}

# Check and kill process on port 8112
$portConnection = Get-NetTCPConnection -LocalPort $BACKEND_PORT -State Listen -ErrorAction SilentlyContinue
if ($portConnection) {
    Write-Host "  Port $BACKEND_PORT is in use, stopping..." -ForegroundColor Yellow
    Stop-Process -Id $portConnection.OwningProcess -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
}

Write-Host ""

# Step 2: Clean and build
Write-Host "[2/4] Building project (mvn clean package)..." -ForegroundColor Yellow
Write-Host "  This may take 30-60 seconds..." -ForegroundColor Gray

$buildOutput = & mvn clean package -pl launcher -am -DskipTests 2>&1

if ($LASTEXITCODE -eq 0) {
    Write-Host "  Build successful!" -ForegroundColor Green
} else {
    Write-Host "  Build failed!" -ForegroundColor Red
    Write-Host ""
    Write-Host "Build output:" -ForegroundColor Yellow
    Write-Host $buildOutput
    exit 1
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
    -ArgumentList '-jar', $JAR_PATH, '--spring.profiles.active=docker' `
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
