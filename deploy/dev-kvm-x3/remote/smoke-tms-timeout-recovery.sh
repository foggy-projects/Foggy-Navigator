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

export TMS_TIMEOUT_RECOVERY_REPORT="${TMS_TIMEOUT_RECOVERY_REPORT:-$RUNTIME_DIR/tms-timeout-recovery-report.json}"

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
REPORT = Path(env("TMS_TIMEOUT_RECOVERY_REPORT", "/opt/foggy/navigator/runtime/tms-timeout-recovery-report.json"))
POLL_INTERVAL = float(env("NAVI_TIMEOUT_RECOVERY_POLL_INTERVAL_SECONDS", "3"))
POLL_ATTEMPTS = int(env("NAVI_TIMEOUT_RECOVERY_POLL_ATTEMPTS", "25"))
DETACH_SECONDS = float(env("NAVI_TIMEOUT_RECOVERY_DETACH_SECONDS", "5"))


def unwrap(payload):
    if isinstance(payload, dict) and "code" in payload and "data" in payload:
        code = payload.get("code")
        if code is not None and code >= 400:
            raise RuntimeError(payload.get("message") or json.dumps(payload, ensure_ascii=False))
        return payload.get("data")
    return payload


def request(method, path, headers=None, body=None, timeout=45):
    data = None
    all_headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        all_headers["Content-Type"] = "application/json; charset=UTF-8"
    if headers:
        all_headers.update(headers)
    req = urllib.request.Request(BASE + path, data=data, headers=all_headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
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


def auth():
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
    return token_payload, runtime_headers, auth_headers


def task_path(task_id):
    return f"/api/v1/open/agents/{urllib.parse.quote(AGENT_ID, safe='')}/tasks/{urllib.parse.quote(task_id, safe='')}"


def poll_to_terminal(task_id, headers):
    terminal = {"COMPLETED", "FAILED", "ABORTED", "CANCELED", "CANCELLED"}
    last_task = {}
    last_messages = {}
    for _ in range(POLL_ATTEMPTS):
        time.sleep(POLL_INTERVAL)
        last_task = request("GET", task_path(task_id), headers=headers)
        last_messages = request("GET", task_path(task_id) + "/messages?limit=80", headers=headers)
        status = (last_task or {}).get("status")
        if status in terminal:
            return last_task, last_messages
    return last_task, last_messages


token_payload, runtime_headers, auth_headers = auth()

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

first_task = request(
    "POST",
    f"/api/v1/open/agents/{urllib.parse.quote(AGENT_ID, safe='')}/ask",
    headers=runtime_headers,
    body={
        "message": "OPT-029真实链路验证：请只用一句话回复 TMS detach reattach OK。",
        "question": "OPT-029真实链路验证：请只用一句话回复 TMS detach reattach OK。",
        "maxTurns": 3,
        "modelConfigId": MODEL_CONFIG_ID,
        "metadata": {
            "modelConfigId": MODEL_CONFIG_ID,
            "source": "opt029-tms-timeout-recovery",
            "simulatedUiWaitTimeoutMs": 0,
        },
        "clientContext": {
            "source": "opt029-tms-timeout-recovery",
            "skillId": SKILL_ID,
        },
    },
)

first_task_id = first_task.get("taskId")
first_context_id = first_task.get("contextId") or first_task.get("sessionId")
if not first_task_id or not first_context_id:
    raise RuntimeError(f"first ask missing taskId/contextId: {first_task}")

# Simulate a UI wait timeout/client detach: after receiving task identity, stop polling
# without calling cancel, then reattach later through task/messages and contextId.
time.sleep(DETACH_SECONDS)
first_last_task, first_last_messages = poll_to_terminal(first_task_id, runtime_headers)
first_messages = (first_last_messages or {}).get("messages") or []

second_task = request(
    "POST",
    f"/api/v1/open/agents/{urllib.parse.quote(AGENT_ID, safe='')}/ask",
    headers=runtime_headers,
    body={
        "message": "继续上一轮，确认上下文还在。只回复 context kept。",
        "question": "继续上一轮，确认上下文还在。只回复 context kept。",
        "contextId": first_context_id,
        "maxTurns": 3,
        "modelConfigId": MODEL_CONFIG_ID,
        "metadata": {
            "modelConfigId": MODEL_CONFIG_ID,
            "source": "opt029-tms-timeout-recovery-next-turn",
        },
        "clientContext": {
            "source": "opt029-tms-timeout-recovery",
            "skillId": SKILL_ID,
        },
    },
)

second_task_id = second_task.get("taskId")
second_context_id = second_task.get("contextId") or second_task.get("sessionId")
if not second_task_id:
    raise RuntimeError(f"second ask missing taskId: {second_task}")
second_last_task, second_last_messages = poll_to_terminal(second_task_id, runtime_headers)
second_messages = (second_last_messages or {}).get("messages") or []

first_final_status = (first_last_task or {}).get("status")
second_final_status = (second_last_task or {}).get("status")

verdicts = {
    "initial_task_created": bool(first_task_id and first_context_id),
    "ui_wait_timeout_simulated_without_cancel": first_final_status not in {"CANCELED", "CANCELLED"},
    "reattach_messages_available": len(first_messages) > 0,
    "first_task_completed": first_final_status == "COMPLETED",
    "next_turn_reused_context": bool(second_context_id and second_context_id == first_context_id),
    "second_task_completed": second_final_status == "COMPLETED",
}

report = {
    "agentId": AGENT_ID,
    "skillId": SKILL_ID,
    "upstreamUserId": UPSTREAM_USER_ID,
    "runtimeTokenIssued": True,
    "runtimeTokenExpiresAt": token_payload.get("expiresAt"),
    "preflight": preflight,
    "simulatedUiWaitTimeoutMs": 0,
    "simulatedClientDetachSeconds": DETACH_SECONDS,
    "cancelApiCalled": False,
    "firstTaskId": first_task_id,
    "firstContextId": first_context_id,
    "firstInitialStatus": first_task.get("status"),
    "firstFinalStatus": first_final_status,
    "firstMessageCount": len(first_messages),
    "secondTaskId": second_task_id,
    "secondContextId": second_context_id,
    "secondInitialStatus": second_task.get("status"),
    "secondFinalStatus": second_final_status,
    "secondMessageCount": len(second_messages),
    "verdicts": verdicts,
    "passed": all(verdicts.values()),
}

REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
os.chmod(REPORT, 0o600)

print(json.dumps({
    "agentId": report["agentId"],
    "skillId": report["skillId"],
    "upstreamUserId": report["upstreamUserId"],
    "firstTaskId": report["firstTaskId"],
    "firstContextId": report["firstContextId"],
    "firstInitialStatus": report["firstInitialStatus"],
    "firstFinalStatus": report["firstFinalStatus"],
    "firstMessageCount": report["firstMessageCount"],
    "secondTaskId": report["secondTaskId"],
    "secondContextId": report["secondContextId"],
    "secondInitialStatus": report["secondInitialStatus"],
    "secondFinalStatus": report["secondFinalStatus"],
    "secondMessageCount": report["secondMessageCount"],
    "passed": report["passed"],
    "verdicts": report["verdicts"],
    "reportFile": str(REPORT),
}, ensure_ascii=False, indent=2))

if not report["passed"]:
    raise SystemExit(1)
PY
