#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"
UPDATE_TRANSACTION_NONCE=""
LIFECYCLE_LOCK_NONCE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --update-transaction-nonce) UPDATE_TRANSACTION_NONCE="${2:?missing transaction nonce}"; shift 2 ;;
    --update-transaction-nonce=*) UPDATE_TRANSACTION_NONCE="${1#*=}"; shift ;;
    --lifecycle-lock-nonce) LIFECYCLE_LOCK_NONCE="${2:?missing lifecycle lock nonce}"; shift 2 ;;
    --lifecycle-lock-nonce=*) LIFECYCLE_LOCK_NONCE="${1#*=}"; shift ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done
[[ "$(uname -s)" == Linux ]] || { echo 'codex-app-server-worker lifecycle operations require Linux exact process identity support' >&2; exit 5; }
read_env_value() {
  local key="$1"
  [[ -f "$ROOT/.env" ]] || return 0
  node "$ROOT/scripts/read-dotenv-value.mjs" "$ROOT/.env" "$key"
}
trim_value() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "$value"
}
select_config_value() {
  local process_value dotenv_value default_value
  process_value="$(trim_value "$1")"
  dotenv_value="$(trim_value "$2")"
  default_value="$3"
  if [[ -n "$process_value" ]]; then
    printf '%s' "$process_value"
  elif [[ -n "$dotenv_value" ]]; then
    printf '%s' "$dotenv_value"
  else
    printf '%s' "$default_value"
  fi
}
run_process_tree() {
  if node "$PROCESS_TREE_HELPER" "$@" >/dev/null 2>&1; then
    PROCESS_TREE_STATUS=0
  else
    PROCESS_TREE_STATUS=$?
  fi
}
run_lifecycle_marker() {
  if node "$LIFECYCLE_MARKER_HELPER" "$@" >/dev/null 2>&1; then
    LIFECYCLE_MARKER_STATUS=0
  else
    LIFECYCLE_MARKER_STATUS=$?
  fi
}
write_failed_stop_latch() {
  local reason="$1"
  run_lifecycle_marker write-once --path "$FAILED_STOP_FILE" --reason "$reason"
  (( LIFECYCLE_MARKER_STATUS == 0 ))
}
remove_evidence_file() {
  local evidence="$1"
  if [[ -e "$evidence" || -L "$evidence" ]]; then rm -f -- "$evidence" || return 1; fi
  [[ ! -e "$evidence" && ! -L "$evidence" ]]
}

dotenv_run_dir="$(read_env_value CODEX_APP_SERVER_RUN_DIR)"
dotenv_log_dir="$(read_env_value CODEX_APP_SERVER_LOG_DIR)"
dotenv_state_dir="$(read_env_value CODEX_APP_SERVER_STATE_DIR)"
dotenv_host="$(read_env_value CODEX_APP_SERVER_WORKER_HOST)"
dotenv_port="$(read_env_value CODEX_APP_SERVER_WORKER_PORT)"
RUN_DIR="$(select_config_value "${CODEX_APP_SERVER_RUN_DIR:-}" "$dotenv_run_dir" "$ROOT/logs/run")"
LOG_DIR="$(select_config_value "${CODEX_APP_SERVER_LOG_DIR:-}" "$dotenv_log_dir" "$ROOT/logs")"
STATE_DIR="$(select_config_value "${CODEX_APP_SERVER_STATE_DIR:-}" "$dotenv_state_dir" "$ROOT/logs/state")"
display_host="$(select_config_value "${CODEX_APP_SERVER_WORKER_HOST:-}" "$dotenv_host" "127.0.0.1")"
display_port="$(select_config_value "${CODEX_APP_SERVER_WORKER_PORT:-}" "$dotenv_port" "3062")"

export CODEX_APP_SERVER_RUN_DIR="$RUN_DIR"
export CODEX_APP_SERVER_LOG_DIR="$LOG_DIR"
export CODEX_APP_SERVER_STATE_DIR="$STATE_DIR"
export CODEX_APP_SERVER_WORKER_HOST="$display_host"
export CODEX_APP_SERVER_WORKER_PORT="$display_port"

PID_FILE="$RUN_DIR/worker.pid"
STOP_FILE="$RUN_DIR/stop.request"
FAILED_STOP_FILE="$RUN_DIR/stop.failed"
SHUTDOWN_SUCCESS_FILE="$RUN_DIR/shutdown.success"
SHUTDOWN_FAILURE_FILE="$RUN_DIR/shutdown.failure"
SNAPSHOT_FILE="$RUN_DIR/worker.process-tree.json"
PROCESS_TREE_HELPER="$ROOT/scripts/process-tree.mjs"
LIFECYCLE_MARKER_HELPER="$ROOT/scripts/lifecycle-marker.mjs"
LIFECYCLE_LOCK_FILE="$ROOT/lifecycle.lock"
UPDATE_TRANSACTION_FILE="$ROOT/update.in-progress"
ENTRY="$ROOT/dist/index.js"
dotenv_shutdown_timeout_ms="$(read_env_value CODEX_APP_SERVER_SHUTDOWN_TIMEOUT_MS)"
shutdown_timeout_ms="$(select_config_value "${CODEX_APP_SERVER_SHUTDOWN_TIMEOUT_MS:-}" "$dotenv_shutdown_timeout_ms" "30000")"
[[ "$shutdown_timeout_ms" =~ ^[0-9]+$ ]] || shutdown_timeout_ms=30000
shutdown_wait_seconds=$(( (shutdown_timeout_ms + 5000 + 999) / 1000 ))

