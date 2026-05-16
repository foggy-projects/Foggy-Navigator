#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
. "$SCRIPT_DIR/lib.sh"

REGISTRY="${1:-${HARBOR_REGISTRY:-test.synthoflow.com:8080}}"

log "Initializing Navigator release directories"
sudo mkdir -p "$DEPLOY_ROOT" "$BUILD_ROOT" "$SOURCE_DIR" "$RUNTIME_DIR" "$DEPLOY_ROOT/logs"
sudo chown -R "$(id -u):$(id -g)" "$DEPLOY_ROOT"
ensure_runtime_files

log "Checking required build/deploy commands"
for cmd in docker git curl python3; do
  require_command "$cmd"
done

if ! docker compose version >/dev/null 2>&1; then
  die "Docker Compose plugin is required"
fi

missing_build_tools=()
for cmd in java mvn node npm pnpm; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    missing_build_tools+=("$cmd")
  fi
done
if [ "${#missing_build_tools[@]}" -gt 0 ]; then
  log "Build tools missing: ${missing_build_tools[*]}"
  log "This host can still run deploy-by-image if Docker is available; build-and-push requires these tools."
fi

log "Configuring Docker insecure registry: $REGISTRY"
tmp="$(mktemp)"
sudo python3 - "$REGISTRY" <<'PY' > "$tmp"
import json
import sys
from pathlib import Path

registry = sys.argv[1]
path = Path("/etc/docker/daemon.json")
if path.exists():
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise SystemExit(f"Invalid /etc/docker/daemon.json: {exc}")
else:
    data = {}

items = data.get("insecure-registries", [])
if registry not in items:
    items.append(registry)
data["insecure-registries"] = items
print(json.dumps(data, indent=2, ensure_ascii=False))
PY

if ! sudo cmp -s "$tmp" /etc/docker/daemon.json 2>/dev/null; then
  sudo install -m 0644 "$tmp" /etc/docker/daemon.json
  log "Restarting Docker after daemon.json update"
  sudo systemctl restart docker
else
  log "Docker insecure registry already configured"
fi
rm -f "$tmp"

if [ ! -f "$RELEASE_ENV" ]; then
  cp "$RELEASE_KIT_DIR/release.env.example" "$RELEASE_ENV"
  chmod 0600 "$RELEASE_ENV"
  log "Created $RELEASE_ENV from template; fill secrets before build/deploy."
fi

log "Init complete"
