#!/bin/bash
# Navigator Frontend - Build Verification Script
# Builds workspace packages (foggy-chat-core, foggy-chat) then navigator-frontend.
# Usage: bash scripts/build-frontend.sh

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

# Resolve project root (script lives in scripts/)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

echo ""
echo -e "${CYAN}========================================${NC}"
echo -e "${CYAN}  Navigator Frontend Build${NC}"
echo -e "${CYAN}========================================${NC}"
echo ""

# Check pnpm
if ! command -v pnpm &> /dev/null; then
    echo -e "${RED}  pnpm not found! Install: npm install -g pnpm${NC}"
    exit 1
fi

# Step 1: Install dependencies if needed
if [ ! -d "packages/navigator-frontend/node_modules" ]; then
    echo -e "${YELLOW}[1/3] Installing dependencies...${NC}"
    pnpm install --no-frozen-lockfile
    if [ $? -ne 0 ]; then
        echo -e "${RED}  pnpm install failed!${NC}"
        exit 1
    fi
else
    echo -e "${GREEN}[1/3] Dependencies already installed${NC}"
fi

# Step 2: Build workspace packages (foggy-chat-core -> foggy-chat)
# Always rebuild to ensure dist/ type declarations are up-to-date
echo -e "${YELLOW}[2/3] Building workspace packages (foggy-chat-core, foggy-chat)...${NC}"
(cd packages/foggy-chat-core && pnpm build) && (cd packages/foggy-chat && pnpm build)
if [ $? -ne 0 ]; then
    echo -e "${RED}  Workspace package build failed!${NC}"
    exit 1
fi

# Step 3: Build navigator-frontend (vue-tsc type-check + vite build)
echo -e "${YELLOW}[3/3] Building navigator-frontend...${NC}"
cd packages/navigator-frontend
pnpm build
if [ $? -ne 0 ]; then
    echo -e "${RED}  Frontend build failed!${NC}"
    exit 1
fi
cd "$PROJECT_ROOT"

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  Frontend Build Succeeded!${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
