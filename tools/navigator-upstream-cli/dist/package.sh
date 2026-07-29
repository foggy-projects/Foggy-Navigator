#!/usr/bin/env bash
set -euo pipefail

UPLOAD=false
ALLOW_SAME_VERSION=false
while [[ $# -gt 0 ]]; do
  case $1 in
    --upload) UPLOAD=true; shift ;;
    --allow-same-version) ALLOW_SAME_VERSION=true; shift ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
TOOL_DIR=$(dirname "$SCRIPT_DIR")
REPO_ROOT=$(cd "$TOOL_DIR/../.." && pwd -P)
SDK_DIR="$REPO_ROOT/navigator-open-sdk"
VERSION=$(sed -n \
  '/<artifactId>navigator-open-sdk<\/artifactId>/,/<version>/s/^[[:space:]]*<version>\([^<]*\)<\/version>.*/\1/p' \
  "$SDK_DIR/pom.xml" | head -n 1)
[[ -n $VERSION ]] || { echo "Could not read navigator-open-sdk version from $SDK_DIR/pom.xml" >&2; exit 1; }
[[ $VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+(-SNAPSHOT)?$ ]] || {
  echo "Unsupported navigator-open-sdk release version: $VERSION" >&2
  exit 1
}

GIT_COMMIT=$(git -C "$REPO_ROOT" rev-parse HEAD)
GIT_BRANCH=$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD)
GIT_DIRTY=false
[[ -z $(git -C "$REPO_ROOT" status --porcelain) ]] || GIT_DIRTY=true
if [[ $UPLOAD == true && $GIT_DIRTY == true ]]; then
  echo 'Refusing to publish from a dirty git worktree.' >&2
  exit 1
fi

echo '=== Navigator Upstream CLI Packager ==='
echo "Version: $VERSION"
echo "Repo:    $REPO_ROOT"

pushd "$REPO_ROOT" >/dev/null
mvn -q -pl navigator-open-sdk -DskipTests package dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/dependency
popd >/dev/null

BUILD_TIME_UTC=$(date -u +%Y-%m-%dT%H:%M:%SZ)
BUILD_INFO=$(jq -n --arg version "$VERSION" --arg buildTimeUtc "$BUILD_TIME_UTC" --arg gitCommit "$GIT_COMMIT" --arg gitBranch "$GIT_BRANCH" --argjson gitDirty "$GIT_DIRTY" '{version:$version,buildTimeUtc:$buildTimeUtc,gitCommit:$gitCommit,gitBranch:$gitBranch,gitDirty:$gitDirty,features:["structured-json-redaction","typed-termination-reconciliation","termination-receipt-policy","config-check","auth-login","runtime-token","owner-smoke","agent-readiness","ask","safe-ask","runtime-request-audit","safe-ask-client-request-correlation","runtime-standard-ask-request-audit","standard-ask-client-request-correlation","runtime-token-standard-ask-correlation","runtime-audit-no-task-id","runtime-binding-audit","runtime-task-audit","runtime-task-completion-readiness","runtime-audit-no-token-issuance","runtime-task-terminal-state-audit","runtime-audit-no-dispatch","runtime-request-time-basis","runtime-task-terminate","runtime-task-terminate-dry-run","runtime-task-reconcile","runtime-task-termination-audit","runtime-audit-standard-ask","runtime-task-scope-at-admission","runtime-task-token-revocation-audit","runtime-termination-no-redispatch","runtime-reconcile-no-dispatch","messages","sessions","skill-artifact-read","skill-sync","skill-clear","agent-sync","function-import","function-grant","function-grant-status","function-visible","upstream-route","model-grant","model-owned-config","model-subscription-config","codex-app-server-model-config","gpt-5.6-model-catalog","model-connection-test","model-system-audit","model-variant","runtime-budget-preset","account-context","deterministic-e2e","admin-key-bootstrap","client-app-bootstrap","client-app-runtime-credential","tms-saas-three-lane","tenant-credential-profile-split","system-admin-clientapp-scope","upstream-worker-orchestration","upstream-directory-orchestration","upstream-worker-pool-orchestration","task-diagnostics","session-directory-diagnostics","task-evidence","message-event-contract","physical-worker-diagnostics","worker-host-suite","navi-routed-codex-config","codex-biz-worker-route","codex-biz-runtime-options","ask-allowed-tools","ask-allowed-functions","runtime-profile-posix-0600","ask-directory-actionable-error"]}')

STAGING_ROOT="$SCRIPT_DIR/staging"
OUTPUT_DIR="$SCRIPT_DIR/output"
WINDOWS_ROOT="$STAGING_ROOT/windows/navigator-upstream"
LINUX_ROOT="$STAGING_ROOT/linux/navigator-upstream"
rm -rf "$STAGING_ROOT" "$OUTPUT_DIR"
mkdir -p "$WINDOWS_ROOT/lib" "$WINDOWS_ROOT/bin" "$LINUX_ROOT/lib" "$LINUX_ROOT/bin" "$OUTPUT_DIR"

