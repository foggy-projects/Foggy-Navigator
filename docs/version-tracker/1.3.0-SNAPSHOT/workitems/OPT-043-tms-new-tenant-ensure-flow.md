---
type: optimization
version: 1.3.0-SNAPSHOT
ticket: OPT-043
severity: major
status: ready-for-verification
source: GitHub issue #137 https://github.com/foggy-projects/Foggy-Navigator/issues/137
title: Optimize upstream tenant ensure flow for TMS new-tenant onboarding
owner: business-agent-module + navigator-open-sdk + upstream-cli
delivery_mode: single-root-delivery
---

# Optimize Upstream Tenant Ensure Flow for TMS New-Tenant Onboarding

## Issue Title

```text
Optimize upstream tenant ensure flow for TMS new-tenant onboarding
```

## Background

TMS tenant `138` has recovered. The admin key / scope / tenant mismatch chain has also been verified. During recovery, a follow-up new-tenant onboarding gap was exposed: `ensure-tenant` can rebuild or rotate ClientApp credentials and create model grants, but TMS still needs a complete activation payload before it can write an ACTIVE `x3_navigator_integration_binding` record.

This is an independent optimization and must not be folded back into issue `#133`.

TMS is a multi-tenant upstream system:

- TMS holds one upstream-system scoped `NAVI_ADMIN_API_KEY`.
- Each TMS tenant calls Navi `ensure-tenant` with `sourceTenantId`.
- Navi creates or reuses the tenant-level ClientApp for that source tenant.
- TMS writes tenant-scoped runtime/control credentials and runtime binding information into `x3_navigator_integration_binding`.

Current tenant `138` has recovered:

- `navigatorTenantId=nav_tms_138`
- ClientApp has been rebuilt or reused.
- Tenant model grant has been created.
- TMS DB binding is ACTIVE.
- Readiness `none/runtime` has passed.

However, recovery required TMS to call its explicit admin activation endpoint to fill the missing fields. It was not completed as a pure `ensure-tenant` self-healing flow.

## Current Gap

`ensure-tenant` currently handles:

- create/reuse tenant ClientApp
- issue/reissue runtime credential
- issue/reissue control credential
- create/reuse model config or model grant
- return tenant profile level credential data

TMS DB activation also needs:

- `agentCode` / `rootAgentId`
- `skillId`
- `modelConfigId`
- `workerBackend`
- `physicalWorkerId`
- `directoryId`
- `bizWorkerBaseUrl` if applicable
- `upstreamRef`
- capability domain / namespace metadata

If these fields are not obtained from the Navi provisioning response or TMS default policy, the TMS self-healing flow fails before DB activation. One observed failure was:

```text
workerBackend is required
```

## Requested Navi Improvement

Optimize the upstream tenant provisioning API so TMS new-tenant onboarding can complete in one operation.

### Option A: Navi returns activation-ready payload

`POST /api/v1/admin/upstream-tenants/client-apps/ensure` returns a complete TMS activation payload on success:

```json
{
  "navigatorTenantId": "nav_tms_138",
  "clientAppId": "...",
  "clientAppName": "TMS tenant 138",
  "clientAppCapabilityDomain": "tms.ops",
  "upstreamRef": "TMS-138",
  "runtimeCredential": {
    "clientAppKey": "...",
    "clientAppSecret": "..."
  },
  "controlCredential": {
    "controlApiKey": "..."
  },
  "agentCode": "tms.ops-root-agent",
  "rootAgentId": "tms.ops-root-agent",
  "skillId": "tms.navigator.agent",
  "modelConfigId": "...",
  "workerBackend": "LANGGRAPH_BIZ",
  "physicalWorkerId": "...",
  "directoryId": "...",
  "bizWorkerBaseUrl": "..."
}
```

After receiving this payload, TMS can directly call its activation flow and write the ACTIVE DB binding.

### Option B: Navi supports upstream-system default policy

Navi configures a default provisioning policy for `upstreamSystemId=TMS`:

- default root agent
- default skill bundle
- default model config template
- default worker backend
- default physical worker
- default directory/workspace policy
- default capability domain
- default namespace

Then TMS only needs to send:

```json
{
  "sourceSystem": "TMS",
  "sourceTenantId": "138",
  "name": "TMS tenant 138",
  "capabilityDomain": "tms.ops"
}
```

Navi can prepare the full resource set according to the default policy and return an activation-ready payload.

## Admin Key / Credential UX Improvement

The recovery also exposed an admin credential UX ambiguity: `admin-key request/claim` looks like every call creates a new upstream admin credential identity.

CLI/API output should explicitly show or provide a query for:

- current upstream admin credential id/principal bound to `NAVI_ADMIN_API_KEY`
- whether the operation is key rotation/replacement for the same upstream admin credential
- `authorizedTenantIds`
- namespace
- scopes
- expiry
- revoked/superseded state
- relationship between request code and credential/principal

Prefer supporting key rotation/replacement on an existing upstream admin credential, so TMS does not assume each request creates a new identity and worry that existing ClientApps no longer belong to the same upstream credential.

