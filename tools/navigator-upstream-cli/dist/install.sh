#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT=''
INSTALL_DIR=''
RELEASE_BASE_URL=''
RELEASE_MANIFEST_PATH=''
UPGRADE=false

while [[ $# -gt 0 ]]; do
  case $1 in
    --project-root) PROJECT_ROOT=$2; shift 2 ;;
    --install-dir) INSTALL_DIR=$2; shift 2 ;;
    --release-base-url) RELEASE_BASE_URL=$2; shift 2 ;;
    --release-manifest-path) RELEASE_MANIFEST_PATH=$2; shift 2 ;;
    --upgrade) UPGRADE=true; shift ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

SOURCE_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
if [[ ! -d $SOURCE_ROOT/lib && -d $(dirname "$SOURCE_ROOT")/lib ]]; then
  SOURCE_ROOT=$(dirname "$SOURCE_ROOT")
fi
[[ -d $SOURCE_ROOT/lib ]] || { echo "Installer source is missing lib directory: $SOURCE_ROOT" >&2; exit 1; }

PROJECT_ROOT=${PROJECT_ROOT:-$(pwd -P)}
mkdir -p "$PROJECT_ROOT"
PROJECT_ROOT=$(cd "$PROJECT_ROOT" && pwd -P)
INSTALL_DIR=${INSTALL_DIR:-"$PROJECT_ROOT/tools/navigator-upstream"}
INSTALL_PARENT=$(dirname "$INSTALL_DIR")
mkdir -p "$INSTALL_PARENT"
INSTALL_PARENT=$(cd "$INSTALL_PARENT" && pwd -P)
INSTALL_DIR="$INSTALL_PARENT/$(basename "$INSTALL_DIR")"
[[ $INSTALL_DIR == "$PROJECT_ROOT"/* ]] || { echo "InstallDir must be inside ProjectRoot. InstallDir=$INSTALL_DIR ProjectRoot=$PROJECT_ROOT" >&2; exit 1; }
[[ $(basename "$INSTALL_DIR") == navigator-upstream ]] || { echo "Refusing to replace unexpected install directory: $INSTALL_DIR" >&2; exit 1; }

echo 'Installing Navigator Upstream CLI'
echo "  Project: $PROJECT_ROOT"
echo "  Target:  $INSTALL_DIR"
rm -rf "$INSTALL_DIR"
mkdir -p "$INSTALL_DIR"
cp -a "$SOURCE_ROOT"/. "$INSTALL_DIR"/

VERSION=$(tr -d '\r\n' < "$INSTALL_DIR/VERSION" 2>/dev/null || true)
if [[ -n $VERSION && -d $INSTALL_DIR/lib ]]; then
  find "$INSTALL_DIR/lib" -maxdepth 1 -type f -name 'navigator-open-sdk-*.jar' ! -name "navigator-open-sdk-$VERSION.jar" -delete
fi
chmod +x "$INSTALL_DIR/navi" "$INSTALL_DIR/navi-e2e" "$INSTALL_DIR/bin/navi" "$INSTALL_DIR/bin/navi-e2e"

mkdir -p "$PROJECT_ROOT/.navigator"
PROFILE="$PROJECT_ROOT/.navigator/upstream.env"
if [[ ! -f $PROFILE ]]; then
  cat > "$PROFILE" <<'EOF'
NAVI_BASE_URL=http://localhost:8112
NAVI_TENANT_ID=
NAVI_CLIENT_APP_ID=
NAVI_CLIENT_APP_KEY=
NAVI_CLIENT_APP_SECRET=
NAVI_CLIENT_APP_ACCESS_TOKEN=
NAVI_AGENT_CODE=
NAVI_MODEL_CONFIG_ID=
NAVI_E2E_MOCK_LLM_URL=http://localhost:8200
NAVI_POLL_INTERVAL_SECONDS=4
EOF
fi
touch "$PROJECT_ROOT/.gitignore"
grep -qxF '.navigator/upstream.env' "$PROJECT_ROOT/.gitignore" || printf '.navigator/upstream.env\n' >> "$PROJECT_ROOT/.gitignore"
grep -qxF '.navi-upstream.env' "$PROJECT_ROOT/.gitignore" || printf '.navi-upstream.env\n' >> "$PROJECT_ROOT/.gitignore"

if [[ -n $RELEASE_BASE_URL ]]; then
  printf '%s' "$RELEASE_BASE_URL" > "$INSTALL_DIR/RELEASE_URL"
fi
if [[ -n $RELEASE_MANIFEST_PATH ]]; then
  [[ -f $RELEASE_MANIFEST_PATH ]] || { echo "ReleaseManifestPath does not exist: $RELEASE_MANIFEST_PATH" >&2; exit 2; }
  cp "$RELEASE_MANIFEST_PATH" "$INSTALL_DIR/RELEASE_MANIFEST.json"
fi

echo 'Installed.'
echo "  Config:  $PROFILE"
echo '  Command: ./tools/navigator-upstream/navi upstream config check'
echo '  E2E:     ./tools/navigator-upstream/navi-e2e config check'
[[ $UPGRADE == true ]] && echo '  Upgrade complete.'
