#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
. "$SCRIPT_DIR/lib.sh"

BOOTSTRAP_ENV="${PLATFORM_BOOTSTRAP_ENV:-$RUNTIME_DIR/platform-bootstrap.env}"
[ -f "$BOOTSTRAP_ENV" ] || die "Missing platform bootstrap env: $BOOTSTRAP_ENV"

set -a
# shellcheck disable=SC1090
. "$BOOTSTRAP_ENV"
set +a

require_command python3
require_command curl
require_command openssl

chmod 0600 "$BOOTSTRAP_ENV"

ensure_generated_secret() {
  local key="$1"
  local prefix="$2"
  local current="${!key:-}"
  if [ -n "$current" ]; then
    return
  fi
  local value
  value="${prefix}$(openssl rand -hex 24)"
  printf '\n%s=%s\n' "$key" "$value" >> "$BOOTSTRAP_ENV"
  chmod 0600 "$BOOTSTRAP_ENV"
  export "$key=$value"
}

ensure_generated_secret NAVIGATOR_TENANT_ADMIN_PASSWORD "pwd_"
ensure_generated_secret NAVIGATOR_BIZ_WORKER_TOKEN "bw_"
ensure_generated_secret NAVIGATOR_TMS_SEED_UPSTREAM_USER_TOKEN "sut_"

export TMS_UPSTREAM_ENV_FILE="${TMS_UPSTREAM_ENV_FILE:-$RUNTIME_DIR/tms-upstream.env}"
export BOOTSTRAP_REPORT_FILE="${BOOTSTRAP_REPORT_FILE:-$RUNTIME_DIR/platform-bootstrap-report.json}"

python3 <<'PY'
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path


def env(name, default=None, required=False):
    value = os.environ.get(name, default)
    if required and (value is None or value == ""):
        raise SystemExit(f"{name} is required")
    return value


BASE = env("NAVIGATOR_API_BASE", "http://127.0.0.1:8112").rstrip("/")
PUBLIC_BASE = env("NAVIGATOR_PUBLIC_BASE_URL", BASE).rstrip("/")
TENANT_ID = env("NAVIGATOR_TENANT_ID", "tms-x3")
ADMIN_USERNAME = env("NAVIGATOR_TENANT_ADMIN_USERNAME", "tms-admin")
ADMIN_PASSWORD = env("NAVIGATOR_TENANT_ADMIN_PASSWORD", required=True)
WORKER_ID = env("NAVIGATOR_BIZ_WORKER_ID", "dev-kvm-x3-langgraph-biz-worker")
WORKER_BASE_URL = env("NAVIGATOR_BIZ_WORKER_BASE_URL", "http://192.168.31.81:3061")
WORKER_TOKEN = env("NAVIGATOR_BIZ_WORKER_TOKEN", required=True)
POOL_ID = env("NAVIGATOR_BIZ_WORKER_POOL_ID", "tms-x3-langgraph-pool")
MODEL_NAME = env("NAVIGATOR_LLM_NAME", "tms-x3-qwen3.5-plus")
MODEL_BASE_URL = env("NAVIGATOR_LLM_BASE_URL", required=True)
MODEL_ID_NAME = env("NAVIGATOR_LLM_MODEL", required=True)
MODEL_API_KEY = env("NAVIGATOR_LLM_API_KEY", required=True)
CLIENT_APP_NAME = env("NAVIGATOR_TMS_CLIENT_APP_NAME", "TMS X3 Demo")
CLIENT_APP_DESC = env("NAVIGATOR_TMS_CLIENT_APP_DESCRIPTION", "TMS X3 dev/demo integration for Navigator Business Agent.")
CAPABILITY_DOMAIN = env("NAVIGATOR_TMS_CAPABILITY_DOMAIN", "TMS")
TMS_WEB_BASE_URL = env("NAVIGATOR_TMS_WEB_BASE_URL", "http://192.168.31.81:12580")
AGENT_CODE = env("NAVIGATOR_TMS_AGENT_CODE", "tms.navigator.agent")
SKILL_ID = env("NAVIGATOR_TMS_SKILL_ID", AGENT_CODE)
AGENT_NAME = env("NAVIGATOR_TMS_AGENT_NAME", "TMS Navigator Agent")
AGENT_DESC = env("NAVIGATOR_TMS_AGENT_DESCRIPTION", "Navigator Business Agent for TMS X3 dev/demo.")
SEED_USER = env("NAVIGATOR_TMS_SEED_UPSTREAM_USER_ID", "tms-x3-smoke-user")
SEED_USER_TOKEN = env("NAVIGATOR_TMS_SEED_UPSTREAM_USER_TOKEN", required=True)
MATERIALIZE_AGENT_BUNDLE = env("NAVIGATOR_TMS_MATERIALIZE_AGENT_BUNDLE", "false").lower() in ("1", "true", "yes", "y", "on")
RUN_SMOKE_TASK = env("NAVIGATOR_PLATFORM_SMOKE_TASK", "false").lower() in ("1", "true", "yes", "y", "on")
UPSTREAM_ENV_FILE = Path(env("TMS_UPSTREAM_ENV_FILE", "/opt/foggy/navigator/runtime/tms-upstream.env"))
REPORT_FILE = Path(env("BOOTSTRAP_REPORT_FILE", "/opt/foggy/navigator/runtime/platform-bootstrap-report.json"))


