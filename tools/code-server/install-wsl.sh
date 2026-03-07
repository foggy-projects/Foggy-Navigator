#!/usr/bin/env bash
# Code Server - WSL Installation Script
# Usage: wsl -d Ubuntu-24.04 -- bash /mnt/d/foggy-projects/Foggy-Navigator/tools/code-server/install-wsl.sh
#
# Installs code-server to D: drive (persists across WSL reinstalls).
# Default port: 8443

set -euo pipefail

# ---- Configuration ----
VERSION="4.109.2"
INSTALL_DIR="/mnt/d/foggy-tools/code-server"
DATA_DIR="/mnt/d/foggy-tools/code-server-data"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "=== Code Server WSL Installation ==="
echo "Version:     $VERSION"
echo "Install dir: $INSTALL_DIR"
echo "Data dir:    $DATA_DIR"
echo ""

# ---- Download & extract ----
ARCHIVE="code-server-${VERSION}-linux-amd64.tar.gz"
URL="https://github.com/coder/code-server/releases/download/v${VERSION}/${ARCHIVE}"

if [ -x "$INSTALL_DIR/bin/code-server" ]; then
  CURRENT=$("$INSTALL_DIR/bin/code-server" --version 2>/dev/null | head -1 || echo "unknown")
  echo "Existing installation found: $CURRENT"
  if [ "$CURRENT" = "$VERSION" ]; then
    echo "Already at version $VERSION, skipping download."
  else
    echo "Upgrading from $CURRENT to $VERSION..."
    rm -rf "$INSTALL_DIR"
  fi
fi

if [ ! -x "$INSTALL_DIR/bin/code-server" ]; then
  echo "Downloading code-server v${VERSION}..."
  TMP_DIR=$(mktemp -d)
  curl -fSL "$URL" -o "$TMP_DIR/$ARCHIVE"

  echo "Extracting..."
  mkdir -p "$INSTALL_DIR"
  tar -xzf "$TMP_DIR/$ARCHIVE" -C "$INSTALL_DIR" --strip-components=1

  rm -rf "$TMP_DIR"
  echo "Installed to $INSTALL_DIR"
fi

# ---- Create data directory ----
mkdir -p "$DATA_DIR"

# ---- Verify ----
echo ""
echo "=== Installation Complete ==="
echo ""
"$INSTALL_DIR/bin/code-server" --version
echo ""
echo "Install dir: $INSTALL_DIR"
echo "Data dir:    $DATA_DIR"
echo "Config:      $SCRIPT_DIR/config.yaml"
echo ""
echo "Start from PowerShell:"
echo "  powershell -ExecutionPolicy Bypass -File $SCRIPT_DIR/start.ps1"
