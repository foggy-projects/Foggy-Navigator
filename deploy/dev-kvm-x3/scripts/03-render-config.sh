#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
. "$SCRIPT_DIR/lib.sh"

: "${NAVIGATOR_ROOT_PASSWORD:?NAVIGATOR_ROOT_PASSWORD is required in $ENV_FILE}"
: "${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD is required in $ENV_FILE}"
: "${MYSQL_PASSWORD:?MYSQL_PASSWORD is required in $ENV_FILE}"
: "${RABBITMQ_PASS:?RABBITMQ_PASS is required in $ENV_FILE}"

echo "Rendering remote configuration on $(print_target)"

remote_cmd=$(cat <<REMOTE
set -euo pipefail
cd "$(remote_quote "$REMOTE_CURRENT_DIR")"
mkdir -p launcher docker logs

cat > launcher/.env <<'EOF'
ROOT_USERNAME=${NAVIGATOR_ROOT_USERNAME:-root}
ROOT_PASSWORD=${NAVIGATOR_ROOT_PASSWORD}
ROOT_EMAIL=${NAVIGATOR_ROOT_EMAIL:-root@foggy.local}
ROOT_PASSWORD_RESET=${NAVIGATOR_ROOT_PASSWORD_RESET:-false}
SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE}
SPRING_DATASOURCE_URL=${SPRING_DATASOURCE_URL:-jdbc:mysql://localhost:${MYSQL_PORT:-13309}/${MYSQL_DATABASE:-coding_agent}?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true}
SPRING_DATASOURCE_USERNAME=${SPRING_DATASOURCE_USERNAME:-${MYSQL_USER:-foggy}}
SPRING_DATASOURCE_PASSWORD=${SPRING_DATASOURCE_PASSWORD:-${MYSQL_PASSWORD}}
NAVIGATOR_API_EXTERNAL_URL=${NAVIGATOR_API_EXTERNAL_URL:-http://$HOST_IP:8112}
RABBITMQ_HOST=${RABBITMQ_HOST:-localhost}
RABBITMQ_PORT=${RABBITMQ_PORT:-5672}
RABBITMQ_USER=${RABBITMQ_USER:-foggy}
RABBITMQ_PASS=${RABBITMQ_PASS}
TZ=${TZ}
EOF
chmod 600 launcher/.env

cat > docker/.env <<'EOF'
MYSQL_ROOT_PASSWORD=${MYSQL_ROOT_PASSWORD}
MYSQL_DATABASE=${MYSQL_DATABASE:-coding_agent}
MYSQL_USER=${MYSQL_USER:-foggy}
MYSQL_PASSWORD=${MYSQL_PASSWORD}
MYSQL_PORT=${MYSQL_PORT:-13309}
RABBITMQ_USER=${RABBITMQ_USER:-foggy}
RABBITMQ_PASS=${RABBITMQ_PASS}
RABBITMQ_PORT=${RABBITMQ_PORT:-5672}
RABBITMQ_MANAGEMENT_PORT=${RABBITMQ_MANAGEMENT_PORT:-15672}
TZ=${TZ}
EOF
chmod 600 docker/.env

cat > docker/docker-compose.override.yml <<'EOF'
services:
  rabbitmq:
    image: rabbitmq:3-management-alpine
    container_name: foggy-navigator-rabbitmq
    restart: unless-stopped
    ports:
      - "${RABBITMQ_PORT:-5672}:5672"
      - "${RABBITMQ_MANAGEMENT_PORT:-15672}:15672"
    environment:
      RABBITMQ_DEFAULT_USER: ${RABBITMQ_USER:-foggy}
      RABBITMQ_DEFAULT_PASS: ${RABBITMQ_PASS}
    volumes:
      - rabbitmq_data:/var/lib/rabbitmq
    networks:
      - foggy-network
    healthcheck:
      test: ["CMD", "rabbitmqctl", "status"]
      interval: 10s
      timeout: 5s
      retries: 5

volumes:
  rabbitmq_data:
    name: foggy-navigator-rabbitmq-data
    driver: local
EOF

echo "launcher/.env, docker/.env and docker/docker-compose.override.yml rendered."
REMOTE
)

ssh_cmd "$remote_cmd"
