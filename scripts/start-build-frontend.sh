#!/bin/bash
# Foggy Navigator - Build Frontend & Restart Nginx
#
# 1. Install dependencies (if needed)
# 2. Run the root frontend CI baseline
# 3. Restart the docker-compose nginx container (foggy-navigator-nginx)
#
# Usage:
#   ./scripts/start-build-frontend.sh              # full build + restart nginx
#   ./scripts/start-build-frontend.sh --force      # clean workspace dist & rebuild all
#   ./scripts/start-build-frontend.sh --skip-build # skip build, only restart nginx
#   ./scripts/start-build-frontend.sh --build-only # build only, don't restart nginx

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
FRONTEND_DIR="$REPO_ROOT/packages/navigator-frontend"
DIST_DIR="$FRONTEND_DIR/dist"
DOCKER_DIR="$REPO_ROOT/docker"
CONTAINER_NAME="foggy-navigator-nginx"
NGINX_PORT=80
NGINX_HEALTH_TIMEOUT=40
LOG_DIR="$REPO_ROOT/logs"
LOCKFILE="$REPO_ROOT/pnpm-lock.yaml"
MODULES_META="$REPO_ROOT/node_modules/.modules.yaml"

# ── Colors ────────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
GRAY='\033[0;37m'
BOLD='\033[1m'
NC='\033[0m'

needs_pnpm_install() {
    local required_paths=(
        "$REPO_ROOT/packages/navigator-frontend/node_modules/@foggy/chat"
        "$REPO_ROOT/packages/foggy-chat/node_modules/@foggy/chat-core"
        "$REPO_ROOT/packages/foggy-chat/node_modules/vue-virtual-scroller"
    )

    if [ ! -f "$MODULES_META" ]; then
        return 0
    fi

    if [ -f "$LOCKFILE" ] && [ "$LOCKFILE" -nt "$MODULES_META" ]; then
        return 0
    fi

    for path in "${required_paths[@]}"; do
        if [ ! -e "$path" ]; then
            return 0
        fi
    done

    return 1
}

wait_for_nginx_health() {
    local attempt state health

    for ((attempt = 1; attempt <= NGINX_HEALTH_TIMEOUT; attempt++)); do
        state="$(docker inspect --format '{{.State.Status}}' "$CONTAINER_NAME" 2>/dev/null || true)"
        health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$CONTAINER_NAME" 2>/dev/null || true)"

        if [ "$state" = "running" ] && [ "$health" = "healthy" ]; then
            return 0
        fi

        sleep 1
    done

    echo -e "${RED}  Nginx did not become healthy within ${NGINX_HEALTH_TIMEOUT}s (state: ${state:-missing}, health: ${health:-unknown}).${NC}"
    echo -e "${GRAY}  Recent container logs:${NC}"
    docker logs --tail 30 "$CONTAINER_NAME" 2>&1 || true
    return 1
}

# ── Parse args ────────────────────────────────────────────────────────────────
SKIP_BUILD=false
BUILD_ONLY=false
FORCE_REBUILD=false
for arg in "$@"; do
    case "$arg" in
        --skip-build|-s) SKIP_BUILD=true ;;
        --build-only|-b) BUILD_ONLY=true ;;
        --force|-f)      FORCE_REBUILD=true ;;
    esac
done

echo ""
echo -e "${CYAN}${BOLD}╔════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}${BOLD}║    Frontend Build & Nginx                      ║${NC}"
echo -e "${CYAN}${BOLD}╚════════════════════════════════════════════════╝${NC}"
echo ""

mkdir -p "$LOG_DIR"

