# Navigator Upstream CLI upload script for Huawei Cloud OBS.

param(
    [string]$Version = "",
    [switch]$AllowSameVersion
)

$ErrorActionPreference = "Stop"

function Write-Utf8NoBom {
    param([string]$Path, [string]$Content)
    $Content = $Content -replace "`r`n", "`n"
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Content, $utf8NoBom)
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ToolDir = Split-Path -Parent $ScriptDir
$OutputDir = Join-Path $ScriptDir "output"
$DotEnv = Join-Path $ToolDir ".env"

if (-not (Test-Path $DotEnv)) {
    throw ".env not found. Copy tools/navigator-upstream-cli/.env.example to .env and configure RELEASE_OBS_BUCKET / RELEASE_BASE_URL."
}

$obsBucket = ""
$baseUrl = ""
Get-Content $DotEnv | ForEach-Object {
    if ($_ -match "^RELEASE_OBS_BUCKET=(.+)") { $obsBucket = $Matches[1].Trim() }
    if ($_ -match "^RELEASE_BASE_URL=(.+)") { $baseUrl = $Matches[1].Trim() }
}
if (-not $obsBucket -or -not $baseUrl) {
    throw "RELEASE_OBS_BUCKET and RELEASE_BASE_URL are required in $DotEnv"
}

$obsUtil = (Get-Command obsutil -ErrorAction SilentlyContinue).Source
if (-not $obsUtil) {
    foreach ($candidate in @(
        "D:\work\obsutil_windows_amd64_5.7.9\obsutil.exe",
        "C:\Windows\obsutil.exe",
        "$env:USERPROFILE\obsutil\obsutil.exe"
    )) {
        if (Test-Path $candidate) { $obsUtil = $candidate; break }
    }
}
if (-not $obsUtil) {
    throw "obsutil not found in PATH"
}

if (-not $Version) {
    $archives = Get-ChildItem $OutputDir -File -Filter "navigator-upstream-cli-*-windows.zip" | Sort-Object LastWriteTime -Descending
    if (-not $archives) {
        throw "No archives found in $OutputDir"
    }
    if ($archives[0].Name -match "^navigator-upstream-cli-(.+)-windows\.zip$") {
        $Version = $Matches[1]
    }
}

Write-Host "=== Navigator Upstream CLI OBS Upload ===" -ForegroundColor Cyan
Write-Host "Version:  $Version"
Write-Host "Bucket:   $obsBucket"
Write-Host "Base URL: $baseUrl"

if (-not $AllowSameVersion) {
    try {
        $remote = Invoke-RestMethod -Uri "$baseUrl/latest.json?ts=$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())" -TimeoutSec 15
        if ($remote.version -eq $Version) {
            throw "Remote latest is already $Version. Bump version or use -AllowSameVersion for metadata repair."
        }
    }
    catch {
        if ($_.Exception.Message -like "Remote latest is already*") {
            throw
        }
        Write-Host "Could not read remote latest.json, continuing. $($_.Exception.Message)" -ForegroundColor Yellow
    }
}

$archive = Join-Path $OutputDir "navigator-upstream-cli-$Version-windows.zip"
if (-not (Test-Path $archive)) {
    throw "Archive not found: $archive"
}
$sha = (Get-FileHash -Algorithm SHA256 -Path $archive).Hash.ToLowerInvariant()

& $obsUtil cp $archive "$obsBucket/$Version/$(Split-Path $archive -Leaf)" -f
if ($LASTEXITCODE -ne 0) { throw "Failed to upload archive" }

$latest = @{
    version = $Version
    released = (Get-Date -Format "yyyy-MM-dd")
    files = @{
        windows = "$Version/$(Split-Path $archive -Leaf)"
    }
    sha256 = @{
        windows = $sha
    }
} | ConvertTo-Json -Depth 4

$latestPath = Join-Path $OutputDir "latest.json"
Write-Utf8NoBom -Path $latestPath -Content $latest
& $obsUtil cp $latestPath "$obsBucket/latest.json" -f
if ($LASTEXITCODE -ne 0) { throw "Failed to upload latest.json" }

$remoteInstall = Join-Path $ScriptDir "remote-install.ps1"
$installContent = (Get-Content $remoteInstall -Raw) -replace '\$ReleaseBaseUrl\s*=\s*"[^"]*"', "`$ReleaseBaseUrl = `"$baseUrl`""
$installPath = Join-Path $OutputDir "install.ps1"
Write-Utf8NoBom -Path $installPath -Content $installContent
& $obsUtil cp $installPath "$obsBucket/install.ps1" -f
if ($LASTEXITCODE -ne 0) { throw "Failed to upload install.ps1" }

Write-Host ""
Write-Host "Upload complete." -ForegroundColor Green
Write-Host "Install from upstream project root:" -ForegroundColor Cyan
Write-Host "  irm $baseUrl/install.ps1 | iex"
