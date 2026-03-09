#!/bin/bash
# Claude Agent Worker - Package Script
# Builds a distributable archive from the monorepo.
#
# Usage:
#   bash dist/package.sh              # Auto-detect OS
#   bash dist/package.sh linux        # Force Linux build
#   bash dist/package.sh macos        # Force macOS build
#   bash dist/package.sh windows      # Force Windows build (.zip)
#   bash dist/package.sh all          # Build for all platforms
#   bash dist/package.sh all --upload # Build all + upload to OBS
#
# Output: dist/output/claude-worker-{version}-{os}.tar.gz (or .zip)

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WORKER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# --- Parse --upload flag --------------------------------------------------
DO_UPLOAD=false
ARGS=()
for arg in "$@"; do
    if [ "$arg" = "--upload" ]; then
        DO_UPLOAD=true
    else
        ARGS+=("$arg")
    fi
done
set -- "${ARGS[@]}"

# --- Read version from __init__.py (single source of truth) ---------------
VERSION=$(grep '__version__' "$WORKER_DIR/src/agent_worker/__init__.py" | sed 's/.*"\(.*\)".*/\1/')
if [ -z "$VERSION" ]; then
    echo "ERROR: Could not read version from src/agent_worker/__init__.py"
    exit 1
fi

echo "=== Claude Agent Worker Packager ==="
echo "Version: $VERSION"
echo "Source:  $WORKER_DIR"
echo ""

# --- Determine target OS --------------------------------------------------
OS_INPUT="${1:-auto}"

build_for_os() {
    local OS_TAG="$1"
    local ARCHIVE_EXT="tar.gz"
    [ "$OS_TAG" = "windows" ] && ARCHIVE_EXT="zip"

    echo "Building for: $OS_TAG ($ARCHIVE_EXT)"

    # Create staging directory
    local STAGE_DIR="$WORKER_DIR/dist/staging/claude-worker"
    rm -rf "$WORKER_DIR/dist/staging"
    mkdir -p "$STAGE_DIR"

    # --- Copy source code (no tests, no dev files) ------------------------
    cp -r "$WORKER_DIR/src" "$STAGE_DIR/"

    # --- Copy project metadata -------------------------------------------
    cp "$WORKER_DIR/pyproject.toml"   "$STAGE_DIR/"
    cp "$WORKER_DIR/.env.example"     "$STAGE_DIR/"
    cp "$WORKER_DIR/SETUP.md"         "$STAGE_DIR/"

    # --- Copy start/stop scripts -----------------------------------------
    for f in start.ps1 stop.ps1 start.sh stop.sh start-mac.sh; do
        if [ -f "$WORKER_DIR/$f" ]; then
            cp "$WORKER_DIR/$f" "$STAGE_DIR/"
        fi
    done

    # --- Copy install scripts and CLI wrapper ----------------------------
    cp "$SCRIPT_DIR/install.sh"  "$STAGE_DIR/"
    cp "$SCRIPT_DIR/install.ps1" "$STAGE_DIR/"

    mkdir -p "$STAGE_DIR/bin"
    cp "$SCRIPT_DIR/bin/claude-worker"     "$STAGE_DIR/bin/"
    cp "$SCRIPT_DIR/bin/claude-worker.ps1" "$STAGE_DIR/bin/"

    # --- Write VERSION file -----------------------------------------------
    echo "$VERSION" > "$STAGE_DIR/VERSION"

    # --- Clean up build artifacts -----------------------------------------
    find "$STAGE_DIR" -type d -name "__pycache__" -exec rm -rf {} + 2>/dev/null || true
    find "$STAGE_DIR" -type d -name "*.egg-info" -exec rm -rf {} + 2>/dev/null || true
    find "$STAGE_DIR" -type f -name "*.pyc" -delete 2>/dev/null || true

    # --- Make scripts executable ------------------------------------------
    chmod +x "$STAGE_DIR/install.sh" "$STAGE_DIR/bin/claude-worker" 2>/dev/null || true
    chmod +x "$STAGE_DIR/start.sh" "$STAGE_DIR/stop.sh" "$STAGE_DIR/start-mac.sh" 2>/dev/null || true

    # --- Create archive ---------------------------------------------------
    local OUTPUT_DIR="$WORKER_DIR/dist/output"
    mkdir -p "$OUTPUT_DIR"
    local ARCHIVE_NAME="claude-worker-${VERSION}-${OS_TAG}"

    if [ "$ARCHIVE_EXT" = "zip" ]; then
        (cd "$WORKER_DIR/dist/staging" && zip -rq "../output/${ARCHIVE_NAME}.zip" claude-worker/)
        echo "  -> dist/output/${ARCHIVE_NAME}.zip"
    else
        (cd "$WORKER_DIR/dist/staging" && tar czf "../output/${ARCHIVE_NAME}.tar.gz" claude-worker/)
        echo "  -> dist/output/${ARCHIVE_NAME}.tar.gz"
    fi

    # Cleanup staging
    rm -rf "$WORKER_DIR/dist/staging"
}

# --- Build ----------------------------------------------------------------
case "$OS_INPUT" in
    auto)
        case "$(uname -s)" in
            Linux*)   build_for_os "linux";;
            Darwin*)  build_for_os "macos";;
            MINGW*|MSYS*|CYGWIN*) build_for_os "windows";;
            *)        build_for_os "linux";;
        esac
        ;;
    all)
        build_for_os "linux"
        build_for_os "macos"
        build_for_os "windows"
        ;;
    linux|macos|windows)
        build_for_os "$OS_INPUT"
        ;;
    *)
        echo "Usage: $0 [auto|linux|macos|windows|all]"
        exit 1
        ;;
esac

echo ""
echo "Done! Archives are in: $WORKER_DIR/dist/output/"
ls -lh "$WORKER_DIR/dist/output/" 2>/dev/null || true

# --- Upload to OBS if requested -------------------------------------------
if [ "$DO_UPLOAD" = true ]; then
    echo ""
    UPLOAD_SCRIPT="$SCRIPT_DIR/upload.sh"
    if [ -f "$UPLOAD_SCRIPT" ]; then
        echo -e "${CYAN:-}Uploading to OBS...${NC:-}"
        bash "$UPLOAD_SCRIPT" "$VERSION"
    else
        echo "WARNING: dist/upload.sh not found, skipping upload."
    fi
fi
