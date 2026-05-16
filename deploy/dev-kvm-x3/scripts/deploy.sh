#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
. "$SCRIPT_DIR/lib.sh"

: "${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD is required in $ENV_FILE}"

SKIP_SYNC=false
SKIP_BUILD=false
BOOTSTRAP=false

for arg in "$@"; do
  case "$arg" in
    --skip-sync) SKIP_SYNC=true ;;
    --skip-build|-s) SKIP_BUILD=true ;;
    --bootstrap) BOOTSTRAP=true ;;
    *)
      echo "Unknown argument: $arg" >&2
      echo "Usage: $0 [--bootstrap] [--skip-sync] [--skip-build]" >&2
      exit 1
      ;;
  esac
done

echo "Deploying Navigator to $(print_target)"

if [ "$BOOTSTRAP" = true ]; then
  bash "$SCRIPT_DIR/01-bootstrap-host.sh"
fi

if [ "$SKIP_SYNC" = false ]; then
  bash "$SCRIPT_DIR/02-sync-code.sh"
fi

bash "$SCRIPT_DIR/03-render-config.sh"

start_arg=""
if [ "$SKIP_BUILD" = true ]; then
  start_arg="--skip-build"
fi

remote_cmd=$(cat <<REMOTE
set -euo pipefail
cd "$(remote_quote "$REMOTE_CURRENT_DIR")"

compose() {
  if docker compose version >/dev/null 2>&1; then
    docker compose "\$@"
  elif command -v docker-compose >/dev/null 2>&1; then
    docker-compose "\$@"
  else
    echo "Docker Compose is not installed." >&2
    return 1
  fi
}

echo "[1/4] Starting MySQL and RabbitMQ"
(cd docker && compose up -d mysql rabbitmq)

echo "[2/4] Waiting for MySQL"
for i in \$(seq 1 60); do
  if docker exec foggy-navigator-mysql sh -c 'mysqladmin ping -h localhost -u root -p"\$MYSQL_ROOT_PASSWORD" --silent' >/dev/null 2>&1; then
    echo "MySQL is ready."
    break
  fi
  if [ "\$i" -eq 60 ]; then
    echo "MySQL did not become ready in time." >&2
    docker logs --tail 80 foggy-navigator-mysql || true
    exit 1
  fi
  sleep 2
done

echo "[2/4] Waiting for RabbitMQ"
for i in \$(seq 1 60); do
  if docker exec foggy-navigator-rabbitmq rabbitmq-diagnostics -q ping >/dev/null 2>&1; then
    echo "RabbitMQ is ready."
    break
  fi
  if [ "\$i" -eq 60 ]; then
    echo "RabbitMQ did not become ready in time." >&2
    docker logs --tail 80 foggy-navigator-rabbitmq || true
    exit 1
  fi
  sleep 2
done

echo "[3/4] Starting Navigator stack"
bash ./start-all.sh $start_arg

echo "[4/4] Checking HTTP endpoints"
curl -fsS http://127.0.0.1:8112/actuator/health >/dev/null || echo "Backend health is not UP yet."
curl -fsS http://127.0.0.1/health >/dev/null || echo "Nginx health is not UP yet."

echo
echo "Deploy complete:"
echo "  Frontend: ${FRONTEND_EXTERNAL_URL:-http://$HOST_IP}"
echo "  Backend:  ${NAVIGATOR_API_EXTERNAL_URL:-http://$HOST_IP:8112}"
REMOTE
)

ssh_cmd "$remote_cmd"
