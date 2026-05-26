---
type: bug
bug_source: live-smoke
version: 1.3.0-SNAPSHOT
ticket: BUG-042
severity: major
status: fixed-pending-live-validation
reproduction_status: observed
test_strategy: live-smoke
automation_decision: required
---

# BUG-042 Biz Agent Skill Handoff Smoke

## Summary

School Sim Biz live ask 已证明 provider credential、Agent routing 和 Worker execution 恢复，但 PM actor smoke 没有写 marker，而是进入旧的订单诊断流程。

这不是 `BUG-041` 的 provider/transport/diagnostics 问题。`BUG-041` 已解决“任务是否到达 worker、providerTaskId/workerTaskId 是否可见、失败阶段是否可诊断”。本问题单独跟踪 Biz actor live smoke 的 prompt/skill handoff 口径和验证。

## Evidence

### R4

- Agent: `school-sim.actor.pm.m2.v1`
- Task: `lgt_468350fcdeac400b`
- Context: `bctx_20260526_bc_bc90de810c9b49feb7ebbb7d72346568`
- Final status: `COMPLETED`
- `providerTaskId`: `lgt_468350fcdeac400b`
- `workerTaskId`: `lgt_468350fcdeac400b`
- Messages count: `12`
- `failureStage/failureSummary`: empty
- Marker: not generated
- Observed behavior: task reached Biz Worker but followed the legacy order diagnostic flow instead of the marker instruction.

### R5

- Agent: `school-sim.actor.pm.m2.v1`
- Task: `lgt_933e770093584927`
- Context: `bctx_20260526_40_405fd44bf9b64405b9539baf6596821f`
- Final status: `COMPLETED`
- `providerTaskId`: `lgt_933e770093584927`
- `workerTaskId`: `lgt_933e770093584927`
- Messages count: `12`
- `failureStage/failureSummary`: empty
- Marker: `simulations/school/runs/2026-05-24-m2-owner-aware-001/actors/pm/biz-m2-live-20260526-r5.txt`
- Marker content: not generated
- Observed legacy flow:
  - `Opening child frame: order_evidence_collect`
  - `Opening child frame: address_verify`
  - `Opening child frame: rule_check`
  - `Order diagnosed as vehicle_delay, recommend manual_dispatch...`

### R6

- Agent: `school-sim.actor.pm.m2.v1`
- Task: `lgt_9f0fe937bc6a4a29`
- Context: `bctx_20260526_0b_0be1c3b50aae4adf9418c45311a71d38`
- Final status: `COMPLETED`
- `providerTaskId`: `lgt_9f0fe937bc6a4a29`
- `workerTaskId`: `lgt_9f0fe937bc6a4a29`
- Messages count: `3`
- `failureStage/failureSummary`: empty
- Marker: `simulations/school/runs/2026-05-24-m2-owner-aware-001/actors/pm/biz-m2-live-20260526-r6.txt`
- Marker content: not generated
- Legacy order diagnostic flow: not entered
- Observed behavior: BizWorker returned `未能识别出与请求匹配的业务技能`, proving the task fell back before Root LLM skill selection.

R6 runtime inspection found the remaining root cause: live tasks were routed to Biz Worker `3161`, but the launcher default for BusinessAgent skill materialization still pointed to `http://localhost:3061`. As a result, the PM skill was not present in the actual worker-side catalog used by the live task.

## Decision

A2A direct ask should not require upstream callers to populate hidden skill routing fields such as `businessSkillName`, `businessSkillId`, `skill_name`, or `skillId` in `clientContext`, metadata, or profile env files.

For live actor smoke, the target actor/skill must be expressed in the user message, for example:

```text
请使用 school-sim.actor.pm.m2.v1 技能，完成 School Sim M2 PM live ask smoke。
```

If the task reaches Biz Worker and completes but still follows an unrelated legacy flow, record it as Biz prompt/skill handoff behavior and capture sanitized task evidence. Do not ask upstream to retry by adding hidden routing metadata.

