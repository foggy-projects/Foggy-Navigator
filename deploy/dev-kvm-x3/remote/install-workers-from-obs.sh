#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
. "$SCRIPT_DIR/lib.sh"

load_release_env
require_command curl
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

install_worker() {
  local enabled="$1"
  local name="$2"
  local cli="$3"
  local url="$4"
  local env_name="$5"

  if [ "$enabled" != "true" ]; then
    log "Skipping $name worker install"
    return
  fi

  [ -n "$url" ] || die "$env_name is required when $name worker install is enabled"
  log "Installing/upgrading $name worker from $url"
  case "$name" in
    claude) CLAUDE_WORKER_URL="$url" bash -c "curl -sSL '$url/install.sh' | bash" ;;
    codex) CODEX_WORKER_URL="$url" bash -c "curl -sSL '$url/install.sh' | bash" ;;
    gemini) GEMINI_WORKER_URL="$url" bash -c "curl -sSL '$url/install.sh' | bash" ;;
    *) die "Unknown worker: $name" ;;
  esac

  cli_path="$(resolve_cli "$cli" || true)"
  if [ -n "$cli_path" ]; then
    log "Starting $name worker via $cli_path"
    "$cli_path" start
  else
    die "$cli was not found after installing $name worker"
  fi
}

install_worker "${WORKER_INSTALL_CLAUDE:-true}" claude claude-worker "${CLAUDE_WORKER_URL:-https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/claude-worker}" CLAUDE_WORKER_URL
install_worker "${WORKER_INSTALL_CODEX:-false}" codex codex-worker "${CODEX_WORKER_URL:-https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/codex-worker}" CODEX_WORKER_URL
install_worker "${WORKER_INSTALL_GEMINI:-false}" gemini gemini-worker "${GEMINI_WORKER_URL:-https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/gemini-worker}" GEMINI_WORKER_URL

log "Worker install/update from OBS complete"
