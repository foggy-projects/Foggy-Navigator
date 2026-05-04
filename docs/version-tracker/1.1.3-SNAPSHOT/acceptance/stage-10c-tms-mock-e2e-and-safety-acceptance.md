# Stage 10C TMS Mock E2E And Safety Acceptance

## Document Purpose

- doc_type: acceptance-record
- version: 1.1.3-SNAPSHOT
- status: accepted
- decision: accepted
- date: 2026-05-04
- scope: TMS mock E2E, SDK onboarding smoke, orderIdentifier schema guard, sensitive-field safety check

## Acceptance Scope

Stage 10C verifies the TMS-oriented Business Agent onboarding loop:

1. SDK can express the minimal TMS onboarding sequence.
2. Business Function input schema uses `orderIdentifier` as the only LLM-facing order identifier.
3. `expressOrderId` is absent from BusinessFunction input schema, Worker schema response, request body, response body, and SDK onboarding payload.
4. REST Adapter invokes a real local TMS mock HTTP server.
5. TMS mock receives server-controlled upstream user token and Navigator runtime context headers.

## Evidence

### SDK Smoke

- Test: `BusinessAgentApiSmokeTest.testTmsOnboardingSequence_usesOrderIdentifierAndCreatesTask`
- Covers:
  - BusinessObject creation request.
  - BusinessFunction manifest import request.
  - Skill allowlist request.
  - Function grant request.
  - Skill grant request.
  - upstream user grant with server-side token request.
  - Business task creation request.
  - `orderIdentifier` present in LLM-facing function payload.
  - internal order id absent from SDK onboarding payload.

### Business Agent E2E

- Test: `RestAdapterUpstreamE2ETest.tmsRestAdapter_e2e_usesOrderIdentifier_injectsHeaders_and_writesAudit`
- Covers:
  - Real JDK `HttpServer` mock TMS service.
  - Task creation through `BusinessAgentTaskService`.
  - Worker Gateway schema lookup.
  - Worker Gateway invoke.
  - REST Adapter outbound call.
  - `X-TMS-Agent-Token` injection.
  - Navigator runtime context header injection.
  - audit rows for invoke lifecycle.
  - `orderIdentifier` schema and payload guard.

## Security Result

Accepted with these boundaries:

1. `X-TMS-Agent-Token` appears only in docs/tests and outbound request header assertions.
2. user token is not returned by `ClientAppUpstreamUserGrantDTO`.
3. Worker Gateway schema and invoke response still do not expose `adapterConfigJson` or `manifestJson`.
4. Manifest-defined `Authorization`, `X-Navigator-*`, and configured token headers remain rejected by Stage 10B adapter tests.
5. `expressOrderId` appears only in safety documentation/search guidance, not in TMS onboarding payload or Worker schema.

## Verification Commands

```powershell
mvn test -pl navigator-open-sdk -am
mvn test -pl business-agent-module -am
mvn test -pl addons/langgraph-biz-worker -am
mvn compile -pl launcher -am -DskipTests
rg -n "task_scoped_token|adapterConfigJson|manifestJson|X-TMS-Agent-Token|Authorization|expressOrderId" business-agent-module navigator-open-sdk addons/langgraph-biz-worker docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration
```

## Remaining Risks

1. TMS internal authorization, tenant ownership checks, and business audit remain TMS-side responsibilities.
2. The sample uses local SDK installation and current development environment; a formal external sandbox still needs to be split after the first TMS feedback loop.
3. User token storage is still grant-bound plain server-side storage from Stage 10B; secret store/encryption remains future hardening.
