# claude-code-proxy.ps1 -- Unified CLI for Claude Code Proxy (Windows)
# Usage: claude-code-proxy <command> [options]
param(
    [Parameter(Position = 0)]
    [string]$Command = "help",

    [Parameter(Position = 1, ValueFromRemainingArguments)]
    [string[]]$ExtraArgs
)

$ErrorActionPreference = "Continue"

$InstallDir = if ($env:CLAUDE_PROXY_HOME) { $env:CLAUDE_PROXY_HOME } else { Join-Path $env:USERPROFILE ".claude-code-proxy" }

# Allow running from monorepo (dist\bin\claude-code-proxy.ps1 -> proxy root is ..\..\..)
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$MonorepoInit = Join-Path (Split-Path -Parent (Split-Path -Parent $ScriptDir)) "src\__init__.py"
if ((Test-Path $MonorepoInit) -and -not (Test-Path (Join-Path $InstallDir "VERSION"))) {
    $InstallDir = Split-Path -Parent (Split-Path -Parent $ScriptDir)
}

$VersionFile = Join-Path $InstallDir "VERSION"
$Version = if (Test-Path $VersionFile) { (Get-Content $VersionFile -Raw).Trim() } else { "unknown" }
$Port = 8082

# Load port from .env
$EnvFile = Join-Path $InstallDir ".env"
if (Test-Path $EnvFile) {
    $portLine = Get-Content $EnvFile | Where-Object { $_ -match "^PORT=(\d+)" }
    if ($portLine -and $portLine -match "=(\d+)") { $Port = [int]$Matches[1] }
}

function Invoke-Start {
    Write-Host "Starting Claude Code Proxy v$Version..." -ForegroundColor Cyan
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
    Write-Host "Claude Code Proxy" -ForegroundColor Cyan
    Write-Host "  Version:  $Version" -ForegroundColor Green
    Write-Host "  Install:  $InstallDir"
    Write-Host "  Port:     $Port"

    $listening = netstat -ano 2>$null | Select-String ":$Port\s+.*LISTENING"
    if ($listening) {
        $proxyPid = ($listening[0] -split '\s+')[-1]
        Write-Host "  Status:   RUNNING (PID: $proxyPid)" -ForegroundColor Green
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
    Write-Host "claude-code-proxy $Version"
}

function Invoke-Logs {
    $logFile = Join-Path $InstallDir "logs\proxy.log"
    if (Test-Path $logFile) {
        Get-Content $logFile -Tail 50 -Wait
    }
    else {
        Write-Host "No log file found at $logFile" -ForegroundColor Yellow
        Write-Host "The proxy may not have been started yet." -ForegroundColor Yellow
    }
}

function Invoke-Upgrade {
    param([string]$ArchivePath)

    Write-Host "Claude Code Proxy Upgrade" -ForegroundColor Cyan

    # Local archive
    if ($ArchivePath -and (Test-Path $ArchivePath)) {
        Write-Host "Upgrading from local archive: $ArchivePath" -ForegroundColor Cyan
        Invoke-UpgradeFromArchive $ArchivePath
        return
    }

    # Load CLAUDE_PROXY_URL from .env if not already set
    $proxyUrl = $env:CLAUDE_PROXY_URL
    if (-not $proxyUrl -and (Test-Path $EnvFile)) {
        $urlLine = Get-Content $EnvFile | Where-Object { $_ -match "^CLAUDE_PROXY_URL=(.+)" }
        if ($urlLine -and $urlLine -match "=(.+)") { $proxyUrl = $Matches[1].Trim() }
    }

    # --- Priority 1: OBS / custom URL ---
    if ($proxyUrl) {
        Invoke-UpgradeFromObs $proxyUrl
        return
    }

    # --- Priority 2: GitHub Releases ---
    $repo = $env:CLAUDE_PROXY_REPO
    if ($repo) {
        Invoke-UpgradeFromGitHub $repo
        return
    }

    # --- No source configured ---
    Write-Host "Usage:" -ForegroundColor Yellow
    Write-Host "  claude-code-proxy upgrade C:\path\to\claude-code-proxy-X.Y.Z-windows.zip"
    Write-Host ""
    Write-Host "To enable auto-upgrade, set one of:" -ForegroundColor Yellow
    Write-Host "  CLAUDE_PROXY_URL    OBS/HTTP base URL (in .env or environment)"
    Write-Host "  CLAUDE_PROXY_REPO   GitHub repo (e.g. your-org/repo)"
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

    $tmpDir = Join-Path ([System.IO.Path]::GetTempPath()) "claude-code-proxy-upgrade"
    if (Test-Path $tmpDir) { Remove-Item $tmpDir -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
    $archiveFile = Join-Path $tmpDir (Split-Path $filePath -Leaf)

    Invoke-WebRequest -Uri $downloadUrl -OutFile $archiveFile

    # Pass through CLAUDE_PROXY_URL so install.ps1 can preserve it
    $env:CLAUDE_PROXY_URL = $BaseUrl

    Invoke-UpgradeFromArchive $archiveFile
    Remove-Item $tmpDir -Recurse -Force
}

function Invoke-UpgradeFromGitHub {
    param([string]$Repo)

    Write-Host "Checking GitHub Releases ($Repo)..." -ForegroundColor Cyan

    $headers = @{}
    if ($env:GITHUB_TOKEN) {
        $headers["Authorization"] = "token $($env:GITHUB_TOKEN)"
    }

    try {
        $release = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/latest" `
            -Headers $headers -ErrorAction Stop
        $latestTag = $release.tag_name
        $latestVersion = $latestTag -replace '^proxy-v?|^v', ''

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

        $tmpDir = Join-Path ([System.IO.Path]::GetTempPath()) "claude-code-proxy-upgrade"
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
        Write-Host "Or use manual upgrade: claude-code-proxy upgrade C:\path\to\archive.zip" -ForegroundColor Yellow
    }
}

function Invoke-UpgradeFromArchive {
    param([string]$Archive)

    Write-Host "Extracting..." -ForegroundColor Cyan

    $tmpDir = Join-Path ([System.IO.Path]::GetTempPath()) "claude-code-proxy-extract"
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
    Write-Host "claude-code-proxy v$Version -- Claude Code Proxy CLI"
    Write-Host ""
    Write-Host "Usage: claude-code-proxy <command> [options]"
    Write-Host ""
    Write-Host "Commands:"
    Write-Host "  start              Start the proxy service"
    Write-Host "  stop               Stop the proxy service"
    Write-Host "  status             Show proxy status and health"
    Write-Host "  version            Show installed version"
    Write-Host "  logs               Tail proxy log output"
    Write-Host "  upgrade [archive]  Upgrade from OBS/GitHub or local .zip"
    Write-Host "  help               Show this help message"
    Write-Host ""
    Write-Host "Environment:"
    Write-Host "  CLAUDE_PROXY_HOME     Install directory (default: ~\.claude-code-proxy)"
    Write-Host "  CLAUDE_PROXY_URL      OBS/HTTP base URL for auto-upgrade (preferred)"
    Write-Host "  CLAUDE_PROXY_REPO     GitHub repo for auto-upgrade (fallback)"
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
