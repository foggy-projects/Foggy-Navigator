---
type: bug
bug_source: user-report
version: 1.3.0-SNAPSHOT
ticket: BUG-133
severity: major
status: ready-for-verification
reproduction_status: confirmed
test_strategy: unit-test
automation_decision: required
owner: business-agent-module
---

# BUG Work Item

## Background

TMS reported GitHub issue 133: source tenant `138` cannot create or reuse its Navigator ClientApp binding through `POST /api/v1/admin/upstream-tenants/client-apps/ensure`.

The request is authenticated with the platform/upstream-system scoped `NAVI_ADMIN_API_KEY`, but Navigator rejects it before ClientApp provisioning with:

```text
upstream admin credential tenant mismatch
```

TMS expected one upstream-system bootstrap credential to provision tenant-scoped ClientApp credentials, and then store those returned runtime/control credentials as the ACTIVE DB binding.

## Reproduction

1. Build an `EnsureUpstreamTenantClientAppForm` with `sourceSystem=TMS` and `sourceTenantId=138`.
2. Authenticate with an upstream admin principal whose `upstreamSystemId` is `TMS`.
3. Scope the principal to the upstream system rather than the derived Navigator tenant id.
4. Call `UpstreamTenantClientAppProvisioningService.ensure(...)`.

## Expected vs Actual

Expected:

- `ensure-tenant` accepts an admin key scoped to the matching upstream system.
- The same endpoint still accepts the existing derived Navigator tenant scope.
- A mismatched upstream system remains rejected.

Actual before the fix:

- `ensure-tenant` only checked `authorizedTenantIds` for the derived `nav_tms_<sourceTenantId>` tenant id.
- A platform/upstream-system scoped admin credential was rejected before ClientApp create/reuse.

## Impact Scope

- TMS cold-start ClientApp provisioning.
- Navigator readiness for TMS tenants that require an ACTIVE DB-backed binding.
- Upstream tenant aggregate provisioning via `NAVI_ADMIN_API_KEY`.

## Test Strategy

Add unit coverage in `UpstreamTenantClientAppProvisioningServiceTest` for:

- upstream-system scoped admin credential can provision the derived tenant,
- source-tenant scoped admin credential can provision the derived tenant,
- the existing unauthorized derived tenant rejection still fails closed.

## Code Inventory

- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/UpstreamTenantClientAppProvisioningService.java`
  - adjusts `requirePrincipal(...)` tenant authorization for the aggregate provisioning endpoint.
- `business-agent-module/src/test/java/com/foggy/navigator/business/agent/service/UpstreamTenantClientAppProvisioningServiceTest.java`
  - adds regression coverage for the TMS provisioning model.

## Fix Checklist

- [x] Confirm the rejection path matches issue 133.
- [x] Preserve the existing exact derived Navigator tenant authorization path.
- [x] Allow matching upstream-system scoped credentials for `ensure-tenant`.
- [x] Allow source-tenant scoped credentials for `ensure-tenant`.
- [x] Verify existing mismatch checks remain in place.

## Verification

Commands run:

```powershell
mvn test -pl business-agent-module -am "-Dtest=UpstreamTenantClientAppProvisioningServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false"
mvn test -pl business-agent-module -am
```

Result:

- targeted provisioning service test: 10 tests passed,
- business-agent-module reactor test: 415 tests passed.

## References

- GitHub issue: https://github.com/foggy-projects/Foggy-Navigator/issues/133
- TMS local work item from issue: `docs/v3.2.3/workitems/BUG-B608-navigator-tenant-138-active-db-binding-missing.md`
