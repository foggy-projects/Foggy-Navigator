# Code Server (Web VS Code) - Start Script
# Usage: powershell -ExecutionPolicy Bypass -File start.ps1
#
# Supports two modes:
#   1. Windows native (npm global install) - default
#   2. WSL (standalone binary)            - set CODE_SERVER_MODE=wsl in .env

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# ---- Load config from .env ----
$EnvFile = Join-Path $ScriptDir ".env"
$Port = 8443
$Mode = "windows"  # windows | wsl
$Install = "/mnt/d/foggy-tools/code-server"
$Project = "D:\foggy-projects\Foggy-Navigator"
$ProjectWSL = "/mnt/d/foggy-projects/Foggy-Navigator"
$DataDir = "/mnt/d/foggy-tools/code-server-data"

if (Test-Path $EnvFile) {
    Get-Content $EnvFile | ForEach-Object {
        if ($_ -match "^CODE_SERVER_PORT=(\d+)") { $Port = [int]$Matches[1] }
        if ($_ -match "^CODE_SERVER_MODE=(.+)") { $Mode = $Matches[1].Trim().ToLower() }
        if ($_ -match "^CODE_SERVER_INSTALL=(.+)") { $Install = $Matches[1].Trim() }
        if ($_ -match "^CODE_SERVER_PROJECT=(.+)") { $Project = $Matches[1].Trim() }
        if ($_ -match "^CODE_SERVER_DATA=(.+)") { $DataDir = $Matches[1].Trim() }
    }
}

# Config file (shared between modes)
$ConfigWin = Join-Path $ScriptDir "config.yaml"

Write-Host "=== Code Server (Web VS Code) ===" -ForegroundColor Cyan
Write-Host "Mode:    $Mode" -ForegroundColor Cyan
Write-Host "Port:    $Port" -ForegroundColor Cyan
Write-Host "Project: $Project" -ForegroundColor Cyan
Write-Host ""

# ---- Check if already running ----
$existingPid = (netstat -ano 2>$null | Select-String ":$Port\s+.*LISTENING" | ForEach-Object {
    ($_ -split '\s+')[-1]
} | Select-Object -First 1)

if ($existingPid) {
    Write-Host "Code Server already running on port $Port (PID: $existingPid)" -ForegroundColor Yellow
    Write-Host "URL: http://localhost:$Port" -ForegroundColor Green
    exit 0
}

# ============================================================
# Mode: Windows native (npm global install)
# ============================================================
if ($Mode -eq "windows") {
    # Find code-server.cmd
    $codeServerCmd = Get-Command code-server.cmd -ErrorAction SilentlyContinue
    if (-not $codeServerCmd) {
        $npmGlobal = npm root -g 2>$null
        $codeServerPath = Join-Path $npmGlobal "..\code-server.cmd"
        if (Test-Path $codeServerPath) {
            $codeServerCmd = Get-Item $codeServerPath
        } else {
            Write-Host "ERROR: code-server not found. Run install.ps1 first." -ForegroundColor Red
            exit 1
        }
    }
    $codeServerBin = $codeServerCmd.Source

    # Ensure log directory
    $LogDir = Join-Path $ScriptDir "logs"
    if (-not (Test-Path $LogDir)) {
        New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
    }
    $LogFile = Join-Path $LogDir "code-server.log"

    Write-Host "Starting Code Server on port $Port..." -ForegroundColor Green

    # Create a launcher batch file to handle logging and the .cmd invocation
    $LauncherBat = Join-Path $LogDir "launcher.bat"
    $ErrorLog = Join-Path $LogDir "code-server-error.log"
    @"
@echo off
"$codeServerBin" --config "$ConfigWin" --bind-addr 0.0.0.0:$Port "$Project" > "$LogFile" 2> "$ErrorLog"
"@ | Out-File -FilePath $LauncherBat -Encoding ascii

    # Start the launcher batch file as a hidden background process
    $proc = Start-Process -FilePath "cmd.exe" `
        -ArgumentList "/c", "`"$LauncherBat`"" `
        -WindowStyle Hidden `
        -PassThru

    # Save PID for stop script
    $proc.Id | Out-File -FilePath (Join-Path $ScriptDir ".pid") -Encoding ascii -NoNewline

    Start-Sleep -Seconds 3

    $check = netstat -ano 2>$null | Select-String ":$Port\s+.*LISTENING"
    if ($check) {
        Write-Host ""
        Write-Host "Code Server started! (PID: $($proc.Id))" -ForegroundColor Green
        Write-Host "URL:      http://localhost:$Port" -ForegroundColor Green
        Write-Host "Password: see config.yaml" -ForegroundColor Yellow
        Write-Host "Log:      $LogFile" -ForegroundColor Gray
    } else {
        Write-Host "Code Server may still be starting. Check:" -ForegroundColor Yellow
        Write-Host "  Get-Content $LogFile" -ForegroundColor Gray
    }
}

# ============================================================
# Mode: WSL (standalone binary)
# ============================================================
elseif ($Mode -eq "wsl") {
    # Convert config.yaml path to WSL
    $driveLetter = $ConfigWin.Substring(0, 1).ToLower()
    $rest = $ConfigWin.Substring(2) -replace '\\', '/'
    $ConfigWSL = "/mnt/$driveLetter$rest"

    Write-Host "Starting Code Server in WSL on port $Port..." -ForegroundColor Green

    # Use Start-Process to keep WSL session alive (WSL2 kills background processes when session exits)
    $proc = Start-Process -WindowStyle Hidden -FilePath "wsl" `
        -ArgumentList "-d", "Ubuntu-24.04", "--", "bash", "-c", `
            "mkdir -p $DataDir; export XDG_DATA_HOME=$DataDir; $Install/bin/code-server --config $ConfigWSL $ProjectWSL > $DataDir/code-server.log 2>&1" `
        -PassThru

    # Save PID for stop script
    $proc.Id | Out-File -FilePath (Join-Path $ScriptDir ".pid") -Encoding ascii -NoNewline

    # Wait for code-server to start (retry up to 10 seconds)
    $started = $false
    for ($i = 0; $i -lt 10; $i++) {
        Start-Sleep -Seconds 1
        $check = netstat -ano 2>$null | Select-String ":$Port\s+.*LISTENING"
        if ($check) { $started = $true; break }
    }

    if ($started) {
        Write-Host ""
        Write-Host "Code Server started! (PID: $($proc.Id))" -ForegroundColor Green
        Write-Host "URL:      http://localhost:$Port" -ForegroundColor Green
        Write-Host "Password: foggy123 (see config.yaml)" -ForegroundColor Yellow
        Write-Host ""
        Write-Host "Log:  wsl -d Ubuntu-24.04 -- tail -f $DataDir/code-server.log" -ForegroundColor Gray
        Write-Host "Stop: powershell -ExecutionPolicy Bypass -File `"$ScriptDir\stop.ps1`"" -ForegroundColor Gray
    } else {
        Write-Host "Code Server may still be starting. Check:" -ForegroundColor Yellow
        Write-Host "  wsl -d Ubuntu-24.04 -- cat $DataDir/code-server.log" -ForegroundColor Gray
    }
}
else {
    Write-Host "ERROR: Unknown mode '$Mode'. Use 'windows' or 'wsl'." -ForegroundColor Red
    exit 1
}
