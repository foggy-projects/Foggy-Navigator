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

INSTALL_DIR="$(node -e 'console.log(require("path").resolve(process.argv[1]))' "$INSTALL_DIR")"
INSTALL_PARENT="$(dirname "$INSTALL_DIR")"
mkdir -p "$INSTALL_PARENT"
STAGE_ROOT="$INSTALL_PARENT/.caw-$(node -e 'console.log(require("crypto").randomUUID().replaceAll("-", "").slice(0, 12))')"
EXTRACT_ROOT="$STAGE_ROOT/c"
BACKUP_ROOT="$STAGE_ROOT/backup"
MANAGED=(dist src tests contracts scripts node_modules .env.example README.md VERSION package.json package-lock.json tsconfig.json start.ps1 start.sh stop.ps1 stop.sh update.ps1 update.sh install.ps1 install.sh)
SWAP_STARTED=false
WAS_RUNNING=false

cleanup() { rm -rf "$STAGE_ROOT"; }
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
  set +e
  [[ -f "$INSTALL_DIR/stop.sh" ]] && bash "$INSTALL_DIR/stop.sh" >/dev/null 2>&1
  for name in "${MANAGED[@]}"; do rm -rf "$INSTALL_DIR/$name"; done
  for name in "${MANAGED[@]}"; do
    [[ -e "$BACKUP_ROOT/$name" ]] && mv "$BACKUP_ROOT/$name" "$INSTALL_DIR/$name"
  done
  if [[ "$WAS_RUNNING" == true && -f "$INSTALL_DIR/start.sh" ]]; then bash "$INSTALL_DIR/start.sh" --no-build; fi
}
on_error() {
  local status=$?
  if [[ "$SWAP_STARTED" == true ]]; then rollback; fi
  exit "$status"
}
trap cleanup EXIT
trap on_error ERR

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
  mapfile -t CANDIDATES < <(find "$EXTRACT_ROOT" -mindepth 2 -maxdepth 2 -name package.json -type f -print)
  [[ ${#CANDIDATES[@]} -eq 1 ]] || { echo 'Release must contain exactly one codex-app-server-worker root' >&2; exit 1; }
  CANDIDATE="$(dirname "${CANDIDATES[0]}")"
fi
[[ "$(node -p 'require(process.argv[1]).name' "$CANDIDATE/package.json")" == codex-app-server-worker ]] || { echo 'Unexpected package identity' >&2; exit 1; }
for required in dist src tests contracts scripts package-lock.json tsconfig.json VERSION; do
  [[ -e "$CANDIDATE/$required" ]] || { echo "Release is missing $required" >&2; exit 1; }
done
for forbidden in .env logs node_modules CODEX_HOME auth.json; do
  [[ ! -e "$CANDIDATE/$forbidden" ]] || { echo "Release contains forbidden runtime path: $forbidden" >&2; exit 1; }
done

VERSION="$(node -p 'require(process.argv[1]).version' "$CANDIDATE/package.json")"
echo "Validating codex-app-server-worker $VERSION before drain..."
previous_directory="$PWD"
cd "$CANDIDATE"
run_with_timeout npm ci
run_with_timeout npm test
run_with_timeout npm run verify:schema
run_with_timeout npm run typecheck
run_with_timeout npm run build
cd "$previous_directory"
if [[ "$DRY_RUN" == true ]]; then
  echo 'Dry run complete; current installation was not modified.'
  exit 0
fi

RUN_DIR="${CODEX_APP_SERVER_RUN_DIR:-$INSTALL_DIR/logs/run}"
PID_FILE="$RUN_DIR/worker.pid"
if [[ -f "$PID_FILE" ]]; then
  pid="$(tr -d '[:space:]' < "$PID_FILE")"
  if [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null; then WAS_RUNNING=true; fi
fi
if [[ "$WAS_RUNNING" == true ]]; then
  [[ -f "$INSTALL_DIR/stop.sh" ]] || { echo 'Running installation has no stop.sh; refusing unsafe replacement' >&2; exit 1; }
  bash "$INSTALL_DIR/stop.sh"
fi

mkdir -p "$INSTALL_DIR"
SWAP_STARTED=true
for name in "${MANAGED[@]}"; do [[ -e "$INSTALL_DIR/$name" ]] && mv "$INSTALL_DIR/$name" "$BACKUP_ROOT/$name"; done
for name in "${MANAGED[@]}"; do [[ -e "$CANDIDATE/$name" ]] && mv "$CANDIDATE/$name" "$INSTALL_DIR/$name"; done
if [[ "$WAS_RUNNING" == true && "$NO_RESTART" != true ]]; then bash "$INSTALL_DIR/start.sh" --no-build; fi
SWAP_STARTED=false
echo "codex-app-server-worker updated to $VERSION; runtime configuration and state were preserved."
