# Gemini Agent Worker stop script (Windows)

$ErrorActionPreference = "SilentlyContinue"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

$PORT = 3071
if (Test-Path ".env") {
    $portLine = Get-Content ".env" | Where-Object { $_ -match "^GEMINI_WORKER_PORT=" }
    if ($portLine) {
        $PORT = ($portLine -split "=", 2)[1].Trim()
    }
}

Write-Host "Stopping Gemini Worker on port $PORT..." -ForegroundColor Yellow

$pids = netstat -ano | Select-String ":$PORT\s" | ForEach-Object {
    ($_ -split "\s+")[-1]
} | Where-Object { $_ -ne "0" } | Sort-Object -Unique

if ($pids) {
    foreach ($procId in $pids) {
        Write-Host "  Killing PID=$procId" -ForegroundColor Yellow
        Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
    }
    Write-Host "Gemini Worker stopped." -ForegroundColor Green
}
else {
    Write-Host "No process found on port $PORT." -ForegroundColor Gray
}
