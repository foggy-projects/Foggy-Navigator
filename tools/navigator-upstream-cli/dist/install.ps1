# Navigator Upstream CLI project-local installer.
#
# Run from an upstream project root:
#   powershell -ExecutionPolicy Bypass -File install.ps1

param(
    [string]$ProjectRoot = "",
    [string]$InstallDir = "",
    [string]$ReleaseBaseUrl = "",
    [switch]$Upgrade
)

$ErrorActionPreference = "Stop"

function Write-Utf8NoBom {
    param([string]$Path, [string]$Content)
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Content, $utf8NoBom)
}

function Add-GitIgnoreLine {
    param([string]$GitIgnorePath, [string]$Line)
    $existingText = if (Test-Path $GitIgnorePath) { [System.IO.File]::ReadAllText($GitIgnorePath) } else { "" }
    $existingLines = if ($existingText) { $existingText -split "`r?`n" } else { @() }
    if ($existingLines -notcontains $Line) {
        $prefix = if ($existingText -and -not $existingText.EndsWith("`n")) { "`n" } else { "" }
        Write-Utf8NoBom -Path $GitIgnorePath -Content ($existingText + $prefix + $Line + "`n")
    }
}

$SourceRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not (Test-Path (Join-Path $SourceRoot "lib"))) {
    $candidate = Split-Path -Parent $SourceRoot
    if (Test-Path (Join-Path $candidate "lib")) {
        $SourceRoot = $candidate
    }
}
if (-not (Test-Path (Join-Path $SourceRoot "lib"))) {
    throw "Installer source is missing lib directory: $SourceRoot"
}

if (-not $ProjectRoot) {
    $ProjectRoot = (Get-Location).Path
}
$ProjectRoot = [System.IO.Path]::GetFullPath($ProjectRoot)
if (-not (Test-Path $ProjectRoot)) {
    New-Item -ItemType Directory -Force -Path $ProjectRoot | Out-Null
}

if (-not $InstallDir) {
    $InstallDir = Join-Path $ProjectRoot "tools\navigator-upstream"
}
$InstallDir = [System.IO.Path]::GetFullPath($InstallDir)

$projectRootResolved = (Resolve-Path $ProjectRoot).Path
$installParent = Split-Path -Parent $InstallDir
if (-not $InstallDir.StartsWith($projectRootResolved, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "InstallDir must be inside ProjectRoot. InstallDir=$InstallDir ProjectRoot=$projectRootResolved"
}
if (-not (Test-Path $installParent)) {
    New-Item -ItemType Directory -Force -Path $installParent | Out-Null
}

Write-Host "Installing Navigator Upstream CLI" -ForegroundColor Cyan
Write-Host "  Project: $projectRootResolved"
Write-Host "  Target:  $InstallDir"

if (Test-Path $InstallDir) {
    $leaf = Split-Path -Leaf $InstallDir
    if ($leaf -ne "navigator-upstream") {
        throw "Refusing to replace unexpected install directory: $InstallDir"
    }
    Remove-Item -LiteralPath $InstallDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null

Copy-Item -Path (Join-Path $SourceRoot "*") -Destination $InstallDir -Recurse -Force

$rootWrapper = Join-Path $InstallDir "navi.ps1"
if (-not (Test-Path $rootWrapper)) {
    $binWrapper = Join-Path $InstallDir "bin\navi.ps1"
    if (Test-Path $binWrapper) {
        Copy-Item -LiteralPath $binWrapper -Destination $rootWrapper -Force
    }
}
$rootCmd = Join-Path $InstallDir "navi.cmd"
if (-not (Test-Path $rootCmd)) {
    $binCmd = Join-Path $InstallDir "bin\navi.cmd"
    if (Test-Path $binCmd) {
        Copy-Item -LiteralPath $binCmd -Destination $rootCmd -Force
    }
}

$navigatorDir = Join-Path $projectRootResolved ".navigator"
New-Item -ItemType Directory -Force -Path $navigatorDir | Out-Null
$profile = Join-Path $navigatorDir "upstream.env"
if (-not (Test-Path $profile)) {
    Write-Utf8NoBom -Path $profile -Content @"
NAVI_BASE_URL=http://localhost:8112
NAVI_TENANT_ID=
NAVI_CLIENT_APP_ID=
NAVI_CLIENT_APP_KEY=
NAVI_CLIENT_APP_SECRET=
NAVI_CLIENT_APP_ACCESS_TOKEN=
NAVI_AGENT_CODE=
NAVI_MODEL_CONFIG_ID=
NAVI_POLL_INTERVAL_SECONDS=4
"@
}

$gitIgnore = Join-Path $projectRootResolved ".gitignore"
Add-GitIgnoreLine -GitIgnorePath $gitIgnore -Line ".navigator/upstream.env"
Add-GitIgnoreLine -GitIgnorePath $gitIgnore -Line ".navi-upstream.env"

if (-not $ReleaseBaseUrl -and $env:NAVI_UPSTREAM_CLI_URL) {
    $ReleaseBaseUrl = $env:NAVI_UPSTREAM_CLI_URL
}
if ($ReleaseBaseUrl) {
    Write-Utf8NoBom -Path (Join-Path $InstallDir "RELEASE_URL") -Content $ReleaseBaseUrl
}

Write-Host ""
Write-Host "Installed." -ForegroundColor Green
Write-Host "  Config:  $profile"
Write-Host "  Command: .\tools\navigator-upstream\navi.ps1 upstream config check"
if ($Upgrade) {
    Write-Host "  Upgrade complete." -ForegroundColor Green
}
