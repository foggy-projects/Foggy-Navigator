---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-017
status: ULTRA_EXECUTING
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

- [ ] AC-1: CLI generates and prints `clientRequestId=<uuid>` before its first safe-ask network request and sends the same `X-Navigator-Client-Request-Id` on runtime-token and safe-smoke.
- [ ] AC-2: Runtime-token and safe-smoke record sanitized received/issued-or-rejected/completed-or-failed stages; successful safe-smoke additionally records synthetic evidence creation and task-token revocation.
- [ ] AC-3: `GET /api/v1/open/runtime-audits` and `navi upstream runtime audit` work without taskId/contextId/providerTaskId or an issued runtime access token, by exact request ID or a bounded time window.
- [ ] AC-4: Audit authorization is same tenant + upstream system + ClientApp only, derives scope from a valid runtime key/secret, rejects other credential lanes, and performs no token issuance or execution-side mutation.
- [ ] AC-5: Output contains every required stable field, preserves JSON booleans, returns null/`UNKNOWN` for unknown facts, and never folds unknown into false.
- [ ] AC-6: Query validation enforces a 15-minute maximum window, bounded/default limit, no unbounded scan, operation allowlist, and explicit `AUDIT_RECORD_EXPIRED_OR_NOT_FOUND` for exact lookup misses.
- [ ] AC-7: Persistence/query indexes cover ClientApp + time and exact correlation lookup; retention is configurable with a safe default and expired records are not returned.
- [ ] AC-8: No stored or printed audit material includes secrets, tokens, authorization/API-key/header sets, prompts/messages, environment/workspace/business files, Worker/provider payloads, model responses, or raw stacks/bodies.
- [ ] AC-9: Safe-ask still returns its existing terminal synthetic evidence on success; failure/response loss preserves the correlation ID, uses a stable sanitized error code, prints no raw HTTP body, performs no retry/fallback, and is never polled as a Worker task.
- [ ] AC-10: Automated coverage includes all fifteen requested success/failure/isolation/retention/help cases, plus a minimal live Spring endpoint test proving no execution dispatch side effects.
- [ ] AC-11: Top-level/runtime help, usage documentation, canonical route manifest, release features, version/provenance, package SHA-256, and copyable installation/query commands are complete.
- [ ] AC-12: Release packages are built from a clean source-matched Git commit without staging or committing the user's pre-existing dirty paths.

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

- implementation_summary: pending
- changed_paths: pending
- tests_and_results: pending
- manual_or_experience_evidence: pending
- deviations: none
- residual_risks: pending
- readiness: pending

## References

- requirement / issue: project owner request dated 2026-07-23
- related runtime contract: `GOV-001-dev-s1-s2-integration-mvp.md`
- CLI usage baseline: `../../1.1.3-SNAPSHOT/upstream-integration/18-navigator-upstream-cli-usage-guide.md`
