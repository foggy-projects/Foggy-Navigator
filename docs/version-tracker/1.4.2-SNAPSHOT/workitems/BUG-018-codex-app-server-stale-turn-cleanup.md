---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.2-SNAPSHOT
ticket: BUG-018
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
approved_by: repository owner
approved_at: 2026-07-18
open_questions: []
---

# Delivery Spec: Codex App Server 遗留 Native Turn 清理

## Document Purpose

- intended_for: ultra-implementation / independent-signoff
- purpose: 固定逻辑 Task 已终态、但同一 Codex App Server native turn 仍 active 而阻塞“继续”时的显式、精确、可审计恢复路径。
- canonical_path: `docs/version-tracker/1.4.2-SNAPSHOT/workitems/BUG-018-codex-app-server-stale-turn-cleanup.md`

## Goal

- version_goal: 让受影响的已终态 App Server 会话可在不杀共享 app-server child 的前提下恢复继续。
- target_outcome: 已授权用户可对该 Task 已持久化的精确 `thread_id + turn_id` 发起一次受签名的清理；仅在 Worker 实际观察到该 turn 已终态或已中断后，页面才提示可以再次继续同一 Thread。

## Scope

- in_scope: Java Codex 控制面与一次性 termination operation、Codex App Server Worker 的精确 native-turn probe/interrupt、任务面板的恢复操作、回归测试和版本记录。
- affected_modules: `addons/codex-worker-agent`、`session-module` 的 operation service、`tools/codex-app-server-worker`、`packages/navigator-frontend`。
- external_dependencies: 固定的 Codex app-server `thread/read` 与 `turn/interrupt` 协议；不假设或调用未验证的 CLI/PID 行为。

## Non-Goals

- out_of_scope: 通用 PID 杀进程、自动/定时清理、清除未知或不匹配的 turn、SDK Worker 行为、改变 session/thread 绑定、发布或重启任何 Worker。
- do_not_touch: 不修改 `~/.codex-app-server-worker`、本机运行实例或其 `.env`；不把本项与当前工作区未提交的 BUG-007/BUG-017 改动合并提交。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| 单独的 `STALE_TURN_INTERRUPT` operation | 逻辑 Task 已终态时既有 cancel/abort 正确返回 no-op，不能复用。 | operation 仍为 HMAC 签名、一次性 receipt 和审计记录；不改变普通 cancel。 |
| 精确 Task/Worker/Thread/Turn 与 lane 绑定 | 防止旧 Task 或其他 Thread 误中断新的工作。 | Java 先验证 owned terminal App Server Task 和 pinned runtime；Worker 以自己的持久 record、原请求和 `thread/read` 作最终权威校验。 |
| Worker 先 read 再 interrupt | `turn/interrupt` 只可作用于精确、仍 active 的 turn。 | exact turn 缺失、状态未知、lane/runtime 不可用、超时或回读无法证明终态均 fail closed，绝不把会话标为可继续。 |
| 不杀共享 child | 一个 app-server child 可承载多个 Thread，PID 终止会伤及无关任务。 | 本项不得调用 process kill、force terminate 或修改 pool lifecycle。 |
| UI 采用服务器派生的资格与结果 | 浏览器不能根据 `ABORTED` 自行推断 native turn 已释放。 | 仅对 owned terminal App Server Task 显示/启用；确认说明仅中断精确遗留 turn，不中止共享运行时；完成后刷新而非乐观放行。 |

## Acceptance Criteria

