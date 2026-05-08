---
doc_role: acceptance-record
doc_purpose: Record feature-level acceptance review for Stage 5 Worker Tools / fsscript.
acceptance_scope: feature
version: 1.1.3-SNAPSHOT
target: Stage 5 Worker Tools / fsscript
status: signed-off
decision: accepted
signed_off_by: Codex reviewer
signed_off_at: 2026-05-03
reviewed_by: Codex reviewer
blocking_items: []
follow_up_required: no
evidence_count: 7
---

# Feature Acceptance

## Background

Stage 5 is expected to add LLM-callable LangGraph Biz Worker tools that call Java Worker Gateway for business function list/schema/invoke and report tool execution events back to Java. fsscript execution remains a Worker-side concern, but Java should still be able to receive related tool messages and approval wait/status signals.

## Acceptance Basis

- [08-implementation-plan.md](../08-implementation-plan.md)
- [03-java-worker-gateway-api-contract.md](../03-java-worker-gateway-api-contract.md)
- [WorkerGatewayClient.java](../../../../addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/client/WorkerGatewayClient.java)
- [InvokeBusinessFunctionTool.java](../../../../addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/tool/InvokeBusinessFunctionTool.java)
- [RunBusinessScriptTool.java](../../../../addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/tool/RunBusinessScriptTool.java)
- [WorkerGatewayService.java](../../../../business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/WorkerGatewayService.java)
- [WorkerGatewayServiceTest.java](../../../../business-agent-module/src/test/java/com/foggy/navigator/business/agent/service/WorkerGatewayServiceTest.java)
- [InvokeBusinessFunctionToolTest.java](../../../../addons/langgraph-biz-worker/src/test/java/com/foggy/navigator/langgraph/worker/tool/InvokeBusinessFunctionToolTest.java)
- [RunBusinessScriptToolTest.java](../../../../addons/langgraph-biz-worker/src/test/java/com/foggy/navigator/langgraph/worker/tool/RunBusinessScriptToolTest.java)

## Checklist

- [x] Worker has LLM-callable tools for list/schema/invoke.
- [x] WorkerGatewayClient calls Java Gateway over HTTP with `X-Task-Scoped-Token`.
- [x] list/schema/invoke tools do not directly access Function Registry.
- [x] invoke tool handles `SUSPENDED` as `approval_wait` and preserves `suspendId`.
- [x] Java has a `POST /internal/worker-gateway/v1/tool-messages` endpoint that validates task-scoped token.
- [x] invoke tool reports tool messages to Java on success/error.
- [x] fsscript placeholder tool (`run_business_script`) reports a `SCRIPT_NOT_AVAILABLE` tool message to Java (best-effort).
- [ ] task-scoped token is injected by Worker runtime rather than exposed as an LLM-supplied tool parameter. *(known open item, not a blocker for this stage)*

## Evidence

- Code review: `WorkerGatewayClient` wraps list/schema/invoke/tool-message Gateway calls and requires token before HTTP execution.
- Code review: `InvokeBusinessFunctionTool` reports `APPROVAL_WAIT`, `SUCCESS`, and `ERROR` tool messages through `WorkerGatewayClient.reportToolMessage(...)`.
- Code review: `RunBusinessScriptTool` injects `WorkerGatewayClient` and calls `reportToolMessageSafely(token, scriptId)` after validation passes. Failure is best-effort (warn log only) and does not affect the placeholder return result.
- Code review: `reportToolMessageSafely` reports `{ toolName: "run_business_script", status: "SCRIPT_NOT_AVAILABLE", message: "..." }` to Java.
- Code review: tool schemas include `task_scoped_token` as a required LLM parameter (open item, not a blocker).
- Test evidence: `RunBusinessScriptToolTest` (5 tests) covers: placeholder return + reportToolMessage called, reportToolMessage throws → still returns placeholder, missing token → no gateway call, missing scriptId → no gateway call, null params.
- Test evidence: `InvokeBusinessFunctionToolTest` covers suspended handling and tool-message reporting.
- Verification: `mvn test -pl business-agent-module -am` passed: 119 tests, 0 failures.
- Verification: `mvn test -pl addons/langgraph-biz-worker -am` passed: 47 tests, 0 failures.
- Verification: `mvn compile -pl launcher -am -DskipTests` passed.

## Resolved Items

- ~~`run_business_script` does not report its tool execution event to Java~~ → **FIXED**: `RunBusinessScriptTool` now calls `reportToolMessageSafely(token, scriptId)` after validation.

## Risks / Open Items

- The current tool APIs require the LLM to supply `task_scoped_token`. This should be replaced by Worker runtime context injection before production use, so the token is not part of the model-visible tool schema.
- `WorkerGatewayService.reportToolMessage(...)` accepts a minimal form and logs only. Persistence is explicitly out of scope, but null/form-field validation should be tightened in a follow-up hardening pass.

## Final Decision

Accepted. All Stage 5 blocking items resolved. The fsscript placeholder now reports a tool message to Java, preserving the boundary that fsscript runs in Worker while Java receives observable tool events.

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted
- signed_off_by: Codex reviewer
- signed_off_at: 2026-05-03
- acceptance_record: docs/version-tracker/1.1.3-SNAPSHOT/acceptance/stage-5-worker-tools-fsscript-acceptance.md
- blocking_items: []
- follow_up_required: no
