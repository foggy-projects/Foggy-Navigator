#!/usr/bin/env bash
# WSL/Linux local development stack controller.
# Usage:
#   scripts/local-dev-stack.sh restart --skip-build
#   scripts/local-dev-stack.sh status
#   scripts/local-dev-stack.sh restart --wsl-biz-distro Ubuntu-24.04

set -euo pipefail

ACTION="restart"
SKIP_BUILD=0
NO_BACKEND=0
NO_CLAUDE=0
NO_CODEX=0
NO_GEMINI=0
NO_LOCAL_BIZ=0
NO_WSL_BIZ=0
SYNC_WSL_BIZ_SOURCE=0
WSL_BIZ_DISTRO="Ubuntu-24.04"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BACKEND_PORT=8112
WSL_BIZ_PORT=3161

usage() {
  cat <<'EOF'
Usage: local-dev-stack.sh [start|stop|restart|status] [options]

Options:
  --skip-build              Skip backend Maven build
  --no-backend              Skip Java backend
  --no-claude               Skip Claude worker
  --no-codex                Skip Codex worker
  --no-gemini               Skip Gemini worker
  --no-local-biz            Skip local LangGraph Biz worker on 3061
  --no-wsl-biz              Skip WSL LangGraph Biz worker on 3161
  --sync-wsl-biz-source     Sync repo source to WSL biz worker before start
  --wsl-biz-distro <name>   Restart/status the 3161 worker in another WSL distro, default Ubuntu-24.04
  --wsl-biz-current         Restart/status the 3161 worker in the current WSL distro
  --help                    Show this help
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    start|stop|restart|status)
      ACTION="$1"
      shift
      ;;
    --skip-build|-SkipBuild)
      SKIP_BUILD=1
      shift
      ;;
    --no-backend|-NoBackend)
      NO_BACKEND=1
      shift
      ;;
    --no-claude|-NoClaude)
      NO_CLAUDE=1
      shift
      ;;
    --no-codex|-NoCodex)
      NO_CODEX=1
      shift
      ;;
    --no-gemini|-NoGemini)
      NO_GEMINI=1
      shift
      ;;
    --no-local-biz|--no-win-biz|-NoWinBiz)
      NO_LOCAL_BIZ=1
      shift
      ;;
    --no-wsl-biz|-NoWslBiz)
      NO_WSL_BIZ=1
      shift
      ;;
    --sync-wsl-biz-source|-SyncWslBizSource)
      SYNC_WSL_BIZ_SOURCE=1
      shift
      ;;
    --wsl-biz-distro)
      WSL_BIZ_DISTRO="$2"
      shift 2
      ;;
    --wsl-biz-current)
      WSL_BIZ_DISTRO=""
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

dotenv_value() {
  local file="$1"
  local key="$2"
  local default_value="$3"
  local line value first last

  [ -f "$file" ] || {
    printf '%s\n' "$default_value"
    return
  }

  line="$(grep -E "^[[:space:]]*$key=" "$file" | head -n 1 || true)"
  [ -n "$line" ] || {
    printf '%s\n' "$default_value"
    return
  }

  value="${line#*=}"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  if [ "${#value}" -ge 2 ]; then
    first="${value:0:1}"
    last="${value: -1}"
    if { [ "$first" = '"' ] && [ "$last" = '"' ]; } || { [ "$first" = "'" ] && [ "$last" = "'" ]; }; then
      value="${value:1:${#value}-2}"
    fi
  fi
  printf '%s\n' "$value"
}

port_pids() {
  local port="$1"
  if command -v lsof >/dev/null 2>&1; then
    lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true
  else
    ss -ltnp 2>/dev/null \
      | grep -E "[:.]$port[[:space:]]" \
      | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p' \
      | sort -u || true
  fi
}

test_health() {
  local url="$1"
  curl -fsS --max-time 3 "$url" >/dev/null 2>&1
}

write_status() {
  local label="$1"
  local port="$2"
  local health_url="$3"
  local pids health

  pids="$(port_pids "$port" | tr '\n' ',' | sed 's/,$//')"
  if [ -n "$pids" ]; then
    health="LISTENING"
    if [ -n "$health_url" ] && test_health "$health_url"; then
      health="UP"
    fi
    printf '%-18s port %-5s %-10s PID %s\n' "$label" "$port" "$health" "$pids"
  else
    printf '%-18s port %-5s DOWN\n' "$label" "$port"
  fi
}

