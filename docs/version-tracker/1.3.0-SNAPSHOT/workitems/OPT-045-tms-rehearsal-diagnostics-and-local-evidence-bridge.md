---
type: optimization
version: 1.3.0-SNAPSHOT
ticket: OPT-045
severity: medium
status: in_progress
owner: biz-worker-runtime + navigator-open-sdk + upstream-integration
source: foggy-world-sim v0.0.706 TMS real rehearsal M0
---

# OPT-045: TMS Rehearsal Diagnostics and Local Evidence Bridge

## Document Purpose

- doc_type: design + implementation-plan + progress-tracking
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 将 2026-06-14 TMS real rehearsal 中发现的 diagnostics、frame report 状态口径、本地 evidence 截图分析边界和上游续跑口径固化为可执行版本项。

## Source

- upstream_project: `foggy-world-sim`
- upstream_version: `v0.0.706`
- upstream_issue: `D:/foggy-projects/foggy-data-mcp/foggy-world-sim/docs/versions/v0.0.706/tms-real-rehearsal-m0/navi-issues/bizworker-tms-real-rehearsal-20260614.md`
- observed_date: 2026-06-14
- related_context_id: `bctx_20260614_86_86050a0a9c1f40bd9c36e34efaa06a4b`

## Background

TMS real rehearsal M0 已能完成真实运单创建和查询，但排查中暴露出四类后续治理点：

1. SIM 工作区安装的 `navigator-upstream` CLI 版本落后于当前源码，无法使用 `upstream diagnostics session-dir` 直接定位 BizWorker 本地 session、tool-call 和 report 证据。
2. `analyze_attachment` 当前只接受运行时附件上下文中的 attachment id，并要求附件具备 `url` / `href`；delegated workspace 的本地 evidence path 不能直接作为 `attachment_id`。
3. frame report digest 中 `status=COMPLETED` 与 `frame_status=RUNNING` 容易被误读；实际语义是本轮 turn 已完成，但 persistent root frame 仍继续存在。
4. 同一 `contextId` 下通过新 task 发送“继续”恢复上下文的能力已开发，不应重复规划为新能力，但需要在 rehearsal handoff 中写清使用条件。

## Design Decisions

### D1 - CLI session-dir is a distribution/update issue

Current source already contains:

- `upstream diagnostics session-dir`
- `--context-id`
- `--task-id`
- `--data-root` / `NAVI_BIZ_WORKER_DATA_ROOT`
- output fields including `sessionDirectory`、`logsDirectory`、`skillToolCallsDirectory`、`skillToolCallsFile`

The TMS rehearsal failure to invoke this command is caused by the SIM workspace installing an older CLI package. P0 is therefore package/update/verification work, not a new CLI contract.

### D2 - Local evidence files must be bridged before attachment analysis

`analyze_attachment.attachment_id` remains an attachment id lookup key, not a file path. It must not silently treat arbitrary local paths as model-visible files.

Target bridge design:

1. Add a dedicated local evidence registration step before vision analysis.
2. Input is a delegated workspace/evidence file path plus purpose.
3. Runtime resolves the path only under authorized execution-policy workspace roots.
4. Runtime validates file existence, image MIME type, and size limit.
5. Runtime creates a current-turn attachment record with a generated id and `url` / `href` usable by the current attachment analysis implementation.
6. `analyze_attachment` is then called with the generated attachment id.

The implemented bridge uses a bounded data URL for small image evidence files. The implementation does not expose raw absolute paths, credentials, signed URL query strings, or unrestricted local file reads to the model.

Proposed tool shape:

```json
{
  "name": "register_evidence_attachment",
  "arguments": {
    "path": "tms-order-clerk-real-browser-m0/evidence/.../screenshot.png",
    "purpose": "inspect TMS order creation error screenshot",
    "expected_mime_type": "image/png"
  }
}
```

Expected result:

```json
{
  "ok": true,
  "attachment_id": "evidence-att-...",
  "name": "screenshot.png",
  "mimeType": "image/png",
  "size": 123456,
  "provider": "local-evidence-bridge",
  "attachment_evidence": {
    "attachment_count": 1,
    "attachment_ids": ["evidence-att-..."],
    "attachment_ref_types": ["id", "name", "media_type", "url_digest"]
  }
}
```

### D3 - Report status aliases should separate turn and persistent frame lifecycle

