---
workitem: NAVI-CORE-001-S4-02D2B1
status: IMPLEMENTED_REVIEWED
date: 2026-08-04
baseline: b87b9c02
prerequisites: NAVI-CORE-001-S4-02D2B0@b87b9c02, NAVI-CORE-001-S4-02D2A3@3ba1d298
coordination_freeze: 22de183
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: FORWARD_SERVICE_CANONICAL_COMPOSITION
---

# NAVI-CORE-001 S4-02D2B1 forward service composition

This slice composes NEW_SESSION forwarding through the already-reviewed D0 reservation, D1 trusted
create factory, D2A2 outcome store, D2A3 preauthorization and D2B0 transaction boundary. The
EXISTING_SESSION branch retains its published behavior while running through its preserved
transaction contract.

## Exact scope

- Select the existing-target or new-target transaction boundary before reading business facts.
- Resolve a sessionless, owner-bound canonical target with the same create resolver used by the D1
  command pipeline; freeze all effect-bearing facts in one immutable forward plan without mutating
  the caller DTO.
- For a local logical A2A Agent, accept only the resolver-issued, owner/tenant/Agent-exact pending
  synthetic context/session claim produced from that sessionless input, then discard its provisional
  identity before deriving the D0 Session. Durable, foreign or canonical-context Session bindings
  remain forbidden at preplan.
- Mint the request scope and deterministic target Session identity, then preauthorize before D0 can
  reserve the target.
- Submit the same server-built request through the trusted D1 factory and canonical pipeline.
- At the fresh-participant boundary, require exact equality for every effect-bearing field, UUID,
  resolve-context field and canonical directory metadata before Provider execution.
- Persist a fresh D2A2 outcome only after the Provider returns a verified Task; replay reads the
  exact existing outcome and creates no second Provider or relation effect.
- Build the public response only from the accepted Task and durable outcome snapshot.

## Authority and transaction invariants

- The service does not query an Agent/provider registry or invent a fallback. The same canonical
  create resolver performs the sessionless preflight and the D1 participant recheck against the D0
  Session; this is preflight/recheck of one authority, not a second authority.
- Preauthorization completes before D0. A denied credential therefore has zero Session, Task,
  Provider, receipt or outcome effect.
- D0 reservation disposition never decides whether the Task command is fresh or replay. D1 receipt
  state owns that decision, and D2A2 follows the D1 participant callback.
- NEW_SESSION has no service outer transaction. D0, D1 and D2A2 retain their reviewed independent
  boundaries. EXISTING_SESSION retains `READ_COMMITTED` and repaired-state exception semantics.
- No legacy NEW_SESSION `createSession`, mutable Session update or direct relation save remains.
- The post-resolver directory projection is re-read only for path/milestone and is fenced by exact
  directory, owner, tenant, enabled and Worker facts before scope mint or D0 reservation.

## Deliberate non-scope

- No Controller/header, DTO, entity, repository, schema, migration, Provider addon, facade,
  resolver, pipeline-stage, factory or POM change.
- The public four-argument service overload accepts a server-supplied request UUID for the next
  controller slice; the existing three-argument API remains compatible and server-mints when null.
- No compensation, auto-retry, cleanup, repair, backfill, replay effect, reconciliation or
  historical/existing-data mutation.

## Validation budget

1. Compile affected production sources with tests skipped.
2. Run only `SessionForwardServiceTest` and `SessionForwardNewSessionPlanTest`.
3. Run one bounded affected seam lane covering the transaction boundary, D0 reservation, D2A2
   outcome store and D1 factory fresh/replay/preauthorization selectors.
4. Obtain three independent read-only P1/P2 reviews of the exact five-path diff.

Do not run a whole module/reactor, E2E/live Provider lane, runtime or final joint full cycle in this
slice. Final joint budget remains `0/3 consumed`.

## Implementation evidence

- Affected production compile: PASS (`14.079s`).
- Final two-class focused lane after review hardening: PASS (`25/25`, `32.047s`, including production
  recompilation). It covers both transaction branches,
  immutable target projection, fresh/replay separation, exact prepared-request matching,
  directory-bound and directory-free metadata, deterministic target identity and denied
  preauthorization with zero reservation/Provider/outcome effect. It also proves that a canonical
  local-A2A pending synthetic preplan is accepted and that tenant drift in the directory projection
  fails before scope mint/D0.
- Existing real resolver/normalizer focused selector
  `TaskDispatchFacadeTest#resolvePendingContextDefersMutationAndKeepsPlanBindingAcrossClaim`: PASS
  (`1/1`, `18.315s`), proving the accepted pending claim is read-only before the effect permit and
  binds owner/tenant/Agent plus its synthetic Session identity.
- Bounded affected seam lane: PASS (`36/36`, `21.253s`): D1 trusted factory `18/18`,
  transaction boundary `2/2`, D2A2 outcome store `8/8`, and D0 target reservation `8/8`.
  Disposable H2 contexts were created and destroyed by the tests.
- Three independent final read-only reviews accepted the corrected exact staged diff with no
  remaining P1/P2 across authorization/tenant safety, service/fresh-replay authority, and
  transaction/pipeline effect ordering.
- Initial independent reviews found and closed two pre-D0 issues inside the existing service/test
  paths: local A2A pending synthetic preplans were incorrectly rejected as durable Session bindings,
  and the directory path/milestone re-read lacked a tenant drift fence. Final rerun and re-review are
  complete.
- Validation used mocks, immutable in-memory projections and test-owned disposable databases only;
  it did not start a service or Worker and did not read or mutate business, runtime, historical or
  existing data.
- Whole module/reactor, E2E/live Provider, runtime and final joint full validation were not run;
  final joint budget remains `0/3 consumed`.

## Stop conditions

Stop and replan if composition needs a sixth path, a second target authority, caller DTO mutation,
reservation before authentication, D0-based Task fresh/replay choice, a legacy NEW_SESSION write,
an outer new-target transaction, compensation/retry, Provider bypass, historical/existing-data
mutation or a broader validation lane.
