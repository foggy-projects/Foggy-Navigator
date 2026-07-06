# TMS UI Experience Reviewer Navi Provisioning Run - 2026-07-05

## Scope

- actor: `tms-ui-experience-reviewer-a`
- scope: `tms-ltl-ui-qa`
- role: `ui-experience-reviewer`
- target agent: `world-sim.biz-worker-browser-smoke.v1`
- directoryId: `20260705-228b`
- ClientApp: `capp_2852124a-48f7-4098-9d5e-33eb736c4375`
- profile: `.navigator/upstream.env` (gitignored)

## Boundaries

- Did not access real TMS.
- Did not read `accounts/`.
- Did not print or persist token, secret, cookie, real account, password, admin key, control key, runtime token, or claim token in tracked files.
- Did not dispatch the first UI inspection task.

## Initial Cause

The provisioning failure was caused by stale / cross-owner local dev data rather than a missing runtime capability:

- The requested modelConfig `e8b12e11-bebc-4446-9601-20418d11c28a` belonged to a different tenant and failed with a tenant mismatch.
- Directory `20260705-228b` existed under an old sandbox ClientApp, so it was not visible to the current TMS ClientApp until local dev data was repaired.
- The current TMS ClientApp row had no `upstream_system_id`, preventing the TMS-owned Biz Worker identity from being visible during readiness.
- The agent manifest still declared `defaultModel=LangBizWorker` after switching the modelConfig to an OpenAI-compatible model, causing model variant validation to fail until the agent was re-synced.

## Provisioning Result

Completed on 2026-07-05:

- Created / refreshed TMS gitignored profile with admin, control, and runtime lanes.
- Applied TMS worker-host manifest from `.navigator/tms-ui-experience-reviewer-worker-host.json`.
- Verified Biz Worker identity:
  - `workerId=tms-ui-experience-reviewer-biz`
  - `source=BIZ_WORKER_IDENTITY`
- Created current-ClientApp-owned modelConfig:
  - `modelConfigId=a8ed6f14-949c-4003-b108-99b78de65ff5`
  - `name=tms-ui-experience-reviewer-langbiz-20260705b`
  - `modelName=gpt-oss-120b-medium`
  - `workerBackend=LANGGRAPH_BIZ`
- Synced agent `world-sim.biz-worker-browser-smoke.v1` to the current TMS ClientApp.
- Bound default model to `a8ed6f14-949c-4003-b108-99b78de65ff5`.
- Bound default workspace to `directoryId=20260705-228b`.
- Ensured upstream user grant for `tms-ui-experience-reviewer-a`.
- Set `TMS-88800` to a local mock route `http://localhost:8200` for Navigator readiness only.

## Smoke Evidence

### Readiness

- command family: `upstream verify-agent-readiness`
- result: passed
- effectiveModelConfigId: `a8ed6f14-949c-4003-b108-99b78de65ff5`
- effectiveModelName: `gpt-oss-120b-medium`
- effectiveWorkerBackend: `LANGGRAPH_BIZ`
- workerRole biz source: `BIZ_WORKER_IDENTITY`
- effectiveDirectoryId: `20260705-228b`
- `WORKER_HOST_ROLE_ROUTING=OK`
- `FILE_TOOL_ROOT_ALIGNMENT=OK`
- `OWNER_AWARE_RUNTIME_RESOURCES=OK`

### Owner Smoke

- command family: `upstream owner-smoke`
- result: passed
- readiness: OK
- resources: OK
- effective directory: `20260705-228b`

### Actor Home Live Smoke

- prompt: `docs/scopes/tms/tms-ltl-ui-qa/rehearsals/prompts/ui-experience-reviewer-actor-home-live-smoke-20260705-001.md`
- taskId: `lgt_5f997dcdb8834d51`
- contextId: `bctx_20260705_39_39d465b61ad44639a32af9db045fd723`
- taskStatus: `COMPLETED`
- workerSource: `BIZ_WORKER_IDENTITY`
- modelConfigSource: `AGENT_DEFAULT_MODEL:REQUESTED_MODEL_GRANT`
- result: runtime smoke can proceed; effective directory is `20260705-228b`; no real TMS access or business functions required.

## Isolation Evidence

- SIM profile attempting to list TMS ClientApp model grants failed with `HTTP 403: control-plane credential clientAppId mismatch`.
- TMS profile attempting to list SIM ClientApp model grants failed with `HTTP 403: control-plane credential clientAppId mismatch`.

## Follow-Up

- Do not send the first UI inspection task until the product owner explicitly approves after this smoke evidence.
- Keep `.navigator/upstream.env`, worker-host manifests with local tokens, and tenant profiles gitignored.
- For production, replace local mock route and local dev DB repairs with normal bootstrap / provisioning operator flows.
