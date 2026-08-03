---
workitem: NAVI-CORE-001-S3-05B
status: IMPLEMENTED_REVIEWED
date: 2026-08-03
baseline: 85a1aa92aa2c0773d0f608f49aadbc266aec3035
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
---

# NAVI-CORE-001 S3-05B Codex AppServer execution adapter

The Codex AppServer execution seam is now the dedicated
`CodexAppServerExecutionAdapter`. Its dependency allowlist is exactly the accepted A1
`CodexAppServerRuntimeAffinityAdapter`, `CodexAppServerAcceptanceService` and
`CodexTaskRuntimeStateService`. It has no repository, TaskService, Registry, client factory,
lifecycle/event/terminal authority, scheduler, lock, recovery budget or lease dependency.

Relay projects the persisted Task into the Navigator task id, nullable persisted Worker task id and
the complete seven-field A1 `DurableAffinity`. The adapter resolves that value through A1 and keeps
the exact returned client in a controlled handle whose constructor is private. Subscribe and status
operations accept only that handle and therefore cannot receive another task id, Worker task id,
affinity or client from their caller. Missing or blank task identity and incomplete affinity fail
closed without fallback, repair, backfill or historical-data mutation.

Initial acceptance preserves the established order: A1 resolve/client, exact request construction,
`prepareAcceptance`, `accept` using the Navigator task id as provider idempotency key, the existing
AcceptanceService's durable `recordAccepted`, Relay's `appServerAccepted=true`, subscription-state
admission, then provider subscribe using the accepted id. Subscription denial happens before the
provider subscribe call. Existing AcceptanceService rejected/cancelled/unknown exception types and
Relay's corresponding failure/result-unknown/recovery policy are unchanged; on unknown acceptance
the adapter calls `markAcceptanceUnknown` and rethrows the original exception.

Recovery remains owned by Relay. It acquires the existing finite policy lease before the adapter is
called. A missing acceptance loads only `loadPreparedRequest(handle.navigatorTaskId)`. One automatic
policy attempt makes exactly one existing `acceptForRecoveryAttempt` call; manual reconnect uses the
normal existing `accept` policy. Relay then re-reads the persisted Task and creates a fresh handle,
so the subscription uses only the durably recorded Worker task id. A continuation that already has
a Worker task id can subscribe or observe status but cannot recreate or cross-resume another task.

Every status observation re-reads the persisted Task and performs a fresh seven-field A1 proof.
The adapter queries only the handle's persisted Worker task id, rejects a mismatched response task
id and returns only raw safe status, outcome, thread, model, error code and pending-interaction
fields. It owns no terminal classifier. Relay retains the existing policy: remote failed and
aborted outcomes reconcile terminal state, while remote completed without the final durable SSE
remains nonterminal and recoverable.

AppServer execution selection now consistently uses the exact raw persisted
`codex-app-server-worker` provider, including SSE error/completion observation. SDK and Biz raw
providers cannot borrow AppServer behavior from a spoofed `APP_SERVER` runtime type. Conversely,
an exact AppServer provider with a spoofed SDK runtime fails through A1 before Registry, client,
status or SDK fallback effect.

All SSE concatenation, sequence/gap/duplicate handling, durable message and atomic terminal ACK,
terminal event mapping, message/tool/image/input/native-subtask events, operation locks, recovery
scheduling and lease transfer remain in `CodexStreamRelay`. The automatic lease is transferred to
the connection-settlement callback only after the callback-bearing Flux is obtained and the local
subscription is registered; every earlier exit remains covered by Relay's `finally` close. SDK and
Biz execution continue through the unchanged `CodexSdkExecutionAdapter`.

## Changed paths

- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexAppServerExecutionAdapter.java`
- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexStreamRelay.java`
- `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexAppServerExecutionAdapterTest.java`
- `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexStreamRelayTest.java`
- this work item

## Frozen characterization baseline

The scout's frozen command, which had previously existed only in the controller message, was run
unchanged before any implementation edit:

