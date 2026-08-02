---
workitem: NAVI-CORE-001-S3-03B
status: IMPLEMENTED_REVIEWED
date: 2026-08-03
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
baseline: ba1c7f6d73476dfab4572066d6b18f3a3c5a358a
---

# NAVI-CORE-001 S3-03B Codex task query service

`CodexTaskService` now keeps its existing public lookup, list, active-list, page, directory and
search signatures as thin delegates to the read-only `CodexTaskQueryService`. Command, mutation,
stream, client, runtime-affinity, token, lifecycle, event, termination and reconciliation behavior,
including `hasRunningTask*`, remains in `CodexTaskService`.

The query service owns session grouping/filtering/paging/search and immutable provider, logical
Agent and context facts. Provider resolution keeps the existing priority
`entity -> SessionTask -> Session -> codex-worker`; logical Agent resolution remains
`resolvedAgent -> SessionTask -> Session -> null`; context resolution remains
`entity -> SessionTask taskStateJson -> null`. Provider filtering and DTO projection reuse one
resolved batch. The entity provider is never normalized by mutation, saved or backfilled.

Bulk query projection captures one `observedAt` per returned DTO batch. SessionTask and Session
fallbacks are loaded once per batch rather than once per Task. Structured diagnostics are queried
only for unique `FAILED` Task IDs that are actually projected, at most once per unique ID because
`ErrorDiagnosticService` has no batch API.

## Changed paths

- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskQueryService.java`
- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java`
- `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexTaskQueryServiceTest.java`
- `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexTaskServiceTest.java`
- this work item

## Focused validation

- Direct query tests cover the class-level read-only boundary and dependency allowlist, owned
  lookup/not-found behavior, optional fallback absence, provider fallback and partition priority,
  one SessionTask/Session batch with no per-Task lookup, unchanged entities and no repository
  writes, shared batch observation, `task:<id>` grouping, flat session ordering, interaction-state
  filtering, latest-task worker/directory search, any-task keyword matching, cost/time map shape,
  and one diagnostic lookup per unique failed Task.
- `CodexTaskServiceTest` adds a characterization that exercises every retained public query
  signature against a mocked query delegate and proves the Service does not query the repository
  directly. Existing provider/identity/batch/page/search/canary cases remain selected compatibility
  evidence.
- `mvn -q -pl addons/codex-worker-agent -am -DskipTests compile`: passed; observed wall 19.03
  seconds.
- `mvn -q -pl addons/codex-worker-agent -am
  -Dtest='CodexTaskQueryServiceTest,CodexTaskServiceTest#publicQuerySurfaceDelegatesWithoutUsingTaskRepositoryDirectly'
  -Dsurefire.failIfNoSpecifiedTests=false test`: 6 tests passed, 0 failures/errors/skips; observed
  wall 31.81 seconds.
- Selected compatibility command covering `CodexTaskQueryServiceTest`, seven
  `CodexTaskServiceTest` query cases, CodexBiz listing, AppServer provider scope, AppServer canary,
  SDK facade status and the outer Codex A2A cases: 24 tests passed, 0 failures/errors/skips;
  observed wall 32.37 seconds. Its first attempt exposed only a test-fixture NPE when a broad Mockito
  batch Answer received the null value produced while a later `any()` stub was being declared; a
  null guard was added to the fixture and the identical command then passed.
- Static boundary scans found no save/delete, entity provider setter, client, relay, runtime,
  lifecycle, termination or reconciliation dependency in `CodexTaskQueryService`; `git diff
  --check` passed.
- No affected-module lane, full, E2E, live, package, service/Worker start-stop or data validation was
  run in this slice.

## Independent review

Independent read-only review returned `ACCEPT` with no MAJOR or MINOR. It confirmed the exact
five-path boundary; complete public query delegation; retention of command, stream, runtime,
lifecycle, event and `hasRunningTask*` ownership in `CodexTaskService`; read-only dependency and
zero-mutation boundaries; provider/Agent/context priority; batch reuse and a single `observedAt`;
unique FAILED diagnostic lookup; and page, grouping and search compatibility. The reviewer reused
the compile and `24/24` focused evidence and did not rerun tests or inspect `BOOT-INF/`.

## Affected validation

- `/usr/bin/time -p mvn -q -pl addons/codex-worker-agent -am
  -Dtest='*Test,!*E2ETest,!*IntegrationTest,!*Live*'
  -Dsurefire.failIfNoSpecifiedTests=false test`: PASS `2303/2303`, failures/errors/skipped `0`,
  wall `94.84 s` (`user 191.33 s`, `sys 25.32 s`). Surefire XML aggregation covered `253`
  suites: `navigator-common 128`, `navigator-spi 9`, `agent-framework 215`,
  `user-auth-module 173`, `session-module 493`, `business-agent-module 745`, and
  `codex-worker-agent 540` tests.
- WARN/ERROR and repair-oriented log lines came from expected negative or disposable test
  fixtures; the run did not connect to or mutate existing business/runtime data.
- This was the one post-review affected Codex lane for S3-03B. It was not a final joint full
  validation and consumed none of the user-authorized `0/3` final cycles.

## Compatibility and residual boundary

No public method, bean identity, DTO, status, reason code, provider identity, session grouping,
search map or persisted-data contract changed. The SDK default remains conservative; missing
logical Agent/context values remain null. Existing or historical rows were read only: no repair,
backfill, reconcile, normalization flush or synthetic fact was performed.

This slice is implemented but not independently reviewed or committed. The unrelated untracked
`BOOT-INF/` tree was not inspected or changed.
