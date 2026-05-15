#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
. "$SCRIPT_DIR/lib.sh"

load_release_env
export PATH="$HOME/.local/bin:$PATH"

resolve_cli() {
  local cli="$1"
  if command -v "$cli" >/dev/null 2>&1; then
    command -v "$cli"
    return
  fi
  if [ -x "$HOME/.local/bin/$cli" ]; then
    printf '%s/.local/bin/%s\n' "$HOME" "$cli"
    return
  fi
  return 1
}

upgrade_worker() {
  local enabled="$1"
  local cli="$2"
  local url_var="$3"
  local url="${!url_var:-}"

  if [ "$enabled" != "true" ]; then
    log "Skipping $cli upgrade"
    return
  fi

  local cli_path
  cli_path="$(resolve_cli "$cli" || true)"
  if [ -z "$cli_path" ]; then
    log "$cli is not installed; running installer instead"
    bash "$SCRIPT_DIR/install-workers-from-obs.sh"
    return
  fi

  if [ -n "$url" ]; then
    export "$url_var=$url"
  fi
  log "Upgrading $cli from OBS"
  "$cli_path" upgrade
  "$cli_path" start
}

upgrade_worker "${WORKER_INSTALL_CLAUDE:-true}" claude-worker CLAUDE_WORKER_URL
upgrade_worker "${WORKER_INSTALL_CODEX:-false}" codex-worker CODEX_WORKER_URL
upgrade_worker "${WORKER_INSTALL_GEMINI:-false}" gemini-worker GEMINI_WORKER_URL

log "Worker upgrade complete"
