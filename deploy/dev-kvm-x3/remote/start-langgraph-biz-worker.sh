#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
. "$SCRIPT_DIR/lib.sh"

BOOTSTRAP_ENV="${PLATFORM_BOOTSTRAP_ENV:-$RUNTIME_DIR/platform-bootstrap.env}"
[ -f "$BOOTSTRAP_ENV" ] || die "Missing platform bootstrap env: $BOOTSTRAP_ENV"

set -a
# shellcheck disable=SC1090
. "$BOOTSTRAP_ENV"
set +a

SOURCE_DIR="${BIZ_WORKER_SOURCE_DIR:-/opt/foggy/navigator/current/tools/langgraph-biz-worker}"
VENV_DIR="${BIZ_WORKER_VENV_DIR:-$SOURCE_DIR/.venv-x3}"
WORKER_ENV_FILE="${BIZ_WORKER_RUNTIME_ENV_FILE:-$RUNTIME_DIR/langgraph-biz-worker.env}"
PID_FILE="${BIZ_WORKER_PID_FILE:-$RUNTIME_DIR/langgraph-biz-worker.pid}"
LOG_DIR="${BIZ_WORKER_LOG_DIR:-$RUNTIME_DIR/logs}"
LOG_FILE="$LOG_DIR/langgraph-biz-worker.log"
PORT="${BIZ_WORKER_PORT:-3061}"
HOST="${BIZ_WORKER_HOST:-0.0.0.0}"

require_command python3
require_command curl

[ -d "$SOURCE_DIR" ] || die "Missing LangGraph Biz Worker source dir: $SOURCE_DIR"
mkdir -p "$RUNTIME_DIR" "$LOG_DIR"

ensure_generated_secret() {
  local key="$1"
  local prefix="$2"
  local current="${!key:-}"
  if [ -n "$current" ]; then
    return
  fi
  require_command openssl
  local value
  value="${prefix}$(openssl rand -hex 24)"
  printf '\n%s=%s\n' "$key" "$value" >> "$BOOTSTRAP_ENV"
  chmod 0600 "$BOOTSTRAP_ENV"
  export "$key=$value"
}

ensure_generated_secret NAVIGATOR_BIZ_WORKER_TOKEN "bw_"

cat > "$WORKER_ENV_FILE" <<EOF
BIZ_WORKER_PORT=$PORT
BIZ_WORKER_HOST=$HOST
BIZ_WORKER_WORKER_NAME=${NAVIGATOR_BIZ_WORKER_NAME:-dev-kvm-x3 LangGraph Biz Worker}
BIZ_WORKER_WORKER_TOKEN=${NAVIGATOR_BIZ_WORKER_TOKEN}
BIZ_WORKER_MAX_CONCURRENT_TASKS=${BIZ_WORKER_MAX_CONCURRENT_TASKS:-5}
BIZ_WORKER_DATA_ROOT=${BIZ_WORKER_DATA_ROOT:-$RUNTIME_DIR/langgraph-biz-worker-data}
BIZ_WORKER_LLM_PROVIDER=openai
BIZ_WORKER_LLM_BASE_URL=${NAVIGATOR_LLM_BASE_URL:?NAVIGATOR_LLM_BASE_URL is required}
BIZ_WORKER_LLM_API_KEY=${NAVIGATOR_LLM_API_KEY:?NAVIGATOR_LLM_API_KEY is required}
BIZ_WORKER_LLM_MODEL=${NAVIGATOR_LLM_MODEL:?NAVIGATOR_LLM_MODEL is required}
BIZ_WORKER_LLM_TEMPERATURE=${BIZ_WORKER_LLM_TEMPERATURE:-0.0}
BIZ_WORKER_LLM_AGENTIC_ROUTING=${BIZ_WORKER_LLM_AGENTIC_ROUTING:-true}
BIZ_WORKER_LLM_EXECUTE_SKILLS=${BIZ_WORKER_LLM_EXECUTE_SKILLS:-true}
BIZ_WORKER_LLM_SKILL_MAX_ITERATIONS=${BIZ_WORKER_LLM_SKILL_MAX_ITERATIONS:-6}
BIZ_WORKER_NAVIGATOR_API_BASE=${NAVIGATOR_PUBLIC_BASE_URL:-http://192.168.31.81:8112}
EOF
chmod 0600 "$WORKER_ENV_FILE"

if [ ! -x "$VENV_DIR/bin/python" ]; then
  log "Creating Python venv for LangGraph Biz Worker"
  if ! python3 -m venv "$VENV_DIR"; then
    log "python3 venv support missing; installing python3-venv"
    sudo apt-get update
    sudo apt-get install -y python3-venv python3.10-venv || sudo apt-get install -y python3-venv
    python3 -m venv "$VENV_DIR"
  fi
fi

log "Installing LangGraph Biz Worker Python dependencies"
"$VENV_DIR/bin/python" -m pip install --upgrade pip wheel >/dev/null
"$VENV_DIR/bin/python" -m pip install -e "$SOURCE_DIR" >/dev/null

if [ -f "$PID_FILE" ]; then
  old_pid="$(cat "$PID_FILE" 2>/dev/null || true)"
  if [ -n "$old_pid" ] && kill -0 "$old_pid" 2>/dev/null; then
    log "Stopping existing LangGraph Biz Worker pid=$old_pid"
    kill "$old_pid" || true
    for _ in $(seq 1 20); do
      kill -0 "$old_pid" 2>/dev/null || break
      sleep 0.5
    done
  fi
fi

if ss -ltn 2>/dev/null | awk '{print $4}' | grep -Eq "(:|\\])$PORT$"; then
  die "Port $PORT is already in use and pid file is not owning it: $PID_FILE"
fi

log "Starting LangGraph Biz Worker on $HOST:$PORT"
cd "$SOURCE_DIR"
BIZ_WORKER_ENV_FILE="$WORKER_ENV_FILE" \
  nohup "$VENV_DIR/bin/python" -m uvicorn langgraph_biz_worker.main:app \
  --host "$HOST" --port "$PORT" > "$LOG_FILE" 2>&1 &
echo "$!" > "$PID_FILE"

for _ in $(seq 1 40); do
  if curl -fsS "http://127.0.0.1:$PORT/health" >/dev/null; then
    log "LangGraph Biz Worker health OK: http://127.0.0.1:$PORT/health"
    exit 0
  fi
  sleep 1
done

tail -n 80 "$LOG_FILE" >&2 || true
die "LangGraph Biz Worker failed to become healthy"
