#!/bin/bash
# Claude Agent Worker - Stop Script
# Usage: chmod +x stop.sh && ./stop.sh

WorkerDir="$(cd "$(dirname "$0")" && pwd)"
Port=3031
PidFile="$WorkerDir/logs/worker.pid"

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
GRAY='\033[0;37m'
NC='\033[0m' # No Color

# Load port from .env if present
EnvFile="$WorkerDir/.env"
if [ -f "$EnvFile" ]; then
    PortLine=$(grep "^AGENT_WORKER_PORT=" "$EnvFile" 2>/dev/null || true)
    if [ ! -z "$PortLine" ]; then
        Port=${PortLine#*=}
    fi
fi

# Stop process from PID file first
if [ -f "$PidFile" ]; then
    SavedPid=$(cat "$PidFile" 2>/dev/null || true)
    if [ ! -z "$SavedPid" ] && ps -p $SavedPid > /dev/null 2>&1; then
        echo -e "${YELLOW}Stopping Agent Worker from PID file (PID: $SavedPid)...${NC}"
        kill -9 $SavedPid 2>/dev/null || true
        rm -f "$PidFile"
    fi
fi

# Find and kill processes on the port
Pids=$(lsof -ti:$Port 2>/dev/null || true)
if [ ! -z "$Pids" ]; then
    for Pid in $Pids; do
        echo -e "${YELLOW}Stopping Agent Worker on port $Port (PID: $Pid)...${NC}"
        kill -9 $Pid 2>/dev/null || true
    done
    echo -e "${GREEN}Agent Worker stopped.${NC}"
else
    echo -e "${GRAY}No Agent Worker running on port $Port.${NC}"
fi

# Clean up PID file if it still exists
rm -f "$PidFile" 2>/dev/null || true