---
type: optimization
version: 1.3.2-SNAPSHOT
ticket: OPT-004
severity: medium
status: in-progress
owner: session-module | claude-worker-agent | codex-worker-agent | gemini-worker-agent | navigator-frontend
created_at: 2026-06-30
---

# OPT-004: Worker Response Timeout Indicator

## 文档作用

- doc_type: workitem
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 记录 Worker 任务长时间无用户可见输出时的辅助超时提示方案、实现边界、测试要求与进度。

## Background

Codex、Claude Code 等 SDK/CLI 在任务已经实际停止产出时，可能不会自动退出 SDK 进程。现有设计不允许系统自动 kill SDK/CLI，避免误杀仍在执行的长任务；但这会导致前端会话列表一直显示任务运行中，用户误以为“还没有结果”。

需要新增一个非破坏性的辅助状态：当运行中的任务超过 5 分钟没有新的用户可见输出时，在会话列表中提示“响应超时”。该提示不能改变任务主状态，不能触发自动失败，也不能自动 kill SDK/CLI。

## Target Outcome

- 运行中的 Worker 任务超过 5 分钟无用户可见输出时，前端在会话行 `中止` 操作旁显示响应超时提示。
- 收到新的 assistant/tool/result/error 等用户可见输出后，提示自动隐藏。
- 用户主动中止、任务完成、失败或中止后，提示自动隐藏。
- Claude、Codex、Gemini Worker 使用一致的判断口径。
- 保留现有安全边界：不自动 kill SDK/CLI，不把辅助状态写成 `FAILED` / `ABORTED`。

## Glossary

- response timeout indicator: 任务仍处于 `RUNNING` 时的辅助提示，表示超过阈值未收到用户可见输出；不是任务失败状态。
- last output: 最近一次用户可见输出，包括 assistant 文本、tool use、tool result、result、error。内部 keepalive、waiting、heartbeat 不算 last output。
- task status: 已有任务主生命周期状态，例如 `RUNNING`、`COMPLETED`、`FAILED`、`ABORTED`、`AWAITING_PERMISSION`。

## Confirmed Rules

- 阈值默认 5 分钟，后端可配置，前端只消费后端结果。
- 辅助状态只对 `RUNNING` 任务生效。
- `AWAITING_PERMISSION` 不显示响应超时，避免把等待用户授权误判为 SDK 无输出。
- 不使用 `lastAliveAt` 判断响应超时；该字段表达 worker/CLI 存活，不能代表用户可见输出。
- 不把 `响应超时` 加入 `ConversationConfig.interactionState`，避免污染会话过滤和主工作流状态。
- 新输出、终态和用户中止均应清除提示。

## Non-Goals

- 不自动 kill SDK/CLI 进程。
- 不自动把任务标记为失败或中止。
- 不新增独立会话筛选状态。
- 不改变现有任务创建、恢复、中止 API 的基本语义。

## Implementation Plan

1. 后端任务模型增加用户可见输出时间和辅助超时投影。
   - 字段建议：`lastOutputAt`、`responseTimedOut`、`silentForSeconds`、`responseTimeoutThresholdSeconds`。
   - `lastOutputAt` 作为持久字段；其他字段可按当前时间计算后返回 DTO。
2. Claude / Codex / Gemini Java relay 在收到用户可见输出事件时刷新 `lastOutputAt`。
   - 刷新事件包括 assistant 文本、tool use、tool result、result、error。
   - waiting、heartbeat、keepalive 不刷新。
3. 统一任务 DTO 和各 provider DTO 暴露辅助字段。
   - 主状态仍保持原值。
   - 仅 `RUNNING && !AWAITING_PERMISSION && now - lastOutputAt >= threshold` 返回 `responseTimedOut=true`。
4. 前端类型和会话列表增加提示。
   - `conv.latestTask.responseTimedOut` 为 true 时，在 `中止` 附近显示 warning 图标与 `响应超时`。
   - tooltip 文案说明“超过 5 分钟没有收到 Worker 输出，任务未被自动中止”。
5. 任务事件和轮询都能收敛状态。
   - 收到新输出后 DTO 字段清除提示。
   - 如只依赖轮询，最多有轮询间隔延迟；如已有 task update SSE 可低成本扩展，优先让列表更快刷新。

## Acceptance Criteria

- `RUNNING` 任务超过 5 分钟无用户可见输出时，列表显示响应超时提示。
- 有新输出后提示消失，任务主状态不变。
- 用户点击 `中止` 后提示消失，任务进入已有中止流程。
- `AWAITING_PERMISSION` 不显示响应超时。
- Claude、Codex、Gemini 任务 DTO 都有一致字段语义。
- 前端构建通过；后端目标测试通过。

## Verification Plan

```powershell
mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent -am "-DfailIfNoTests=false"
bash scripts/build-frontend.sh
git diff --check
```

如本地完整目标测试耗时过长，可先运行相关单测类，并在 Progress Tracking 中记录未运行范围与原因。

## Plan Review

- 评审对象类型：子目录执行文档。
- 评估通过：方案保留 task status 主生命周期，仅增加辅助投影，符合“不自动 kill”和“避免误杀”的安全边界。
- 替代方案：直接依赖 worker 侧 heartbeat 可以覆盖 Claude，但 Codex/Gemini 不稳定；放在 Java relay / task DTO 层更适合跨 worker 统一。
- 复杂度：中等，主要复杂度在三类 provider DTO/relay 字段一致性；不引入新框架或独立状态机。
- 风险点：长时间推理但无输出会出现提示，因此 UI 文案必须表达“长时间无输出/可能超时”，不能表达任务失败。
- 命名与术语：`responseTimedOut` 表示辅助提示布尔值，`lastOutputAt` 表示用户可见输出时间；不复用 `lastAliveAt`，避免存活和输出语义混淆。
- 证据缺口：需要补后端时间判断测试和前端渲染/构建证据。

## Progress Tracking

### Development Progress

- [x] 需求语义确认：辅助状态，不自动 kill，不改主状态。
- [x] 执行文档落盘。
- [ ] 后端模型 / DTO / relay 实现。
- [ ] 前端类型与会话列表提示实现。
- [ ] 执行 check-in 回写。

### Testing Progress

| Case | Scope | Status | Evidence |
| --- | --- | --- | --- |
| timeout calculation | backend DTO/service | not-run | 待补或运行相关测试。 |
| output clears timeout | backend relay/service | not-run | 待补或运行相关测试。 |
| abort/terminal hides timeout | frontend/backend state | not-run | 待验证。 |
| frontend build | navigator-frontend | not-run | `bash scripts/build-frontend.sh`。 |
| diff hygiene | repository | not-run | `git diff --check`。 |

### Experience Progress

| Check | Status | Notes |
| --- | --- | --- |
| 页面可达性 | pending | 会话列表仍在 ClaudeWorkerView 原入口。 |
| 核心交互流程 | pending | 响应超时提示与中止按钮并列显示。 |
| 异常状态 | pending | 无输出、授权等待、终态、中止后隐藏均需验证。 |
| 数据一致性 | pending | 前端只消费后端辅助字段，不本地推导主状态。 |
| Playwright / manual evidence | not-run | UI 改动完成后补充。 |

### Implementation Self-Check

- scope conformance: pending.
- non-goals preserved: pending.
- touched code paths listed: pending.
- tests recorded: pending.
- remaining risks documented: pending.
- self-check conclusion: pending.
