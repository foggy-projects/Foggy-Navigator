#!/bin/bash
# Foggy Navigator - One-Click Stop All Services
#
# Stops (in reverse order):
#   1. Code Server
#   2. Claude Agent Worker
#   3. Frontend
#   4. Backend
#
# Usage: ./stop-all.sh

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ── Colors ────────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
GRAY='\033[0;37m'
BOLD='\033[1m'
NC='\033[0m'

# ── Helper ────────────────────────────────────────────────────────────────────
kill_port() {
    local port="$1"
    local pids
    pids=$(lsof -ti:"$port" 2>/dev/null || true)
    if [ -n "$pids" ]; then
        # shellcheck disable=SC2086
        kill -9 $pids 2>/dev/null || true
        sleep 1
        return 0
    fi
    return 1
}

sep() {
    echo -e "${GRAY}────────────────────────────────────────────────${NC}"
}

# ── Banner ────────────────────────────────────────────────────────────────────
echo ""
echo -e "${CYAN}${BOLD}╔════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}${BOLD}║       Foggy Navigator  ·  Stop All             ║${NC}"
echo -e "${CYAN}${BOLD}╚════════════════════════════════════════════════╝${NC}"
echo ""

# ══ 1. Code Server ════════════════════════════════════════════════════════════
sep
echo -e "${YELLOW}[1/4] Code Server${NC}  ${GRAY}(port 18443)${NC}"
CODE_SERVER_STOP="$HOME/.local/lib/code-server/stop.sh"
if [ -x "$CODE_SERVER_STOP" ]; then
    bash "$CODE_SERVER_STOP"
    echo -e "${GREEN}  ✓ Code Server stopped${NC}"
elif kill_port 18443; then
    echo -e "${GREEN}  ✓ Code Server stopped (port 18443)${NC}"
else
    echo -e "${GRAY}  − Code Server was not running${NC}"
fi

# ══ 2. Claude Agent Worker ════════════════════════════════════════════════════
echo ""
sep
echo -e "${YELLOW}[2/4] Claude Agent Worker${NC}  ${GRAY}(port 3031)${NC}"
WORKER_STOP="$SCRIPT_DIR/tools/claude-agent-worker/stop.sh"
if [ -f "$WORKER_STOP" ]; then
    bash "$WORKER_STOP"
elif kill_port 3031; then
    echo -e "${GREEN}  ✓ Claude Agent Worker stopped (port 3031)${NC}"
else
    echo -e "${GRAY}  − Claude Agent Worker was not running${NC}"
fi

# ══ 3. Frontend (Nginx container) ═════════════════════════════════════════════
echo ""
sep
echo -e "${YELLOW}[3/4] Frontend (Nginx)${NC}  ${GRAY}(container: foggy-navigator-nginx)${NC}"
if docker ps --format '{{.Names}}' 2>/dev/null | grep -q "^foggy-navigator-nginx$"; then
    docker rm -f foggy-navigator-nginx > /dev/null 2>&1
    echo -e "${GREEN}  ✓ Nginx container stopped${NC}"
elif docker ps -a --format '{{.Names}}' 2>/dev/null | grep -q "^foggy-navigator-nginx$"; then
    docker rm -f foggy-navigator-nginx > /dev/null 2>&1
    echo -e "${GREEN}  ✓ Nginx container removed (was stopped)${NC}"
else
    echo -e "${GRAY}  − Nginx container was not running${NC}"
fi

# ══ 4. Backend ════════════════════════════════════════════════════════════════
echo ""
sep
echo -e "${YELLOW}[4/4] Backend${NC}  ${GRAY}(port 8112)${NC}"
if [ -f "$SCRIPT_DIR/stop-launcher.sh" ]; then
    bash "$SCRIPT_DIR/stop-launcher.sh"
else
    if kill_port 8112; then
        echo -e "${GREEN}  ✓ Backend stopped (port 8112)${NC}"
        rm -f "$LOG_DIR/backend.pid" 2>/dev/null || true
    else
        echo -e "${GRAY}  − Backend was not running${NC}"
    fi
fi

# ══ Done ══════════════════════════════════════════════════════════════════════
echo ""
echo -e "${GREEN}${BOLD}╔════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}${BOLD}║         All services stopped.                  ║${NC}"
echo -e "${GREEN}${BOLD}╚════════════════════════════════════════════════╝${NC}"
echo ""
