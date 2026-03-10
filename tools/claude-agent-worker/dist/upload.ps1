# Claude Agent Worker — Upload to Huawei Cloud OBS
# Uploads release archives + latest.json + bootstrap scripts to OBS.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File dist\upload.ps1
#   powershell -ExecutionPolicy Bypass -File dist\upload.ps1 -Version 0.2.0
#
# Prerequisites:
#   - obsutil installed and configured (obsutil config -i=AK -k=SK -e=endpoint)
#   - .env with RELEASE_OBS_BUCKET and RELEASE_BASE_URL (in worker root)
#   - Archives already built in dist/output/

param(
    [string]$Version = ""
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$WorkerDir = Split-Path -Parent $ScriptDir
$OutputDir = Join-Path $ScriptDir "output"

# --- Load .env from worker root ------------------------------------------
$DotEnv = Join-Path $WorkerDir ".env"
if (-not (Test-Path $DotEnv)) {
    Write-Host "ERROR: .env not found at $WorkerDir" -ForegroundColor Red
    Write-Host "Copy .env.example to .env and fill in RELEASE_OBS_BUCKET / RELEASE_BASE_URL." -ForegroundColor Yellow
    exit 1
}

$ObsBucket = ""
$BaseUrl = ""
Get-Content $DotEnv | ForEach-Object {
    if ($_ -match "^RELEASE_OBS_BUCKET=(.+)") { $ObsBucket = $Matches[1].Trim() }
    if ($_ -match "^RELEASE_BASE_URL=(.+)") { $BaseUrl = $Matches[1].Trim() }
}

if (-not $ObsBucket -or -not $BaseUrl) {
    Write-Host "ERROR: RELEASE_OBS_BUCKET and RELEASE_BASE_URL must be set in .env" -ForegroundColor Red
    Write-Host "See .env.example for reference." -ForegroundColor Yellow
    exit 1
}

# --- Check obsutil --------------------------------------------------------
if (-not (Get-Command obsutil -ErrorAction SilentlyContinue)) {
    Write-Host "ERROR: obsutil not found in PATH." -ForegroundColor Red
    Write-Host "Install: https://support.huaweicloud.com/utiltg-obs/obs_11_0003.html" -ForegroundColor Yellow
    exit 1
}

# --- Determine version ----------------------------------------------------
if (-not $Version) {
    $InitFile = Join-Path $WorkerDir "src\agent_worker\__init__.py"
    $VersionMatch = (Get-Content $InitFile | Select-String '__version__\s*=\s*"([^"]+)"')
    $Version = $VersionMatch.Matches.Groups[1].Value
}

Write-Host "=== Claude Agent Worker — OBS Upload ===" -ForegroundColor Cyan
Write-Host "Version:  $Version" -ForegroundColor Cyan
Write-Host "Bucket:   $ObsBucket" -ForegroundColor Cyan
Write-Host "Base URL: $BaseUrl" -ForegroundColor Cyan
Write-Host ""

# --- Check archives exist ------------------------------------------------
if (-not (Test-Path $OutputDir)) {
    Write-Host "ERROR: dist/output/ directory not found. Run package.ps1 first." -ForegroundColor Red
    exit 1
}

$archives = Get-ChildItem $OutputDir -File | Where-Object { $_.Name -like "claude-worker-$Version-*" }
if (-not $archives -or $archives.Count -eq 0) {
    Write-Host "ERROR: No archives found for version $Version in dist/output/" -ForegroundColor Red
    Write-Host "Available files:" -ForegroundColor Yellow
    Get-ChildItem $OutputDir -File | ForEach-Object { Write-Host "  $_" }
    exit 1
}

# --- Upload archives to obs://bucket/claude-worker/{version}/ ------------
Write-Host "Uploading archives..." -ForegroundColor Cyan
foreach ($archive in $archives) {
    $obsPath = "$ObsBucket/$Version/$($archive.Name)"
    Write-Host "  $($archive.Name) -> $obsPath" -ForegroundColor Gray
    obsutil cp $archive.FullName $obsPath -f
    if ($LASTEXITCODE -ne 0) {
        Write-Host "ERROR: Failed to upload $($archive.Name)" -ForegroundColor Red
        exit 1
    }
}

# --- Generate and upload latest.json -------------------------------------
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
$latestJson | Out-File -Encoding UTF8 $latestJsonPath
Write-Host "  Content: $latestJson" -ForegroundColor Gray

obsutil cp $latestJsonPath "$ObsBucket/latest.json" -f
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: Failed to upload latest.json" -ForegroundColor Red
    exit 1
}

# --- Generate and upload bootstrap install scripts -----------------------
Write-Host "Generating bootstrap install scripts..." -ForegroundColor Cyan

# remote-install.sh (Bash bootstrap)
$bashBootstrap = Join-Path $ScriptDir "remote-install.sh"
if (Test-Path $bashBootstrap) {
    # Inject RELEASE_BASE_URL into the script
    $bashContent = (Get-Content $bashBootstrap -Raw) -replace 'RELEASE_BASE_URL="[^"]*"', "RELEASE_BASE_URL=`"$BaseUrl`""
    $tmpBash = Join-Path $OutputDir "install.sh"
    $bashContent | Out-File -Encoding UTF8 -NoNewline $tmpBash
    obsutil cp $tmpBash "$ObsBucket/install.sh" -f
    Write-Host "  install.sh uploaded" -ForegroundColor Gray
}

# remote-install.ps1 (PowerShell bootstrap)
$ps1Bootstrap = Join-Path $ScriptDir "remote-install.ps1"
if (Test-Path $ps1Bootstrap) {
    $ps1Content = (Get-Content $ps1Bootstrap -Raw) -replace 'RELEASE_BASE_URL\s*=\s*"[^"]*"', "`$ReleaseBaseUrl = `"$BaseUrl`""
    $tmpPs1 = Join-Path $OutputDir "install.ps1"
    $ps1Content | Out-File -Encoding UTF8 $tmpPs1
    obsutil cp $tmpPs1 "$ObsBucket/install.ps1" -f
    Write-Host "  install.ps1 uploaded" -ForegroundColor Gray
}

# --- Done -----------------------------------------------------------------
Write-Host ""
Write-Host "=== Upload Complete ===" -ForegroundColor Green
Write-Host ""
Write-Host "Remote install commands:" -ForegroundColor Cyan
Write-Host "  Linux/Mac:  curl -sSL $BaseUrl/install.sh | bash" -ForegroundColor White
Write-Host "  Windows:    irm $BaseUrl/install.ps1 | iex" -ForegroundColor White
Write-Host ""
Write-Host "Upgrade:      claude-worker upgrade" -ForegroundColor White
