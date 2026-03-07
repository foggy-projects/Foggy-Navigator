# claude-worker.ps1 -- Unified CLI for Claude Agent Worker (Windows)
# Usage: claude-worker <command> [options]
param(
    [Parameter(Position = 0)]
    [string]$Command = "help",

    [Parameter(Position = 1, ValueFromRemainingArguments)]
    [string[]]$ExtraArgs
)

$ErrorActionPreference = "Continue"

$InstallDir = if ($env:CLAUDE_WORKER_HOME) { $env:CLAUDE_WORKER_HOME } else { Join-Path $env:USERPROFILE ".claude-worker" }

# Allow running from monorepo (dist\bin\claude-worker.ps1 -> worker root is ..\..\..)
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$MonorepoInit = Join-Path (Split-Path -Parent (Split-Path -Parent $ScriptDir)) "src\agent_worker\__init__.py"
if ((Test-Path $MonorepoInit) -and -not (Test-Path (Join-Path $InstallDir "VERSION"))) {
    $InstallDir = Split-Path -Parent (Split-Path -Parent $ScriptDir)
}

$VersionFile = Join-Path $InstallDir "VERSION"
$Version = if (Test-Path $VersionFile) { (Get-Content $VersionFile -Raw).Trim() } else { "unknown" }
$Port = 3031

# Load port from .env
$EnvFile = Join-Path $InstallDir ".env"
if (Test-Path $EnvFile) {
    $portLine = Get-Content $EnvFile | Where-Object { $_ -match "^AGENT_WORKER_PORT=(\d+)" }
    if ($portLine -and $portLine -match "=(\d+)") { $Port = [int]$Matches[1] }
}

function Invoke-Start {
    Write-Host "Starting Claude Agent Worker v$Version..." -ForegroundColor Cyan
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
    Write-Host "Claude Agent Worker" -ForegroundColor Cyan
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
    Write-Host "claude-worker $Version"
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

    $repo = $env:CLAUDE_WORKER_REPO
    Write-Host "Claude Agent Worker Upgrade" -ForegroundColor Cyan

    # Local archive
    if ($ArchivePath -and (Test-Path $ArchivePath)) {
        Write-Host "Upgrading from local archive: $ArchivePath" -ForegroundColor Cyan
        Invoke-UpgradeFromArchive $ArchivePath
        return
    }

    # GitHub Releases
    if (-not $repo) {
        Write-Host "Usage:" -ForegroundColor Yellow
        Write-Host "  claude-worker upgrade C:\path\to\claude-worker-X.Y.Z-windows.zip"
        Write-Host ""
        Write-Host "To enable auto-upgrade from GitHub Releases, set:" -ForegroundColor Yellow
        Write-Host '  $env:CLAUDE_WORKER_REPO = "your-org/your-repo"'
        return
    }

    Write-Host "Checking GitHub Releases ($repo)..." -ForegroundColor Cyan

    $headers = @{}
    if ($env:GITHUB_TOKEN) {
        $headers["Authorization"] = "token $($env:GITHUB_TOKEN)"
    }

    try {
        $release = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/latest" `
            -Headers $headers -ErrorAction Stop
        $latestTag = $release.tag_name
        $latestVersion = $latestTag -replace '^worker-v?|^v', ''

        if ($latestVersion -eq $Version) {
            Write-Host "Already up to date (v$Version)." -ForegroundColor Green
            return
        }

        Write-Host "New version available: $Version -> $latestVersion" -ForegroundColor Cyan

        $asset = $release.assets | Where-Object { $_.name -like "*windows*" } | Select-Object -First 1
        if (-not $asset) {
            Write-Host "No Windows release artifact found" -ForegroundColor Red
            return
        }

        $tmpDir = Join-Path ([System.IO.Path]::GetTempPath()) "claude-worker-upgrade"
        if (Test-Path $tmpDir) { Remove-Item $tmpDir -Recurse -Force }
        New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
        $archiveFile = Join-Path $tmpDir $asset.name

        Write-Host "Downloading..." -ForegroundColor Cyan
        Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $archiveFile -Headers $headers

        Invoke-UpgradeFromArchive $archiveFile
        Remove-Item $tmpDir -Recurse -Force
    }
    catch {
        Write-Host "Could not reach GitHub Releases." -ForegroundColor Yellow
        Write-Host "For private repos, set GITHUB_TOKEN environment variable." -ForegroundColor Yellow
        Write-Host "Or use manual upgrade: claude-worker upgrade C:\path\to\archive.zip" -ForegroundColor Yellow
    }
}

function Invoke-UpgradeFromArchive {
    param([string]$Archive)

    Write-Host "Extracting..." -ForegroundColor Cyan

    $tmpDir = Join-Path ([System.IO.Path]::GetTempPath()) "claude-worker-extract"
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
    Write-Host "claude-worker v$Version -- Claude Agent Worker CLI"
    Write-Host ""
    Write-Host "Usage: claude-worker <command> [options]"
    Write-Host ""
    Write-Host "Commands:"
    Write-Host "  start              Start the worker service"
    Write-Host "  stop               Stop the worker service"
    Write-Host "  status             Show worker status and health"
    Write-Host "  version            Show installed version"
    Write-Host "  logs               Tail worker log output"
    Write-Host "  upgrade [archive]  Upgrade from local .zip or GitHub Release"
    Write-Host "  help               Show this help message"
    Write-Host ""
    Write-Host "Environment:"
    Write-Host "  CLAUDE_WORKER_HOME    Install directory (default: ~\.claude-worker)"
    Write-Host "  CLAUDE_WORKER_REPO    GitHub repo for auto-upgrade (e.g. your-org/repo)"
    Write-Host "  GITHUB_TOKEN          Token for private GitHub repos"
    Write-Host ""
    Write-Host "Config: $InstallDir\.env"
}

# Dispatch
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
