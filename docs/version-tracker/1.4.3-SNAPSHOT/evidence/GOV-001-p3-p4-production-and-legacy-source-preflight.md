---
doc_type: source-evidence-inventory
version: 1.4.3-SNAPSHOT
tickets:
  - GOV-001-P3
  - GOV-001-P4
status: SOURCE_OBSERVED_NO_RUNTIME_OR_PRODUCTION_CLAIM
observed_at: 2026-07-19
scope: repository-source-only
decision_authority: none
---

# GOV-001 P3/P4 Production and Legacy Source Preflight

## Boundary and Method

- This is static source evidence for the blocked P3 and P4 decision gates. It does not authorize code, configuration, an external flag, strict Gateway mode, legacy removal, or a production claim.
- Only tracked source and version documentation were inspected. No profile, environment, account, credential, database, service, deployment, traffic, or upstream-system data was read.
- Configuration/header names below are contract identifiers, never secret values. An absence in source is not proof about deployed infrastructure or live callers.

## P3 Production-boundary Facts

| Area | Source-observed fact | Consequence |
| --- | --- | --- |
| Startup guard | `ProductionConfigurationGuard` checks selected DDL, bean override, datasource, secret, external URL, Actuator, health, payload-store and deployment-identity conditions (`launcher/src/main/java/com/foggy/navigator/launcher/ProductionConfigurationGuard.java:42-67,76-161`). | This is a partial fail-fast guard, not evidence for ingress, TLS, trusted proxy, KMS/broker, Worker isolation, reliable audit delivery, migration rehearsal, or promotion ownership. |
| Production DDL baseline | The production YAML sets `ddl-auto=validate` (`launcher/src/main/resources/application-prod.yml:11-14`). | A source setting does not prove database migration, rollback, or deployed validation evidence. |
| CORS / proxy boundary | `WebMvcConfig`, `CorsConfig`, and `SecurityConfig` still contain wildcard-origin behaviour together with credential support (`user-auth-module/src/main/java/com/foggy/navigator/auth/config/WebMvcConfig.java:53-61`; `CorsConfig.java:17-42`; `SecurityConfig.java:149-173`). No scoped CORS/trusted-proxy negative test was found. | P3 must retain an approved ingress, exact-origin, trusted-proxy and CSRF/session design gate. |
| Codex SDK Worker | External-mode state always adds `EXTERNAL_EXECUTION_POLICY_PENDING` when external intent is enabled, and middleware returns 503 while it is unready (`tools/codex-agent-worker/src/external-mode.ts:21-50`). | A Worker token or external intent flag is not external execution or production readiness. |
| Codex app-server Worker | Its auth guard returns 503 when external intent is enabled but external readiness remains false (`tools/codex-app-server-worker/src/auth.ts:12-18`; `external-mode.ts:20-32`). | The app-server is not a production ingress path merely because an external flag is set. |
| LangGraph Biz Worker | Tests prove that external intent with a configured token still returns 503 because execution policy remains pending (`tools/langgraph-biz-worker/tests/test_auth.py:72-118`). | Do not remove readiness denial or use health as an approval surrogate. |
| Authorization audit | P1A source records append-only authorization decisions through the local store/repository (`navigator-common/src/main/java/com/foggy/navigator/common/authorization/AuthorizationDecisionAuditStoreImpl.java:21-35`; `.../repository/AuthorizationDecisionRepositoryImpl.java:17-28`). | No GOV-001 evidence establishes an immutable external sink, outbox, retry, ordering/deduplication, outage handling, retention or independent audit access. P3 remains blocked. |

These facts preserve the separation among `NAVIGATOR_EXTERNAL_ENABLED` (Open API route gate), `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED` (strict Worker-principal requirement), per-Worker external intent, and production approval. None is a shortcut for another.

## P4 Legacy-retirement Facts

