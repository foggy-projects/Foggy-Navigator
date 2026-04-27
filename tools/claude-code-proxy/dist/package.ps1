# Claude Code Proxy - Package Script (Windows)
# Builds a distributable .zip archive from the monorepo.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File dist\package.ps1              # Windows .zip
#   powershell -ExecutionPolicy Bypass -File dist\package.ps1 -OS all     # All platforms
#   powershell -ExecutionPolicy Bypass -File dist\package.ps1 -OS all -Upload  # Package + upload to OBS
#   powershell -ExecutionPolicy Bypass -File dist\package.ps1 -OS all -Bump patch -Upload
#   powershell -ExecutionPolicy Bypass -File dist\package.ps1 -OS all -Version 1.1.1 -Upload

param(
    [ValidateSet("auto", "windows", "linux", "macos", "all")]
    [string]$OS = "auto",

    [switch]$Upload,

    [ValidateSet("none", "patch", "minor", "major")]
    [string]$Bump = "none",

    [string]$Version = ""
)

$ErrorActionPreference = "Stop"

# Helper: convert file to UTF-8 no BOM + LF line endings (Linux-safe)
function ConvertTo-UnixLineEndings {
    param([string]$FilePath)
    $content = [System.IO.File]::ReadAllText($FilePath)
    $content = $content -replace "`r`n", "`n"
    # Remove BOM if present
    if ($content.Length -gt 0 -and $content[0] -eq [char]0xFEFF) {
        $content = $content.Substring(1)
    }
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($FilePath, $content, $utf8NoBom)
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProxyDir = Split-Path -Parent $ScriptDir

# --- Read version from src/__init__.py ------------------------------------
$InitFile = Join-Path $ProxyDir "src\__init__.py"

function Get-ProxyVersion {
    $match = (Get-Content $InitFile | Select-String '__version__\s*=\s*"([^"]+)"')
    if (-not $match) {
        Write-Host "ERROR: Could not read version from src/__init__.py" -ForegroundColor Red
        exit 1
    }
    return $match.Matches.Groups[1].Value
}

function Set-ProxyVersion {
    param([string]$TargetVersion)

    $content = [System.IO.File]::ReadAllText($InitFile)
    if ($content -notmatch '__version__\s*=\s*"([^"]+)"') {
        Write-Host "ERROR: Could not update version in src/__init__.py" -ForegroundColor Red
        exit 1
    }

    $updated = [regex]::Replace($content, '(__version__\s*=\s*")[^"]+(")', "`${1}$TargetVersion`${2}", 1)
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($InitFile, $updated, $utf8NoBom)
}

function Get-BumpedVersion {
    param([string]$CurrentVersion, [string]$BumpType)

    if ($CurrentVersion -notmatch '^(\d+)\.(\d+)\.(\d+)$') {
        Write-Host "ERROR: Cannot bump non-semver version '$CurrentVersion'. Use -Version X.Y.Z instead." -ForegroundColor Red
        exit 1
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
    Write-Host "ERROR: Use either -Version or -Bump, not both." -ForegroundColor Red
    exit 1
}

if ($Version) {
    Write-Host "Setting release version to $Version" -ForegroundColor Cyan
    Set-ProxyVersion $Version
}
elseif ($Bump -ne "none") {
    $currentVersion = Get-ProxyVersion
    $nextVersion = Get-BumpedVersion -CurrentVersion $currentVersion -BumpType $Bump
    Write-Host "Bumping release version: $currentVersion -> $nextVersion" -ForegroundColor Cyan
    Set-ProxyVersion $nextVersion
}

$VersionMatch = (Get-Content $InitFile | Select-String '__version__\s*=\s*"([^"]+)"')
if (-not $VersionMatch) {
    Write-Host "ERROR: Could not read version from src/__init__.py" -ForegroundColor Red
    exit 1
}
$ReleaseVersion = $VersionMatch.Matches.Groups[1].Value

Write-Host "=== Claude Code Proxy Packager ===" -ForegroundColor Cyan
Write-Host "Version: $ReleaseVersion" -ForegroundColor Cyan
Write-Host "Source:  $ProxyDir" -ForegroundColor Cyan
Write-Host ""

function Build-ForOS {
    param([string]$OsTag)

    $ext = if ($OsTag -eq "windows") { "zip" } else { "tar.gz" }
    Write-Host "Building for: $OsTag ($ext)" -ForegroundColor Green

    # Create staging directory
    $StageDir = Join-Path $ProxyDir "dist\staging\claude-code-proxy"
    $StagingRoot = Join-Path $ProxyDir "dist\staging"
    if (Test-Path $StagingRoot) { Remove-Item $StagingRoot -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $StageDir | Out-Null

    # --- Copy source code ------------------------------------------------
    Copy-Item (Join-Path $ProxyDir "src") (Join-Path $StageDir "src") -Recurse

    # --- Copy project metadata -------------------------------------------
    Copy-Item (Join-Path $ProxyDir "pyproject.toml") $StageDir
    Copy-Item (Join-Path $ProxyDir "requirements.txt") $StageDir
    Copy-Item (Join-Path $ProxyDir ".env.example") $StageDir
    Copy-Item (Join-Path $ProxyDir "QUICKSTART.md") $StageDir
    # README.md is required by pyproject.toml (readme = "README.md") for hatchling builds
    $readmePath = Join-Path $ProxyDir "README.md"
    if (Test-Path $readmePath) {
        Copy-Item $readmePath $StageDir
    }

    # --- Copy startup entry point ----------------------------------------
    Copy-Item (Join-Path $ProxyDir "start_proxy.py") $StageDir

    # --- Copy start/stop scripts -----------------------------------------
    foreach ($f in @("start.ps1", "stop.ps1", "start.sh", "stop.sh")) {
        $srcFile = Join-Path $ProxyDir $f
        if (Test-Path $srcFile) {
            Copy-Item $srcFile $StageDir
        }
    }

    # --- Copy install scripts and CLI wrapper ----------------------------
    Copy-Item (Join-Path $ScriptDir "install.sh") $StageDir
    Copy-Item (Join-Path $ScriptDir "install.ps1") $StageDir

    $BinDir = Join-Path $StageDir "bin"
    New-Item -ItemType Directory -Force -Path $BinDir | Out-Null
    Copy-Item (Join-Path $ScriptDir "bin\claude-code-proxy") $BinDir
    Copy-Item (Join-Path $ScriptDir "bin\claude-code-proxy.ps1") $BinDir

    # --- Copy bundled docs (install skill, etc.) --------------------------
    $DocsDir = Join-Path $ScriptDir "docs"
    if (Test-Path $DocsDir) {
        Copy-Item $DocsDir (Join-Path $StageDir "docs") -Recurse
    }

    # --- Write VERSION file -----------------------------------------------
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText((Join-Path $StageDir "VERSION"), $ReleaseVersion, $utf8NoBom)

    # --- Convert text files to LF for linux/macos -------------------------
    if ($OsTag -ne "windows") {
        # Convert all text files that may end up on Linux/Mac
        Get-ChildItem -Path $StageDir -Recurse -Include "*.sh","*.py","*.md","*.toml","*.example","*.txt","*.cfg","*.json" -File | ForEach-Object {
            ConvertTo-UnixLineEndings $_.FullName
        }
        # Also convert the CLI wrapper (no extension) and VERSION
        $cliWrapper = Join-Path $StageDir "bin\claude-code-proxy"
        if (Test-Path $cliWrapper) { ConvertTo-UnixLineEndings $cliWrapper }
        $versionFile = Join-Path $StageDir "VERSION"
        if (Test-Path $versionFile) { ConvertTo-UnixLineEndings $versionFile }
    }

    # --- Clean build artifacts --------------------------------------------
    Get-ChildItem -Path $StageDir -Directory -Recurse -Filter "__pycache__" -ErrorAction SilentlyContinue |
        Remove-Item -Recurse -Force 2>$null
    Get-ChildItem -Path $StageDir -Directory -Recurse -Filter "*.egg-info" -ErrorAction SilentlyContinue |
        Remove-Item -Recurse -Force 2>$null

    # --- Create archive ---------------------------------------------------
    $OutputDir = Join-Path $ProxyDir "dist\output"
    New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
    $ArchiveName = "claude-code-proxy-$ReleaseVersion-$OsTag"

    if ($ext -eq "zip") {
        $ArchivePath = Join-Path $OutputDir "$ArchiveName.zip"
        if (Test-Path $ArchivePath) { Remove-Item $ArchivePath -Force }
        Compress-Archive -Path "$StagingRoot\*" -DestinationPath $ArchivePath -Force
        Write-Host "  -> dist\output\$ArchiveName.zip" -ForegroundColor Green
    }
    else {
        # For .tar.gz on Windows, use Windows native tar (not Git's tar which can't handle drive letters)
        $ArchivePath = Join-Path $OutputDir "$ArchiveName.tar.gz"
        $WinTar = "C:\Windows\System32\tar.exe"
        if (Test-Path $WinTar) {
            Push-Location $StagingRoot
            & $WinTar czf $ArchivePath claude-code-proxy
            Pop-Location
            Write-Host "  -> dist\output\$ArchiveName.tar.gz" -ForegroundColor Green
        }
        elseif (Get-Command tar -ErrorAction SilentlyContinue) {
            # Fallback: try any tar in PATH
            Push-Location $StagingRoot
            tar czf $ArchivePath claude-code-proxy
            Pop-Location
            Write-Host "  -> dist\output\$ArchiveName.tar.gz" -ForegroundColor Green
        }
        else {
            # Fallback: create .zip even for linux/macos (user can convert)
            $ArchivePath = Join-Path $OutputDir "$ArchiveName.zip"
            if (Test-Path $ArchivePath) { Remove-Item $ArchivePath -Force }
            Compress-Archive -Path "$StagingRoot\*" -DestinationPath $ArchivePath -Force
            Write-Host "  -> dist\output\$ArchiveName.zip (tar not available, created .zip)" -ForegroundColor Yellow
        }
    }

    # Cleanup staging
    Remove-Item $StagingRoot -Recurse -Force
}

# --- Build ----------------------------------------------------------------
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
Write-Host "Done! Archives are in: $ProxyDir\dist\output\" -ForegroundColor Green
Get-ChildItem (Join-Path $ProxyDir "dist\output") | Format-Table Name, Length -AutoSize

# --- Upload to OBS if requested -------------------------------------------
if ($Upload) {
    Write-Host ""
    $uploadScript = Join-Path $ScriptDir "upload.ps1"
    if (Test-Path $uploadScript) {
        Write-Host "Uploading to OBS..." -ForegroundColor Cyan
        & powershell -ExecutionPolicy Bypass -File $uploadScript -Version $ReleaseVersion
    }
    else {
        Write-Host "WARNING: dist\upload.ps1 not found, skipping upload." -ForegroundColor Yellow
    }
}
