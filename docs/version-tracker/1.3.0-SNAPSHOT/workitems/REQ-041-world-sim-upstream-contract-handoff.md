# REQ-041 World-Sim Upstream Contract Handoff

## 文档作用

- doc_type: integration-contract-handoff
- intended_for: world-sim | sim | upstream-integrator | reviewer
- purpose: 给 world-sim 对接方提供 Navigator task diagnostics / evidence / message event contract 的最小稳定说明，明确事实边界、调用入口、推断责任和真实 smoke 状态。

## 基本信息

- version: 1.3.0-SNAPSHOT
- status: READY_FOR_WORLD_SIM_SMOKE
- source_requirement: `docs/version-tracker/1.3.0-SNAPSHOT/workitems/REQ-041-world-sim-task-diagnostics-contract.md`
- source_issue: GitHub issue `foggy-projects/Foggy-Navigator#134`
- navigator_scope: fact-provider-only
- world_sim_scope: recovery-adjudication
- implementation_status: backend + SDK + CLI implemented
- unit_verification_status: passed
- navigator_openapi_mock_e2e_status: passed
- real_world_sim_smoke_status: pending-upstream
- generated_at: 2026-05-27

## Contract Boundary

Navigator 只提供事实，不替 world-sim 做恢复裁决。

Navigator 输出：

- task lifecycle facts: `status`, `terminal`, `terminalStatus`, timestamps, message count
- worker / provider facts: `workerBackend`, `providerType`, worker task refs, provider task refs
- progress facts: message `eventKind`, `progressType`, terminal marker, heartbeat / retry / progress events
- completion evidence: final answer summary, structured output, frame report refs, artifact refs
- recovery correlation: original task, correlation key, attempt number, idempotency key
- cancel / cleanup capability facts: supported or unsupported, mode, backend limitations

Navigator 不输出：

- `meaningfulProgress`
- `runningNoMeaningfulProgress`
- `ruminationSuspected`
- `retryDecision`
- `tickDecision`
- `acceptanceDecision`
- world-sim contract pass/fail verdict

world-sim 负责基于这些事实推断：

- 是否继续等待当前 task
- 是否暂停 tick
- 是否接受已有结果
- 是否重试或发起 continuation
- 是否调用 world-sim 自己的 judge agent

## Authentication And Ownership

所有接口使用 ClientApp runtime access token，权限边界与 upstream ask / messages 一致或更严格。

调用方必须满足：

- token 属于当前 ClientApp / tenant。
- route 中的 `agentId` 对当前调用方可用。
- `taskId` 必须属于该 tenant 和该 `agentId`。
- 返回内容默认脱敏，不暴露 Authorization、Bearer token、secret、provider raw request body 或私有 signed URL query。

## OpenAPI Endpoints

### Task Diagnostics

```text
GET /api/v1/open/agents/{agentId}/tasks/{taskId}/diagnostics
```

用途：获取一次 task 的当前事实快照。适合 world-sim 在等待、重试、恢复前读取。

核心字段：

