---
workitem: NAVI-CORE-001-S4-03A0
status: IMPLEMENTED_REVIEWED
date: 2026-08-03
baseline: 3fde7562
prerequisite: NAVI-CORE-001-S4-02C2D@3fde7562
coordination_freeze: 029d73c
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: AGENT_CANCEL_FACADE_CONVERGENCE
---

# NAVI-CORE-001 S4-03A0 Agent cancel Facade convergence

This preparatory slice removes the only UI/A2A/Shared cancel ingress that directly called
`A2aAgent.cancelTask`. After the existing owner, tenant and path-Agent equality check,
`AgentDiscoveryController` passes the persisted Agent identity and the current UI resolve context
to `TaskDispatchFacade.cancelTask`. The Facade remains the single provider/A2A route selector and
continues to preserve exact provider identity, force semantics and existing termination behavior.

The public route, request body, authentication requirement, `RX<String>` response and success text
remain unchanged. This slice adds no client request header, command envelope, receipt, ledger,
provider adapter or terminal interpretation. It is a prerequisite only and does not claim that
S4-03A canonical termination is complete.

## Changed paths

- `session-module/src/main/java/com/foggy/navigator/session/controller/AgentDiscoveryController.java`
- `session-module/src/test/java/com/foggy/navigator/session/controller/AgentDiscoveryControllerTest.java`
- this work item

## Validation boundary

Four exact Controller cancel selectors prove owner/tenant and path-Agent rejection occurs before
the Facade and that the successful request carries the persisted Agent plus the existing UI
context. One bounded affected lane may add only the seven existing Facade cancel route selectors
frozen by the coordination gate. No whole test class, module, reactor, database, E2E, live Provider
or final joint full-validation cycle is included.

## Implementation evidence

- The four exact Controller selectors passed (`4/4`) with zero failure, error or skip; Maven total
  time was `31.287 s`, including fresh production and test compilation.
- The single bounded affected lane passed (`11/11`) with zero failure, error or skip in `19.515 s`:
  the four Controller selectors plus the seven frozen Facade routing selectors.
- Three independent read-only reviews accepted the exact three paths with no P1/P2 finding. They
  separately checked authority ordering and persisted-Agent use, public response and routing
  compatibility, and the exact path/test evidence boundary.
- No whole test class, module, reactor, database, E2E, live Provider or final joint full validation
  was run. The authorized final joint validation budget remains `0/3` consumed.

## Data and rollback boundary

Tests use mocks only. No service or Worker is started and no business/runtime or historical data is
read or mutated. No repair, backfill, data replay, reconciliation or deletion is authorized.
Rollback is one exact three-path commit revert and requires no data action.
