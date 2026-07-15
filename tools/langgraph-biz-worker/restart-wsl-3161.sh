#!/usr/bin/env bash
# Restart/status helper for the WSL-hosted LangGraph Biz Worker.
# Usage:
#   tools/langgraph-biz-worker/restart-wsl-3161.sh
#   tools/langgraph-biz-worker/restart-wsl-3161.sh restart --sync-source
#   tools/langgraph-biz-worker/restart-wsl-3161.sh status --distro Ubuntu-24.04

set -euo pipefail

ACTION="restart"
PORT=3161
WSL_WORKER_DIR="/home/navigator/.langgraph-biz-worker"
WSL_USER="navigator"
ENV_FILE=".env"
SYNC_SOURCE=0
DISTRO=""
LOCAL_ONLY=0

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

usage() {
  cat <<'EOF'
Usage: restart-wsl-3161.sh [start|stop|restart|status] [options]

Options:
  --port <port>             Worker port, default 3161
  --worker-dir <path>       Worker install dir, default /home/navigator/.langgraph-biz-worker
  --user <user>             Worker Linux user, default navigator
  --env-file <file>         Env file inside worker dir, default .env
  --sync-source             Sync repo src/ and pyproject.toml before start
  --distro <name>           Run the action in another WSL distro via wsl.exe
  --help                    Show this help
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    start|stop|restart|status)
      ACTION="$1"
      shift
      ;;
    --port)
      PORT="$2"
      shift 2
      ;;
    --worker-dir)
      WSL_WORKER_DIR="$2"
      shift 2
      ;;
    --user)
      WSL_USER="$2"
      shift 2
      ;;
    --env-file)
      ENV_FILE="$2"
      shift 2
      ;;
    --sync-source)
      SYNC_SOURCE=1
      shift
      ;;
    --distro)
      DISTRO="$2"
      shift 2
      ;;
    --local-only)
      LOCAL_ONLY=1
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

find_wsl_exe() {
  if command -v wsl.exe >/dev/null 2>&1; then
    command -v wsl.exe
  elif [ -x /mnt/c/Windows/System32/wsl.exe ]; then
    printf '%s\n' /mnt/c/Windows/System32/wsl.exe
  else
    return 1
  fi
}

if [ -n "$DISTRO" ] && [ "$LOCAL_ONLY" -eq 0 ]; then
  WSL_EXE="$(find_wsl_exe)" || {
    echo "wsl.exe not found. Run without --distro inside the target WSL, or install WSL interop." >&2
    exit 1
  }

  LOCAL_SCRIPT="$(readlink -f "$0")"
  if [ "$SYNC_SOURCE" -eq 1 ] && { [ "$ACTION" = "start" ] || [ "$ACTION" = "restart" ]; }; then
    echo "Syncing LangGraph Biz Worker source to $DISTRO:$WSL_WORKER_DIR"
    {
      cat <<'REMOTE_SYNC_SCRIPT'
set -euo pipefail
worker_dir="$1"
worker_user="$2"
test -d "$worker_dir"
tmp_archive="$(mktemp /tmp/langgraph-biz-worker-src.XXXXXX.tar)"
cleanup() {
  rm -f "$tmp_archive"
}
trap cleanup EXIT
base64 -d > "$tmp_archive" <<'REMOTE_SYNC_ARCHIVE'
REMOTE_SYNC_SCRIPT
      tar -C "$SCRIPT_DIR" -cf - src pyproject.toml | base64
      cat <<'REMOTE_SYNC_SCRIPT'
REMOTE_SYNC_ARCHIVE
tar -C "$worker_dir" -xf "$tmp_archive"
if [ "$(id -u)" -eq 0 ] && [ -n "$worker_user" ]; then
  chown -R "$worker_user":"$worker_user" "$worker_dir/src" "$worker_dir/pyproject.toml" 2>/dev/null || true
fi
REMOTE_SYNC_SCRIPT
    } | "$WSL_EXE" -d "$DISTRO" --cd / -- bash -s -- "$WSL_WORKER_DIR" "$WSL_USER"
  fi

  REMOTE_ARGS=("$ACTION" "--port" "$PORT" "--worker-dir" "$WSL_WORKER_DIR" "--user" "$WSL_USER" "--env-file" "$ENV_FILE" "--local-only")
  {
    cat <<'REMOTE_RUN_SCRIPT'
set -euo pipefail
tmp_script="$(mktemp /tmp/restart-wsl-3161.XXXXXX.sh)"
cleanup() {
  rm -f "$tmp_script"
}
trap cleanup EXIT
base64 -d > "$tmp_script" <<'REMOTE_RUN_LOCAL_SCRIPT_B64'
REMOTE_RUN_SCRIPT
    base64 "$LOCAL_SCRIPT"
    cat <<'REMOTE_RUN_SCRIPT'
REMOTE_RUN_LOCAL_SCRIPT_B64
chmod +x "$tmp_script"
"$tmp_script" "$@"
REMOTE_RUN_SCRIPT
  } | "$WSL_EXE" -d "$DISTRO" --cd / -- bash -s -- "${REMOTE_ARGS[@]}"
  exit $?
