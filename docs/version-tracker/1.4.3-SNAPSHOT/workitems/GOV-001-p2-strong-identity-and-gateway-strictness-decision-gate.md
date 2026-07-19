---
doc_type: decision-gate
delivery_type: cross-module
version: 1.4.3-SNAPSHOT
ticket: GOV-001-P2
status: DRAFT
gate_state: BLOCKED
canonical: true
canonical_slice: p2-strong-identity-and-gateway-strictness
parent_delivery_spec: GOV-001-upstream-permission-and-trust-boundary.md
execution_mode: normal-analysis
implementation_authorization: none
source_inventory_status: completed-static-no-runtime-claim
approved_by: pending-security-architecture-and-worker-owners
approved_at: pending
open_questions:
  - signed upstream-user assertion trust model
  - complete Worker principal and lease propagation contract
  - explicit worker route-kind model and migration
  - replay, revocation and audit retention semantics
---

# Decision Gate: GOV-001 P2 Strong Upstream Identity and Worker Gateway Strictness

## Document Purpose

- intended_for: security architecture / Worker owners / upstream integration owners / future implementation / independent signoff
- purpose: 将 signed upstream-user identity 与 Worker Gateway strictness 的未决架构事实拆开冻结，避免把 task capability、ClientApp credential、Worker identity 和网络开关混成一个“external”动作。
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/GOV-001-p2-strong-identity-and-gateway-strictness-decision-gate.md`
- current_state: `DRAFT` + `BLOCKED`; it authorizes neither code nor `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED`.

## Confirmed Non-Negotiable Boundaries

| Boundary | Current fact / required invariant |
|---|---|
| Open API gate | `NAVIGATOR_EXTERNAL_ENABLED` only gates `/api/v1/open/**`; it is not Gateway, Provider, Worker or production readiness. |
| Gateway flag | `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED` means complete Worker-principal headers are mandatory. It does not open a port, configure TLS/Ingress, or create a Worker identity. |
| Current compatibility | `WorkerGatewayRequestAuthorizationService` permits token-only only when strict=false and all three principal headers are absent; any partial, blank or legacy header is denied. strict=true denies headerless access. |
| Current client gap | Java LangGraph and Codex SDK business-MCP source paths currently send task token only. Python LangGraph has a conditional full-header builder only when a local Worker pair and trusted runtime lease are both present; the full client-to-server propagation matrix is not proven. No direct app-server Gateway client was found in the scoped source review. |
| Capability intersection | task token cannot replace exact Worker principal/lease, and Worker identity cannot enlarge task/user/Agent policy. |
| Codex topology | Existing Physical Worker remains the only permitted Codex route. No new Worker, BizWorkerIdentity or Pool member may be created to satisfy strict Gateway routing. |

## Required Architecture Decisions

| Decision | Must be fixed before implementation | Decision owner / required evidence |
|---|---|---|
| Upstream-user assertion | issuer(s), audience, subject/client binding, signing/JWKS discovery, key rotation, expiry/skew and invalid-claim reason codes | security architecture + upstream owner; threat model and testable contract |
| Replay and revocation | nonce/jti storage, replay window, revoke propagation, cache fail-closed behavior and token/credential generation interaction | security + platform operations; outage/recovery semantics |
| Worker principal model | exact physical/Biz identity representation, credential custody, lease authority, owner/backend/tenant/App binding and how a physical route is distinguished | Worker architecture owner; no implicit Biz identity assumption |
| `routeKind` migration | explicit vocabulary for physical versus pool route, source-of-truth claims, migration/rollback and collision handling for existing overloaded `poolId`/`workerId` | data + Worker owner; migration design and compatibility matrix |
| Client propagation | each Java, LangGraph, Codex SDK and Codex app-server call path must provide task token plus exact principal headers/lease without log or subprocess leakage | every client owner; end-to-end trace and negative tests |
| Audit boundary | decision record fields, correlation across upstream assertion/task/Worker route, redaction and retention handoff to P3 | security + audit owner; no secret-bearing trace |

## Explicit Non-Goals

- Do not enable strict Gateway mode, change compatibility behavior, issue signed user assertions, accept third-party identities, or modify real client/Worker configuration from this DRAFT.
- Do not infer strong upstream-user identity from `client-app-delegated`, request `userId`, tenant, owner, cwd or trust profile.
- Do not use a header fixture, local URL, test token or documentation change as evidence that a client propagation chain is complete.
- Do not enable Worker external mode, Open API external mode or production; those are separate P3 gates.

## Approval-to-Implementation Exit Criteria

- [ ] The signed assertion and replay/revocation design is threat-modelled, has named key custody and fail-closed outage semantics.
- [ ] One authoritative Worker principal/lease schema and explicit `routeKind` migration/rollback plan are approved; pool and physical routes cannot be inferred from colliding IDs.
- [ ] Every Gateway caller has a reviewed propagation design, secret-redaction contract and migration order; headerless compatibility has an explicit trusted-internal expiry/telemetry plan.
- [ ] P1B-B factual owner/tenant/ClientApp/Worker mapping is approved where needed for exact binding validation.
- [ ] A future implementation spec scopes client-first propagation, server validation, test fixtures, audit evidence and rollback. Only after all clients pass may the strict flag be considered in a controlled environment.

## Minimum Acceptance Matrix for a Future Implementation

| Scenario | Expected result |
|---|---|
| strict=false, all Worker headers absent, valid task token on trusted internal path | temporary compatibility only; auditable, never external proof |
| strict=true, complete exact task token + worker ID + credential + lease | allow only if routeKind, owner, backend, tenant/App and lease all bind |
| any missing, blank, partial or legacy Worker header | deny; no token-only fallback |
| worker/lease/task route, tenant/App, owner or backend mismatch | deny |
| invalid issuer/audience/expiry, replay, revoked assertion/key or unavailable verifier | deny |
| cross tenant/App/upstream user request or self-declared scope | deny |
| existing Codex Physical Worker strict route | allow only through existing worker-host/`claudeCode.codexConfig` path; test proves no new Worker/Biz identity/Pool member |

## Risks and Open Questions

- known_risks: enabling strict mode before all clients propagate principal/lease headers will cause runtime failures. Treating Biz worker credentials as a universal identity would misroute Codex Physical Workers. A signed assertion without replay/revocation policy is not a production identity boundary.
- open_questions:
  - Which standard assertion format and issuer trust root will S1/S2 use, and what is the minimum S3 extension point?
  - Which durable store can safely support replay/revocation at the required availability?
  - What are the exact client inventory, traffic telemetry and compatibility sunset criteria?
  - How will `routeKind` be migrated without changing owner semantics or breaking existing internal tasks?

## Future Execution Contract

- A future `APPROVED` slice must implement client-first, keep headerless compatibility internal-only until its retirement criteria are met, and prove partial-header denial.
- It must preserve the existing Codex Physical Worker path and must not alter P3 production/external controls.
- Any design that makes task token, ClientApp credential or root/control authority substitute for Worker principal/lease is `NEEDS_REPLAN`.

## References

- parent: [GOV-001 trust boundary](./GOV-001-upstream-permission-and-trust-boundary.md)
- server evidence: `launcher/src/main/resources/application.yml`; `business-agent-module/src/main/java/com/foggy/navigator/business/agent/config/WorkerGatewayProperties.java`; `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/WorkerGatewayRequestAuthorizationService.java`
- client evidence: `addons/langgraph-biz-worker/.../WorkerGatewayClient.java`; `tools/langgraph-biz-worker/.../business_function_tools.py`; `tools/codex-agent-worker/.../navigator-business-mcp-server.ts`
- static propagation and route-kind inventory: [GOV-001 P2 Worker Gateway Propagation and Route-kind Source Inventory](../evidence/GOV-001-p2-worker-gateway-propagation-and-route-kind-source-inventory.md)