- [x] AC-1: 已终态的 owned App Server Task 可获得服务器派生的 cleanup eligibility；非 App Server、非终态、缺 pinned runtime/thread 或无权 Task 被拒绝。
- [x] AC-2: Java 为每次尝试创建并签发一次性 `STALE_TURN_INTERRUPT` operation；Worker、provider task 和 owner/tenant 绑定均可审计，重放被拒绝。
- [x] AC-3: Worker 只对其持久 record 中的 exact `thread_id + turn_id`，且原请求计算出的同一 lane，执行 `thread/read`；不匹配或未知不得调用 interrupt。
- [x] AC-4: 若 exact turn 仍 active，Worker 只发 `turn/interrupt(threadId, turnId)`，并在 bounded reread 中观察到 `completed`、`failed` 或 `interrupted` 后才返回 cleanup success。
- [x] AC-5: exact turn 已终态可作为安全的 no-op success 返回；缺失、其他 turn、未确认状态、runtime/lane 不可用或 timeout 必须是明确失败/待确认，且不使会话可继续。
- [x] AC-6: 该路径不调用 PID/process kill，不关闭或替换 shared app-server child，也不自动修改其他 Task/Thread。
- [x] AC-7: UI 在 eligible terminal App Server Task 上提供“清理遗留运行”确认操作；成功后刷新 Task/eligibility 并提示再点“继续”，失败时展示服务端安全错误且不作成功提示。
- [x] AC-8: Java、Worker 和前端均有针对性自动化回归；前端构建与 Worker test/typecheck/build 实际通过。

## Contract / Data / Security Constraints

- API or event contract: 新增 task-scoped cleanup eligibility/read 和 cleanup POST；Worker 新增仅供已签名 capability 调用的 task-scoped endpoint。响应只能包含稳定状态/错误码及绑定的 Task/operation 标识，不返回 thread 内容、模型输出或 secrets。
- data and migration: 不新增数据库表或 schema migration；复用 `termination_operations` 和 Worker receipt ledger，允许新增 kind/安全结果码。
- compatibility and rollback: 普通 `/cancel`、`/resume`、SDK Worker 和已有 `REMOTE_CANCEL`/`MANUAL_PID_KILL` wire contract 不变；回滚仅移除新 UI/API，不应要求清理持久化 operation 历史。
- permissions and secrets: 以已有 owned Task/tenant scope 作为 UI 发起权限；HMAC token 只在 Java→Worker 内存请求头中使用，日志、返回和文档不得输出 token、thread 内容或运行时凭据。

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| AC-1/2 | critical | Java service/controller and capability tests | owned/terminal gates、operation kind/origin/worker binding、replay/invalid rejection |
| AC-3/4/5/6 | critical | Worker HTTP, TaskManager and executor protocol tests | exact read, active interrupt, terminal reread, mismatch/missing/unknown/unavailable fail-closed, no PID path |
| AC-7 | major | focused frontend unit/integration test and build | visible eligibility, confirmation, success refresh, rejected error behavior |
| AC-8 | major | targeted Maven test, Worker `npm test`/`typecheck`/`build`, `bash scripts/build-frontend.sh`, `git diff --check` | exact commands, exit status and any environment blocker recorded below |

## Bug Context

- bug_source: user-report
- severity: major
- environment: `dev-kvm-jdk17.foggysource.com` 的 App Server Task，逻辑任务已因模型用量耗尽中止，但同一 persisted Thread 的 native turn 仍被 app-server 视作运行中。
- current_behavior: `/api/v1/tasks/resume` 被运行中会话拒绝；前端只在 active logical Task 上提供“中止”，终态 Task 的既有 cancel 又是安全 no-op。
- expected_behavior: 用户显式确认后仅清理匹配的遗留 native turn；成功观察后可在同一 Thread 上继续，未观察成功则保持拒绝继续。
- reproduction_steps: 令 app-server turn 在模型额度耗尽后失去正常 terminal 收口；Navigator Task 落入 terminal；调用继续得到“会话正在运行任务”。
- reproduction_status: confirmed by user report; live target verification is deferred until all changed components are deployed by the authorized operator.
- existing_evidence: 用户提供的 resume response `B600`；现有代码的 terminal no-op guards 与 Worker terminal abort guard。
- existing_tests: `CodexTaskServiceTest`、Worker `http-contract`/reconciliation/executor suites、`ClaudeWorkerView` integration tests。
- regression_protection: required
- waiver_reason_and_risk: 不在本项中接管用户的 live Worker/会话；真实 CLI smoke 由用户在部署后验证，自动化只证明协议与拒绝边界。

## Risks and Open Questions

