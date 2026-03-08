#!/bin/bash
# Codex Agent Worker 停止脚本 (Linux/macOS)

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

PORT=3032
if [ -f .env ]; then
    PORT_LINE=$(grep "^CODEX_WORKER_PORT=" .env 2>/dev/null || true)
    if [ -n "$PORT_LINE" ]; then
        PORT=$(echo "$PORT_LINE" | cut -d= -f2 | tr -d ' ')
    fi
fi

echo "Stopping Codex Worker on port $PORT..."

PIDS=$(lsof -ti :$PORT 2>/dev/null || true)
if [ -n "$PIDS" ]; then
    echo "  Killing PIDs: $PIDS"
    kill -9 $PIDS 2>/dev/null || true
    echo "Codex Worker stopped."
else
    echo "No process found on port $PORT."
fi
