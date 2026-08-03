---
workitem: NAVI-CORE-001-S3-05A2
status: IMPLEMENTED_REVIEWED
date: 2026-08-03
baseline: 5032da82a85339ba832da2c18589779fc47971f7
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
---

# S3-05A2 extension runtime-affinity consumer

`CodexTaskExtensionController` no longer depends on `CodexRuntimeRegistryService` or reads runtime
endpoint/credential state. Generated-image, context-usage, termination-inspection and both compact
entry points copy the already-owned and already-validated persisted Task affinity into the A1
seven-field immutable `DurableAffinity`: raw provider type, runtime id, runtime revision, runtime
type, physical Worker id, runtime instance id and routing epoch. The Controller immediately calls
`runtimeAffinityAdapter.client(runtimeAffinityAdapter.resolveBound(affinity))`; it does not retain,
inspect or expose `BoundRuntime`.

The approved handoff named a convenience API `clientForBound(...)`, while accepted A1 at this
baseline exposes the equivalent two-step `resolveBound(DurableAffinity)` and
`client(BoundRuntime)` API. The root controller explicitly approved the single-expression semantic
composition above rather than reopening the already-reviewed A1 adapter solely to add a convenience
method. The three-path scope and all affinity semantics remain unchanged.

The existing authorization and validation sequence remains intact: unified Task ownership, private
Task provider/runtime/session/Worker/owner/tenant agreement, artifact/thread/operation/terminal
checks and current Worker access still precede provider access. The existing exception boundaries
also remain exact: generated image, context usage and compact resolve the adapter client outside the
provider try/catch, while termination inspection resolves inside its try so
`CodexRuntimeUnavailableException` continues to expose only its safe reason code. Response shapes,
image headers and cleaning, remote Task fallback, reason codes, compact request, 30-second image
timeout and other provider timeouts are unchanged.

`CodexWorkerClientFactory` remains solely for SDK file-hints and retains its original three-argument
client call. AppServer paths neither call the factory nor resolve Registry state directly. A legacy
Task missing any of the seven persisted affinity fields fails closed before Worker/provider effect;
there is no fallback, repair, backfill, field splicing or historical-data mutation.

## Changed paths

- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/controller/CodexTaskExtensionController.java`
- `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/controller/CodexTaskExtensionControllerTest.java`
- this work item

## Focused validation

- Before modification, the complete `CodexTaskExtensionControllerTest` class passed `31/31`,
  failures/errors/skips `0`; wall `24.09 s` (`user 11.28 s`, `sys 3.93 s`).
- Production compile after implementation passed on its first run:
  `/usr/bin/time -p mvn -q -pl addons/codex-worker-agent -am -DskipTests compile`; wall `19.51 s`
  (`user 22.89 s`, `sys 3.91 s`).
- The first complete post-change `CodexTaskExtensionControllerTest` run passed `37/37`,
  failures/errors/skips `0`; wall `31.71 s` (`user 34.34 s`, `sys 5.74 s`). The six additional
  executions come from the seven-field missing-affinity matrix replacing the former single missing
  instance case.
- Positive tests prove generated image, context usage, termination inspection, compact submission
  and compact-operation read each preserve owner/Worker-access order, pass the exact seven persisted
  fields to `resolveBound`, pass the returned opaque proof immediately to `client`, and only then
  call the Worker. The missing-field matrix covers provider, runtime id, revision, runtime type,
  Worker, instance and epoch with zero adapter, client-factory or provider effect. SDK file-hints
  proves the unchanged three-argument factory call and zero adapter interaction.
- No focused selector repair run or second post-change complete-class run was required. No
  affected-module lane, full, E2E, live, package, process action, database access, data mutation,
  commit or push was performed.

## Independent review

Independent read-only review returned `ACCEPT` with no MAJOR or MINOR. It confirmed the exact
three-path scope, seven persisted-field handoff, all five AppServer entry-point resolve/client
orders and original exception boundaries, the SDK-only three-argument factory path, public response
compatibility and the structure of all 37 test executions. The reviewer reused the recorded focused
evidence and did not modify files, rerun tests or inspect `BOOT-INF/`.

## Residual boundary

A1 continues
to own Registry identity, endpoint/credential and client construction; the Controller owns only
authorization, request validation, response mapping and the raw persisted affinity handoff. There
is no known functional residual inside this slice. The pre-existing untracked `BOOT-INF/` tree was
not read or changed.
