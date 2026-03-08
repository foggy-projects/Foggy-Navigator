#!/usr/bin/env bash
# Claude Code Proxy - Linux Start Script (background)
# Usage: bash start.sh

set -e

PROXY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PORT=8082
LOG_FILE="$PROXY_DIR/logs/proxy.log"
PID_FILE="$PROXY_DIR/proxy.pid"

# Load PORT from .env if present
ENV_FILE="$PROXY_DIR/.env"
if [ -f "$ENV_FILE" ]; then
    _port=$(grep -E '^PORT=' "$ENV_FILE" | head -1 | cut -d'=' -f2 | tr -d '"' | tr -d "'" | tr -d '[:space:]')
    if [ -n "$_port" ]; then
        PORT="$_port"
    fi
fi

echo "=== Claude Code Proxy ==="
echo "Port: $PORT"

# Kill existing process recorded in PID file
if [ -f "$PID_FILE" ]; then
    OLD_PID=$(cat "$PID_FILE")
    if kill -0 "$OLD_PID" 2>/dev/null; then
        echo "Stopping existing process (PID: $OLD_PID)..."
        kill "$OLD_PID"
        sleep 1
    fi
    rm -f "$PID_FILE"
fi

# Also kill anything still listening on the port
LISTENING_PID=$(lsof -ti TCP:"$PORT" -sTCP:LISTEN 2>/dev/null || true)
if [ -n "$LISTENING_PID" ]; then
    echo "Stopping process on port $PORT (PID: $LISTENING_PID)..."
    kill "$LISTENING_PID" 2>/dev/null || true
    sleep 1
fi

# Ensure log directory exists
mkdir -p "$(dirname "$LOG_FILE")"

cd "$PROXY_DIR"

# Install / update dependencies
if [ ! -d "$PROXY_DIR/venv" ]; then
    echo "Creating virtual environment..."
    python3 -m venv "$PROXY_DIR/venv"
fi

echo "Installing dependencies..."
"$PROXY_DIR/venv/bin/pip" install -q --upgrade pip
"$PROXY_DIR/venv/bin/pip" install -q -r "$PROXY_DIR/requirements.txt"

# Start in background
# Python logging module writes to logs/proxy.log (with rotation).
# nohup captures stdout/stderr (startup messages, uncaught errors) to proxy-stdout.log.
STDOUT_LOG="$PROXY_DIR/logs/proxy-stdout.log"
echo "Starting Claude Code Proxy on port $PORT (background)..."
export PYTHONPATH="$PROXY_DIR/src"
nohup "$PROXY_DIR/venv/bin/python" "$PROXY_DIR/start_proxy.py" \
    >> "$STDOUT_LOG" 2>&1 &
PROXY_PID=$!
echo "$PROXY_PID" > "$PID_FILE"

# Wait briefly and verify it is still running
sleep 2
if kill -0 "$PROXY_PID" 2>/dev/null; then
    echo "Proxy started successfully (PID: $PROXY_PID)"
    echo "Log: $LOG_FILE"
else
    echo "ERROR: Proxy failed to start. Check logs:"
    echo "  $LOG_FILE"
    echo "  $STDOUT_LOG"
    tail -20 "$STDOUT_LOG"
    exit 1
fi
