#!/bin/bash
# Codex Agent Worker - SDK Update (Release / OBS-installed)
# Upgrades just @openai/codex-sdk (and the bundled codex CLI it ships)
# WITHOUT replacing the worker itself.
#
# Shipped INSIDE the OBS-distributed archive; lives in $INSTALL_DIR alongside
# start.sh / stop.sh. End users normally invoke it via:
#   codex-worker upgrade-sdk
#   codex-worker upgrade-sdk --sdk-version 0.144.1
#   codex-worker upgrade-sdk --sdk-version 0.142.5 --force --no-restart
#   codex-worker upgrade-sdk --no-restart
#   codex-worker upgrade-sdk --registry https://registry.npmjs.org/
#
# Differences from the dev-side update-sdk.sh (in tools/codex-agent-worker root):
#   - No `npm run typecheck` (OBS install has no devDependencies and no src/)
#   - Uses `npm install ... --omit=dev` to stay consistent with install.sh
#   - Health-check smoke test after restart
#   - On failure, hints user to run `codex-worker upgrade` to reinstall

set -e

INSTALL_DIR="$(cd "$(dirname "$0")" && pwd)"
DEFAULT_PORT=3051
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

if [ ! -f "$INSTALL_DIR/package.json" ]; then
    echo -e "${RED}ERROR: package.json not found in $INSTALL_DIR.${NC}"
    echo -e "${YELLOW}This script must be run from a Codex Worker install directory.${NC}"
    exit 1
fi

if [ -n "$SdkVersion" ]; then
    EnsureSdkScript="$INSTALL_DIR/scripts/ensure-sdk.mjs"
    if [ ! -f "$EnsureSdkScript" ]; then
        echo -e "${RED}ERROR: SDK preflight script not found: $EnsureSdkScript${NC}"
        exit 1
    fi
    CheckArgs=("$EnsureSdkScript" --worker-dir "$INSTALL_DIR" --check-target "$SdkVersion")
    if [ "$Force" = true ]; then
        CheckArgs+=(--force)
    fi
    node "${CheckArgs[@]}"
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

get_pkg_version() {
    local pkg="$1"
    local pkgJson="$INSTALL_DIR/node_modules/$pkg/package.json"
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

resolve_sdk_version() {
    local spec="${SdkVersion:-latest}"
    local args=("view" "@openai/codex-sdk@$spec" "version")
    if [ -n "$Registry" ]; then
        args+=("--registry=$Registry")
    fi
    npm "${args[@]}" 2>/dev/null | tail -n 1
}

worker_running() {
    lsof -i :$Port >/dev/null 2>&1
}

health_check() {
    local timeout=$1
    local deadline=$(( $(date +%s) + timeout ))
    while [ $(date +%s) -lt $deadline ]; do
        HealthBody=$(curl -sS --max-time 3 "http://localhost:$Port/health" 2>/dev/null || true)
        if [ -n "$HealthBody" ] && printf '%s' "$HealthBody" | node -e '
let input = "";
process.stdin.on("data", chunk => input += chunk);
process.stdin.on("end", () => {
  try {
    const health = JSON.parse(input);
    process.exit(health.status === "ok" && health.codex_sdk_available === true && health.codex_sdk_compatible === true ? 0 : 1);
  } catch { process.exit(1); }
});'; then
            printf '%s' "$HealthBody"
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
if [ -n "$Registry" ]; then
    echo -e "${CYAN}npm registry: $Registry (script override)${NC}"
else
    echo -e "${CYAN}npm registry: $(get_npm_registry)${NC}"
fi

SdkBefore=$(get_pkg_version "@openai/codex-sdk")
CliBefore=$(get_pkg_version "@openai/codex")
echo -e "${GRAY}@openai/codex-sdk before: $SdkBefore${NC}"
echo -e "${GRAY}@openai/codex (CLI) before: $CliBefore${NC}"

ResolvedSdkVersion=$(resolve_sdk_version)
if [ -z "$ResolvedSdkVersion" ]; then
    echo -e "${RED}Could not resolve the requested @openai/codex-sdk version; refusing an unverified update.${NC}"
    exit 1
fi
SdkComparison=$(node "$INSTALL_DIR/scripts/runtime-dependency-version.mjs" --compare "$SdkBefore" "$ResolvedSdkVersion")
if [ "$SdkComparison" = "1" ]; then
    echo -e "${YELLOW}Installed @openai/codex-sdk $SdkBefore is newer than requested $ResolvedSdkVersion; leaving it unchanged.${NC}"
    exit 0
fi

if [ "$WasRunning" = true ]; then
    echo -e "${YELLOW}Worker is running on port $Port. Stopping before upgrade...${NC}"
    bash "$INSTALL_DIR/stop.sh"
fi

cd "$INSTALL_DIR"

Target="@openai/codex-sdk@$ResolvedSdkVersion"

if ! npm_install_with_registry_fallback "$Target" --omit=dev; then
    echo -e "${RED}npm install FAILED. Worker has not been restarted.${NC}"
    echo -e "${YELLOW}Recovery: install a compatible newer SDK, then retry; Worker upgrade will preserve the installed SDK.${NC}"
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
    echo -e "${YELLOW}Recovery: install a compatible newer SDK, then retry; Worker upgrade will preserve the installed SDK.${NC}"
    exit 1
fi
