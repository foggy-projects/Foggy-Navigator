---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-017
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
bug_source: user-report
approved_by: project-owner-explicit-implementation-request
approved_at: 2026-07-23
open_questions: []
---

# Delivery Spec: runtime request audit without taskId

## Document Purpose

- intended_for: ultra-implementation / independent-signoff
- purpose: Add a task-independent, read-only, sanitized self-audit trail for ClientApp runtime-token and safe-ask request chains.
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-017-runtime-request-audit-no-task-id.md`

## Goal

- version_goal: Preserve fail-closed credential lanes while making failed or response-lost runtime requests diagnosable without a task, context, provider task, or successfully issued runtime access token.
- target_outcome: Every CLI safe-ask prints a non-secret UUID before networking, propagates it through runtime-token and safe-smoke, and can query bounded server audit evidence by request ID or a short time window using only the same ClientApp runtime credential lane.

## Scope

- in_scope:
  - Client request correlation for runtime-token and safe-ask, with no automatic safe-ask retry or fallback.
  - Persistent, short-retention, sanitized runtime request audit records and lifecycle stages.
  - A strictly read-only ClientApp self-audit endpoint and `navi upstream runtime audit` command.
  - Request-ID and bounded-window filters, stable tri-state/unknown output, small default limit, hard maximum, and explicit expired/not-found behavior.
  - CLI help, SDK models, route authorization manifest, operator documentation, release features, version/provenance, and clean source-matched packages.
  - Unit, controller/integration, CLI contract, and minimal live endpoint validation.
- affected_modules:
  - `business-agent-module`
  - `addons/claude-worker-agent`
  - `navigator-common`
  - `navigator-open-sdk`
  - `tools/navigator-upstream-cli`
  - `tools/navigator-upstream`
  - `docs/version-tracker/1.4.3-SNAPSHOT`
- external_dependencies: none; no sibling workspace change and no real upstream credential is required for automated validation.

## Non-Goals

- out_of_scope:
  - Replacing safe-ask with normal ask, changing ask semantics, or adding automatic retry/idempotency authorization.
  - Actuator metric exposure, raw traffic capture, prompt/model/Worker payload logging, or cross-ClientApp operator search.
  - Admin/control/platform audit fallback or widening runtime credential permissions beyond same-ClientApp read-only audit.
  - Any Worker, model, BusinessFunction, gateway, session, context, or normal task dispatch from an audit query.
- do_not_touch:
  - Existing user modifications under Codex Worker release tooling.
  - Sibling TMS/SIM workspaces, real profiles, credentials, accounts, business data, or historical runtime evidence.

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| Keep the existing dedicated safe-ask | It already creates terminal synthetic evidence with empty scopes and no runtime dispatch. | Normal ask remains unchanged; safe-ask never falls back to ask. |
| Authenticate self-audit with ClientApp key/secret | A failed token exchange must still be queryable without issuing another access token. | The endpoint only resolves/validates the credential read-only and rejects runtime access, control, admin, platform, task, and Worker credentials. |
| Derive tenant, upstream system, and ClientApp scope server-side | Caller-supplied owner selectors would permit horizontal probing. | Query accepts no tenant/upstream-system/ClientApp target override. |
| Treat correlation as observability only | A UUID must not become a replay or idempotency capability. | Duplicate IDs do not authorize retry; safe-ask is never automatically retried. |
| Store one sanitized aggregate plus ordered stage rows | Aggregate output is stable while stage history identifies the stopping point. | No raw headers, bodies, prompts, tokens, stack traces, payloads, or environment values are persisted. |
| Use explicit nullable Boolean/UNKNOWN semantics | Unknown must not be misreported as false. | JSON booleans remain booleans; unknown enum/text values are `UNKNOWN` and absent booleans remain null. |
| Default retention is short and configurable | Audit data is operational evidence, not long-term business history. | Default 24 hours, bounded query window 15 minutes, default limit 20, hard maximum 100, bounded write-triggered and scheduled expiry cleanup. |

## Acceptance Criteria

- [x] AC-1: CLI generates and prints `clientRequestId=<uuid>` before its first safe-ask network request and sends the same `X-Navigator-Client-Request-Id` on runtime-token and safe-smoke.
- [x] AC-2: Runtime-token and safe-smoke record sanitized received/issued-or-rejected/completed-or-failed stages; successful safe-smoke additionally records synthetic evidence creation and task-token revocation.
- [x] AC-3: `GET /api/v1/open/runtime-audits` and `navi upstream runtime audit` work without taskId/contextId/providerTaskId or an issued runtime access token, by exact request ID or a bounded time window.
- [x] AC-4: Audit authorization is same tenant + upstream system + ClientApp only, derives scope from a valid runtime key/secret, rejects other credential lanes, and performs no token issuance or execution-side mutation.
- [x] AC-5: Output contains every required stable field, preserves JSON booleans, returns null/`UNKNOWN` for unknown facts, and never folds unknown into false.
- [x] AC-6: Query validation enforces a 15-minute maximum window, bounded/default limit, no unbounded scan, operation allowlist, and explicit `AUDIT_RECORD_EXPIRED_OR_NOT_FOUND` for exact lookup misses.
- [x] AC-7: Persistence/query indexes cover ClientApp + time and exact correlation lookup; retention is configurable with a safe default and expired records are not returned.
- [x] AC-8: No stored or printed audit material includes secrets, tokens, authorization/API-key/header sets, prompts/messages, environment/workspace/business files, Worker/provider payloads, model responses, or raw stacks/bodies.
- [x] AC-9: Safe-ask still returns its existing terminal synthetic evidence on success; failure/response loss preserves the correlation ID, uses a stable sanitized error code, prints no raw HTTP body, performs no retry/fallback, and is never polled as a Worker task.
- [x] AC-10: Automated coverage includes all fifteen requested success/failure/isolation/retention/help cases, plus a minimal live Spring endpoint test proving no execution dispatch side effects.
- [x] AC-11: Top-level/runtime help, usage documentation, canonical route manifest, release features, version/provenance, package SHA-256, and copyable installation/query commands are complete.
- [x] AC-12: Release packages are built from a clean source-matched Git commit without staging or committing the user's pre-existing dirty paths.

## Contract / Data / Security Constraints

- API or event contract:
  - Header: `X-Navigator-Client-Request-Id` UUID, observability-only.
  - Read endpoint filters: `requestId` or `since` + `until`; optional operation/agentCode/upstreamUserId/limit.
  - Supported operation values: `runtime-token`, `safe-ask`.
  - Stable sanitized response fields are those listed in the owner request; taskId is explicitly nullable.
- data and migration:
  - Add JPA-managed audit aggregate/stage tables with controlled indexes; production `ddl-auto=validate` requires a documented DDL migration snippet/runbook update.
  - No secret/token lookup key is stored; correlation is a random non-secret UUID.
- compatibility and rollback:
  - Existing runtime-token, ask, safe-ask, diagnostics, and evidence commands remain compatible.
  - Rollback removes the new route/CLI command and stops new audit writes; short-retention rows can expire naturally.
- permissions and secrets:
  - Query endpoint accepts only key/secret runtime credential headers and rejects mixed or foreign credential lanes.
  - It never issues an access token and never creates task/context/session or dispatches Worker/model/BusinessFunction work.

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| AC-1/AC-9 | lost correlation or unsafe replay | SDK/CLI HTTP contract tests | exact tests for two-hop header equality, pre-network output, no retry/body leak, exit/error code |
| AC-2/AC-5 | misleading audit state | service/controller unit tests | lifecycle matrices including reject, pre-safe-smoke failure, response loss, revoked, false, unknown |
| AC-3/AC-4/AC-6/AC-7 | horizontal exposure/unbounded scan | repository/service/controller integration tests | request/window query, cross-scope rejection, lane rejection, retention and limits |
| AC-8 | secret/data disclosure | focused assertions and scoped source/output scan | forbidden marker test and scan result |
| AC-10 | route integration regression | `mvn test -pl addons/claude-worker-agent -am` plus focused SDK suite | command, counts, exit status |
| AC-11/AC-12 | unusable or untraceable release | package scripts and installed CLI smoke | version/buildId/commit/dirty/features/SHA and help output |

Validation order is focused tests, affected Maven lane, one final package/install smoke. Expected focused and module checks are under 30 minutes; no check is expected above 30 minutes. If the same full-lane check fails twice for environment-only reasons, set `NEEDS_REPLAN` before another expensive retry.

## Bug Context

- bug_source: user-report
- severity: major
- environment: `foggy-world-sim`, 2026-07-23T14:30:09+08:00, CLI 1.0.24 build `1.0.24+9fcb57faa871`.
- current_behavior: safe-ask exit 1 returns neither taskId nor terminal evidence, metrics are unavailable, and all existing diagnostics/evidence commands require taskId.
- expected_behavior: the pre-network correlation ID remains queryable through a strictly scoped audit endpoint even when no task exists or the client loses the response.
- reproduction_steps: run `navi upstream runtime safe-ask`, lose/reject either runtime-token or safe-smoke response, then attempt current diagnostics/evidence without a taskId.
- reproduction_status: confirmed by reported evidence and current CLI/API source contract.
- existing_evidence: owner-provided timestamp/version/provenance and current task-bound diagnostics/evidence implementation.
- existing_tests: safe-smoke empty-scope/no-dispatch tests and CLI safe-ask contract tests exist; task-independent request audit coverage does not.
- regression_protection: required
- waiver_reason_and_risk: N/A

## Risks and Open Questions

- known_risks:
  - A request that never reaches the server cannot create server evidence; an exact lookup miss must say expired-or-not-found and the operator compares it with the locally printed correlation ID.
  - If the presented runtime key/secret is itself invalid, self-audit authentication must fail closed; no alternate admin/control lane is introduced by this work.
- open_questions: none

## Ultra Execution Contract

- Read this work item, root/module guidance, and runtime-provisioning skill before implementation.
- Keep implementation inside the approved modules and preserve the existing safe-smoke no-runtime contract.
- Add regression tests before or alongside fixes where deterministic failure modes are reproducible.
- If implementation requires cross-ClientApp search, accepting broader credentials, logging raw traffic, changing safe-ask into ask, or adding retries, set `NEEDS_REPLAN` and stop that expansion.
- Record changed paths, exact checks, deviations, residual risks, package provenance, and installation evidence below; finish at `READY_FOR_SIGNOFF`, never `ACCEPTED`.

## Implementation Result

- implementation_summary:
  - Added a sanitized aggregate/stage audit model for correlated `runtime-token` and `safe-ask` chains, including retention, bounded cleanup, tri-state facts, terminal evidence, task-token revocation, and no-dispatch proof.
  - Added `GET /api/v1/open/runtime-audits`, authenticated only by the ClientApp runtime key/secret and scoped server-side to exact tenant + upstream system + ClientApp ownership.
  - Added `navi upstream runtime audit` with exact request-ID and bounded-window modes, stable key/value or JSON output, pre-network UUID correlation, stable sanitized failures, and no safe-ask retry/fallback.
  - Preserved the dedicated safe-ask implementation because it is a small synthetic terminal path with exact empty tool/function scopes, immediate task-token revocation, and `runtimeDispatched=false`; normal ask remains the real Worker/model execution path.
  - Added migration/rollback SQL, route-manifest provenance, help/runbook/release features, CLI 1.0.25 packages, and a refreshed tracked CLI snapshot.
- changed_paths:
  - Backend/controller:
    - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiController.java`
    - `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiControllerMessageMappingTest.java`
    - `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/ClientAppRuntimeCredentialResolver.java`
    - `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/RuntimeRequestAuditProperties.java`
    - `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/RuntimeRequestAuditService.java`
    - `business-agent-module/src/main/java/com/foggy/navigator/business/agent/model/dto/RuntimeRequestAuditDTO.java`
    - `business-agent-module/src/main/java/com/foggy/navigator/business/agent/model/dto/RuntimeRequestAuditPageDTO.java`
    - `business-agent-module/src/main/java/com/foggy/navigator/business/agent/model/dto/RuntimeRequestAuditStageDTO.java`
    - `business-agent-module/src/main/java/com/foggy/navigator/business/agent/model/entity/RuntimeRequestAuditEntity.java`
    - `business-agent-module/src/main/java/com/foggy/navigator/business/agent/model/entity/RuntimeRequestAuditStageEntity.java`
    - `business-agent-module/src/main/java/com/foggy/navigator/business/agent/repository/RuntimeRequestAuditRepository.java`
    - `business-agent-module/src/main/java/com/foggy/navigator/business/agent/repository/RuntimeRequestAuditStageRepository.java`
    - `business-agent-module/src/test/java/com/foggy/navigator/business/agent/service/RuntimeRequestAuditServiceTest.java`
  - Authorization/provenance:
    - `navigator-common/src/main/java/com/foggy/navigator/common/authorization/AuthorizationRouteCatalog.java`
    - `navigator-common/src/main/resources/authorization/route-manifest-v1.csv`
    - `navigator-common/src/test/java/com/foggy/navigator/common/authorization/AuthorizationContractTest.java`
    - `navigator-common/src/test/java/com/foggy/navigator/common/authorization/AuthorizationRequiredSectionCatalogRegressionTest.java`
    - `docs/version-tracker/1.4.3-SNAPSHOT/evidence/GOV-001-p0.5-method-route-manifest.csv`
  - SDK/CLI/release:
    - `navigator-open-sdk/pom.xml`
    - `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/api/AgentApi.java`
    - `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/api/BusinessAgentApi.java`
    - `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/cli/CliArguments.java`
    - `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/cli/UpstreamCli.java`
    - `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/model/businessagent/RuntimeRequestAuditDTO.java`
    - `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/model/businessagent/RuntimeRequestAuditPageDTO.java`
    - `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/model/businessagent/RuntimeRequestAuditStageDTO.java`
    - `navigator-open-sdk/src/main/resources/com/foggy/navigator/sdk/cli/authorization-provenance.properties`
    - `navigator-open-sdk/src/test/java/com/foggy/navigator/sdk/cli/UpstreamCliTest.java`
    - `tools/navigator-upstream-cli/dist/package.sh`
    - `tools/navigator-upstream-cli/dist/package.ps1`
    - `tools/navigator-upstream/BUILD_INFO.json`
    - `tools/navigator-upstream/RELEASE_MANIFEST.json`
    - `tools/navigator-upstream/VERSION`
    - `tools/navigator-upstream/lib/navigator-open-sdk-1.0.18.jar` (removed from the active snapshot; recoverable copy retained under task test artifacts)
    - `tools/navigator-upstream/lib/navigator-open-sdk-1.0.25.jar`
  - Migration/documentation:
    - `docs/migration/2026-07-23-runtime-request-audit.sql`
    - `docs/migration/2026-07-23-runtime-request-audit-rollback.sql`
    - `docs/version-tracker/1.4.3-SNAPSHOT/README.md`
    - `docs/version-tracker/1.4.3-SNAPSHOT/runbooks/BUG-017-runtime-request-audit.md`
    - `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-017-runtime-request-audit-no-task-id.md`
