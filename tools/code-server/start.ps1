# Code Server (Web VS Code) - Start Script
# Usage: powershell -ExecutionPolicy Bypass -File start.ps1
#
# Supports two modes:
#   1. Windows native (npm global install) - default
#   2. WSL (standalone binary)            - set CODE_SERVER_MODE=wsl in .env
#
# HTTPS support:
#   Set CODE_SERVER_HTTPS_PORT in .env to enable nginx reverse proxy with
#   both HTTP and HTTPS. If not set, code-server runs directly (HTTP only).

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# ---- Load config from .env ----
$EnvFile = Join-Path $ScriptDir ".env"
$Port = 8443
$HttpsPort = 0  # 0 = disabled
$Mode = "windows"  # windows | wsl
$ProjectWin = "D:\foggy-projects\Foggy-Navigator"
$ProjectWSL = "/mnt/d/foggy-projects/Foggy-Navigator"
$Install = "/mnt/d/foggy-tools/code-server"
$DataDir = "/mnt/d/foggy-tools/code-server-data"

if (Test-Path $EnvFile) {
    Get-Content $EnvFile | ForEach-Object {
        if ($_ -match "^CODE_SERVER_PORT=(\d+)") { $Port = [int]$Matches[1] }
        if ($_ -match "^CODE_SERVER_HTTPS_PORT=(\d+)") { $HttpsPort = [int]$Matches[1] }
        if ($_ -match "^CODE_SERVER_MODE=(.+)") { $Mode = $Matches[1].Trim().ToLower() }
        if ($_ -match "^CODE_SERVER_INSTALL=(.+)") { $Install = $Matches[1].Trim() }
        if ($_ -match "^CODE_SERVER_PROJECT=(.+)") {
            $ProjectWin = $Matches[1].Trim()
            # Convert Windows path to WSL path (D:\ -> /mnt/d/)
            if ($ProjectWin -match "^([A-Z]):\\(.*)$") {
                $driveLetter = $Matches[1].ToLower()
                $rest = $Matches[2] -replace '\\', '/'
                $ProjectWSL = "/mnt/$driveLetter/$rest"
            } else {
                # Already in WSL format
                $ProjectWSL = $ProjectWin
            }
        }
        if ($_ -match "^CODE_SERVER_DATA=(.+)") { $DataDir = $Matches[1].Trim() }
    }
}

# Config file (shared between modes)
$ConfigWin = Join-Path $ScriptDir "config.yaml"

# Internal port: code-server binds here when HTTPS is enabled
$InternalPort = $Port + 100

# Determine if HTTPS proxy mode is enabled
$UseHttpsProxy = ($HttpsPort -gt 0)

Write-Host "=== Code Server (Web VS Code) ===" -ForegroundColor Cyan
Write-Host "Mode:    $Mode" -ForegroundColor Cyan
Write-Host "Port:    $Port (HTTP)" -ForegroundColor Cyan
if ($UseHttpsProxy) {
    Write-Host "HTTPS:   $HttpsPort" -ForegroundColor Cyan
    Write-Host "Proxy:   nginx (code-server on internal port $InternalPort)" -ForegroundColor Cyan
}
Write-Host "Project: $ProjectWin" -ForegroundColor Cyan
Write-Host ""

# ---- Check if already running ----
$existingPid = (netstat -ano 2>$null | Select-String ":$Port\s+.*LISTENING" | ForEach-Object {
    ($_ -split '\s+')[-1]
} | Select-Object -First 1)

