#!/bin/bash
# Claude Code Proxy - Installer (Linux / macOS)
# This script is run from INSIDE the extracted archive directory.
#
# Usage:
#   ./install.sh              # First-time install
#   ./install.sh --upgrade    # Upgrade existing installation
#
# Environment:
#   CLAUDE_PROXY_HOME   Install directory (default: ~/.claude-code-proxy)

set -e

# --- Colors ---------------------------------------------------------------
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; NC='\033[0m'

# --- Paths ----------------------------------------------------------------
INSTALL_DIR="${CLAUDE_PROXY_HOME:-$HOME/.claude-code-proxy}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VERSION=$(cat "$SCRIPT_DIR/VERSION" 2>/dev/null || echo "unknown")

# --- Detect install vs upgrade --------------------------------------------
IS_UPGRADE=false
if [ "$1" = "--upgrade" ] || [ -f "$INSTALL_DIR/VERSION" ]; then
    IS_UPGRADE=true
    OLD_VERSION=$(cat "$INSTALL_DIR/VERSION" 2>/dev/null || echo "unknown")
    echo -e "${CYAN}Upgrading Claude Code Proxy: ${OLD_VERSION} -> ${VERSION}${NC}"
else
    echo -e "${CYAN}Installing Claude Code Proxy v${VERSION}${NC}"
fi
echo -e "${CYAN}Install directory: $INSTALL_DIR${NC}"
echo ""

