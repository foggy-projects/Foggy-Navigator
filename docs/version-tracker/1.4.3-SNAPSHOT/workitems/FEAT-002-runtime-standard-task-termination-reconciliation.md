---
doc_type: delivery-spec
delivery_type: cross-module
version: 1.4.3-SNAPSHOT
ticket: FEAT-002
status: ACCEPTED
canonical: true
execution_mode: ultra
assurance_level: elevated
approved_by: project-owner-user-confirmed
approved_at: 2026-07-24
execution_started_at: 2026-07-24
implementation_completed_at: 2026-07-24
repair_contract_approved_by: project-owner-user-confirmed
repair_contract_approved_at: 2026-07-24
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
| 历史无 PID binding task 的 repair 必须使用独立 signed `RECONCILE_CANCEL` operation | 原 REMOTE_CANCEL receipt 只能证明 Worker 消费过终止命令，空进程列表单独不足以证明 exact task exit | Worker 同时验证原 receipt、精确 provider task/Physical Worker、原 sanitized `UNCONFIRMED` lifecycle evidence，并现场确认 Worker-wide Codex process total=0；任一缺失均 fail closed |
| approved repair 可在 Worker 重启后消费 persisted receipt/event | 3151 当前任务只存在内存，正常升级不能保留 registry | 不把重启或 task 404 本身作为 terminal evidence；必须由新显式 reconcile 生成 `OBSERVED_EXIT` durable event/receipt |
| STANDARD 空 scope 在 admission 前持久化 | RUNNING task 也必须可审计 | 终止/失败不可清除；历史目标任务只能从已有 durable evidence 恢复，禁止手工补写 |
| taskFacts 与 auditSideEffects 分层 | 消除原任务 dispatch 与查询副作用歧义 | auditSideEffects 的十项只读断言全部为 false |
| 3151 只补本机 Worker identity 配置 | Worker 已有签名终止/receipt 能力 | credential/secret 不输出、不进证据、不进进程参数 |

## Acceptance Criteria

