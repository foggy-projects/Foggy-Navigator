# Codex Agent Worker - Update bundled @openai/codex-sdk (and the codex CLI it ships)
# Usage:
#   powershell -ExecutionPolicy Bypass -File update.ps1
#   powershell -ExecutionPolicy Bypass -File update.ps1 -NoRestart
#   powershell -ExecutionPolicy Bypass -File update.ps1 -SdkVersion 0.130.0
#   powershell -ExecutionPolicy Bypass -File update.ps1 -Registry https://registry.npmjs.org/
#
# Notes:
#   - @openai/codex-sdk pulls @openai/codex (the CLI) as a transitive dep with platform-specific
#     binaries (codex-win32-x64, codex-darwin-arm64, ...). Upgrading the SDK upgrades the CLI.
#   - Plain `npm update` won't bump across minors because package.json pins ^0.x.y; this script
#     runs `npm install @openai/codex-sdk@<version>` so package.json + lockfile are rewritten.

param(
    [string]$SdkVersion = "",
    [switch]$NoRestart,
    [string]$Registry = ""
)

$ErrorActionPreference = "Stop"
$WorkerDir = Split-Path -Parent $MyInvocation.MyCommand.Path
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

function Get-CodexCliVersion {
    param([string]$RootDir)

    # @openai/codex-sdk depends on @openai/codex (the CLI npm package).
    # Its actual binary version == the @openai/codex package.json "version" field.
    return Get-PackageVersion -RootDir $RootDir -PackageName "@openai/codex"
}

function Resolve-Npm {
    $cmd = Get-Command npm.cmd -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }

    $cmd = Get-Command npm -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }

    throw "npm not found on PATH. Please install Node.js (>=18) first."
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

$Port = Get-Port -RootDir $WorkerDir
$StopScript = Join-Path $WorkerDir "stop.ps1"
$StartScript = Join-Path $WorkerDir "start.ps1"
$Npm = Resolve-Npm
$NpmRegistry = if ($Registry) { "$Registry (script override)" } else { Get-NpmRegistry -NpmPath $Npm }

$wasRunning = (Get-WorkerPids -ListenPort $Port).Count -gt 0

Write-Host "=== Codex Agent Worker Update ===" -ForegroundColor Cyan
Write-Host "Worker dir: $WorkerDir" -ForegroundColor Cyan
Write-Host "Port: $Port" -ForegroundColor Cyan
Write-Host "npm: $Npm" -ForegroundColor Cyan
Write-Host "npm registry: $NpmRegistry" -ForegroundColor Cyan

$sdkBefore = Get-PackageVersion -RootDir $WorkerDir -PackageName "@openai/codex-sdk"
$cliBefore = Get-CodexCliVersion -RootDir $WorkerDir
Write-Host "@openai/codex-sdk before: $sdkBefore" -ForegroundColor Gray
Write-Host "@openai/codex (CLI) before: $cliBefore" -ForegroundColor Gray

if ($wasRunning) {
    Write-Host "Worker is running on port $Port. Stopping before upgrade..." -ForegroundColor Yellow
    & powershell -ExecutionPolicy Bypass -File $StopScript
}

Set-Location $WorkerDir

if ($SdkVersion) {
    $target = "@openai/codex-sdk@$SdkVersion"
} else {
    $target = "@openai/codex-sdk@latest"
}

if (-not (Invoke-NpmInstallWithRegistryFallback -NpmPath $Npm -Target $target -Registry $Registry)) {
    throw "npm install $target failed."
}

Write-Host "Running: npm run typecheck (sanity check)" -ForegroundColor Cyan
& $Npm run typecheck
if ($LASTEXITCODE -ne 0) {
    Write-Host "typecheck FAILED after upgrade. The new SDK may have breaking changes." -ForegroundColor Red
    Write-Host "Worker has NOT been restarted. Inspect errors above before retrying." -ForegroundColor Red
    exit 1
}

$sdkAfter = Get-PackageVersion -RootDir $WorkerDir -PackageName "@openai/codex-sdk"
$cliAfter = Get-CodexCliVersion -RootDir $WorkerDir

Write-Host "@openai/codex-sdk after: $sdkAfter" -ForegroundColor Green
Write-Host "@openai/codex (CLI) after: $cliAfter" -ForegroundColor Green

if ($NoRestart) {
    Write-Host "Update complete. Worker not restarted because -NoRestart was used." -ForegroundColor Yellow
} elseif ($wasRunning) {
    Write-Host "Restarting worker..." -ForegroundColor Cyan
    & powershell -ExecutionPolicy Bypass -File $StartScript
} else {
    Write-Host "Update complete. Worker was not running, so no restart was needed." -ForegroundColor Green
}
