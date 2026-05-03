---
doc_role: acceptance-record
doc_purpose: Record feature-level acceptance review for Stage 4C Gateway suspension lifecycle.
acceptance_scope: feature
version: 1.1.3-SNAPSHOT
target: Stage 4C Gateway suspension lifecycle
status: signed-off
decision: accepted-with-risks
signed_off_by: Codex reviewer
signed_off_at: 2026-05-03
reviewed_by: Codex reviewer
blocking_items: []
follow_up_required: yes
evidence_count: 7
---

# Feature Acceptance

## Background

Stage 4C is expected to turn the Stage 4B `suspendId` placeholder into a Java-owned approval and suspension lifecycle. The core safety goal is fail-closed approval handling: Worker and LLM must not self-approve, and resume must be bound to the original task, session, Client App, upstream user, function, and input.

This record was updated after the final security-fix review on 2026-05-03. The trusted Control Plane authorization boundary, context binding, mandatory input hash validation, structured input normalization, and version binding checks are now implemented.

## Acceptance Basis

- [08-implementation-plan.md](../08-implementation-plan.md)
- [03-java-worker-gateway-api-contract.md](../03-java-worker-gateway-api-contract.md)
- [05-approval-suspension-and-upstream-callback-contract.md](../05-approval-suspension-and-upstream-callback-contract.md)
- [BusinessFunctionApprovalController.java](../../../../business-agent-module/src/main/java/com/foggy/navigator/business/agent/controller/BusinessFunctionApprovalController.java)
- [BusinessFunctionSuspensionService.java](../../../../business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessFunctionSuspensionService.java)
- [WorkerGatewayService.java](../../../../business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/WorkerGatewayService.java)
- [BusinessFunctionSuspensionServiceTest.java](../../../../business-agent-module/src/test/java/com/foggy/navigator/business/agent/service/BusinessFunctionSuspensionServiceTest.java)
- [WorkerGatewayServiceTest.java](../../../../business-agent-module/src/test/java/com/foggy/navigator/business/agent/service/WorkerGatewayServiceTest.java)
- [LanggraphWorkerResumeEventListener.java](../../../../addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphWorkerResumeEventListener.java)

## Checklist

- [x] Invoke creates a persisted suspension record when approval is required.
- [x] Expired suspension is rejected before resume.
- [x] Already processed suspension cannot be processed again.
- [x] Resume endpoint has a trusted caller/auth boundary.
- [x] Resume validates tenant, Client App, upstream user, task, session, and function binding.
- [x] Resume validates version and input binding fail-closed.
- [x] Resume dispatch event carries task/session and business binding data for Worker-side handling.
- [x] Tests cover Control Plane authorization and basic binding mismatch cases.
- [x] Tests cover missing input hash rejection and hash mismatch rejection.
- [x] Tests cover version mismatch rejection.

## Evidence

- Code review: `BusinessFunctionApprovalController.resumeSuspension(...)` now uses the Control Plane path and requires `TENANT_ADMIN`, which resolves the previous unprotected internal endpoint blocker.
- Code review: `BusinessFunctionSuspensionService.resumeSuspension(...)` now validates request tenant, Client App, upstream user, task, session, and function against the persisted suspension.
- Code review: `BusinessFunctionSuspensionService.resumeSuspension(...)` now requires `binding_context.input_hash` and rejects mismatches with `SecurityException`.
- Code review: `BusinessFunctionSuspensionService.resumeSuspension(...)` now validates `binding_context.version` against the persisted suspension version.
- Code review: `WorkerGatewayService.invokeBusinessFunction(...)` now normalizes structured `input` into JSON when `inputJson` is not supplied.
- Test evidence: `BusinessFunctionSuspensionServiceTest` covers approved/rejected resume, already processed, expired, binding mismatch, version mismatch, missing input hash, and hash mismatch.
- Verification: `mvn test -pl business-agent-module -am` passed: 114 tests, 0 failures.
- Verification: `mvn test -pl addons/langgraph-biz-worker -am` passed: 28 tests in `langgraph-biz-worker`, 0 failures.
- Verification: `mvn compile -pl launcher -am -DskipTests` passed.

## Failed Items

None blocking after the final security-fix review.

## Risks / Open Items

- Resolved by Stage 4D: `WorkerGatewayService.invokeBusinessFunction(...)` now fails closed when structured input serialization fails. See [stage-4d-gateway-suspension-hardening-acceptance.md](stage-4d-gateway-suspension-hardening-acceptance.md).
- The suspension row advances to `RESUME_DISPATCHED`, while approved/rejected is transiently reflected before dispatch. This is acceptable for the current lifecycle skeleton, but a later audit model should preserve final approval outcome as a durable field or separate approval event.

## Final Decision

Accepted with risks. Stage 4C now satisfies the required fail-closed approval resume boundary for this stage: trusted Control Plane entry, task/session/client/user/function/version binding, mandatory input hash validation, Worker resume dispatch, and passing module verification are all present.

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex reviewer
- signed_off_at: 2026-05-03
- acceptance_record: docs/version-tracker/1.1.3-SNAPSHOT/acceptance/stage-4c-gateway-suspension-lifecycle-acceptance.md
- blocking_items: none
- follow_up_required: yes
