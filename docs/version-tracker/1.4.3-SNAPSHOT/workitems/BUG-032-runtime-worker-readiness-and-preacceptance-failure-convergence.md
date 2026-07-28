---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-032
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
assurance_level: elevated
approved_by: project-owner-user-confirmed
approved_at: 2026-07-28
implementation_authorized: true
runtime_validation_authorized: true
open_questions: []
---

# Delivery Spec: Runtime Worker readiness and pre-acceptance failure convergence

## Goal

解除 Foggy Navigator issue #153 剩余的本机 SIM fixture 联调阻断：

- readiness/owner-smoke 不得在选定 physical Worker 或实际 execution role 不可用时返回成功。
- Worker 尚未接受 Task 前发生的稳定连接失败必须收敛为可诊断的可信终态，不得留下无限 `RUNNING`、零 dispatch 和仍有效的 task token。
- Worker 可用时，readiness 通过后的 fixture-only Task 能进入实际 Worker/runtime/model dispatch 并到达可信终态。

## Scope

- `OpenApiAgentReadinessService` 对 exact physical Worker 与 execution role 的运行可用性进行 fail-closed 检查和真实连接探测。
- Codex SDK Worker 在 remote task acceptance 之前发生的连接/stream failure 使用稳定错误码并成为不可恢复终态。
- 对修复前已出现的“provider 已终态、外层 Task 仍活跃”记录，允许受 owner/worker/dispatch 约束的 reconcile 重投影既有 definitive terminal fact，不向 Worker 重发命令。
- 覆盖 readiness、owner-smoke、token late-bind race 和 Task terminal projection 的 focused/affected tests。
- 在用户授权的本机环境部署 Navigator，核验并重启 Ubuntu-24.04 中 SIM 专用的 3131/3151 Worker，收敛旧 fixture Task，并重跑一个新的 fixture-only Task。
- 更新本 workitem 的 changed paths、验证结果、运行证据和残余风险。

## Non-Goals

- 不新增独立 task-token revoke API。
- 不改变 Worker 已接受 Task 后的 retry/recovery/resync 语义。
- 不访问真实 TMS 或 SIM 业务数据；live 验收仅使用 SYSTEM_FAMILIARIZATION fixture。
- 不升级或操作当前工作 WSL 的 `/home/sa/.codex-worker`、`/home/sa/.claude-worker`。
- 不发布新的 Worker 版本，除非后续证据证明服务端修复与现有目标 Worker 不兼容。
- 不扩大 external/production enablement。

## Confirmed Decisions

| Decision | Constraint |
|---|---|
| 角色配置存在与运行可用性分开报告 | `WORKER_HOST_ROLE_ROUTING` 只证明路由；`WORKER_RUNTIME_AVAILABILITY` 证明实际可用性 |
| readiness 使用 exact selected Worker 和 effective model 做真实 backend probe | 探测失败返回稳定、脱敏、可操作的 fail check |
| SDK Worker 在 `workerTaskId` 尚未建立前的 stream 连接失败为 definitive terminal | 已被 Worker 接受后的断流仍保留现有可恢复语义 |
| terminal tombstone 必须阻止晚到 task-token bind 重新注册能力 | 不引入新 lifecycle API 或独立状态机 |
| 旧 pre-acceptance provider 终态可由 reconcile 重投影 | 只补发已持久化的 definitive terminal event，不创建 Task、不触发 Worker/model dispatch |
| 本机恢复只操作已核验归属的 Ubuntu-24.04 3131/3151 | 端口、VERSION、`.env`、listener cwd、health 必须联合核验 |

## Acceptance Criteria

- [x] AC-1: selected physical Worker 为 OFFLINE 或 execution endpoint 不可达时，readiness/owner-smoke 在创建 Task 前失败，并包含稳定 `WORKER_RUNTIME_AVAILABILITY` 诊断。
- [x] AC-2: selected Worker 与 execution endpoint 可用时，readiness/owner-smoke 成功且证据与实际 Worker health 一致。
- [x] AC-3: Codex SDK Worker 在 remote acceptance 前连接失败时，Task 收敛到 `FAILED`，稳定错误码为 `CODEX_WORKER_STREAM_FAILED_BEFORE_ACCEPTANCE`，task token 不再 ACTIVE，active registration 不再保留。
- [x] AC-4: Worker 已接受 Task 后的可恢复断流行为不因本修复改变。
- [x] AC-5: 一个新的 SIM fixture-only SYSTEM_FAMILIARIZATION Task 被 Worker 接单并到达可信终态，或在 Worker 不可用时由 AC-1 提前拒绝；不得再次出现无限 `RUNNING` 且零 dispatch。

