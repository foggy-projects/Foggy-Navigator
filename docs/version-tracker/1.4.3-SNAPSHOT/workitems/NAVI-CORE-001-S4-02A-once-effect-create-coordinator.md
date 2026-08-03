---
workitem: NAVI-CORE-001-S4-02A
status: IMPLEMENTED_REVIEWED
date: 2026-08-03
baseline: 9f72b631
prerequisite: NAVI-CORE-001-S4-02B1@9f72b631
coordination_freeze: cc52cb0
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: OWNER_PROVEN_ONCE_EFFECT_TASK_CREATE_COORDINATOR
---

# NAVI-CORE-001 S4-02A once-effect task create coordinator

This slice adds one narrow `TaskCreateCommandCoordinator` around the B1 owner-proven create plan.
It does not connect a new HTTP ingress and does not change the public two-argument Facade create or
submit path. The package-private strict seam now requires a coordinator-minted, single-use Provider
effect gate; the former three-argument strict seam no longer exists.

## Exact plan binding

`PlanBinding` is private-mint and colocated with the coordinator. It is the single mapping that the
later B2 server command factory must reuse. Before receipt prepare it requires an exact CREATE,
canonical owner/tenant reference, Target and Effect:

- present tenant uses `navi.tenant.present.v1:<canonicalTenantId>` and canonical absence uses
  `navi.tenant.absent.v1`; the real nullable tenant, not this display/reference tag, enters the
  digest;
- a real logical Agent uses `LOGICAL_AGENT/<agentId>`; Direct without a real Agent uses
  `RUNTIME/<opaque-create-scope>`, which denotes only a pre-effect execution scope and does not
  assert that a runtime or Task already exists;
- Target explicitly carries provider, Worker, model-config and Navigator Session identities and a
  null Task identity;
- Effect action is `task.create`; its domain-separated `LP_UTF8_SHA256_V1` scope covers nullable
  tenant, owner, logical Agent, provider, Worker, model-config, model, Session, Directory and the
  independent A2A/Direct execution route in fixed order with null/present tags and big-endian byte
  lengths.

`CommandIngress` remains the trusted entry source and is not derived from the execution route.
Actor, client surface/route, request/correlation/idempotency, client-app/upstream and authorization
mint remain B2 responsibilities.

## Effect and failure order

The A2A path completes exact Provider Agent/card, model, binding and message preflight, then reserves
the lifecycle foreground lane. The Direct path reserves the lane, then completes Router
provider/model validation, exact command-provider lookup, parameter construction and runtime
affinity marking. At each real callback point the code reconstructs identity from trusted context,
the canonical request and the actual Provider/card, rechecks the same private plan, then enters the
single-use gate.

Only a receipt `PERMITTED` result invokes the Provider supplier. A durable recorded result is
returned only as `TASK:<taskId>` without synthesizing `DispatchTaskDTO`; started or ambiguous state
fails closed. Pre-permit failure releases an acquired lifecycle reservation and leaves the receipt
prepared. Once a permit is durable, Provider/persistence/context/confirm/result-recording failure is
marked ambiguous with the exact attempt and is never automatically redispatched; the lifecycle
reservation is deliberately retained because a Provider Task may already exist.

Successful output requires a nonblank Task ID. Any nonblank Provider, Agent, Worker, model,
model-config, Session or Directory identity that conflicts with the plan makes the permitted outcome
ambiguous. Result data never fills a canonical-null plan identity, and receipt storage contains only
the opaque Task reference and fixed safe code.

## Changed paths

- `session-module/src/main/java/com/foggy/navigator/session/service/TaskCreateCommandCoordinator.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TaskOperationRouter.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/TaskCreateCommandCoordinatorTest.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java`
- this work item

No SPI, addon, Controller, POM, auto-configuration, schema, migration, runtime process or historical
data is changed.

## Validation record

Production compilation passed before test changes:

`/usr/bin/time -p mvn -q -pl session-module -am -Dmaven.test.skip=true compile`

It exited `0` in `16.33 s` (`user 21.15 s`, `sys 3.44 s`).

The initial exact selector ran the seven then-present coordinator methods plus three guarded
Facade/Router methods. The three Facade methods and three coordinator negative/binding methods
passed; four coordinator methods stopped only because the test helper created an `EffectPermit`
mock inside an unfinished outer Mockito stubbing. The command exited nonzero in `27.83 s` with
`10` run, `0` assertion failures and `4` fixture errors. Moving permit construction before the
outer stubbing changed test code only; the exact four impacted methods then passed `4/4` in
`26.38 s`.

The three B1 strict-seam compatibility methods were then selected. Same-plan A2A and Direct passed;
the drift method reached its intended pre-gate rejection but Mockito strictness rejected its unused
test-only pass-through stub. Marking only that helper stub lenient changed no production behavior;
the one impacted drift method passed in `27.23 s`. The first compatibility command took `18.22 s`
and reported `3` run, `0` assertion failures and `1` fixture error.

Three evidence gaps were added without expanding paths: a begin-effect race method covers durable
`RESULT_RECORDED`, `ALREADY_STARTED` and `AMBIGUOUS` with zero Provider supplier calls (`1/1`,
`26.20 s`), and an invalid-result method covers blank Task ID and Provider identity conflict with
exact ambiguity and no synthetic result (`1/1`, `26.62 s`). A real Facade/Router method then proved
that permitted Provider failure in both A2A and Direct retains the reservation and neither releases
nor confirms it (`1/1`, `27.28 s`).

Independent architecture review found four P1 issues before commit: actual and result identities
were trimmed instead of compared raw; Direct actual identity omitted a valid real logical Agent;
and null digest fields omitted their four-byte zero length. The limited correction preserved raw
identity, used the canonical request Agent in Direct, fixed null encoding and added a deterministic
scope golden vector plus padded owner/card/result and Direct-real-Agent assertions. The exact five
impacted methods passed `5/5` in `31.56 s`. Two independent limited re-reviews returned `ACCEPT` and
confirmed both the four architecture findings and the reservation evidence gap closed with no new
finding.

Across the focused evidence, all `16` distinct selected methods ultimately passed; already-green
methods were not blindly rerun after test-fixture-only corrections or unrelated limited changes.

No affected, full-module/reactor, E2E, live/provider or final joint validation has run.

## Data and rollback boundary

Implementation and unit validation do not read or mutate business/runtime data. Rollback is one
source/document commit revert and needs no repair, backfill, replay or reconciliation.
