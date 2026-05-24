# Navigator Upstream CLI upload script for Huawei Cloud OBS.

param(
    [string]$Version = "",
    [switch]$AllowSameVersion,
    [switch]$SkipSmoke
)

$ErrorActionPreference = "Stop"

function Write-Utf8NoBom {
    param([string]$Path, [string]$Content)
    $Content = $Content -replace "`r`n", "`n"
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Content, $utf8NoBom)
}

function Get-WebContentString {
    param([string]$Uri)
    $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -Headers @{ "Cache-Control" = "no-cache" } -TimeoutSec 30
    if ($response.Content -is [byte[]]) {
        return [System.Text.Encoding]::UTF8.GetString([byte[]]$response.Content)
    }
    return [string]$response.Content
}

function Invoke-RemoteInstallSmoke {
    param([string]$BaseUrl, [string]$ExpectedVersion)

    $tmpRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("navigator-upstream-cli-smoke-" + [Guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path $tmpRoot | Out-Null
    Push-Location $tmpRoot
    try {
        $installer = Get-WebContentString -Uri "$BaseUrl/install.ps1?ts=$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
        Invoke-Expression $installer
        $navi = Join-Path $tmpRoot "tools\navigator-upstream\navi.ps1"
        if (-not (Test-Path $navi)) {
            throw "remote install smoke did not create $navi"
        }

        $versionOutput = & powershell -ExecutionPolicy Bypass -File $navi version 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0 -or $versionOutput -notmatch [regex]::Escape($ExpectedVersion)) {
            throw "version smoke failed: $versionOutput"
        }

        $helpOutput = & powershell -ExecutionPolicy Bypass -File $navi upstream --help 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0 -or $helpOutput -notmatch "function import" -or $helpOutput -notmatch "--model-variant") {
            throw "upstream help smoke did not list function commands: $helpOutput"
        }

        $functionHelpOutput = & powershell -ExecutionPolicy Bypass -File $navi upstream function --help 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0 -or $functionHelpOutput -notmatch "Commands: import, grant, grant-status, visible") {
            throw "function help smoke failed: $functionHelpOutput"
        }

        $routeHelpOutput = & powershell -ExecutionPolicy Bypass -File $navi upstream route --help 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0 -or $routeHelpOutput -notmatch "Commands: list, set, status") {
            throw "route help smoke failed: $routeHelpOutput"
        }

        $adminKeyHelpOutput = & powershell -ExecutionPolicy Bypass -File $navi upstream admin-key --help 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0 -or $adminKeyHelpOutput -notmatch "Commands: request, status, claim, list, approve, deny, revoke, rotate") {
            throw "admin-key help smoke failed: $adminKeyHelpOutput"
        }

        $clientAppHelpOutput = & powershell -ExecutionPolicy Bypass -File $navi upstream client-app --help 2>&1 | Out-String
        $clientAppHelpOk = $LASTEXITCODE -eq 0 `
            -and $clientAppHelpOutput -match "issue-runtime-key" `
            -and $clientAppHelpOutput -match "issue-runtime-credential" `
            -and $clientAppHelpOutput -match "issue-control-key"
        if (-not $clientAppHelpOk) {
            throw "client-app help smoke failed: $clientAppHelpOutput"
        }

        $workerHelpOutput = & powershell -ExecutionPolicy Bypass -File $navi upstream worker --help 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0 -or $workerHelpOutput -notmatch "Commands: list, create, get, update, delete, health, processes, kill") {
            throw "worker help smoke failed: $workerHelpOutput"
        }

        $directoryHelpOutput = & powershell -ExecutionPolicy Bypass -File $navi upstream directory --help 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0 -or $directoryHelpOutput -notmatch "Commands: list, init, get, delete, env, files") {
            throw "directory help smoke failed: $directoryHelpOutput"
        }

        $workerPoolHelpOutput = & powershell -ExecutionPolicy Bypass -File $navi upstream worker-pool --help 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0 -or $workerPoolHelpOutput -notmatch "Commands: list, create, add-member, status") {
            throw "worker-pool help smoke failed: $workerPoolHelpOutput"
        }

        Write-Host "Remote install smoke passed." -ForegroundColor Green
    }
    finally {
        Pop-Location
        Remove-Item -LiteralPath $tmpRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
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
$buildInfoPath = Join-Path $OutputDir "BUILD_INFO.json"
$buildInfo = if (Test-Path $buildInfoPath) { Get-Content $buildInfoPath -Raw | ConvertFrom-Json } else { $null }
$gitCommit = if ($buildInfo -and $buildInfo.gitCommit) { [string]$buildInfo.gitCommit } else { "" }
$gitDirty = if ($buildInfo -and $null -ne $buildInfo.gitDirty) { [bool]$buildInfo.gitDirty } else { $false }
$shortCommit = if ($gitCommit.Length -ge 12) { $gitCommit.Substring(0, 12) } else { $gitCommit }
$buildTimeUtc = if ($buildInfo -and $buildInfo.buildTimeUtc) { [string]$buildInfo.buildTimeUtc } else { (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ") }
$buildId = if ($shortCommit) { "$Version+$shortCommit" } else { "$Version+$((Get-Date).ToUniversalTime().ToString('yyyyMMddHHmmss'))" }
if ($gitDirty) {
    $buildId = "$buildId.dirty"
}
$features = if ($buildInfo -and $buildInfo.features) { @($buildInfo.features) } else { @() }

& $obsUtil cp $archive "$obsBucket/$Version/$(Split-Path $archive -Leaf)" -f
if ($LASTEXITCODE -ne 0) { throw "Failed to upload archive" }

$latest = [ordered]@{
    version = $Version
    released = (Get-Date -Format "yyyy-MM-dd")
    buildTimeUtc = $buildTimeUtc
    buildId = $buildId
    gitCommit = $gitCommit
    gitDirty = $gitDirty
    features = $features
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

if (-not $SkipSmoke) {
    Invoke-RemoteInstallSmoke -BaseUrl $baseUrl -ExpectedVersion $Version
}

Write-Host ""
Write-Host "Upload complete." -ForegroundColor Green
Write-Host "Install from upstream project root:" -ForegroundColor Cyan
Write-Host "  irm $baseUrl/install.ps1 | iex"
