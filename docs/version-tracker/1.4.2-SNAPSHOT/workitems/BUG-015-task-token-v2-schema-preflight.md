---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.2-SNAPSHOT
ticket: BUG-015
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
approved_by: user
approved_at: 2026-07-17
open_questions: []
---

# Delivery Spec: Task-scoped token v2 schema preflight

## Document Purpose

- intended_for: implementation / independent-signoff
- purpose: Resolve GitHub Issue #152: prevent an internally ready Open API task from failing during task-scoped token issuance because the active Navigator database lacks the token-v2 schema.
- canonical_path: `docs/version-tracker/1.4.2-SNAPSHOT/workitems/BUG-015-task-token-v2-schema-preflight.md`

## Goal

- version_goal: Keep the internal Codex direct-Physical-Worker path fail-closed and diagnosable before any task dispatch.
- target_outcome: The documented idempotent migration supplies every v2 token column and declared index, and a Navigator startup preflight stops an incompatible schema from being reported ready for Open API task submission.

## Scope

- in_scope: `business_task_scoped_token` migration completeness; startup schema preflight of every entity column and index; focused regression tests; local internal-dev database migration and post-restart health/schema evidence; this work item.
- affected_modules: `business-agent-module`, `docs/migration`, and `docs/version-tracker/1.4.2-SNAPSHOT`.
- external_dependencies: the local Navigator MySQL instance only; no upstream resource mutation.

## Non-Goals

- out_of_scope: resubmitting the failed ask; issuing a task token; creating or replacing a Worker; BizWorkerIdentity onboarding; WorkerPool membership; Directory binding changes; external or production enablement; TMS or ActorHome access.
- do_not_touch: unrelated dirty worktree changes, credentials, existing data values, and upstream SIM workspace resources.

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| Reuse and complete the existing manual v2 SQL migration. | The repository already defines an idempotent, MySQL 8.0/8.4-compatible migration and current data must be preserved. | Add only missing idempotent index guarantees, including a single-column unique `token_id` index when absent; do not log token rows or values. |
| Validate the table at Navigator startup through JDBC metadata. | A missing schema is an application deployment incompatibility, not a request-level business failure. | Startup must fail clearly before health/readiness can claim success; it must not auto-mutate schema. |
| Validate every `BusinessTaskScopedTokenEntity` column and index. | The incident exposed one missing column, while the entity also declares task-lookup indexes and a unique token identifier. | Keep the list explicit and regression-tested; no broad database inventory scan. |
| Apply the migration only to the verified local 8112 Navigator database. | The user authorized fixing this current environment and prior evidence identifies it as internal development. | Verify process/workspace ownership first; leave external/production disabled. |

## Acceptance Criteria

- [x] AC-1: `docs/migration/2026-07-14-business-task-token-v2.sql` is safely rerunnable and ensures every `BusinessTaskScopedTokenEntity` migration-owned column plus `idx_biz_token_task`, `idx_biz_token_tenant_worker_task`, and a single-column unique `token_id` index.
- [x] AC-2: An incompatible `business_task_scoped_token` schema makes Navigator startup fail with a clear migration reference before Open API asks can be accepted as ready.
- [x] AC-3: A compatible schema passes the same preflight without reading or emitting token data.
- [x] AC-4: Focused automated tests cover the missing-column, missing-index, missing unique-token index, and compatible-schema outcomes.
- [x] AC-5: The local 8112 database passes a metadata-only schema verification after the idempotent migration, and the restarted local Navigator reports health `UP` with external and Worker Gateway external modes disabled.
- [x] AC-6: No live ask is submitted and no Worker, identity, Pool, Directory, production, TMS, or ActorHome resource is changed. The pre-existing local external flag was corrected to disabled before final handoff.

## Contract / Data / Security Constraints

- API or event contract: no new endpoint or request field.
- data and migration: additive columns and indexes only; preserve existing rows; legacy rows retain the existing v1 fail-closed semantics.
- compatibility and rollback: the SQL remains idempotent. Rolling back application code does not require data rollback; removing indexes is not part of this change.
- permissions and secrets: use local ignored runtime configuration only; do not write or disclose passwords, tokens, request headers, or token rows.

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| Schema preflight | critical | focused unit tests for missing column/index and compatible metadata | Maven result with test count |
| Migration completeness | critical | metadata-only MySQL queries before/after the idempotent SQL | column/index names and no row values |
| Runtime fail-closed | critical | rebuild/restart the verified local 8112 process and request health | process ownership, restart result, health response |
| Boundary preservation | major | inspect local enabled flags and do not call ask endpoints | recorded false flags and no ask execution |
| Patch hygiene | low | `git diff --check` | passing command result |

## Bug Context