```bash
mvn -pl addons/codex-worker-agent -am -Dtest='CodexStreamRelayTest#appServerPersistsAcceptanceBeforeSubscribe+appServerUnknownAcceptanceRetriesOnlySameIdempotencyKeyAndRuntime+appServerIdempotencyConflictIsStableFailureWithoutRetry+failureAfterAcceptanceDoesNotMarkPotentiallyRunningTaskFailed+applicationReadyRecoveryRecreatesMissingWorkerTaskIdWithEncryptedEnvelope+applicationReadyFailsPreparedTaskThatNeverStartedRemoteAcceptance+manualReconnectReconcilesRemoteAbortBeforeResubscribing+manualReconnectKeepsCompletedStatusRecoverableUntilFinalSse+automaticConcurrencyPermitWaitsForSseConnectionSettlement+sseCompletionWithoutTerminalEventDoesNotPersistNullCompletion+acceptedAppServerStreamExhaustionKeepsLocalTaskRunningWhenRemoteIsNonTerminal+reconnectingAcceptedTaskConsumesFinalRemoteResultWithoutRecreatingTask+reconnectWaitsForInitialAcceptanceAndDoesNotOpenSecondStream+durableMessageFailureTerminatesStreamBeforeHigherSequenceCanAck+sequenceGapTerminatesStreamWithoutPublishingOrAdvancingAck+sequencedResultDoesNotAdvanceReplayCursorWhenAtomicTerminalAckFails' -Dsurefire.failIfNoSpecifiedTests=false test
```

The 16 method selectors produced 17 JUnit executions because
`applicationReadyRecoveryRecreatesMissingWorkerTaskIdWithEncryptedEnvelope` is parameterized.
Baseline result: `17/17` passed, failures/errors/skips `0`; Surefire `3.439 s`, Maven total
`24.141 s`, observed wall `25.64 s`.

## Focused validation

- Production compile passed on its first run:
  `mvn -pl addons/codex-worker-agent -am -DskipTests compile`; Maven total `18.489 s`, observed wall
  `19.97 s`.
- The exact initial acceptance ordering selector passed `1/1`, failures/errors/skips `0`.
- Complete `CodexAppServerExecutionAdapterTest` passed on its first run: `14/14`,
  failures/errors/skips `0`; Surefire `1.727 s`, Maven total `30.280 s`. It covers the exact
  three-dependency allowlist and private handle constructor, exact seven-field A1/client bind,
  partial-affinity zero effect, same-key acceptance order, mismatch rejection, automatic/manual
  recovery calls, continuation recreation denial, pre-provider subscription denial, raw safe
  status, task mismatch, no generic terminal authority, unknown marking/rethrow and preservation of
  existing rejected/cancelled exceptions.
- The unchanged frozen 16-selector command then passed `17/17`, failures/errors/skips `0`;
  Surefire `3.349 s`, Maven total `23.882 s`.
- The raw-provider spoof negatives passed on their first targeted run: `3/3`,
  failures/errors/skips `0`; Surefire `2.240 s`, Maven total `35.613 s`. The parameterized SDK/Biz
  cases prove a spoofed AppServer runtime type causes no A1/status reconciliation, while the exact
  AppServer case with spoofed SDK runtime fails A1 closed with no SDK/client fallback.
- The required final complete pair ran once:
  `mvn -pl addons/codex-worker-agent -am
  -Dtest='CodexAppServerExecutionAdapterTest,CodexStreamRelayTest'
  -Dsurefire.failIfNoSpecifiedTests=false test`. It passed `80/80`, failures/errors/skips `0`:
  adapter `14`, Relay `66`; Surefire `1.836 s` and `2.163 s`, Maven total `25.443 s`, observed wall
  `26.18 s`.
- Three independent no-final-SSE proofs are present: the adapter exposes completed only as a raw
  observation with no terminal helper; manual reconnect subscribes for the final SSE after remote
  completed; and SSE completion without a terminal event never persists a null completion.
