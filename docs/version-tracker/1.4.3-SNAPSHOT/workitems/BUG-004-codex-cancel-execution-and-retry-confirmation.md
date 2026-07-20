---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-004
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
approved_by: Project Owner
approved_at: 2026-07-19
open_questions: []
---

# Delivery Spec: Codex 真实中止闭环与再次中止状态确认

## Document Purpose

- intended_for: ultra-implementation / independent-signoff
- purpose: 固定 Codex SDK Worker 真实中止、Codex App Server Worker 运行态核验和“再次中止”确认交互的目标、边界与验收要求。
- canonical_path: docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-004-codex-cancel-execution-and-retry-confirmation.md

## Goal

- version_goal: 修复 Codex 任务中止请求已受理但真实执行仍持续、会话仍输出且重复中止无效的关键生命周期缺口。
- target_outcome: 用户发起的显式中止只在任务真实停止后进入终态；未确认时显示可诊断的真实状态。Codex App Server Worker 的“再次中止”先查询精确 thread/turn 状态并让用户确认，再执行与当前状态匹配的动作。

## Scope

- in_scope:
  - Codex SDK Worker 的显式中止执行、任务专属 CLI 进程退出确认、未确认状态与重复请求语义。
  - Codex App Server Worker 基于持久化 runtime/thread/turn 绑定的只读状态查询，以及必要时对精确 turn 的再次 interrupt。
  - Navigator 后端提供不泄露原生标识的中止状态检查与重试编排，并避免活跃终止操作被前端误解为“再次中止成功”。
  - 前端在 Codex App Server Worker 的“再次中止”前展示平台任务、原生 thread/turn 和建议动作的服务端权威摘要，要求用户确认。
  - 取消 API 后刷新服务端权威任务状态，覆盖相关 Worker、后端和前端回归测试。
- affected_modules: `tools/codex-agent-worker`、`tools/codex-app-server-worker`、`addons/codex-worker-agent`、`packages/navigator-frontend`；仅在现有终止操作状态机确有需要时修改 `session-module`。
- external_dependencies: Codex SDK CLI 子进程语义；Codex App Server `thread/read` 与 `turn/interrupt` 原生协议。

## Non-Goals

- out_of_scope: 杀死共享 Codex App Server 进程、按端口或模糊 PID 猜测进程、让超时/看门狗自动获得杀进程能力、改变 session/thread 身份、部署或发布 Worker、现场数据修复。
- do_not_touch: 其他工作区、无 exact task/runtime/thread/turn 或 task/PID 绑定的执行、用户现有未提交改动、明文凭据和无关版本工作项。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| Codex SDK Worker 与 Codex App Server Worker 保持独立中止机制 | SDK Worker 没有 app-server thread/turn 查询协议；把两者统一走 SDK 或统一走 app-server 都会丢失真实生命周期语义 | provider 类型必须由服务端/Worker 绑定决定，浏览器不得自行选择机制 |
| SDK 显式中止先使用 SDK AbortSignal，并验证精确任务 CLI 进程已经退出 | “请求已发出”不是终态证据，当前缺陷正是中止后执行仍持续 | 只有已签名、限时、单任务绑定的用户显式中止可触发；后台超时和看门狗仍禁止杀进程 |
| SDK 原生中止在宽限期内未退出时，只能升级终止该任务已确认绑定的本地 CLI 进程树 | 单纯重复 AbortSignal 无法解决真实进程继续运行 | 缺少或冲突的 PID 绑定必须 fail closed；不得作用于 app-server 共享进程或其他任务 |
| App Server “再次中止”前读取精确 thread/turn 状态 | thread 状态不能替代 exact turn 的终态判断 | 使用持久化 runtime/thread/turn affinity；缺失、漂移或查询失败时只显示不可确认，不得猜测 |
| 确认弹窗只展示安全摘要，执行动作时后端再次校验 | 避免把原生 ID 暴露给浏览器并防止检查到执行之间的状态竞态 | 新增字段/接口保持向后兼容；浏览器结果不能成为授权或终态证据 |
| 活跃终止操作不能再被作为空操作静默成功 | 这会让“再次中止”看似生效而实际没有下发 | 应返回明确的正在处理、已终态、可重试或不可确认结果，并保持单任务操作幂等 |

## Acceptance Criteria

