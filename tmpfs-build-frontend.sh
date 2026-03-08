#!/bin/bash
# Foggy Navigator - tmpfs 加速构建
#
# 原理：将整个项目 rsync 到内存盘（/dev/shm），在内存中完成构建，
#       然后把 dist 拷回原目录。全程零磁盘随机 I/O。
#
# 首次运行会全量拷贝（~700MB，约 10-20 秒），后续增量同步极快。
#
# Usage:
#   ./tmpfs-build-frontend.sh              # 增量同步 + 构建 + 拷回 dist + 重启 nginx
#   ./tmpfs-build-frontend.sh --build-only # 只构建，不重启 nginx
#   ./tmpfs-build-frontend.sh --force      # 强制重建 workspace 包
#   ./tmpfs-build-frontend.sh --clean      # 清理 tmpfs 中的构建缓存

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_NAME="foggy-navigator-build"
TMPFS_BASE="/dev/shm"
TMPFS_DIR="${TMPFS_BASE}/${PROJECT_NAME}"
DIST_REL="packages/navigator-frontend/dist"
LOG_DIR="${SCRIPT_DIR}/logs"

# ── Colors ────────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
GRAY='\033[0;37m'
BOLD='\033[1m'
NC='\033[0m'

# ── Parse args ────────────────────────────────────────────────────────────────
BUILD_ONLY=false
FORCE_REBUILD=false
CLEAN_ONLY=false
EXTRA_ARGS=()
for arg in "$@"; do
    case "$arg" in
        --build-only|-b) BUILD_ONLY=true ;;
        --force|-f)      FORCE_REBUILD=true; EXTRA_ARGS+=("--force") ;;
        --clean|-c)      CLEAN_ONLY=true ;;
    esac
done

# ── Clean mode ────────────────────────────────────────────────────────────────
if [ "$CLEAN_ONLY" = true ]; then
    echo -e "${YELLOW}Cleaning tmpfs build cache: ${TMPFS_DIR}${NC}"
    rm -rf "${TMPFS_DIR}"
    echo -e "${GREEN}Done. Freed $(du -sh "${TMPFS_BASE}" 2>/dev/null | cut -f1) on /dev/shm${NC}"
    exit 0
fi

echo ""
echo -e "${CYAN}${BOLD}╔════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}${BOLD}║    Frontend Build (tmpfs accelerated)          ║${NC}"
echo -e "${CYAN}${BOLD}╚════════════════════════════════════════════════╝${NC}"
echo ""

mkdir -p "$LOG_DIR"

# ── Pre-flight checks ────────────────────────────────────────────────────────
TMPFS_AVAIL_MB=$(df -BM "${TMPFS_BASE}" --output=avail | tail -1 | tr -d ' M')
PROJECT_SIZE_MB=$(du -sm "${SCRIPT_DIR}" --exclude='.git' | cut -f1)
REQUIRED_MB=$((PROJECT_SIZE_MB + 200))  # 额外 200MB 余量给构建产物

if [ "${TMPFS_AVAIL_MB}" -lt "${REQUIRED_MB}" ]; then
    echo -e "${RED}  /dev/shm 空间不足！需要 ${REQUIRED_MB}MB，当前可用 ${TMPFS_AVAIL_MB}MB${NC}"
    echo -e "${YELLOW}  运行 ./tmpfs-build-frontend.sh --clean 清理旧缓存后重试${NC}"
    exit 1
fi

# ══ Step 1: Sync to tmpfs ════════════════════════════════════════════════════
echo -e "${YELLOW}[1/4] Syncing project to tmpfs (${TMPFS_DIR})...${NC}"

SYNC_START=$(date +%s%N)
rsync -a --delete \
    --exclude='.git' \
    --exclude='/packages/navigator-frontend/dist' \
    --exclude='/packages/foggy-chat-core/dist' \
    --exclude='/packages/foggy-chat/dist' \
    --exclude='/packages/foggy-mobile/dist' \
    --exclude='/logs' \
    --exclude='/.claude' \
    "${SCRIPT_DIR}/" "${TMPFS_DIR}/"
