#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
. "$SCRIPT_DIR/lib.sh"

load_release_env
require_var GIT_URL
require_var GIT_REF
require_var IMAGE_TAG

src="$(release_source_dir)"
mkdir -p "$(dirname "$src")"

if [ ! -d "$src/.git" ]; then
  log "Cloning $GIT_URL into $src"
  git clone "$GIT_URL" "$src"
else
  log "Updating existing source checkout: $src"
  git -C "$src" remote set-url origin "$GIT_URL"
fi

git -C "$src" fetch --tags origin '+refs/heads/*:refs/remotes/origin/*'

if git -C "$src" rev-parse --verify --quiet "$GIT_REF^{commit}" >/dev/null; then
  git -C "$src" checkout --detach "$GIT_REF"
elif git -C "$src" rev-parse --verify --quiet "origin/$GIT_REF^{commit}" >/dev/null; then
  git -C "$src" checkout --detach "origin/$GIT_REF"
elif git -C "$src" rev-parse --verify --quiet "refs/tags/$GIT_REF^{commit}" >/dev/null; then
  git -C "$src" checkout --detach "refs/tags/$GIT_REF"
else
  die "Cannot resolve GIT_REF=$GIT_REF"
fi

git -C "$src" submodule update --init --recursive
git -C "$src" rev-parse HEAD > "$RUNTIME_DIR/source-commit"

log "Checked out $(git -C "$src" rev-parse --short HEAD) for image tag $IMAGE_TAG"
