#!/bin/bash
# Navigator Frontend - Start Script
# Usage: chmod +x scripts/start-frontend.sh && ./scripts/start-frontend.sh

FRONTEND_PORT=5174
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
FRONTEND_DIR="$REPO_ROOT/packages/navigator-frontend"
LOG_DIR="$REPO_ROOT/logs"
LOCKFILE="$REPO_ROOT/pnpm-lock.yaml"
MODULES_META="$REPO_ROOT/node_modules/.modules.yaml"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
GRAY='\033[0;37m'
NC='\033[0m' # No Color

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

echo ""
echo -e "${CYAN}========================================${NC}"
echo -e "${CYAN}  Navigator Frontend${NC}"
echo -e "${CYAN}========================================${NC}"
echo ""

# Check if port is already in use
PID=$(lsof -ti:${FRONTEND_PORT} 2>/dev/null)
if [ ! -z "$PID" ]; then
    PROCESS=$(ps -p $PID -o comm= 2>/dev/null)
    echo -e "${YELLOW}  Port ${FRONTEND_PORT} in use by ${PROCESS} (PID=${PID}), stopping...${NC}"
    kill -9 $PID 2>/dev/null
    sleep 2
fi

# Check pnpm
if ! command -v pnpm &> /dev/null; then
    echo -e "${RED}  pnpm not found! Use Node 22.23.1 and run: corepack enable${NC}"
    exit 1
fi

# Install dependencies if needed
if needs_pnpm_install; then
    echo -e "${YELLOW}[1/3] Installing dependencies (workspace missing/stale)...${NC}"
    (cd "$REPO_ROOT" && pnpm install --frozen-lockfile)
    if [ $? -ne 0 ]; then
        echo -e "${RED}  pnpm install failed!${NC}"
        exit 1
    fi
fi

# Build workspace packages if dist is missing
if [ ! -d "$REPO_ROOT/packages/foggy-chat-core/dist" ] || [ ! -d "$REPO_ROOT/packages/foggy-chat/dist" ]; then
    echo -e "${YELLOW}[2/3] Building workspace packages...${NC}"
    (cd "$REPO_ROOT" && pnpm run prepare:frontend)
    if [ $? -ne 0 ]; then
        echo -e "${RED}  Workspace package build failed!${NC}"
        exit 1
    fi
fi

# Create logs directory
if [ ! -d "$LOG_DIR" ]; then
    mkdir -p "$LOG_DIR"
fi

echo -e "${YELLOW}[3/3] Starting dev server in background...${NC}"

cd "$FRONTEND_DIR"
nohup pnpm dev > "$LOG_DIR/frontend.log" 2> "$LOG_DIR/frontend-error.log" &
echo $! > "$LOG_DIR/frontend.pid"

# Wait for port to be ready
max_wait=30
waited=0
started=false

echo -n "  Waiting for server"
while [ $waited -lt $max_wait ]; do
    sleep 1
    waited=$((waited + 1))

    if lsof -ti:${FRONTEND_PORT} >/dev/null 2>&1; then
        started=true
        break
    fi

    echo -n "."
done

echo ""
echo ""

if [ "$started" = true ]; then
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}  Frontend Started Successfully!${NC}"
    echo -e "${GREEN}========================================${NC}"
    echo ""
    echo -e "${CYAN}  URL:   http://localhost:${FRONTEND_PORT}${NC}"
    echo -e "${CYAN}  Login: root / root123${NC}"
    echo ""
    echo -e "${CYAN}  Logs:${NC}"
    echo -e "${GRAY}    - Output: ${LOG_DIR}/frontend.log${NC}"
    echo -e "${GRAY}    - Errors: ${LOG_DIR}/frontend-error.log${NC}"
    echo ""
    echo -e "${GREEN}========================================${NC}"
else
    echo -e "${RED}========================================${NC}"
    echo -e "${RED}  Frontend Startup Failed!${NC}"
    echo -e "${RED}========================================${NC}"
    echo ""
    echo -e "${RED}  Timeout after ${max_wait} seconds${NC}"
    echo ""
    echo -e "${YELLOW}  Check logs for details:${NC}"
    echo -e "${GRAY}    ${LOG_DIR}/frontend.log${NC}"
    echo -e "${GRAY}    ${LOG_DIR}/frontend-error.log${NC}"
    echo ""

    if [ -f "$LOG_DIR/frontend-error.log" ]; then
        echo -e "${YELLOW}Last 20 lines of error log:${NC}"
        tail -20 "$LOG_DIR/frontend-error.log"
    fi

    exit 1
fi