| Surface | Source-observed fact | Retirement implication |
| --- | --- | --- |
| Legacy upstream-admin header | `UpstreamClientAppAdminCredentialService` reads `X-Navi-Admin-Key` and validates its hashed, active, revoked, expiry and scope state (`business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/UpstreamClientAppAdminCredentialService.java:28,38-85,123-175`). The retired `X-Navi-Admin-Api-Key` remains rejected by test (`.../UpstreamClientAppAdminCredentialServiceTest.java:53-65`). | The active legacy header needs a caller/route/version inventory before any removal; the retired header must not be resurrected as fallback. |
| Long-lived legacy key option | CLI help permits `--claim-ttl-minutes 0|-1` (`navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/cli/UpstreamCli.java:412-425`), and the bootstrap service maps it to `expiresAt=null` (`business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/UpstreamBootstrapRequestService.java:424-439`). | Any cutoff needs a migration/exception plan and telemetry; source alone cannot identify active users. |
| Control-to-runtime compatibility | `UpstreamClientAppAdminCredentialService` still allows `CLIENT_APP_MANAGE` to satisfy `CLIENT_APP_RUNTIME_KEY_ISSUE` (`business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/UpstreamClientAppAdminCredentialService.java:210-219`) with a retained test (`.../UpstreamClientAppAdminCredentialServiceTest.java:80-95`). | This is a route-family compatibility concern, not a reason to merge control and runtime lanes or to remove the bridge without evidence. |
| Typed-management separation | Typed CLI rejects legacy-only and mixed-lane sources before HTTP dispatch (`navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/cli/UpstreamCli.java:3684-3711`). | Typed CLI fail-closed behaviour is not telemetry that proves legacy callers have migrated. |
| Artifact provenance | CLI provenance reports source/published artifact drift without a release claim (`navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/cli/CliProvenance.java:7-44`; `navigator-open-sdk/src/main/resources/com/foggy/navigator/sdk/cli/authorization-provenance.properties:1-6`). | Installed artifact versions must be inventoried before deprecating or retiring an old help/API surface. |
| Canonical governance | The parent contract fixes legacy-header semantics and requires a separately approved work item, verified zero-use window and rollback before removal (`workitems/GOV-001-upstream-permission-and-trust-boundary.md:597-611,631-640`). | P4 cannot start from a source-only zero-call assumption. |

## Explicit No-claim Boundaries

- This does not establish actual legacy caller count, installed artifact version, traffic, retention compliance, audit sink availability, KMS custody, proxy topology, Worker execution policy, or production readiness.
- It does not authorize removal/relaxation of any legacy path, acceptance of a deprecated header, a Gateway strict flip, an external flip, Worker topology change, or third-party onboarding.
- `NAVI_ADMIN_API_KEY` / `X-Navi-Admin-Key` remains only legacy upstream-system-admin authority. It cannot become S1 instance root, S2 platform/security-admin, runtime, task, Worker, or production-promotion authority by compatibility or documentation.
- In particular, it does not create a Worker, BizWorkerIdentity, or WorkerPool member. Codex remains an existing Physical Worker routing concern.

## Safe Preparation Before Architecture Approval

1. Build a sanitized legacy caller/header/lane/route/artifact inventory with a named source owner; do not collect secrets or request bodies.
2. Approve a privacy-safe telemetry contract containing route family, outcome/reason, client build and bounded retention, then prove sink/outage behaviour.
3. Prepare a route-family compatibility-window, notice, exception-expiry, rollback-threshold and kill-switch ownership matrix.
4. Prepare an independent release evidence matrix, but keep P3/P4 `DRAFT + BLOCKED` until the architecture/infrastructure owners provide the required decisions and deployed evidence.

## Reproducibility

The inventory was formed from scoped `rg` searches and line-numbered reads of the cited source and version documents. It intentionally excluded profiles, credential stores, runtime endpoints, databases, deployment configuration, traffic systems and sibling upstream repositories.
