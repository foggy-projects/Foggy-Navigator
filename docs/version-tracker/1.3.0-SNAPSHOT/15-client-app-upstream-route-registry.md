# ClientApp Upstream Route Registry

## Problem

GitHub issue #115 exposed a scaling gap in REST business function delivery: every new upstream `upstream_ref` required a Navigator JVM startup property such as:

```properties
foggy.navigator.business.agent.upstreams.world-sim.url=http://localhost:13080
```

That kept SSRF protection intact, but it made upstream onboarding depend on Navigator maintainers for every new project.

## Design

Navigator now supports a ClientApp-scoped upstream route registry:

- `tenantId + clientAppId + upstreamRef` is unique.
- Routes store `baseUrl`, optional `userTokenHeader`, `status`, and audit timestamps.
- Control-plane access requires `UPSTREAM_ROUTE_MANAGE` or `CONTROL_PLANE_ALL`.
- Existing JVM properties still take precedence for backward compatibility and operator override.
- If no JVM property exists, the REST adapter resolves an enabled route for the current function runtime `tenantId + clientAppId + upstream_ref`.
- Routes do not expose URLs to LLM/tool schemas; they are used only by the Java REST adapter.

The REST adapter still rejects direct URLs in function manifests. Function manifests continue to contain only:

```json
{
  "type": "rest",
  "upstream_ref": "world-sim",
  "method": "POST",
  "path": "/api/script/request-notification-draft"
}
```

## CLI

Upstream projects can self-register routes with their existing ClientApp-scoped control key:

```powershell
.\tools\navigator-upstream\navi.ps1 upstream route set `
  --upstream-ref world-sim `
  --url http://localhost:13080 `
  --user-token-header X-World-Sim-User-Token

.\tools\navigator-upstream\navi.ps1 upstream route list

.\tools\navigator-upstream\navi.ps1 upstream route status `
  --upstream-ref world-sim `
  --status DISABLED
```

The route command requires `NAVI_CONTROL_API_KEY` and `NAVI_CLIENT_APP_ID`.

## Safety

- `baseUrl` must use `http` or `https`, include a host, and not include user info.
- `upstreamRef` must match `[A-Za-z0-9._-]{1,128}`.
- `userTokenHeader` cannot be `Authorization`, proxy/connection headers, or any `X-Navigator-*` controlled header.
- Disabled or cross-ClientApp routes are not considered by adapter resolution.

## Issue #115

For the world-sim local sandbox, the long-term fix is:

```powershell
.\tools\navigator-upstream\navi.ps1 upstream route set `
  --upstream-ref world-sim `
  --url http://localhost:<world-sim-port>
```

After that, `invoke_business_function` can resolve `upstream_ref: world-sim` without adding a new Navigator startup property.

## Acceptance Follow-up

Upstream acceptance on 2026-05-16 passed source-level and unit-test validation, but the live `world-sim` smoke is still blocked before REST forwarding:

- The OBS-installed `navigator-upstream-cli 1.0.2` package did not contain `upstream route` commands.
- The current SIM ClientApp control credential returned `HTTP 401: control-plane credential lacks scope: UPSTREAM_ROUTE_MANAGE`.
- The smoke still fails in Navigator route resolution with `HTTP 400: Unauthorized or unconfigured upstream_ref: world-sim`.

Delivery follow-up:

1. Completed on 2026-05-16: released `navigator-upstream-cli 1.0.3` with `upstream route list/set/status`.
2. Reissue or update the SIM ClientApp control credential with `UPSTREAM_ROUTE_MANAGE` or `CONTROL_PLANE_ALL`.
3. Re-run `upstream route set/list/status` and `world-sim.script.request-notification-draft.v1@v1`.
