---
title: Stage 8B Outbound REST Adapter Acceptance
date: 2026-05-04
status: accepted
---

# Stage 8B Acceptance Record

## Context
Stage 8B introduces the `RestBusinessFunctionAdapterInvoker` to safely execute HTTP REST calls for business functions, completing the outbound execution loop with strict security, SSRF prevention, and non-2xx fail-closed constraints.

## Verification Steps Completed

1. **Adapter Implementations & Routing**
   - Implemented `RestBusinessFunctionAdapterInvoker` and `CompositeBusinessFunctionAdapterInvoker`.
   - Updated `BusinessFunctionAdapterInvoker` to include `supports(String type)` for dynamic routing based on `adapterConfigJson.type`.
   - Modified `LocalEchoBusinessFunctionAdapterInvoker` and removed `@Component` to be manually registered in the `CompositeBusinessFunctionAdapterInvoker` via `BusinessAgentAutoConfiguration`.

2. **REST Adapter Capabilities**
   - Uses Spring `RestTemplate` to make requests.
   - Extracts `upstream_ref`, `path`, `method` from configuration.
   - Designed a `SimpleJsonPathEvaluator` that maps parameters using dot-notation string literals (e.g., `$.input.order_id`, `$.context.client_app_id`) evaluated against a combined Jackson `JsonNode`.
   - Dynamically resolves `path_params`, `headers`, and `body` payload safely.

3. **Security: SSRF Mitigation**
   - The REST Adapter explicitly denies calling arbitrary URLs.
   - `upstream_ref` (e.g., `tms-order-service`) MUST map to an environment property `foggy.navigator.business.agent.upstreams.{upstream_ref}.url`.
   - If the property is missing or blank, the adapter fails-closed throwing an `IllegalArgumentException` with "Unauthorized or unconfigured upstream_ref".
   - The resolved upstream URL must use `http` or `https`, must include a host, and must not contain URL user-info.
   - Adapter `path` is mandatory and must be an absolute path, not a URL or protocol-relative target.
   - Adapter `method` is mandatory and restricted to `GET`, `POST`, `PUT`, `PATCH`, and `DELETE`.
   - `Host`, `Content-Length`, `Transfer-Encoding`, `Connection`, `Authorization`, and `Proxy-Authorization` headers are blocked.
   - `GET` and `DELETE` requests do not send a request body even if a body mapping is present.

4. **Security: Non-2xx Fail-Closed**
   - Catches `HttpStatusCodeException` which handles all 4xx and 5xx responses.
   - Also rejects returned non-2xx `ResponseEntity` statuses such as 3xx redirects.
   - Rethrows as `IllegalArgumentException` carrying the HTTP status and body where available. This prevents the system from misinterpreting a failed remote call as a successful completion.

## Test Execution

```powershell
mvn test -pl business-agent-module -am
mvn compile -pl launcher -am -DskipTests
```
**Results:** All tests passed (business-agent-module: 160 tests, agent-framework: 213 tests). Launcher compiled successfully. Tests explicitly cover missing upstream refs, missing path/method, unsupported methods, invalid base URLs, absolute URL paths, forbidden headers, GET body suppression, non-2xx failures, and successful mappings.

## Remaining Risks / Future Work

1. **Advanced JSON Pathing**: Currently `SimpleJsonPathEvaluator` strictly supports only dot-notation mapping. Array filtering or advanced extraction requires upgrading to Jayway JsonPath or similar if required by future adapters.
2. **DNS Rebinding / Runtime IP Validation**: Stage 8B uses property-driven upstream allowlisting and URL shape validation. It does not yet perform DNS resolution pinning or runtime IP range checks.
3. **Credential Management**: Direct `Authorization` headers are intentionally blocked; a dedicated credential injection model is still future work.
4. **Persistent Audit**: Adapter results and HTTP traces are only emitted as debug/info logs. Persistent database storage for business function invocations is not implemented in this stage.

## Final Decision

**Accepted.** The Outbound REST adapter safely routes payloads while strictly mitigating SSRF risks via property-driven allowlisting. All tests pass, ensuring fail-closed stability.
