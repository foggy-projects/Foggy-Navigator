# Navigator Frontend - Build Verification Script (Windows)
# Runs the canonical root frontend type-check, test, and build matrix.
# Usage: powershell -ExecutionPolicy Bypass -File scripts/build-frontend.ps1

$ErrorActionPreference = "Stop"

# Resolve project root (script lives in scripts/)
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent $ScriptDir
Set-Location $ProjectRoot

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Navigator Frontend Build" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Check pnpm
if (-not (Get-Command pnpm -ErrorAction SilentlyContinue)) {
    Write-Host "  pnpm not found! Use Node 22.23.1 and run: corepack enable" -ForegroundColor Red
    exit 1
}

# Step 1: Install dependencies if needed
if (-not (Test-Path "packages/navigator-frontend/node_modules")) {
    Write-Host "[1/2] Installing dependencies..." -ForegroundColor Yellow
    pnpm install --frozen-lockfile
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  pnpm install failed!" -ForegroundColor Red
        exit 1
    }
} else {
    Write-Host "[1/2] Dependencies already installed" -ForegroundColor Green
}

# Step 2: Run the canonical frontend matrix from the workspace root.
Write-Host "[2/2] Running frontend CI baseline..." -ForegroundColor Yellow
pnpm run ci:frontend
if ($LASTEXITCODE -ne 0) {
    Write-Host "  Frontend CI baseline failed!" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host "  Frontend Build Succeeded!" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
Write-Host ""
