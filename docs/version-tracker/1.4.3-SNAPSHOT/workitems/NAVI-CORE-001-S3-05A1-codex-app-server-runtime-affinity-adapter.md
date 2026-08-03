---
workitem: NAVI-CORE-001-S3-05A1
status: IMPLEMENTED_REVIEWED
date: 2026-08-03
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
baseline: 54e2fae18bf60f138ccce74097f906e1ab9be58a
---

# NAVI-CORE-001 S3-05A1 Codex App Server runtime affinity adapter

The exact `codex-app-server-worker` runtime-affinity boundary is now the dedicated
`CodexAppServerRuntimeAffinityAdapter`. It depends only on `CodexRuntimeRegistryService` and
`CodexWorkerClientFactory`, accepts no SDK, Biz, generic or `legacy-sdk:*` identity, and exposes an
immutable durable affinity containing exactly provider type, runtime id, runtime revision, runtime
type, Worker id, instance id and routing epoch. Endpoint and credential remain private current
Registry inputs and are never part of the durable value.

New AppServer tasks select through the adapter and persist the epoch returned by that selection.
Existing Task and Session paths resolve only a complete seven-field affinity. A Session with no
runtime markers may use the latest Task's complete affinity as one source; a partially populated
Session fails closed and never falls back, reselects or splices fields from another source. Current
Registry endpoint and credential rotation is accepted after exact runtime/revision/Worker/instance
proof, while the current Registry epoch never overwrites the persisted epoch. Missing legacy fields
fail before provider effect without save, repair, backfill or historical-data mutation.

Every AppServer client obtained in `CodexTaskService` now crosses the adapter's four-argument
`getOrCreate(runtimeKey, endpoint, token, persistedInstanceId)` proof. Bound resolution preserves
the Registry's existing archived/disabled-drain semantics and rejects physical replacement,
quarantine, cross-Worker or instance drift. SDK and Biz continue to use the existing three-argument
client path and do not depend on the adapter.

`CodexTaskService` still owns request, tenant, owner, Session, Worker, model, pristine-session and
feature validation, lifecycle/effect orchestration, persistence, events, user input, termination,
reconciliation and deletion policy. The adapter performs no writes and owns no recovery or
termination policy. An unavailable adapter fails only AppServer resolution; SDK/Biz behavior is
unchanged.

## Changed paths

- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexAppServerRuntimeAffinityAdapter.java`
- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java`
- `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexAppServerRuntimeAffinityAdapterTest.java`
- `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexTaskServiceTest.java`
- this work item

## Focused validation

- Pre-change characterization of selected TaskService AppServer command/session/delete behavior,
  historical SDK drain and Registry bound-runtime behavior: `16/16` passed, failures/errors/skips
  `0`; wall `24.42 s` (`user 18.22 s`, `sys 4.45 s`).
- `/usr/bin/time -p mvn -q -pl addons/codex-worker-agent -am -DskipTests compile`: passed; wall
  `19.47 s` (`user 23.93 s`, `sys 3.95 s`).
- Direct `CodexAppServerRuntimeAffinityAdapterTest`: final `8/8` passed, failures/errors/skips `0`;
  wall `30.52 s` (`user 28.89 s`, `sys 5.23 s`). It covers the exact two-dependency service and
  seven-field public value, selection epoch, rejected SDK/Biz/generic/legacy identities, incomplete
  affinity with zero effect, durable-epoch preservation, current endpoint/credential use, exact
  persisted-instance four-argument client proof, identity drift/quarantine, bound capability
  validation and no reselection.
- The first direct-test run had `7/8` passing and one test-code-only Mockito
  `InvalidUseOfMatchersException` in the selection verification (`real 30.79 s`, `user 29.52 s`,
  `sys 5.55 s`). Production code had no failure. The verification was corrected to use matchers for
  all arguments, after which the final direct run above passed.
- Combined adapter plus selected TaskService and Registry focused cases: `27/27` passed,
  failures/errors/skips `0`; wall `24.39 s` (`user 18.35 s`, `sys 4.21 s`). These cover new
  selection, existing Session whole-source affinity, partial Session rejection, latest-complete
  Task fallback, AppServer command/delete, SDK/Biz isolation, archived bound identity, cross-Worker,
  replacement/instance drift and credential rotation.
- Selected AppServer termination/retry/stale-cleanup cases: `3/3` passed,
  failures/errors/skips `0`; wall `23.93 s` (`user 15.56 s`, `sys 4.16 s`).
