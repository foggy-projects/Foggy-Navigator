# REQ-041 World-Sim Task Diagnostics Contract

## 文档作用

- doc_type: requirement + implementation-plan + progress-template
- intended_for: root-controller | execution-agent | reviewer | signoff-owner
- purpose: 固化 Navigator 接受 world-sim task recovery 诊断需求后的事实层契约、模块责任、代码触点、实施阶段与验收标准。

## 基本信息

- version: 1.3.0-SNAPSHOT
- type: requirement
- priority: P1
- status: SIGNED_OFF
- source: GitHub issue `foggy-projects/Foggy-Navigator#134`
- source_project: foggy-world-sim
- source_version: v0.0.120
- owner: upstream-integration + session-module + business-agent-module + navigator-open-sdk + upstream-cli
- delivery_mode: single-root-delivery

## 背景

world-sim 的 `WorldTaskRecovery` 需要判断一次外部 Agent task 是否应继续等待、重试、暂停 tick 或接受已有结果。仅依赖 Navigator task core status 不够，因为实际链路中可能出现以下情况：

1. task 仍为 `RUNNING`，但消息、final marker、structured output、frame report 或 artifact 已经足以让 world-sim 接受结果。
2. task 长时间停在 `SUBMITTED` 或 `RUNNING/messages=0`，需要区分未接单、路由问题、worker 容量问题、worker 接收前失败或后台仍在正常执行。
3. 长耗时代码任务可以合法运行数小时，单纯 wall-clock timeout 不能作为失败依据。
4. LLM 可能持续输出但没有形成 world-sim 认为有意义的业务进展。
5. 部分模糊场景可能需要 world-sim 自己的 judge agent 或本地规则裁判。

Navigator 已通过 `BUG-041` 暴露了部分诊断字段，并通过 `OPT-029` 建立了 timeout、detach、retry、progress、heartbeat 和 recoverable context 的治理基础。本需求把这些已有能力整理成稳定上游契约，供 world-sim 基于事实数据自行推断恢复状态。

## Scope Decision

Navigator 本次接受的是事实层能力，不接受把 world-sim 裁判逻辑内置到平台核心。

Navigator 负责提供：

- task 状态事实
- worker 接收与执行诊断
- message / progress / heartbeat / retry 事实
- terminal marker 与 failure summary
- final answer / structured output / frame report / artifact reference 等 completion evidence
- context 下 task correlation 与 recovery attempt 元数据
- cancel / cleanup capability 的显式能力说明

world-sim 负责推断：

- task 是否已经满足 world-sim contract
- tick 是否推进、暂停或重试
- 当前输出是否属于 meaningful progress
- 是否疑似 rumination
- 是否需要调用独立 judge agent

## 目标

1. 为上游提供一个安全、稳定、可轮询的 task diagnostics 视图。
2. 为上游提供一个安全、稳定的 completion evidence 视图。
3. 强化 task messages / session messages 中可用于恢复判断的事件语义，避免上游依赖 raw internal message。
4. 明确 same-context continuation 与 recovery correlation 规则。
5. 明确 cancel / cleanup 在不同 backend 下的能力矩阵和限制。
6. 在 SDK 与 upstream CLI 中暴露 diagnostics / evidence 摘要。
7. 所有新增输出默认脱敏，不暴露 token、secret、Authorization、Bearer credential、provider request body、raw private attachment URL。

## 非目标

1. 不在 Navigator 核心中实现 world-sim 的业务 adjudication。
2. 不由 Navigator 判断 `RUNNING_NO_MEANINGFUL_PROGRESS` 或 `RUNNING_RUMINATION_SUSPECTED` 的最终结论。
3. 不要求第一阶段实现独立 judge agent；judge 可以作为 world-sim 本地能力或后续 read-only BizWorker skill。
4. 不承诺所有 worker backend 都支持强制 cancel 或 cleanup。
5. 不把 admin worker process inventory 暴露给普通 runtime token 调用方。
6. 不要求第一阶段改造所有历史 task 数据。

## 外部契约草案

### Task Diagnostics

推荐新增 OpenAPI：

