# Mock LLM 测试启动脚本
# 启动后端服务，使用 Mock LLM Service 进行测试

$JAVA_PATH = "C:\Program Files\Java\jdk-17.0.1\bin\java.exe"
$BACKEND_PORT = 8112
$LOG_DIR = "logs"
$JAR_PATH = "launcher\target\launcher-1.0.0-SNAPSHOT.jar"
$MOCK_LLM_PORT = 8200

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Mock LLM Testing Launcher" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Step 0: Check Mock LLM Service
Write-Host "[0/5] Checking Mock LLM Service..." -ForegroundColor Yellow
try {
    $health = Invoke-RestMethod -Uri "http://localhost:$MOCK_LLM_PORT/admin/health" -TimeoutSec 5 -ErrorAction Stop
    if ($health.status -eq "ok") {
        Write-Host "  Mock LLM Service: OK ($($health.rules_count) rules)" -ForegroundColor Green
    }
} catch {
    Write-Host "  Mock LLM Service not running!" -ForegroundColor Red
    Write-Host "  Please start it first: cd tools/mock-llm-service && .\start.ps1" -ForegroundColor Yellow
    exit 1
}

Write-Host ""

# Step 1: Kill old processes
Write-Host "[1/5] Stopping old Java processes..." -ForegroundColor Yellow
$javaProcesses = Get-Process java -ErrorAction SilentlyContinue
if ($javaProcesses) {
    $javaProcesses | Stop-Process -Force -ErrorAction SilentlyContinue
    Write-Host "  Stopped $($javaProcesses.Count) Java process(es)" -ForegroundColor Green
    Start-Sleep -Seconds 3
} else {
    Write-Host "  No Java processes running" -ForegroundColor Gray
}

$portConnection = Get-NetTCPConnection -LocalPort $BACKEND_PORT -State Listen -ErrorAction SilentlyContinue
if ($portConnection) {
    Write-Host "  Port $BACKEND_PORT is in use, stopping..." -ForegroundColor Yellow
    Stop-Process -Id $portConnection.OwningProcess -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
}

Write-Host ""

# Step 2: Build if JAR doesn't exist
Write-Host "[2/5] Checking JAR file..." -ForegroundColor Yellow
if (-not (Test-Path $JAR_PATH)) {
    Write-Host "  JAR not found, building..." -ForegroundColor Yellow
    $buildOutput = & mvn clean package -pl launcher -am -DskipTests 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  Build failed!" -ForegroundColor Red
        exit 1
    }
    Write-Host "  Build successful!" -ForegroundColor Green
} else {
    Write-Host "  JAR exists, skipping build" -ForegroundColor Gray
}

Write-Host ""

# Step 3: Create logs directory
if (-not (Test-Path $LOG_DIR)) {
    New-Item -ItemType Directory -Force -Path $LOG_DIR | Out-Null
}
Write-Host "[3/5] Logs directory ready" -ForegroundColor Yellow

Write-Host ""

# Step 4: Start the service with mock profile
Write-Host "[4/5] Starting backend with Mock LLM profile..." -ForegroundColor Yellow
Write-Host "  Profile: docker,mock" -ForegroundColor Gray
Write-Host "  Mock LLM: http://localhost:$MOCK_LLM_PORT/v1" -ForegroundColor Gray
Write-Host ""

Start-Process $JAVA_PATH `
    -ArgumentList '-jar', $JAR_PATH, '--spring.profiles.active=docker,mock' `
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
    Write-Host "  Mock Testing Environment Ready!" -ForegroundColor Green
    Write-Host "========================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "Backend: http://localhost:$BACKEND_PORT" -ForegroundColor Cyan
    Write-Host "Mock LLM: http://localhost:$MOCK_LLM_PORT" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "Run tests:" -ForegroundColor Cyan
    Write-Host "  cd session-module/integration-tests" -ForegroundColor Gray
    Write-Host "  npm test" -ForegroundColor Gray
    Write-Host ""
} else {
    Write-Host "  Service Startup Failed!" -ForegroundColor Red
    Write-Host "  Check logs\backend-error.log" -ForegroundColor Yellow
    exit 1
}
