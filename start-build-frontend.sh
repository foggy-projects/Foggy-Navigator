#!/bin/bash
# Foggy Navigator - Build Frontend & Restart Nginx
#
# 1. Install dependencies (if needed)
# 2. Build workspace packages (foggy-chat-core, foggy-chat)
# 3. Build navigator-frontend → packages/navigator-frontend/dist/
# 4. Restart the docker-compose nginx container (foggy-navigator-nginx)
#
# Usage:
#   ./start-build-frontend.sh              # full build + restart nginx
#   ./start-build-frontend.sh --skip-build # skip build, only restart nginx
#   ./start-build-frontend.sh --build-only # build only, don't restart nginx

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FRONTEND_DIR="$SCRIPT_DIR/packages/navigator-frontend"
DIST_DIR="$FRONTEND_DIR/dist"
DOCKER_DIR="$SCRIPT_DIR/docker"
CONTAINER_NAME="foggy-navigator-nginx"
NGINX_PORT=80
LOG_DIR="$SCRIPT_DIR/logs"

# ── Colors ────────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
GRAY='\033[0;37m'
BOLD='\033[1m'
NC='\033[0m'

# ── Parse args ────────────────────────────────────────────────────────────────
SKIP_BUILD=false
BUILD_ONLY=false
for arg in "$@"; do
    case "$arg" in
        --skip-build|-s) SKIP_BUILD=true ;;
        --build-only|-b) BUILD_ONLY=true ;;
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
        echo -e "${RED}  pnpm not found! Install: npm install -g pnpm${NC}"
        exit 1
    fi

    # Install dependencies if needed
    if [ ! -d "$FRONTEND_DIR/node_modules" ]; then
        echo -e "${YELLOW}[1/3] Installing dependencies...${NC}"
        (cd "$SCRIPT_DIR" && pnpm install --no-frozen-lockfile)
        if [ $? -ne 0 ]; then
            echo -e "${RED}  pnpm install failed!${NC}"
            exit 1
        fi
    else
        echo -e "${GRAY}[1/3] Dependencies already installed, skipped${NC}"
    fi

    # Build workspace packages if dist is missing
    if [ ! -d "$SCRIPT_DIR/packages/foggy-chat-core/dist" ] || [ ! -d "$SCRIPT_DIR/packages/foggy-chat/dist" ]; then
        echo -e "${YELLOW}[2/3] Building workspace packages (foggy-chat-core, foggy-chat)...${NC}"
        (cd "$SCRIPT_DIR/packages/foggy-chat-core" && pnpm build) && \
        (cd "$SCRIPT_DIR/packages/foggy-chat" && pnpm build)
        if [ $? -ne 0 ]; then
            echo -e "${RED}  Workspace package build failed!${NC}"
            exit 1
        fi
    else
        echo -e "${GRAY}[2/3] Workspace packages already built, skipped${NC}"
    fi

    # Build navigator-frontend
    echo -e "${YELLOW}[3/3] Building navigator-frontend...${NC}"
    (cd "$FRONTEND_DIR" && pnpm build) > "$LOG_DIR/frontend-build.log" 2>&1
    if [ $? -ne 0 ]; then
        echo -e "${RED}  Frontend build failed! Check logs/frontend-build.log${NC}"
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

# Verify container is healthy
sleep 1
if docker ps --format '{{.Names}}' 2>/dev/null | grep -q "^${CONTAINER_NAME}$"; then
    echo ""
    echo -e "${GREEN}╔════════════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║  Frontend (Nginx) Started Successfully!        ║${NC}"
    echo -e "${GREEN}╚════════════════════════════════════════════════╝${NC}"
    echo ""
    echo -e "${CYAN}  URL:        http://localhost:${NGINX_PORT}${NC}"
    echo -e "${CYAN}  Container:  ${CONTAINER_NAME}${NC}"
    echo -e "${CYAN}  Login:      root / root123${NC}"
    echo ""
    echo -e "${GRAY}  Rebuild:    ./start-build-frontend.sh${NC}"
    echo -e "${GRAY}  Nginx only: ./start-build-frontend.sh --skip-build${NC}"
    echo -e "${GRAY}  Stop:       docker rm -f ${CONTAINER_NAME}${NC}"
    echo ""
else
    echo -e "${RED}  Container failed to start! Check: docker logs ${CONTAINER_NAME}${NC}"
    exit 1
fi
