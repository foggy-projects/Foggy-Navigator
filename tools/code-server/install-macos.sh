#!/usr/bin/env bash
# Code Server - macOS Installation Script
# Usage: bash install-macos.sh [--port PORT] [--install-dir DIR] [--data-dir DIR]
#
# Installs code-server standalone to the specified directory.
# Default port: 8443 (local development)

set -euo pipefail

# ---- Defaults ----
VERSION="4.95.3"  # Latest stable version
PORT=8443
INSTALL_DIR="$HOME/.local/lib/code-server"
DATA_DIR="$HOME/.local/share/code-server"
PASSWORD="foggy123"

# ---- Parse args ----
while [[ $# -gt 0 ]]; do
  case $1 in
    --port) PORT="$2"; shift 2 ;;
    --install-dir) INSTALL_DIR="$2"; shift 2 ;;
    --data-dir) DATA_DIR="$2"; shift 2 ;;
    --password) PASSWORD="$2"; shift 2 ;;
    --version) VERSION="$2"; shift 2 ;;
    -h|--help)
      echo "Usage: $0 [--port PORT] [--install-dir DIR] [--data-dir DIR] [--password PWD] [--version VER]"
      exit 0
      ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

echo "=== Code Server Installation for macOS ==="
echo "Version:     $VERSION"
echo "Port:        $PORT"
echo "Install dir: $INSTALL_DIR"
echo "Data dir:    $DATA_DIR"
echo ""

# ---- Detect architecture ----
ARCH=$(uname -m)
if [ "$ARCH" = "arm64" ]; then
  PLATFORM="macos-arm64"
elif [ "$ARCH" = "x86_64" ]; then
  PLATFORM="macos-amd64"
else
  echo "Unsupported architecture: $ARCH"
  exit 1
fi

echo "Detected architecture: $ARCH ($PLATFORM)"

# ---- Check if already installed ----
if [ -x "$INSTALL_DIR/bin/code-server" ]; then
  CURRENT=$("$INSTALL_DIR/bin/code-server" --version 2>/dev/null | head -1 || echo "unknown")
  echo "Existing installation found: $CURRENT"
  if [ "$CURRENT" = "$VERSION" ]; then
    echo "Already at version $VERSION, skipping download."
  else
    echo "Upgrading to $VERSION..."
    rm -rf "$INSTALL_DIR"
  fi
fi

# ---- Download & extract ----
if [ ! -x "$INSTALL_DIR/bin/code-server" ]; then
  ARCHIVE="code-server-${VERSION}-${PLATFORM}.tar.gz"
  URL="https://github.com/coder/code-server/releases/download/v${VERSION}/${ARCHIVE}"

  echo "Downloading code-server v${VERSION} for ${PLATFORM}..."
  TMP_DIR=$(mktemp -d)

  if ! curl -fSL "$URL" -o "$TMP_DIR/$ARCHIVE" 2>/dev/null; then
    echo "Failed to download. Trying alternative method..."
    # Try brew as fallback
    if command -v brew >/dev/null 2>&1; then
      echo "Installing via Homebrew..."
      brew install code-server
      BREW_PREFIX=$(brew --prefix)
      INSTALL_DIR="$BREW_PREFIX/opt/code-server"
      echo "Installed via brew to $INSTALL_DIR"
    else
      echo "Download failed and brew not available. Please install manually."
      exit 1
    fi
  else
    echo "Extracting..."
    mkdir -p "$INSTALL_DIR"
    tar -xzf "$TMP_DIR/$ARCHIVE" -C "$INSTALL_DIR" --strip-components=1
    rm -rf "$TMP_DIR"
    echo "Installed to $INSTALL_DIR"
  fi
fi

# ---- Create config ----
mkdir -p "$DATA_DIR"
CONFIG_FILE="$DATA_DIR/config.yaml"

cat > "$CONFIG_FILE" << YAML
bind-addr: 127.0.0.1:${PORT}
auth: password
password: ${PASSWORD}
cert: false
YAML