def request(method, path, token=None, body=None, headers=None, tolerate=()):
    data = None
    all_headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        all_headers["Content-Type"] = "application/json; charset=UTF-8"
    if token:
        all_headers["Authorization"] = f"Bearer {token}"
    if headers:
        all_headers.update(headers)
    req = urllib.request.Request(BASE + path, data=data, headers=all_headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            text = resp.read().decode("utf-8")
            payload = json.loads(text) if text else {}
            code = payload.get("code")
            if code is not None and code >= 400:
                raise RuntimeError(payload.get("message") or text)
            return payload.get("data", payload)
    except urllib.error.HTTPError as exc:
        text = exc.read().decode("utf-8", errors="replace")
        if exc.code in tolerate:
            try:
                return json.loads(text)
            except Exception:
                return {"httpStatus": exc.code, "raw": text}
        try:
            payload = json.loads(text)
            msg = payload.get("message") or payload.get("error") or text
        except Exception:
            msg = text
        raise RuntimeError(f"{method} {path} failed: HTTP {exc.code}: {msg}") from exc


def login(username, password):
    data = request("POST", "/api/v1/auth/login", body={"username": username, "password": password})
    token = data.get("token")
    if not token:
        raise RuntimeError(f"login failed for {username}")
    return token, data.get("user") or {}


def find_by(items, **conditions):
    for item in items or []:
        if all(item.get(k) == v for k, v in conditions.items()):
            return item
    return None


def load_existing_env(path):
    values = {}
    if not path.exists():
        return values
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key] = value
    return values


root_token, root_user = login(env("NAVIGATOR_ROOT_USERNAME", "root"), env("NAVIGATOR_ROOT_PASSWORD", required=True))
users = request("GET", "/api/v1/users", token=root_token)
admin = find_by(users, username=ADMIN_USERNAME)
created_admin = False
if not admin:
    request("POST", "/api/v1/auth/register", body={
        "tenantId": TENANT_ID,
        "username": ADMIN_USERNAME,
        "password": ADMIN_PASSWORD,
        "email": env("NAVIGATOR_TENANT_ADMIN_EMAIL", "tms-admin@tms-x3.local"),
        "displayName": env("NAVIGATOR_TENANT_ADMIN_DISPLAY_NAME", "TMS X3 Admin"),
        "roles": "TENANT_ADMIN,DEVELOPER",
    })
    time.sleep(0.5)
    users = request("GET", "/api/v1/users", token=root_token)
    admin = find_by(users, username=ADMIN_USERNAME)
    created_admin = True
if not admin:
    raise RuntimeError("tenant admin was not found after registration")
if admin.get("tenantId") != TENANT_ID:
    raise RuntimeError(f"{ADMIN_USERNAME} tenant mismatch: {admin.get('tenantId')} != {TENANT_ID}")

admin_token, admin_login_user = login(ADMIN_USERNAME, ADMIN_PASSWORD)
admin_user_id = admin.get("id") or admin_login_user.get("id")

