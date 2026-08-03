---
workitem: NAVI-CORE-001-S4-03A-L1
status: IMPLEMENTED_REVIEWED
date: 2026-08-04
baseline: a443d905
coordination_freeze: 04e3803
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: LANGGRAPH_TERMINATION_FAIL_CLOSED
---

# NAVI-CORE-001 S4-03A-L1 LangGraph termination fail-closed

LangGraph Biz Worker has no remote cancellation effect or receipt. The Java provider therefore no
longer advertises `CANCEL_TASK`. Owner-qualified cancellation of every non-terminal or unknown
Task state fails closed with the stable `TERMINATION_REQUEST_NOT_SUPPORTED` code and performs no
local terminal, projection, event, or Worker-interruption mutation. Canonical terminal Tasks
remain idempotent no-ops. The SPI force overload delegates to the same owner-first state contract.

The existing recoverable interruption path is retained exclusively for observed stream errors and
timeouts. It records interruption context while preserving the Task's active lifecycle status; it
is not evidence of a user-requested remote cancellation.

## Changed paths

- `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphTaskService.java`
- `addons/langgraph-biz-worker/src/test/java/com/foggy/navigator/langgraph/worker/service/LanggraphTaskServiceTest.java`
- this work item

## Validation evidence

- Final affected LangGraph addon production compile passed in `18.875 s`.
- Seven exact selectors passed `7/7` with zero failure, error, or skip in `27.989 s`: capability
  truth; non-terminal/unknown rejection including the force overload; terminal force no-op;
  owner-first force rejection; recoverable stream-interruption recording; and the two existing
  Relay stream-error/read-timeout cases.
- No whole test class, module/reactor suite, Worker, database, E2E, live Provider, or final joint
  full-validation cycle was run for this slice.
- Three independent final read-only reviews of ownership/state truth, adapter/force compatibility,
  and test/failure evidence each returned `ACCEPT` after the two P1 corrections.

## Compatibility and residual risk

The public adapter and SPI method shapes remain unchanged. Existing callers receive a stable
unsupported failure for non-terminal LangGraph termination instead of a fabricated local
`ABORTED` or accepted outcome; force cannot bypass owner lookup or terminal idempotency. The future
canonical A2 ingress must reject this unsupported route before receipt effect permission; that
cross-module wiring is outside this provider-local slice.

The first three-way read-only review found two P1 truth gaps: the SPI force default bypassed the
owner/state contract, and unknown/null status returned normally and could be reported as accepted.
Both were corrected inside the same service/test/work-item path cap before the final evidence run.

No service or Worker was started. No business/runtime or historical data was read or mutated, and
no repair, backfill, replay, reconciliation, cleanup, or deletion was performed. SIM, TMS, and the
user-owned `BOOT-INF/` directory were not changed.
