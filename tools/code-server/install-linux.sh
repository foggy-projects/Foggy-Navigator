#!/usr/bin/env bash
# Code Server - Linux Installation Script
# Usage: bash install-linux.sh [--from-env] [--port PORT] [--https-port PORT] [--install-dir DIR] [--data-dir DIR]
#
# Supports two scenarios:
#   1. WSL dev machine:  bash install-linux.sh --from-env
#      Reads INSTALL_DIR/DATA_DIR from .env (same dir as this script)
#   2. Remote Linux:     bash install-linux.sh [--install-dir DIR] [--data-dir DIR]
#      Uses defaults or explicit args
#
# Default port: 18443 (avoids conflict with local dev 8443/8444)
#
# HTTPS support:
#   Use --https-port to enable nginx reverse proxy with both HTTP and HTTPS.
#   A self-signed certificate is generated automatically.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ---- Defaults ----
VERSION="4.109.2"
PORT=18443
HTTPS_PORT=0  # 0 = disabled
INSTALL_DIR="$HOME/.local/lib/code-server"
DATA_DIR="$HOME/.local/share/code-server"
PASSWORD="foggy123"
FROM_ENV=false

# ---- Parse args ----
while [[ $# -gt 0 ]]; do
  case $1 in
    --from-env) FROM_ENV=true; shift ;;
    --port) PORT="$2"; shift 2 ;;
    --https-port) HTTPS_PORT="$2"; shift 2 ;;
    --install-dir) INSTALL_DIR="$2"; shift 2 ;;
    --data-dir) DATA_DIR="$2"; shift 2 ;;
    --password) PASSWORD="$2"; shift 2 ;;
    --version) VERSION="$2"; shift 2 ;;
    -h|--help)
      echo "Usage: $0 [--from-env] [--port PORT] [--https-port PORT] [--install-dir DIR] [--data-dir DIR] [--password PWD] [--version VER]"
      echo ""
      echo "  --from-env      Read INSTALL_DIR, DATA_DIR, PORT, HTTPS_PORT from .env file (for WSL dev machines)"
      echo "  --https-port    HTTPS port (enables nginx reverse proxy + self-signed cert)"
      exit 0
      ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

# ---- Load from .env if requested ----
if [ "$FROM_ENV" = true ]; then
  ENV_FILE="$SCRIPT_DIR/.env"
  if [ ! -f "$ENV_FILE" ]; then
    echo "ERROR: .env not found at $ENV_FILE"
    exit 1
  fi
  echo "Loading config from $ENV_FILE ..."
  while IFS='=' read -r key value; do
    case "$key" in
      CODE_SERVER_PORT)       PORT="$value" ;;
      CODE_SERVER_HTTPS_PORT) HTTPS_PORT="$value" ;;
      CODE_SERVER_INSTALL)    INSTALL_DIR="$value" ;;
      CODE_SERVER_DATA)       DATA_DIR="$value" ;;
    esac
  done < <(grep -v '^\s*#' "$ENV_FILE" | grep '=')
fi

# Compute internal port (code-server's actual listen port when HTTPS is enabled)
INTERNAL_PORT=$((PORT + 100))
USE_HTTPS_PROXY=false
if [ "$HTTPS_PORT" -gt 0 ] 2>/dev/null; then
  USE_HTTPS_PROXY=true
fi

echo "=== Code Server Installation ==="
echo "Version:     $VERSION"
echo "Port:        $PORT (HTTP)"
if [ "$USE_HTTPS_PROXY" = true ]; then
  echo "HTTPS port:  $HTTPS_PORT"
  echo "Internal:    $INTERNAL_PORT (code-server behind nginx)"
fi
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

if [ "$USE_HTTPS_PROXY" = true ]; then
  # Bind to localhost only — nginx handles external traffic
  BIND_ADDR="127.0.0.1:${INTERNAL_PORT}"
else
  # Direct mode — bind to all interfaces
  BIND_ADDR="0.0.0.0:${PORT}"
fi

cat > "$CONFIG_FILE" << YAML
bind-addr: ${BIND_ADDR}
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
$(if [ "$USE_HTTPS_PROXY" = true ]; then echo "Wants=nginx.service"; fi)

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
USE_HTTPS_PROXY="__USE_HTTPS_PROXY__"
SETUP_HTTPS_SCRIPT="__SETUP_HTTPS_SCRIPT__"
HTTP_PORT="__HTTP_PORT__"
HTTPS_PORT="__HTTPS_PORT__"
INTERNAL_PORT="__INTERNAL_PORT__"

# Get the port code-server actually listens on
if [ "$USE_HTTPS_PROXY" = "true" ]; then
  CS_PORT="$INTERNAL_PORT"
else
  CS_PORT=$(grep bind-addr "$CONFIG" | grep -oP ':\K\d+')
fi

# Check if already running
if pgrep -f "code-server.*--config.*$CONFIG" > /dev/null 2>&1; then
  echo "Code Server already running"
  echo "  HTTP:  http://0.0.0.0:$HTTP_PORT"
  if [ "$USE_HTTPS_PROXY" = "true" ]; then
    echo "  HTTPS: https://0.0.0.0:$HTTPS_PORT"
  fi
  exit 0
fi

echo "Starting Code Server on port $CS_PORT..."
export XDG_DATA_HOME="$DATA_DIR"
nohup "$SCRIPT_DIR/bin/code-server" --config "$CONFIG" "$@" > "$DATA_DIR/code-server.log" 2>&1 &
sleep 2

