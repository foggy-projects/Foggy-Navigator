---
workitem: NAVI-CORE-001-S4-02B1A0
status: IMPLEMENTED_REVIEWED
date: 2026-08-03
baseline: 822d0255
prerequisite: NAVI-CORE-001-S4-02A@822d0255
coordination_freeze: 7839fe2
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: CODEX_A2A_RUNTIME_AFFINITY_MARKER_ADAPTER
---

# NAVI-CORE-001 S4-02B1A0 Codex A2A runtime-affinity marker adapter

This provider-only capability maps the existing JVM-local
`InternalTaskDispatchMarkers` runtime-affinity token from A2A message metadata to the internal
`CodexTaskCreateCommand.initializeRuntimeAffinity` flag. It does not mint the token, alter the
public request contract, or change Codex runtime selection and pristine-session rules.

The marker is accepted by object identity and only for the exact Codex App Server provider. A JSON
boolean/string under the same metadata key does not enable initialization, and even a trusted token
cannot enable the SDK provider. Before the TaskService call, the adapter replaces the local message
with a metadata copy that removes the real identity token, so it cannot enter returned A2A history.
The later S4-02B1A sealed context path owns the only approved mint point and also removes its
temporary token as defense in depth.

## Changed paths

- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/adapter/CodexWorkerInnerA2aAgent.java`
- `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/adapter/CodexWorkerA2aAgentTest.java`
- this work item

No SPI, TaskService, Session/schema/data, Controller, POM, runtime process or other Provider is
changed.

## Validation record

The first exact three-method command passed `3/3` in `45.06 s`, but independent review correctly
found that it exercised the SDK provider and retained the identity token in returned history. That
evidence was therefore not accepted as closure. The implementation was limited within the same
three paths to exact App Server admission plus a token-free local history copy, and the three exact
impacted methods then passed `3/3` in `35.44 s` (exit `0`):

- trusted JVM-local identity token maps to App Server internal initialization `true` and is absent
  from returned A2A history;
- boolean and string values under the same key both remain `false`;
- a trusted token on SDK and an ordinary token-free App Server request both remain `false`.

Existing focused `CodexTaskServiceTest` evidence already characterizes successful trusted
initialization of a pristine preallocated App Server Session and fail-closed ordinary
missing-affinity behavior. Those methods were not blindly rerun because B1A0 does not modify the
TaskService or pristine rules and the adapter selectors directly close the only new seam.

The limited independent re-review returned `ACCEPT`: exact App Server plus the JVM identity token is
the only true case, SDK remains false even with the real token, and the sanitized local message is
used for returned history before the TaskService call. No P1/P2 remained.

No affected, full-module/reactor, E2E, live/provider or final joint validation has run.

## Data and rollback boundary

The implementation and tests do not read or mutate business/runtime data. Rollback is one source
and test commit revert and needs no repair, backfill, replay or reconciliation.
