# Claude Agent Worker - Task Scheduler runner
# Runs the worker in the foreground so Task Scheduler can supervise it.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File run-scheduled-task.ps1

$ErrorActionPreference = "Stop"
$WorkerDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Port = 3031
$LogDir = Join-Path $WorkerDir "logs"
$BootstrapOut = Join-Path $LogDir "scheduled-task-stdout.log"
$BootstrapErr = Join-Path $LogDir "scheduled-task-stderr.log"
$VenvDir = Join-Path $WorkerDir ".venv"
$VenvPython = Join-Path $VenvDir "Scripts\python.exe"
$VenvPip = Join-Path $VenvDir "Scripts\pip.exe"

function Write-BootstrapLog {
    param(
        [string]$Message,
        [string]$Level = "INFO"
    )

    if (-not (Test-Path $LogDir)) {
        New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
    }

    $line = "{0} [{1}] {2}" -f ([DateTime]::Now.ToString("yyyy-MM-dd HH:mm:ss")), $Level, $Message
    Add-Content -Path $BootstrapOut -Value $line -Encoding UTF8
    Write-Host $line
}

# Load port from .env if present
$EnvFile = Join-Path $WorkerDir ".env"
if (Test-Path $EnvFile) {
    $portLine = Get-Content $EnvFile | Where-Object { $_ -match "^AGENT_WORKER_PORT=(\d+)" }
    if ($portLine -and $portLine -match "=(\d+)") {
        $Port = [int]$Matches[1]
    }
}

Write-BootstrapLog "Task Scheduler runner starting for worker dir: $WorkerDir"
Write-BootstrapLog "Configured port: $Port"

# Avoid duplicate instances if the task is triggered again while the worker is already alive.
$existingPid = (netstat -ano 2>$null | Select-String ":$Port\s+.*LISTENING" | ForEach-Object {
    ($_ -split '\s+')[-1]
} | Select-Object -First 1)

if ($existingPid) {
    Write-BootstrapLog "Port $Port is already listening (PID: $existingPid). Exiting without starting a duplicate."
    exit 0
}

# Create venv if it doesn't exist
if (-not (Test-Path $VenvPython)) {
    Write-BootstrapLog "Creating venv at $VenvDir"
    $PythonCmd = $null
    foreach ($cmd in @("python3", "python")) {
        try {
            $pyVer = & $cmd -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')" 2>$null
            if ($pyVer) {
                $parts = $pyVer.Split('.')
                if ([int]$parts[0] -ge 3 -and [int]$parts[1] -ge 10) {
                    $PythonCmd = $cmd
                    break
                }
            }
        }
        catch { }
    }

    if (-not $PythonCmd) {
        Write-BootstrapLog "Python 3.10+ not found. Cannot create venv." "ERROR"
        exit 1
    }

    if (Test-Path $VenvDir) {
        Remove-Item $VenvDir -Recurse -Force
    }

    & $PythonCmd -m venv $VenvDir
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $VenvPython)) {
        Write-BootstrapLog "Failed to create venv." "ERROR"
        exit 1
    }
}

# Install dependencies if uvicorn is missing in the venv
if (-not (Test-Path $VenvPip)) {
    Write-BootstrapLog "pip missing in venv. Recreating venv." "WARN"
    Remove-Item $VenvDir -Recurse -Force
    & python -m venv $VenvDir
}

& $VenvPython -m uvicorn --version *> $null
if ($LASTEXITCODE -ne 0) {
    Write-BootstrapLog "Installing Python dependencies into venv"
    Set-Location $WorkerDir
    & $VenvPip install -e . -q
    if ($LASTEXITCODE -ne 0) {
        Write-BootstrapLog "pip install -e . failed." "ERROR"
        exit 1
    }
}

Set-Location $WorkerDir
$env:PYTHONPATH = Join-Path $WorkerDir "src"

Write-BootstrapLog "Launching uvicorn in foreground for Task Scheduler supervision"

$cmdLine = '"{0}" -m uvicorn agent_worker.main:app --host 0.0.0.0 --port {1} 1>> "{2}" 2>> "{3}"' -f `
    $VenvPython, $Port, $BootstrapOut, $BootstrapErr

cmd.exe /d /c $cmdLine
$exitCode = $LASTEXITCODE

Write-BootstrapLog "Worker process exited with code $exitCode" ($(if ($exitCode -eq 0) { "INFO" } else { "ERROR" }))
exit $exitCode
