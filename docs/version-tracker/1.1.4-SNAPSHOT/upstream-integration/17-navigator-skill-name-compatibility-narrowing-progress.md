# Navigator skill_name Compatibility Narrowing Progress

- doc_type: implementation-progress
- version: 1.1.4-SNAPSHOT
- status: implemented
- date: 2026-05-19
- scope: Navigator LangGraph Java adapter -> BizWorker skill identity boundary

## Decision

Navigator Java keeps the enterprise `skillId` model for database records, grants, task-scoped tokens, and authorization.

At the LangGraph/BizWorker boundary, `skill_name` is the canonical worker-facing identity. Java `skillName` remains a transitional Java-side alias in provider config for existing in-process relay code. Legacy inbound `skill_id` and `skillId` are accepted only as deprecated compatibility aliases.

## Implemented Changes

1. Added `LanggraphSkillNameContract` to centralize alias resolution.
2. `LanggraphTaskService.createTaskDirect` now resolves `skill_name`, `skillName`, `skill_id`, and `skillId` through the shared contract.
3. `LanggraphTaskService` publishes both canonical `skill_name` and transitional `skillName` in provider config.
4. `LanggraphStreamRelay` reads skill identity through the shared contract before calling `LanggraphWorkerClient`.
5. `LanggraphWorkerInnerA2aAgent` validates A2A metadata aliases before creating a LangGraph task.
6. Conflicting non-empty aliases now fail fast with `skill_name aliases must resolve to the same value`.

## Explicit Non-Goals

- No Java DB/table/entity rename from `skillId` to `skillName`.
- No removal of task-scoped token or grant authorization by `skillId`.
- No Python runtime-wide rename of internal `skill_id`.
- No BizWorker plugin hot reload or Java-owned sandbox expansion.

## Compatibility Notes

New Java -> BizWorker paths should emit `skill_name`.

Existing Java code that still reads `skillName` continues to work during the transition because provider config carries both:

```json
{
  "skill_name": "order-assistant",
  "skillName": "order-assistant"
}
```

Legacy inbound payloads remain accepted when values match:

```json
{
  "skill_id": "order-assistant"
}
```

If a payload sends conflicting aliases, Navigator rejects it before task creation.

## Test Evidence

Verification command:

```powershell
mvn -pl addons/langgraph-biz-worker -am `
  "-Dtest=LanggraphSkillNameContractTest,LanggraphTaskServiceTest,LanggraphWorkerInnerA2aAgentTest,BusinessAgentLanggraphLaunchE2ETest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" `
  "-Djdk.net.URLClassPath.disableClassPathURLCheck=true" test
```

Result:

```text
Tests run: 29, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```
