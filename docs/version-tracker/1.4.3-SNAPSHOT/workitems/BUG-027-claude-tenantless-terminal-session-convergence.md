---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-027
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
assurance_level: standard
approved_by: project-owner-direct-runtime-bug-report
approved_at: 2026-07-27
open_questions: []
---

# Delivery Spec: Claude tenantless terminal session convergence

## Document Purpose

- intended_for: ultra-implementation / independent-signoff
- purpose: 修复平台超级管理员的无 tenant Claude 会话收到 Worker 终态后，Java
  终态事务回滚并使任务与会话永久停留在进行中的问题。
- canonical_path:
  `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-027-claude-tenantless-terminal-session-convergence.md`

## Goal

- version_goal: Claude Worker 任务终态与 Navigator task/session 状态可靠收敛。
- target_outcome: tenantless 平台会话可以提交本地终态并进入 `AWAITING_REPLY`；
  缺少 tenant 的状态事件不得被误当成 tenant-scoped definitive governance evidence。
- critical_outcomes:
  - Worker `result` 不再因缺 tenant 回滚；
  - task、session task 与 session 状态在一个事务内收敛；
  - tenant-scoped 终态仍携带 tenant 和 `recoverable=false`；
  - tenantless 终态事件使用非权威 `recoverable=null`，不会触发业务 Token tombstone。
- success_is_sufficient_when: failure-first 回归、Claude addon focused/full tests 和
  scoped diff audit 通过，且现有线上证据可映射到修复行为。

## Scope

- in_scope:
  - Claude task terminal status event 对 tenantless 平台会话的兼容语义；
  - task/session 状态收敛回归测试；
  - Worker relay 终态重放链路的受影响验证。
- affected_modules:
  - `addons/claude-worker-agent`
  - `docs/version-tracker/1.4.3-SNAPSHOT`
- external_dependencies: 现有 Session、Claude task、Session task 数据和
  `BusinessTaskScopedTokenTerminalListener` 的 tenant 校验。

## Non-Goals

- out_of_scope:
  - Claude Worker Python 或 OBS 版本变更；
  - Codex、Gemini、LangGraph 行为变更；
  - 为平台超级管理员伪造 tenant；
  - 修改业务 Token listener 的 tenant-scoped 权限边界；
  - 手工更新线上任务或数据库。
- do_not_touch:
  - 当前工作树中 FEAT-003、LangGraph、前端、Claude files route 等无关改动；
  - dev-kvm 运行进程和数据；
  - 明文 Token、API key 或数据库凭据。
- non_blocking_or_waivable_items: 用户更新 Java 后执行一次短任务 smoke。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| tenantless Claude 终态允许提交本地 task/session 状态 | 平台超级管理员会话合法不携带 tenant | 不伪造 tenant |
| tenantless 终态事件使用 `recoverable=null` | 不能把缺 tenant 事件声明为 tenant-scoped definitive evidence | Business Token listener 保持 fail-closed |
| tenantful 终态保持 `recoverable=false` | 保留现有 tombstone/revocation 契约 | 不改变业务 Worker 治理 |
| 依靠 Worker durable result 自动修复滞留任务 | 现有 result 会按 ACK 重放 | 不直接改线上数据库 |

## Acceptance Criteria

- [x] AC-1: tenantless Claude `COMPLETED` 事务成功提交 task 和 session task 终态。
- [x] AC-2: 对应 Session 从 `PROCESSING` 转为 `AWAITING_REPLY`。
- [x] AC-3: tenantless `TaskStatusChangeEvent` 的 tenantId 为空且
  `recoverable=null`，tenant-scoped listener 不获得 definitive evidence。
- [x] AC-4: tenantful `COMPLETED` 仍发布 tenantId 与 `recoverable=false`。
- [x] AC-5: Worker result relay 不再把该场景分类为 durable persistence failure，
  ACK 可推进并停止重复回放。
- [x] AC-6: focused/full tests 和 scoped diff audit 通过。

## Contract / Data / Security Constraints

- API or event contract: 不改变 HTTP/SSE 字段；只收窄 tenantless
  `TaskStatusChangeEvent.recoverable` 的语义。
