#!/bin/bash
# Gemini Agent Worker stop script (Linux / macOS)

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

PORT=3071
if [ -f .env ]; then
    PORT_LINE=$(grep "^GEMINI_WORKER_PORT=" .env 2>/dev/null || true)
    if [ -n "$PORT_LINE" ]; then
        PORT=$(echo "$PORT_LINE" | cut -d= -f2 | tr -d ' ')
    fi
fi

echo "Stopping Gemini Worker on port $PORT..."

PIDS=$(lsof -ti :$PORT 2>/dev/null || true)
if [ -n "$PIDS" ]; then
    echo "  Killing PIDs: $PIDS"
    kill -9 $PIDS 2>/dev/null || true
    echo "Gemini Worker stopped."
else
    echo "No process found on port $PORT."
fi
