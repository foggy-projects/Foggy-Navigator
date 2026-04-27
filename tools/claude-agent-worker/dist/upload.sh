#!/bin/bash
# Claude Agent Worker — Upload to Huawei Cloud OBS
# Uploads release archives + latest.json + bootstrap scripts to OBS.
#
# Usage:
#   bash dist/upload.sh
#   bash dist/upload.sh 0.2.0    # Specify version explicitly
#   bash dist/upload.sh 0.2.0 --allow-same-version
#
# Prerequisites:
#   - obsutil installed and configured
#   - .env with RELEASE_OBS_BUCKET and RELEASE_BASE_URL (in worker root)
#   - Archives already built in dist/output/

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WORKER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT_DIR="$SCRIPT_DIR/output"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; GRAY='\033[0;37m'; NC='\033[0m'

ALLOW_SAME_VERSION=false
VERSION=""
while [ "$#" -gt 0 ]; do
    case "$1" in
        --allow-same-version)
            ALLOW_SAME_VERSION=true
            ;;
        *)
            if [ -z "$VERSION" ]; then
                VERSION="$1"
            else
                echo -e "${RED}ERROR: Unexpected argument: $1${NC}"
                exit 1
            fi
            ;;
    esac
    shift
done

compare_semver() {
    local left="${1%%[-+]*}"
    local right="${2%%[-+]*}"

    if echo "$left" | grep -Eq '^[0-9]+(\.[0-9]+){0,3}$' && echo "$right" | grep -Eq '^[0-9]+(\.[0-9]+){0,3}$'; then
        local l1 l2 l3 r1 r2 r3
        IFS=. read -r l1 l2 l3 _ <<EOF
$left
EOF
        IFS=. read -r r1 r2 r3 _ <<EOF
$right
EOF
        l1=${l1:-0}; l2=${l2:-0}; l3=${l3:-0}
        r1=${r1:-0}; r2=${r2:-0}; r3=${r3:-0}

        for part in 1 2 3; do
            local lvar="l${part}"
            local rvar="r${part}"
            local lvalue="${!lvar}"
            local rvalue="${!rvar}"
            if [ "$lvalue" -gt "$rvalue" ]; then echo 1; return; fi
            if [ "$lvalue" -lt "$rvalue" ]; then echo -1; return; fi
        done
        echo 0
        return
    fi

    if [ "$1" = "$2" ]; then echo 0; elif [[ "$1" > "$2" ]]; then echo 1; else echo -1; fi
}

# --- Load .env from worker root ------------------------------------------
DOT_ENV="$WORKER_DIR/.env"
if [ ! -f "$DOT_ENV" ]; then
    echo -e "${RED}ERROR: .env not found at $WORKER_DIR${NC}"
    echo -e "${YELLOW}Copy .env.example to .env and fill in RELEASE_OBS_BUCKET / RELEASE_BASE_URL.${NC}"
    exit 1
fi

OBS_BUCKET=$(grep "^RELEASE_OBS_BUCKET=" "$DOT_ENV" | cut -d= -f2- | tr -d '[:space:]')
BASE_URL=$(grep "^RELEASE_BASE_URL=" "$DOT_ENV" | cut -d= -f2- | tr -d '[:space:]')

if [ -z "$OBS_BUCKET" ] || [ -z "$BASE_URL" ]; then
    echo -e "${RED}ERROR: RELEASE_OBS_BUCKET and RELEASE_BASE_URL must be set in .env${NC}"
    echo -e "${YELLOW}See .env.example for reference.${NC}"
    exit 1
fi

# --- Check obsutil --------------------------------------------------------
if ! command -v obsutil &>/dev/null; then
    echo -e "${RED}ERROR: obsutil not found in PATH.${NC}"
    echo -e "${YELLOW}Install: https://support.huaweicloud.com/utiltg-obs/obs_11_0003.html${NC}"
    exit 1
fi

# --- Determine version ----------------------------------------------------
if [ -z "$VERSION" ]; then
    VERSION=$(grep '__version__' "$WORKER_DIR/src/agent_worker/__init__.py" | sed 's/.*"\(.*\)".*/\1/')
fi

echo -e "${CYAN}=== Claude Agent Worker — OBS Upload ===${NC}"
echo -e "${CYAN}Version:  $VERSION${NC}"
echo -e "${CYAN}Bucket:   $OBS_BUCKET${NC}"
echo -e "${CYAN}Base URL: $BASE_URL${NC}"
echo ""

