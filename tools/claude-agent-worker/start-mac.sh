#!/bin/bash
# Claude Agent Worker - Mac Start Script
# Usage: chmod +x start-mac.sh && ./start-mac.sh
#
# 特性：
#   - 使用 uv 管理 Python 和依赖（自动安装 uv）
#   - 自动创建 Python 3.12 venv
#   - 自动同步依赖
#   - 前台运行，Ctrl+C 停止
#   - 自动清理端口占用

set -e

WorkerDir="$(cd "$(dirname "$0")" && pwd)"
Port=3031
VenvDir="$WorkerDir/.venv"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

# Ensure uv is on PATH
export PATH="$HOME/.local/bin:$PATH"

# Install uv if not available
if ! command -v uv &>/dev/null; then
    echo -e "${CYAN}Installing uv (Python package manager)...${NC}"
    curl -LsSf https://astral.sh/uv/install.sh | sh
    export PATH="$HOME/.local/bin:$PATH"
fi

# Load port from .env if present
EnvFile="$WorkerDir/.env"
if [ -f "$EnvFile" ]; then
    PortLine=$(grep "^AGENT_WORKER_PORT=" "$EnvFile" 2>/dev/null || true)
    if [ -n "$PortLine" ]; then
        Port=${PortLine#*=}
    fi
fi

echo -e "${CYAN}=== Claude Agent Worker (Mac) ===${NC}"
echo -e "${CYAN}Port: $Port${NC}"

# Kill existing process on the port
ExistingPid=$(lsof -ti:$Port 2>/dev/null || true)
if [ -n "$ExistingPid" ]; then
    echo -e "${YELLOW}Stopping existing process on port $Port (PID: $ExistingPid)...${NC}"
    kill $ExistingPid 2>/dev/null || true
    sleep 0.5
fi

# Setup Python venv with Python 3.12 if not exists
cd "$WorkerDir"
if [ ! -d "$VenvDir" ]; then
    echo -e "${CYAN}Creating Python 3.12 venv...${NC}"
    uv venv --python 3.12 "$VenvDir"
fi

# Sync dependencies
echo -e "${CYAN}Syncing Python dependencies...${NC}"
uv pip install -e . --python "$VenvDir/bin/python" 2>&1 || {
    echo -e "${YELLOW}WARNING: dependency install failed, continuing with existing env...${NC}"
}

# Start the worker in foreground
export PYTHONPATH="$WorkerDir/src"
echo -e "${GREEN}Starting Agent Worker on port $Port (foreground, Ctrl+C to stop)...${NC}"
"$VenvDir/bin/python" -m uvicorn agent_worker.main:app --host 0.0.0.0 --port $Port