- The partial-Task deletion zero-effect case passed `1/1`, failures/errors/skips `0`; wall
  `31.38 s` (`user 34.71 s`, `sys 5.92 s`). It proves a missing persisted epoch fails before
  deletion claim, Registry resolution, client/provider effect, save or delete.
- Static scans confirmed that TaskService has no direct Registry dependency, AppServer client
  creation exists only as the adapter's four-argument call, the unchanged SDK client call remains
  three-argument, and the adapter exposes no endpoint, credential, token, request, model, prompt,
  Session or Task payload in its durable value.
- No affected-module lane, full, E2E, live, package, Spring context, service/Worker start-stop or
  historical-data validation was run in this slice.

## Independent review findings and rework

The first independent review returned `REJECT` with three MAJOR findings and one MINOR. It found
that termination routing still chose AppServer from `runtimeType` instead of the raw persisted
provider, operation reservations did not yet preflight or snapshot all seven affinity fields before
mutation and recheck them before dispatch, and read-side provider fallback wrote the interpreted
provider back to the Task entity. It also required strictly positive runtime revision and routing
epoch rather than merely non-null values.

The rework makes raw `Task.providerType` authoritative for every termination signing/client/delete
or command path. Exact AppServer always crosses the complete adapter preflight even when its
`runtimeType` is null or spoofed as `SDK_EXEC`; only exact raw SDK/Biz identities may reach the
three-argument client. A null raw provider cannot borrow AppServer identity from SessionTask or
Session. `resolveProviderType` remains available for compatible read interpretation but is now pure
and never mutates the entity.

General abort, runtime termination/admission/reconciliation, AppServer retry, stale-turn cleanup and
manual PID reservation now run read-only affinity resolution while holding the Task row lock and
before operation accept/supersede or Task status persistence. Their private reservation snapshots
carry the exact raw provider and the complete seven-field AppServer affinity. Before dispatch state
or Worker effect, the Task is re-read, all snapshot fields including provider and durable epoch are
compared, and the exact bound runtime is resolved again. Partial affinity therefore has zero
operation, supersede, save or client effect. The adapter now rejects runtime revision or routing
epoch values that are null, zero or negative.

### Rework focused validation

- Rework production compile passed on its first run: wall `19.45 s` (`user 23.50 s`, `sys 4.06 s`).
- The first rework direct-test command stopped in test compilation because the new Service test used
  `Arrays` without its import; no production test ran or failed. Wall was `27.26 s` (`user 26.45 s`,
  `sys 4.52 s`). Adding the missing test import fixed it.
- Direct `CodexAppServerRuntimeAffinityAdapterTest` then passed `8/8`, failures/errors/skips `0`;
  wall `30.65 s` (`user 31.33 s`, `sys 5.40 s`). Its invalid matrix now includes revision and epoch
  values `0` and `-1` with zero Registry/client effect.
- New raw-provider, provider-splice, non-positive revision/epoch, partial reservation and dispatch
  epoch negative selectors passed `7/7`, failures/errors/skips `0`; wall `24.15 s`
  (`user 16.35 s`, `sys 4.35 s`).
- The first rework combined command ran `27` tests with `25` passing and two test-adaptation
  failures: one user-input fixture encountered an unnecessary early registration preflight, and one
  delete assertion still expected one Registry resolution although the new claim fence resolves
  before and after claim. The unnecessary scope expansion was removed and the assertion updated to
  the intended two resolutions; no production defect was found. Wall was `24.70 s`
  (`user 18.78 s`, `sys 4.45 s`).
- The original adapter plus selected TaskService/Registry combined command then passed `27/27`,
  failures/errors/skips `0`; wall `36.74 s` (`user 47.10 s`, `sys 7.01 s`).
- The original AppServer retry/stale-cleanup termination selectors passed `3/3`,
  failures/errors/skips `0`; wall `24.32 s` (`user 16.51 s`, `sys 4.29 s`).
- Twenty-one selected SDK closure, termination admission, general abort, manual PID and runtime
  reconciliation compatibility cases passed `21/21`, failures/errors/skips `0`; wall `24.52 s`
  (`user 16.88 s`, `sys 4.39 s`).
- General-abort epoch, stale-cleanup epoch and stale-cleanup raw-provider dispatch fences passed
  `3/3`, failures/errors/skips `0`; wall `32.05 s` (`user 36.31 s`, `sys 5.97 s`).
- A final production compile after the last command-order cleanup passed; wall `19.33 s`
  (`user 22.86 s`, `sys 3.73 s`).
- The original partial-Task deletion zero-effect case is included in the passing new negative
  selector run. No affected-module lane, full, E2E, live, package, Spring context or historical-data
  validation was run during rework.
