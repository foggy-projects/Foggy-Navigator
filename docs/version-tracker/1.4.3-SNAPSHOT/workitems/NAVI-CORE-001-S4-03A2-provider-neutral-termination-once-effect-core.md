---
workitem: NAVI-CORE-001-S4-03A2
status: IMPLEMENTED_REVIEWED
date: 2026-08-04
baseline: 0a599f87
coordination_freeze: a073b64
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: PROVIDER_NEUTRAL_TERMINATION_ONCE_EFFECT_CORE
---

# NAVI-CORE-001 S4-03A2 Provider-neutral termination once-effect core

This dormant core resolves one immutable, owner-proven Task termination plan and captures its
existing Provider or A2A route before any durable command receipt is prepared. The coordinator
uses Navigator's single neutral command receipt only as an attempt/effect guard. It does not own or
replace any Provider termination operation, lifecycle intent/outbox/fence, Worker acknowledgement,
terminal observation, or canonical terminal commit.

The plan binds Task, Session, provider task, owner, tenant, logical Agent, Provider, physical
Worker, directory, model/model-config, runtime affinity, route, and force mode. Mutable status and
timestamps are excluded. Before the permit and again at the effect point, the Router reads the
owner-qualified Task and rejects stable identity drift. After permission it invokes only the
captured route and cannot fall back or resolve another Provider.

Review tightened the first implementation in three places: terminal no-op resolution now precedes
all live A2A force/Agent availability checks; a coordinator-minted, single-use effect gate is the
only path from the Facade/Router to a captured Provider/A2A callback; and recorded replay must name
the exact Task bound by the current plan. The plan exposes no Provider or Agent capability.

## Changed paths

- `session-module/src/main/java/com/foggy/navigator/session/service/TaskOperationRouter.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TaskTerminationCommandCoordinator.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/TaskTerminationCommandCoordinatorTest.java`
- this work item

## Validation evidence

- The final affected production compile passed in `15.696 s`.
- The coordinator focused class, five canonical Facade selectors, and seven existing cancellation
  route selectors passed `20/20` with zero failure, error, or skip in `27.484 s`.
- Three independent read-only reviews of owner/context stability, Facade/gate structure, and receipt
  race/replay semantics each returned `ACCEPT` after the findings above were corrected.
- No whole test class beyond the new focused class, whole module/reactor test suite, database, E2E,
  live Provider/runtime, or final joint full-validation cycle was run.

## Compatibility and data boundary

No Controller calls this coordinator in this slice; all existing public cancel routes and response
semantics remain unchanged until later trusted-ingress wiring. Gemini and LangGraph termination
truthfulness remain explicit blockers for S4-03A acceptance and are not hidden by this receipt.

No service or Worker was started. No business/runtime or historical data was read or mutated, and
no repair, backfill, replay, reconciliation, cleanup, or deletion was performed. Provider addons,
OpenAPI, Shared, Business, schema/SPI/POM, SIM, TMS, and the user-owned `BOOT-INF/` directory were
not changed.
