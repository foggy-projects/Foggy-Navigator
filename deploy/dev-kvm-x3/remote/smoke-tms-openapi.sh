#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
. "$SCRIPT_DIR/lib.sh"

TMS_UPSTREAM_ENV_FILE="${TMS_UPSTREAM_ENV_FILE:-$RUNTIME_DIR/tms-upstream.env}"
[ -f "$TMS_UPSTREAM_ENV_FILE" ] || die "Missing TMS upstream env: $TMS_UPSTREAM_ENV_FILE"

set -a
# shellcheck disable=SC1090
. "$TMS_UPSTREAM_ENV_FILE"
set +a

require_command python3

export TMS_OPENAPI_SMOKE_REPORT="${TMS_OPENAPI_SMOKE_REPORT:-$RUNTIME_DIR/tms-openapi-smoke-report.json}"

python3 <<'PY'
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


def env(name, default=None, required=False):
    value = os.environ.get(name, default)
    if required and (value is None or value == ""):
        raise SystemExit(f"{name} is required")
    return value


BASE = env("NAVI_BASE_URL", required=True).rstrip("/")
CLIENT_APP_KEY = env("NAVI_CLIENT_APP_KEY", required=True)
CLIENT_APP_SECRET = env("NAVI_CLIENT_APP_SECRET", required=True)
UPSTREAM_USER_ID = env("NAVI_UPSTREAM_USER_ID", "tms-x3-smoke-user")
AGENT_ID = env("NAVI_AGENT_CODE", env("NAVI_SKILL_ID", required=True))
SKILL_ID = env("NAVI_SKILL_ID", AGENT_ID)
MODEL_CONFIG_ID = env("NAVI_MODEL_CONFIG_ID", required=True)
REPORT = Path(env("TMS_OPENAPI_SMOKE_REPORT", "/opt/foggy/navigator/runtime/tms-openapi-smoke-report.json"))


def unwrap(payload):
    if isinstance(payload, dict) and "code" in payload and "data" in payload:
        code = payload.get("code")
        if code is not None and code >= 400:
            raise RuntimeError(payload.get("message") or json.dumps(payload, ensure_ascii=False))
        return payload.get("data")
    return payload


def request(method, path, headers=None, body=None):
    data = None
    all_headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        all_headers["Content-Type"] = "application/json; charset=UTF-8"
    if headers:
        all_headers.update(headers)
    req = urllib.request.Request(BASE + path, data=data, headers=all_headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=45) as resp:
            text = resp.read().decode("utf-8")
            return unwrap(json.loads(text) if text else {})
    except urllib.error.HTTPError as exc:
        text = exc.read().decode("utf-8", errors="replace")
        try:
            payload = json.loads(text)
            msg = payload.get("message") or payload.get("error") or text
        except Exception:
            msg = text
        raise RuntimeError(f"{method} {path} failed: HTTP {exc.code}: {msg}") from exc


token_payload = request(
    "POST",
    "/api/v1/open/client-apps/runtime-token",
    headers={
        "X-Client-App-Key": CLIENT_APP_KEY,
        "X-Client-App-Secret": CLIENT_APP_SECRET,
    },
)
access_token = token_payload.get("accessToken")
if not access_token:
    raise RuntimeError("runtime-token response did not include accessToken")

runtime_headers = {
    "X-Client-App-Key": CLIENT_APP_KEY,
    "X-Client-App-Access-Token": access_token,
    "X-Upstream-User-Id": UPSTREAM_USER_ID,
}
auth_headers = {
    "X-Client-App-Key": CLIENT_APP_KEY,
    "X-Client-App-Access-Token": access_token,
}

preflight = request(
    "POST",
    f"/api/v1/open/agents/{urllib.parse.quote(AGENT_ID, safe='')}/preflight",
    headers=auth_headers,
    body={
        "upstreamUserId": UPSTREAM_USER_ID,
        "modelConfigId": MODEL_CONFIG_ID,
        "context": {"skillId": SKILL_ID},
    },
)

task = request(
    "POST",
    f"/api/v1/open/agents/{urllib.parse.quote(AGENT_ID, safe='')}/ask",
    headers=runtime_headers,
    body={
        "message": "请用一句话返回：x3 TMS Navigator OpenAPI smoke OK。",
        "question": "请用一句话返回：x3 TMS Navigator OpenAPI smoke OK。",
        "maxTurns": 3,
        "modelConfigId": MODEL_CONFIG_ID,
        "metadata": {"modelConfigId": MODEL_CONFIG_ID},
        "clientContext": {"source": "tms-openapi-smoke", "skillId": SKILL_ID},
    },
)

task_id = task.get("taskId")
if not task_id:
    raise RuntimeError(f"ask response did not include taskId: {task}")

terminal = {"COMPLETED", "FAILED", "ABORTED", "CANCELED", "CANCELLED"}
last_task = task
last_messages = {}
for _ in range(20):
    time.sleep(3)
    last_task = request(
        "GET",
        f"/api/v1/open/agents/{urllib.parse.quote(AGENT_ID, safe='')}/tasks/{urllib.parse.quote(task_id, safe='')}",
        headers=runtime_headers,
    )
    last_messages = request(
        "GET",
        f"/api/v1/open/agents/{urllib.parse.quote(AGENT_ID, safe='')}/tasks/{urllib.parse.quote(task_id, safe='')}/messages?limit=50",
        headers=runtime_headers,
    )
    status = (last_task or {}).get("status")
    if status in terminal:
        break

messages = (last_messages or {}).get("messages") or []
def metadata_contains(message, value):
    metadata = message.get("metadata")
    if isinstance(metadata, str):
        return value in metadata
    if isinstance(metadata, dict):
        return value in json.dumps(metadata, ensure_ascii=False)
    return False

result_messages = [
    m for m in messages
    if (m.get("type") == "RESULT" or metadata_contains(m, "RESULT"))
]
final_message = (result_messages[-1].get("content") if result_messages else (messages[-1].get("content") if messages else None))

report = {
    "baseUrl": BASE,
    "agentId": AGENT_ID,
    "skillId": SKILL_ID,
    "upstreamUserId": UPSTREAM_USER_ID,
    "runtimeTokenIssued": True,
    "runtimeTokenExpiresAt": token_payload.get("expiresAt"),
    "preflight": preflight,
    "taskId": task_id,
    "contextId": task.get("contextId") or task.get("sessionId"),
    "initialStatus": task.get("status"),
    "finalStatus": (last_task or {}).get("status"),
    "messageCount": len(messages),
    "finalMessage": final_message,
}
REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
os.chmod(REPORT, 0o600)

print(json.dumps({
    "agentId": report["agentId"],
    "skillId": report["skillId"],
    "upstreamUserId": report["upstreamUserId"],
    "runtimeTokenIssued": report["runtimeTokenIssued"],
    "taskId": report["taskId"],
    "contextId": report["contextId"],
    "initialStatus": report["initialStatus"],
    "finalStatus": report["finalStatus"],
    "messageCount": report["messageCount"],
    "reportFile": str(REPORT),
}, ensure_ascii=False, indent=2))
PY
