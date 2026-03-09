# Claude Agent Worker — Remote Bootstrap Installer (Windows)
# This script is hosted on OBS and run via: irm <url>/install.ps1 | iex
#
# It downloads the latest release archive and runs the bundled install.ps1.
# The $ReleaseBaseUrl is injected by upload.ps1 before uploading to OBS.

$ErrorActionPreference = "Stop"

$ReleaseBaseUrl = "__RELEASE_BASE_URL__"

Write-Host "=== Claude Agent Worker - Remote Installer ===" -ForegroundColor Cyan
Write-Host ""

# --- Validate BASE_URL ---------------------------------------------------
if ($ReleaseBaseUrl -eq "__RELEASE_BASE_URL__" -or -not $ReleaseBaseUrl) {
    Write-Host "ERROR: This script has not been configured with a release URL." -ForegroundColor Red
    Write-Host "The upload.ps1 script should inject ReleaseBaseUrl before uploading." -ForegroundColor Yellow
    exit 1
}

# --- Fetch latest.json ---------------------------------------------------
Write-Host "Fetching latest version info..." -ForegroundColor Cyan
try {
    $latestJson = Invoke-RestMethod -Uri "$ReleaseBaseUrl/latest.json" -ErrorAction Stop
}
catch {
    Write-Host "ERROR: Could not fetch $ReleaseBaseUrl/latest.json" -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}

$version = $latestJson.version
if (-not $version) {
    Write-Host "ERROR: Could not parse version from latest.json" -ForegroundColor Red
    exit 1
}

Write-Host "Latest version: $version" -ForegroundColor Green

# --- Determine download URL -----------------------------------------------
$filePath = $latestJson.files.windows
if (-not $filePath) {
    Write-Host "ERROR: No Windows release found in latest.json" -ForegroundColor Red
    exit 1
}

$downloadUrl = "$ReleaseBaseUrl/$filePath"
Write-Host "Downloading: $downloadUrl" -ForegroundColor Cyan

# --- Download and extract -------------------------------------------------
$tmpDir = Join-Path ([System.IO.Path]::GetTempPath()) "claude-worker-remote-install"
if (Test-Path $tmpDir) { Remove-Item $tmpDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null

$archiveFile = Join-Path $tmpDir (Split-Path $filePath -Leaf)
Invoke-WebRequest -Uri $downloadUrl -OutFile $archiveFile

Write-Host "Extracting..." -ForegroundColor Cyan
Expand-Archive -Path $archiveFile -DestinationPath $tmpDir -Force

# --- Find and run install.ps1 from the extracted archive ------------------
$installScript = Get-ChildItem -Path $tmpDir -Recurse -Filter "install.ps1" | Select-Object -First 1
if (-not $installScript) {
    Write-Host "ERROR: No install.ps1 found in archive" -ForegroundColor Red
    Remove-Item $tmpDir -Recurse -Force
    exit 1
}

# Pass through CLAUDE_WORKER_URL so install.ps1 can write it to .env
$env:CLAUDE_WORKER_URL = $ReleaseBaseUrl

& powershell -ExecutionPolicy Bypass -File $installScript.FullName

# --- Cleanup --------------------------------------------------------------
Remove-Item $tmpDir -Recurse -Force -ErrorAction SilentlyContinue