- [x] AC-1: Codex SDK Worker 收到合法显式中止后，SDK CLI 执行真实停止且不再产生任务输出；任务只有在 provider 终态或精确进程退出证据成立后进入终态。
- [x] AC-2: SDK 原生中止未能在宽限期内退出时，只对 exact task-bound CLI 进程树执行有限升级；绑定缺失、冲突或目标非 SDK 时 fail closed，并保留可诊断的未确认状态。
- [x] AC-3: SDK `CANCEL_REQUESTED` 的重复中止不会因本地状态判断直接 409 或被 Navigator 静默吞掉；同一活跃操作保持幂等，同时客户端获得真实处理结果。
- [x] AC-4: 仅 Codex App Server Worker 的“再次中止”会先查询 exact thread/turn 状态，返回平台任务状态、原生 thread/turn 状态、检查时间和建议动作的安全投影，不向浏览器暴露原生 ID。
- [x] AC-5: App Server exact turn 为 `inProgress` 时，确认后可再次 interrupt；已经 terminal 时刷新/对账而不重复 interrupt；查询不可用或绑定不一致时 fail closed 并给出明确提示。
- [x] AC-6: 前端弹窗清晰区分“仍在运行”“已终止/待同步”“状态不可确认”，取消弹窗不产生副作用，确认动作后刷新权威任务状态。
- [x] AC-7: 自动化回归覆盖 SDK 正常退出、宽限期升级、PID 绑定失败、重复中止，App Server in-progress/terminal/unavailable/binding-conflict，以及前端弹窗和取消后权威刷新。

## Contract / Data / Security Constraints

- API or event contract: 新状态检查接口和响应字段必须向后兼容；`CANCEL_REQUESTED` 保持非终态。App Server 原生查询使用真实 `thread/read(includeTurns=true)`，中止使用 exact `turn/interrupt`。
- data and migration: 默认无数据库迁移；如需新增持久化字段或改变历史状态含义，必须转 `NEEDS_REPLAN`。
- compatibility and rollback: 旧 Worker 或查询不可用时安全降级为“无法确认、稍后重查”，不得伪造终态；回滚不能恢复前端乐观 `ABORTED`。
- permissions and secrets: 沿用签名、时效、单任务绑定的终止能力；原生 threadId、turnId、PID、命令行和凭据不进入浏览器安全投影或日志。

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| AC-1/AC-2/AC-3/AC-7 | critical | SDK Worker 单元/集成测试，覆盖真实退出判定、升级边界和重复请求 | 精确命令、测试断言与结果；不能只证明测试代码存在 |
| AC-4/AC-5/AC-7 | critical | App Server Worker 协议测试 + Java Service/Controller 测试 | exact turn 状态、重复 interrupt、终态/不可用/fail-closed 证据 |
| AC-6/AC-7 | major | 前端单元/组件测试 + `bash scripts/build-frontend.sh`；可运行时补浏览器体验检查 | 弹窗文案/动作分支、取消无副作用、确认后刷新和构建结果 |
| 全部 | critical | scoped review，确认 app-server 共享进程和自动终止源没有获得进程杀权 | changed-path review、测试覆盖和残余风险记录 |

## Bug Context

- bug_source: user-report
- severity: critical
- environment: `dev-kvm-jdk17.foggysource.com`，用户截图同时包含 Codex SDK Worker 与 Codex App Server Worker 会话。
- current_behavior: 点击中止后界面显示 `CANCEL_REQUESTED`/“再次中止”，但 SDK 任务仍运行并继续输出；重复点击可能被活跃终止操作静默吸收或由 Worker 拒绝。App Server 重试前没有读取 exact thread/turn 状态。
- expected_behavior: 显式中止形成真实执行停止和终态证据闭环；App Server 重试前先显示原生运行态供用户确认并执行正确动作。
- reproduction_steps:
  1. 启动一个持续输出的 Codex SDK Worker 任务并点击中止。
  2. 观察任务保持输出且平台停留在 `CANCEL_REQUESTED`。
  3. 点击“再次中止”，观察没有新的有效终止或明确状态反馈。
  4. 对 App Server Worker 的同类状态点击“再次中止”，当前只出现通用确认，未核验 exact turn。
- reproduction_status: confirmed
- existing_evidence: 用户于 2026-07-19 提供两张 dev 截图；代码复核确认 SDK Worker 仅调用 AbortController 后保留 `cancel_requested`，重复请求只接受 `running`；Navigator 活跃终止操作路径会返回空操作；App Server 已具备 `thread/read` 与 `turn/interrupt` 底层能力但未用于重试确认。
- existing_tests: 既有测试覆盖签名中止、终止超时和 stale-turn cleanup，但未覆盖 SDK 真实进程退出闭环与 App Server 重试前状态确认。
- regression_protection: required
- waiver_reason_and_risk: N/A

## Risks and Open Questions