`agentId` remains required in OpenAPI and CLI calls because it locates the target A2Agent route. It is not a replacement for the user-message skill instruction when validating a specific Biz actor skill.

## Fix

- `root_graph.py` no longer parses user prompt text to route or bind a skill.
- Persistent `system.root` execution now injects the loaded account/public/app-public skill catalog into the Root system prompt as `id`/`name`/`description`.
- When `allowed_skills` is missing or empty, BizWorker uses the default loaded skill catalog. When `allowed_skills` is non-empty, it filters the default loaded catalog to those skill ids and ignores unknown ids.
- Removed the legacy automatic `order_id -> exception_triage` routing path. A plain order context can no longer open the legacy order diagnostic flow implicitly.
- Legacy non-LLM fallback now only honors explicit trusted skill context. It rejects non-programmatic skills instead of silently running `exception_triage`; explicit `exception_triage` remains supported by the old programmatic subgraph fallback for compatibility.
- A2A direct ask ignores hidden skill routing aliases at top level and strips `skill_name`/`skillName`/`skill_id`/`skillId`/`businessSkillName`/`businessSkillId` from A2A metadata, A2A `context`, A2A `runtimeContext`, and echoed task history before forwarding to Worker. `agentId` remains the A2Agent route key.
- OpenAPI Business runtime context no longer injects hidden `businessSkillId`/`businessSkillName` or `runtimeContext.skill_name` into A2A metadata. The task-scoped token is still issued with the resolved skill internally, but skill selection for live ask is driven by the user message plus the worker-visible skill catalog.
- `start-launcher.ps1` now defaults `BUSINESS_AGENT_DEV_SYNC_WORKER_URL` to `http://127.0.0.1:3161` when the env var is not set, so local Skill sync materializes into the same Biz Worker used by live routing.
- Added `tools/langgraph-biz-worker/restart-wsl-3161.ps1` for WSL Biz Worker restart and optional source sync.
- Request-scoped `llm_config` from Navigator now enables Root LLM execution even when the WSL worker-local `.env` has `BIZ_WORKER_LLM_EXECUTE_SKILLS=false`. The global flag only controls worker-local default LLM execution; a Navigator-resolved model config is an explicit per-task execution config.

## Documentation Updates

- `C:/Users/oldse/.claude/skills/navigator-upstream-cli/SKILL.md`
- `C:/Users/oldse/.claude/skills/navigator-upstream-llm-integration/SKILL.md`
- `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/18-navigator-upstream-cli-usage-guide.md`
- `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/19-navigator-upstream-cli-install-update.md`
- `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/38-sim-biz-worker-skill-handoff.md`

## Prompt Hardcoding Scan

- Python Root LLM prompt construction is centralized in `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_agent_prompts.py`. It hardcodes Root identity, tool/skill policy, completion contract, runtime context projection, and the `可用业务技能` rendering, but it does not parse user prompt text for skill routing.
- `root_graph.py` hardcodes the `system.root` orchestration manifest and allowed tools. It now injects the visible skill catalog and no longer maps `order_id` to `exception_triage`.
- Legacy order diagnostic strings remain in programmatic sample skills/manifests and tests: `exception_triage`, `order_evidence_collect`, `address_verify`, and `rule_check`. They are no longer entered by a plain order context.
- `llm_skill_agent.py` contains generic child-agent and trusted bound-skill prompt templates. The bound-skill path depends on internal runtime context, not regex over user prompt text.
- Java A2A direct ask now forwards the user prompt without hidden skill metadata. Java does not construct the Root system prompt.
- `LanggraphBusinessAgentWorkerTaskLauncher` still contains a fixed task prompt (`Business Agent task ... Use the business function tools ...`) and context fields for `businessSkillId`/`businessSkillName`. That is the BusinessAgent launcher path, not the A2A live-ask path; track it separately if that API must preserve caller wording.

## Next Live Validation

Ask payload should include the actor skill instruction in the message:

