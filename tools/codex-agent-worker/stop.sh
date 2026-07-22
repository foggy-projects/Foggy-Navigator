#!/usr/bin/env bash
# Codex Agent Worker stop script (Linux/macOS).
#
# This script is intentionally fail-closed. A port listener alone is not
# ownership proof, and a missing or non-quiescent Worker snapshot is not
# permission to terminate a managed task. Evidence contains only counts and
# PIDs; never write process command lines or Worker tokens to disk.

set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ENV_FILE="$SCRIPT_DIR/.env"
LOG_DIR="$SCRIPT_DIR/logs"
PID_FILE="$LOG_DIR/worker.pid"
EVIDENCE_DIR="$LOG_DIR/stop-evidence"
PORT=3051
SNAPSHOT_ACTIVE="unavailable"
SNAPSHOT_TOTAL="unavailable"
SAVED_PID=""
PID_FILE_INVALID=0
SAVED_PID_IS_LAUNCHER=0
FORCE_OWNED=0
declare -a LISTENER_PIDS=()

usage() {
  cat <<'EOF'
Usage: stop.sh [--force-owned]

Options:
  --force-owned  After current-checkout listener ownership has been verified,
                 allow TERM then KILL even when the task snapshot is non-quiescent.
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --force-owned)
      FORCE_OWNED=1
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

configured_port="$(read_dotenv_value CODEX_WORKER_PORT)"
if [ -n "$configured_port" ]; then
  PORT="$configured_port"
fi
if [[ ! "$PORT" =~ ^[1-9][0-9]*$ ]] || [ "$PORT" -gt 65535 ]; then
  echo "Invalid CODEX_WORKER_PORT: $PORT" >&2
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
    printf 'worker=codex-agent-worker\n'
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

  [ "$cwd" = "$SCRIPT_DIR" ] || return 1
  [[ "$args" == *"src/index.ts"* ]] || return 1
  [[ "$args" == *"node"* || "$args" == *"tsx"* ]] || return 1
}

is_owned_launcher_pid() {
  local pid="$1"
  local args cwd
  args="$(ps -p "$pid" -o args= 2>/dev/null || true)"
  cwd="$(process_cwd "$pid")"

  [ "$cwd" = "$SCRIPT_DIR" ] || return 1
  [[ "$args" == *"tsx src/index.ts"* ]] || return 1
  [[ "$args" == *"npm"* || "$args" == *"node"* || "$args" == *"sh -c"* ]] || return 1
}

is_ancestor_of_listener() {
  local ancestor="$1"
  local current parent listener hops

  for listener in "${LISTENER_PIDS[@]}"; do
    current="$listener"
    hops=0
    while [ "$hops" -lt 64 ]; do
      [ "$current" = "$ancestor" ] && return 0
      parent="$(ps -p "$current" -o ppid= 2>/dev/null | tr -d '[:space:]' || true)"
      [[ "$parent" =~ ^[1-9][0-9]*$ ]] || break
      [ "$parent" = "$current" ] && break
      current="$parent"
      hops=$((hops + 1))
    done
  done
  return 1
}

is_process_running() {
  kill -0 "$1" 2>/dev/null
}

fetch_snapshot() {
  local body parsed
  local -a curl_args=(-fsS --max-time 3)

  if [ -n "$WORKER_TOKEN" ]; then
    curl_args+=(-H "Authorization: Bearer $WORKER_TOKEN")
  fi
  if ! body="$(curl "${curl_args[@]}" "http://127.0.0.1:$PORT/api/v1/processes" 2>/dev/null)"; then
    return 1
  fi
  if ! parsed="$(printf '%s' "$body" | node -e '
    let body = "";
    process.stdin.on("data", chunk => { body += chunk; });
    process.stdin.on("end", () => {
      try {
        const snapshot = JSON.parse(body);
        const active = snapshot.active_task_count;
        const total = snapshot.total;
        if (!Number.isInteger(active) || active < 0 || !Number.isInteger(total) || total < 0) process.exit(1);
        process.stdout.write(`${active} ${total}`);
      } catch {
        process.exit(1);
      }
    });
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
      echo "Requesting graceful Codex Worker drain (PID=$pid)..."
      kill -TERM "$pid"
    fi
  done
}

wait_for_exit() {
  local timeout_seconds="${1:-30}"
  local deadline=$((SECONDS + timeout_seconds))
  local pid alive

  while :; do
    alive=0
    for pid in "${LISTENER_PIDS[@]}"; do
      if is_process_running "$pid"; then
        alive=1
        break
      fi
    done
    if [ "$alive" -eq 0 ] && [ "$SAVED_PID_IS_LAUNCHER" -eq 1 ] && is_process_running "$SAVED_PID"; then
      alive=1
    fi
    [ "$alive" -eq 0 ] && return 0
    [ "$SECONDS" -ge "$deadline" ] && return 1
    sleep 1
  done
}

