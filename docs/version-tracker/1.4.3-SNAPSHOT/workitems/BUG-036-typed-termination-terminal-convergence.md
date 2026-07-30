---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-036
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
assurance_level: elevated
approved_by: project-owner-user-confirmed
approved_at: 2026-07-30
open_questions: []
---

# Delivery Spec: Typed Termination Terminal Convergence

## Goal

- 修复 typed termination 已返回 `ACCEPTED`、Worker 已 ACK，但 Task 永久停在
  `CANCEL_REQUESTED` 且原 request ID reconciliation 永久返回 `ACCEPTED` 的缺陷。
- `ACCEPTED`、Worker ACK、Worker 文本或局部 receipt 均不得成为 terminal authority。
- 同一 request ID 的只读 reconciliation 最终只能基于 canonical Task terminal 与
  cleanup 事实返回 `TERMINAL`；最终证据缺失时在有界时间后返回 fail-closed
  `AMBIGUOUS`，不得伪造终态、重放 termination 或允许新 request ID 绕过。

## Scope and Non-Goals

- in_scope:
  - Codex SDK Worker 在 termination 早于首个 SDK event/PID binding 时的安全收敛。
  - Navigator canonical terminal event 对 termination receipt 的证据回填。
  - typed request-ID reconciliation 的 canonical terminal/cleanup gate 与收敛超时。
  - focused、affected、Worker package/static 与 launcher reactor 回归测试。
- out_of_scope:
  - 修改或重放历史 Task `20260730-0e01`。
  - 修改 `foggy-world-sim`、访问 TMS 业务数据、发布 SDK/CLI/Worker、push/tag/release。
  - 将 ACK、文本、进程不明或超时本身提升为 terminal evidence。
- data_and_api_compatibility:
  - 无数据库 schema、endpoint、SDK DTO 或 CLI wire shape 变化。
  - 相同 client request ID 的 receipt-backed 幂等与只读语义保持不变。
  - 新增可配置
    `navigator.runtime-audit.termination-convergence-timeout`，默认 `PT5M`。

## Read-Only Incident Diagnosis

- correlation:
  - taskId: `20260730-0e01`
  - physicalWorkerId: `ddc45293`
  - clientRequestId: `34e16ef1-05cd-4e46-8864-6e407c259f60`
  - observed runtime commit: `efbe55262bd3e8a2a207fc6e348ff152bb128594`
- Navigator durable facts:
  - Codex Task 与 Session Task 均为 `CANCEL_REQUESTED`。
  - termination operation 为 `CANCEL_REQUESTED`，dispatch 为 `ACKNOWLEDGED`，
    未记录 observed terminal time。
  - termination request receipt 已完成并记录 `TERMINATION_REQUESTED`；该 receipt 的
    request-completion `terminal` 字段不是 canonical Task terminal。
  - ask/task token 仍为 `ACTIVE`，terminal tombstone 不存在，active registration 仍存在。
  - dispatch/retry/recovery 为 `1/0/0`。
- Worker facts:
  - exact Worker task 为 `cancel_requested`。
  - termination operation 为 `UNCONFIRMED`，结果为
    `CANCEL_DISPATCHED_AWAITING_PROVIDER_OR_PROCESS_EXIT`，attention 为
    `PROCESS_UNVERIFIED`，未记录 PID/completed time。
  - Worker 已结束 SDK execution tracking，但没有产生可信 terminal event。
- No-mutation statement:
  - 历史 Task、termination operation、token、registration 和 SIM evidence 均只读；
    未进行第二次 termination、replay、repair、状态修改或新 request ID。

## Precise Root Cause

1. **Primary Worker race**：termination 在 SDK stream 的首个 event、PID 与
   `processStartedAt` 绑定前到达。旧 `waitForBoundTaskProcessExit` 对缺失绑定立即返回
   `unverified`，Worker 将 operation 固定为 `UNCONFIRMED`；SDK execution 随后结束时
   没有再次执行 settlement，因此只留下 ACK，未产生最终 termination result/event。
