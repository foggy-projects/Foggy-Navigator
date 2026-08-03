---
workitem: NAVI-CORE-001-S4-02D2A3
status: IMPLEMENTED_REVIEWED
date: 2026-08-04
baseline: 990b62e3
prerequisite: NAVI-CORE-001-S4-02D2A2@990b62e3
coordination_freeze: 975d2fb
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: DORMANT_FORWARD_PRE_RESERVATION_AUTHORITY
---

# NAVI-CORE-001 S4-02D2A3 forward pre-reservation authority

This dormant seam proves the existing Navigator HTTP credential, route and authenticated owner
facts before D2B is allowed to reserve its deterministic target Session. It does not issue a
command decision or receipt and does not create a Session, Task, relation, audit record or Provider
effect.

## Exact scope

- Add package-local `preauthorizeForwardScope(scope, expectedRequest)` to the existing trusted
  Navigator create factory.
- Accept only a factory-issued forward scope whose client request UUID is already carried by the
  same server-built submit request object.
- Reuse the exact existing forward checks for POST route, `UI_FORWARD` source, JWT versus Navigator
  API-key lane, foreign/mixed credential rejection, `UserContext`, all four AuthInterceptor request
  attributes, and resolve-context owner/tenant.
- Record an approved preauthorization exactly once. Any failed or repeated attempt before the
  execution claim poisons that scope; the approved identity check and single execution claim are
  atomic. A late duplicate after that claim is rejected as already used without altering the
  in-flight command, and can never create a second execution.
- Keep the existing factory-stage proof in place so any credential or request drift after
  preauthorization still fails before receipt or Provider effect.

## Deliberate non-scope

- No Controller, forward service, D0 reservation, coordinator, receipt, entity, repository, schema,
  migration or public DTO change.
- No raw credential, token, header, derived credential fingerprint, prompt or business payload is
  copied into the scope, logs, content or durable facts.
- Preauthorization is not a canonical command authorization decision and cannot permit Provider
  execution by itself.
- No fallback, compensation, cleanup, repair, backfill, replay effect or historical-data mutation.

## Validation budget

1. Compile affected production sources with tests skipped.
2. Run only the exact forward preauthorization/fresh/replay/scope selectors in
   `TrustedNavigatorTaskCreateCommandFactoryTest`.
3. Run at most one bounded factory test-class lane after focused selectors are green.
4. Obtain three independent read-only P1/P2 reviews of the exact three-path diff.

Do not run a whole module/reactor, database, E2E/live Provider lane, runtime, or final joint full
cycle in this slice. Final joint budget remains `0/3 consumed`.

## Implementation evidence

- Affected production compile: PASS (`16.454s`).
- Initial exact six forward preauthorization/fresh/replay/scope selectors: PASS (`6/6`,
  `26.069s`). The bounded factory affected class then passed (`17/17`, `18.071s`).
- A final nested-preauthorization hardening poisons the active outer scope before rejecting the
  nested attempt. The same complete six-selector set passed after that delta (`6/6`, `31.877s`),
  including production recompilation.
- Final review hardening also makes an execute-without-preauthorization attempt permanently poison
  that scope. Its exact scope-state selector passed (`1/1`, `35.345s`) with production and test
  recompilation; no wider rerun was needed because no other lane consumes the dormant scope.
- Two independent reviews then found candidate-scope reuse after nested rejection and a split
  preauthorization-check/execution-claim race. Candidate rejection now poisons any not-yet-claimed
  scope, while the approved identity check and execution claim are one synchronized transition;
  late duplicates can neither poison the already-claimed command nor execute again. The three
  directly impacted selectors passed (`3/3`, `40.144s`) with production/test recompilation.
- The final real two-thread selector proved that a duplicate arriving after the first thread has
  claimed execution receives `FORWARD_TASK_CREATE_SCOPE_ALREADY_USED` without poisoning the
  in-flight command or causing a second coordinator execution. It and the three impacted scope
  selectors passed together (`4/4`, `31.442s`).
- Three independent read-only reviews of the final exact staged diff accepted the authentication
  context, facade/concurrency linearization and canonical-pipeline authority boundaries with no
  P1/P2 findings.
- Validation used only mock MVC/auth contexts and in-memory objects; it did not start a service or
  Worker and did not read or mutate business, runtime, or historical data.
- Whole module/reactor, database, E2E/live Provider, runtime and final joint full validation were
  not run; final joint budget remains `0/3 consumed`.

## Stop conditions

Stop and replan if the seam needs a fourth path, duplicates AuthInterceptor logic in a Controller or
service, creates any durable/effect fact, weakens an existing credential lane, permits caller-minted
scope state, exposes a secret, accesses business/runtime data, or mutates historical/existing data.
