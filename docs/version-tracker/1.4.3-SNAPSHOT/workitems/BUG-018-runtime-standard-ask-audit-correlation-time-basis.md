---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-018
status: ULTRA_EXECUTING
canonical: true
execution_mode: ultra
bug_source: user-report
approved_by: project-owner-explicit-implementation-request
approved_at: 2026-07-25
open_questions: []
---

# Delivery Spec: STANDARD ask request audit correlation and time basis

## Document Purpose

- intended_for: ultra-implementation / independent-signoff
- purpose: Close the runtime-only STANDARD ask request-audit and correlation gap for Java/runtime-profile callers, and freeze an explicit UTC/Asia-Shanghai/task-ID time contract.
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-018-runtime-standard-ask-audit-correlation-time-basis.md`

## Goal

- version_goal: Make every STANDARD ask request independently auditable before task creation, across runtime-token exchange, admission, dispatch, terminal state, and task-token revocation, without taskId or privileged credentials.
- target_outcome: Java/runtime-profile callers generate a stable clientRequestId before their first network request, correlate runtime-token and STANDARD ask unambiguously, and query complete sanitized evidence by request ID or a bounded time window; Navigator also exposes a tested, non-ambiguous time basis and terminal visibility contract.

## Scope

- in_scope:
  - Java SDK/application-path client request correlation for runtime-token plus STANDARD ask.
  - Server-side request-audit creation before authentication/admission failure points that are reachable with an identifiable ClientApp scope.
  - STANDARD ask audit lifecycle through admission, task creation, task-token issuance, Worker/model dispatch, terminal result or explicit closure, and token revocation.
  - Request-ID and at-most-15-minute time-window queries without taskId.
  - Complete sanitized fields, explicit occurred/not-occurred stages, and query-side-effect proof.
  - UTC storage/RFC-3339 output, Asia/Shanghai task-ID date semantics, readiness time fields, and cross-midnight tests.
  - Terminal state visibility and idempotent consumer guidance for task-status/audit APIs.
  - Server, SDK, CLI, release manifest, migrations, runbook, tests, provenance, clean packaging, and deployment.
- affected_modules:
  - `business-agent-module`
  - `addons/claude-worker-agent`
  - `navigator-open-sdk`
  - `navigator-common`
  - `launcher`
  - `tools/navigator-upstream-cli`
  - `tools/navigator-upstream`
  - `docs/version-tracker/1.4.3-SNAPSHOT`
- external_dependencies: none; validation uses Navigator-owned sanitized fixtures only.

## Non-Goals

- out_of_scope:
  - Creating a new SIM ask/safe-ask/STANDARD request.
  - Reprocessing, retrying, resuming, recovering, terminating, reconciling, or redispatching historical task `20260725-543a`.
  - Modifying SIM business state, durable files, provisioning, Agent/model/Directory/Worker binding, grants, or Worker identity.
  - Reading SIM/TMS credentials, profiles, prompts, responses, workspace paths, ActorHome, browser/account data, or business data.
  - Retrofactively synthesizing or hand-editing historical request/task audit evidence.
- do_not_touch:
  - `.navigator/` and all credential/profile contents.
  - Existing unrelated dirty worktree paths.
  - Sibling workspaces.

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| Generate correlation in the Java SDK before network I/O | The application path must not rely on callers remembering an optional header. | Existing explicit-ID overloads remain supported; generated IDs are non-secret UUIDs. |
| Use one logical correlation chain with explicit request/parent fields | Runtime-token and ask are separate HTTP requests but one STANDARD operation. | Exact ask lookup is unambiguous; a runtime-token child can be queried independently and linked to the ask. |
| Persist request audit before task creation and retain nullable taskId | Pre-admission failures are first-class terminal request outcomes. | No task/context/session is created solely for audit. |
| Store timestamps as `Instant`/UTC and render RFC 3339 | Offset-equivalent windows must return identical records. | API timestamps use `Z`; CLI accepts RFC-3339 offsets and normalizes to instants. |
| Define task-ID date in `Asia/Shanghai` | Existing task IDs are date-prefixed in the deployment’s operational timezone. | The task-ID prefix and `createdAt` must share the same natural date in that zone. |
| Audit query evidence is structurally separate from task facts | The queried task’s historical dispatch must not be confused with query effects. | All query-side-effect fields are explicit false booleans. |
| Do not create retrospective evidence for the frozen historical request | Audit truth must come from actual durable events. | The historical task may only be inspected through existing read-only state if needed for contract analysis; no runtime call is allowed. |

## Acceptance Criteria

- [ ] AC-1: The Java/runtime-profile STANDARD application path creates and exposes a UUID clientRequestId before its first network request.
- [ ] AC-2: Runtime-token and STANDARD ask use the same clientRequestId or explicit parent/correlation identifiers with a documented one-to-one chain and exchange count.
- [ ] AC-3: A successful STANDARD ask is queryable by exact request ID and by equivalent bounded UTC or offset windows without taskId.
- [ ] AC-4: A request failing before task creation remains terminal and queryable by request ID with `taskId=null` and explicit `NOT_CREATED`/`NOT_ISSUED`/`NOT_DISPATCHED` stages.
- [ ] AC-5: Empty request-scoped tools/functions are persisted at admission and remain visible after terminal/closure state.
- [ ] AC-6: Audit output contains every owner-required field, complete sanitized stages, `taskFacts`, and `auditSideEffects`.
- [ ] AC-7: Audit queries issue no token, create no task/context/session, trigger no retry/recovery/reconcile/dispatch, call no Worker/model/BusinessFunction, and change no provisioning resource.
- [ ] AC-8: Terminal task state and task-token revocation update the correlated STANDARD request audit without redispatch or synthetic Worker evidence.
- [ ] AC-9: Runtime readiness/actuator evidence exposes serverTime, serverTimezone, auditStorageTimezone, and taskIdDateTimezone.
- [ ] AC-10: RFC-3339 UTC and Asia/Shanghai cross-date conversions and task-ID-date/createdAt consistency are covered by automated tests.
- [ ] AC-11: Existing task-status read API exposes terminal state after reconciliation; terminal-event behavior, visibility delay, polling endpoint, and idempotent consumption are documented and tested at the Navigator boundary.
- [ ] AC-12: No prompt, response, token, credential, authorization/header content, raw HTTP body, workspace path, or business data is stored or returned.
- [ ] AC-13: Manifest/help advertise the new STANDARD request-audit, correlation, no-task-ID/no-side-effect, and time-basis capabilities.
- [ ] AC-14: A clean source-matched server and CLI release is built, provenance and SHA-256 values are recorded, migration/ddl-auto validation passes, and the deployed health endpoint is UP.
- [ ] AC-15: No historical task or SIM provisioning/resource was modified during implementation or validation.

## Contract / Data / Security Constraints

- API or event contract:
  - Correlation headers/fields are UUID-based, non-secret, and observability/idempotency identifiers only.
  - Exact lookup accepts requestId plus optional operation filters.
  - Window lookup accepts `since`, `until`, operation, agentCode, upstreamUserId, and limit; maximum duration is 15 minutes.
  - `taskId` is nullable and never required for request audit.
  - STANDARD stages explicitly represent issued/not-issued, created/not-created, dispatched/not-dispatched, terminal/non-terminal, and revoked/not-revoked facts.
- data and migration:
  - Extend the existing sanitized runtime request audit schema; production `ddl-auto=validate` receives forward migration and rollback documentation.
  - Do not persist secrets, tokens, headers, raw bodies, prompts, responses, provider payloads, paths, or stacks.
- compatibility and rollback:
  - Existing explicit correlation overloads and CLI commands remain source-compatible.
  - No implicit retry, resume, recovery, termination, reconciliation, or redispatch is added.
- permissions and secrets:
  - Runtime self-audit continues to use only the same ClientApp runtime credential lane and server-derived owner scope.
  - No system-admin, control, platform, or management credential is accepted or required for runtime audit.

## Test and Evidence Obligations

| Required case | Validation |
|---|---|
| successful STANDARD ask by request ID | service/controller/SDK integration fixture |
| successful STANDARD ask by short window | repository/controller fixture with equivalent offsets |
| Java/runtime-profile application path audit | SDK loopback HTTP contract test |
| runtime-token/ask correlation | SDK and service correlation assertions |
| empty tools/functions persistence | admission + terminal lifecycle test |
| pre-task failure by request ID | controller failure fixture |
| null taskId query | service/controller test |
| terminal token revocation | lifecycle refresh test |
| audit zero side effects | endpoint and DTO contract assertions |
| UTC/Asia-Shanghai cross-midnight | deterministic clock/time-basis unit tests |
| taskId date and createdAt alignment | task-ID generator test |
| terminate/reconcile terminal visibility | task-status/read-model test without mutation of historical data |
| forbidden sensitive material | focused serialization/source scan |

Validation order is focused red/green tests, affected Maven modules, launcher package, CLI package/install smoke, migration validation, and deployment health. No live SIM runtime operation is authorized.

## Bug Context

- bug_source: user-report
- severity: critical acceptance blocker
- environment: clean Navigator/CLI 1.0.31 at commit `6637b6202a1ee17ce8a53bf71aebf161b597a225`.
- current_behavior: a real Java/runtime-profile STANDARD task has durable task/dispatch evidence but equivalent `operation=ask` request-audit windows return count zero.
- expected_behavior: the request chain is queryable independently from taskId and remains complete through terminal token revocation.
- reproduction_status: confirmed by owner evidence and current source inspection; the SDK’s common overload omits correlation and the server begins ask audit only after runtime authentication when the header is present.
- regression_protection: required

## Risks and Open Questions

- known_risks:
  - A request that never reaches Navigator cannot create server-side evidence; the SDK-generated ID remains the authoritative local correlation for such transport failures.
  - Existing historical STANDARD requests cannot be reconstructed without fabricating evidence and remain outside this delivery.
  - Terminal audit refresh must be attached to authoritative lifecycle changes without turning request audit into a dispatcher.
- open_questions: none

## Ultra Execution Contract

- Read this work item and repository/module guidance before implementation.
- Preserve all existing dirty paths not owned by BUG-018.
- Add failing focused tests before or alongside each deterministic fix.
- If implementation would require broader credentials, SIM mutation, historical data repair, or a new runtime dispatch, set `NEEDS_REPLAN` and stop that expansion.
- Record changed paths, exact test/build/migration/deployment results, deviations, residual risks, and release provenance below.
- Implementation may finish at `READY_FOR_SIGNOFF`; only an independent signoff step may assign acceptance.

## Implementation Result

- implementation_summary:
  - The Java SDK now creates UUID request identifiers before runtime-token and STANDARD ask HTTP calls. The runtime-token request is the correlation root; each ask has its own `clientRequestId` and an explicit `parentClientRequestId`/`correlationId`.
  - The Open API ask path creates a sanitized request-audit row before runtime access-token authentication and records authentication, admission scope, task/token creation, runtime/model dispatch, terminal state, and token revocation without creating a parallel task or dispatch path.
  - Exact request lookup and at-most-15-minute window lookup are task-ID independent and owner-scoped. A failure before task creation remains queryable with `taskId=null`.
  - Request facts and query effects are separated into `taskFacts` and `auditSideEffects`; every query-effect field is explicitly false.
  - Request audit timestamps use `Instant`, JDBC timestamp normalization is pinned to UTC, and CLI/API request-audit timestamps are RFC-3339 `Z` values. Task IDs use `Asia/Shanghai`; task-audit `createdAt`/`completedAt` and stage times are emitted as RFC-3339 `+08:00` values.
  - Readiness now returns `serverTime`, `serverTimezone`, `auditStorageTimezone=UTC`, and `taskIdDateTimezone=Asia/Shanghai`.
  - Durable terminal state overrides stale Worker `WORKING` observations in the read-only task-status projection.
- changed_paths:
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiController.java`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/model/dto/`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/OpenApiAgentReadinessService.java`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/RuntimeStateAuditService.java`
  - `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/`
  - `business-agent-module/src/main/java/com/foggy/navigator/business/agent/event/BusinessTaskScopedTokenTerminalListener.java`
  - `business-agent-module/src/main/java/com/foggy/navigator/business/agent/model/{dto,entity}/`
  - `business-agent-module/src/main/java/com/foggy/navigator/business/agent/repository/RuntimeRequestAuditRepository.java`
  - `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/{ClientAppRuntimeCredentialResolver,RuntimeRequestAuditService}.java`
  - `business-agent-module/src/test/java/com/foggy/navigator/business/agent/{event,service}/`
  - `navigator-common/src/main/java/com/foggy/navigator/common/util/IdGenerator.java`
  - `navigator-common/src/test/java/com/foggy/navigator/common/util/IdGeneratorTest.java`
  - `launcher/src/main/resources/application.yml`
  - `launcher/src/test/java/com/foggy/navigator/launcher/RuntimeTimeBasisConfigurationTest.java`
  - `navigator-open-sdk/pom.xml`
  - `navigator-open-sdk/src/main/`
  - `navigator-open-sdk/src/test/`
  - `tools/navigator-upstream-cli/dist/package.{sh,ps1}`
  - `docs/migration/2026-07-25-standard-ask-request-audit-correlation{,-rollback}.sql`
- tests_and_results:
  - `mvn -pl launcher -am -Dtest=RuntimeTimeBasisConfigurationTest,IdGeneratorTest,RuntimeRequestAuditServiceTest,BusinessTaskScopedTokenTerminalListenerTest,OpenApiControllerMessageMappingTest,OpenApiAgentReadinessServiceTest,RuntimeStateAuditServiceTest,RuntimeTaskClosureServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`
    - PASS on 2026-07-25: Common 2, Business 32, Claude Worker/OpenAPI 93, Launcher 1; zero failures/errors/skips.
  - `mvn -f navigator-open-sdk/pom.xml -Dtest=BusinessAgentApiSmokeTest,UpstreamCliTest test`
    - PASS on 2026-07-25: 182 tests, zero failures/errors/skips.
- manual_or_experience_evidence:
  - Navigator MySQL schema inspection showed the BUG-018 columns absent before migration.
  - `docs/migration/2026-07-25-standard-ask-request-audit-correlation.sql` applied successfully on 2026-07-25.
  - Post-migration `INFORMATION_SCHEMA` inspection confirmed all eight added columns; `runtime_request_audit.correlation_id` is NOT NULL.
- deviations:
  - Runtime-token and STANDARD ask use an explicit parent/child chain instead of reusing one request ID across two HTTP requests. This is within AC-2 and avoids ambiguous reuse when one runtime token serves more than one ask.
  - No historical missing STANDARD ask audit was synthesized. Existing rows received only the structural `correlation_id=client_request_id` migration backfill.
- residual_risks:
  - A transport failure before a request reaches Navigator has only the SDK-side generated ID; no server can persist a request it never receives.
  - SSE terminal delivery is asynchronous and best-effort. Consumers must treat the read-only task-status API as authoritative and poll idempotently when an event is absent.
  - Existing historical asks that did not create request-audit rows remain unqueryable by design; repairing them would fabricate evidence.
- readiness: ULTRA_EXECUTING

## Frozen Runtime Contracts

### Correlation

- Runtime-token request:
  - `clientRequestId=<runtime-token-request-id>`
  - `correlationId=<runtime-token-request-id>`
- STANDARD ask:
  - `clientRequestId=<ask-request-id>`
  - `parentClientRequestId=<runtime-token-request-id>`
  - `correlationId=<runtime-token-request-id>`
- The SDK creates both UUIDs before constructing/sending their respective HTTP requests.
- `runtimeTokenExchangeCount` is copied from the validated parent evidence; a missing, cross-owner, or non-issued parent fails closed.

### Time basis

- `serverTime`: current server instant, RFC 3339.
- `serverTimezone`: the JVM/server IANA timezone reported by readiness.
- `auditStorageTimezone`: fixed `UTC`; request audit uses `Instant` and Hibernate JDBC normalization is fixed to UTC.
- Request-audit `receivedAt` and `completedAt`: RFC-3339 UTC (`Z`).
- `taskIdDateTimezone`: fixed `Asia/Shanghai`.
- Task-audit `createdAt`, `completedAt`, and stage `occurredAt`: RFC-3339 with `+08:00`.
- A task ID date prefix is generated from the same `Asia/Shanghai` natural date as task creation. The deterministic boundary test proves that `2026-07-24T16:00:00Z` equals `2026-07-25T00:00:00+08:00` and produces a `20260725-*` task ID.
- Therefore `2026-07-25T00:xx:xx+08:00` and `2026-07-24T16:xx:xxZ` are the same instant, not future-dated evidence.

### Terminal visibility

- Authoritative read endpoint: `GET /api/v1/open/agents/{agentId}/tasks/{taskId}`. The existing messages endpoint also reflects durable terminal state.
- After a termination/reconciliation transaction commits, the durable task projection is visible to read-only callers; durable `ABORTED` maps to external `CANCELLED`.
- Navigator publishes an asynchronous `task_status_change` SSE notification after the durable transition, but consumers must not rely on exactly-once event delivery.
- Recommended consumer behavior: poll the task endpoint every 2 seconds, treat terminal state as monotonic, and deduplicate by `(taskId,status)`. Allow up to 10 seconds for asynchronous event/projection observation before diagnosing delayed visibility; this is operational guidance, not a hard server SLA.

## References

- predecessor: `BUG-017-runtime-request-audit-no-task-id.md`
- runtime closure: `FEAT-002-runtime-standard-task-termination-reconciliation.md`
- owner request: 2026-07-25
