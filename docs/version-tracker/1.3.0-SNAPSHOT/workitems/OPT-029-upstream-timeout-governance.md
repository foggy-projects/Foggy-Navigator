---
type: optimization
version: 1.3.0-SNAPSHOT
ticket: OPT-029
severity: major
status: signed_off
owner: upstream-integration + session-module + biz-worker-runtime + widget
---

# OPT-029: Upstream Interruption Recovery and Timeout Governance

## 文档作用

- doc_type: optimization
- intended_for: execution-agent / reviewer / signoff-owner
- purpose: 承接 TMS 业务助手联调暴露的任务中断恢复、timeout、deadline、cancel 和状态收敛治理，作为后续实现与验收的主跟踪文档。

## Background

2026-05-18 TMS 业务助手联调暴露出跨层 timeout 语义不一致问题。前端在 300 秒后显示 `等待响应超时（300秒）`，但 Java 到 Python Worker 的连接仍在，Python Worker 仍有 active task，LLM provider 连接仍保持 established。

这说明当前 `300 秒` 是 widget 等待预算，而不是端到端取消、后端 deadline 或 Worker/LLM request timeout。

本项要解决的本质问题不是单点 timeout 参数，而是：当一次任务被中断时，系统如何保留 frame 上下文，由 LLM 在下一轮用户输入中自主判断继续工作、调整计划或终止该 frame，并让用户看到准确状态。

## Problem Statement

当前链路至少包含：

```text
TMS Frontend / NavigatorChat Widget
  -> TMS BFF / SDK / OpenAPI ask
  -> Navigator Java session task / LangGraph relay
  -> Python LangGraph Biz Worker
  -> root skill / child skill / business function
  -> LLM provider
```

存在的问题：

1. 客户端网络断开、浏览器关闭、上游 BFF 断连、UI 等待超时都可能只是“用户不再连接”，不等价于用户取消，也不等价于服务端任务失败。
2. 服务端任务可能在 LLM request timeout、retry 超阈值、Worker step timeout、业务函数 timeout 时中断，但当前缺少统一的可恢复状态和用户可见提示。
3. 前端 `config.timeout ?? 300_000` 到期后停止轮询并显示超时，但未明确这是 client detach、用户取消，还是服务端任务失败。
4. Java session task、LangGraph task、Python Worker active task 与 frame/report 状态没有统一 deadline、interruption reason 和恢复语义。
5. retry/backoff 期间如果长时间没有消息或事件，客户端会误判为无响应并断开；服务端需要在 retry 前后发出可持久化的进度/心跳消息。
6. task timeout、client disconnect、用户取消、LLM provider timeout、业务函数 timeout 混在一起，无法稳定映射到用户可读状态。
7. 缺少每一跳的 deadline/remaining budget、interruption reason、retry attempt 和 last activity 观测字段，排障只能靠进程连接和 frame 数据反推。

## Core Recovery Scenarios

OPT-029 将恢复问题收敛为两类大场景：

1. 客户端脱离，但服务端任务仍在运行：这是 attach / reattach 问题，不应被误判为任务中断。
2. 任务或 frame 已中断：不论来源是 LLM retry 耗尽、step timeout、用户取消、审批拒绝或 task deadline，persistent frame 上下文都不应被直接丢弃；下一轮用户消息应进入同一个 frame 上下文，由 LLM 决定继续、修正、搁置或终止。

### Scenario 1 - Client Detached, Server Still Running

用户自己的网络断了、页面关闭、上游请求超时或 widget 等待预算到期，但服务器中的任务仍在正常运行。

期望语义：

1. 默认不取消服务端任务，除非用户明确点击取消或上游显式调用 cancel。
2. 任务继续产生 message、frame event、execution report，并持久化 cursor / taskId / contextId。
3. 客户端恢复连接后，可以通过 taskId 或 contextId 重新拉取增量消息，并看到任务当前状态或最终结果。
4. UI 文案应表达“连接已中断/后台仍在处理/可继续查看”，不能误报为“任务失败”或“已取消”。
5. 服务端仍必须受 task deadline 和资源预算约束，避免客户端断开后无限运行。

### Scenario 2 - Task / Frame Interrupted, Context Recoverable

服务器中的任务出现 LLM timeout、retry 超阈值、Worker step timeout、业务函数 timeout、provider 熔断、用户主动取消或审批拒绝。

这里统一视为“当前执行被中断”。中断可以停止当前业务执行或副作用，但不应让 persistent frame 退出或丢失上下文。下一轮用户消息不论内容是什么，都应拥有该 frame 的上下文；由 LLM 基于用户输入和中断记录判断继续原工作、调整方案、重新尝试、搁置中断，或终止该 frame。

审批通过后的业务执行恢复不纳入本场景；本场景只关注用户拒绝审批后又继续发消息时，原 frame 上下文仍然可用。

期望语义：

1. 每次 retry/backoff 前后必须发出可持久化进度消息或事件，至少包含 reason、attempt、maxAttempts、nextRetryAfterMs、remainingMs、taskId、frameId。
2. 客户端收到 retry/progress/heartbeat 后，应视为任务仍有活动，避免把“服务端正在恢复”误判为客户端等待超时。
3. retry 仍需消耗 task deadline 和 LLM execution deadline，不能因为持续发消息而无限延长任务。
4. 当前执行中断后，persistent root / 多轮会话应进入可恢复中断态，保留上下文、frame/report 和下一轮继续所需状态。
5. 下一轮用户输入应注入 recoverable interruption context，由 LLM 产出 `CONTINUE_PREVIOUS`、`ABANDON_PREVIOUS`、`START_UNRELATED_NEW_TASK` 或 `ASK_CLARIFICATION` 等意图决策。
6. 确认终止的 frame 才进入明确终态，并输出用户可读失败原因、可重试建议和执行报告。

