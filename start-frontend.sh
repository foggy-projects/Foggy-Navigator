#!/bin/bash
# Navigator Frontend - Start Script
# Usage: chmod +x start-frontend.sh && ./start-frontend.sh

FRONTEND_PORT=5174
FRONTEND_DIR="packages/navigator-frontend"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
GRAY='\033[0;37m'
NC='\033[0m' # No Color

echo ""
echo -e "${CYAN}========================================${NC}"
echo -e "${CYAN}  Navigator Frontend${NC}"
echo -e "${CYAN}========================================${NC}"
echo ""

# Check if port is already in use
PID=$(lsof -ti:${FRONTEND_PORT} 2>/dev/null)
if [ ! -z "$PID" ]; then
    PROCESS=$(ps -p $PID -o comm= 2>/dev/null)
    echo -e "${YELLOW}  Port ${FRONTEND_PORT} already in use by ${PROCESS} (PID=${PID})${NC}"
    echo -e "${GRAY}  Frontend may already be running: http://localhost:${FRONTEND_PORT}${NC}"
    echo ""
    read -p "  Stop and restart? (y/N) " confirm
    if [ "$confirm" != "y" ]; then
        echo -e "${GRAY}  Aborted.${NC}"
        exit 0
    fi
    kill -9 $PID 2>/dev/null
    sleep 2
fi

# Check pnpm
if ! command -v pnpm &> /dev/null; then
    echo -e "${RED}  pnpm not found! Install: npm install -g pnpm${NC}"
    exit 1
fi

# Install dependencies if needed
if [ ! -d "$FRONTEND_DIR/node_modules" ]; then
    echo -e "${YELLOW}[1/2] Installing dependencies...${NC}"
    cd "$FRONTEND_DIR"
    pnpm install
    cd ..
else
    echo -e "${GRAY}[1/2] Dependencies ready${NC}"
fi

echo -e "${YELLOW}[2/2] Starting dev server...${NC}"
echo ""
echo -e "${CYAN}  URL: http://localhost:${FRONTEND_PORT}${NC}"
echo -e "${CYAN}  Login: root / root123${NC}"
echo ""

cd "$FRONTEND_DIR"
pnpm dev