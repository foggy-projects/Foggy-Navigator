# ARCH-001 Independent Resignoff — 2026-07-31

## Decision

- verdict: `REJECTED`
- decision_type: independent resignoff of a `READY_FOR_SIGNOFF` remediation
- audit_completed_at: `2026-07-31T12:07:49+08:00`
- remediation_commit:
  `adb9ee449fe4b7eecfd1e5c6e4d257ff44302449`
- original_independent_rejection_commit:
  `297c79160657d0413b608ba2f4f5386486e14837`
- rejected_implementation_commit:
  `fac98161d5e59b54d8f605061af1adae6f4b6415`
- original_baseline:
  `d3eb7f76d31d6dfd2a78009d30caff9f8307284d`
- canonical_work_item:
  `docs/version-tracker/1.4.3-SNAPSHOT/workitems/ARCH-001-unified-session-task-lifecycle-owner.md`
- prior_rejection_record:
  `docs/version-tracker/1.4.3-SNAPSHOT/evidence/ARCH-001-independent-signoff-2026-07-31.md`
- activation_gate: `CLOSED`
- contract_disposition: `REJECTED`, not `NEEDS_REPLAN`. The findings are implementation
  and evidence failures against the approved authority, Worker-v1 wire, public compatibility,
  migration and activation contracts; closing them does not presently require changing those
  contracts.

The remediation materially improves the implementation and all required executable test commands
completed successfully. That does not close the non-waivable assurance gaps. B1–B6 still contain
production-call-path, transaction/authorization, cross-runtime, representative-fixture or
migration-runtime-evidence failures.

## Independence, Repository Integrity and Boundaries

- `git rev-parse HEAD` returned the exact remediation commit.
- `git merge-base --is-ancestor adb9ee... HEAD` exited 0.
- `git merge-base --is-ancestor 297c791... adb9ee...` exited 0.
- The initial worktree was clean: `## main...origin/main [ahead 3]`; no user modification was
  present. Repo-owned test artifacts were written only below the ignored
  `temp/test-artifacts/arch-001-resignoff-2026-07-31/`.
- No reset, checkout, revert, source fix, Worker/Navigator operation, sibling-repository access,
  business-data access, real controller/process operation, live SIM, push, tag or release was
  performed.
- The complete remediation diff was inspected:
  `297c791...adb9ee` contains 78 files, 4,586 insertions and 185 deletions.
- The root `AGENTS.md`, the complete `foggy-delivery-signoff` skill and checklist/template,
  the complete canonical work item, the prior independent rejection, and the closer
  `addons/claude-worker-agent/AGENTS.md` were read. No closer `AGENTS.md` exists for the other
  changed modules.
- Current production sources, repositories/entities, forward/rollback SQL, test sources, root and
  module Maven POMs, and `tools/codex-agent-worker/package.json` scripts were inspected. This
  decision does not rely on the remediation narrative.

## B1–B6 Verdict Summary

| Blocker | Verdict | Independent conclusion |
|---|---|---|
| B1 lifecycle owner production vertical chain | `REJECTED` | Ingress reservation exists, but the scheduled Sentinel can only iterate already-existing Worker snapshots, no production caller creates the first snapshot, and Sentinel never invokes `events()`. The advertised vertical test directly calls owner methods and bypasses Sentinel/adapter scheduling. |
| B2 receipt admission and durable delivery | `REJECTED` | Public preflight and atomic receipt/outbox persistence improved, but missing lifecycle enrollment is accepted, no owner lifecycle fact is committed, termination authorization bypasses writer proof/reference, and the “dispatcher” has no scheduler/consumer. Recovery tests mock the coordinator instead of exercising the production coordinator/repository chain. |
| B3 terminal authority, tombstone and cleanup | `REJECTED` | Conflict quarantine, atomic tombstone/plan and real cleanup components exist. However production normalization makes `TASK_NEVER_ACCEPTED_CONFIRMED` non-authoritative and terminal commit only accepts a provider-terminal fact; exact pre-effect rejection cannot complete. Cleanup applicability is inferred from provider/operation strings rather than exact durable resources. |
| B4 Codex Worker lifecycle v1 Java/Node contract | `REJECTED` | Lifecycle endpoint method/header/envelope alignment is improved. The Java↔Node integration fixture mounts only lifecycle routes, not real query/abort routes; Java does not consume/persist `lifecycle_disposition`; termination reuses the initial dispatch ID; and a later abort terminal transition is not linked to the abort lifecycle disposition. |
| B5 writer proof/reference/outbox and Slice 8 | `REJECTED` | The proof → reference → outbox lock order and DB concurrency tests are real. The authorization service has no production caller, public termination uses a separate authorization path, quarantine changes only the proof row, and Slice 8 connects a Node inventory GET and a separately seeded JPA proof fixture rather than one production effect chain. |
| B6 migration/schema readiness | `REJECTED` | The additive migration ran twice on disposable MySQL, preserved a legacy table, passed Hibernate validate and selected schema assertions, rolled back empty state, and failed closed after one ENFORCED Task marker. The claimed reference/outbox rollback-gate branches were never executed; their only evidence is SQL text, which is not sufficient critical readiness evidence. |

