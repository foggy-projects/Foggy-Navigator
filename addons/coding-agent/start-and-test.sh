#!/bin/bash

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

PROJECT_DIR="/d/foggy-projects/Foggy-Navigator/addons/coding-agent"
LOG_DIR="${PROJECT_DIR}/logs"
PID_FILE="${PROJECT_DIR}/app.pid"
PORT=8112

echo -e "${YELLOW}========================================${NC}"
echo -e "${YELLOW}Starting Coding Agent Application${NC}"
echo -e "${YELLOW}========================================${NC}"

# 加载 .env 文件
if [ -f "${PROJECT_DIR}/.env" ]; then
    echo -e "${GREEN}Loading .env file...${NC}"
    export $(cat "${PROJECT_DIR}/.env" | grep -v '^#' | xargs)
else
    echo -e "${RED}.env file not found!${NC}"
    exit 1
fi

# 创建日志目录
mkdir -p "$LOG_DIR"

# 检查端口是否已被占用
if lsof -Pi :$PORT -sTCP:LISTEN -t >/dev/null 2>&1; then
    echo -e "${RED}Port $PORT is already in use. Killing existing process...${NC}"
    lsof -ti:$PORT | xargs kill -9 2>/dev/null || true
    sleep 2
fi

# 启动应用（使用docker配置）
echo -e "${YELLOW}Starting Spring Boot application with docker profile...${NC}"
cd "$PROJECT_DIR"

java -jar target/coding-agent-*.jar \
    --spring.profiles.active=docker \
    --server.port=$PORT \
    > "$LOG_DIR/app.log" 2>&1 &

APP_PID=$!
echo $APP_PID > "$PID_FILE"

echo -e "${GREEN}Application started with PID: $APP_PID${NC}"
echo -e "${GREEN}Logs available at: $LOG_DIR/app.log${NC}"
echo ""

# 等待应用启动
echo -e "${YELLOW}Waiting for application to start...${NC}"
for i in {1..30}; do
    if curl -s http://localhost:$PORT/actuator/health > /dev/null 2>&1; then
        echo -e "${GREEN}Application is ready!${NC}"
        sleep 2
        break
    fi
    echo "Attempt $i/30..."
    sleep 1
done

# 检查应用是否成功启动
if ! curl -s http://localhost:$PORT/actuator/health > /dev/null 2>&1; then
    echo -e "${RED}Application failed to start${NC}"
    echo -e "${RED}Check logs at: $LOG_DIR/app.log${NC}"
    exit 1
fi

echo ""
echo -e "${YELLOW}========================================${NC}"
echo -e "${YELLOW}Running Integration Tests${NC}"
echo -e "${YELLOW}========================================${NC}"

cd "${PROJECT_DIR}/integration-tests"
npm run test:run

TEST_RESULT=$?

echo ""
echo -e "${YELLOW}========================================${NC}"
if [ $TEST_RESULT -eq 0 ]; then
    echo -e "${GREEN}All tests passed!${NC}"
else
    echo -e "${RED}Some tests failed. Check output above.${NC}"
fi
echo -e "${YELLOW}========================================${NC}"

echo ""
echo -e "${YELLOW}Application is still running (PID: $APP_PID)${NC}"
echo -e "${YELLOW}To stop: kill $APP_PID${NC}"
echo -e "${YELLOW}Logs: $LOG_DIR/coding-agent.log${NC}"

exit $TEST_RESULT
