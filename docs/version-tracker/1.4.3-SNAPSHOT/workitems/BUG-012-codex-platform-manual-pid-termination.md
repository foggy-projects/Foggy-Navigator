---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-012
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
approved_by: Project Owner
approved_at: 2026-07-22
open_questions: []
---

# Delivery Spec: Codex 平台域 CLI 手工终止授权与诊断修复

## Document Purpose

- intended_for: ultra-implementation / independent-signoff
- purpose: 固定 Codex 平台域任务无法通过已验证 CLI PID 手工终止、且安全业务错误码被泛化隐藏的修复范围。
- canonical_path: docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-012-codex-platform-manual-pid-termination.md

## Goal

- version_goal: 恢复平台管理员对平台域 Codex 任务的受审计人工止损能力。
- target_outcome: SUPER_ADMIN 可对其有权管理的、无租户平台域任务发起已绑定 CLI PID 终止；调用方获得安全且可行动的本地终止错误码。

## Scope

- in_scope:
  - 为 `tenant_id = null` 的 Codex 任务增加仅限 SUPER_ADMIN 的手工 PID 终止授权分支。
  - 保留手工 PID 终止中的安全业务错误码，而非统一映射为 Worker 请求未确认。
  - 增加服务与控制器层自动化回归。
- affected_modules: `addons/codex-worker-agent`；版本工作项文档。
- external_dependencies: Codex Worker 进程快照与签名终止 capability。

## Non-Goals

- out_of_scope: 修改 Worker 协议、绕过 Worker 所属者校验、放宽 PID/任务身份绑定、改数据库 schema、直接修改现场任务或发布重启环境。
- do_not_touch: 现有租户管理员授权语义、Claude Worker、其他工作区与既有 BUG-006 行为。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| 平台域无租户任务仅允许 `SUPER_ADMIN_MANUAL` | 租户管理员不应获得跨租户或平台域信号权限 | 保留 Controller 角色门禁、Worker 所属者检查、签名 capability 和审计 operation |
| 租户任务继续要求租户匹配的管理员上下文 | 不扩大现有租户权限边界 | 原 `TENANT_ADMIN_MANUAL` 路径保持不变 |
| 仅白名单返回稳定的 `TERMINATION_*` 业务码 | UI 需要可行动原因，且不能泄露异常文本、URL、token 或 Worker 诊断 | 网络、超时和 HTTP Worker 错误保持既有安全码 |

## Acceptance Criteria

- [ ] AC-1: SUPER_ADMIN 在平台域上下文可为无租户、已验证绑定的 Codex 任务准备手工 PID 终止；会创建审计 operation 与签名 capability。
- [ ] AC-2: 普通用户和租户管理员不能以平台管理员路径终止平台域任务；租户任务仍要求租户一致。
- [ ] AC-3: 本地 `TERMINATION_TASK_ACCESS_DENIED` 与 `TERMINATION_OPERATION_PENDING` 等稳定业务码通过 API 返回；Worker HTTP、连接和超时错误语义不变。
- [ ] AC-4: 针对改动面的 Maven 自动化测试与 `git diff --check` 实际通过。

## Contract / Data / Security Constraints

- API or event contract: 保持 `POST /api/v1/codex-workers/{workerId}/processes/{pid}/kill` path、参数和成功响应不变；失败 message 使用既有安全 `TERMINATION_*` code。
- data and migration: 无 schema 或数据迁移；继续记录 termination operation。
- compatibility and rollback: 回滚仅恢复平台域手工终止拒绝与泛化错误信息；不影响 Worker signal 协议。
- permissions and secrets: 不记录或返回 JWT、Worker token、远端 URL 或原始异常信息。

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| AC-1/AC-2 | critical | `CodexTaskServiceTest` 平台/租户授权回归 | failure-first 与修复后 Maven 结果 |
| AC-3 | major | `CodexWorkerControllerTest` 安全错误码映射 | 精确断言与 Maven 结果 |
| AC-4 | major | affected module tests、`git diff --check` | 命令、结果、changed paths |

## Bug Context

