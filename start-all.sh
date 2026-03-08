#!/bin/bash
# Foggy Navigator - One-Click Start All Services
#
# Starts:
#   1. foggy-monitor    (pip install library)
#   2. Backend          (Spring Boot · port 8112)
#   3. Frontend         (Vue 3 · port 5174)
#   4. Claude Agent Worker  (Python · port 3031)
#   5. Code Server      (Web VS Code · port 18443)
#
# Usage:
#   ./start-all.sh               # full build + start all
#   ./start-all.sh --skip-build  # skip Maven build (use existing JAR)

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ── Colors ────────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BLUE='\033[0;34m'
GRAY='\033[0;37m'
BOLD='\033[1m'
NC='\033[0m'

# ── Parse args ────────────────────────────────────────────────────────────────
SKIP_BUILD=""
for arg in "$@"; do
    case "$arg" in
        --skip-build|-s) SKIP_BUILD="--skip-build" ;;
    esac
done

# ── State tracking ────────────────────────────────────────────────────────────
declare -A SVC_STATUS   # ok | fail | warn | skip
TOTAL=5
FAIL_COUNT=0

# ── Helpers ───────────────────────────────────────────────────────────────────
sep() {
    echo -e "${BLUE}────────────────────────────────────────────────${NC}"
}

status_icon() {
    case "$1" in
        ok)   echo -e "${GREEN}✓${NC}" ;;
        fail) echo -e "${RED}✗${NC}" ;;
        warn) echo -e "${YELLOW}⚠${NC}" ;;
        *)    echo -e "${GRAY}−${NC}" ;;
    esac
}

# ── Banner ────────────────────────────────────────────────────────────────────
echo ""
echo -e "${CYAN}${BOLD}╔════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}${BOLD}║       Foggy Navigator  ·  Start All            ║${NC}"
echo -e "${CYAN}${BOLD}╚════════════════════════════════════════════════╝${NC}"
echo ""
if [ -n "$SKIP_BUILD" ]; then
    echo -e "${GRAY}  Mode: skip-build (using existing JAR)${NC}"
else
    echo -e "${GRAY}  Mode: full build${NC}"
fi
echo -e "${GRAY}  $(date '+%Y-%m-%d %H:%M:%S')${NC}"

# ══ 1. foggy-monitor (library install) ═══════════════════════════════════════
echo ""
sep
echo -e "${BOLD}[1/${TOTAL}] foggy-monitor${NC}  ${GRAY}(Python library · pip install)${NC}"
sep
MONITOR_DIR="$SCRIPT_DIR/tools/foggy-monitor"
if [ -f "$MONITOR_DIR/pyproject.toml" ]; then
    if pip install -e "$MONITOR_DIR" -q --disable-pip-version-check 2>/dev/null; then
        echo -e "${GREEN}  ✓ foggy-monitor installed${NC}"
        SVC_STATUS["foggy-monitor"]="ok"
    else
        echo -e "${YELLOW}  ⚠ pip install failed (non-critical)${NC}"
        SVC_STATUS["foggy-monitor"]="warn"
    fi
else
    echo -e "${GRAY}  − tools/foggy-monitor not found, skipped${NC}"
    SVC_STATUS["foggy-monitor"]="skip"
fi

# ══ 2. Backend ════════════════════════════════════════════════════════════════
echo ""
sep
echo -e "${BOLD}[2/${TOTAL}] Backend${NC}  ${GRAY}(Spring Boot · http://localhost:8112)${NC}"
sep
if bash "$SCRIPT_DIR/start-launcher.sh" $SKIP_BUILD; then
    SVC_STATUS["backend"]="ok"
else
    echo -e "${RED}  ✗ Backend startup failed — continuing with other services${NC}"
    SVC_STATUS["backend"]="fail"
    FAIL_COUNT=$((FAIL_COUNT + 1))
fi

