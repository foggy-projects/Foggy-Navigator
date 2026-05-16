#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
. "$SCRIPT_DIR/lib.sh"

echo "Status for $(print_target)"

remote_cmd=$(cat <<REMOTE
set -euo pipefail
cd "$(remote_quote "$REMOTE_CURRENT_DIR")" 2>/dev/null || {
  echo "Remote app dir does not exist: $REMOTE_CURRENT_DIR"
  exit 1
}

echo "Processes:"
if [ -f logs/backend.pid ]; then
  pid=\$(cat logs/backend.pid)
  if ps -p "\$pid" >/dev/null 2>&1; then
    echo "  backend: running pid=\$pid"
  else
    echo "  backend: pid file exists but process is not running"
  fi
else
  echo "  backend: no pid file"
fi

if [ -f tools/claude-agent-worker/logs/worker.pid ]; then
  worker_pid=\$(cat tools/claude-agent-worker/logs/worker.pid)
  if ps -p "\$worker_pid" >/dev/null 2>&1; then
    echo "  claude-agent-worker: running pid=\$worker_pid"
  else
    echo "  claude-agent-worker: pid file exists but process is not running"
  fi
else
  echo "  claude-agent-worker: no pid file"
fi

echo
echo "Docker:"
docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}' | grep -E 'NAMES|foggy-navigator' || true

echo
echo "HTTP:"
curl -fsS http://127.0.0.1:8112/actuator/health 2>/dev/null || echo "  backend health unavailable"
echo
curl -fsS http://127.0.0.1:3031/health 2>/dev/null || echo "  claude-agent-worker health unavailable"
echo
curl -fsS http://127.0.0.1/health 2>/dev/null || echo "  nginx health unavailable"
echo
REMOTE
)

ssh_cmd "$remote_cmd"
