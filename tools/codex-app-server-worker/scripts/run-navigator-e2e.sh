#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
WORKER_DIR=$(cd -- "$SCRIPT_DIR/.." && pwd)
REPO_ROOT=$(cd -- "$WORKER_DIR/../.." && pwd)
MOCK_DIR="$REPO_ROOT/tools/mock-llm-service"
MOCK_PORT=${BUG007_MOCK_PORT:-18200}
WORKER_PORT=${BUG007_WORKER_PORT:-13062}
WORKER_ID=${BUG007_WORKER_ID:-bug007-repo-e2e}
WORKER_TOKEN=${BUG007_WORKER_TOKEN:-bug007-e2e-token}
TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)
ARTIFACT_DIR="$REPO_ROOT/temp/test-artifacts/bug007-navigator-e2e/$TIMESTAMP"
RUNTIME_DIR=$(mktemp -d "/tmp/foggy-bug007-navigator-e2e.XXXXXX")
MOCK_PID=
WORKER_PID=

mkdir -p "$ARTIFACT_DIR" "$RUNTIME_DIR/state" "$RUNTIME_DIR/codex-home" \
  "$RUNTIME_DIR/run" "$RUNTIME_DIR/logs"

cleanup_group() {
  local pid=${1:-}
  [[ -n "$pid" ]] || return 0
  kill -0 "$pid" 2>/dev/null || return 0
  local sid
  sid=$(ps -o sid= -p "$pid" 2>/dev/null | tr -d ' ' || true)
  if [[ "$sid" == "$pid" ]]; then
    kill -TERM -- "-$pid" 2>/dev/null || true
  else
    kill -TERM "$pid" 2>/dev/null || true
  fi
  for _ in $(seq 1 50); do
    kill -0 "$pid" 2>/dev/null || return 0
    sleep 0.1
  done
  if [[ "$sid" == "$pid" ]]; then
    kill -KILL -- "-$pid" 2>/dev/null || true
  else
    kill -KILL "$pid" 2>/dev/null || true
  fi
}

cleanup() {
  local exit_code=$?
  cleanup_group "$WORKER_PID"
  cleanup_group "$MOCK_PID"
  wait "$WORKER_PID" "$MOCK_PID" 2>/dev/null || true
  rm -rf "$RUNTIME_DIR"
  exit "$exit_code"
}
trap cleanup EXIT INT TERM

require_file() {
  [[ -f "$1" ]] || {
    echo "Required file is missing: $1" >&2
    exit 1
  }
}

assert_port_free() {
  local port=$1
  if "$MOCK_DIR/.venv/bin/python" - "$port" <<'PY'
import socket
import sys

port = int(sys.argv[1])
sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
try:
    sock.bind(("127.0.0.1", port))
except OSError:
    raise SystemExit(1)
finally:
    sock.close()
PY
  then
    return 0
  fi
  echo "Refusing to replace an existing listener on 127.0.0.1:$port" >&2
  exit 1
}

wait_for_http() {
  local url=$1
  local token=${2:-}
  for _ in $(seq 1 120); do
    if [[ -n "$token" ]]; then
      curl -fsS -H "Authorization: Bearer $token" "$url" >/dev/null 2>&1 && return 0
    else
      curl -fsS "$url" >/dev/null 2>&1 && return 0
    fi
    sleep 0.25
  done
  echo "Timed out waiting for $url" >&2
  return 1
}

require_file "$MOCK_DIR/.venv/bin/uvicorn"
require_file "$WORKER_DIR/node_modules/@openai/codex/package.json"
assert_port_free "$MOCK_PORT"
assert_port_free "$WORKER_PORT"

WORKER_VERSION=$(node -p "require('$WORKER_DIR/package.json').version")
CLI_VERSION=$(node -p "require('$WORKER_DIR/node_modules/@openai/codex/package.json').version")
[[ "$WORKER_VERSION" == "0.3.21" ]] || {
  echo "BUG-007 Navigator E2E requires Worker 0.3.21, found $WORKER_VERSION" >&2
  exit 1
}
[[ "$CLI_VERSION" == "0.144.3" ]] || {
  echo "BUG-007 Navigator E2E requires @openai/codex 0.144.3, found $CLI_VERSION" >&2
  exit 1
}

