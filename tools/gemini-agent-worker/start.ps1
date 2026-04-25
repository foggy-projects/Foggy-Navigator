# Gemini Agent Worker - Start Script
# Usage: powershell -ExecutionPolicy Bypass -File start.ps1

$ErrorActionPreference = "Stop"
$WorkerDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Port = 3071
$HostAddress = "127.0.0.1"
$WorkerName = "gemini-agent-worker"
$LogDir = Join-Path $WorkerDir "logs"
$StdOutLog = Join-Path $LogDir "worker.log"
$StdErrLog = Join-Path $LogDir "worker-error.log"

function Get-EnvValue {
    param(
        [string]$FilePath,
        [string]$Key
    )

    if (-not (Test-Path $FilePath)) {
        return $null
    }

    $line = Get-Content $FilePath | Where-Object { $_ -match "^\s*$Key=(.+)$" } | Select-Object -First 1
    if (-not $line) {
        return $null
    }

    $value = ($line -split "=", 2)[1].Trim()
    if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
        return $value.Substring(1, $value.Length - 2)
    }
    return $value
}

function Get-PortListeners {
    param([int]$TargetPort)

    $listeners = @()
    $lines = netstat -ano | Select-String "^\s*TCP\s+\S+:$TargetPort\s+\S+\s+LISTENING\s+\d+\s*$"
    foreach ($line in $lines) {
        $parts = ($line.ToString().Trim() -split "\s+")
        if ($parts.Length -ge 5) {
            $listeners += [pscustomobject]@{
                Protocol = $parts[0]
                LocalAddress = $parts[1]
                Pid = [int]$parts[-1]
            }
        }
    }
    return $listeners | Sort-Object Pid -Unique
}

function Get-ProcessSummary {
    param([int]$ProcessId)

    try {
        return Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId" -ErrorAction Stop
    } catch {
        return $null
    }
}

function Test-WorkerHealth {
    param(
        [string]$TargetHost,
        [int]$TargetPort
    )

    $probeHost = $TargetHost
    if ($probeHost -eq "0.0.0.0" -or $probeHost -eq "::") {
        $probeHost = "127.0.0.1"
    }

    try {
        $response = Invoke-WebRequest -UseBasicParsing "http://$probeHost`:$TargetPort/health" -TimeoutSec 3
        return $response.StatusCode -eq 200
    } catch {
        return $false
    }
}

$EnvFile = Join-Path $WorkerDir ".env"
$envPort = Get-EnvValue -FilePath $EnvFile -Key "GEMINI_WORKER_PORT"
if ($envPort) {
    $Port = [int]$envPort
}

$envHost = Get-EnvValue -FilePath $EnvFile -Key "GEMINI_WORKER_HOST"
if ($envHost) {
    $HostAddress = $envHost
}

$envWorkerName = Get-EnvValue -FilePath $EnvFile -Key "GEMINI_WORKER_NAME"
if ($envWorkerName) {
    $WorkerName = $envWorkerName
}

$envAllowedCwds = Get-EnvValue -FilePath $EnvFile -Key "GEMINI_ALLOWED_CWDS"

if (-not $env:GEMINI_WORKER_PORT) {
    $env:GEMINI_WORKER_PORT = "$Port"
}
if (-not $env:GEMINI_WORKER_HOST) {
    $env:GEMINI_WORKER_HOST = $HostAddress
}
if (-not $env:GEMINI_WORKER_NAME) {
    $env:GEMINI_WORKER_NAME = $WorkerName
}
if ($envAllowedCwds) {
    $env:GEMINI_ALLOWED_CWDS = $envAllowedCwds
} elseif (-not $env:GEMINI_ALLOWED_CWDS) {
    $env:GEMINI_ALLOWED_CWDS = $PWD.Path
}

Write-Host "=== Gemini Agent Worker ===" -ForegroundColor Cyan
Write-Host "Port: $Port" -ForegroundColor Cyan
Write-Host "Host: $HostAddress" -ForegroundColor Cyan
Write-Host "Worker Name: $WorkerName" -ForegroundColor Cyan
Write-Host "Allowed CWDs: $($env:GEMINI_ALLOWED_CWDS)" -ForegroundColor Cyan

$listeners = Get-PortListeners -TargetPort $Port
if ($listeners.Count -gt 0) {
    $summaries = foreach ($listener in $listeners) {
        $process = Get-ProcessSummary -ProcessId $listener.Pid
        if ($process) {
            [pscustomobject]@{
                Pid = $listener.Pid
                Name = $process.Name
                CommandLine = $process.CommandLine
            }
        } else {
            [pscustomobject]@{
                Pid = $listener.Pid
                Name = "unknown"
                CommandLine = ""
            }
        }
    }

    foreach ($summary in $summaries) {
        Write-Host "Port $Port is occupied. Stopping process PID=$($summary.Pid) Name=$($summary.Name)..." -ForegroundColor Yellow
        if ($summary.CommandLine) {
            Write-Host ("CommandLine: {0}" -f $summary.CommandLine) -ForegroundColor DarkYellow
        }
        taskkill /F /PID $summary.Pid 2>$null | Out-Null
    }
    Start-Sleep -Milliseconds 800
}

if (-not (Test-Path $LogDir)) {
    New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
}

Set-Location $WorkerDir

if (-not (Test-Path (Join-Path $WorkerDir "node_modules"))) {
    Write-Host "Installing npm dependencies..." -ForegroundColor Cyan
    & cmd.exe /c npm install
    if ($LASTEXITCODE -ne 0) {
        Write-Host "ERROR: npm install failed." -ForegroundColor Red
        exit 1
    }
}

Write-Host "Starting Gemini worker on port $Port (background)..." -ForegroundColor Green
Start-Process "cmd.exe" `
    -ArgumentList "/c", "npm run start" `
    -WorkingDirectory $WorkerDir `
    -RedirectStandardOutput $StdOutLog `
    -RedirectStandardError $StdErrLog `
    -WindowStyle Hidden

$maxWait = 30
$waited = 0
$started = $false

while ($waited -lt $maxWait) {
    Start-Sleep -Seconds 1
    $waited += 1
    if (Test-WorkerHealth -TargetHost $HostAddress -TargetPort $Port) {
        $started = $true
        break
    }
    Write-Host "." -NoNewline
}

Write-Host ""

if ($started) {
    Write-Host "Gemini worker started on http://$HostAddress`:$Port" -ForegroundColor Green
    Write-Host "Logs: $StdOutLog" -ForegroundColor Gray
    Write-Host "Errors: $StdErrLog" -ForegroundColor Gray
    exit 0
}

Write-Host "Gemini worker failed to start within $maxWait seconds." -ForegroundColor Red
Write-Host "Check logs: $StdErrLog" -ForegroundColor Yellow
if (Test-Path $StdErrLog) {
    Get-Content $StdErrLog -Tail 30
}
exit 1
