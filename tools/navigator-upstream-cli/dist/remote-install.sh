#!/usr/bin/env bash
set -euo pipefail

RELEASE_BASE_URL='__RELEASE_BASE_URL__'
[[ $RELEASE_BASE_URL != '__RELEASE_BASE_URL__' && -n $RELEASE_BASE_URL ]] || { echo 'This installer has not been configured with RELEASE_BASE_URL.' >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo 'curl is required.' >&2; exit 1; }
command -v jq >/dev/null 2>&1 || { echo 'jq is required.' >&2; exit 1; }

echo '=== Navigator Upstream CLI Remote Installer ==='
echo "Release: $RELEASE_BASE_URL"
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT
LATEST_PATH="$TMP_DIR/latest.json"
curl -fsSL "$RELEASE_BASE_URL/latest.json?ts=$(date +%s)" -o "$LATEST_PATH"
VERSION=$(jq -r '.version // empty' "$LATEST_PATH")
FILE_PATH=$(jq -r '.files.linux // empty' "$LATEST_PATH")
EXPECTED_SHA=$(jq -r '.sha256.linux // empty' "$LATEST_PATH")
[[ -n $VERSION && -n $FILE_PATH ]] || { echo 'latest.json does not contain version/files.linux.' >&2; exit 1; }

ARCHIVE="$TMP_DIR/$(basename "$FILE_PATH")"
curl -fsSL "$RELEASE_BASE_URL/$FILE_PATH" -o "$ARCHIVE"
if [[ -n $EXPECTED_SHA ]]; then
  ACTUAL_SHA=$(sha256sum "$ARCHIVE" | awk '{print $1}')
  [[ ${ACTUAL_SHA,,} == ${EXPECTED_SHA,,} ]] || { echo 'SHA256 mismatch for downloaded archive.' >&2; exit 1; }
fi
tar -xzf "$ARCHIVE" -C "$TMP_DIR"
INSTALLER=$(find "$TMP_DIR" -path '*/install.sh' -type f | head -n 1)
[[ -n $INSTALLER ]] || { echo 'No install.sh found in archive.' >&2; exit 1; }
bash "$INSTALLER" --project-root "$(pwd -P)" --release-base-url "$RELEASE_BASE_URL" --release-manifest-path "$LATEST_PATH"
