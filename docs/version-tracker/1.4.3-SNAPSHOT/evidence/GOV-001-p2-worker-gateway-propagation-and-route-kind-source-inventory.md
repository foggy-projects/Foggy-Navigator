---
doc_type: source-evidence-inventory
version: 1.4.3-SNAPSHOT
ticket: GOV-001-P2
status: SOURCE_OBSERVED_NO_RUNTIME_CLAIM
observed_at: 2026-07-19
scope: repository-source-only
decision_authority: none
---

# GOV-001 P2 Worker Gateway Propagation and Route-kind Source Inventory

## Boundary and Method

- This is a static source inventory for the blocked P2 decision gate. It does not approve implementation, change a gate, or claim deployed/runtime readiness.
- Only tracked source and version documentation were inspected. No profile, environment, account, credential, database, service, or upstream-system data was read.
- Header names and configuration variable names below are contract identifiers, not credential values.
- The searched client scope is: Java LangGraph addon, Python LangGraph Worker, Codex SDK Worker, and Codex app-server Worker source trees. An absence in this scope is not proof about sidecars, environment injection, deployed artifacts, or out-of-repository callers.

## Inbound Gateway Contract Observed in Source

The internal Gateway controller exposes four `/internal/worker-gateway/v1` endpoints. Each accepts `X-Task-Scoped-Token`; each can additionally receive the same three canonical Worker headers and the legacy Worker-ID header. See `WorkerGatewayController.java:11-88`.

| Input / setting | Source-observed behavior | Boundary implication |
| --- | --- | --- |
| `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED` | Defaults to `false` through `navigator.worker-gateway.external-enabled`; `WorkerGatewayProperties` describes it as strict Worker authentication on the internal Gateway. | This is a strict-header requirement, not a port exposure, Open API, Provider, or production-readiness switch. |
| Task token only, all three Worker headers absent | Allowed only while the strict property is false; the task token is still resolved and policy-checked. | Temporary internal-development compatibility only. |
| Strict property enabled, all Worker headers absent | Denied. | Do not enable until every actual caller can propagate the complete contract. |
| Any legacy Worker-ID header | Denied. | No legacy-header fallback. |
| Partial or blank canonical Worker header set | Denied. | No downgrade from a malformed strict attempt to task-token-only behavior. |
| Complete canonical header set | Credential is checked, then Worker ID and lease bind to the task token before pool/physical ownership checks. | A task capability is necessary but does not replace Worker principal or lease. |

Canonical Worker headers are `X-Navigator-Worker-Id`, `X-Navigator-Worker-Credential`, and `X-Navigator-Worker-Lease-Id`; the rejected legacy header is `X-Worker-Id`. See `WorkerGatewayRequestAuthorizationService.java:32-88`.

For a strict request, the token must carry tenant, ClientApp, upstream user, pool/route ID, Worker ID, and Worker lease. The header Worker ID must equal both the authenticated Worker and token Worker; the header lease must equal the token lease. An active ClientApp plus route-specific backend, membership, owner, and visibility checks are then required. See `WorkerGatewayRequestAuthorizationService.java:97-214`.

`NAVIGATOR_EXTERNAL_ENABLED` is configured separately under `navigator.external.enabled` and is not part of this Gateway authorization decision. `application.yml:95-104` is the source evidence for the separate defaults.

## Outbound Client Propagation Matrix

| Caller path | Task capability header | Canonical Worker headers in examined source | Static conclusion | Evidence still required before strict mode |
| --- | --- | --- | --- | --- |
| Java LangGraph addon: `addons/langgraph-biz-worker/.../WorkerGatewayClient.java` | Yes, on list/schema/invoke/tool-message calls. | No Worker ID, credential, or lease parameter/header appears in the client contract. | Source-level task-token-only caller; it cannot satisfy the observed strict contract as written. | Approved propagation design, secret-safe custody, request tests, and an end-to-end trace. |
| Python LangGraph Worker: `tools/langgraph-biz-worker/.../business_function_tools.py` | Yes. | Conditional. It sends all three only when local Worker ID plus local credential are both configured and trusted runtime context supplies a matching `worker_id` and nonempty `worker_lease_id`. | A known dispatcher wrapper covers list/schema/invoke/direct business-function paths (`runtime/llm_tool_dispatcher.py:181-335`), but static review does not prove trusted ingress, every dispatch, full call surface, or provisioning of the local pair. | Dispatch-to-tool trace, fixture tests for all routes, and deployment/custody evidence. |
| Codex SDK Worker business MCP: `tools/codex-agent-worker/.../navigator-business-mcp-server.ts` | Yes. | No. The runtime/environment allowlist and request construction contain gateway URL, task token, tool/task metadata, but no Worker ID, credential, or lease. | Source-level task-token-only caller. More importantly, `sdk-wrapper.ts` deliberately strips Worker/task environment from model-controlled Codex CLI/Shell/MCP children and throws `CODEX_NAVIGATOR_WORKER_CREDENTIAL_FORWARDING_UNREADY` rather than forwarding a long-lived Worker credential without an OS-isolated channel (`sdk-wrapper.ts:79-88,383-409,1380-1400`). | Approved isolated-custody/propagation design, redaction review, executed tests, and end-to-end trace. This is not repairable by creating a Worker, BizWorkerIdentity, or Pool member. |
| Codex app-server Worker: `tools/codex-app-server-worker/src` | No direct client call found in the searched source scope. | No direct canonical-header use found in the searched source scope. | No direct Worker Gateway client is evidenced here. This does not rule out a sidecar, injected process environment, generated artifact, or external call chain. | Call-chain inventory before any strict-readiness claim. |

