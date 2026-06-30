# Programming Project Orchestration

This reference was split from the navigator-upstream-cli skill main file. Read it only when the current task matches the section title or routing guidance in SKILL.md.

## Programming Project Orchestration

For non-TMS coding projects, an upstream orchestrator can use `NAVI_ADMIN_API_KEY` to maintain tenant-level worker resources, then create a ClientApp, model config, and A2A agent. Keep all request JSON under `.navigator/` and do not paste tokens into prompts or logs.

Worker lifecycle:

```powershell
.\tools\navigator-upstream\navi.ps1 upstream worker create --file .navigator/worker.json --target-tenant-id <tenantId> --write-profile
.\tools\navigator-upstream\navi.ps1 upstream worker list --target-tenant-id <tenantId>
.\tools\navigator-upstream\navi.ps1 upstream worker health
.\tools\navigator-upstream\navi.ps1 upstream worker processes
```

For a LangGraph BizWorker trial, install the worker package first, start it on a reachable port, then use that `baseUrl` in `worker.json`. Linux is the preferred runtime for command-enabled trials:

```bash
curl -fsSL https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/langgraph-biz-worker/install.sh | bash
cd ~/.langgraph-biz-worker
cp .env.example .env
printf '\nBIZ_WORKER_ENABLE_COMMAND=true\n' >> .env
python -m venv .venv
. .venv/bin/activate
pip install -e .
uvicorn langgraph_biz_worker.main:app --host 0.0.0.0 --port 3065
```

`worker.json` is the Navigator `RegisterWorkerForm`, for example:

```json
{
  "name": "langgraph-biz-worker-1",
  "baseUrl": "http://127.0.0.1:3065",
  "authToken": "<worker-auth-token>",
  "authMode": "BEARER",
  "workerBackend": "LANGGRAPH_BIZ"
}
```

Working directory lifecycle:

```powershell
.\tools\navigator-upstream\navi.ps1 upstream directory init --file .navigator/directory-init.json --write-profile
.\tools\navigator-upstream\navi.ps1 upstream directory list --worker-id <workerId>
.\tools\navigator-upstream\navi.ps1 upstream directory env --directory-id <directoryId> --file .navigator/directory-env.json
.\tools\navigator-upstream\navi.ps1 upstream directory files --directory-id <directoryId> --file .navigator/directory-files.json
```

`directory-init.json` contains `workerId`, absolute worker-side `path`, optional `projectName`, and initial `files`. Use this path as the coding agent workspace; do not reuse a ClientApp account data directory as a code workspace.

WorkerPool is now an internal Navigator routing artifact. Normal upstream bootstrap should not create or select WorkerPool directly. Use `worker create`, `directory init`, `model create/system-create`, and `agent sync/system-create`; readiness will show `physicalWorkerId`, `effectiveWorkerBackend`, and any internal route if the current server still uses one.

After worker resources exist, create/reuse the ClientApp, then create a ClientApp-owned model config and sync the root A2A agent:

```powershell
.\tools\navigator-upstream\navi.ps1 upstream client-app ensure --target-tenant-id <tenantId> --upstream-ref <projectCode> --name "<projectName>" --write-profile
.\tools\navigator-upstream\navi.ps1 upstream client-app issue-runtime-key --client-app-id <clientAppId> --write-profile
.\tools\navigator-upstream\navi.ps1 upstream client-app issue-control-key --client-app-id <clientAppId> --write-profile

$env:NAVI_LLM_API_KEY="<llm-api-key>"
.\tools\navigator-upstream\navi.ps1 upstream model create --name "<modelName>" --model-base-url "https://llm.example/v1" --model-name "<model>" --set-default --write-profile

.\tools\navigator-upstream\navi.ps1 upstream agent sync --manifest .navigator/agent-bundle.json
```

`model create/update/rotate-key` and `agent sync` accept `NAVI_ADMIN_API_KEY` as an upstream-admin fallback when `NAVI_CONTROL_API_KEY` has not been issued yet. Navi still checks that `NAVI_CLIENT_APP_ID` belongs to the approved upstream system, namespace, and tenant list.
