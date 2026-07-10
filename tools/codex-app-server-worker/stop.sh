#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"
RUN_DIR="${CODEX_APP_SERVER_RUN_DIR:-$ROOT/logs/run}"
PID_FILE="$RUN_DIR/worker.pid"
STOP_FILE="$RUN_DIR/stop.request"
ENTRY="$ROOT/dist/index.js"
shutdown_timeout_ms="${CODEX_APP_SERVER_SHUTDOWN_TIMEOUT_MS:-}"
if [[ -z "$shutdown_timeout_ms" && -f "$ROOT/.env" ]]; then
  shutdown_timeout_ms="$(sed -nE 's/^[[:space:]]*CODEX_APP_SERVER_SHUTDOWN_TIMEOUT_MS[[:space:]]*=[[:space:]]*([0-9]+)[[:space:]]*$/\1/p' "$ROOT/.env" | tail -n 1)"
fi
[[ "$shutdown_timeout_ms" =~ ^[0-9]+$ ]] || shutdown_timeout_ms=30000
wait_iterations=$(( (shutdown_timeout_ms + 5000 + 99) / 100 ))

if [[ ! -f "$PID_FILE" ]]; then
  echo "codex-app-server-worker is not running (PID file missing)"
  exit 0
fi
pid="$(tr -d '[:space:]' < "$PID_FILE")"
[[ "$pid" =~ ^[0-9]+$ ]] || { echo 'Invalid Worker PID file' >&2; exit 1; }
if ! kill -0 "$pid" 2>/dev/null; then
  rm -f "$PID_FILE"
  echo "codex-app-server-worker is not running (stale PID removed)"
  exit 0
fi
command_line="$(ps -p "$pid" -o args=)"
[[ "$command_line" == *"$ENTRY"* ]] || { echo "PID $pid does not belong to this Worker" >&2; exit 1; }

printf '%s' "$(date +%s)" > "$STOP_FILE"
iteration=0
while (( iteration < wait_iterations )); do
  kill -0 "$pid" 2>/dev/null || break
  sleep 0.1
  iteration=$((iteration + 1))
done
if kill -0 "$pid" 2>/dev/null; then kill -9 "$pid"; fi
rm -f "$STOP_FILE"
rm -f "$PID_FILE"
echo 'codex-app-server-worker stopped'
