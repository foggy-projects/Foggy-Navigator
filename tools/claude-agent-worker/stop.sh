#!/usr/bin/env bash
# Claude Agent Worker stop script (Linux/macOS).
#
# It never escalates to SIGKILL. Claude's legacy Worker has no drain endpoint,
# so a stop is allowed only after an authenticated process snapshot proves that
# no active task and no managed CLI process remain.

set -euo pipefail
umask 077

WORKER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ENV_FILE="$WORKER_DIR/.env"
LOG_DIR="$WORKER_DIR/logs"
PID_FILE="$LOG_DIR/worker.pid"
EVIDENCE_DIR="$LOG_DIR/stop-evidence"
PORT=3031
SNAPSHOT_ACTIVE="unavailable"
SNAPSHOT_TOTAL="unavailable"
SAVED_PID=""
PID_FILE_INVALID=0
declare -a LISTENER_PIDS=()

read_dotenv_value() {
  local key="$1"
  local line value first last

  [ -f "$ENV_FILE" ] || return 0
  line="$(grep -E "^[[:space:]]*${key}=" "$ENV_FILE" | head -n 1 || true)"
  [ -n "$line" ] || return 0

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

configured_port="$(read_dotenv_value AGENT_WORKER_PORT)"
if [ -n "$configured_port" ]; then
  PORT="$configured_port"
fi
if [[ ! "$PORT" =~ ^[1-9][0-9]*$ ]] || [ "$PORT" -gt 65535 ]; then
  echo "Invalid AGENT_WORKER_PORT: $PORT" >&2
  exit 2
fi

mkdir -p "$EVIDENCE_DIR"
EVIDENCE_FILE="$EVIDENCE_DIR/stop-$(date -u +%Y%m%dT%H%M%SZ)-$$.log"

joined_listener_pids() {
  local joined=""
  local pid
  for pid in "${LISTENER_PIDS[@]}"; do
    joined="${joined:+$joined,}$pid"
  done
  printf '%s' "$joined"
}

write_evidence() {
  local result="$1"
  local action="$2"
  local detail="$3"

  {
    printf 'timestamp_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'worker=claude-agent-worker\n'
    printf 'port=%s\n' "$PORT"
    printf 'listener_pids=%s\n' "$(joined_listener_pids)"
    printf 'pid_file_pid=%s\n' "${SAVED_PID:-none}"
    printf 'snapshot_active_task_count=%s\n' "$SNAPSHOT_ACTIVE"
    printf 'snapshot_managed_process_count=%s\n' "$SNAPSHOT_TOTAL"
    printf 'action=%s\n' "$action"
    printf 'result=%s\n' "$result"
    printf 'detail=%s\n' "$detail"
  } > "$EVIDENCE_FILE"
  chmod 600 "$EVIDENCE_FILE" 2>/dev/null || true
  echo "Stop evidence: $EVIDENCE_FILE"
}

list_listener_pids() {
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -t -iTCP:"$PORT" -sTCP:LISTEN 2>/dev/null || true
    return 0
  fi
  if ! command -v ss >/dev/null 2>&1; then
    return 127
  fi

  local sockets pids
  sockets="$(ss -ltnp "sport = :$PORT" 2>/dev/null || true)"
  if ! printf '%s\n' "$sockets" | awk 'NR > 1 && $1 == "LISTEN" { found = 1 } END { exit !found }'; then
    return 0
  fi
  pids="$(printf '%s\n' "$sockets" | grep -o 'pid=[0-9][0-9]*' | cut -d= -f2 | sort -u || true)"
  [ -n "$pids" ] || return 126
  printf '%s\n' "$pids"
}

add_listener_pid() {
  local candidate="$1"
  local existing
  for existing in "${LISTENER_PIDS[@]}"; do
    [ "$existing" = "$candidate" ] && return 0
  done
  LISTENER_PIDS+=("$candidate")
}

process_cwd() {
  local pid="$1"
  local cwd=""

  if [ -e "/proc/$pid/cwd" ]; then
    cwd="$(readlink -f "/proc/$pid/cwd" 2>/dev/null || readlink "/proc/$pid/cwd" 2>/dev/null || true)"
  fi
  if [ -z "$cwd" ] && command -v lsof >/dev/null 2>&1; then
    cwd="$(lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -n 1 || true)"
  fi
  printf '%s\n' "$cwd"
}

is_owned_worker_pid() {
  local pid="$1"
  local args cwd
  args="$(ps -p "$pid" -o args= 2>/dev/null || true)"
  cwd="$(process_cwd "$pid")"

  [ "$cwd" = "$WORKER_DIR" ] || return 1
  [[ "$args" == *"agent_worker.main:app"* ]] || return 1
  [[ "$args" == *"uvicorn"* ]] || return 1
}

is_process_running() {
  kill -0 "$1" 2>/dev/null
}

snapshot_python() {
  if [ -x "$WORKER_DIR/.venv/bin/python" ]; then
    printf '%s\n' "$WORKER_DIR/.venv/bin/python"
  elif command -v python3 >/dev/null 2>&1; then
    command -v python3
  elif command -v python >/dev/null 2>&1; then
    command -v python
  fi
}

fetch_snapshot() {
  local body parsed python_bin
  local -a curl_args=(-fsS --max-time 3)

  if [ -n "$WORKER_TOKEN" ]; then
    curl_args+=(-H "Authorization: Bearer $WORKER_TOKEN")
  fi
  if ! body="$(curl "${curl_args[@]}" "http://127.0.0.1:$PORT/api/v1/processes" 2>/dev/null)"; then
    return 1
  fi
  python_bin="$(snapshot_python)"
  [ -n "$python_bin" ] || return 1
  if ! parsed="$(printf '%s' "$body" | "$python_bin" -c '
import json
import sys

try:
    snapshot = json.load(sys.stdin)
    active = snapshot["active_task_count"]
    total = snapshot["total"]
    if isinstance(active, bool) or isinstance(total, bool):
        raise ValueError("boolean count")
    if not isinstance(active, int) or active < 0 or not isinstance(total, int) or total < 0:
        raise ValueError("invalid count")
    print(f"{active} {total}", end="")
except Exception:
    raise SystemExit(1)
' 2>/dev/null)"; then
    return 1
  fi
  [[ "$parsed" =~ ^[0-9]+[[:space:]][0-9]+$ ]] || return 1
  SNAPSHOT_ACTIVE="${parsed%% *}"
  SNAPSHOT_TOTAL="${parsed##* }"
}

request_graceful_stop() {
  local pid
  for pid in "${LISTENER_PIDS[@]}"; do
    if is_process_running "$pid"; then
      echo "Requesting non-forced Claude Worker stop (PID=$pid)..."
      kill -TERM "$pid"
    fi
  done
}

wait_for_exit() {
  local deadline=$((SECONDS + 30))
  local pid alive

  while :; do
    alive=0
    for pid in "${LISTENER_PIDS[@]}"; do
      if is_process_running "$pid"; then
        alive=1
        break
      fi
    done
    [ "$alive" -eq 0 ] && return 0
    [ "$SECONDS" -ge "$deadline" ] && return 1
    sleep 1
  done
}

WORKER_TOKEN="$(read_dotenv_value AGENT_WORKER_WORKER_TOKEN)"

if ! raw_listener_pids="$(list_listener_pids)"; then
  write_evidence "WORKER_DRAIN_UNCONFIRMED" "none" "listener_inspection_unavailable"
  echo "Refusing to stop: listener inspection is required to prove Worker ownership." >&2
  exit 2
fi
while IFS= read -r candidate; do
  [ -z "$candidate" ] && continue
  if [[ "$candidate" =~ ^[1-9][0-9]*$ ]]; then
    add_listener_pid "$candidate"
  else
    write_evidence "WORKER_DRAIN_UNCONFIRMED" "none" "listener_pid_unparseable"
    echo "Refusing to stop: listener PID could not be parsed." >&2
    exit 2
  fi
done <<< "$raw_listener_pids"

if [ -f "$PID_FILE" ]; then
  saved_pid_value="$(tr -d '[:space:]' < "$PID_FILE")"
  if [ -n "$saved_pid_value" ]; then
    if [[ "$saved_pid_value" =~ ^[1-9][0-9]*$ ]]; then
      SAVED_PID="$saved_pid_value"
    else
      PID_FILE_INVALID=1
    fi
  fi
fi

if [ "$PID_FILE_INVALID" -eq 1 ]; then
  write_evidence "WORKER_DRAIN_UNCONFIRMED" "none" "pid_file_unparseable"
  echo "Refusing to stop: PID file is not trustworthy: $PID_FILE" >&2
  exit 2
fi

if [ -n "$SAVED_PID" ] && is_process_running "$SAVED_PID"; then
  saved_pid_is_listener=0
  for candidate in "${LISTENER_PIDS[@]}"; do
    if [ "$candidate" = "$SAVED_PID" ]; then
      saved_pid_is_listener=1
      break
    fi
  done
  if [ "$saved_pid_is_listener" -eq 0 ]; then
    write_evidence "WORKER_DRAIN_UNCONFIRMED" "none" "live_pid_file_process_not_listening"
    echo "Refusing to stop: live PID-file process is not the expected listener." >&2
    exit 2
  fi
fi

if [ "${#LISTENER_PIDS[@]}" -eq 0 ]; then
  if [ -n "$SAVED_PID" ] && ! is_process_running "$SAVED_PID"; then
    rm -f "$PID_FILE"
  fi
  write_evidence "NO_LISTENER" "none" "no_worker_listener_found"
  echo "No Claude Worker listener found on port $PORT."
  exit 0
fi

for candidate in "${LISTENER_PIDS[@]}"; do
  if ! is_owned_worker_pid "$candidate"; then
    write_evidence "WORKER_DRAIN_UNCONFIRMED" "none" "worker_ownership_unverified"
    echo "Refusing to stop PID $candidate: command line and workspace ownership are not proven." >&2
    exit 2
  fi
done

if ! fetch_snapshot; then
  write_evidence "WORKER_DRAIN_UNCONFIRMED" "none" "process_snapshot_unavailable_or_invalid"
  echo "Refusing to stop: unable to prove the Claude Worker task snapshot is quiescent." >&2
  exit 2
fi

if [ "$SNAPSHOT_ACTIVE" -ne 0 ] || [ "$SNAPSHOT_TOTAL" -ne 0 ]; then
  # Unlike the Codex Worker, this legacy Worker has no signal-safe drain
  # lifecycle. Retain it for an explicit operator-managed drain instead.
  write_evidence "WORKER_DRAIN_UNCONFIRMED" "none" "preflight_not_quiescent"
  echo "Claude Worker has active or unverified managed work; automatic restart is blocked. See $EVIDENCE_FILE" >&2
  exit 2
fi

write_evidence "QUIESCENT_STOP_REQUESTED" "non_forced_stop_requested" "preflight_quiescent"
if ! request_graceful_stop; then
  write_evidence "WORKER_DRAIN_UNCONFIRMED" "non_forced_stop_requested" "graceful_stop_signal_failed"
  echo "Non-forced stop signal failed; preserving Worker for operator review." >&2
  exit 2
fi

if ! wait_for_exit; then
  write_evidence "WORKER_DRAIN_UNCONFIRMED" "non_forced_stop_requested" "worker_exit_not_observed_within_30_seconds"
  echo "Claude Worker did not exit within 30 seconds; no forced termination was attempted." >&2
  exit 2
fi

if [ -n "$SAVED_PID" ] && ! is_process_running "$SAVED_PID"; then
  rm -f "$PID_FILE"
fi
write_evidence "QUIESCENT_STOPPED" "non_forced_stop_requested" "worker_exit_observed"
echo "Claude Worker stopped after a quiescent snapshot."
