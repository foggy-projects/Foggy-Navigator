---
workitem: NAVI-CORE-001-S4-02B1A
status: IMPLEMENTED_REVIEWED
date: 2026-08-03
baseline: 5045c92d
prerequisite: NAVI-CORE-001-S4-02B1A0@5045c92d
coordination_freeze: 607d6f9
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: STRICT_LOCAL_A2A_CANONICAL_CONTEXT_NORMALIZATION
---

# NAVI-CORE-001 S4-02B1A canonical context normalization

This slice closes the second context-resolution window on the guarded local-A2A CREATE seam.
Every production local-A2A request is inspected before the single target resolver, then receives a
server-minted, process-local proof of one exact owner-qualified Agent, Navigator Session, physical
Worker and provider target. Direct, external A2A and the public legacy create path retain their
existing behavior.

The normalizer reads existing rows without repairing them. An existing Session must already have
an exact owner, tenant, Agent, provider, Worker/approved pristine App Server absence, Directory,
model binding and ACTIVE status. Missing or malformed old facts fail before Provider effect. A new
request may insert a new context and, only when the request supplied no Session, its new Session in
one `REQUIRES_NEW` transaction. Commit-time unique conflicts roll back the whole claim before a new
transaction reads the winner; a fresh winner forces an explicit retry, so the losing request cannot
produce a second first Provider effect.

The sealed decorator branch does not query or write `AgentContextStore` and does not use the legacy
prompt dedup hooks. It removes the proof before the inner Provider call, exact-fences canonical
message metadata, and temporarily mints the already-reviewed JVM-local App Server affinity marker
only for a pristine proof. The marker map is cleaned in `finally`; the Codex inner adapter also
sanitizes returned history as defense in depth. Successful persisted Provider tasks complete only
`agentSessionRef` through an exact context-and-final-Session CAS fenced by the returned task ID.
Synthetic `FAILED` tasks do not attempt that CAS.

## Changed paths

- `session-module/src/main/java/com/foggy/navigator/session/service/TaskCreateContextNormalizer.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TaskCreateTargetResolver.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`
- `session-module/src/main/java/com/foggy/navigator/session/agent/ContextResolvingA2aAgent.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java`
- this work item

No repository interface, entity/schema, SPI, addon, Controller, POM, Coordinator, TaskService,
public DTO or runtime process is changed.

## Validation record

- Affected production compile: `mvn -pl session-module -am -DskipTests compile`, exit `0`,
  `BUILD SUCCESS` in `16.12 s` after strict decorator wiring.
- Affected test compile: `mvn -pl session-module -am -DskipTests test-compile`, exit `0`,
  `BUILD SUCCESS` in `25.96 s`.
- Initial exact 11-selector run found two local issues: guarded resume no longer preserved the
  existing request projection before rejection, and one rejection test had an over-eager Mockito
  stub. The other nine selectors passed. The correction retained strict non-resume zero-lookup
  behavior and the two exact impacted selectors then passed `2/2` in `31.12 s`.
- The final exact focused command reran the complete bounded selector set after those corrections:
  `11/11`, failures `0`, errors `0`, exit `0`, `BUILD SUCCESS` in `18.92 s`.
- A final owner/Agent/tenant/Session mismatch selector was added to make the fail-closed scope
  explicit and passed `1/1`, failures `0`, errors `0`, exit `0`, in `26.61 s`; the accepted focused
  evidence at that point covered `12/12` exact selectors without running the full test class.
- The final App Server review then found one P1 before commit: field-level pristine checks did not
  prove the provider task store and unified Session task store were both empty, so an inconsistent
  existing Session could consume the once-effect permit before the provider rejected affinity
  initialization. The correction injects the existing read-only `TaskLookupProvider` list and
  `SessionTaskRepository` into the normalizer. Because the provider's raw pristine guard rejects
  every Codex task row under the Session, the final correction requires exactly one registered
  lookup view for each canonical SDK, App Server and Biz Codex provider and requires all three
  provider-scoped views plus the unified task store to be empty. This also covers legacy/null rows
  through the Codex projection fallback and explicit rows through their canonical view. Missing,
  duplicate, null or non-empty evidence fails before the permit and never repairs existing rows.
- The new cross-Codex/raw-store-equivalent boundary selector plus the existing marker selector
  passed `2/2`, then
  the complete corrected exact selector set passed `13/13`, failures `0`, errors `0`, skipped `0`,
  exit `0`, `BUILD SUCCESS` in `18.89 s`. This corrected run supersedes the earlier 12-selector
  evidence without expanding to the full test class.

The focused selectors cover alias winner/miss, pre-plan zero mutation and atomic final claim,
fresh-winner retry, incomplete/malformed old Session rejection, proof-only zero-store dispatch,
App Server dual-task-store pristine proof and affinity marker cleanup, exact returned-task CAS,
synthetic failure handling, existing same-plan execution, plan drift, and guarded resume
compatibility.

An independent static review found and closed two P1 issues before focused validation: a deferred
path would have used legacy `getOrBind` to repair a missing old provider, and malformed nonblank
provider state could have been mistaken for pristine. The refreshed review reported no remaining
P1; its one message-metadata TOCTOU P2 was then closed by exact target metadata fencing. A final
post-evidence review subsequently found and triggered the dual-task-store P1 correction above. A
final delta review checked the corrected three-view projection against the raw Codex query fallback,
the unified task fence and the five negative/positive task-store cases, and returned
`ACCEPT / NO REMAINING P1/P2`.

No affected lane, full module/reactor, E2E, live/provider or final joint validation has run.

## Data and rollback boundary

No service or Worker was started and no business/runtime data was read or mutated. Tests use mocks
and current-request in-memory disposable candidates only. No repair, backfill, replay,
reconciliation or deletion of historical data occurred. Rollback is one source-and-test commit
revert and requires no data action.