- tests_and_results:
  - `mvn test -pl addons/claude-worker-agent -am -Dtest=RuntimeRequestAuditServiceTest,OpenApiControllerMessageMappingTest -Dsurefire.failIfNoSpecifiedTests=false` -> PASS; audit service 8/8 and controller 55/55.
  - `mvn test -pl navigator-common -Dtest=AuthorizationContractTest,AuthorizationRequiredSectionCatalogRegressionTest,AuthorizationRequiredSectionValidationTest,AuthorizationDecisionAuditDraftTest` -> PASS; 18/18.
  - `mvn test -pl addons/claude-worker-agent -am` -> PASS; 8-module reactor, 2,039 tests, 0 failures, 0 errors, 3 skipped.
  - `mvn -f navigator-open-sdk/pom.xml test` -> PASS; 184 tests, including 143 CLI contract/live-loopback HTTP tests, 0 failures/errors.
  - `mvn package -pl launcher -am -DskipTests` -> PASS; 14-module reactor, packaged the deployable launcher at 2026-07-23T18:34:37+08:00.
  - `bash -n tools/navigator-upstream-cli/dist/package.sh tools/navigator-upstream-cli/dist/install.sh` -> PASS.
  - `command -v pwsh` -> unavailable in the current Linux environment; Windows package generation and SHA validation passed, but `package.ps1`/Windows installation were not executed locally.
  - `git diff --check` -> PASS; only existing CRLF-to-LF normalization warnings, no whitespace errors.
  - Scoped audit DTO/entity forbidden-field scan -> PASS across 8 files; controller/CLI tests also assert raw body, token-shaped exception text, credential headers, and prompt/secret markers are not emitted.
  - Clean package/install smoke -> PASS for `navi version`, top-level help, runtime help, three release features, and installed 1.0.25 jar selection.
  - `docs/migration/2026-07-23-runtime-request-audit.sql` -> APPLIED to the local `coding_agent` database; both audit tables and the exact-request, scoped-time, expiry, and stage-time indexes were verified.
  - Public OBS re-download -> PASS; Linux and Windows archives both matched `latest.json` SHA-256, and the public Linux installer produced CLI 1.0.25 with the expected version/provenance and runtime-audit help contract.