## Validation Obligations

- Focused:
  - `OpenApiAgentReadinessServiceTest`
  - `CodexTaskServiceTest`
  - `CodexStreamRelayTest`
  - Open API task-token terminal late-bind regression
- Affected:
  - `mvn test -pl addons/claude-worker-agent -am`
  - Codex Worker addon affected tests through the same reactor
  - `git diff --check` and scoped secret scan
- Runtime:
  - clean build/deploy and `/actuator/info` exact commit/dirty/build metadata
  - exact 3131/3151 ownership and health
  - readiness + owner-smoke
  - old Task closure evidence
  - one new fixture-only Task audit/terminal evidence

## Authorization and Safety

- Project owner explicitly authorized implementation, deployment, SIM-dedicated Worker restart/update if necessary, stale fixture Task termination/reconciliation, and fixture reruns without repeated confirmation.
- No credentials, token material, prompt/model output, business payload, or sensitive filesystem details may enter tracked evidence.
- The implementation session may only mark this workitem `READY_FOR_SIGNOFF`; independent acceptance remains separate.

## Implementation Record

- changed_paths:
  - `addons/claude-worker-agent/.../OpenApiAgentReadinessService.java`
  - `addons/claude-worker-agent/.../OpenApiAgentReadinessServiceTest.java`
  - `addons/codex-worker-agent/.../CodexTaskService.java`
  - `addons/codex-worker-agent/.../CodexTaskServiceTest.java`
  - 本 workitem 与版本索引
- focused_tests:
  - `mvn -pl addons/claude-worker-agent,addons/codex-worker-agent -am -Dtest=OpenApiAgentReadinessServiceTest,CodexTaskServiceTest,CodexStreamRelayTest,BusinessTaskScopedTokenLifecycleServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`
  - PASS: readiness 26、token lifecycle 34、Codex stream 46、Codex task 146；合计 252，0 failures/errors/skips。
  - `mvn -pl addons/codex-worker-agent -am -Dtest=CodexTaskServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`
  - PASS after legacy terminal-projection reconciliation coverage: Codex task 147，0 failures/errors/skips。
- affected_tests:
  - `mvn test -pl addons/claude-worker-agent,addons/codex-worker-agent -am`
  - PASS: affected reactor 全部成功；直接受影响模块 `claude-worker-agent` 437 tests、`codex-worker-agent` 491 tests，均 0 failures/errors/skips。
- runtime_evidence:
  - Navigator clean deployment `c1978c3d648a09f6be5f12f8079b98839bd64e0a`：`/actuator/info` commit 精确匹配、`dirty=false`、build version/time 非空，health `UP`。
  - Ubuntu-24.04 目标实例经 `VERSION`、`.env` 端口、listener cwd 与 health 联合核验：3131 Claude Worker `0.1.3`、3151 Codex SDK Worker `1.0.25`；未操作当前工作 WSL 的独立 Worker。
  - Worker 离线时 readiness 以 `WORKER_RUNTIME_AVAILABILITY=FAIL`、`WORKER_RUNTIME_UNAVAILABLE` 提前拒绝；目标 Worker heartbeat/health 恢复后，同一检查返回 `OK`，owner-smoke 返回 `ready`，selected physical Worker `ddc45293` 为 `ONLINE`。
  - 修复前遗留 Task `20260728-6170` 通过受约束 reconcile 重投影既有 definitive provider terminal fact：最终 `FAILED`、token `REVOKED`、active registration `false`、`dispatchCount=0`，未重新触发 Worker/model dispatch。
  - 新 SIM fixture `sim153-bug032-20260728-122128` 关联 Navigator Task `20260728-3b68`：最终 `COMPLETED`，runtime/model dispatch 均成功，`dispatchCount=1`、`retryCount=0`、`recoveryCount=0`、token `REVOKED`、active registration `false`；未派发 BusinessFunction，未访问真实 TMS 数据。
- deviations: none
- residual_risks: Worker heartbeat 状态可能在调度周期内短暂滞后；readiness 的 exact backend probe 仍会在受 10 秒上限保护的请求内 fail closed。现有目标 Worker 版本与服务端修复兼容，因此未执行 Worker 升级。