## 2026-05-18 Baseline Review

### BUG Handoff

- `BUG-027` 核心修复已复核：Biz Worker LLM 调用现在具备 request timeout、execution deadline、有限 retry、provider retry 关闭、熔断和并发保护。剩余问题不是“没有 LLM timeout”，而是跨 Java session、Worker active task、frame/report、UI 等待预算的统一 deadline/cancel 治理。
- `BUG-028` 核心修复已复核：附件已能从上游请求透传到 `tms-ticket-agent` 子技能可见上下文。该问题不再作为 OPT-029 的主线范围；仅保留附件 URL/API key 脱敏观测约束，避免 timeout 日志和事件泄露敏感附件信息。

### Confirmed Current State

- Widget：`packages/navigator-chat-widget/src/composables/useNavigatorChat.ts` 使用 `config.timeout ?? 300_000` 作为轮询等待预算；超时分支停止 polling、设置 `等待响应超时`，但没有调用 `cancelTask`。
- Widget API：`packages/navigator-chat-widget/src/api/navigatorApi.ts` 已有 `POST /tasks/{taskId}/cancel` 封装；当前 UI timeout 分支未使用该能力。
- Java Worker Client：`LanggraphWorkerClient` 当前设置 connect timeout `10s`、SSE response timeout `30min`；未携带 request-level `taskDeadlineAt`、remaining budget 或 timeout reason 到 Python Worker。
- Java task cancel：`LanggraphTaskService.cancelTask` 只覆盖用户主动取消语义，落库 `ABORTED` 并异步向 Worker 记录 `user_cancelled` interruption；尚未区分 `UI_WAIT_TIMEOUT`、`TASK_DEADLINE_EXCEEDED`、`CLIENT_DETACHED`。
- Biz Worker LLM：默认 `llm_request_timeout_seconds=120s`、`llm_execution_deadline_seconds=240s`、`llm_max_retries=1`、`llm_provider_max_retries=0`、`llm_circuit_failure_threshold=3`、`llm_circuit_open_seconds=60s`、`llm_max_concurrent_requests=5`。
- Biz Worker LLM 约束：当前 guard 可以让调用方在 timeout 后返回明确错误，但无法强杀底层已进入 provider SDK/HTTP 的同步线程；并发槽会等底层 future 真正结束后释放。OPT-029 需要把这个资源风险纳入 soak test、provider HTTP timeout 和进程级熔断策略。

### Verification Snapshot

- Python targeted: `tests/test_llm_call_guard.py`、LLM timeout/retry/recoverable interruption、attachment context 相关用例，`9 passed`。
- Worker scripted E2E: TMS ticket child attachment handoff 与“带附件但默认不分析图片”，`2 passed`。
- Java targeted: `LanggraphWorkerClientTest`、`LanggraphBusinessAgentWorkerTaskLauncherTest`，Maven `BUILD SUCCESS`，`6 tests passed`。
- Not yet covered: 浏览器真实网络断开后的端到端重连可再补充实网演练；widget timeout/cancel、Java session/relay timeout taxonomy、真实 TMS wait-timeout 收敛和 provider hang soak 均已有目标测试覆盖。

### Scope Lock

- OPT-029 只治理 interruption recovery、timeout、deadline、cancel、retry budget、状态映射、观测字段和资源收敛。
- `tms-ticket-agent` 与 `tms-attachment-agent` 的业务边界、附件是否需要视觉分析，除非影响 timeout/cancel 语义，否则不进入 OPT-029 主实现范围。

## Target Outcome

建立端到端 interruption recovery + timeout 治理合同，使每一层都能回答：

- 这次请求的用户可等待多久？
- 客户端断开后，后端是否继续执行，用户如何重新连接查看？
- 服务端 retry 或中断时，如何通知客户端任务仍在恢复？
- 任务中断后，frame 上下文如何保留，并由 LLM 在下一轮输入中决定继续还是终止？
- LLM 单次请求最多等待多久？
- retry 会消耗多少剩余预算？
- 超时后 task/frame/report/message 如何收敛？
- 哪些 timeout 是用户可重试，哪些是系统应降级或熔断？

## Proposed Contract

### Glossary

| Term | Definition |
| --- | --- |
| `upstream` | 从 Navigator 视角看，指接入 Navigator 的外部业务系统、SDK、BFF 或 Widget 调用方；不包含 LLM provider、Worker 或业务函数本身。 |
| `client detach` | 客户端连接、页面、HTTP 请求、SSE 或 polling 等等待关系断开；不表达用户取消意图，也不表达服务端任务失败。 |
| `task interruption` | 当前 task 执行链停止或无法继续推进，例如 retry 耗尽、deadline 耗尽、用户取消、审批拒绝、step timeout。 |
| `frame interruption` | Worker 内部 frame 被标记为 `continuation_state=INTERRUPTED` 且 `recoverable=true`，用于下一轮 Root LLM 恢复判断。 |
| `reattach` | 客户端重新连接到仍在运行或已完成的任务消息流，补拉历史消息和最终状态。 |
| `recoverable continuation` | 下一轮用户消息进入 Worker persistent root，由 LLM 根据 interruption context 自主决定继续、调整、搁置、澄清或终止。 |