```text
请使用 school-sim.actor.pm.m2.v1 技能，完成 School Sim M2 PM live ask smoke R7。
不要执行订单诊断，不要改走旧的订单诊断流程，不要输出任何密钥、token、API key 或 credential。

请写入 marker：
simulations/school/runs/2026-05-24-m2-owner-aware-001/actors/pm/biz-m2-live-20260526-r7.txt

文件内容必须严格为：
SCHOOL_SIM_M2_BIZ_PM_20260526_R7_OK

完成后只返回 marker 路径和 marker 内容。
```

Report required:

- `taskId`
- `contextId`
- final status
- `providerTaskId`
- `workerTaskId`
- messages count
- `failureStage/failureSummary`
- marker path
- marker content
- whether the legacy order diagnostic flow appeared

## Verification

Local regression tests:

```powershell
cd tools/langgraph-biz-worker
$env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest tests/test_root_graph.py tests/test_single_skill.py tests/test_child_skill.py tests/test_multi_child_aggregation.py tests/test_three_level_nesting.py tests/test_context_isolation.py tests/test_e2e_smoke.py tests/test_graph_edges.py -q
$env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m ruff check src/langgraph_biz_worker/graphs/root_graph.py tests/test_root_graph.py tests/test_single_skill.py tests/test_child_skill.py tests/test_multi_child_aggregation.py tests/test_three_level_nesting.py tests/test_context_isolation.py tests/test_e2e_smoke.py
cd ..\..
mvn -pl addons/langgraph-biz-worker -am "-Dtest=LanggraphWorkerInnerA2aAgentTest" "-Dsurefire.failIfNoSpecifiedTests=false" test -q
mvn -pl addons/claude-worker-agent -am "-Dtest=OpenApiControllerMessageMappingTest" "-Dsurefire.failIfNoSpecifiedTests=false" test -q
mvn -pl addons/langgraph-biz-worker -am "-Dtest=LanggraphBusinessAgentWorkerTaskLauncherTest,BusinessAgentLanggraphLaunchE2ETest" "-Dsurefire.failIfNoSpecifiedTests=false" test -q
powershell -ExecutionPolicy Bypass -File tools\langgraph-biz-worker\restart-wsl-3161.ps1 -SyncSource
.\start-launcher.ps1
.\tools\navigator-upstream\navi.ps1 upstream skill sync --profile <school-m2-pm-smoke.env> --scope client-app-public --manifest <pm.client-app-public.json>
.\tools\navigator-upstream\navi.ps1 upstream skill sync --profile <school-m2-pm-smoke.env> --scope account-private --manifest <pm.account-private.json> --upstream-user-id sim-upstream-user-local
```

Result:

- Python BizWorker regression: `66 passed`
- Python BizWorker regression after R6 request-scoped LLM fix: `68 passed`
- Python `ruff check`: passed
- Java A2A adapter regression: passed
- Java OpenAPI metadata mapping regression: passed
- Java BusinessAgent launcher/LangGraph launch regressions: passed
- WSL 3161 restarted with source sync; `/health` returned `active_tasks=0`, `worker_name=biz-worker-dev`
- WSL 3161 restarted again after request-scoped LLM fix; new process `89695`, `/health` returned `active_tasks=0`, `worker_name=biz-worker-dev`
- 8112 restarted with `start-launcher.ps1`; `/actuator/health` returned `UP`.
- PM public and account-private skill sync returned `materializeStatus=MATERIALIZED`, `workerStatusCode=200`.
- Direct 3161 `/api/v1/skills/resolve` returned `resolved=true`, `account_skill_exists=true`, `client_app_public_skill_exists=true` for `school-sim.actor.pm.m2.v1`.

## Acceptance Criteria

- Biz PM live ask produces the requested marker.
- Task exposes `providerTaskId` and `workerTaskId`.
- Messages are non-empty and terminal status is explicit.
- No secrets appear in reports, prompts, docs, logs, or screenshots.
- If the marker is still missing, the report clearly separates worker/provider success from prompt/skill handoff failure.
