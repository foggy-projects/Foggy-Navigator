# Stage 12 Suspension Execution Semantics Acceptance

- doc_type: acceptance-record
- version: 1.1.3-SNAPSHOT
- status: accepted-with-live-retest
- date: 2026-05-06
- owner: navigator-java | langgraph-biz-worker

## Scope

This stage closes the resume/execute ambiguity found during the TMS P1 approval-required invoke retest.

The previous runtime path made approved business execution look like a Python Worker resume fallback: when the Worker had no active waiting frame, Java executed the approved suspension. The new model makes this explicit:

1. `WorkerGatewayResumeEvent` is a Worker conversation resume notification.
2. `BusinessSuspensionResumeDecisionEvent` is the Java-side business execution decision.
3. Approved business side effects are executed from the persisted `BusinessFunctionSuspensionEntity` context by `BusinessFunctionSuspensionService`.

## Implemented Changes

1. Added `BusinessSuspensionResumeDecisionEvent` in `business-agent-module`.
2. `BusinessFunctionSuspensionService.resumeSuspension(...)` now publishes two distinct events after commit:
   - Worker conversation notification event.
   - Java business execution decision event.
3. `LanggraphWorkerResumeEventListener` no longer calls `executeApprovedSuspension(...)`; it only notifies Python Worker conversation state.
4. `BusinessFunctionSuspensionService` listens for approved business decision events and executes the adapter from persisted suspension context.
5. Added persisted fields:
   - `suspensionType`
   - `businessExecutionStatus`
   - `workerNotificationStatus`
6. Added reserved suspension type constants:
   - `APPROVAL_REQUIRED`
   - `USER_PAYMENT_REQUIRED`
   - `USER_CONFIRMATION_REQUIRED`
   - `EXTERNAL_CALLBACK_WAIT`
   - `MANUAL_CHECK_REQUIRED`
7. Added pessimistic row lock lookup for suspension resume/execution idempotency.
8. Duplicate approve/resume and duplicate approved execution are no-op/idempotent and do not re-invoke the adapter.
9. Business execution decision listener runs in a new transaction because the decision event is published after commit and then acquires the suspension row lock.
10. Approved suspension invoke success/failure audit records now carry `suspendId`, so live execution evidence can be queried by suspension id.

## Security Semantics

Resume and execution remain fail-closed. Tenant, Client App, upstream user, business task, business session, function, version, and input hash are validated against the original persisted suspension. Resume payload and LLM/tool parameters cannot override the execution context.

Sensitive runtime values remain out of Worker-facing DTOs and logs: token, `task_scoped_token`, `adapterConfigJson`, and `manifestJson` are not exposed by this stage.

## Verification

```powershell
mvn test -pl business-agent-module -am "-Dtest=BusinessFunctionSuspensionServiceTest,WorkerGatewayServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false"
# Tests run: 37, Failures: 0, Errors: 0, Skipped: 0

mvn test -pl business-agent-module -am "-Dtest=BusinessFunctionSuspensionServiceTest,BusinessFunctionRuntimeAuditServiceTest,RestAdapterUpstreamE2ETest,WorkerGatewayServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false"
# Tests run: 53, Failures: 0, Errors: 0, Skipped: 0

mvn test -pl addons/langgraph-biz-worker -am "-Dtest=LanggraphWorkerResumeEventListenerTest" "-Dsurefire.failIfNoSpecifiedTests=false"
# Tests run: 10, Failures: 0, Errors: 0, Skipped: 0

mvn compile -pl launcher -am -DskipTests
# BUILD SUCCESS
```

## Runtime Retest Evidence

A new TMS test order is required for each live P1 approval-required invoke retest; do not reuse the already signed-off `orderIdentifier=1124` or any order already signed by a previous run.

TMS now provides a test-only order generator for this live retest. It is hosted by `x3-web-agent` and is enabled only under `dev-a3`, `local`, or `test` profiles:

```text
POST http://localhost:12580/api/test/business-agent/orders/self-pickup-sign-ready
GET  http://localhost:12580/api/test/business-agent/orders/{orderIdentifier}/self-pickup-sign-readiness
```

Required headers:

```text
Authorization: Bearer <TMS_STAFF_SESSION_TOKEN>
X-Tenant-Id: 88800
Content-Type: application/json
```

Generation request:

```json
{
  "scenario": "SELF_PICKUP_SIGN_P1",
  "requestedBy": "navigator-p1-test",
  "remark": "Navigator Business Agent approval-required retest"
}
```

The endpoint runs the real TMS order, inventory, dispatch, loading, departure, arrival, and unloading chain, then returns only LLM-facing/external fields such as `orderIdentifier`, `scenario`, `ready`, and `expiresAt`. It must not return internal ids, tokens, credentials, or Navigator internal gateway data.

TMS-side validation reported:

