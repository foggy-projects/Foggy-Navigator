# LLM Gateway 启动脚本
param(
    [switch]$Pull  # 加 -Pull 参数强制拉取最新镜像
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $ScriptDir

try {
    # 检查 .env 是否存在
    if (-not (Test-Path ".env")) {
        Write-Host "[INFO] .env not found, copying from .env.example..." -ForegroundColor Yellow
        Copy-Item ".env.example" ".env"
        Write-Host "[INFO] Please edit .env before first use." -ForegroundColor Yellow
    }

    # 创建 data 目录
    if (-not (Test-Path "data")) {
        New-Item -ItemType Directory -Path "data" | Out-Null
    }

    if ($Pull) {
        Write-Host "[INFO] Pulling latest image..." -ForegroundColor Cyan
        docker compose pull
    }

    Write-Host "[INFO] Starting LLM Gateway..." -ForegroundColor Cyan
    docker compose up -d

    Start-Sleep -Seconds 3

    # 健康检查
    $status = docker inspect --format='{{.State.Status}}' llm-gateway 2>$null
    if ($status -eq "running") {
        $port = (Select-String -Path ".env" -Pattern "^GATEWAY_PORT=(\d+)" | ForEach-Object { $_.Matches[0].Groups[1].Value })
        if (-not $port) { $port = "3000" }
        Write-Host ""
        Write-Host "========================================" -ForegroundColor Green
        Write-Host " LLM Gateway started successfully!" -ForegroundColor Green
        Write-Host " Admin UI:  http://localhost:${port}" -ForegroundColor Green
        Write-Host " Default:   root / 123456" -ForegroundColor Yellow
        Write-Host "========================================" -ForegroundColor Green
        Write-Host ""
    } else {
        Write-Host "[ERROR] Container not running. Check: docker logs llm-gateway" -ForegroundColor Red
    }
} finally {
    Pop-Location
}
