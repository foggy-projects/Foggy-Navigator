#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
. "$SCRIPT_DIR/lib.sh"

: "${NAVIGATOR_ROOT_PASSWORD:?NAVIGATOR_ROOT_PASSWORD is required in $ENV_FILE}"
: "${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD is required in $ENV_FILE}"
: "${MYSQL_PASSWORD:?MYSQL_PASSWORD is required in $ENV_FILE}"

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
TZ=${TZ}
EOF
chmod 600 launcher/.env

cat > docker/.env <<'EOF'
MYSQL_ROOT_PASSWORD=${MYSQL_ROOT_PASSWORD}
MYSQL_DATABASE=${MYSQL_DATABASE:-coding_agent}
MYSQL_USER=${MYSQL_USER:-foggy}
MYSQL_PASSWORD=${MYSQL_PASSWORD}
MYSQL_PORT=${MYSQL_PORT:-13309}
TZ=${TZ}
EOF
chmod 600 docker/.env
rm -f docker/docker-compose.override.yml

echo "launcher/.env and docker/.env rendered."
REMOTE
)

ssh_cmd "$remote_cmd"
