# Codex Agent Worker - SDK Update (Release / OBS-installed)
# Upgrades just @openai/codex-sdk (and the bundled codex CLI it ships)
# WITHOUT replacing the worker itself.
#
# This script is shipped INSIDE the OBS-distributed archive and lives in
# $InstallDir alongside start.ps1 / stop.ps1. End users normally invoke it via:
#   codex-worker upgrade-sdk
#   codex-worker upgrade-sdk -SdkVersion 0.144.1
#   codex-worker upgrade-sdk -SdkVersion 0.142.5 -Force -NoRestart
#   codex-worker upgrade-sdk -NoRestart
#   codex-worker upgrade-sdk -Registry https://registry.npmjs.org/
#
# Differences from the dev-side update-sdk.ps1 (in tools/codex-agent-worker root):
#   - No `npm run typecheck` (OBS install has no devDependencies and no src/)
#   - Uses `npm install ... --omit=dev` to stay consistent with install.ps1
#   - Health-check smoke test after restart
#   - On failure, hints user to run `codex-worker upgrade` to reinstall

param(
    [string]$SdkVersion = "",
    [switch]$NoRestart,
    [string]$Registry = "",
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$InstallDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$DefaultPort = 3051
$OfficialNpmRegistry = "https://registry.npmjs.org/"

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

function Get-NpmRegistry {
    param([string]$NpmPath)

    try {
        $lines = @(& $NpmPath --loglevel=silent config get registry 2>$null)
        if ($LASTEXITCODE -eq 0 -and $lines.Count -gt 0 -and $lines[0]) {
            return $lines[0].Trim()
        }
    }
    catch { }

    return "unknown"
}

function Normalize-RegistryUrl {
    param([string]$RegistryUrl)

    if (-not $RegistryUrl -or $RegistryUrl -eq "unknown") {
        return ""
    }

    return $RegistryUrl.Trim().TrimEnd([char]'/')
}

function Invoke-NpmInstallWithRegistryFallback {
    param(
        [string]$NpmPath,
        [string]$Target,
        [string[]]$ExtraArgs = @(),
        [string]$Registry = ""
    )

    $installArgs = @("install", $Target) + $ExtraArgs
    if ($Registry) {
        $installArgs += "--registry=$Registry"
    }

    Write-Host "Running: npm $($installArgs -join ' ')" -ForegroundColor Cyan
    & $NpmPath @installArgs
    if ($LASTEXITCODE -eq 0) {
        return $true
    }

    if ($Registry) {
        return $false
    }

    $configuredRegistry = Get-NpmRegistry -NpmPath $NpmPath
    if ((Normalize-RegistryUrl $configuredRegistry) -eq (Normalize-RegistryUrl $OfficialNpmRegistry)) {
        return $false
    }

    Write-Host "npm install failed using registry: $configuredRegistry" -ForegroundColor Yellow
    Write-Host "Retrying with official npm registry: $OfficialNpmRegistry" -ForegroundColor Yellow
    $retryArgs = @("install", $Target) + $ExtraArgs + @("--registry=$OfficialNpmRegistry")
    Write-Host "Running: npm $($retryArgs -join ' ')" -ForegroundColor Cyan
    & $NpmPath @retryArgs
    return ($LASTEXITCODE -eq 0)
}

function Resolve-SdkVersion {
    param(
        [string]$NpmPath,
        [string]$RequestedVersion = "",
        [string]$Registry = ""
    )

    $spec = if ($RequestedVersion) { $RequestedVersion } else { "latest" }
    $viewArgs = @("view", "@openai/codex-sdk@$spec", "version")
    if ($Registry) { $viewArgs += "--registry=$Registry" }
    $lines = @(& $NpmPath @viewArgs 2>$null)
    if ($LASTEXITCODE -ne 0 -or $lines.Count -eq 0) { return "" }
    return $lines[-1].Trim()
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
                $resp = Invoke-RestMethod -Uri $url -TimeoutSec 3 -ErrorAction Stop
                if ($resp.status -eq "ok" -and $resp.codex_sdk_available -eq $true -and $resp.codex_sdk_compatible -eq $true) {
                    return @{ ok = $true; body = ($resp | ConvertTo-Json -Compress) }
                }
            }
            catch { }
        }
        Start-Sleep -Seconds 1
    }
    return @{ ok = $false; body = "" }
}