## Detailed B1 Evidence Mapping

| Criterion | Result | Code/test/runtime evidence |
|---|---|---|
| create/resume reserve the foreground lane and apply the offline gate before provider effect | `PASS` | `TaskDispatchFacade.java:175-235,380-391` reserves before direct/A2A create and resume; `LifecycleIngressGate.java:33-73` locks the Session row, evaluates Worker operational state and rejects ENFORCED before effect. `LifecycleIngressGateIntegrationTest` ran 3/3. |
| Sentinel is genuinely scheduled, consumes inventory/events, and sends ACK | `FAIL` | `WorkerLifecycleSentinelScheduler.java:29-43` is scheduled but only loops `worker_lifecycle_snapshots.findAll()`. Production search found the only `new WorkerLifecycleSnapshotEntity()` at `WorkerLifecycleSentinelService.java:54`; there is no bootstrap caller for the first row. `WorkerLifecycleSentinel.java:56-113` calls `probe`, `inventory` and `acknowledge`, never `events`. |
| normalized facts enter the canonical reducer | `PARTIAL` | `WorkerLifecycleSentinelService.java:75-89` enrolls inventory Tasks and sends inventory facts to `TaskLifecycleOwnerService`; `TaskLifecycleOwnerService.java:102-130` persists facts and invokes the reducer. Because the scheduler cannot discover the first Worker and never reads the events route, the chain is not generally reachable. |
| exact Worker identity, mode, dispatch/operation, binding digest and provider Task identity are retained and verified | `PARTIAL/FAIL` | Exact checks exist at `TaskLifecycleOwnerService.java:165-187`. Enrollment sets `operationId=initialDispatchId` at lines 69-78, while termination admission later mutates the Task snapshot operation at `TaskTerminationIntentRecorder.java:159-180`. Subsequent Worker facts without that new operation normalize to the original dispatch and no longer exact-match the current owner binding. Sentinel also treats a changed `instanceEpoch` as a permanent identity-change blocker at `WorkerLifecycleSentinel.java:82-87`, preventing normal restart reconciliation. |
| reducer → snapshot → terminal → cleanup → typed reconciliation is a production chain | `FAIL` | Those components exist, but the upstream Sentinel path is incomplete and the exact-never-accepted branch cannot reach terminal commit (B3). `TaskLifecycleOwnerVerticalIntegrationTest.java:88-125` directly seeds the owner and directly calls `ingestNormalizedBatch`, bypassing scheduler, Sentinel, Worker port and events. |
| SHADOW has zero lifecycle-owner provider effect | `PASS` | New Sessions default to SHADOW (`LifecycleIngressGate.java:113-123`); owner terminal commit is restricted to ENFORCED (`TaskLifecycleOwnerService.java:128-130`); legacy Codex command overload remains available. Lifecycle observation rows are still written as intended. |
| repo-owned vertical integration traverses the real service/repository chain | `FAIL` | It uses real JPA/service/reducer/tombstone/cleanup repositories, but not the production Sentinel/adapter/route chain. Its fixture provider is `codex-worker`, and cleanup resources inferred at `TaskLifecycleOwnerService.java:154-159` mark physical token and receipt false, so the claimed token-revoke/registration evidence is absent from this vertical test. |

## Detailed B2 Evidence Mapping

