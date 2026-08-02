---
workitem: NAVI-CORE-001-S3-01B
status: IMPLEMENTED_REVIEWED
date: 2026-08-03
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
---

# NAVI-CORE-001 S3-01B Open API runtime task create facade

`OpenApiController.askAgent` now delegates its single create mutation to
`OpenApiRuntimeTaskCreateFacade` after HTTP authentication, route/body/message validation,
resource planning and task-admission audit have completed. The facade accepts only server-verified
credential identifiers, route/context values, the S3-01A immutable launch plan and an audit handle;
it has no `HttpServletRequest`, header, raw application secret or public Form/DTO dependency.

The facade owns existing-context ownership validation, Agent owner/resolve, task-token preparation
and fail-closed compensation, exactly one `AgentSubmitPipeline` submission, token-to-Worker task
binding, dispatch audit, client-context persistence and business-session binding. It returns a typed
outcome so the Controller remains the owner of stable RX/error sanitization and the existing
`OpenApiTaskDTO` mapping. Existing closure, termination, readiness, reconciliation and durable-read
services remain unchanged and are not routed through this facade.

## Changed paths

- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiRuntimeTaskCreateFacade.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiController.java`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiRuntimeTaskCreateFacadeTest.java`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiControllerMessageMappingTest.java`
- this work item

## Focused validation

- Direct facade focused: `OpenApiRuntimeTaskCreateFacadeTest` — PASS `4/4`, `36.61s`.
- Full controller mapping: `OpenApiControllerMessageMappingTest` — PASS `67/67`, `25.13s`.
- Typed closure characterization: `RuntimeTaskTypedContractServiceTest` — PASS `27/27` in the
  initial combined run. That combined run first exposed one shared failure cause: `Map.copyOf`
  rejected planner metadata with a legal nullable value, causing 17 mapping errors while the other
  81 tests passed. The outcome copy now uses an unmodifiable `LinkedHashMap`; only the affected
  facade and mapping tests were rerun, and all `98/98` focused cases are valid green.
- Independent static review: `ACCEPT`; public HTTP/Form/DTO/RX, header/credential precedence,
  ownership/provider behavior, validation/admission ordering, token compensation, single submit,
  dispatch audit and Session binding remain equivalent.
- Sole affected command:
  `/usr/bin/time -p mvn -q -pl addons/claude-worker-agent -am
  -Dtest='*Test,!*E2ETest,!*IntegrationTest,!*Live*'
  -Dsurefire.failIfNoSpecifiedTests=false test` — PASS `2286/2286`, failures/errors/skipped `0`,
  wall clock `97.59s` (common 128, SPI 9, framework 215, auth 173, session 493, business 745,
  Claude addon 523).
- `git diff --check`: PASS. No full, E2E, live, provider or final joint cycle was run.

## Residual boundary

This slice does not change public paths, Form/DTO/status/reason codes, credential precedence,
ownership, provider selection, task/session lifecycle authority, historical data or runtime data.
Provider-observing reads and durable query projections remain separate later Stage 3 work items.