models = request("GET", "/api/v1/config/platform/llm", token=admin_token)
model = find_by(models, name=MODEL_NAME) or next(
    (m for m in models or []
     if m.get("baseUrl") == MODEL_BASE_URL and m.get("modelName") == MODEL_ID_NAME and m.get("workerBackend") == "LANGGRAPH_BIZ"),
    None,
)
model_body = {
    "name": MODEL_NAME,
    "category": "GENERAL",
    "baseUrl": MODEL_BASE_URL,
    "modelName": MODEL_ID_NAME,
    "apiKey": MODEL_API_KEY,
    "isDefault": True,
    "scope": "GLOBAL",
    "workerBackend": "LANGGRAPH_BIZ",
    "availableModels": [MODEL_ID_NAME],
}
created_model = False
if model:
    model_config_id = model.get("id")
    request("PUT", f"/api/v1/config/platform/llm/{model_config_id}", token=admin_token, body=model_body)
else:
    model_config_id = request("POST", "/api/v1/config/platform/llm", token=admin_token, body=model_body)
    created_model = True

worker_body = {
    "workerId": WORKER_ID,
    "name": env("NAVIGATOR_BIZ_WORKER_NAME", "dev-kvm-x3 LangGraph Biz Worker"),
    "baseUrl": WORKER_BASE_URL,
    "authToken": WORKER_TOKEN,
    "authMode": "API_KEY",
    "status": "UNKNOWN",
}
workers = request("GET", "/api/v1/langgraph-workers", token=admin_token)
worker = find_by(workers, workerId=WORKER_ID)
created_worker = False
if worker:
    request("PUT", f"/api/v1/langgraph-workers/{WORKER_ID}", token=admin_token, body=worker_body)
else:
    worker = request("POST", "/api/v1/langgraph-workers", token=admin_token, body=worker_body)
    created_worker = True
try:
    worker = request("POST", f"/api/v1/langgraph-workers/{WORKER_ID}/health-check", token=admin_token)
except Exception as exc:
    raise RuntimeError(f"LangGraph worker registered but health-check failed: {exc}") from exc

request("POST", "/api/v1/business-agent/worker-identities", token=root_token, body={
    "workerId": WORKER_ID,
    "workerBackend": "LANGGRAPH_BIZ",
    "baseUrl": WORKER_BASE_URL,
    "version": worker.get("workerVersion") or "x3-dev",
    "identityToken": WORKER_TOKEN,
})

pools = request("GET", "/api/v1/business-agent/worker-pools", token=admin_token)
pool = find_by(pools, poolId=POOL_ID)
created_pool = False
if not pool:
    pool = request("POST", "/api/v1/business-agent/worker-pools", token=admin_token, body={
        "poolId": POOL_ID,
        "name": env("NAVIGATOR_BIZ_WORKER_POOL_NAME", "TMS X3 LangGraph Worker Pool"),
        "workerBackend": "LANGGRAPH_BIZ",
        "routingPolicy": "ROUND_ROBIN",
    })
    created_pool = True
try:
    request("POST", f"/api/v1/business-agent/worker-pools/{POOL_ID}/members", token=admin_token, body={"workerId": WORKER_ID})
    pool_member_added = True
except Exception as exc:
    if "worker already in pool" in str(exc):
        pool_member_added = False
    else:
        raise

client_apps = request("GET", "/api/v1/client-apps", token=admin_token)
client_app = find_by(client_apps, name=CLIENT_APP_NAME)
created_client_app = False
if not client_app:
    provisioning = request("POST", "/api/v1/admin/client-apps/provisioning-credentials", token=admin_token, body={
        "targetTenantId": TENANT_ID,
        "maxUses": 1,
        "ownerUserId": admin_user_id,
        "capabilityDomain": CAPABILITY_DOMAIN,
        "auditTag": "dev-kvm-x3-platform-bootstrap",
    })
    client_app = request("POST", "/api/v1/client-apps", token=admin_token, body={
        "provisioningToken": provisioning["token"],
        "name": CLIENT_APP_NAME,
        "description": CLIENT_APP_DESC,
        "ownerUserId": admin_user_id,
        "capabilityDomain": CAPABILITY_DOMAIN,
    })
    created_client_app = True

client_app_id = client_app["clientAppId"]
existing_env = load_existing_env(UPSTREAM_ENV_FILE)

client_app_key = existing_env.get("NAVI_CLIENT_APP_KEY")
client_app_secret = existing_env.get("NAVI_CLIENT_APP_SECRET")
if not client_app_key or not client_app_secret or existing_env.get("NAVI_CLIENT_APP_ID") != client_app_id:
    runtime = request("POST", f"/api/v1/client-apps/{client_app_id}/runtime-credentials", token=admin_token, body={
        "description": "TMS X3 dev/demo runtime credential"
    })
    client_app_key = runtime["appKey"]
    client_app_secret = runtime["secret"]

