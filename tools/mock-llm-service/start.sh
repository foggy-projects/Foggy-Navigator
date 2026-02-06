#!/bin/bash
# Mock LLM Service 启动脚本 (Linux/Mac)

set -e

SERVICE_NAME="mock-llm-service"
IMAGE_NAME="foggy/mock-llm-service:latest"
PORT=8200
REBUILD=false

# 解析参数
while [[ $# -gt 0 ]]; do
    case $1 in
        --rebuild) REBUILD=true; shift ;;
        *) shift ;;
    esac
done

echo "========================================"
echo "  Mock LLM Service Launcher"
echo "========================================"

# 进入脚本目录
cd "$(dirname "$0")"

# 检查 Docker
if ! command -v docker &> /dev/null; then
    echo "[ERROR] Docker not found. Please install Docker."
    exit 1
fi

# 停止已存在的容器
if docker ps -aq -f "name=$SERVICE_NAME" | grep -q .; then
    echo "[INFO] Stopping existing container..."
    docker stop $SERVICE_NAME 2>/dev/null || true
    docker rm $SERVICE_NAME 2>/dev/null || true
fi

# 构建镜像
if [ "$REBUILD" = true ] || ! docker images -q $IMAGE_NAME | grep -q .; then
    echo "[INFO] Building Docker image..."
    docker build -t $IMAGE_NAME .
    echo "[OK] Image built successfully"
fi

# 启动容器
echo "[INFO] Starting container..."
docker run -d \
    --name $SERVICE_NAME \
    -p $PORT:8200 \
    -v "$(pwd)/responses:/app/responses:ro" \
    -e MOCK_LLM_LOG_LEVEL=INFO \
    $IMAGE_NAME

# 等待服务就绪
echo "[INFO] Waiting for service to be ready..."
for i in {1..30}; do
    if curl -s "http://localhost:$PORT/admin/health" > /dev/null 2>&1; then
        break
    fi
    sleep 1
done

# 检查是否启动成功
if ! curl -s "http://localhost:$PORT/admin/health" > /dev/null 2>&1; then
    echo "[ERROR] Service failed to start!"
    docker logs $SERVICE_NAME
    exit 1
fi

echo ""
echo "========================================"
echo "  Mock LLM Service Started!"
echo "========================================"
echo ""
echo "  API Endpoint:  http://localhost:$PORT/v1/chat/completions"
echo "  Admin API:     http://localhost:$PORT/admin/responses"
echo "  Health Check:  http://localhost:$PORT/admin/health"
echo ""
echo "  Stop command:  ./stop.sh"
echo ""

# 显示加载的规则数
RULES=$(curl -s "http://localhost:$PORT/admin/health" | grep -o '"rules_count":[0-9]*' | cut -d: -f2)
echo "[INFO] Loaded $RULES response rules"
