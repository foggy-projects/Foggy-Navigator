#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PACKAGE=""
INSTALL_DIR="$SCRIPT_ROOT"
NO_RESTART=false
DRY_RUN=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --package) PACKAGE="${2:?missing --package value}"; shift 2 ;;
    --package=*) PACKAGE="${1#*=}"; shift ;;
    --install-dir) INSTALL_DIR="${2:?missing --install-dir value}"; shift 2 ;;
    --install-dir=*) INSTALL_DIR="${1#*=}"; shift ;;
    --no-restart) NO_RESTART=true; shift ;;
    --dry-run) DRY_RUN=true; shift ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done
[[ -n "$PACKAGE" ]] || { echo 'Usage: update.sh --package <release.zip|directory> [--install-dir <path>] [--no-restart] [--dry-run]' >&2; exit 2; }
[[ "$(uname -s)" == Linux ]] || { echo 'codex-app-server-worker lifecycle operations require Linux exact process identity support' >&2; exit 1; }

INSTALL_DIR="$(node -e 'console.log(require("path").resolve(process.argv[1]))' "$INSTALL_DIR")"
INSTALL_PARENT="$(dirname "$INSTALL_DIR")"
LIFECYCLE_MARKER_SOURCE="$SCRIPT_ROOT/scripts/lifecycle-marker.mjs"
[[ -f "$LIFECYCLE_MARKER_SOURCE" ]] || { echo "Required lifecycle marker helper is missing: $LIFECYCLE_MARKER_SOURCE" >&2; exit 1; }
UPDATE_TRANSACTION_FILE="$INSTALL_DIR/update.in-progress"
node - "$INSTALL_DIR" <<'NODE'
const fs = require('node:fs')
const path = require('node:path')
const target = path.resolve(process.argv[2])
if (target === path.parse(target).root) throw new Error('Install directory must not be a filesystem root')
if (!fs.existsSync(target)) process.exit(0)
const stat = fs.lstatSync(target)
if (!stat.isDirectory() || stat.isSymbolicLink()) {
  throw new Error('Install directory must be a real directory, not a file or symbolic link')
}
if (fs.readdirSync(target).length === 0) process.exit(0)
const packageFile = path.join(target, 'package.json')
const versionFile = path.join(target, 'VERSION')
for (const file of [packageFile, versionFile]) {
  if (!fs.existsSync(file)) throw new Error('Refusing non-empty install directory without codex-app-server-worker identity')
  const identityStat = fs.lstatSync(file)
  if (!identityStat.isFile() || identityStat.isSymbolicLink()) throw new Error('Product identity file is unsafe')
}
let identity
try { identity = JSON.parse(fs.readFileSync(packageFile, 'utf8')) } catch {
  throw new Error('Refusing non-empty install directory with invalid product identity')
}
if (identity.name !== 'codex-app-server-worker') {
  throw new Error('Refusing non-empty install directory owned by another product')
}
if (fs.readFileSync(versionFile, 'utf8').trim() === '0.1.0') {
  throw new Error('In-place update of codex-app-server-worker 0.1.0 is not supported; install into a new empty directory or use an external OS-level zero-residue migration')
}
NODE
read_env_value() {
  local key="$1"
  [[ -f "$INSTALL_DIR/.env" ]] || return 0
  node "$CANDIDATE/scripts/read-dotenv-value.mjs" "$INSTALL_DIR/.env" "$key"
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
  if node "$CONTROL_MARKER_HELPER" "$@" >/dev/null 2>&1; then
    LIFECYCLE_MARKER_STATUS=0
  else
    LIFECYCLE_MARKER_STATUS=$?
  fi
}
set_transaction_phase() {
  local phase="$1" backed_up="${2:-}" installed="${3:-}"
  local arguments=(update --path "$UPDATE_TRANSACTION_FILE" --nonce "$TRANSACTION_NONCE" --phase "$phase")
  [[ -z "$backed_up" ]] || arguments+=(--append-backed-up "$backed_up")
  [[ -z "$installed" ]] || arguments+=(--append-installed "$installed")
  run_lifecycle_marker "${arguments[@]}"
  (( LIFECYCLE_MARKER_STATUS == 0 )) || { echo "Failed to persist update transaction phase '$phase'" >&2; return 1; }
}
write_failed_stop_latch() {
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
remove_evidence_file() {
  local evidence="$1"
  if [[ -e "$evidence" || -L "$evidence" ]]; then rm -f -- "$evidence" || return 1; fi
  [[ ! -e "$evidence" && ! -L "$evidence" ]]
}
mkdir -p "$INSTALL_PARENT"
STAGE_ROOT="$INSTALL_PARENT/.caw-$(node -e 'console.log(require("crypto").randomUUID().replaceAll("-", "").slice(0, 12))')"
EXTRACT_ROOT="$STAGE_ROOT/c"
BACKUP_ROOT="$STAGE_ROOT/backup"
MANAGED=(dist src tests contracts scripts node_modules .env.example README.md VERSION package.json package-lock.json tsconfig.json start.ps1 start.sh stop.ps1 stop.sh update.ps1 update.sh install.ps1 install.sh)
SWAP_STARTED=false
WAS_RUNNING=false
CANDIDATE_START_ATTEMPTED=false
PRESERVE_STAGE_ROOT=false
PRESERVE_LIFECYCLE_LOCK=false
TRANSACTION_OWNED=false
TRANSACTION_CAN_CLOSE=false
TRANSACTION_NONCE="$(node -e 'console.log(require("crypto").randomUUID().replaceAll("-", ""))')"
LIFECYCLE_LOCK_FILE="$INSTALL_DIR/lifecycle.lock"
LIFECYCLE_LOCK_OWNED=false
CONTROL_MARKER_HELPER="$INSTALL_PARENT/.caw-marker-$TRANSACTION_NONCE.mjs"
BACKED_UP_PATHS=()
INSTALLED_CANDIDATE_PATHS=()

cleanup() {
  local status=$?
  trap - EXIT
  set +e
  if (( status != 0 )) && [[ "$TRANSACTION_OWNED" == true && "$TRANSACTION_CAN_CLOSE" != true ]]; then
    if [[ "$SWAP_STARTED" == true ]]; then
      PRESERVE_STAGE_ROOT=true
    else
      set_transaction_phase failed_pre_swap || true
      TRANSACTION_CAN_CLOSE=true
    fi
  fi
  if [[ "$PRESERVE_STAGE_ROOT" == true ]]; then
    echo "Update staging was preserved for operator recovery at $STAGE_ROOT" >&2
  else
    if [[ -e "$STAGE_ROOT" ]] && ! rm -rf "$STAGE_ROOT"; then
      PRESERVE_STAGE_ROOT=true
      status=1
      echo "Failed to clean update staging; transaction marker and remaining evidence are preserved at $STAGE_ROOT" >&2
    fi
    if [[ "$PRESERVE_STAGE_ROOT" != true && "$TRANSACTION_OWNED" == true && "$TRANSACTION_CAN_CLOSE" == true ]]; then
      run_lifecycle_marker remove --path "$UPDATE_TRANSACTION_FILE" --nonce "$TRANSACTION_NONCE"
      if (( LIFECYCLE_MARKER_STATUS != 0 )); then
        PRESERVE_STAGE_ROOT=true
        status=1
        echo "Failed to remove the owned update transaction marker at $UPDATE_TRANSACTION_FILE" >&2
      else
        TRANSACTION_OWNED=false
      fi
    fi
  fi
  if [[ "$LIFECYCLE_LOCK_OWNED" == true && "$PRESERVE_LIFECYCLE_LOCK" != true ]]; then
    local lock_helper="$CONTROL_MARKER_HELPER"
    [[ -f "$lock_helper" ]] || lock_helper="$LIFECYCLE_MARKER_SOURCE"
    if node "$lock_helper" lock-release --path "$LIFECYCLE_LOCK_FILE" --nonce "$TRANSACTION_NONCE" >/dev/null 2>&1; then
      LIFECYCLE_LOCK_OWNED=false
    else
      status=1
      echo "Failed to release the owned lifecycle lock at $LIFECYCLE_LOCK_FILE" >&2
    fi
  fi
  if [[ "$TRANSACTION_OWNED" != true && "$LIFECYCLE_LOCK_OWNED" != true && -f "$CONTROL_MARKER_HELPER" ]]; then
    if ! rm -f "$CONTROL_MARKER_HELPER"; then
      status=1
      echo "Failed to remove transaction control helper at $CONTROL_MARKER_HELPER" >&2
    fi
  fi
  exit "$status"
}
TREE_PIDS=()
collect_tree() {
  local parent="$1"
  local child
  for child in $(ps -eo pid=,ppid= | awk -v parent="$parent" '$2 == parent { print $1 }'); do collect_tree "$child"; done
  TREE_PIDS+=("$parent")
}
run_with_timeout() {
  local timeout_sec="${CODEX_APP_SERVER_UPDATE_STEP_TIMEOUT_SEC:-300}"
  [[ "$timeout_sec" =~ ^[1-9][0-9]*$ ]] || { echo 'CODEX_APP_SERVER_UPDATE_STEP_TIMEOUT_SEC must be a positive integer' >&2; return 1; }
  "$@" &
  local command_pid=$!
  local waited=0
  while kill -0 "$command_pid" 2>/dev/null; do
    if (( waited >= timeout_sec )); then
      TREE_PIDS=()
      collect_tree "$command_pid"
      local tree_pid
      for tree_pid in "${TREE_PIDS[@]}"; do kill -TERM "$tree_pid" 2>/dev/null || true; done
      sleep 1
      for tree_pid in "${TREE_PIDS[@]}"; do kill -KILL "$tree_pid" 2>/dev/null || true; done
      wait "$command_pid" 2>/dev/null || true
      echo "Command timed out after $timeout_sec seconds: $*" >&2
      return 124
    fi
    sleep 1
    waited=$((waited + 1))
  done
  wait "$command_pid"
}
rollback() {
  trap - ERR
  if [[ "$CANDIDATE_START_ATTEMPTED" == true ]]; then
    PRESERVE_STAGE_ROOT=true
    echo "Candidate startup was attempted; automatic rollback is suppressed so candidate files and backup evidence remain at $STAGE_ROOT" >&2
    return 1
  fi
  set_transaction_phase rollback || { PRESERVE_STAGE_ROOT=true; return 1; }
  local failed=false name
  for name in "${INSTALLED_CANDIDATE_PATHS[@]}"; do
    if [[ -e "$INSTALL_DIR/$name" ]] && ! rm -rf "$INSTALL_DIR/$name"; then
      echo "Rollback could not remove candidate path: $name" >&2
      failed=true
    fi
  done
  for name in "${BACKED_UP_PATHS[@]}"; do
    if [[ ! -e "$BACKUP_ROOT/$name" ]]; then
      echo "Rollback backup is missing: $name" >&2
      failed=true
    elif [[ -e "$INSTALL_DIR/$name" ]]; then
      echo "Rollback restore target is occupied: $name" >&2
      failed=true
    elif ! mv "$BACKUP_ROOT/$name" "$INSTALL_DIR/$name"; then
      echo "Rollback could not restore: $name" >&2
      failed=true
    fi
  done
  if [[ "$failed" == true ]]; then
    PRESERVE_STAGE_ROOT=true
    set_transaction_phase rollback_failed || true
    echo "Rollback failed; installation and backup were preserved for operator recovery at $STAGE_ROOT" >&2
    return 1
  fi
  if [[ "$WAS_RUNNING" == true && ! -f "$FAILED_STOP_FILE" ]]; then
    if [[ ! -f "$INSTALL_DIR/start.sh" ]]; then
      PRESERVE_STAGE_ROOT=true
      echo "Previous package was restored without start.sh; recovery evidence remains at $STAGE_ROOT" >&2
      return 1
    fi
    if ! bash "$INSTALL_DIR/start.sh" --no-build \
      --update-transaction-nonce "$TRANSACTION_NONCE" --lifecycle-lock-nonce "$TRANSACTION_NONCE"; then
      PRESERVE_STAGE_ROOT=true
      echo "Previous package was restored but failed to restart; recovery evidence remains at $STAGE_ROOT" >&2
      return 1
    fi
  fi
}
on_error() {
  local status=$?
  trap - ERR
  if [[ "$SWAP_STARTED" == true ]]; then
    if rollback; then TRANSACTION_CAN_CLOSE=true; else status=1; fi
  else
    if [[ "$TRANSACTION_OWNED" == true ]]; then set_transaction_phase failed_pre_swap || true; fi
    TRANSACTION_CAN_CLOSE=true
  fi
  exit "$status"
}
preserve_lifecycle_lock_on_signal() {
  local status="$1"
  PRESERVE_LIFECYCLE_LOCK=true
  trap - HUP INT TERM
  exit "$status"
}
trap cleanup EXIT
trap on_error ERR
trap 'preserve_lifecycle_lock_on_signal 129' HUP
trap 'preserve_lifecycle_lock_on_signal 130' INT
trap 'preserve_lifecycle_lock_on_signal 143' TERM

mkdir -p "$INSTALL_DIR"
if node "$LIFECYCLE_MARKER_SOURCE" lock-acquire \
  --path "$LIFECYCLE_LOCK_FILE" --nonce "$TRANSACTION_NONCE" --operation update >/dev/null 2>&1; then
  LIFECYCLE_LOCK_OWNED=true
else
  echo "Lifecycle operation is locked at $LIFECYCLE_LOCK_FILE; complete manual recovery before retrying update" >&2
  false
fi
cp "$LIFECYCLE_MARKER_SOURCE" "$CONTROL_MARKER_HELPER"
[[ ! -e "$UPDATE_TRANSACTION_FILE" ]] || {
  echo "An unresolved update transaction is present at $UPDATE_TRANSACTION_FILE; operator recovery is required" >&2
  false
}
run_lifecycle_marker create --path "$UPDATE_TRANSACTION_FILE" --nonce "$TRANSACTION_NONCE" --stage-root "$STAGE_ROOT"
(( LIFECYCLE_MARKER_STATUS == 0 )) || { echo 'Could not acquire the exclusive update transaction marker' >&2; false; }
TRANSACTION_OWNED=true
mkdir -p "$EXTRACT_ROOT" "$BACKUP_ROOT"
PACKAGE_PATH="$(node -e 'console.log(require("path").resolve(process.argv[1]))' "$PACKAGE")"
if [[ -d "$PACKAGE_PATH" ]]; then
  (cd "$PACKAGE_PATH" && tar cf - .) | (cd "$EXTRACT_ROOT" && tar xf -)
elif [[ -f "$PACKAGE_PATH" && "$PACKAGE_PATH" == *.zip ]]; then
  command -v unzip >/dev/null 2>&1 || { echo 'unzip is required for release archives' >&2; exit 1; }
  unzip -q "$PACKAGE_PATH" -d "$EXTRACT_ROOT"
else
  echo "Release package not found or unsupported: $PACKAGE_PATH" >&2
  exit 1
fi

if [[ -f "$EXTRACT_ROOT/package.json" ]]; then
  CANDIDATE="$EXTRACT_ROOT"
else
  CANDIDATES=()
  for child in "$EXTRACT_ROOT"/*; do
    [[ -d "$child" && -f "$child/package.json" ]] || continue
    CANDIDATES[${#CANDIDATES[@]}]="$child"
  done
  [[ ${#CANDIDATES[@]} -eq 1 ]] || { echo 'Release must contain exactly one codex-app-server-worker root' >&2; exit 1; }
  CANDIDATE="${CANDIDATES[0]}"
fi
[[ "$(node -p 'require(process.argv[1]).name' "$CANDIDATE/package.json")" == codex-app-server-worker ]] || { echo 'Unexpected package identity' >&2; exit 1; }
for required in \
  dist src tests contracts scripts package-lock.json tsconfig.json VERSION \
  start.ps1 start.sh stop.ps1 stop.sh update.ps1 update.sh install.ps1 install.sh \
  scripts/configure-install-env.mjs scripts/lifecycle-marker.mjs \
  scripts/process-tree.mjs scripts/read-dotenv-value.mjs; do
  [[ -e "$CANDIDATE/$required" ]] || { echo "Release is missing $required" >&2; exit 1; }
done
for forbidden in .env logs node_modules CODEX_HOME auth.json; do
  [[ ! -e "$CANDIDATE/$forbidden" ]] || { echo "Release contains forbidden runtime path: $forbidden" >&2; exit 1; }
done

VERSION="$(node -p 'require(process.argv[1]).version' "$CANDIDATE/package.json")"
[[ "$(tr -d '\r\n' < "$CANDIDATE/VERSION")" == "$VERSION" ]] || { echo 'Release VERSION must exactly match package.json version' >&2; exit 1; }
echo "Validating codex-app-server-worker $VERSION before drain..."
previous_directory="$PWD"
cd "$CANDIDATE"
run_with_timeout npm ci
run_with_timeout npm test
run_with_timeout npm run verify:schema
run_with_timeout npm run typecheck
run_with_timeout npm run build
cd "$previous_directory"
set_transaction_phase validated
dotenv_run_dir="$(read_env_value CODEX_APP_SERVER_RUN_DIR)"
dotenv_log_dir="$(read_env_value CODEX_APP_SERVER_LOG_DIR)"
dotenv_state_dir="$(read_env_value CODEX_APP_SERVER_STATE_DIR)"
dotenv_host="$(read_env_value CODEX_APP_SERVER_WORKER_HOST)"
dotenv_port="$(read_env_value CODEX_APP_SERVER_WORKER_PORT)"
RUN_DIR="$(select_config_value "${CODEX_APP_SERVER_RUN_DIR:-}" "$dotenv_run_dir" "$INSTALL_DIR/logs/run")"
LOG_DIR="$(select_config_value "${CODEX_APP_SERVER_LOG_DIR:-}" "$dotenv_log_dir" "$INSTALL_DIR/logs")"
STATE_DIR="$(select_config_value "${CODEX_APP_SERVER_STATE_DIR:-}" "$dotenv_state_dir" "$INSTALL_DIR/logs/state")"
display_host="$(select_config_value "${CODEX_APP_SERVER_WORKER_HOST:-}" "$dotenv_host" "127.0.0.1")"
display_port="$(select_config_value "${CODEX_APP_SERVER_WORKER_PORT:-}" "$dotenv_port" "3062")"

export CODEX_APP_SERVER_RUN_DIR="$RUN_DIR"
export CODEX_APP_SERVER_LOG_DIR="$LOG_DIR"
export CODEX_APP_SERVER_STATE_DIR="$STATE_DIR"
export CODEX_APP_SERVER_WORKER_HOST="$display_host"
export CODEX_APP_SERVER_WORKER_PORT="$display_port"

LIFECYCLE_FAILURE_FILE="$STATE_DIR/lifecycle.failed"
RUNTIME_PROCESS_TREE_DIR="$STATE_DIR/runtime-process-trees"
FAILED_STOP_FILE="$RUN_DIR/stop.failed"
if [[ -f "$FAILED_STOP_FILE" ]]; then
  echo "Previous failed stop is latched at $FAILED_STOP_FILE; refusing package replacement" >&2
  exit 1
fi

PID_FILE="$RUN_DIR/worker.pid"
LIFECYCLE_SNAPSHOT="$RUN_DIR/worker.process-tree.json"
UPDATE_SNAPSHOT="$RUN_DIR/update.process-tree.json"
PROCESS_TREE_HELPER="$CANDIDATE/scripts/process-tree.mjs"
INSTALLED_ENTRY="$INSTALL_DIR/dist/index.js"
if [[ -e "$UPDATE_SNAPSHOT" ]]; then
  write_failed_stop_latch previous_update_identity_evidence_present
  echo "Previous update identity evidence remains at $UPDATE_SNAPSHOT; refusing to overwrite or bypass it" >&2
  exit 1
fi
if [[ -f "$PID_FILE" ]]; then
  pid="$(tr -d '[:space:]' < "$PID_FILE")"
  if [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null; then WAS_RUNNING=true; fi
fi
if [[ "$WAS_RUNNING" != true && ( -e "$PID_FILE" || -e "$LIFECYCLE_SNAPSHOT" ) ]]; then
  write_failed_stop_latch update_found_unresolved_worker_identity
  echo 'Worker identity evidence exists without a verifiably running Worker; refusing package replacement' >&2
  exit 1
fi
if [[ "$WAS_RUNNING" == true ]]; then
  if [[ -f "$LIFECYCLE_SNAPSHOT" ]]; then
    run_process_tree status --pid "$pid" --entry "$INSTALLED_ENTRY" --output "$LIFECYCLE_SNAPSHOT"
  else
    write_failed_stop_latch update_worker_identity_snapshot_missing
    echo 'Running Worker has no persisted lifecycle identity snapshot; in-place capture is forbidden' >&2
    exit 1
  fi
  identity_proven=$(( PROCESS_TREE_STATUS == 10 ? 1 : 0 ))
  if (( identity_proven != 1 )); then
    write_failed_stop_latch update_worker_identity_not_proven
    echo 'Running Worker identity could not be proven from the persisted snapshot; refusing package replacement' >&2
    exit 1
  fi
fi

if [[ -e "$LIFECYCLE_FAILURE_FILE" || -L "$LIFECYCLE_FAILURE_FILE" ]]; then
  echo "Unresolved runtime lifecycle evidence exists at $LIFECYCLE_FAILURE_FILE; refusing package replacement" >&2
  exit 1
fi
if has_runtime_process_tree_evidence && [[ "$WAS_RUNNING" != true ]]; then
  echo "Unresolved runtime process-tree evidence exists at $RUNTIME_PROCESS_TREE_DIR without a running Worker; refusing package replacement" >&2
  exit 1
fi

if [[ "$DRY_RUN" == true ]]; then
  set_transaction_phase committed
  TRANSACTION_CAN_CLOSE=true
  echo 'Dry run complete; current installation was not modified.'
  exit 0
fi

if [[ "$WAS_RUNNING" == true ]]; then
  set_transaction_phase draining
  [[ -f "$INSTALL_DIR/stop.sh" ]] || { echo 'Running installation has no stop.sh; refusing unsafe replacement' >&2; exit 1; }
  cp "$LIFECYCLE_SNAPSHOT" "$UPDATE_SNAPSHOT"
  run_process_tree status --pid "$pid" --entry "$INSTALLED_ENTRY" --output "$UPDATE_SNAPSHOT"
  if (( PROCESS_TREE_STATUS != 10 )); then
    write_failed_stop_latch update_worker_identity_not_proven
    echo 'Copied Worker identity evidence could not be revalidated before drain' >&2
    exit 1
  fi
  if bash "$INSTALL_DIR/stop.sh" \
    --update-transaction-nonce "$TRANSACTION_NONCE" --lifecycle-lock-nonce "$TRANSACTION_NONCE"; then stop_status=0; else stop_status=$?; fi
  run_process_tree verify --pid "$pid" --entry "$INSTALLED_ENTRY" --output "$UPDATE_SNAPSHOT"
  verify_status=$PROCESS_TREE_STATUS
  if (( stop_status != 0 || verify_status != 0 )); then
    if (( verify_status == 0 )); then reason=update_drain_not_proven; else reason=update_drain_pending_operator_decision; fi
    write_failed_stop_latch "$reason"
    echo 'Worker drain was not proven; no Worker process was terminated and the current installation was not replaced. Use an explicit signed termination operation or operator recovery.' >&2
    exit 1
  fi
  if [[ -e "$LIFECYCLE_FAILURE_FILE" || -L "$LIFECYCLE_FAILURE_FILE" ]]; then
    write_failed_stop_latch update_runtime_lifecycle_failure
    echo "Worker drain produced runtime lifecycle failure evidence at $LIFECYCLE_FAILURE_FILE; current installation was not replaced" >&2
    exit 1
  fi
  if has_runtime_process_tree_evidence; then
    write_failed_stop_latch update_runtime_process_tree_residue
    echo "Worker drain left runtime process-tree evidence at $RUNTIME_PROCESS_TREE_DIR; current installation was not replaced" >&2
    exit 1
  fi
  if ! remove_evidence_file "$UPDATE_SNAPSHOT"; then
    write_failed_stop_latch update_identity_evidence_cleanup_failed
    echo "Worker drain completed but update identity evidence cleanup failed at $UPDATE_SNAPSHOT" >&2
    exit 1
  fi
  set_transaction_phase drained
fi

SWAP_STARTED=true
set_transaction_phase backing_up
for name in "${MANAGED[@]}"; do
  if [[ -e "$INSTALL_DIR/$name" ]]; then
    mv "$INSTALL_DIR/$name" "$BACKUP_ROOT/$name"
    BACKED_UP_PATHS[${#BACKED_UP_PATHS[@]}]="$name"
    set_transaction_phase backing_up "$name"
  fi
done
set_transaction_phase installing
for name in "${MANAGED[@]}"; do
  if [[ -e "$CANDIDATE/$name" ]]; then
    mv "$CANDIDATE/$name" "$INSTALL_DIR/$name"
    INSTALLED_CANDIDATE_PATHS[${#INSTALLED_CANDIDATE_PATHS[@]}]="$name"
    set_transaction_phase installing "" "$name"
  fi
done
if [[ "$WAS_RUNNING" == true && "$NO_RESTART" != true ]]; then
  CANDIDATE_START_ATTEMPTED=true
  set_transaction_phase candidate_start
  if ! bash "$INSTALL_DIR/start.sh" --no-build \
    --update-transaction-nonce "$TRANSACTION_NONCE" --lifecycle-lock-nonce "$TRANSACTION_NONCE"; then
    write_failed_stop_latch rollback_after_candidate_start_failure || echo "Failed to persist candidate startup failure latch at $FAILED_STOP_FILE" >&2
    set_transaction_phase candidate_failed || true
    false
  fi
fi
set_transaction_phase committed
SWAP_STARTED=false
TRANSACTION_CAN_CLOSE=true
echo "codex-app-server-worker updated to $VERSION; runtime configuration and state were preserved."
