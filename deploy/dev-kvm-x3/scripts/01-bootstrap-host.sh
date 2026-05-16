#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
. "$SCRIPT_DIR/lib.sh"

echo "Bootstrapping host $(print_target)"

ssh_cmd 'bash -s' <<'REMOTE'
set -euo pipefail

if [ "$(id -u)" -eq 0 ]; then
  SUDO=""
else
  SUDO="sudo"
fi

install_common_debian() {
  $SUDO apt-get update

  local packages=(
    ca-certificates curl gnupg git rsync lsof unzip jq \
    python3 python3-pip python3-venv openjdk-17-jdk maven
  )

  if ! command -v docker >/dev/null 2>&1; then
    packages+=(docker.io docker-compose-plugin)
  elif ! docker compose version >/dev/null 2>&1; then
    packages+=(docker-compose-plugin)
  fi

  $SUDO apt-get install -y "${packages[@]}"

  if ! command -v node >/dev/null 2>&1 || [ "$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null || echo 0)" -lt 20 ]; then
    curl -fsSL https://deb.nodesource.com/setup_20.x | $SUDO bash -
    $SUDO apt-get install -y nodejs
  fi
}

install_common_rhel() {
  local pm
  if command -v dnf >/dev/null 2>&1; then
    pm=dnf
  else
    pm=yum
  fi

  local packages=(
    ca-certificates curl git rsync lsof unzip jq \
    python3 python3-pip java-17-openjdk-devel maven
  )

  if ! command -v docker >/dev/null 2>&1; then
    packages+=(docker)
  fi

  $SUDO "$pm" install -y "${packages[@]}"

  if ! command -v node >/dev/null 2>&1 || [ "$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null || echo 0)" -lt 20 ]; then
    curl -fsSL https://rpm.nodesource.com/setup_20.x | $SUDO bash -
    $SUDO "$pm" install -y nodejs
  fi
}

if command -v apt-get >/dev/null 2>&1; then
  install_common_debian
elif command -v dnf >/dev/null 2>&1 || command -v yum >/dev/null 2>&1; then
  install_common_rhel
else
  echo "Unsupported package manager. Install Docker, JDK 17, Maven, Node 20, pnpm, git, rsync, lsof and curl manually." >&2
  exit 1
fi

$SUDO systemctl enable --now docker

LOGIN_USER="$(id -un)"

if ! id -nG "$LOGIN_USER" | grep -qw docker; then
  $SUDO usermod -aG docker "$LOGIN_USER" || true
  echo "Added $LOGIN_USER to docker group. Re-login may be required before docker works without sudo."
fi

if ! command -v pnpm >/dev/null 2>&1; then
  $SUDO npm install -g pnpm
fi

$SUDO mkdir -p /opt/foggy
$SUDO chown "$LOGIN_USER:$(id -gn "$LOGIN_USER")" /opt/foggy

echo
echo "Bootstrap complete."
echo "Versions:"
docker --version || true
java -version 2>&1 | head -n 1 || true
mvn -version 2>/dev/null | head -n 1 || true
node -v || true
pnpm -v || true
REMOTE
