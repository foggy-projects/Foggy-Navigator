---
quality_scope: feature
quality_mode: pre-coverage-audit
version: 1.4.0-SNAPSHOT
target: OPT-003 Codex app-server interactive input
status: reviewed
decision: ready-for-coverage-audit-with-risks
reviewed_by: Codex
reviewed_at: 2026-07-11
follow_up_required: yes
production_enablement: not-approved
---

# Implementation Quality Gate

## Background

OPT-003 已完成 app-server 原生 `request_user_input`、同 turn 回复、SSE 重连同步和活动 turn resume 防重。此检查只判断隔离实现是否可进入覆盖审计，不批准 P3 生产切流。

## Check Basis

- [Workitem](../workitems/OPT-003-codex-app-server-interactive-input.md)
- Worker `0.2.0` 协议、HTTP、恢复和 release 回归。
- Java Codex/Session/Claude、通用聊天组件、Navigator PC 和 Mobile 回归。
- 真实 Worker、Java-SSE-PC、刷新/窄屏和重启恢复证据。

## Changed Surface

- `tools/codex-app-server-worker`: experimental request allowlist、sanitized pending interaction、一次性 respond、线程占用和 channel-loss 恢复。
- `addons/codex-worker-agent`、`session-module`: `AWAITING_INPUT`、typed response、runtime affinity、resume guard、SSE confirmation projection。
- `packages/foggy-chat-core`、`packages/foggy-chat`、`packages/navigator-frontend`、`packages/foggy-mobile`: 问题卡、数字快捷回复、刷新恢复和 320px 布局。
- `addons/claude-worker-agent`: array answer 向旧 Claude wire format 的兼容归一化。
- 旧 Codex SDK Worker 未修改交互设计，仍不支持 Ultra。

## Quality Checklist

| Check | Result | Notes |
|---|---|---|
| scope conformance | pass | 仅 allowlist `item/tool/requestUserInput`；command/file approval 和未知 request 仍拒绝 |
| state semantics | pass | `RUNNING/AWAITING_INPUT` 是活动态；断流只重连/同步；仅终态允许 resume |
| once-only response | pass | 精确 request/turn/runtime/instance affinity；成功后重复回复稳定拒绝 |
| duplicate execution protection | pass | Java 和 Worker 均拒绝活动 thread 新任务；真实误发 `continue` 未新增任务 |
| durability | pass-isolated | 待答内容做 sanitized projection；Worker 重启后失去原 channel 时 `USER_INPUT_CHANNEL_LOST` fail closed |
| response-loss compensation | pass | HTTP 成功响应丢失与跨进程 SSE 竞态使用确定性 confirmation 补偿，不重复确认 |
| privacy | pass | secret answer 不写任务、事件或日志；前端/SSE 不暴露原始 request payload、token、endpoint 或 reasoning |
| compatibility | pass | Claude array answer 兼容；旧 SDK Worker 路径不变 |
| UX | pass-isolated | desktop/320px 问题卡、刷新恢复、单选快捷回复、多问题和已答状态通过 |
| release engineering | pass-isolated | Worker `0.2.0` full test、schema、typecheck、build、release archive 通过 |

## Findings

- 独立 review 发现跨进程场景中“Worker 已接受答案但 Java HTTP 响应丢失”可能使 UI 永久停留待答；已改为仅由实际执行 `PENDING -> RESOLVED` 的 SSE 路径发布确定性 confirmation，并补回归。
- SSE 连接不是 turn owner。浏览器关闭、刷新或网络断流时，后端任务保持 `AWAITING_INPUT`，恢复后依靠 status/snapshot 重新展示原问题。
- 真实活动 turn 上调用 resume 返回业务拒绝；任务数保持 `1`，没有第二个 turn 或重复副作用。Worker 直连同类请求返回 `409 APP_SERVER_THREAD_ACTIVE`。
- 固定 CLI `0.144.1` 的真实模型侧工具会生成单选/多问题和 `isOther`，但未生成纯 secret/freeform-only 请求；这些字段已有协议和 UI 自动化，尚缺真实模型闭环。
- 首次 Windows package 内嵌测试出现两项进程树 settle 时序波动；同一变更的独立 full suite 和随后 release 全流程均通过，未发现功能断言失败。

## Risks / Follow-ups

- `experimentalApi` 和 `default_mode_request_user_input` 仍是固定 CLI 的实验能力；升级 Codex CLI 时必须重新校验 schema digest 和真实请求行为。
- 纯 secret/freeform-only 请求缺真实模型证据；不得把协议单测表述成模型端已验证。
- 当前只完成隔离验证，未计入 P3 的 50 terminal tasks、72 小时或 2 次实例轮换。
- 生产 rollout 前仍需目标环境 owner、监控、回滚窗口和 release owner 签收。

## Recommended Next Skills

- 当前实现可进入 `foggy-test-coverage-audit`，并按隔离范围执行 `foggy-acceptance-signoff`。
- 若后续 CLI 改变 request schema 或生产发现重复执行，转入 `foggy-bug-regression-workflow`。

## Decision

- decision: ready-for-coverage-audit-with-risks
- decision_scope: OPT-003 isolated interactive input
- isolated_experience: ready-for-acceptance-review
- production_routing_change: no
- production_enablement: not-approved
- follow_up_required: yes
