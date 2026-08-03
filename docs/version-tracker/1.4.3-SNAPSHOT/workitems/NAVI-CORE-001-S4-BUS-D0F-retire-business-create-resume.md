---
workitem: NAVI-CORE-001-S4-BUS-D0F
status: IMPLEMENTED_REVIEWED
date: 2026-08-04
baseline: 00f0df68
coordination_freeze: c8ba0e1
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: RETIRE_BUSINESS_CREATE_PSEUDO_RESUME
---

# NAVI-CORE-001 S4-BUS-D0F retire Business create pseudo-resume

Business create no longer interprets `resumeFromTaskId`. The published Form field remains for wire
compatibility, but every present value fails before resource reads or effects. No new RESUME feature
is inferred from this unused legacy field.

## Exact paths

1. `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskService.java`
2. `business-agent-module/src/test/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskServiceTest.java`
3. This work item.

## Behavior and compatibility

- The HTTP production path already uses D0D, which rejects any present resume value. The remaining
  public legacy service fresh-create seam now applies the same pre-read fence and is marked for
  eventual removal.
- The boolean escape hatch, old Task lookup and model/directory inheritance branch are removed.
  Ordinary fresh model/workspace resolution and mutation ordering are unchanged.
- `BusinessAgentTaskCreateInput` still snapshots the field so an effect-time input change cannot
  smuggle resume semantics past canonical preflight.
- Server and SDK Form/DTO/wire, D0A-D0E, TMS and SIM are unchanged. Caller census found no active
  production consumer setting the field.

## Focused validation

- Final affected production compile: PASS (`15.469s`); the earlier compile also passed (`18.117s`).
- Four exact selectors: PASS (`4/4`, failures/errors/skips all zero, `25.732s`), covering
  canonical/direct-service pre-read rejection plus ordinary and launcher fresh creation.
- Independent final read-only reviews: PASS (`3/3 ACCEPT`, no P1/P2). Reviews confirmed exact
  three-path scope, pre-read rejection across every resolver, complete legacy branch removal,
  unchanged fresh ordering and closure of the Business resume disposition.
- No module/reactor, SDK/TMS/SIM, E2E, live runtime or final joint full validation is run. Final
  joint budget remains `0/3 consumed`.
- Tests use mocks only. No service/Worker or historical/existing data is read or mutated.

## Stop conditions

Stop and replan if an active resume caller is found; real provider continuation is required; a
fourth path, Form/DTO/SDK/POM/TMS/SIM change, command/receipt/token/audit addition or fresh-order
change is needed; historical data or `BOOT-INF/` is touched.
