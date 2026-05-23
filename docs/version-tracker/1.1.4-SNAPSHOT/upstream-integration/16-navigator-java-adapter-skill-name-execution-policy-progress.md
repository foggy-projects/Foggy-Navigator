# Navigator Java Adapter Skill Name And Execution Policy Progress

- doc_type: implementation-progress
- version: 1.1.4-SNAPSHOT
- status: implemented
- date: 2026-05-19
- scope: Navigator Java enterprise adapter -> LangGraph BizWorker request contract

## Decision

Navigator Java keeps `skillId` as the internal compatibility and authorization field for the current enterprise tables, grants, task-scoped token records, and existing public APIs.

The Worker-facing contract now carries `skill_name` as the canonical identity. In this phase, Java resolves `skillName` from the optional alias and falls back to `skillId`; if both are supplied they must match. This matches the current assumption that BizWorker treats `skill_id` as the governed folder `skill_name` until the Java-side storage model is renamed later.

## Implemented Flow

1. `CreateBusinessAgentTaskForm` accepts:
   - `skill_name` / `skillName`
   - `workdir`
   - `allowed_dirs` / camelCase aliases
   - `allowed_tools` / camelCase aliases
2. `BusinessAgentTaskService` validates the compatibility alias, keeps authorization on `skillId`, and passes `skillName` plus execution policy fields into `BusinessAgentWorkerTaskLaunchRequest`.
3. `LanggraphBusinessAgentWorkerTaskLauncher` sets `CreateLanggraphTaskForm.skillName`, removes visible `skillId` from LLM context, and places `execution_policy` in hidden `runtimeContext`.
4. `LanggraphTaskService` stores `skillName` in provider config for relay use.
5. `LanggraphStreamRelay` forwards provider `skillName` to `LanggraphWorkerClient`.
6. `LanggraphWorkerClient` sends top-level JSON `skill_name` to Python BizWorker.
7. OpenAPI ask supports top-level `workdir`, `allowed_dirs`, and `allowed_tools`; these are merged into `metadata.context.execution_policy`. The derived business skill is forwarded as metadata `skill_name`, not as visible prompt text.

## Explicit Non-Goals

- No plugin hot reload in this phase.
- No Java-owned multi-tenant sandbox in BizWorker.
- No global Java DB rename from `skillId` to `skillName`.
- No removal of legacy `skillId` from task DB records or task-scoped token authorization.

BizWorker remains responsible for enforcing the upstream execution policy against the actual local paths and tool allowlist. Navigator Java only carries the contract and prevents accidental LLM-visible identifier leakage in the LangGraph launch context.

## Test Evidence

Targeted tests added or updated:

- `BusinessAgentTaskServiceTest`
- `LanggraphBusinessAgentWorkerTaskLauncherTest`
- `LanggraphTaskServiceTest`
- `LanggraphWorkerClientTest`
- `LanggraphWorkerInnerA2aAgentTest`
- `OpenApiControllerMessageMappingTest`
- `BusinessAgentApiSmokeTest`

Verification command:

```powershell
mvn -pl business-agent-module,addons/langgraph-biz-worker,addons/claude-worker-agent,navigator-open-sdk -am `
  "-Dtest=BusinessAgentTaskServiceTest,LanggraphBusinessAgentWorkerTaskLauncherTest,LanggraphTaskServiceTest,LanggraphWorkerClientTest,LanggraphWorkerInnerA2aAgentTest,OpenApiControllerMessageMappingTest,BusinessAgentApiSmokeTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

