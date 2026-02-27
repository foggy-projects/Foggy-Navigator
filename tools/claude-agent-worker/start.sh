#!/bin/bash
# Claude Agent Worker - Start Script
# Usage: chmod +x start.sh && ./start.sh

WorkerDir="$(cd "$(dirname "$0")" && pwd)"
Port=3031
LogFile="$WorkerDir/logs/worker.log"
PidFile="$WorkerDir/logs/worker.pid"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Create logs directory if not exists
mkdir -p "$WorkerDir/logs"

# Load port from .env if present
EnvFile="$WorkerDir/.env"
if [ -f "$EnvFile" ]; then
    PortLine=$(grep "^AGENT_WORKER_PORT=" "$EnvFile" 2>/dev/null || true)
    if [ ! -z "$PortLine" ]; then
        Port=${PortLine#*=}
    fi
fi

echo -e "${CYAN}=== Claude Agent Worker ===${NC}"
echo -e "${CYAN}Port: $Port${NC}"

# Kill existing process on the port
ExistingPid=$(lsof -ti:$Port 2>/dev/null || true)
if [ ! -z "$ExistingPid" ]; then
    echo -e "${YELLOW}Stopping existing process on port $Port (PID: $ExistingPid)...${NC}"
    kill -9 $ExistingPid 2>/dev/null || true
    sleep 0.5
fi

# Remove old PID file if exists
if [ -f "$PidFile" ]; then
    OldPid=$(cat "$PidFile" 2>/dev/null || true)
    if [ ! -z "$OldPid" ]; then
        kill -9 $OldPid 2>/dev/null || true
    fi
    rm -f "$PidFile"
fi

# Start the worker in background
cd "$WorkerDir"
export PYTHONPATH="$WorkerDir/src"
echo -e "${GREEN}Starting Agent Worker on port $Port in background...${NC}"
echo -e "${GREEN}Logs: $LogFile${NC}"

nohup python3 -m uvicorn agent_worker.main:app --host 0.0.0.0 --port $Port > "$LogFile" 2>&1 &
WorkerPid=$!

# Save PID
echo $WorkerPid > "$PidFile"

# Wait a moment and check if started successfully
sleep 2
if ps -p $WorkerPid > /dev/null 2>&1; then
    echo -e "${GREEN}Agent Worker started successfully (PID: $WorkerPid)${NC}"
else
    echo -e "${RED}Failed to start Agent Worker. Check logs: $LogFile${NC}"
    exit 1
fi