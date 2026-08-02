---
doc_type: delivery-spec
delivery_type: cross-module
version: 1.4.3-SNAPSHOT
ticket: ARCH-001-ACT-002
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
assurance_level: elevated
approved_by: repository-owner
approved_at: 2026-08-02
open_questions: []
---

# Delivery Spec: Bounded Local-Development Lifecycle Activation

## Document Purpose

- intended_for: normal-analysis / ultra-implementation / independent-signoff
- purpose: 冻结当前 8112 Navigator 与既有隔离 WSL 3151 Codex role 的一次性 lifecycle activation authority 配置、最小适配和验证边界。
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/ARCH-001-ACT-002-bounded-local-development-activation.md`

## Goal

- version_goal: 在不放宽默认 termination policy 的前提下，让当前本地开发实例可被显式识别为 bounded local-development activation target。
- target_outcome: registration/proof acquire 形成 server-side authority readiness，下一唯一新 Task 可在 provider effect 前完成 ENFORCED admission。
- critical_outcomes: 默认关闭、exact target/instance/controller/Worker identity、active DB-time proof、one-shot reservation、zero Task/model/termination during provisioning。
- success_is_sufficient_when: `authorityReady=true`、`admissionGateOpen=true`、proof active，且数据库与 focused tests 证明 admission 写入三类 ENFORCED owner、三条 proof reference、initial fact 和 durable effect outbox。

## Scope

- in_scope: 现有 8112 launcher、MySQL 8.0.44 开发数据库、既有 `ddc45293` 的 3151 Codex role、gitignored activation artifacts/profile、registration/proof/readiness、关闭方案。
- affected_modules: `session-module` lifecycle authority/admission、launcher configuration、activation observer tooling、additive migration/tests、version work item。
- external_dependencies: 只读核验 SIM 冻结 tuple；不修改 SIM。

## Non-Goals

- out_of_scope: 创建 Task、调用模型、发送 termination、生产 enablement、外部 ingress、Worker/Gateway 发布。
- do_not_touch: TMS、3151 Worker 配置/进程/binding、Physical Worker/Agent/modelConfig/Directory/grant/credential 资源。
- non_blocking_or_waivable_items: 旧 disposable canary 的专用端口/新数据库拓扑不适用于本轮，但其 exact identity、proof、observation 和 provider-effect fences 不可豁免。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| 新增显式 local-development target opt-in，默认 false | 当前拓扑已由独立 WSL Worker 与 loopback DB 隔离，无需替代资源 | 旧 disposable target 行为保持不变 |
| local-development target 只接受 manifest 精确声明的 `codex-worker` | SIM 冻结创建路径的真实 provider 是 `codex-worker` | 不允许客户端自报 ENFORCED；provider 必须与 server-loaded manifest 相等 |
| 使用 fresh content-free live observation 和 server-side Worker inventory | 防止仅凭配置布尔值宣称 ready | observation drift 或 Worker identity drift 必须 quarantine |
| activation target 可表达无 Codex scoped-home 的标准 Codex 路径 | 标准 `codex-worker` 请求没有 `codexHomeKey` | 仅 additive nullable migration；旧 canary 仍要求该值 |

## Acceptance Criteria

- [ ] AC-1: 默认配置仍关闭；未显式开启 local-development target 时注册稳定拒绝且无 authority 写入。
- [ ] AC-2: 当前 database schema/identity、launcher provenance、controller observation、Worker v1 identity/capabilities 均 exact。
- [ ] AC-3: registration + proof acquire 后 authority/readiness/gate/proof 全部为 true/active，并持续 fresh renew。
- [ ] AC-4: production admission 在 provider effect 前原子形成 Worker/Session/Task ENFORCED owner、三条 active proof reference、initial fact 与 durable outbox；失败保持 zero provider effect。
- [ ] AC-5: 本轮 Task/model/termination delta 均为 0，未修改既有 Worker binding 或创建替代资源。
- [ ] AC-6: 提供可执行关闭顺序：先停 enrollment，再 quarantine proof，再关闭 control/admission，并保留正常 Task 审计。

## Contract / Data / Security Constraints

- API or event contract: 复用既有 internal activation control routes；请求体仍为空，exact target-owned control token 仍为唯一 control credential。
- data and migration: 仅将 activation target 的 `codex_home_key` 改为 nullable，以表达标准 Codex provider；不得 DML 业务 Task。
- compatibility and rollback: local-development opt-in 关闭即回到原始 fail-closed；旧 disposable target/provider contract 保持。
- permissions and secrets: token/profile 仅在 gitignored target-owned `0600` 文件；不得输出 token、数据库密码、Authorization header 或 provider credential。

## Test and Evidence Obligations

| Item | Classification | Risk | Required Validation | Reusable Evidence | Required Evidence |
|---|---|---|---|---|---|
| AC-1/AC-4 | must-pass | critical | focused lifecycle integration tests | existing production activation test fixture | exact Maven command/result |
| AC-2/AC-3 | must-pass | critical | live local observation + registration/proof/readiness | current provenance/schema/Worker health | sanitized IDs/states/counts |
| AC-5 | must-pass | critical | before/after DB and Worker counts | preflight counts | delta 0 |
| AC-6 | must-pass | major | quarantine/readiness/config closure command review | existing control API | exact commands without secrets |

## Validation Budget and Evidence Sufficiency

- assurance_level: elevated
- lightweight_validation: source contract, migration shape, secret scan, observer unit tests。
- medium_validation: affected Maven modules/tests、launcher package/restart、live registration/proof/readiness。
- expensive_validation: none；SIM owns the later unique Task/termination smoke。
- large_authority_or_replay_policy: approved only for this bounded configuration/registration/proof chain; Task/model/termination execution remains prohibited。
- full_chain_recommendation_trigger: none
- estimated_full_chain_wall_clock: not-applicable
- full_chain_prerequisites: none
- user_approval_status: approved for configuration/authority only
- decision_if_not_approved: not-applicable
- expensive_validation_trigger: none
- maximum_expensive_attempts: 0
- reusable_evidence: exact preflight provenance/schema/Worker identity and existing lifecycle integration fixture。
- stop_when_evidence_is_sufficient: AC-1..AC-6 have exact command/API/DB evidence and no Task/model/termination delta。
- validation_not_required: SIM live Task, model result, real termination, TMS, production/external readiness。

## Waiver Policy

- waivable_items: none for AC-1..AC-6
- authorized_role: repository owner
- non_waivable_guards: default closed、exact identity、fresh observation、server-side proof、provider-effect fence、secret isolation、zero Task/model/termination。
- required_risk_record: any residual topology ambiguity or stale observation is a FAIL with stable blocker。

## Risks and Open Questions

- known_risks: current dev Docker restart policy must be disabled during activation and restored after smoke closure; a process/Worker/DB identity drift quarantines authority and requires a new target generation rather than repair-in-place。
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md`、相关模块规范和专项技能。
- 在 scope 内自主决定具体文件、类和实现结构。
- 如需改变目标、范围、已确认决策、兼容或安全边界，设置 `NEEDS_REPLAN` 并停止扩展。
- 运行与改动面匹配的验证，不得声称未实际运行的测试通过。
- 不得创建 Task、调用模型、发送 termination 或修改 3151 binding。
- 完成后填写 `Implementation Result`，并将状态改为 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