- [x] AC-1 termination-readiness 返回规定字段并完全只读；durable Worker mismatch、Worker unreachable/not-ready、terminal/token/registration 状态均 fail closed。
- [x] AC-2 task-terminate dry-run 完全只读；execute 有 confirm guard、clientRequestId exactly-once、alreadyTerminal no-op、无新资源/token/dispatch/retry/recovery。
- [x] AC-3 execute 仅向 durable-bound Worker 发送至多一次 signed termination，撤销 task token、清除 active registration，并返回显式 `reconcileRequired`。
- [x] AC-4 task-reconcile 支持 dry-run/execute，只基于 durable evidence 修复投影；无 evidence fail closed；一致时 `reconciliationChanged=false`。
- [x] AC-5 runtime audit 支持 `ask`、`task-terminate`、`task-reconcile` 与不超过 15 分钟窗口，返回完整规定字段和 sanitized stages。
- [x] AC-6 STANDARD empty tool/function scope 在 admission/task-token creation 前持久化，task-audit 对 RUNNING/FAILED/COMPLETED/CANCELLED 均返回；真实 dispatch facts 为 runtime/model true、BusinessFunction false。
- [x] AC-7 task-audit/operation response 使用 `taskFacts` 与 `auditSideEffects`，只读 audit side effects 全 false。
- [x] AC-8 CLI 1.0.27+ clean release 与 server clean build 提供完整 provenance、hash、feature manifest/help、health 和 ddl-auto validate/migration 结果。
- [x] AC-9 3151 readiness 为 true 且三项 readiness bool 为 true；Worker identity 精确绑定 `ddc45293`，secret 不泄露。
- [x] AC-10 `20260724-e279` 按自然终态分支 exactly once 处理，最终满足 owner 指定 task/scope/token/stage/counter/side-effect facts。

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
  - 3151 Worker drain 会保留当前 unverified in-memory task，升级可能需要在确认无 Codex child process、receipt/event 已 durable 后显式重启 Worker 主进程；该重启不得被当作 terminal evidence。
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md`、`CLAUDE.md`、相关源码与专项技能。
- 在 scope 内自主决定具体文件、类和实现结构。
- 如需改变目标、状态模型、credential lane、兼容、安全或数据边界，设置 `NEEDS_REPLAN` 并停止扩展。
- 对 exactly-once、dry-run、evidence-gated reconcile、admission scope 建立自动化回归保护。
- 运行与改动面匹配的验证；不运行任何新 ask/model/BF smoke 或大型 authority/replay。
- 完成后填写 `Implementation Result` 并改为 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

- implementation_summary: 已实现、部署并验证 runtime-only termination readiness、terminate dry-run/execute、显式 evidence-gated reconcile、同 request ID 幂等重放、STANDARD ask/request audit 扩展、admission-time scope 固化、task token revocation audit 与 `taskFacts`/`auditSideEffects` 分层。3151 Worker 已安全绑定 Physical Worker `ddc45293`，readiness/auth/worker-id/replay-ledger 全部 ready。遗留任务 `20260724-e279` 已基于 Worker durable termination/reconciliation evidence 收敛为公开终态 `CANCELLED / OPERATOR_TERMINATED`；token 已撤销、active registration 已清除、dispatch/retry/recovery 保持 `1/0/0`，没有创建新 task/context/session、没有签发 token、没有模型或 BusinessFunction 再派发、没有 provisioning 变更。
- changed_paths:
  - `navigator-spi`、`addons/codex-worker-agent`、`addons/claude-worker-agent`: runtime closure SPI、durable Worker guard、signed terminate、durable evidence reconcile、readiness/task facts/side effects。
  - `business-agent-module`: STANDARD ask/admission/request audit facts、15-minute window、operation idempotency、failed-audit same-ID convergence 与 sanitized stages。
  - `navigator-open-sdk`、`tools/navigator-upstream-cli`: CLI/API/forms、pre-network clientRequestId、1.0.31 manifest/help、nested task facts preservation、closure safety/replay options。
  - `user-auth-module`: runtime credential lane route authorization evidence。
  - `launcher`: full commit build metadata and clean provenance validation。
  - `session-module`: durable termination operation lifecycle and terminal evidence observation。
  - `tools/codex-agent-worker`: signed `RECONCILE_CANCEL`、durable replay ledger、terminal receipt/event persistence 与 fail-closed exact evidence validation。
  - `docs/migration/2026-07-24-runtime-task-closure-audit*.sql`: additive audit schema and rollback。
- tests_and_results:
  - final focused rerun: `RuntimeRequestAuditServiceTest` 11/11 passed；`CodexTaskServiceTest` 141/141 passed；8-module Maven reactor `BUILD SUCCESS`。
  - `RuntimeStateAuditServiceTest` + `OpenApiControllerMessageMappingTest` + `RuntimeTaskClosureServiceTest`: 68/68 passed。
  - `WebMvcConfigTest`: 2/2 passed。
  - `UpstreamCliTest`: final 152/152 passed。
  - authorization contract/required-section tests: 11/11 passed。
  - `BuildMetadataResourceTest`: 1/1 passed；full commit metadata present。
  - Codex Worker 1.0.21: 239 tests, 238 passed, 1 skipped, 0 failed；typecheck/build/package passed。
  - clean `mvn clean package -pl launcher -am -DskipTests`: 14-module reactor passed。
- manual_or_experience_evidence:
  - final clean implementation commit `6637b6202a1ee17ce8a53bf71aebf161b597a225`；branch `main`。
  - CLI version `1.0.31`、buildId `1.0.31+6637b6202a1e`、full commit 同上、`gitDirty=false`、build time `2026-07-24T14:29:40Z`、Linux package SHA-256 `60f1d34944ffdc0c94885c5b2d4eda6201ca509431a2ed495efdbbeab49d5033`。
  - CLI 1.0.31 已于 `2026-07-24` 发布至官方 OBS channel；remote `latest.json` 已指向 1.0.31，Windows/Linux objects 均可下载，公开 installer smoke 返回相同 clean provenance。远端重新下载校验：Linux SHA-256 `60f1d34944ffdc0c94885c5b2d4eda6201ca509431a2ed495efdbbeab49d5033`，Windows SHA-256 `222338a873bf655086be3e3be6c7570bc368a19fdd81d7098c71cf451f6c9352`。
  - launcher artifact `launcher`、version `1.0.0-SNAPSHOT`、branch `main`、full commit 同上、`gitDirty=false`、build time `2026-07-24T14:29:29.793Z`、JAR SHA-256 `dfe60b97f5a3f6a044da9e33d923c7f62c11bf5a0c48a92b78d59a198ed08708`；8112 health/database `UP`。
  - additive migration 已应用；launcher explicit `SPRING_JPA_HIBERNATE_DDL_AUTO=validate` 启动、JPA initialization 与 health 均通过。
  - CLI manifest 共 68 项，包含 `runtime-task-terminate`、`runtime-task-terminate-dry-run`、`runtime-task-reconcile`、`runtime-task-termination-audit`、`runtime-audit-standard-ask`、`runtime-task-scope-at-admission`、`runtime-task-token-revocation-audit`、`runtime-termination-no-redispatch`、`runtime-reconcile-no-dispatch`。
  - Worker version `1.0.21`、Linux package SHA-256 `aa65613feca2cd5ba38d12cdd5e81b91ec36d0ee97dd175a17f1135da8ea1d4a`。
  - 3151 final health: `status=ok`、`active_tasks=0`、`termination_ready=true`、`termination_auth_configured=true`、`termination_worker_id_configured=true`、`termination_replay_ledger_ready=true`、reason empty；Worker identity 已绑定 `ddc45293`。
  - before task facts: `terminal=false`、`status=RUNNING`、token `ACTIVE`、registration present、dispatch/retry/recovery `1/0/0`；physical Worker、model config、model variant 均与 frozen binding 一致。
  - STANDARD admission/scope facts: requested/effective tool `0/0`、`NO_RUNTIME_MODEL_TOOL_SURFACE`、`REQUEST_EXPLICIT_EMPTY`；requested/effective function `0/0`、`REQUEST_EXPLICIT_EMPTY`、task-token function scope empty；runtime/model dispatched true，BusinessFunction dispatched false。
  - exactly one effective termination-readiness network invocation: Worker reachable/active、ready/allowed true、dryRun true，side effects 全 false。更早的 1.0.28 CLI 因未知 safety option 在首次网络请求前本地拒绝，不构成 server invocation。
  - exactly one terminate dry-run: clientRequestId `06a68607-ef34-42cb-b166-c414d1c3da26`；任务、token、registration、计数均未变化，side effects 全 false。
  - exactly one formal terminate: clientRequestId `216d62f7-2d9f-4144-9be3-40f744652058`；Worker termination dispatch ACK，返回 `reconcileRequired=true`；未签发 token、未创建资源、未触发 model/BusinessFunction/retry/recovery/redispatch/provisioning。
  - initial explicit reconcile clientRequestId `04570b92-6a1b-4d4a-a2c8-dad15d0a6072` 在 repair contract 前按 `RUNTIME_TASK_RECONCILE_EVIDENCE_INSUFFICIENT` fail closed，未触发 Worker reconcile。
  - owner-approved repair reconcile clientRequestId `063174eb-62c0-451c-944c-91d4bf0fdfed`：唯一一次 Worker `RECONCILE_CANCEL` dispatch 生成并持久化 exact durable terminal evidence；首次响应因 Navigator transaction/projection 缺陷返回 `RUNTIME_TASK_RECONCILE_EVIDENCE_UNREACHABLE`。
  - 8112 clean build 重启后，既有 Worker durable `STATE_SYNC` terminal evidence 被正常 SSE replay/observation 消费，Navigator 在 `2026-07-24T22:33:19.651628+08:00` 收敛为 terminal；这不是新的 `task-reconcile` POST，也未增加 task recovery counter。
  - 同一 reconcile clientRequestId 的显式 replay 返回 `reconciliationChanged=false`、`alreadyConsistent=true`、durable evidence `NAVIGATOR_ALREADY_TERMINAL`；provider 在创建 Worker client 前即返回，未发送第二次 Worker reconcile。
  - final task facts: `terminal=true`、`status=CANCELLED`、`sanitizedErrorCode=OPERATOR_TERMINATED`、token `REVOKED`、registration absent、dispatch/retry/recovery `1/0/0`、Physical Worker `ddc45293`、model config `ec356713-1d8e-41a5-920b-71ccf63133ff`、variant `codex-luna:high`；scope facts 保持 `0/0`，runtime/model true，BusinessFunction false。
  - final sanitized task stages: `TASK_TOKEN/ISSUED`、`TASK_REGISTERED/RECORDED`、`PROVIDER_TASK_REGISTERED/RECORDED`、`RUNTIME_DISPATCH/DISPATCHED`、`MODEL_DISPATCH/DISPATCHED`、`BUSINESS_FUNCTION_DISPATCH/NOT_DISPATCHED`、`TERMINATION_REQUESTED/ABORTED`、`TERMINATION_DISPATCH/OBSERVED`、`TERMINATION_OBSERVED/ABORTED`、`TASK_TERMINAL/CANCELLED(OPERATOR_TERMINATED)`、`TASK_TOKEN/REVOKED`、`ACTIVE_TASK_REGISTRATION/ABSENT`。
  - final terminate audit stages additionally include `TERMINATION_EVIDENCE_OBSERVED/SUCCEEDED` and `TASK_TOKEN_REVOKED/SUCCEEDED`；final reconcile audit retains the original sanitized failure stage and appends `RECONCILIATION_NO_CHANGE/SUCCEEDED`、`TASK_TOKEN_REVOKED/SUCCEEDED`、`REQUEST_COMPLETED/SUCCEEDED`。
  - terminate/reconcile/task-audit/readiness 的 `auditSideEffects` 十项全部 false；operation response 的 `newTaskCreated`、`newContextCreated`、`newSessionCreated`、token issuance、model redispatch、BusinessFunction、retry/recovery、provisioning flags 全 false。
  - no new ask/safe-ask/STANDARD task was created。原 STANDARD ask 发生于新版 request-audit 持久化上线前，历史 10-minute `--operation ask` 窗口返回 0 条；未为补证创建新 ask，ask audit contract 由 focused tests、help 和 manifest 验证。
  - sanitized evidence scan 未发现 Authorization、Bearer material、secret value、Worker token 或 runtime profile path。
- deviations:
  - 迁移首次执行因 MySQL 不支持 `BEFORE` column positioning 被原子拒绝；确认无 partial schema change 后修正、重建 clean artifacts 并成功应用。
  - CLI 1.0.27/1.0.28 暴露了两项 release validation 缺口：SDK task-audit DTO 未保留 nested/scope fields，CLI known-options 未登记 closure safety options；分别由 commits `8a1220783d7f316e31bd797bcc460e9390ef1864` 与 `8d411a5f902d5bc6677cd42b1005d91869ac0555` 修复。中间验证版本为 1.0.29；事务/projection 与同 request ID replay 收敛修复后，最终 clean release 为 1.0.31。
  - 历史任务没有 PID/start-time binding；owner 批准了严格的 signed `RECONCILE_CANCEL` repair contract，以原 termination receipt、exact provider task/Worker identity、既有 sanitized lifecycle evidence 和现场 Worker-wide zero-process proof 共同生成 durable terminal evidence。
  - repair reconcile 首次 Worker evidence 已成功持久化，但 Navigator 因 self-invoked transaction/projection 缺陷未同步落终态；commits `153a96626d00d6fe6bba0623060182dfba98d87d` 与 `6637b6202a1ee17ce8a53bf71aebf161b597a225` 修复事务、same-ID projection repair 与 failed-audit convergence。最终 release 升至 CLI 1.0.31。
  - server restart 的正常 Worker terminal event replay 先于同-ID显式 reconcile replay完成 Navigator 投影；因此 replay 结果为 no-change/already-consistent，而不是再次写投影。
  - 初次正式签收时仅验证了 clean CLI package 和本机安装，未完成官方 OBS distribution；SIM 从官方 channel 仍只能取得 1.0.26，因而正确报告阻断。该发布缺口已在 `2026-07-24` 补齐，并以 remote `latest.json`、双平台 package SHA 与公开 installer smoke 重新验收。
- residual_risks:
  - `task-audit` 为兼容旧 CLI 仍保留 flat compatibility fields；新消费者必须以 nested `taskFacts` 与 `auditSideEffects` 为权威，避免把 audit side-effect flat alias 与原任务事实混读。
  - 原 STANDARD ask request 早于 ask request-audit persistence，无法补造历史 request record；本次仅保留其 durable task/admission facts。
- reused_evidence: 复用 FEAT-001 read-only audit foundation；所有受本次 contract 影响的 focused tests 已重新执行。
- omitted_validation_and_reason: 未执行任何新 ask/safe-ask/model/BusinessFunction smoke，符合交付边界。未运行 frontend/Playwright 或无关 full reactor。未手工修改 task 数据库、未伪造 Worker terminal evidence、未使用管理 credential。
- evidence_location: `temp/test-artifacts/runtime-task-closure-delivery-20260724-KqGcaX5v/final-runtime-audit/`；正式独立签收记录写入版本 evidence 目录。
- readiness: ACCEPTED

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted
- signed_off_by: independent-codex-reviewer
- signed_off_at: 2026-07-24
- acceptance_record: `../evidence/FEAT-002-independent-signoff.md`
- blocking_items: none
- follow_up_required: no

## References

- requirement / issue: project owner runtime task closure request dated 2026-07-24
- architecture / glossary: `CLAUDE.md`; `docs/02-modules/task-governance.md`; `FEAT-001-runtime-binding-task-read-only-audit.md`
- related work items: `BUG-014-codex-sdk-termination-identity-and-reconciliation.md`; `BUG-016-openapi-safe-ask-request-scoped-empty-surfaces.md`; `BUG-017-runtime-request-audit-no-task-id.md`