Frame report digest / markdown should add explicit alias fields:

- `turn_status` or `report_status`: this task/turn report result.
- `persistent_frame_status`: lifecycle state of the persistent frame.

Backward compatibility:

- Keep existing `status` and `frame_status` for existing consumers.
- In human-readable markdown, state that `status=COMPLETED` and `frame_status=RUNNING` is normal for a completed turn on a persistent root frame.

### D4 - Same-context continuation is already implemented

The existing root skill design already supports this semantic:

```text
new user turn / new lgt_* task
  -> same contextId
  -> locate current conversation root frame
  -> append "继续" or the user's follow-up instruction
  -> restart LLM tool loop on preserved frame context
```

Operational clarification:

- Upstream should create a new task/ask with the same `contextId`; do not reuse the old `taskId`.
- The user prompt can be simply `继续`, but the implementation is not a keyword-only branch. The model receives recoverable frame/focus context and decides whether to continue, shelve, start unrelated work, or ask for clarification.
- If the interruption is inside a recoverable child skill, root prompt exposes that child focus and the model should call `resume_recoverable_child_skill`.
- This does not resume the old HTTP request, SSE connection, Python call stack, or old provider streaming response.
- It requires the old root/focus frame to still be recoverable and the new request to use the same scoped context identity.

## Scope

In scope:

- P0: update/verify SIM-side `navigator-upstream` CLI package and BizWorker data root configuration.
- P1: document and enforce the local evidence path vs attachment id boundary.
- P2: improve diagnostics/report wording and observable fields for rehearsal debugging.
- P3: design and implement a safe bridge from authorized local evidence files to temporary attachment ids for vision analysis.

Out of scope:

- Replacing `analyze_attachment` with arbitrary local file reading.
- Reopening OPT-032 direct attachment handoff decisions.
- Rebuilding same-context continuation; P4 is an existing capability and only needs handoff clarification unless validation finds a regression.
- Putting world-sim adjudication logic into Navigator core.

## P0-P3 Plan

### P0 - SIM CLI update and verification

- [x] Confirm current Navi source supports `upstream diagnostics session-dir`.
- [x] Confirm SIM workspace installed CLI is older and lacks `session-directory-diagnostics`.
- [x] Build a CLI package containing `session-directory-diagnostics`.
- [x] Install/update the package in the SIM workspace through the normal upstream CLI update path.
- [ ] Persist `NAVI_BIZ_WORKER_DATA_ROOT` in SIM profile if the team wants this as the default.
- [x] Verify with explicit `--data-root`:

```powershell
.\tools\navigator-upstream\navi.ps1 upstream diagnostics session-dir `
  --context-id bctx_20260614_86_86050a0a9c1f40bd9c36e34efaa06a4b `
  --task-id lgt_9b3d876a560e4226
```

Expected evidence:

- command help contains `diagnostics session-dir --context-id <contextId>`
- output contains `sessionDirectory=...`
- output contains `skillToolCallsFile=...`

Actual evidence:

- package: `tools/navigator-upstream-cli/dist/output/navigator-upstream-cli-1.0.17-windows.zip`
- sha256: `9498826452c4f9ccbed49e9af952798b44756b80ce6d538ebfd2df0e55d0d16d`
- installed SIM CLI version: `1.0.17`
- installed feature: `session-directory-diagnostics`
- verified output:
  - `exists=true`
  - `sessionDirectory=\\wsl.localhost\Ubuntu-24.04\home\navigator\.langgraph-biz-worker\data\runtime\sessions\by-date\2026\06\14\86\bctx_20260614_86_86050a0a9c1f40bd9c36e34efaa06a4b`
  - `skillToolCallsFile=\\wsl.localhost\Ubuntu-24.04\home\navigator\.langgraph-biz-worker\data\runtime\sessions\by-date\2026\06\14\86\bctx_20260614_86_86050a0a9c1f40bd9c36e34efaa06a4b\logs\skill-tool-calls\lgt_9b3d876a560e4226.jsonl`
  - `runtimeMessageEventsFile=\\wsl.localhost\Ubuntu-24.04\home\navigator\.langgraph-biz-worker\data\runtime\sessions\by-date\2026\06\14\86\bctx_20260614_86_86050a0a9c1f40bd9c36e34efaa06a4b\logs\runtime-message-events\lgt_9b3d876a560e4226.jsonl`

