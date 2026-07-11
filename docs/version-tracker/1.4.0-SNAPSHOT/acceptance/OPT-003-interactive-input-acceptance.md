---
acceptance_scope: feature
version: 1.4.0-SNAPSHOT
target: OPT-003 Codex app-server interactive input
doc_role: acceptance-record
status: signed-off
decision: accepted-with-risks
production_enablement: not-approved
signed_off_by: Codex
signed_off_at: 2026-07-11
production_signed_off_by: null
production_signed_off_at: null
reviewed_by: Codex
reviewed_at: 2026-07-11
blocking_items: []
follow_up_required: yes
evidence_count: 2
---

# Feature Acceptance

## Background

本记录签收 OPT-003 在隔离环境中的原生输入、防重复执行、SSE 重连恢复和 PC 体验。它不批准 Ultra 生产路由，也不改变旧 SDK Worker 的设计。

## Acceptance Basis

- [Workitem](../workitems/OPT-003-codex-app-server-interactive-input.md)
- [Implementation quality gate](../quality/OPT-003-interactive-input-implementation-quality.md)
- [Coverage audit](../coverage/OPT-003-interactive-input-coverage-audit.md)
- [Worker/live evidence](../evidence/OPT-003-ultra-native-input-v1.json)
- [PC evidence](../evidence/OPT-003-pc-interactive-input-v1.json)

### Decision Boundary

- 签收固定 CLI `0.144.1`、Worker `0.2.0`、隔离 runtime revision 3 和当前 PC 实现。
- 签收“断流只重连/同步，活动 turn 禁止 resume，终态后才允许 resume”的端到端语义。
- 不把纯 secret/freeform-only 的协议测试升级为真实模型验证。
- 不计入 P3 生产样本，不批准 P3-P6。

## Checklist

- [x] 原生单选在 PC 展示，输入 `1` 后同一 turn 完成。
- [x] 多问题使用结构化答案并在同一 turn 完成；`isOther`/free-text wire shape 有协议与 UI 覆盖。
- [x] 同一 request 只允许一次成功回复，重复或 stale 回复稳定拒绝。
- [x] `RUNNING/AWAITING_INPUT` 的 resume 在 Java 与 Worker fail closed，不创建第二个 task/turn。
- [x] SSE 断流、浏览器关闭和刷新不改变任务状态，重开后恢复同一 pending card。
- [x] runtime/revision/instance/thread/turn/request affinity 严格校验。
- [x] Worker 重启丢失原 request channel 后明确失败，答案不缓存重放。
- [x] command/file approval 和未知 server request 继续拒绝。
- [x] desktop/320px 无横向溢出、卡片/抽屉重叠或浏览器错误。
- [x] secret 答案不持久化、不进入事件或日志；原始 request、token、endpoint 和 reasoning 不进入 UI/SSE。

## Evidence

- Worker `215 total / 208 passed / 7 platform-skipped / 0 failed`；release archive SHA-256 `03949845DE8C405E1CC679D5DE5FB7F2AE86734C13C16E63102BC150A003343E`。
- Session `306/306`、Codex addon `276/276`、Claude normalization `2/2`。
- foggy-chat `100/100`、Navigator `196/196`、Mobile `40/40`，相关构建均通过。
- 直连重启任务进入 `USER_INPUT_CHANNEL_LOST`，旧答案返回 `USER_INPUT_NOT_PENDING`，同 thread 在终态后新 turn 正常完成。
- PC 单选任务 `20260711-0847` 结果为 `PC_INPUT_ACCEPTED:Alpha`。
- PC 多问题任务 `20260711-b814` 刷新恢复后结果为 `PC_MULTI_ACCEPTED:Staging:Quick`；活动态误发 `continue` 前后任务数均为 `1`。
- Durable records: [Worker/live](../evidence/OPT-003-ultra-native-input-v1.json), [PC](../evidence/OPT-003-pc-interactive-input-v1.json).

## Failed Items

- OPT-003 isolated core scope: none.
- 纯 secret/freeform-only 未获得当前模型的真实请求，不作为已经通过的 live item；协议/安全自动化通过并作为开放风险保留。

## Risks / Open Items

- Codex app-server interactive request 仍是 experimental；CLI 升级必须重新做 schema、恢复和 live 验证。
- 纯 secret/freeform-only 请求需在 CLI/model 能稳定生成后补真实闭环。
- Windows 生命周期 settle 曾出现一次测试时序波动；最终 full/release 均通过，生产 canary 仍需持续观察。
- P3 生产门禁和外部样本未开始，`production_enablement` 保持 `not-approved`。

## Final Decision

OPT-003 的隔离核心交互已签收：SSE 断流使用重连/status/snapshot 同步，误发 `continue` 会被活动态 guard 拒绝且不创建重复任务；用户回复选项后，原 turn 可继续完成。已知 experimental 和纯 secret/freeform live gap 不阻断当前固定 CLI 的隔离体验，但必须保留为后续风险。生产路由仍未批准。

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- acceptance_record: docs/version-tracker/1.4.0-SNAPSHOT/acceptance/OPT-003-interactive-input-acceptance.md
- isolated_experience: accepted
- blocking_items: none
- production_enablement: not-approved
- P3_entry: not-approved
- signed_off_by: Codex
- signed_off_at: 2026-07-11
- follow_up_required: yes
