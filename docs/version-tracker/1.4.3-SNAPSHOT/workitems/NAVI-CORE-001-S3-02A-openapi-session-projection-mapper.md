---
workitem: NAVI-CORE-001-S3-02A
status: IMPLEMENTED_REVIEWED
date: 2026-08-03
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
---

# NAVI-CORE-001 S3-02A Open API Session projection mapper

`OpenApiController` now delegates durable Session message, synthetic Task failure message and
Session summary projection to `OpenApiSessionProjectionMapper`. The mapper's only injected
dependency is `ObjectMapper`; its inputs are already loaded entities, caller-resolved context and
caller-precomputed public Task status. It performs no authentication, ownership decision, query,
pagination, visibility filtering, lifecycle inference or mutation.

Message terminality remains message-evidence based: an owning Task's durable status is exposed as
the message `status` field but never changes `terminal` or `terminalStatus`. Malformed metadata and
client context fail closed to null/nonterminal projections. Existing public shape details remain
stable, including case handling, shallow metadata copy without `taskId`, structured-output
sanitization, attachment order and report/artifact reference normalization and deduplication.

The Controller continues to own all runtime credential/route/owner checks, bounds and cursors,
visibility filtering, query and batch preload operations, context resolution, durable Task status
mapping, failure summary/stage, Task response assembly, diagnostics and evidence. Task final-answer
and evidence helpers deliberately remain in the Controller for the separate S3-02B convergence
slice.

## Changed paths

- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiSessionProjectionMapper.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiController.java`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiSessionProjectionMapperTest.java`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiControllerMessageMappingTest.java`
- this work item

## Focused validation

- `OpenApiSessionProjectionMapperTest`: PASS `6/6`; direct coverage includes message terminal and
  event/progress semantics, attachment/report/artifact references, structured-output sanitization,
  malformed/empty metadata, synthetic failure and Session summary projection.
- Selected `OpenApiControllerMessageMappingTest`: PASS `10/10` for the required terminal/result,
  structured output, tool nonterminal, owning Task status, attachment, event/evidence reference,
  synthetic failed-Task, Session title/context and internal-message visibility characterizations.
- Combined focused command: PASS `16/16`, failures/errors/skipped `0`.
- `git diff --check`: PASS.
- No affected-module, full, E2E, live, package, install, service, Worker or data validation is in
  this narrow slice.

## Independent review history

- Review found one MINOR equivalence gap in the Controller wiring: a null durable Task status was
  mapped to response-level `UNKNOWN` before projection and therefore also appeared as message-level
  `UNKNOWN`; the prior contract kept the response status `UNKNOWN` while message status stayed
  null.
- The Controller now retains the raw status long enough to derive those two projections
  independently. A focused endpoint regression asserts both fields and message nonterminality.
- The first invocation of the new regression exposed only an incorrect mock limit (`51` instead of
  the Controller's stable `50`), not a production-code failure. After correcting that fixture, the
  exact single-test rerun passed `1/1`, failures/errors/skipped `0`.
- No affected or broader suite was run for the review correction.

## Residual boundary

This slice does not change any public path, DTO/Form, status/reason enum, credential or ownership
rule, provider selection, Task response/evidence contract, lifecycle authority, runtime data or
historical data. S3-02B owns the later Task projection extraction and shared pure-helper
convergence.
