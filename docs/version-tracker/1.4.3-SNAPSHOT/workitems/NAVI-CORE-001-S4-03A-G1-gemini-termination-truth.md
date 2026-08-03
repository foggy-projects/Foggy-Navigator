---
workitem: NAVI-CORE-001-S4-03A-G1
status: IMPLEMENTED_REVIEWED
date: 2026-08-04
baseline: c0a4f609
coordination_freeze: c7f9f0f
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: GEMINI_TERMINATION_RECEIPT_TRUTH
---

# NAVI-CORE-001 S4-03A-G1 Gemini termination receipt truth

Gemini termination now treats the Worker response as the only proof that the requested remote
Task was aborted. The Java client transports the existing `task_id/status` JSON receipt. The
relay no longer swallows HTTP, timeout, empty-body, decoding, identity, or outcome failures, and
only accepts an exact persisted Worker Task ID with lowercase `aborted`.

Direct, A2A, and compatibility abort entry points reuse one service mutation order. Terminal Tasks
remain no-ops. Active Tasks keep their original status, stream, persisted projection, and event
state when remote proof is unavailable. A caller-supplied A2A remote ID is an equality fence only;
it is never written into the Task as provider identity.

## Changed paths

- `addons/gemini-worker-agent/src/main/java/com/foggy/navigator/gemini/worker/client/GeminiWorkerClient.java`
- `addons/gemini-worker-agent/src/main/java/com/foggy/navigator/gemini/worker/service/GeminiStreamRelay.java`
- `addons/gemini-worker-agent/src/main/java/com/foggy/navigator/gemini/worker/service/GeminiTaskService.java`
- `addons/gemini-worker-agent/src/test/java/com/foggy/navigator/gemini/worker/client/GeminiWorkerClientTest.java`
- `addons/gemini-worker-agent/src/test/java/com/foggy/navigator/gemini/worker/service/GeminiStreamRelayTest.java`
- `addons/gemini-worker-agent/src/test/java/com/foggy/navigator/gemini/worker/service/GeminiTaskServiceAuthResolutionTest.java`
- this work item

## Validation evidence

- The final affected production compile passed in `17.670 s`.
- Ten exact selectors passed `10/10` with zero failure, error, or skip in `28.296 s`:
  two Client receipt/HTTP cases, two Relay identity/failure-matrix cases, and six Service
  owner/terminal/remote-proof cases.
- Three independent final read-only reviews of ownership/identity, Client-to-Service effect order,
  and failure/receipt evidence each returned `ACCEPT`.
- No whole test class, module/reactor suite, Worker, database, E2E, live Provider, or final joint
  full-validation cycle is authorized for this slice.

## Compatibility and residual risk

No public Controller, adapter, SPI, schema, entity, repository, Worker contract, or other Provider
was changed. A legacy Worker returning an empty success response now fails closed. A lost receipt
after a real Worker abort, or a local persistence failure after an exact receipt, remains
ambiguous and is not automatically retried or reconciled in this slice.

No service or Worker was started. No business/runtime or historical data was read or mutated, and
no repair, backfill, replay, reconciliation, cleanup, or deletion was performed. SIM, TMS, and the
user-owned `BOOT-INF/` directory were not changed.
