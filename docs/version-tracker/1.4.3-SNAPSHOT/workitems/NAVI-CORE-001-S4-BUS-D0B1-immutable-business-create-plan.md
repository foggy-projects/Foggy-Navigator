---
workitem: NAVI-CORE-001-S4-BUS-D0B1
status: IMPLEMENTED_REVIEWED
date: 2026-08-04
baseline: 7940ff9b
coordination_freeze: 8fe7f12
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: BUSINESS_CREATE_READ_ONLY_PLAN
---

# NAVI-CORE-001 S4-BUS-D0B1 Business create read-only plan

This slice cleanly separates Business Task create resource resolution and exact Worker selection
from the existing mutation tail. It establishes an immutable, content-free plan for a future canonical
receipt facade without connecting that facade in this slice.

## Exact scope

- Add one package-local immutable `BusinessAgentTaskCreatePlan` that freezes identity, owner,
  Agent/Skill route, model, workspace, context, selected Worker and semantic input digests.
- Resolve the exact Worker once before assigning a Business Task ID or performing any Task, token,
  lease, Session or Provider mutation.
- Make canonical fresh-plan resolution reject `resumeFromTaskId` before any repository or resource
  access. Preserve the existing legacy create/resume behavior through the shared internal resolver.
- Preserve the existing mutation order after resolution: Task save, token/lease issue, rollback
  revocation hook, Provider launch, Session bind, final Task save and token/Worker binding.
- Canonicalize policy maps before hashing so insertion order cannot create false plan drift.
- Use a stable backend launch-mode marker rather than a proxy/runtime class name, avoiding false
  drift across restarts while retaining the selected launch lane.
- Reject malformed UTF-16 before canonical UTF-8 encoding so distinct request values cannot
  collapse through replacement-character encoding.

## Exact paths

1. `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskCreatePlan.java`
2. `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskService.java`
3. `business-agent-module/src/test/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskServiceTest.java`
4. This work item.

## Deliberate non-scope

- No receipt coordinator/facade, Controller, HTTP contract, SDK, DTO/Form, schema, repository,
  entity, addon, launcher SPI or POM change.
- No fresh transaction executor or replay hydration; those remain separate reviewed slices.
- No Provider relocation or mutation-order change.
- No historical/existing Task or receipt repair, backfill, replay, reconciliation or mutation.

## Validation budget

1. Compile the affected production graph with tests skipped.
2. Run only the six new plan selectors.
3. Reuse ten existing characteristic selectors for no-launcher create, Worker launch/binding,
   rollback revoke, launch failure, Worker drift, direct/stale physical Worker, context mismatch and
   legacy resume behavior.
4. Obtain three independent read-only P1/P2 reviews of the exact staged four-path diff.

Do not run a whole test class/module/reactor, E2E/live Provider/runtime or final joint full cycle.
Final joint budget remains `0/3 consumed`.

## Implementation evidence

- Affected production compile: PASS.
- New plan focused selectors: PASS (`6/6`, failures/errors/skips all zero), including a
  Worker-resolution failure proof with zero mutation/effect and direct/stale physical-Worker plan
  freezing.
- Stable launcher marker follow-up selector: PASS (`1/1`, failures/errors/skips all zero).
- Existing create characteristic selectors: PASS (`10/10`, failures/errors/skips all zero).
- The selection request contains no Task ID, selected Worker, lease or task token. Plan resolution
  invokes no Task/token mutation, Session bind or Provider launch.
- The plan defensively copies lists, distinguishes null/blank and list order, uses a pinned semantic
  fingerprint, rejects malformed surrogate input and redacts content/policies from `toString`.
- Identity/context, route/selected Worker, model, workspace and input drift classes each change the
  fingerprint; exact revalidation accepts an equivalent plan and rejects a changed plan.
- Canonical CREATE rejects every supplied `resumeFromTaskId`, including blank/whitespace, before
  reads or effects. The legacy create/resume branch retains its previous nonblank semantics.
- Tests used mocks only. No service, Worker or runtime was started; no historical/existing business
  or runtime data was read or mutated.
- Whole class/module/reactor, E2E/live Provider/runtime and final joint full validation were not
  run; final joint budget remains `0/3 consumed`.
- Three independent final read-only reviews accepted the exact four-path diff with no open P1/P2
  across plan/data boundaries, fingerprint/validation evidence and transaction/effect ordering.

## Stop conditions

Stop and replan if this requires a fifth path; connects receipt, HTTP or SDK surfaces; retains a
Form/entity/credential/token/lease/provider result in the public plan; assigns a Task ID or performs
a mutation before exact Worker resolution; calls Provider during planning; changes the legacy
mutation order or resume behavior; touches `BOOT-INF/`; or reads/mutates historical data.
