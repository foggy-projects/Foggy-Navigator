---
workitem: NAVI-CORE-001-S4-02D1
status: IMPLEMENTED_REVIEWED
date: 2026-08-03
baseline: cb91c7b8
prerequisite: NAVI-CORE-001-S4-02B4@cb91c7b8
coordination_freeze: 6787a9f
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: DORMANT_UI_FORWARD_SCOPED_CREATE
---

# NAVI-CORE-001 S4-02D1 forward scoped create command

This dormant seam lets the later `NEW_SESSION` forward adapter enter the existing canonical Task
CREATE factory without adding another adapter, receipt or coordinator. A private-issued,
non-inheritable and non-nestable scope binds one exact submit request, a canonical client request
UUID and one server-computed semantic SHA-256 fingerprint.

No Controller or forward service is wired in this slice. Unscoped `UI_FORWARD` therefore retains
its current behavior until D2. While a scope is active, the factory always claims the request and
fails closed on route, source, identity, credential or request-object drift.

## Changed paths

- `session-module/src/main/java/com/foggy/navigator/session/service/TrustedNavigatorTaskCreateCommandFactory.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/TrustedNavigatorTaskCreateCommandFactoryTest.java`
- this work item

## Canonical binding and participant boundary

The scoped envelope uses `CREATE / DIRECT / NAVIGATOR_UI_FORWARD`, the forward route, the scope UUID
as client/correlation reference and `UI_FORWARD_SHA256:<digest>` as idempotency reference. Existing
JWT and Navigator API-key identity proofs remain distinct. The semantic digest is accepted only
from package-local server wiring; D2 owns its complete ForwardPlan projection.

Fresh participants run exactly once after the coordinator permit and after Provider return but
before result recording. Recorded replay runs no participant and hydrates the exact Task through
the existing owner-aware read. Missing, duplicate, reordered or failed callbacks poison the scope.

## Validation and data boundary

Focused tests cover both credential lanes, binding references, semantic drift, fresh/replay,
participant ordering, scope nesting/reuse/request replacement, route/source authority, skipped
stage and cleanup. Validation remains limited to exact selectors and at most one factory test-class
lane. No database, runtime, Provider, E2E, whole-module/reactor or final joint cycle is included.
No business or historical data is read or mutated, and rollback is one exact three-path revert.

## Implementation evidence

- Affected production compile: PASS (`16.590s`).
- Frozen exact selectors: `6/6`, zero failures/errors/skips (`26.949s`).
- One bounded factory test-class lane: `16/16`, zero failures/errors/skips (`19.420s`).
- Three independent read-only reviews accepted with no P1/P2 findings.
- No Controller/forward service/runtime/Provider/database/E2E, whole-module/reactor or final joint
  full-cycle validation was run. Final joint budget remains `0/3`.
