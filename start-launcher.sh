#!/bin/bash
# Coding Agent Launcher - Start Script
# Usage: chmod +x start-launcher.sh && ./start-launcher.sh

# Configuration
JAVA_CMD="java"
BACKEND_PORT=8112
LOG_DIR="logs"
JAR_PATH="launcher/target/launcher-1.0.0-SNAPSHOT.jar"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
GRAY='\033[0;37m'
NC='\033[0m' # No Color

echo ""
echo -e "${CYAN}========================================${NC}"
echo -e "${CYAN}  Coding Agent Launcher${NC}"
echo -e "${CYAN}========================================${NC}"
echo ""

# Step 1: Stop existing service on port
echo -e "${YELLOW}[1/4] Checking port ${BACKEND_PORT}...${NC}"
PID=$(lsof -ti:${BACKEND_PORT} 2>/dev/null)
if [ ! -z "$PID" ]; then
    PROCESS=$(ps -p $PID -o comm= 2>/dev/null)
    echo -e "${YELLOW}  Port ${BACKEND_PORT} in use by ${PROCESS} (PID=${PID}), stopping...${NC}"
    kill -9 $PID 2>/dev/null
    sleep 3
else
    echo -e "${GRAY}  Port ${BACKEND_PORT} is free${NC}"
fi

echo ""

# Step 2: Build
SKIP_BUILD=false
if [ "$1" = "--skip-build" ]; then
    SKIP_BUILD=true
fi

if [ "$SKIP_BUILD" = true ]; then
    if [ ! -f "$JAR_PATH" ]; then
        echo -e "${YELLOW}[2/4] JAR not found, building anyway...${NC}"
        SKIP_BUILD=false
    else
        echo -e "${GRAY}[2/4] Build skipped (--skip-build)${NC}"
    fi
fi

if [ "$SKIP_BUILD" = false ]; then
    echo -e "${YELLOW}[2/4] Building project (mvn package)...${NC}"
    echo -e "${GRAY}  This may take 30-60 seconds...${NC}"

    mvn package -pl launcher -am -DskipTests

    if [ $? -eq 0 ]; then
        echo -e "${GREEN}  Build successful!${NC}"
    else
        echo -e "${RED}  Build failed!${NC}"
        exit 1
    fi
fi

echo ""

# Step 3: Create logs directory
if [ ! -d "$LOG_DIR" ]; then
    mkdir -p "$LOG_DIR"
    echo -e "${YELLOW}[3/4] Created logs directory${NC}"
else
    echo -e "${YELLOW}[3/4] Logs directory ready${NC}"
fi

echo ""

# Step 4: Start the service
echo -e "${YELLOW}[4/4] Starting backend service...${NC}"
echo -e "${GRAY}  Java: ${JAVA_CMD}${NC}"
echo -e "${GRAY}  JAR: ${JAR_PATH}${NC}"
echo -e "${GRAY}  Profile: docker${NC}"
echo -e "${GRAY}  Port: ${BACKEND_PORT}${NC}"
echo ""

# Start service in background
nohup java -Dfile.encoding=UTF-8 -jar "$JAR_PATH" --spring.profiles.active=docker > "$LOG_DIR/backend.log" 2> "$LOG_DIR/backend-error.log" &
echo $! > "$LOG_DIR/backend.pid"

echo -e "${GRAY}  Waiting for service to be ready...${NC}"

# Wait for service to start
max_wait=60
waited=0
started=false

while [ $waited -lt $max_wait ]; do
    sleep 2
    waited=$((waited + 2))

    if lsof -ti:${BACKEND_PORT} >/dev/null 2>&1; then
        started=true
        break
    fi

    echo -n "."
done

echo ""
echo ""

if [ "$started" = true ]; then
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}  Service Started Successfully!${NC}"
    echo -e "${GREEN}========================================${NC}"
    echo ""
    echo -e "${CYAN}Service URL: http://localhost:${BACKEND_PORT}${NC}"
    echo -e "${CYAN}Health Check: http://localhost:${BACKEND_PORT}/actuator/health${NC}"
    echo ""
    echo -e "${CYAN}Logs:${NC}"
    echo -e "${GRAY}  - Output: ${LOG_DIR}/backend.log${NC}"
    echo -e "${GRAY}  - Errors: ${LOG_DIR}/backend-error.log${NC}"
    echo ""

    # Test health endpoint
    sleep 5
    if curl -s http://localhost:${BACKEND_PORT}/actuator/health | grep -q '"status":"UP"'; then
        echo -e "${CYAN}Health Check: ${GREEN}UP${NC}"
    else
        echo -e "${CYAN}Health Check: ${YELLOW}Checking...${NC}"
    fi

    echo ""
    echo -e "${GREEN}========================================${NC}"

else
    echo -e "${RED}========================================${NC}"
    echo -e "${RED}  Service Startup Failed!${NC}"
    echo -e "${RED}========================================${NC}"
    echo ""
    echo -e "${RED}Timeout after ${max_wait} seconds${NC}"
    echo ""
    echo -e "${YELLOW}Check logs for details:${NC}"
    echo -e "${GRAY}  ${LOG_DIR}/backend.log${NC}"
    echo -e "${GRAY}  ${LOG_DIR}/backend-error.log${NC}"
    echo ""

    if [ -f "$LOG_DIR/backend-error.log" ]; then
        echo -e "${YELLOW}Last 20 lines of error log:${NC}"
        tail -20 "$LOG_DIR/backend-error.log"
    fi

    exit 1
fi