```text
GET /api/v1/open/agents/{agentId}/tasks/{taskId}/diagnostics
```

调用方必须满足与 ask / poll 同等或更严格的 ClientApp scoped access。第一阶段字段：

```json
{
  "taskId": "task-id",
  "contextId": "context-id",
  "status": "SUBMITTED | RUNNING | COMPLETED | FAILED | CANCELLED",
  "submittedAt": "2026-05-27T00:00:00Z",
  "workerStartedAt": "2026-05-27T00:00:05Z",
  "lastObservedAt": "2026-05-27T00:01:00Z",
  "messagesCount": 3,
  "lastAckedSeq": 5,
  "providerTaskId": "provider-task-id",
  "workerTaskId": "worker-task-id",
  "workerBackend": "CODEX | LANGGRAPH_BIZ | CLAUDE | GEMINI",
  "providerType": "CODEX | LANGGRAPH_BIZ | CLAUDE | GEMINI",
  "safeWorkerRef": "non-secret-worker-ref",
  "failureStage": "WORKER_SUBMIT | PROVIDER_API | RUNTIME | UNKNOWN",
  "failureSummary": "sanitized summary",
  "terminal": false,
  "terminalStatus": null,
  "cancelCapability": {
    "cancelSupported": true,
    "cancelMode": "best_effort",
    "cleanupSupported": false,
    "backendLimitations": []
  },
  "correlation": {
    "originalTaskId": null,
    "recoveryCorrelationKey": null,
    "attemptNumber": null,
    "idempotencyKey": null
  }
}
```

字段说明：

- `submittedAt` 第一阶段可映射 `session_tasks.created_at`，但 API 语义必须写清楚。
- `workerStartedAt` 表示 worker 可见接收时间；如当前 backend 无法精确提供，必须返回 `null`，不能伪造。
- `lastObservedAt` 由 latest message time、`lastAliveAt`、`updatedAt` 等事实计算，具体算法需在实现中单测锁定。
- `messagesCount` 第一阶段统计该 task 已持久化的 message 数量；message 内容仍由 messages/evidence 视图分别执行可见性过滤与脱敏。
- `safeWorkerRef` 只能用于排障关联，不能泄露 worker token、host secret 或 admin-only process detail。

### Task Evidence

推荐新增 OpenAPI：

```text
GET /api/v1/open/agents/{agentId}/tasks/{taskId}/evidence
```

第一阶段字段：

```json
{
  "taskId": "task-id",
  "contextId": "context-id",
  "status": "RUNNING",
  "finalAnswer": {
    "available": true,
    "summary": "sanitized final answer",
    "messageId": "message-id",
    "createdAt": "2026-05-27T00:01:00Z"
  },
  "structuredOutput": {
    "available": true,
    "value": {},
    "source": "task_state | message_metadata | frame_report"
  },
  "reportRefs": [
    {
      "type": "frame-report",
      "ref": "frame-report://worker-task-id/frame-id",
      "frameId": "frame-id",
      "summary": "sanitized report summary"
    }
  ],
  "artifactRefs": [
    {
      "path": "relative-or-safe-artifact-ref",
      "summary": "sanitized artifact summary",
      "hash": "sha256-or-null",
      "mtime": "2026-05-27T00:01:00Z"
    }
  ]
}
```

Frame report 内容继续通过现有 `GET /api/v1/open/frame-reports` 读取。`evidence` 只负责发现和索引，不默认返回大段报告正文。

### Message / Event Contract

现有 task messages / session messages 保留，但需要补齐以下稳定语义：

- cursor 必须可稳定补拉，推荐明确 `messageId + createdAt` 或补充单调 `messageSeq`。
- 每条消息应尽可能提供 `eventKind`，例如 `text_delta`、`text_complete`、`progress`、`heartbeat`、`retrying`、`tool_call_summary`、`tool_result_summary`、`structured_output`、`final_marker`、`error`。
- retry/backoff/progress/heartbeat 必须可由上游区分，不能只落成普通 text。
- tool-call/tool-result 默认返回脱敏摘要，不要求 world-sim 使用 raw internal message。
- terminal marker 必须能被补拉，且 task messages response 保持 `terminal` / `terminalStatus`。

