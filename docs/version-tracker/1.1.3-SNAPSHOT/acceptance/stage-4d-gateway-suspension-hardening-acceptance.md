---
doc_role: acceptance-record
doc_purpose: Record feature-level acceptance review for Stage 4D Gateway suspension hardening.
acceptance_scope: feature
version: 1.1.3-SNAPSHOT
target: Stage 4D Gateway suspension hardening
status: signed-off
decision: accepted
signed_off_by: Codex reviewer
signed_off_at: 2026-05-03
reviewed_by: Codex reviewer
blocking_items: []
follow_up_required: no
evidence_count: 6
---

# Feature Acceptance

## Background

Stage 4D closes the non-blocking risks left by Stage 4C before moving into Worker tools and end-to-end validation. The scope is intentionally narrow: align resume endpoint documentation, fail closed on structured input serialization errors, and reject null resume payloads explicitly.

## Acceptance Basis

- [08-implementation-plan.md](../08-implementation-plan.md)
- [03-java-worker-gateway-api-contract.md](../03-java-worker-gateway-api-contract.md)
- [05-approval-suspension-and-upstream-callback-contract.md](../05-approval-suspension-and-upstream-callback-contract.md)
- [WorkerGatewayService.java](../../../../business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/WorkerGatewayService.java)
- [BusinessFunctionSuspensionService.java](../../../../business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessFunctionSuspensionService.java)
- [WorkerGatewayServiceTest.java](../../../../business-agent-module/src/test/java/com/foggy/navigator/business/agent/service/WorkerGatewayServiceTest.java)
- [BusinessFunctionSuspensionServiceTest.java](../../../../business-agent-module/src/test/java/com/foggy/navigator/business/agent/service/BusinessFunctionSuspensionServiceTest.java)

## Checklist

- [x] Resume endpoint docs point to the Control Plane API, not an exposed Worker Gateway resume API.
- [x] Approval contract states Worker does not expose resume control plane.
- [x] Structured `input` is serialized into a deterministic JSON string before suspension creation.
- [x] Structured input serialization failure throws and does not create a suspension.
- [x] Null resume form is rejected before any repository or event side effect.
- [x] Tests cover the new hardening branches.

## Evidence

- Code review: `WorkerGatewayService.invokeBusinessFunction(...)` now throws `IllegalArgumentException` when structured input cannot be serialized.
- Code review: `BusinessFunctionSuspensionService.resumeSuspension(...)` now rejects null form explicitly.
- Test evidence: `WorkerGatewayServiceTest.invokeBusinessFunction_withStructuredInput_success`.
- Test evidence: `WorkerGatewayServiceTest.invokeBusinessFunction_withStructuredInput_serialization_fails`.
- Test evidence: `BusinessFunctionSuspensionServiceTest.resumeSuspension_nullForm_rejected`.
- Verification: `mvn test -pl business-agent-module -am` passed: 117 tests, 0 failures.
- Verification: `mvn test -pl addons/langgraph-biz-worker -am` passed.
- Verification: `mvn compile -pl launcher -am -DskipTests` passed.

## Failed Items

None.

## Risks / Open Items

None blocking for Stage 4D. The later durable audit model can still improve approval outcome history, but that is outside this hardening scope.

## Final Decision

Accepted. Stage 4D closes the Stage 4C hardening follow-up and establishes a stable baseline for the next stage.

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted
- signed_off_by: Codex reviewer
- signed_off_at: 2026-05-03
- acceptance_record: docs/version-tracker/1.1.3-SNAPSHOT/acceptance/stage-4d-gateway-suspension-hardening-acceptance.md
- blocking_items: none
- follow_up_required: no
