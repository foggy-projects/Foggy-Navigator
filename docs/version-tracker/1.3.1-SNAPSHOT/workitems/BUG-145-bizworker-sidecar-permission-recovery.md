---
type: bug
bug_source: user-report
version: 1.3.1-SNAPSHOT
ticket: BUG-145
github_issue: https://github.com/foggy-projects/Foggy-Navigator/issues/145
severity: major
status: accepted-with-risks
reproduction_status: confirmed
test_strategy: "unit-test + java-relay-regression + wsl-permission-smoke"
automation_decision: required
owner: langgraph-biz-worker
created_at: 2026-06-26
---

# BUG-145: BizWorker Sidecar Permission Recovery

## Background

SIM TMS rehearsal exposed a BizWorker sidecar repair task that needed to write `REPORT.md`, `task-progress-writeback.json`, and `task-self-check.json` under an Actor Home task directory.

The directory had been materialized by a Windows/UNC process into WSL Actor Home and ended up owned by `root:root` with mode `755`, while BizWorker runs as `navigator`. `write_file` therefore hit `PermissionError: [Errno 13] Permission denied`.

## Actual Behavior

- The file-tool layer did not classify write permission denial as a stable runtime error.
- The LLM saw a generic tool failure and could continue diagnosing worker logs, source files, or system directories.
- The upstream task could remain observable as long-running `SUBMITTED` instead of receiving a terminal recoverable failure signal.

## Expected Behavior

- BizWorker file-tool permission denial is reported as `storage_permission_denied`.
- The current LLM turn stops immediately with `llm_retry_allowed=false`.
- The error remains upstream-recoverable with `recoverable=true` and `requires_upstream_action=true`.
- Runtime memory and stream projections expose enough metadata for upstream to mark the task failed and retry after fixing directory ownership or sidecar materialization.

## Scope

- Classify account file-tool write-side `PermissionError` without leaking absolute filesystem paths.
- Convert `storage_permission_denied` into a terminal tool result for the current LLM turn.
- Preserve recoverability metadata for runtime memory projection.
- Preserve the same metadata through Java SSE `error` relay so the task is failed and the frontend/upstream sees the recovery signal.
- Add focused regressions for the file tool, dispatcher, LLM loop, projection metadata, Java relay, and A2A failed-state query.

## Non-Goals

- No production-grade filesystem sandbox in this bug fix.
- No change to Java A2A task-state mapping implementation; regression coverage now locks the existing `FAILED` mapping.
- No automatic chmod/chown repair from BizWorker.

## Recommended Operation

For already stuck tasks, cancel or mark the provider task failed, repair the Actor Home directory ownership/permissions, and dispatch a fresh repair context. Upstream sidecar materialization should go through Navi/Directory Worker APIs where possible so files are written by the same runtime user boundary used by BizWorker.

## Upstream Sidecar Materialization Runbook

Preferred path:

1. Create or update sidecar files through Navi/Directory Worker APIs instead of a Windows/UNC side process writing directly into WSL Actor Home.
2. Ensure `REPORT.md`, `task-progress-writeback.json`, and `task-self-check.json` are owned by the same runtime boundary that will execute BizWorker file tools.
3. Dispatch the repair task only after the target task directory is writable by the BizWorker runtime user.

Fallback path when files are staged outside Navi:

1. Before dispatch, verify directory ownership and writability from the BizWorker runtime user, for example `sudo -u navigator touch <task-dir>/.navi-write-smoke`.
2. If ownership is wrong, repair the directory outside the LLM turn with the platform operator path, then remove the smoke file.
3. Do not ask the LLM to inspect worker logs, source directories, `/mnt`, `/var`, `/etc`, or other system paths after `storage_permission_denied`; the error already means upstream storage ownership must be repaired.

Stuck task cleanup:

1. Cancel or mark the current provider task failed; do not wait for another LLM retry.
2. Repair ownership/permissions or rematerialize the sidecar files through Navi/Directory Worker.
3. Start a new repair task with the same business context and record the old task id as superseded.

## Verification Plan

```powershell
cd tools/langgraph-biz-worker
$env:PYTHONPATH='src'
python -m pytest tests/test_account_file_tools.py tests/test_llm_tool_dispatcher.py tests/test_llm_skill_agent.py tests/test_root_graph.py -q

mvn test -pl addons/langgraph-biz-worker -am "-Dtest=LanggraphStreamRelayTest,LanggraphWorkerInnerA2aAgentTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"
```

WSL permission smoke:

```bash
base=$(mktemp -d /tmp/foggy-bug145-smoke.XXXXXX)
mkdir -p "$base/workspace"
chown root:root "$base/workspace"
chmod 755 "$base/workspace"
sudo -u navigator touch "$base/workspace/REPORT.md"
```

Expected result: `Permission denied`.

## Progress

- 2026-06-26: Created BUG-145 work item from GitHub issue #145.
- 2026-06-26: Implemented stable `storage_permission_denied` classification for file-tool storage permission failures.
- 2026-06-26: Added terminal LLM-loop handling for storage permission denial with upstream-recoverable metadata.
- 2026-06-26: Added focused regression coverage.
- 2026-06-26: Verification passed: `.venv\Scripts\python.exe -m pytest tests/test_account_file_tools.py tests/test_llm_tool_dispatcher.py tests/test_llm_skill_agent.py tests/test_root_graph.py -q` -> 152 passed, 2 skipped.
- 2026-06-26: Added Java relay metadata propagation for worker `error` events and A2A failed-state regression coverage.
- 2026-06-26: Verification passed: `mvn test -pl addons/langgraph-biz-worker -am "-Dtest=LanggraphStreamRelayTest,LanggraphWorkerInnerA2aAgentTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"` -> 23 passed.
- 2026-06-26: WSL permission smoke confirmed `navigator` cannot write into `root:root 755` task directory; failure is `Permission denied`.
- 2026-06-26: Quality gate, coverage audit, and acceptance record completed with one residual follow-up: validate in next live SIM replay.
