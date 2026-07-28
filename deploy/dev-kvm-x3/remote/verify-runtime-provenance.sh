#!/usr/bin/env bash
set -euo pipefail

expected_commit="${1:-${NAVIGATOR_EXPECTED_COMMIT:-}}"
metadata_url="${2:-${NAVIGATOR_BACKEND_INFO_URL:-http://127.0.0.1:8112/actuator/info}}"
metadata_file="${NAVIGATOR_RUNTIME_PROVENANCE_FILE:-}"

if [[ ! "$expected_commit" =~ ^[0-9a-fA-F]{40}$ ]]; then
  echo "ERROR: expected runtime commit is missing or malformed" >&2
  exit 1
fi
expected_commit="${expected_commit,,}"

temporary_file=""
if [ -z "$metadata_file" ]; then
  temporary_file="$(mktemp)"
  trap 'rm -f "$temporary_file"' EXIT
  if ! curl --fail --silent --show-error \
      --connect-timeout "${NAVIGATOR_PROVENANCE_CONNECT_TIMEOUT_SECONDS:-3}" \
      --max-time "${NAVIGATOR_PROVENANCE_MAX_TIME_SECONDS:-10}" \
      "$metadata_url" >"$temporary_file" 2>/dev/null; then
    echo "ERROR: private runtime metadata probe failed" >&2
    exit 1
  fi
  metadata_file="$temporary_file"
fi

python3 - "$metadata_file" "$expected_commit" <<'PY'
import json
import re
import sys

path, expected = sys.argv[1], sys.argv[2]

def fail(category):
    print(f"ERROR: runtime provenance verification failed ({category})", file=sys.stderr)
    raise SystemExit(1)

try:
    with open(path, "r", encoding="utf-8") as handle:
        payload = json.load(handle)
except Exception:
    fail("malformed-metadata")

if not isinstance(payload, dict):
    fail("malformed-metadata")
git = payload.get("git")
build = payload.get("build")
if not isinstance(git, dict) or not isinstance(build, dict):
    fail("missing-metadata")
commit_block = git.get("commit")
actual = commit_block.get("id") if isinstance(commit_block, dict) else None
dirty = git.get("dirty")
version = build.get("version")
build_time = build.get("time")

if not isinstance(actual, str) or not re.fullmatch(r"[0-9a-fA-F]{40}", actual.strip()):
    fail("malformed-commit")
actual = actual.strip().lower()
if actual != expected:
    fail("commit-mismatch")
if dirty is not False and not (isinstance(dirty, str) and dirty.strip().lower() == "false"):
    fail("dirty-build")
if not isinstance(version, str) or not version.strip():
    fail("missing-build-version")
if not isinstance(build_time, str) or not build_time.strip():
    fail("missing-build-time")

print(json.dumps({
    "commit": actual,
    "dirty": False,
    "buildVersion": version.strip(),
    "buildTime": build_time.strip(),
}, separators=(",", ":"), sort_keys=True))
PY
