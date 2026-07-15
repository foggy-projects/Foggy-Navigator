#!/bin/bash
# Foggy Navigator - One-Click Start All Services
#
# Starts:
#   1. Frontend         (Nginx · port 80)
#   2. Backend          (Spring Boot · port 8112)
#   3. Claude Agent Worker  (Python · port 3031)
#   4. Code Server      (Web VS Code · port 18443)
#
# Usage:
#   ./scripts/start-all.sh               # full build + start all
#   ./scripts/start-all.sh --skip-build  # skip Maven build (use existing JAR)

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

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
TOTAL=4
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

# Release the existing JVM before a full frontend build. On an 8G development
# host the Java process and Vite/TypeScript build can otherwise compete for the
# same memory. The backend is started again in step 2 even if the frontend fails.
if [ -z "$SKIP_BUILD" ]; then
    echo ""
    echo -e "${GRAY}  Releasing backend memory before frontend build...${NC}"
    if ! bash "$SCRIPT_DIR/stop-launcher.sh"; then
        echo -e "${YELLOW}  ⚠ Could not stop the existing backend cleanly; continuing.${NC}"
    fi
fi

# ══ 1. Frontend (Build + Nginx) ═══════════════════════════════════════════════
echo ""
sep
echo -e "${BOLD}[1/${TOTAL}] Frontend${NC}  ${GRAY}(Nginx · http://localhost:80)${NC}"
sep
if bash "$SCRIPT_DIR/start-build-frontend.sh" $SKIP_BUILD; then
    SVC_STATUS["frontend"]="ok"
else
    echo -e "${RED}  ✗ Frontend build/nginx startup failed${NC}"
    SVC_STATUS["frontend"]="fail"
    FAIL_COUNT=$((FAIL_COUNT + 1))
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

# ══ 3. Claude Agent Worker ════════════════════════════════════════════════════
echo ""
sep
echo -e "${BOLD}[3/${TOTAL}] Claude Agent Worker${NC}  ${GRAY}(Python · http://localhost:3031)${NC}"
sep
WORKER_START="$REPO_ROOT/tools/claude-agent-worker/start.sh"
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

# ══ 4. Code Server ════════════════════════════════════════════════════════════
echo ""
sep
echo -e "${BOLD}[4/${TOTAL}] Code Server${NC}  ${GRAY}(Web VS Code · http://localhost:18443)${NC}"
sep
CODE_SERVER_START="$HOME/.local/lib/code-server/start.sh"
if [ -x "$CODE_SERVER_START" ]; then
    # Pass project dir so the editor opens the right workspace
    if bash "$CODE_SERVER_START" "$REPO_ROOT"; then
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