### Frame Recovery Model

1. `CLIENT_DETACHED` / `UI_WAIT_TIMEOUT` 只表示客户端等待或连接关系变化；如果服务端任务仍在运行，不进入 frame interruption。
2. 除客户端脱离外，`USER_CANCELLED`、`APPROVAL_REJECTED`、`SERVER_STEP_INTERRUPTED`、`TASK_DEADLINE_EXCEEDED` 等都属于“任务或 frame 中断”候选；只要存在 persistent frame，就应优先保留上下文并记录 recoverable interruption。
3. 中断只表示当前执行链停止，不等价于 frame 上下文销毁。下一轮用户消息应带着同一个 frame 的中断摘要、计划、工具上下文和 report 进入 Root LLM。
4. LLM 自主决定恢复动作：继续原 frame 工作、改写计划后继续、搁置旧 frame 开新任务、询问澄清，或显式终止该 frame。
5. 审批拒绝按 frame interruption 处理：业务副作用不执行，但后续任意用户消息都应拥有审批前后的 frame 上下文；审批通过后的 side-effect resume 不在 OPT-029 主线范围内。

### Interruption Types

| Type | Meaning | Default behavior |
| --- | --- | --- |
| `CLIENT_DETACHED` | 客户端连接断开、页面关闭、SSE/polling 断连 | 服务端任务继续，消息持久化，客户端可重新 attach |
| `UI_WAIT_TIMEOUT` | UI 等待预算到期但没有显式 cancel | 默认按 detach/continue 处理，不自动 cancel |
| `USER_CANCELLED` | 用户明确点击取消或上游显式调用 cancel | 停止当前执行，记录 recoverable interruption，保留 frame 上下文供下一轮 LLM 判断 |
| `APPROVAL_REJECTED` | 用户拒绝审批后又继续对话 | 不执行业务副作用，保留原 frame 上下文供下一轮 LLM 判断 |
| `SERVER_STEP_RETRYING` | LLM/tool/business step 出现可重试失败，正在 backoff/retry | 发出 progress/retry 消息，保持客户端活动感知 |
| `SERVER_STEP_INTERRUPTED` | retry 超阈值、step timeout 或 provider 熔断 | persistent root 进入可恢复中断态，下一轮由 LLM 决定继续或终止 |
| `TASK_DEADLINE_EXCEEDED` | 总任务 deadline 耗尽 | 停止后台执行，保留可恢复中断摘要，禁止无限运行 |

### State Mapping Contract

Java/session task 的核心状态必须保持精简，用于表达运行、完成、失败、取消等主干终态；recoverable、interruption reason、deadline/cancel 细节通过 `interruptionReason`、`recoverable`、`taskSubStatus` 或 `executionSubStatus` 等字段投影。Worker runtime context JSONL / journal 才是 frame recovery 的唯一事实源，Java/session 表只作为索引、查询和 UI 展示投影。

| Scenario | Client connection state | Java/session task state | Worker task/run state | Worker frame state |
| --- | --- | --- | --- | --- |
| 服务端仍在运行，客户端断开或 UI 等待预算到期 | detached / idle timeout | RUNNING 或可查询处理中 | RUNNING | 不新增 interruption，继续当前 frame |
| 用户显式取消 | connected or detached | CANCELLED / ABORTED 核心终态，`interruptionReason=USER_CANCELLED`，`recoverable=true` | 停止当前 active execution | persistent root 保留 `recoverable interruption` |
| 审批拒绝 | connected | 当前审批任务结束，`interruptionReason=APPROVAL_REJECTED`，`recoverable=true` | 不执行业务副作用 | 原审批相关 frame 保留 `recoverable interruption` |
| LLM/tool retry 中 | connected | RUNNING，lastActivityAt 刷新 | RETRYING / RUNNING | frame 仍运行，不进入 interrupted |
| retry 耗尽或 step timeout | connected or detached | FAILED 或现有失败终态，`interruptionReason=LLM_RETRY_EXHAUSTED/WORKER_STEP_TIMEOUT`，`recoverable=true` | 当前执行停止 | persistent root 或 child frame 进入 `recoverable interruption` |
| task deadline 耗尽 | connected or detached | FAILED / TIMEOUT 或现有失败终态，`interruptionReason=TASK_DEADLINE_EXCEEDED`，`recoverable=true` | 后台执行停止 | 保留可恢复中断摘要；下一轮用户消息获得新 task budget |

### Recovery Contract

1. `CLIENT_DETACHED` 和 `UI_WAIT_TIMEOUT` 不是 `USER_CANCELLED`。默认不杀后端任务，UI 应提供继续查看或重新连接入口。
2. 每个 task 必须有稳定的 `taskId`、`contextId`、message cursor 和 terminal signal，保证断线后可增量恢复。
3. 服务端长耗时、retry/backoff、等待业务函数时必须周期性输出 progress/heartbeat；这些事件应进入持久消息流或可恢复事件流。
4. 客户端等待逻辑应区分 `idle silence timeout` 和 `absolute wait budget`：有进度事件时刷新 idle 计时，但不能突破后端 task deadline。
5. 服务端中断时必须写入 frame/report/interruption reason，并明确下一步是自动 retry、等待用户继续、允许手动重试，还是失败终止。
6. frame 不应因中断直接退出；只有 LLM 在下一轮明确选择终止、或运行时判定无 persistent frame 可恢复时，才进入不可恢复终态。
7. task deadline 只约束当前执行尝试；deadline 耗尽后的下一轮用户消息应获得新的 task budget，但继续使用 Worker JSONL / journal 中的 interrupted frame context。
8. Worker runtime context JSONL / journal 是恢复 frame 的唯一事实源；Java/session、SDK、BFF、Widget 只能保留路由锚点和状态投影，不能覆盖或重建 Worker frame 上下文。
9. 所有恢复消息必须脱敏，不能输出完整附件签名 URL、API key、provider 原始错误栈或 prompt。

