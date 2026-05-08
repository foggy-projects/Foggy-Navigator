# Stage 10E Acceptance: SDK Bearer Control Plane Auth

Status: accepted

Date: 2026-05-05

## Scope

TMS bootstrap reached the real Navigator control-plane authentication boundary. The runner used the current sandbox `NAVIGATOR_ADMIN_TOKEN` as `NavigatorClient.apiKey(...)`, so the SDK sent it as `X-API-Key` and the server returned 401.

Stage 10E adds an explicit Bearer-token path to the SDK while preserving API-key authentication.

## Accepted Behavior

- `NavigatorClient.builder().apiKey("sk-*")` sends `X-API-Key`.
- `NavigatorClient.builder().bearerToken(jwt)` sends `Authorization: Bearer <jwt>`.
- `NavigatorClient.builder().adminToken(jwt)` is an alias for `bearerToken(jwt)`.
- If the caller passes a value already prefixed with `Bearer `, the SDK keeps the prefix and does not duplicate it.
- A client must provide at least one of `apiKey` or `bearerToken`.

## Evidence

- `BusinessAgentApiSmokeTest.testBearerTokenAuthHeader`
- `BusinessAgentApiSmokeTest.testAdminTokenAliasKeepsBearerPrefix`
- Existing API-key smoke tests still verify `X-API-Key` behavior.

## Security Notes

- Bearer/admin token is only a control-plane SDK credential. It must not be placed in manifest, frontend state, LLM prompts, logs, or Worker Gateway calls.
- TMS user token injection remains server-controlled through the Stage 10B upstream user credential flow.
- JWT values must not be passed to `apiKey(...)`; that path intentionally maps to `X-API-Key`.

## Decision

Accepted. Current sandbox bootstrap runners should use `NAVIGATOR_ADMIN_TOKEN` with `NavigatorClient.adminToken(...)`, or use a real `sk-*` key with `NavigatorClient.apiKey(...)`.
