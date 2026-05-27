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

function Compare-Semver {
    param([string]$Left, [string]$Right)
    try {
        return ([Version](($Left -split '[-+]')[0])).CompareTo([Version](($Right -split '[-+]')[0]))
    } catch {
        return [string]::Compare($Left, $Right, $true)
    }
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$WorkerDir = Split-Path -Parent $ScriptDir
$OutputDir = Join-Path $ScriptDir "output"

$ObsBucket = if ($env:LANGGRAPH_BIZ_WORKER_RELEASE_OBS_BUCKET) {
    $env:LANGGRAPH_BIZ_WORKER_RELEASE_OBS_BUCKET
} else {
    "obs://obs-fe55/langgraph-biz-worker"
}
$BaseUrl = if ($env:LANGGRAPH_BIZ_WORKER_RELEASE_BASE_URL) {
    $env:LANGGRAPH_BIZ_WORKER_RELEASE_BASE_URL
} else {
    "https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/langgraph-biz-worker"
}

$ObsUtil = (Get-Command obsutil -ErrorAction SilentlyContinue).Source
if (-not $ObsUtil) {
    foreach ($candidate in @(
        "D:\work\obsutil_windows_amd64_5.7.9\obsutil.exe",
        "C:\Windows\obsutil.exe",
        "$env:USERPROFILE\obsutil\obsutil.exe"
    )) {
        if (Test-Path $candidate) { $ObsUtil = $candidate; break }
    }
}
if (-not $ObsUtil) {
    throw "obsutil not found. Install and configure obsutil first."
}

if (-not $Version) {
    $initFile = Join-Path $WorkerDir "src\langgraph_biz_worker\__init__.py"
    $match = (Get-Content $initFile | Select-String '__version__\s*=\s*"([^"]+)"')
    if (-not $match) { throw "Could not read BizWorker version." }
    $Version = $match.Matches.Groups[1].Value
}

Write-Host "=== LangGraph BizWorker OBS Upload ===" -ForegroundColor Cyan
Write-Host "Version:  $Version"
Write-Host "Bucket:   $ObsBucket"
Write-Host "Base URL: $BaseUrl"
Write-Host "obsutil:  $ObsUtil"

if (-not $AllowSameVersion) {
    try {
        $remote = Invoke-RestMethod -Uri "$BaseUrl/latest.json?ts=$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())" -TimeoutSec 15
        $remoteVersion = [string]$remote.version
        if ($remoteVersion) {
            $comparison = Compare-Semver -Left $Version -Right $remoteVersion
            if ($comparison -eq 0) {
                throw "Remote latest is already $remoteVersion. Bump version or use -AllowSameVersion for metadata repair."
            }
            if ($comparison -lt 0) {
                throw "Local version $Version is older than remote latest $remoteVersion."
            }
        }
    } catch {
        if ($_.Exception.Message -like "Remote latest is already*" -or $_.Exception.Message -like "Local version*") {
            throw
        }
        Write-Host "Could not read remote latest.json, continuing. $($_.Exception.Message)" -ForegroundColor Yellow
    }
}

if (-not (Test-Path $OutputDir)) {
    throw "release/output not found. Run release/package.ps1 first."
}
$archives = Get-ChildItem $OutputDir -File | Where-Object { $_.Name -like "langgraph-biz-worker-$Version-*" }
if (-not $archives) {
    throw "No archives for version $Version found in $OutputDir"
}

$files = @{}
$sha256 = @{}
foreach ($archive in $archives) {
    $obsPath = "$ObsBucket/$Version/$($archive.Name)"
    Write-Host "Uploading $($archive.Name) -> $obsPath" -ForegroundColor Gray
    & $ObsUtil cp $archive.FullName $obsPath -f
    if ($LASTEXITCODE -ne 0) { throw "Failed to upload $($archive.Name)" }

    if ($archive.Name -like "*-windows.*") { $key = "windows" }
    elseif ($archive.Name -like "*-linux.*") { $key = "linux" }
    elseif ($archive.Name -like "*-macos.*") { $key = "macos" }
    else { $key = $archive.BaseName }
    $files[$key] = "$Version/$($archive.Name)"
    $sha256[$key] = (Get-FileHash -Algorithm SHA256 -Path $archive.FullName).Hash.ToLowerInvariant()
}

$gitCommit = ""
$gitDirty = $false
try {
    $gitCommit = (git -C $WorkerDir rev-parse HEAD 2>$null).Trim()
    $gitDirty = [bool]((git -C $WorkerDir status --short -- . 2>$null | Out-String).Trim())
} catch {
    $gitCommit = ""
    $gitDirty = $false
}

$latest = [ordered]@{
    version = $Version
    released = (Get-Date -Format "yyyy-MM-dd")
    buildTimeUtc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    gitCommit = $gitCommit
    gitDirty = $gitDirty
    files = $files
    sha256 = $sha256
} | ConvertTo-Json -Depth 5

$latestPath = Join-Path $OutputDir "latest.json"
Write-Utf8NoBom -Path $latestPath -Content $latest
& $ObsUtil cp $latestPath "$ObsBucket/latest.json" -f
if ($LASTEXITCODE -ne 0) { throw "Failed to upload latest.json" }

$bashTemplate = Join-Path $ScriptDir "remote-install.sh"
$bashOut = Join-Path $OutputDir "install.sh"
$bashContent = (Get-Content $bashTemplate -Raw) -replace 'RELEASE_BASE_URL="[^"]*"', "RELEASE_BASE_URL=`"$BaseUrl`""
Write-Utf8NoBom -Path $bashOut -Content $bashContent
& $ObsUtil cp $bashOut "$ObsBucket/install.sh" -f
if ($LASTEXITCODE -ne 0) { throw "Failed to upload install.sh" }

$psTemplate = Join-Path $ScriptDir "remote-install.ps1"
$psOut = Join-Path $OutputDir "install.ps1"
$psContent = (Get-Content $psTemplate -Raw) -replace '\$ReleaseBaseUrl\s*=\s*"[^"]*"', "`$ReleaseBaseUrl = `"$BaseUrl`""
Write-Utf8NoBom -Path $psOut -Content $psContent
& $ObsUtil cp $psOut "$ObsBucket/install.ps1" -f
if ($LASTEXITCODE -ne 0) { throw "Failed to upload install.ps1" }

Write-Host ""
Write-Host "Upload complete." -ForegroundColor Green
Write-Host "  $BaseUrl/latest.json"
Write-Host "  curl -fsSL $BaseUrl/install.sh | bash"
Write-Host "  irm $BaseUrl/install.ps1 | iex"
