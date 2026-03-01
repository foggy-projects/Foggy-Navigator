#!/usr/bin/env bash
# Code Server - Linux Installation Script (for production Worker machines)
# Usage: bash install-linux.sh [--port PORT] [--install-dir DIR] [--data-dir DIR]
#
# Installs code-server standalone to the specified directory.
# Default port: 18443 (avoids conflict with local dev 8443)

set -euo pipefail

# ---- Defaults ----
VERSION="4.109.2"
PORT=18443
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

echo "=== Code Server Installation ==="
echo "Version:     $VERSION"
echo "Port:        $PORT"
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
    echo "Upgrading to $VERSION..."
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

# ---- Create config ----
mkdir -p "$DATA_DIR"
CONFIG_FILE="$DATA_DIR/config.yaml"

cat > "$CONFIG_FILE" << YAML
bind-addr: 0.0.0.0:${PORT}
auth: password
password: ${PASSWORD}
cert: false
YAML

echo "Config written to $CONFIG_FILE"

# ---- Create systemd service (optional) ----
SERVICE_FILE="$DATA_DIR/code-server.service"

cat > "$SERVICE_FILE" << UNIT
[Unit]
Description=Code Server (Web VS Code)
After=network.target

[Service]
Type=exec
Environment=XDG_DATA_HOME=${DATA_DIR}
ExecStart=${INSTALL_DIR}/bin/code-server --config ${CONFIG_FILE}
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
UNIT

echo "Systemd unit file written to $SERVICE_FILE"
echo ""

# ---- Create start/stop scripts ----
cat > "$INSTALL_DIR/start.sh" << 'SCRIPT'
#!/usr/bin/env bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DATA_DIR="__DATA_DIR__"
CONFIG="$DATA_DIR/config.yaml"
PORT=$(grep bind-addr "$CONFIG" | grep -oP ':\K\d+')

# Check if already running
if pgrep -f "code-server.*--config.*$CONFIG" > /dev/null 2>&1; then
  echo "Code Server already running on port $PORT"
  exit 0
fi

echo "Starting Code Server on port $PORT..."
export XDG_DATA_HOME="$DATA_DIR"
nohup "$SCRIPT_DIR/bin/code-server" --config "$CONFIG" "$@" > "$DATA_DIR/code-server.log" 2>&1 &
sleep 2

if pgrep -f "code-server.*--config.*$CONFIG" > /dev/null 2>&1; then
  echo "Code Server started: http://0.0.0.0:$PORT"
else
  echo "Failed to start. Check: $DATA_DIR/code-server.log"
  exit 1
fi
SCRIPT

sed -i "s|__DATA_DIR__|${DATA_DIR}|g" "$INSTALL_DIR/start.sh"
chmod +x "$INSTALL_DIR/start.sh"

cat > "$INSTALL_DIR/stop.sh" << 'SCRIPT'
#!/usr/bin/env bash
DATA_DIR="__DATA_DIR__"
CONFIG="$DATA_DIR/config.yaml"

echo "Stopping Code Server..."
pkill -f "code-server.*--config.*$CONFIG" 2>/dev/null
sleep 1

if pgrep -f "code-server.*--config.*$CONFIG" > /dev/null 2>&1; then
  pkill -9 -f "code-server.*--config.*$CONFIG" 2>/dev/null
fi
echo "Code Server stopped."
SCRIPT

sed -i "s|__DATA_DIR__|${DATA_DIR}|g" "$INSTALL_DIR/stop.sh"
chmod +x "$INSTALL_DIR/stop.sh"

echo ""
echo "=== Installation Complete ==="
echo ""
echo "Quick start:"
echo "  $INSTALL_DIR/start.sh /path/to/your/project"
echo ""
echo "Stop:"
echo "  $INSTALL_DIR/stop.sh"
echo ""
echo "Systemd (user-level, auto-start on boot):"
echo "  mkdir -p ~/.config/systemd/user"
echo "  cp $SERVICE_FILE ~/.config/systemd/user/code-server.service"
echo "  systemctl --user daemon-reload"
echo "  systemctl --user enable --now code-server"
echo ""
echo "Nginx reverse proxy snippet (path: /code/):"
echo '  location /code/ {'
echo "    proxy_pass http://127.0.0.1:${PORT}/;"
echo '    proxy_set_header Host $host;'
echo '    proxy_set_header Upgrade $http_upgrade;'
echo '    proxy_set_header Connection "upgrade";'
echo '    proxy_set_header X-Real-IP $remote_addr;'
echo '    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;'
echo '    proxy_set_header X-Forwarded-Proto $scheme;'
echo '  }'
echo ""
echo "frpc tunnel snippet (add to your frpc.toml):"
echo "  [[proxies]]"
echo "  name = \"code-server-$(hostname)\""
echo "  type = \"tcp\""
echo "  localIP = \"127.0.0.1\""
echo "  localPort = ${PORT}"
echo "  remotePort = ${PORT}  # adjust if port conflict"
echo ""
echo "Then set codeServerUrl in Worker settings to: http://<frps-host>:${PORT}"