## Error Response Improvement

Navi provisioning API should return structured errors with:

- stable `errorCode`
- `missingFields`
- `requiredScopes`
- `actualScopes`
- `upstreamSystemId`
- `authorizedTenantIds`
- target Navigator tenant
- remediation hint

The recovery hit or investigated these failures:

- HTTP 401 ClientApp ensure
- missing `CLIENT_APP_MANAGE`
- scopes persisted as whitespace-joined string
- upstream admin credential tenant mismatch
- model config tenant mismatch
- credential non-replayable
- `workerBackend is required`

Structured errors would reduce TMS/Navi back-and-forth diagnosis cost.

## Expected Outcome

New TMS tenant onboarding should support:

1. TMS uses a fixed upstream-system scoped `NAVI_ADMIN_API_KEY`.
2. TMS calls `ensure-tenant` with `sourceSystem=TMS`, `sourceTenantId=<tenantId>`.
3. Navi creates or reuses ClientApp, runtime credential, control credential, model grant, agent/skill/worker/directory binding.
4. Navi returns an activation-ready payload.
5. TMS writes ACTIVE `x3_navigator_integration_binding`.
6. TMS readiness `none -> runtime -> grant -> preflight -> ask` passes continuously.

After this, new TMS tenant creation should not require manual data patching across CLI profile, Navi DB, TMS DB activation, and worker/directory fields.

## Acceptance Criteria

- `ensure-tenant` can return enough non-secret routing and binding metadata for TMS DB activation, while secrets remain one-time or explicitly masked outside the issuance response.
- TMS can onboard a new tenant from no binding to ACTIVE binding through its self-healing flow without manual worker/model/agent/skill/directory field input.
- Existing tenant `nav_tms_138` continues to work and remains a regression fixture for tenant-scoped ClientApp reuse.
- Admin key CLI/API output distinguishes credential identity from key rotation/replacement.
- Provisioning failures use structured error payloads with stable remediation fields.

## Implementation Notes

- Prefer Option A as the API contract baseline because it gives TMS a deterministic activation payload.
- Option B can be layered as policy-driven default resolution for missing request fields.
- The implementation must preserve the issue `#133` security boundary: upstream-system scoped credentials may provision matching TMS source tenants, but mismatched upstream systems or unauthorized tenants still fail closed.

## Implementation Progress

- 2026-05-27: Extended `ensure-tenant` request/response contract with activation metadata: `upstreamRef`, capability domain, namespace, agent/root agent, worker backend, physical worker, directory, and business worker URL.
- Added activation readiness fields to the provisioning response: `activationReady`, `missingFields`, `remediationHint`, `requiredScopes`, `actualScopes`, `authorizedTenantIds`, and target/upstream tenant identifiers.
- Added TMS default worker backend fallback to `LANGGRAPH_BIZ` while preserving explicit request overrides and model-grant-derived worker backend where available.
- Added upstream admin credential self-inspection API and CLI support through `GET /api/v1/upstream-admin/admin-credential/current` and `navi upstream admin-key inspect`.
- Updated SDK models, Java CLI output, and tenant profile writing for the expanded provisioning payload.
- Added a real local HTTP smoke / mock E2E regression in `business-agent-module/integration-tests/tests/02-upstream-tenant-client-app-provisioning.test.ts`. The test exercises bootstrap admin-key request/approve/claim, admin credential inspect, `ensure-tenant`, model grant creation, non-replayable credential reuse, and activation-ready credential rotation.
- 2026-05-27 follow-up from TMS tenant `110`: activation succeeded but `preflight` / `ask` failed with `RUNTIME_AGENT_RESOURCE - physical worker owner is not configured: dev-langgraph-worker-20260504123547`. `ensure-tenant` now validates the root agent runtime worker route before marking `activationReady=true`; owner/backend/status/health/visibility failures are returned as `errorCode=RUNTIME_AGENT_RESOURCE`, `missingFields` such as `physicalWorker.owner`, a blocker entry, and a remediation hint.
- The expected physical worker ownership is `PLATFORM/platform` for shared runtime infrastructure or `UPSTREAM_SYSTEM/<upstreamSystemId>` for upstream-system dedicated workers. It is not bound to the derived Navigator tenant such as `nav_tms_110`.
- The local HTTP smoke now registers an upstream-system owned BizWorker identity through `/api/v1/upstream-admin/worker-identities` before `ensure-tenant`, so activation-ready success requires a real runtime-visible physical worker identity instead of only a string-valued `physicalWorkerId`.
- 2026-05-27 follow-up from TMS tenant `110` on Navi `77e9b044`: Navi recognized the missing physical worker owner and logged the blocker, but the admin `ensure-tenant` response was overwritten by HTTP 500 because the caught runtime-resource exception marked the surrounding transaction rollback-only. `A2AgentResourceResolver.resolveRequiredAgent` now declares `noRollbackFor` for readiness/authorization exceptions that provisioning intentionally catches and converts into `activationReady=false` payloads.