# ══ 1. Build ═════════════════════════════════════════════════════════════════
if [ "$SKIP_BUILD" = false ]; then

    # Check pnpm
    if ! command -v pnpm &> /dev/null; then
        echo -e "${RED}  pnpm not found! Use Node 22.23.1 and run: corepack enable${NC}"
        exit 1
    fi

    # Install dependencies if needed
    if needs_pnpm_install; then
        echo -e "${YELLOW}[1/2] Installing dependencies (workspace missing/stale)...${NC}"
        (cd "$REPO_ROOT" && pnpm install --frozen-lockfile)
        if [ $? -ne 0 ]; then
            echo -e "${RED}  pnpm install failed!${NC}"
            exit 1
        fi
    else
        echo -e "${GRAY}[1/2] Dependencies already installed, skipped${NC}"
    fi

    # --force preserves the previous clean-rebuild behavior for workspace libraries.
    CHAT_CORE_DIR="$REPO_ROOT/packages/foggy-chat-core"
    CHAT_DIR="$REPO_ROOT/packages/foggy-chat"

    if [ "$FORCE_REBUILD" = true ]; then
        echo -e "${YELLOW}  --force: cleaning workspace dist...${NC}"
        rm -rf "$CHAT_CORE_DIR/dist" "$CHAT_DIR/dist"
    fi

    echo -e "${YELLOW}[2/2] Running frontend CI baseline...${NC}"
    (cd "$REPO_ROOT" && pnpm run ci:frontend) > "$LOG_DIR/frontend-build.log" 2>&1
    if [ $? -ne 0 ]; then
        echo -e "${RED}  Frontend CI baseline failed! Check logs/frontend-build.log${NC}"
        tail -20 "$LOG_DIR/frontend-build.log"
        exit 1
    fi
    echo -e "${GREEN}  Build complete → $DIST_DIR${NC}"

else
    echo -e "${GRAY}  Build skipped (--skip-build)${NC}"
fi

# Check dist exists
if [ ! -d "$DIST_DIR" ] || [ ! -f "$DIST_DIR/index.html" ]; then
    echo -e "${RED}  dist/ not found! Run without --skip-build first.${NC}"
    exit 1
fi

if [ "$BUILD_ONLY" = true ]; then
    echo ""
    echo -e "${GREEN}  Build finished. Nginx not restarted (--build-only).${NC}"
    echo ""
    exit 0
fi

# ══ 2. Restart Nginx Container (docker-compose) ═════════════════════════════
echo ""
echo -e "${YELLOW}  Restarting Nginx container...${NC}"

# Use docker-compose to recreate the nginx service (picks up new volume path)
if (cd "$DOCKER_DIR" && docker compose up -d --force-recreate nginx) > /dev/null 2>&1; then
    :  # success
elif (cd "$DOCKER_DIR" && docker-compose up -d --force-recreate nginx) > /dev/null 2>&1; then
    :  # fallback to docker-compose v1
else
    echo -e "${RED}  docker compose up failed! Check: docker compose -f docker/docker-compose.yml logs nginx${NC}"
    exit 1
fi

# Verify the configured health endpoint returns HTTP 200 before reporting success.
echo -e "${GRAY}  Waiting for Nginx health check (up to ${NGINX_HEALTH_TIMEOUT}s)...${NC}"
if wait_for_nginx_health; then
    echo ""
    echo -e "${GREEN}╔════════════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║  Frontend (Nginx) Started Successfully!        ║${NC}"
    echo -e "${GREEN}╚════════════════════════════════════════════════╝${NC}"
    echo ""
    echo -e "${CYAN}  URL:        http://localhost:${NGINX_PORT}${NC}"
    echo -e "${CYAN}  Container:  ${CONTAINER_NAME}${NC}"
    echo -e "${CYAN}  Login:      root / root123${NC}"
    echo ""
    echo -e "${GRAY}  Rebuild:    ./scripts/start-build-frontend.sh${NC}"
    echo -e "${GRAY}  Nginx only: ./scripts/start-build-frontend.sh --skip-build${NC}"
    echo -e "${GRAY}  Stop:       docker rm -f ${CONTAINER_NAME}${NC}"
    echo ""
else
    echo -e "${RED}  Frontend/Nginx startup failed.${NC}"
    exit 1
fi
