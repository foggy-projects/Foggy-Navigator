# Navigator Frontend - Build Verification Script (Windows)
# Builds workspace packages (foggy-chat-core, foggy-chat) then navigator-frontend.
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
    Write-Host "  pnpm not found! Install: npm install -g pnpm" -ForegroundColor Red
    exit 1
}

# Step 1: Install dependencies if needed
if (-not (Test-Path "packages/navigator-frontend/node_modules")) {
    Write-Host "[1/3] Installing dependencies..." -ForegroundColor Yellow
    pnpm install --no-frozen-lockfile
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  pnpm install failed!" -ForegroundColor Red
        exit 1
    }
} else {
    Write-Host "[1/3] Dependencies already installed" -ForegroundColor Green
}

# Step 2: Build workspace packages (foggy-chat-core -> foggy-chat)
# Always rebuild to ensure dist/ type declarations are up-to-date
Write-Host "[2/3] Building workspace packages (foggy-chat-core, foggy-chat)..." -ForegroundColor Yellow

Push-Location "packages/foggy-chat-core"
pnpm build
if ($LASTEXITCODE -ne 0) {
    Pop-Location
    Write-Host "  foggy-chat-core build failed!" -ForegroundColor Red
    exit 1
}
Pop-Location

Push-Location "packages/foggy-chat"
pnpm build
if ($LASTEXITCODE -ne 0) {
    Pop-Location
    Write-Host "  foggy-chat build failed!" -ForegroundColor Red
    exit 1
}
Pop-Location

# Step 3: Build navigator-frontend (vue-tsc type-check + vite build)
Write-Host "[3/3] Building navigator-frontend..." -ForegroundColor Yellow

Push-Location "packages/navigator-frontend"
pnpm build
if ($LASTEXITCODE -ne 0) {
    Pop-Location
    Write-Host "  Frontend build failed!" -ForegroundColor Red
    exit 1
}
Pop-Location

Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host "  Frontend Build Succeeded!" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
Write-Host ""
