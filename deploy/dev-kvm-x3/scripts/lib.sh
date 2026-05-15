#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$DEPLOY_DIR/../.." && pwd)"
ENV_FILE="${DEPLOY_ENV_FILE:-$DEPLOY_DIR/.env}"

if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
else
  echo "Missing env file: $ENV_FILE" >&2
  echo "Copy $DEPLOY_DIR/.env.example to .env and fill credentials." >&2
  exit 1
fi

HOST_ALIAS="${HOST_ALIAS:-dev-kvm-x3}"
HOST_IP="${HOST_IP:?HOST_IP is required}"
SSH_USER="${SSH_USER:-sa}"
SSH_PORT="${SSH_PORT:-22}"
REMOTE_APP_DIR="${REMOTE_APP_DIR:-/opt/foggy/navigator}"
REMOTE_CURRENT_DIR="${REMOTE_CURRENT_DIR:-$REMOTE_APP_DIR/current}"
REMOTE_BACKUP_DIR="${REMOTE_BACKUP_DIR:-/opt/foggy/backups/navigator}"
SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-docker}"
TZ="${TZ:-Asia/Shanghai}"

SSH_TARGET="${SSH_USER}@${HOST_IP}"
SSH_OPTS=(-p "$SSH_PORT" -o ServerAliveInterval=30 -o ServerAliveCountMax=4 -o StrictHostKeyChecking=accept-new)

has_sshpass() {
  command -v sshpass >/dev/null 2>&1
}

ssh_cmd() {
  if [ -n "${SSH_PASSWORD:-}" ] && has_sshpass; then
    sshpass -p "$SSH_PASSWORD" ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "$@"
  else
    ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "$@"
  fi
}

rsync_cmd() {
  local ssh_rsh
  ssh_rsh="ssh -p $SSH_PORT -o ServerAliveInterval=30 -o ServerAliveCountMax=4 -o StrictHostKeyChecking=accept-new"

  if [ -n "${SSH_PASSWORD:-}" ] && has_sshpass; then
    RSYNC_RSH="sshpass -p ${SSH_PASSWORD} $ssh_rsh" rsync "$@"
  else
    rsync -e "$ssh_rsh" "$@"
  fi
}

require_local_command() {
  local name="$1"
  if ! command -v "$name" >/dev/null 2>&1; then
    echo "Missing local command: $name" >&2
    exit 1
  fi
}

remote_quote() {
  printf "%q" "$1"
}

print_target() {
  echo "$HOST_ALIAS ($SSH_TARGET:$SSH_PORT)"
}