### Continuation / Correlation

同一 `contextId` 可以发起新 task；每次 recovery attempt 都应获得新的 `taskId`。

第一阶段推荐允许上游在 ask metadata 中传入：

```json
{
  "originalTaskId": "task-id",
  "recoveryCorrelationKey": "world-sim-run/task-contract/key",
  "attemptNumber": 2,
  "idempotencyKey": "caller-stable-key"
}
```

Navigator 只保存并回传这些 correlation facts，不解释它们的业务含义。

## Desired State Class Support

| world-sim state class | Navigator support stance | Notes |
| --- | --- | --- |
| `TASK_NOT_PICKED_UP` | support by facts | 依赖 `submittedAt`、`workerStartedAt=null`、`providerTaskId=null`、`messagesCount=0`、failure facts。 |
| `RUNNING_WITH_COMPLETION_EVIDENCE` | support by facts | 通过 evidence/report/artifact/final marker 暴露事实；是否接受由 world-sim 判断。 |
| `RUNNING_NO_ACTIVITY` | support by facts | 通过 `lastObservedAt`、messages cursor、heartbeat/progress facts 支撑上游判断。 |
| `RUNNING_NO_MEANINGFUL_PROGRESS` | partial | Navi 暴露 progress/message/tool/report facts；meaningful 的定义属于 world-sim contract。 |
| `RUNNING_RUMINATION_SUSPECTED` | out of core | 可由 world-sim 或可选 judge agent 判断，Navi 核心不直接输出该结论。 |

## 模块责任

### Root / Workspace

- 维护本 workitem 的需求、实施阶段、验收标准和风险记录。
- 协调 OpenAPI、session、business-agent、SDK、CLI 的契约一致性。
- 完成实现后触发 implementation quality gate、coverage audit 和 acceptance signoff。

### `addons/claude-worker-agent`

- 作为 OpenAPI 聚合层，新增或扩展 diagnostics / evidence endpoint。
- 复用现有 ClientApp runtime/control credential 校验和 route/task ownership 校验。
- 统一输出脱敏 diagnostics DTO。
- 补充 endpoint 单元测试与 secret masking 回归。

### `session-module`

- 提供 task diagnostics 查询所需 read model 支撑。
- 支持 messages count、latest message time、task messages cursor 语义稳定化。
- 如需要新增 read-only query service，应保持与现有 session/task repository 依赖方向一致。

### `business-agent-module`

- 保持 ClientApp scoped access、upstream user grant 和 frame report access 约束。
- 对 frame report / artifact / structured output evidence 的发现提供 read-only 支撑。
- 避免将 raw internal tool payload 默认暴露给上游。

### `navigator-common`

- 如现有 `session_tasks.task_state_json` 不足以承载 correlation facts，可在不破坏兼容性的前提下扩展实体字段或统一 JSON 投影。
- 不在第一阶段强行新增 world-sim 专用枚举。

### `navigator-open-sdk`

- 增加 diagnostics / evidence API client 与 DTO。
- CLI 复用 SDK 输出 diagnostics / evidence summary。
- 单测覆盖字段解析、null/unsupported capability、secret masking。

### `tools/navigator-upstream-cli`

- 如 CLI 分发包需要更新，补齐 diagnostics / evidence 命令或现有 `messages --poll` 摘要输出。
- 输出只展示安全字段，不打印 raw prompt、token、Authorization、Bearer credential、private attachment URL。

### Worker Backends

- Codex / Claude / Gemini / LangGraph BizWorker 不需要同时实现相同深度能力。
- 每个 backend 只需尽量提供事实字段；缺失能力通过 `null`、`unsupported` 或 `backendLimitations` 明确表达。

## Code Inventory

