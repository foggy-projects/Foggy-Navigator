# Gemini Agent Worker - Upload to Huawei Cloud OBS
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File release\upload.ps1
#   powershell -ExecutionPolicy Bypass -File release\upload.ps1 -Version 1.0.0
#   powershell -ExecutionPolicy Bypass -File release\upload.ps1 -Version 1.0.0 -AllowSameVersion

param(
    [string]$Version = "",
    [switch]$AllowSameVersion
)

$ErrorActionPreference = "Stop"

function Write-UnixFile {
    param([string]$Path, [string]$Content)
    $Content = $Content -replace "`r`n", "`n"
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Content, $utf8NoBom)
}

function Compare-Semver {
    param([string]$Left, [string]$Right)

    try {
        $leftCore = ($Left -split '[-+]')[0]
        $rightCore = ($Right -split '[-+]')[0]
        $leftVersion = [Version]$leftCore
        $rightVersion = [Version]$rightCore
        return $leftVersion.CompareTo($rightVersion)
    }
    catch {
        return [string]::Compare($Left, $Right, $true)
    }
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$WorkerDir = Split-Path -Parent $ScriptDir
$OutputDir = Join-Path $WorkerDir "release\output"

$ObsBucket = ""
$BaseUrl = ""
$DotEnv = Join-Path $WorkerDir ".env"
if (Test-Path $DotEnv) {
    Get-Content $DotEnv | ForEach-Object {
        if ($_ -match "^RELEASE_OBS_BUCKET=(.+)") { $ObsBucket = $Matches[1].Trim() }
        if ($_ -match "^RELEASE_BASE_URL=(.+)") { $BaseUrl = $Matches[1].Trim() }
    }
}

if (-not $ObsBucket) { $ObsBucket = $env:RELEASE_OBS_BUCKET }
if (-not $BaseUrl) { $BaseUrl = $env:RELEASE_BASE_URL }

if (-not $ObsBucket -or -not $BaseUrl) {
    Write-Host "ERROR: RELEASE_OBS_BUCKET and RELEASE_BASE_URL must be set in .env or environment" -ForegroundColor Red
    Write-Host "See .env.example for reference." -ForegroundColor Yellow
    exit 1
}

$ObsUtil = (Get-Command obsutil -ErrorAction SilentlyContinue).Source
if (-not $ObsUtil) {
    foreach ($candidate in @("C:\Windows\obsutil.exe", "$env:USERPROFILE\obsutil\obsutil.exe")) {
        if (Test-Path $candidate) { $ObsUtil = $candidate; break }
    }
}
if (-not $ObsUtil) {
    Write-Host "ERROR: obsutil not found in PATH." -ForegroundColor Red
    exit 1
}
Write-Host "obsutil: $ObsUtil" -ForegroundColor Gray

if (-not $Version) {
    $PackageJson = Get-Content (Join-Path $WorkerDir "package.json") -Raw | ConvertFrom-Json
    $Version = $PackageJson.version
}

Write-Host "=== Gemini Agent Worker - OBS Upload ===" -ForegroundColor Cyan
Write-Host "Version:  $Version" -ForegroundColor Cyan
Write-Host "Bucket:   $ObsBucket" -ForegroundColor Cyan
Write-Host "Base URL: $BaseUrl" -ForegroundColor Cyan
Write-Host ""

if (-not $AllowSameVersion) {
    try {
        $cacheBust = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        $remoteLatest = Invoke-RestMethod -Uri "$BaseUrl/latest.json?ts=$cacheBust" -Headers @{ "Cache-Control" = "no-cache" } -TimeoutSec 15
        $remoteVersion = [string]$remoteLatest.version

        if ($remoteVersion) {
            $comparison = Compare-Semver -Left $Version -Right $remoteVersion
            if ($comparison -eq 0) {
                Write-Host "ERROR: Remote latest is already version $remoteVersion. Bump the version before uploading." -ForegroundColor Red
                Write-Host "Suggested: powershell -ExecutionPolicy Bypass -File release\package.ps1 -OS all -Bump patch -Upload" -ForegroundColor Yellow
                Write-Host "Override only when repairing metadata: add -AllowSameVersion to upload.ps1." -ForegroundColor Yellow
                exit 1
            }
            elseif ($comparison -lt 0) {
                Write-Host "ERROR: Local version $Version is older than remote latest $remoteVersion." -ForegroundColor Red
                exit 1
            }
            else {
                Write-Host "Remote latest version: $remoteVersion -> uploading $Version" -ForegroundColor Gray
            }
        }
    }
    catch {
        Write-Host "WARNING: Could not read remote latest.json, continuing upload. $($_.Exception.Message)" -ForegroundColor Yellow
    }
}

if (-not (Test-Path $OutputDir)) {
    Write-Host "ERROR: release/output/ directory not found. Run release/package.ps1 first." -ForegroundColor Red
    exit 1
}

$archives = Get-ChildItem $OutputDir -File | Where-Object { $_.Name -like "gemini-worker-$Version-*" }
if (-not $archives -or $archives.Count -eq 0) {
    Write-Host "ERROR: No archives found for version $Version in release/output/" -ForegroundColor Red
    exit 1
}

Write-Host "Uploading archives..." -ForegroundColor Cyan
foreach ($archive in $archives) {
    $obsPath = "$ObsBucket/$Version/$($archive.Name)"
    Write-Host "  $($archive.Name) -> $obsPath" -ForegroundColor Gray
    & $ObsUtil cp $archive.FullName $obsPath -f
    if ($LASTEXITCODE -ne 0) {
        Write-Host "ERROR: Failed to upload $($archive.Name)" -ForegroundColor Red
        exit 1
    }
}

Write-Host "Generating latest.json..." -ForegroundColor Cyan
$filesMap = @{}
foreach ($archive in $archives) {
    $name = $archive.Name
    if ($name -like "*-windows.*") { $filesMap["windows"] = "$Version/$name" }
    elseif ($name -like "*-linux.*") { $filesMap["linux"] = "$Version/$name" }
    elseif ($name -like "*-macos.*") { $filesMap["macos"] = "$Version/$name" }
}

$latestJson = @{
    version  = $Version
    released = (Get-Date -Format "yyyy-MM-dd")
    files    = $filesMap
} | ConvertTo-Json -Depth 3

$latestJsonPath = Join-Path $OutputDir "latest.json"
Write-UnixFile -Path $latestJsonPath -Content $latestJson
& $ObsUtil cp $latestJsonPath "$ObsBucket/latest.json" -f
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: Failed to upload latest.json" -ForegroundColor Red
    exit 1
}