stop_port() {
  local label="$1"
  local port="$2"
  local pids

  pids="$(port_pids "$port")"
  if [ -z "$pids" ]; then
    echo "$label is not listening on port $port."
    return 0
  fi

  for pid in $pids; do
    echo "Stopping $label on port $port (PID=$pid)..."
    kill "$pid" 2>/dev/null || true
  done
  sleep 1
  for pid in $pids; do
    if kill -0 "$pid" 2>/dev/null; then
      kill -9 "$pid" 2>/dev/null || true
    fi
  done
}

invoke_script() {
  local label="$1"
  local relative_path="$2"
  shift 2
  local script="$REPO_ROOT/$relative_path"

  [ -f "$script" ] || {
    echo "$label script not found: $script" >&2
    exit 1
  }

  echo
  echo "==> $label"
  bash "$script" "$@"
}

start_node_worker() {
  local label="$1"
  local dir="$2"
  local port="$3"

  echo
  echo "==> Start $label"
  (
    cd "$dir"
    stop_port "$label" "$port"
    if [ ! -d node_modules ]; then
      npm install
    fi
    mkdir -p logs
    rm -f logs/worker.pid
    setsid -f sh -c 'echo $$ > logs/worker.pid; exec npx tsx src/index.ts' > logs/worker.log 2> logs/worker-error.log < /dev/null
  )

  for _ in $(seq 1 40); do
    if test_health "http://127.0.0.1:$port/health"; then
      echo "$label is ready on http://localhost:$port"
      return 0
    fi
    sleep 1
  done

  echo "$label failed to become healthy on port $port" >&2
  tail -n 40 "$dir/logs/worker-error.log" >&2 || true
  exit 1
}

start_local_biz_worker() {
  local dir="$REPO_ROOT/tools/langgraph-biz-worker"
  local default_port env_file port python_bin

  default_port="$(dotenv_value "$dir/.env" BIZ_WORKER_PORT "3061")"
  if [ -f "$dir/.env.local" ]; then
    port="$(dotenv_value "$dir/.env.local" BIZ_WORKER_PORT "$default_port")"
    env_file=".env.local"
  elif [ -f "$dir/.env" ]; then
    port="$default_port"
    env_file=".env"
  else
    port="$(dotenv_value "$dir/.env.example" BIZ_WORKER_PORT "3061")"
    env_file=".env.example"
  fi

  echo
  echo "==> Start Local LangGraph Biz Worker"
  (
    cd "$dir"
    stop_port "local-biz-worker" "$port"
    if [ ! -x .venv/bin/python ]; then
      python3 -m venv .venv
      .venv/bin/python -m pip install --upgrade pip
      .venv/bin/python -m pip install -e .
    fi
    python_bin=".venv/bin/python"
    mkdir -p logs
    rm -f logs/worker.pid
    export PYTHONPATH="$dir/src"
    export BIZ_WORKER_ENV_FILE="$dir/$env_file"
    nohup "$python_bin" -m uvicorn langgraph_biz_worker.main:app --host 0.0.0.0 --port "$port" > logs/worker.log 2> logs/worker-error.log < /dev/null &
    echo $! > logs/worker.pid
  )

  for _ in $(seq 1 40); do
    if test_health "http://127.0.0.1:$port/health"; then
      echo "local-biz-worker is ready on http://localhost:$port"
      return 0
    fi
    sleep 1
  done

  echo "local-biz-worker failed to become healthy on port $port" >&2
  tail -n 40 "$dir/logs/worker-error.log" >&2 || true
  exit 1
}

invoke_wsl_biz() {
  local action="$1"
  local args=("$action" "--port" "$WSL_BIZ_PORT")
  if [ "$SYNC_WSL_BIZ_SOURCE" -eq 1 ]; then
    args+=("--sync-source")
  fi
  if [ -n "$WSL_BIZ_DISTRO" ]; then
    args+=("--distro" "$WSL_BIZ_DISTRO")
  fi
  invoke_script "WSL LangGraph Biz Worker ($action)" "tools/langgraph-biz-worker/restart-wsl-3161.sh" "${args[@]}"
}

CLAUDE_PORT="$(dotenv_value "$REPO_ROOT/tools/claude-agent-worker/.env" AGENT_WORKER_PORT "3031")"
CODEX_PORT="$(dotenv_value "$REPO_ROOT/tools/codex-agent-worker/.env" CODEX_WORKER_PORT "3051")"
GEMINI_PORT="$(dotenv_value "$REPO_ROOT/tools/gemini-agent-worker/.env" GEMINI_WORKER_PORT "3071")"
LOCAL_BIZ_DEFAULT_PORT="$(dotenv_value "$REPO_ROOT/tools/langgraph-biz-worker/.env" BIZ_WORKER_PORT "3061")"
LOCAL_BIZ_PORT="$(dotenv_value "$REPO_ROOT/tools/langgraph-biz-worker/.env.local" BIZ_WORKER_PORT "$LOCAL_BIZ_DEFAULT_PORT")"