- Final tracked and untracked whitespace checks produced no errors. Static scans confirmed no direct
  Registry dependency in TaskService, one four-argument AppServer client factory call in the adapter,
  one unchanged three-argument SDK call in TaskService, a pure `resolveProviderType`, and exactly the
  five authorized changed paths plus the untouched pre-existing `BOOT-INF/` tree.

## Second independent review and rework

The second independent review again returned `REJECT`, with two MAJOR findings. First, command
authorization still allowed interpreted Session/SessionTask provider fallback: every command must
authorize from the exact raw Task provider, projection sync must not write an interpreted provider
back to that Task, and AppServer reconnect/resync must finish exact bound-runtime preflight before
relay, persistence or event effects. Second, an already-existing runtime reconciliation operation
did not yet acquire the Task row lock and reserve a seven-field affinity snapshot before replay,
recheck it before the readiness request, and recheck it again under the terminal-mutation lock.

The second rework makes `requireTaskProvider` compare only the raw persisted Task provider. A null
raw provider is now an opaque not-found for every command even if SessionTask or Session can still
interpret it for compatible reads. `syncSessionTask` uses that interpreted provider only in its
projection and never mutates the Task entity. AppServer reconnect performs its first adapter
preflight before the active-state gate, then acquires the Task row lock in the termination
transaction, revalidates the full snapshot and performs the relay effect while that lock remains
held. AppServer resync already owns the Task lock and now completes the same adapter preflight before
changing status, saving, publishing or reconnecting. Missing affinity, a null or `SDK_EXEC` runtime,
or provider/instance/epoch drift therefore has zero command effect or mutation.

Existing reconciliation replay now enters a short Task-row-lock transaction and creates the same
raw-provider plus seven-field AppServer affinity snapshot used by new reconciliation reservation.
It re-reads and validates that snapshot before requesting readiness. The locked terminal mutation
accepts the snapshot and validates it again before status change, save, terminal observation or
event publication. Provider, instance or epoch drift before readiness has no readiness request,
terminal observation or mutation; drift at the final lock also prevents terminal mutation and
operation observation.

### Second-rework focused validation

- Production compile after the code rework passed on its first run: wall `19.43 s` (`user 23.76 s`,
  `sys 3.69 s`).
- The first new raw-command/projection/replay selector run passed `9/9`, failures/errors/skips `0`;
  wall `36.58 s` (`user 45.20 s`, `sys 7.34 s`). It includes three parameterized AppServer
  existing-operation provider/instance/epoch drift cases and a final terminal-lock epoch fence.
- Two additional AppServer reconnect lock/order selectors first completed their production
  assertions but were reported as two test errors by strict Mockito because a shared helper had an
  unused client stub; wall `31.96 s` (`user 36.26 s`, `sys 5.89 s`). Making only that optional helper
  stub lenient resolved the test-only issue; the rerun passed `2/2`, failures/errors/skips `0`, wall
  `31.50 s` (`user 35.08 s`, `sys 5.77 s`).
- Direct `CodexAppServerRuntimeAffinityAdapterTest` passed `8/8`, failures/errors/skips `0`; wall
  `22.64 s` (`user 9.25 s`, `sys 3.48 s`).
- Eight prior raw-provider, partial-affinity and dispatch-snapshot selectors first passed `7/8`;
  one assertion still expected an affinity exception where the new raw-null authorization contract
  intentionally returns opaque not-found. After adapting that assertion, the same set passed `8/8`,
  failures/errors/skips `0`; wall `31.81 s` (`user 35.40 s`, `sys 5.97 s`).
- Twenty-seven command and Session compatibility selectors first passed `25/27`; the two failures
  were historical rewind test fixtures with no raw SDK provider. Supplying the fixture's intended
  SDK provider/runtime made the unchanged behavior reachable, and the rerun passed `27/27`,
  failures/errors/skips `0`; wall `32.12 s` (`user 37.15 s`, `sys 5.72 s`).
- Twenty-six termination and reconciliation compatibility selectors first passed `25/26`; the one
  failure was an old assertion expecting one transaction although existing-operation replay now
  intentionally uses a preflight transaction and a terminal-mutation transaction. After updating
  that assertion, the rerun passed `26/26`, failures/errors/skips `0`; wall `32.46 s`
  (`user 37.93 s`, `sys 6.07 s`).
- The final combined second-rework selector run passed `11/11`, failures/errors/skips `0`; wall
  `24.18 s` (`user 15.81 s`, `sys 4.51 s`). Final production compile passed; wall `12.88 s`
  (`user 4.45 s`, `sys 2.15 s`).
