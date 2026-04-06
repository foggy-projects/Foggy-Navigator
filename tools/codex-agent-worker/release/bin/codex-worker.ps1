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
    param([string]$ArchivePath)

    Write-Host "Codex Agent Worker Upgrade" -ForegroundColor Cyan

    if ($ArchivePath -and (Test-Path $ArchivePath)) {
        Write-Host "Upgrading from local archive: $ArchivePath" -ForegroundColor Cyan
        Invoke-UpgradeFromArchive $ArchivePath
        return
    }

    $workerUrl = $env:CODEX_WORKER_URL
    if (-not $workerUrl -and (Test-Path $EnvFile)) {
        $urlLine = Get-Content $EnvFile | Where-Object { $_ -match "^CODEX_WORKER_URL=(.+)" }
        if ($urlLine -and $urlLine -match "=(.+)") { $workerUrl = $Matches[1].Trim() }
    }

    if ($workerUrl) {
        Invoke-UpgradeFromObs $workerUrl
        return
    }

    Write-Host "Usage:" -ForegroundColor Yellow
    Write-Host "  codex-worker upgrade C:\path\to\codex-worker-X.Y.Z-windows.zip"
    Write-Host ""
    Write-Host "To enable auto-upgrade, set:" -ForegroundColor Yellow
    Write-Host "  CODEX_WORKER_URL   OBS/HTTP base URL (in .env or environment)"
}

function Invoke-UpgradeFromObs {
    param([string]$BaseUrl)

    Write-Host "Checking latest version from $BaseUrl ..." -ForegroundColor Cyan

    try {
        $latestJson = Invoke-RestMethod -Uri "$BaseUrl/latest.json" -TimeoutSec 15 -ErrorAction Stop
    }
    catch {
        Write-Host "Could not fetch $BaseUrl/latest.json" -ForegroundColor Red
        Write-Host $_.Exception.Message -ForegroundColor Red
        return
    }

    $latestVersion = $latestJson.version
    if (-not $latestVersion) {
        Write-Host "Could not parse version from latest.json" -ForegroundColor Red
        return
    }

    if ($latestVersion -eq $Version) {
        Write-Host "Already up to date (v$Version)." -ForegroundColor Green
        return
    }

    Write-Host "New version available: $Version -> $latestVersion" -ForegroundColor Cyan

    $filePath = $latestJson.files.windows
    if (-not $filePath) {
        Write-Host "No Windows release found in latest.json" -ForegroundColor Red
        return
    }

    $downloadUrl = "$BaseUrl/$filePath"
    Write-Host "Downloading: $downloadUrl" -ForegroundColor Cyan

    $tmpDir = Join-Path ([System.IO.Path]::GetTempPath()) "codex-worker-upgrade"
    if (Test-Path $tmpDir) { Remove-Item $tmpDir -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
    $archiveFile = Join-Path $tmpDir (Split-Path $filePath -Leaf)

    Invoke-WebRequest -Uri $downloadUrl -OutFile $archiveFile
    $env:CODEX_WORKER_URL = $BaseUrl
    Invoke-UpgradeFromArchive $archiveFile
    Remove-Item $tmpDir -Recurse -Force
}

function Invoke-UpgradeFromArchive {
    param([string]$Archive)

    Write-Host "Extracting..." -ForegroundColor Cyan

    $tmpDir = Join-Path ([System.IO.Path]::GetTempPath()) "codex-worker-extract"
    if (Test-Path $tmpDir) { Remove-Item $tmpDir -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null

    Expand-Archive -Path $Archive -DestinationPath $tmpDir -Force

    $installScript = Get-ChildItem -Path $tmpDir -Recurse -Filter "install.ps1" | Select-Object -First 1
    if (-not $installScript) {
        Write-Host "No install.ps1 found in archive" -ForegroundColor Red
        Remove-Item $tmpDir -Recurse -Force
        return
    }

    & powershell -ExecutionPolicy Bypass -File $installScript.FullName -Upgrade
    Remove-Item $tmpDir -Recurse -Force

    $newVersion = if (Test-Path $VersionFile) { (Get-Content $VersionFile -Raw).Trim() } else { "unknown" }
    Write-Host "Upgraded to v$newVersion!" -ForegroundColor Green
}

function Invoke-Help {
    Write-Host "codex-worker v$Version -- Codex Agent Worker CLI"
    Write-Host ""
    Write-Host "Usage: codex-worker <command> [options]"
    Write-Host ""
    Write-Host "Commands:"
    Write-Host "  start              Start the worker service"
    Write-Host "  stop               Stop the worker service"
    Write-Host "  status             Show worker status and health"
    Write-Host "  version            Show installed version"
    Write-Host "  logs               Tail worker log output"
    Write-Host "  upgrade [archive]  Upgrade from OBS or local .zip"
    Write-Host "  help               Show this help message"
    Write-Host ""
    Write-Host "Environment:"
    Write-Host "  CODEX_WORKER_HOME   Install directory (default: ~\.codex-worker)"
    Write-Host "  CODEX_WORKER_URL    OBS/HTTP base URL for auto-upgrade"
    Write-Host ""
    Write-Host "Config: $InstallDir\.env"
}

switch ($Command) {
    "start" { Invoke-Start }
    "stop" { Invoke-Stop }
    "status" { Invoke-Status }
    { $_ -in "version", "-v", "--version" } { Invoke-Version }
    "logs" { Invoke-Logs }
    "upgrade" { Invoke-Upgrade -ArchivePath ($ExtraArgs | Select-Object -First 1) }
    { $_ -in "help", "--help", "-h" } { Invoke-Help }
    default {
        Write-Host "Unknown command: $Command" -ForegroundColor Red
        Invoke-Help
        exit 1
    }
}
