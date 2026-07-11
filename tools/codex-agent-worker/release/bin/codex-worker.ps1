# codex-worker.ps1 -- Unified CLI for Codex Agent Worker (Windows)

param(
    [Parameter(Position = 0)]
    [string]$Command = "help",

    [Parameter(Position = 1, ValueFromRemainingArguments)]
    [string[]]$ExtraArgs
)

$ErrorActionPreference = "Continue"

$InstallDir = if ($env:CODEX_WORKER_HOME) { $env:CODEX_WORKER_HOME } else { Join-Path $env:USERPROFILE ".codex-worker" }

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$MonorepoPackageJson = Join-Path (Split-Path -Parent (Split-Path -Parent $ScriptDir)) "package.json"
if ((Test-Path $MonorepoPackageJson) -and -not (Test-Path (Join-Path $InstallDir "VERSION"))) {
    try {
        $pkg = Get-Content $MonorepoPackageJson -Raw | ConvertFrom-Json
        if ($pkg.name -eq "codex-agent-worker") {
            $InstallDir = Split-Path -Parent (Split-Path -Parent $ScriptDir)
        }
    }
    catch { }
}

$VersionFile = Join-Path $InstallDir "VERSION"
if (Test-Path $VersionFile) {
    $Version = (Get-Content $VersionFile -Raw).Trim()
}
elseif (Test-Path (Join-Path $InstallDir "package.json")) {
    try {
        $Version = ((Get-Content (Join-Path $InstallDir "package.json") -Raw | ConvertFrom-Json).version).Trim()
    }
    catch {
        $Version = "unknown"
    }
}
else {
    $Version = "unknown"
}

$Port = 3051
$EnvFile = Join-Path $InstallDir ".env"
if (Test-Path $EnvFile) {
    $portLine = Get-Content $EnvFile | Where-Object { $_ -match "^CODEX_WORKER_PORT=(\d+)" }
    if ($portLine -and $portLine -match "=(\d+)") { $Port = [int]$Matches[1] }
}

function Invoke-Start {
    Write-Host "Starting Codex Agent Worker v$Version..." -ForegroundColor Cyan
    $startScript = Join-Path $InstallDir "start.ps1"
    if (Test-Path $startScript) {
        & powershell -ExecutionPolicy Bypass -File $startScript
    }
    else {
        Write-Host "start.ps1 not found at $InstallDir" -ForegroundColor Red
    }
}

function Invoke-Stop {
    $stopScript = Join-Path $InstallDir "stop.ps1"
    if (Test-Path $stopScript) {
        & powershell -ExecutionPolicy Bypass -File $stopScript
    }
    else {
        Write-Host "stop.ps1 not found at $InstallDir" -ForegroundColor Red
    }
}

function Invoke-Status {
    Write-Host "Codex Agent Worker" -ForegroundColor Cyan
    Write-Host "  Version:  $Version" -ForegroundColor Green
    Write-Host "  Install:  $InstallDir"
    Write-Host "  Port:     $Port"

    $listening = netstat -ano 2>$null | Select-String ":$Port\s+.*LISTENING"
    if ($listening) {
        $workerPid = ($listening[0] -split '\s+')[-1]
        Write-Host "  Status:   RUNNING (PID: $workerPid)" -ForegroundColor Green
        try {
            $health = Invoke-RestMethod -Uri "http://localhost:$Port/health" -TimeoutSec 3 -ErrorAction SilentlyContinue
            Write-Host "  Health:   $($health | ConvertTo-Json -Compress)" -ForegroundColor Green
        }
        catch { }
    }
    else {
        Write-Host "  Status:   STOPPED" -ForegroundColor Gray
    }
}

function Invoke-Version {
    Write-Host "codex-worker $Version"
}

function Invoke-Logs {
    $logFile = Join-Path $InstallDir "logs\worker.log"
    if (Test-Path $logFile) {
        Get-Content $logFile -Tail 50 -Wait
    }
    else {
        Write-Host "No log file found at $logFile" -ForegroundColor Yellow
        Write-Host "The worker may not have been started yet." -ForegroundColor Yellow
    }
}

function Invoke-Upgrade {
    param([string[]]$Args)

    $updateScript = Join-Path $InstallDir "update-worker.ps1"
    if (-not (Test-Path $updateScript)) {
        Write-Host "update-worker.ps1 not found at $InstallDir" -ForegroundColor Red
        Write-Host "Install a release that includes the worker self-update script first." -ForegroundColor Yellow
        exit 1
    }

    $passThrough = @("-ExecutionPolicy", "Bypass", "-File", $updateScript)
    if ($Args) { $passThrough += $Args }
    & powershell @passThrough
    exit $LASTEXITCODE
}

function Invoke-UpgradeSdk {
    param([string[]]$Args)

    $updateScript = Join-Path $InstallDir "update.ps1"
    if (-not (Test-Path $updateScript)) {
        Write-Host "update.ps1 not found at $InstallDir" -ForegroundColor Red
        Write-Host "Your install may predate the upgrade-sdk feature. Run 'codex-worker upgrade' first to" -ForegroundColor Yellow
        Write-Host "pull a newer worker version that ships update.ps1." -ForegroundColor Yellow
        exit 1
    }

    $passThrough = @("-ExecutionPolicy", "Bypass", "-File", $updateScript)
    if ($Args) { $passThrough += $Args }

    & powershell @passThrough
    exit $LASTEXITCODE
}

function Invoke-Help {
    Write-Host "codex-worker v$Version -- Codex Agent Worker CLI"
    Write-Host ""
    Write-Host "Usage: codex-worker <command> [options]"
    Write-Host ""
    Write-Host "Commands:"
    Write-Host "  start                Start the worker service"
    Write-Host "  stop                 Stop the worker service"
    Write-Host "  status               Show worker status and health"
    Write-Host "  version              Show installed version"
    Write-Host "  logs                 Tail worker log output"
    Write-Host "  upgrade [opts]       Upgrade the worker itself from OBS or a local .zip"
    Write-Host "                         opts: [-Archive] <path>, -Url <base-url>, -Force, -NoRestart"
    Write-Host "  upgrade-sdk [opts]   Upgrade only @openai/codex-sdk in-place"
    Write-Host "                         opts: -SdkVersion <ver>, -NoRestart, -Registry <url>, -Force"
    Write-Host "  help                 Show this help message"
    Write-Host ""
    Write-Host "Environment:"
    Write-Host "  CODEX_WORKER_HOME   Install directory (default: ~\.codex-worker)"
    Write-Host "  CODEX_WORKER_URL    OBS/HTTP base URL for auto-upgrade"
    Write-Host "  CODEX_WORKER_AUTO_UPDATE_SDK=false disables startup SDK repair"
    Write-Host ""
    Write-Host "Config: $InstallDir\.env"
}

switch ($Command) {
    "start" { Invoke-Start }
    "stop" { Invoke-Stop }
    "status" { Invoke-Status }
    { $_ -in "version", "-v", "--version" } { Invoke-Version }
    "logs" { Invoke-Logs }
    "upgrade" { Invoke-Upgrade -Args $ExtraArgs }
    "upgrade-sdk" { Invoke-UpgradeSdk -Args $ExtraArgs }
    { $_ -in "help", "--help", "-h" } { Invoke-Help }
    default {
        Write-Host "Unknown command: $Command" -ForegroundColor Red
        Invoke-Help
        exit 1
    }
}
