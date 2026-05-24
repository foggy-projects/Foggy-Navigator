param(
    [ValidateSet("auto", "windows", "linux", "macos", "all")]
    [string]$OS = "auto",

    [switch]$Upload,

    [ValidateSet("none", "patch", "minor", "major")]
    [string]$Bump = "none",

    [string]$Version = ""
)

$ErrorActionPreference = "Stop"

function ConvertTo-UnixLineEndings {
    param([string]$FilePath)
    $content = [System.IO.File]::ReadAllText($FilePath)
    $content = $content -replace "`r`n", "`n"
    if ($content.Length -gt 0 -and $content[0] -eq [char]0xFEFF) {
        $content = $content.Substring(1)
    }
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($FilePath, $content, $utf8NoBom)
}

function Write-Utf8NoBom {
    param([string]$Path, [string]$Content)
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Content, $utf8NoBom)
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$WorkerDir = Split-Path -Parent $ScriptDir
$InitFile = Join-Path $WorkerDir "src\langgraph_biz_worker\__init__.py"

function Get-WorkerVersion {
    $match = (Get-Content $InitFile | Select-String '__version__\s*=\s*"([^"]+)"')
    if (-not $match) {
        throw "Could not read version from $InitFile"
    }
    return $match.Matches.Groups[1].Value
}

function Set-WorkerVersion {
    param([string]$TargetVersion)
    $content = [System.IO.File]::ReadAllText($InitFile)
    $updated = [regex]::Replace($content, '(__version__\s*=\s*")[^"]+(")', "`${1}$TargetVersion`${2}", 1)
    if ($updated -eq $content) {
        throw "Could not update version in $InitFile"
    }
    Write-Utf8NoBom -Path $InitFile -Content $updated
}

function Get-BumpedVersion {
    param([string]$CurrentVersion, [string]$BumpType)
    if ($CurrentVersion -notmatch '^(\d+)\.(\d+)\.(\d+)$') {
        throw "Cannot bump non-semver version '$CurrentVersion'. Use -Version X.Y.Z instead."
    }
    $major = [int]$Matches[1]
    $minor = [int]$Matches[2]
    $patch = [int]$Matches[3]
    switch ($BumpType) {
        "major" { $major++; $minor = 0; $patch = 0 }
        "minor" { $minor++; $patch = 0 }
        "patch" { $patch++ }
    }
    return "$major.$minor.$patch"
}

if ($Version -and $Bump -ne "none") {
    throw "Use either -Version or -Bump, not both."
}
if ($Version) {
    Set-WorkerVersion $Version
} elseif ($Bump -ne "none") {
    Set-WorkerVersion (Get-BumpedVersion -CurrentVersion (Get-WorkerVersion) -BumpType $Bump)
}

$ReleaseVersion = Get-WorkerVersion
Write-Host "=== LangGraph BizWorker Packager ===" -ForegroundColor Cyan
Write-Host "Version: $ReleaseVersion"
Write-Host "Source:  $WorkerDir"

function Copy-IfExists {
    param([string]$Source, [string]$Destination)
    if (Test-Path $Source) {
        Copy-Item $Source $Destination -Recurse -Force
    }
}

function Build-ForOS {
    param([string]$OsTag)

    $ext = if ($OsTag -eq "windows") { "zip" } else { "tar.gz" }
    Write-Host "Building for $OsTag ($ext)" -ForegroundColor Green

    $StagingRoot = Join-Path $ScriptDir "staging"
    $StageDir = Join-Path $StagingRoot "langgraph-biz-worker"
    if (Test-Path $StagingRoot) {
        Remove-Item $StagingRoot -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $StageDir | Out-Null

    Copy-Item (Join-Path $WorkerDir "src") (Join-Path $StageDir "src") -Recurse
    Copy-Item (Join-Path $WorkerDir "pyproject.toml") $StageDir
    Copy-Item (Join-Path $WorkerDir ".env.example") $StageDir
    Copy-Item (Join-Path $WorkerDir "README.md") $StageDir
    Copy-IfExists (Join-Path $WorkerDir "docs") (Join-Path $StageDir "docs")
    Copy-IfExists (Join-Path $WorkerDir "start.ps1") $StageDir
    Copy-IfExists (Join-Path $WorkerDir "stop.ps1") $StageDir
    Copy-Item (Join-Path $ScriptDir "install.sh") $StageDir
    Copy-Item (Join-Path $ScriptDir "install.ps1") $StageDir

    $skillsRoot = Join-Path $StageDir "skills"
    New-Item -ItemType Directory -Force -Path (Join-Path $skillsRoot "public") | Out-Null
    Copy-IfExists (Join-Path $WorkerDir "skills\builtin") (Join-Path $skillsRoot "builtin")

    Write-Utf8NoBom -Path (Join-Path $StageDir "VERSION") -Content $ReleaseVersion

    if ($OsTag -ne "windows") {
        Get-ChildItem -Path $StageDir -Recurse -Include "*.sh","*.py","*.md","*.toml","*.example","*.txt","*.json","VERSION" -File |
            ForEach-Object { ConvertTo-UnixLineEndings $_.FullName }
    }

    Get-ChildItem -Path $StageDir -Directory -Recurse -Filter "__pycache__" -ErrorAction SilentlyContinue |
        Remove-Item -Recurse -Force 2>$null
    Get-ChildItem -Path $StageDir -Directory -Recurse -Filter "*.egg-info" -ErrorAction SilentlyContinue |
        Remove-Item -Recurse -Force 2>$null

    $OutputDir = Join-Path $ScriptDir "output"
    New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
    $ArchiveName = "langgraph-biz-worker-$ReleaseVersion-$OsTag"

    if ($ext -eq "zip") {
        $ArchivePath = Join-Path $OutputDir "$ArchiveName.zip"
        if (Test-Path $ArchivePath) { Remove-Item $ArchivePath -Force }
        Compress-Archive -Path "$StagingRoot\*" -DestinationPath $ArchivePath -Force
    } else {
        $ArchivePath = Join-Path $OutputDir "$ArchiveName.tar.gz"
        if (Test-Path $ArchivePath) { Remove-Item $ArchivePath -Force }
        $tar = if (Test-Path "C:\Windows\System32\tar.exe") { "C:\Windows\System32\tar.exe" } else { "tar" }
        Push-Location $StagingRoot
        & $tar czf $ArchivePath langgraph-biz-worker
        Pop-Location
        if ($LASTEXITCODE -ne 0) { throw "tar failed for $OsTag" }
    }

    Write-Host "  -> $ArchivePath" -ForegroundColor Green
    Remove-Item $StagingRoot -Recurse -Force
}

switch ($OS) {
    "auto" { Build-ForOS "windows" }
    "all" {
        Build-ForOS "linux"
        Build-ForOS "macos"
        Build-ForOS "windows"
    }
    default { Build-ForOS $OS }
}

Write-Host ""
Write-Host "Done. Archives are in: $ScriptDir\output" -ForegroundColor Green
Get-ChildItem (Join-Path $ScriptDir "output") -File | Format-Table Name, Length -AutoSize

if ($Upload) {
    & powershell -ExecutionPolicy Bypass -File (Join-Path $ScriptDir "upload.ps1") -Version $ReleaseVersion
}
