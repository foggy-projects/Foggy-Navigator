#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
. "$SCRIPT_DIR/lib.sh"

REMOTE_RELEASE_KIT_DIR="${REMOTE_RELEASE_KIT_DIR:-$REMOTE_APP_DIR/release-kit}"

bash "$SCRIPT_DIR/10-sync-release-kit.sh"
ssh_cmd "bash $(remote_quote "$REMOTE_RELEASE_KIT_DIR/remote/check-business-agent-materialize-env.sh")"