2. **Navigator bounded-convergence gap**：Navigator 没有丢失可用的 Worker terminal
   result；本次 Worker 根本没有产生该 result。但 typed reconciliation 只被动读取
   已完成的 accepted receipt，没有“accepted 后长期无 canonical terminal”的期限，
   所以可以永久返回 `ACCEPTED`。
3. **Projection gap**：canonical Task terminal event 只更新原 ask receipt/token，
   没有回填原 termination request receipt 的 observed evidence。
4. **Cleanup is downstream, not the trigger**：token revoke、terminal tombstone 和
   active registration cleanup 依赖 canonical `TaskStatusChangeEvent`。本次没有该事件，
   因此 cleanup 未触发；不是 cleanup 执行后丢失。

结论不是单纯的 reconciliation 显示 BUG，也不是 Navigator 未消费一个已存在的最终
Worker 回执，而是“Worker 首事件前取消竞态 + Navigator 无界 accepted 投影”组成的闭环
缺陷。现有 authority 设计（ACK/文本不得成为终态、tombstone fail-closed）方向正确；
但 termination 生命周期事实分散在 Worker registry、provider stream、两类 Task、
termination operation、request receipt、token/tombstone 多个投影中，缺少统一的有界
收敛 owner，因此联调中一个局部漏写会表现为永久悬挂。

## Confirmed Decisions

| Decision | Behavior |
|---|---|
| 首事件前 cancellation 等待 binding grace | 缺失 PID 不再立即永久 `UNCONFIRMED` |
| SDK execution settled 后重新 settlement | 仅 exact operation 仍有效、execution 不会再派生进程且 fresh Worker process snapshot 为零时，产生 verified terminal |
| 进程扫描不可用或仍有进程 | 保持 `CANCEL_REQUESTED/UNCONFIRMED`，不得猜测终态 |
| canonical terminal event 回填 termination receipt | 写入 `TASK_TERMINATED`、canonical status、token revoke 与 termination evidence stage |
| receipt 不能单独产生 TERMINAL | `ALREADY_TERMINAL` 或 `TASK_TERMINATED` receipt 与 canonical Task 冲突时返回 `AMBIGUOUS` |
| typed TERMINAL 增加 cleanup gate | 同时要求 canonical terminal status、`canonicalTerminal=true`、token `REVOKED`、active registration `false` |
| accepted bounded timeout | 默认五分钟后返回 `AMBIGUOUS`，reason 区分 request 未完成与最终 result 未观察到 |
| reconciliation 保持只读 | 重复查询不调用 provider、不创建 operation、不 repair、不允许新 ID |
| Worker 断连只表示观测不可用 | Worker unreachable、SSE disconnect、query timeout 均不得推导 Task aborted/failed，也不得撤销仍可能运行的 Task token |

## Authority Boundary and Disconnect Semantics

- Worker/runtime 是执行域事实的 authority，负责报告 provider turn、CLI/process、
  exact termination operation 和 verified process-exit 等结构化事实。
- Navigator 是 control-plane/canonical lifecycle 的 authority，负责 request
  idempotency、owner/tenant/physical Worker binding、canonical Session Task、token、
  active registration、receipt 和 cleanup。
- 正常 execution-derived terminal 必须有 Worker/runtime terminal evidence，但不能
  单独形成对外 typed `TERMINAL`；Navigator 还必须完成 exact correlation、canonical
  Task persistence 和 terminal cleanup。管理员 logical close 属于 ARCH-001 延后范围；
  当前普通 task-owner `force` 仍是 provider cancellation，不能冒充独立 canonical
  terminal authority 或 verified Worker process/provider terminal。
- Worker 当前内存 registry 不是跨重启的唯一事实源；可作为 authority 的必须是与
  exact task/operation/Worker 绑定、可校验和可持久重放的结构化 evidence。
- Navigator 已基于可信 evidence 持久化的 canonical terminal 不因 Worker 重启、
  registry 丢失或暂时 unreachable 而重新打开。