### Worker Frame Recovery Routing Contract

1. “下一轮消息回到同一 frame”是 Worker 内部责任，不要求 Java、SDK、BFF 或 Widget 选择具体 frame，也不要求上游传 `frameId`。
2. Java/session 层只负责保持稳定的 `sessionId`、`contextId`、`taskId`、`logicalAgentId/providerType/modelConfigId` 和用户新输入，并把下一轮消息路由到同一个 Worker / conversation context。
3. Worker 在收到下一轮消息后，根据 persistent root、conversation/task 绑定、runtime context JSONL / journal、`continuation_state=INTERRUPTED` 和 `recoverable=true` 自行定位可恢复 frame。
4. 如果存在 pending recoverable child，Root LLM 应通过 `resume_recoverable_child_skill` 继续同一个 child frame；如果用户放弃或开启无关任务，应通过 `shelve_interrupted_frame` 关闭旧中断焦点。
5. 如果 Worker 找不到可恢复 frame，应返回明确的 non-recoverable / no-interrupted-frame 状态，并允许 Java/session 将下一轮消息作为普通新 turn 处理；不得伪造 frame 上下文。
6. 当前串行会话模型下，同一 conversation / persistent root 只应存在一个 active recoverable focus；历史 JSONL 中可以保留多个 interrupted frame 记录，但下一轮恢复只使用当前 pending focus。
7. 如果 crash、手工修复或历史数据导致多个 active recoverable focus，Worker 必须按 runtime context JSONL / journal 的单调顺序选择最后一个 focus，并把更旧 focus 标记为 superseded / shelved；Root LLM 不参与底层 frame 选择，只在最新 focus 上决定继续、调整、搁置或终止。

### Retry Progress Event Contract

Retry、backoff、长耗时 step 和 provider 恢复期间必须输出可持久化事件，用于刷新客户端 idle timer 和后续排障。

最小字段：

```json
{
  "eventType": "task_progress",
  "progressType": "retrying",
  "reason": "LLM_REQUEST_TIMEOUT",
  "attempt": 1,
  "maxAttempts": 2,
  "nextRetryAfterMs": 1000,
  "remainingMs": 120000,
  "taskId": "task-id",
  "contextId": "context-id",
  "frameId": "frame-id",
  "presentationHint": "debug_detail"
}
```

要求：

1. 事件进入可补拉消息流或任务事件流，客户端 reattach 后能看到 retry 期间发生过什么。
2. retry/progress 事件必须发送给 UI；是否在聊天流中展开展示由 UI 展示模式决定，`debug` 和 `detail` 模式应显示，`simple` 模式可以折叠或只显示摘要。
3. 事件可刷新客户端 `uiIdleSilenceTimeoutMs`，但不能延长 `taskDeadlineAt`。
4. 事件和日志必须脱敏，不能包含完整附件签名 URL、API key、provider 原始错误栈或 prompt。

### Timeout Types

| Type | Meaning | Owner | Example |
| --- | --- | --- | --- |
| `uiWaitTimeoutMs` | 用户界面等待预算，到期后停止等待或提示继续后台执行 | widget / upstream frontend | 300000 |
| `uiIdleSilenceTimeoutMs` | 客户端多久没有收到 message/progress/heartbeat 后提示连接异常 | widget / upstream frontend | 30s / 60s |
| `progressHeartbeatMs` | 服务端长耗时或 retry/backoff 期间最大发消息间隔 | Java + Worker | 10s / 30s |
| `taskDeadlineAt` | 后端任务总 deadline，超过后必须停止后台执行并保留可恢复中断摘要 | session-module / Worker | now + 300s |
| `workerStepTimeoutMs` | 单个 Worker step 或 child skill 执行预算 | LangGraph Worker | 60s / 120s |
| `llmRequestTimeoutMs` | 单次 LLM HTTP request timeout | BizWorker LLM runtime | 30s / 60s |
| `businessFunctionTimeoutMs` | 单个业务函数或上游 callback timeout | business-agent-module | by function manifest |
| `cancelGraceMs` | cancel 后等待清理和报告落库的宽限时间 | Java + Worker | 5s / 10s |

### Cancellation And Detach Semantics

已确认的默认语义：

1. UI timeout 后默认按 detach/continue 处理，不自动调用后端 cancel；UI 层无法可靠判断是等待预算到期还是自身断连。
2. UI 应展示“后台仍在处理”或等价状态，并提供继续查看或 reattach 入口。
3. 只有用户显式点击取消或上游显式调用 cancel API，才表示取消意图。
4. cancel 也是一种可恢复中断：Java 和 Python Worker 必须传播 cancel 到 active task、report 和 LLM/tool execution，但 persistent frame context 不应被直接丢弃；用户下一轮任意消息仍应进入该 frame context，由 LLM 判断继续、调整、搁置或终止。
5. 用户主动取消、客户端网络断开、UI 等待超时、服务端 retry 耗尽应使用不同 reason，便于审计和提示。
6. 上游 BFF 自己断开 HTTP 连接时，不应被下游误解释为用户取消；只有显式 cancel API 才表示取消意图。