- No affected-module lane, full, E2E, live, package, Spring context, service/Worker process action,
  database access or historical-data mutation was performed in the second rework.

## Third independent review and narrow rework

The third independent review returned one remaining MAJOR. Existing-operation replay acquired and
rechecked its seven-field snapshot before the reconciliation-readiness request, but its earlier
Worker `getTaskStatus` query still ran before that row-lock preflight. If that query returned
`aborted`, the method used the older null-snapshot terminal helper, so provider/runtime affinity
could drift between the Worker observation and terminal Task lock and still be written as
`ABORTED`.

The narrow third rework probes only valid, non-dry-run reconciliation request IDs for an existing
operation before the first provider status query. When an operation already exists, it validates
the operation identity, enters the existing short Task-row-lock transaction, creates the raw
provider plus complete seven-field AppServer snapshot, then re-reads and validates a fresh Task
before obtaining the client or calling `getTaskStatus`. A Worker `aborted` observation now passes
that exact snapshot into the locked terminal mutation. The lock validates it again before status,
save, Task-level terminal observation or event effects. The null-snapshot helper remains only for
the unchanged no-existing-operation status observation path.

If the early read-only operation probe misses, the no-existing/new-reconciliation path continues
through its prior provider observation, later operation lookup, locked reservation and dispatch
flow. Dry-run behavior is unchanged. Existing readiness replay reuses the early snapshot and still
performs its fresh pre-readiness validation. No SDK/Biz client routing, reconnect/resync behavior,
public contract, persistence model or historical data is changed.

### Third-rework focused validation

- Production compile passed on the first run after the narrow code change: wall `19.66 s`
  (`user 22.87 s`, `sys 3.84 s`).
- Five parameterized AppServer existing-operation cases changed raw provider, runtime id, runtime
  revision, instance id or routing epoch only after the Worker returned `aborted` and before the
  terminal row lock. Together with one stable-snapshot positive case, the new selector run passed
  `6/6`, failures/errors/skips `0`, on its first run; wall `31.91 s` (`user 35.23 s`,
  `sys 6.17 s`). Every drift case proves no Task/Session save, terminal observation, readiness,
  reconciliation dispatch, relay or event effect; the stable case preserves the existing
  `WORKER_TERMINAL_ABORTED` result and durable `ABORTED` transition.
- Eight existing-replay, new-reconciliation, SDK closure and affinity-fence neighbors first ran with
  five passing and three strict-Mockito test errors. The three pre-readiness drift tests now fail
  before provider status as intended, leaving their old status/original-operation stubs unused; no
  assertion or production behavior failed. Removing those two obsolete stubs and asserting zero
  `getTaskStatus` produced `8/8` passing, failures/errors/skips `0`; wall `32.16 s`
  (`user 37.27 s`, `sys 6.23 s`). The initial test-only run was `23.96 s`
  (`user 16.02 s`, `sys 4.02 s`).
- Direct `CodexAppServerRuntimeAffinityAdapterTest` passed `8/8`, failures/errors/skips `0`; wall
  `22.34 s` (`user 8.61 s`, `sys 3.71 s`).
- Six reconnect/resync SDK and AppServer compatibility selectors passed `6/6`,
  failures/errors/skips `0`; wall `24.52 s` (`user 15.48 s`, `sys 4.26 s`).
- Final production compile passed; wall `12.81 s` (`user 4.62 s`, `sys 1.90 s`). No affected-module
  lane, full, E2E, live, package, Spring context, process action, database access, data mutation,
  commit or push was performed.

## Final independent review

The final strictly scoped independent read-only review returned `ACCEPT`. It confirmed that an
existing non-dry-run reconciliation operation now acquires and rechecks the complete seven-field
snapshot before the first Worker status query, and that an `aborted` observation carries the same
snapshot into the terminal row lock before any save, terminal observation or event. It also
confirmed the five drift negatives and stable positive, the unchanged no-existing/new/dry-run and
SDK/Biz flows, the exact five-path budget, and consistency of the recorded focused evidence. The
reviewer did not modify files, rerun tests or inspect `BOOT-INF/`.

## Residual boundary

The filtered Codex affected lane remains intentionally deferred to S3-05B, where the complete
AppServer affinity and execution extraction will receive one affected validation. No public DTO,
HTTP path, Worker
protocol, Entity, repository, migration, Registry, Binding model, Relay, Extension Controller,
SDK/Biz contract or persistence schema was changed. No historical or existing business/runtime
data was read or mutated, no disposable fixture or process was created, and no commit or push was
performed. The unrelated pre-existing untracked `BOOT-INF/` tree was not inspected or changed.