- 以下推导明确禁止：
  - `WORKER_UNREACHABLE -> TASK_FAILED`
  - `SSE_DISCONNECTED -> TASK_ABORTED`
  - `WORKER_TASK_NOT_FOUND -> TASK_TERMINAL`（除非另有严格的 never-accepted 或
    exact process-absence 证明）
  - `TERMINATION_ACKNOWLEDGED -> TASK_TERMINAL`
  - `CONVERGENCE_TIMEOUT -> TASK_TERMINAL`
- 观测不可用或证据冲突时保持当前 canonical Task，并将 caller disposition 收敛到
  `AMBIGUOUS`；不得通过新 request ID、自动第二次 termination 或伪造 cleanup 绕过。

## Acceptance Criteria

- [x] `ACCEPTED -> CANCEL_REQUESTED -> TERMINAL` 由真实 verified Worker evidence 推进。
- [x] Worker ACK 后的最终 verified event 能进入既有 Codex stream/task terminal 链路。
- [x] reconciliation 可从 `IN_PROGRESS/ACCEPTED` 收敛到 `TERMINAL`。
- [x] `TERMINAL` 同时要求 canonical status、token revoked、active registration removed。
- [x] Worker 无最终响应时，accepted receipt 超时后 fail-closed 为 `AMBIGUOUS`。
- [x] 重复 reconciliation 不产生第二次 provider termination/reconcile。
- [x] ACCEPTED、ACK、Worker 文本和 receipt 文本均不能单独成为 terminal。
- [x] 历史 Task 保持只读且未被修复逻辑伪造为 terminal。

## Implementation Result

