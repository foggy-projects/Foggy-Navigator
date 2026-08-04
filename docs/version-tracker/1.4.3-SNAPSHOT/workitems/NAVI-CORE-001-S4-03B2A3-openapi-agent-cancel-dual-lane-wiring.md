# NAVI-CORE-001 S4-03B2A3 — OpenAPI Agent Cancel Dual-lane Wiring

## Status

`ACCEPTED`

## Scope

Wire `POST /api/v1/open/agents/{agentId}/tasks/{taskId}/cancel` to the already accepted canonical termination cores without adding another receipt, outbox, Provider path, or compatibility fallback.

- Census every supported credential family before JWT/API-key or domain resolution.
- Route exact Navigator user credentials through the existing management role AOP and B2A2 adapter.
- Route exact ClientApp runtime-access credentials through the existing B2A1 adapter.
- Remove direct `A2aAgent.cancelTask`, Provider/Task reread, and fabricated terminal status.
- Keep the SDK's existing void descriptors while adding typed, request-ID-aware overloads.

## Frozen Boundaries

The implementation is limited to the 13 paths frozen in the coordination work item. Route manifest/provenance, CLI diagnostics, public capability truth, and Provider capability remain S4-03B2B. B2A1/B2A2 lifecycle, receipt, outbox, Provider, schema, POM, SIM, and TMS code are unchanged.

Historical and existing data are read-only. Validation uses mocks and process-local HTTP fixtures only; no repair, backfill, replay, reconcile, cleanup mutation, or live Provider is authorized for this slice.

## Acceptance

- Mixed, repeated, partial, malformed, and foreign credentials fail before any credential/domain resolver.
- Runtime requests never populate management identity or invoke the management role gate.
- Management requests retain `TENANT_ADMIN | DEVELOPER | SUPER_ADMIN` behavior and always traverse a real Spring AOP proxy.
- Accepted/replayed/reconcile-required results return `CANCEL_REQUESTED`; only canonical terminal facts return terminal status, with public `ABORTED -> CANCELLED` compatibility.
- SDK runtime cancel sends only explicit runtime headers and never configured default credentials or tenant context.
- Existing void SDK methods consume the object response successfully; typed overloads return `AgentTask` and use one canonical request ID.

## Validation Budget

Only affected production compile and exact selectors in:

- `OpenApiAgentCancelCredentialCensusTest`
- `AuthInterceptorTest`
- `OpenApiAgentTaskTerminationFacadeTest`
- `OpenApiControllerMessageMappingTest`
- `AgentApiCancellationTest`

Whole-module/reactor, database, E2E, live Provider, and final joint full validation are not run in B2A3. Final joint validation remains `0/3` until all seven stages and SIM-NAVI closure are frozen.

## Implementation Result

- The exact cancel route now performs a content-free credential census at the start of
  `AuthInterceptor`. Raw query `token` is parsed from `getQueryString()` rather than the Servlet
  parameter merge; form and multipart carriers, mixed/partial/foreign credentials, repeated or
  comma-folded headers, and conflicting server-owned route patterns fail before credential or
  domain resolution. Runtime and rejected requests clear stale management identity and invoke no
  JWT/API-key resolver. JSON request bodies remain compatible.
- The Controller is a thin delegate. Runtime access calls only B2A1; management calls only B2A2
  through an independent proxied role gate. Management authentication and role enforcement run
  before request-ID validation. The facade performs no direct Provider cancellation, Task reread,
  fallback, or terminal fabrication, and exposes only exact allowlisted safe codes plus the two
  fixed AuthAspect messages.
- Both legacy SDK `void` descriptors remain intact and consume the object response. Additive typed
  overloads return `AgentTask`, management emits exactly one Navigator credential family, and the
  runtime overload suppresses all configured default identity and tenant headers.
- Affected production compile passed for `user-auth-module` (12.39 s),
  `addons/claude-worker-agent` (20.68 s), and `navigator-open-sdk` (4.91 s).
- Final focused evidence is 110/110 with zero failures, errors, or skips: credential census 9,
  interceptor 16, termination facade 7, Controller mapping 72, and SDK cancellation 6. The final
  JSON-content-type census selector was rerun after its assertion was added and remained 9/9.
- Three independent revised-diff reviews returned `ACCEPT` with no P1/P2 findings. Two optional P3
  hardening ideas (a stricter public record constructor invariant and a real-body MockMvc JSON
  selector) were deliberately not promoted into another validation cycle because production
  factories are closed, JSON content type is already covered, and neither changes the go/no-go
  decision.
- Only the frozen 13 paths changed. No historical/existing data was read or mutated, no repair,
  backfill, replay, reconcile, cleanup mutation, database, E2E, live Provider, whole-module/reactor,
  or final joint full validation ran. `BOOT-INF/` was not read or touched. Final joint validation
  remains 0/3 until Stage 1–7 and SIM-NAVI closure are frozen.