### Reason / Event Taxonomy

建议区分：

- `UI_WAIT_TIMEOUT`
- `CLIENT_DETACHED`
- `TASK_DEADLINE_EXCEEDED`
- `SERVER_STEP_RETRYING`
- `SERVER_STEP_INTERRUPTED`
- `APPROVAL_REJECTED`
- `LLM_REQUEST_TIMEOUT`
- `LLM_RETRY_EXHAUSTED`
- `WORKER_STEP_TIMEOUT`
- `BUSINESS_FUNCTION_TIMEOUT`
- `USER_CANCELLED`

这些 reason/event 应映射到精简 core status、reason/substatus、frame status、execution report status 和用户可读 message。Java/session task 不为每个可恢复中断扩展新的核心终态。

### Architecture Review Notes

与主流 durable execution / workflow / agent runtime 架构对照后，本方案的主方向是成立的：

1. Durable execution 系统一般把可恢复执行建立在持久化 history / checkpoint / event sourcing 上。OPT-029 把 Worker runtime context JSONL / journal 定为 frame recovery 唯一事实源，与 Temporal、Azure Durable Functions、LangGraph checkpointer 的方向一致。
2. State-machine / workflow 系统一般显式建模 timeout、heartbeat、retry、catch/error name。OPT-029 的 reason taxonomy、progress/heartbeat、task deadline 和 retry budget 与 AWS Step Functions 的超时和错误处理模型一致。
3. Agent runtime 的恢复通常依赖稳定 thread / checkpoint 指针。OPT-029 要求 Java/session 只保留 `sessionId`、`contextId`、`taskId` 等路由锚点，Worker 自己根据 JSONL / journal 恢复 frame，符合 LangGraph `thread_id` / checkpoint 的职责划分。
4. 与传统 workflow 不同，LLM 调用天然非确定性，因此 OPT-029 不应追求完整 deterministic replay；更合适的策略是把 LLM/tool/business function 调用结果、失败、retry 和中断摘要作为 journal event 持久化，恢复时让 LLM 基于已记录事实继续决策。

需要补齐的工程约束：

1. 明确 journal 单调顺序字段，例如 `journalSeq` / `eventSeq` / `interruptedAt`，并规定多个 active recoverable focus 一律取最后一个。
2. 明确 side-effect 幂等策略：恢复或重试时不能重复执行已成功落库、已发起审批、已创建工单等业务副作用。
3. 明确 reattach cursor 合同：客户端补拉必须能拿到 terminal signal、最后一次 progress/retry、interruption reason 和最终 report。
4. 明确 journal repair / compaction 规则：历史 interrupted frame 可保留，但 active focus 投影必须可被重建，旧 focus 被新 focus 覆盖时要有 superseded / shelved 事件。
5. 明确测试门槛覆盖 crash recovery、重复消息、重复 cancel、deadline 耗尽后下一轮继续、以及多个 recoverable focus 取最后一个的异常修复路径。

参考的一手资料：

- Temporal durable execution: https://docs.temporal.io/
- AWS Step Functions error handling / timeout best practices: https://docs.aws.amazon.com/step-functions/latest/dg/concepts-error-handling.html
- AWS Step Functions timeout best practices: https://docs.aws.amazon.com/step-functions/latest/dg/sfn-best-practices.html
- LangGraph interrupts and persistence: https://docs.langchain.com/oss/python/langgraph/interrupts
- LangGraph persistence: https://langchain-5e9cc07a.mintlify.app/oss/python/langgraph/persistence
- Azure Durable Functions / Durable Task orchestrations: https://learn.microsoft.com/en-us/azure/azure-functions/durable/durable-functions-orchestrations

## Implementation Plan

### Stage A - Current State Inventory

- [x] 梳理 widget timeout、polling、cancel 当前行为。
- [x] 梳理 SDK/OpenAPI 是否支持 timeout/deadline 参数。
- [x] 梳理 session-module task timeout、SSE 断连、cancel 当前行为。
- [x] 梳理 LangGraph Java relay 对 Worker 连接、read timeout、cancel 的行为。
- [x] 梳理 Python Worker active task、frame、report 在 timeout/cancel 下的收敛行为。
- [x] 梳理 LLM runtime 的 provider timeout、retry、backoff 和 exception taxonomy。

### Stage B - Contract Design

- [x] 固化 glossary，明确 `upstream`、`client detach`、`task interruption`、`frame interruption`、`recoverable continuation` 的观察视角和边界。
- [x] 定义 client detach 与 task/frame interruption 的两类恢复语义。
- [x] 定义 user cancel、approval rejected、server interruption、deadline exceeded 统一进入 recoverable frame context 的规则。
- [x] 定义 client connection state、Java/session task state、Worker execution state、Worker frame state 的映射表。
- [x] 定义 request-level timeout/deadline 字段和默认值。
- [x] 定义 Java task 精简核心状态 + reason/substatus/recoverable 投影规则，避免核心终态膨胀。
- [x] 定义 timeout reason taxonomy 和跨层状态映射。
- [x] 定义 UI timeout 后默认 detach/continue，不自动 cancel；只有显式 cancel API 表示取消意图。
- [x] 定义 task reconnect / reattach 合同：taskId、contextId、cursor、terminal signal 和历史消息补拉。
- [x] 定义 Worker 内部 frame recovery routing 合同：Java/上游不选择 frame，Worker 基于 persistent root / runtime context JSONL / journal / interruption state 自行恢复。
- [x] 定义串行会话下单 active recoverable focus 规则；异常出现多个 focus 时按 journal 顺序取最后一个，并修复旧 focus 投影。
- [x] 定义 retry/progress/heartbeat 消息格式，确保 retry/backoff 期间客户端不会误判中断。
- [x] 定义 retry/progress 事件必须发送到 UI，`debug` / `detail` 模式展示，`simple` 模式可折叠。
- [x] 定义 retry 如何消费剩余 budget。
- [x] 定义日志与事件字段，避免输出敏感 URL、API key 或附件签名。

