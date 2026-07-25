---
acceptance_scope: bug
version: 1.4.3-SNAPSHOT
target: BUG-018-runtime-standard-ask-audit-correlation-time-basis
status: signed-off
decision: accepted
signed_off_by: independent-root-signoff
signed_off_at: 2026-07-25
reviewed_by: independent-root-signoff
blocking_items: []
follow_up_required: no
evidence_count: 15
assurance_level: standard
---

# BUG-018 Delivery Signoff

## Document Purpose

- intended_for: signoff-owner / project-root-session
- purpose: Independently verify the STANDARD ask request-audit/correlation and time-basis delivery against the approved BUG-018 contract.

## Background

- delivery_spec: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-018-runtime-standard-ask-audit-correlation-time-basis.md`
- target_outcome: Runtime-only Java/application callers can correlate and audit STANDARD asks before task creation through terminal token revocation, without taskId or privileged credentials, with an explicit UTC/Asia-Shanghai time contract.
- signoff_scope: implementation commit `d1dd1daef7e09945ace89aff56fd7c8fccb92919`, clean launcher artifact, CLI `1.0.32`, migration, deployed health, OBS release, and focused test evidence.
- critical_outcomes: request correlation before network I/O; task-ID-independent audit; pre-task failure evidence; admission scope persistence; terminal/revocation visibility; zero audit side effects; explicit time basis; clean published artifacts.
- non_blocking_or_waivable_items: none.

## Acceptance Basis

- approved delivery spec: canonical BUG-018 work item, explicitly approved by the project owner.
- changed paths / diff: 45 implementation, test, migration, CLI, manifest, and documentation files in commit `d1dd1dae`; `git show --check` passed.
- test records:
  - Clean-clone focused backend run passed on 2026-07-25: Common 2, Business 32, Claude Worker/OpenAPI 93, Launcher 1; zero failures/errors/skips.
  - Clean-clone SDK/CLI run passed on 2026-07-25: 182 tests; zero failures/errors/skips.
- experience evidence:
  - Deployed `/actuator/health` is `UP`; `/actuator/info` reports the expected clean implementation commit.
  - OBS `latest.json` reports CLI `1.0.32`, the expected commit, `gitDirty=false`, and the expected Linux SHA-256; remote installation smoke passed.
- migration / compatibility evidence:
  - Forward and rollback SQL are present.
  - Forward migration applied without synthesizing historical request evidence.
  - Schema inspection confirmed eight new columns and the required non-null correlation key.
  - Deployed server started successfully with `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`.

## Contract Conformance

| Item | Expected | Delivered | Evidence | Result |
|---|---|---|---|---|
| AC-1 | UUID before first network request | SDK generates IDs before HTTP construction/send | `javaApplicationPathGeneratesRequestIdsBeforeNetworkAndCorrelatesTokenToAsk` | pass |
| AC-2 | Unambiguous token/ask correlation | Runtime-token root plus ask child and exchange count | SDK/service correlation tests and frozen contract | pass |
| AC-3 | Exact and bounded-window lookup without taskId | Both query forms are owner-scoped and task-ID independent | `correlatesRuntimeTokenParentWithStandardAskAndQueriesByRequestIdAndWindow` | pass |
| AC-4 | Pre-task failures remain queryable | Nullable taskId and explicit negative stages | `preTaskFailureRemainsQueryableWithNullTaskIdAndExplicitNotStages` | pass |
| AC-5 | Empty scopes fixed at admission | Requested/effective tool and function facts persist through terminal state | runtime request audit lifecycle test | pass |
| AC-6 | Complete sanitized facts/stages | Detailed DTO, `taskFacts`, and `auditSideEffects` | service/CLI serialization assertions | pass |
| AC-7 | Audit has zero runtime side effects | All side-effect booleans false; no token/task/dispatch calls | `runtimeAuditEndpointSupportsNoTaskIdAndHasNoRuntimeSideEffects` | pass |
| AC-8 | Terminal/revocation refresh without redispatch | Terminal listener updates request audit and preserves counters | `terminalEventMakesRevocationAndTerminalStateVisibleWithoutChangingDispatchCounters` | pass |
| AC-9 | Readiness exposes time basis | Four time fields added to server and SDK DTOs | readiness service tests and runtime help | pass |
| AC-10 | UTC/Shanghai boundary correctness | Deterministic cross-midnight and JDBC UTC tests | `IdGeneratorTest`, `RuntimeTimeBasisConfigurationTest` | pass |
| AC-11 | Terminal state visible to read callers | Durable ABORTED overrides stale Worker WORKING | `durableAbortedProjectionOverridesStaleWorkerWorkingStatus` and documented polling contract | pass |
| AC-12 | No sensitive payload returned/stored | Sanitized schema/DTO and negative serialization assertions | request-audit serialization test and source review | pass |
| AC-13 | Manifest/help advertise capability | New feature flags and runtime help text published | CLI package manifest and help smoke | pass |
| AC-14 | Clean release, migration validate, health UP | Clean launcher and CLI artifacts deployed/published | provenance, SHA-256, OBS receipt, actuator evidence | pass |
| AC-15 | No frozen history/SIM mutation | Only Navigator source/schema/release/deployment changed | command history and scoped diff review | pass |

## Implementation Quality

- scope and changed surface: Changes follow existing SDK/controller/service/entity boundaries; no unrelated dirty worktree paths were included.
- maintainability and duplication: Correlation and audit lifecycle logic is centralized in `RuntimeRequestAuditService`; SDK header propagation is centralized in the existing HTTP helper.
- error handling and edge cases: Missing/cross-owner/unissued parent evidence fails closed; taskId-null failures, terminal replay facts, time-window bounds, and stale Worker state are covered.
- contract, data and compatibility: Existing explicit-ID SDK paths remain available; migration is additive with rollback; no implicit retry/recovery/reconcile/dispatch behavior was introduced.
- terminology and documentation: Runtime-token root, ask child, request facts, audit side effects, UTC storage, and Asia/Shanghai task-ID date are consistently named in code, help, migration, and the canonical work item.

## Evidence Matrix

| Item | Classification | Risk | Evidence | Reused / New | Result |
|---|---|---|---|---|---|
| AC-1–AC-2 | core-blocker | major | clean-clone SDK loopback tests | new signoff rerun | pass |
| AC-3–AC-8 | core-blocker | major | clean-clone service/controller/lifecycle tests | new signoff rerun | pass |
| AC-9–AC-11 | core-blocker | major | deterministic time/readiness/task projection tests | new signoff rerun | pass |
| AC-12 | core-blocker | major | serialization assertions plus scoped source review | reused + reviewed | pass |
| AC-13 | scoped-risk | moderate | packaged manifest/help and remote install smoke | new | pass |
| AC-14 | core-blocker | major | clean build, migration validation, actuator, SHA, OBS latest | new | pass |
| AC-15 | core-blocker | major | scoped Git commits and no SIM/runtime invocation | reviewed | pass |

## Evidence Sufficiency

- assurance_level: standard
- why_existing_evidence_is_sufficient_or_not: Every critical criterion has an executed fixture, artifact/provenance evidence, or deployment observation. The implementation commit was independently rebuilt and retested in a clean clone.
- new_validation_that_could_change_decision: none within the authorized boundary.
- expensive_validation_omitted_and_reason: A live SIM STANDARD ask was not run because the owner explicitly prohibited new SIM asks and historical-task mutation. Navigator-owned deterministic fixtures directly cover the changed contracts.

## Optional Full-Chain Recommendation

- recommendation: not-needed
- qualifying_condition: none
- estimated_wall_clock_and_basis: N/A
- scope_and_prerequisites: N/A
- maximum_attempts: 1
- decision_impact: none
- user_approval: not-requested
- execution_status: not-run

## Waivers

| Waived Item | Authority | Reason | Bounded Impact | Non-Waivable Guards | Follow-up |
|---|---|---|---|---|---|
| none | N/A | N/A | N/A | N/A | N/A |

## Failed Items

- none

## Risks / Follow-ups

- Requests that fail before reaching Navigator can only retain the locally generated SDK identifier; no server-side record can exist for an unreceived request.
- Historical STANDARD asks that never produced request-audit records remain unqueryable; no evidence was fabricated.
- SSE terminal notifications are best-effort. The read-only task endpoint remains authoritative and consumers should poll idempotently.

## Final Decision

- decision: accepted
- rationale: All 15 acceptance criteria have sufficient evidence, release provenance is clean, the deployed server is healthy, CLI `1.0.32` is published, and no prohibited SIM or historical-task operation occurred.
- blocking_items: none
- follow_up_owner_and_due: none

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted
- signed_off_by: independent-root-signoff
- signed_off_at: 2026-07-25
- acceptance_record: `docs/version-tracker/1.4.3-SNAPSHOT/evidence/BUG-018-independent-signoff.md`
- blocking_items: none
- follow_up_required: no
