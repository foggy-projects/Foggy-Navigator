# Code Server (Web VS Code via WSL) - Start Script
# Usage: powershell -ExecutionPolicy Bypass -File start.ps1

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# Load config from .env
$EnvFile = Join-Path $ScriptDir ".env"
$Port = 8443
$Install = "/mnt/d/foggy-tools/code-server"
$Project = "/mnt/d/foggy-projects/Foggy-Navigator"
$DataDir = "/mnt/d/foggy-tools/code-server-data"

if (Test-Path $EnvFile) {
    Get-Content $EnvFile | ForEach-Object {
        if ($_ -match "^CODE_SERVER_PORT=(\d+)") { $Port = [int]$Matches[1] }
        if ($_ -match "^CODE_SERVER_INSTALL=(.+)") { $Install = $Matches[1].Trim() }
        if ($_ -match "^CODE_SERVER_PROJECT=(.+)") { $Project = $Matches[1].Trim() }
        if ($_ -match "^CODE_SERVER_DATA=(.+)") { $DataDir = $Matches[1].Trim() }
    }
}

# Convert config.yaml path to WSL
$ConfigWin = Join-Path $ScriptDir "config.yaml"
$driveLetter = $ConfigWin.Substring(0, 1).ToLower()
$rest = $ConfigWin.Substring(2) -replace '\\', '/'
$ConfigWSL = "/mnt/$driveLetter$rest"

Write-Host "=== Code Server (Web VS Code) ===" -ForegroundColor Cyan
Write-Host "Port:    $Port" -ForegroundColor Cyan
Write-Host "Project: $Project" -ForegroundColor Cyan

# Check if already running
$existingPid = (netstat -ano | Select-String ":$Port\s+.*LISTENING" | ForEach-Object {
    ($_ -split '\s+')[-1]
} | Select-Object -First 1)

if ($existingPid) {
    Write-Host "Code Server already running on port $Port (PID: $existingPid)" -ForegroundColor Yellow
    Write-Host "URL: http://localhost:$Port" -ForegroundColor Green
    exit 0
}

# Start code-server in WSL via setsid (survives shell exit)
Write-Host "Starting Code Server on port $Port..." -ForegroundColor Green
wsl -d Ubuntu -- bash -c "mkdir -p $DataDir; export XDG_DATA_HOME=$DataDir; setsid $Install/bin/code-server --config $ConfigWSL $Project > $DataDir/code-server.log 2>&1 &"

# Wait for startup
Start-Sleep -Seconds 3

$check = netstat -ano | Select-String ":$Port\s+.*LISTENING"
if ($check) {
    Write-Host ""
    Write-Host "Code Server started!" -ForegroundColor Green
    Write-Host "URL:      http://localhost:$Port" -ForegroundColor Green
    Write-Host "Password: foggy123 (see config.yaml)" -ForegroundColor Yellow
    Write-Host "Log:      wsl -d Ubuntu -- tail -f $DataDir/code-server.log" -ForegroundColor Gray
} else {
    Write-Host "Code Server may still be starting. Check:" -ForegroundColor Yellow
    Write-Host "  wsl -d Ubuntu -- cat $DataDir/code-server.log" -ForegroundColor Gray
}