### Stage C - Runtime Implementation

- [x] Widget timeout 分支按合同展示 detach/后台继续处理状态；除用户显式 cancel 外，不调用 cancel。
- [x] Widget 支持 reconnect/reattach 后按 cursor 补拉任务消息和最终结果。
- [x] Java session task 记录 deadline、interruption reason、cancel reason、lastActivityAt。
- [x] LangGraph Java relay 设置连接/read timeout，并把 deadline 传给 Worker。
- [x] Python Worker 接收 deadline，LLM step 使用 remaining budget。
- [x] LLM client 配置基础 request timeout、有限 retry 与熔断。
- [x] LLM retry/backoff 前后发出持久化 progress/retry 消息，包含 attempt、reason、remaining budget。
- [x] UI 在 `debug` / `detail` 模式展示 retry/progress 事件，在 `simple` 模式折叠或摘要展示。
- [x] LLM client 接入 request-level deadline/remaining budget 和统一 timeout taxonomy。
- [x] retry 耗尽、用户取消、审批拒绝、deadline 耗尽或服务端中断后，persistent root 进入可恢复中断态，下一轮用新 task budget 并由 LLM 决定继续或终止。
- [x] Worker 以 runtime context JSONL / journal 作为唯一事实源定位 recoverable frame，并维护单 active recoverable focus；异常出现多个 focus 时按 journal 顺序取最后一个。
- [x] Worker 对恢复后的业务副作用执行幂等校验，避免 retry/resume 重复创建审批、工单或外部调用。
- [x] timeout/cancel/detach/interruption 后 report、visible messages、active task 清理必须收敛。

### Stage D - Verification

- [x] 单测覆盖 timeout taxonomy 和状态映射。
- [x] 单测覆盖多个 active recoverable focus 时按 journal 顺序取最后一个，并将旧 focus 标记为 superseded / shelved。
- [x] 单测覆盖 resume/retry 不重复执行业务副作用。
- [x] 集成测试覆盖客户端断线后服务端继续运行并可重新 attach。
- [x] targeted tests 覆盖 LLM hang、LLM retry progress 消息、retry 耗尽后的可恢复中断。
- [x] Worker 合同捕获覆盖 LLM timeout retry progress、retry 耗尽 recoverable interruption、下一轮复用 interrupted frame、client detach 后服务端继续完成并落 journal。
- [x] 集成测试覆盖 Java relay read timeout。
- [x] targeted tests 覆盖 Worker slow 期间 active task 保持、首个进度事件后继续等待结果，以及 UI wait timeout 默认 detach/continue。
- [x] 真实链路覆盖 TMS 业务助手 wait timeout/detach recovery：远程 dev-kvm-x3 TMS OpenAPI 链路用短等待预算模拟 UI wait timeout，确认不 cancel、可补拉消息、下一轮复用 `contextId`。
- [x] E2E 覆盖 TMS 业务助手用户可见提示、取消、后台继续查看、断线恢复。
- [x] targeted soak test 覆盖 provider hang 不导致 LLM 并发槽和 worker thread 泄漏。

## Acceptance Criteria

1. 客户端网络断开、页面关闭或 UI wait timeout 后，服务端任务默认继续运行且可通过 taskId/contextId/cursor 恢复查看。
2. 用户主动取消、客户端断开、UI 等待超时、服务端 retry 中、retry 耗尽、task deadline 耗尽均有不同 reason 和用户可读提示。
3. LLM provider hang 能在配置时间内进入 retry；每次 retry/backoff 都有持久化 progress/retry 消息，客户端不会因为静默误判中断。
4. retry 耗尽、用户取消、审批拒绝或服务端中断后，persistent root / 多轮会话进入可恢复中断态，保留 frame/report/context；下一轮用户消息由 LLM 判断继续、调整、搁置或终止。
5. 前端 300 秒等待超时不自动 cancel；默认 detach/continue 可追踪，只有用户显式 cancel 才进入取消链路。
6. 每一跳都能通过日志或事件看到 interruption type、timeoutMs、deadlineAt、elapsedMs、remainingMs、attempt、taskId、frameId。
7. Java/session task 核心状态保持精简，recoverable、interruption reason、deadline/cancel 细节通过 reason/substatus 字段表达。
8. timeout、heartbeat 和 retry 策略可配置，默认值适合 TMS 业务助手交互。
9. 审批拒绝后用户再次发送任意消息时，系统仍能把原审批 frame 的上下文交给 LLM，而不是从空白会话或错误 frame 重新开始。
10. deadline 耗尽后的下一轮用户消息获得新的 task budget，但仍复用 interrupted frame context。
11. Worker runtime context JSONL / journal 是 frame recovery 唯一事实源；Java/session 只能提供投影状态和路由锚点，不能覆盖 frame recovery 结果。
12. 下一轮消息进入同一 Worker conversation 后，由 Worker 内部自行定位 recoverable frame；Java、SDK、BFF、Widget 不需要也不允许承担 frame 选择责任。
13. 串行会话同一时刻最多只有一个 active recoverable focus；多个 focus 只作为 crash/manual repair 后的异常防御场景处理，并且一律按 journal 顺序取最后一个。
14. retry/progress 事件必须进入 UI 可消费事件流；`debug` 和 `detail` 模式可见，`simple` 模式允许折叠或摘要。
15. resume/retry/cancel 后的恢复路径不得重复执行已成功完成的业务副作用。

