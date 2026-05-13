---
type: bug
bug_source: user-report
version: 1.3.0-SNAPSHOT
ticket: BUG-open-task-messages-terminal-status
severity: major
status: ready-for-verification
reproduction_status: confirmed
test_strategy: unit-test
automation_decision: required
owner: claude-worker-agent
---

# Open Task Messages Terminal Status Bug

## Background

Upstream polls `/bff/navigator/agent/api/v1/open/agents/{agentId}/tasks/{taskId}/messages`
with a cursor. After the task has completed, the response may contain no new messages and no
task-level terminal state:

```json
{
  "messages": [],
  "status": null,
  "terminal": false,
  "terminalStatus": null
}
```

The frontend widget depends on the messages page `terminal` signal to stop task polling and to
finalize running skill/tool frames.

## Reproduction

1. Start a TMS agent task that invokes a skill/tool.
2. Poll the open messages endpoint with the returned cursor.
3. Observe multiple empty pages during or after execution.
4. After the task completes, observe that an empty page does not report task terminal state.

## Expected vs Actual

Expected:
- The messages page should expose the current task `status`.
- If the task is terminal, the page should set `terminal=true` and provide `terminalStatus`.
- This must work even when the page contains no new messages.

Actual:
- The page DTO did not include task-level status fields.
- Upstream could keep rendering skill/tool frames as running when no terminal message was present in the current page.

## Impact Scope

- Open API task message polling.
- Navigator chat widget and upstream integrations that rely on SDK page fields.
- Skill/tool execution UI finalization after task completion.

## Test Strategy

Add focused unit coverage for terminal status derivation from task status and verify the existing
message mapping behavior remains intact.

## Code Inventory

- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiController.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/model/dto/OpenTaskMessagesResponse.java`
- `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/model/TaskMessagesPage.java`
- `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/model/SessionMessage.java`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiControllerMessageMappingTest.java`

## Fix Checklist

- Add task-level `status`, `terminal`, and `terminalStatus` to the open messages response.
- Derive terminal status from persisted task status when building a messages page.
- Mirror the response fields in the Java open SDK models.
- Add regression coverage for status-to-terminal mapping.

## Verification

Command:

```powershell
mvn -pl addons/claude-worker-agent -am "-Dtest=OpenApiControllerMessageMappingTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Result:

- Passed. `OpenApiControllerMessageMappingTest`: 6 tests, 0 failures, 0 errors.

Command:

```powershell
mvn -pl navigator-open-sdk "-DskipTests" compile
```

Result:

- Passed.
