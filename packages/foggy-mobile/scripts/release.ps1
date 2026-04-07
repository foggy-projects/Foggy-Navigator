<#
.SYNOPSIS
    Foggy Navigator wgt release script.

.DESCRIPTION
    Flow:
    1. Ask for version and release note
    2. Update manifest.json and package.json
    3. Build wgt package
    4. Publish to uni-admin

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts/release.ps1
#>

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$ManifestPath = Join-Path $ProjectRoot "src\manifest.json"
$PackagePath = Join-Path $ProjectRoot "package.json"
$ScriptDir = Join-Path $ProjectRoot "scripts"
$ApiScript = Join-Path $ScriptDir "uni-admin-api.js"
if (-not ("System.Text.UTF8Encoding" -as [type])) {
    Add-Type -AssemblyName System.Runtime
}

function Write-Utf8NoBomJson([string]$Path, $Value, [int]$Depth = 100) {
    $json = $Value | ConvertTo-Json -Depth $Depth
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $json, $utf8NoBom)
}

function Parse-SemVer([string]$Version) {
    $normalized = ($Version -split '-')[0]
    $normalized = ($normalized -split '\+')[0]
    $parts = $normalized -split '\.'
    if ($parts.Count -lt 3) {
        throw "Invalid semver format: $Version (expected X.Y.Z)"
    }

    return @{
        Major = [int]$parts[0]
        Minor = [int]$parts[1]
        Patch = [int]$parts[2]
    }
}

function Compare-SemVer([string]$Left, [string]$Right) {
    $l = Parse-SemVer $Left
    $r = Parse-SemVer $Right

    if ($l.Major -ne $r.Major) {
        if ($l.Major -gt $r.Major) { return $Left }
        return $Right
    }
    if ($l.Minor -ne $r.Minor) {
        if ($l.Minor -gt $r.Minor) { return $Left }
        return $Right
    }
    if ($l.Patch -ge $r.Patch) { return $Left }
    return $Right
}

function Get-ApiJsonValue([string[]]$ApiArgs) {
    try {
        $output = node $ApiScript @ApiArgs 2>$null |
            Where-Object { $_ -match '^\s*\{' } |
            Select-Object -Last 1
        if ([string]::IsNullOrWhiteSpace($output)) {
            return $null
        }
        return $output | ConvertFrom-Json
    } catch {
        return $null
    }
}

$manifest = Get-Content $ManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
$currentVersion = $manifest.versionName
$currentVersionCode = [int]$manifest.versionCode

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Foggy Navigator - wgt release" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Local version: v$currentVersion (code: $currentVersionCode)" -ForegroundColor Yellow

Write-Host "Checking latest server version..." -ForegroundColor Gray
$latestVersionInfo = Get-ApiJsonValue -ApiArgs @("latest-version")
$serverVersion = $latestVersionInfo.version

if ($serverVersion) {
    Write-Host "Server version: v$serverVersion" -ForegroundColor Yellow
}
else {
    Write-Host "Server version: none" -ForegroundColor Yellow
}

$baseVersion = $currentVersion
if ($serverVersion) {
    $baseVersion = Compare-SemVer $currentVersion $serverVersion
}
$baseParts = Parse-SemVer $baseVersion
$suggestedVersion = "$($baseParts.Major).$($baseParts.Minor).$($baseParts.Patch + 1)"

Write-Host ""
Write-Host "Suggested version: v$suggestedVersion" -ForegroundColor Green

$newVersion = Read-Host "New version (Enter for $suggestedVersion)"
if ([string]::IsNullOrWhiteSpace($newVersion)) {
    $newVersion = $suggestedVersion
    Write-Host "Using version: v$newVersion" -ForegroundColor Gray
}

$newVersionCode = $currentVersionCode + 1
Write-Host "Version code: $currentVersionCode -> $newVersionCode"

