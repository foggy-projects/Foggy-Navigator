---
doc_type: delivery-spec
delivery_type: cross-module
version: 1.4.3-SNAPSHOT
ticket: BUG-040
status: READY_FOR_SIGNOFF
canonical: true
approved_by: repository-owner
approved_at: 2026-08-02
---

# BUG-040: Lifecycle Provider-Effect Model Canonicalization

## Goal and scope

Fix the bounded local-development activation path so reservation and
provider-effect admission use the same server-side Codex physical-model family
mapping. Keep exact provider, modelConfig, owner, Worker identity, proof and
binding-digest checks fail closed.

Also close a failed one-shot provider-effect admission without releasing it for
reuse, classify lifecycle pre-effect failures as definitive zero-dispatch
terminal transitions, revoke their task-scoped capability, and keep request and
task audit dispatch projections consistent.

## Non-goals and constraints

- No new Task, provider/model call, termination, TMS access, Worker change or
  binding/resource mutation during implementation validation.
- Do not rewrite or delete the retained failed Task audit.
- A failed one-shot target is quarantined, never reset to READY.
- Unknown aliases, model families, providers and modelConfig values remain
  rejected.

## Acceptance criteria

- [x] `gpt-5.6-luna` and granted `codex-luna:high` pass both reservation and
  canonical persisted-task admission.
- [x] Different/malformed model families and all other binding mismatches fail
  before provider effect and atomically leave zero enrollment writes.
- [x] Provider-effect admission failure quarantines the one-shot target/proof.
- [x] Lifecycle pre-effect FAILED is definitive, zero-dispatch and drives task
  token/tombstone closure; genuinely recoverable provider failures remain
  recoverable.
- [x] Request audit and task audit derive provider/model dispatch from
  server-observed providerTaskId/terminal facts rather than Task creation.
- [x] Focused affected tests, launcher package and restarted 8112 provenance/
  health pass.

## Implementation result

- changed_paths:
  - `session-module`: shared physical-model family comparison at provider-effect
    admission, plus one-shot quarantine on failure.
  - `addons/codex-worker-agent`: definitive lifecycle pre-effect failure and
    server-observed dispatch facts.
  - `agent-framework`, `business-agent-module`,
    `addons/claude-worker-agent`: terminal dispatch projection, token closure,
    and request/task audit alignment.
- tests_and_results:
  - lifecycle activation integration: 22 passed.
  - runtime request audit and terminal listener: 41 passed.
  - Codex task and stream relay: 199 passed.
  - OpenAPI mapping and runtime state audit: 73 passed.
  - launcher 14-module package: passed.
  - restarted 8112: health/database `UP`; actuator provenance matched the
    packaged clean `main` commit.

### SIM-NAVI-001 Navigator implementation-evidence addendum (2026-08-02)

- scope: the follow-up keeps the retained pre-effect terminal Task read-only
  and adds the provider-side typed terminal-cleanup repair and typed
  completion-readiness contract needed by SIM.  It does not treat a client
  declaration, `NOT_FOUND` token state, or a non-terminal receipt as closure.
- focused_tests:
  - `mvn -pl business-agent-module -am -Dtest=BusinessTerminalCleanupPortTest,BusinessTaskScopedTokenLifecycleServiceTest,RuntimeRequestAuditServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`: 60 tests passed, 0 failures, 0 errors.
  - `mvn -pl addons/claude-worker-agent -am -Dtest=RuntimeStateAuditServiceTest,RuntimeTaskTypedContractServiceTest,RuntimeTaskCompletionReadinessServiceTest,RuntimeTaskTerminalCleanupRepairServiceTest,OpenApiControllerMessageMappingTest -Dsurefire.failIfNoSpecifiedTests=false test`: 123 tests passed, 0 failures, 0 errors.
  - `mvn -pl session-module -am -Dtest=TerminalCleanupRepairServiceTest,TaskTerminalCommitServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`: 15 tests passed, 0 failures, 0 errors.
  - `mvn -pl navigator-open-sdk -Dtest=RuntimeTaskTypedContractTest,UpstreamCliTest test`: 163 tests passed, 0 failures, 0 errors.
