# Navigator Upstream CLI wrapper for Windows.
param(
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
    [string[]]$CliArgs
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$InstallDir = $ScriptDir
if ((Split-Path -Leaf $ScriptDir) -eq "bin") {
    $InstallDir = Split-Path -Parent $ScriptDir
}

$VersionFile = Join-Path $InstallDir "VERSION"
$Version = if (Test-Path $VersionFile) { (Get-Content $VersionFile -Raw).Trim() } else { "unknown" }

function Get-ProjectRoot {
    if ($env:NAVI_UPSTREAM_PROJECT_ROOT) {
        return (Resolve-Path $env:NAVI_UPSTREAM_PROJECT_ROOT).Path
    }
    $toolsDir = Split-Path -Parent $InstallDir
    if ((Split-Path -Leaf $toolsDir) -eq "tools") {
        return (Split-Path -Parent $toolsDir)
    }
    return (Get-Location).Path
}

function Has-Option {
    param([string[]]$Values, [string]$Name)
    foreach ($value in $Values) {
        if ($value -eq $Name -or $value.StartsWith("$Name=")) {
            return $true
        }
    }
    return $false
}

function Get-ReleaseBaseUrl {
    if ($env:NAVI_UPSTREAM_CLI_URL) {
        return $env:NAVI_UPSTREAM_CLI_URL
    }
    $releaseUrlFile = Join-Path $InstallDir "RELEASE_URL"
    if (Test-Path $releaseUrlFile) {
        return (Get-Content $releaseUrlFile -Raw).Trim()
    }
    return ""
}

function Invoke-SelfUpdate {
    $baseUrl = Get-ReleaseBaseUrl
    if (-not $baseUrl) {
        Write-Host "No release URL configured." -ForegroundColor Yellow
        Write-Host "Set NAVI_UPSTREAM_CLI_URL or reinstall from the remote install script."
        exit 2
    }

    Write-Host "Checking Navigator Upstream CLI updates from $baseUrl ..." -ForegroundColor Cyan
    $latest = Invoke-RestMethod -Uri "$baseUrl/latest.json" -TimeoutSec 20
    $latestVersion = [string]$latest.version
    if (-not $latestVersion) {
        throw "Could not parse version from latest.json"
    }
    if ($latestVersion -eq $Version) {
        Write-Host "Already up to date (v$Version)." -ForegroundColor Green
        return
    }

    $filePath = $latest.files.windows
    if (-not $filePath) {
        throw "latest.json does not contain files.windows"
    }
    $downloadUrl = "$baseUrl/$filePath"
    Write-Host "Updating: $Version -> $latestVersion" -ForegroundColor Cyan
    Write-Host "Downloading: $downloadUrl" -ForegroundColor Cyan

    $tmpDir = Join-Path ([System.IO.Path]::GetTempPath()) "navigator-upstream-cli-update"
    if (Test-Path $tmpDir) {
        Remove-Item -LiteralPath $tmpDir -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
    $archive = Join-Path $tmpDir (Split-Path $filePath -Leaf)
    Invoke-WebRequest -Uri $downloadUrl -OutFile $archive

    $expectedSha = $latest.sha256.windows
    if ($expectedSha) {
        $actualSha = (Get-FileHash -Algorithm SHA256 -Path $archive).Hash.ToLowerInvariant()
        if ($actualSha -ne ([string]$expectedSha).ToLowerInvariant()) {
            throw "SHA256 mismatch for downloaded archive"
        }
    }

    Expand-Archive -Path $archive -DestinationPath $tmpDir -Force
    $installScript = Get-ChildItem -Path $tmpDir -Recurse -Filter "install.ps1" | Select-Object -First 1
    if (-not $installScript) {
        throw "No install.ps1 found in downloaded archive"
    }

    & powershell -ExecutionPolicy Bypass -File $installScript.FullName `
        -ProjectRoot (Get-ProjectRoot) `
        -ReleaseBaseUrl $baseUrl `
        -Upgrade
    Remove-Item -LiteralPath $tmpDir -Recurse -Force -ErrorAction SilentlyContinue
}

function Invoke-JavaCli {
    $libDir = Join-Path $InstallDir "lib"
    if (-not (Test-Path $libDir)) {
        Write-Host "lib directory not found: $libDir" -ForegroundColor Red
        exit 1
    }
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
        Write-Host "java not found in PATH. Install JDK 17+ first." -ForegroundColor Red
        exit 1
    }

    $projectRoot = Get-ProjectRoot
    $profile = Join-Path $projectRoot ".navigator\upstream.env"
    $effectiveArgs = @($CliArgs)
    if (-not (Has-Option -Values $effectiveArgs -Name "--profile")) {
        $effectiveArgs += @("--profile", $profile)
    }

    $classpath = (Get-ChildItem -Path $libDir -Filter "*.jar" -File | ForEach-Object { $_.FullName }) -join [System.IO.Path]::PathSeparator
    if (-not $classpath) {
        Write-Host "No jar files found in $libDir" -ForegroundColor Red
        exit 1
    }

    Push-Location $projectRoot
    try {
        & java -cp $classpath com.foggy.navigator.sdk.cli.UpstreamCli @effectiveArgs
        exit $LASTEXITCODE
    }
    finally {
        Pop-Location
    }
}

if ($CliArgs.Count -ge 2 -and $CliArgs[0] -eq "self" -and $CliArgs[1] -in @("update", "upgrade")) {
    Invoke-SelfUpdate
    exit 0
}

if ($CliArgs.Count -ge 1 -and $CliArgs[0] -in @("version", "--version", "-v")) {
    Write-Host "navigator-upstream-cli $Version"
    exit 0
}

Invoke-JavaCli
