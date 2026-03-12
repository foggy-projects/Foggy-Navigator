# Claude Agent Worker - Package Script (Windows)
# Builds a distributable .zip archive from the monorepo.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File dist\package.ps1              # Windows .zip
#   powershell -ExecutionPolicy Bypass -File dist\package.ps1 -OS all     # All platforms
#   powershell -ExecutionPolicy Bypass -File dist\package.ps1 -OS all -Upload  # Package + upload to OBS

param(
    [ValidateSet("auto", "windows", "linux", "macos", "all")]
    [string]$OS = "auto",

    [switch]$Upload
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
$WorkerDir = Split-Path -Parent $ScriptDir

# --- Read version from __init__.py ----------------------------------------
$InitFile = Join-Path $WorkerDir "src\agent_worker\__init__.py"
$VersionMatch = (Get-Content $InitFile | Select-String '__version__\s*=\s*"([^"]+)"')
if (-not $VersionMatch) {
    Write-Host "ERROR: Could not read version from src/agent_worker/__init__.py" -ForegroundColor Red
    exit 1
}
$Version = $VersionMatch.Matches.Groups[1].Value

Write-Host "=== Claude Agent Worker Packager ===" -ForegroundColor Cyan
Write-Host "Version: $Version" -ForegroundColor Cyan
Write-Host "Source:  $WorkerDir" -ForegroundColor Cyan
Write-Host ""

function Build-ForOS {
    param([string]$OsTag)

    $ext = if ($OsTag -eq "windows") { "zip" } else { "tar.gz" }
    Write-Host "Building for: $OsTag ($ext)" -ForegroundColor Green

    # Create staging directory
    $StageDir = Join-Path $WorkerDir "dist\staging\claude-worker"
    $StagingRoot = Join-Path $WorkerDir "dist\staging"
    if (Test-Path $StagingRoot) { Remove-Item $StagingRoot -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $StageDir | Out-Null

    # --- Copy source code ------------------------------------------------
    Copy-Item (Join-Path $WorkerDir "src") (Join-Path $StageDir "src") -Recurse

    # --- Copy project metadata -------------------------------------------
    Copy-Item (Join-Path $WorkerDir "pyproject.toml") $StageDir
    Copy-Item (Join-Path $WorkerDir ".env.example") $StageDir
    Copy-Item (Join-Path $WorkerDir "SETUP.md") $StageDir

    # --- Copy start/stop scripts -----------------------------------------
    foreach ($f in @("start.ps1", "stop.ps1", "start.sh", "stop.sh", "start-mac.sh")) {
        $srcFile = Join-Path $WorkerDir $f
        if (Test-Path $srcFile) {
            Copy-Item $srcFile $StageDir
        }
    }

    # --- Copy install scripts and CLI wrapper ----------------------------
    Copy-Item (Join-Path $ScriptDir "install.sh") $StageDir
    Copy-Item (Join-Path $ScriptDir "install.ps1") $StageDir

    $BinDir = Join-Path $StageDir "bin"
    New-Item -ItemType Directory -Force -Path $BinDir | Out-Null
    Copy-Item (Join-Path $ScriptDir "bin\claude-worker") $BinDir
    Copy-Item (Join-Path $ScriptDir "bin\claude-worker.ps1") $BinDir

    # --- Copy bundled docs (install skill, etc.) --------------------------
    $DocsDir = Join-Path $ScriptDir "docs"
    if (Test-Path $DocsDir) {
        Copy-Item $DocsDir (Join-Path $StageDir "docs") -Recurse
    }

    # --- Write VERSION file -----------------------------------------------
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText((Join-Path $StageDir "VERSION"), $Version, $utf8NoBom)

    # --- Convert text files to LF for linux/macos -------------------------
    if ($OsTag -ne "windows") {
        # Convert all text files that may end up on Linux/Mac
        Get-ChildItem -Path $StageDir -Recurse -Include "*.sh","*.py","*.md","*.toml","*.example","*.txt","*.cfg","*.json" -File | ForEach-Object {
            ConvertTo-UnixLineEndings $_.FullName
        }
        # Also convert the CLI wrapper (no extension) and VERSION
        $cliWrapper = Join-Path $StageDir "bin\claude-worker"
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
    $OutputDir = Join-Path $WorkerDir "dist\output"
    New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
    $ArchiveName = "claude-worker-$Version-$OsTag"

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
            & $WinTar czf $ArchivePath claude-worker
            Pop-Location
            Write-Host "  -> dist\output\$ArchiveName.tar.gz" -ForegroundColor Green
        }
        elseif (Get-Command tar -ErrorAction SilentlyContinue) {
            # Fallback: try any tar in PATH
            Push-Location $StagingRoot
            tar czf $ArchivePath claude-worker
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
Write-Host "Done! Archives are in: $WorkerDir\dist\output\" -ForegroundColor Green
Get-ChildItem (Join-Path $WorkerDir "dist\output") | Format-Table Name, Length -AutoSize

# --- Upload to OBS if requested -------------------------------------------
if ($Upload) {
    Write-Host ""
    $uploadScript = Join-Path $ScriptDir "upload.ps1"
    if (Test-Path $uploadScript) {
        Write-Host "Uploading to OBS..." -ForegroundColor Cyan
        & powershell -ExecutionPolicy Bypass -File $uploadScript $Version
    }
    else {
        Write-Host "WARNING: dist\upload.ps1 not found, skipping upload." -ForegroundColor Yellow
    }
}