# --- Prerequisite check: Python 3.9+ -------------------------------------
PYTHON_CMD=""
for cmd in python3 python; do
    if command -v $cmd &>/dev/null; then
        PY_VER=$($cmd -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')" 2>/dev/null || echo "0.0")
        PY_MAJOR=$(echo "$PY_VER" | cut -d. -f1)
        PY_MINOR=$(echo "$PY_VER" | cut -d. -f2)
        if [ "$PY_MAJOR" -ge 3 ] && [ "$PY_MINOR" -ge 9 ]; then
            PYTHON_CMD=$cmd
            break
        fi
    fi
done

if [ -z "$PYTHON_CMD" ]; then
    echo -e "${RED}ERROR: Python 3.9+ is required but not found.${NC}"
    echo -e "${YELLOW}Please install Python 3.9 or later:${NC}"
    echo -e "  macOS:  brew install python@3.12"
    echo -e "  Ubuntu: sudo apt install python3.12 python3.12-venv"
    echo -e "  Other:  https://www.python.org/downloads/"
    exit 1
fi
echo -e "${GREEN}Python: $($PYTHON_CMD --version)${NC}"

# --- Backup .env if upgrading --------------------------------------------
ENV_BACKUP=""
if [ "$IS_UPGRADE" = true ] && [ -f "$INSTALL_DIR/.env" ]; then
    ENV_BACKUP=$(mktemp)
    cp "$INSTALL_DIR/.env" "$ENV_BACKUP"
    echo -e "${CYAN}Backed up existing .env${NC}"
fi

# --- Stop running proxy if upgrading -------------------------------------
if [ "$IS_UPGRADE" = true ] && [ -f "$INSTALL_DIR/stop.sh" ]; then
    echo -e "${YELLOW}Stopping running proxy...${NC}"
    bash "$INSTALL_DIR/stop.sh" 2>/dev/null || true
    echo ""
fi

# --- Create install directory ---------------------------------------------
mkdir -p "$INSTALL_DIR"

# --- Copy source code (always overwrite) ----------------------------------
echo -e "${CYAN}Copying files...${NC}"

# Remove old src to avoid stale .pyc files
if [ -d "$INSTALL_DIR/src" ]; then
    rm -rf "$INSTALL_DIR/src"
fi

cp -r "$SCRIPT_DIR/src"             "$INSTALL_DIR/"
cp    "$SCRIPT_DIR/pyproject.toml"  "$INSTALL_DIR/"
cp    "$SCRIPT_DIR/requirements.txt" "$INSTALL_DIR/"
cp    "$SCRIPT_DIR/.env.example"    "$INSTALL_DIR/"
cp    "$SCRIPT_DIR/QUICKSTART.md"   "$INSTALL_DIR/"
cp    "$SCRIPT_DIR/start_proxy.py"  "$INSTALL_DIR/"
cp    "$SCRIPT_DIR/VERSION"         "$INSTALL_DIR/"
# README.md is required by pyproject.toml (readme = "README.md") for pip install
[ -f "$SCRIPT_DIR/README.md" ] && cp "$SCRIPT_DIR/README.md" "$INSTALL_DIR/"

# Start/stop scripts
for f in start.sh stop.sh start.ps1 stop.ps1; do
    if [ -f "$SCRIPT_DIR/$f" ]; then
        cp "$SCRIPT_DIR/$f" "$INSTALL_DIR/"
    fi
done
chmod +x "$INSTALL_DIR/start.sh" "$INSTALL_DIR/stop.sh" 2>/dev/null || true

# CLI wrapper
mkdir -p "$INSTALL_DIR/bin"
cp "$SCRIPT_DIR/bin/claude-code-proxy" "$INSTALL_DIR/bin/"
chmod +x "$INSTALL_DIR/bin/claude-code-proxy"
if [ -f "$SCRIPT_DIR/bin/claude-code-proxy.ps1" ]; then
    cp "$SCRIPT_DIR/bin/claude-code-proxy.ps1" "$INSTALL_DIR/bin/"
fi

# Bundled docs
if [ -d "$SCRIPT_DIR/docs" ]; then
    rm -rf "$INSTALL_DIR/docs" 2>/dev/null || true
    cp -r "$SCRIPT_DIR/docs" "$INSTALL_DIR/"
fi

# --- Restore or create .env -----------------------------------------------
if [ -n "$ENV_BACKUP" ] && [ -f "$ENV_BACKUP" ]; then
    cp "$ENV_BACKUP" "$INSTALL_DIR/.env"
    rm -f "$ENV_BACKUP"
    echo -e "${GREEN}Restored .env from backup${NC}"
elif [ ! -f "$INSTALL_DIR/.env" ]; then
    cp "$SCRIPT_DIR/.env.example" "$INSTALL_DIR/.env"
    echo -e "${YELLOW}Created .env from template${NC}"
    echo -e "${YELLOW}  -> Please edit: $INSTALL_DIR/.env${NC}"
fi

# --- Write CLAUDE_PROXY_URL to .env if provided ---------------------------
if [ -n "$CLAUDE_PROXY_URL" ]; then
    ENV_FILE="$INSTALL_DIR/.env"
    if grep -q "^CLAUDE_PROXY_URL=" "$ENV_FILE" 2>/dev/null; then
        # Update existing value
        sed -i.bak "s|^CLAUDE_PROXY_URL=.*|CLAUDE_PROXY_URL=$CLAUDE_PROXY_URL|" "$ENV_FILE"
        rm -f "${ENV_FILE}.bak"
    else
        # Append
        echo "" >> "$ENV_FILE"
        echo "# Auto-upgrade URL (set by remote installer)" >> "$ENV_FILE"
        echo "CLAUDE_PROXY_URL=$CLAUDE_PROXY_URL" >> "$ENV_FILE"
    fi
    echo -e "${GREEN}Saved CLAUDE_PROXY_URL to .env${NC}"
fi

# --- Clean __pycache__ and .egg-info --------------------------------------
find "$INSTALL_DIR/src" -type d -name "__pycache__" -exec rm -rf {} + 2>/dev/null || true
find "$INSTALL_DIR/src" -type d -name "*.egg-info" -exec rm -rf {} + 2>/dev/null || true
# Also remove top-level egg-info if present
rm -rf "$INSTALL_DIR/src/claude_code_proxy.egg-info" 2>/dev/null || true

# --- Setup venv and install dependencies ---------------------------------
echo ""
echo -e "${CYAN}Setting up Python environment...${NC}"

VENV_DIR="$INSTALL_DIR/.venv"

# On upgrade, always recreate venv to avoid stale/broken pip or python symlinks
if [ "$IS_UPGRADE" = true ] && [ -d "$VENV_DIR" ]; then
    echo -e "${YELLOW}Removing old venv (will recreate)...${NC}"
    rm -rf "$VENV_DIR"
fi

# Detect if uv is available (preferred, much faster)
USE_UV=false
if command -v uv &>/dev/null; then
    USE_UV=true
fi

if [ "$USE_UV" = true ]; then
    # uv is available — use it for fast venv + install
    if [ ! -d "$VENV_DIR" ]; then
        echo -e "${CYAN}Creating venv with uv...${NC}"
        uv venv --python "$PYTHON_CMD" "$VENV_DIR"
    fi
    echo -e "${CYAN}Installing dependencies with uv...${NC}"
    uv pip install -e "$INSTALL_DIR" --python "$VENV_DIR/bin/python" 2>&1
else
    # Fallback: standard pip
    if [ ! -f "$VENV_DIR/bin/python" ] || [ ! -f "$VENV_DIR/bin/pip" ]; then
        echo -e "${CYAN}Creating venv...${NC}"
        rm -rf "$VENV_DIR"
        $PYTHON_CMD -m venv "$VENV_DIR"
    fi
    echo -e "${CYAN}Installing dependencies with pip...${NC}"
    "$VENV_DIR/bin/pip" install --upgrade pip -q
    "$VENV_DIR/bin/pip" install -e "$INSTALL_DIR" -q
fi

# --- Create logs directory ------------------------------------------------
mkdir -p "$INSTALL_DIR/logs"

# --- PATH guidance --------------------------------------------------------
echo ""
BIN_DIR="$INSTALL_DIR/bin"
SHELL_RC=""
case "$SHELL" in
    */zsh)  SHELL_RC="$HOME/.zshrc";;
    */bash) SHELL_RC="$HOME/.bashrc";;
    *)      SHELL_RC="$HOME/.profile";;
