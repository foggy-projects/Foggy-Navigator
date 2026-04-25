#!/bin/bash
# Codex Agent Worker - Update bundled @openai/codex-sdk (and the codex CLI it ships)
# Usage:
#   ./update.sh
#   ./update.sh --no-restart
#   ./update.sh --sdk-version 0.130.0
#
# Notes:
#   - @openai/codex-sdk pulls @openai/codex (the CLI) as a transitive dep with platform-specific
#     binaries. Upgrading the SDK upgrades the CLI.
#   - Plain `npm update` won't bump across minors because package.json pins ^0.x.y; this script
#     runs `npm install @openai/codex-sdk@<version>` so package.json + lockfile are rewritten.

set -e

WorkerDir="$(cd "$(dirname "$0")" && pwd)"
DefaultPort=3051
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

# Read port from .env
Port=$DefaultPort
if [ -f "$WorkerDir/.env" ]; then
    PortLine=$(grep "^CODEX_WORKER_PORT=" "$WorkerDir/.env" 2>/dev/null || true)
    if [ -n "$PortLine" ]; then
        Port=$(echo "$PortLine" | cut -d= -f2 | tr -d ' ')
    fi
fi

# Locate npm
if ! command -v npm >/dev/null 2>&1; then
    echo -e "${RED}npm not found on PATH. Please install Node.js (>=18) first.${NC}"
    exit 1
fi
NpmPath="$(command -v npm)"

# Helper: read a package.json version field via node (always available with npm)
get_pkg_version() {
    local pkg="$1"
    local pkgJson="$WorkerDir/node_modules/$pkg/package.json"
    if [ ! -f "$pkgJson" ]; then
        echo "not-installed"
        return
    fi
    node -e "try{console.log(require('$pkgJson').version)}catch(e){console.log('unknown')}" 2>/dev/null || echo "unknown"
}

# Detect if worker is running
WasRunning=false
if lsof -i :$Port >/dev/null 2>&1; then
    WasRunning=true
fi

echo -e "${CYAN}=== Codex Agent Worker Update ===${NC}"
echo -e "${CYAN}Worker dir: $WorkerDir${NC}"
echo -e "${CYAN}Port: $Port${NC}"
echo -e "${CYAN}npm: $NpmPath${NC}"

SdkBefore=$(get_pkg_version "@openai/codex-sdk")
CliBefore=$(get_pkg_version "@openai/codex")
echo -e "${GRAY}@openai/codex-sdk before: $SdkBefore${NC}"
echo -e "${GRAY}@openai/codex (CLI) before: $CliBefore${NC}"

if [ "$WasRunning" = true ]; then
    echo -e "${YELLOW}Worker is running on port $Port. Stopping before upgrade...${NC}"
    bash "$WorkerDir/stop.sh"
fi

cd "$WorkerDir"

if [ -n "$SdkVersion" ]; then
    Target="@openai/codex-sdk@$SdkVersion"
else
    Target="@openai/codex-sdk@latest"
fi

echo -e "${CYAN}Running: npm install $Target${NC}"
npm install "$Target"

echo -e "${CYAN}Running: npm run typecheck (sanity check)${NC}"
if ! npm run typecheck; then
    echo -e "${RED}typecheck FAILED after upgrade. The new SDK may have breaking changes.${NC}"
    echo -e "${RED}Worker has NOT been restarted. Inspect errors above before retrying.${NC}"
    exit 1
fi

SdkAfter=$(get_pkg_version "@openai/codex-sdk")
CliAfter=$(get_pkg_version "@openai/codex")
echo -e "${GREEN}@openai/codex-sdk after: $SdkAfter${NC}"
echo -e "${GREEN}@openai/codex (CLI) after: $CliAfter${NC}"

if [ "$NoRestart" = true ]; then
    echo -e "${YELLOW}Update complete. Worker not restarted because --no-restart was used.${NC}"
elif [ "$WasRunning" = true ]; then
    echo -e "${CYAN}Restarting worker...${NC}"
    bash "$WorkerDir/start.sh"
else
    echo -e "${GREEN}Update complete. Worker was not running, so no restart was needed.${NC}"
fi
