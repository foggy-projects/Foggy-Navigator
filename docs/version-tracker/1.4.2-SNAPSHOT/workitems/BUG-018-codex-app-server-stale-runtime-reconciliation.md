---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.2-SNAPSHOT
ticket: BUG-018
status: ULTRA_EXECUTING
canonical: true
execution_mode: ultra
approved_by: repository owner
approved_at: 2026-07-18
open_questions: []
---

# Delivery Spec: Codex App Server 终态对账与可恢复入口

## Goal

- target_outcome: App Server 的手动“重新查询任务状态”先以绑定运行时的任务状态为证据进行终态对账；`STATE_SYNC/reconnect_pending` 在会话中呈现为可操作的恢复卡片，而不是不可操作的原始状态码。

## Scope

- in_scope: `addons/codex-worker-agent` 的 App Server 手动重连预对账；`packages/foggy-chat-core` 对 `STATE_SYNC` 恢复字段的保留；`packages/foggy-chat` 的恢复卡片、文案和回归测试；版本工作项索引。
- affected_modules: `addons/codex-worker-agent`、`packages/foggy-chat-core`、`packages/foggy-chat`。

## Non-Goals

- out_of_scope: SDK Worker 的 resume guard（BUG-017）；仅凭进程空快照自动清理；自动 kill、取消或删除任务；App Server Worker 发布、重启、提交、推送或生产数据修复。
- do_not_touch: BUG-017 的现有未提交实现、GOV-004 终止契约、浏览器传入的 task/thread/runtime 身份。

## Confirmed Decisions

| Decision | Rationale | Constraint |
|---|---|---|
| 手动重连先查询持久绑定 App Server 的 `GET /tasks/{id}/status` | 远端明确 `failed`/`aborted` 是可验证终态，避免无谓 SSE 重连 | 只使用服务端持久的任务、runtime 和 worker task id |
| `completed` 没有结果正文时仍不直接写成功 | 状态端点不能证明完整最终结果 | 继续重放 durable SSE，保留 fail-closed |
| transport error、未知/非终态、畸形或身份不匹配响应均保持可恢复 | 不把缺失或不确定信息当作清理依据 | 不自动结束本地任务 |
| `reconnect_pending` 呈现“重新查询任务状态” CTA | 用户需要能恢复，而非阅读原始代码 | 只有携带 taskId 才展示可点击按钮 |

## Acceptance Criteria

- [ ] AC-1: App Server 手动重连在订阅 SSE 前查询已绑定的远端任务状态；远端 `aborted` 或 `failed` 时写入对应本地终态，且不再订阅。
- [ ] AC-2: 远端 `completed` 但 status 无结果正文时不伪造成功；非终态、异常、无响应或 task id 不匹配时继续保持本地活跃与恢复路径。
- [ ] AC-3: `STATE_SYNC` 的 `reconnect_pending` 保留 `raw.taskId` 与 `reconnectable`，渲染为错误说明卡片与“重新查询任务状态”按钮；点击仍沿用既有 taskId 重连事件。
- [ ] AC-4: 普通 ERROR 重连按钮文案和其他 STATE_SYNC（审批、等待、压缩、普通状态）不回归。
- [ ] AC-5: 定向 Java/前端测试、类型检查或构建实际执行，命令和结果记录在本工作项。

## Contract / Security Constraints

- API or event contract: 不新增浏览器可伪造的远端 task/thread 参数；复用现有统一 reconnect API 和 `STATE_SYNC` payload。
- data and migration: 无 schema/migration；只在远端返回明确 `failed`/`aborted` 时收敛当前任务投影。
- compatibility and rollback: 回滚本切片代码即可恢复旧行为；不得反向伪造已确认的 Provider 终态。
- permissions and secrets: 不记录 token、API key、完整 Worker 命令或用户输入。

## Test and Evidence Obligations

| Item | Required Validation |
|---|---|
| App Server terminal preflight | `CodexStreamRelayTest` 覆盖 aborted/failed 与非终态 fail-closed 行为 |
| Recovery UX | `MessageList`/`ErrorBlock` 回归覆盖可见 CTA、taskId 和既有按钮文案 |
| Integration compatibility | 受影响 Maven module 测试及前端定向测试、typecheck/build |
| Hygiene | `git diff --check`，不提交、不推送、不发布 |

## Risks

- status 为 `completed` 仍必须依赖 SSE 最终结果，短暂不可确认状态会保留到重放成功或用户再次重试。
- 本地单元测试不能代替目标环境 Worker 和 UI 的人工复验。

## Implementation Result

- implementation_summary: App Server 的 `reconnectTask` 在已恢复 `workerTaskId` 后、订阅 SSE 前查询绑定运行时任务状态；明确 `aborted`/`failed` 复用既有受控终态写入，`completed` 保持等待最终 SSE。聊天状态保留 `STATE_SYNC/reconnect_pending` 的 `raw` 与 `reconnectable`，消息列表用错误说明卡片呈现，并以“重新查询任务状态”触发既有 reconnect 事件。
- changed_paths:
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexStreamRelay.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexStreamRelayTest.java`
  - `packages/foggy-chat-core/src/store/chatState.ts`
  - `packages/foggy-chat/src/components/ErrorBlock.vue`
  - `packages/foggy-chat/src/components/MessageList.vue`
  - `packages/foggy-chat/src/__tests__/interactionCards.test.ts`
- tests_and_results:
  - `mvn -B -pl addons/codex-worker-agent -am -Dtest=CodexStreamRelayTest -Dsurefire.failIfNoSpecifiedTests=false test`: passed, `CodexStreamRelayTest` 44/44; reactor 8/8 SUCCESS (2026-07-18).
  - 初始新增回归在实现前按预期失败：未调用 `getTaskStatus` 而直接 `subscribeToTask`；实施后定向测试通过。
  - 前端定向 Vitest、typecheck/build: not run. 当前 Linux shell 是 Node `v18.19.1` 且无 pnpm；发现的 Windows Node 为 `v22.22.0`，低于仓库要求 `>=22.23.1 <23`，未使用不合规运行时。
- manual_or_experience_evidence: 源码审查确认当前 `ClaudeWorkerView` 的既有 reconnect 事件会以 `raw.taskId` 路由；本切片不修改该处已有未提交文件。
- deviations: 前端验证因缺少符合版本约束的 Node/pnpm 环境尚未执行；未部署、未重启、未提交、未推送、未发布。
- residual_risks:
  - 远端 `completed` 缺少结果正文时仍保守停留在可恢复态，依赖 SSE 重放或再次重连。
  - 需要在 Node `22.23.1` 与 pnpm `10.34.5` 环境执行前端测试与 `bash scripts/build-frontend.sh` 后，才能进入 `READY_FOR_SIGNOFF`。
- readiness: ULTRA_EXECUTING
