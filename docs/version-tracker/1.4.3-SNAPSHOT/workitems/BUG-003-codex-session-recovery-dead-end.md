---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-003
status: ULTRA_EXECUTING
canonical: true
execution_mode: ultra
approved_by: Project Owner
approved_at: 2026-07-19
open_questions: []
---

# Delivery Spec: Codex App Server 会话加载、终止与继续死局

## Document Purpose

- intended_for: ultra-implementation / independent-signoff
- purpose: 固定用户已确认的会话可见性、终止恢复和继续语义，以及部署核验边界。
- canonical_path: docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-003-codex-session-recovery-dead-end.md

## Goal

- version_goal: 保证 Codex App Server 会话在消息历史较大、取消未确认或 Worker 已终止的情况下都有安全、可理解且可执行的恢复路径。
- target_outcome: 用户始终能查看已持久化消息；Worker 仍在执行时能看到并使用与真实状态一致的终止入口；Worker 已终止并完成服务端对账后能继续会话，不再陷入“不能中止也不能继续”。

## Scope

- in_scope:
  - 大历史会话的前端首屏加载、重试/降级与单条消息解析隔离，不因一次响应截断或一条异常消息清空整个消息列表。
  - `RUNNING`、`AWAITING_PERMISSION`、`AWAITING_INPUT`、`CANCEL_REQUESTED` 及 Worker 已终止场景的服务端权威恢复状态和前端操作入口。
  - 取消接口仅表示请求已受理时，前端刷新并展示服务端真实状态，不乐观写入 `ABORTED`。
  - Worker 已终止时，将逻辑任务安全对账到终态；若原生 turn 仍占用会话，则衔接既有 stale-turn cleanup，完成后允许继续。
  - exact task/runtime/thread/turn 绑定下的终止重试或状态重查，以及对应自动化回归。
  - `dev-kvm-jdk17.foggysource.com` 的前后端制品/进程核验和实际承载 Worker 的版本、健康与生命周期 smoke 证据。
- affected_modules: `packages/navigator-frontend`、`addons/codex-worker-agent`；仅当代码级根因要求时修改 `tools/codex-app-server-worker`。
- external_dependencies: dev Navigator、任务绑定的 Codex App Server Worker；部署前需要 dev 主机具备可用磁盘空间。

## Non-Goals

- out_of_scope: 强杀共享 app-server 进程、绕过签名终止能力、直接修改数据库任务状态、改变 session/thread 身份、清理远端磁盘、扩展 `start-all.sh` 的 Worker 所有权范围。
- do_not_touch: 用户现有 GOV-001 未提交变更、其他工作区、无 exact task/runtime/thread/turn 绑定的进程或 turn、任何明文凭据。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| 浏览器以服务端权威任务/恢复状态决定可用动作 | 前端仅凭本地状态会把取消请求误报成终态 | 保留现有 API 路径时也必须刷新服务端状态；不得伪造 `ABORTED` |
| `CANCEL_REQUESTED` 是不可继续但必须可恢复的过渡态 | Worker 终止尚未确认，直接 resume 会被 B600 拒绝 | 必须提供状态重查、终止恢复或明确等待入口，直到获得 Worker 终态证据 |
| Worker 终态证据先于逻辑任务终态和继续 | 防止仍运行的 turn 与新 turn 并发 | 复用既有 reconnect/stale-turn cleanup 语义，保持 fail-closed |
| 消息首屏使用有界分页，并允许自动降级与显式重试 | 现场 4565 条消息、`limit=800` 响应约 6.88 MB 且出现截断 | 不改变消息持久化格式；已成功加载的消息不得因后续失败被清空 |
| 单条持久化消息转换失败不能使整个会话不可见 | 历史兼容或异常事件应局部降级 | 保留可诊断占位/日志，不能静默丢失整个列表 |
| 终止恢复只作用于 exact bound task/runtime/thread/turn | 避免误杀共享 runtime 或其他会话 | 缺失、冲突或过期绑定必须 fail closed，不允许 PID/端口猜测 |
| Worker 是否需要发布由实际改动面决定 | Java/前端修复本身不要求 Worker 发版 | 若修改 app-server Worker，必须按 Worker 发布流程完成版本、安装和 full smoke |

## Acceptance Criteria

