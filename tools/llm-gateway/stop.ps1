# LLM Gateway 停止脚本
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $ScriptDir

try {
    Write-Host "[INFO] Stopping LLM Gateway..." -ForegroundColor Cyan
    docker compose down
    Write-Host "[INFO] LLM Gateway stopped." -ForegroundColor Green
} finally {
    Pop-Location
}