echo "Config written to $CONFIG_FILE"

# ---- Create LaunchAgent plist (optional, for auto-start) ----
PLIST_FILE="$DATA_DIR/com.coder.code-server.plist"

cat > "$PLIST_FILE" << PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>com.coder.code-server</string>
  <key>ProgramArguments</key>
  <array>
    <string>${INSTALL_DIR}/bin/code-server</string>
    <string>--config</string>
    <string>${CONFIG_FILE}</string>
  </array>
  <key>EnvironmentVariables</key>
  <dict>
    <key>XDG_DATA_HOME</key>
    <string>${DATA_DIR}</string>
  </dict>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <dict>
    <key>SuccessfulExit</key>
    <false/>
  </dict>
  <key>StandardOutPath</key>
  <string>${DATA_DIR}/code-server.log</string>
  <key>StandardErrorPath</key>
  <string>${DATA_DIR}/code-server.error.log</string>
</dict>
</plist>
PLIST

echo "LaunchAgent plist written to $PLIST_FILE"

# ---- Create start/stop scripts ----
cat > "$INSTALL_DIR/start.sh" << SCRIPT
#!/usr/bin/env bash
SCRIPT_DIR="\$(cd "\$(dirname "\$0")" && pwd)"
DATA_DIR="${DATA_DIR}"
CONFIG="\$DATA_DIR/config.yaml"
PORT=\$(grep bind-addr "\$CONFIG" | grep -oE ':[0-9]+' | cut -d: -f2)

# Check if already running
if pgrep -f "code-server.*--config.*\$CONFIG" > /dev/null 2>&1; then
  echo "Code Server already running on port \$PORT"
  exit 0
fi

echo "Starting Code Server on port \$PORT..."
export XDG_DATA_HOME="\$DATA_DIR"
nohup "\$SCRIPT_DIR/bin/code-server" --config "\$CONFIG" "\$@" > "\$DATA_DIR/code-server.log" 2>&1 &
sleep 2

if pgrep -f "code-server.*--config.*\$CONFIG" > /dev/null 2>&1; then
  echo "Code Server started: http://127.0.0.1:\$PORT"
  echo "Password: ${PASSWORD}"
  echo "Logs: \$DATA_DIR/code-server.log"
else
  echo "Failed to start. Check: \$DATA_DIR/code-server.log"
  exit 1
fi
SCRIPT

chmod +x "$INSTALL_DIR/start.sh"

cat > "$INSTALL_DIR/stop.sh" << SCRIPT
#!/usr/bin/env bash
DATA_DIR="${DATA_DIR}"
CONFIG="\$DATA_DIR/config.yaml"

echo "Stopping Code Server..."
pkill -f "code-server.*--config.*\$CONFIG" 2>/dev/null
sleep 1

if pgrep -f "code-server.*--config.*\$CONFIG" > /dev/null 2>&1; then
  echo "Force stopping..."
  pkill -9 -f "code-server.*--config.*\$CONFIG" 2>/dev/null
fi
echo "Code Server stopped."
SCRIPT

chmod +x "$INSTALL_DIR/stop.sh"

echo ""
echo "=== Installation Complete ==="
echo ""
echo "Quick start:"
echo "  $INSTALL_DIR/start.sh"
echo "  # or with a specific project:"
echo "  $INSTALL_DIR/start.sh /path/to/your/project"
echo ""
echo "Stop:"
echo "  $INSTALL_DIR/stop.sh"
echo ""
echo "Access:"
echo "  URL: http://127.0.0.1:${PORT}"
echo "  Password: ${PASSWORD}"
echo ""
echo "Auto-start on login (optional):"
echo "  cp $PLIST_FILE ~/Library/LaunchAgents/"
echo "  launchctl load ~/Library/LaunchAgents/com.coder.code-server.plist"
echo ""
echo "Uninstall auto-start:"
echo "  launchctl unload ~/Library/LaunchAgents/com.coder.code-server.plist"
echo "  rm ~/Library/LaunchAgents/com.coder.code-server.plist"
echo ""