copy_libs() {
  local target=$1
  cp "$SDK_DIR/target/navigator-open-sdk-$VERSION.jar" "$target/lib/"
  cp "$SDK_DIR"/target/dependency/*.jar "$target/lib/"
}
copy_libs "$WINDOWS_ROOT"
copy_libs "$LINUX_ROOT"

cp "$SCRIPT_DIR/bin/navi.ps1" "$WINDOWS_ROOT/navi.ps1"
cp "$SCRIPT_DIR/bin/navi.cmd" "$WINDOWS_ROOT/navi.cmd"
cp "$SCRIPT_DIR/bin/navi-e2e.ps1" "$WINDOWS_ROOT/navi-e2e.ps1"
cp "$SCRIPT_DIR/bin/navi-e2e.cmd" "$WINDOWS_ROOT/navi-e2e.cmd"
cp "$SCRIPT_DIR/bin/navi.ps1" "$WINDOWS_ROOT/bin/navi.ps1"
cp "$SCRIPT_DIR/bin/navi.cmd" "$WINDOWS_ROOT/bin/navi.cmd"
cp "$SCRIPT_DIR/bin/navi-e2e.ps1" "$WINDOWS_ROOT/bin/navi-e2e.ps1"
cp "$SCRIPT_DIR/bin/navi-e2e.cmd" "$WINDOWS_ROOT/bin/navi-e2e.cmd"
cp "$SCRIPT_DIR/install.ps1" "$WINDOWS_ROOT/install.ps1"

cp "$SCRIPT_DIR/bin/navi" "$LINUX_ROOT/navi"
cp "$SCRIPT_DIR/bin/navi-e2e" "$LINUX_ROOT/navi-e2e"
cp "$SCRIPT_DIR/bin/navi" "$LINUX_ROOT/bin/navi"
cp "$SCRIPT_DIR/bin/navi-e2e" "$LINUX_ROOT/bin/navi-e2e"
cp "$SCRIPT_DIR/install.sh" "$LINUX_ROOT/install.sh"
chmod +x "$LINUX_ROOT/navi" "$LINUX_ROOT/navi-e2e" "$LINUX_ROOT/bin/navi" "$LINUX_ROOT/bin/navi-e2e" "$LINUX_ROOT/install.sh"

for root in "$WINDOWS_ROOT" "$LINUX_ROOT"; do
  printf '%s' "$VERSION" > "$root/VERSION"
  printf '%s\n' "$BUILD_INFO" > "$root/BUILD_INFO.json"
done

WINDOWS_ARCHIVE="$OUTPUT_DIR/navigator-upstream-cli-$VERSION-windows.zip"
LINUX_ARCHIVE="$OUTPUT_DIR/navigator-upstream-cli-$VERSION-linux.tar.gz"
(cd "$STAGING_ROOT/windows" && zip -qr "$WINDOWS_ARCHIVE" navigator-upstream)
tar -C "$STAGING_ROOT/linux" -czf "$LINUX_ARCHIVE" navigator-upstream
WINDOWS_SHA=$(sha256sum "$WINDOWS_ARCHIVE" | awk '{print $1}')
LINUX_SHA=$(sha256sum "$LINUX_ARCHIVE" | awk '{print $1}')
printf '%s  %s\n' "$WINDOWS_SHA" "$(basename "$WINDOWS_ARCHIVE")" > "$WINDOWS_ARCHIVE.sha256"
printf '%s  %s\n' "$LINUX_SHA" "$(basename "$LINUX_ARCHIVE")" > "$LINUX_ARCHIVE.sha256"
printf '%s\n' "$BUILD_INFO" > "$OUTPUT_DIR/BUILD_INFO.json"

SHORT_COMMIT=${GIT_COMMIT:0:12}
BUILD_ID="$VERSION+${SHORT_COMMIT:-$(date -u +%Y%m%d%H%M%S)}"
[[ $GIT_DIRTY == true ]] && BUILD_ID="$BUILD_ID.dirty"
FEATURES=$(jq -c '.features // []' <<<"$BUILD_INFO")
jq -n \
  --arg version "$VERSION" \
  --arg released "$(date -u +%F)" \
  --arg buildTimeUtc "$BUILD_TIME_UTC" \
  --arg buildId "$BUILD_ID" \
  --arg gitCommit "$GIT_COMMIT" \
  --argjson gitDirty "$GIT_DIRTY" \
  --argjson features "$FEATURES" \
  --arg windows "$VERSION/$(basename "$WINDOWS_ARCHIVE")" \
  --arg linux "$VERSION/$(basename "$LINUX_ARCHIVE")" \
  --arg windowsSha "$WINDOWS_SHA" \
  --arg linuxSha "$LINUX_SHA" \
  '{version:$version,released:$released,buildTimeUtc:$buildTimeUtc,buildId:$buildId,gitCommit:$gitCommit,gitDirty:$gitDirty,features:$features,files:{windows:$windows,linux:$linux},sha256:{windows:$windowsSha,linux:$linuxSha}}' \
  > "$OUTPUT_DIR/RELEASE_MANIFEST.json"
rm -rf "$STAGING_ROOT"

echo "Windows archive: $WINDOWS_ARCHIVE"
echo "Linux archive:   $LINUX_ARCHIVE"
echo "Release manifest: $OUTPUT_DIR/RELEASE_MANIFEST.json"
if [[ $UPLOAD == true ]]; then
  upload_args=(--version "$VERSION")
  [[ $ALLOW_SAME_VERSION == true ]] && upload_args+=(--allow-same-version)
  "$SCRIPT_DIR/upload.sh" "${upload_args[@]}"
fi