## Related Bugs

- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/BUG-027-biz-worker-llm-call-timeout-fuse-missing.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/BUG-028-tms-ticket-agent-attachment-not-delivered.md`

## Progress Tracking

### Development Progress

- [x] 记录 2026-05-18 TMS 业务助手 300 秒 UI timeout 与后台任务仍运行的现象。
- [x] 确认 widget 当前 300 秒 timeout 是前端等待预算，不等价于后端取消。
- [x] 复核 `BUG-027` / `BUG-028` 核心修复，把剩余治理项收敛到 OPT-029。
- [x] 明确 OPT-029 本质问题为“中断任务如何优雅恢复”，并拆分客户端断开与服务端中断两大场景。
- [x] 明确除客户端 detach 外，用户取消、审批拒绝、retry 耗尽、deadline 耗尽等统一收敛为 recoverable frame interruption，由 LLM 在下一轮自主决定继续或终止。
- [x] 明确“下一轮消息回到同一 frame”是 Worker 内部恢复路由责任，Java/上游只保持稳定会话、上下文和 Worker 路由锚点。
- [x] 明确 Worker runtime context JSONL / journal 是 frame recovery 唯一事实源，Java/session 只做状态投影和路由索引。
- [x] 明确 Java task 核心状态保持精简，recoverable、reason、deadline/cancel 等细节通过 substatus/reason 字段维护。
- [x] 明确 UI 300 秒 wait timeout 默认 detach/continue，不自动 cancel；显式 cancel 也是 recoverable interruption。
- [x] 明确 retry/progress 事件必须发送到 UI，`debug` / `detail` 模式展示，`simple` 模式可折叠。
- [x] 明确 deadline 耗尽后下一轮继续复用 interrupted frame context，并使用新的 task budget。
- [x] 明确串行会话同一时刻只允许一个 active recoverable focus；多个 focus 为异常防御场景，并固定取最后一个。
- [x] 完成与 durable execution / workflow / agent checkpoint 主流架构的对照评审，确认需要补充 journal 顺序、幂等、副作用和 reattach cursor 约束。
- [x] 完成跨层 current-state inventory：Widget wait timeout、Java task/relay、Python Worker frame/journal、LLM guard、cancel 与附件脱敏边界已落点。
- [x] 完成 interruption recovery / timeout / deadline / cancel 合同设计：两类恢复场景、Worker 内部 frame routing、状态投影、retry progress、deadline 与 idempotency 合同已固化。
- [x] 完成 runtime 实现：Widget timeout 按 detach/continue 处理，Java task 投影 interruption/deadline/recoverable，Worker 基于 JSONL/journal 选最新 recoverable focus，LLM retry 发送 progress，业务函数调用生成稳定 idempotency key。
- [x] 完成 Worker 级 OPT-029 合同捕获脚本与基线记录：`tools/langgraph-biz-worker/scripts/opt029_timeout_recovery_capture.py` 复用 `tools/mock-llm-service` scripted endpoint 与 `delay_ms` 慢响应模拟，基线记录目录 `docs/version-tracker/1.3.0-SNAPSHOT/test-records/opt-029-timeout-recovery/20260518-203003-99163e/`。
- [x] 完成 3061 真实 Worker 复跑：脚本重启 `tools/langgraph-biz-worker` 后执行 OPT-029 capture，记录目录 `docs/version-tracker/1.3.0-SNAPSHOT/test-records/opt-029-timeout-recovery/20260518-210232-e9042c/`，6 个 verdict 全通过。
- [x] 完成真实链路治理验收：远程 TMS OpenAPI wait-timeout/detach recovery 已通过；TMS 浏览器 mock-BFF E2E 已覆盖 UI 等待超时用户可见提示、显式 cancel 不丢 context、重新打开历史会话后继续同一 `contextId`。Java relay read timeout、Worker slow、UI wait timeout 和 provider hang soak 已有目标测试覆盖。

### Testing Progress

- [x] Python Worker targeted tests: `test_llm_tool_dispatcher.py`、`test_file_frame_journal.py`、`test_frame_lifecycle.py::...latest_recoverable...`、`test_llm_call_guard.py`，`25 passed`.
- [x] Python agent targeted tests: LLM transient retry、child timeout recoverable interruption、continue reopen failed child、persistent root model error recoverable，`4 passed`.
- [x] Java relay/session timeout tests: `mvn -pl addons/langgraph-biz-worker -am "-Dtest=LanggraphWorkerClientTest,LanggraphStreamRelayTest,LanggraphTaskServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，`33 tests passed`；覆盖可配置 WebClient response timeout、`stream_read_timeout` interruption reason 投影和 task 状态映射。
- [x] Widget timeout/progress behavior tests: `pnpm --filter @foggy/navigator-chat-widget test -- useNavigatorChat.ux.test.ts`，`12 passed`；覆盖 UI wait timeout 后不调用 cancel、状态显示为后台处理，并且下一轮发送复用原 `contextId`。
- [x] Client detach and reattach recovery tests: `pytest tests/test_e2e_scripted_tool_call_streaming.py::test_client_detach_then_next_turn_reuses_persistent_frame -q`，真实 Uvicorn Worker + mock LLM 慢响应，验证客户端 read timeout 后服务端完成并在下一轮复用同一 root frame。
- [x] LLM retry progress message tests.
- [x] Worker OPT-029 capture: `$env:PYTHONPATH='src'; .\.venv\Scripts\python.exe scripts\opt029_timeout_recovery_capture.py --base-url http://localhost:3061`，基于项目 mock LLM scripted endpoint，最新记录 `20260518-210232-e9042c`，6 个 verdict 全通过（retry progress、reason interruption、next-turn recovery、client detach、detach 后 server completed、artifact collected）。
- [x] 明确慢任务模拟分层：Java relay read timeout 使用首个 SSE 延迟的确定性 HTTP server；FSScript bridge 会先发 `system/FSScript started`，更适合覆盖 Worker 内部慢任务仍有进度事件/心跳的场景。
- [x] Worker slow targeted tests: `$env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest tests/test_query.py -q`，`13 passed`；新增 FSScript 慢任务用例验证首个 `system` 事件后 task 仍处于 active，直到 result 后清理。
- [x] Provider hang guard/soak targeted tests: `$env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest tests/test_llm_call_guard.py -q`，`4 passed`；覆盖 request timeout、retry progress、circuit open、连续 provider hang 后并发槽释放和 `llm-call` worker thread 回落。
- [x] TMS OpenAPI end-to-end timeout/detach recovery smoke: `bash deploy/dev-kvm-x3/scripts/54-smoke-tms-timeout-recovery.sh`，远程 dev-kvm-x3 真实 TMS Navigator agent 链路通过。第一轮 `lgt_4956a179d5c4464b` / `contextId=20260518-73b3` 从 `SUBMITTED` 到 `COMPLETED`，模拟 UI wait timeout 后停止轮询 5 秒且未调用 cancel，消息可补拉；第二轮 `lgt_ae42b98871a045fe` 复用同一 `contextId=20260518-73b3` 并 `COMPLETED`。证据：`docs/version-tracker/1.3.0-SNAPSHOT/test-records/opt-029-tms-timeout-recovery/20260518-remote-tms-openapi/summary.json`。
- [x] TMS browser E2E timeout/cancel/reopen recovery: `pnpm exec playwright test tests/playwright/navigator-chat-timeout-recovery.spec.ts --project=chromium`，`3 passed`；覆盖 UI 300 秒 wait timeout 后展示后台处理提示且不调用 cancel、下一条消息复用 `contextId`，用户显式 cancel 后下一条消息仍复用 `contextId`，以及重新打开浏览器会话后从历史会话恢复并继续同一 `contextId`。
- [x] Provider hang soak / leak test.