- data and migration: 无迁移、无人工数据修复；部署后依靠 durable result replay 收敛。
- compatibility and rollback: Java-only 向后兼容；可独立回滚；无需重新发布 Worker。
- permissions and secrets: tenantless 事件不得触发 tenant-scoped terminal authority；
  不记录或输出用户提交的 Bearer Token。

## Test and Evidence Obligations

| Item | Classification | Risk | Required Validation | Reusable Evidence | Required Evidence |
|---|---|---|---|---|---|
| AC-1/2/3/4 | must-pass | major | Claude task service failure-first unit tests | existing tenant fallback/fail-closed tests | red/green output |
| AC-5 | must-pass | major | Worker relay focused tests | BUG-026 relay tests | actual focused result |
| AC-6 | must-pass | major | Claude addon full test lane + diff audit | prior 424-test baseline | exact command/result |

## Validation Budget and Evidence Sufficiency

- assurance_level: standard
- lightweight_validation: focused task-service/relay tests、diff check，单次 `<5m`。
- medium_validation: Claude addon 全量测试，单次 `5-30m`。
- expensive_validation: none。
- large_authority_or_replay_policy: prohibited-unless-user-approved。
- full_chain_recommendation_trigger: none。
- estimated_full_chain_wall_clock: not-estimated。
- full_chain_prerequisites: none。
- user_approval_status: not-requested。
- decision_if_not_approved: proceed-with-focused-and-affected-validation。
- expensive_validation_trigger: none。
- maximum_expensive_attempts: 0。
- reusable_evidence: dev-kvm task `20260727-16e1` 的脱敏日志与只读数据库状态。
- stop_when_evidence_is_sufficient: AC-1 至 AC-6 均有代码或实际测试证据。
- validation_not_required: Maven root reactor、前端构建、Worker 发布、远端重启。

## Waiver Policy

- waivable_items: 用户部署后的 live smoke。
- authorized_role: project owner。
- non_waivable_guards: tenant-scoped definitive event 不能丢失 tenant；
  不得伪造 tenant 或泄露凭据。
- required_risk_record: 更新 Java 后仍需验证一个 tenantless 超级管理员会话。

## Bug Context

- bug_source: user-report。
- severity: major。
- environment: `dev-kvm-jdk17-2`，Navigator commit `2ab6e38e`，
  Claude Worker `3031`。
- current_behavior: Worker 产生 result 后，Claude `publishStatusChange` 因 task、
  session、worker 均无 tenant 抛出 `CLAUDE_TASK_TENANT_MISSING`；终态事务回滚，
  task 保持 `RUNNING/ack=2`，session 保持 `PROCESSING`，result 持续重放。
- expected_behavior: tenantless 平台 task 本地完成，session 等待下一轮输入；
  tenant-scoped governance 不消费该非权威事件。
- reproduction_steps:
  1. 以无 tenant 的平台超级管理员向 Claude session resume。
  2. Worker 返回 assistant_text 和 result。
  3. Java 进入 `completeTask` 后发布终态状态事件。
  4. 查询 task/session 状态并观察 result 重放。
- reproduction_status: confirmed。
- existing_evidence:
  - result 首次到达时间 `2026-07-27 09:01:14`；
  - Java 每次记录 `Task completed` 后立即进入
    `DurableWorkerEventPersistenceException`；
  - 数据库实际为 task `RUNNING/lastAckedSeq=2`、session `PROCESSING`；
  - task、session 和 Worker tenant 均为空。
- existing_tests: tenant fallback 与 tenantless fail-closed 单测、BUG-026 relay tests。
- regression_protection: required。
- waiver_reason_and_risk: N/A。

## Risks and Open Questions

- known_risks:
  - 直接把缺 tenant 事件声明为 definitive 会绕开治理身份要求，因此必须降为
    `recoverable=null`；
  - 当前工作树有无关改动，必须路径级编辑和验证。
- open_questions: none。

## Ultra Execution Contract

- 先读取本文件、根与 Claude Worker `AGENTS.md`。
- 先将现有 tenantless 单测改为期望本地终态收敛并确认 failure-first。
- 只修改 Claude Java addon 和本 work item；如需改变业务 Token listener 或数据模型，
  设置 `NEEDS_REPLAN`。
