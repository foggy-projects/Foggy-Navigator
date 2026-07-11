#!/usr/bin/env bash
set -euo pipefail

SOURCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALL_DIR="${CODEX_APP_SERVER_WORKER_HOME:-$HOME/.codex-app-server-worker}"
if [[ "${1:-}" == --install-dir ]]; then INSTALL_DIR="${2:?missing --install-dir value}"; shift 2; fi
[[ $# -eq 0 ]] || { echo 'Usage: install.sh [--install-dir <path>]' >&2; exit 2; }
bash "$SOURCE_DIR/update.sh" --package "$SOURCE_DIR" --install-dir "$INSTALL_DIR" --no-restart
if [[ ! -f "$INSTALL_DIR/.env" ]]; then
  cp "$INSTALL_DIR/.env.example" "$INSTALL_DIR/.env"
  if ! node "$INSTALL_DIR/scripts/configure-install-env.mjs" "$INSTALL_DIR/.env" '/'; then
    rm -f -- "$INSTALL_DIR/.env"
    exit 1
  fi
  echo "Created $INSTALL_DIR/.env with workspace root /."
  echo 'WARNING: The cwd allowlist is an admission check, not a filesystem sandbox. Use this default only for trusted tasks on a dedicated host.' >&2
  echo "Generated a persistent state key and isolated CODEX_HOME at $INSTALL_DIR/codex-home"
  echo 'Worker token and OPENAI_API_KEY remain empty; Navigator ModelConfig may supply model credentials.'
fi
echo "codex-app-server-worker installed at $INSTALL_DIR"
