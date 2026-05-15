#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
. "$SCRIPT_DIR/lib.sh"

REMOTE_RELEASE_KIT_DIR="${REMOTE_RELEASE_KIT_DIR:-$REMOTE_APP_DIR/release-kit}"

require_local_command rsync

echo "Syncing Navigator release kit to $(print_target):$REMOTE_RELEASE_KIT_DIR"
ssh_cmd "sudo mkdir -p $(remote_quote "$REMOTE_RELEASE_KIT_DIR") && sudo chown -R $(remote_quote "$SSH_USER"):$(remote_quote "$SSH_USER") $(remote_quote "$REMOTE_RELEASE_KIT_DIR")"

rsync_cmd -az --delete \
  --exclude '.env' \
  --exclude 'host.env' \
  --exclude 'release.env' \
  --exclude 'runtime/release.env' \
  --exclude 'runtime/current-image-tag' \
  --exclude 'runtime/previous-image-tag' \
  "$DEPLOY_DIR/" "$SSH_TARGET:$REMOTE_RELEASE_KIT_DIR/"

ssh_cmd "find $(remote_quote "$REMOTE_RELEASE_KIT_DIR/remote") -type f -name '*.sh' -exec chmod +x {} \\;"
echo "Release kit synced."
