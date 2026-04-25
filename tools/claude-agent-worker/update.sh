#!/bin/bash
# Claude Agent Worker - Update bundled Claude Code in .venv
# Usage:
#   ./update.sh
#   ./update.sh --no-restart
#   ./update.sh --sdk-version 0.1.50

set -e

WorkerDir="$(cd "$(dirname "$0")" && pwd)"
DefaultPort=3031
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
    PortLine=$(grep "^AGENT_WORKER_PORT=" "$WorkerDir/.env" 2>/dev/null || true)
    if [ -n "$PortLine" ]; then
        Port=$(echo "$PortLine" | cut -d= -f2 | tr -d ' ')
    fi
fi

VenvDir="$WorkerDir/.venv"
VenvPython="$VenvDir/bin/python"
# claude-agent-sdk ships its CLI under .venv/lib/pythonX.Y/site-packages/claude_agent_sdk/_bundled/claude
# We resolve it dynamically via the venv's python rather than guessing the python version.

ensure_venv() {
    if [ -x "$VenvPython" ]; then
        return
    fi

    local pythonCmd=""
    for cmd in python3 python; do
        if command -v $cmd >/dev/null 2>&1; then
            local ver
            ver=$($cmd -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')" 2>/dev/null || true)
            if [ -n "$ver" ]; then
                local major minor
                major=$(echo "$ver" | cut -d. -f1)
                minor=$(echo "$ver" | cut -d. -f2)
                if [ "$major" -gt 3 ] || { [ "$major" -eq 3 ] && [ "$minor" -ge 10 ]; }; then
                    pythonCmd=$cmd
                    break
                fi
            fi
        fi
    done

    if [ -z "$pythonCmd" ]; then
        echo -e "${RED}Python 3.10+ not found. Cannot create .venv.${NC}"
        exit 1
    fi

    echo -e "${CYAN}Creating .venv with $pythonCmd ...${NC}"
    rm -rf "$VenvDir"
    "$pythonCmd" -m venv "$VenvDir"
}

get_sdk_version() {
    if [ ! -x "$VenvPython" ]; then
        echo "not-installed"
        return
    fi
    "$VenvPython" -c "import claude_agent_sdk; print(getattr(claude_agent_sdk, '__version__', 'unknown'))" 2>/dev/null || echo "not-installed"
}

get_bundled_claude_path() {
    if [ ! -x "$VenvPython" ]; then
        echo ""
        return
    fi
    "$VenvPython" -c "import os, claude_agent_sdk; print(os.path.join(os.path.dirname(claude_agent_sdk.__file__), '_bundled', 'claude'))" 2>/dev/null || echo ""
}

get_bundled_claude_version() {
    local claudeBin
    claudeBin=$(get_bundled_claude_path)
    if [ -z "$claudeBin" ] || [ ! -x "$claudeBin" ]; then
        echo "not-found"
        return
    fi
    "$claudeBin" --version 2>/dev/null || echo "unknown"
}

# Detect if worker is running
WasRunning=false
if lsof -i :$Port >/dev/null 2>&1; then
    WasRunning=true
fi

ensure_venv

echo -e "${CYAN}=== Claude Agent Worker Update ===${NC}"
echo -e "${CYAN}Worker dir: $WorkerDir${NC}"
echo -e "${CYAN}Port: $Port${NC}"
echo -e "${CYAN}Using venv: $VenvPython${NC}"
echo -e "${GRAY}SDK before: $(get_sdk_version)${NC}"
echo -e "${GRAY}Bundled Claude before: $(get_bundled_claude_version)${NC}"

if [ "$WasRunning" = true ]; then
    echo -e "${YELLOW}Worker is running on port $Port. Stopping before upgrade...${NC}"
    bash "$WorkerDir/stop.sh"
fi

cd "$WorkerDir"

if [ -n "$SdkVersion" ]; then
    PipTarget="claude-agent-sdk==$SdkVersion"
else
    PipTarget="claude-agent-sdk"
fi

echo -e "${CYAN}Upgrading $PipTarget ...${NC}"
"$VenvPython" -m pip install --upgrade "$PipTarget"

echo -e "${CYAN}Refreshing local editable install ...${NC}"
"$VenvPython" -m pip install -e . -q

SdkAfter=$(get_sdk_version)
ClaudeAfter=$(get_bundled_claude_version)

echo -e "${GREEN}SDK after: $SdkAfter${NC}"
echo -e "${GREEN}Bundled Claude after: $ClaudeAfter${NC}"

if [ "$NoRestart" = true ]; then
    echo -e "${YELLOW}Update complete. Worker not restarted because --no-restart was used.${NC}"
elif [ "$WasRunning" = true ]; then
    echo -e "${CYAN}Restarting worker...${NC}"
    bash "$WorkerDir/start.sh"
else
    echo -e "${GREEN}Update complete. Worker was not running, so no restart was needed.${NC}"
fi
