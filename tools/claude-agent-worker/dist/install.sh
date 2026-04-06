#!/bin/bash
# Claude Agent Worker - Installer (Linux / macOS)
# This script is run from INSIDE the extracted archive directory.
#
# Usage:
#   ./install.sh              # First-time install
#   ./install.sh --upgrade    # Upgrade existing installation
#
# Environment:
#   CLAUDE_WORKER_HOME   Install directory (default: ~/.claude-worker)

set -e

# --- Colors ---------------------------------------------------------------
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

# --- Paths ----------------------------------------------------------------
INSTALL_DIR="${CLAUDE_WORKER_HOME:-$HOME/.claude-worker}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VERSION=$(cat "$SCRIPT_DIR/VERSION" 2>/dev/null || echo "unknown")

# --- Detect install vs upgrade --------------------------------------------
IS_UPGRADE=false
if [ "$1" = "--upgrade" ] || [ -f "$INSTALL_DIR/VERSION" ]; then
    IS_UPGRADE=true
    OLD_VERSION=$(cat "$INSTALL_DIR/VERSION" 2>/dev/null || echo "unknown")
    echo -e "${CYAN}Upgrading Claude Agent Worker: ${OLD_VERSION} -> ${VERSION}${NC}"
else
    echo -e "${CYAN}Installing Claude Agent Worker v${VERSION}${NC}"
fi
echo -e "${CYAN}Install directory: $INSTALL_DIR${NC}"
echo ""

# --- Prerequisite check: Python 3.10+ ------------------------------------
PYTHON_CMD=""
for cmd in python3 python; do
    if command -v $cmd &>/dev/null; then
        PY_VER=$($cmd -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')" 2>/dev/null || echo "0.0")
        PY_MAJOR=$(echo "$PY_VER" | cut -d. -f1)
        PY_MINOR=$(echo "$PY_VER" | cut -d. -f2)
        if [ "$PY_MAJOR" -ge 3 ] && [ "$PY_MINOR" -ge 10 ]; then
            PYTHON_CMD=$cmd
            break
        fi
    fi
done

if [ -z "$PYTHON_CMD" ]; then
    echo -e "${RED}ERROR: Python 3.10+ is required but not found.${NC}"
    echo -e "${YELLOW}Please install Python 3.10 or later:${NC}"
    echo -e "  macOS:  brew install python@3.12"
    echo -e "  Ubuntu: sudo apt install python3.12 python3.12-venv"
    echo -e "  Other:  https://www.python.org/downloads/"
    exit 1
fi
echo -e "${GREEN}Python: $($PYTHON_CMD --version)${NC}"

# --- Prerequisite check: Claude Code CLI ----------------------------------
if ! command -v claude &>/dev/null; then
    echo -e "${YELLOW}WARNING: Claude Code CLI not found in PATH.${NC}"
    echo -e "${YELLOW}The worker requires 'claude' CLI to function.${NC}"
    echo -e "${YELLOW}Install it with: npm install -g @anthropic-ai/claude-code${NC}"
    echo ""
fi

# --- Backup .env if upgrading --------------------------------------------
ENV_BACKUP=""
if [ "$IS_UPGRADE" = true ] && [ -f "$INSTALL_DIR/.env" ]; then
    ENV_BACKUP=$(mktemp)
    cp "$INSTALL_DIR/.env" "$ENV_BACKUP"
    echo -e "${CYAN}Backed up existing .env${NC}"
fi

# --- Stop running worker if upgrading ------------------------------------
if [ "$IS_UPGRADE" = true ] && [ -f "$INSTALL_DIR/stop.sh" ]; then
    echo -e "${YELLOW}Stopping running worker...${NC}"
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

cp -r "$SCRIPT_DIR/src"            "$INSTALL_DIR/"
cp    "$SCRIPT_DIR/pyproject.toml" "$INSTALL_DIR/"
cp    "$SCRIPT_DIR/.env.example"   "$INSTALL_DIR/"
cp    "$SCRIPT_DIR/SETUP.md"       "$INSTALL_DIR/"
cp    "$SCRIPT_DIR/VERSION"        "$INSTALL_DIR/"

# Start/stop scripts
for f in start.sh start-mac.sh stop.sh start.ps1 stop.ps1; do
    if [ -f "$SCRIPT_DIR/$f" ]; then
        cp "$SCRIPT_DIR/$f" "$INSTALL_DIR/"
    fi
done
chmod +x "$INSTALL_DIR/start.sh" "$INSTALL_DIR/stop.sh" 2>/dev/null || true
chmod +x "$INSTALL_DIR/start-mac.sh" 2>/dev/null || true

# CLI wrapper
mkdir -p "$INSTALL_DIR/bin"
cp "$SCRIPT_DIR/bin/claude-worker" "$INSTALL_DIR/bin/"
chmod +x "$INSTALL_DIR/bin/claude-worker"
if [ -f "$SCRIPT_DIR/bin/claude-worker.ps1" ]; then
    cp "$SCRIPT_DIR/bin/claude-worker.ps1" "$INSTALL_DIR/bin/"
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

# --- Write CLAUDE_WORKER_URL to .env if provided --------------------------
if [ -n "$CLAUDE_WORKER_URL" ]; then
    ENV_FILE="$INSTALL_DIR/.env"
    if grep -q "^CLAUDE_WORKER_URL=" "$ENV_FILE" 2>/dev/null; then
        # Update existing value
        sed -i.bak "s|^CLAUDE_WORKER_URL=.*|CLAUDE_WORKER_URL=$CLAUDE_WORKER_URL|" "$ENV_FILE"
        rm -f "${ENV_FILE}.bak"
    else
        # Append
        echo "" >> "$ENV_FILE"
        echo "# Auto-upgrade URL (set by remote installer)" >> "$ENV_FILE"
        echo "CLAUDE_WORKER_URL=$CLAUDE_WORKER_URL" >> "$ENV_FILE"
    fi
    echo -e "${GREEN}Saved CLAUDE_WORKER_URL to .env${NC}"
fi

# --- Clean __pycache__ and .egg-info --------------------------------------
find "$INSTALL_DIR/src" -type d -name "__pycache__" -exec rm -rf {} + 2>/dev/null || true
find "$INSTALL_DIR/src" -type d -name "*.egg-info" -exec rm -rf {} + 2>/dev/null || true
# Also remove top-level egg-info if present
rm -rf "$INSTALL_DIR/src/claude_agent_worker.egg-info" 2>/dev/null || true

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
ACTIVE_CLI_PATH=$(install_cli_link "claude-worker" "$INSTALL_DIR/bin/claude-worker")
ACTIVE_CLI_DIR=$(dirname "$ACTIVE_CLI_PATH")
SHELL_RC=""
case "$SHELL" in
    */zsh)  SHELL_RC="$HOME/.zshrc";;
    */bash) SHELL_RC="$HOME/.bashrc";;
    *)      SHELL_RC="$HOME/.profile";;
