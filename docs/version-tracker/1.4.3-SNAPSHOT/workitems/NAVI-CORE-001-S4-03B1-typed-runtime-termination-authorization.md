---
workitem: NAVI-CORE-001-S4-03B1
status: REVIEWED_READY_TO_COMMIT
date: 2026-08-04
baseline: f2d74ce3
coordination_freeze: 6e8a5cf
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: TYPED_RUNTIME_TERMINATION_AUTHORIZATION
---

# NAVI-CORE-001 S4-03B1 typed runtime termination authorization

This slice adds a content-free, server-issued command authorization to non-dry-run typed runtime
termination. It does not add a receipt or an effect gate. The existing runtime request receipt,
termination admission, lifecycle intent/outbox, dispatcher, and canonical terminal facts remain the
only typed termination authority. Three independent reviews rejected the initial eight-path draft:
the authorization selected its own verifier, the dispatcher could use a drifted Task principal, and
legacy `OwnedRuntimeTask` constructors allowed fabricated authority identity. Coordination freeze
`6e8a5cf` reopens only the existing intent/outbox binding needed to close those findings.

## Exact changed paths

- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/RuntimeStateAuditService.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/RuntimeTaskClosureService.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/RuntimeTerminationAcceptanceCoordinator.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/RuntimeTerminationOutboxDispatcher.java`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/RuntimeStateAuditServiceTest.java`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/RuntimeTaskTypedContractServiceTest.java`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/RuntimeTerminationAcceptanceCoordinatorTest.java`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/RuntimeTerminationDeliveryRecoveryTest.java`
- `navigator-spi/src/main/java/com/foggy/navigator/spi/lifecycle/RuntimeTerminationIntentPort.java`
- `session-module/src/main/java/com/foggy/navigator/session/lifecycle/persistence/LifecycleEffectOutboxEntity.java`
- `session-module/src/main/java/com/foggy/navigator/session/lifecycle/TaskTerminationIntentRecorder.java`
- `session-module/src/test/java/com/foggy/navigator/session/lifecycle/TaskTerminationIntentRecorderIntegrationTest.java`
- `docs/migration/2026-08-04-runtime-termination-effect-principal-binding.sql`
- this work item

## Frozen boundary

- The actor is `CLIENT_APP / CLIENT_APP_RUNTIME_CREDENTIAL`; raw app key/secret and their hashes
  never enter the envelope, decision, receipt, lifecycle intent/outbox, log, error, or safe result.
  Raw reason does not enter the new authorization, request receipt, or lifecycle outbox; the
  existing Provider `TerminationOperationEntity.reasonCode` behavior is unchanged.
- Credential, ClientApp, tenant, Task owner, session, logical Agent, Provider, physical Worker, and
  model identities are server-derived and rechecked before receipt/intent admission and before a
  fresh HTTP effect. Compatibility constructors cannot issue mutation authorization.
- The authorization never carries its verifier. Closure and acceptance independently inject the
  same Spring `ServerAuthority`; a foreign authority seal is rejected before receipt or Provider.
- The existing lifecycle outbox stores the accepted owner/tenant effect snapshot. The owner records
  exact binding before PREPARED and rechecks the locked canonical Task before EFFECT_STARTED;
  dispatcher recovery uses the frozen delivery identity and never rereads a mutable Task principal.
- The additive migration has no UPDATE/backfill. Legacy PREPARED rows with missing principal fields
  fail closed and are not repaired by this work item.
- The existing client request ID remains required and retains its public format and echo behavior.
- Dry-run does not issue a command decision or write a receipt, intent, or outbox.
- `TaskTerminationCommandCoordinator`, `CommandOnceReceipt`, Controllers, SDK/CLI, Provider, SIM, TMS,
  read-only observation, legacy projection repair, and terminal cleanup repair are out of scope.

## Validation budget

Run one affected production compile, exact focused selectors in the five changed test files, and
at most one filtered Claude unit affected lane. Migration validation is static in this slice. Do not
run a whole test class, module/reactor, target database/E2E/live Provider test, or a final joint
full-validation cycle.

## Validation evidence

- Affected production compilation passed for the final implementation:
  `mvn -pl addons/claude-worker-agent -am -DskipTests compile` (eight reactor projects,
  `BUILD SUCCESS`, 20.220 seconds). An earlier structural compilation also passed in 37.086
  seconds.
- The two exact session owner/outbox selectors passed 2/2 in 32.369 seconds. They prove that a
  committed PREPARED delivery survives restart, cannot authorize twice, and rejects Task principal
  drift before changing effect-authorization state.
- The filtered Claude affected lane selected 17 exact methods. Fifteen passed on the first run; the
  two failures were test-only expectation/setup defects (durable PREPARED correctly returns
  `PROCESSING`, and shared Mockito stubs needed to be lenient). After correcting those fixtures,
  the two exact selectors passed 2/2 in 30.532 seconds.
- After moving non-recovery effect authorization behind the fresh principal check, the two
  order-sensitive recovery/response-loss selectors passed 2/2 in 22.435 seconds.
- After adding the durable-acceptance catch fence, its changed selector passed 1/1 in 30.751
  seconds. Once PREPARED is durable, a later principal mismatch now returns conservative
  `PROCESSING / reconcileRequired` without marking the receipt failed or starting Provider effect.
- Three independent revised-diff reviews accepted the final implementation with no P1/P2 finding:
  command context/permission isolation, facade/API compatibility, and durable once-effect pipeline.
- `git diff --check` passed. The migration was reviewed statically: it adds only nullable
  `owner_user_id` and `tenant_id`, contains no DML/UPDATE/backfill, and was not executed against a
  database.
- No whole test class, whole module/reactor test set, target database, E2E/live Provider test, or
  final joint full-validation cycle was run. Final-cycle usage remains 0/3.

## Review result

The final revision preserves one canonical server verifier, freezes the accepted principal in the
existing lifecycle outbox, rechecks the locked Task binding before EFFECT_STARTED, and makes both
fresh HTTP and restart dispatch fail closed on identity drift. Compatibility constructors remain
source/binary callable but cannot mint mutation authority. The slice is ready for an exact-path
commit; it does not authorize or perform repair of legacy PREPARED rows.

## Data boundary

Use mocks, in-memory values, and new disposable fixtures only. Do not inspect or mutate historical
business/runtime data, and do not repair, backfill, replay, reconcile, clean, delete, or synthesize
old facts. Do not inspect or touch the user-owned `BOOT-INF/` directory.
