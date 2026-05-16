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

failed=0

check_worker() {
  local enabled="$1"
  local cli="$2"
  local url="$3"

  if [ "$enabled" != "true" ]; then
    echo "  $cli: skipped"
    return
  fi

  local cli_path
  cli_path="$(resolve_cli "$cli" || true)"
  if [ -n "$cli_path" ]; then
    "$cli_path" status || failed=1
  else
    echo "  $cli: command not found" >&2
    failed=1
  fi

  if [ -n "$url" ]; then
    if curl -fsS "$url" >/dev/null; then
      echo "  $url: OK"
    else
      echo "  $url: FAILED" >&2
      failed=1
    fi
  fi
}

check_worker "${WORKER_INSTALL_CLAUDE:-true}" claude-worker "${CLAUDE_WORKER_HEALTH_URL:-http://127.0.0.1:3031/health}"
check_worker "${WORKER_INSTALL_CODEX:-false}" codex-worker "${CODEX_WORKER_HEALTH_URL:-http://127.0.0.1:3051/health}"
check_worker "${WORKER_INSTALL_GEMINI:-false}" gemini-worker "${GEMINI_WORKER_HEALTH_URL:-http://127.0.0.1:3071/health}"

exit "$failed"
