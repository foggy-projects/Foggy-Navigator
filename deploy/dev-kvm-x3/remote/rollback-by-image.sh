#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
. "$SCRIPT_DIR/lib.sh"

target_tag="${1:-}"
if [ -z "$target_tag" ]; then
  if [ -f "$RUNTIME_DIR/previous-image-tag" ]; then
    target_tag="$(cat "$RUNTIME_DIR/previous-image-tag")"
  else
    die "Usage: $0 <image-tag>; no previous-image-tag file exists"
  fi
fi

load_release_env
require_var HARBOR_REGISTRY
require_var HARBOR_PROJECT

log "Rolling back Navigator to image tag $target_tag"
update_release_env_value IMAGE_TAG "$target_tag"
update_release_env_value NAVIGATOR_BACKEND_IMAGE "$HARBOR_REGISTRY/$HARBOR_PROJECT/navigator-backend:$target_tag"
update_release_env_value NAVIGATOR_FRONTEND_IMAGE "$HARBOR_REGISTRY/$HARBOR_PROJECT/navigator-frontend:$target_tag"

bash "$SCRIPT_DIR/deploy-by-image.sh"
