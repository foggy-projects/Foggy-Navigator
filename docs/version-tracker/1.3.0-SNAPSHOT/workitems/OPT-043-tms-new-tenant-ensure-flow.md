---
type: optimization
version: 1.3.0-SNAPSHOT
ticket: OPT-043
severity: major
status: proposed
source: pending GitHub issue
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
