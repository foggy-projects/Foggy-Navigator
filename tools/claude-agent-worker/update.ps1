# Claude Agent Worker - Update bundled Claude Code in .venv
# Usage:
#   powershell -ExecutionPolicy Bypass -File update.ps1
#   powershell -ExecutionPolicy Bypass -File update.ps1 -NoRestart
#   powershell -ExecutionPolicy Bypass -File update.ps1 -SdkVersion 0.1.50

param(
    [string]$SdkVersion = "",
    [switch]$NoRestart
)

$ErrorActionPreference = "Stop"
$WorkerDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Port = 3031

function Get-Port {
    param([string]$RootDir)

    $envFile = Join-Path $RootDir ".env"
    $port = 3031

    if (Test-Path $envFile) {
        $portLine = Get-Content $envFile | Where-Object { $_ -match "^AGENT_WORKER_PORT=(\d+)" }
        if ($portLine -and $portLine -match "=(\d+)") {
            $port = [int]$Matches[1]
        }
    }

    return $port
}

function Get-WorkerPids {
    param([int]$ListenPort)

    return @(netstat -ano | Select-String ":$ListenPort\s+.*LISTENING" | ForEach-Object {
        ($_ -split '\s+')[-1]
    } | Sort-Object -Unique)
}

function Get-SuitablePython {
    foreach ($cmd in @("python3", "python")) {
        try {
            $pyVer = & $cmd -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')" 2>$null
            if ($pyVer) {
                $parts = $pyVer.Split('.')
                if ([int]$parts[0] -gt 3 -or ([int]$parts[0] -eq 3 -and [int]$parts[1] -ge 10)) {
                    return $cmd
                }
            }
        }
        catch { }
    }

    return $null
}

function Ensure-Venv {
    param([string]$RootDir)

    $venvDir = Join-Path $RootDir ".venv"
    $venvPython = Join-Path $venvDir "Scripts\python.exe"

    if (Test-Path $venvPython) {
        return
    }

    $pythonCmd = Get-SuitablePython
    if (-not $pythonCmd) {
        throw "Python 3.10+ not found. Cannot create .venv."
    }

    Write-Host "Creating .venv with $pythonCmd ..." -ForegroundColor Cyan
    if (Test-Path $venvDir) {
        Remove-Item $venvDir -Recurse -Force
    }

    & $pythonCmd -m venv $venvDir
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to create .venv."
    }
}

function Get-SdkVersionText {
    param([string]$PythonPath)

    try {
        return (& $PythonPath -c "import claude_agent_sdk; print(getattr(claude_agent_sdk, '__version__', 'unknown'))" 2>$null).Trim()
    }
    catch {
        return "not-installed"
    }
}

function Get-BundledClaudeVersionText {
    param([string]$ClaudePath)

    if (-not (Test-Path $ClaudePath)) {
        return "not-found"
    }

    try {
        return (& $ClaudePath --version 2>$null).Trim()
    }
    catch {
        return "unknown"
    }
}

$Port = Get-Port -RootDir $WorkerDir
$StopScript = Join-Path $WorkerDir "stop.ps1"
$StartScript = Join-Path $WorkerDir "start.ps1"

Ensure-Venv -RootDir $WorkerDir

$VenvPython = Join-Path $WorkerDir ".venv\Scripts\python.exe"
$BundledClaude = Join-Path $WorkerDir ".venv\Lib\site-packages\claude_agent_sdk\_bundled\claude.exe"
$PipArgs = @("install", "--upgrade")

if ($SdkVersion) {
    $PipArgs += "claude-agent-sdk==$SdkVersion"
} else {
    $PipArgs += "claude-agent-sdk"
}

$wasRunning = (Get-WorkerPids -ListenPort $Port).Count -gt 0

Write-Host "=== Claude Agent Worker Update ===" -ForegroundColor Cyan
Write-Host "Worker dir: $WorkerDir" -ForegroundColor Cyan
Write-Host "Port: $Port" -ForegroundColor Cyan
Write-Host "Using venv: $VenvPython" -ForegroundColor Cyan
Write-Host "SDK before: $(Get-SdkVersionText -PythonPath $VenvPython)" -ForegroundColor Gray
Write-Host "Bundled Claude before: $(Get-BundledClaudeVersionText -ClaudePath $BundledClaude)" -ForegroundColor Gray

if ($wasRunning) {
    Write-Host "Worker is running on port $Port. Stopping before upgrade..." -ForegroundColor Yellow
    & powershell -ExecutionPolicy Bypass -File $StopScript
}

Set-Location $WorkerDir

Write-Host "Upgrading claude-agent-sdk..." -ForegroundColor Cyan
& $VenvPython -m pip @PipArgs
if ($LASTEXITCODE -ne 0) {
    throw "Failed to upgrade claude-agent-sdk."
}

Write-Host "Refreshing local editable install..." -ForegroundColor Cyan
& $VenvPython -m pip install -e . -q
if ($LASTEXITCODE -ne 0) {
    throw "Failed to refresh claude-agent-worker editable install."
}

$sdkAfter = Get-SdkVersionText -PythonPath $VenvPython
$claudeAfter = Get-BundledClaudeVersionText -ClaudePath $BundledClaude

Write-Host "SDK after: $sdkAfter" -ForegroundColor Green
Write-Host "Bundled Claude after: $claudeAfter" -ForegroundColor Green

if ($NoRestart) {
    Write-Host "Update complete. Worker not restarted because -NoRestart was used." -ForegroundColor Yellow
} elseif ($wasRunning) {
    Write-Host "Restarting worker..." -ForegroundColor Cyan
    & powershell -ExecutionPolicy Bypass -File $StartScript
} else {
    Write-Host "Update complete. Worker was not running, so no restart was needed." -ForegroundColor Green
}
