---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-001
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
approved_by: Project Owner
approved_at: 2026-07-19
open_questions: []
---

# Delivery Spec: Codex PID 终止超时的 API 错误可辨识性

## Document Purpose

- intended_for: ultra-implementation / independent-signoff
- purpose: 固定已确认的可复现缺陷、自动化回归边界与修复验收条件。
- canonical_path: docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-001-codex-pid-termination-timeout-api.md

## Goal

- version_goal: 保持 Codex 终止操作的确认优先语义，同时让调用方能区分 Worker 超时、网络不可达、HTTP 拒绝和未确认退出。
- target_outcome: `/api/v1/codex-workers/{workerId}/processes/{pid}/kill` 在 Worker 调用超时时不再返回笼统的 `CODEX_WORKER_REQUEST_UNCONFIRMED`。

## Scope

- in_scope: 该控制面 API 的 Worker 传输失败错误映射；稳定的 API 级自动化复现与回归测试；本工作项的验证记录。
- affected_modules: `addons/codex-worker-agent`。
- external_dependencies: 测试仅使用本地替身，不访问 dev Worker、不使用用户凭据、不发送实际 OS 信号。

## Non-Goals

- out_of_scope: 改变已签名的终止能力、PID/任务绑定、进程退出确认规则、任务终态规则，或自动重试/强制终止策略。
- do_not_touch: `tools/codex-agent-worker` 运行时行为、其他工作区、dev/production Worker、凭据与现有 GOV-001 变更。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| 终态仍必须由 Worker 观察到进程退出后确认 | 防止信号发送成功被误报为任务已终止 | 不把超时或网络失败转换为 `ABORTED` |
| 超时需要稳定、非敏感的专用错误码 | `A600` 无法指导用户或排障 | 不返回 Worker 原始错误、命令行或凭据 |
| 先建立失败的 API 回归基线 | 用户已确认该 BUG 可复现 | 修复前该测试预期失败，并记录为复现证据 |

## Acceptance Criteria

- [x] AC-1: 本地 API 级测试能稳定模拟 Worker 调用超时，覆盖实际 `kill` 路由和控制面错误映射。
- [x] AC-2: 修复后超时响应包含稳定的专用安全码，且不泄露底层异常内容。
- [x] AC-3: 超时不会将任务标记为 `ABORTED`，不改变既有 unconfirmed 安全语义。

## Contract / Data / Security Constraints

- API or event contract: 仅细化当前笼统错误信息；成功、HTTP 拒绝和 `202/unconfirmed` 语义保持兼容。
- data and migration: 无迁移。
- compatibility and rollback: 新错误码可由前端渐进识别；必要时可回退到当前笼统展示，但不得回退安全确认规则。
- permissions and secrets: 测试不可使用真实 Bearer Token 或真实 Worker 配置。

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| AC-1 | major | `mvn test -pl addons/codex-worker-agent -Dtest=CodexWorkerControllerApiE2ETest` | 初始失败输出，证明当前响应仍为笼统码 |
| AC-2/AC-3 | major | 修复后运行同一测试及相关模块测试 | 精确命令、结果和安全语义断言 |

## Bug Context

- bug_source: user-report
- severity: major
- environment: dev 控制面，Codex CLI 进程手动终止。
- current_behavior: Worker 调用超时或无 HTTP 回执时，控制面返回 `CODEX_WORKER_REQUEST_UNCONFIRMED`。
- expected_behavior: 返回可辨识、无敏感信息的超时错误码，并维持未确认的终止状态。
- reproduction_steps: 使用本地 API 测试替身使 Worker 进程列表请求超时，再调用 PID 终止 API。
- reproduction_status: confirmed
- existing_evidence: 用户提供的 API 响应；控制器当前仅将 `WebClientResponseException` 映射为具体 HTTP 码。
- existing_tests: Worker 侧 `process-termination-route.test.ts` 覆盖 `202/unconfirmed`，未覆盖控制面传输超时映射。
- regression_protection: required
- waiver_reason_and_risk: N/A

## Risks and Open Questions

- known_risks: 需在修复时区分确定性 HTTP 拒绝与不确定传输结果，避免把未知结果宣称为已终止。
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md`、相关模块规范和专项技能。
- 在 scope 内自主决定具体文件、类和实现结构。
- 如需改变目标、范围、已确认决策、兼容或安全边界，设置 `NEEDS_REPLAN` 并停止扩展。
- 运行与改动面匹配的验证，不得声称未实际运行的测试通过。
- 完成后填写 `Implementation Result`，并将状态改为 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

> 由实施会话填写。

- implementation_summary: 控制器以有界、循环安全的 cause 链分类 Worker 异常：HTTP 响应保留 `CODEX_WORKER_HTTP_<status>`，`TimeoutException` 与 Reactor `block(Duration)` 的阻塞超时统一为 `CODEX_WORKER_TIMEOUT`，`WebClientRequestException` 映射为 `CODEX_WORKER_CONNECTION_UNAVAILABLE`，其余未知结果保持 `CODEX_WORKER_REQUEST_UNCONFIRMED`。不改变预检、签名能力或终止状态机。
- changed_paths: `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/controller/CodexWorkerController.java`; `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/controller/CodexWorkerControllerApiE2ETest.java`; 本 work item。
- tests_and_results: 修复前：`mvn test -pl addons/codex-worker-agent -am -Dtest=CodexWorkerControllerApiE2ETest -Dsurefire.failIfNoSpecifiedTests=false` — 依赖闭包编译通过，超时断言按预期失败，响应仍为 `CODEX_WORKER_REQUEST_UNCONFIRMED`。修复后：`mvn test -pl addons/codex-worker-agent -am -Dtest=CodexWorkerControllerApiE2ETest,CodexWorkerControllerTest,CodexTaskServiceTest -Dsurefire.failIfNoSpecifiedTests=false` — 成功；API-slice 2/2、控制器 3/3、任务服务 127/127 通过。`git diff --check -- <changed paths>` 通过。
- manual_or_experience_evidence: 本地 `MockMvc` 路由 + 回环替身 Worker 覆盖六秒预检延迟与 502 响应；前者返回 `CODEX_WORKER_TIMEOUT`，后者返回 `CODEX_WORKER_HTTP_502` 且不回显替身诊断文本。两种预检失败均验证 `taskService` 零交互，因此不创建终止操作或标记 `ABORTED`。未访问真实 dev Worker、未使用用户 Bearer Token、未发送任何 OS 终止信号。
- deviations: none
- residual_risks: 未在真实 dev/production Worker 上执行；该环境验证不在本次授权范围。未知传输异常仍保守显示为 `CODEX_WORKER_REQUEST_UNCONFIRMED`，不会被误报为已终止。
- readiness: READY_FOR_SIGNOFF — 等待独立 signoff，不得视为 ACCEPTED。

## References

- requirement / issue: 用户于 2026-07-19 提供的 dev API 复现。
- architecture / glossary: `tools/codex-agent-worker/src/routes/processes.ts` 的确认退出语义。
- related work items: GOV-001-upstream-permission-and-trust-boundary.md