- 运行 focused 和 addon 全量验证，记录精确结果。
- 完成后填写 `Implementation Result` 并设置 `READY_FOR_SIGNOFF`；不得自行设置
  `ACCEPTED`。

## Implementation Result

- implementation_summary:
  - `ClaudeTaskService.publishStatusChange` 对合法 tenantless 平台终态不再抛异常；
    本地 task/session 事务可以提交。
  - tenantless 终态事件发布为 `tenantId=null/recoverable=null`，不声明
    tenant-scoped definitive authority；tenantful 终态仍保持
    `recoverable=false`。
  - 回归测试覆盖 task `COMPLETED`、session `AWAITING_REPLY` 及事件权限语义。
- changed_paths:
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java`
  - `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/ClaudeTaskServiceAbortGuardTest.java`
  - `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-027-claude-tenantless-terminal-session-convergence.md`
- tests_and_results:
  - failure-first：
    `mvn -pl addons/claude-worker-agent
    -Dtest=ClaudeTaskServiceAbortGuardTest#tenantlessPlatformTerminalCommitsWithoutClaimingTenantAuthority
    test`；修复前 1 test / 1 error，错误为
    `CLAUDE_TASK_TENANT_MISSING`。
  - 同一命令修复后 1 test / 0 failure / 0 error，`BUILD SUCCESS`。
  - `mvn -pl addons/claude-worker-agent
    -Dtest=ClaudeTaskServiceAbortGuardTest,WorkerStreamRelayTest test`：
    37 tests / 0 failure / 0 error，`BUILD SUCCESS`。
  - `mvn -pl addons/claude-worker-agent test`：
    424 tests / 0 failure / 0 error，`BUILD SUCCESS`。
  - `mvn -pl business-agent-module
    -Dtest=BusinessTaskScopedTokenTerminalListenerTest test`：
    18 tests / 0 failure / 0 error，`BUILD SUCCESS`；确认 tenantless
    非权威事件不会触发 tenant-scoped tombstone。
  - `git diff --check -- <本次三个路径>`：通过，仅报告既有测试文件
    CRLF 将在 Git 后续处理时转换为 LF 的提示。
  - 额外执行 `mvn test -pl addons/claude-worker-agent -am`；依赖 reactor
    在进入 Claude addon 前被当前工作树中无关的
    `BusinessTaskScopedTokenLifecycleJpaTest` 阻断：business-agent
    710 tests / 9 errors，缺少 `RuntimeRequestAuditService` bean，Claude addon
    因此被 Maven 标为 skipped。本次未修改该无关工作。
- manual_or_experience_evidence:
  - dev-kvm 脱敏日志确认同一 Worker result 在 `completeTask` 后因
    `CLAUDE_TASK_TENANT_MISSING` 回滚并重复重放。
  - 只读数据确认 task 为 `RUNNING/lastAckedSeq=2`、session 为
    `PROCESSING`；与修复后的 failure-first 场景一致。
- deviations: none
- residual_risks:
  - dev-kvm 尚未部署本次 Java 变更；部署后需确认旧 result 自动重放、ACK 推进且
    session 转为 `AWAITING_REPLY`。
  - dependency reactor 的无关 business-agent Spring context 错误需由其当前改动
    所有者修复，但不影响 Claude addon 424 项全量通过的证据。
- reused_evidence: BUG-026 已验证的 Worker durable result replay/reconnect 契约。
- omitted_validation_and_reason: 未重启或修改 dev-kvm，也未手工改数据库；按范围等待
  用户部署 Java 后完成 live smoke。
- readiness: READY_FOR_SIGNOFF

## References

- requirement / issue: 用户报告 Worker task 已结束但会话仍显示进行中。
- architecture / glossary:
  - `agent-framework/.../TaskStatusChangeEvent.java`
  - `business-agent-module/.../BusinessTaskScopedTokenTerminalListener.java`
- related work items:
  - `BUG-026-claude-terminal-replay-reconnect-convergence.md`
  - `GOV-002-biz-worker-and-upstream-user-boundary.md`
