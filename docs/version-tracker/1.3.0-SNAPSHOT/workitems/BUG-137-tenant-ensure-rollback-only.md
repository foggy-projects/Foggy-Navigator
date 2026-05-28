---
type: bug
bug_source: user-report
version: 1.3.0-SNAPSHOT
ticket: BUG-137
severity: major
status: ready-for-verification
reproduction_status: confirmed
test_strategy: unit-test
automation_decision: required
owner: navigator-backend
---

# BUG Work Item

## Background

TMS retested tenant `138` after Navi `7f31ab8a`. `POST /bff/navigator/admin/integration-bindings/ensure-tenant` with `rotateCredentials=true` still returned HTTP 500. Navi logs showed structured blockers had already been assembled:

- `model config resource is not activation-ready: model config is not visible to this ClientApp`
- `root agent was ensured without defaultModelConfigId`
- `workspace resource is not activation-ready: working directory tenant mismatch: 20260525-8fa8`

The final response was then overwritten by `UnexpectedRollbackException: Transaction silently rolled back because it has been marked as rollback-only`.

## Root Cause

`UpstreamTenantClientAppProvisioningService.ensure` held a single outer REQUIRED transaction while it performed best-effort provisioning and readiness validation. Some downstream Spring-proxied readiness methods could throw expected business exceptions such as `IllegalArgumentException`, `IllegalStateException`, or `SecurityException`. Even when `ensure` caught those exceptions and converted them to structured blockers, the downstream transaction interceptor could mark the shared transaction rollback-only first.

The first fix covered part of model grant mutation, but model resolution and A2 resource validation still had scattered/default transaction policies.

## Expected vs Actual

Expected: direct Navi admin ensure and TMS BFF ensure return HTTP 200 with structured not-ready payload when legacy tenant resources are invalid.

Actual: structured not-ready blockers were assembled, but commit of the shared outer transaction raised `UnexpectedRollbackException`, causing HTTP 500.

## Fix Summary

- Added `ReadinessTransactional`, a shared transaction meta-annotation for readiness/business validation paths.
- Changed tenant ensure to `Propagation.NOT_SUPPORTED`, so best-effort readiness checks cannot poison a single outer ensure transaction.
- Applied the shared readiness transaction policy to model grant resolution, A2 model/workspace/agent resolvers, and ClientApp read-side require methods used by readiness paths.
- Added a Spring transaction proxy regression test that calls ensure from a transactional caller while downstream readiness methods roll back their own transactions.

## Verification

Command:

```bash
mvn -pl business-agent-module -am "-Dtest=UpstreamTenantClientAppProvisioningTransactionTest,UpstreamTenantClientAppProvisioningServiceTest,ClientAppModelConfigGrantServiceTest,A2AgentResourceResolverTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Result: pass, 70 tests.

## Verification Expectation For TMS

After Navi deployment, tenant `138` should return structured not-ready instead of HTTP 500:

- `activationReady=false`
- `errorCode=MODEL_CONFIG_RESOURCE`
- `missingFields` includes `modelConfig.visibility` and `directory.tenant`
- `blockers` includes model config visibility and working directory tenant mismatch
- `remediationHint` is populated

The remaining blockers are legacy data/resource repair items, not admin key or ClientApp secret failures.

## TMS Retest Result

TMS verified Navi commit `82803dce`: direct Navi admin ensure and TMS BFF ensure both return HTTP 200 structured not-ready. The rollback-only HTTP 500 regression is fixed.

Latest remaining blocker is legacy tenant `138` resource ownership/visibility:

- model config `576d04e5-a99a-49c8-a219-7924a262f3c6` is not visible to ClientApp `capp_fe18fc28-9130-4ed5-9fac-5c85eb75d539`
- directory `20260525-8fa8` has a tenant mismatch and must be repaired to `nav_tms_138`
- root agent `tms-tenant-138-root-agent` needs a default model config and valid default bindings after repair

## Resource Repair Follow-up

Directory repair already has a controlled operator/super-admin API:

```text
POST /api/v1/admin/working-directories/{directoryId}/repair-upstream-system
```

Model config owner repair now has a matching controlled operator/super-admin API:

```text
POST /api/v1/admin/model-configs/{modelConfigId}/repair-owner
```

The model repair endpoint only updates model visibility metadata (`ownerType`, `ownerId`, optional `enabled`) and returns the previous/new owner metadata. It does not update runtime model fields or credentials.

Tenant `138` repair plan after deployment:

1. Repair model config `576d04e5-a99a-49c8-a219-7924a262f3c6` to `UPSTREAM_SYSTEM/TMS` or `CLIENT_APP/capp_fe18fc28-9130-4ed5-9fac-5c85eb75d539`.
2. Repair directory `20260525-8fa8` to `tenantId=nav_tms_138`, `ownerType=UPSTREAM_SYSTEM`, `ownerId=TMS`, `workspaceScope=UPSTREAM_SYSTEM_SHARED`, and set it as root agent default directory.
3. Rerun `ensure-tenant rotateCredentials=true`; ensure should grant the now-visible model config and set `tms-tenant-138-root-agent.defaultModelConfigId`.
4. Rerun readiness `none -> runtime -> grant -> preflight -> ask`.
