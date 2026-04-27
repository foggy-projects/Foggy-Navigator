# Codex Agent Worker - SDK Update (Release / OBS-installed)
# Upgrades just @openai/codex-sdk (and the bundled codex CLI it ships)
# WITHOUT replacing the worker itself.
#
# This script is shipped INSIDE the OBS-distributed archive and lives in
# $InstallDir alongside start.ps1 / stop.ps1. End users normally invoke it via:
#   codex-worker upgrade-sdk
#   codex-worker upgrade-sdk -SdkVersion 0.130.0
#   codex-worker upgrade-sdk -NoRestart
#
# Differences from the dev-side update.ps1 (in tools/codex-agent-worker root):
#   - No `npm run typecheck` (OBS install has no devDependencies and no src/)
#   - Uses `npm install ... --omit=dev` to stay consistent with install.ps1
#   - Health-check smoke test after restart
#   - On failure, hints user to run `codex-worker upgrade` to reinstall

param(
    [string]$SdkVersion = "",
    [switch]$NoRestart
)

$ErrorActionPreference = "Stop"
$InstallDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$DefaultPort = 3051

function Get-Port {
    param([string]$RootDir)

    $envFile = Join-Path $RootDir ".env"
    $port = $DefaultPort

    if (Test-Path $envFile) {
        $portLine = Get-Content $envFile | Where-Object { $_ -match "^CODEX_WORKER_PORT=(\d+)" }
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

function Get-PackageVersion {
    param(
        [string]$RootDir,
        [string]$PackageName
    )

    $pkgJson = Join-Path $RootDir "node_modules\$PackageName\package.json"
    if (-not (Test-Path $pkgJson)) {
        return "not-installed"
    }

    try {
        $json = Get-Content $pkgJson -Raw | ConvertFrom-Json
        return $json.version
    }
    catch {
        return "unknown"
    }
}

function Resolve-Npm {
    $cmd = Get-Command npm.cmd -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $cmd = Get-Command npm -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw "npm not found on PATH. Please install Node.js (>=20) first."
}

function Test-WorkerHealth {
    param([int]$ListenPort, [int]$TimeoutSec = 30)

    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $resp = Invoke-RestMethod -Uri "http://localhost:$ListenPort/health" -TimeoutSec 3 -ErrorAction Stop
            return @{ ok = $true; body = ($resp | ConvertTo-Json -Compress) }
        }
        catch {
            Start-Sleep -Seconds 1
        }
    }
    return @{ ok = $false; body = "" }
}

$Port = Get-Port -RootDir $InstallDir
$StopScript = Join-Path $InstallDir "stop.ps1"
$StartScript = Join-Path $InstallDir "start.ps1"
$Npm = Resolve-Npm

if (-not (Test-Path (Join-Path $InstallDir "package.json"))) {
    Write-Host "ERROR: package.json not found in $InstallDir." -ForegroundColor Red
    Write-Host "This script must be run from a Codex Worker install directory." -ForegroundColor Yellow
    exit 1
}

$wasRunning = (Get-WorkerPids -ListenPort $Port).Count -gt 0

Write-Host "=== Codex Worker SDK Update ===" -ForegroundColor Cyan
Write-Host "Install dir: $InstallDir" -ForegroundColor Cyan
Write-Host "Port: $Port" -ForegroundColor Cyan
Write-Host "npm: $Npm" -ForegroundColor Cyan

$sdkBefore = Get-PackageVersion -RootDir $InstallDir -PackageName "@openai/codex-sdk"
$cliBefore = Get-PackageVersion -RootDir $InstallDir -PackageName "@openai/codex"
Write-Host "@openai/codex-sdk before: $sdkBefore" -ForegroundColor Gray
Write-Host "@openai/codex (CLI) before: $cliBefore" -ForegroundColor Gray

if ($wasRunning) {
    Write-Host "Worker is running on port $Port. Stopping before upgrade..." -ForegroundColor Yellow
    & powershell -ExecutionPolicy Bypass -File $StopScript
}

Set-Location $InstallDir

if ($SdkVersion) {
    $target = "@openai/codex-sdk@$SdkVersion"
} else {
    $target = "@openai/codex-sdk@latest"
}

Write-Host "Running: npm install $target --omit=dev" -ForegroundColor Cyan
& $Npm install $target --omit=dev
if ($LASTEXITCODE -ne 0) {
    Write-Host "npm install FAILED. Worker has not been restarted." -ForegroundColor Red
    Write-Host "Recovery: run 'codex-worker upgrade' to reinstall the pinned SDK from OBS." -ForegroundColor Yellow
    exit 1
}

$sdkAfter = Get-PackageVersion -RootDir $InstallDir -PackageName "@openai/codex-sdk"
$cliAfter = Get-PackageVersion -RootDir $InstallDir -PackageName "@openai/codex"

Write-Host "@openai/codex-sdk after: $sdkAfter" -ForegroundColor Green
Write-Host "@openai/codex (CLI) after: $cliAfter" -ForegroundColor Green

if ($NoRestart) {
    Write-Host "Update complete. Worker not restarted because -NoRestart was used." -ForegroundColor Yellow
    exit 0
}

if (-not $wasRunning) {
    Write-Host "Update complete. Worker was not running, so no restart was needed." -ForegroundColor Green
    exit 0
}

Write-Host "Restarting worker..." -ForegroundColor Cyan
& powershell -ExecutionPolicy Bypass -File $StartScript

Write-Host "Health-checking worker on port $Port ..." -ForegroundColor Cyan
$health = Test-WorkerHealth -ListenPort $Port -TimeoutSec 30
if ($health.ok) {
    Write-Host "Worker is healthy after SDK upgrade." -ForegroundColor Green
    if ($health.body) { Write-Host "  /health: $($health.body)" -ForegroundColor Green }
}
else {
    Write-Host "Worker did NOT become healthy within 30s after SDK upgrade." -ForegroundColor Red
    Write-Host "The new SDK may have a breaking change. Check logs:" -ForegroundColor Yellow
    Write-Host "  codex-worker logs" -ForegroundColor Yellow
    Write-Host "Recovery: run 'codex-worker upgrade' to reinstall the worker-pinned SDK from OBS." -ForegroundColor Yellow
    exit 1
}