- known_risks: app-server protocol/status vocabulary 随固定 CLI 升级可能变化；跨版本升级前必须重新执行协议回归。运行时无法读取 exact turn 时必须继续拒绝恢复，即使用户知道它“看起来已死”。
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、根 `AGENTS.md`、`CLAUDE.md`、Worker README 和现有 termination/reconciliation 实现。
- 在 scope 内自主选择精确类、函数和测试组织；不得把 cleanup 扩展为 PID 或自动恢复策略。
- 如需改变 ownership 权限、签名范围、Thread 复用兼容或部署边界，设置 `NEEDS_REPLAN` 并停止扩展。
- 运行与改动面匹配的验证，记录实际命令与结果；不能把未部署的 live 验证写成通过。
- 完成后填写 `Implementation Result` 并将 status 更新为 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

- implementation_summary:
  - 平台新增 cleanup eligibility 与 POST 入口；仅 owned、终态、App Server、pinned runtime 的 Task 可以创建一次性 `STALE_TURN_INTERRUPT` capability，并以 Worker access 与 owner/tenant scope 二次校验。
  - App Server Worker 只从持久化 record 和加密原请求还原 exact `thread_id + turn_id` 与 lane；先 `thread/read`，必要时只 interrupt 该 turn，bounded reread 确认 terminal 后才释放 lease 并返回成功。
  - 页面新增“清理遗留运行”确认操作；只使用服务端 eligibility，成功后强制刷新 Task/eligibility 并提示再点“继续”，409/503 保持不可继续。
- changed_paths:
  - `addons/codex-worker-agent`: `CodexWorkerClient`、`CodexTaskExtensionController`、`CodexTaskService` 及其定向测试。
  - `session-module`: `TerminationOperationService` 及其测试。
  - `tools/codex-app-server-worker`: executor、TaskManager、route、operation model/validation、新 stale-turn cleanup helper 与回归测试。
  - `packages/navigator-frontend`: unified task API、`ClaudeWorkerView` 与 integration test。
  - `docs/version-tracker/1.4.2-SNAPSHOT`: 本 work item 与版本索引。
- tests_and_results:
  - `mvn test -pl session-module,addons/codex-worker-agent -am -Dtest=TerminationOperationServiceTest,CodexWorkerClientTest,CodexTaskExtensionControllerTest,CodexTaskServiceTest -Dsurefire.failIfNoSpecifiedTests=false` — PASS；159 tests，0 failures/errors/skips（session 8；Codex control plane 151）。
  - Node `22.23.1`：`npm test`（`tools/codex-app-server-worker`）— PASS；332 passed、1 skipped、0 failed（含 stale-turn cleanup protocol/lease/HTTP tests）；`npm run typecheck`、`npm run build` — PASS。
  - Node `22.23.1`：`npm run type-check` 与 `npm test -- src/views/__tests__/ClaudeWorkerView.integration.test.ts`（`packages/navigator-frontend`）— PASS；focused 40/40。
  - `bash scripts/build-frontend.sh`（Node `22.23.1`、pnpm `10.34.5`）— PASS；navigator frontend 257、chat 114、mobile 59、widget 31 tests passed，构建通过。
  - `git diff --check` 与 `git diff --cached --check` — PASS。
- manual_or_experience_evidence: not-run；未部署、未触碰用户原会话或 `~/.codex-app-server-worker`。部署后由授权操作者在原错误 Task 上执行“清理遗留运行”，成功后再点“继续”。
- deviations: 无产品/架构范围偏离。验证 shell 初始为 Node 18 且没有 pnpm；切换到项目冻结的 Node `22.23.1` / pnpm `10.34.5` 后全量前端基线通过。
- residual_risks: 原错误会话的 live cleanup/resume 仍待部署后用户验证；app-server CLI 升级前必须重新执行协议回归。未观察到 exact terminal 的任何情况保持 fail-closed 和 lease 保留。
- readiness: READY_FOR_SIGNOFF；不是 ACCEPTED。

## References

- requirement / issue: 2026-07-18 user report of a terminal Navigator Task with an active native App Server turn.
- architecture / glossary: `GOV-004-cli-non-termination-and-lifecycle-observability.md`; `BUG-007-app-server-single-instance-containment.md`.
- related work items: `BUG-017-codex-stale-resume-guard-reconciliation.md` (separate dirty worktree item; do not merge into this change).