- bug_source: user-report
- severity: major
- environment: `dev-kvm-jdk17.foggysource.com`，2026-07-22；任务 `20260722-59df`，Worker task `0812187a-6d7a-4323-9c7e-0c380be54bf0`。
- current_behavior: 已验证的 CLI PID 手工终止在控制面本地抛出 `IllegalArgumentException`，API 仅返回 `CODEX_WORKER_REQUEST_UNCONFIRMED`；平台域无 tenant 的任务无法满足原租户管理员校验。
- expected_behavior: SUPER_ADMIN 能以受限、审计、签名的路径终止有权管理的平台域任务；安全业务拒绝原因可见。
- reproduction_steps:
  1. 以 SUPER_ADMIN 平台上下文请求绑定到无租户 Codex 任务的 CLI PID kill。
  2. 观察进程快照确认任务/PID 身份绑定后，旧实现仍在本地拒绝。
  3. API 返回泛化 `CODEX_WORKER_REQUEST_UNCONFIRMED`。
- reproduction_status: confirmed
- existing_evidence: 现场后端日志记录 `IllegalArgumentException`；任务与 operation 均为无 tenant；进程快照绑定有效。
- existing_tests: 已有租户管理员成功、普通 owner 拒绝、PID identity 拒绝测试。
- regression_protection: required
- waiver_reason_and_risk: N/A

## Risks and Open Questions

- known_risks:
  - push 不会自动部署；dev-kvm 必须重建并重启后端后才能现场验证。
  - 当前已有远程 cancel operation 未确认时，手工终止仍应返回 `TERMINATION_OPERATION_PENDING`，不应并发创建第二条 destructive operation。
- open_questions: none

## Ultra Execution Contract

- 在 scope 内实现最小改动；不得放宽 Worker ownership、PID identity、capability 签名或 operation 审计。
- 若需改变权限边界、Worker 协议或数据库结构，设置 `NEEDS_REPLAN` 并停止扩展。
- 运行 focused 后 affected-module 验证；记录精确命令与结果。
- 完成后填写 `Implementation Result` 并改为 `READY_FOR_SIGNOFF`；不得设置 `ACCEPTED`。

## Implementation Result

- implementation_summary:
  - Codex CLI PID 终止入口根据认证上下文为 SUPER_ADMIN 签发内部 `SUPER_ADMIN_MANUAL` actor type；其余管理员保持 `TENANT_ADMIN_MANUAL`。
  - 手工 PID 授权保留租户管理员的严格租户匹配，并新增仅在 task 与请求上下文均为无 tenant 时生效的平台 SUPER_ADMIN 分支；Worker ownership、精确 PID identity、签名 capability 和 termination operation 审计未改变。
  - Controller 仅白名单透出稳定的本地 `TERMINATION_*`/`REMOTE_TASK_ID_UNAVAILABLE` code；Worker HTTP、连接、超时和未知异常继续使用既有安全码。
- changed_paths:
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/controller/CodexWorkerController.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/controller/CodexWorkerControllerTest.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexTaskServiceTest.java`
  - `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-012-codex-platform-manual-pid-termination.md`
- tests_and_results:
  - focused: `mvn -pl addons/codex-worker-agent -am -Dtest=CodexTaskServiceTest,CodexWorkerControllerTest -Dsurefire.failIfNoSpecifiedTests=false test` — 140 tests passed, 0 failures/errors/skips.
  - affected reactor: `mvn -pl addons/codex-worker-agent -am test` — success; 2,098 tests passed, 0 failures/errors, 3 existing environment-dependent MySQL integration skips.
  - `git diff --check` — passed.
- manual_or_experience_evidence: 未对 dev-kvm 执行 live kill；该环境运行的是旧后端 JAR，必须先基于本提交重建并重启才能验证真实 Worker 信号与现场任务收敛。
- deviations: none
- residual_risks:
  - 现场已有未确认的 `REMOTE_CANCEL` operation 时仍会安全返回 `TERMINATION_OPERATION_PENDING`；本修复不会并发创建第二条终止 operation。
  - 用户先前在对话中暴露的 Bearer JWT 必须在环境侧轮换；仓库未保存该凭据。
- readiness: READY_FOR_SIGNOFF

## References

- requirement / issue: 用户于 2026-07-22 报告 Codex CLI 进程终止接口返回 `CODEX_WORKER_REQUEST_UNCONFIRMED`。
- architecture / glossary: termination operation、manual PID kill、platform scope、tenant scope。
- related work items: `BUG-004-codex-cancel-execution-and-retry-confirmation.md`、`BUG-006-codex-historical-termination-reconciliation.md`。
