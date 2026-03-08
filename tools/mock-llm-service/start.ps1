# Mock LLM Service 启动脚本 (Windows)
# 自动构建并启动 Docker 容器

param(
    [switch]$Rebuild,      # 强制重新构建镜像
    [switch]$Detach = $true # 后台运行（默认）
)

$ErrorActionPreference = "Stop"
$ServiceName = "mock-llm-service"
$ImageName = "foggy/mock-llm-service:latest"
$Port = 8200

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Mock LLM Service Launcher" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# 进入脚本所在目录
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

# 检查 Docker
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Host "[ERROR] Docker not found. Please install Docker Desktop." -ForegroundColor Red
    exit 1
}

# 停止已存在的容器
$existingContainer = docker ps -aq -f "name=$ServiceName"
if ($existingContainer) {
    Write-Host "[INFO] Stopping existing container..." -ForegroundColor Yellow
    docker stop $ServiceName 2>$null
    docker rm $ServiceName 2>$null
}

# 构建镜像
$needBuild = $Rebuild -or (-not (docker images -q $ImageName))
if ($needBuild) {
    Write-Host "[INFO] Building Docker image..." -ForegroundColor Yellow
    docker build -t $ImageName .
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[ERROR] Docker build failed!" -ForegroundColor Red
        exit 1
    }
    Write-Host "[OK] Image built successfully" -ForegroundColor Green
}

# 启动容器
Write-Host "[INFO] Starting container..." -ForegroundColor Yellow

$dockerArgs = @(
    "run"
    "--name", $ServiceName
    "-p", "${Port}:8200"
    "-v", "${ScriptDir}/responses:/app/responses:ro"
    "-e", "MOCK_LLM_LOG_LEVEL=INFO"
)

if ($Detach) {
    $dockerArgs += "-d"
}

$dockerArgs += $ImageName

docker @dockerArgs

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Failed to start container!" -ForegroundColor Red
    exit 1
}

# 等待服务就绪
Write-Host "[INFO] Waiting for service to be ready..." -ForegroundColor Yellow
$maxRetries = 30
$retries = 0
while ($retries -lt $maxRetries) {
    try {
        $response = Invoke-WebRequest -Uri "http://localhost:$Port/admin/health" -UseBasicParsing -TimeoutSec 2
        if ($response.StatusCode -eq 200) {
            break
        }
    } catch {
        # 继续等待
    }
    Start-Sleep -Seconds 1
    $retries++
}

if ($retries -eq $maxRetries) {
    Write-Host "[ERROR] Service failed to start!" -ForegroundColor Red
    docker logs $ServiceName
    exit 1
}

# 显示信息
Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host "  Mock LLM Service Started!" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
Write-Host ""
Write-Host "  API Endpoint:  http://localhost:$Port/v1/chat/completions" -ForegroundColor White
Write-Host "  Admin API:     http://localhost:$Port/admin/responses" -ForegroundColor White
Write-Host "  Health Check:  http://localhost:$Port/admin/health" -ForegroundColor White
Write-Host ""
Write-Host "  Stop command:  .\stop.ps1" -ForegroundColor Gray
Write-Host ""

# 显示加载的规则数
$health = Invoke-RestMethod -Uri "http://localhost:$Port/admin/health"
Write-Host "[INFO] Loaded $($health.rules_count) response rules" -ForegroundColor Cyan
