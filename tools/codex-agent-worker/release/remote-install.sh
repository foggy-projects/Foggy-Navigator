#!/bin/bash
# Codex Agent Worker - Remote Bootstrap Installer (Linux / macOS)
# Hosted on OBS and run via: curl -sSL <url>/install.sh | bash

set -e

RELEASE_BASE_URL="__RELEASE_BASE_URL__"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; NC='\033[0m'

echo -e "${CYAN}=== Codex Agent Worker - Remote Installer ===${NC}"
echo ""

if ! command -v node >/dev/null 2>&1; then
    echo -e "${RED}ERROR: Node.js 20+ is required to validate release metadata.${NC}"
    exit 1
fi

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

VERSION=$(printf '%s' "$LATEST_JSON" | node -e '
let raw=""; process.stdin.on("data", chunk => raw += chunk); process.stdin.on("end", () => {
  const manifest = JSON.parse(raw)
  if (manifest.schemaVersion !== 1 || manifest.product !== "codex-agent-worker") process.exit(2)
  process.stdout.write(String(manifest.version || ""))
})')
if [ -z "$VERSION" ]; then
    echo -e "${RED}ERROR: Could not validate version/product/schema from latest.json${NC}"
    exit 1
fi

echo -e "${GREEN}Latest version: $VERSION${NC}"

case "$(uname -s)" in
    Linux*) OS_TAG="linux" ;;
    Darwin*) OS_TAG="macos" ;;
    *) OS_TAG="linux" ;;
esac

FILE_PATH=$(printf '%s' "$LATEST_JSON" | OS_TAG="$OS_TAG" node -e '
let raw=""; process.stdin.on("data", chunk => raw += chunk); process.stdin.on("end", () => {
  const manifest = JSON.parse(raw)
  process.stdout.write(String(manifest.files?.[process.env.OS_TAG] || ""))
})')
EXPECTED_SHA256=$(printf '%s' "$LATEST_JSON" | OS_TAG="$OS_TAG" node -e '
let raw=""; process.stdin.on("data", chunk => raw += chunk); process.stdin.on("end", () => {
  const manifest = JSON.parse(raw)
  process.stdout.write(String(manifest.sha256?.[process.env.OS_TAG] || ""))
})')
EXPECTED_BYTES=$(printf '%s' "$LATEST_JSON" | OS_TAG="$OS_TAG" node -e '
let raw=""; process.stdin.on("data", chunk => raw += chunk); process.stdin.on("end", () => {
  const manifest = JSON.parse(raw)
  process.stdout.write(String(manifest.bytes?.[process.env.OS_TAG] || ""))
})')
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

ACTUAL_BYTES=$(wc -c < "$ARCHIVE_FILE" | tr -d ' ')
if [ -z "$EXPECTED_BYTES" ] || [ "$ACTUAL_BYTES" != "$EXPECTED_BYTES" ]; then
    echo -e "${RED}ERROR: Release archive size mismatch (expected $EXPECTED_BYTES, got $ACTUAL_BYTES).${NC}"
    exit 1
fi
if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL_SHA256=$(sha256sum "$ARCHIVE_FILE" | awk '{print $1}')
elif command -v shasum >/dev/null 2>&1; then
    ACTUAL_SHA256=$(shasum -a 256 "$ARCHIVE_FILE" | awk '{print $1}')
else
    echo -e "${RED}ERROR: sha256sum or shasum is required.${NC}"
    exit 1
fi
if [ -z "$EXPECTED_SHA256" ] || [ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]; then
    echo -e "${RED}ERROR: Release archive SHA-256 mismatch.${NC}"
    exit 1
fi
echo -e "${GREEN}Release archive integrity verified.${NC}"

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
