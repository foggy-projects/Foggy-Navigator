#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
. "$SCRIPT_DIR/lib.sh"

ensure_runtime_files
if [ ! -f "$RELEASE_ENV" ]; then
  cp "$RELEASE_KIT_DIR/release.env.example" "$RELEASE_ENV"
  chmod 0600 "$RELEASE_ENV"
  log "Created $RELEASE_ENV from template."
fi

load_release_env
require_var IMAGE_TAG
require_var HARBOR_REGISTRY
require_var HARBOR_PROJECT

backend="$(image_ref navigator-backend)"
frontend="$(image_ref navigator-frontend)"

update_release_env_value NAVIGATOR_BACKEND_IMAGE "$backend"
update_release_env_value NAVIGATOR_FRONTEND_IMAGE "$frontend"

log "Release configured"
echo "  image tag: $IMAGE_TAG"
echo "  backend:   $backend"
echo "  frontend:  $frontend"
echo "  compose:   $RUNTIME_COMPOSE_FILE"
echo "  env:       $RELEASE_ENV"
