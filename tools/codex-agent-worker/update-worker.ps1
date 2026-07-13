# Codex Agent Worker - Worker self-update (Windows)
#
# Updates the worker release package itself. To update only @openai/codex-sdk
# and its bundled Codex CLI, use update.ps1 instead.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File update-worker.ps1
#   powershell -ExecutionPolicy Bypass -File update-worker.ps1 -Url https://example.com/codex-worker
#   powershell -ExecutionPolicy Bypass -File update-worker.ps1 -Archive C:\path\codex-worker-X.Y.Z-windows.zip
#   powershell -ExecutionPolicy Bypass -File update-worker.ps1 -Force -NoRestart

param(
    [Parameter(Position = 0)]
    [string]$Archive = "",
    [string]$Url = "",
    [switch]$Force,
    [switch]$NoRestart
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$InstallDir = if ($env:CODEX_WORKER_HOME) {
    $env:CODEX_WORKER_HOME
}
elseif ((Test-Path (Join-Path $ScriptDir "VERSION")) -and (Test-Path (Join-Path $ScriptDir "package.json"))) {
    $ScriptDir
}
else {
    Join-Path $env:USERPROFILE ".codex-worker"
}
$DefaultPort = 3051
$TempDirs = [System.Collections.Generic.List[string]]::new()

function Get-EnvValue {
    param([string]$Path, [string]$Name)

    if (-not (Test-Path $Path)) { return "" }
    $line = Get-Content $Path | Where-Object { $_ -match "^$([regex]::Escape($Name))=(.*)$" } | Select-Object -Last 1
    if ($line -and $line -match "^[^=]+=(.*)$") { return $Matches[1].Trim() }
    return ""
}

function Get-WorkerPids {
    param([int]$ListenPort)

    return @(netstat -ano 2>$null | Select-String ":$ListenPort\s+.*LISTENING" | ForEach-Object {
        ($_ -split '\s+')[-1]
    } | Sort-Object -Unique)
}

function Test-WorkerHealth {
    param([int]$ListenPort, [int]$TimeoutSec = 30)

    $healthUrls = @(
        "http://127.0.0.1:$ListenPort/health"
        "http://localhost:$ListenPort/health"
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        foreach ($url in $healthUrls) {
            try {
                $response = Invoke-RestMethod -Uri $url -TimeoutSec 3 -ErrorAction Stop
                if ($response.status -eq "ok") { return $true }
            }
            catch { }
        }
        Start-Sleep -Seconds 1
    }
    return $false
}

if (-not (Test-Path (Join-Path $InstallDir "package.json"))) {
    Write-Host "Codex Worker is not installed at $InstallDir." -ForegroundColor Red
    Write-Host "Set CODEX_WORKER_HOME if it is installed elsewhere." -ForegroundColor Yellow
    exit 1
}

$EnvFile = Join-Path $InstallDir ".env"
$PortText = Get-EnvValue -Path $EnvFile -Name "CODEX_WORKER_PORT"
$Port = if ($PortText -match '^\d+$') { [int]$PortText } else { $DefaultPort }
$VersionFile = Join-Path $InstallDir "VERSION"
if (Test-Path $VersionFile) {
    $CurrentVersion = (Get-Content $VersionFile -Raw).Trim()
}
else {
    $CurrentVersion = ((Get-Content (Join-Path $InstallDir "package.json") -Raw | ConvertFrom-Json).version).Trim()
}
$WasRunning = (Get-WorkerPids -ListenPort $Port).Count -gt 0

try {
    Write-Host "=== Codex Agent Worker Self-Update ===" -ForegroundColor Cyan
    Write-Host "Install dir: $InstallDir" -ForegroundColor Cyan
    Write-Host "Current version: $CurrentVersion" -ForegroundColor Gray

    if ($Archive) {
        $Archive = (Resolve-Path -LiteralPath $Archive).Path
        Write-Host "Using local archive: $Archive" -ForegroundColor Cyan
    }
    else {
        $BaseUrl = if ($Url) { $Url } elseif ($env:CODEX_WORKER_URL) { $env:CODEX_WORKER_URL } else { Get-EnvValue -Path $EnvFile -Name "CODEX_WORKER_URL" }
        if (-not $BaseUrl) {
            Write-Host "No update source configured." -ForegroundColor Red
            Write-Host "Pass -Archive, use -Url, or set CODEX_WORKER_URL in .env." -ForegroundColor Yellow
            exit 1
        }
        $BaseUrl = $BaseUrl.TrimEnd('/')

        Write-Host "Checking latest version from $BaseUrl ..." -ForegroundColor Cyan
        $Latest = Invoke-RestMethod -Uri "$BaseUrl/latest.json" -TimeoutSec 15 -ErrorAction Stop
        $LatestVersion = "$($Latest.version)".Trim()
        if (-not $LatestVersion) { throw "Could not parse version from latest.json." }

        if ($LatestVersion -eq $CurrentVersion -and -not $Force) {
            Write-Host "Already up to date (v$CurrentVersion)." -ForegroundColor Green
            exit 0
        }

        $FilePath = "$($Latest.files.windows)".Trim()
        if (-not $FilePath) { throw "No Windows release artifact found in latest.json." }

        Write-Host "New version available: $CurrentVersion -> $LatestVersion" -ForegroundColor Cyan
        $DownloadDir = Join-Path ([System.IO.Path]::GetTempPath()) ("codex-worker-download-" + [guid]::NewGuid().ToString("N"))
        New-Item -ItemType Directory -Force -Path $DownloadDir | Out-Null
        [void]$TempDirs.Add($DownloadDir)
        $Archive = Join-Path $DownloadDir (Split-Path $FilePath -Leaf)
        $DownloadUrl = "$BaseUrl/$FilePath"
        Write-Host "Downloading: $DownloadUrl" -ForegroundColor Cyan
        Invoke-WebRequest -Uri $DownloadUrl -OutFile $Archive -TimeoutSec 120
    }

    if ([System.IO.Path]::GetExtension($Archive) -ne ".zip") {
        throw "Windows updates require a .zip archive: $Archive"
    }

    $ExtractDir = Join-Path ([System.IO.Path]::GetTempPath()) ("codex-worker-extract-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path $ExtractDir | Out-Null
    [void]$TempDirs.Add($ExtractDir)
    Write-Host "Extracting release..." -ForegroundColor Cyan
    Expand-Archive -LiteralPath $Archive -DestinationPath $ExtractDir -Force

    $InstallScript = Get-ChildItem -Path $ExtractDir -Recurse -Filter "install.ps1" | Select-Object -First 1
    if (-not $InstallScript) { throw "No install.ps1 found in archive." }

    if ($BaseUrl) { $env:CODEX_WORKER_URL = $BaseUrl }
    $env:CODEX_WORKER_HOME = $InstallDir
    & powershell -ExecutionPolicy Bypass -File $InstallScript.FullName -Upgrade
    if ($LASTEXITCODE -ne 0) { throw "Worker installer failed with exit code $LASTEXITCODE." }

    $NewVersion = if (Test-Path $VersionFile) { (Get-Content $VersionFile -Raw).Trim() } else { "unknown" }
    Write-Host "Codex Worker updated: $CurrentVersion -> $NewVersion" -ForegroundColor Green

    if ($NoRestart) {
        Write-Host "Worker not restarted because -NoRestart was used." -ForegroundColor Yellow
    }
    elseif ($WasRunning) {
        Write-Host "Restarting worker..." -ForegroundColor Cyan
        & powershell -ExecutionPolicy Bypass -File (Join-Path $InstallDir "start.ps1")
        if ($LASTEXITCODE -ne 0) { throw "Worker start failed with exit code $LASTEXITCODE." }

        Write-Host "Health-checking worker on port $Port ..." -ForegroundColor Cyan
        if (Test-WorkerHealth -ListenPort $Port -TimeoutSec 30) {
            Write-Host "Worker is healthy after update." -ForegroundColor Green
        }
        else {
            throw "Worker did not become healthy within 30 seconds. Check logs with: codex-worker logs"
        }
    }
    else {
        Write-Host "Worker was not running, so it remains stopped." -ForegroundColor Green
    }
}
catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}
finally {
    foreach ($TempDir in $TempDirs) {
        if (Test-Path $TempDir) { Remove-Item $TempDir -Recurse -Force -ErrorAction SilentlyContinue }
    }
}
