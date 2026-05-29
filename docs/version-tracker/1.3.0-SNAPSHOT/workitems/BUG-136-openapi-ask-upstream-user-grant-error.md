---
type: bug
bug_source: user-report
version: 1.3.0-SNAPSHOT
ticket: BUG-136
severity: major
status: ready-for-verification
reproduction_status: confirmed
test_strategy: unit-test
automation_decision: required
owner: openapi-backend
---

# BUG Work Item

## Background

GitHub issue #136 reports that `POST /api/v1/open/agents/{agentId}/ask` returned HTTP 500 when the upstream user grant was missing for a ClientApp-owned route. The route became successful after running `upstream ensure-grant`, so the actionable condition was missing `UPSTREAM_USER_GRANT`, not a platform failure.

## Reproduction

1. Use a valid ClientApp runtime credential and OpenAPI route.
2. Send an ask request with `X-Upstream-User-Id` for a user that has no enabled ClientApp upstream user grant.
3. Observe generic HTTP 500 from `/ask`.

## Expected vs Actual

Expected: OpenAPI ask rejects the request with an actionable sanitized 4xx Navigator error such as `Upstream user is not granted access to this Client App`.

Actual: `BusinessAgentTaskService.issueOpenApiTaskScopedToken` throws `IllegalStateException`, which escaped the OpenAPI controller and was handled as HTTP 500.

## Impact Scope

This affects operator diagnosis for OpenAPI ClientApp ask routes, especially BizWorker-backed routes that require task-scoped runtime tokens. It does not expose secrets, but it hides the grant remediation step behind a generic server error.

## Test Strategy

Add a controller-level unit test that simulates missing upstream user grant during task-scoped token issuance and verifies the request is rejected before task submission.

## Code Inventory

- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiController.java`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiControllerMessageMappingTest.java`

## Fix Checklist

- [x] Convert pre-submit runtime token validation failures to sanitized `RX.throwB` errors.
- [x] Ensure missing upstream user grant fails before `agent.sendTask`.
- [x] Run targeted controller tests.

## Verification

`mvn -pl addons/claude-worker-agent -am -Dtest=OpenApiControllerMessageMappingTest "-Dsurefire.failIfNoSpecifiedTests=false" test`

Result: pass, 29 tests.

## References

- https://github.com/foggy-projects/Foggy-Navigator/issues/136
