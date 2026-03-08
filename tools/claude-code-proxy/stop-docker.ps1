# Claude Code Proxy - Docker Stop Script
# Usage: powershell -ExecutionPolicy Bypass -File stop-docker.ps1

$ErrorActionPreference = "Stop"

Write-Host "=== Claude Code Proxy (Docker) ===" -ForegroundColor Cyan

$containerName = "claude-code-proxy"
$existingContainer = docker ps --filter "name=$containerName" --format "{{.Names}}"

if ($existingContainer) {
    Write-Host "Stopping container: $containerName" -ForegroundColor Yellow
    docker stop $containerName 2>$null
    docker rm $containerName 2>$null
    Write-Host "Container stopped and removed." -ForegroundColor Green
} else {
    Write-Host "No running container found: $containerName" -ForegroundColor Gray
}

# Also check for stopped containers
$stoppedContainer = docker ps -a --filter "name=$containerName" --format "{{.Names}}"
if ($stoppedContainer) {
    Write-Host "Removing stopped container: $containerName" -ForegroundColor Yellow
    docker rm $containerName 2>$null
}