esac

PATH_LINE="export PATH=\"$BIN_DIR:\$PATH\""
if ! echo "$PATH" | grep -qF "$BIN_DIR"; then
    if [ -n "$SHELL_RC" ] && ! grep -qF "$BIN_DIR" "$SHELL_RC" 2>/dev/null; then
        echo -e "${YELLOW}To add 'claude-code-proxy' to your PATH, run:${NC}"
        echo -e "  echo '$PATH_LINE' >> $SHELL_RC && source $SHELL_RC"
        echo ""
    fi
fi

# --- Done -----------------------------------------------------------------
echo -e "${GREEN}============================================${NC}"
if [ "$IS_UPGRADE" = true ]; then
    echo -e "${GREEN}Claude Code Proxy upgraded to v${VERSION}${NC}"
else
    echo -e "${GREEN}Claude Code Proxy v${VERSION} installed!${NC}"
fi
echo -e "${GREEN}============================================${NC}"
echo ""
echo -e "Commands:"
echo -e "  ${CYAN}claude-code-proxy start${NC}     Start the proxy"
echo -e "  ${CYAN}claude-code-proxy stop${NC}      Stop the proxy"
echo -e "  ${CYAN}claude-code-proxy status${NC}    Check status & health"
echo -e "  ${CYAN}claude-code-proxy version${NC}   Show version"
echo -e "  ${CYAN}claude-code-proxy logs${NC}      Tail log output"
echo -e "  ${CYAN}claude-code-proxy upgrade${NC}   Upgrade to new version"
echo ""
echo -e "Config:  $INSTALL_DIR/.env"
echo -e "Logs:    $INSTALL_DIR/logs/"