1. Generated sample `orderIdentifier=1126`, `ready=true`, `reasons=[]`.
2. Direct TMS `selfPickupSign` call with `{"orderIdentifier":"1126"}` returned `code=200`, `success=true`.
3. Re-signing the same order returned `code=200`, `alreadyCompleted=true`.
4. Sensitive string check passed.
5. TMS did not call Navigator `/internal/worker-gateway/v1/**`.

Navigator live retest on 2026-05-06:

1. Basic test session token issuance succeeded through `POST http://localhost:10001/session-token/newStaffSessionToken`; the token value was used only as a runtime credential and was not written to docs or logs.
2. TMS generated fresh test order `orderIdentifier=1129`; readiness before Navigator invocation was `ready=true`, `reasons=[]`.
3. Navigator created task `bt_d3b15e67da524eb192778467b1facfce` and worker task `lgt_8938e77e5ede47be`.
4. Worker Gateway invoke for `tms.fulfillment.selfPickupSign:v1` with input `{"orderIdentifier":"1129"}` returned `SUSPENDED` with `suspendId=sus_d98e372efc7b43e5b7e7a57457a11603`.
5. First approval resume dispatched Worker conversation notification and Java business execution decision.
6. Python Worker resume returned no active approval frame; Java did not treat that as a business fallback. The log explicitly recorded that business execution is handled by Java suspension service.
7. Java suspension service executed the REST adapter once and completed with TMS `code=200`.
8. Replaying the same approve/resume request returned idempotently and did not call TMS again.
9. TMS readiness after completion returned `ready=false`, `reasons=["order already signed"]`.
10. Final suspension state was `status=COMPLETED`, `businessExecutionStatus=COMPLETED`, `workerNotificationStatus=DISPATCH_REQUESTED`, `suspensionType=APPROVAL_REQUIRED`.

During the retest, an earlier run with order `1128` exposed a transaction boundary issue: the after-commit decision listener attempted a pessimistic row-lock query while still participating in a completed transaction context. The listener now uses a new transaction before calling `findBySuspendIdForUpdate(...)`; focused service tests were rerun after this fix.

No additional TMS-side feature work is required for this stage. Future manual retests should generate a fresh order through the test-only endpoint and pass only `{"orderIdentifier":"<fresh-orderIdentifier>"}` to `tms.fulfillment.selfPickupSign`.

## Local Live Retest Runbook

Navigator restart:

```powershell
$env:JAVA_TOOL_OPTIONS = '-Dfoggy.navigator.business.agent.upstreams.tms-x3-agent.url=http://localhost:12580 -Dfoggy.navigator.business.agent.upstreams.tms-x3-agent.user-token-header=X-TMS-Agent-Token'
.\start-launcher.ps1
```

`start-launcher.ps1` closes the process listening on port `8112`, builds the launcher jar, and starts Navigator in the background. Do not put TMS staff session tokens in `JAVA_TOOL_OPTIONS`; only upstream URL/header configuration belongs there.

Staff session token issuance for local TMS dev/test:

```bash
curl -X POST "http://localhost:10001/session-token/newStaffSessionToken" \
  -H "X-Tenant-Id: 88800" \
  -H "Content-Type: application/json" \
  -d '{
    "tel": "13800000001",
    "userId": 88801,
    "tenantMemberId": 88801,
    "tenantMemberName": "Navigator Agent Test Operator",
    "orgId": 88810,
    "ownerOrgId": 88810,
    "staffId": 88801,
    "staffTel": "13800000001",
    "staffName": "Navigator Agent Test Operator",
    "tenantId": 88800,
    "loginOrgId": 88810,
    "loginTenantId": 88800,
    "effectiveSecond": 3600,
    "persist": 0,
    "roleNames": ["Q"],
    "orgType": 1
  }'
```

Use only `data.sessionTokenId` as the runtime upstream-user credential. Do not paste the token into docs, LLM prompts, frontend DTOs, or logs.

Generate a fresh signable order:

```bash
curl -X POST "http://localhost:12580/api/test/business-agent/orders/self-pickup-sign-ready" \
  -H "Authorization: Bearer <TMS_STAFF_SESSION_TOKEN>" \
  -H "X-Tenant-Id: 88800" \
  -H "Content-Type: application/json" \
  -d '{"scenario":"SELF_PICKUP_SIGN_P1","requestedBy":"navigator-p1-test","remark":"Navigator Business Agent approval-required retest"}'
```

Readiness check:

```bash
curl -X GET "http://localhost:12580/api/test/business-agent/orders/<orderIdentifier>/self-pickup-sign-readiness" \
  -H "Authorization: Bearer <TMS_STAFF_SESSION_TOKEN>" \
  -H "X-Tenant-Id: 88800"
```

Navigator invocation input remains:

```json
{"orderIdentifier":"<fresh-orderIdentifier>"}
```

Expected retest result:

1. Before Navigator invoke, TMS readiness is `ready=true`.
2. Worker Gateway returns `SUSPENDED`.
3. First approve/resume executes Java-owned business side effect and TMS returns `code=200`.
4. Replaying approve/resume does not call TMS again.
5. After completion, TMS readiness reports the order already signed.
