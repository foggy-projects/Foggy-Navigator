# Navigator Frontend - Start Script
# Usage: powershell -ExecutionPolicy Bypass -File start-frontend.ps1
# Options:
#   -Build        先编译前端（更新 dist/nginx，供 Nginx/内网访问用）
#   -BuildOnly    仅编译，不启动 dev server

param(
    [switch]$Build,
    [switch]$BuildOnly
)

$FRONTEND_PORT = 5174
$FRONTEND_DIR = "packages\navigator-frontend"
$NGINX_DIST = "dist\nginx"

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Navigator Frontend" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Check pnpm
if (-not (Get-Command pnpm -ErrorAction SilentlyContinue)) {
    Write-Host "  pnpm not found! Install: npm install -g pnpm" -ForegroundColor Red
    exit 1
}

# ── Step 1: Install dependencies ──────────────────────────────────────────────
if (-not (Test-Path "$FRONTEND_DIR\node_modules")) {
    Write-Host "[1] Installing dependencies..." -ForegroundColor Yellow
    Set-Location $FRONTEND_DIR
    pnpm install
    Set-Location $PSScriptRoot
} else {
    Write-Host "[1] Dependencies ready" -ForegroundColor Gray
}

# ── Step 2: Build for Nginx（-Build 或 -BuildOnly 时执行）────────────────────
if ($Build -or $BuildOnly) {
    Write-Host "[2] Building for Nginx (dist/nginx)..." -ForegroundColor Yellow
    $buildStart = Get-Date

    Set-Location "$PSScriptRoot\$FRONTEND_DIR"
    pnpm build
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  Build FAILED!" -ForegroundColor Red
        Set-Location $PSScriptRoot
        exit 1
    }
    Set-Location $PSScriptRoot

    # 同步到 dist/nginx（nginx 容器挂载目录）
    $srcDir  = "$PSScriptRoot\$FRONTEND_DIR\dist"
    $destDir = "$PSScriptRoot\$NGINX_DIST"

    if (-not (Test-Path $destDir)) {
        New-Item -ItemType Directory -Path $destDir -Force | Out-Null
    }
    # 清空旧文件再复制，避免残留
    Remove-Item "$destDir\*" -Recurse -Force -ErrorAction SilentlyContinue
    Copy-Item -Path "$srcDir\*" -Destination $destDir -Recurse -Force

    $elapsed = ((Get-Date) - $buildStart).TotalSeconds
    Write-Host "  Build done in $([math]::Round($elapsed, 1))s → $NGINX_DIST" -ForegroundColor Green

    if ($BuildOnly) {
        Write-Host ""
        Write-Host "  Nginx will serve the new build on http://localhost:80" -ForegroundColor Cyan
        Write-Host ""
        exit 0
    }
} else {
    Write-Host "[2] Skip build (use -Build to update Nginx dist)" -ForegroundColor Gray
}

# ── Step 3: Start dev server ───────────────────────────────────────────────────
$portConnection = Get-NetTCPConnection -LocalPort $FRONTEND_PORT -State Listen -ErrorAction SilentlyContinue
if ($portConnection) {
    $procId = $portConnection.OwningProcess | Select-Object -First 1
    $process = Get-Process -Id $procId -ErrorAction SilentlyContinue
    Write-Host "  Port $FRONTEND_PORT already in use by $($process.ProcessName) (PID=$procId)" -ForegroundColor Yellow
    Write-Host "  Frontend may already be running: http://localhost:$FRONTEND_PORT" -ForegroundColor Gray
    Write-Host ""
    $confirm = Read-Host "  Stop and restart? (y/N)"
    if ($confirm -ne 'y') {
        Write-Host "  Aborted." -ForegroundColor Gray
        exit 0
    }
    Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
}

Write-Host "[3] Starting dev server..." -ForegroundColor Yellow
Write-Host ""
Write-Host "  Dev  : http://localhost:$FRONTEND_PORT  (Vite hot-reload)" -ForegroundColor Cyan
Write-Host "  Nginx: http://localhost:80              (needs -Build to update)" -ForegroundColor Cyan
Write-Host "  Login: root / root123" -ForegroundColor Cyan
Write-Host ""

Set-Location "$PSScriptRoot\$FRONTEND_DIR"
pnpm dev
