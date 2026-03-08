# Foggy Navigator - Build Frontend & Restart Nginx
#
# 1. Install dependencies (if needed)
# 2. Build workspace packages (foggy-chat-core, foggy-chat)
# 3. Build navigator-frontend → packages/navigator-frontend/dist/
# 4. Restart the docker-compose nginx container (foggy-navigator-nginx)
#
# Usage:
#   .\start-build-frontend.ps1              # full build + restart nginx
#   .\start-build-frontend.ps1 -Force       # clean workspace dist & rebuild all
#   .\start-build-frontend.ps1 -SkipBuild   # skip build, only restart nginx
#   .\start-build-frontend.ps1 -BuildOnly   # build only, don't restart nginx

param(
    [switch]$SkipBuild,
    [switch]$BuildOnly,
    [switch]$Force
)

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$FrontendDir = Join-Path $ScriptDir "packages\navigator-frontend"
$DistDir = Join-Path $FrontendDir "dist"
$DockerDir = Join-Path $ScriptDir "docker"
$ContainerName = "foggy-navigator-nginx"
$NginxPort = 80
$LogDir = Join-Path $ScriptDir "logs"

# ── Colors ────────────────────────────────────────────────────────────────────
function Write-ColorText {
    param([string]$Text, [string]$Color = "White")
    Write-Host $Text -ForegroundColor $Color
}

$ColorMap = @{
    Red    = "Red"
    Green  = "Green"
    Yellow = "Yellow"
    Cyan   = "Cyan"
    Gray   = "Gray"
}

# ── Banner ────────────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "╔════════════════════════════════════════════════╗" -ForegroundColor Cyan
Write-Host "║    Frontend Build & Nginx                      ║" -ForegroundColor Cyan
Write-Host "╚════════════════════════════════════════════════╝" -ForegroundColor Cyan
Write-Host ""

# Create log directory if not exists
if (-not (Test-Path $LogDir)) {
    New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
}

# ══ 1. Build ═════════════════════════════════════════════════════════════════
if (-not $SkipBuild) {

    # Check pnpm
    $pnpmCmd = Get-Command pnpm -ErrorAction SilentlyContinue
    if (-not $pnpmCmd) {
        Write-ColorText "  pnpm not found! Install: npm install -g pnpm" "Red"
        exit 1
    }

    # Install dependencies if needed
    $NodeModulesPath = Join-Path $FrontendDir "node_modules"
    if (-not (Test-Path $NodeModulesPath)) {
        Write-ColorText "[1/3] Installing dependencies..." "Yellow"
        Push-Location $ScriptDir
        try {
            pnpm install --no-frozen-lockfile
            if ($LASTEXITCODE -ne 0) {
                Write-ColorText "  pnpm install failed!" "Red"
                exit 1
            }
        } finally {
            Pop-Location
        }
    } else {
        Write-ColorText "[1/3] Dependencies already installed, skipped" "Gray"
    }

    # Build workspace packages if dist is missing, stale, or -Force
    $WsNeedsBuild = $false
    $ChatCoreDir = Join-Path $ScriptDir "packages\foggy-chat-core"
    $ChatDir = Join-Path $ScriptDir "packages\foggy-chat"
    $ChatCoreDist = Join-Path $ChatCoreDir "dist"
    $ChatDist = Join-Path $ChatDir "dist"

    if ($Force) {
        Write-ColorText "  -Force: cleaning workspace dist..." "Yellow"
        if (Test-Path $ChatCoreDist) { Remove-Item -Recurse -Force $ChatCoreDist }
        if (Test-Path $ChatDist) { Remove-Item -Recurse -Force $ChatDist }
        $WsNeedsBuild = $true
    } elseif (-not (Test-Path $ChatCoreDist) -or -not (Test-Path $ChatDist)) {
        $WsNeedsBuild = $true
    } else {
        # Check if any src file is newer than dist (stale detection)
        $CoreNewestSrc = Get-ChildItem -Path (Join-Path $ChatCoreDir "src") -Recurse -File -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty LastWriteTime

        $CoreOldestDist = Get-ChildItem -Path $ChatCoreDist -Recurse -File -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime | Select-Object -First 1 -ExpandProperty LastWriteTime

        $ChatNewestSrc = Get-ChildItem -Path (Join-Path $ChatDir "src") -Recurse -File -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty LastWriteTime

        $ChatOldestDist = Get-ChildItem -Path $ChatDist -Recurse -File -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime | Select-Object -First 1 -ExpandProperty LastWriteTime

        if ($CoreNewestSrc -and $CoreOldestDist -and $CoreNewestSrc -gt $CoreOldestDist) {
            Write-ColorText "  foggy-chat-core src is newer than dist, rebuilding..." "Yellow"
            $WsNeedsBuild = $true
        } elseif ($ChatNewestSrc -and $ChatOldestDist -and $ChatNewestSrc -gt $ChatOldestDist) {
            Write-ColorText "  foggy-chat src is newer than dist, rebuilding..." "Yellow"
            $WsNeedsBuild = $true
        }
    }

    if ($WsNeedsBuild) {
        Write-ColorText "[2/3] Building workspace packages (foggy-chat-core, foggy-chat)..." "Yellow"

        # Build foggy-chat-core
        Push-Location $ChatCoreDir
        try {
            pnpm build
            if ($LASTEXITCODE -ne 0) {
                Write-ColorText "  foggy-chat-core build failed!" "Red"
                exit 1
            }
        } finally {
            Pop-Location
        }

        # Build foggy-chat
        Push-Location $ChatDir
        try {
            pnpm build
            if ($LASTEXITCODE -ne 0) {
                Write-ColorText "  foggy-chat build failed!" "Red"
                exit 1
            }
        } finally {
            Pop-Location
        }
    } else {
        Write-ColorText "[2/3] Workspace packages already built, skipped" "Gray"
    }

    # Build navigator-frontend
    Write-ColorText "[3/3] Building navigator-frontend..." "Yellow"
    $BuildLogPath = Join-Path $LogDir "frontend-build.log"

    Push-Location $FrontendDir
    try {
        pnpm build *>&1 | Out-File -FilePath $BuildLogPath -Encoding UTF8
        if ($LASTEXITCODE -ne 0) {
            Write-ColorText "  Frontend build failed! Check logs\frontend-build.log" "Red"
            Get-Content $BuildLogPath -Tail 20
            exit 1
        }
    } finally {
        Pop-Location
    }
    Write-ColorText "  Build complete → $DistDir" "Green"

} else {
    Write-ColorText "  Build skipped (-SkipBuild)" "Gray"
}

