# Stage 10B Upstream User Credential And REST Header Injection Acceptance

- status: accepted
- decision: accepted
- date: 2026-05-04

## Scope

Stage 10B closes the TMS user credential injection loop:

1. Store an upstream user token on the server side, bound to `tenantId + clientAppId + upstreamUserId`.
2. Keep the token out of control-plane DTOs, LLM-facing schema, manifest output, audit output, and logs.
3. Inject the configured token header from the REST adapter using server-side runtime context only.
4. Inject Navigator context headers for TMS audit and consistency checks.
5. Prevent Manifest from spoofing token or `X-Navigator-*` headers.

## Implementation

- `GrantUpstreamUserForm` now accepts `upstreamUserToken`.
- `ClientAppUpstreamUserGrantEntity` stores `upstreamUserToken`; `ClientAppUpstreamUserGrantDTO` intentionally does not expose it.
- `ClientAppUserGrantService.resolveUpstreamUserToken(...)` validates active ClientApp, enabled user grant, and configured token before returning it to the adapter.
- `BusinessFunctionRuntimeContextDTO` carries internal runtime binding fields: `clientAppId`, `upstreamUserId`, `skillId`, `taskId`, `sessionId`, and `workerPoolId`.
- `WorkerGatewayService.invokeBusinessFunction(...)` copies task-scoped token context into the internal function runtime context before adapter invocation.
- `RestBusinessFunctionAdapterInvoker` injects:
  - configured upstream user token header, for example `X-TMS-Agent-Token`
  - `X-Navigator-Tenant-Id`
  - `X-Navigator-Client-App-Id`
  - `X-Navigator-Upstream-User-Id`
  - `X-Navigator-Task-Id`
  - `X-Navigator-Session-Id`
  - `X-Navigator-Function-Id`
  - `X-Navigator-Function-Version`

## Security Checks

- Manifest-defined `Authorization`, `Proxy-Authorization`, and other forbidden headers remain rejected.
- Manifest-defined `X-Navigator-*` headers are rejected.
- Manifest-defined configured token header is rejected.
- Token injection is disabled unless `foggy.navigator.business.agent.upstreams.<upstream-ref>.user-token-header` is configured.
- Token is not exposed by `ClientAppUpstreamUserGrantDTO`.
- Audit entities and DTOs still do not include token fields.

## Test Evidence

- `ClientAppUserGrantServiceTest`
  - stores token on grant entity
  - proves DTO does not expose token
  - resolves token for enabled grants
  - rejects missing token
- `RestBusinessFunctionAdapterInvokerTest`
  - injects configured token header
  - injects Navigator context headers
  - rejects Manifest spoofing of `X-Navigator-*`
  - rejects Manifest spoofing of configured token header
- `RestAdapterUpstreamE2ETest`
  - starts a real local HTTP server
  - verifies upstream receives `X-TMS-Agent-Token`
  - verifies upstream receives Navigator context headers
- `BusinessAgentApiSmokeTest`
  - verifies SDK sends `upstreamUserToken` in `grantUpstreamUserAccess`

## Remaining Risks

1. Token is currently stored in the grant table as a plain server-side field for the development loop. Future hardening should move it to encrypted storage or a secret store.
2. The current controlled injection supports configured custom headers such as `X-TMS-Agent-Token`; `Authorization: Bearer` remains intentionally unsupported for Manifest and is not enabled as a controlled token header.
3. Stage 10C still needs an end-to-end TMS-oriented onboarding sample and `orderIdentifier` schema safety checks.