```yaml
code_inventory:
  - repo: Foggy-Navigator
    path: docs/version-tracker/1.3.0-SNAPSHOT/workitems/REQ-041-world-sim-task-diagnostics-contract.md
    role: root execution workitem
    expected_change: create
    notes: 本文档为总控执行文档，后续 progress / acceptance 以此为基线。

  - repo: Foggy-Navigator
    path: addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi
    role: OpenAPI aggregation layer
    expected_change: update
    notes: 新增或扩展 task diagnostics / evidence endpoint；沿用 ClientApp scoped access 与脱敏策略。

  - repo: Foggy-Navigator
    path: addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/model/dto
    role: OpenAPI DTOs
    expected_change: update
    notes: 增加 diagnostics / evidence response DTO；字段命名必须与 SDK 一致。

  - repo: Foggy-Navigator
    path: session-module/src/main/java/com/foggy/navigator/session/service
    role: session/task read model
    expected_change: update
    notes: 查询 task facts、message count、latest observed time、task correlation；不要放业务裁判逻辑。

  - repo: Foggy-Navigator
    path: navigator-common/src/main/java/com/foggy/navigator/common/entity
    role: shared task/message persistence projection
    expected_change: update
    notes: 只有在现有 taskStateJson / metadata 无法稳定承载 correlation 或 timing facts 时才扩展。

  - repo: Foggy-Navigator
    path: business-agent-module/src/main/java/com/foggy/navigator/business/agent/service
    role: frame report / evidence access support
    expected_change: update
    notes: 复用 BusinessAgentFrameReportService access checks；补 evidence discovery 时保持 read-only。

  - repo: Foggy-Navigator
    path: business-agent-module/src/main/java/com/foggy/navigator/business/agent/support
    role: message visibility and sanitized summaries
    expected_change: update
    notes: 必要时提供 diagnostics 视图专用脱敏摘要，避免 world-sim 依赖 raw internal tool message。

  - repo: Foggy-Navigator
    path: navigator-open-sdk/src/main/java/com/foggy/navigator/sdk
    role: SDK API and model layer
    expected_change: update
    notes: 增加 diagnostics / evidence client method、DTO、null/unsupported capability handling。

  - repo: Foggy-Navigator
    path: navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/cli
    role: upstream CLI command implementation
    expected_change: update
    notes: 增加或扩展 diagnostics 输出；所有 secret-like values 必须 masking。

  - repo: Foggy-Navigator
    path: tools/navigator-upstream-cli
    role: packaged upstream CLI distribution
    expected_change: update
    notes: SDK 版本更新后同步分发包；是否发布由 release owner 决定。

  - repo: Foggy-Navigator
    path: tools/langgraph-biz-worker
    role: frame report producer and artifact facts
    expected_change: read-only-analysis
    notes: 第一阶段优先复用已有 report_ref / artifact facts；仅当 Java evidence 无法发现必要引用时再进入实现。

  - repo: Foggy-Navigator
    path: packages/navigator-frontend
    role: Navigator management UI
    expected_change: do-not-touch
    notes: 本需求第一阶段无 UI 交互目标；experience: N/A。
```

模块归属验证：

- 当前计划不新增 Java module，不触发新的 Maven module dependency direction。
- OpenAPI endpoint 归属 `addons/claude-worker-agent`，符合现有 `/api/v1/open` 聚合边界。
- Read model 归属 `session-module`，只提供 task/message facts，不注入 worker backend implementation，避免循环依赖。
- Frame report access 继续归属 `business-agent-module`，不迁移到 `session-module`。
- `launcher` 作为部署壳不承载 Controller / Service / DTO。

## Implementation Plan

### Stage A - Contract Baseline

- [ ] 将 GitHub issue #134 的 Navi feedback 回写为“facts by Navigator, adjudication by world-sim”。
- [x] 明确 diagnostics / evidence endpoint 是否新增路径，或是否扩展现有 task detail。
- [x] 锁定 diagnostics DTO 字段、null 语义、unsupported capability 语义。
- [x] 锁定 evidence DTO 字段、reportRefs / artifactRefs 的安全表达方式。
- [x] 明确 runtime token、control credential、upstream user grant 在 diagnostics / evidence 中的 access model。

### Stage B - Backend Read Model

