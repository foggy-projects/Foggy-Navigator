#!/bin/bash
# Codex Agent Worker - SDK Update (Release / OBS-installed)
# Upgrades just @openai/codex-sdk (and the bundled codex CLI it ships)
# WITHOUT replacing the worker itself.
#
# Shipped INSIDE the OBS-distributed archive; lives in $INSTALL_DIR alongside
# start.sh / stop.sh. End users normally invoke it via:
#   codex-worker upgrade-sdk
#   codex-worker upgrade-sdk --sdk-version 0.130.0
#   codex-worker upgrade-sdk --no-restart
#
# Differences from the dev-side update.sh (in tools/codex-agent-worker root):
#   - No `npm run typecheck` (OBS install has no devDependencies and no src/)
#   - Uses `npm install ... --omit=dev` to stay consistent with install.sh
#   - Health-check smoke test after restart
#   - On failure, hints user to run `codex-worker upgrade` to reinstall

set -e

INSTALL_DIR="$(cd "$(dirname "$0")" && pwd)"
DEFAULT_PORT=3051
SdkVersion=""
NoRestart=false

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
GRAY='\033[0;37m'
NC='\033[0m'

# Parse args
while [ $# -gt 0 ]; do
    case "$1" in
        --no-restart)
            NoRestart=true
            shift
            ;;
        --sdk-version)
            SdkVersion="$2"
            shift 2
            ;;
        --sdk-version=*)
            SdkVersion="${1#*=}"
            shift
            ;;
        *)
            echo -e "${RED}Unknown argument: $1${NC}"
            echo "Usage: $0 [--no-restart] [--sdk-version <version>]"
            exit 1
            ;;
    esac
done

if [ ! -f "$INSTALL_DIR/package.json" ]; then
    echo -e "${RED}ERROR: package.json not found in $INSTALL_DIR.${NC}"
    echo -e "${YELLOW}This script must be run from a Codex Worker install directory.${NC}"
    exit 1
fi

# Read port
Port=$DEFAULT_PORT
if [ -f "$INSTALL_DIR/.env" ]; then
    PortLine=$(grep "^CODEX_WORKER_PORT=" "$INSTALL_DIR/.env" 2>/dev/null || true)
    if [ -n "$PortLine" ]; then
        Port=$(echo "$PortLine" | cut -d= -f2 | tr -d ' ')
    fi
fi

if ! command -v npm >/dev/null 2>&1; then
    echo -e "${RED}npm not found on PATH. Please install Node.js (>=20) first.${NC}"
    exit 1
fi
NpmPath="$(command -v npm)"

get_pkg_version() {
    local pkg="$1"
    local pkgJson="$INSTALL_DIR/node_modules/$pkg/package.json"
    if [ ! -f "$pkgJson" ]; then
        echo "not-installed"
        return
    fi
    node -e "try{console.log(require('$pkgJson').version)}catch(e){console.log('unknown')}" 2>/dev/null || echo "unknown"
}

worker_running() {
    lsof -i :$Port >/dev/null 2>&1
}

health_check() {
    local timeout=$1
    local deadline=$(( $(date +%s) + timeout ))
    while [ $(date +%s) -lt $deadline ]; do
        if curl -sS --max-time 3 "http://localhost:$Port/health" >/dev/null 2>&1; then
            curl -sS --max-time 3 "http://localhost:$Port/health" 2>/dev/null
            return 0
        fi
        sleep 1
    done
    return 1
}

WasRunning=false
if worker_running; then WasRunning=true; fi

echo -e "${CYAN}=== Codex Worker SDK Update ===${NC}"
echo -e "${CYAN}Install dir: $INSTALL_DIR${NC}"
echo -e "${CYAN}Port: $Port${NC}"
echo -e "${CYAN}npm: $NpmPath${NC}"

SdkBefore=$(get_pkg_version "@openai/codex-sdk")
CliBefore=$(get_pkg_version "@openai/codex")
echo -e "${GRAY}@openai/codex-sdk before: $SdkBefore${NC}"
echo -e "${GRAY}@openai/codex (CLI) before: $CliBefore${NC}"

if [ "$WasRunning" = true ]; then
    echo -e "${YELLOW}Worker is running on port $Port. Stopping before upgrade...${NC}"
    bash "$INSTALL_DIR/stop.sh"
fi

cd "$INSTALL_DIR"

if [ -n "$SdkVersion" ]; then
    Target="@openai/codex-sdk@$SdkVersion"
else
    Target="@openai/codex-sdk@latest"
fi

echo -e "${CYAN}Running: npm install $Target --omit=dev${NC}"
if ! npm install "$Target" --omit=dev; then
    echo -e "${RED}npm install FAILED. Worker has not been restarted.${NC}"
    echo -e "${YELLOW}Recovery: run 'codex-worker upgrade' to reinstall the pinned SDK from OBS.${NC}"
    exit 1
fi

SdkAfter=$(get_pkg_version "@openai/codex-sdk")
CliAfter=$(get_pkg_version "@openai/codex")
echo -e "${GREEN}@openai/codex-sdk after: $SdkAfter${NC}"
echo -e "${GREEN}@openai/codex (CLI) after: $CliAfter${NC}"

if [ "$NoRestart" = true ]; then
    echo -e "${YELLOW}Update complete. Worker not restarted because --no-restart was used.${NC}"
    exit 0
fi

if [ "$WasRunning" = false ]; then
    echo -e "${GREEN}Update complete. Worker was not running, so no restart was needed.${NC}"
    exit 0
fi

echo -e "${CYAN}Restarting worker...${NC}"
bash "$INSTALL_DIR/start.sh"

echo -e "${CYAN}Health-checking worker on port $Port ...${NC}"
HealthBody=$(health_check 30 || true)
if [ -n "$HealthBody" ]; then
    echo -e "${GREEN}Worker is healthy after SDK upgrade.${NC}"
    echo -e "${GREEN}  /health: $HealthBody${NC}"
else
    echo -e "${RED}Worker did NOT become healthy within 30s after SDK upgrade.${NC}"
    echo -e "${YELLOW}The new SDK may have a breaking change. Check logs:${NC}"
    echo -e "${YELLOW}  codex-worker logs${NC}"
    echo -e "${YELLOW}Recovery: run 'codex-worker upgrade' to reinstall the worker-pinned SDK from OBS.${NC}"
    exit 1
fi