| Criterion | Result | Code/test/runtime evidence |
|---|---|---|
| exact Task/provider/Worker/enrollment/preconditions precede receipt commit | `FAIL` | `RuntimeTaskClosureService.java:181-210` performs Task ownership, provider and readiness checks before receipt. But `TaskTerminationIntentRecorder.java:159-173` accepts a missing lifecycle snapshot and, when one exists, checks only Session/Worker/provider Task for ENFORCED; it does not require enrollment mode, generation, epoch, proof or expected owner precondition. |
| receipt, owner operation/fact, exact binding and outbox commit in one transaction | `FAIL` | `RuntimeTerminationAcceptanceCoordinator.java:37-62` does atomically join public receipt and `recordIntent`; the recorder updates the Task snapshot operation and inserts an outbox. It creates no `lifecycle_facts` owner operation/fact, and exact enrollment is optional. |
| receipt persistence failure produces zero provider calls | `PASS WITH LIMITED TEST` | Provider invocation occurs after acceptance/authorization at `RuntimeTaskClosureService.java:212-269`; persistence failures return the stable typed rejection at lines 238-255. `RuntimeTerminationDeliveryRecoveryTest.java:116-132` verifies zero calls, but mocks the coordinator rather than forcing a real DB commit failure. |
| a real durable outbox dispatcher/handler exists | `FAIL` | `RuntimeTerminationOutboxDispatcher.java:14-31` is only a synchronous wrapper. No `@Scheduled`, listener, claim loop or PREPARED-outbox poller invokes it. Product search found calls only from the same HTTP termination request. |
| commit-before-dispatch crash can recover with the same clientRequestId | `PARTIAL/FAIL` | A same-ID HTTP retry can authorize a durable PREPARED row; `TaskTerminationIntentRecorderIntegrationTest` proves recorder restart behavior. There is no autonomous recovery, and `RuntimeTerminationDeliveryRecoveryTest` mocks both acceptance and authorization. If the process fails after `EFFECT_STARTED` but before the provider call, redelivery is permanently suppressed with no convergence mechanism. |
| response loss/same-ID redelivery cannot create a second provider termination | `PASS FOR SYNCHRONOUS HTTP PATH` | `TaskTerminationIntentRecorder.java:74-90` makes `EFFECT_STARTED` and later read-only. The delivery recovery test verifies one mock provider call. This does not cure the missing-dispatch case above. |
| receipt-disabled same-ID requests remain two one-shot attempts | `PASS` | `RuntimeTaskTypedContractServiceTest.java:297-341` executes the production closure service and verifies two provider calls and no receipt lookup. |
| public disabled reconciliation matrix retains BUG-035 | `PASS` | The same test verifies `AMBIGUOUS`, `TERMINATION_REQUEST_RECEIPT_DISABLED`, unavailable reconciliation and fail-closed replay flags. Open SDK typed fixtures also pass. |
| typed admission/recovery tests traverse the production coordinator | `FAIL` | `RuntimeTerminationDeliveryRecoveryTest.java:27-43` injects a Mockito `RuntimeTerminationAcceptanceCoordinator`; repository integration exercises the recorder separately. No test crosses production closure service → real coordinator → real public receipt → owner/outbox repository → provider handler. |

## Detailed B3 Evidence Mapping

