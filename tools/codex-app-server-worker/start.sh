#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"
NO_BUILD=false
UPDATE_TRANSACTION_NONCE=""
LIFECYCLE_LOCK_NONCE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-build) NO_BUILD=true; shift ;;
    --update-transaction-nonce) UPDATE_TRANSACTION_NONCE="${2:?missing transaction nonce}"; shift 2 ;;
    --update-transaction-nonce=*) UPDATE_TRANSACTION_NONCE="${1#*=}"; shift ;;
    --lifecycle-lock-nonce) LIFECYCLE_LOCK_NONCE="${2:?missing lifecycle lock nonce}"; shift 2 ;;
    --lifecycle-lock-nonce=*) LIFECYCLE_LOCK_NONCE="${1#*=}"; shift ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done
[[ "$(uname -s)" == Linux ]] || { echo 'codex-app-server-worker lifecycle operations require Linux exact process identity support' >&2; exit 1; }
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
write_failed_start_latch() {
  local reason="$1"
  run_lifecycle_marker write-once --path "$FAILED_STOP_FILE" --reason "$reason"
  (( LIFECYCLE_MARKER_STATUS == 0 ))
}
has_runtime_process_tree_evidence() {
  if [[ -L "$RUNTIME_PROCESS_TREE_DIR" || ( -e "$RUNTIME_PROCESS_TREE_DIR" && ! -d "$RUNTIME_PROCESS_TREE_DIR" ) ]]; then
    return 0
  fi
  if [[ -d "$RUNTIME_PROCESS_TREE_DIR" ]]; then
    local count
    if ! count="$(node -e 'const fs=require("node:fs"); process.stdout.write(String(fs.readdirSync(process.argv[1]).length))' "$RUNTIME_PROCESS_TREE_DIR" 2>/dev/null)"; then
      echo 'Runtime process-tree evidence inspection failed; treating evidence as present' >&2
      return 0
    fi
    if [[ ! "$count" =~ ^[0-9]+$ ]]; then
      echo 'Runtime process-tree evidence inspection returned an invalid result; treating evidence as present' >&2
      return 0
    fi
    (( count != 0 )) && return 0
  fi
  return 1
}
write_state_lifecycle_failure() {
  local reason="$1"
  run_lifecycle_marker write-once --path "$LIFECYCLE_FAILURE_FILE" --reason "$reason"
  (( LIFECYCLE_MARKER_STATUS == 0 ))
}
fail_worker_startup() {
  local message="$1" poll_status kill_status verify_status
  run_process_tree poll --pid "$pid" --entry "$ENTRY" --output "$LIFECYCLE_SNAPSHOT_FILE"
  poll_status=$PROCESS_TREE_STATUS
  run_process_tree kill --pid "$pid" --entry "$ENTRY" --output "$LIFECYCLE_SNAPSHOT_FILE"
  kill_status=$PROCESS_TREE_STATUS
  run_process_tree verify --pid "$pid" --entry "$ENTRY" --output "$LIFECYCLE_SNAPSHOT_FILE"
  verify_status=$PROCESS_TREE_STATUS
  write_failed_start_latch startup_not_ready || echo "Failed to persist startup failure latch at $FAILED_STOP_FILE" >&2
  if (( kill_status != 0 || verify_status != 0 )); then
    write_state_lifecycle_failure WORKER_START_CLEANUP_NOT_PROVEN || echo "Failed to persist state lifecycle evidence at $LIFECYCLE_FAILURE_FILE" >&2
  fi
  rm -f "$PID_FILE" "$STOP_FILE" "$SHUTDOWN_SUCCESS_FILE" "$SHUTDOWN_FAILURE_FILE"
  tail -n 20 "$LOG_DIR/worker.stderr.log" >&2 || true
  echo "$message Process-tree cleanup status: poll=$poll_status kill=$kill_status verify=$verify_status. Evidence: $LIFECYCLE_SNAPSHOT_FILE" >&2
  exit 1
}
UNVERIFIED_TREE=()
add_unverified_tree() {
  local candidate="$1" known child
  for known in "${UNVERIFIED_TREE[@]}"; do [[ "$known" != "$candidate" ]] || return 0; done
  UNVERIFIED_TREE[${#UNVERIFIED_TREE[@]}]="$candidate"
  for child in $(ps -eo pid=,ppid= | awk -v parent="$candidate" '$2 == parent { print $1 }'); do
    add_unverified_tree "$child"
  done
}
is_unverified_process_alive() {
  local candidate="$1" state
  kill -0 "$candidate" 2>/dev/null || return 1
  state="$(ps -o stat= -p "$candidate" 2>/dev/null || true)"
  [[ "$state" != Z* ]]
}
cleanup_unverified_tree() {
  local pass candidate index clean
  UNVERIFIED_TREE=()
  for ((pass=0; pass<4; pass+=1)); do
    add_unverified_tree "$pid"
    for candidate in "${UNVERIFIED_TREE[@]}"; do kill -STOP "$candidate" 2>/dev/null || true; done
    sleep 0.05
  done
  for ((index=${#UNVERIFIED_TREE[@]}-1; index>=0; index-=1)); do
    candidate="${UNVERIFIED_TREE[$index]}"
    kill -KILL "$candidate" 2>/dev/null || true
  done
  sleep 0.2
  clean=true
  for candidate in "${UNVERIFIED_TREE[@]}"; do
    if is_unverified_process_alive "$candidate"; then clean=false; fi
  done
  [[ "$clean" == true ]]
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
PID_FILE="$RUN_DIR/worker.pid"
STOP_FILE="$RUN_DIR/stop.request"
FAILED_STOP_FILE="$RUN_DIR/stop.failed"
SHUTDOWN_SUCCESS_FILE="$RUN_DIR/shutdown.success"
SHUTDOWN_FAILURE_FILE="$RUN_DIR/shutdown.failure"
LIFECYCLE_SNAPSHOT_FILE="$RUN_DIR/worker.process-tree.json"
RUNTIME_PROCESS_TREE_DIR="$STATE_DIR/runtime-process-trees"
LIFECYCLE_FAILURE_FILE="$STATE_DIR/lifecycle.failed"
PROCESS_TREE_HELPER="$ROOT/scripts/process-tree.mjs"
LIFECYCLE_MARKER_HELPER="$ROOT/scripts/lifecycle-marker.mjs"
LIFECYCLE_LOCK_FILE="$ROOT/lifecycle.lock"
UPDATE_TRANSACTION_FILE="$ROOT/update.in-progress"
ENTRY="$ROOT/dist/index.js"

export CODEX_APP_SERVER_RUN_DIR="$RUN_DIR"
export CODEX_APP_SERVER_LOG_DIR="$LOG_DIR"
export CODEX_APP_SERVER_STATE_DIR="$STATE_DIR"
export CODEX_APP_SERVER_WORKER_HOST="$display_host"
export CODEX_APP_SERVER_WORKER_PORT="$display_port"

mkdir -p "$RUN_DIR" "$LOG_DIR"
if [[ ! -f "$PROCESS_TREE_HELPER" || ! -f "$LIFECYCLE_MARKER_HELPER" ]]; then
  echo 'Required lifecycle helpers are missing' >&2
  exit 1
fi
OWN_LIFECYCLE_LOCK=false
LIFECYCLE_LOCK_CAN_RELEASE=true
PRESERVE_LIFECYCLE_LOCK=false
release_lifecycle_lock() {
  local status=$?
  trap - EXIT
  if [[ "$OWN_LIFECYCLE_LOCK" == true && "$LIFECYCLE_LOCK_CAN_RELEASE" == true && "$PRESERVE_LIFECYCLE_LOCK" != true ]]; then
    run_lifecycle_marker lock-release --path "$LIFECYCLE_LOCK_FILE" --nonce "$LIFECYCLE_LOCK_NONCE"
    if (( LIFECYCLE_MARKER_STATUS != 0 )); then
      echo "Failed to release the owned lifecycle lock at $LIFECYCLE_LOCK_FILE" >&2
      status=1
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
  exit 1
fi
if [[ -n "$LIFECYCLE_LOCK_NONCE" && ( -z "$UPDATE_TRANSACTION_NONCE" || "$UPDATE_TRANSACTION_NONCE" != "$LIFECYCLE_LOCK_NONCE" ) ]]; then
  echo 'Internal lifecycle lock ownership requires the matching update transaction nonce' >&2
  exit 1
fi
if [[ -z "$LIFECYCLE_LOCK_NONCE" ]]; then
  LIFECYCLE_LOCK_NONCE="$(node -e 'process.stdout.write(require("node:crypto").randomBytes(16).toString("hex"))')"
  run_lifecycle_marker lock-acquire --path "$LIFECYCLE_LOCK_FILE" --nonce "$LIFECYCLE_LOCK_NONCE" --operation start
  if (( LIFECYCLE_MARKER_STATUS != 0 )); then
    echo "Lifecycle operation is locked at $LIFECYCLE_LOCK_FILE; complete manual recovery before retrying start" >&2
    exit 1
  fi
  OWN_LIFECYCLE_LOCK=true
  trap release_lifecycle_lock EXIT
else
  run_lifecycle_marker lock-verify-owner --path "$LIFECYCLE_LOCK_FILE" --nonce "$LIFECYCLE_LOCK_NONCE" --operation update
  if (( LIFECYCLE_MARKER_STATUS != 0 )); then
    echo "Lifecycle lock owner verification failed at $LIFECYCLE_LOCK_FILE" >&2
    exit 1
  fi
fi
if [[ -e "$UPDATE_TRANSACTION_FILE" ]]; then
  if [[ -z "$UPDATE_TRANSACTION_NONCE" ]]; then
    echo "An unresolved update transaction is present at $UPDATE_TRANSACTION_FILE; only its owner updater may start the Worker" >&2
    exit 1
  fi
  run_lifecycle_marker verify-owner --path "$UPDATE_TRANSACTION_FILE" --nonce "$UPDATE_TRANSACTION_NONCE"
  if (( LIFECYCLE_MARKER_STATUS != 0 )); then
    echo "An unresolved update transaction is present at $UPDATE_TRANSACTION_FILE; only its owner updater may start the Worker" >&2
    exit 1
  fi
elif [[ -n "$UPDATE_TRANSACTION_NONCE" ]]; then
  echo "The owner update transaction is missing at $UPDATE_TRANSACTION_FILE" >&2
  exit 1
fi
if [[ -f "$FAILED_STOP_FILE" ]]; then
  echo "Previous failed stop is latched at $FAILED_STOP_FILE; verify no Worker descendants remain, then remove the latch explicitly" >&2
  exit 1
fi
if [[ -e "$LIFECYCLE_SNAPSHOT_FILE" && -f "$PID_FILE" ]]; then
  existing_pid="$(tr -d '[:space:]' < "$PID_FILE")"
  if [[ "$existing_pid" =~ ^[0-9]+$ ]]; then
    run_process_tree status --pid "$existing_pid" --entry "$ENTRY" --output "$LIFECYCLE_SNAPSHOT_FILE"
    if (( PROCESS_TREE_STATUS == 10 )); then
      echo "codex-app-server-worker is already running (PID $existing_pid)" >&2
      exit 1
    fi
  fi
fi
if has_runtime_process_tree_evidence; then
  write_failed_start_latch runtime_process_tree_evidence_present || echo "Failed to persist runtime process-tree latch at $FAILED_STOP_FILE" >&2
  echo "Unresolved app-server process-tree evidence exists at $RUNTIME_PROCESS_TREE_DIR; startup is blocked pending operator review" >&2
  exit 1
fi
if [[ -e "$LIFECYCLE_FAILURE_FILE" ]]; then
  write_failed_start_latch runtime_lifecycle_failure_present || echo "Failed to persist runtime lifecycle latch at $FAILED_STOP_FILE" >&2
  echo "Unresolved runtime lifecycle evidence exists at $LIFECYCLE_FAILURE_FILE; startup is blocked pending operator review" >&2
  exit 1
fi
if [[ -e "$LIFECYCLE_SNAPSHOT_FILE" ]]; then
  write_failed_start_latch existing_worker_identity_evidence || echo "Failed to persist existing identity latch at $FAILED_STOP_FILE" >&2
  echo "Existing Worker identity evidence is present at $LIFECYCLE_SNAPSHOT_FILE; review it before starting" >&2
  exit 1
fi
if [[ -f "$PID_FILE" ]]; then
  write_failed_start_latch worker_pid_without_identity_snapshot || echo "Failed to persist stale PID latch at $FAILED_STOP_FILE" >&2
  echo "Stale Worker PID evidence is present at $PID_FILE without an identity snapshot; review it before starting" >&2
  exit 1
fi

if [[ "$NO_BUILD" != true ]]; then
  npm run build --prefix "$ROOT"
fi
[[ -f "$ENTRY" ]] || { echo "Missing build output: $ENTRY" >&2; exit 1; }
rm -f "$STOP_FILE" "$SHUTDOWN_SUCCESS_FILE" "$SHUTDOWN_FAILURE_FILE"

nohup node "$ENTRY" >>"$LOG_DIR/worker.stdout.log" 2>>"$LOG_DIR/worker.stderr.log" &
pid=$!
run_process_tree snapshot --pid "$pid" --entry "$ENTRY" --output "$LIFECYCLE_SNAPSHOT_FILE"
if (( PROCESS_TREE_STATUS != 0 )); then
  if cleanup_unverified_tree; then fallback_cleanup=clean; else fallback_cleanup=residue; fi
  failure_evidence_persisted=false
  if write_failed_start_latch startup_identity_not_proven; then
    failure_evidence_persisted=true
  else
    echo "Failed to persist startup failure latch at $FAILED_STOP_FILE" >&2
  fi
  if write_state_lifecycle_failure WORKER_START_IDENTITY_NOT_PROVEN; then
    failure_evidence_persisted=true
  else
    echo "Failed to persist state lifecycle evidence at $LIFECYCLE_FAILURE_FILE" >&2
  fi
  if [[ "$failure_evidence_persisted" != true ]]; then LIFECYCLE_LOCK_CAN_RELEASE=false; fi
  echo "Worker startup identity could not be proven; recursively discovered tree cleanup=$fallback_cleanup and exact cleanup remains unproven. State evidence: $LIFECYCLE_FAILURE_FILE" >&2
  exit 1
fi
sleep 0.5
run_process_tree poll --pid "$pid" --entry "$ENTRY" --output "$LIFECYCLE_SNAPSHOT_FILE"
(( PROCESS_TREE_STATUS == 10 )) || fail_worker_startup 'Worker exited during startup'
run_process_tree poll-root --pid "$pid" --entry "$ENTRY" --output "$LIFECYCLE_SNAPSHOT_FILE"
(( PROCESS_TREE_STATUS == 10 )) || fail_worker_startup 'Worker root exited during startup'
temporary_pid_file="$PID_FILE.$$.tmp"
if ! printf '%s' "$pid" > "$temporary_pid_file" || ! mv "$temporary_pid_file" "$PID_FILE"; then
  rm -f "$temporary_pid_file"
  fail_worker_startup 'Worker PID identity could not be persisted.'
fi
health_host="$display_host"
if [[ "$health_host" == "0.0.0.0" || "$health_host" == "::" || "$health_host" == "[::]" ]]; then
  health_host="127.0.0.1"
fi
health_url="http://$health_host:$display_port/health"
ready=false
attempt=0
while (( attempt < 120 )); do
  run_process_tree poll --pid "$pid" --entry "$ENTRY" --output "$LIFECYCLE_SNAPSHOT_FILE"
  (( PROCESS_TREE_STATUS == 10 )) || fail_worker_startup 'Worker exited before readiness'
  run_process_tree poll-root --pid "$pid" --entry "$ENTRY" --output "$LIFECYCLE_SNAPSHOT_FILE"
  (( PROCESS_TREE_STATUS == 10 )) || fail_worker_startup 'Worker root exited before readiness'
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
  fail_worker_startup "Worker did not become ready: $health_url"
fi
run_process_tree poll --pid "$pid" --entry "$ENTRY" --output "$LIFECYCLE_SNAPSHOT_FILE"
(( PROCESS_TREE_STATUS == 10 )) || fail_worker_startup 'Worker identity could not be persisted after readiness.'
run_process_tree poll-root --pid "$pid" --entry "$ENTRY" --output "$LIFECYCLE_SNAPSHOT_FILE"
(( PROCESS_TREE_STATUS == 10 )) || fail_worker_startup 'Worker root identity exited after readiness.'
echo "codex-app-server-worker started (PID $pid, URL http://$display_host:$display_port)"
