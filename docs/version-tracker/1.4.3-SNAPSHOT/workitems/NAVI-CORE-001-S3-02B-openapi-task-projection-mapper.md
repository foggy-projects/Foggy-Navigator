---
workitem: NAVI-CORE-001-S3-02B
status: IMPLEMENTED_REVIEWED
date: 2026-08-03
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
---

# NAVI-CORE-001 S3-02B Open API Task projection mapper

`OpenApiController` now delegates pure Task status, A2A/durable/active DTO, Task message facts,
diagnostics, evidence, final-answer, structured-output, cancellation-capability, correlation,
failure-stage and backend projection to the stateless `OpenApiTaskProjectionMapper`. The same source
file owns the package-private `OpenApiProjectionSupport` primitives shared with
`OpenApiSessionProjectionMapper` for JSON parsing, evidence sanitization, message typing and
report/artifact references.

The mapper has no Spring/Lombok injection and accepts `ObjectMapper`, loaded entities and
caller-precomputed facts explicitly. The Controller still owns HTTP/authentication, tenant and
Agent ownership, context resolution, query/paging/visibility, provider observation, create/cancel,
closure/audit/token/lifecycle and other effects. It preloads the latest message timestamp and
message count before diagnostics projection; neither mapper accepts an Agent, repository or
service.

Compatibility remains unchanged: durable Task facts override stale provider facts, a missing raw
status projects response `UNKNOWN` while message status remains null, owning Task terminality does
not make an ordinary message terminal, and unknown status strings remain opaque. Durable Task DTO
`result` retains raw `resultText`; only diagnostic/evidence surfaces sanitize it. Task-state
references retain first/first-seen order, marker matching retains its existing casing behavior,
malformed Task JSON fails closed to an empty map, and malformed Session metadata continues to
project null. Opaque provider failures gain no new classification.

## Changed paths

- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiTaskProjectionMapper.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiController.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiSessionProjectionMapper.java`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiTaskProjectionMapperTest.java`
- this work item

The existing `OpenApiControllerMessageMappingTest` source was not modified.

## Focused validation

- `mvn -q -pl addons/claude-worker-agent -am -Dtest=OpenApiTaskProjectionMapperTest
  -Dsurefire.failIfNoSpecifiedTests=false test`: final rerun PASS `7/7`, failures/errors/skipped `0`;
  Surefire elapsed `0.033 s` (observed command wall approximately `16.9 s`). The first direct run
  was `6/7` because the new test incorrectly expected `api_key=...` to trigger the existing
  failure-stage classifier; the classifier intentionally recognizes `api key`/`apikey`, while the
  sanitizer independently recognizes `api_key`. The assertion was corrected to preserve the
  existing opaque `DISPATCH` classification; production behavior was not changed.
- `git diff --check && mvn -q -pl addons/claude-worker-agent -am
  -Dtest='OpenApiTaskProjectionMapperTest,OpenApiSessionProjectionMapperTest'
  -Dsurefire.failIfNoSpecifiedTests=false test`: PASS `13/13`, failures/errors/skipped `0` (`7`
  Task mapper and full `6` Session mapper); Surefire elapsed `0.033 s` and `0.284 s` respectively
  (observed command wall approximately `16.8 s`).
- `mvn -q -pl addons/claude-worker-agent -am
  -Dtest='OpenApiControllerMessageMappingTest#terminalStatusCanBeDerivedFromCompletedTaskStatus+
  durableAbortedProjectionOverridesStaleWorkerWorkingStatus+
  getTaskMessagesReturnsSyntheticErrorWhenFailedTaskHasNoPersistedMessages+
  getTaskMessagesKeepsMessageStatusNullWhenOwningTaskStatusIsMissing+
  askAgent_bindsOpenApiBusinessRuntimeTokenToVisibleWorkerTask+
  getTaskDiagnosticsReturnsFactSnapshotForOwnedTask+
  getTaskDiagnosticsReturnsSubmittedFactsWhenTaskNotPickedUp+
  getTaskEvidenceReturnsSanitizedSummariesAndRefs+
  getTaskEvidenceLiftsOpenArtifactFromFinalJsonMessage+
  getTaskDiagnosticsRejectsTaskOwnedByAnotherAgent+
  workerBackendFromProviderType_usesSharedRouteAliasesAndPreservesUnknownFallback+
  appServerLaunchUsesWorkspaceWorkerAndClassifiesOpaqueFailureAsRuntime'
  -Dsurefire.failIfNoSpecifiedTests=false test`: PASS `12/12`, failures/errors/skipped `0`;
  Surefire elapsed `2.670 s` (observed command wall approximately `12.4 s`).
- Final `git diff --check`: PASS.
- No affected-module, full, E2E, live, package, install, service, Worker or data validation was run
  in this slice. The unrelated untracked `BOOT-INF/` tree was not inspected or modified.

## Independent review

Independent read-only review returned `ACCEPT` with no MAJOR or MINOR. It confirmed durable/A2A
precedence, raw-null response/message status, terminal and casing rules, caller-preloaded
diagnostics, Task-result versus evidence sanitization, reference ordering/dedup/query stripping,
distinct malformed-JSON contracts and opaque failure classification. It also confirmed that the
same-file package-private support is stateless and effect-free, Controller ownership and Provider
observation remain in place, and the five-path budget and focused evidence are complete. The
reviewer did not rerun tests or inspect the unrelated `BOOT-INF/` tree.

## Residual boundary

This slice changes no public endpoint, DTO/Form, status/reason enum, ownership, provider routing,
lifecycle, persistence or historical data contract. It does not repair or mutate old data. Query
facade extraction remains the separately gated S3-02C work and must not be inferred from this
projection-only implementation.