if [[ ! -f "$PROCESS_TREE_HELPER" || ! -f "$LIFECYCLE_MARKER_HELPER" ]]; then
  echo 'Required lifecycle helpers are missing' >&2
  exit 5
fi
OWN_LIFECYCLE_LOCK=false
PRESERVE_LIFECYCLE_LOCK=false
release_lifecycle_lock() {
  local status=$?
  trap - EXIT
  if [[ "$OWN_LIFECYCLE_LOCK" == true && "$PRESERVE_LIFECYCLE_LOCK" != true ]]; then
    run_lifecycle_marker lock-release --path "$LIFECYCLE_LOCK_FILE" --nonce "$LIFECYCLE_LOCK_NONCE"
    if (( LIFECYCLE_MARKER_STATUS != 0 )); then
      echo "Failed to release the owned lifecycle lock at $LIFECYCLE_LOCK_FILE" >&2
      status=5
    fi
  fi
  exit "$status"
}
preserve_lifecycle_lock_on_signal() {
  local status="$1"
  PRESERVE_LIFECYCLE_LOCK=true
  trap - HUP INT TERM
  exit "$status"
}
trap 'preserve_lifecycle_lock_on_signal 129' HUP
trap 'preserve_lifecycle_lock_on_signal 130' INT
trap 'preserve_lifecycle_lock_on_signal 143' TERM
if [[ -z "$LIFECYCLE_LOCK_NONCE" && -n "$UPDATE_TRANSACTION_NONCE" ]]; then
  echo 'Internal update transaction ownership also requires the lifecycle lock nonce' >&2
  exit 5
fi
if [[ -n "$LIFECYCLE_LOCK_NONCE" && ( -z "$UPDATE_TRANSACTION_NONCE" || "$UPDATE_TRANSACTION_NONCE" != "$LIFECYCLE_LOCK_NONCE" ) ]]; then
  echo 'Internal lifecycle lock ownership requires the matching update transaction nonce' >&2
  exit 5
fi
if [[ -z "$LIFECYCLE_LOCK_NONCE" ]]; then
  LIFECYCLE_LOCK_NONCE="$(node -e 'process.stdout.write(require("node:crypto").randomBytes(16).toString("hex"))')"
  run_lifecycle_marker lock-acquire --path "$LIFECYCLE_LOCK_FILE" --nonce "$LIFECYCLE_LOCK_NONCE" --operation stop
  if (( LIFECYCLE_MARKER_STATUS != 0 )); then
    echo "Lifecycle operation is locked at $LIFECYCLE_LOCK_FILE; complete manual recovery before retrying stop" >&2
    exit 5
  fi
  OWN_LIFECYCLE_LOCK=true
  trap release_lifecycle_lock EXIT
else
  run_lifecycle_marker lock-verify-owner --path "$LIFECYCLE_LOCK_FILE" --nonce "$LIFECYCLE_LOCK_NONCE" --operation update
  if (( LIFECYCLE_MARKER_STATUS != 0 )); then
    echo "Lifecycle lock owner verification failed at $LIFECYCLE_LOCK_FILE" >&2
    exit 5
  fi
fi
if [[ -e "$UPDATE_TRANSACTION_FILE" ]]; then
  if [[ -z "$UPDATE_TRANSACTION_NONCE" ]]; then
    echo "An unresolved update transaction is present at $UPDATE_TRANSACTION_FILE; only its owner updater may stop the Worker" >&2
    exit 5
  fi
  run_lifecycle_marker verify-owner --path "$UPDATE_TRANSACTION_FILE" --nonce "$UPDATE_TRANSACTION_NONCE"
  if (( LIFECYCLE_MARKER_STATUS != 0 )); then
    echo "An unresolved update transaction is present at $UPDATE_TRANSACTION_FILE; only its owner updater may stop the Worker" >&2
    exit 5
  fi
elif [[ -n "$UPDATE_TRANSACTION_NONCE" ]]; then
  echo "The owner update transaction is missing at $UPDATE_TRANSACTION_FILE" >&2
  exit 5
fi
if [[ -f "$FAILED_STOP_FILE" ]]; then
  echo "Previous failed stop is latched at $FAILED_STOP_FILE; verify descendants and remove the latch explicitly" >&2
  exit 4