Relevant source anchors:

- Java client task-token header only: `WorkerGatewayClient.java:13-101`.
- Python token header, runtime-context binding, and conditional header builder: `business_function_tools.py:53-62, 128-198`; local-pair validation is in `config.py:19-24, 94-109`.
- Codex SDK runtime inputs/environment and request header: `navigator-business-mcp-server.ts:118-125, 190-206, 396-403`.
- The app-server conclusion is based on scoped searches for `/internal/worker-gateway`, `X-Navigator-Worker-`, `X-Task-Scoped-Token`, and `navigator-business-mcp` under `tools/codex-app-server-worker/src`.

## Current Route-kind Semantics

There is no explicit `routeKind` / `route_kind` / `route-kind` field in the examined P2-relevant production source scopes. This is a scoped negative finding, not an assertion about every repository artifact.

The current path carries both pool and physical fields, then derives the internal route identifier:

- `BusinessAgentWorkerTaskLaunchRequest` has `workerPoolId`, pool owner values, `physicalWorkerId`, server-resolved `selectedWorkerId`, and a server-generated `workerLeaseId` (`BusinessAgentWorkerTaskLaunchRequest.java:22-30`).
- The resolver first treats `workerRef` as a tenant pool. Only when that lookup fails does it try a physical Worker runtime registry (`A2AgentResourceResolver.java:583-624`).
- Dispatch persists the resolved pool ID or physical Worker ID into the same task/token `workerPoolId` field, binds a selected Worker and generated lease to the token, and refuses a launcher result for a different Worker (`BusinessAgentTaskService.java:190-271, 646-671, 752-761`).
- Gateway strict validation repeats the same inference: a tenant-scoped pool lookup selects the pool path; otherwise a global-pool collision, `poolId != workerId`, or empty Worker backend is rejected before physical-owner validation (`WorkerGatewayRequestAuthorizationService.java:126-140`).

For the pool path, an enabled member and backend match are required, with PLATFORM visibility or same-upstream ownership rules. For the physical path, the authenticated Worker must be owned by the canonical PLATFORM owner or by the ClientApp's upstream system. See `WorkerGatewayRequestAuthorizationService.java:143-214`.

This is guarded inference, but it is not an explicit, independently versioned route-kind claim. The P2 decision gate must settle the authoritative physical/pool vocabulary, source of truth, collision migration, and rollback before a strict-mode rollout.

## No-claim Boundaries and P2 Consequences

- This inventory does not establish the active values of either external flag, actual traffic, real Worker credentials, lease issuance/custody, runtime header propagation, or client compatibility.
- It does not settle the P2 upstream-assertion issuer/audience/key-rotation model, replay/revocation/outage semantics, audit retention, or the P1B-B factual owner/tenant/ClientApp/Worker mapping required for exact binding. The Gateway matrix is only one P2 prerequisite.
- It does not authorize creation of a Worker, BizWorkerIdentity, or WorkerPool member. In particular, Codex remains an existing Physical Worker route concern; it must not be repaired by inventing a Biz identity or pool membership.
- It does not authorize `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED`, `NAVIGATOR_EXTERNAL_ENABLED`, Worker external exposure, Provider enablement, or production deployment.
- The safe migration order remains client/dispatch propagation and negative-contract evidence first; only after the full caller inventory and explicit route-kind decision can a controlled strict-mode evaluation be proposed.

## Reproducibility

The source inventory used scoped `rg` searches and line-numbered reads of the paths cited above. It intentionally omitted profile directories, credential stores, runtime endpoints, databases, and upstream repositories. A future implementation/signoff must add reproducible non-secret fixtures and live evidence separately; this document must not be treated as that evidence.

`tools/codex-agent-worker/tests/sdk-wrapper.test.ts:508-560` contains a relevant isolation test, but this static inventory did not execute it and makes no test-pass claim.
