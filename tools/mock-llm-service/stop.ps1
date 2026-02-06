# Mock LLM Service 停止脚本 (Windows)

$ServiceName = "mock-llm-service"

Write-Host "[INFO] Stopping Mock LLM Service..." -ForegroundColor Yellow

$container = docker ps -aq -f "name=$ServiceName"
if ($container) {
    docker stop $ServiceName
    docker rm $ServiceName
    Write-Host "[OK] Service stopped" -ForegroundColor Green
} else {
    Write-Host "[INFO] Service is not running" -ForegroundColor Gray
}
