# Codex Agent Worker start script (Windows)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

function Import-DotEnv {
    param([string]$Path)

    if (-not (Test-Path $Path)) {
        return
    }

    foreach ($rawLine in Get-Content $Path) {
        $line = $rawLine.Trim()
        if (-not $line -or $line.StartsWith("#")) {
            continue
        }

        $parts = $line -split "=", 2
        if ($parts.Count -ne 2) {
            continue
        }

        $key = $parts[0].Trim()
        if ($key -notmatch "^[A-Za-z_][A-Za-z0-9_]*$") {
            continue
        }

        $value = $parts[1].Trim()
        if ($value.Length -ge 2) {
            $first = $value.Substring(0, 1)
            $last = $value.Substring($value.Length - 1, 1)
            if (($first -eq '"' -and $last -eq '"') -or ($first -eq "'" -and $last -eq "'")) {
                $value = $value.Substring(1, $value.Length - 2)
            }
        }

        Set-Item -Path "Env:$key" -Value $value
    }
}

Import-DotEnv -Path ".env"
# Prevent another Codex runtime's generic Home from crossing the Worker boundary.
Remove-Item Env:CODEX_HOME -ErrorAction SilentlyContinue
$PORT = if ($env:CODEX_WORKER_PORT) { $env:CODEX_WORKER_PORT.Trim() } else { "3051" }

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Codex Agent Worker" -ForegroundColor Cyan
Write-Host "  Port: $PORT" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

Write-Host "`n[1/4] Checking existing processes on port $PORT..." -ForegroundColor Yellow
$existingPids = netstat -ano | Select-String ":$PORT\s" | ForEach-Object {
    ($_ -split "\s+")[-1]
} | Where-Object { $_ -ne "0" } | Sort-Object -Unique

if ($existingPids) {
    foreach ($procId in $existingPids) {
        Write-Host "  Killing existing process PID=$procId" -ForegroundColor Yellow
        try { Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue } catch {}
    }
    Start-Sleep -Seconds 2
}

Write-Host "`n[2/4] Checking dependencies..." -ForegroundColor Yellow
if (-not (Test-Path "node_modules")) {
    if (Test-Path "package-lock.json") {
        Write-Host "  Running npm ci --omit=dev..." -ForegroundColor Yellow
        npm ci --omit=dev 2>&1 | Out-Null
    }
    else {
        Write-Host "  Running npm install --omit=dev..." -ForegroundColor Yellow
        npm install --omit=dev 2>&1 | Out-Null
    }
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  npm install failed!" -ForegroundColor Red
        exit 1
    }
    Write-Host "  Dependencies installed." -ForegroundColor Green
}
else {
    Write-Host "  node_modules exists, skipping install." -ForegroundColor Green
}

$EnsureSdkScript = Join-Path $ScriptDir "scripts\ensure-sdk.mjs"
if (-not (Test-Path -LiteralPath $EnsureSdkScript)) {
    Write-Host "  SDK preflight script not found: $EnsureSdkScript" -ForegroundColor Red
    exit 1
}
& node $EnsureSdkScript --worker-dir $ScriptDir --omit-dev
if ($LASTEXITCODE -ne 0) {
    Write-Host "  Codex SDK preflight failed; worker will not start." -ForegroundColor Red
    exit $LASTEXITCODE
}

if (-not (Test-Path "logs")) {
    New-Item -ItemType Directory -Path "logs" | Out-Null
}

Write-Host "`n[3/4] Starting Codex Worker..." -ForegroundColor Yellow
$logFile = "logs/worker.log"
$errFile = "logs/worker-error.log"

$process = Start-Process -FilePath "node" -ArgumentList "dist/index.js" `
    -WorkingDirectory $ScriptDir `
    -RedirectStandardOutput $logFile `
    -RedirectStandardError $errFile `
    -PassThru -WindowStyle Hidden

Write-Host "  PID: $($process.Id)" -ForegroundColor Green

Write-Host "`n[4/4] Waiting for worker to be ready..." -ForegroundColor Yellow
$maxWait = 60
$waited = 0
$ready = $false
$healthUrls = @(
    "http://127.0.0.1:$PORT/health"
    "http://localhost:$PORT/health"
)

function Test-WorkerHealth {
    param(
        [string[]]$Urls
    )

    foreach ($url in $Urls) {
        try {
            $health = Invoke-RestMethod -Uri $url -TimeoutSec 2 -ErrorAction Stop
            if ($health.status -eq "ok" -and $health.codex_sdk_available -eq $true -and $health.codex_sdk_compatible -eq $true) {
                return $true
            }
        }
        catch {
        }
    }

    return $false
}

while ($waited -lt $maxWait) {
    Start-Sleep -Seconds 1
    $waited++

    if (Test-WorkerHealth -Urls $healthUrls) {
        $ready = $true
        break
    }

    $process.Refresh()
    if ($process.HasExited) {
        Write-Host "`n  Worker process exited unexpectedly!" -ForegroundColor Red
        if (Test-Path $errFile) {
            Write-Host "`n  Error log:" -ForegroundColor Red
            Get-Content $errFile | Select-Object -Last 20 | ForEach-Object { Write-Host "    $_" -ForegroundColor Red }
        }
        exit 1
    }

    Write-Host "  Waiting... ($waited/$maxWait)" -ForegroundColor Gray
}

if ($ready) {
    Start-Sleep -Seconds 3
    $process.Refresh()
    if ($process.HasExited) {
        Write-Host "`n  Worker exited after readiness!" -ForegroundColor Red
        if (Test-Path $errFile) {
            Write-Host "`n  Error log:" -ForegroundColor Red
            Get-Content $errFile | Select-Object -Last 20 | ForEach-Object { Write-Host "    $_" -ForegroundColor Red }
        }
        exit 1
    }
    if (-not (Test-WorkerHealth -Urls $healthUrls)) {
        Write-Host "`n  Worker health failed after readiness!" -ForegroundColor Red
        if (Test-Path $errFile) {
            Write-Host "`n  Error log:" -ForegroundColor Red
            Get-Content $errFile | Select-Object -Last 20 | ForEach-Object { Write-Host "    $_" -ForegroundColor Red }
        }
        exit 1
    }
    Write-Host "`n========================================" -ForegroundColor Green
    Write-Host "  Codex Worker is READY!" -ForegroundColor Green
    Write-Host "  URL: http://localhost:$PORT" -ForegroundColor Green
    Write-Host "  Health: http://localhost:$PORT/health" -ForegroundColor Green
    Write-Host "  PID: $($process.Id)" -ForegroundColor Green
    Write-Host "========================================" -ForegroundColor Green
}
else {
    Write-Host "`n  Worker failed to start within ${maxWait}s!" -ForegroundColor Red
    if (Test-Path $errFile) {
        Write-Host "`n  Error log:" -ForegroundColor Red
        Get-Content $errFile | Select-Object -Last 20 | ForEach-Object { Write-Host "    $_" -ForegroundColor Red }
    }
    exit 1
}