$Port = Get-Port -RootDir $InstallDir
$StopScript = Join-Path $InstallDir "stop.ps1"
$StartScript = Join-Path $InstallDir "start.ps1"
$Npm = Resolve-Npm
$NpmRegistry = if ($Registry) { "$Registry (script override)" } else { Get-NpmRegistry -NpmPath $Npm }

if (-not (Test-Path (Join-Path $InstallDir "package.json"))) {
    Write-Host "ERROR: package.json not found in $InstallDir." -ForegroundColor Red
    Write-Host "This script must be run from a Codex Worker install directory." -ForegroundColor Yellow
    exit 1
}

if ($Force -and -not $SdkVersion) {
    Write-Host "ERROR: -Force requires an explicit -SdkVersion." -ForegroundColor Red
    exit 1
}
if ($SdkVersion) {
    $EnsureSdkScript = Join-Path $InstallDir "scripts\ensure-sdk.mjs"
    if (-not (Test-Path -LiteralPath $EnsureSdkScript)) {
        Write-Host "ERROR: SDK preflight script not found: $EnsureSdkScript" -ForegroundColor Red
        exit 1
    }
    $checkArgs = @($EnsureSdkScript, "--worker-dir", $InstallDir, "--check-target", $SdkVersion)
    if ($Force) { $checkArgs += "--force" }
    & node @checkArgs
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

$wasRunning = (Get-WorkerPids -ListenPort $Port).Count -gt 0

Write-Host "=== Codex Worker SDK Update ===" -ForegroundColor Cyan
Write-Host "Install dir: $InstallDir" -ForegroundColor Cyan
Write-Host "Port: $Port" -ForegroundColor Cyan
Write-Host "npm: $Npm" -ForegroundColor Cyan
Write-Host "npm registry: $NpmRegistry" -ForegroundColor Cyan

$sdkBefore = Get-PackageVersion -RootDir $InstallDir -PackageName "@openai/codex-sdk"
$cliBefore = Get-PackageVersion -RootDir $InstallDir -PackageName "@openai/codex"
Write-Host "@openai/codex-sdk before: $sdkBefore" -ForegroundColor Gray
Write-Host "@openai/codex (CLI) before: $cliBefore" -ForegroundColor Gray

$resolvedSdkVersion = Resolve-SdkVersion -NpmPath $Npm -RequestedVersion $SdkVersion -Registry $Registry
if (-not $resolvedSdkVersion) {
    throw "Could not resolve the requested @openai/codex-sdk version; refusing an unverified update."
}
$runtimeVersionHelper = Join-Path $InstallDir "scripts\runtime-dependency-version.mjs"
$sdkComparison = (& node $runtimeVersionHelper --compare $sdkBefore $resolvedSdkVersion).Trim()
if ($sdkComparison -eq "1") {
    Write-Host "Installed @openai/codex-sdk $sdkBefore is newer than requested $resolvedSdkVersion; leaving it unchanged." -ForegroundColor Yellow
    exit 0
}

if ($wasRunning) {
    Write-Host "Worker is running on port $Port. Stopping before upgrade..." -ForegroundColor Yellow
    & powershell -ExecutionPolicy Bypass -File $StopScript
}

Set-Location $InstallDir

$target = "@openai/codex-sdk@$resolvedSdkVersion"

if (-not (Invoke-NpmInstallWithRegistryFallback -NpmPath $Npm -Target $target -ExtraArgs @("--omit=dev") -Registry $Registry)) {
    Write-Host "npm install FAILED. Worker has not been restarted." -ForegroundColor Red
    Write-Host "Recovery: install a compatible newer SDK, then retry; Worker upgrade will preserve the installed SDK." -ForegroundColor Yellow
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
    Write-Host "Recovery: install a compatible newer SDK, then retry; Worker upgrade will preserve the installed SDK." -ForegroundColor Yellow
    exit 1
}
