---
workitem: NAVI-CORE-001-S4-02B1
status: IMPLEMENTED_REVIEWED
date: 2026-08-03
baseline: 2cd00d48
prerequisite: NAVI-CORE-001-S4-02B0@2cd00d48
coordination_freeze: 63c3f7b
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: OWNER_PROVEN_CONTENT_FREE_CREATE_EXECUTION_PLAN
---

# NAVI-CORE-001 S4-02B1 owner-proven create target plan

This slice resolves one immutable, content-free `CreateExecutionPlan` before a task-create
Provider effect. The plan records the authenticated owner/tenant, real logical Agent when one
exists, exact provider, proven physical Worker when selected, model selection, Session,
WorkingDirectory, independent `A2A|DIRECT` route and exact A2A lookup. A null component means the
completed resolution found canonical absence; missing authority, lookup failure and conflicting
selection constraints fail closed.

## Authority and route behavior

- Session identity is accepted only through `SessionTaskResourceAccessService.requireOwnedSession`.
- WorkingDirectory and CodingAgent rows are user/tenant scoped and must have `enabled == TRUE`. Request fields
  remain selection constraints and cannot establish ownership.
- A bare local Worker is accepted only after
  `WorkerManagementFacade.validateWorkerAccess(userId, tenantId, workerId)` succeeds. No physical
  owner is inferred from that access proof. The same exact access proof is required once for every
  non-null Worker selected from an owned Session, Directory or local CodingAgent; external A2A's
  canonical null Worker skips it.
- A selected model config must exist, be enabled and identify a known Worker backend. Worker/model
  access uses the existing two-argument validation when model is canonically absent and the
  model-specific overload otherwise. Credential-bearing model config also requires exact canonical
  tenant equality plus non-null `ownerType` and non-blank `ownerId`; canonical null tenant is valid
  only when both plan and config tenant are null. Cross-tenant and legacy ownerless rows fail closed
  without mutation.
- Direct routing requires an exact registered command provider. A Directory pseudo-Agent is
  reduced to its owned Directory and never appears as a logical Agent. A local logical Agent may
  not enter Direct or A2A execution without an exact accessible physical Worker.
- Local A2A requires an owner-qualified Agent, exact provider and physical Worker. External A2A
  requires an owner-qualified registration plus an exact provider card and may canonically omit
  Worker, Session and model. `LOCAL_LANGGRAPH_WORKER` uses its durable CodingAgent `workerId` just
  like the other local providers; a legacy null binding remains denied rather than repaired.
- A real Agent's `defaultDirectoryId` is a fallback only after explicit, synthetic and Session
  directory selection. The resulting Directory is owner-qualified before its ID/Worker/model
  selection enters the plan, so inner A2A code never needs an unproved directory fallback.

The guarded package-private plan-resolution seam first performs the Facade's existing owned
Session check, context-binding check and continuation normalization. It rejects a resume
continuation instead of converting guarded CREATE into resume. Its paired execution seam requires
the request and trusted context to still match the plan before applying the canonical target.
Authenticated user, tenant and A2A request source are exact, and the latter is frozen in the Agent
lookup so UI/OPEN_API ownership semantics cannot change between resolution and effect. The plan is
a final class with a resolver-only private constructor; guarded CREATE also rejects resume drift
and non-blank `contextAlias` until their canonicalization has a separately frozen contract.

The B0 prerequisite supplies exact provider-scoped Agent resolution. Guarded A2A calls it once
with the plan provider and lookup ID; it never uses global first-match resolution or a second
provider scan. A missing/blank/mismatched actual card ID fails before `sendTask`. Before either
Direct or A2A effect, plan application copies metadata and removes target/authority keys including
Agent/provider/Session/context/Worker/Directory/model/user/tenant IDs, then canonical non-null plan
fields are emitted by the existing parameter mapper; unrelated business metadata remains intact.

