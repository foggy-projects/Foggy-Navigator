---
doc_type: delivery-spec
delivery_type: cross-module
version: 1.4.3-SNAPSHOT
ticket: GOV-001-P1B-A
status: ACCEPTED
canonical: true
canonical_slice: p1b-a-typed-management-auth-core
parent_delivery_spec: GOV-001-upstream-permission-and-trust-boundary.md
execution_mode: ultra
approved_by: project-owner-user-confirmed
approved_at: 2026-07-19
accepted_by: Independent Signoff Reviewer (Codex)
accepted_at: 2026-07-19
acceptance_record: ../evidence/GOV-001-p1b-a-independent-signoff.md
open_questions: []
---

# Delivery Spec: GOV-001 P1B-A Typed Management Authentication Core

## Document Purpose

- intended_for: independent-signoff / Project Owner / future-P1B-B implementation
- purpose: 将已批准且已实施的 fixture-only P1B-A 收敛为独立可签核对象；它不改变父项 GOV-001 的整体阶段状态。
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/GOV-001-p1b-a-typed-management-auth-core.md`
- parent_contract: `GOV-001-upstream-permission-and-trust-boundary.md`, section `P1B-A activated acceptance subset`.

## Goal

- version_goal: 为 S1/S2 后续凭据生命周期建立 fail-closed 的 typed-management HTTP 认证基础，而不真实开通任何上游主体。
- target_outcome: 新管理入口只接受一个 typed credential source，能够签发短期 control/action token，并在默认无 verifier/seed 的部署中拒绝全部请求。

## Scope

- in_scope: `/api/v1/management/v1/auth/**` 的 typed credential/bearer 解析、canonical ingress guard、control exchange、security-action authorization、whoami、permissions、non-binding explain、fixture-only verifier contract、route catalog/evidence 和负向测试。
- affected_modules: `navigator-common`, `user-auth-module`, `business-agent-module`, `launcher`（route coverage test only）, version evidence.
- external_dependencies: none; real SIM/TMS inventory and owner/operator mapping are explicitly deferred to P1B-B.

## Non-Goals

- out_of_scope: S1/S2 principal, credential, platform-grant or tenant-authority seed/lifecycle write API; legacy route-family cutover; CLI/SKILL/operator UX; signed upstream-user assertion; Gateway client propagation; Worker or Codex routing; external/Worker/production enablement.
- do_not_touch: real credentials or secrets; `foggy-world-sim` and `tms-x3` workspaces; `packages/navigator-frontend/src/views/ClaudeWorkerView.vue`; Observer BFF runtime; Worker/BizWorkerIdentity/WorkerPool topology.

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| Exactly one typed source | Avoid header precedence and accidental privilege union | Only `X-Navi-Principal-Credential` or management bearer; legacy/admin/control/runtime/task/Worker material conflicts and is rejected before Controller |
| Default verifier absence denies | Avoid fixture behavior becoming a live bypass | No built-in verifier or seed; all stored credential envelope checks remain mandatory |
| Security action token is bound and single-use | Separate high-risk authorization from reusable control access | Exact action/target/impact/reason binding plus approval; atomically consume with compare-and-set |
| New namespace uses a canonical guard | Keep typed ingress independent of legacy and P1A shadow paths | Any mapped but unregistered route is denied before Controller |
| P1B-A is fixture-only | Do not claim upstream provisioning or production readiness | No real SIM/TMS identity, credential, grant, tenant authority or owner mapping is introduced |

## Acceptance Criteria

- [x] AC-1: exactly one typed source is parsed; missing, conflict, malformed and prohibited legacy/control/runtime/task/Worker sources are stable fail-closed pre-controller denies.
- [x] AC-2: resolver validates instance, environment, principal type, lane, status, expiry, generation, verifier and principal binding; unknown/mismatch/revoked/expired inputs deny.
- [x] AC-3: management bearer validates hash/reference, purpose, credential generation, instance/profile, status, expiry and binding; security action consumption is atomic and replay/concurrency-safe.
- [x] AC-4: only the five registered canonical auth endpoints are reachable through the typed guard; a mapped-but-unregistered management route is denied before Controller execution.
- [x] AC-5: exchange yields only short-lived `CONTROL_ACCESS`; security authorization yields only bound single-use `SECURITY_ACTION`; explain is `PREFLIGHT` and `nonBinding=true`; responses and object representations do not disclose secret material.
- [x] AC-6: fixture-only tests do not seed or print real SIM/TMS principals, grants, tenant authorities or credentials; no default runtime verifier exists.
- [x] AC-7: source/evidence manifest integrity and all required negative contracts have actually executed successfully.

## Contract / Data / Security Constraints

- API or event contract: only the five endpoints under `/api/v1/management/v1/auth/**`; no generic credential resolver or cross-lane compatibility endpoint.
- data and migration: uses the additive GOV-001 P1A authorization schema only; no business data seed or legacy automatic promotion.
- compatibility and rollback: existing legacy routes and all external/Gateway/Worker configuration remain unchanged; removing this slice's routes/wiring restores the prior no-management-route state.
- permissions and secrets: token/credential material is hash/reference based. A successful issuance returns only the newly issued opaque bearer once to the already authenticated caller; presented credential, token ID, token reference, verifier material and diagnostic `toString()` output are excluded or redacted. Errors do not reveal other-owner resource existence.

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| AC-1/AC-2 | critical | typed resolver, source conflict, lane/instance/profile/status/expiry/revocation/generation negatives | executed Surefire reports and full reactor run |
| AC-3 | critical | token purpose/binding/replay/concurrency and CAS repository contract | unit/integration reports and schema review |
| AC-4/AC-5 | critical | interceptor/config/controller/endpoint route and secret-redaction tests | route manifest/hash and MVC reports |
| AC-6/AC-7 | critical | forbidden-surface/secret scan, manifest comparison and full `launcher -am` test | scoped review, `git diff --check`, build result |

## Risks and Open Questions

- known_risks: no live verifier/secret-store, real principal mapping, owner/grant resolver, CLI UX, route cutover, signed user assertion, Gateway strict propagation or production infrastructure exists yet; this is intentional and remains fail closed.
- open_questions: none for P1B-A; P1B-B has separate inventory and owner/operator-approval gates.

## Ultra Execution Contract

- This slice is already implemented. Any correction that changes authentication semantics, real seed, credential lifecycle, route cutover, external flags, Worker topology or production boundary requires a separate approved work item.
- The implementation session may not self-accept this slice; its maximum state was `READY_FOR_SIGNOFF`.

## Implementation Result

- implementation_summary: Added a typed-management ingress guard, typed principal-credential and management-bearer verification, canonical enforcement decisions, control exchange, step-up/approval-bound security action issuance, request-local whoami/permissions and non-binding explain. `SECURITY_ACTION` consumption is an exact atomic compare-and-set. The default deployment has no credential or step-up verifier and therefore denies.
- changed_paths: `navigator-common/src/main/java/com/foggy/navigator/common/authorization/**`, authorization credential/principal/token entities and repositories, `user-auth-module` typed guard/config/tests, `business-agent-module` controller/service/forms/DTOs/tests, P1A route manifests and version documents. No legacy route family, real seed, CLI/SKILL, Open API/Gateway/Worker external setting, Worker/Codex route or Observer BFF runtime path changed.
- tests_and_results: 2026-07-19 `mvn test -pl launcher -am` completed `BUILD SUCCESS` across 14 reactor modules. Current focused Surefire reports: `TypedManagementAuthorizationServiceTest` 8/8; `TypedManagementAuthInterceptorTest` 66/66; `TypedManagementSecurityConfigTest` 3/3; unregistered MVC contract 1/1; `ManagementAuthEndpointServiceTest` 13/13; `ManagementAuthControllerTest` 3/3.
- manual_or_experience_evidence: source and evidence manifests are byte-identical: 420 entries/421 lines including header, SHA-256 `55cd6b2f67c98ace16fcc58334d9dfccb8f36d7045cd9f5eb5f6bd5ba58231f2`; five typed-management routes have explicit required sections and no duplicate route ID. `git diff --check` passed with only pre-existing CRLF conversion warnings; scoped high-confidence secret scan found no match.
- deviations: none from the approved fixture-only P1B-A contract.
- residual_risks: this slice is not real S1/S2 provisioning, Open API/Gateway/Worker readiness, Provider readiness or production readiness.
- readiness: ACCEPTED by independent signoff; this does not authorize real S1/S2 seed, credential issuance, route cutover, external/Gateway/Worker enablement or production use.

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted
- signed_off_by: Independent Signoff Reviewer (Codex)
- signed_off_at: 2026-07-19
- acceptance_record: `docs/version-tracker/1.4.3-SNAPSHOT/evidence/GOV-001-p1b-a-independent-signoff.md`
- blocking_items: none
- follow_up_required: yes (P1B-B inventory and owner/operator approval gate)

## References

- [GOV-001 parent delivery spec](./GOV-001-upstream-permission-and-trust-boundary.md)
- [P1A accepted repair](./BUG-002-p1a-required-section-contract.md)
- [Version index](../README.md)