fi

run_as_worker_user() {
  local user="$1"
  shift

  if [ -z "$user" ] || [ "$(id -un)" = "$user" ]; then
    "$@"
  elif [ "$(id -u)" -eq 0 ]; then
    runuser -u "$user" -- "$@"
  elif command -v sudo >/dev/null 2>&1 && sudo -n true >/dev/null 2>&1; then
    sudo -n -u "$user" "$@"
  else
    echo "Cannot run as '$user'. Re-run as root, configure passwordless sudo, or pass --user $(id -un)." >&2
    exit 1
  fi
}

listener_pids() {
  if command -v lsof >/dev/null 2>&1; then
    lsof -tiTCP:"$PORT" -sTCP:LISTEN 2>/dev/null || true
    return
  fi

  ss -ltnp 2>/dev/null \
    | grep -E "[:.]$PORT[[:space:]]" \
    | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p' \
    | sort -u || true
}

stop_worker() {
  local pids
  pids="$(listener_pids)"
  if [ -z "$pids" ]; then
    echo "No WSL biz worker listening on port $PORT"
    return 0
  fi

  for pid in $pids; do
    echo "Stopping WSL biz worker on port $PORT (pid=$pid)"
    kill "$pid" 2>/dev/null || sudo -n kill "$pid" 2>/dev/null || true
  done
  sleep 1
  for pid in $pids; do
    if kill -0 "$pid" 2>/dev/null; then
      kill -9 "$pid" 2>/dev/null || sudo -n kill -9 "$pid" 2>/dev/null || true
    fi
  done
}

sync_source() {
  [ "$SYNC_SOURCE" -eq 1 ] || return 0
  test -d "$SCRIPT_DIR/src"
  test -d "$WSL_WORKER_DIR/src"

  echo "Syncing LangGraph Biz Worker source to $WSL_WORKER_DIR"
  run_as_worker_user "$WSL_USER" rsync -a "$SCRIPT_DIR/src/" "$WSL_WORKER_DIR/src/"
  run_as_worker_user "$WSL_USER" rsync -a "$SCRIPT_DIR/pyproject.toml" "$WSL_WORKER_DIR/pyproject.toml"
}

start_worker() {
  test -d "$WSL_WORKER_DIR" || {
    echo "Worker dir not found: $WSL_WORKER_DIR" >&2
    exit 1
  }

  sync_source
  mkdir -p "$WSL_WORKER_DIR/logs"
  if [ "$(id -u)" -eq 0 ] && [ -n "$WSL_USER" ]; then
    chown -R "$WSL_USER":"$WSL_USER" "$WSL_WORKER_DIR/logs" 2>/dev/null || true
  fi

  run_as_worker_user "$WSL_USER" bash -lc '
set -euo pipefail
worker_dir="$1"
env_file="$2"
port="$3"
cd "$worker_dir"
if [ -x ".venv/bin/python" ]; then
  python_bin=".venv/bin/python"
elif [ -x ".venv-wsl/bin/python" ]; then
  python_bin=".venv-wsl/bin/python"
else
  python_bin="python3"
fi
mkdir -p logs
export PYTHONPATH="$worker_dir/src"
export BIZ_WORKER_ENV_FILE="$worker_dir/$env_file"
setsid -f sh -c '\''echo $$ > "logs/worker-$2.pid"; exec "$1" -m uvicorn langgraph_biz_worker.main:app --host 0.0.0.0 --port "$2"'\'' sh "$python_bin" "$port" > "logs/worker-$port.log" 2> "logs/worker-$port-error.log" < /dev/null
' bash "$WSL_WORKER_DIR" "$ENV_FILE" "$PORT"

  for _ in $(seq 1 30); do
    if curl -fsS "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then
      curl -fsS "http://127.0.0.1:$PORT/health"
      echo
      return 0
    fi
    sleep 1
  done

  echo "Worker did not become healthy on port $PORT" >&2
  tail -n 40 "$WSL_WORKER_DIR/logs/worker-$PORT-error.log" >&2 || true
  exit 1
}

status_worker() {
  if curl -fsS "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then
    echo "wsl-biz-worker port $PORT UP"
  else
    echo "wsl-biz-worker port $PORT DOWN"
    return 1
  fi
}

case "$ACTION" in
  stop)
    stop_worker
    ;;
  start)
    start_worker
    ;;
  restart)
    stop_worker
    start_worker
    ;;
  status)
    status_worker
    ;;
esac
