# Mock LLM 测试启动脚本
# 启动后端服务，使用 Mock LLM Service 进行测试

$BACKEND_PORT = 8112
$LOG_DIR = "logs"
$JAR_PATH = "launcher\target\launcher-1.0.0-SNAPSHOT.jar"
$MOCK_LLM_PORT = 8200
$ENV_FILE = "launcher\.env"

# Set defaults
$JAVA_PATH = ""

# Load environment variables from .env file if exists
if (Test-Path $ENV_FILE) {
    Write-Host "Loading configuration from $ENV_FILE" -ForegroundColor Gray
    Get-Content $ENV_FILE | ForEach-Object {
        if ($_ -notmatch '^#' -and $_ -match '^(.+?)=(.+)$') {
            $name = $matches[1]
            $value = $matches[2]
            Set-Item -Path "env:$name" -Value $value
        }
    }
    if ($env:JAVA_PATH) { $JAVA_PATH = $env:JAVA_PATH }
} else {
    Write-Host "Warning: $ENV_FILE not found, using defaults" -ForegroundColor Yellow
}

# Resolve Java path: env > system PATH
if (-not $JAVA_PATH) {
    $JAVA_PATH = (Get-Command java -ErrorAction SilentlyContinue).Source
}
if (-not $JAVA_PATH) {
    Write-Host "ERROR: Java not found." -ForegroundColor Red
    Write-Host "Please set JAVA_PATH in $ENV_FILE or ensure java is in your system PATH" -ForegroundColor Yellow
    exit 1
}
if (-not (Test-Path $JAVA_PATH)) {
    Write-Host "ERROR: Java not found at $JAVA_PATH" -ForegroundColor Red
    Write-Host "Please update JAVA_PATH in $ENV_FILE" -ForegroundColor Yellow
    exit 1
}

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

# Step 1: Stop existing service on port (only kill the specific process, not all Java)
Write-Host "[1/5] Checking port $BACKEND_PORT..." -ForegroundColor Yellow
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