- changed_paths:
  - `tools/codex-agent-worker/src/codex/sdk-wrapper.ts`
  - `tools/codex-agent-worker/src/models.ts`
  - `tools/codex-agent-worker/tests/sdk-wrapper.test.ts`
  - `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/RuntimeRequestAuditProperties.java`
  - `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/RuntimeRequestAuditService.java`
  - `business-agent-module/src/test/java/com/foggy/navigator/business/agent/service/RuntimeRequestAuditServiceTest.java`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/RuntimeTaskClosureService.java`
  - `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/RuntimeTaskTypedContractServiceTest.java`
  - `launcher/.env.example`
  - `launcher/src/main/resources/application.yml`
  - `launcher/src/test/java/com/foggy/navigator/launcher/RuntimeTimeBasisConfigurationTest.java`
- worker_behavior:
  - `sdkExecutionSettled` 明确区分“尚可能启动进程”和“execution 已不可再启动进程”。
  - cancellation 缺失 task process binding 时等待 grace；execution finally 再次 settlement。
  - 只有 fresh zero-process verification 才发布 `ABORTED` terminal event；扫描失败或
    非零保持 unconfirmed。
- navigator_behavior:
  - request snapshot 计算 accepted convergence deadline。
  - canonical Task terminal event 同时回填 ask 与 termination receipts。
  - typed `TERMINAL` 不再信任 receipt outcome，且对 token/registration cleanup
    未完成返回 `AMBIGUOUS`。
- deviations: none
- readiness: `READY_FOR_SIGNOFF`；实现会话未自行标记 `ACCEPTED`。

## Validation

- failure-first:
  - Worker 首事件前 cancellation test 在实现前保持 `cancel_requested`，预期
    `aborted` 失败；实现后通过。
  - typed reconciliation timeout/receipt-authority tests 在实现前分别错误返回
    `ACCEPTED`/`TERMINAL`；实现后通过。
- focused:
  - Worker targeted cancellation tests：`5/5` passed。
  - `RuntimeRequestAuditServiceTest` 与 terminal listener：`38/38` passed。
  - typed closure 与 runtime state audit：全部 passed。
  - Codex stream/task terminal consumer tests：exit `0`。
- affected:
  - `tools/codex-agent-worker`: `npm test`，`256` tests，`254` passed，
    `2` skipped，`0` failed。
  - `tools/codex-agent-worker`: `npm run typecheck` 与 `npm run build`，exit `0`。
  - `mvn test -pl launcher -am`：全部 reactor modules `SUCCESS`，
    `2976` tests、`0` failures、`0` errors、`5` skipped，`BUILD SUCCESS`。
- static: `git diff --check` passed；最终 staged-diff 在本地 commit 前复核。

## Deployment and Residual Risk

- Open SDK/CLI：不需要重新发布；public contract 与
  `navigator-open-sdk:1.0.39-SNAPSHOT` wire shape 未变。
- Navigator：需要以本修复 commit 重新构建并重启，才能启用 receipt backfill、
  cleanup gate 与五分钟 fail-closed timeout。
- Codex SDK Worker：需要从本修复 commit 构建、升级并重启，才能修复首事件前取消竞态。
- 历史 `20260730-0e01` 未做 retroactive repair，部署后也不得用它执行隐式重放；
  SIM 应在 Navigator 与 Worker 均升级后创建新 Task 重跑。
- live SIM/provider smoke 尚未执行，是独立 signoff 前的剩余环境证据。

## Architectural Follow-Up

本修复不通过放宽 terminal authority 来掩盖分散投影。后续宜独立设计一个 durable
termination lifecycle aggregate/outbox，将
`REQUESTED -> DISPATCHED -> ACKNOWLEDGED -> EVIDENCE_OBSERVED ->
CANONICAL_TERMINAL/CLEANUP_COMPLETE` 作为单一幂等 finalizer 管理，并对 stale
accepted operation 进行审计/告警（不得自动 terminal 或自动重放）。该重构超出本 BUG
的兼容修复范围。

统一 lifecycle owner 的第一阶段 MVP 由独立
[ARCH-001 Unified Session and Task Lifecycle Owner](./ARCH-001-unified-session-task-lifecycle-owner.md)
delivery spec 承接。其总体方向仍采用“authoritative facts + deterministic reducer +
durable effects + Worker-scoped Sentinel”的增量
`LEGACY -> SHADOW -> ENFORCED` 路线。2026-07-30 初次独立 review 曾将状态降为
`NEEDS_REPLAN`；Round 7 独立复审现已以 `0 BLOCKER / 0 MAJOR / 0 MINOR` 批准。
第一次审查的 `5 BLOCKER + 5 MAJOR + 1 MINOR` 已建立显式 closure
matrix；第三轮的 F-04/F-05/F-06 已闭合，第四轮独立复审进一步指出 exact
binding/status、accepted query provider Task identity、Worker/Session/Task proof
reference、proof-loss/effect authorization 线性化和内部状态词汇缺口。ARCH-001 现已按
最小兼容/fail-closed 方向冻结：disposition/status 回带并校验
`ownership_mode + safe_binding_digest` 与 durable `never_accepted_proof`，同一
dispatch/body 的 SHADOW/ENFORCED record 不可复用；create/resume 在
`PREPARED` 同一 record 原子分配 provider Task ID；proof 按三类 aggregate 持续持有；
outbox claim 不授权 provider call，loss 与 outbox
`effectState=EFFECT_STARTED` 使用同一 CAS 顺序；
`availability/conflictState` 使用唯一 enum，并冻结 offline/storage/configuration/
proof-loss/state-loss/evidence-conflict 的 precedence 与合法组合。legacy
`ONLINE/SUSPECTED/RECOVERING_RECONCILIATION/STORAGE_PRESSURE_*` 不进入 target
snapshot。同时继续保留 BUG-035 相同 ID 两次 one-shot provider attempt、
receipt-enabled pre-effect admission gate、activation授权边界及 exact
`codex-biz-worker` must-pass。第七轮将 command/status 两入口、cross-mode 两方向以及
并行/晚到 attempt 的失败结果统一冻结为 mode-first：
`409 LIFECYCLE_OWNERSHIP_MODE_MISMATCH`；只有 durable mode exact match 后才允许
generic binding mismatch。ARCH-001 当前为 `APPROVED`，Source Slice 0–8 可按契约顺序
实现；真实 controller/process、首次非 fixture `ENFORCED` aggregate、live SIM、
部署和发布仍需单独授权。ARCH-001 也明确不把历史 Task replay、全 provider 接管、
Session transfer、Worker-generated Physical Worker identity 或真实部署 cutover 并入
本 BUG。

本 BUG 继续只承载已完成的兼容修复和原始 incident evidence；不得因 ARCH-001 获批而
重开、重放或修改历史 Task。
