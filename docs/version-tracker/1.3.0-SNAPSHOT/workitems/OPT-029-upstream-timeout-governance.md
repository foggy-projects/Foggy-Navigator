---
type: optimization
version: 1.3.0-SNAPSHOT
ticket: OPT-029
severity: major
status: open
owner: upstream-integration + session-module + biz-worker-runtime + widget
---

# OPT-029: Upstream Timeout Governance

## Background

2026-05-18 TMS 业务助手联调暴露出跨层 timeout 语义不一致问题。前端在 300 秒后显示 `等待响应超时（300秒）`，但 Java 到 Python Worker 的连接仍在，Python Worker 仍有 active task，LLM provider 连接仍保持 established。

这说明当前 `300 秒` 是 widget 等待预算，而不是端到端取消、后端 deadline 或 Worker/LLM request timeout。

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

1. 前端 `config.timeout ?? 300_000` 到期后停止轮询并显示超时，但未自动取消后端任务。
2. Java session task、LangGraph task、Python Worker active task 与 frame 状态没有统一 deadline 语义。
3. LLM request 没有显式 timeout、有限 retry、熔断和错误分类。
4. task timeout、client disconnect、用户取消、LLM provider timeout、业务函数 timeout 混在一起，无法稳定映射到用户可读状态。
5. 缺少每一跳的 deadline/remaining budget 观测字段，排障只能靠进程连接和 frame 数据反推。

## Target Outcome

建立端到端 timeout 治理合同，使每一层都能回答：

- 这次请求的用户可等待多久？
- 后端是否继续执行，还是必须取消？
- LLM 单次请求最多等待多久？
- retry 会消耗多少剩余预算？
- 超时后 task/frame/report/message 如何收敛？
- 哪些 timeout 是用户可重试，哪些是系统应降级或熔断？

## Proposed Contract

### Timeout Types

| Type | Meaning | Owner | Example |
| --- | --- | --- | --- |
| `uiWaitTimeoutMs` | 用户界面等待预算，到期后停止等待或提示继续后台执行 | widget / upstream frontend | 300000 |
| `taskDeadlineAt` | 后端任务总 deadline，超过后必须进入终态或可恢复终止态 | session-module / Worker | now + 300s |
| `workerStepTimeoutMs` | 单个 Worker step 或 child skill 执行预算 | LangGraph Worker | 60s / 120s |
| `llmRequestTimeoutMs` | 单次 LLM HTTP request timeout | BizWorker LLM runtime | 30s / 60s |
| `businessFunctionTimeoutMs` | 单个业务函数或上游 callback timeout | business-agent-module | by function manifest |
| `cancelGraceMs` | cancel 后等待清理和报告落库的宽限时间 | Java + Worker | 5s / 10s |

### Cancellation Semantics

需要产品和平台共同明确：

1. UI timeout 后默认是否调用后端 cancel。
2. 如果不 cancel，UI 应展示“后台仍在处理”，并提供继续查看入口。
3. 如果 cancel，Java 和 Python Worker 必须传播 cancel 到 active task、frame、report 和 LLM/tool execution。
4. 用户主动取消与 UI 等待超时应使用不同 reason，便于审计和提示。

### Error Taxonomy

建议区分：

- `UI_WAIT_TIMEOUT`
- `TASK_DEADLINE_EXCEEDED`
- `LLM_REQUEST_TIMEOUT`
- `LLM_RETRY_EXHAUSTED`
- `WORKER_STEP_TIMEOUT`
- `BUSINESS_FUNCTION_TIMEOUT`
- `CLIENT_DISCONNECTED`
- `USER_CANCELLED`

这些错误应映射到统一 session task status、LangGraph task status、frame status、execution report status 和用户可读 message。

## Implementation Plan

### Stage A - Current State Inventory

- [ ] 梳理 widget timeout、polling、cancel 当前行为。
- [ ] 梳理 SDK/OpenAPI 是否支持 timeout/deadline 参数。
- [ ] 梳理 session-module task timeout、SSE 断连、cancel 当前行为。
- [ ] 梳理 LangGraph Java relay 对 Worker 连接、read timeout、cancel 的行为。
- [ ] 梳理 Python Worker active task、frame、report 在 timeout/cancel 下的收敛行为。
- [ ] 梳理 LLM runtime 的 provider timeout、retry、backoff 和 exception taxonomy。

### Stage B - Contract Design

- [ ] 定义 request-level timeout/deadline 字段和默认值。
- [ ] 定义 timeout reason taxonomy 和跨层状态映射。
- [ ] 定义 UI timeout 后是 cancel 还是 detach/continue 的默认策略。
- [ ] 定义 retry 如何消费剩余 budget。
- [ ] 定义日志与事件字段，避免输出敏感 URL、API key 或附件签名。

### Stage C - Runtime Implementation

- [ ] Widget timeout 分支按合同调用 cancel 或展示后台继续处理状态。
- [ ] Java session task 记录 deadline、timeout reason、cancel reason。
- [ ] LangGraph Java relay 设置连接/read timeout，并把 deadline 传给 Worker。
- [ ] Python Worker 接收 deadline，所有 root/child frame 与 LLM step 使用 remaining budget。
- [ ] LLM client 配置 request timeout、有限 retry 与熔断。
- [ ] timeout/cancel 后 report、visible messages、active task 清理必须收敛。

### Stage D - Verification

- [ ] 单测覆盖 timeout taxonomy 和状态映射。
- [ ] 集成测试覆盖 LLM hang、Worker slow、Java relay read timeout、UI wait timeout。
- [ ] E2E 覆盖 TMS 业务助手用户可见提示、取消、后台继续查看。
- [ ] 压测或 soak test 覆盖 provider hang 不导致 active task 泄漏。

## Acceptance Criteria

1. 前端 300 秒等待超时不再产生“UI 放弃但后台无限 RUNNING”的不可观测状态。
2. LLM provider hang 能在配置时间内转为明确失败或可重试状态。
3. 每一跳都能通过日志或事件看到 timeout type、timeoutMs、deadlineAt、elapsedMs、taskId、frameId。
4. 用户主动取消、UI 等待超时、后端 task deadline、LLM request timeout 有不同 reason。
5. session task、LangGraph task、frame execution report 最终状态一致或有明确的 detach/continue 语义。
6. timeout 和 retry 策略可配置，默认值适合 TMS 业务助手交互。

## Related Bugs

- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/BUG-027-biz-worker-llm-call-timeout-fuse-missing.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/BUG-028-tms-ticket-agent-attachment-not-delivered.md`

## Progress Tracking

### Development Progress

- [x] 记录 2026-05-18 TMS 业务助手 300 秒 UI timeout 与后台任务仍运行的现象。
- [x] 确认 widget 当前 300 秒 timeout 是前端等待预算，不等价于后端取消。
- [ ] 完成跨层 current-state inventory。
- [ ] 完成 timeout/deadline/cancel 合同设计。
- [ ] 完成 runtime 实现。

### Testing Progress

- [ ] Python LLM timeout tests.
- [ ] Java relay/session timeout tests.
- [ ] Widget timeout/cancel behavior tests.
- [ ] TMS end-to-end timeout scenario.

### Experience Progress

- [ ] 明确 UI timeout 文案：等待超时、已取消、后台继续处理三者不能混用。
- [ ] 明确用户是否可在历史会话中继续查看后台完成结果。