if pgrep -f "code-server.*--config.*$CONFIG" > /dev/null 2>&1; then
  echo "Code Server started."

  # Setup HTTPS proxy if configured
  if [ "$USE_HTTPS_PROXY" = "true" ] && [ -f "$SETUP_HTTPS_SCRIPT" ]; then
    echo ""
    echo "Setting up HTTPS proxy (nginx)..."
    bash "$SETUP_HTTPS_SCRIPT" \
      --http-port "$HTTP_PORT" \
      --https-port "$HTTPS_PORT" \
      --internal-port "$INTERNAL_PORT" \
      --data-dir "$DATA_DIR"
  fi

  echo ""
  echo "Access URLs:"
  echo "  HTTP:  http://0.0.0.0:$HTTP_PORT"
  if [ "$USE_HTTPS_PROXY" = "true" ]; then
    echo "  HTTPS: https://0.0.0.0:$HTTPS_PORT"
  fi
else
  echo "Failed to start. Check: $DATA_DIR/code-server.log"
  exit 1
fi
SCRIPT

sed -i "s|__DATA_DIR__|${DATA_DIR}|g" "$INSTALL_DIR/start.sh"
sed -i "s|__USE_HTTPS_PROXY__|${USE_HTTPS_PROXY}|g" "$INSTALL_DIR/start.sh"
sed -i "s|__SETUP_HTTPS_SCRIPT__|${SCRIPT_DIR}/setup-https.sh|g" "$INSTALL_DIR/start.sh"
sed -i "s|__HTTP_PORT__|${PORT}|g" "$INSTALL_DIR/start.sh"
sed -i "s|__HTTPS_PORT__|${HTTPS_PORT}|g" "$INSTALL_DIR/start.sh"
sed -i "s|__INTERNAL_PORT__|${INTERNAL_PORT}|g" "$INSTALL_DIR/start.sh"
chmod +x "$INSTALL_DIR/start.sh"

cat > "$INSTALL_DIR/stop.sh" << 'SCRIPT'
#!/usr/bin/env bash
DATA_DIR="__DATA_DIR__"
CONFIG="$DATA_DIR/config.yaml"
USE_HTTPS_PROXY="__USE_HTTPS_PROXY__"

echo "Stopping Code Server..."
pkill -f "code-server.*--config.*$CONFIG" 2>/dev/null
sleep 1

if pgrep -f "code-server.*--config.*$CONFIG" > /dev/null 2>&1; then
  pkill -9 -f "code-server.*--config.*$CONFIG" 2>/dev/null
fi

# Stop nginx if HTTPS proxy was enabled
if [ "$USE_HTTPS_PROXY" = "true" ]; then
  echo "Stopping nginx proxy..."
  sudo nginx -s stop 2>/dev/null || true
fi

echo "Code Server stopped."
SCRIPT

sed -i "s|__DATA_DIR__|${DATA_DIR}|g" "$INSTALL_DIR/stop.sh"
sed -i "s|__USE_HTTPS_PROXY__|${USE_HTTPS_PROXY}|g" "$INSTALL_DIR/stop.sh"
chmod +x "$INSTALL_DIR/stop.sh"

# ---- Setup HTTPS if configured ----
if [ "$USE_HTTPS_PROXY" = true ]; then
  echo ""
  echo "=== Setting up HTTPS ==="
  bash "$SCRIPT_DIR/setup-https.sh" \
    --http-port "$PORT" \
    --https-port "$HTTPS_PORT" \
    --internal-port "$INTERNAL_PORT" \
    --data-dir "$DATA_DIR"
fi

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
if [ "$USE_HTTPS_PROXY" = true ]; then
  echo "  # Also enable nginx for HTTPS:"
  echo "  sudo systemctl enable --now nginx"
fi
echo ""
echo "Access URLs:"
echo "  HTTP:  http://localhost:${PORT}"
if [ "$USE_HTTPS_PROXY" = true ]; then
  echo "  HTTPS: https://localhost:${HTTPS_PORT}"
fi
echo ""
if [ "$USE_HTTPS_PROXY" = true ]; then
  echo "Nginx reverse proxy (already configured by setup-https.sh):"
  echo "  HTTP  :$PORT  → code-server 127.0.0.1:$INTERNAL_PORT"
  echo "  HTTPS :$HTTPS_PORT → code-server 127.0.0.1:$INTERNAL_PORT"
  echo ""
fi
echo "frpc tunnel snippet (add to your frpc.toml):"
echo "  [[proxies]]"
echo "  name = \"code-server-$(hostname)\""
echo "  type = \"tcp\""
echo "  localIP = \"127.0.0.1\""
echo "  localPort = ${PORT}"
echo "  remotePort = ${PORT}  # adjust if port conflict"
if [ "$USE_HTTPS_PROXY" = true ]; then
  echo ""
  echo "  [[proxies]]"
  echo "  name = \"code-server-https-$(hostname)\""
  echo "  type = \"tcp\""
  echo "  localIP = \"127.0.0.1\""
  echo "  localPort = ${HTTPS_PORT}"
  echo "  remotePort = ${HTTPS_PORT}  # adjust if port conflict"
fi
echo ""
echo "Then set codeServerUrl in Worker settings to:"
echo "  Internal: http://<host>:${PORT}"
if [ "$USE_HTTPS_PROXY" = true ]; then
  echo "  HTTPS:    https://<host>:${HTTPS_PORT}"
fi