# ══ 3. Frontend (Build + Nginx) ═══════════════════════════════════════════════
echo ""
sep
echo -e "${BOLD}[3/${TOTAL}] Frontend${NC}  ${GRAY}(Nginx · http://localhost:80)${NC}"
sep
if bash "$SCRIPT_DIR/start-build-frontend.sh" $SKIP_BUILD; then
    SVC_STATUS["frontend"]="ok"
else
    echo -e "${RED}  ✗ Frontend build/nginx startup failed${NC}"
    SVC_STATUS["frontend"]="fail"
    FAIL_COUNT=$((FAIL_COUNT + 1))
fi

# ══ 4. Claude Agent Worker ════════════════════════════════════════════════════
echo ""
sep
echo -e "${BOLD}[4/${TOTAL}] Claude Agent Worker${NC}  ${GRAY}(Python · http://localhost:3031)${NC}"
sep
WORKER_START="$SCRIPT_DIR/tools/claude-agent-worker/start.sh"
if [ -f "$WORKER_START" ]; then
    if bash "$WORKER_START"; then
        SVC_STATUS["agent-worker"]="ok"
    else
        echo -e "${RED}  ✗ Claude Agent Worker startup failed${NC}"
        SVC_STATUS["agent-worker"]="fail"
        FAIL_COUNT=$((FAIL_COUNT + 1))
    fi
else
    echo -e "${YELLOW}  ⚠ start.sh not found: $WORKER_START${NC}"
    SVC_STATUS["agent-worker"]="warn"
fi

# ══ 5. Code Server ════════════════════════════════════════════════════════════
echo ""
sep
echo -e "${BOLD}[5/${TOTAL}] Code Server${NC}  ${GRAY}(Web VS Code · http://localhost:18443)${NC}"
sep
CODE_SERVER_START="$HOME/.local/lib/code-server/start.sh"
if [ -x "$CODE_SERVER_START" ]; then
    # Pass project dir so the editor opens the right workspace
    if bash "$CODE_SERVER_START" "$SCRIPT_DIR"; then
        SVC_STATUS["code-server"]="ok"
    else
        echo -e "${RED}  ✗ Code Server startup failed${NC}"
        SVC_STATUS["code-server"]="fail"
        FAIL_COUNT=$((FAIL_COUNT + 1))
    fi
else
    echo -e "${YELLOW}  ⚠ Code Server not installed${NC}"
    echo -e "${GRAY}    → Run first: bash tools/code-server/install-linux.sh${NC}"
    SVC_STATUS["code-server"]="warn"
fi

# ══ Summary ═══════════════════════════════════════════════════════════════════
echo ""
echo -e "${CYAN}${BOLD}╔════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}${BOLD}║                   Summary                      ║${NC}"
echo -e "${CYAN}${BOLD}╚════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "  $(status_icon "${SVC_STATUS[foggy-monitor]:-skip}")  foggy-monitor     (library)"
echo -e "  $(status_icon "${SVC_STATUS[backend]:-skip}")  Backend           http://localhost:8112"
echo -e "  $(status_icon "${SVC_STATUS[frontend]:-skip}")  Frontend (Nginx)  http://localhost:80"
echo -e "  $(status_icon "${SVC_STATUS[agent-worker]:-skip}")  Claude Agent Worker  http://localhost:3031"
echo -e "  $(status_icon "${SVC_STATUS[code-server]:-skip}")  Code Server       http://localhost:18443  (pwd: foggy123)"
echo ""
echo -e "${CYAN}  Logs:${NC}"
echo -e "${GRAY}    Backend:       logs/backend.log${NC}"
echo -e "${GRAY}    Frontend:      logs/frontend.log${NC}"
echo -e "${GRAY}    Agent Worker:  tools/claude-agent-worker/logs/worker.log${NC}"
echo ""

if [ "$FAIL_COUNT" -gt 0 ]; then
    echo -e "${RED}  ${FAIL_COUNT} service(s) failed. Check logs above for details.${NC}"
    echo ""
    exit 1
else
    echo -e "${GREEN}  All services started successfully! 🚀${NC}"
    echo ""
    exit 0
fi
