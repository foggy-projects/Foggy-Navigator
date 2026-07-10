#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"
RUN_DIR="${CODEX_APP_SERVER_RUN_DIR:-$ROOT/logs/run}"
LOG_DIR="${CODEX_APP_SERVER_LOG_DIR:-$ROOT/logs}"
PID_FILE="$RUN_DIR/worker.pid"
STOP_FILE="$RUN_DIR/stop.request"
ENTRY="$ROOT/dist/index.js"

mkdir -p "$RUN_DIR" "$LOG_DIR"
if [[ -f "$PID_FILE" ]]; then
  existing_pid="$(tr -d '[:space:]' < "$PID_FILE")"
  if [[ "$existing_pid" =~ ^[0-9]+$ ]] && kill -0 "$existing_pid" 2>/dev/null; then
    echo "codex-app-server-worker is already running (PID $existing_pid)" >&2
    exit 1
  fi
  rm -f "$PID_FILE"
fi

if [[ "${1:-}" != "--no-build" ]]; then
  npm run build --prefix "$ROOT"
fi
[[ -f "$ENTRY" ]] || { echo "Missing build output: $ENTRY" >&2; exit 1; }
export CODEX_APP_SERVER_STATE_DIR="${CODEX_APP_SERVER_STATE_DIR:-$ROOT/logs/state}"
export CODEX_APP_SERVER_RUN_DIR="$RUN_DIR"
rm -f "$STOP_FILE"

nohup node "$ENTRY" >>"$LOG_DIR/worker.stdout.log" 2>>"$LOG_DIR/worker.stderr.log" &
pid=$!
sleep 0.5
if ! kill -0 "$pid" 2>/dev/null; then
  tail -n 20 "$LOG_DIR/worker.stderr.log" >&2 || true
  exit 1
fi
printf '%s' "$pid" > "$PID_FILE"
read_env_value() {
  local key="$1"
  [[ -f "$ROOT/.env" ]] || return 0
  sed -nE "s/^[[:space:]]*${key}[[:space:]]*=[[:space:]]*([^#[:space:]]+)[[:space:]]*$/\1/p" "$ROOT/.env" | tail -n 1 | tr -d "\"'"
}
display_host="${CODEX_APP_SERVER_WORKER_HOST:-$(read_env_value CODEX_APP_SERVER_WORKER_HOST)}"
display_host="${display_host:-127.0.0.1}"
display_port="${CODEX_APP_SERVER_WORKER_PORT:-$(read_env_value CODEX_APP_SERVER_WORKER_PORT)}"
display_port="${display_port:-3062}"
health_host="$display_host"
if [[ "$health_host" == "0.0.0.0" || "$health_host" == "::" || "$health_host" == "[::]" ]]; then
  health_host="127.0.0.1"
fi
health_url="http://$health_host:$display_port/health"
ready=false
attempt=0
while (( attempt < 120 )); do
  if ! kill -0 "$pid" 2>/dev/null; then
    tail -n 20 "$LOG_DIR/worker.stderr.log" >&2 || true
    rm -f "$PID_FILE"
    echo 'Worker exited before readiness' >&2
    exit 1
  fi
  health_body="$(curl -fsS --max-time 2 "$health_url" 2>/dev/null || true)"
  if [[ -n "$health_body" ]] && printf '%s' "$health_body" | node -e '
let body = "";
process.stdin.on("data", chunk => body += chunk);
process.stdin.on("end", () => {
  try { process.exit(JSON.parse(body).ready === true ? 0 : 1); }
  catch { process.exit(1); }
});'; then
    ready=true
    break
  fi
  attempt=$((attempt + 1))
  sleep 0.5
done
if [[ "$ready" != true ]]; then
  kill "$pid" 2>/dev/null || true
  sleep 1
  kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null || true
  rm -f "$PID_FILE"
  echo "Worker did not become ready: $health_url" >&2
  exit 1
fi
echo "codex-app-server-worker started (PID $pid, URL http://$display_host:$display_port)"
