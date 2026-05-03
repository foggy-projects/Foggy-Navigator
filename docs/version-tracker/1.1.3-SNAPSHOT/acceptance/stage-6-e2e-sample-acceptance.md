---
doc_role: acceptance-record
doc_purpose: Record feature-level acceptance review for Stage 6 E2E Sample.
acceptance_scope: feature
version: 1.1.3-SNAPSHOT
target: Stage 6 E2E Sample
status: signed-off
decision: accepted-with-risks
signed_off_by: Codex reviewer
signed_off_at: 2026-05-03
reviewed_by: Codex
blocking_items: []
follow_up_required: yes
evidence_count: 9
implementation_date: 2026-05-03
---

# Feature Acceptance – Stage 6 E2E Sample

## Background

Stage 6 closes the lifecycle loop for the business agent. Its purpose is to confirm that all
control-plane components (Stages 1-5) can be composed into a coherent, auditable execution chain:
provisioning credential → ClientApp → grants → task-scoped token → Worker Gateway tools →
approval suspension → control-plane resume → RESUME_DISPATCHED event.

No new production code was added; Stage 6 is verification-only.

## Acceptance Basis

- [08-implementation-plan.md](../08-implementation-plan.md)
- [03-java-worker-gateway-api-contract.md](../03-java-worker-gateway-api-contract.md)
- [05-approval-suspension-and-upstream-callback-contract.md](../05-approval-suspension-and-upstream-callback-contract.md)
- [stage-5-worker-tools-fsscript-acceptance.md](stage-5-worker-tools-fsscript-acceptance.md)
- [BusinessAgentE2ESampleTest.java](../../../../business-agent-module/src/test/java/com/foggy/navigator/business/agent/e2e/BusinessAgentE2ESampleTest.java)

## Concrete Sample

| Field | Value |
|---|---|
| tenant | `tenant_stage6` |
| clientAppId | `capp_stage6_fixed` |
| upstreamUserId | `upstream_user_stage6` |
| skillId | `stage6_order_skill` |
| functionId | `stage6.order.close_apply.submit` |
| version | `v1` |
| workerPoolId | `stage6_langgraph_pool` |
| modelConfigId | `model_stage6` |
| riskLevel | `state_change` |
| approvalRequired | `true` |
| inputJson | `{"orderId":"ORD-0001"}` |

## Checklist

- [x] ClientApp is created from a provisioning credential path (mocked credential lookup).
- [x] Model config is granted to the Client App and marked as default; `createTask` resolves and fixes the `modelConfigId`.
- [x] Upstream user grant is required and checked before task creation and Worker Gateway access.
- [x] Skill exists, Skill-Function allowlist is ENABLED, and Skill grant to Client App is ENABLED.
- [x] Business Function + Version + ClientApp Function Grant are all ENABLED before invoke.
- [x] `createTask` returns a task-scoped token exactly once; all binding fields (tenantId, clientAppId, upstreamUserId, skillId, workerPoolId, taskId, sessionId) are present in the resolved token.
- [x] `listBusinessFunctions` returns exactly the allowlisted function with its LLM-visible summary.
- [x] `getBusinessFunctionSchema` returns safe schema fields; `adapterConfigJson` and `manifestJson` are NOT fields on `WorkerGatewayFunctionSchemaDTO`.
- [x] `invokeBusinessFunction` for an approval-required function returns `SUSPENDED` with a `sus_`-prefixed `suspendId`.
- [x] `resumeSuspension` performs strict binding validation and saves status `RESUME_DISPATCHED`.
- [x] `resumeSuspension` publishes a `WorkerGatewayResumeEvent` with correct taskId, suspendId, approvalResult, and comment.

## Negative Evidence

| Test | What it proves |
|---|---|
| `stage6_negative_skillAllowlistDisabled_functionHiddenFromList` | Disabling the Skill allowlist hides the function from `listBusinessFunctions` |
| `stage6_negative_resumeBinding_wrongClientApp_rejected` | Mismatched `clientAppId` in resume binding throws `SecurityException` |
| `stage6_negative_resumeBinding_wrongInputHash_rejected` | Wrong `inputHash` in resume binding throws `SecurityException` |
| `stage6_negative_missingInputHash_rejected` | Absent `inputHash` in resume binding throws `SecurityException` (fail-closed) |
| `stage6_negative_invalidToken_rejectsGatewayAccess` | Invalid task-scoped token throws `IllegalArgumentException` at Gateway entry |

## Evidence

- Test: `BusinessAgentE2ESampleTest` — 6 tests total (1 lifecycle + 5 negative), all pass.
- Commands run and results:

```
mvn test -pl business-agent-module -Dtest=BusinessAgentE2ESampleTest
  → Tests run: 6, Failures: 0, Errors: 0

mvn test -pl business-agent-module
  → Tests run: 125, Failures: 0, Errors: 0  (119 existing + 6 new E2E)

mvn test -pl addons/langgraph-biz-worker
  → Tests run: 47, Failures: 0, Errors: 0

mvn compile -pl launcher -am -DskipTests
  → BUILD SUCCESS
```

- Terminology check: no `UpstreamApp` / `upstream_app` references found in business-agent-module or version-tracker docs.
- Sensitive field check: `adapterConfigJson` and `manifestJson` are not present on `WorkerGatewayFunctionSchemaDTO` (verified by reflection in the lifecycle test).

## Risks / Open Items

1. **`task_scoped_token` runtime injection** (carried from Stage 5): Worker tools currently receive the task-scoped token as an LLM-visible parameter. This must be replaced by Worker runtime context injection before production use, ensuring the token is never part of the model-visible tool schema. This is a **production hardening item**, not a Stage 6 blocker.

2. **Tool message persistence**: `WorkerGatewayService.reportToolMessage` logs at INFO level only. Persistent audit storage is explicitly out of scope for 1.1.3-SNAPSHOT.

3. **Real adapter invocation**: `invokeBusinessFunction` returns `ADAPTER_NOT_IMPLEMENTED` for non-approval-required functions. Actual upstream REST adapter integration is Stage 7+.

4. **`WorkerGatewayResumeEvent` binding context consumption**: `WorkerGatewayResumeEvent` now carries binding context fields such as `tenantId`, `clientAppId`, `upstreamUserId`, `functionId`, and `inputHash`, and the project compiles successfully with Lombok-generated getters. The current LangGraph listener still validates only `taskId` and `sessionId` before dispatching resume to the Python worker. Consuming more binding context in the listener is a follow-up hardening item.

## Final Decision

**Accepted with risks.** All Stage 6 acceptance criteria are met by test evidence.
The implementation is self-consistent and demonstrates the full lifecycle. Remaining items are production hardening or Stage 7+ execution integration work, not Stage 6 blockers.

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex reviewer
- signed_off_at: 2026-05-03
- acceptance_record: docs/version-tracker/1.1.3-SNAPSHOT/acceptance/stage-6-e2e-sample-acceptance.md
- blocking_items: []
- follow_up_required: yes