- manual_or_experience_evidence:
  - clean release commit: `61ad20bd3cdaee83cbf45abed3892527f6708411`
  - version/build: `1.0.25`, `1.0.25+61ad20bd3cda`, `gitDirty=false`
  - Linux package SHA-256: `3db939a3de082704e019ac5ba88618f7d787bffeeb5b47c04a779bc6fe7acb70`
  - Windows package SHA-256: `7ade4de3d9e29c4a8c77a413f83b041eb47406269010fdb615826ff7761a2eda`
  - package/install evidence root: `temp/test-artifacts/BUG-017-runtime-audit-release-aaAbnBVX/`
  - public re-download/install evidence root: `temp/test-artifacts/BUG-017-runtime-audit-remote-F641zz/`
  - generated release output: `tools/navigator-upstream-cli/dist/output/`
  - release features: `runtime-request-audit`, `safe-ask-client-request-correlation`, `runtime-audit-no-task-id`
  - release publication: clean source commit fast-forwarded to `origin/main`; Linux archive, Windows archive, `latest.json`, `install.sh`, and `install.ps1` returned successful OBS uploads on 2026-07-23.
  - local deployment: launcher PID `2552943` started at 2026-07-23T18:34:49+08:00 from the newly packaged jar; `/actuator/health` and MySQL are `UP`.
  - required runtime services: Navigator backend 8112, Claude Worker 3031, Codex Worker 3051, Gemini Worker 3072, local Biz Worker 3061, and WSL Biz Worker 3161 all reported ready/up.
  - SIM readiness/owner-smoke: `world-sim-order-clerk-v2-dev-20260716-a` passed explicit readiness and owner-smoke for tenant `tenant_upstream_sandbox`, upstream system `foggy-world-sim`, modelConfig `ec356713-1d8e-41a5-920b-71ccf63133ff`, and directory `20260716-8b89`; no resource mutation or sibling-workspace change was needed.
  - live safe-ask: `clientRequestId=8032ca98-bb34-4844-848b-95beaab154e9`, synthetic `taskId=smk_f62e1cbfb4f3475bbaef270b696a47e2`, status `COMPLETED`, task token `REVOKED`, and `runtimeDispatched=false`.
  - live exact and bounded-window audit: both returned one sanitized terminal record with all seven expected lifecycle stages, zero effective tools/functions, no Worker/model/BusinessFunction dispatch, and no taskId dependency; evidence root `temp/test-artifacts/BUG-017-runtime-audit-live-20260723/`.
