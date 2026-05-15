#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
. "$SCRIPT_DIR/lib.sh"

require_local_command rsync

echo "Syncing repository to $(print_target):$REMOTE_CURRENT_DIR"

ssh_cmd "REMOTE_CURRENT_DIR=$(remote_quote "$REMOTE_CURRENT_DIR") bash -s" <<'REMOTE'
set -euo pipefail
if [ "$(id -u)" -eq 0 ]; then
  SUDO=""
else
  SUDO="sudo"
fi

LOGIN_USER="$(id -un)"
LOGIN_GROUP="$(id -gn "$LOGIN_USER")"
REMOTE_PARENT="$(dirname "$REMOTE_CURRENT_DIR")"

$SUDO mkdir -p "$REMOTE_CURRENT_DIR"
$SUDO chown -R "$LOGIN_USER:$LOGIN_GROUP" "$REMOTE_PARENT"
REMOTE

rsync_cmd -az --delete \
  --exclude-from "$DEPLOY_DIR/rsync-excludes.txt" \
  "$REPO_ROOT/" \
  "$SSH_TARGET:$REMOTE_CURRENT_DIR/"

echo "Sync complete."
