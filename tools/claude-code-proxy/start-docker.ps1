# Claude Code Proxy - Docker Start Script
# Usage: powershell -ExecutionPolicy Bypass -File start-docker.ps1

$ErrorActionPreference = "Stop"
$ProxyDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Port = 8082

# Load port from .env if present
$EnvFile = Join-Path $ProxyDir ".env"
if (Test-Path $EnvFile) {
    $portLine = Get-Content $EnvFile | Where-Object { $_ -match "^PORT=(\d+)" }
    if ($portLine -and $portLine -match "=(\d+)") {
        $Port = [int]$Matches[1]
    }
}

Write-Host "=== Claude Code Proxy (Docker) ===" -ForegroundColor Cyan
Write-Host "Port: $Port" -ForegroundColor Cyan

# Stop existing container
$existingContainer = docker ps --filter "name=claude-code-proxy" --format "{{.Names}}"
if ($existingContainer) {
    Write-Host "Stopping existing container..." -ForegroundColor Yellow
    docker stop claude-code-proxy 2>$null
    docker rm claude-code-proxy 2>$null
    Start-Sleep -Milliseconds 1000
}

# Ensure log directory exists on host
$LogDir = Join-Path $ProxyDir "logs"
if (-not (Test-Path $LogDir)) {
    New-Item -ItemType Directory -Path $LogDir | Out-Null
}

# Build and start container
Set-Location $ProxyDir
Write-Host "Building Docker image..." -ForegroundColor Green
docker build -t claude-code-proxy .

Write-Host "Starting container..." -ForegroundColor Green
docker run -d `
    --name claude-code-proxy `
    -p ${Port}:8082 `
    --env-file .env `
    -v "${LogDir}:/app/logs" `
    --restart unless-stopped `
    claude-code-proxy

Write-Host "Claude Code Proxy started on port $Port" -ForegroundColor Green
Write-Host "Container name: claude-code-proxy" -ForegroundColor Gray
Write-Host "Log file: $LogDir\proxy.log" -ForegroundColor Gray
Write-Host "View console logs: docker logs -f claude-code-proxy" -ForegroundColor Gray
