#!/bin/bash
# Gemini Agent Worker - Installer (Linux / macOS)
# Run from inside the extracted release archive.
#
# Usage:
#   ./install.sh
#   ./install.sh --upgrade

set -e

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; NC='\033[0m'

install_cli_link() {
    local cli_name="$1"
    local cli_source="$2"
    local target_dir=""

    for candidate in "$HOME/.local/bin" "$HOME/bin"; do
        if echo ":$PATH:" | grep -q ":$candidate:"; then
            mkdir -p "$candidate"
            if [ -w "$candidate" ]; then
                target_dir="$candidate"
                break
            fi
        fi
    done

    if [ -z "$target_dir" ]; then
        local old_ifs="$IFS"
        IFS=':'
        for dir in $PATH; do
            [ -n "$dir" ] || continue
            if [ -d "$dir" ] && [ -w "$dir" ]; then
                target_dir="$dir"
                break
            fi
        done
        IFS="$old_ifs"
    fi

    if [ -z "$target_dir" ]; then
        target_dir="$HOME/.local/bin"
        mkdir -p "$target_dir"
    fi

    local target_path="$target_dir/$cli_name"
    if ln -snf "$cli_source" "$target_path" 2>/dev/null; then
        :
    else
        cp "$cli_source" "$target_path"
        chmod +x "$target_path"
    fi

    echo "$target_path"
}

INSTALL_DIR="${GEMINI_WORKER_HOME:-$HOME/.gemini-worker}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VERSION=$(cat "$SCRIPT_DIR/VERSION" 2>/dev/null || echo "unknown")

IS_UPGRADE=false
if [ "$1" = "--upgrade" ] || [ -f "$INSTALL_DIR/VERSION" ]; then
    IS_UPGRADE=true
    OLD_VERSION=$(cat "$INSTALL_DIR/VERSION" 2>/dev/null || echo "unknown")
    echo -e "${CYAN}Upgrading Gemini Agent Worker: ${OLD_VERSION} -> ${VERSION}${NC}"
else
    echo -e "${CYAN}Installing Gemini Agent Worker v${VERSION}${NC}"
fi
echo -e "${CYAN}Install directory: $INSTALL_DIR${NC}"
echo ""

if ! command -v node >/dev/null 2>&1; then
    echo -e "${RED}ERROR: Node.js is required but was not found in PATH.${NC}"
    echo -e "${YELLOW}Install Node.js 20+ from https://nodejs.org/${NC}"
    exit 1
fi
echo -e "${GREEN}Node: $(node --version)${NC}"

if ! command -v gemini >/dev/null 2>&1; then
    echo -e "${YELLOW}WARNING: Gemini CLI not found in PATH.${NC}"
    echo -e "${YELLOW}The worker can still start, but subscription login mode requires 'gemini'.${NC}"
    echo ""
fi

ENV_BACKUP=""
if [ "$IS_UPGRADE" = true ] && [ -f "$INSTALL_DIR/.env" ]; then
    ENV_BACKUP=$(mktemp)
    cp "$INSTALL_DIR/.env" "$ENV_BACKUP"
    echo -e "${CYAN}Backed up existing .env${NC}"
fi

if [ "$IS_UPGRADE" = true ] && [ -f "$INSTALL_DIR/stop.sh" ]; then
    echo -e "${YELLOW}Stopping running worker...${NC}"
    bash "$INSTALL_DIR/stop.sh" 2>/dev/null || true
    echo ""
fi

mkdir -p "$INSTALL_DIR"

echo -e "${CYAN}Copying files...${NC}"
rm -rf "$INSTALL_DIR/dist" "$INSTALL_DIR/docs" "$INSTALL_DIR/bin"

cp -r "$SCRIPT_DIR/dist" "$INSTALL_DIR/"
cp "$SCRIPT_DIR/package.json" "$INSTALL_DIR/"
cp "$SCRIPT_DIR/package-lock.json" "$INSTALL_DIR/"
cp "$SCRIPT_DIR/.env.example" "$INSTALL_DIR/"
cp "$SCRIPT_DIR/VERSION" "$INSTALL_DIR/"

if [ -d "$SCRIPT_DIR/docs" ]; then
    cp -r "$SCRIPT_DIR/docs" "$INSTALL_DIR/"
fi

for f in start.sh stop.sh start.ps1 stop.ps1 install.sh install.ps1; do
    if [ -f "$SCRIPT_DIR/$f" ]; then
        cp "$SCRIPT_DIR/$f" "$INSTALL_DIR/"
    fi
done

