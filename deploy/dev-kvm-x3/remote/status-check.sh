#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
. "$SCRIPT_DIR/lib.sh"

load_release_env
ensure_runtime_files

log "Docker Compose status"
compose ps

failed=0
for name in foggy-navigator-backend foggy-navigator-frontend; do
  state="$(docker inspect -f '{{.State.Status}}' "$name" 2>/dev/null || true)"
  health="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$name" 2>/dev/null || true)"
  echo "  $name: state=${state:-missing} health=${health:-missing}"
  if [ "$state" != "running" ]; then
    failed=1
  fi
done

check_http() {
  local name="$1"
  local url="$2"
  if [ -z "$url" ]; then
    echo "  $name: skipped"
    return
  fi
  if curl -fsS -o /tmp/navigator-check.out -w "%{http_code}" "$url" | grep -Eq '^(2|3)[0-9][0-9]$'; then
    echo "  $name: OK $url"
  else
    echo "  $name: FAILED $url" >&2
    failed=1
  fi
}

log "HTTP checks"
check_http frontend "${NAVIGATOR_FRONTEND_HEALTH_URL:-http://127.0.0.1/health}"
check_http backend "${NAVIGATOR_BACKEND_HEALTH_URL:-http://127.0.0.1:8112/actuator/health}"

log "Startup log scan"
for name in foggy-navigator-backend foggy-navigator-frontend; do
  if docker logs --tail 160 "$name" 2>&1 | grep -Eiq 'Application run failed|Failed to start|Traceback|FATAL'; then
    echo "  $name: startup failure pattern found" >&2
    failed=1
  else
    echo "  $name: no startup failure pattern in recent logs"
  fi
done

if [ -f "$RUNTIME_DIR/current-image-tag" ]; then
  echo "Current image tag: $(cat "$RUNTIME_DIR/current-image-tag")"
else
  echo "Current image tag: ${IMAGE_TAG:-unknown}"
fi

exit "$failed"
