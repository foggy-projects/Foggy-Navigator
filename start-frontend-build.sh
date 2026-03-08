#!/bin/bash
# Navigator Frontend - Build + Nginx Deploy Script
# Usage: chmod +x start-frontend-build.sh && ./start-frontend-build.sh

# Configuration
NGINX_PORT=80
FRONTEND_DIR="packages/navigator-frontend"
BUILD_OUTPUT_DIR="dist/nginx"
NGINX_CONFIG_FILE="nginx/navigator.conf"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
GRAY='\033[0;37m'
NC='\033[0m' # No Color

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
if [ ! -d "$FRONTEND_DIR/node_modules" ]; then
    echo -e "${YELLOW}[1/4] Installing dependencies...${NC}"
    pnpm install --no-frozen-lockfile
    if [ $? -ne 0 ]; then
        echo -e "${RED}  pnpm install failed!${NC}"
        exit 1
    fi
else
    echo -e "${GREEN}[1/4] Dependencies already installed${NC}"
fi

# Build workspace packages if dist is missing
if [ ! -d "packages/foggy-chat-core/dist" ] || [ ! -d "packages/foggy-chat/dist" ]; then
    echo -e "${YELLOW}[2/4] Building workspace packages...${NC}"
    (cd packages/foggy-chat-core && pnpm build) && (cd packages/foggy-chat && pnpm build)
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
pnpm build
if [ $? -ne 0 ]; then
    echo -e "${RED}  Frontend build failed!${NC}"
    exit 1
fi
cd ../..

# Move build output to nginx directory
echo -e "${YELLOW}[4/4] Preparing nginx directory...${NC}"
mkdir -p "$BUILD_OUTPUT_DIR"
rm -rf "${BUILD_OUTPUT_DIR:?}"/*
cp -r "$FRONTEND_DIR/dist/"* "$BUILD_OUTPUT_DIR/"

# Generate nginx config if not exists
echo -e "${CYAN}Generating nginx configuration...${NC}"
mkdir -p nginx
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