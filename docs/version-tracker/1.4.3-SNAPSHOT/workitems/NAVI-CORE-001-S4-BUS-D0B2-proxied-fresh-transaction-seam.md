---
workitem: NAVI-CORE-001-S4-BUS-D0B2
status: IMPLEMENTED_REVIEWED
date: 2026-08-04
baseline: bc2f5c94
coordination_freeze: 25cf11a
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: BUSINESS_CREATE_PROXIED_FRESH_TRANSACTION
---

# NAVI-CORE-001 S4-BUS-D0B2 proxied fresh transaction seam

This slice creates the commit-before-return transaction seam needed by the future Business command
coordinator. It does not connect the receipt port or any HTTP/SDK ingress.

## Exact scope

- Add one package-local immutable input snapshot and prepared command. The snapshot retains every
  current Form field, including null/blank/list order and opaque client context, while redacting all
  request content from its representation.
- Snapshot before planning and rebuild a local Form for each resolution, so mutation of the caller's
  Form after preparation cannot change the admitted effect.
- Add a Spring-proxied `REQUIRES_NEW` fresh executor to `BusinessAgentTaskService` using a
  package-local command type.
- At effect time, resolve the current owner/resource/Worker plan again and exact-revalidate it before
  assigning a Task ID or invoking any Task/token/Session/Provider mutation.
- Extract and share the existing mutation tail without changing legacy create's single resolution,
  transaction or effect order.

## Exact paths

1. `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskCreateInput.java`
2. `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskService.java`
3. `business-agent-module/src/test/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskFreshTransactionTest.java`
4. This work item.

## Deliberate non-scope

- No receipt/coordinator/facade, Controller, SDK, Form/DTO, entity/repository/schema/POM or Provider
  addon change.
- No generic transaction callback/boundary, retry, compensation, replay hydration or new state.
- Provider remains inside the fresh outer transaction.
- No historical/existing Task or receipt repair, backfill, replay, reconciliation or mutation.

## Canonical transaction order

The future nontransactional facade must use:

```text
prepare immutable plan/input
→ receipt permit
→ executeFreshCreatePlan through the Spring proxy
  → effect-time exact plan revalidation
  → Task ID/save
  → lease/token issue
  → rollback revoke hook
  → Provider
  → Business Session bind
  → final Task save
  → token/Worker bind
  → fresh transaction commit
→ proxy returns
→ receipt recordResult
```

Legacy `createTask` continues to resolve once and invoke the same private mutation tail within its
existing transaction. It never self-invokes the fresh executor.

## Validation evidence

- The first affected compile stopped before tests on one stale local `tenantId` reference exposed by
  mutation-tail extraction; it was corrected to the frozen Task tenant. Final affected production
  compile: PASS.
- New real Spring/H2 transaction test: PASS (`9/9`, failures/errors/skips all zero), covering input
  deep copy/redaction, actual AOP `REQUIRES_NEW`, inner commit visibility before return, outer
  suspend/resume and rollback independence, fresh rollback plus token revoke, Worker drift,
  client-content/model/context/workspace drift and null-command fail-fast. Review remediation
  replaced the former Mockito-only token state with a Spring-proxied test-owned token lifecycle:
  token issue, bind and rollback revoke each write a disposable H2 probe in an actual independent
  `REQUIRES_NEW` transaction, and independent connections prove durable ACTIVE/REVOKED state,
  exact rollback reason/token identity and resources distinct from the fresh Task transaction.
- Existing legacy characteristic selectors: PASS (`10/10`, failures/errors/skips all zero),
  covering no-launcher create, exact Worker launch/order/binding, rollback revoke, launch failure,
  Worker-result drift, direct/stale physical Worker, context mismatch and legacy resume.
- D0B1 plan compatibility selectors after snapshot delegation: PASS (`2/2`,
  failures/errors/skips all zero), covering zero-effect selected Worker planning and all non-null
  resume values.
- Three independent final read-only reviews: ACCEPT with no remaining P1/P2. The review that found
  the former mocked token-state gap explicitly rechecked and accepted the Spring-proxied
  transaction probe remediation.
- Tests used mocks plus a Spring-proxied test lifecycle and test-owned disposable H2 probe tables
  only. No service, Worker or runtime was started; no historical/existing business or runtime data
  was read or mutated.
- Whole class/module/reactor, E2E/live Provider/runtime and final joint full validation were not run;
  final joint budget remains `0/3 consumed`.

## Stop conditions

Stop and replan if this requires a fifth path; fresh execution is not an actual proxied
`REQUIRES_NEW`; legacy create self-invokes fresh or resolves twice; exact revalidation follows any
Task ID/mutation/effect; input loses caller fields or logs content; receipt enters the fresh
transaction or records before commit; Provider moves outside the transaction; a retry,
prepare/completion split, compensation, ledger or schema is added; `BOOT-INF/` is touched; or
historical/existing data is read or mutated.
