#!/usr/bin/env bash
# Claude Code Proxy - Linux Stop Script
# Usage: bash stop.sh

PROXY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PORT=8082
PID_FILE="$PROXY_DIR/proxy.pid"

# Load PORT from .env if present
ENV_FILE="$PROXY_DIR/.env"
if [ -f "$ENV_FILE" ]; then
    _port=$(grep -E '^PORT=' "$ENV_FILE" | head -1 | cut -d'=' -f2 | tr -d '"' | tr -d "'" | tr -d '[:space:]')
    if [ -n "$_port" ]; then
        PORT="$_port"
    fi
fi

STOPPED=0

# Stop via PID file
if [ -f "$PID_FILE" ]; then
    OLD_PID=$(cat "$PID_FILE")
    if kill -0 "$OLD_PID" 2>/dev/null; then
        echo "Stopping Claude Code Proxy (PID: $OLD_PID)..."
        kill "$OLD_PID"
        STOPPED=1
    fi
    rm -f "$PID_FILE"
fi

# Fallback: kill anything still on the port
LISTENING_PID=$(lsof -ti TCP:"$PORT" -sTCP:LISTEN 2>/dev/null || true)
if [ -n "$LISTENING_PID" ]; then
    echo "Stopping process on port $PORT (PID: $LISTENING_PID)..."
    kill "$LISTENING_PID" 2>/dev/null || true
    STOPPED=1
fi

if [ "$STOPPED" -eq 1 ]; then
    echo "Claude Code Proxy stopped."
else
    echo "No Claude Code Proxy running on port $PORT."
fi
