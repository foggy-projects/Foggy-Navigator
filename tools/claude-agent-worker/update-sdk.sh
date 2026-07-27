#!/bin/bash
# Claude Agent Worker - Update claude-agent-sdk and its bundled Claude Code CLI.
#
# Usage:
#   ./update-sdk.sh
#   ./update-sdk.sh --no-restart
#   ./update-sdk.sh --sdk-version 0.2.128
#   ./update-sdk.sh --sdk-version 0.2.123 --force --no-restart
#   ./update-sdk.sh --index-url https://pypi.org/simple
#
# The Worker invokes the Claude Code binary bundled in claude-agent-sdk. A
# global npm-installed `claude` is not used by the SDK execution path.

set -e

WorkerDir="$(cd "$(dirname "$0")" && pwd)"
DefaultPort=3031
SdkVersion=""
IndexUrl=""
NoRestart=false
Force=false

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
GRAY='\033[0;37m'
NC='\033[0m'

usage() {
    echo "Usage: $0 [--sdk-version <version>] [--index-url <url>] [--no-restart] [--force]"
}

require_value() {
    local option="$1"
    local value="${2:-}"
    if [ -z "$value" ] || [[ "$value" == --* ]]; then
        echo -e "${RED}${option} requires a value.${NC}" >&2
        usage >&2
        exit 1
    fi
}

while [ $# -gt 0 ]; do
    case "$1" in
        --sdk-version)
            require_value "$1" "${2:-}"
            SdkVersion="$2"
            shift 2
            ;;
        --sdk-version=*)
            SdkVersion="${1#*=}"
            require_value "--sdk-version" "$SdkVersion"
            shift
            ;;
        --index-url)
            require_value "$1" "${2:-}"
            IndexUrl="$2"
            shift 2
            ;;
        --index-url=*)
            IndexUrl="${1#*=}"
            require_value "--index-url" "$IndexUrl"
            shift
            ;;
        --no-restart)
            NoRestart=true
            shift
            ;;
        --force)
            Force=true
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo -e "${RED}Unknown argument: $1${NC}" >&2
            usage >&2
            exit 1
            ;;
    esac
done

if [ "$Force" = true ] && [ -z "$SdkVersion" ]; then
    echo -e "${RED}--force requires an explicit --sdk-version.${NC}" >&2
    exit 1
fi

VenvDir="$WorkerDir/.venv"
Python="$VenvDir/bin/python"
if [ ! -x "$Python" ]; then
    echo -e "${RED}Worker venv Python not found: $Python${NC}" >&2
    echo -e "${YELLOW}Install or start the Worker once before updating its SDK.${NC}" >&2
    exit 1
fi
if ! "$Python" -m pip --version >/dev/null 2>&1; then
    echo -e "${RED}pip is unavailable in the Worker venv.${NC}" >&2
    exit 1
fi

PipIndexArgs=()
if [ -n "$IndexUrl" ]; then
    PipIndexArgs+=(--index-url "$IndexUrl")
fi

get_sdk_version() {
    "$Python" -c 'from importlib.metadata import version; print(version("claude-agent-sdk"))' 2>/dev/null \
        || echo "not-installed"
}

get_cli_path() {
    "$Python" - <<'PY' 2>/dev/null
import inspect
import os
import claude_agent_sdk

print(os.path.join(os.path.dirname(inspect.getfile(claude_agent_sdk)), "_bundled", "claude"))
PY
}

get_cli_version() {
    local cli_path
    cli_path=$(get_cli_path)
    if [ -n "$cli_path" ] && [ -x "$cli_path" ]; then
        "$cli_path" --version 2>/dev/null || echo "unknown"
    else
        echo "not-installed"
    fi
}

resolve_latest_version() {
    local output
    output=$("$Python" -m pip index versions claude-agent-sdk "${PipIndexArgs[@]}" 2>/dev/null || true)
    echo "$output" | sed -n '1s/^claude-agent-sdk (\([^)]*\)).*/\1/p'
}

