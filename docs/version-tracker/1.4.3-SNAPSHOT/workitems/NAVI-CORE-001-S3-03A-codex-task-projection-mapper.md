---
workitem: NAVI-CORE-001-S3-03A
status: IMPLEMENTED_REVIEWED
date: 2026-08-03
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
---

# NAVI-CORE-001 S3-03A Codex task projection mapper

`CodexTaskService` now delegates its already-resolved `DispatchTaskDTO` field projection,
structured-error allowlist and Codex interaction-state mapping to the dependency-free
`CodexTaskProjectionMapper`.

The Service remains responsible for repository and batch fallback queries, logical Agent and
context resolution, provider resolution (including the existing entity normalization), diagnostic
lookup, event construction and publication, terminal recoverability, task mutation, lifecycle,
runtime, token, stream, page, search and grouping behavior. The mapper receives those resolved
values explicitly and never falls back from a missing logical Agent to the provider.

Timeout projection uses one caller-captured `observedAt` for both `silentForSeconds` and
`responseTimedOut`. Existing `createdAtEpochMs` values are copied exactly; a null legacy value stays
null and no historical timestamp is inferred.

## Changed paths

- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java`
- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskProjectionMapper.java`
- `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexTaskProjectionMapperTest.java`
- this work item

## Focused validation

- Direct mapper cases cover the current DTO field set, explicit nullable identity/provider/context,
  nullable creation epoch, deterministic 299/300-second timeout boundaries, future and non-running
  timestamps, the exact ten-field structured-error allowlist, error-map ordering and enum values,
  case-sensitive interaction-state mapping, and absence of entity mutation.
- Selected `CodexTaskServiceTest` cases retain provider/identity fallback, batch loading, paging,
  event publication, terminal admission failure and resync behavior.
- `mvn -q -pl addons/codex-worker-agent -am -Dtest=CodexTaskProjectionMapperTest
  -Dsurefire.failIfNoSpecifiedTests=false test`: 5 tests passed, 0 failures/errors/skips;
  Surefire elapsed 0.058 seconds (observed command wall approximately 26.2 seconds).
- `mvn -q -pl addons/codex-worker-agent -am
  -Dtest='CodexTaskServiceTest#getTaskExposesProviderTypeAndAuthoritativeCreatedAtEpoch+
  listTasksBatchLoadsProviderTypesWithoutPerTaskQueries+
  listTasksPaged_groupsCodexTasksBySessionAndSupportsInteractionStateFilter+
  codexBizProviderFiltersLookupListingAndSearchAwayFromPlainCodexTasks+
  getTaskById_recoversLogicalAgentIdFromUnifiedSessionStore+
  completeTask_publishesTaskStatusChangeEvent+
  failTask_publishesTaskStatusChangeEventWithError+
  sdkStreamFailureBeforeAcceptancePublishesDefinitiveTerminalEvent+
  lifecycleProviderEffectAdmissionFailureIsDefinitiveAndZeroDispatch+
  resyncFailedTaskPublishesNonTerminalRecoveryTransition'
  -Dsurefire.failIfNoSpecifiedTests=false test`: 10 selected tests passed, 0
  failures/errors/skips; Surefire elapsed 2.767 seconds (observed command wall approximately 11.2
  seconds).
- `git diff --check`: passed.
- No affected-module, full, E2E, live, package, service or data validation was run in this slice.

## Independent review

Independent read-only review returned `ACCEPT`: the mapper preserves the original field set,
ten-field error allowlist and case-sensitive interaction mapping; all resolved identity, provider,
context and diagnostic values remain caller-owned; timeout fields share one observation; the mapper
does not query or mutate. The four-path budget and focused coverage were also accepted. The reviewer
did not run additional tests and did not inspect the unrelated untracked `BOOT-INF/` tree.

## Residual boundary

No public DTO, status, reason code, identity, provider or persisted-data contract changed. This
slice does not move event/lifecycle/repository/provider/session behavior, `toSearchResult`, batch
projection, grouping, filtering, sorting or pagination. It does not modify or repair old data.
