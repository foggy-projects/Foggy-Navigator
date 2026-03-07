# Code Server - Stop Script
# Usage: powershell -ExecutionPolicy Bypass -File stop.ps1

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# Load config from .env
$EnvFile = Join-Path $ScriptDir ".env"
$Port = 8443
$HttpsPort = 0
$Mode = "windows"

if (Test-Path $EnvFile) {
    Get-Content $EnvFile | ForEach-Object {
        if ($_ -match "^CODE_SERVER_PORT=(\d+)") { $Port = [int]$Matches[1] }
        if ($_ -match "^CODE_SERVER_HTTPS_PORT=(\d+)") { $HttpsPort = [int]$Matches[1] }
        if ($_ -match "^CODE_SERVER_MODE=(.+)") { $Mode = $Matches[1].Trim().ToLower() }
    }
}

$UseHttpsProxy = ($HttpsPort -gt 0)
$InternalPort = $Port + 100

Write-Host "Stopping Code Server on port $Port..." -ForegroundColor Yellow

# ---- Windows mode: use saved PID or find by port ----
if ($Mode -eq "windows") {
    $procIdFile = Join-Path $ScriptDir ".pid"
    $stopped = $false

    # Try saved PID first
    if (Test-Path $procIdFile) {
        $savedPid = (Get-Content $procIdFile).Trim()
        if ($savedPid -and (Get-Process -Id $savedPid -ErrorAction SilentlyContinue)) {
            Stop-Process -Id $savedPid -Force -ErrorAction SilentlyContinue
            $stopped = $true
        }
        Remove-Item $procIdFile -Force -ErrorAction SilentlyContinue
    }

    # Also kill any code-server processes on the port
    $procIds = netstat -ano 2>$null | Select-String ":$Port\s+.*LISTENING" | ForEach-Object {
        ($_ -split '\s+')[-1]
    } | Sort-Object -Unique

    foreach ($procId in $procIds) {
        if ($procId -and $procId -ne "0") {
            taskkill /F /PID $procId /T 2>$null | Out-Null
            $stopped = $true
        }
    }

    # Kill any remaining code-server node processes
    Get-Process -Name "node" -ErrorAction SilentlyContinue | Where-Object {
        $_.CommandLine -like "*code-server*"
    } | Stop-Process -Force -ErrorAction SilentlyContinue
}

# ---- WSL mode ----
elseif ($Mode -eq "wsl") {
    # Kill saved PID (the wsl.exe host process)
    $procIdFile = Join-Path $ScriptDir ".pid"
    if (Test-Path $procIdFile) {
        $savedPid = (Get-Content $procIdFile).Trim()
        if ($savedPid -and (Get-Process -Id $savedPid -ErrorAction SilentlyContinue)) {
            Stop-Process -Id $savedPid -Force -ErrorAction SilentlyContinue
        }
        Remove-Item $procIdFile -Force -ErrorAction SilentlyContinue
    }

    # Also kill code-server inside WSL
    wsl -d Ubuntu-24.04 -- bash -c "pkill -f 'code-server.*--config' 2>/dev/null; sleep 0.5; pkill -9 -f 'code-server' 2>/dev/null"

    # Stop nginx if HTTPS proxy was enabled
    if ($UseHttpsProxy) {
        Write-Host "Stopping nginx proxy..." -ForegroundColor Yellow
        wsl -d Ubuntu-24.04 -- bash -c "sudo nginx -s stop 2>/dev/null || true"
    }

    Start-Sleep -Seconds 1

    # Force kill by port if still alive (check both HTTP and internal ports)
    $portsToCheck = @($Port)
    if ($UseHttpsProxy) {
        $portsToCheck += @($HttpsPort, $InternalPort)
    }

    foreach ($checkPort in $portsToCheck) {
        $check = netstat -ano 2>$null | Select-String ":$checkPort\s+.*LISTENING"
        if ($check) {
            $procId = ($check -split '\s+')[-1]
            if ($procId -and $procId -ne "0") {
                taskkill /F /PID $procId /T 2>$null | Out-Null
            }
        }
    }
}

Start-Sleep -Seconds 1

# Verify
$check = netstat -ano 2>$null | Select-String ":$Port\s+.*LISTENING"
if ($check) {
    Write-Host "WARNING: Port $Port still in use." -ForegroundColor Yellow
} else {
    Write-Host "Code Server stopped." -ForegroundColor Green
    if ($UseHttpsProxy) {
        $httpsCheck = netstat -ano 2>$null | Select-String ":$HttpsPort\s+.*LISTENING"
        if ($httpsCheck) {
            Write-Host "WARNING: HTTPS port $HttpsPort still in use." -ForegroundColor Yellow
        } else {
            Write-Host "HTTPS proxy (nginx) stopped." -ForegroundColor Green
        }
    }
}