compare_versions() {
    "$Python" - "$1" "$2" <<'PY'
import sys
try:
    from packaging.version import InvalidVersion, Version
except ImportError:
    from pip._vendor.packaging.version import InvalidVersion, Version

try:
    installed = Version(sys.argv[1])
    target = Version(sys.argv[2])
except InvalidVersion as exc:
    raise SystemExit(f"invalid SDK version: {exc}")

print((installed > target) - (installed < target))
PY
}

Port=$DefaultPort
if [ -f "$WorkerDir/.env" ]; then
    PortLine=$(grep "^AGENT_WORKER_PORT=" "$WorkerDir/.env" 2>/dev/null || true)
    if [ -n "$PortLine" ]; then
        Port=$(echo "${PortLine#*=}" | tr -d ' ')
    fi
fi

SdkBefore=$(get_sdk_version)
CliBefore=$(get_cli_version)
TargetVersion="$SdkVersion"
if [ -z "$TargetVersion" ]; then
    TargetVersion=$(resolve_latest_version)
fi
if [ -z "$TargetVersion" ]; then
    echo -e "${RED}Could not resolve the requested claude-agent-sdk version.${NC}" >&2
    exit 1
fi

if [ "$SdkBefore" != "not-installed" ]; then
    Comparison=$(compare_versions "$SdkBefore" "$TargetVersion")
    if [ "$Comparison" = "1" ] && [ "$Force" != true ]; then
        echo -e "${YELLOW}Installed SDK $SdkBefore is newer than target $TargetVersion; leaving it unchanged.${NC}"
        echo -e "${YELLOW}Use --sdk-version $TargetVersion --force to allow an explicit downgrade.${NC}"
        exit 0
    fi
fi

WasRunning=false
if command -v lsof >/dev/null 2>&1 && lsof -i ":$Port" >/dev/null 2>&1; then
    WasRunning=true
fi

echo -e "${CYAN}=== Claude Agent Worker SDK Update ===${NC}"
echo -e "${CYAN}Worker dir: $WorkerDir${NC}"
echo -e "${CYAN}Port: $Port${NC}"
echo -e "${GRAY}claude-agent-sdk before: $SdkBefore${NC}"
echo -e "${GRAY}Claude Code before: $CliBefore${NC}"
echo -e "${CYAN}Target claude-agent-sdk: $TargetVersion${NC}"

if [ "$SdkBefore" = "$TargetVersion" ]; then
    echo -e "${GREEN}Already up to date.${NC}"
    exit 0
fi

if [ "$WasRunning" = true ]; then
    echo -e "${YELLOW}Worker is running on port $Port. Stopping before the SDK update...${NC}"
    bash "$WorkerDir/stop.sh"
fi

InstallArgs=(--upgrade "claude-agent-sdk==$TargetVersion")
if [ -n "$IndexUrl" ]; then
    InstallArgs+=(--index-url "$IndexUrl")
fi

echo -e "${CYAN}Installing claude-agent-sdk==$TargetVersion...${NC}"
if ! "$Python" -m pip install "${InstallArgs[@]}"; then
    echo -e "${RED}SDK installation failed. The Worker has not been restarted.${NC}" >&2
    exit 1
fi

SdkAfter=$(get_sdk_version)
CliAfter=$(get_cli_version)
if [ "$SdkAfter" != "$TargetVersion" ]; then
    echo -e "${RED}SDK verification failed: expected $TargetVersion, found $SdkAfter.${NC}" >&2
    exit 1
fi
if [ "$CliAfter" = "not-installed" ] || [ "$CliAfter" = "unknown" ]; then
    echo -e "${RED}Bundled Claude Code CLI verification failed.${NC}" >&2
    exit 1
fi

echo -e "${GREEN}claude-agent-sdk after: $SdkAfter${NC}"
echo -e "${GREEN}Claude Code after: $CliAfter${NC}"

if [ "$NoRestart" = true ]; then
    echo -e "${YELLOW}Update complete. Worker not restarted because --no-restart was used.${NC}"
elif [ "$WasRunning" = true ]; then
    echo -e "${CYAN}Restarting Worker...${NC}"
    bash "$WorkerDir/start.sh"
else
    echo -e "${GREEN}Update complete. Worker was not running, so no restart was needed.${NC}"
fi