Existing public `createTask` and `submitTask` signatures and their legacy target/effect path remain
behavior-compatible. The strict seams are deliberately not connected to every public ingress in
B1; they are available for the later S4-02A once-effect coordinator. Resolver helpers used by
legacy create and resume routing also remain behavior-compatible.

## Changed paths

- `session-module/src/main/java/com/foggy/navigator/session/service/TaskCreateTargetResolver.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/TaskCreateTargetResolverTest.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java`
- this work item

No repository/SPI API, addon, Router, POM, HTTP ingress, historical row or runtime process is
changed.

## Validation record

After the accepted B0 prerequisite, the authoritative final production/test build carried both
frozen B1 selectors in one Maven invocation; Maven compiled the affected main and test sources,
so no separate compile was run:

`/usr/bin/time -p mvn -q -pl session-module -am
-Dtest='TaskCreateTargetResolverTest,TaskDispatchFacadeTest#createTask_reusesSessionBoundAgentWhenAgentIdOmitted+createTask_usesDirectProviderRouteWhenModelConfigTargetsCodex+createTask_usesExplicitCodexBizProviderFromDirectoryDefaultCodexModelConfig+createTaskRejectsAnExistingSessionOwnedByAnotherUserBeforeRouting+createTask_rejectsModelConfigThatTargetsDifferentProviderThanResolvedAgent+createTask_rejectsExplicitProviderTypeThatConflictsWithResolvedAgent+createTaskConsumesTheSameResolvedPlanBeforeA2aEffect+createTaskConsumesTheSameResolvedPlanBeforeDirectEffect+createTaskRejectsPlanDriftWithZeroProviderEffect+resolveCreateExecutionPlanRejectsResumeContinuationBeforeTargetOrEffect'
-Dsurefire.failIfNoSpecifiedTests=false test`

It exited `0` in `31.64 s` (`user 39.07 s`, `sys 5.86 s`). Surefire ran `19/19` tests with failures,
errors and skips all zero: resolver selector `9` (`0.041 s`) and exact Facade selector `10`
(`2.16 s`). Evidence covers owner-qualified Session/Directory/Agent/default Directory, canonical
tenant including both-null personal config, complete model ownership, uniform Worker access,
LangBiz durable Worker binding, local/external A2A, Direct canonical absence, immutable/private
plan construction, real context-bound resume normalization then guarded denial, resume/alias/source/
owner/target drift, metadata injection sanitization for Direct and A2A, exact-provider single Agent
resolution, mandatory exact card ID, same-plan consumption and zero Provider effect on rejection.

The final evidence review found that the execution guard for post-plan `resume=true` drift was
implemented but not asserted explicitly. That single assertion was added to the existing drift
method, and only that impacted method was rerun:

`/usr/bin/time -p mvn -q -pl session-module -am
-Dtest='TaskDispatchFacadeTest#createTaskRejectsPlanDriftWithZeroProviderEffect'
-Dsurefire.failIfNoSpecifiedTests=false test`

It exited `0` in `27.29 s` (`user 29.28 s`, `sys 5.00 s`); Surefire ran `1/1` with zero failures,
errors or skips. The full 19-test selector was not blindly repeated because production code and
the other 18 methods were unchanged.

An independent final read-only architecture review accepted the five-path diff and confirmed that
all previously reported target, provider, metadata, Worker/model authority, plan-mint, resume/alias
and public-compatibility findings were closed. It ran no additional tests and introduced no new
acceptance conditions.

Earlier pre-B0 focused/compile attempts were review evidence only and are superseded by this final
B0-based `19/19` run. One early assertion-only failure led to a more precise model/Agent conflict
diagnostic; no final compilation or behavioral failure remained.

No affected lane, full module/reactor, E2E, live/provider or final joint validation was run for
this B1 slice.

## Data and rollback boundary

No business/runtime data is read or mutated by implementation or unit validation. Rolling back
the five source/document paths requires no data repair, backfill, replay or reconciliation.