control_key = existing_env.get("NAVI_CONTROL_API_KEY")
if not control_key or existing_env.get("NAVI_CLIENT_APP_ID") != client_app_id:
    control = request("POST", f"/api/v1/client-apps/{client_app_id}/control-credentials", token=admin_token, body={
        "description": "TMS X3 dev/demo control credential",
        "effectiveUserId": admin_user_id,
        "scopes": [
            "AGENT_BUNDLE_SYNC",
            "SKILL_BUNDLE_SYNC",
            "FUNCTION_MANIFEST_IMPORT",
            "FUNCTION_GRANT_MANAGE",
            "E2E_MODEL_ENSURE",
            "UPSTREAM_USER_GRANT",
            "UPSTREAM_ROUTE_MANAGE",
            "MODEL_CONFIG_GRANT_MANAGE",
            "MODEL_CONFIG_MANAGE",
        ],
    })
    control_key = control["controlApiKey"]

grants = request("GET", f"/api/v1/client-apps/{client_app_id}/model-config-grants", token=admin_token)
grant = next((g for g in grants or [] if g.get("modelConfigId") == model_config_id), None)
if not grant:
    grant = request("POST", f"/api/v1/client-apps/{client_app_id}/model-config-grants", token=admin_token, body={
        "modelConfigId": model_config_id,
        "isDefault": True,
        "grantScope": "APP",
    })
elif not grant.get("isDefault"):
    request("PUT", f"/api/v1/client-apps/{client_app_id}/model-config-grants/{grant.get('id')}/default", token=admin_token)

control_headers = {"X-Client-App-Control-Key": control_key}
agent_bundle = request("POST", "/api/v1/business-agent/agent-bundles/sync", headers=control_headers, body={
    "clientAppId": client_app_id,
    "agentCode": AGENT_CODE,
    "skillId": SKILL_ID,
    "name": AGENT_NAME,
    "description": AGENT_DESC,
    "status": "ENABLED",
    "workerId": WORKER_ID,
    "defaultModelConfigId": model_config_id,
    "defaultModel": MODEL_ID_NAME,
    "contextVisibility": "isolated",
    "markdownBody": (
        f"# {AGENT_NAME}\n\n"
        f"You are the Navigator Business Agent for tenant `{TENANT_ID}` and ClientApp `{client_app_id}`.\n"
        "Use registered business functions when a user asks for controlled TMS execution. "
        "If no business function is available for a request, explain the limitation and ask TMS to sync the required function manifest.\n"
        "For bootstrap smoke tasks, return a concise readiness confirmation."
    ),
    "functions": [],
    "materialize": MATERIALIZE_AGENT_BUNDLE,
})

upstream_user_grant = request(
    "POST",
    f"/api/v1/business-agent/client-apps/{client_app_id}/upstream-users",
    headers=control_headers,
    body={
        "upstreamUserId": SEED_USER,
        "upstreamUserToken": SEED_USER_TOKEN,
        "status": "ENABLED",
    },
)

smoke_task = None
if RUN_SMOKE_TASK:
    session_id = f"platform-smoke-{int(time.time())}"
    created_task = request("POST", "/api/v1/business-agent/tasks", token=admin_token, body={
        "clientAppId": client_app_id,
        "sessionId": session_id,
        "contextId": session_id,
        "upstreamUserId": SEED_USER,
        "skillId": SKILL_ID,
        "workerPoolId": POOL_ID,
        "requestedModelConfigId": model_config_id,
        "clientContextJson": json.dumps({"source": "platform-bootstrap", "smoke": True}, ensure_ascii=False),
    })
    worker_task = None
    worker_task_id = created_task.get("workerTaskId")
    if worker_task_id:
        for _ in range(15):
            try:
                worker_task = request(
                    "GET",
                    f"/api/v1/langgraph-tasks/{worker_task_id}?userId={admin_user_id}",
                    token=admin_token,
                )
            except Exception as exc:
                worker_task = {"error": str(exc)}
            if isinstance(worker_task, dict) and worker_task.get("status") in ("COMPLETED", "FAILED", "ABORTED"):
                break
            time.sleep(2)
    smoke_task = {
        "taskId": created_task.get("taskId"),
        "sessionId": created_task.get("sessionId"),
        "workerTaskId": worker_task_id,
        "workerSessionId": created_task.get("workerSessionId"),
        "status": created_task.get("status"),
        "workerStatus": worker_task.get("status") if isinstance(worker_task, dict) else None,
        "workerError": worker_task.get("errorMessage") if isinstance(worker_task, dict) else None,
    }

