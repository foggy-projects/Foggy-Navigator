---
doc_role: acceptance-record
doc_purpose: Record feature-level acceptance for Stage 4B Worker Gateway invoke skeleton.
acceptance_scope: feature
version: 1.1.3-SNAPSHOT
target: Stage 4B Worker Gateway invoke skeleton + approval placeholder
status: signed-off
decision: accepted
signed_off_by: Codex reviewer
signed_off_at: 2026-05-03
reviewed_by: Codex reviewer
blocking_items: []
follow_up_required: no
evidence_count: 4
---

# Feature Acceptance

## Background

Stage 4B extends the Stage 4A Worker Gateway list/schema closure with an internal invoke endpoint. The intended scope is deliberately limited: validate the task-scoped token and full business-function authorization chain, then return a placeholder result without executing adapters or fsscript.

## Acceptance Basis

- [08-implementation-plan.md](../08-implementation-plan.md)
- [WorkerGatewayService.java](../../../../business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/WorkerGatewayService.java)
- [WorkerGatewayController.java](../../../../business-agent-module/src/main/java/com/foggy/navigator/business/agent/controller/WorkerGatewayController.java)
- [WorkerGatewayServiceTest.java](../../../../business-agent-module/src/test/java/com/foggy/navigator/business/agent/service/WorkerGatewayServiceTest.java)

## Checklist

- [x] `POST /internal/worker-gateway/v1/business-functions/{functionId}/invoke` is implemented without `TENANT_ADMIN` control-plane auth.
- [x] Invoke requires `X-Task-Scoped-Token` and performs full token integrity checks.
- [x] Invoke delegates authorization to `BusinessFunctionAuthorizationService.resolveExecutableBusinessFunction(...)`.
- [x] `approvalRequired=true` returns `SUSPENDED` with a `suspendId` placeholder.
- [x] `approvalRequired=false` returns `ADAPTER_NOT_IMPLEMENTED`.
- [x] No real adapter or fsscript execution is performed in Stage 4B.
- [x] Gateway invoke response does not expose `adapterConfigJson` or `manifestJson`.
- [x] Unit tests cover invalid token, incomplete token, unauthorized function, approval placeholder, non-approval placeholder, and DTO leakage guard.

## Evidence

- Code review: `WorkerGatewayService.invokeBusinessFunction(...)` validates form/version/input, resolves and validates task-scoped token, calls the shared authorization service, then returns only placeholder statuses.
- Security review: `WorkerGatewayController.invokeBusinessFunction(...)` has no `@RequireAuth`; `BizWorkerControlPlaneAuthorizationTest` explicitly asserts the Gateway controller and methods do not require `TENANT_ADMIN`.
- Test evidence: `mvn test -pl business-agent-module -am` passed on 2026-05-03 with 104 tests, 0 failures, 0 errors.
- Build evidence: `mvn compile -pl launcher -am -DskipTests` passed on 2026-05-03.

## Failed Items

None.

## Risks / Open Items

- Stage 4B intentionally does not persist suspension records or implement resume lifecycle. This remains Stage 4C scope.
- Stage 4B intentionally does not execute adapters or fsscript. Real execution remains Stage 5 scope.

## Final Decision

Accepted. The Stage 4B implementation satisfies the defined invoke skeleton and approval placeholder scope, with no blocking defects found during review or verification.

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted
- signed_off_by: Codex reviewer
- signed_off_at: 2026-05-03
- acceptance_record: docs/version-tracker/1.1.3-SNAPSHOT/acceptance/stage-4b-worker-gateway-invoke-acceptance.md
- blocking_items: none
- follow_up_required: no