| Criterion | Result | Code/test/runtime evidence |
|---|---|---|
| only exact approved evidence creates a terminal candidate | `PARTIAL/FAIL` | Reducer exact-binding checks are correct at `TaskLifecycleReducer.java:69-90`. Production `TaskLifecycleOwnerService.normalizedFact` only constructs authoritative facts for `TASK_PROVIDER_TERMINAL_OBSERVED` at lines 194-205; every other type, including `TASK_NEVER_ACCEPTED_CONFIRMED`, is constructed with `exactTerminalAuthority=false` at lines 207-209. |
| conflicting terminal evidence quarantines without terminal/tombstone/effect | `PASS` | `TaskLifecycleReducer.java:69-95` clears terminal outcome on conflict. Reducer and vertical tests prove OPEN + `AUTHORITY_QUARANTINED`, no tombstone/outbox, and unchanged canonical Task. |
| terminal, authorization tombstone and cleanup plan are atomic; fence failure rolls back | `PASS IN SOURCE/FOCUSED TEST` | `TaskTerminalCommitService.java:38-61` freezes plan, invokes MANDATORY participants, then writes owner tombstone/plan/snapshot in one transaction. `BusinessTaskTerminalTombstoneParticipant.java:35-51` joins the transaction. Focused tests passed. |
| never-accepted exact authority can complete the same terminal chain | `FAIL` | Even if the reducer were given an exact never-accepted fact, `TaskLifecycleOwnerService.java:133-137` requires an exact `TASK_PROVIDER_TERMINAL_OBSERVED` fact for terminal commit and throws otherwise. AC-19 is unreachable through the production owner. |
| token revoke, compatibility receipt/projection, registration=false and lane release have real actions | `PARTIAL` | Real token and receipt actions exist in `BusinessTerminalCleanupPort.java:24-84`; cleanup scheduling/checkpointing exists in `TerminalCleanupHandler.java:30-61`; typed projection requires canonical Task terminal. However applicability is guessed from provider type and `operationId != dispatchId` at `TaskLifecycleOwnerService.java:154-159`, not frozen from exact durable resources. Derived registration has no separate resource, as approved. |
| cleanup is idempotent/retryable across Java restart and checkpoints | `PARTIAL` | Persistent checkpoint rows, `REQUIRES_NEW` step execution and scheduled resume exist. Tests cover local retry/checkpoint behavior, but no restart integration traverses all real business participants, and the vertical test selects no token/receipt actions. |
| typed TERMINAL requires canonical lifecycle terminal and canonical Task terminal | `PASS` | `TaskLifecycleProjectionPort.java:17-25` additionally requires cleanup complete and an allowlisted canonical terminal Task status. |
| ACK/ACCEPTED/text/log/disconnect/timeout cannot independently authorize terminal | `PASS` | `TaskLifecycleReducerTest.java:43-63` verifies those inputs remain OPEN/AMBIGUOUS with zero effect. |

## Detailed B4 Evidence Mapping

| Criterion | Result | Code/test/runtime evidence |
|---|---|---|
| Java/Node lifecycle method/path/headers/mode-first fence/digest/envelope align | `PASS FOR LIFECYCLE ENDPOINTS` | Java uses PUT ACK and exact expected headers (`CodexWorkerLifecycleHttpAdapter.java:27-32,78-205`); the real Node lifecycle-router integration test passed. |
| Java adapter implements probe, inventory/events, ACK, status and command context | `PASS IN SOURCE` | Adapter implements probe/inventory/events/ACK/status. `CodexWorkerClient` provides lifecycle context to query/abort. The production Sentinel does not consume adapter `events`, as recorded under B1. |
| create/resume/abort durably perform PREPARED → EFFECT_STARTED → RESULT_OBSERVED/fact | `FAIL` | Query route performs durable PREPARED/EFFECT_STARTED and writes a terminal fact in `finally`. Abort marks RESULT_OBSERVED only when `requestTaskCancellation()` is already terminal (`tasks.ts:507-517`); the usual `CANCEL_REQUESTED` later terminal transition is not connected back to this abort lifecycle dispatch. |
| EFFECT_STARTED must commit before provider call; PREPARED may continue; later phases prohibit repeat | `PARTIAL` | Node query/abort routes call `markEffectStarted` before the provider/cancel request and store-level restart tests pass. The Java side does not persist the returned disposition before the business stream, and production route integration is absent. |
| terminal/result facts are durable and Sentinel can ingest them | `FAIL` | Node query terminal facts are durable. Java `CodexStreamRelay.handleSseEventLocked` deserializes `data` as `WorkerEvent` and ignores the SSE event name; `lifecycle_disposition` has no Worker `type` and is discarded (`CodexStreamRelay.java:951-985`). Sentinel never invokes events, so the full ingestion path is unproved/unreachable. |
| Java adapter ↔ Node test starts real command routes | `FAIL` | `CodexWorkerLifecycleNodeContractIntegrationTest.java:21-50` tests only health/inventory/events/ACK. Its fixture mounts only `createLifecycleRouter` (`lifecycle-router-server.ts:24-35`), not `/api/v1/query` or `/api/v1/tasks/:id/abort`. |
| exact codex-biz-worker passes typed readiness, terminate and same-ID reconcile | `FAIL AS VERTICAL EVIDENCE` | Support/readiness unit tests pass, but `CodexTaskServiceTest` mocks `CodexWorkerClient`; no public closure → Codex adapter → real Node command route test exists. |
| Java uses an exact durable dispatch/operation binding | `FAIL` | Query hard-codes `"dispatch-" + taskId` (`CodexStreamRelay.java:256-265`). Termination prefers the existing Task snapshot dispatch (`CodexTaskService.java:1938-1953`) instead of a distinct termination dispatch; Node durable store can therefore see a query record under a termination command and reject it. |
| Node contract tests represent the actual wire | `FAIL` | `lifecycle-contract.test.ts:145-263` calls `LifecycleStore` directly. Its abort digest fixture uses HTTP PUT and `/api/v1/tasks/:taskId/abort`, while the production route is POST and its canonical template is `/api/v1/tasks/{providerTaskId}/abort` (`tasks.ts:401-417`). |
| SHADOW legacy wire remains compatible | `PASS` | Legacy overload remains the fallback when no lifecycle context exists; full Java/Node tests pass and no public DTO changed. |