- [x] OpenAPI 增加 diagnostics endpoint。
- [x] OpenAPI 增加 evidence endpoint。
- [x] session read model 支持 messages count、latest observed time、task facts 聚合。
- [x] taskStateJson 或 metadata 支持 correlation facts 的保存与回传。
- [x] message/event DTO 增加或规范 `eventKind`、`progressType`、terminal marker、report/artifact refs。
- [x] cancel capability matrix 返回 `cancelSupported`、`cancelMode`、`cleanupSupported`、`backendLimitations`。
- [x] 所有新增 diagnostic text 复用或增强 secret masking。

### Stage C - SDK / CLI

- [x] `navigator-open-sdk` 增加 diagnostics / evidence API。
- [x] SDK model 支持 capability matrix、correlation、evidence refs。
- [x] upstream CLI 增加 diagnostics / evidence 命令或扩展现有 task/messages 输出。
- [x] CLI 输出保持简洁摘要，并 masking secret-like values。
- [x] 更新 CLI packaged distribution 的版本与 release metadata。

### Stage D - Verification

- [x] 单测覆盖 `SUBMITTED` 未接单诊断。
- [x] 单测覆盖 `RUNNING/messages>0` 的 lastObservedAt / messagesCount。
- [x] 单测覆盖存在 final/report/artifact evidence。
- [x] 单测覆盖 terminal `FAILED/messages=0` 的 failureStage/failureSummary/synthetic error 兼容。
- [x] 单测覆盖 context 下 recovery correlation facts 的保存与回传。
- [x] 单测覆盖 unsupported cancel capability。
- [x] 单测覆盖 runtime token 的 task tenant / agent 归属越权保护。
- [x] 单测覆盖 secret masking，不泄露 token、secret、Authorization、Bearer credential、provider request body、private attachment URL。
- [x] SDK / CLI 单测覆盖 diagnostics / evidence 输出。
- [x] E2E 环境补充 opt-in smoke，覆盖 runtime token -> OpenAPI ask -> BizWorker -> diagnostics/messages/evidence 真实链路。

## Acceptance Criteria

1. 上游可通过 taskId 获取 safe diagnostics snapshot，至少包含 status、contextId、submittedAt、lastObservedAt、messagesCount、providerTaskId、workerTaskId、lastAckedSeq、failureStage、failureSummary。
2. 未被 worker 接收的 task 能通过 diagnostics 与已接收但尚无消息的 task 区分；无法区分时必须返回 `unknown` 或 `null`，不能伪造。
3. 上游可通过 taskId 获取 completion evidence refs，包括 final answer、structured output、frame report refs、artifact refs 中当前 backend 能提供的部分。
4. task messages / session messages 能让上游区分 no activity、heartbeat/progress、retry/backoff、tool summary、terminal marker 和 error。
5. 同一 context 下的 recovery attempt 能被关联，且 correlation facts 由上游提供、Navigator 保存并回传。
6. cancel / cleanup 能力以 capability matrix 呈现；unsupported backend 不被包装成 supported。
7. 所有 diagnostics / evidence API 都执行 ClientApp scoped access 和任务归属校验。
8. 所有输出通过 secret masking；测试覆盖 token、secret、Authorization、Bearer credential、provider request body、private attachment URL。
9. SDK 与 upstream CLI 可消费并展示 diagnostics / evidence summary。
10. 本需求验收不要求 Navigator 输出 world-sim adjudication verdict。

## Testing Plan

Java targeted tests:

