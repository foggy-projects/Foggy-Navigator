---
doc_type: delivery-spec
delivery_type: cross-module
version: 1.4.3-SNAPSHOT
ticket: FEAT-002
status: ULTRA_EXECUTING
canonical: true
execution_mode: ultra
assurance_level: elevated
approved_by: project-owner-user-confirmed
approved_at: 2026-07-24
execution_started_at: 2026-07-24
open_questions: []
---

# Delivery Spec: Runtime-only STANDARD Task Termination and Reconciliation

## Document Purpose

- intended_for: ultra-implementation / SIM runtime operator / independent-signoff
- purpose: 固定 ClientApp runtime credential 对既有 STANDARD task 执行审计、dry-run、显式终止、必要时显式 reconcile 与最终事实验收的唯一交付契约。
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/FEAT-002-runtime-standard-task-termination-reconciliation.md`

## Goal

- version_goal: 在不授予 SIM system-admin、control、platform 或 management credential 的前提下，形成 runtime-only、fail-closed、可审计的 STANDARD task closure 闭环。
- target_outcome: 发布 clean server 与 CLI 1.0.27+，补齐 termination readiness、dry-run、exactly-once terminate、evidence-gated reconcile、STANDARD ask/scope audit，并安全关闭既有任务 `20260724-e279`。
- critical_outcomes:
  - Physical Worker 从 durable task binding 解析，调用方只能用 expected ID fail-closed 校验，不能重定向 Worker。
  - terminate/reconcile 不创建 task/context/session，不签发 token，不 dispatch/retry/resume/recovery/model/BusinessFunction，不改 provisioning。
  - STANDARD effective scope 在 admission/task-token creation 前固化，任何终态均保留。
  - task facts 与本次 audit/operation side effects 明确分层，所有只读 side effects 为 false。
  - 既有 task 最终 terminal、token revoked、active registration absent，dispatch/retry/recovery 保持 `1/0/0`。
- success_is_sufficient_when: focused/affected tests、clean package/provenance、3151 readiness、当前任务 before/after、terminate/reconcile request audits、token/stage/side-effect 证据全部可复核，且无禁止行为发生。

## Scope

- in_scope:
  - runtime credential self-service termination readiness、task terminate dry-run/execute、task reconcile dry-run/execute API/SDK/CLI。
  - clientRequestId 幂等、Worker signed termination、durable operation receipt、explicit reconciliation 与 stable sanitized audit stages。
  - STANDARD ask request audit、admission-time empty tool/function scope persistence、task-audit taskFacts/auditSideEffects contract。
  - route authorization catalog、CLI feature manifest/help、server/CLI clean provenance、3151 local secure identity configuration。
  - 部署后对 `20260724-e279` 严格按自然终态分支处理。
- affected_modules:
  - `business-agent-module`
  - `session-module`
  - `addons/claude-worker-agent`
  - `addons/codex-worker-agent`
  - `navigator-common`
  - `navigator-open-sdk`
  - `user-auth-module`
  - `tools/navigator-upstream-cli`
  - `launcher` packaging/runtime
- external_dependencies: 当前 8112 Navigator、MySQL durable state、现有 Physical Worker `ddc45293` 的 3151 Codex SDK Worker。

## Non-Goals

- out_of_scope:
  - 新 ask/safe-ask/STANDARD task、模型或 BusinessFunction smoke。
  - retry、resume、recovery、redispatch、隐式 reconcile 或 synthetic terminal evidence。
  - frozen Agent/model/directory/Worker binding 变更、替代 Worker、TMS/SIM 业务数据访问。
  - typed-management、system-admin、control、platform credential 或 authority 扩张。
- do_not_touch:
  - sibling workspaces、账号、浏览器、ActorHome、workspace/task body/prompt/response。
  - 既有 `FEAT-001` 用户修改与其他无关 dirty worktree 内容。
- non_blocking_or_waivable_items: none；credential lane、Worker target、exactly-once、durable evidence、no-dispatch/no-token/no-provisioning 和 token revocation 不可豁免。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| stable terminal status=`CANCELLED`，errorCode=`OPERATOR_TERMINATED` | 当前公开状态模型已支持 CANCELLED | Worker/provider 内部 `ABORTED` 可映射，但 runtime audit 固定公开值 |
| expected Physical Worker 仅作 equality guard | 防止调用方重定向任务 | actual Worker 必须由 durable task binding 解析 |
| terminate 与 reconcile 分离 | 防止隐式修复或伪造 terminal | 仅 `reconcileRequired=true` 后可显式调用一次 |
| clientRequestId 为 runtime operation idempotency key | 支持 exactly-once 和审计关联 | CLI 首次网络请求前生成并输出；同 ID 不重复 Worker termination |
| reconcile 仅消费 durable Worker/provider/termination evidence | 保持事实权威 | 无足够 evidence 必须 fail closed；绝不 dispatch |
| STANDARD 空 scope 在 admission 前持久化 | RUNNING task 也必须可审计 | 终止/失败不可清除；历史目标任务只能从已有 durable evidence 恢复，禁止手工补写 |
| taskFacts 与 auditSideEffects 分层 | 消除原任务 dispatch 与查询副作用歧义 | auditSideEffects 的十项只读断言全部为 false |
| 3151 只补本机 Worker identity 配置 | Worker 已有签名终止/receipt 能力 | credential/secret 不输出、不进证据、不进进程参数 |

## Acceptance Criteria

- [ ] AC-1 termination-readiness 返回规定字段并完全只读；durable Worker mismatch、Worker unreachable/not-ready、terminal/token/registration 状态均 fail closed。
- [ ] AC-2 task-terminate dry-run 完全只读；execute 有 confirm guard、clientRequestId exactly-once、alreadyTerminal no-op、无新资源/token/dispatch/retry/recovery。
- [ ] AC-3 execute 仅向 durable-bound Worker 发送至多一次 signed termination，撤销 task token、清除 active registration，并返回显式 `reconcileRequired`。
- [ ] AC-4 task-reconcile 支持 dry-run/execute，只基于 durable evidence 修复投影；无 evidence fail closed；一致时 `reconciliationChanged=false`。
- [ ] AC-5 runtime audit 支持 `ask`、`task-terminate`、`task-reconcile` 与不超过 15 分钟窗口，返回完整规定字段和 sanitized stages。
- [ ] AC-6 STANDARD empty tool/function scope 在 admission/task-token creation 前持久化，task-audit 对 RUNNING/FAILED/COMPLETED/CANCELLED 均返回；真实 dispatch facts 为 runtime/model true、BusinessFunction false。
- [ ] AC-7 task-audit/operation response 使用 `taskFacts` 与 `auditSideEffects`，只读 audit side effects 全 false。
- [ ] AC-8 CLI 1.0.27+ clean release 与 server clean build 提供完整 provenance、hash、feature manifest/help、health 和 ddl-auto validate/migration 结果。
- [ ] AC-9 3151 readiness 为 true 且三项 readiness bool 为 true；Worker identity 精确绑定 `ddc45293`，secret 不泄露。
- [ ] AC-10 `20260724-e279` 按自然终态分支 exactly once 处理，最终满足 owner 指定 task/scope/token/stage/counter/side-effect facts。

## Contract / Data / Security Constraints

- API or event contract: additive runtime-scoped endpoints；所有输入有长度/格式/confirm/expected count guard；稳定 sanitized code；响应不含 credential、secret、token、header、payload、path 或 stack。
- data and migration: 允许 additive schema migration/ddl update；部署验收必须以 `ddl-auto=validate` 启动；禁止手工 SQL 修改任务或伪造 evidence。
- compatibility and rollback: 新 CLI/API additive；Worker 终止协议沿用现有签名 capability；关闭新 endpoints/回滚 artifact 不得恢复已撤销 token。
- permissions and secrets: 仅 ClientApp runtime long-term credential；server-to-Worker credential 只在服务端 durable registration/config 与本机 Worker secret store 中使用。

## Test and Evidence Obligations

| Item | Classification | Risk | Required Validation | Reusable Evidence | Required Evidence |
|---|---|---|---|---|---|
| AC-1/2 | must-pass | critical | service/controller/SDK/CLI tests + live dry-run | existing audit/auth guards | exact commands/results + zero-write proof |
| AC-3/4 | must-pass | critical | idempotency/concurrency/Worker client/reconcile tests + one live target operation | existing Worker termination unit tests | operation receipts, invocation counts, before/after |
| AC-5/6/7 | must-pass | critical | audit/admission/task-state tests + historical live task audit | FEAT-001 audit base | DTO/output/stage/scope evidence |
| AC-8/9 | must-pass | major | affected Maven/npm tests, clean package, launcher validate start, health/provenance | current release scripts | version/buildId/commit/hash/readiness |
| AC-10 | must-pass | critical | strictly bounded live closure | target task owner facts | final audit and no-new-resource counters |

## Validation Budget and Evidence Sufficiency

- assurance_level: elevated
- lightweight_validation: focused unit/contract/CLI tests、typecheck、secret/output scans，单次 `<5m`。
- medium_validation: affected Maven modules、Worker tests（仅当源码受影响）、CLI package、launcher package/start/live closure，单次 `5-30m`。
- expensive_validation: none planned。
- large_authority_or_replay_policy: prohibited-unless-user-approved
- full_chain_recommendation_trigger: none；不运行 synthetic/full root/真实 ask authority 链。
- estimated_full_chain_wall_clock: not-estimated
- full_chain_prerequisites: none
- user_approval_status: not-requested
- decision_if_not_approved: proceed-with-focused-and-affected-validation
- expensive_validation_trigger: focused/affected 证据无法证明 exactly-once 或 no-side-effect 时停止并 `NEEDS_REPLAN`，不得扩大 live 操作。
- maximum_expensive_attempts: 0 without new approval
- reusable_evidence: 现有 Worker termination tests、FEAT-001 runtime audit foundation、owner frozen binding；仅在相关代码/artifact/state 未变化时复用。
- stop_when_evidence_is_sufficient: required tests pass、clean provenance 固定、readiness true、目标任务 closed、audit/stages/counters/side-effects 完整且 secret scan clean。
- validation_not_required: frontend/Playwright、TMS/SIM business smoke、新 ask、模型/BF 调用、full reactor、Worker publish（若 Worker source 未变）。

## Waiver Policy

- waivable_items: none
- authorized_role: project owner
- non_waivable_guards: runtime-only lane、durable Worker targeting、exactly-once、no redispatch/token issuance/provisioning、durable evidence、secret redaction、target task counters。
- required_risk_record: 环境依赖检查未运行、目标状态漂移、无法从 durable evidence 恢复历史 scope 或 reconcile 必须记录为 blocker，不得猜测通过。

## Risks and Open Questions

- known_risks:
  - 当前 worktree dirty 且 HEAD artifact 为 dirty build；clean release 需要隔离 worktree/commit，不得覆盖用户改动。
  - 历史目标任务 admission scope 为空；若现有 durable token/request/provider evidence 不足，禁止手工回填并必须按 AC-6 fail closed。
  - terminate ACK 可能不是 terminal proof；只能以 Worker/provider durable terminal/exit evidence 决定 reconcile。
  - launcher restart 可能触发无关后台 recovery；必须在目标窗口隔离并证明目标无 retry/recovery/redispatch。
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md`、`CLAUDE.md`、相关源码与专项技能。
- 在 scope 内自主决定具体文件、类和实现结构。
- 如需改变目标、状态模型、credential lane、兼容、安全或数据边界，设置 `NEEDS_REPLAN` 并停止扩展。
- 对 exactly-once、dry-run、evidence-gated reconcile、admission scope 建立自动化回归保护。
- 运行与改动面匹配的验证；不运行任何新 ask/model/BF smoke 或大型 authority/replay。
- 完成后填写 `Implementation Result` 并改为 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

- implementation_summary: pending
- changed_paths: pending
- tests_and_results: pending
- manual_or_experience_evidence: pending
- deviations: none
- residual_risks: pending
- reused_evidence: existing Worker termination unit coverage and FEAT-001 audit foundation, subject to revalidation
- omitted_validation_and_reason: pending
- readiness: ULTRA_EXECUTING

## References

- requirement / issue: project owner runtime task closure request dated 2026-07-24
- architecture / glossary: `CLAUDE.md`; `docs/02-modules/task-governance.md`; `FEAT-001-runtime-binding-task-read-only-audit.md`
- related work items: `BUG-014-codex-sdk-termination-identity-and-reconciliation.md`; `BUG-016-openapi-safe-ask-request-scoped-empty-surfaces.md`; `BUG-017-runtime-request-audit-no-task-id.md`