### P1 - Documentation boundary for local evidence and attachments

- [x] Record in the upstream issue that local evidence path is not a valid `analyze_attachment.attachment_id`.
- [x] Add this workitem's bridge design.
- [x] Update BizWorker tool schema so LLMs do not pass delegated evidence paths directly to `analyze_attachment`.
- [x] Add a regression test and schema assertion that `analyze_attachment` remains id-based and returns clear errors for local paths.

### P2 - Diagnostics and report clarity

- [x] Add explicit `turn_status` / `report_status` and `persistent_frame_status` aliases to frame report digest and markdown.
- [x] Keep `status` / `frame_status` compatible for existing consumers.
- [x] Add `runtimeMessageEventsDirectory` and task-scoped `runtimeMessageEventsFile` candidates to `diagnostics session-dir` output when a task id is provided.
- [ ] Make timeout classification visible in diagnostics/report summaries where source facts exist:
  - `LLM_REQUEST_TIMEOUT`
  - execution deadline
  - per-request timeout
  - provider quota/cooldown summary

### P3 - Local evidence attachment bridge

- [x] Implement `register_evidence_attachment` bridge in BizWorker runtime.
- [x] Restrict file resolution to authorized execution-policy workspace roots.
- [x] Validate image MIME/extension and file size before registration.
- [x] Generate a temporary attachment id and current-turn attachment record with `url` / `href`.
- [x] Redact path/URL evidence using existing attachment evidence helpers.
- [x] Add unit tests for:
  - valid screenshot registration
  - path traversal rejection
  - unsupported MIME rejection
  - oversized file rejection
  - subsequent `analyze_attachment` lookup by generated id

## Testing Plan

Targeted tests:

```powershell
cd tools/langgraph-biz-worker
$env:PYTHONPATH='src'
.\.venv\Scripts\python.exe -m pytest tests/test_evidence_attachment_bridge.py tests/test_attachment_analysis.py tests/test_llm_tool_schemas.py tests/test_frame_execution_report.py
```

Result on 2026-06-14: `40 passed in 0.83s`.

```powershell
mvn -pl navigator-open-sdk -Dtest=UpstreamCliTest test
```

Result on 2026-06-14: `89 tests, 0 failures, 0 errors`.

If P2 changes OpenAPI diagnostics fields, also run the focused diagnostics controller tests in `addons/claude-worker-agent`.

## Progress Tracking

Development progress:

- [x] Rehearsal facts analyzed and linked to source issue.
- [x] Existing same-context continuation capability verified against root skill design.
- [x] P0-P3 design and execution plan created.
- [x] P0 SIM CLI package/update completed.
- [x] P1 prompt/tool documentation update completed.
- [x] P2 report status alias implementation completed.
- [x] P2 diagnostics session-dir/runtime-message-events follow-up completed.
- [x] P3 evidence bridge implementation completed.

Validation progress:

- [x] Source inspection confirms `navigator-open-sdk` has `upstream diagnostics session-dir`.
- [x] Source inspection confirms `analyze_attachment` requires runtime attachment id plus `url` / `href`.
- [x] Source inspection confirms continuation design covers same-context new task + "继续" semantics.
- [x] Local package smoke for SIM CLI.
- [x] LangGraph BizWorker unit tests for evidence bridge.
- [x] SDK CLI unit tests for diagnostics output changes.

Experience progress:

- N/A for UI. This item affects CLI/runtime/debug experience only.

## Risks

1. Data URL support varies by model/provider. P3 implementation may need a local temporary object-serving path instead of direct data URL.
2. Authorized workspace root resolution must be conservative; a convenience bridge must not become arbitrary local file exfiltration.
3. Adding report aliases must not break existing consumers that parse `status` and `frame_status`.
4. Timeout classification depends on whether provider errors and deadline facts are already persisted at the report/diagnostics layer.

## References

- `docs/version-tracker/1.3.0-SNAPSHOT/13-biz-worker-root-skill-context-design.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/19-biz-worker-frame-execution-report-design.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/OPT-029-upstream-timeout-governance.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/OPT-032-attachment-preprocessing-governance-follow-up.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/REQ-030-biz-worker-on-demand-attachment-analysis-and-vision-model-config.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/REQ-041-world-sim-task-diagnostics-contract.md`