- [x] AC-1: 现场规模的大历史会话可稳定打开并显示已持久化消息；初始大页失败时自动以更小页重试，单条异常消息不使整个列表失败，用户可显式重试失败部分。
- [x] AC-2: 所有可取消的活跃状态都有清晰的终止入口；`CANCEL_REQUESTED` 显示真实的请求中/未确认状态及可用恢复动作，不显示虚假的“已中止”。
- [x] AC-3: 调用取消 API 后，前端不直接写入 `ABORTED`，而是重新获取服务端任务状态；仅 Worker 终态证据可使任务进入相应终态。
- [x] AC-4: Worker 仍在执行时，用户能安全重查或恢复 exact-task 终止流程；重复操作保持幂等，不创建并发终止操作或影响其他任务。
- [x] AC-5: Worker 已停止时，服务端能将 `CANCEL_REQUESTED` 等非终态任务对账到真实终态；如原生 turn 仍活跃，则 cleanup 完成后 resume 可成功，不再返回“该会话正在运行任务”。
- [x] AC-6: resume eligibility 与 UI 输入状态覆盖 `CANCEL_REQUESTED`、终止未确认、终态待 cleanup 和可继续四类状态，并与后端实际接受条件一致。
- [x] AC-7: 自动化回归覆盖大历史加载降级、单条消息隔离、取消后的权威状态刷新、`CANCEL_REQUESTED` 操作入口、Worker active/terminal 两条恢复路径和 exact-binding fail-closed。
- [ ] AC-8: dev 部署证据能分别证明 source HEAD、前端 dist、Java 制品/进程和实际承载 Worker 的版本/健康；现场会话完成打开、终止恢复、继续的体验 smoke。

## Contract / Data / Security Constraints

- API or event contract: 优先扩展现有任务恢复/eligibility 响应或增加兼容的新查询/动作；已有调用方不得因字段增加而破坏。取消响应不得再被前端解释为终态确认。
- data and migration: 默认无数据库迁移；如实现需要持久化新的恢复状态，必须转 `NEEDS_REPLAN` 说明迁移与回滚。
- compatibility and rollback: 允许前端对旧后端安全降级为只读状态提示；回滚不能恢复乐观 `ABORTED` 或绕过终态确认。
- permissions and secrets: 沿用现有会话、任务和 Worker 权限；终止能力必须签名、限时、单任务绑定。用户提供的 Bearer Token 不得写入代码、文档、日志或测试产物。

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| AC-1/AC-7 | major | 前端单元测试 + `bash scripts/build-frontend.sh` + 现场规模 mock/fixture | 精确命令、分页/降级断言、构建结果 |
| AC-2/AC-3/AC-6 | major | 前端组件/状态测试 + Playwright 或等价浏览器体验检查 | 操作可见性、取消后真实状态、输入禁用/启用证据 |
| AC-4/AC-5/AC-7 | critical | `CodexTaskService`/Controller 集成测试，覆盖 active、terminal、重复请求、绑定冲突 | 精确 Maven 命令、断言结果、未确认语义 |
| AC-8 | critical | dev 前后端健康检查、制品时间/SHA、实际 Worker health/version、真实或安全等价 lifecycle smoke | 脱敏命令结果；环境阻塞必须明确记录 |

## Bug Context

- bug_source: user-report
- severity: critical
- environment: `dev-kvm-jdk17.foggysource.com`，Codex App Server 会话 `5a70a72a-dcc7-4343-be0d-ca81f67df287`，逻辑任务 `20260719-77a3`。
- current_behavior: 消息接口存在间歇性截断，前端统一显示“消息加载失败”；任务为 `CANCEL_REQUESTED / TERMINATION_UNCONFIRMED` 时无可靠终止恢复入口，resume 返回 B600，stale cleanup 又因任务非终态拒绝。
- expected_behavior: 历史消息可见；Worker active 时可安全终止/重查；Worker terminal 时可对账、cleanup 并继续。
- reproduction_steps:
  1. 打开具有数千条消息的 App Server 会话。
  2. 观察首屏一次请求大量消息，传输或转换失败后整个列表不可用。
  3. 对未确认终止的任务调用 resume，得到 B600；查询 stale cleanup 得到 `STALE_TURN_CLEANUP_TASK_NOT_TERMINAL`。
  4. 前端在 `CANCEL_REQUESTED` 下缺少与真实状态一致的终止/恢复动作。
- reproduction_status: confirmed
- existing_evidence: 会话共 4565 条持久化消息；`limit=800` 响应约 6.88 MB，实测出现 `curl: (18) transfer closed with outstanding read data remaining`；任务投影为 `CANCEL_REQUESTED / TERMINATION_UNCONFIRMED`；远端 source HEAD 为 `43de9792`。dev 根文件系统曾观测到 100% 满，2026-07-19 再次只读核验时已恢复为 89% 使用率、约 11 GB 可用。
- existing_tests: 既有 stale resume、stale turn cleanup、App Server terminal reconciliation 和终止超时分类测试未覆盖该跨层死局。
- regression_protection: required
- waiver_reason_and_risk: N/A

