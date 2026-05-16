---
type: bug
bug_source: acceptance-found
version: 1.3.0-SNAPSHOT
ticket: BUG-issue-115-world-sim-route-smoke-blocked
severity: major
status: in-progress
reproduction_status: confirmed
test_strategy: integration-test
automation_decision: required
owner: navigator-business-agent
---

# BUG Work Item

## Background

Issue #115 added a ClientApp-scoped upstream route registry so new upstream projects can register `upstream_ref` routes without Navigator startup properties.

Upstream acceptance on 2026-05-16 confirmed the code and unit-test layer, but the live `world-sim` smoke did not reach REST forwarding.

## Reproduction

From the upstream project, install the remote Navigator Upstream CLI package and run:

```powershell
.\tools\navigator-upstream\navi.ps1 upstream route set `
  --upstream-ref world-sim `
  --url http://localhost:<world-sim-port>
```

Then run the `world-sim.script.request-notification-draft.v1@v1` smoke.

## Expected vs Actual

Expected:

- `upstream route list/set/status` is available in the installed CLI package.
- The current ClientApp control credential can register the `world-sim` route.
- The tool-call smoke reaches the `world-sim` upstream service.

Actual:

- Installed `navigator-upstream-cli 1.0.2` did not contain `upstream route`.
- Locally rebuilt CLI exposed the command, but route management returned `HTTP 401: control-plane credential lacks scope: UPSTREAM_ROUTE_MANAGE`.
- Tool-call smoke still failed in Navigator route resolution with `HTTP 400: Unauthorized or unconfigured upstream_ref: world-sim`.

## Impact Scope

The long-term #115 code path is present, but live onboarding for a new upstream cannot complete until the CLI package and ClientApp control credential are refreshed.

## Test Strategy

Keep unit coverage for route resolution, route command behavior, disabled routes, trimmed headers, and credential scope checks.

Add release/package validation so OBS-installed CLI help includes:

```powershell
.\tools\navigator-upstream\navi.ps1 upstream route --help
```

## Code Inventory

- `navigator-open-sdk/pom.xml`
- `tools/navigator-upstream-cli/dist/package.ps1`
- `tools/navigator-upstream-cli/dist/upload.ps1`
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/ClientAppControlCredentialService.java`
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/ClientAppUpstreamRouteService.java`

## Fix Checklist

- Completed: bumped Navigator Upstream CLI package version to `1.0.3` for a fresh remote release.
- Completed: added `upstream-route` to packaged CLI feature metadata.
- Completed: added remote install smoke coverage for `upstream route --help`.
- Completed: published `navigator-upstream-cli 1.0.3` to OBS and refreshed `latest.json`.
- Reissue or update the SIM ClientApp control credential with `UPSTREAM_ROUTE_MANAGE` or `CONTROL_PLANE_ALL`.
- Re-run live `world-sim` smoke and update issue #115.

## Verification

Run:

```powershell
mvn -pl navigator-open-sdk -Dtest=UpstreamCliTest test
mvn -pl business-agent-module -am "-Dtest=ClientAppUpstreamRouteServiceTest,ClientAppControlCredentialServiceTest,RestBusinessFunctionAdapterInvokerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
powershell -ExecutionPolicy Bypass -File tools\navigator-upstream-cli\dist\package.ps1
```

After publishing:

```powershell
powershell -ExecutionPolicy Bypass -File tools\navigator-upstream-cli\dist\upload.ps1 -Version 1.0.3
.\tools\navigator-upstream\navi.ps1 self update
.\tools\navigator-upstream\navi.ps1 upstream route --help
```

Current release evidence:

- `navigator-upstream-cli 1.0.3`
- Remote base URL: `https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/navigator-upstream-cli`
- Package SHA256: `66b7731b047528194d523bf0c59aa976a82ebb34f47502056d9dcf4c7f2550a6`
- Remote install smoke: passed

## References

- GitHub issue #115
- Follow-up comment: https://github.com/foggy-projects/Foggy-Navigator/issues/115#issuecomment-4466735960
- `docs/version-tracker/1.3.0-SNAPSHOT/15-client-app-upstream-route-registry.md`