if ($existingPid) {
    Write-Host "Code Server already running on port $Port (PID: $existingPid)" -ForegroundColor Yellow
    Write-Host "HTTP:  http://localhost:$Port" -ForegroundColor Green
    if ($UseHttpsProxy) {
        Write-Host "HTTPS: https://localhost:$HttpsPort" -ForegroundColor Green
    }
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
"$codeServerBin" --config "$ConfigWin" --bind-addr 0.0.0.0:$Port "$ProjectWin" > "$LogFile" 2> "$ErrorLog"
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

    # Convert script dir to WSL path (for setup-https.sh)
    $scriptDriveLetter = $ScriptDir.Substring(0, 1).ToLower()
    $scriptRest = $ScriptDir.Substring(2) -replace '\\', '/'
    $ScriptDirWSL = "/mnt/$scriptDriveLetter$scriptRest"

    # Determine the port code-server actually listens on
    if ($UseHttpsProxy) {
        $codeServerPort = $InternalPort
    } else {
        $codeServerPort = $Port
    }

    Write-Host "Starting Code Server in WSL on port $codeServerPort..." -ForegroundColor Green

    # 使用 wsl.exe 保持会话，确保 code-server 持续运行
    # 创建一个保持活动的批处理文件
    $KeepAliveBat = Join-Path $ScriptDir "keep-alive-wsl.bat"
    @"
@echo off
title Code-Server-WSL
wsl -d Ubuntu-24.04 -- bash -c "export XDG_DATA_HOME=$DataDir; $Install/bin/code-server --config $ConfigWSL $ProjectWSL > $DataDir/code-server.log 2>&1"
"@ | Out-File -FilePath $KeepAliveBat -Encoding ascii

    # 启动保持活动的 WSL 会话（最小化窗口）
    $proc = Start-Process -FilePath "cmd.exe" `
        -ArgumentList "/c", "`"$KeepAliveBat`"" `
        -WindowStyle Minimized `
        -PassThru

    # Save PID for stop script
    $proc.Id | Out-File -FilePath (Join-Path $ScriptDir ".pid") -Encoding ascii -NoNewline

    # Wait for code-server to start (retry up to 10 seconds)
    $started = $false
    for ($i = 0; $i -lt 10; $i++) {
        Start-Sleep -Seconds 1
        # Check the internal port (code-server's actual listen port)
        $check = wsl -d Ubuntu-24.04 -- bash -c "ss -tln 2>/dev/null | grep -q ':$codeServerPort '" 2>$null
        if ($LASTEXITCODE -eq 0) { $started = $true; break }
    }

    if ($started) {
        Write-Host ""
        Write-Host "Code Server started! (PID: $($proc.Id))" -ForegroundColor Green

        # ---- Setup HTTPS proxy (nginx) if configured ----
        if ($UseHttpsProxy) {
            Write-Host ""
            Write-Host "Setting up HTTPS proxy (nginx)..." -ForegroundColor Yellow
            $setupResult = wsl -d Ubuntu-24.04 -- bash -c "bash '$ScriptDirWSL/setup-https.sh' --http-port $Port --https-port $HttpsPort --internal-port $InternalPort --data-dir '$DataDir'" 2>&1
            Write-Host $setupResult
        }

        # ---- Auto-setup port forwarding (WSL2 needs portproxy to expose ports to host) ----
        Write-Host ""
        Write-Host "Checking port forwarding..." -ForegroundColor Yellow
        $WSLIP = (wsl -d Ubuntu-24.04 -- bash -c "hostname -I" 2>$null) -split '\s+' | Select-Object -First 1

        if ($WSLIP) {
            # Ports to forward: always HTTP, plus HTTPS if enabled
            $portsToForward = @($Port)
            if ($UseHttpsProxy) {
                $portsToForward += $HttpsPort
            }

            foreach ($fwdPort in $portsToForward) {
                # Check if portproxy already points to the current WSL IP
                $existingRule = netsh interface portproxy show v4tov4 2>$null | Select-String "$fwdPort\s+$([regex]::Escape($WSLIP))\s+$fwdPort"

                if ($existingRule) {
                    Write-Host "  Port forwarding OK (0.0.0.0:$fwdPort -> ${WSLIP}:$fwdPort)" -ForegroundColor Green
                } else {
                    Write-Host "  Setting up port forwarding (0.0.0.0:$fwdPort -> ${WSLIP}:$fwdPort)..." -ForegroundColor Yellow
                    # Requires elevation - use Start-Process -Verb RunAs
                    $cmds = @(
                        "netsh interface portproxy delete v4tov4 listenport=$fwdPort listenaddress=0.0.0.0 2>`$null"
                        "netsh interface portproxy add v4tov4 listenport=$fwdPort listenaddress=0.0.0.0 connectport=$fwdPort connectaddress=$WSLIP"
                        # Firewall rule (idempotent: delete then add)
                        "netsh advfirewall firewall delete rule name=`\"WSL code-server $fwdPort`\" 2>`$null"
                        "netsh advfirewall firewall add rule name=`\"WSL code-server $fwdPort`\" dir=in action=allow protocol=TCP localport=$fwdPort"
                    ) -join "; "
                    Start-Process -FilePath "powershell" `
                        -ArgumentList "-Command", $cmds `
                        -Verb RunAs -Wait -WindowStyle Hidden -ErrorAction SilentlyContinue

                    # Verify
                    $verifyRule = netsh interface portproxy show v4tov4 2>$null | Select-String "$fwdPort"
                    if ($verifyRule) {
                        Write-Host "  Port forwarding configured!" -ForegroundColor Green
                    } else {
                        Write-Host "  WARNING: Port forwarding setup may have failed (UAC declined?)" -ForegroundColor Yellow
                        Write-Host "  Run as admin: powershell -File `"$ScriptDir\setup-portforward.ps1`"" -ForegroundColor Gray
                    }
                }
            }
        } else {
            Write-Host "  WARNING: Could not detect WSL IP. Port forwarding skipped." -ForegroundColor Yellow
        }

        Write-Host ""
        Write-Host "URL:      http://localhost:$Port" -ForegroundColor Green
        if ($UseHttpsProxy) {
            Write-Host "HTTPS:    https://localhost:$HttpsPort" -ForegroundColor Green
        }
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
