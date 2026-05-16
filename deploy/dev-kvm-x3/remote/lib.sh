#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RELEASE_KIT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
DEPLOY_ROOT="${NAVIGATOR_DEPLOY_ROOT:-/opt/foggy/navigator}"
BUILD_ROOT="${NAVIGATOR_BUILD_ROOT:-$DEPLOY_ROOT/build}"
SOURCE_DIR="${NAVIGATOR_SOURCE_DIR:-$BUILD_ROOT/source}"
RUNTIME_DIR="${NAVIGATOR_RUNTIME_DIR:-$DEPLOY_ROOT/runtime}"
RUNTIME_COMPOSE_FILE="${RUNTIME_COMPOSE_FILE:-$RUNTIME_DIR/docker-compose.navigator.yml}"
RELEASE_ENV="${RELEASE_ENV:-$RUNTIME_DIR/release.env}"
PROJECT_NAME="${NAVIGATOR_COMPOSE_PROJECT:-foggy-navigator}"

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
}

die() {
  echo "ERROR: $*" >&2
  exit 1
}

require_command() {
  local name="$1"
  command -v "$name" >/dev/null 2>&1 || die "Missing command: $name"
}

load_release_env() {
  [ -f "$RELEASE_ENV" ] || die "Missing release env: $RELEASE_ENV"
  set -a
  # shellcheck disable=SC1090
  . "$RELEASE_ENV"
  set +a
}

require_var() {
  local name="$1"
  if [ -z "${!name:-}" ]; then
    die "$name is required in $RELEASE_ENV"
  fi
}

compose_args() {
  local args=(-p "$PROJECT_NAME" --env-file "$RELEASE_ENV" -f "$RUNTIME_COMPOSE_FILE")
  if [ "${NAVIGATOR_LOCAL_INFRA:-false}" = "true" ]; then
    args=(--profile local-infra "${args[@]}")
  fi
  printf '%s\n' "${args[@]}"
}

compose() {
  local args
  mapfile -t args < <(compose_args)
  docker compose "${args[@]}" "$@"
}

image_ref() {
  local component="$1"
  require_var HARBOR_REGISTRY
  require_var HARBOR_PROJECT
  require_var IMAGE_TAG
  printf '%s/%s/%s:%s' "$HARBOR_REGISTRY" "$HARBOR_PROJECT" "$component" "$IMAGE_TAG"
}

release_source_dir() {
  require_var IMAGE_TAG
  printf '%s/%s' "$SOURCE_DIR" "$IMAGE_TAG"
}

ensure_runtime_files() {
  mkdir -p "$RUNTIME_DIR" "$BUILD_ROOT" "$SOURCE_DIR"
  cp "$RELEASE_KIT_DIR/runtime/docker-compose.navigator.yml" "$RUNTIME_COMPOSE_FILE"
}

update_release_env_value() {
  local key="$1"
  local value="$2"
  python3 - "$RELEASE_ENV" "$key" "$value" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
key = sys.argv[2]
value = sys.argv[3]
lines = path.read_text(encoding="utf-8").splitlines()
out = []
seen = False
for line in lines:
    if line.startswith(f"{key}="):
        out.append(f"{key}={value}")
        seen = True
    else:
        out.append(line)
if not seen:
    out.append(f"{key}={value}")
path.write_text("\n".join(out) + "\n", encoding="utf-8")
PY
}
