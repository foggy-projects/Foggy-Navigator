#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
. "$SCRIPT_DIR/lib.sh"

LINES="${1:-120}"

remote_cmd=$(cat <<REMOTE
set -euo pipefail
cd "$(remote_quote "$REMOTE_CURRENT_DIR")"

echo "===== backend.log ====="
tail -n "$LINES" logs/backend.log 2>/dev/null || true

echo
echo "===== backend-error.log ====="
tail -n "$LINES" logs/backend-error.log 2>/dev/null || true

echo
echo "===== frontend-build.log ====="
tail -n "$LINES" logs/frontend-build.log 2>/dev/null || true

echo
echo "===== nginx docker logs ====="
docker logs --tail "$LINES" foggy-navigator-nginx 2>/dev/null || true
REMOTE
)

ssh_cmd "$remote_cmd"