- deviations:
  - Existing SIM runtime resources were already correctly published and ready, so validation intentionally did not recreate or mutate Agent, ClientApp grant, model grant, Directory, WorkerHost, or BusinessFunction routes.
  - No SIM source, profile, credential, account, or business-data file was modified.
  - The historical 2026-07-23T14:30:09+08:00 request predates this server audit and cannot be reconstructed retroactively; the documented window query becomes authoritative for requests made after deployment/replay.
- residual_risks:
  - Apply `docs/migration/2026-07-23-runtime-request-audit.sql` before deploying this server build into any additional environment that uses Hibernate schema validation; the current local Navigator database is already migrated.
  - A request that truly never reaches Navigator and an already-expired/unknown request intentionally share `AUDIT_RECORD_EXPIRED_OR_NOT_FOUND`; operators distinguish them using local correlation/time evidence and configured retention.
  - Run the generated Windows archive through `install.ps1` on a PowerShell-capable host before a Windows-specific rollout; this workspace has no `pwsh` executable.
- readiness: READY_FOR_SIGNOFF; implementation, local deployment, SIM resource readiness, public CLI publication, and live runtime-audit validation are complete. The implementing session does not self-assign `ACCEPTED`.

## References

- requirement / issue: project owner request dated 2026-07-23
- related runtime contract: `GOV-001-dev-s1-s2-integration-mvp.md`
- CLI usage baseline: `../../1.1.3-SNAPSHOT/upstream-integration/18-navigator-upstream-cli-usage-guide.md`