SYNC_END=$(date +%s%N)
SYNC_MS=$(( (SYNC_END - SYNC_START) / 1000000 ))

TMPFS_USED=$(du -sh "${TMPFS_DIR}" | cut -f1)
echo -e "${GREEN}  Synced ${TMPFS_USED} in ${SYNC_MS}ms${NC}"

# ══ Step 2: Build workspace packages on tmpfs ════════════════════════════════
CHAT_CORE_DIR="${TMPFS_DIR}/packages/foggy-chat-core"
CHAT_DIR="${TMPFS_DIR}/packages/foggy-chat"

WS_NEEDS_BUILD=false
if [ "$FORCE_REBUILD" = true ]; then
    echo -e "${YELLOW}  --force: cleaning workspace dist...${NC}"
    rm -rf "${CHAT_CORE_DIR}/dist" "${CHAT_DIR}/dist"
    WS_NEEDS_BUILD=true
elif [ ! -d "${CHAT_CORE_DIR}/dist" ] || [ ! -d "${CHAT_DIR}/dist" ] || \
     [ -z "$(find "${CHAT_CORE_DIR}/dist" -name '*.d.ts' 2>/dev/null)" ] || \
     [ -z "$(find "${CHAT_DIR}/dist" -name '*.d.ts' 2>/dev/null)" ]; then
    WS_NEEDS_BUILD=true
else
    CORE_NEWEST_SRC=$(find "${CHAT_CORE_DIR}/src" -type f -printf '%T@\n' 2>/dev/null | sort -rn | head -1)
    CORE_OLDEST_DIST=$(find "${CHAT_CORE_DIR}/dist" -type f -printf '%T@\n' 2>/dev/null | sort -n | head -1)
    CHAT_NEWEST_SRC=$(find "${CHAT_DIR}/src" -type f -printf '%T@\n' 2>/dev/null | sort -rn | head -1)
    CHAT_OLDEST_DIST=$(find "${CHAT_DIR}/dist" -type f -printf '%T@\n' 2>/dev/null | sort -n | head -1)
    if [ -n "$CORE_NEWEST_SRC" ] && [ -n "$CORE_OLDEST_DIST" ] && \
       [ "$(echo "$CORE_NEWEST_SRC > $CORE_OLDEST_DIST" | bc 2>/dev/null)" = "1" ]; then
        WS_NEEDS_BUILD=true
    elif [ -n "$CHAT_NEWEST_SRC" ] && [ -n "$CHAT_OLDEST_DIST" ] && \
         [ "$(echo "$CHAT_NEWEST_SRC > $CHAT_OLDEST_DIST" | bc 2>/dev/null)" = "1" ]; then
        WS_NEEDS_BUILD=true
    fi
fi

if [ "$WS_NEEDS_BUILD" = true ]; then
    echo -e "${YELLOW}[2/4] Building workspace packages on tmpfs...${NC}"
    WS_START=$(date +%s)
    (cd "${CHAT_CORE_DIR}" && pnpm build) && \
    (cd "${CHAT_DIR}" && pnpm build)
    if [ $? -ne 0 ]; then
        echo -e "${RED}  Workspace package build failed!${NC}"
        exit 1
    fi
    WS_END=$(date +%s)
    echo -e "${GREEN}  Workspace packages built in $((WS_END - WS_START))s${NC}"
else
    echo -e "${GRAY}[2/4] Workspace packages already built, skipped${NC}"
fi

# ══ Step 3: Build navigator-frontend on tmpfs ════════════════════════════════
FRONTEND_DIR="${TMPFS_DIR}/packages/navigator-frontend"

echo -e "${YELLOW}[3/4] Building navigator-frontend on tmpfs...${NC}"
BUILD_START=$(date +%s)
(cd "${FRONTEND_DIR}" && pnpm build) > "${LOG_DIR}/frontend-build-tmpfs.log" 2>&1
BUILD_RC=$?
BUILD_END=$(date +%s)
BUILD_SECS=$((BUILD_END - BUILD_START))

