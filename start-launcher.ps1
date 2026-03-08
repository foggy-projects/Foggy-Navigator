param(
    [switch]$SkipBuild
)

# Configuration
$BACKEND_PORT = 8112
$LOG_DIR = "logs"
$JAR_PATH = "launcher\target\launcher-1.0.0-SNAPSHOT.jar"
$ENV_FILE = "launcher\.env"

# Set defaults
$JAVA_PATH = ""
$ROOT_USERNAME = "root"
$ROOT_PASSWORD = "root123"
$ROOT_EMAIL = "root@foggy.local"
$SPRING_PROFILES_ACTIVE = "docker"

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

    # Override with env values if set
    if ($env:JAVA_PATH) { $JAVA_PATH = $env:JAVA_PATH }
    if ($env:ROOT_USERNAME) { $ROOT_USERNAME = $env:ROOT_USERNAME }
    if ($env:ROOT_PASSWORD) { $ROOT_PASSWORD = $env:ROOT_PASSWORD }
    if ($env:ROOT_EMAIL) { $ROOT_EMAIL = $env:ROOT_EMAIL }
    if ($env:SPRING_PROFILES_ACTIVE) { $SPRING_PROFILES_ACTIVE = $env:SPRING_PROFILES_ACTIVE }
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

# Step 2: Build (clean build to avoid stale compilation cache issues)
if ($SkipBuild) {
    if (-not (Test-Path $JAR_PATH)) {
        Write-Host "[2/4] JAR not found, building anyway..." -ForegroundColor Yellow
        $SkipBuild = $false
    } else {
        Write-Host "[2/4] Build skipped (-SkipBuild)" -ForegroundColor Gray
    }
}

if (-not $SkipBuild) {
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
Write-Host "  Profile: $SPRING_PROFILES_ACTIVE" -ForegroundColor Gray
Write-Host "  Port: $BACKEND_PORT" -ForegroundColor Gray
Write-Host "  Root User: $ROOT_USERNAME" -ForegroundColor Gray
Write-Host ""

# JVM tuning: 4-8G heap, G1GC, better throughput
$JAVA_OPTS = @(
    '-Xms4g', '-Xmx8g',
    '-XX:+UseG1GC',
    '-XX:MaxGCPauseMillis=200',
    '-XX:+ParallelRefProcEnabled',
    '-XX:+HeapDumpOnOutOfMemoryError',
    "-XX:HeapDumpPath=logs\heap-dump.hprof"
)

Start-Process $JAVA_PATH `
    -ArgumentList ($JAVA_OPTS + @(
        '-Dfile.encoding=UTF-8',
        "-Dsystem.root.username=$ROOT_USERNAME",
        "-Dsystem.root.password=$ROOT_PASSWORD",
        "-Dsystem.root.email=$ROOT_EMAIL",
        '-jar', $JAR_PATH,
        "--spring.profiles.active=$SPRING_PROFILES_ACTIVE"
    )) `
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