echo "Building repo-local codex-app-server-worker $WORKER_VERSION (CLI $CLI_VERSION)"
(cd "$WORKER_DIR" && npm run build) 2>&1 | tee "$ARTIFACT_DIR/worker-build.log"

(
  cd "$MOCK_DIR"
  exec setsid env \
    -u OPENAI_API_KEY -u CODEX_API_KEY -u AZURE_OPENAI_API_KEY \
    -u ANTHROPIC_API_KEY -u GEMINI_API_KEY -u GOOGLE_API_KEY \
    MOCK_LLM_PORT="$MOCK_PORT" \
    MOCK_LLM_RESPONSES_DIR="$MOCK_DIR/responses" \
    .venv/bin/uvicorn mock_llm.main:app --host 127.0.0.1 --port "$MOCK_PORT"
) >"$ARTIFACT_DIR/mock.log" 2>&1 &
MOCK_PID=$!

STATE_KEY=$(openssl rand -base64 32)
(
  cd "$WORKER_DIR"
  exec setsid env \
    -u CODEX_API_KEY -u AZURE_OPENAI_API_KEY -u ANTHROPIC_API_KEY \
    -u GEMINI_API_KEY -u GOOGLE_API_KEY \
    PATH="$WORKER_DIR/node_modules/.bin:$PATH" \
    CODEX_APP_SERVER_WORKER_PORT="$WORKER_PORT" \
    CODEX_APP_SERVER_WORKER_HOST=127.0.0.1 \
    CODEX_APP_SERVER_WORKER_NAME="$WORKER_ID" \
    CODEX_APP_SERVER_NAVIGATOR_WORKER_ID="$WORKER_ID" \
    CODEX_APP_SERVER_WORKER_TOKEN="$WORKER_TOKEN" \
    CODEX_APP_SERVER_ALLOWED_CWDS="$REPO_ROOT" \
    CODEX_APP_SERVER_STATE_KEY="$STATE_KEY" \
    CODEX_APP_SERVER_STATE_DIR="$RUNTIME_DIR/state" \
    CODEX_APP_SERVER_RUN_DIR="$RUNTIME_DIR/run" \
    CODEX_APP_SERVER_LOG_DIR="$RUNTIME_DIR/logs" \
    CODEX_APP_SERVER_RUNTIME_ID=bug007-navigator-e2e \
    CODEX_APP_SERVER_RUNTIME_REVISION=1 \
    OPENAI_API_KEY=mock-key \
    OPENAI_BASE_URL="http://127.0.0.1:$MOCK_PORT/v1" \
    CODEX_HOME="$RUNTIME_DIR/codex-home" \
    CODEX_DEFAULT_MODEL=codex-terra \
    node dist/index.js
) >"$ARTIFACT_DIR/worker.log" 2>&1 &
WORKER_PID=$!

wait_for_http "http://127.0.0.1:$MOCK_PORT/admin/health"
wait_for_http "http://127.0.0.1:$WORKER_PORT/health" "$WORKER_TOKEN"

curl -fsS -H "Authorization: Bearer $WORKER_TOKEN" \
  "http://127.0.0.1:$WORKER_PORT/health" >"$ARTIFACT_DIR/worker-health-before.json"

echo "Running Navigator public-API E2E with isolated Java/H2, Worker, CLI, and mock Responses API"
(
  cd "$REPO_ROOT"
  mvn -pl launcher -am \
    -Dtest=CodexAppServerNavigatorE2ETest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Dbug007.e2e.enabled=true \
    -Dbug007.worker.base-url="http://127.0.0.1:$WORKER_PORT" \
    -Dbug007.mock.base-url="http://127.0.0.1:$MOCK_PORT" \
    -Dbug007.worker.id="$WORKER_ID" \
    -Dbug007.worker.token="$WORKER_TOKEN" test
) 2>&1 | tee "$ARTIFACT_DIR/maven-e2e.log"

curl -fsS -H "Authorization: Bearer $WORKER_TOKEN" \
  "http://127.0.0.1:$WORKER_PORT/health" >"$ARTIFACT_DIR/worker-health-after.json"
cp "$REPO_ROOT"/launcher/target/surefire-reports/*CodexAppServerNavigatorE2ETest* "$ARTIFACT_DIR/"

echo "BUG-007 Navigator E2E passed. Evidence: $ARTIFACT_DIR"
