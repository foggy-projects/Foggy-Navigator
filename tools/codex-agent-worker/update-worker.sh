#!/bin/bash
# Codex Agent Worker - Worker self-update (Linux / macOS)
#
# Updates the worker release package itself. To update only @openai/codex-sdk
# and its bundled Codex CLI, use update-sdk.sh instead.
#
# Usage:
#   ./update-worker.sh
#   ./update-worker.sh --url https://example.com/codex-worker
#   ./update-worker.sh /path/to/codex-worker-X.Y.Z-linux.tar.gz
#   ./update-worker.sh --force --no-restart

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -n "$CODEX_WORKER_HOME" ]; then
    INSTALL_DIR="$CODEX_WORKER_HOME"
elif [ -f "$SCRIPT_DIR/VERSION" ] && [ -f "$SCRIPT_DIR/package.json" ]; then
    INSTALL_DIR="$SCRIPT_DIR"
else
    INSTALL_DIR="$HOME/.codex-worker"
fi

DEFAULT_PORT=3051
Archive=""
BaseUrl=""
Force=false
NoRestart=false

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; GRAY='\033[0;37m'; NC='\033[0m'

usage() {
    echo "Usage: $0 [archive] [--url <base-url>] [--force] [--no-restart]"
}

while [ $# -gt 0 ]; do
    case "$1" in
        --url)
            [ $# -ge 2 ] || { echo -e "${RED}--url requires a value.${NC}"; exit 1; }
            BaseUrl="$2"
            shift 2
            ;;
        --url=*)
            BaseUrl="${1#*=}"
            shift
            ;;
        --force)
            Force=true
            shift
            ;;
        --no-restart)
            NoRestart=true
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        --*)
            echo -e "${RED}Unknown argument: $1${NC}"
            usage
            exit 1
            ;;
        *)
            if [ -n "$Archive" ]; then
                echo -e "${RED}Only one local archive may be specified.${NC}"
                usage
                exit 1
            fi
            Archive="$1"
            shift
            ;;
    esac
done

if [ ! -f "$INSTALL_DIR/package.json" ]; then
    echo -e "${RED}Codex Worker is not installed at $INSTALL_DIR.${NC}"
    echo -e "${YELLOW}Set CODEX_WORKER_HOME if it is installed elsewhere.${NC}"
    exit 1
fi

if ! command -v node >/dev/null 2>&1; then
    echo -e "${RED}Node.js 20+ is required to update Codex Worker.${NC}"
    exit 1
fi

Port=$DEFAULT_PORT
if [ -f "$INSTALL_DIR/.env" ]; then
    PortLine=$(grep '^CODEX_WORKER_PORT=' "$INSTALL_DIR/.env" 2>/dev/null | tail -1 || true)
    [ -z "$PortLine" ] || Port=$(printf '%s' "${PortLine#*=}" | tr -d ' ')
fi

CurrentVersion=$(cat "$INSTALL_DIR/VERSION" 2>/dev/null || node -p "require('$INSTALL_DIR/package.json').version" 2>/dev/null || echo "unknown")
WasRunning=false
if command -v lsof >/dev/null 2>&1 && lsof -i :"$Port" >/dev/null 2>&1; then
    WasRunning=true
fi

TempDirs=()
cleanup() {
    local dir
    for dir in "${TempDirs[@]}"; do
        [ ! -d "$dir" ] || rm -rf "$dir"
    done
}
trap cleanup EXIT

read_env_url() {
    local line=""
    if [ -f "$INSTALL_DIR/.env" ]; then
        line=$(grep '^CODEX_WORKER_URL=' "$INSTALL_DIR/.env" 2>/dev/null | tail -1 || true)
    fi
    printf '%s' "${line#*=}"
}

