---
workitem: NAVI-CORE-001-S4-BUS-D0C
status: IMPLEMENTED_REVIEWED
date: 2026-08-04
baseline: a55d4a23
coordination_freeze: 5f548a7
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: BUSINESS_CREATE_ONCE_EFFECT_COORDINATOR
---

# NAVI-CORE-001 S4-BUS-D0C once-effect coordinator

This slice adds the Business-only once-effect coordinator between the immutable D0B plan and the
single D0A receipt authority. It has no production caller yet; D0D will own nontransactional
composition, authorization minting and replay hydration.

## Exact paths

1. `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskCreateCommandCoordinator.java`
2. `business-agent-module/src/test/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskCreateCommandCoordinatorTest.java`
3. This work item.

## Canonical boundary

- The coordinator is a Business-specific Spring service with no transaction annotation. It injects
  only `CanonicalCommandReceiptPort` and the Spring-managed `BusinessAgentTaskService` proxy.
- A package-visible `PlanBinding` maps plan-proven ownership, logical Agent, selected
  Worker/Provider when a launcher exists, model, Session and a versioned fingerprint-only effect
  scope to the canonical envelope.
- The coordinator validates only plan-proven command kind, ownership, target and effect. Ingress,
  canonical request identity, principal/credential lane and actor fingerprint remain D0D authority
  composition responsibilities.
- Upstream ownership uses a domain-separated digest over tenant, ClientApp, upstream system and
  upstream user. Request content, client context, token, lease, credential and Provider result
  content never enter the envelope, receipt, reference or safe code.

## Once-effect order

```text
plan/envelope exact binding
→ receipt prepare
→ receipt beginEffect
→ only PERMITTED + EFFECT_STARTED + nonblank attempt
→ executeFreshCreatePlan through the injected Spring proxy
→ fresh transaction commits and proxy returns
→ exact fresh-result validation
→ receipt recordResult
→ exact RESULT_RECORDED snapshot validation
```

- A recorded prepare or begin race returns only `BUSINESS_TASK:<taskId>` as a typed replay
  reference. D0C does not hydrate a DTO or issue a token.
- Started/already-started/ambiguous states fail closed without a second fresh effect.
- After a valid permit, a fresh, result-validation or record failure best-effort marks the same
  attempt `BUSINESS_TASK_CREATE_OUTCOME_UNKNOWN`; mark failure is suppressed on the original
  exception. There is no retry, compensation, redispatch or token operation.
- Receipt recording happens only after the D0B2 proxy has returned. A returned receipt snapshot
  must match request ID, attempt ID, opaque reference, state and safe code before success is
  returned.

## Result and secret fence

- Fresh result validation covers safe Task identity, tenant/effective actor, ClientApp/upstream
  user, Session, Agent, Skill, internal Worker route, directory, model/requested facts, CREATED
  status, context and launcher-specific selected Worker/Provider/Worker Task facts.
- The one-time task token must exist for a fresh result, but stays only in the in-memory `Executed`
  result. `Executed.toString()` redacts the DTO and token.
- Recorded replay contains only the Task reference. D0D must later perform owner-aware read-only
  hydration and return `taskScopedToken=null`.

## Focused validation

- Affected production compile: PASS.
- New focused coordinator test: PASS (`11/11`, failures/errors/skips all zero), covering plan binding
  and pre-receipt drift, exact record-after-fresh-return order, prepare/begin recorded races,
  started/ambiguous no-replay, same-ID single fresh effect, fresh failure ambiguity, all predictable
  result-field drifts, record/mark failure semantics, invalid attempts/references, no-launcher
  behavior, exact existing model-variant and Provider Worker-ID normalization semantics, and
  token/content redaction.
- D0A receipt and D0B proxy evidence were reused because this slice changes neither boundary.
- Three independent final read-only reviews: ACCEPT with no remaining P1/P2. Review found two
  post-commit false-ambiguity risks in local normalization; the final code now exactly mirrors the
  existing service's Spring Unicode-whitespace model-variant handling and trimmed Worker identity
  comparison, with both cases exercised by the focused success path.
- No whole class beyond the new focused class, whole module/reactor, E2E, live Provider/runtime or
  final joint full validation was run. Final joint budget remains `0/3 consumed`.
- Tests used mocks and in-memory values only. No service or Worker was started, and no historical or
  existing business/runtime data was read or mutated.

## Deliberate non-scope and stop conditions

No D0D facade/authority/factory/hydrate, Controller, SDK, plan/input/service, receipt adapter,
schema, POM or addon is changed. Stop and replan if a fourth path is required; Business must depend
on session; a second ledger/authority or generic coordinator appears; the coordinator starts a
transaction or self-invokes fresh; receipt records before fresh commit; started/ambiguous retries;
D0C hydrates/reissues a token; public/schema/HTTP behavior changes; historical data or `BOOT-INF/`
is touched.
