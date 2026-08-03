---
workitem: NAVI-CORE-001-S4-02D2A0
status: IMPLEMENTED_REVIEWED
date: 2026-08-03
baseline: 0f2f8d95
prerequisite: NAVI-CORE-001-S4-02D1@0f2f8d95
coordination_freeze: 7016750
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: READ_ONLY_FORWARD_SOURCE_PROJECTION
---

# NAVI-CORE-001 S4-02D2A0 forward source read projection

This slice removes the implicit historical Session-message repair from forward source resolution.
An explicit assistant message still projects its real ID, role and content. A fallback source Task
must be owner-proven, belong to the source Session, be `COMPLETED` and contain a nonblank result; it
projects that result with the existing deterministic `forward-task-result:<session>:<task>` UUID.

The Task-derived reference is stable regardless of matching Session-message rows and is valid as a
relation provenance reference even when no message row exists. No message is constructed, saved,
backfilled or selected as a later replacement for that reference.

## Changed paths

- `session-module/src/main/java/com/foggy/navigator/session/service/SessionForwardService.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/SessionForwardServiceTest.java`
- this work item

## Compatibility and validation boundary

Explicit message IDs, request/response shape and the existing NEW/EXISTING dispatch behavior remain
unchanged. Only sourceTask fallback semantics change from state-dependent materialization/reuse to a
read-only deterministic projection. Focused verification covers canonical projection, independence
from matching messages, invalid/unowned Task facts and the explicit-message guard; at most one
`SessionForwardServiceTest` class lane may follow.

No real database, Session, Task, message, relation, runtime or Provider is accessed. No repair,
backfill, replay, reconcile, delete or historical mutation is authorized. This slice does not wire
D0/D1 or claim canonical NEW_SESSION create completion.

## Implementation evidence

- Frozen exact selectors: `5/5`, zero failures/errors/skips (`25.785s`).
- One bounded `SessionForwardServiceTest` lane: `12/12`, zero failures/errors/skips
  (`12.240s`).
- Three independent read-only reviews accepted with no P1/P2 findings.
- No real database, runtime, Provider, module/reactor, E2E or final joint full-cycle validation
  was run. Final joint budget remains `0/3`.