esac

echo -e "${GREEN}CLI shim: $ACTIVE_CLI_PATH${NC}"

PATH_LINE="export PATH=\"$ACTIVE_CLI_DIR:\$PATH\""
if ! echo ":$PATH:" | grep -q ":$ACTIVE_CLI_DIR:"; then
    if [ -n "$SHELL_RC" ] && ! grep -qF "$ACTIVE_CLI_DIR" "$SHELL_RC" 2>/dev/null; then
        echo -e "${YELLOW}To add 'claude-worker' to your PATH, run:${NC}"
        echo -e "  echo '$PATH_LINE' >> $SHELL_RC && source $SHELL_RC"
        echo ""
    fi
fi

# --- Done -----------------------------------------------------------------
echo -e "${GREEN}============================================${NC}"
if [ "$IS_UPGRADE" = true ]; then
    echo -e "${GREEN}Claude Agent Worker upgraded to v${VERSION}${NC}"
else
    echo -e "${GREEN}Claude Agent Worker v${VERSION} installed!${NC}"
fi
echo -e "${GREEN}============================================${NC}"
echo ""
echo -e "Commands:"
echo -e "  ${CYAN}claude-worker start${NC}     Start the worker"
echo -e "  ${CYAN}claude-worker stop${NC}      Stop the worker"
echo -e "  ${CYAN}claude-worker status${NC}    Check status & health"
echo -e "  ${CYAN}claude-worker version${NC}   Show version"
echo -e "  ${CYAN}claude-worker logs${NC}      Tail log output"
echo -e "  ${CYAN}claude-worker upgrade${NC}   Upgrade to new version"
echo -e "  ${CYAN}$ACTIVE_CLI_PATH start${NC}   Direct command path"
echo ""
echo -e "Config:  $INSTALL_DIR/.env"
echo -e "Logs:    $INSTALL_DIR/logs/"