| Field | Meaning | Notes |
| --- | --- | --- |
| `taskId` | Navigator task id | 输入 task id 的回显。 |
| `agentId` | Agent route id | 必须与路径归属一致。 |
| `contextId` | Session / context id | 用于 continuation 和消息关联。 |
| `status` | task core status | 常见值：`SUBMITTED`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`。 |
| `terminal` | 是否终态 | 事实字段，不表示 world-sim 可接受。 |
| `terminalStatus` | 终态归一值 | 例如 `COMPLETED`, `FAILED`, `CANCELLED`。非终态为空。 |
| `submittedAt` | task 创建时间 | 可用于 wall-clock 观察，但不应单独作为失败依据。 |
| `workerStartedAt` | worker 接手时间 | 如果 backend 没有持久化该事实，返回 `null`。 |
| `lastObservedAt` | 最近可见事实时间 | 取消息、alive、updated 等持久化事实的最近时间。 |
| `messagesCount` | task message 数量 | `0` 通常表示尚无可见输出。 |
| `workerTaskId` | worker 侧任务 id | 已脱敏。 |
| `providerTaskId` | provider 侧任务 id | 已脱敏。 |
| `lastAckedSeq` | 最近 ack 序号 | 如无该事实则为空。 |
| `modelConfigId` | 模型配置 id | 可辅助排查路由/模型问题。 |
| `modelConfigSource` | 模型配置来源 | 事实说明。 |
| `workerBackend` | worker backend | 例如 Codex / Claude / BizWorker 等。 |
| `providerType` | provider 类型 | 由任务或路由事实得出。 |
| `taskSource` | task 来源 | 事实说明。 |
| `workerSource` | worker 来源 | 事实说明。 |
| `backendSource` | backend 来源 | 事实说明。 |
| `safeWorkerRef` | 安全 worker 引用 | 不暴露敏感 host/process inventory。 |
| `failureStage` | 失败阶段 | 仅失败时有意义。 |
| `failureSummary` | 脱敏失败摘要 | world-sim 可用于错误分类。 |
| `cancelCapability` | cancel / cleanup 能力 | 第一阶段 runtime 调用方不直接获得强制 cancel。 |
| `correlation` | recovery correlation | 见下文。 |
| `createdAt`, `updatedAt` | task 持久化时间 | 只作为事实参考。 |

### Task Evidence

```text
GET /api/v1/open/agents/{agentId}/tasks/{taskId}/evidence
```

用途：获取 completion evidence 摘要。适合 world-sim 判断 task 是否已经产出可验收材料。

核心字段：

| Field | Meaning | Notes |
| --- | --- | --- |
| `taskId`, `agentId`, `contextId` | 基础关联字段 | 与 diagnostics 保持一致。 |
| `status`, `terminal`, `terminalStatus` | task 状态事实 | 不等同于 world-sim contract verdict。 |
| `finalAnswer.available` | 是否存在最终回答摘要 | 来自 task result 或最新可见 result/text message。 |
| `finalAnswer.summary` | 脱敏摘要 | CLI 会再次做输出侧脱敏和截断。 |
| `finalAnswer.messageId` | 来源 message id | 为空时表示来源不是 message。 |
| `finalAnswer.source` | 来源 | 例如 task result 或 message。 |
| `finalAnswer.createdAt` | 来源时间 | 可为空。 |
| `structuredOutput.available` | 是否存在结构化输出 | 来自 task state 或 message metadata。 |
| `structuredOutput.value` | 结构化输出值 | 已走安全清洗，不保证包含业务完整原文。 |
| `structuredOutput.source` | 来源 | task state 或 message metadata。 |
| `reportRefs[]` | frame / report 引用 | 只返回引用和摘要，不读取 report body。 |
| `artifactRefs[]` | artifact 引用 | 私有 signed URL query 会被剥离。 |

## Message Event Contract

world-sim 应优先消费 open task/session messages 中的归一化字段，而不是解析 raw content。

关键字段：

| Field | Meaning |
| --- | --- |
| `eventKind` | 事件类型归一值。 |
| `progressType` | progress 子类型；非 progress 事件通常为空。 |
| `terminal` | 当前 message 是否带有终态 marker。 |
| `terminalStatus` | message 暴露的终态状态。 |
| `status` | message 相关状态。 |
| `reportRefs[]` | message 附带 report 引用。 |
| `artifactRefs[]` | message 附带 artifact 引用。 |

当前稳定 `eventKind` 值：

| eventKind | Meaning |
| --- | --- |
| `user_message` | 上游用户输入。 |
| `text_delta` | 增量文本输出。 |
| `text_complete` | 完整文本或普通 assistant 输出。 |
| `tool_call_summary` | 工具调用摘要。 |
| `tool_result_summary` | 工具结果摘要。 |
| `structured_output` | 结构化输出事件。 |
| `progress` | 普通进度事件。 |
| `heartbeat` | 存活/心跳事件。 |
| `retrying` | 重试或 backoff 事件。 |
| `final_marker` | task 完成 marker 或 final answer marker。 |
| `error` | 错误事件。 |

`progressType` 规则：

- 若 metadata 显式提供 `progressType` / `progress_type`，优先使用归一化后的值。
- `eventKind=heartbeat` 时默认为 `heartbeat`。
- `eventKind=retrying` 时默认为 `retry`。
- `eventKind=progress` 时取 subtype / state / stage / phase；没有则为 `progress`。
- 非 progress 类事件通常为空。

## CLI Usage

CLI package: `navigator-upstream-cli-1.0.16-windows.zip`

Package SHA256:

```text
a5a639da836a1be83e984cfff5a338ae9239f542d73d8d3dd823f4588fa14ea2
```

Diagnostics:

```powershell
navigator-upstream-cli diagnostics --task-id <taskId> --agent-code <agentId> --upstream-user-id <id>
```

Evidence:

```powershell
navigator-upstream-cli evidence --task-id <taskId> --agent-code <agentId> --upstream-user-id <id>
```

Messages:

```powershell
navigator-upstream-cli messages --task-id <taskId> --agent-code <agentId> --poll --upstream-user-id <id>
```

CLI message output includes `eventKind`, `progressType`, `status`, `terminal`, `terminalStatus`, `messageReportRef` and `messageArtifactRef` lines where available.

## Recommended World-Sim Inference Inputs

以下不是 Navigator 输出的 verdict，只是 world-sim 可采用的输入组合。

| world-sim question | Suggested facts |
| --- | --- |
| 是否还没有被 worker 接手 | `status=SUBMITTED`, `workerStartedAt=null`, `providerTaskId=null`, `messagesCount=0` |
| 是否仍有可见活动 | `lastObservedAt`, `messagesCount`, recent messages, `eventKind=heartbeat/progress/retrying/text_delta` |
| 是否已经可接受 | `terminal`, `terminalStatus`, `finalAnswer.available`, `structuredOutput.available`, `reportRefs`, `artifactRefs`, `eventKind=final_marker` |
| 是否需要重试 | `status`, `terminalStatus`, `failureStage`, `failureSummary`, age since `lastObservedAt`, absence of acceptable evidence |
| 是否 continuation 同一恢复链 | `correlation.originalTaskId`, `correlation.recoveryCorrelationKey`, `correlation.attemptNumber`, `correlation.idempotencyKey` |
| 是否可取消 | `cancelCapability.cancelSupported`, `cancelCapability.cancelMode`, `cancelCapability.backendLimitations` |

推荐策略：

1. 先读 `diagnostics`，确认 task 是否存在、归属是否正确、是否有 worker/provider 事实。
2. 再读 `messages --poll`，用 `eventKind` / `progressType` 判断是否仍有可见活动。
3. 对终态或疑似可接受输出，读 `evidence`，检查 final answer / structured output / report / artifact。
4. world-sim 自己根据业务 contract 判断是否接受、继续等待、暂停 tick 或重试。
5. 对模糊场景，调用 world-sim 自己的 judge agent；Navigator 不内置该裁决。

## Correlation Contract

上游发起恢复或 continuation 时，建议在 task metadata / request metadata 中传入：

```json
{
  "originalTaskId": "first-task-id",
  "recoveryCorrelationKey": "world-id:actor-id:tick-id:goal-id",
  "attemptNumber": 2,
  "idempotencyKey": "stable-idempotency-key"
}
```

Navigator 会持久化并在 diagnostics 的 `correlation` 中返回这些安全字段。字段也接受 snake_case 兼容输入，例如 `original_task_id`, `recovery_correlation_key`, `attempt_number`, `idempotency_key`。

## Cancel And Cleanup Capability

第一阶段 runtime ClientApp 接口只暴露能力事实，不承诺直接 cancel / cleanup：

- 非终态 task: `cancelSupported=false`, `cancelMode=admin_only`, `cleanupSupported=false`
- 终态 task: `cancelSupported=false`, `cancelMode=none`, `cleanupSupported=false`
- `backendLimitations` 会说明 `runtime_client_app_cancel_not_exposed` 等限制

如果 world-sim 需要强制停止后台运行任务，应作为后续需求单独定义控制凭证、审计和 backend 支持边界。

## Verification Status

已完成：

| Area | Status | Evidence |
| --- | --- | --- |
| Backend OpenAPI contract | pass | `OpenApiControllerMessageMappingTest`, 28 tests |
| Session read model / correlation persistence | pass | `TaskDispatchFacadeTest`, `OpenApiSessionQueryServiceTest`, 64 tests |
| LangGraph task state preservation | pass | `LanggraphTaskServiceTest`, 24 tests |
| SDK / CLI commands | pass | `UpstreamCliTest`, 80 tests |
| Navigator OpenAPI mock E2E smoke | pass | `$env:BIZ_AGENT_E2E_OPENAPI_DIAGNOSTICS_SMOKE='true'; npm test -- tests/04-openapi-task-diagnostics-evidence-contract.test.ts`; evidence record: `docs/version-tracker/1.3.0-SNAPSHOT/test-records/req-041-openapi-diagnostics-evidence-smoke-20260527.md` |
| Affected module compile | pass | `mvn -pl session-module,addons/claude-worker-agent,navigator-open-sdk -am -DskipTests compile` |
| CLI local package metadata | pass | `BUILD_INFO.json` includes `task-diagnostics`, `task-evidence`, `message-event-contract` |

未完成：

| Area | Status | Reason |
| --- | --- | --- |
| External world-sim route smoke | pending-upstream | Navigator-owned OpenAPI/BizWorker/mock-LLM E2E smoke 已通过；world-sim 自身业务 route / adjudication 的现场 smoke 仍由上游联调执行。 |
| Remote CLI publication | not-run | 本地包已生成，但还未发布到远端分发位置。 |

因此当前结论是：Navigator 侧代码级、模块级和本地 E2E 真实链路验证均已通过；外部 world-sim route smoke 只用于验证 world-sim 自身路由和裁决逻辑，不再阻塞 Navigator 事实层契约交付。

## Navigator Mock E2E Regression

本仓已沉淀 opt-in E2E smoke，覆盖 runtime token、OpenAPI ask、BizWorker 执行、diagnostics、messages、evidence、frame report refs、correlation facts 和 secret non-leak。

```powershell
cd business-agent-module/integration-tests
npm run typecheck
$env:BIZ_AGENT_E2E_OPENAPI_DIAGNOSTICS_SMOKE='true'
Remove-Item Env:BIZ_AGENT_E2E_MOCK_BASE_URL -ErrorAction SilentlyContinue
npm test -- tests/04-openapi-task-diagnostics-evidence-contract.test.ts
```

当前通过记录：`docs/version-tracker/1.3.0-SNAPSHOT/test-records/req-041-openapi-diagnostics-evidence-smoke-20260527.md`。

## External World-Sim Smoke Checklist

world-sim 联调时建议记录以下证据：

1. 使用已发布 CLI 或 SDK 版本，确认 feature metadata 包含 `task-diagnostics`, `task-evidence`, `message-event-contract`。
2. 用 world-sim 的真实 route 提交一个 task，metadata 带 `originalTaskId`, `recoveryCorrelationKey`, `attemptNumber`, `idempotencyKey`。
3. task 刚提交后读取 diagnostics，确认 `status`, `contextId`, `messagesCount`, `correlation` 可读。
4. task 运行中执行 `messages --poll`，确认 message 输出含 `eventKind` / `progressType`。
5. task 结束后读取 evidence，确认 final answer / structured output / report refs / artifact refs 至少一种可被 world-sim 使用。
6. 用另一个 agent 或无权限 task 调用 diagnostics/evidence，确认被拒绝。
7. world-sim 记录自己的裁决结果，明确裁决来自 world-sim，不是 Navigator 输出。

Smoke 通过标准：

- world-sim 能用 runtime token 读取三类事实：diagnostics、messages、evidence。
- 所有返回内容没有暴露 token、secret、Authorization、Bearer credential 或私有 signed URL query。
- world-sim 能基于 facts 自己得出 wait / accept / retry / pause tick 的决定。
- 未发现必须由 Navigator 新增的事实字段缺口。

## Issue Reply Draft

可回复给 sim / world-sim：

```text
Navigator has implemented the facts-only task recovery contract for issue #134.

Available facts:
- task diagnostics: status, timestamps, worker/provider refs, message count, failure summary, cancel capability, recovery correlation
- task evidence: final answer summary, structured output, frame report refs, artifact refs
- message stream semantics: eventKind, progressType, terminal marker, report/artifact refs

Navigator intentionally does not return meaningfulProgress, ruminationSuspected, retryDecision, tickDecision, or acceptanceDecision. Those remain world-sim adjudication responsibilities.

Code-level, module-level, and Navigator-owned OpenAPI/BizWorker mock E2E smoke have passed. A separate world-sim route smoke can still be run by world-sim to validate its own route and adjudication logic, but Navigator does not need to embed those decisions.
```

## Related Documents

- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/REQ-041-world-sim-task-diagnostics-contract.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/acceptance/req-041-world-sim-task-diagnostics-acceptance.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/coverage/req-041-world-sim-task-diagnostics-coverage-audit.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/quality/req-041-world-sim-task-diagnostics-implementation-quality.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/test-records/req-041-openapi-diagnostics-evidence-smoke-20260527.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/BUG-issue-115-world-sim-route-smoke-blocked.md`
