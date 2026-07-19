---
doc_type: decision-gate
delivery_type: cross-module
version: 1.4.3-SNAPSHOT
ticket: GOV-001-P1B-B
status: DRAFT
gate_state: BLOCKED
canonical: true
canonical_slice: p1b-b-real-inventory-owner-operator-seed-activation
parent_delivery_spec: GOV-001-upstream-permission-and-trust-boundary.md
execution_mode: normal-analysis
implementation_authorization: none
approved_by: pending-named-data-owner-operator-and-security-approval
approved_at: pending
open_questions:
  - target Navigator instance and environment profile
  - factual S1/S2 subject-owner-tenant-ClientApp mapping
  - secure-source inventory authority and four-eyes approval
  - seed lifecycle, verifier/KMS owner, rollback and evidence retention
---

# Decision Gate: GOV-001 P1B-B Real Inventory, Owner/Operator Approval, and Seed Activation

## Document Purpose

- intended_for: data owner / deployment operator / security approver / future implementation / independent signoff
- purpose: 定义从 P1B-B0 的纯离线 synthetic validator 进入真实 S1/S2 inventory、审批和受控 seed 的前置事实；本文件不是 seed 授权，也不保存或读取真实事实。
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/GOV-001-p1b-b-real-inventory-owner-operator-seed-activation-gate.md`
- current_state: `DRAFT` + `BLOCKED`。没有 named owners、secured factual inventory 和四眼批准时，任何真实 seed、credential issuance、route cutover 或环境开关均不得开始。

## Goal

- version_goal: 让 S1 `INSTANCE_ROOT` 与 S2 `SAAS_PLATFORM` 的实际启用建立在可追溯、最小披露、可撤销的事实和审批上，而不是 fixture、历史 profile、CLI help 或 legacy key 的推断。
- target_outcome: 形成一份可由受控系统外部持有的脱敏 inventory、mapping/冲突处置和 owner/operator/security approval evidence；随后才可另建 `APPROVED` 的 seed/lifecycle 实施契约。

## Current Evidence and Boundary

- P1B-A 只提供 fixture-only typed-management authentication；默认部署没有真实 verifier、principal、grant 或 tenant authority。
- P1B-B0 已 accepted，但其 `navi.authorization.preseed-inventory.v1` validator 只处理 synthetic 或安全提供的 envelope；`VALID` 不等于事实正确、owner approval、seed 权限、external/Gateway/production readiness。
- 当前会话不得读取 `.navigator/**`、`accounts/**`、环境变量、数据库、KMS、真实 profile、上游系统或任何 secret；真实材料只可由被授权 owner 在受控系统中处理。

## Required Decisions and Evidence

| Gate item | Required fact or decision | Named accountable role | Minimum durable evidence |
|---|---|---|---|
| Deployment binding | exact `navigatorInstanceId`、`environmentProfile`、build/migration baseline | deployment operator | sanitized instance/profile/build fingerprint |
| S1 root mapping | SIM source `upstreamSystemId`、one `INSTANCE_ROOT` subject、control/security lane intent；不得用 ClientApp 或 legacy admin 推导 | SIM owner + instance custodian | opaque subject/reference, authority scope, approval reference |
| S2 platform mapping | exact TMS upstream subject、allowed tenant ownership basis、provisioning/security lane intent | TMS platform owner + tenant-data owner | opaque platform/grant/tenant references, owner status/version |
| Resource facts | ClientApp、Worker、Directory、Agent/Model、owner、binding 与 allocation 的现状及冲突 | resource owner/operator | IDs or safe aliases, owner/binding state, conflict disposition; no secret |
| Credential facts | lane、status、expiry、generation 和 safe fingerprint；legacy must remain legacy pending reissue | credential custodian | redacted fingerprint/status/expiry/generation and verifier/KMS ownership reference |
| Source authority | source system、effective time、extraction method、integrity checksum 和 data freshness | data owner | source authority statement, checksum, timestamp, retention location |
| Four-eyes approval | mapping/exception/legacy disposition and proposed operation reviewed by distinct people | owner + operator + security approver | immutable approval references, approver identities/roles, decision time |
| Recovery and rollback | revoked/failed/partial seed handling, credential generation rollback, audit and support owner | deployment/security operator | tested rollback plan, decision/audit sink reference |

## Non-Goals and Hard Stops

- 不在本文件、repo fixture、migration、CLI profile 或测试中写入 real principal、credential、token、key、verifier、tenant/owner business data 或 approval artifact 内容。
- 不执行 seed、credential/token/grant/tenant-authority issuance、legacy migration、route-family enforcement cutover、Worker allocation、Open API/Gateway/Worker external 或 production enablement。
- 不把 `NAVI_ADMIN_API_KEY`、legacy upstream-admin、trust profile、`VALID` inventory 或本地 loopback URL 解释为 S1 root、S2 platform/security lane 或 owner approval。
- 不创建额外 Worker、BizWorkerIdentity 或 WorkerPool member；Codex 继续只允许 existing Physical Worker → `worker-host verify` → `worker-host update --worker-id` / `claudeCode.codexConfig`。

## Approval-to-Implementation Exit Criteria

- [ ] A named deployment operator supplies the target instance/profile/build and confirms no cross-instance credential reuse.
- [ ] S1/S2 facts are extracted from an approved secure source, represented only as sanitized inventory evidence, and pass the B0 structural/quarantine checks.
- [ ] Each owner, tenant, ClientApp, Worker allocation and legacy mapping conflict has a documented disposition; ambiguous or stale facts remain quarantined.
- [ ] Separate business/data owner, deployment operator and security approver complete four-eyes approval. No person self-approves a destructive or credential-lifecycle action they will execute.
- [ ] Credential/verifier/KMS custody, expiry/rotation/revocation behavior, audit sink and rollback plan are named and reviewed without exposing secret material.
- [ ] A future implementation contract declares the exact seed/lifecycle scope, migrations, rollback, test matrix and independent signoff. This gate itself never changes to `APPROVED` as a substitute for that contract.

## Minimum Negative Matrix for the Future Contract

| Case | Expected result |
|---|---|
| Missing, stale, duplicate or conflicting owner/source fact | quarantine; no seed or issuance |
| Legacy upstream-admin / scope / tenant-list-only record | no automatic promotion; explicit reviewed reissue only |
| Cross-instance/profile mismatch | reject before credential or grant creation |
| S1 root represented by ClientApp/control/runtime/task/Worker credential | reject |
| S2 tenant/App represented by platform or wildcard caller field | reject |
| Worker allocation proposed as implicit owner transfer | reject; binding/grant and owner transfer remain separate |
| Codex routing proposal adds Worker/Biz identity/Pool member | reject; use existing Physical Worker path |
| Approval, audit sink or rollback plan missing | block activation |

## Risks and Open Questions

- known_risks: secured inventory can be structurally valid yet factually wrong; source freshness, authority and conflict disposition therefore remain human-accountable gates. A seed that partially succeeds may require credential-generation revocation rather than database-only rollback.
- open_questions:
  - Which named systems and people own each S1/S2 fact, and what is the approved secure evidence location?
  - What is the exact real seed lifecycle and its rollback/compensation boundary?
  - Which KMS/verifier and append-only audit sink own lifecycle evidence?
  - Which existing legacy routes remain compatible during a migration window?

## Future Execution Contract

- Do not implement from this DRAFT. A future `APPROVED` work item must read this gate and P1B-B0, use secure-source facts without committing them, and record only redacted evidence.
- Any need to change S1 root scope, S2 platform/tenant separation, binding-versus-owner semantics, credential lane boundaries, Codex topology or security invariants is `NEEDS_REPLAN`.
- Implementation may finish only at `READY_FOR_SIGNOFF`; independent signoff must verify the approval evidence, migrations, actual test results and residual risk.

## References

- parent: [GOV-001 trust boundary](./GOV-001-upstream-permission-and-trust-boundary.md)
- prerequisite: [P1B-B0 offline inventory gate](./GOV-001-p1b-b0-preseed-inventory-and-owner-approval.md)
- runtime safety: `.agents/skills/navigator-runtime-provisioning/SKILL.md`