## Detailed B5 Evidence Mapping

| Criterion | Result | Code/test/runtime evidence |
|---|---|---|
| lock order is proof → exact reference → outbox | `PASS` | `WriterExclusivityProofService.java:77-101` uses that order in one transaction. |
| generation, inventory digest, aggregate reference, effect class and claim are exact | `PASS IN ISOLATED SERVICE` | Checks are at `WriterExclusivityProofService.java:84-121`; service tests pass. |
| EFFECT_STARTED is the only provider-call authorization linearization point | `FAIL IN PRODUCT` | The proof service provides this transition, but production search finds `WriterExclusivityProofService.authorizeEffect` only in tests. Public termination instead uses `TaskTerminationIntentRecorder.authorizeEffect`, which locks only the outbox and does not validate proof/reference. |
| reference/proof release derives wholly from durable aggregate/outbox state | `PARTIAL` | Task/Session release predicates and proof-specific unfinished-outbox counts are durable (`WriterExclusivityProofService.java:138-189`). Worker release is always false, which is safe until retirement. Production code does not acquire the full Worker/Session/Task reference set. |
| proof releases only after all references and unfinished proof outboxes are gone | `PASS IN SERVICE` | `mayReleaseProof` checks both counts. No representative product-chain test proves all three references. |
| loss-first and authorization-first use real concurrent transactions/CAS | `PARTIAL/FAIL AS PROVIDER EVIDENCE` | `WriterExclusivityProofConcurrencyIntegrationTest` uses real DB transactions and row locks, and both tests pass. Provider execution is an `AtomicInteger` increment after authorization (`lines 124-160`), not a real outbox/Worker handler; the authorization service itself is not on a production effect path. |
| Slice 8 traverses real entity/repository/service/Worker route | `FAIL` | `IsolatedEnforcedLifecycleContractTest.java:55-96` performs only a Node inventory GET, then independently seeds proof/reference/outbox rows and invokes the writer service. It manually constructs `LifecycleEnrollmentGate`; it does not call production enrollment, owner, create/resume/abort or provider invocation. |
| fixture respects no real controller/process/non-fixture ENFORCED boundary | `PASS` | It starts only a repository-owned ephemeral Node fixture and H2 fixture rows; no real process/controller or non-fixture aggregate was touched. |
| proof loss quarantines existing aggregates | `FAIL` | `WriterExclusivityProofService.java:130-135` changes only the proof status. It does not quarantine referenced Worker/Session/Task snapshots. |

## Detailed B6 Evidence Mapping

