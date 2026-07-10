#!/usr/bin/env bash
set -euo pipefail

SOURCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALL_DIR="${CODEX_APP_SERVER_WORKER_HOME:-$HOME/.codex-app-server-worker}"
if [[ "${1:-}" == --install-dir ]]; then INSTALL_DIR="${2:?missing --install-dir value}"; shift 2; fi
[[ $# -eq 0 ]] || { echo 'Usage: install.sh [--install-dir <path>]' >&2; exit 2; }
bash "$SOURCE_DIR/update.sh" --package "$SOURCE_DIR" --install-dir "$INSTALL_DIR" --no-restart
if [[ ! -f "$INSTALL_DIR/.env" ]]; then
  cp "$INSTALL_DIR/.env.example" "$INSTALL_DIR/.env"
  echo "Created $INSTALL_DIR/.env; configure required secrets and isolation paths before start."
fi
echo "codex-app-server-worker installed at $INSTALL_DIR"
