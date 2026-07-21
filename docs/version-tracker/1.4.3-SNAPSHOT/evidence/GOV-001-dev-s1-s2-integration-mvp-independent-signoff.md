---
acceptance_scope: feature
version: 1.4.3-SNAPSHOT
target: GOV-001-DEV-MVP
status: signed-off
decision: accepted
signed_off_by: Independent Signoff Reviewer (Codex)
signed_off_at: 2026-07-20
reviewed_by: Independent code and evidence audit
blocking_items: []
follow_up_required: yes
evidence_count: 10
---

# GOV-001 Development S1/S2 Integration MVP Independent Signoff

## Document Purpose

- intended_for: Project Owner / SIM and TMS upstream owners / future reviewers
- purpose: 对开发期 S1/S2 本地联调 MVP 的实现、测试和边界形成独立、可复核的签收结论。

## Background

- delivery_spec: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/GOV-001-dev-s1-s2-integration-mvp.md`
- target_outcome: 受授权的 TMS upstream-admin 可列出派生动态 tenant ClientApp；CLI、SKILL 与手册明确 bootstrap/control/runtime lane 和本地联调前置条件。
- signoff_scope: 仅本 MVP 的 ClientApp list、CLI help、runtime-provisioning SKILL、runbook 与版本文档。真实 profile、SIM/TMS runtime、Gateway/Worker external、production、sibling workspace 和 Codex Physical Worker 路由均不属于本签收。

## Acceptance Basis

- approved delivery spec: 该 canonical delivery spec 在审计开始时为 `READY_FOR_SIGNOFF`，含 AC-1 至 AC-5；本记录完成独立签收回写。
- changed paths / diff: `git status --short --branch` 和 path-scoped diff 显示变更仅在 `business-agent-module`、`navigator-open-sdk`、`.agents/skills/navigator-runtime-provisioning` 与 `docs/version-tracker/1.4.3-SNAPSHOT`；没有 launcher、external/Gateway 配置、Worker 路由、schema 或 sibling 路径变更。
- independent test records:
  - `mvn test -pl business-agent-module -am -Dtest=UpstreamClientAppManagementServiceTest,UpstreamClientAppAdminCredentialServiceTest -Dsurefire.failIfNoSpecifiedTests=false` — reviewer executed; 21 tests, 0 failures/errors/skips, `BUILD SUCCESS`.
  - `mvn test -pl business-agent-module -am -Dtest=BusinessFunctionRuntimeAuditRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false` — reviewer executed; 2 tests, 0 failures/errors/skips; `@DataJpaTest` scanned 33 JPA repositories and created the new derived query without a query-creation error.
  - `mvn test -pl navigator-open-sdk -am -Dtest=UpstreamCliTest -Dsurefire.failIfNoSpecifiedTests=false` — reviewer executed; 125 tests, 0 failures/errors/skips, `BUILD SUCCESS`.
  - `mvn package -pl navigator-open-sdk -am -DskipTests` — reviewer executed; `BUILD SUCCESS`, source JAR `navigator-open-sdk-1.0.21.jar` produced.
  - `env -i ... java -cp <source-jar>:<local-libs> com.foggy.navigator.sdk.cli.UpstreamCli upstream client-app --help` — reviewer executed with no `NAVI_*` environment; help contains the upstream-admin and runtime-only profile boundary text.
- hygiene: current `git diff --check` passed (only shared-tree CRLF conversion warnings); both new Markdown files passed whitespace check. Changed lines and a literal credential-assignment scan found no plaintext credential value.
- experience evidence: source/help/runbook review only. No profile was read, no service was started, and no SIM/TMS readiness, owner-smoke or live ask was executed.
- migration / compatibility: no schema or data migration; explicit `tenantId` remains a single-tenant repository query. The unfiltered candidate query stays constrained by existing `upstreamSystemId + upstreamClientAppNamespace` index-leading fields.

## Contract Conformance

| Item | Expected | Delivered evidence | Result |
|---|---|---|---|
| AC-1 | Unfiltered list exposes only authorized dynamic Navigator tenants and remains fail-closed | `UpstreamClientAppManagementService.listClientApps` returns before repository access for an empty authorization set; otherwise queries only the caller's upstream and namespace and applies existing `isTenantAuthorized` per result. System-scoped allow and source-tenant isolation regressions passed. | pass |
| AC-2 | Explicit dynamic tenant stays authorized and exact; denial performs no resource query | Explicit branch calls `requireTenant` before the existing single-tenant `tenantIdIn` repository query. Exact-query and unauthorized/no-query regressions passed. | pass |
| AC-3 | CLI, SKILL and runbook preserve lane, profile, SIM and external/Gateway/Codex boundaries | Help test and independently run source-JAR help contain upstream-admin/control/runtime-only guidance. SKILL and runbook preserve legacy-SIM dev-only, combined-profile split, Open API-only, Gateway-disabled and existing-Physical-Worker-only rules. | pass |
| AC-4 | Runnable secret-free local order and truthful runtime preconditions | Runbook requires a private bootstrap profile, a separately delivered runtime-only profile, `activationReady=true`, readiness and owner-smoke before safe ask. It explicitly defers live smoke to upstream owners. | pass |
| AC-5 | Targeted tests pass and no prohibited surface changed | All three targeted test invocations passed (148 tests total, including 2 JPA tests); diff/hygiene review found no external flag, Gateway, Worker route, schema or sibling-workspace change. | pass |

## Implementation Quality

- scope and changed surface: the new repository method has no `findAll()` path and is limited to the declared upstream/namespace candidate set. The implementation and documentation surfaces match the canonical changed-path list.
- maintainability and duplication: the unfiltered branch reuses the existing `isTenantAuthorized` predicate instead of duplicating dynamic-tenant parsing. Explicit query behavior remains isolated in its former path.
- error handling and edge cases: missing/empty authorization fails closed before a repository access; unauthorized explicit tenant is rejected before query. Existing predicate retains exact, system, source-tenant and qualified-source authorization semantics.
- contract, data and compatibility: API path and DTO contract are unchanged; no migration is introduced. Future pagination of the bounded candidate query remains a separately recorded work item.
- terminology and documentation: upstream-admin, ClientApp control and runtime terminology is aligned across CLI help, SKILL and runbook. The corrected runbook accurately says credentials are never printed in plaintext and only masked diagnostics may appear.

## Evidence Matrix

| Item | Risk | Unit | Integration | E2E | Playwright | Manual | Evidence | Coverage |
|---|---|---|---|---|---|---|---|---|
| AC-1 | critical cross-tenant visibility | System allow, source isolation and empty-authority/no-query cases passed | 33-repository JPA startup passed | not required by local MVP contract | not applicable | code/diff review | management and credential tests; service/repository review | covered |
| AC-2 | critical unauthorized disclosure | Explicit exact-query and deny/no-query cases passed | 33-repository JPA startup passed | not required | not applicable | code/diff review | service test plus credential predicate review | covered |
| AC-3 | major credential-lane misuse | `UpstreamCliTest` 125/125 including help assertion | source JAR package/help passed | no live CLI credential operation required | not applicable | SKILL/runbook review | CLI output, test and docs | covered |
| AC-4 | major false runtime readiness | not applicable | not applicable | deferred by approved contract | not applicable | secret-free runbook review | runtime-only delivery and readiness/owner-smoke gates documented | covered |
| AC-5 | critical scope or secret expansion | target module tests passed | JPA context startup passed | not required | not applicable | changed-surface/hygiene review | `git diff --check`, changed paths and credential scan | covered |

## Failed Items

- none

## Risks / Follow-ups

- SIM/TMS readiness, owner-smoke and a safe ask are intentionally not accepted here. Each upstream owner must use its own gitignored runtime-only profile and actual runtime tuple before claiming live readiness.
- `ensure-tenant` still writes control and runtime credentials into one platform-private bootstrap profile. The platform-side runtime-only split remains manual; automatic delivery needs a separately approved work item.
- The bounded upstream/namespace list query is not paginated. If its candidate set needs pagination, create a new scoped work item rather than widening this implementation.

## Final Decision

- decision: accepted
- rationale: all five acceptance criteria have direct code/diff review and independently executed, passing validation. No scope deviation, security-boundary relaxation, plaintext secret, external/Gateway/Worker-route change or missing critical evidence was found. The outstanding runtime smoke is expressly outside this local-preflight MVP and remains recorded as a follow-up, not evidence of live or production readiness.
- blocking_items: none for GOV-001-DEV-MVP
- follow_up_owner_and_due: SIM and TMS upstream owners, before any readiness/owner-smoke/safe-ask or external/production claim.

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted
- signed_off_by: Independent Signoff Reviewer (Codex)
- signed_off_at: 2026-07-20
- acceptance_record: `docs/version-tracker/1.4.3-SNAPSHOT/evidence/GOV-001-dev-s1-s2-integration-mvp-independent-signoff.md`
- blocking_items: none
- follow_up_required: yes
