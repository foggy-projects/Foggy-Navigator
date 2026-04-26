#!/bin/bash
# Gemini Agent Worker start script (Linux / macOS)

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

PORT=3071
if [ -f .env ]; then
    PORT_LINE=$(grep "^GEMINI_WORKER_PORT=" .env 2>/dev/null || true)
    if [ -n "$PORT_LINE" ]; then
        PORT=$(echo "$PORT_LINE" | cut -d= -f2 | tr -d ' ')
    fi
fi

echo "========================================"
echo "  Gemini Agent Worker"
echo "  Port: $PORT"
echo "========================================"

echo ""
echo "[1/4] Checking existing processes on port $PORT..."
EXISTING_PIDS=$(lsof -ti :$PORT 2>/dev/null || true)
if [ -n "$EXISTING_PIDS" ]; then
    echo "  Killing existing processes: $EXISTING_PIDS"
    kill -9 $EXISTING_PIDS 2>/dev/null || true
    sleep 2
fi

echo ""
echo "[2/4] Checking dependencies..."
if [ ! -d "node_modules" ]; then
    echo "  Running npm ci --omit=dev..."
    npm ci --omit=dev >/dev/null 2>&1
    echo "  Dependencies installed."
else
    echo "  node_modules exists, skipping install."
fi

mkdir -p logs

echo ""
echo "[3/4] Starting Gemini Worker..."
nohup node dist/index.js > logs/worker.log 2> logs/worker-error.log &
WORKER_PID=$!
echo "  PID: $WORKER_PID"

echo ""
echo "[4/4] Waiting for worker to be ready..."
MAX_WAIT=30
WAITED=0

while [ $WAITED -lt $MAX_WAIT ]; do
    sleep 1
    WAITED=$((WAITED + 1))

    if lsof -i :$PORT >/dev/null 2>&1; then
        echo ""
        echo "========================================"
        echo "  Gemini Worker is READY!"
        echo "  URL: http://localhost:$PORT"
        echo "  Health: http://localhost:$PORT/health"
        echo "  PID: $WORKER_PID"
        echo "========================================"
        exit 0
    fi

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
