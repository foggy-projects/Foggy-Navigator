#!/bin/bash
# Coding Agent Launcher - Stop Script
# Usage: chmod +x stop-launcher.sh && ./stop-launcher.sh

# Configuration
BACKEND_PORT=8112
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
echo -e "${CYAN}  Stopping Coding Agent Launcher${NC}"
echo -e "${CYAN}========================================${NC}"
echo ""

# Find and kill process on port
PID=$(lsof -ti:${BACKEND_PORT} 2>/dev/null)
if [ ! -z "$PID" ]; then
    PROCESS=$(ps -p $PID -o comm= 2>/dev/null)
    echo -e "${YELLOW}  Found process on port ${BACKEND_PORT}: PID=${PID} (${PROCESS})${NC}"
    kill -9 $PID 2>/dev/null
    sleep 2

    # Verify
    CHECK_PID=$(lsof -ti:${BACKEND_PORT} 2>/dev/null)
    if [ ! -z "$CHECK_PID" ]; then
        echo -e "${RED}  Failed to stop process!${NC}"
        exit 1
    else
        echo -e "${GREEN}  Process stopped successfully${NC}"
    fi
else
    echo -e "${GRAY}  No process listening on port ${BACKEND_PORT}${NC}"
fi

# Clean up PID file if exists
if [ -f "$LOG_DIR/backend.pid" ]; then
    rm -f "$LOG_DIR/backend.pid"
fi

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  Done${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""