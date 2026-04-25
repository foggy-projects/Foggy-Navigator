# Gemini Agent Worker - Stop Script
# Usage: powershell -ExecutionPolicy Bypass -File stop.ps1

$WorkerDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Port = 3071

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

$EnvFile = Join-Path $WorkerDir ".env"
$envPort = Get-EnvValue -FilePath $EnvFile -Key "GEMINI_WORKER_PORT"
if ($envPort) {
    $Port = [int]$envPort
}

$listeners = Get-PortListeners -TargetPort $Port
if ($listeners.Count -eq 0) {
    Write-Host "No Gemini worker running on port $Port." -ForegroundColor Gray
    exit 0
}

foreach ($listener in $listeners) {
    Write-Host "Stopping Gemini worker on port $Port (PID: $($listener.Pid))..." -ForegroundColor Yellow
    taskkill /F /PID $listener.Pid 2>$null | Out-Null
}

Write-Host "Gemini worker stopped." -ForegroundColor Green
