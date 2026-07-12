#!/bin/bash
# Navigator Frontend - Build + Nginx Deploy Script
# Usage: chmod +x scripts/start-frontend-build.sh && ./scripts/start-frontend-build.sh

# Configuration
NGINX_PORT=80
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
FRONTEND_DIR="$REPO_ROOT/packages/navigator-frontend"
BUILD_OUTPUT_DIR="$REPO_ROOT/dist/nginx"
NGINX_CONFIG_FILE="$REPO_ROOT/nginx/navigator.conf"
LOCKFILE="$REPO_ROOT/pnpm-lock.yaml"
MODULES_META="$REPO_ROOT/node_modules/.modules.yaml"
FRONTEND_NODE_MAX_OLD_SPACE_SIZE="${FRONTEND_NODE_MAX_OLD_SPACE_SIZE:-4096}"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
GRAY='\033[0;37m'
NC='\033[0m' # No Color

needs_pnpm_install() {
    local required_paths=(
        "$REPO_ROOT/packages/navigator-frontend/node_modules/@foggy/chat"
        "$REPO_ROOT/packages/foggy-chat/node_modules/@foggy/chat-core"
        "$REPO_ROOT/packages/foggy-chat/node_modules/vue-virtual-scroller"
    )

    if [ ! -f "$MODULES_META" ]; then
        return 0
    fi

    if [ -f "$LOCKFILE" ] && [ "$LOCKFILE" -nt "$MODULES_META" ]; then
        return 0
    fi

    for path in "${required_paths[@]}"; do
        if [ ! -e "$path" ]; then
            return 0
        fi
    done

    return 1
}

echo ""
echo -e "${CYAN}========================================${NC}"
echo -e "${CYAN}  Navigator Frontend Build + Nginx${NC}"
echo -e "${CYAN}========================================${NC}"
echo ""

# Check pnpm
if ! command -v pnpm &> /dev/null; then
    echo -e "${RED}  pnpm not found! Install: npm install -g pnpm${NC}"
    exit 1
fi

# Install dependencies if needed
if needs_pnpm_install; then
    echo -e "${YELLOW}[1/4] Installing dependencies (workspace missing/stale)...${NC}"
    (cd "$REPO_ROOT" && pnpm install --no-frozen-lockfile)
    if [ $? -ne 0 ]; then
        echo -e "${RED}  pnpm install failed!${NC}"
        exit 1
    fi
else
    echo -e "${GREEN}[1/4] Dependencies already installed${NC}"
fi

# Build workspace packages if dist is missing
if [ ! -d "$REPO_ROOT/packages/foggy-chat-core/dist" ] || [ ! -d "$REPO_ROOT/packages/foggy-chat/dist" ]; then
    echo -e "${YELLOW}[2/4] Building workspace packages...${NC}"
    (cd "$REPO_ROOT/packages/foggy-chat-core" && pnpm build) && (cd "$REPO_ROOT/packages/foggy-chat" && pnpm build)
    if [ $? -ne 0 ]; then
        echo -e "${RED}  Workspace package build failed!${NC}"
        exit 1
    fi
else
    echo -e "${GREEN}[2/4] Workspace packages already built${NC}"
fi

# Build frontend
echo -e "${YELLOW}[3/4] Building frontend...${NC}"
cd "$FRONTEND_DIR"
NODE_OPTIONS="${NODE_OPTIONS:+$NODE_OPTIONS }--max-old-space-size=$FRONTEND_NODE_MAX_OLD_SPACE_SIZE" pnpm build
if [ $? -ne 0 ]; then
    echo -e "${RED}  Frontend build failed!${NC}"
    exit 1
fi
cd "$REPO_ROOT"

# Move build output to nginx directory
echo -e "${YELLOW}[4/4] Preparing nginx directory...${NC}"
mkdir -p "$BUILD_OUTPUT_DIR"
rm -rf "${BUILD_OUTPUT_DIR:?}"/*
cp -r "$FRONTEND_DIR/dist/"* "$BUILD_OUTPUT_DIR/"

# Generate nginx config if not exists
echo -e "${CYAN}Generating nginx configuration...${NC}"
mkdir -p "$REPO_ROOT/nginx"
cat > "$NGINX_CONFIG_FILE" << 'NGINX_CONFIG_END'
server {
    listen 80;
    server_name localhost;

    root /path/to/Foggy-Navigator/dist/nginx;
    index index.html;

    # Gzip compression
    gzip on;
    gzip_vary on;
    gzip_min_length 1024;
    gzip_types text/plain text/css text/xml text/javascript application/javascript application/xml+rss application/json;

    # SPA router support - all routes go to index.html
    location / {
        try_files $uri $uri/ /index.html;
    }

    # API proxy to backend (if needed)
    location /api/ {
        proxy_pass http://localhost:8112/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # SSE support
    location /sse/ {
        proxy_pass http://localhost:8112/sse/;
        proxy_buffering off;
        proxy_cache off;
        proxy_set_header Connection '';
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_read_timeout 86400;
    }

    # Static assets caching
    location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)$ {
        expires 1y;
        add_header Cache-Control "public, immutable";
    }

    # Security headers
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-XSS-Protection "1; mode=block" always;
}
NGINX_CONFIG_END

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  Build Completed Successfully!${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "${CYAN}  Build output: ${BUILD_OUTPUT_DIR}${NC}"
echo -e "${CYAN}  Nginx config: ${NGINX_CONFIG_FILE}${NC}"
echo ""
echo -e "${YELLOW}To deploy with nginx:${NC}"
echo -e "${GRAY}  1. Update 'root' path in ${NGINX_CONFIG_FILE}:${NC}"
echo -e "${GRAY}     root $(pwd)/${BUILD_OUTPUT_DIR};${NC}"
echo ""
echo -e "${GRAY}  2. Copy config to nginx:${NC}"
echo -e "${GRAY}     sudo cp ${NGINX_CONFIG_FILE} /etc/nginx/sites-available/navigator${NC}"
echo -e "${GRAY}     sudo ln -sf /etc/nginx/sites-available/navigator /etc/nginx/sites-enabled/${NC}"
echo ""
echo -e "${GRAY}  3. Test and restart nginx:${NC}"
echo -e "${GRAY}     sudo nginx -t${NC}"
echo -e "${GRAY}     sudo systemctl reload nginx${NC}"
echo ""
echo -e "${CYAN}  After nginx restart, access: http://localhost${NC}"
echo ""
echo -e "${GREEN}========================================${NC}"
