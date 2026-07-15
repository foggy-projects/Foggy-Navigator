#!/bin/bash
# Navigator Frontend - Build Verification Script
# Runs the canonical root frontend type-check, test, and build matrix.
# Usage: bash scripts/build-frontend.sh

set -e

# Resolve project root (script lives in scripts/)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
LOCKFILE="$PROJECT_ROOT/pnpm-lock.yaml"
MODULES_META="$PROJECT_ROOT/node_modules/.modules.yaml"
FRONTEND_NODE_MAX_OLD_SPACE_SIZE="${FRONTEND_NODE_MAX_OLD_SPACE_SIZE:-4096}"
cd "$PROJECT_ROOT"

needs_pnpm_install() {
    local required_paths=(
        "$PROJECT_ROOT/packages/navigator-frontend/node_modules/@foggy/chat"
        "$PROJECT_ROOT/packages/foggy-chat/node_modules/@foggy/chat-core"
        "$PROJECT_ROOT/packages/foggy-chat/node_modules/vue-virtual-scroller"
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

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

echo ""
echo -e "${CYAN}========================================${NC}"
echo -e "${CYAN}  Navigator Frontend Build${NC}"
echo -e "${CYAN}========================================${NC}"
echo ""

# Check pnpm
if ! command -v pnpm &> /dev/null; then
    echo -e "${RED}  pnpm not found! Use Node 22.23.1 and run: corepack enable${NC}"
    exit 1
fi

# Step 1: Install dependencies if needed
if needs_pnpm_install; then
    echo -e "${YELLOW}[1/2] Installing dependencies (workspace missing/stale)...${NC}"
    pnpm install --frozen-lockfile
    if [ $? -ne 0 ]; then
        echo -e "${RED}  pnpm install failed!${NC}"
        exit 1
    fi
else
    echo -e "${GREEN}[1/2] Dependencies already installed${NC}"
fi

# Step 2: Run the canonical frontend matrix from the workspace root.
echo -e "${YELLOW}[2/2] Running frontend CI baseline...${NC}"
echo -e "  Node heap limit: ${FRONTEND_NODE_MAX_OLD_SPACE_SIZE} MB"
NODE_OPTIONS="${NODE_OPTIONS:+$NODE_OPTIONS }--max-old-space-size=$FRONTEND_NODE_MAX_OLD_SPACE_SIZE" pnpm run ci:frontend
if [ $? -ne 0 ]; then
    echo -e "${RED}  Frontend CI baseline failed!${NC}"
    exit 1
fi

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  Frontend Build Succeeded!${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