force_stop_verified_listeners() {
  local reason="$1"
  local pid

  echo "Requesting graceful stop before verified local Codex Worker recovery kill..." >&2
  request_graceful_stop
  if wait_for_exit 5; then
    if [ -n "$SAVED_PID" ] && ! is_process_running "$SAVED_PID"; then
      rm -f "$PID_FILE"
    fi
    write_evidence "FORCED_STOPPED" "term_requested" "${reason}_listener_exited_after_term"
    return 0
  fi

  for pid in "${LISTENER_PIDS[@]}"; do
    if is_process_running "$pid"; then
      echo "Forcing verified Codex Worker listener stop (PID=$pid)..." >&2
      kill -KILL "$pid"
    fi
  done

  if ! wait_for_exit 5; then
    write_evidence "WORKER_DRAIN_UNCONFIRMED" "term_then_kill_requested" "verified_listener_exit_not_observed_after_kill"
    echo "Verified Codex Worker listener did not exit after KILL." >&2
    return 1
  fi

  if [ -n "$SAVED_PID" ] && ! is_process_running "$SAVED_PID"; then
    rm -f "$PID_FILE"
  fi
  write_evidence "FORCED_STOPPED" "term_then_kill_requested" "${reason}_verified_listener_killed"
  echo "Codex Worker stopped after TERM and KILL of a verified listener."
}

WORKER_TOKEN="$(read_dotenv_value CODEX_WORKER_TOKEN)"

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
    if is_owned_launcher_pid "$SAVED_PID" && is_ancestor_of_listener "$SAVED_PID"; then
      SAVED_PID_IS_LAUNCHER=1
    else
      write_evidence "WORKER_DRAIN_UNCONFIRMED" "none" "live_pid_file_process_not_owned_listener_or_launcher"
      echo "Refusing to stop: live PID-file process is not the expected listener or its verified launcher." >&2
      exit 2
    fi
  fi
fi

if [ "${#LISTENER_PIDS[@]}" -eq 0 ]; then
  if [ -n "$SAVED_PID" ] && ! is_process_running "$SAVED_PID"; then
    rm -f "$PID_FILE"
  fi
  write_evidence "NO_LISTENER" "none" "no_worker_listener_found"
  echo "No Codex Worker listener found on port $PORT."
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
  force_stop_verified_listeners "snapshot_unavailable"
  exit $?
fi

if [ "$SNAPSHOT_ACTIVE" -ne 0 ] || [ "$SNAPSHOT_TOTAL" -ne 0 ]; then
  if [ "$FORCE_OWNED" -eq 1 ]; then
    force_stop_verified_listeners "force_owned_preflight_not_quiescent"
    exit $?
  fi
  # The Codex Worker handles SIGTERM as ingress drain and keeps itself alive
  # while active executions or reservations remain. Do not auto-restart even
  # if it exits: this invocation did not obtain a post-drain quiescent snapshot.
  write_evidence "WORKER_DRAIN_UNCONFIRMED" "graceful_drain_requested" "preflight_not_quiescent"
  if ! request_graceful_stop; then
    write_evidence "WORKER_DRAIN_UNCONFIRMED" "graceful_drain_requested" "graceful_drain_signal_failed"
    echo "Graceful drain signal failed; preserving Worker for operator review." >&2
    exit 2
  fi
  if wait_for_exit; then
    write_evidence "WORKER_DRAIN_UNCONFIRMED" "graceful_drain_requested" "worker_exited_without_post_drain_snapshot"
  else
    write_evidence "WORKER_DRAIN_UNCONFIRMED" "graceful_drain_requested" "worker_retained_active_or_unverified_state"
  fi
  echo "Codex Worker had active or unverified managed work; automatic restart is blocked. See $EVIDENCE_FILE" >&2
  exit 2
fi

write_evidence "QUIESCENT_STOP_REQUESTED" "graceful_stop_requested" "preflight_quiescent"
if ! request_graceful_stop; then
  write_evidence "WORKER_DRAIN_UNCONFIRMED" "graceful_stop_requested" "graceful_stop_signal_failed"
  echo "Graceful stop signal failed; preserving Worker for operator review." >&2
  exit 2
fi

if ! wait_for_exit; then
  write_evidence "WORKER_DRAIN_UNCONFIRMED" "graceful_stop_requested" "worker_exit_not_observed_within_30_seconds"
  echo "Codex Worker did not exit within 30 seconds; no forced termination was attempted." >&2
  exit 2
fi

if [ -n "$SAVED_PID" ] && ! is_process_running "$SAVED_PID"; then
  rm -f "$PID_FILE"
fi
write_evidence "QUIESCENT_STOPPED" "graceful_stop_requested" "worker_exit_observed"
echo "Codex Worker stopped after a quiescent snapshot."