- sdk_artifact_identity:
  - GAV: `com.foggy.navigator:navigator-open-sdk:1.0.40-SNAPSHOT`.
  - binary SHA-256: `f19bbf00f4527e69d3d017d0b28eca9b158c85493e49904a57564ed9d6de6e5e`.
  - sources SHA-256: `d6efa1f9b2b27a78913596edbddf6156d9493f4258447dedd5aaacfd637e4e98`.
- affected_lane:
  - `mvn -pl session-module,business-agent-module,addons/claude-worker-agent,navigator-open-sdk -am test` stopped in the unchanged dependency module `agent-framework`; `LlmCircuitBreakerTest$HalfOpenToClosedTest.shouldCloseOnProbeSuccess` failed at its cooldown assertion, so the four target modules were skipped by Maven fail-fast.
  - `git diff --name-only -- agent-framework` was empty.  The timing-sensitive test uses a 250 ms sleep with a wall-clock cooldown; the independent focused retry `mvn -pl agent-framework -Dtest=LlmCircuitBreakerTest test` passed 12 tests with 0 failures/errors.  This lane result is recorded as a pre-existing baseline flake, not as a SIM-NAVI-001 pass or candidate regression.
- boundary_and_remaining_evidence: no historical Task/Session repair, replay,
  reconciliation, cleanup, or fact synthesis was performed.  A fresh
  disposable bilateral smoke, SIM consumer verification, independent commits,
  and the shared canonical Implementation Result remain pending and are not
  claimed by this addendum.
- residual_runtime_disposition: retain the pre-effect terminal Task and its
  quarantined target/proof for read-only audit only.  Per SIM-NAVI-001, do not
  reconcile, repair, replay, revoke, clean, or synthesize facts for that
  historical Task; its cleanup gap is a non-blocking residual risk.  The
  forward cleanup contract is proved only with a fresh disposable one-shot
  target and Task.
- readiness: READY_FOR_SIGNOFF

### Authority-clock termination admission follow-up (2026-08-02)

- root_cause: `TaskTerminationIntentRecorder` used JVM wall-clock time to
  evaluate writer-proof expiry while lifecycle activation and proof ownership use
  the database authority clock. A proof valid at authority time could therefore
  be rejected as `LIFECYCLE_WRITER_EXCLUSIVITY_LOST`.
- fix: receipt admission, effect authorization, worker-command preparation and
  authorization, and active-proof filtering now use
  `LifecycleAuthorityClock.databaseNow()`.
- changed_paths:
  - `session-module/src/main/java/com/foggy/navigator/session/lifecycle/TaskTerminationIntentRecorder.java`
  - `session-module/src/test/java/com/foggy/navigator/session/lifecycle/TaskTerminationIntentRecorderIntegrationTest.java`
  - `launcher/src/test/java/com/foggy/navigator/launcher/Arch001ThirdRemediationSlice8IntegrationTest.java`
  - `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/BusinessLifecycleTerminalVerticalIntegrationTest.java`
- focused_tests:
  - authority-clock regression: 6 passed.
  - launcher vertical context: 4 passed; Claude terminal vertical context: 3
    passed.
  - lifecycle/model/terminal cleanup: 51 passed; business token/audit/cleanup:
    79 passed; Claude typed/readiness/repair/audit/OpenAPI: 126 passed; Codex
    task/relay: 200 passed; SDK typed contract and CLI: 163 passed.
- final_sdk_artifact_identity:
  - GAV: `com.foggy.navigator:navigator-open-sdk:1.0.40-SNAPSHOT`.
  - binary SHA-256: `d459b75a5c66ef6a064da59481c9d5772d3ad61e071f7f795660346a99576b4b`.
  - sources SHA-256: `ab0786a9412bd578181458a90df2d399b25140715b360f56eb323a3f600e9732`.
- boundary: no historical Task, Session, target or proof was repaired, replayed,
  reconciled, or reused. The bilateral evidence remains limited to a fresh
  disposable fixture.
