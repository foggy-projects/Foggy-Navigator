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
#   ./start-build-frontend.sh --force      # clean workspace dist & rebuild all
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

    # Build workspace packages if dist is missing, stale, or --force
    WS_NEEDS_BUILD=false
    CHAT_CORE_DIR="$SCRIPT_DIR/packages/foggy-chat-core"
    CHAT_DIR="$SCRIPT_DIR/packages/foggy-chat"

    if [ "$FORCE_REBUILD" = true ]; then
        echo -e "${YELLOW}  --force: cleaning workspace dist...${NC}"
        rm -rf "$CHAT_CORE_DIR/dist" "$CHAT_DIR/dist"
        WS_NEEDS_BUILD=true
    elif [ ! -d "$CHAT_CORE_DIR/dist" ] || [ ! -d "$CHAT_DIR/dist" ] || \
         [ -z "$(find "$CHAT_CORE_DIR/dist" -name '*.d.ts' 2>/dev/null)" ] || \
         [ -z "$(find "$CHAT_DIR/dist" -name '*.d.ts' 2>/dev/null)" ]; then
        WS_NEEDS_BUILD=true
    else
        # Check if any src file is newer than dist (stale detection)
        CORE_NEWEST_SRC=$(find "$CHAT_CORE_DIR/src" -type f -printf '%T@\n' 2>/dev/null | sort -rn | head -1)
        CORE_OLDEST_DIST=$(find "$CHAT_CORE_DIR/dist" -type f -printf '%T@\n' 2>/dev/null | sort -n | head -1)
        CHAT_NEWEST_SRC=$(find "$CHAT_DIR/src" -type f -printf '%T@\n' 2>/dev/null | sort -rn | head -1)
        CHAT_OLDEST_DIST=$(find "$CHAT_DIR/dist" -type f -printf '%T@\n' 2>/dev/null | sort -n | head -1)
        if [ -n "$CORE_NEWEST_SRC" ] && [ -n "$CORE_OLDEST_DIST" ] && \
           [ "$(echo "$CORE_NEWEST_SRC > $CORE_OLDEST_DIST" | bc 2>/dev/null)" = "1" ]; then
            echo -e "${YELLOW}  foggy-chat-core src is newer than dist, rebuilding...${NC}"
            WS_NEEDS_BUILD=true
        elif [ -n "$CHAT_NEWEST_SRC" ] && [ -n "$CHAT_OLDEST_DIST" ] && \
             [ "$(echo "$CHAT_NEWEST_SRC > $CHAT_OLDEST_DIST" | bc 2>/dev/null)" = "1" ]; then
            echo -e "${YELLOW}  foggy-chat src is newer than dist, rebuilding...${NC}"
            WS_NEEDS_BUILD=true
        fi
    fi

    if [ "$WS_NEEDS_BUILD" = true ]; then
        echo -e "${YELLOW}[2/3] Building workspace packages (foggy-chat-core, foggy-chat)...${NC}"
        (cd "$CHAT_CORE_DIR" && pnpm build) && \
        (cd "$CHAT_DIR" && pnpm build)
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
