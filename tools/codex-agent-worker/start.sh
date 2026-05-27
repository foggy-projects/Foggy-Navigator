#!/bin/bash
# Codex Agent Worker 启动脚本 (Linux/macOS)

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# 读取端口
PORT=3051
if [ -f .env ]; then
    PORT_LINE=$(grep "^CODEX_WORKER_PORT=" .env 2>/dev/null || true)
    if [ -n "$PORT_LINE" ]; then
        PORT=$(echo "$PORT_LINE" | cut -d= -f2 | tr -d ' ')
    fi
fi

echo "========================================"
echo "  Codex Agent Worker"
echo "  Port: $PORT"
echo "========================================"

# 杀掉已有进程
echo ""
echo "[1/4] Checking existing processes on port $PORT..."
EXISTING_PIDS=$(lsof -ti :$PORT 2>/dev/null || true)
if [ -n "$EXISTING_PIDS" ]; then
    echo "  Killing existing processes: $EXISTING_PIDS"
    kill -9 $EXISTING_PIDS 2>/dev/null || true
    sleep 2
fi

# 安装依赖
echo ""
echo "[2/4] Checking dependencies..."
if [ ! -d "node_modules" ]; then
    echo "  Running npm install..."
    npm install > /dev/null 2>&1
    echo "  Dependencies installed."
else
    echo "  node_modules exists, skipping install."
fi

# 确保 logs 目录存在
mkdir -p logs

# 后台启动
echo ""
echo "[3/4] Starting Codex Worker..."
PID_FILE="logs/worker.pid"
rm -f "$PID_FILE"
WORKER_PID=""
if command -v setsid >/dev/null 2>&1; then
    setsid -f sh -c 'echo $$ > logs/worker.pid; exec npx tsx src/index.ts' > logs/worker.log 2> logs/worker-error.log < /dev/null
else
    nohup sh -c 'echo $$ > logs/worker.pid; exec npx tsx src/index.ts' > logs/worker.log 2> logs/worker-error.log < /dev/null &
    WORKER_PID=$!
    disown "$WORKER_PID" 2>/dev/null || true
fi
sleep 1
PID_FROM_FILE=$(cat "$PID_FILE" 2>/dev/null || true)
if [ -n "$PID_FROM_FILE" ]; then
    WORKER_PID="$PID_FROM_FILE"
fi
if [ -z "$WORKER_PID" ]; then
    echo "  Worker PID file was not created!"
    echo "  Error log:"
    tail -20 logs/worker-error.log 2>/dev/null || true
    exit 1
fi
echo "  PID: $WORKER_PID"

# 等待就绪
echo ""
echo "[4/4] Waiting for worker to be ready..."
MAX_WAIT=30
WAITED=0

while [ $WAITED -lt $MAX_WAIT ]; do
    sleep 1
    WAITED=$((WAITED + 1))

    if curl -fsS --max-time 2 "http://localhost:$PORT/health" > /dev/null 2>&1; then
        sleep 3
        if ! kill -0 $WORKER_PID 2>/dev/null; then
            echo ""
            echo "  Worker exited after readiness!"
            echo "  Error log:"
            tail -20 logs/worker-error.log 2>/dev/null || true
            exit 1
        fi
        if ! curl -fsS --max-time 2 "http://localhost:$PORT/health" > /dev/null 2>&1; then
            echo ""
            echo "  Worker health failed after readiness!"
            echo "  Error log:"
            tail -20 logs/worker-error.log 2>/dev/null || true
            exit 1
        fi
        echo ""
        echo "========================================"
        echo "  Codex Worker is READY!"
        echo "  URL: http://localhost:$PORT"
        echo "  Health: http://localhost:$PORT/health"
        echo "  PID: $WORKER_PID"
        echo "========================================"
        exit 0
    fi

    # 检查进程是否崩溃
    if ! kill -0 $WORKER_PID 2>/dev/null; then
        echo ""
        echo "  Worker process exited unexpectedly!"
        echo "  Error log:"
        tail -20 logs/worker-error.log 2>/dev/null || true
        exit 1
    fi

    echo "  Waiting... ($WAITED/$MAX_WAIT)"
done

echo ""
echo "  Worker failed to start within ${MAX_WAIT}s!"
tail -20 logs/worker-error.log 2>/dev/null || true
exit 1