```text
mvn -pl "addons/claude-worker-agent,session-module,business-agent-module,navigator-open-sdk" -am "-Dtest=*Diagnostics*,*Evidence*,UpstreamCliTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Recommended focused coverage:

- OpenAPI controller tests for diagnostics / evidence access and masking.
- Session read model tests for message count, cursor, latest observed time, multi-task context correlation.
- Business agent service tests for frame report / evidence access boundaries.
- SDK tests for DTO parsing and null/unsupported semantics.
- CLI tests for command output and secret masking.
- Opt-in Navigator E2E smoke for runtime token, generated context, BizWorker execution, diagnostics, messages, evidence and secret non-leak:

```text
cd business-agent-module/integration-tests
$env:BIZ_AGENT_E2E_OPENAPI_DIAGNOSTICS_SMOKE='true'
npm test -- tests/04-openapi-task-diagnostics-evidence-contract.test.ts
```

Experience validation:

- experience: N/A
- reason: 第一阶段为后端 OpenAPI、SDK、CLI 事实层契约，不新增或修改前端页面、路由、表单、列表、弹窗或按钮交互。

## Progress Template

### Development Progress

- [x] Stage A contract baseline completed.
- [x] Stage B backend read model completed.
- [x] Stage C SDK / CLI completed.
- [x] Stage D verification completed. Session read model, OpenAPI controller, SDK / CLI tests, local CLI package metadata check, and Navigator OpenAPI E2E smoke pass.

### Implementation Self-Check

- [x] Scope remains facts-only; no world-sim adjudication logic was added to Navigator core.
- [x] Diagnostics / evidence field names match SDK and CLI.
- [x] Access checks match ask / poll or are stricter.
- [x] Secret masking was applied to all new text fields and refs.
- [x] Null / unsupported capability semantics are documented and tested.
- [x] No unrelated UI or worker backend refactor was introduced.

### Testing Progress

| Test area | Command / evidence | Status | Notes |
| --- | --- | --- | --- |
| OpenAPI diagnostics / evidence / message event contract | `mvn -pl addons/claude-worker-agent -am -Dtest=OpenApiControllerMessageMappingTest "-Dsurefire.failIfNoSpecifiedTests=false" test` | pass | 28 tests; includes SUBMITTED not-picked-up facts, diagnostics/evidence DTO assembly, message `eventKind` / `progressType` / refs, ownership rejection, masking and unsupported cancel capability. |
| Session read model and correlation persistence | `mvn -pl session-module -am "-Dtest=TaskDispatchFacadeTest,OpenApiSessionQueryServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` | pass | 64 tests; includes task message count, latest message, latest-N ascending order and recovery correlation metadata persistence. |
| Business frame report / evidence access | Existing frame report endpoint unchanged | not-run | Evidence endpoint returns refs only and does not read frame report bodies; no business-agent service change in this slice. |
| LangGraph session task state preservation | `mvn -pl addons/langgraph-biz-worker -am -Dtest=LanggraphTaskServiceTest "-Dsurefire.failIfNoSpecifiedTests=false" test` | pass | 24 tests; includes regression that completion sync preserves existing taskState diagnostics/correlation metadata. |
| SDK / CLI | `mvn -pl navigator-open-sdk -Dtest=UpstreamCliTest test` | pass | 80 tests; includes diagnostics / evidence command coverage and message event/ref output. |
| Secret masking regression | `mvn -pl addons/claude-worker-agent -am -Dtest=OpenApiControllerMessageMappingTest "-Dsurefire.failIfNoSpecifiedTests=false" test`; `mvn -pl navigator-open-sdk -Dtest=UpstreamCliTest test` | pass | Backend evidence test and CLI diagnostics/evidence tests assert secret masking. |
| Integration test typecheck | `npm run typecheck` in `business-agent-module/integration-tests` | pass | TypeScript client/types for OpenAPI runtime diagnostics/evidence compile. |
| Navigator OpenAPI diagnostics/evidence smoke | `$env:BIZ_AGENT_E2E_OPENAPI_DIAGNOSTICS_SMOKE='true'; npm test -- tests/04-openapi-task-diagnostics-evidence-contract.test.ts` | pass | 1 test, 87.92s; creates ClientApp/runtime token, submits OpenAPI ask without caller contextId, reaches BizWorker `COMPLETED`, validates diagnostics/messages/evidence, generated `bctx_...`, `frame_report` ref, correlation facts and no credential leakage. Evidence: `docs/version-tracker/1.3.0-SNAPSHOT/test-records/req-041-openapi-diagnostics-evidence-smoke-20260527.md`. |
| Final compile | `mvn -pl session-module,addons/claude-worker-agent,navigator-open-sdk -am -DskipTests compile` | pass | Reactor compile passed for affected modules and dependencies. |
| Packaged upstream CLI metadata | `powershell -ExecutionPolicy Bypass -File tools\navigator-upstream-cli\dist\package.ps1` | pass | Built `navigator-upstream-cli-1.0.16-windows.zip`; SHA256 `a5a639da836a1be83e984cfff5a338ae9239f542d73d8d3dd823f4588fa14ea2`; `BUILD_INFO.json` includes `task-evidence` and `message-event-contract`. |

