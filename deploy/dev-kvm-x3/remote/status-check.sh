#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
. "$SCRIPT_DIR/lib.sh"

load_release_env
ensure_runtime_files

run_status_check_once() {
  local failed=0

  log "Docker Compose status"
  compose ps

  for name in foggy-navigator-backend foggy-navigator-frontend; do
    local state
    local health
    state="$(docker inspect -f '{{.State.Status}}' "$name" 2>/dev/null || true)"
    health="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$name" 2>/dev/null || true)"
    echo "  $name: state=${state:-missing} health=${health:-missing}"
    if [ "$state" != "running" ]; then
      failed=1
    fi
    if [ -n "$health" ] && [ "$health" != "none" ] && [ "$health" != "healthy" ]; then
      failed=1
    fi
  done

  check_http() {
    local name="$1"
    local url="$2"
    local tmp_out="/tmp/navigator-check-${name}.out"
    local tmp_err="/tmp/navigator-check-${name}.err"
    local http_code
    if [ -z "$url" ]; then
      echo "  $name: skipped"
      return
    fi
    http_code="$(curl -fsS -o "$tmp_out" -w "%{http_code}" "$url" 2>"$tmp_err" || true)"
    if echo "$http_code" | grep -Eq '^(2|3)[0-9][0-9]$'; then
      echo "  $name: OK $url"
    else
      if [ "${NAVIGATOR_STATUS_QUIET_TRANSIENT_FAILURES:-false}" = "true" ] && [ "$attempt" -lt "$retries" ]; then
        echo "  $name: waiting $url http=${http_code:-none}"
      else
        echo "  $name: FAILED $url http=${http_code:-none}" >&2
        if [ -s "$tmp_err" ]; then
          sed 's/^/    /' "$tmp_err" >&2
        fi
      fi
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

  return "$failed"
}

retries="${NAVIGATOR_STATUS_RETRIES:-1}"
interval_seconds="${NAVIGATOR_STATUS_INTERVAL_SECONDS:-2}"
attempt=1

while true; do
  if run_status_check_once; then
    exit 0
  fi

  if [ "$attempt" -ge "$retries" ]; then
    exit 1
  fi

  log "Status check failed on attempt $attempt/$retries; retrying in ${interval_seconds}s"
  sleep "$interval_seconds"
  attempt=$((attempt + 1))
done
