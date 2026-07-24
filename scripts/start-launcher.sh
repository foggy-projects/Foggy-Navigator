#!/bin/bash
# Coding Agent Launcher - Start Script
# Usage: chmod +x scripts/start-launcher.sh && ./scripts/start-launcher.sh

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Configuration
JAVA_CMD="java"
BACKEND_PORT=8112
LOG_DIR="$REPO_ROOT/logs"
JAR_PATH="$REPO_ROOT/launcher/target/launcher-1.0.0-SNAPSHOT.jar"
ENV_FILE="$REPO_ROOT/launcher/.env"
SECRET_TREE_DIR="${NAVIGATOR_ROOT_SECRET_TREE:-${XDG_CONFIG_HOME:-$HOME/.config}/foggy-navigator/launcher-root-secrets}"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
GRAY='\033[0;37m'
NC='\033[0m' # No Color

# Load environment variables from .env file if exists
if [ -f "$ENV_FILE" ]; then
    echo -e "${GRAY}Loading configuration from $ENV_FILE${NC}"
    export $(cat "$ENV_FILE" | grep -v '^#' | grep -v '^$' | xargs)
else
    echo -e "${YELLOW}Warning: $ENV_FILE not found, using defaults${NC}"
fi

# Import system-root properties from a protected Spring config tree. Only the
# directory path enters the service environment; credential values stay in
# permission-restricted files and never enter argv or environment diagnostics.
if [ ! -d "$SECRET_TREE_DIR" ]; then
    echo -e "${RED}System-root secret tree not found: $SECRET_TREE_DIR${NC}"
    exit 1
fi
SECRET_TREE_MODE=$(stat -c '%a' "$SECRET_TREE_DIR" 2>/dev/null || true)
if [ -z "$SECRET_TREE_MODE" ] || [ $((8#$SECRET_TREE_MODE & 077)) -ne 0 ]; then
    echo -e "${RED}Refusing insecure secret-tree permissions: $SECRET_TREE_DIR${NC}"
    exit 1
fi
for SECRET_KEY in system.root.username system.root.password system.root.email system.root.password-reset; do
    SECRET_FILE="$SECRET_TREE_DIR/$SECRET_KEY"
    SECRET_MODE=$(stat -c '%a' "$SECRET_FILE" 2>/dev/null || true)
    if [ ! -f "$SECRET_FILE" ] || [ -z "$SECRET_MODE" ] || [ $((8#$SECRET_MODE & 077)) -ne 0 ]; then
        echo -e "${RED}Missing or insecure system-root property file: $SECRET_FILE${NC}"
        exit 1
    fi
done
SECRET_TREE_IMPORT="optional:configtree:${SECRET_TREE_DIR%/}/"
if [ -n "${SPRING_CONFIG_IMPORT:-}" ]; then
    export SPRING_CONFIG_IMPORT="$SECRET_TREE_IMPORT,$SPRING_CONFIG_IMPORT"
else
    export SPRING_CONFIG_IMPORT="$SECRET_TREE_IMPORT"
fi
unset ROOT_USERNAME ROOT_PASSWORD ROOT_EMAIL ROOT_PASSWORD_RESET
unset SYSTEM_ROOT_USERNAME SYSTEM_ROOT_PASSWORD SYSTEM_ROOT_EMAIL SYSTEM_ROOT_PASSWORD_RESET

SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE:-docker}
JAVA_HEAP_MIN=${JAVA_HEAP_MIN:-1g}
JAVA_HEAP_MAX=${JAVA_HEAP_MAX:-4g}

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

    (cd "$REPO_ROOT" && mvn package -pl launcher -am -DskipTests)

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
echo -e "${GRAY}  Profile: ${SPRING_PROFILES_ACTIVE}${NC}"
echo -e "${GRAY}  Port: ${BACKEND_PORT}${NC}"
echo -e "${GRAY}  JVM Heap: ${JAVA_HEAP_MIN} - ${JAVA_HEAP_MAX}${NC}"
echo ""

# JVM tuning defaults target an 8G development host. Override JAVA_HEAP_MIN and
# JAVA_HEAP_MAX in launcher/.env for larger production-like workloads.
JAVA_OPTS="-Xms${JAVA_HEAP_MIN} -Xmx${JAVA_HEAP_MAX} \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=200 \
    -XX:+ParallelRefProcEnabled \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath=${LOG_DIR}/heap-dump.hprof"

# Start service in background. Use a new session when available so callers that
# clean up their process group do not take the backend down with them.
cd "$REPO_ROOT"
START_PREFIX=()
if command -v setsid >/dev/null 2>&1; then
    START_PREFIX=(setsid)
fi

nohup "${START_PREFIX[@]}" "${JAVA_CMD}" ${JAVA_OPTS} -Dfile.encoding=UTF-8 \
    -jar "$JAR_PATH" \
    --spring.profiles.active="${SPRING_PROFILES_ACTIVE}" \
    > "$LOG_DIR/backend.log" 2> "$LOG_DIR/backend-error.log" &
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