Write-Host "Generating bootstrap install scripts..." -ForegroundColor Cyan

$bashBootstrap = Join-Path $ScriptDir "remote-install.sh"
if (Test-Path $bashBootstrap) {
    $bashContent = (Get-Content $bashBootstrap -Raw) -replace 'RELEASE_BASE_URL="[^"]*"', "RELEASE_BASE_URL=`"$BaseUrl`""
    $tmpBash = Join-Path $OutputDir "install.sh"
    Write-UnixFile -Path $tmpBash -Content $bashContent
    & $ObsUtil cp $tmpBash "$ObsBucket/install.sh" -f
    if ($LASTEXITCODE -ne 0) {
        Write-Host "ERROR: Failed to upload install.sh" -ForegroundColor Red
        exit 1
    }
}

$ps1Bootstrap = Join-Path $ScriptDir "remote-install.ps1"
if (Test-Path $ps1Bootstrap) {
    $ps1Content = (Get-Content $ps1Bootstrap -Raw) -replace '\$ReleaseBaseUrl\s*=\s*"[^"]*"', "`$ReleaseBaseUrl = `"$BaseUrl`""
    $tmpPs1 = Join-Path $OutputDir "install.ps1"
    Write-UnixFile -Path $tmpPs1 -Content $ps1Content
    & $ObsUtil cp $tmpPs1 "$ObsBucket/install.ps1" -f
    if ($LASTEXITCODE -ne 0) {
        Write-Host "ERROR: Failed to upload install.ps1" -ForegroundColor Red
        exit 1
    }
}

Write-Host ""
Write-Host "=== Upload Complete ===" -ForegroundColor Green
Write-Host "Remote install commands:" -ForegroundColor Cyan
Write-Host "  Linux/Mac:  curl -sSL $BaseUrl/install.sh | bash" -ForegroundColor White
Write-Host "  Windows:    irm $BaseUrl/install.ps1 | iex" -ForegroundColor White
Write-Host "Upgrade:      gemini-worker upgrade" -ForegroundColor White
