#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
. "$SCRIPT_DIR/lib.sh"

load_release_env
ensure_runtime_files

container="${NAVIGATOR_BACKEND_CONTAINER:-foggy-navigator-backend}"
url_expected="${BUSINESS_AGENT_DEV_SYNC_WORKER_URL:-http://192.168.31.81:3061}"

state="$(docker inspect -f '{{.State.Status}}' "$container" 2>/dev/null || true)"
health="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$container" 2>/dev/null || true)"
echo "backend_state=${state:-missing}"
echo "backend_health=${health:-missing}"
if [ "$state" != "running" ] || { [ "$health" != "none" ] && [ "$health" != "healthy" ]; }; then
  die "$container is not healthy"
fi

tmp_inspect="$(mktemp)"
trap 'rm -f "$tmp_inspect"' EXIT
docker inspect "$container" > "$tmp_inspect"

python3 - "$url_expected" "$tmp_inspect" <<'PY'
import json
import sys

expected_url = sys.argv[1]
inspect_path = sys.argv[2]
with open(inspect_path, encoding="utf-8") as fh:
    data = json.load(fh)
env_items = data[0].get("Config", {}).get("Env") or []
env = {}
for item in env_items:
    key, _, value = item.partition("=")
    env[key] = value

actual_url = env.get("BUSINESS_AGENT_DEV_SYNC_WORKER_URL", "")
token = env.get("BUSINESS_AGENT_DEV_SYNC_WORKER_TOKEN", "")

print(f"business_agent_dev_sync_worker_url={actual_url}")
print(f"business_agent_dev_sync_worker_url_matches={'true' if actual_url == expected_url else 'false'}")
print(f"business_agent_dev_sync_worker_token_configured={'true' if bool(token) else 'false'}")

if actual_url != expected_url:
    raise SystemExit(1)
if not token:
    raise SystemExit(1)
PY