| Criterion | Result | Code/test/runtime evidence |
|---|---|---|
| migration is additive and compatible with existing tables | `PASS` | Forward SQL only creates 12 new `IF NOT EXISTS` tables. The MySQL test creates/preserves a legacy sentinel table and reapplies forward SQL. |
| forward SQL executes on a repo-owned disposable compatible database | `PASS` | Testcontainers started `mysql:8.0`; the exact opt-in command passed 1/1 with no skip. |
| Hibernate/JPA schema validation executes | `PASS` | The test builds a `SessionFactory` with all 12 entities and `hibernate.hbm2ddl.auto=validate` (`LifecycleMigrationMySqlIntegrationTest.java:108-138`). |
| unique/index/nullability/length are checked | `PARTIAL` | The test checks both idempotency unique indexes and four representative column contracts against `information_schema` (`lines 68-80,141-176`). It does not exhaustively validate the new schema's declared unique/index/nullability/length surface. |
| rollback succeeds without ENFORCED state | `PASS` | It drops all 12 lifecycle tables and preserves the legacy table (`lines 82-88`). |
| rollback fails closed with enforcement marker/reference/outbox | `FAIL` | The runtime fixture inserts only an ENFORCED Task marker and observes the exact database error (`lines 90-102`). It never inserts an active generation, unreleased reference or unfinished outbox and reruns rollback for those cases. Their only evidence is the SQL OR clauses, which cannot substitute for the required runtime fixture. |
| production schema still requires pre-apply | `PASS` | Forward SQL header explicitly requires pre-apply before `ddl-auto=validate`; activation remains closed. |
| warning-only migration runner is not used as readiness evidence | `PASS` | This decision relies on direct SQL execution and Hibernate validate, not the startup warning runner. |

## Canonical Acceptance Criteria Mapping

The verdict is evaluated against every AC in the approved work item. `PARTIAL` does not satisfy a
compound acceptance criterion.

| AC | Verdict | Evidence mapping |
|---|---|---|
| AC-1 | `PASS` | Reducer determinism/recompute tests pass; participant direction remains SPI-based. |
| AC-2 | `PASS` | Exact terminal reducer and non-authoritative ACK/text/disconnect/timeout tests pass. |
| AC-3 | `FAIL` | Disabled compatibility passes, but enabled recovery lacks a real durable dispatcher and production-chain recovery test. |
| AC-4 | `FAIL` | Sentinel/events and operation-binding gaps prevent reliable final-evidence convergence. |
| AC-5 | `FAIL` | Atomic tombstone/plan and typed gate exist, but cleanup applicability is not derived from exact durable resources. |
| AC-6 | `PARTIAL` | Persistent scheduled checkpoints exist; full real-participant restart/retry evidence is absent. |
| AC-7 | `PASS` | Offline gate/freeze and local cleanup separation exist; no pending-command queue was added. |
| AC-8 | `FAIL` | Sentinel does not bootstrap the first Worker, does not consume events, and rejects changed instance epoch. |
| AC-9 | `FAIL` | Real command continuation/status paths are not integrated; termination dispatch binding is incorrect. |
| AC-10 | `PASS` | Transactional foreground reservation/occupancy tests pass. |
| AC-11 | `PASS` | No admin logical-close/permanent-loss authority or endpoint was added. |
| AC-12 | `PASS` | Activation remains closed; SHADOW/legacy and BUG-035 public wire semantics are preserved. |
| AC-13 | `FAIL` | Forward/reapply/JPA validate and one marker gate pass, but reference/outbox rollback branches and the full schema metadata contract lack runtime coverage. |
| AC-14 | `PASS` | Required no-mutation/security boundaries were maintained. |
| AC-15 | `FAIL` | Lifecycle endpoints work, but real query/abort Java↔Node wire and restart/event convergence are not proven. |
| AC-16 | `FAIL` | Slice 8 is a disconnected inventory-plus-seeded-JPA fixture; no production reference/proof chain exists. |
| AC-17 | `PASS` | Existing typed/Open SDK mappings and all affected reactor tests pass; no public enum/wire change was found. |
| AC-18 | `PASS` | Maven dependency direction compiles/tests without a cycle; adapters/participants use SPI boundaries. |
| AC-19 | `FAIL` | Production normalization makes never-accepted non-authoritative and terminal commit requires a provider-terminal fact. |
| AC-20 | `FAIL` | Java uses hard-coded/reused dispatch IDs and does not persist lifecycle disposition before business SSE. |
| AC-21 | `PARTIAL` | Open SDK/disabled fixtures pass; exact first-canary public vertical lane is not demonstrated. |
| AC-22 | `FAIL` | Receipt/outbox share a transaction, but no exact enrollment or owner lifecycle fact is included. |
| AC-23 | `FAIL` | codex-biz support tests mock the Worker client; no full public vertical route proves termination/reconciliation. |
| AC-24 | `FAIL` | Proof authorization has no product caller and proof quarantine does not quarantine referenced aggregates. |
| AC-25 | `FAIL` | Worker can persist provider ID, but Java does not consume/persist the initial lifecycle disposition before business SSE. |
| AC-26 | `PASS IN NODE SCOPE` | Node lifecycle route/mode/digest negative tests pass; this does not replace missing real command-route integration. |
| AC-27 | `FAIL` | No production acquisition/release of exact Worker+Session+Task references; Slice 8 has only a seeded Task reference. |
| AC-28 | `FAIL` | Row-lock races are real, but provider count is a local counter and the proof authorization is not wired to a product effect. |
| AC-29 | `PASS` | Frozen availability/conflict precedence and clear/reveal reducer tests pass. |