### Implementation Quality Gate - 2026-05-18

- Quality record: `docs/version-tracker/1.3.0-SNAPSHOT/quality/OPT-029-upstream-timeout-governance-implementation-quality.md`
- Decision: `ready-for-coverage-audit`.
- Findings: no blocking implementation quality findings.
- Follow-up: none required before coverage audit; browser physical network-disconnect drill can remain a later hardening smoke.

### Test Coverage Audit - 2026-05-18

- Coverage record: `docs/version-tracker/1.3.0-SNAPSHOT/coverage/OPT-029-upstream-timeout-governance-coverage-audit.md`
- Conclusion: `ready-with-gaps`.
- Covered: UI wait timeout detach/continue, explicit cancel context retention, Java relay timeout projection, Worker recoverable frame routing, retry progress, provider hang guard, client detach/reattach, remote TMS OpenAPI recovery, and TMS browser timeout/cancel/reopen E2E.
- Non-blocking gap: live browser physical network-disconnect reattach through TMS BFF is still recommended as hardening evidence.
- Acceptance readiness: ready for `foggy-acceptance-signoff` with the non-blocking hardening gap acknowledged.

### Acceptance Signoff - 2026-05-18

- Acceptance record: `docs/version-tracker/1.3.0-SNAPSHOT/acceptance/OPT-029-upstream-timeout-governance-acceptance.md`
- Acceptance status: `signed-off`.
- Acceptance decision: `accepted-with-risks`.
- Blocking items: none.
- Follow-up required: yes, only for the non-blocking live browser physical network-disconnect drill.

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-05-18
- acceptance_record: docs/version-tracker/1.3.0-SNAPSHOT/acceptance/OPT-029-upstream-timeout-governance-acceptance.md
- blocking_items: none
- follow_up_required: yes

### Experience Progress

- [x] 明确 UI timeout 语义：等待超时、已取消、后台继续处理三者不能混用；300 秒等待超时默认后台继续处理。
- [x] 明确用户可在历史会话中继续查看后台完成结果：前端按 taskId/contextId/cursor 恢复消息流，Worker 恢复 frame 选择不依赖 UI 传 frameId。
- [x] 明确服务端 retry/backoff 时的用户可见策略：事件必须发给 UI，`debug` / `detail` 展示，`simple` 可折叠。
