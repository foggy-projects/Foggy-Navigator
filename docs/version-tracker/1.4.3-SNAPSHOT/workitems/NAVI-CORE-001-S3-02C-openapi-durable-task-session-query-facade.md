---
workitem: NAVI-CORE-001-S3-02C
status: IMPLEMENTED_REVIEWED
date: 2026-08-03
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
---

# NAVI-CORE-001 S3-02C Open API durable Task/Session query facade

`OpenApiController` now delegates owned Task diagnostics/evidence/messages and Session
list/messages durable gathering to `OpenApiDurableTaskSessionQueryFacade`. The facade is a
`@Transactional(readOnly = true)` service whose only constructor dependencies are
`OpenApiSessionQueryService`, `OpenApiSessionProjectionMapper` and `ObjectMapper`; it owns a
stateless internal `OpenApiTaskProjectionMapper` rather than creating another injected dependency.

The Controller continues to perform runtime credential resolution, route resolution, logical
Agent/owner validation and HTTP response wrapping before delegation. Provider-observing Task
status/list, completion/termination readiness, runtime state/request audit and every
create/cancel/terminate/reconcile/token/lifecycle/cleanup path remain in the Controller or their
existing services. The facade accepts no servlet request, header, credential, `UserContext`, Agent,
provider/runtime service or repository.

Compatibility remains stable: foreign tenant or Agent Task ownership fails as the same opaque
`Task not found` result and stops before later reads; Session messages retain context+owner lookup
without a new Agent check. Query-service pagination still receives the clamped limit exactly once
because the underlying service owns its `limit + 1` fetch. Visibility remains after raw-page
selection, invalid cursors continue to inherit the query service's first-page fallback, and Task
raw-null response/message status, ordinary-message terminality and synthetic failed-Task messages
are unchanged. Evidence remains latest `200` in ascending order. Session summaries use exactly the
two existing page batches, and Session messages use one distinct Task-status batch.

## Changed paths

- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiDurableTaskSessionQueryFacade.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiController.java`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiDurableTaskSessionQueryFacadeTest.java`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiControllerMessageMappingTest.java`
- this work item

The existing Controller test changed only its single constructor helper to assemble the facade
with the same query service, mapper and `ObjectMapper` used by the Controller.

## Focused validation

- `/usr/bin/time -p mvn -q -pl addons/claude-worker-agent -am
  -Dtest=OpenApiDurableTaskSessionQueryFacadeTest
  -Dsurefire.failIfNoSpecifiedTests=false test`: final PASS `9/9`, failures/errors/skipped `0`;
  Surefire elapsed `1.519 s`, command wall `37.01 s`. Direct coverage locks the read-only
  annotation and dependency allowlist, owned diagnostics preload, missing/foreign fail-closed
  query order, latest-200 evidence, Task cursor/page/raw-null/synthetic/post-page visibility,
  Session limit/two-batch summaries, context-owner messages/one distinct-status batch, malformed
  JSON fail-closed projection, entity/message/context immutability, the existing blank durable
  Session ID lookup behavior and zero invocation of `updateClientContextJson`. The initial direct
  run passed `8/8`; final equivalence review then found that the first facade draft skipped context
  lookup for a blank Session ID while the old Controller skipped only null. The original behavior
  was restored and the added characterization produced the final `9/9` result.
- `/usr/bin/time -p mvn -q -pl addons/claude-worker-agent -am
  -Dtest='OpenApiControllerMessageMappingTest#getTaskMessagesReturnsSyntheticErrorWhenFailedTaskHasNoPersistedMessages+
  getTaskMessagesKeepsMessageStatusNullWhenOwningTaskStatusIsMissing+
  getSessionMessages_hidesInternalRuntimeMessagesByDefault+
  sessionSummaryIncludesClientContext+
  sessionSummaryUsesFirstUserMessageAsDefaultTitle+
  getTaskDiagnosticsReturnsFactSnapshotForOwnedTask+
  getTaskDiagnosticsReturnsSubmittedFactsWhenTaskNotPickedUp+
  getTaskDiagnosticsRejectsTaskOwnedByAnotherAgent+
  getTaskEvidenceReturnsSanitizedSummariesAndRefs+
  getTaskEvidenceLiftsOpenArtifactFromFinalJsonMessage+
  durableAbortedProjectionOverridesStaleWorkerWorkingStatus+
  runtimeCompletionReadinessMapsContentFreeFactsAndRejectsForeignCredentialLane+
  runtimeStateAuditEndpointsUseOnlyLongTermRuntimeCredentialAndExistingState+
  runtimeStateAuditRejectsForeignCredentialLanesAndOwnerOverridesWithoutQueryingState'
  -Dsurefire.failIfNoSpecifiedTests=false test`: PASS `14/14`, failures/errors/skipped `0`;
  Surefire elapsed `3.494 s`, command wall `25.09 s`.
- `/usr/bin/time -p mvn -q -pl addons/claude-worker-agent -am
  -Dtest=RuntimeStateAuditServiceTest
  -Dsurefire.failIfNoSpecifiedTests=false test`: PASS `11/11`, failures/errors/skipped `0`;
  Surefire elapsed `2.158 s`, command wall `23.75 s`.
- Aggregate final focused result: PASS `34/34`, failures/errors/skipped `0`.
- Final `git diff --check`: PASS.
- No affected-module, full, E2E, live, package, install, service, Worker, provider or data
  validation was run in the implementation session. The filtered Claude affected lane was kept
  for the post-review convergence gate documented below.

## Independent review

Independent read-only review returned `ACCEPT` with no MAJOR or MINOR. It confirmed the credential,
route, Agent and opaque ownership order; Session context+owner behavior; single limit expansion,
post-page visibility and cursor fallback; raw-null/synthetic/terminal/evidence and batch semantics;
the restored blank Session-ID lookup; class-level read-only transaction and dependency allowlist;
zero writes/entity mutation; and that Provider observation, audit, readiness and mutation remain
outside the facade. The reviewer reused the `34/34` evidence and did not rerun tests or inspect the
unrelated `BOOT-INF/` tree.

## Affected validation

- `/usr/bin/time -p mvn -q -pl addons/claude-worker-agent -am
  -Dtest='*Test,!*E2ETest,!*IntegrationTest,!*Live*'
  -Dsurefire.failIfNoSpecifiedTests=false test`: PASS `2309/2309`, failures/errors/skipped `0`,
  wall `96.95 s` (`user 192.46 s`, `sys 24.50 s`). Surefire XML aggregation covered `290`
  suites: `navigator-common 128`, `navigator-spi 9`, `agent-framework 215`,
  `user-auth-module 173`, `session-module 493`, `business-agent-module 745`, and
  `claude-worker-agent 546` tests.
- The run used test-owned disposable fixtures only. Expected negative-fixture WARN/ERROR logging
  did not correspond to test failures.
- This was the one post-review affected Claude lane for S3-02C. It was not a final joint full
  validation and consumed none of the user-authorized `0/3` final cycles.

## Residual boundary

This slice changes no public path, Form/DTO, status/reason code, credential/header precedence,
provider observation, runtime audit, repository, persistence, SDK, SIM or TMS contract. It does not
invoke the query service's sole write method and does not mutate, repair, backfill or otherwise
modify historical data. The unrelated untracked `BOOT-INF/` tree was not inspected or modified.
