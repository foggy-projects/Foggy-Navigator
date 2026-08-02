---
workitem: NAVI-CORE-001-S3-01A
status: IMPLEMENTED_PENDING_REVIEW
date: 2026-08-03
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
---

# NAVI-CORE-001 S3-01A Open API runtime launch planner

`OpenApiController.askAgent` now delegates its read-only launch planning to
`OpenApiRuntimeTaskLaunchPlanner`. The immutable plan owns resource/model/workspace resolution,
server-derived Worker selection, launch-only metadata sanitization, attachment and scope
normalization, and construction of a fresh `BusinessAgentWorkerTaskLaunchRequest`.

The Controller still owns HTTP/header authentication, route admission, Agent owner and context
ownership checks, request audit writes, task-token issue/bind/revoke, provider submission, session
binding, and response/error mapping. The planner has no `HttpServletRequest`, raw secret, token,
audit, session-write, provider-dispatch, query, readiness, or closure dependency. Caller metadata
cannot select the physical Worker, Worker pool ownership, directory, or resolved model.
The two-phase API preserves the original ordering around Controller ownership checks. Nullable
function entries are retained for the existing token-policy validator, and duplicate normalized
`allowed_dirs` keep their request order and shape.

## Changed paths

- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiRuntimeTaskLaunchPlanner.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiController.java`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiRuntimeTaskLaunchPlannerTest.java`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiControllerMessageMappingTest.java`
- this work item

## Focused validation

- `OpenApiRuntimeTaskLaunchPlannerTest`: 2 tests passed.
- Selected `OpenApiControllerMessageMappingTest` ask/attachment/model/runtime-option/owner/workspace/
  Worker-selection/backend cases: 12 tests passed.
- Combined final focused rerun after independent review fixes: 14 tests passed, 37.58 seconds wall time.
- `git diff --check`: passed.
- Affected-module lane: intentionally deferred to the S3-01B exit gate; no full-suite authorization
  was consumed by this slice.

## Residual boundary

This slice does not yet extract task-token and submission orchestration from `askAgent`; that is the
separate S3-01B work item. No public HTTP path, Form/DTO, RX/reason code, credential, ownership,
provider, data, or historical record was changed.