cd "$REPO_ROOT"

if [ "$ACTION" = "status" ]; then
  [ "$NO_BACKEND" -eq 1 ] || write_status "backend" "$BACKEND_PORT" "http://127.0.0.1:$BACKEND_PORT/actuator/health"
  [ "$NO_CLAUDE" -eq 1 ] || write_status "claude-worker" "$CLAUDE_PORT" "http://127.0.0.1:$CLAUDE_PORT/health"
  [ "$NO_CODEX" -eq 1 ] || write_status "codex-worker" "$CODEX_PORT" "http://127.0.0.1:$CODEX_PORT/health"
  [ "$NO_GEMINI" -eq 1 ] || write_status "gemini-worker" "$GEMINI_PORT" "http://127.0.0.1:$GEMINI_PORT/health"
  [ "$NO_LOCAL_BIZ" -eq 1 ] || write_status "local-biz-worker" "$LOCAL_BIZ_PORT" "http://127.0.0.1:$LOCAL_BIZ_PORT/health"
  [ "$NO_WSL_BIZ" -eq 1 ] || invoke_wsl_biz status || true
  exit 0
fi

if [ "$ACTION" = "stop" ] || [ "$ACTION" = "restart" ]; then
  [ "$NO_GEMINI" -eq 1 ] || stop_port "gemini-worker" "$GEMINI_PORT"
  [ "$NO_CODEX" -eq 1 ] || invoke_script "Stop Codex Worker" "tools/codex-agent-worker/stop.sh"
  [ "$NO_CLAUDE" -eq 1 ] || invoke_script "Stop Claude Worker" "tools/claude-agent-worker/stop.sh"
  [ "$NO_LOCAL_BIZ" -eq 1 ] || stop_port "local-biz-worker" "$LOCAL_BIZ_PORT"
  [ "$NO_BACKEND" -eq 1 ] || invoke_script "Stop Java Backend" "scripts/stop-launcher.sh"
  [ "$NO_WSL_BIZ" -eq 1 ] || invoke_wsl_biz stop
fi

if [ "$ACTION" = "stop" ]; then
  echo
  echo "Local stack stopped."
  exit 0
fi

if [ "$ACTION" = "start" ] || [ "$ACTION" = "restart" ]; then
  [ "$NO_WSL_BIZ" -eq 1 ] || invoke_wsl_biz restart
  [ "$NO_LOCAL_BIZ" -eq 1 ] || start_local_biz_worker
  [ "$NO_CLAUDE" -eq 1 ] || invoke_script "Start Claude Worker" "tools/claude-agent-worker/start.sh"
  [ "$NO_CODEX" -eq 1 ] || invoke_script "Start Codex Worker" "tools/codex-agent-worker/start.sh"
  [ "$NO_GEMINI" -eq 1 ] || start_node_worker "gemini-worker" "$REPO_ROOT/tools/gemini-agent-worker" "$GEMINI_PORT"

  if [ "$NO_BACKEND" -eq 0 ]; then
    backend_args=()
    if [ "$SKIP_BUILD" -eq 1 ]; then
      backend_args+=("--skip-build")
    fi
    invoke_script "Start Java Backend" "scripts/start-launcher.sh" "${backend_args[@]}"
  fi
fi

echo
echo "Local stack status:"
[ "$NO_BACKEND" -eq 1 ] || write_status "backend" "$BACKEND_PORT" "http://127.0.0.1:$BACKEND_PORT/actuator/health"
[ "$NO_CLAUDE" -eq 1 ] || write_status "claude-worker" "$CLAUDE_PORT" "http://127.0.0.1:$CLAUDE_PORT/health"
[ "$NO_CODEX" -eq 1 ] || write_status "codex-worker" "$CODEX_PORT" "http://127.0.0.1:$CODEX_PORT/health"
[ "$NO_GEMINI" -eq 1 ] || write_status "gemini-worker" "$GEMINI_PORT" "http://127.0.0.1:$GEMINI_PORT/health"
[ "$NO_LOCAL_BIZ" -eq 1 ] || write_status "local-biz-worker" "$LOCAL_BIZ_PORT" "http://127.0.0.1:$LOCAL_BIZ_PORT/health"
[ "$NO_WSL_BIZ" -eq 1 ] || invoke_wsl_biz status || true