## Test Record

| Time | Scope | Command | Result |
| --- | --- | --- | --- |
| 2026-05-27 17:42 +08:00 | Targeted Java unit tests | `mvn -pl business-agent-module,navigator-open-sdk -am "-Dtest=UpstreamTenantClientAppProvisioningServiceTest,BizWorkerControlPlaneAuthorizationTest,UpstreamCliTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` | PASS: 115 tests passed across provisioning service, control-plane auth, and upstream CLI suites. |
| 2026-05-27 17:47 +08:00 | Full Maven reactor unit tests | `mvn test` | PASS: 240 Surefire report files, 1650 tests, 0 failures, 0 errors, 0 skipped. |
| 2026-05-27 18:24 +08:00 | Integration test typecheck | `npm run typecheck` in `business-agent-module/integration-tests` | PASS: TypeScript integration client and tests compile. |
| 2026-05-27 18:27 +08:00 | Local launcher rebuild and restart | `.\start-launcher.ps1` | PASS: rebuilt `launcher/target/launcher-1.0.0-SNAPSHOT.jar`, restarted local Navigator on `http://localhost:8112`; `/actuator/health` returned `UP` with MySQL and Rabbit `UP`. |
| 2026-05-27 18:28 +08:00 | Real local HTTP smoke / mock E2E regression | `npx vitest run tests/02-upstream-tenant-client-app-provisioning.test.ts` in `business-agent-module/integration-tests` | PASS: 1 test passed. Covered admin key request/approve/claim, current admin credential inspect, first `ensure-tenant` activation payload with missing `modelConfigId`, ClientApp model grant creation, non-replayable repeated ensure, and rotate ensure returning `activationReady=true`. |
| 2026-05-27 18:31 +08:00 | Full Maven reactor unit tests | `mvn test` | PASS: 240 Surefire report files, 1650 tests, 0 failures, 0 errors, 0 skipped. |
| 2026-05-27 20:50 +08:00 | Runtime resource readiness regression | `mvn -pl business-agent-module -am "-Dtest=UpstreamTenantClientAppProvisioningServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` | PASS: 12 tests passed. Added coverage that physical worker owner missing returns `activationReady=false`, `errorCode=RUNTIME_AGENT_RESOURCE`, `missingFields=[physicalWorker.owner]`, and owner remediation. |
| 2026-05-27 20:51 +08:00 | Business Agent module full unit tests | `mvn -pl business-agent-module -am test` | PASS: 421 tests passed, 0 failures, 0 errors, 0 skipped. |
| 2026-05-27 20:54 +08:00 | Local launcher rebuild and restart | `.\start-launcher.ps1` | PASS: local Navigator restarted on `http://localhost:8112`; `/actuator/health` returned `UP` with MySQL and Rabbit `UP`. |
| 2026-05-27 20:55 +08:00 | Real local HTTP smoke / mock E2E regression | `npm run typecheck`; `npx vitest run tests/02-upstream-tenant-client-app-provisioning.test.ts` in `business-agent-module/integration-tests` | PASS: TypeScript compile passed; 1 HTTP smoke test passed. The smoke now creates a TMS upstream admin key with `WORKER_POOL_MANAGE`, registers an `UPSTREAM_SYSTEM/<sourceSystem>` BizWorker identity, then verifies rotated `ensure-tenant` returns `activationReady=true`. |
| 2026-05-27 20:58 +08:00 | Full Maven reactor unit tests | `mvn test` | PASS: 240 Surefire report files, 1651 tests, 0 failures, 0 errors, 0 skipped. |
| 2026-05-27 21:27 +08:00 | Targeted rollback-only regression tests | `mvn -pl business-agent-module -am "-Dtest=A2AgentResourceResolverTest,UpstreamTenantClientAppProvisioningServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` | PASS: 39 tests passed. Covered the activation-readiness payload path and verified `resolveRequiredAgent` does not mark caller transactions rollback-only for caught readiness exceptions. |
| 2026-05-27 21:29 +08:00 | Business Agent module full unit tests | `mvn -pl business-agent-module -am test` | PASS: 422 tests passed, 0 failures, 0 errors, 0 skipped. |
| 2026-05-27 21:30 +08:00 | Local launcher rebuild and restart | `.\start-launcher.ps1`; `Invoke-RestMethod http://localhost:8112/actuator/health` | PASS: local Navigator restarted on `http://localhost:8112`; `/actuator/health` returned `UP` with MySQL and Rabbit `UP`. |
| 2026-05-27 21:31 +08:00 | Real local HTTP smoke / mock E2E regression | `npm run typecheck`; `npx vitest run tests/02-upstream-tenant-client-app-provisioning.test.ts` in `business-agent-module/integration-tests` | PASS: TypeScript compile passed; 1 HTTP smoke test passed against `http://localhost:8112`. |
| 2026-05-27 21:34 +08:00 | Full Maven reactor unit tests | `mvn test` | PASS: 240 Surefire report files, 1652 tests, 0 failures, 0 errors, 0 skipped. |
