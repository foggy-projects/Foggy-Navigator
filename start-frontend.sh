#!/bin/bash
# Navigator Frontend - Start Script
# Usage: chmod +x start-frontend.sh && ./start-frontend.sh

FRONTEND_PORT=5174
FRONTEND_DIR="packages/navigator-frontend"
LOG_DIR="logs"

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
    echo -e "${YELLOW}  Port ${FRONTEND_PORT} in use by ${PROCESS} (PID=${PID}), stopping...${NC}"
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
    echo -e "${YELLOW}[1/3] Installing dependencies...${NC}"
    pnpm install --no-frozen-lockfile
    if [ $? -ne 0 ]; then
        echo -e "${RED}  pnpm install failed!${NC}"
        exit 1
    fi
fi

# Build workspace packages if dist is missing
if [ ! -d "packages/foggy-chat-core/dist" ] || [ ! -d "packages/foggy-chat/dist" ]; then
    echo -e "${YELLOW}[2/3] Building workspace packages...${NC}"
    (cd packages/foggy-chat-core && pnpm build) && (cd packages/foggy-chat && pnpm build)
    if [ $? -ne 0 ]; then
        echo -e "${RED}  Workspace package build failed!${NC}"
        exit 1
    fi
fi

# Create logs directory
if [ ! -d "$LOG_DIR" ]; then
    mkdir -p "$LOG_DIR"
fi

echo -e "${YELLOW}[3/3] Starting dev server in background...${NC}"

cd "$FRONTEND_DIR"
nohup pnpm dev > "../../$LOG_DIR/frontend.log" 2> "../../$LOG_DIR/frontend-error.log" &
echo $! > "../../$LOG_DIR/frontend.pid"
cd ../..

# Wait for port to be ready
max_wait=30
waited=0
started=false

echo -n "  Waiting for server"
while [ $waited -lt $max_wait ]; do
    sleep 1
    waited=$((waited + 1))

    if lsof -ti:${FRONTEND_PORT} >/dev/null 2>&1; then
        started=true
        break
    fi

    echo -n "."
done

echo ""
echo ""

if [ "$started" = true ]; then
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}  Frontend Started Successfully!${NC}"
    echo -e "${GREEN}========================================${NC}"
    echo ""
    echo -e "${CYAN}  URL:   http://localhost:${FRONTEND_PORT}${NC}"
    echo -e "${CYAN}  Login: admin / @Shundao888${NC}"
    echo ""
    echo -e "${CYAN}  Logs:${NC}"
    echo -e "${GRAY}    - Output: ${LOG_DIR}/frontend.log${NC}"
    echo -e "${GRAY}    - Errors: ${LOG_DIR}/frontend-error.log${NC}"
    echo ""
    echo -e "${GREEN}========================================${NC}"
else
    echo -e "${RED}========================================${NC}"
    echo -e "${RED}  Frontend Startup Failed!${NC}"
    echo -e "${RED}========================================${NC}"
    echo ""
    echo -e "${RED}  Timeout after ${max_wait} seconds${NC}"
    echo ""
    echo -e "${YELLOW}  Check logs for details:${NC}"
    echo -e "${GRAY}    ${LOG_DIR}/frontend.log${NC}"
    echo -e "${GRAY}    ${LOG_DIR}/frontend-error.log${NC}"
    echo ""

    if [ -f "$LOG_DIR/frontend-error.log" ]; then
        echo -e "${YELLOW}Last 20 lines of error log:${NC}"
        tail -20 "$LOG_DIR/frontend-error.log"
    fi

    exit 1
fi
