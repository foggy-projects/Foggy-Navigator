#!/bin/bash
# Claude Agent Worker - Start Script (后台模式)
# Usage: chmod +x start.sh && ./start.sh

set -e

WorkerDir="$(cd "$(dirname "$0")" && pwd)"
Port=3031
LogFile="$WorkerDir/logs/worker.log"
PidFile="$WorkerDir/logs/worker.pid"
VenvDir="$WorkerDir/.venv"

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

echo -e "${CYAN}=== Claude Agent Worker (后台模式) ===${NC}"
echo -e "${CYAN}Port: $Port${NC}"

# Find Python 3
PYTHON_CMD=""
for cmd in python3 python; do
    if command -v $cmd &>/dev/null; then
        PYTHON_CMD=$cmd
        break
    fi
done

if [ -z "$PYTHON_CMD" ]; then
    echo -e "${RED}错误：未找到 Python 3${NC}"
    echo -e "${YELLOW}请安装 Python 3.10+${NC}"
    exit 1
fi

PYTHON_VERSION=$($PYTHON_CMD --version 2>&1)
echo -e "${CYAN}System Python: $PYTHON_VERSION${NC}"

# Check venv exists, create if not
if [ ! -f "$VenvDir/bin/python" ]; then
    echo -e "${YELLOW}创建 venv 环境 ($VenvDir)...${NC}"
    $PYTHON_CMD -m venv "$VenvDir"
    if [ $? -ne 0 ]; then
        echo -e "${RED}创建 venv 失败${NC}"
        exit 1
    fi
fi

PYTHON="$VenvDir/bin/python"
PIP="$VenvDir/bin/pip"

# Install dependencies if needed
if ! "$PYTHON" -m uvicorn --version &>/dev/null; then
    echo -e "${YELLOW}安装依赖...${NC}"
    "$PIP" install --upgrade pip
    "$PIP" install -e "$WorkerDir"
    if [ $? -ne 0 ]; then
        echo -e "${RED}依赖安装失败${NC}"
        exit 1
    fi
fi

echo -e "${CYAN}Using venv: $PYTHON${NC}"
echo -e "${CYAN}Python version: $($PYTHON --version)${NC}"

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

# Start the worker in background (使用 venv 的 python，不用系统 python3)
cd "$WorkerDir"
export PYTHONPATH="$WorkerDir/src"
echo -e "${GREEN}Starting Agent Worker on port $Port in background...${NC}"
echo -e "${GREEN}Logs: $LogFile${NC}"

nohup "$PYTHON" -m uvicorn agent_worker.main:app --host 0.0.0.0 --port $Port > "$LogFile" 2>&1 &
WorkerPid=$!

# Save PID
echo $WorkerPid > "$PidFile"

# Wait a moment and check if started successfully
sleep 2
if ps -p $WorkerPid > /dev/null 2>&1; then
    echo -e "${GREEN}Agent Worker started successfully (PID: $WorkerPid)${NC}"
else
    echo -e "${RED}Failed to start Agent Worker. Check logs: $LogFile${NC}"
    echo -e "${RED}常见问题：${NC}"
    echo -e "${RED}  1. brew 升级覆盖了系统 python → 用 venv 的 python（本脚本已修复）${NC}"
    echo -e "${RED}  2. 端口被占用 → lsof -i:$Port${NC}"
    echo -e "${RED}  3. 依赖缺失 → uv pip install -e . --python $VenvDir/bin/python${NC}"
    exit 1
fi