download_latest() {
    local base_url="${1%/}"
    local metadata latest_version os_tag file_path download_url temp_dir

    command -v curl >/dev/null 2>&1 || {
        echo -e "${RED}curl is required for remote updates.${NC}"
        return 1
    }

    echo -e "${CYAN}Checking latest version from $base_url ...${NC}"
    metadata=$(curl -fsSL --max-time 15 "$base_url/latest.json") || {
        echo -e "${RED}Could not fetch $base_url/latest.json${NC}"
        return 1
    }

    latest_version=$(printf '%s' "$metadata" | node -e 'let s="";process.stdin.on("data",c=>s+=c);process.stdin.on("end",()=>{try{process.stdout.write(String(JSON.parse(s).version||""))}catch{process.exit(1)}})') || true
    if [ -z "$latest_version" ]; then
        echo -e "${RED}Could not parse version from latest.json.${NC}"
        return 1
    fi

    if [ "$latest_version" = "$CurrentVersion" ] && [ "$Force" = false ]; then
        echo -e "${GREEN}Already up to date (v${CurrentVersion}).${NC}"
        exit 0
    fi

    case "$(uname -s)" in
        Linux*) os_tag="linux" ;;
        Darwin*) os_tag="macos" ;;
        *)
            echo -e "${RED}Unsupported operating system: $(uname -s)${NC}"
            return 1
            ;;
    esac

    file_path=$(printf '%s' "$metadata" | node -e 'const k=process.argv[1];let s="";process.stdin.on("data",c=>s+=c);process.stdin.on("end",()=>{try{process.stdout.write(String(JSON.parse(s).files?.[k]||""))}catch{process.exit(1)}})' "$os_tag") || true
    if [ -z "$file_path" ]; then
        echo -e "${RED}No release artifact found for $os_tag.${NC}"
        return 1
    fi

    echo -e "${CYAN}New version available: $CurrentVersion -> $latest_version${NC}"
    download_url="$base_url/$file_path"
    temp_dir=$(mktemp -d)
    TempDirs+=("$temp_dir")
    Archive="$temp_dir/$(basename "$file_path")"
    echo -e "${CYAN}Downloading: $download_url${NC}"
    curl -fL --max-time 120 -o "$Archive" "$download_url"
    BaseUrl="$base_url"
}

health_check() {
    local deadline=$(( $(date +%s) + 30 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        if curl -fsS --max-time 3 "http://localhost:$Port/health" >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done
    return 1
}

echo -e "${CYAN}=== Codex Agent Worker Self-Update ===${NC}"
echo -e "${CYAN}Install dir: $INSTALL_DIR${NC}"
echo -e "${GRAY}Current version: $CurrentVersion${NC}"

if [ -n "$Archive" ]; then
    [ -f "$Archive" ] || { echo -e "${RED}Archive not found: $Archive${NC}"; exit 1; }
    echo -e "${CYAN}Using local archive: $Archive${NC}"
else
    if [ -z "$BaseUrl" ]; then
        BaseUrl="${CODEX_WORKER_URL:-$(read_env_url)}"
    fi
    if [ -z "$BaseUrl" ]; then
        echo -e "${RED}No update source configured.${NC}"
        echo -e "${YELLOW}Pass an archive, use --url, or set CODEX_WORKER_URL in .env.${NC}"
        exit 1
    fi
    download_latest "$BaseUrl"
fi

ExtractDir=$(mktemp -d)
TempDirs+=("$ExtractDir")
echo -e "${CYAN}Extracting release...${NC}"
case "$Archive" in
    *.zip)
        command -v unzip >/dev/null 2>&1 || { echo -e "${RED}unzip is required for .zip archives.${NC}"; exit 1; }
        unzip -q "$Archive" -d "$ExtractDir"
        ;;
    *.tar.gz|*.tgz)
        tar xzf "$Archive" -C "$ExtractDir"
        ;;
    *)
        echo -e "${RED}Unsupported archive type: $Archive${NC}"
        exit 1
        ;;
esac

InstallScript=$(find "$ExtractDir" -maxdepth 3 -type f -name install.sh | head -1)
if [ -z "$InstallScript" ]; then
    echo -e "${RED}No install.sh found in archive.${NC}"
    exit 1
fi

if [ -n "$BaseUrl" ]; then
    export CODEX_WORKER_URL="${BaseUrl%/}"
fi
export CODEX_WORKER_HOME="$INSTALL_DIR"
chmod +x "$InstallScript"
bash "$InstallScript" --upgrade

NewVersion=$(cat "$INSTALL_DIR/VERSION" 2>/dev/null || echo "unknown")
echo -e "${GREEN}Codex Worker updated: $CurrentVersion -> $NewVersion${NC}"

if [ "$NoRestart" = true ]; then
    echo -e "${YELLOW}Worker not restarted because --no-restart was used.${NC}"
elif [ "$WasRunning" = true ]; then
    echo -e "${CYAN}Restarting worker...${NC}"
    bash "$INSTALL_DIR/start.sh"
    echo -e "${CYAN}Health-checking worker on port $Port ...${NC}"
    if health_check; then
        echo -e "${GREEN}Worker is healthy after update.${NC}"
    else
        echo -e "${RED}Worker did not become healthy within 30 seconds.${NC}"
        echo -e "${YELLOW}Check logs with: codex-worker logs${NC}"
        exit 1
    fi
else
    echo -e "${GREEN}Worker was not running, so it remains stopped.${NC}"
fi