## Risks and Open Questions

- known_risks:
  - 实际承载 Worker 位于注册地址 `192.168.31.119:3033`，不是 dev-kvm 的 `start-all.sh` 进程；必须按命令行与工作区确认归属后才能升级或重启。
  - dev-kvm 根文件系统曾满，当前已恢复空间但仍需在部署前复核；不得无授权清理。
  - 现场任务可能已经漂移，体验 smoke 必须记录绝对时间和当时权威状态。
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md`、相关模块规范和 `codex-worker-deploy`、`webapp-testing` 专项技能。
- 在 scope 内自主决定具体文件、类和实现结构。
- 如需改变目标、范围、已确认决策、兼容或安全边界，设置 `NEEDS_REPLAN` 并停止扩展。
- 对可稳定复现的链路先建立失败回归，再修复并运行通过。
- 运行与改动面匹配的验证，不得声称未实际运行的测试通过。
- 完成后填写 `Implementation Result`，并将状态改为 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

> 由 Ultra 执行会话填写。

- implementation_summary:
  - 历史消息首屏从固定 800 条改为 100/50/20 自动降级；单条转换失败隔离；传输失败提供显式重试；加载完整历史失败时保留当前可见消息。
  - `CANCEL_REQUESTED` 在会话页、侧栏和分支列表中提供“重查状态/再次中止”，且输入保持禁用，避免与未确认 turn 并发。
  - 取消后立即刷新服务端权威任务投影，不再由前端乐观写入 `ABORTED`。
  - 后端允许 `CANCEL_REQUESTED` 重新进入 exact-task 取消流程；过期未确认终止操作会被收敛为失败并释放重试锁，非过期操作仍保持幂等保护。
- changed_paths:
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexTaskServiceTest.java`
  - `session-module/src/main/java/com/foggy/navigator/session/service/TerminationOperationService.java`
  - `session-module/src/test/java/com/foggy/navigator/session/service/TerminationOperationServiceTest.java`
  - `packages/navigator-frontend/src/composables/useTaskPane.ts`
  - `packages/navigator-frontend/src/composables/useClaudeWorker.ts`
  - `packages/navigator-frontend/src/components/worker/TaskPane.vue`
  - `packages/navigator-frontend/src/components/worker/taskPaneResume.ts`
  - `packages/navigator-frontend/src/views/ClaudeWorkerView.vue`
  - `packages/navigator-frontend/src/types/index.ts`
  - 对应前端测试文件及本交付文档/版本索引。
- tests_and_results:
  - `mvn test -pl addons/codex-worker-agent,session-module -am -Dtest=CodexTaskServiceTest,TerminationOperationServiceTest -Dsurefire.failIfNoSpecifiedTests=false`：通过；`TerminationOperationServiceTest` 10/10，`CodexTaskServiceTest` 128/128。
  - `bash scripts/build-frontend.sh`：通过；类型检查、工作区前端测试与生产构建均成功；Navigator frontend 24 个测试文件、267 个测试通过。
- manual_or_experience_evidence:
  - 现场 API 只读复现确认 4565 条消息、大响应间歇截断和 `CANCEL_REQUESTED / TERMINATION_UNCONFIRMED` 死局。
  - dev 只读核验：source HEAD 仍为 `43de9792`；Java 制品、前端 dist 和 Java 进程均为 2026-07-18 旧部署，本修复尚未部署。
  - 实际 Worker 地址为 `192.168.31.119:3033`；SSH `sa@192.168.31.119:2233` 拒绝当前公钥，因此未能取得其命令行、工作目录和版本证据。此次代码未修改 Worker 包。
- deviations: none
- residual_risks:
  - AC-8 尚未完成：需部署 Navigator 前后端到 dev 后，以现场会话执行打开、重查/再次中止、终态 cleanup 和继续 smoke。
  - 实际 Worker 的只读版本/健康证据受 SSH 凭据阻塞；部署/重启前仍需完成进程归属确认。
- readiness: ULTRA_EXECUTING

## References

- requirement / issue: 用户于 2026-07-19 提供的 dev 截图、resume 与 stale-turn-cleanup API 复现。
- architecture / glossary: Codex task lifecycle、authorized termination operation、App Server reconnect/stale-turn cleanup。
- related work items: `1.4.2-SNAPSHOT` BUG-017/BUG-018；`1.4.3-SNAPSHOT` BUG-001。
