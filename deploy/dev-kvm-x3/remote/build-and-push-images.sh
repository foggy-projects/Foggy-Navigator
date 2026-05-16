#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
. "$SCRIPT_DIR/lib.sh"

load_release_env
require_var IMAGE_TAG
require_var HARBOR_REGISTRY
require_var HARBOR_PROJECT

bash "$SCRIPT_DIR/configure-release.sh"
bash "$SCRIPT_DIR/checkout-ref.sh"
load_release_env

src="$(release_source_dir)"
release_kit="$RELEASE_KIT_DIR"
backend="$(image_ref navigator-backend)"
frontend="$(image_ref navigator-frontend)"

if [ -n "${HARBOR_USERNAME:-}" ] && [ -n "${HARBOR_PASSWORD:-}" ]; then
  log "Logging in to Harbor registry $HARBOR_REGISTRY"
  printf '%s' "$HARBOR_PASSWORD" | docker login "$HARBOR_REGISTRY" -u "$HARBOR_USERNAME" --password-stdin
else
  log "Harbor username/password not set; relying on existing docker login session."
fi

log "Building backend jar"
(
  cd "$src"
  mvn ${MAVEN_ARGS:-"-DskipTests"} package
)

log "Building frontend dist via pnpm registry ${NPM_REGISTRY:-default}"
(
  cd "$src"
  if [ -n "${NPM_REGISTRY:-}" ]; then
    pnpm config set registry "$NPM_REGISTRY"
  fi
  pnpm install ${PNPM_INSTALL_ARGS:---no-frozen-lockfile}
  pnpm --filter @foggy/chat-core build
  pnpm --filter @foggy/chat build
  pnpm --filter @foggy/navigator-frontend build
)

log "Building images"
mkdir -p "$src/deploy/dev-kvm-x3"
rm -rf "$src/deploy/dev-kvm-x3/images"
cp -R "$release_kit/images" "$src/deploy/dev-kvm-x3/images"
docker build -f "$release_kit/images/backend/Dockerfile" -t "$backend" "$src"
docker build -f "$release_kit/images/frontend/Dockerfile" -t "$frontend" "$src"

log "Pushing images"
docker push "$backend"
docker push "$frontend"

printf '%s\n' "$IMAGE_TAG" > "$RUNTIME_DIR/current-image-tag"
git -C "$src" rev-parse HEAD > "$RUNTIME_DIR/current-source-commit"

log "Build and push complete"
echo "  tag:      $IMAGE_TAG"
echo "  backend:  $backend"
echo "  frontend: $frontend"
