---
type: bug
bug_source: user-report
version: 1.3.0-SNAPSHOT
ticket: BUG-developer-role-auth-401
severity: major
status: implementation-complete-verification-blocked
reproduction_status: partial
test_strategy: unit-test
automation_decision: required
owner: navigator-auth-business-agent
---

# BUG: Developer role receives 401 while maintaining workers/config model

## User Report

Navi developer-role users hit HTTP 401 on:

- `GET http://dev-kvm-jdk12.foggysource.com/api/v1/tasks`
- `GET http://dev-kvm-jdk12.foggysource.com/api/v1/tasks/page?page=0&size=100&state=AWAITING_REPLY`

The 401 causes developers to lose access to worker and configModel maintenance flows.

## Diagnosis

- `/api/v1/tasks` and `/api/v1/tasks/page` only require an authenticated user.
- The settings page also loads business worker pool data from `/api/v1/business-agent/worker-pools`.
- Worker pool control-plane endpoints were restricted to `TENANT_ADMIN`, even though worker and LLM configuration maintenance is a private logged-in user flow.
- Authorization failures were returned as HTTP 401 by the global security exception handler.
- The frontend clears login state on 401, so a role-denied request can make later task/config requests look unauthenticated.
- Existing worker/LLM configuration storage still uses `tenantId` as the scope key. If users in the same tenant must not share worker/LLM configuration, a follow-up owner-key migration to `userId` or `ownerUserId` is required.

## Expected Behavior

- Missing or invalid credentials return HTTP 401.
- Authenticated users without a required role return HTTP 403.
- Any authenticated user can maintain their own worker/LLM configuration resources without needing a platform role such as `TENANT_ADMIN` or `DEVELOPER`.
- Private resource queries must not expose another user's worker/LLM configuration.

## Fix

- Map permission-denied `SecurityException`s to HTTP 403 while keeping unauthenticated/invalid credential failures as HTTP 401.
- Remove role requirements from business worker pool list/create/member/status operations; keep only login requirement and current user context scoping.
- Keep worker identity registration restricted to `SUPER_ADMIN`.
- Leave owner-key migration out of this narrow 401 fix because it changes stored data visibility and may require migration/backfill.

## Verification

- Added unit tests for 401 versus 403 security exception mapping.
- Updated authorization reflection tests for business worker pool developer access.
- `mvn -pl user-auth-module test -Dtest=GlobalExceptionHandlerTest` passes.
- `mvn -pl business-agent-module test -Dtest=BizWorkerControlPlaneAuthorizationTest` is blocked by pre-existing compile errors outside this fix:
  - `BusinessAgentBundleService`/`BusinessAgentBundleDTO` reference `CodingAgentEntity.agentProfile` accessors that are absent in the current common entity.