### Acceptance Mapping

| Acceptance criterion | Evidence | Status |
| --- | --- | --- |
| Safe diagnostics snapshot | `GET /tasks/{taskId}/diagnostics`; controller test pass | done |
| Not-picked-up facts | OpenAPI diagnostics test for `SUBMITTED`, `workerStartedAt=null`, `providerTaskId=null`, `messagesCount=0` | done |
| Completion evidence refs | `GET /tasks/{taskId}/evidence`; controller test pass; E2E smoke validates `frame_report` refs | done |
| Message/event distinction | Open session/task messages expose `eventKind`, `progressType`, terminal marker, report refs and artifact refs | done |
| Context recovery correlation | `TaskDispatchFacadeTest` persists upstream correlation metadata; LangGraph sync preserves taskState metadata; E2E smoke validates diagnostics correlation fields | done |
| Cancel capability matrix | diagnostics returns unsupported/admin-only matrix; controller test pass | done |
| Scoped access checks | diagnostics/evidence use runtime token route + tenant/agent task ownership check; controller ownership rejection test pass | done |
| Secret masking | controller evidence test + CLI tests | done |
| SDK / CLI consumption | `mvn -pl navigator-open-sdk -Dtest=UpstreamCliTest test` | done |
| No Navi adjudication verdict | code review | done |

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: codex
- signed_off_at: 2026-05-27
- acceptance_record: `docs/version-tracker/1.3.0-SNAPSHOT/acceptance/req-041-world-sim-task-diagnostics-acceptance.md`
- quality_record: `docs/version-tracker/1.3.0-SNAPSHOT/quality/req-041-world-sim-task-diagnostics-implementation-quality.md`
- coverage_record: `docs/version-tracker/1.3.0-SNAPSHOT/coverage/req-041-world-sim-task-diagnostics-coverage-audit.md`
- handoff_contract: `docs/version-tracker/1.3.0-SNAPSHOT/workitems/REQ-041-world-sim-upstream-contract-handoff.md`
- blocking_items: none
- follow_up_required: yes
- follow_up_items: remote CLI publication; optional external world-sim route smoke

## Risks And Open Questions

1. `workerStartedAt` may require backend-specific instrumentation. If unavailable, return `null` and document limitations.
2. Current message cursor semantics may be insufficient if multiple messages share the same timestamp. Implementation should either harden cursor semantics or document exact ordering guarantees.
3. `lastObservedAt` must be based on persisted facts, not volatile process state alone.
4. Artifact refs need a safe representation. Raw absolute paths and private signed URLs should not be returned by default.
5. Runtime token vs control credential access should be explicitly tested for task ownership and upstream user boundaries.
6. `lastMeaningfulProgressAt` is intentionally not first-stage Navigator output. If later requested, it should be introduced as an optional classifier output with clear confidence and ownership.

## Related Work

- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/BUG-041-upstream-task-diagnostics-gap.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/REQ-041-world-sim-upstream-contract-handoff.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/OPT-029-upstream-timeout-governance.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/19-biz-worker-frame-execution-report-design.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/BUG-033-biz-worker-continue-context-injection-gap.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/BUG-042-biz-agent-skill-handoff-smoke.md`

## Post-Implementation Quality Flow

After coding is complete:

1. [x] Update this workitem's Development Progress, Testing Progress, and Acceptance Mapping.
2. [x] Run a lightweight implementation self-check and record the result in this document.
3. [x] Run `foggy-implementation-quality-gate` because this changes shared OpenAPI / SDK contracts.
4. [x] Run `foggy-test-coverage-audit` because the change affects external recovery behavior and security boundaries.
5. [x] Run `foggy-acceptance-signoff` after tests and evidence are complete.
