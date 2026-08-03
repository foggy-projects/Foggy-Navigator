---
workitem: NAVI-CORE-001-S3-04A
status: IMPLEMENTED_REVIEWED
date: 2026-08-03
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
baseline: 27a998d0cacb35234e71ed3ebc851ba9afc76a0d
---

# NAVI-CORE-001 S3-04A Codex SDK task command provider

The exact `codex-worker` command port is now the dedicated `CodexSdkTaskCommandProvider`.
It implements only `TaskCommandProvider`, depends only on the concrete `CodexTaskService`, and
delegates the eight supported commands to the existing provider-scoped Service methods with
`CodexTaskService.CODEX_PROVIDER_TYPE` fixed as the route.

`CodexTaskService` no longer implements `TaskCommandProvider`. It remains the exact
`codex-worker` lookup/listing bean, retains every existing public direct/A2A/termination wrapper
and its prior transaction boundary, and advertises only the three listing/search capabilities.
The existing AppServer and CodexBiz provider beans and identities were not modified.

The new provider does not copy, normalize or add entries to command maps; maps containing nulls
are passed by the same object reference. Return objects and exceptions are not wrapped. It does
not pre-query a Task, add a transaction, catch failures or depend on a registry, router,
repository, runtime, client or lifecycle service. The inherited force-cancel and checkpoint-scan
operations remain unsupported.

## Changed paths

- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexSdkTaskCommandProvider.java`
- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java`
- `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexSdkTaskCommandProviderTest.java`
- `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexTaskServiceTest.java`
- this work item

## Focused validation

- Direct provider tests cover exact SDK identity, command-only port shape, the single concrete
  Service constructor dependency, the exact eight capabilities, absence of a new transaction,
  all eight fixed-provider delegations, same-reference maps with null entries, unwrapped return
  objects and exceptions, and inherited unsupported force-cancel/checkpoint behavior with zero
  Service interaction.
- Service characterization proves it is no longer a command port, keeps lookup/listing identity,
  advertises exactly the listing/page/search capability set, and retains all eight public command
  wrappers with their original transactional or non-transactional boundaries.
- Selected session-module evidence covers lookup/command separated-port resolution plus SDK versus
  AppServer direct-route conflicts and no cross-provider fallback. Selected Codex facade evidence
  keeps SDK provider forcing and status scope.
- `/usr/bin/time -p mvn -q -pl addons/codex-worker-agent -am -DskipTests compile`: passed; wall
  `19.59 s` (`user 22.64 s`, `sys 4.23 s`).
- `/usr/bin/time -p mvn -q -pl addons/codex-worker-agent -am
  -Dtest='CodexSdkTaskCommandProviderTest,
  CodexTaskServiceTest#exposesOnlyLookupAndListingTaskProviderPorts+
  publicCommandWrappersRetainExistingTransactionBoundaries+
  sdkProviderParamsCannotOverrideRouteToAppServer+sdkProviderCommandRejectsAppServerTask,
  TaskQueryProviderRegistryTest#findCommandProviderForTask_supportsSeparatedLookupAndCommandPorts,
  TaskDispatchFacadeTest#createTask_usesDirectProviderRouteWhenModelConfigTargetsCodex+
  createTask_rejectsExplicitProviderTypeThatConflictsWithResolvedAgent+
  createTask_sdkSessionRejectsDirectAppServerRouteBeforeProviderInvocation+
  createTask_appServerSessionRejectsDirectSdkRouteBeforeProviderInvocation+
  cancelTask_mappedAppServerProviderMissing_doesNotFallbackToSdkProvider,
  CodexWorkerFacadeImplTest#createTaskForcesSdkProvider+statusUsesSdkProviderScope'
  -Dsurefire.failIfNoSpecifiedTests=false test`: passed `16/16`, failures/errors/skips `0`; wall
  `35.89 s` (`user 41.63 s`, `sys 7.15 s`). There was no first-run production or focused failure.
- Static scans confirmed that `CodexTaskService` has no command-port interface/import/capability,
  the new provider contains no `@Primary`, `@Order`, transaction, parameter rewrite, pre-query or
  catch path, and no code references a command provider through the `codexTaskService` bean name.
  `git diff --check` passed.
- No affected-module lane, full, E2E, live, package, Spring context, service/Worker start-stop or
  data validation was run in this slice.

## Independent review

Independent read-only review returned `ACCEPT` with no MAJOR or MINOR. It confirmed that the
Service changed only its command-port type/capabilities and invalidated overrides while preserving
all public wrapper bodies and transaction boundaries; the new bean owns exactly the eight fixed
SDK delegations with same-reference arguments and unwrapped results/failures; and no ordering,
transaction, pre-query, rewrite, cross-provider or default-operation expansion was introduced.
The reviewer reused the compile and `16/16` focused evidence and did not rerun tests or inspect
`BOOT-INF/`.

## Compatibility and residual boundary

There is one exact command bean for each existing Codex provider identity: the new SDK command
provider for `codex-worker`, and the unchanged AppServer and CodexBiz providers for their own
identities. Session command routing already resolves separated lookup and command ports by the
same provider identity; no registry, router, SPI, controller, launcher or POM changed.

No public DTO, status, reason, credential, provider-effect, A2A, termination or persisted-data
contract changed. No historical or existing business/runtime data was read or mutated. This slice
was independently reviewed, committed as `c5fb261b`, and pushed. The unrelated untracked
`BOOT-INF/` tree was not inspected or changed.