- implementation_summary: 新增默认关闭的 bounded local-development target opt-in；server-loaded manifest 精确选择标准 `codex-worker`；outbox provider 取自已注册 target；加入 nullable scoped-home migration 和跨 WSL content-free live observer。
- changed_paths: `session-module` lifecycle authority/admission/properties/entity/tests；`launcher` configuration；`docs/migration/2026-08-02-*`；`tools/arch001-activation/bounded_local_dev_target.py` 及测试。
- tests_and_results: `python3 -m unittest ...test_bounded_local_dev_target.py`：2/2 pass；focused Maven lifecycle tests：18/18 pass；`mvn -pl launcher -am -DskipTests package`：BUILD SUCCESS。
- manual_or_experience_evidence: 当前 8112/3151/DB 的 post-commit registration/proof/readiness 由 gitignored exact target root 留存脱敏结果，并在本轮最终 handoff 汇总。
- deviations: none
- residual_risks: none
- reused_evidence: pre-change exact provenance、MySQL 8.0.44 schema inventory、3151 Worker v1 health/capabilities/identity。
- omitted_validation_and_reason: SIM unique Task/model/termination smoke 明确由 SIM 后续预算执行，本轮禁止。
- readiness: READY_FOR_SIGNOFF

## References

- requirement / issue: user-authorized bounded local-development activation on 2026-08-02
- architecture / glossary: `ARCH-001-unified-session-task-lifecycle-owner.md`
- related work items: `ARCH-001-ACT-001-enforced-canary-activation-readiness.md`