- bug_source: GitHub Issue #152
- severity: major
- environment: local internal development Navigator on port 8112, build reported as `1.0.0-SNAPSHOT` from the dirty `main` worktree on 2026-07-17.
- current_behavior: task preflight can pass, then Open API task creation fails with MySQL SQLState `42S22` when Hibernate inserts `issued_at` into a historical `business_task_scoped_token` table.
- expected_behavior: schema incompatibility is corrected through the documented migration and is rejected at startup if it recurs, before a request can reach token issuance.
- reproduction_steps: use an existing table without token-v2 columns; start Navigator; submit an otherwise ready internal task.
- reproduction_status: confirmed by Issue #152 runtime evidence; no new live reproduction is authorized.
- existing_evidence: entity declares the v2 fields, two named task indexes, and a unique token identifier; the existing migration supplied the fields but did not guarantee the named indexes or backfill nullable `row_version` before strict MySQL conversion.
- existing_tests: token lifecycle and policy tests exist; schema compatibility preflight tests do not yet exist.
- regression_protection: required
- waiver_reason_and_risk: N/A

## Risks and Open Questions

- known_risks: a manual migration is an operational prerequisite for deployments with `ddl-auto=validate`; the startup guard intentionally makes that prerequisite visible rather than silently repairing it.
- open_questions: none

## Ultra Execution Contract

- Implement only this approved scope. Do not submit a real ask or change any upstream resource.
- Keep validation limited to the token table and entity-declared indexes; no automatic DDL in application startup.
- Record changed paths, exact validation commands and results, deviations, and residual risks below.
- Set status to `READY_FOR_SIGNOFF` only after code, migration and local runtime checks complete; do not set `ACCEPTED`.

## Implementation Result

- implementation_summary: Added a startup `ApplicationRunner` preflight that uses JDBC metadata to reject an incompatible `business_task_scoped_token` table before Navigator can report ready for Open API task submission. It checks all 30 persistent columns represented by `BusinessTaskScopedTokenEntity`, the two declared task indexes, and any single-column unique `token_id` index. The manual MySQL migration now idempotently guarantees the two task indexes and a unique `token_id` index, and backfills nullable historical `row_version` values before strict MySQL conversion. The initial local migration attempt exposed that historical-null case; after adding the backfill, the migration completed and reran successfully.
- changed_paths:
  - `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessTaskScopedTokenSchemaPreflight.java`
  - `business-agent-module/src/test/java/com/foggy/navigator/business/agent/service/BusinessTaskScopedTokenSchemaPreflightTest.java`
  - `docs/migration/2026-07-14-business-task-token-v2.sql`
  - `docs/version-tracker/1.4.2-SNAPSHOT/workitems/BUG-015-task-token-v2-schema-preflight.md`
  - local ignored runtime configuration `launcher/.env` (set `NAVIGATOR_EXTERNAL_ENABLED=false`; no secret recorded)
- tests_and_results:
  - `mvn -q -pl business-agent-module -am -Dtest=BusinessTaskScopedTokenSchemaPreflightTest -Dsurefire.failIfNoSpecifiedTests=false test`: PASS; 4 focused tests, 0 failures / 0 errors.
  - `mvn -q -pl business-agent-module -am test`: PASS; 79 Surefire reports, 659 tests, 0 reports containing failures or errors after the final preflight expansion.
  - `mvn -q -pl launcher -am -DskipTests package`: PASS; rebuilt the packaged launcher containing the final preflight.
  - `python3 .../execute_sql_file.py ... --file docs/migration/2026-07-14-business-task-token-v2.sql`: PASS twice after the row-version fix; the second execution demonstrated idempotency. No token row data was queried or logged.
  - `bash scripts/start-launcher.sh --skip-build`: PASS; final local 8112 process started from this repository's `launcher/target/launcher-1.0.0-SNAPSHOT.jar` and `/actuator/health` returned `UP`.
  - `git diff --check`: PASS; only pre-existing CRLF conversion warnings were emitted.
- manual_or_experience_evidence: On the active MySQL schema, metadata-only queries found all 30 entity columns; `idx_biz_token_task(task_id)`, `idx_biz_token_tenant_worker_task(tenant_id, worker_task_id)`, and an existing unique single-column `token_id` index. Final 8112 `/actuator/info` reports build time `2026-07-17T05:45:34.472Z`; the process listens only on `127.0.0.1:8112`. Final actual process environment has both `NAVIGATOR_EXTERNAL_ENABLED=false` and `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false`.
- deviations: The existing ignored `launcher/.env` had `NAVIGATOR_EXTERNAL_ENABLED=true` when the first rebuilt local process was started. No external request, ask, Worker/identity/Pool/Directory mutation, TMS access, or production profile was used. Once detected, the flag was changed to false and Navigator was restarted; final verification confirms both external controls are false.
- residual_risks: No live ask was resubmitted, by design: Issue #152's prior failed ask has no task/context result to continue safely, and a new ask would create task/token state outside this authorization. A separate independent signoff may now review the migration and preflight behavior.
- readiness: READY_FOR_SIGNOFF

## References

- issue: `https://github.com/foggy-projects/Foggy-Navigator/issues/152`
- related_work_items: `BUG-014-codex-physical-worker-readiness-route.md`, `GOV-002-biz-worker-and-upstream-user-boundary.md`
- migration: `docs/migration/2026-07-14-business-task-token-v2.sql`