- known_risks:
  - SDK 未暴露子进程句柄，精确 PID 绑定与退出确认必须复用现有受控进程发现能力，不能退化为端口或命令模糊匹配。
  - App Server thread 可能包含多个 turn，必须只使用任务持久化的 exact turnId，不能把 thread `idle` 或最后一条 turn 当作目标任务证据。
  - Worker 版本不一致时状态检查可能不可用，前后端必须 fail closed 并保留旧版本安全降级。
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md`、相关模块规范和专项技能。
- 在 scope 内自主决定具体文件、类和实现结构。
- 如需改变目标、范围、已确认决策、兼容或安全边界，设置 `NEEDS_REPLAN` 并停止扩展。
- 对可稳定复现的链路先建立失败回归，再修复并运行通过。
- 运行与改动面匹配的验证，不得声称未实际运行的测试通过。
- 完成后填写 `Implementation Result`，并将状态改为 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

- implementation_summary:
  - 保持两套独立中止机制：Codex SDK Worker 使用 SDK AbortSignal，并在宽限期后以 PID + canonical start time 的精确任务绑定升级终止和复查退出；仅凭请求受理不再进入终态，终态后停止消费迟到输出。进程退出确认后除保留生命周期 warning 外，还发送 Java relay 可识别的 `terminal_observed=true`、`terminal_status=ABORTED`、`terminal_source=VERIFIED_PROCESS_EXIT` 终态事件，避免 Worker 已停止但 Navigator 仍停留在 `CANCEL_REQUESTED`。
  - Codex App Server Worker 新增 exact persisted thread/turn 状态检查与用户确认后的重试端点；检查和执行都校验 lane、App Server instance、thread、turn 亲和性，原生 turn 已终态时只返回证据并同步 Navigator，不重复 interrupt。
  - Navigator 后端为再次中止创建新的审计操作、使旧活跃操作显式失效，并只接受 Worker 返回的 exact terminal receipt；浏览器接口仅投影安全状态，不返回 threadId、turnId、PID 或 instanceId。
  - 前端仅在 `codex-app-server-worker` 且任务为 `CANCEL_REQUESTED` 时查询状态并展示确认弹窗；运行中可确认再次中止，已终态可确认同步，不可用或绑定冲突时不下发动作。普通中止和 Codex SDK Worker 中止仍走原有 provider 路由。
- changed_paths:
  - SDK Worker：`tools/codex-agent-worker/src/codex/processes.ts`、`src/codex/sdk-wrapper.ts`、`src/models.ts` 及对应测试。
  - App Server Worker：`tools/codex-app-server-worker/src/app-server/executor.ts`、`src/persistence/task-store.ts`、`src/routes/tasks.ts`、`src/task-manager.ts` 及对应测试。
  - Navigator 后端：`session-module/.../TerminationOperationService.java`、`addons/codex-worker-agent/.../CodexWorkerClient.java`、`CodexTaskService.java`、`CodexTaskExtensionController.java` 及对应测试。
  - 前端：`packages/navigator-frontend/src/api/unifiedTask.ts`、`src/views/ClaudeWorkerView.vue` 及集成测试。工作树中既有的 `src/api/claudeWorker.ts` 和 `src/api/__tests__/claudeWorker.test.ts` 权威刷新改动被保留，并一并通过基线验证，不归因于本 BUG 的新增实现。
- tests_and_results:
  - `cd tools/codex-agent-worker && npm run typecheck && npm test`：通过；223 tests，222 passed，1 个 Windows-only skipped，0 failed。
  - `cd tools/codex-agent-worker && npm test -- --test-name-pattern=...`：2026-07-20 修复终态事件桥接后实际执行完整套件，通过；223 tests，222 passed，1 个 Windows-only skipped，0 failed；新增断言验证 warning 后的 verified terminal error、递增 seq、终止操作回执及 broadcast 关闭。
  - `cd tools/codex-app-server-worker && npm run typecheck && npm test`：通过；337 tests，336 passed，1 skipped，0 failed。
  - `mvn -pl addons/codex-worker-agent -am -Dtest=TerminationOperationServiceTest,CodexWorkerClientTest,CodexTaskExtensionControllerTest,CodexTaskServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`：BUILD SUCCESS；186 tests，0 failures/errors/skips。
  - `mvn -pl addons/codex-worker-agent -am -Dtest=CodexStreamRelayTest -Dsurefire.failIfNoSpecifiedTests=false test`：2026-07-20 BUILD SUCCESS；44 tests，0 failures/errors/skips，确认 Worker verified terminal event 可被 relay 收敛为 `ABORTED`。
  - `pnpm --filter @foggy/navigator-frontend type-check`：通过。
  - `pnpm --filter @foggy/navigator-frontend test -- src/views/__tests__/ClaudeWorkerView.integration.test.ts src/api/__tests__/claudeWorker.test.ts`：通过；Navigator frontend 271 tests，0 failed。
  - `bash scripts/build-frontend.sh`：通过；前端 workspace typecheck、tests、production builds 全部成功，仅保留既有 Vue 测试 stub、动态导入和 chunk size 警告。
  - `git diff --check`：通过；仅 Git 提示 `processes.ts` 工作副本的既有 CRLF/LF 转换风险。
- manual_or_experience_evidence:
  - 组件测试覆盖“仍在运行”确认再次 interrupt、“原生已终态”确认同步且不重复 interrupt、绑定不一致/不可用时无副作用，以及确认后的权威任务刷新。
  - 2026-07-20 按仓库本地链路启动验证环境：Java 使用 `bash scripts/start-launcher.sh` 启动于 `127.0.0.1:8112`，前端运行于 `127.0.0.1:5174`；直接运行 `tools/codex-agent-worker` 于 `127.0.0.1:3051` 和 `tools/codex-app-server-worker` 于 `127.0.0.1:13062`。四个服务最终均可访问，两个 Worker `active_tasks=0`；未操作任何其他工作区或用户安装目录下的 Worker。
  - Codex SDK 真实 Playwright smoke：任务 `20260720-bc06` 执行持续输出命令后点击真实“中止”，页面收到 `CODEX_RUNTIME_REMOTE_ABORTED`，任务收敛为 `ABORTED`，宽限期后不再输出且无“再次中止”；Worker 事件包含 lifecycle warning 后的 verified terminal error。证据：`temp/test-artifacts/bug004-playwright-20260720/sdk-fixed-running.png`、`sdk-fixed-aborted.png`、`sdk-fixed-aborted-after-grace.png`、`sdk-fixed-task-responses.json`。
  - Codex App Server 真实 Playwright smoke：任务 `20260720-4e00` 运行持续输出命令后点击真实“中止”，页面收到 `CODEX_RUNTIME_REMOTE_ABORTED`、退出处理中状态且无“再次中止”；API 网络证据包含 `RUNNING -> ABORTED`。证据：同目录 `appserver-running.png`、`appserver-aborted.png`、`appserver-task-responses.json`。
  - App Server 终态“再次中止”弹窗验证：真实任务 `20260720-b364` 已完成真实 interrupt 和远端终态收敛；因正常链路约几十毫秒即从 `CANCEL_REQUESTED` 进入 `ABORTED`，Playwright 仅在 SSE 已结束后临时把该任务的浏览器列表/详情投影固定为 `CANCEL_REQUESTED`，从而稳定进入 UI 分支，状态查询和服务端数据均未模拟。真实 `termination-inspection` 返回 `taskStatus=ABORTED`、`workerLifecycleStatus=ABORTED`、`providerState=terminal`、`turnStatus=aborted`、`recommendedAction=NO_ACTION`，且安全投影不含 threadId、turnId、runtime instance 或 PID。弹窗显示“原生 Turn 已结束”，点击“关闭”没有调用 `termination-retry`；移除浏览器投影后恢复权威 `ABORTED` 且无“再次中止”。证据：同目录 `appserver-cancel-requested.png`、`appserver-retry-confirmation.png`、`appserver-retry-cancelled-authoritative.png`、`appserver-retry-popup-network.json`。
- deviations:
  - 无目标、兼容、安全边界或数据迁移偏离。SDK 升级终止绑定到 SDK 直接启动且已验证身份的 Codex CLI 进程；POSIX 未新增独立的任意后代进程扫描或模糊进程树清理，以避免扩大杀进程权限。
- residual_risks:
  - 本地真实 Worker、Java 和浏览器 smoke 已通过；用户仍需按自身操作习惯在当前保留环境中完成手工体验确认。本次没有部署或发布到其他环境。
  - 本地 SDK Worker health 为 `degraded` 的原因是外部 credential forwarding readiness 未配置；`codex_sdk_available=true`、SDK 版本兼容且真实任务执行/中止已通过。本项不影响本 BUG 的 internal-dev 验证，但不能作为外部接入就绪证明。
  - 若未来 Codex SDK 在直连 CLI 之外引入独立存活的后代执行进程，需要新增可证明父子身份的进程树契约，不能直接扩大当前 PID 终止范围。
- readiness: READY_FOR_SIGNOFF

## References

- requirement / issue: 用户 2026-07-19 的中止失效截图、问题描述和 App Server 状态确认决策。
- architecture / glossary: authorized termination operation、`CANCEL_REQUESTED` 非终态、Codex App Server thread/turn affinity。
- related work items: `1.4.2-SNAPSHOT` GOV-004、BUG-018；`1.4.3-SNAPSHOT` BUG-001、BUG-003。
