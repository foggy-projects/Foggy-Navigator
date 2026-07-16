#!/usr/bin/env bash
set -euo pipefail

VERSION=''
ALLOW_SAME_VERSION=false
SKIP_SMOKE=false
while [[ $# -gt 0 ]]; do
  case $1 in
    --version) VERSION=$2; shift 2 ;;
    --allow-same-version) ALLOW_SAME_VERSION=true; shift ;;
    --skip-smoke) SKIP_SMOKE=true; shift ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
TOOL_DIR=$(dirname "$SCRIPT_DIR")
OUTPUT_DIR="$SCRIPT_DIR/output"
DOT_ENV="$TOOL_DIR/.env"
[[ -f $DOT_ENV ]] || { echo ".env not found: $DOT_ENV" >&2; exit 1; }
set -a
source "$DOT_ENV"
set +a
: "${RELEASE_OBS_BUCKET:?RELEASE_OBS_BUCKET is required}"
: "${RELEASE_BASE_URL:?RELEASE_BASE_URL is required}"
OBSUTIL_BIN=${OBSUTIL_BIN:-obsutil}
command -v "$OBSUTIL_BIN" >/dev/null 2>&1 || { echo "obsutil not found: $OBSUTIL_BIN" >&2; exit 1; }

compare_semver() {
  local left=${1%%[-+]*} right=${2%%[-+]*}
  local IFS=.
  local -a left_parts=($left) right_parts=($right)
  local index left_value right_value
  for index in 0 1 2; do
    left_value=${left_parts[index]:-0}
    right_value=${right_parts[index]:-0}
    if ((10#$left_value > 10#$right_value)); then printf '1'; return; fi
    if ((10#$left_value < 10#$right_value)); then printf '%s' '-1'; return; fi
  done
  printf '0'
}

if [[ -z $VERSION ]]; then
  VERSION=$(find "$OUTPUT_DIR" -maxdepth 1 -type f -name 'navigator-upstream-cli-*-linux.tar.gz' -printf '%f\n' | sed -n 's/^navigator-upstream-cli-\(.*\)-linux\.tar\.gz$/\1/p' | sort -V | tail -n 1)
fi
[[ -n $VERSION ]] || { echo 'No release version resolved.' >&2; exit 1; }
WINDOWS_ARCHIVE="$OUTPUT_DIR/navigator-upstream-cli-$VERSION-windows.zip"
LINUX_ARCHIVE="$OUTPUT_DIR/navigator-upstream-cli-$VERSION-linux.tar.gz"
[[ -f $WINDOWS_ARCHIVE && -f $LINUX_ARCHIVE ]] || { echo "Both Windows and Linux archives are required for $VERSION." >&2; exit 1; }

if [[ $ALLOW_SAME_VERSION == false ]]; then
  REMOTE_VERSION=$(curl -fsSL --max-time 15 "$RELEASE_BASE_URL/latest.json?ts=$(date +%s)" | jq -r '.version // empty' || true)
  if [[ -n $REMOTE_VERSION ]]; then
    COMPARISON=$(compare_semver "$VERSION" "$REMOTE_VERSION")
    [[ $COMPARISON != 0 ]] || { echo "Remote latest is already $VERSION. Bump version or use --allow-same-version." >&2; exit 1; }
    [[ $COMPARISON != -1 ]] || { echo "Local version $VERSION is older than remote latest $REMOTE_VERSION." >&2; exit 1; }
  fi
fi

BUILD_INFO=$(cat "$OUTPUT_DIR/BUILD_INFO.json")
WINDOWS_SHA=$(sha256sum "$WINDOWS_ARCHIVE" | awk '{print $1}')
LINUX_SHA=$(sha256sum "$LINUX_ARCHIVE" | awk '{print $1}')
GIT_COMMIT=$(jq -r '.gitCommit // empty' <<<"$BUILD_INFO")
GIT_DIRTY=$(jq -r '.gitDirty // false' <<<"$BUILD_INFO")
BUILD_TIME_UTC=$(jq -r '.buildTimeUtc // empty' <<<"$BUILD_INFO")
FEATURES=$(jq -c '.features // []' <<<"$BUILD_INFO")
SHORT_COMMIT=${GIT_COMMIT:0:12}
BUILD_ID="$VERSION+${SHORT_COMMIT:-$(date -u +%Y%m%d%H%M%S)}"
[[ $GIT_DIRTY == true ]] && BUILD_ID="$BUILD_ID.dirty"

echo '=== Navigator Upstream CLI OBS Upload ==='
echo "Version: $VERSION"
"$OBSUTIL_BIN" cp "$WINDOWS_ARCHIVE" "$RELEASE_OBS_BUCKET/$VERSION/$(basename "$WINDOWS_ARCHIVE")" -f
"$OBSUTIL_BIN" cp "$LINUX_ARCHIVE" "$RELEASE_OBS_BUCKET/$VERSION/$(basename "$LINUX_ARCHIVE")" -f

LATEST_PATH="$OUTPUT_DIR/latest.json"
jq -n --arg version "$VERSION" --arg released "$(date +%F)" --arg buildTimeUtc "$BUILD_TIME_UTC" --arg buildId "$BUILD_ID" --arg gitCommit "$GIT_COMMIT" --argjson gitDirty "$GIT_DIRTY" --arg windows "${VERSION}/$(basename "$WINDOWS_ARCHIVE")" --arg linux "${VERSION}/$(basename "$LINUX_ARCHIVE")" --arg windowsSha "$WINDOWS_SHA" --arg linuxSha "$LINUX_SHA" --argjson features "$FEATURES" '{version:$version,released:$released,buildTimeUtc:$buildTimeUtc,buildId:$buildId,gitCommit:$gitCommit,gitDirty:$gitDirty,features:$features,files:{windows:$windows,linux:$linux},sha256:{windows:$windowsSha,linux:$linuxSha}}' > "$LATEST_PATH"
"$OBSUTIL_BIN" cp "$LATEST_PATH" "$RELEASE_OBS_BUCKET/latest.json" -f

WINDOWS_INSTALL="$OUTPUT_DIR/install.ps1"
LINUX_INSTALL="$OUTPUT_DIR/install.sh"
sed "s|\$ReleaseBaseUrl = \"__RELEASE_BASE_URL__\"|\$ReleaseBaseUrl = \"$RELEASE_BASE_URL\"|" "$SCRIPT_DIR/remote-install.ps1" > "$WINDOWS_INSTALL"
sed "s|RELEASE_BASE_URL='__RELEASE_BASE_URL__'|RELEASE_BASE_URL='$RELEASE_BASE_URL'|" "$SCRIPT_DIR/remote-install.sh" > "$LINUX_INSTALL"
chmod +x "$LINUX_INSTALL"
"$OBSUTIL_BIN" cp "$WINDOWS_INSTALL" "$RELEASE_OBS_BUCKET/install.ps1" -f
"$OBSUTIL_BIN" cp "$LINUX_INSTALL" "$RELEASE_OBS_BUCKET/install.sh" -f

if [[ $SKIP_SMOKE == false ]]; then
  TMP_ROOT=$(mktemp -d)
  trap 'rm -rf "$TMP_ROOT"' EXIT
  (
    cd "$TMP_ROOT"
    bash "$LINUX_INSTALL"
    NAVI="$TMP_ROOT/tools/navigator-upstream/navi"
    [[ -x $NAVI ]] || { echo "remote install smoke did not create $NAVI" >&2; exit 1; }
    "$NAVI" version | grep -F "navigator-upstream-cli $VERSION" >/dev/null
    "$NAVI" upstream --help | grep -F 'function import' >/dev/null
    if "$NAVI" upstream ask --not-a-real-option >/dev/null 2>&1; then
      echo 'unknown option smoke unexpectedly succeeded' >&2
      exit 1
    fi
  )
fi

echo 'Upload complete.'