## Independently Executed Verification

Counts are from the generated Surefire XML for the named module(s), not inferred from test source
presence. Every required command was executed; there was no environment-blocked required item.

| Command | Exit | Tests / result |
|---|---:|---|
| `mvn -q -pl session-module -am -Dtest='TaskLifecycleOwnerVerticalIntegrationTest,LifecycleIngressGateIntegrationTest,TaskTerminationIntentRecorderIntegrationTest,TaskLifecycleReducerTest,WorkerLifecycleSentinelTest,WriterExclusivityProofConcurrencyIntegrationTest,WriterExclusivityProofServiceTest,IsolatedEnforcedLifecycleContractTest,TaskTerminalCommitServiceTest,TerminalCleanupPlanFactoryTest,TerminalCleanupStepExecutorTest' -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | 30 tests; 0 failure, 0 error, 0 skip |
| `mvn -pl session-module -am test` | 0 | Session Module 482; 0 failure, 0 error, 1 skip (the separately executed opt-in ARCH-001 MySQL test) |
| `mvn -q -pl business-agent-module -am -Dtest='BusinessTaskScopedTokenTerminalListenerTest,BusinessTerminalCleanupPortTest,BusinessTaskScopedTokenLifecycleJpaTest,BusinessAgentTaskScopedTokenRuntimeStoreTest,BusinessTaskScopedTokenLifecycleServiceTest,BusinessTaskScopedTokenPolicyServiceTest,BusinessTaskScopedTokenSchemaPreflightTest' -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | 92 tests; 0 failure, 0 error, 0 skip |
| `mvn -pl business-agent-module -am test` | 0 | Business Agent Module 739; 0 failure, 0 error, 0 skip |
| `mvn -q -pl addons/claude-worker-agent -am -Dtest='RuntimeTaskClosureServiceTest,RuntimeTaskCompletionReadinessServiceTest,RuntimeTaskTypedContractServiceTest,RuntimeTerminationAcceptanceCoordinatorTest,RuntimeTerminationDeliveryRecoveryTest' -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | 34 tests; 0 failure, 0 error, 0 skip |
| `mvn -pl addons/claude-worker-agent -am test` | 0 | Claude Worker Addon 461; 0 failure, 0 error, 0 skip |
| `mvn -q -pl addons/codex-worker-agent -am -Dtest='CodexWorkerLifecycleHttpAdapterTest,CodexWorkerLifecycleNodeContractIntegrationTest,CodexTaskServiceTest,CodexStreamRelayTest,CodexWorkerFacadeRuntimeClosureProviderTest' -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | 197 tests; 0 failure, 0 error, 0 skip |
| `mvn -pl addons/codex-worker-agent -am test` | 0 | Codex Worker Addon 495; 0 failure, 0 error, 0 skip |
| `cd tools/codex-agent-worker && npm run typecheck && npm test` | 0 | typecheck passed; 264 tests: 262 pass, 0 fail, 2 skip (Windows-only behavior) |
| `cd tools/codex-agent-worker && npm run build` | 0 | TypeScript build passed |
| `mvn -pl navigator-open-sdk -am test` | 0 | Open SDK 203; 0 failure, 0 error, 0 skip |
| `mvn -q -pl session-module -am -Darch001.mysql.integration=true -Dtest=LifecycleMigrationMySqlIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | 1 Testcontainers MySQL test; 0 failure, 0 error, 0 skip |
| `mvn -pl session-module,business-agent-module,addons/claude-worker-agent,addons/codex-worker-agent,navigator-open-sdk -am test` | 0 | Affected reactor 2,895; 0 failure, 0 error, 4 skip |
| `mvn test -pl launcher -am` | 0 | Launcher reactor 3,227; 0 failure, 0 error, 6 skip |
| `git diff --check` after documentation | 0 | No whitespace error; repeated after recording this result |

The affected-reactor skips were three unrelated opt-in GOV-001 MySQL cases plus the separately
run ARCH-001 MySQL case. The launcher reactor additionally skipped two unrelated opt-in
Codex app-server E2E cases. No required ARCH-001 verification remained skipped.

## Public Compatibility

- verdict: `PASS`
- No public Open SDK DTO or request/response wire change was found in the remediation diff.
- Open SDK 203/203 tests passed.
- Receipt-disabled same request ID remains two independent provider attempts; reconciliation
  remains `AMBIGUOUS`, unavailable and fail-closed.
- SHADOW keeps the legacy Codex provider wire and does not gain lifecycle-owner provider effects.
- No SDK or CLI publication is required for this rejected remediation/signoff record.

## Deviations, Unrun Items and Residual Risks

### Deviations

- No approved goal, authority, Worker-v1 wire, public compatibility policy, additive migration
  strategy or activation boundary needs to change. Therefore this is not `NEEDS_REPLAN`.
- The implementation deviates from the approved contract in the B1–B6 areas identified above.

### Intentionally Unrun by Authorized Boundary

- production/shared database pre-apply or rollback;
- real controller inventory/disable/late-relaunch;
- real Navigator or Worker stop/start/restart/upgrade/deploy;
- first non-fixture ENFORCED aggregate;
- live SIM;
- historical Task `20260730-0e01`;
- sibling repositories or business data.

These are activation/deployment operations, not environment failures in the required source
signoff matrix.

### Residual Risks / Blocking Items

1. `ARCH-001-B1-sentinel-not-bootstrap-or-events`: no first Worker snapshot discovery and no
   production events consumption.
2. `ARCH-001-B1-operation-binding-drift`: termination mutates the owner operation binding while
   normal Worker facts retain the initial dispatch operation.
3. `ARCH-001-B2-no-durable-outbox-consumer`: commit-before-dispatch has no autonomous recovery,
   and production recovery tests are coordinator mocks.
4. `ARCH-001-B2-exact-admission-and-proof-bypass`: missing enrollment is accepted, no owner fact
   is committed, and termination authorization bypasses proof/reference.
5. `ARCH-001-B3-never-accepted-unreachable`: the approved exact pre-effect rejection authority
   cannot reach canonical terminal/tombstone/cleanup.
6. `ARCH-001-B3-cleanup-applicability-inferred`: token/receipt applicability is guessed from
   provider/operation strings instead of exact durable resources.
7. `ARCH-001-B4-command-wire-not-integrated`: the Java↔Node test does not mount query/abort, Java
   ignores lifecycle disposition, and termination dispatch identity is not exact.
8. `ARCH-001-B4-abort-terminal-fact-gap`: a normal asynchronous cancel completion does not update
   the abort lifecycle dispatch to RESULT_OBSERVED/terminal fact.
9. `ARCH-001-B5-proof-service-not-on-product-path`: the strong proof authorization service has no
   production effect caller and proof loss does not quarantine referenced aggregates.
10. `ARCH-001-B5-slice8-not-vertical`: the Node inventory request and seeded JPA proof fixture are
    independent halves, not one real provider-effect chain.
11. `ARCH-001-B6-rollback-branches-not-executed`: the repo-owned MySQL fixture executes only the
    ENFORCED Task-marker rollback gate; active generation/reference/unfinished-outbox gates are
    supported only by SQL text, not independent runtime evidence.

## Activation and Operational Conclusion

- canonical delivery status: `REJECTED`
- real activation gate: `CLOSED`
- first non-fixture ENFORCED aggregate: prohibited
- SDK/CLI publication required: `NO`
- Navigator/Worker restart for this audit: `NO`
- later deployment implication: if a future corrected implementation is independently accepted
  and separately authorized for deployment, both the Navigator Java runtime and Codex Worker
  runtime changes would require controlled rollout/restart after production schema pre-apply.
