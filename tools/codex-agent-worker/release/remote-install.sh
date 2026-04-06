#!/bin/bash
# Codex Agent Worker - Remote Bootstrap Installer (Linux / macOS)
# Hosted on OBS and run via: curl -sSL <url>/install.sh | bash

set -e

RELEASE_BASE_URL="__RELEASE_BASE_URL__"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; NC='\033[0m'

echo -e "${CYAN}=== Codex Agent Worker - Remote Installer ===${NC}"
echo ""

if [ "$RELEASE_BASE_URL" = "__RELEASE_BASE_URL__" ] || [ -z "$RELEASE_BASE_URL" ]; then
    echo -e "${RED}ERROR: This script has not been configured with a release URL.${NC}"
    echo -e "${YELLOW}The upload.ps1 script should inject RELEASE_BASE_URL before uploading.${NC}"
    exit 1
fi

echo -e "${CYAN}Fetching latest version info...${NC}"
LATEST_JSON=$(curl -sS --fail "$RELEASE_BASE_URL/latest.json" 2>/dev/null || echo "")
if [ -z "$LATEST_JSON" ]; then
    echo -e "${RED}ERROR: Could not fetch $RELEASE_BASE_URL/latest.json${NC}"
    exit 1
fi

VERSION=$(echo "$LATEST_JSON" | grep '"version"' | head -1 | sed 's/.*"\([0-9][^"]*\)".*/\1/')
if [ -z "$VERSION" ]; then
    echo -e "${RED}ERROR: Could not parse version from latest.json${NC}"
    exit 1
fi

echo -e "${GREEN}Latest version: $VERSION${NC}"

case "$(uname -s)" in
    Linux*) OS_TAG="linux" ;;
    Darwin*) OS_TAG="macos" ;;
    *) OS_TAG="linux" ;;
esac

FILE_PATH=$(echo "$LATEST_JSON" | grep "\"$OS_TAG\"" | head -1 | sed 's/.*"\([^"]*codex-worker[^"]*\)".*/\1/')
if [ -z "$FILE_PATH" ]; then
    echo -e "${RED}ERROR: No release found for $OS_TAG in latest.json${NC}"
    exit 1
fi

DOWNLOAD_URL="$RELEASE_BASE_URL/$FILE_PATH"
echo -e "${CYAN}Downloading: $DOWNLOAD_URL${NC}"

TMPDIR=$(mktemp -d)
trap "rm -rf '$TMPDIR'" EXIT

ARCHIVE_FILE="$TMPDIR/$(basename "$FILE_PATH")"
curl -sSL --fail -o "$ARCHIVE_FILE" "$DOWNLOAD_URL"

echo -e "${CYAN}Extracting...${NC}"
if [[ "$ARCHIVE_FILE" == *.zip ]]; then
    unzip -q "$ARCHIVE_FILE" -d "$TMPDIR"
else
    tar xzf "$ARCHIVE_FILE" -C "$TMPDIR"
fi

INSTALL_SCRIPT=$(find "$TMPDIR" -name "install.sh" -maxdepth 2 | head -1)
if [ -z "$INSTALL_SCRIPT" ]; then
    echo -e "${RED}ERROR: No install.sh found in archive${NC}"
    exit 1
fi

chmod +x "$INSTALL_SCRIPT"
export CODEX_WORKER_URL="$RELEASE_BASE_URL"

bash "$INSTALL_SCRIPT"
