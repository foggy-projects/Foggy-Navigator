# Codex Agent Worker - Package Script (Windows)
# Builds distributable archives for Windows/Linux/macOS from the local workspace.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File release\package.ps1
#   powershell -ExecutionPolicy Bypass -File release\package.ps1 -OS all
#   powershell -ExecutionPolicy Bypass -File release\package.ps1 -OS all -Bump patch -Upload
#   powershell -ExecutionPolicy Bypass -File release\package.ps1 -OS all -Version 1.0.2 -Upload
#   powershell -ExecutionPolicy Bypass -File release\package.ps1 -OS all -Upload

param(
    [ValidateSet("auto", "windows", "linux", "macos", "all")]
    [string]$OS = "auto",

    [ValidateSet("none", "patch", "minor", "major")]
    [string]$Bump = "none",

    [string]$Version = "",

    [switch]$Upload
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

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$WorkerDir = Split-Path -Parent $ScriptDir
$PackageJsonPath = Join-Path $WorkerDir "package.json"

function Invoke-NpmVersion {
    param([string]$TargetVersion)

    Push-Location $WorkerDir
    & npm version $TargetVersion --no-git-tag-version
    $exitCode = $LASTEXITCODE
    Pop-Location
    if ($exitCode -ne 0) {
        Write-Host "ERROR: npm version $TargetVersion failed" -ForegroundColor Red
        exit 1
    }
}

if ($Version -and $Bump -ne "none") {
    Write-Host "ERROR: Use either -Version or -Bump, not both." -ForegroundColor Red
    exit 1
}

if ($Version) {
    Write-Host "Setting release version to $Version..." -ForegroundColor Cyan
    Invoke-NpmVersion $Version
}
elseif ($Bump -ne "none") {
    Write-Host "Bumping release version: $Bump..." -ForegroundColor Cyan
    Invoke-NpmVersion $Bump
}

$PackageJson = Get-Content $PackageJsonPath -Raw | ConvertFrom-Json
$ReleaseVersion = $PackageJson.version

if (-not $ReleaseVersion) {
    Write-Host "ERROR: Could not read version from package.json" -ForegroundColor Red
    exit 1
}

Write-Host "=== Codex Agent Worker Packager ===" -ForegroundColor Cyan
Write-Host "Version: $ReleaseVersion" -ForegroundColor Cyan
Write-Host "Source:  $WorkerDir" -ForegroundColor Cyan
Write-Host ""

Write-Host "Building application..." -ForegroundColor Cyan
Push-Location $WorkerDir
& npm run build
if ($LASTEXITCODE -ne 0) {
    Pop-Location
    Write-Host "ERROR: npm run build failed" -ForegroundColor Red
    exit 1
}
Pop-Location

function Build-ForOS {
    param([string]$OsTag)

    $ext = if ($OsTag -eq "windows") { "zip" } else { "tar.gz" }
    Write-Host "Building for: $OsTag ($ext)" -ForegroundColor Green

    $StageDir = Join-Path $WorkerDir "release\staging\codex-worker"
    $StagingRoot = Join-Path $WorkerDir "release\staging"
    if (Test-Path $StagingRoot) { Remove-Item $StagingRoot -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $StageDir | Out-Null

    Copy-Item (Join-Path $WorkerDir "dist") (Join-Path $StageDir "dist") -Recurse
    Copy-Item (Join-Path $WorkerDir "package.json") $StageDir
    $PackageLockPath = Join-Path $WorkerDir "package-lock.json"
    if (Test-Path $PackageLockPath) {
        Copy-Item $PackageLockPath $StageDir
    }
    else {
        Write-Host "  package-lock.json not found; installer will use npm install." -ForegroundColor Yellow
    }
    Copy-Item (Join-Path $WorkerDir ".env.example") $StageDir

    $DocsDir = Join-Path $WorkerDir "docs"
    if (Test-Path $DocsDir) {
        Copy-Item $DocsDir (Join-Path $StageDir "docs") -Recurse
    }

    foreach ($f in @("start.ps1", "stop.ps1", "start.sh", "stop.sh", "install.ps1", "install.sh", "update.ps1", "update.sh")) {
        Copy-Item (Join-Path $ScriptDir $f) $StageDir
    }

    $BinDir = Join-Path $StageDir "bin"
    New-Item -ItemType Directory -Force -Path $BinDir | Out-Null
    Copy-Item (Join-Path $ScriptDir "bin\codex-worker") $BinDir
    Copy-Item (Join-Path $ScriptDir "bin\codex-worker.ps1") $BinDir

    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText((Join-Path $StageDir "VERSION"), $ReleaseVersion, $utf8NoBom)

    if ($OsTag -ne "windows") {
        Get-ChildItem -Path $StageDir -Recurse -Include "*.sh","*.md","*.json","*.example","*.txt" -File | ForEach-Object {
            ConvertTo-UnixLineEndings $_.FullName
        }
        foreach ($extraFile in @(
            (Join-Path $StageDir "bin\codex-worker"),
            (Join-Path $StageDir "VERSION")
        )) {
            if (Test-Path $extraFile) { ConvertTo-UnixLineEndings $extraFile }
        }
    }

    $OutputDir = Join-Path $WorkerDir "release\output"
    New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
    $ArchiveName = "codex-worker-$ReleaseVersion-$OsTag"

    if ($ext -eq "zip") {
        $ArchivePath = Join-Path $OutputDir "$ArchiveName.zip"
        if (Test-Path $ArchivePath) { Remove-Item $ArchivePath -Force }
        Compress-Archive -Path "$StagingRoot\*" -DestinationPath $ArchivePath -Force
        Write-Host "  -> release\output\$ArchiveName.zip" -ForegroundColor Green
    }
    else {
        $ArchivePath = Join-Path $OutputDir "$ArchiveName.tar.gz"
        $WinTar = "C:\Windows\System32\tar.exe"
        if (Test-Path $WinTar) {
            Push-Location $StagingRoot
            & $WinTar czf $ArchivePath codex-worker
            Pop-Location
            Write-Host "  -> release\output\$ArchiveName.tar.gz" -ForegroundColor Green
        }
        elseif (Get-Command tar -ErrorAction SilentlyContinue) {
            Push-Location $StagingRoot
            tar czf $ArchivePath codex-worker
            Pop-Location
            Write-Host "  -> release\output\$ArchiveName.tar.gz" -ForegroundColor Green
        }
        else {
            Write-Host "ERROR: tar is not available on this machine" -ForegroundColor Red
            exit 1
        }
    }

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
Write-Host "Done! Archives are in: $WorkerDir\release\output\" -ForegroundColor Green
Get-ChildItem (Join-Path $WorkerDir "release\output") | Format-Table Name, Length -AutoSize

if ($Upload) {
    Write-Host ""
    $uploadScript = Join-Path $ScriptDir "upload.ps1"
    if (Test-Path $uploadScript) {
        Write-Host "Uploading to OBS..." -ForegroundColor Cyan
        & powershell -ExecutionPolicy Bypass -File $uploadScript -Version $ReleaseVersion
    }
    else {
        Write-Host "WARNING: release\upload.ps1 not found, skipping upload." -ForegroundColor Yellow
    }
}