# --- Guard against publishing the same or older version -------------------
if [ "$ALLOW_SAME_VERSION" != true ]; then
    REMOTE_JSON=$(curl -fsSL -H "Cache-Control: no-cache" "$BASE_URL/latest.json?ts=$(date +%s)" 2>/dev/null || true)
    REMOTE_VERSION=$(echo "$REMOTE_JSON" | sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1)

    if [ -n "$REMOTE_VERSION" ]; then
        COMPARISON=$(compare_semver "$VERSION" "$REMOTE_VERSION")
        if [ "$COMPARISON" = "0" ]; then
            echo -e "${RED}ERROR: Remote latest is already version $REMOTE_VERSION. Bump the version before uploading.${NC}"
            echo -e "${YELLOW}Suggested: bash dist/package.sh all --bump patch --upload${NC}"
            echo -e "${YELLOW}Override only when repairing metadata: add --allow-same-version to upload.sh.${NC}"
            exit 1
        elif [ "$COMPARISON" = "-1" ]; then
            echo -e "${RED}ERROR: Local version $VERSION is older than remote latest $REMOTE_VERSION.${NC}"
            exit 1
        else
            echo -e "${GRAY}Remote latest version: $REMOTE_VERSION -> uploading $VERSION${NC}"
        fi
    else
        echo -e "${YELLOW}WARNING: Could not read remote latest.json, continuing upload.${NC}"
    fi
fi

# --- Check archives exist ------------------------------------------------
if [ ! -d "$OUTPUT_DIR" ]; then
    echo -e "${RED}ERROR: dist/output/ directory not found. Run package.sh first.${NC}"
    exit 1
fi

ARCHIVES=$(find "$OUTPUT_DIR" -maxdepth 1 -name "claude-worker-${VERSION}-*" -type f 2>/dev/null)
if [ -z "$ARCHIVES" ]; then
    echo -e "${RED}ERROR: No archives found for version $VERSION in dist/output/${NC}"
    echo -e "${YELLOW}Available files:${NC}"
    ls -la "$OUTPUT_DIR/" 2>/dev/null || true
    exit 1
fi

# --- Upload archives ------------------------------------------------------
echo -e "${CYAN}Uploading archives...${NC}"
for archive in $ARCHIVES; do
    filename=$(basename "$archive")
    obs_path="$OBS_BUCKET/$VERSION/$filename"
    echo -e "  ${GRAY}$filename -> $obs_path${NC}"
    obsutil cp "$archive" "$obs_path" -f
done

# --- Generate and upload latest.json -------------------------------------
echo -e "${CYAN}Generating latest.json...${NC}"

# Build files map
FILES_WIN=""; FILES_LINUX=""; FILES_MACOS=""
for archive in $ARCHIVES; do
    filename=$(basename "$archive")
    case "$filename" in
        *-windows.*) FILES_WIN="$VERSION/$filename";;
        *-linux.*)   FILES_LINUX="$VERSION/$filename";;
        *-macos.*)   FILES_MACOS="$VERSION/$filename";;
    esac
done

LATEST_JSON=$(cat <<ENDJSON
{
  "version": "$VERSION",
  "released": "$(date +%Y-%m-%d)",
  "files": {
    "windows": "$FILES_WIN",
    "linux": "$FILES_LINUX",
    "macos": "$FILES_MACOS"
  }
}
ENDJSON
)

LATEST_JSON_PATH="$OUTPUT_DIR/latest.json"
echo "$LATEST_JSON" > "$LATEST_JSON_PATH"
echo -e "  ${GRAY}$LATEST_JSON${NC}"

obsutil cp "$LATEST_JSON_PATH" "$OBS_BUCKET/latest.json" -f

# --- Upload bootstrap install scripts ------------------------------------
echo -e "${CYAN}Uploading bootstrap install scripts...${NC}"

# remote-install.sh → upload as install.sh
if [ -f "$SCRIPT_DIR/remote-install.sh" ]; then
    TMP_BASH="$OUTPUT_DIR/install.sh"
    sed "s|RELEASE_BASE_URL=\"[^\"]*\"|RELEASE_BASE_URL=\"$BASE_URL\"|" \
        "$SCRIPT_DIR/remote-install.sh" > "$TMP_BASH"
    obsutil cp "$TMP_BASH" "$OBS_BUCKET/install.sh" -f
    echo -e "  ${GRAY}install.sh uploaded${NC}"
fi

# remote-install.ps1 → upload as install.ps1
if [ -f "$SCRIPT_DIR/remote-install.ps1" ]; then
    TMP_PS1="$OUTPUT_DIR/install.ps1"
    sed "s|\\\$ReleaseBaseUrl = \"[^\"]*\"|\$ReleaseBaseUrl = \"$BASE_URL\"|" \
        "$SCRIPT_DIR/remote-install.ps1" > "$TMP_PS1"
    obsutil cp "$TMP_PS1" "$OBS_BUCKET/install.ps1" -f
    echo -e "  ${GRAY}install.ps1 uploaded${NC}"
fi

# --- Done -----------------------------------------------------------------
echo ""
echo -e "${GREEN}=== Upload Complete ===${NC}"
echo ""
echo -e "${CYAN}Remote install commands:${NC}"
echo -e "  Linux/Mac:  curl -sSL $BASE_URL/install.sh | bash"
echo -e "  Windows:    irm $BASE_URL/install.ps1 | iex"
echo ""
echo -e "  Upgrade:    claude-worker upgrade"
