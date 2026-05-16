#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
. "$SCRIPT_DIR/lib.sh"

STAMP="$(date +%Y%m%d-%H%M%S)"
REMOTE_FILE="$REMOTE_BACKUP_DIR/coding_agent-$STAMP.sql.gz"

echo "Backing up MySQL on $(print_target)"

remote_cmd=$(cat <<REMOTE
set -euo pipefail
mkdir -p "$(remote_quote "$REMOTE_BACKUP_DIR")"
docker exec foggy-navigator-mysql sh -c 'mysqldump -u root -p"\$MYSQL_ROOT_PASSWORD" coding_agent' | gzip > "$(remote_quote "$REMOTE_FILE")"
chmod 600 "$(remote_quote "$REMOTE_FILE")"
echo "$REMOTE_FILE"
REMOTE
)

ssh_cmd "$remote_cmd"
