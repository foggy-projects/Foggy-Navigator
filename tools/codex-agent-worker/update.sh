#!/bin/bash
# Codex Agent Worker - Update bundled @openai/codex-sdk (and the codex CLI it ships)
# Usage:
#   ./update.sh
#   ./update.sh --no-restart
#   ./update.sh --sdk-version 0.144.1
#   ./update.sh --sdk-version 0.142.5 --force --no-restart
#   ./update.sh --registry https://registry.npmjs.org/
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
Registry=""
Force=false
OfficialNpmRegistry="https://registry.npmjs.org/"

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
        --registry)
            Registry="$2"
            shift 2
            ;;
        --registry=*)
            Registry="${1#*=}"
            shift
            ;;
        --force)
            Force=true
            shift
            ;;
        *)
            echo -e "${RED}Unknown argument: $1${NC}"
            echo "Usage: $0 [--no-restart] [--sdk-version <version>] [--registry <url>] [--force]"
            exit 1
            ;;
    esac
done

if [ "$Force" = true ] && [ -z "$SdkVersion" ]; then
    echo -e "${RED}--force requires an explicit --sdk-version.${NC}"
    exit 1
fi

if [ -n "$SdkVersion" ]; then
    EnsureSdkScript="$WorkerDir/scripts/ensure-sdk.mjs"
    if [ ! -f "$EnsureSdkScript" ]; then
        echo -e "${RED}SDK preflight script not found: $EnsureSdkScript${NC}"
        exit 1
    fi
    CheckArgs=("$EnsureSdkScript" --worker-dir "$WorkerDir" --check-target "$SdkVersion")
    if [ "$Force" = true ]; then
        CheckArgs+=(--force)
    fi
    node "${CheckArgs[@]}"
fi

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

get_npm_registry() {
    local registry
    registry=$(npm --loglevel=silent config get registry 2>/dev/null || true)
    if [ -n "$registry" ]; then
        echo "$registry"
    else
        echo "unknown"
    fi
}

normalize_registry() {
    local registry="$1"
    echo "${registry%/}"
}

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

npm_install_with_registry_fallback() {
    local target="$1"
    shift
    local args=("install" "$target" "$@")
    if [ -n "$Registry" ]; then
        args+=("--registry=$Registry")
    fi

    echo -e "${CYAN}Running: npm ${args[*]}${NC}"
    if npm "${args[@]}"; then
        return 0
    fi

    if [ -n "$Registry" ]; then
        return 1
    fi

    local configured_registry
    configured_registry=$(get_npm_registry)
    if [ "$(normalize_registry "$configured_registry")" = "$(normalize_registry "$OfficialNpmRegistry")" ]; then
        return 1
    fi

    echo -e "${YELLOW}npm install failed using registry: $configured_registry${NC}"
    echo -e "${YELLOW}Retrying with official npm registry: $OfficialNpmRegistry${NC}"
    local retry_args=("install" "$target" "$@" "--registry=$OfficialNpmRegistry")
    echo -e "${CYAN}Running: npm ${retry_args[*]}${NC}"
    if npm "${retry_args[@]}"; then
        return 0
    fi

    return 1
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
if [ -n "$Registry" ]; then
    echo -e "${CYAN}npm registry: $Registry (script override)${NC}"
else
    echo -e "${CYAN}npm registry: $(get_npm_registry)${NC}"
fi

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

if ! npm_install_with_registry_fallback "$Target"; then
    echo -e "${RED}npm install $Target failed.${NC}"
    exit 1
fi

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