mkdir -p "$INSTALL_DIR/bin"
cp "$SCRIPT_DIR/bin/gemini-worker" "$INSTALL_DIR/bin/"
chmod +x "$INSTALL_DIR/bin/gemini-worker"
if [ -f "$SCRIPT_DIR/bin/gemini-worker.ps1" ]; then
    cp "$SCRIPT_DIR/bin/gemini-worker.ps1" "$INSTALL_DIR/bin/"
fi

if [ -n "$ENV_BACKUP" ] && [ -f "$ENV_BACKUP" ]; then
    cp "$ENV_BACKUP" "$INSTALL_DIR/.env"
    rm -f "$ENV_BACKUP"
    echo -e "${GREEN}Restored .env from backup${NC}"
elif [ ! -f "$INSTALL_DIR/.env" ]; then
    cp "$SCRIPT_DIR/.env.example" "$INSTALL_DIR/.env"
    echo -e "${YELLOW}Created .env from template${NC}"
    echo -e "${YELLOW}  -> Please edit: $INSTALL_DIR/.env${NC}"
fi

if [ -n "$GEMINI_WORKER_URL" ]; then
    ENV_FILE="$INSTALL_DIR/.env"
    if grep -q "^GEMINI_WORKER_URL=" "$ENV_FILE" 2>/dev/null; then
        sed -i.bak "s|^GEMINI_WORKER_URL=.*|GEMINI_WORKER_URL=$GEMINI_WORKER_URL|" "$ENV_FILE"
        rm -f "${ENV_FILE}.bak"
    else
        {
            echo ""
            echo "# Auto-upgrade URL (set by remote installer)"
            echo "GEMINI_WORKER_URL=$GEMINI_WORKER_URL"
        } >> "$ENV_FILE"
    fi
    echo -e "${GREEN}Saved GEMINI_WORKER_URL to .env${NC}"
fi

echo ""
echo -e "${CYAN}Installing runtime dependencies...${NC}"
cd "$INSTALL_DIR"
rm -rf node_modules

if [ -f package-lock.json ]; then
    npm ci --omit=dev
else
    npm install --omit=dev
fi

mkdir -p "$INSTALL_DIR/logs"

echo ""
BIN_DIR="$INSTALL_DIR/bin"
ACTIVE_CLI_PATH=$(install_cli_link "gemini-worker" "$INSTALL_DIR/bin/gemini-worker")
ACTIVE_CLI_DIR=$(dirname "$ACTIVE_CLI_PATH")
SHELL_RC=""
case "$SHELL" in
    */zsh) SHELL_RC="$HOME/.zshrc" ;;
    */bash) SHELL_RC="$HOME/.bashrc" ;;
    *) SHELL_RC="$HOME/.profile" ;;
esac

echo -e "${GREEN}CLI shim: $ACTIVE_CLI_PATH${NC}"

PATH_LINE="export PATH=\"$ACTIVE_CLI_DIR:\$PATH\""
if ! echo ":$PATH:" | grep -q ":$ACTIVE_CLI_DIR:"; then
    if [ -n "$SHELL_RC" ] && ! grep -qF "$ACTIVE_CLI_DIR" "$SHELL_RC" 2>/dev/null; then
        echo -e "${YELLOW}To add 'gemini-worker' to your PATH, run:${NC}"
        echo -e "  echo '$PATH_LINE' >> $SHELL_RC && source $SHELL_RC"
        echo ""
    fi
fi

echo -e "${GREEN}============================================${NC}"
if [ "$IS_UPGRADE" = true ]; then
    echo -e "${GREEN}Gemini Agent Worker upgraded to v${VERSION}${NC}"
else
    echo -e "${GREEN}Gemini Agent Worker v${VERSION} installed!${NC}"
fi
echo -e "${GREEN}============================================${NC}"
echo ""
echo -e "Commands:"
echo -e "  ${CYAN}gemini-worker start${NC}     Start the worker"
echo -e "  ${CYAN}gemini-worker stop${NC}      Stop the worker"
echo -e "  ${CYAN}gemini-worker status${NC}    Check status & health"
echo -e "  ${CYAN}gemini-worker version${NC}   Show version"
echo -e "  ${CYAN}gemini-worker logs${NC}      Tail log output"
echo -e "  ${CYAN}gemini-worker upgrade${NC}   Upgrade to new version"
echo -e "  ${CYAN}$ACTIVE_CLI_PATH start${NC}   Direct command path"
echo ""
echo -e "Config:  $INSTALL_DIR/.env"
echo -e "Logs:    $INSTALL_DIR/logs/"
