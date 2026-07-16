#!/bin/bash
# Codex Agent Worker 启动脚本 (Linux/macOS)

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

load_env_file() {
    local env_file="$1"
    [ -f "$env_file" ] || return 0

    while IFS= read -r line || [ -n "$line" ]; do
        line="${line#"${line%%[![:space:]]*}"}"
        line="${line%"${line##*[![:space:]]}"}"
        [ -n "$line" ] || continue
        case "$line" in \#*) continue ;; esac

        local key="${line%%=*}"
        local value="${line#*=}"
        key="${key%"${key##*[![:space:]]}"}"
        key="${key#"${key%%[![:space:]]*}"}"
        [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue

        value="${value#"${value%%[![:space:]]*}"}"
        value="${value%"${value##*[![:space:]]}"}"
        if [ ${#value} -ge 2 ]; then
            local first="${value:0:1}"
            local last="${value: -1}"
            if { [ "$first" = '"' ] && [ "$last" = '"' ]; } || { [ "$first" = "'" ] && [ "$last" = "'" ]; }; then
                value="${value:1:${#value}-2}"
            fi
        fi

        export "$key=$value"
    done < "$env_file"
}

# Load .env before starting so this worker directory overrides inherited CODEX_* variables.
load_env_file ".env"
PORT="${CODEX_WORKER_PORT:-3051}"

echo "========================================"
echo "  Codex Agent Worker"
echo "  Port: $PORT"
echo "========================================"

# Existing Worker safety gate. The stop script verifies workspace ownership and
# task quiescence before it sends a graceful drain/stop request. Never replace
# a Worker just because it happens to own this port.
echo ""
echo "[1/4] Verifying any existing Worker can stop safely..."
if ! bash "$SCRIPT_DIR/stop.sh"; then
    echo "  Refusing to start a replacement: the existing Worker did not prove safe quiescence." >&2
    exit 2
fi

# 安装依赖
echo ""
echo "[2/4] Checking dependencies..."
if [ ! -d "node_modules" ]; then
    echo "  Running npm install..."
    npm install > /dev/null 2>&1
    echo "  Dependencies installed."
else
    echo "  node_modules exists, skipping install."
fi

if [ ! -f "scripts/ensure-sdk.mjs" ]; then
    echo "  SDK preflight script not found: $SCRIPT_DIR/scripts/ensure-sdk.mjs"
    exit 1
fi
node scripts/ensure-sdk.mjs --worker-dir "$SCRIPT_DIR"

# 确保 logs 目录存在
mkdir -p logs

# 后台启动
echo ""
echo "[3/4] Starting Codex Worker..."
PID_FILE="logs/worker.pid"
rm -f "$PID_FILE"
WORKER_PID=""
if command -v setsid >/dev/null 2>&1; then
    setsid -f sh -c 'echo $$ > logs/worker.pid; exec npx tsx src/index.ts' > logs/worker.log 2> logs/worker-error.log < /dev/null
else
    nohup sh -c 'echo $$ > logs/worker.pid; exec npx tsx src/index.ts' > logs/worker.log 2> logs/worker-error.log < /dev/null &
    WORKER_PID=$!
    disown "$WORKER_PID" 2>/dev/null || true
fi
sleep 1
PID_FROM_FILE=$(cat "$PID_FILE" 2>/dev/null || true)
if [ -n "$PID_FROM_FILE" ]; then
    WORKER_PID="$PID_FROM_FILE"
fi
if [ -z "$WORKER_PID" ]; then
    echo "  Worker PID file was not created!"
    echo "  Error log:"
    tail -20 logs/worker-error.log 2>/dev/null || true
    exit 1
fi
echo "  PID: $WORKER_PID"

# 等待就绪
echo ""
echo "[4/4] Waiting for worker to be ready..."
MAX_WAIT=60
WAITED=0

check_health() {
    local base_url health_body
    for base_url in "http://127.0.0.1:$PORT" "http://localhost:$PORT"; do
        health_body=$(curl -fsS --max-time 2 "$base_url/health" 2>/dev/null || true)
        if [ -n "$health_body" ] && printf '%s' "$health_body" | node -e '
let input = "";
process.stdin.on("data", chunk => input += chunk);
process.stdin.on("end", () => {
  try {
    const health = JSON.parse(input);
    process.exit(health.status === "ok" && health.codex_sdk_available === true && health.codex_sdk_compatible === true ? 0 : 1);
  } catch { process.exit(1); }
});'; then
            return 0
        fi
    done
    return 1
}

while [ $WAITED -lt $MAX_WAIT ]; do
    sleep 1
    WAITED=$((WAITED + 1))

    if check_health; then
        sleep 3
        if ! kill -0 $WORKER_PID 2>/dev/null; then
            echo ""
            echo "  Worker exited after readiness!"
            echo "  Error log:"
            tail -20 logs/worker-error.log 2>/dev/null || true
            exit 1
        fi
        if ! check_health; then
            echo ""
            echo "  Worker health failed after readiness!"
            echo "  Error log:"
            tail -20 logs/worker-error.log 2>/dev/null || true
            exit 1
        fi
        echo ""
        echo "========================================"
        echo "  Codex Worker is READY!"
        echo "  URL: http://localhost:$PORT"
        echo "  Health: http://localhost:$PORT/health"
        echo "  PID: $WORKER_PID"
        echo "========================================"
        exit 0
    fi

    # 检查进程是否崩溃
    if ! kill -0 $WORKER_PID 2>/dev/null; then
        echo ""
        echo "  Worker process exited unexpectedly!"
        echo "  Error log:"
        tail -20 logs/worker-error.log 2>/dev/null || true
        exit 1
    fi

    echo "  Waiting... ($WAITED/$MAX_WAIT)"
done

echo ""
echo "  Worker failed to start within ${MAX_WAIT}s!"
tail -20 logs/worker-error.log 2>/dev/null || true
exit 1