setup_status = request("GET", "/api/v1/config/platform/setup-status", token=admin_token)

UPSTREAM_ENV_FILE.parent.mkdir(parents=True, exist_ok=True)
upstream_env = f"""# Generated by deploy/dev-kvm-x3/remote/bootstrap-platform.sh.
# Keep this file out of Git. It contains ClientApp secrets for TMS dev/demo.
NAVI_BASE_URL={PUBLIC_BASE}
NAVI_TENANT_ID={TENANT_ID}
NAVI_CLIENT_APP_ID={client_app_id}
NAVI_CLIENT_APP_KEY={client_app_key}
NAVI_CLIENT_APP_SECRET={client_app_secret}
NAVI_CLIENT_APP_ACCESS_TOKEN=
NAVI_CONTROL_API_KEY={control_key}
NAVI_UPSTREAM_USER_ID={SEED_USER}
NAVI_UPSTREAM_USER_TOKEN={SEED_USER_TOKEN}
NAVI_AGENT_CODE={AGENT_CODE}
NAVI_SKILL_ID={SKILL_ID}
NAVI_MODEL_CONFIG_ID={model_config_id}
NAVI_WORKER_POOL_ID={POOL_ID}
NAVI_POLL_INTERVAL_SECONDS=4

NAVIGATOR_BASE_URL={PUBLIC_BASE}
NAVIGATOR_TENANT_ID={TENANT_ID}
NAVIGATOR_ACTOR_USER_ID={admin_user_id}
NAVIGATOR_MODEL_CONFIG_ID={model_config_id}
NAVIGATOR_WORKER_POOL_ID={POOL_ID}
TMS_WEB_BASE_URL={TMS_WEB_BASE_URL}
TMS_UPSTREAM_USER_ID={SEED_USER}
TMS_USER_TOKEN={SEED_USER_TOKEN}
"""
UPSTREAM_ENV_FILE.write_text(upstream_env, encoding="utf-8")
os.chmod(UPSTREAM_ENV_FILE, 0o600)

report = {
    "navigatorBaseUrl": PUBLIC_BASE,
    "tenantId": TENANT_ID,
    "tenantAdmin": ADMIN_USERNAME,
    "tenantAdminCreated": created_admin,
    "tenantAdminUserId": admin_user_id,
    "llmModelConfigId": model_config_id,
    "llmCreated": created_model,
    "llmModel": MODEL_ID_NAME,
    "langgraphWorkerId": WORKER_ID,
    "langgraphWorkerBaseUrl": WORKER_BASE_URL,
    "langgraphWorkerCreated": created_worker,
    "langgraphWorkerStatus": worker.get("status"),
    "workerPoolId": POOL_ID,
    "workerPoolCreated": created_pool,
    "workerPoolMemberAdded": pool_member_added,
    "clientAppId": client_app_id,
    "clientAppName": CLIENT_APP_NAME,
    "clientAppCreated": created_client_app,
    "agentCode": AGENT_CODE,
    "skillId": SKILL_ID,
    "agentBundleSynced": bool(agent_bundle),
    "agentBundleMaterialized": MATERIALIZE_AGENT_BUNDLE,
    "agentBundleName": agent_bundle.get("name") if isinstance(agent_bundle, dict) else None,
    "upstreamUserId": SEED_USER,
    "upstreamUserGrantId": upstream_user_grant.get("grantId") if isinstance(upstream_user_grant, dict) else None,
    "upstreamUserGrantStatus": upstream_user_grant.get("status") if isinstance(upstream_user_grant, dict) else None,
    "modelGrantId": grant.get("id"),
    "modelGrantDefault": True,
    "smokeTask": smoke_task,
    "setupStatus": setup_status,
    "tmsUpstreamEnvFile": str(UPSTREAM_ENV_FILE),
}
REPORT_FILE.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
os.chmod(REPORT_FILE, 0o600)

print(json.dumps(report, ensure_ascii=False, indent=2))
PY
