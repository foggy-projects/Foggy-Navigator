# Code Server - Windows Installation Script
# Usage: powershell -ExecutionPolicy Bypass -File install.ps1
#
# Installs code-server via npm globally and builds native modules.
# Prerequisites:
#   - Node.js 20+
#   - Visual Studio Build Tools (for native modules)
#     winget install Microsoft.VisualStudio.2022.BuildTools --override "--passive --add Microsoft.VisualStudio.Workload.VCTools"

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Version = "4.106.3"

Write-Host "=== Code Server Installation ===" -ForegroundColor Cyan
Write-Host "Version: $Version" -ForegroundColor Cyan
Write-Host ""

# ---- Check prerequisites ----
$nodeVersion = node --version 2>$null
if (-not $nodeVersion) {
    Write-Host "ERROR: Node.js is not installed. Please install Node.js 20+ first." -ForegroundColor Red
    exit 1
}
Write-Host "Node.js: $nodeVersion" -ForegroundColor Gray

$npmVersion = npm --version 2>$null
Write-Host "npm:     v$npmVersion" -ForegroundColor Gray
Write-Host ""

# ---- Install via npm (skip postinstall node version check) ----
$codeServerDir = "$(npm root -g)\code-server"

Write-Host "Installing code-server v$Version via npm..." -ForegroundColor Green
npm install -g "code-server@$Version" --ignore-scripts 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: npm install failed." -ForegroundColor Red
    exit 1
}

# ---- Build argon2 native module (for password auth) ----
$argon2Dir = Join-Path $codeServerDir "node_modules\argon2"
if (Test-Path $argon2Dir) {
    Write-Host "Building argon2 native module..." -ForegroundColor Green
    Push-Location $argon2Dir
    npx node-pre-gyp install --fallback-to-build 2>&1
    Pop-Location
    if ($LASTEXITCODE -ne 0) {
        Write-Host "WARNING: argon2 build failed. Password auth may not work." -ForegroundColor Yellow
    }
}

# ---- Install VS Code dependencies ----
$vscodeDir = Join-Path $codeServerDir "lib\vscode"
Write-Host "Installing VS Code dependencies..." -ForegroundColor Green
Push-Location $vscodeDir
npm install --unsafe-perm --omit=dev 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "WARNING: Some native modules failed to build." -ForegroundColor Yellow
    Write-Host "Install VS Build Tools and re-run this script:" -ForegroundColor Yellow
    Write-Host '  winget install Microsoft.VisualStudio.2022.BuildTools --override "--passive --add Microsoft.VisualStudio.Workload.VCTools"' -ForegroundColor White
}
Pop-Location

# ---- Create node_modules.asar symlink ----
$asarLink = Join-Path $vscodeDir "node_modules.asar"
if (-not (Test-Path $asarLink)) {
    Write-Host "Creating node_modules.asar junction..." -ForegroundColor Green
    $nodeModules = Join-Path $vscodeDir "node_modules"
    cmd /c "mklink /J `"$asarLink`" `"$nodeModules`"" 2>&1 | Out-Null
}

# ---- Install VS Code extensions dependencies ----
$extDir = Join-Path $vscodeDir "extensions"
Write-Host "Installing VS Code extensions dependencies..." -ForegroundColor Green
Push-Location $extDir
npm install --unsafe-perm --omit=dev --ignore-scripts 2>&1
Pop-Location

# ---- Copy config ----
$configSrc = Join-Path $ScriptDir "config.yaml"
$configDst = Join-Path $env:APPDATA "code-server\Config\config.yaml"

if (Test-Path $configSrc) {
    $configDir = Split-Path -Parent $configDst
    if (-not (Test-Path $configDir)) {
        New-Item -ItemType Directory -Path $configDir -Force | Out-Null
    }
    Copy-Item $configSrc $configDst -Force
    Write-Host "Config copied to $configDst" -ForegroundColor Gray
}

# ---- Verify ----
Write-Host ""
$installedVersion = npx code-server --version 2>$null | Select-String "^\d" | Select-Object -First 1
if ($installedVersion) {
    Write-Host "=== Installation Complete ===" -ForegroundColor Green
    Write-Host "Version:  $installedVersion" -ForegroundColor Green
    Write-Host "Location: $codeServerDir" -ForegroundColor Gray
    Write-Host ""
    Write-Host "Quick start:" -ForegroundColor Cyan
    Write-Host "  powershell -ExecutionPolicy Bypass -File $(Join-Path $ScriptDir 'start.ps1')" -ForegroundColor White
} else {
    Write-Host "ERROR: Installation verification failed." -ForegroundColor Red
    exit 1
}
