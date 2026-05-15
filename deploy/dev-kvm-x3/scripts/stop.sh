#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
. "$SCRIPT_DIR/lib.sh"

echo "Stopping Navigator on $(print_target)"

remote_cmd=$(cat <<REMOTE
set -euo pipefail
cd "$(remote_quote "$REMOTE_CURRENT_DIR")"

if [ -f ./stop-all.sh ]; then
  bash ./stop-all.sh || true
fi

compose() {
  if docker compose version >/dev/null 2>&1; then
    docker compose "\$@"
  elif command -v docker-compose >/dev/null 2>&1; then
    docker-compose "\$@"
  else
    echo "Docker Compose is not installed." >&2
    return 1
  fi
}

(cd docker && compose stop nginx rabbitmq mysql) || true
echo "Stopped."
REMOTE
)

ssh_cmd "$remote_cmd"