fi
if [[ ! -f "$PID_FILE" ]]; then
  if [[ -e "$SNAPSHOT_FILE" ]]; then
    write_failed_stop_latch worker_pid_missing_with_identity_evidence
    echo "Worker PID file is missing while identity evidence remains at $SNAPSHOT_FILE; operator review is required" >&2
    exit 4
  fi
  echo "codex-app-server-worker is not running (PID file missing)"
  exit 0
fi
pid="$(tr -d '[:space:]' < "$PID_FILE")"
if [[ ! "$pid" =~ ^[0-9]+$ ]]; then
  write_failed_stop_latch worker_pid_file_invalid
  echo 'Invalid Worker PID file' >&2
  exit 1
fi
if [[ ! -f "$SNAPSHOT_FILE" ]]; then
  write_failed_stop_latch worker_identity_snapshot_missing
  echo "Worker identity snapshot is missing; PID $pid does not belong to a safely stoppable codex-app-server-worker" >&2
  exit 4
fi
run_process_tree status --pid "$pid" --entry "$ENTRY" --output "$SNAPSHOT_FILE"
if (( PROCESS_TREE_STATUS != 0 && PROCESS_TREE_STATUS != 10 )); then
  write_failed_stop_latch worker_identity_snapshot_invalid
  echo "PID $pid does not belong to the persisted codex-app-server-worker identity" >&2
  exit 4
fi

request_id="$(node -e 'process.stdout.write(require("node:crypto").randomUUID().replaceAll("-", ""))')"
if ! remove_evidence_file "$SHUTDOWN_SUCCESS_FILE" || ! remove_evidence_file "$SHUTDOWN_FAILURE_FILE"; then
  write_failed_stop_latch shutdown_cleanup_failed || echo "Failed to persist shutdown cleanup latch at $FAILED_STOP_FILE" >&2
  echo 'Could not clear stale shutdown outcomes' >&2
  exit 3
fi
temporary_request="$STOP_FILE.$$.tmp"
printf '%s' "$request_id" > "$temporary_request"
mv -f "$temporary_request" "$STOP_FILE"
shutdown_deadline=$((SECONDS + shutdown_wait_seconds))
protocol_failure=false
while (( SECONDS < shutdown_deadline )); do
  run_process_tree poll --pid "$pid" --entry "$ENTRY" --output "$SNAPSHOT_FILE"
  if (( PROCESS_TREE_STATUS == 0 )); then break; fi
  if (( PROCESS_TREE_STATUS != 10 )); then protocol_failure=true; break; fi
  sleep 0.1
done
success_matches=false
if [[ -f "$SHUTDOWN_SUCCESS_FILE" ]] && [[ "$(tr -d '\r\n' < "$SHUTDOWN_SUCCESS_FILE")" == "$request_id" ]]; then
  success_matches=true
fi
if [[ "$protocol_failure" == true ]]; then
  PROCESS_TREE_STATUS=11
else
  run_process_tree verify --pid "$pid" --entry "$ENTRY" --output "$SNAPSHOT_FILE"
fi
if [[ "$success_matches" == true ]] && (( PROCESS_TREE_STATUS == 0 )); then
  if ! remove_evidence_file "$STOP_FILE" ||
     ! remove_evidence_file "$PID_FILE" ||
     ! remove_evidence_file "$SHUTDOWN_SUCCESS_FILE" ||
     ! remove_evidence_file "$SHUTDOWN_FAILURE_FILE" ||
     ! remove_evidence_file "$SNAPSHOT_FILE"; then
    write_failed_stop_latch shutdown_cleanup_failed || echo "Failed to persist shutdown cleanup latch at $FAILED_STOP_FILE" >&2
    echo 'Worker exited cleanly but lifecycle evidence cleanup failed' >&2
    exit 3
  fi
  echo 'codex-app-server-worker stopped'
  exit 0
fi

run_process_tree kill --pid "$pid" --entry "$ENTRY" --output "$SNAPSHOT_FILE"
kill_status=$PROCESS_TREE_STATUS
run_process_tree verify --pid "$pid" --entry "$ENTRY" --output "$SNAPSHOT_FILE"
verify_status=$PROCESS_TREE_STATUS
if [[ "$success_matches" == true ]]; then reason=shutdown_success_with_process_residue; else reason=shutdown_not_proven; fi
write_failed_stop_latch "$reason"
if ! remove_evidence_file "$STOP_FILE" ||
   ! remove_evidence_file "$PID_FILE" ||
   ! remove_evidence_file "$SHUTDOWN_SUCCESS_FILE" ||
   ! remove_evidence_file "$SHUTDOWN_FAILURE_FILE"; then
  echo 'Failed to clean non-snapshot shutdown evidence' >&2
  exit 3
fi
if (( kill_status != 0 || verify_status != 0 )); then
  echo 'Worker shutdown left verified process residue; start and update are latched' >&2
  exit 3
fi
echo "Worker shutdown was not proven graceful; verified process tree was cleaned, but evidence remains at $SNAPSHOT_FILE pending explicit latch review" >&2
exit 2