- There was no production or test first-run failure. No test repair rerun was needed.

## Independent review finding and narrow rework

The first independent review returned one MAJOR. Both adapter acceptance paths attempted
`markAcceptanceUnknown` before rethrowing the provider's original `UnknownException`, but a runtime
state write failure could replace that original exception. On initial acceptance Relay would then
enter its generic pre-acceptance failure path instead of the existing result-unknown/recovery path.

The narrow rework adds one shared best-effort helper used by initial and recovery acceptance. It
still attempts `markAcceptanceUnknown`; if that write fails, the state exception is attached to the
original provider `UnknownException` as one suppressed error. Both paths then always throw the same
original exception object, preserving Relay's existing unknown-acceptance policy while retaining
the state-write diagnostic. No Relay code or policy changed in this rework.

- The new exact selector
  `CodexAppServerExecutionAdapterTest#markAcceptanceUnknownFailureCannotReplaceTheOriginalUnknown`
  passed `1/1`, failures/errors/skips `0`; Surefire `1.438 s`, Maven total `34.991 s`. It proves
  exception identity is unchanged and the exact state error is present in `getSuppressed()`.
- Complete `CodexAppServerExecutionAdapterTest` then passed `15/15`, failures/errors/skips `0`;
  Surefire `1.855 s`, Maven total `23.201 s`, observed wall `23.94 s`.
- No Relay, frozen-selector, compile, affected, full, E2E, live or package command was rerun for this
  adapter-only rework.

## Static and scope checks

Final strictly scoped independent re-review returned `ACCEPT`. It confirmed that both initial and
recovery paths use the same best-effort helper, state-write failure is retained only as a suppressed
diagnostic on the original unknown exception, Relay still reaches its typed unknown catches, and
the new identity/suppressed test plus `15/15` evidence match the implementation. The reviewer did
not reopen the six previously accepted areas, modify files, rerun tests or inspect `BOOT-INF/`.

Static inspection confirms Relay has no injected/direct AppServer Registry, client-factory,
AcceptanceService or RuntimeStateService call dependency. It retains only the existing exception
handling needed to preserve acceptance semantics. The adapter constructor has exactly the three
approved dependencies; it contains no repository, TaskService, scheduler, lease, lock, lifecycle,
event or terminal policy. Provider subscribe/status methods accept no external task-id string.

## Affected validation

- The single planned filtered Codex reactor lane ran once after independent review:
  `/usr/bin/time -p mvn -q -pl addons/codex-worker-agent -am
  -Dtest='*Test,!*E2ETest,!*IntegrationTest,!*Live*'
  -Dsurefire.failIfNoSpecifiedTests=false test`. It passed `2376/2376`, failures/errors/skipped `0`,
  across `257` suites; wall `91.93 s` (`user 168.32 s`, `sys 22.19 s`). Module totals were
  common `128`, SPI `9`, framework `215`, auth `173`, session `493`, business `745` and Codex
  `613` tests.
- `BusinessAgentE2ESampleTest` ran because its name does not match the frozen `!*E2ETest`
  exclusion; it passed and did not require an external system. Expected negative-fixture and
  environment WARN/ERROR lines produced no failure. The lane was not rerun.
- This was an affected unit lane, not a final joint full validation; it consumed none of the
  user-authorized `0/3` final cycles. No live/provider process, service/Worker process, package,
  database or historical-data validation was run.

No existing or historical business/runtime data was read or mutated, and no disposable fixture was
created. No commit or push was performed before this record was finalized.

## Residual boundary

No public DTO, HTTP/SDK/CLI route, Worker protocol, Entity, repository, migration, TaskService,
Registry, A1 affinity adapter, AcceptanceService, RuntimeStateService, SDK/Biz execution contract,
lifecycle/terminal authority or recovery-policy bound was changed. Final full validation remains
outside this implementation slice and is reserved for the user-authorized joint cycles after all
stages and SIM-NAVI work complete. The unrelated pre-existing untracked `BOOT-INF/` tree was not
inspected or changed.
