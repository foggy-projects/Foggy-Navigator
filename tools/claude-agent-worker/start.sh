#!/bin/bash
# Claude Agent Worker - Start Script (后台模式)
# Usage: chmod +x start.sh && ./start.sh
#
# ⚠️  Mac 用户请优先使用 start-mac.sh（前台模式，使用 uv + venv）
# ⚠️  本脚本也使用 venv，请确保先运行过 start-mac.sh 创建 venv 环境

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

# Check venv exists
if [ ! -f "$VenvDir/bin/python" ]; then
    echo -e "${RED}错误：未找到 venv 环境 ($VenvDir)${NC}"
    echo -e "${YELLOW}请先运行 ./start-mac.sh 创建 venv，或手动执行：${NC}"
    echo -e "${YELLOW}  uv venv --python 3.12 $VenvDir${NC}"
    echo -e "${YELLOW}  uv pip install -e . --python $VenvDir/bin/python${NC}"
    exit 1
fi

# Verify uvicorn is available in venv
if ! "$VenvDir/bin/python" -m uvicorn --version &>/dev/null; then
    echo -e "${RED}错误：venv 中未安装 uvicorn${NC}"
    echo -e "${YELLOW}请运行: uv pip install -e . --python $VenvDir/bin/python${NC}"
    exit 1
fi

PYTHON="$VenvDir/bin/python"
echo -e "${CYAN}Python: $($PYTHON --version) ($PYTHON)${NC}"

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