Write-Host ""
$releaseTitle = Read-Host "Release title (Enter for v$newVersion)"
if ([string]::IsNullOrWhiteSpace($releaseTitle)) {
    $releaseTitle = "v$newVersion"
}

$releaseNote = Read-Host "Release note"
if ([string]::IsNullOrWhiteSpace($releaseNote)) {
    $releaseNote = "Bug fixes and improvements"
}

$silentInput = Read-Host "Silent update? (y/N)"
$isSilent = $silentInput -in @("y", "Y")

Write-Host ""
Write-Host "--- Confirm release ---" -ForegroundColor Green
Write-Host "Version: $newVersion (code: $newVersionCode)"
Write-Host "Title:   $releaseTitle"
Write-Host "Note:    $releaseNote"
Write-Host "Silent:  $isSilent"
Write-Host ""

$confirm = Read-Host "Continue? (y/N)"
if ($confirm -notin @("y", "Y")) {
    Write-Host "Cancelled" -ForegroundColor Yellow
    exit 0
}

Write-Host ""
Write-Host "[1/3] Update versions..." -ForegroundColor Cyan

$manifestJson = Get-Content $ManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
$manifestJson.versionName = $newVersion
$manifestJson.versionCode = [string]$newVersionCode
Write-Utf8NoBomJson -Path $ManifestPath -Value $manifestJson

$packageJson = Get-Content $PackagePath -Raw -Encoding UTF8 | ConvertFrom-Json
$packageJson.version = $newVersion
Write-Utf8NoBomJson -Path $PackagePath -Value $packageJson

Write-Host "  manifest.json -> v$newVersion (code: $newVersionCode)"
Write-Host "  package.json  -> v$newVersion"

Write-Host ""
Write-Host "[2/3] Build wgt..." -ForegroundColor Cyan

Push-Location $ProjectRoot
try {
    pnpm build:wgt
    if ($LASTEXITCODE -ne 0) {
        throw "wgt build failed"
    }
}
finally {
    Pop-Location
}

$wgtFile = Join-Path $ProjectRoot ("dist\foggy-navigator-{0}.wgt" -f $newVersion)
if (-not (Test-Path $wgtFile)) {
    Write-Host "ERROR: wgt file not found at $wgtFile" -ForegroundColor Red
    exit 1
}
$wgtSizeMb = [math]::Round(((Get-Item $wgtFile).Length / 1MB), 2)
Write-Host ("  wgt: {0} ({1} MB)" -f $wgtFile, $wgtSizeMb)

Write-Host ""
Write-Host "[3/3] Publish to uni-admin..." -ForegroundColor Cyan
Write-Host "  Checking latest native app version..." -ForegroundColor Gray

$latestNativeInfo = Get-ApiJsonValue -ApiArgs @("latest-native-version")
$latestNativeVersion = $latestNativeInfo.version

if ($latestNativeVersion) {
    Write-Host "  Native app version: v$latestNativeVersion" -ForegroundColor Green
}
else {
    Write-Host "  WARNING: no native app version found on server." -ForegroundColor Red
    Write-Host "  A wgt release needs a compatible native app minVersion." -ForegroundColor Red
    $manualVersion = Read-Host "  Enter minVersion manually (blank to cancel)"
    if ([string]::IsNullOrWhiteSpace($manualVersion)) {
        Write-Host "Cancelled" -ForegroundColor Yellow
        exit 0
    }
    $latestNativeVersion = $manualVersion
}

$publishArgs = @(
    $ApiScript, "publish",
    "--type", "wgt",
    "--version", $newVersion,
    "--title", $releaseTitle,
    "--content", $releaseNote,
    "--file", $wgtFile,
    "--minVersion", $latestNativeVersion
)

if ($isSilent) {
    $publishArgs += "--silent"
}

node @publishArgs

if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "Automatic publish failed. Publish manually in uni-admin." -ForegroundColor Yellow
    Write-Host "wgt file: $wgtFile" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host "  Release complete: v$newVersion" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
Write-Host ""