if [ $BUILD_RC -ne 0 ]; then
    echo -e "${RED}  Build failed! (${BUILD_SECS}s) Check logs/frontend-build-tmpfs.log${NC}"
    tail -20 "${LOG_DIR}/frontend-build-tmpfs.log"
    exit 1
fi
echo -e "${GREEN}  Build complete in ${BUILD_SECS}s${NC}"

# ══ Step 4: Copy dist back ═══════════════════════════════════════════════════
echo -e "${YELLOW}[4/4] Copying dist back to project...${NC}"
ORIGINAL_DIST="${SCRIPT_DIR}/${DIST_REL}"
mkdir -p "${ORIGINAL_DIST}"
rsync -a --delete "${FRONTEND_DIR}/dist/" "${ORIGINAL_DIST}/"
DIST_SIZE=$(du -sh "${ORIGINAL_DIST}" | cut -f1)
echo -e "${GREEN}  dist (${DIST_SIZE}) → ${ORIGINAL_DIST}${NC}"

# Also sync back workspace dist for consistency
rsync -a "${CHAT_CORE_DIR}/dist/" "${SCRIPT_DIR}/packages/foggy-chat-core/dist/"
rsync -a "${CHAT_DIR}/dist/" "${SCRIPT_DIR}/packages/foggy-chat/dist/"

# ══ Summary ══════════════════════════════════════════════════════════════════
echo ""
echo -e "${GREEN}${BOLD}  ✓ Total: sync ${SYNC_MS}ms + build ${BUILD_SECS}s${NC}"
TMPFS_TOTAL=$(du -sh "${TMPFS_DIR}" | cut -f1)
SHMA=$(df -h "${TMPFS_BASE}" --output=avail | tail -1 | tr -d ' ')
echo -e "${GRAY}  tmpfs usage: ${TMPFS_TOTAL} (${SHMA} free on /dev/shm)${NC}"
echo -e "${GRAY}  Clean cache: ./tmpfs-build-frontend.sh --clean${NC}"

if [ "$BUILD_ONLY" = true ]; then
    echo ""
    echo -e "${GREEN}  Build finished. Nginx not restarted (--build-only).${NC}"
    echo ""
    exit 0
fi

# ══ Restart Nginx ════════════════════════════════════════════════════════════
DOCKER_DIR="${SCRIPT_DIR}/docker"
CONTAINER_NAME="foggy-navigator-nginx"
NGINX_PORT=80

echo ""
echo -e "${YELLOW}  Restarting Nginx container...${NC}"

if (cd "$DOCKER_DIR" && docker compose up -d --force-recreate nginx) > /dev/null 2>&1; then
    :
elif (cd "$DOCKER_DIR" && docker-compose up -d --force-recreate nginx) > /dev/null 2>&1; then
    :
else
    echo -e "${RED}  docker compose up failed!${NC}"
    exit 1
fi

sleep 1
if docker ps --format '{{.Names}}' 2>/dev/null | grep -q "^${CONTAINER_NAME}$"; then
    echo ""
    echo -e "${GREEN}╔════════════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║  Frontend (Nginx) Started Successfully!        ║${NC}"
    echo -e "${GREEN}╚════════════════════════════════════════════════╝${NC}"
    echo ""
    echo -e "${CYAN}  URL:        http://localhost:${NGINX_PORT}${NC}"
    echo -e "${CYAN}  Container:  ${CONTAINER_NAME}${NC}"
    echo -e "${CYAN}  Login:      root / root123${NC}"
    echo ""
    echo -e "${GRAY}  Rebuild:    ./tmpfs-build-frontend.sh${NC}"
    echo -e "${GRAY}  Nginx only: ./start-build-frontend.sh --skip-build${NC}"
    echo -e "${GRAY}  Clean:      ./tmpfs-build-frontend.sh --clean${NC}"
    echo ""
else
    echo -e "${RED}  Container failed to start! Check: docker logs ${CONTAINER_NAME}${NC}"
    exit 1
fi
