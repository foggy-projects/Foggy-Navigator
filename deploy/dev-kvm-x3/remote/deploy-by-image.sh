#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
. "$SCRIPT_DIR/lib.sh"

load_release_env
require_var IMAGE_TAG
require_var NAVIGATOR_BACKEND_IMAGE
require_var NAVIGATOR_FRONTEND_IMAGE

ensure_runtime_files

if ! docker network inspect foggy-navigator-network >/dev/null 2>&1; then
  log "Creating Docker network foggy-navigator-network"
  docker network create foggy-navigator-network >/dev/null
fi

previous=""
if [ -f "$RUNTIME_DIR/current-image-tag" ]; then
  previous="$(cat "$RUNTIME_DIR/current-image-tag")"
fi
if [ -n "$previous" ] && [ "$previous" != "$IMAGE_TAG" ]; then
  printf '%s\n' "$previous" > "$RUNTIME_DIR/previous-image-tag"
fi

log "Pulling Navigator images for tag $IMAGE_TAG"
compose pull navigator-backend navigator-frontend

if [ "${NAVIGATOR_LOCAL_INFRA:-false}" = "true" ]; then
  log "Ensuring local Navigator infra is running"
  compose up -d mysql rabbitmq
fi

if [ "${NAVIGATOR_STOP_LEGACY_SOURCE:-true}" = "true" ]; then
  log "Stopping legacy source-deployed Navigator processes on app ports"
  for port in 8112; do
    if command -v lsof >/dev/null 2>&1; then
      pids="$(lsof -ti:"$port" 2>/dev/null || true)"
      if [ -n "$pids" ]; then
        # shellcheck disable=SC2086
        kill -9 $pids 2>/dev/null || true
      fi
    fi
  done
fi

log "Removing old Navigator app containers only"
for name in foggy-navigator-backend foggy-navigator-frontend foggy-navigator-nginx; do
  docker rm -f "$name" >/dev/null 2>&1 || true
done

log "Starting Navigator by image"
compose up -d navigator-backend navigator-frontend
printf '%s\n' "$IMAGE_TAG" > "$RUNTIME_DIR/current-image-tag"

NAVIGATOR_STATUS_RETRIES="${NAVIGATOR_DEPLOY_HEALTH_RETRIES:-45}" \
NAVIGATOR_STATUS_INTERVAL_SECONDS="${NAVIGATOR_DEPLOY_HEALTH_INTERVAL_SECONDS:-2}" \
NAVIGATOR_STATUS_QUIET_TRANSIENT_FAILURES=true \
  bash "$SCRIPT_DIR/status-check.sh"
