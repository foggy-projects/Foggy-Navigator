# Codex Agent Worker 停止脚本
# 用法: powershell -ExecutionPolicy Bypass -File stop.ps1

$ErrorActionPreference = "SilentlyContinue"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

# 读取端口
$PORT = 3032
if (Test-Path ".env") {
    $envContent = Get-Content ".env" | Where-Object { $_ -match "^CODEX_WORKER_PORT=" }
    if ($envContent) {
        $PORT = ($envContent -split "=", 2)[1].Trim()
    }
}

Write-Host "Stopping Codex Worker on port $PORT..." -ForegroundColor Yellow

$pids = netstat -ano | Select-String ":$PORT\s" | ForEach-Object {
    ($_ -split "\s+")[-1]
} | Where-Object { $_ -ne "0" } | Sort-Object -Unique

if ($pids) {
    foreach ($pid in $pids) {
        Write-Host "  Killing PID=$pid" -ForegroundColor Yellow
        Stop-Process -Id $pid -Force -ErrorAction SilentlyContinue
    }
    Write-Host "Codex Worker stopped." -ForegroundColor Green
} else {
    Write-Host "No process found on port $PORT." -ForegroundColor Gray
}
