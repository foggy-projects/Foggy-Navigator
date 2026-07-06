---
type: bug
bug_source: user-report
version: 1.3.1-SNAPSHOT
ticket: BUG-146
github_issue: https://github.com/foggy-projects/Foggy-Navigator/issues/146
severity: major
status: ready-for-verification
reproduction_status: confirmed
test_strategy: unit-test
automation_decision: required
owner: langgraph-biz-worker
created_at: 2026-06-27
---

# BUG-146: BizWorker Command Workspace Owner

## Background

SIM TMS rehearsal reported that BizWorker `command` created task sidecar directories and checkpoint files under an Actor Home workspace as `root:root`, while the same workspace root and parent `tasks/` directory were owned by `navigator:navigator`.

When the LLM later used `write_file` to overwrite the checkpoint, file-tool atomic write failed with `storage_permission_denied` because the target directory/file had been materialized by a different Unix identity.

## Reproduction

Confirmed from the GitHub issue evidence:

- Actor Home effective directory was correct.
- `command` ran `mkdir -p tasks/<task-id> ...`.
- The created task directory and checkpoint file were owned by `root:root`.
- `write_file` then failed against the same logical path with `storage_permission_denied`.

## Expected vs Actual

Expected:

- `command` and file tools must create files under the same Actor Home ownership boundary.
- A root-hosted Worker must not leave root-owned files in delegated workspaces that are owned by the runtime user.

Actual:

- `command` inherited the Worker process identity.
- If the Worker process was root, command-created files became root-owned and blocked later file-tool writes.

## Impact Scope

- LangGraph BizWorker delegated Actor Home workspaces on Linux/WSL.
- Tasks that use both `command` and file tools for sidecar/checkpoint materialization.
- Already-created root-owned files still require one-time operator cleanup.

## Test Strategy

Automation is required because the fix is isolated and easy to regress:

- Unit test root Worker behavior: subprocess receives workspace owner UID/GID, supplementary groups, owner environment, and stable umask.
- Unit test non-root Worker behavior: subprocess keeps current identity and does not perform owner lookup.
- Existing file-tool tests verify write semantics are unchanged.

## Code Inventory

- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/command_tool.py`
- `tools/langgraph-biz-worker/tests/test_command_tool.py`

## Fix Checklist

- [x] Detect root Worker process before running a command.
- [x] Resolve owner UID/GID from `execution_policy.workdir`.
- [x] Run command subprocess as the non-root workspace owner when applicable.
- [x] Pass supplementary groups for the workspace user.
- [x] Set `HOME`, `USER`, and `LOGNAME` for the subprocess user.
- [x] Set command subprocess umask to `022`.
- [x] Preserve current behavior when Worker is not root.
- [x] Add focused regression tests.

## Verification

```powershell
cd tools/langgraph-biz-worker
$env:PYTHONPATH='src'
.\.venv\Scripts\python.exe -m pytest tests/test_command_tool.py
.\.venv\Scripts\python.exe -m pytest tests/test_account_file_tools.py
.\.venv\Scripts\ruff.exe check src\langgraph_biz_worker\runtime\command_tool.py tests\test_command_tool.py
```

Observed result:

- `tests/test_command_tool.py`: 8 passed.
- `tests/test_account_file_tools.py`: 41 passed, 2 skipped.
- Ruff: all checks passed.

## Progress

- 2026-06-27: Created BUG-146 work item from GitHub issue #146.
- 2026-06-27: Implemented command subprocess workspace-owner execution for root-hosted Workers.
- 2026-06-27: Added regression tests for root and non-root Worker identity behavior.
- 2026-06-27: Local validation passed.

## Follow-Up

Existing Actor Home task directories or files that are already `root:root` need one-time ownership repair before retrying affected upstream tasks. This fix prevents newly executed command tool calls from creating additional root-owned workspace files.