# Check dist exists
$IndexPath = Join-Path $DistDir "index.html"
if (-not (Test-Path $DistDir) -or -not (Test-Path $IndexPath)) {
    Write-ColorText "  dist/ not found! Run without -SkipBuild first." "Red"
    exit 1
}

if ($BuildOnly) {
    Write-Host ""
    Write-ColorText "  Build finished. Nginx not restarted (-BuildOnly)." "Green"
    Write-Host ""
    exit 0
}

# ══ 2. Restart Nginx Container (docker-compose) ═════════════════════════════
Write-Host ""
Write-ColorText "  Restarting Nginx container..." "Yellow"

$DockerComposePath = Join-Path $DockerDir "docker-compose.yml"
$DockerComposePathAlt = Join-Path $DockerDir "compose.yml"

Push-Location $DockerDir
try {
    $RecreateSuccess = $false

    # Try docker compose (v2) first
    $dcCmd = Get-Command docker -ErrorAction SilentlyContinue
    if ($dcCmd) {
        # Check if compose plugin is available
        $composeCheck = docker compose version 2>&1
        if ($LASTEXITCODE -eq 0) {
            docker compose up -d --force-recreate nginx 2>&1 | Out-Null
            if ($LASTEXITCODE -eq 0) {
                $RecreateSuccess = $true
            }
        }
    }

    # Fallback to docker-compose (v1)
    if (-not $RecreateSuccess) {
        $dcCmd = Get-Command docker-compose -ErrorAction SilentlyContinue
        if ($dcCmd) {
            docker-compose up -d --force-recreate nginx 2>&1 | Out-Null
            if ($LASTEXITCODE -eq 0) {
                $RecreateSuccess = $true
            }
        }
    }

    if (-not $RecreateSuccess) {
        Write-ColorText "  docker compose up failed! Check: docker compose logs nginx" "Red"
        exit 1
    }
} finally {
    Pop-Location
}

# Verify container is healthy
Start-Sleep -Seconds 1
$RunningContainers = docker ps --format "{{.Names}}" 2>&1
if ($RunningContainers -match "^${ContainerName}$") {
    Write-Host ""
    Write-Host "╔════════════════════════════════════════════════╗" -ForegroundColor Green
    Write-Host "║  Frontend (Nginx) Started Successfully!        ║" -ForegroundColor Green
    Write-Host "╚════════════════════════════════════════════════╝" -ForegroundColor Green
    Write-Host ""
    Write-ColorText "  URL:        http://localhost:${NginxPort}" "Cyan"
    Write-ColorText "  Container:  ${ContainerName}" "Cyan"
    Write-ColorText "  Login:      root / root123" "Cyan"
    Write-Host ""
    Write-ColorText "  Rebuild:    .\start-build-frontend.ps1" "Gray"
    Write-ColorText "  Nginx only: .\start-build-frontend.ps1 -SkipBuild" "Gray"
    Write-ColorText "  Stop:       docker rm -f ${ContainerName}" "Gray"
    Write-Host ""
} else {
    Write-ColorText "  Container failed to start! Check: docker logs ${ContainerName}" "Red"
    exit 1
}