---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.2-SNAPSHOT
ticket: BUG-014
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
approved_by: user
approved_at: 2026-07-17
open_questions: []
---

# Delivery Spec: Codex Physical Worker readiness and launch route

## Document Purpose

- intended_for: implementation / independent-signoff
- purpose: Fix GitHub Issue #151 so a Codex capability configured on an existing Directory Physical Worker has one supported readiness and task-launch route without inventing a BizWorkerIdentity or WorkerPool membership.
- canonical_path: `docs/version-tracker/1.4.2-SNAPSHOT/workitems/BUG-014-codex-physical-worker-readiness-route.md`

## Goal

- target_outcome: For `OPENAI_CODEX`, an existing Directory-resolved Physical Worker whose execution role is `CLAUDE_WORKER_CODEX_CONFIG` passes readiness and reaches the existing direct Physical Worker launch path when its owner, backend, model grant, Directory binding and Codex role all match.

## Scope

- in_scope: Open API readiness classification for the direct Codex Physical Worker route; Open API construction of the pre-token Worker selection request; deployment build traceability through the controlled Actuator `info` endpoint; focused Java regression tests; this work item and its evidence.
- affected_modules: `addons/claude-worker-agent`, `launcher`, and existing `business-agent-module` / `addons/codex-worker-agent` launch contracts as test boundaries.
- external_dependencies: repository-local tests only; no SIM, Worker, Directory, Pool, profile, external mode or production resource is contacted.

## Non-Goals

- out_of_scope: creating, replacing or registering a Worker; creating a BizWorkerIdentity; adding or changing a WorkerPool member; changing Directory bindings; automatic migration or repair of existing resource records; changing app-server routing; live task/token creation.
- do_not_touch: unrelated dirty worktree changes, credentials, runtime processes, external/production configuration and upstream SIM workspace.

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
| --- | --- | --- |
| `CLAUDE_WORKER_CODEX_CONFIG` is a direct Physical Worker capability route, not a BizWorkerIdentity route. | Codex configuration belongs to the existing WorkerHost record and Directory selects that same Physical Worker. | Preserve the existing Worker ID and Directory binding; create no second Worker. |
| Readiness must validate direct Codex route identity through existing Directory, backend and role-routing checks, not `WORKER_POOL_MEMBERSHIP`. | A Pool accepts BizWorkerIdentity members, while a configured Codex capability has no supported identity to add. | The route remains fail-closed for missing or wrong Codex role/source, backend, model grant or Directory Worker. |
| Open API must express this route through the existing direct-Physical-Worker launcher fallback. | The launcher already rejects a pooled route with no Pool owner and only accepts a route ID equal to the selected Physical Worker. | Do not weaken pooled Codex owner/backend/member checks. |
| BUG-008 remains the rule for actual pooled Codex routes. | Issue #151 concerns only the explicit WorkerHost Codex capability source. | The exception is limited to an execution role resolved as `CLAUDE_WORKER_CODEX_CONFIG`; all other pooled `OPENAI_CODEX` paths keep `WORKER_POOL_MEMBERSHIP`. |

## Acceptance Criteria

- [ ] AC-1: A Directory-resolved `OPENAI_CODEX` Physical Worker with a valid `CLAUDE_WORKER_CODEX_CONFIG` execution role does not fail readiness solely because it is not a BizWorkerPool member.
- [ ] AC-2: The same readiness result continues to fail closed when the Directory Worker lacks the Codex role, has the wrong role source, or fails existing owner/backend/model/Directory checks.
- [ ] AC-3: The Open API pre-token selection request for that direct route has no Pool owner and names the resolved Physical Worker as its direct route ID, so the launcher selects that exact Worker without Pool lookup.
- [ ] AC-4: A normal pooled Codex route still runs the existing `WORKER_POOL_MEMBERSHIP` check and launcher owner/backend/member validation.
- [ ] AC-5: Automated regression tests cover the direct route and the retained pooled route; no test creates a live task, token, Worker, identity or Pool member.
- [ ] AC-6: The packaged local Navigator exposes `/actuator/info` with build version/time, a short source revision and a dirty-worktree marker, without Git user or credential metadata.

## Contract / Data / Security Constraints

- API or event contract: no new HTTP path, CLI parameter, schema or persistence object. Existing readiness checks are reclassified only when the resolved execution role proves the direct Codex capability route.
- data and migration: none.
- compatibility and rollback: direct WorkerHost Codex routes move from an impossible Pool-membership prerequisite to the established direct launcher fallback. Pooled routes retain the BUG-008 fail-closed contract. Reverting code restores prior behavior without data rollback.
- permissions and secrets: no credentials or tokens in code, tests, documentation or diagnostics; no owner, backend or Directory authorization is relaxed.

## Bug Context

- bug_source: GitHub Issue #151
- severity: major
- current_behavior: readiness treats a Codex execution role on an existing Physical Worker as if it must be an enabled member of a BizWorkerPool, while that Pool only accepts BizWorkerIdentity records. First-task selection also retains Pool metadata instead of selecting the direct Physical Worker route.
- expected_behavior: a valid WorkerHost Codex role uses the frozen Directory Physical Worker for both readiness and task selection; pooled routes remain member-checked.
- reproduction_status: confirmed by static call-chain review and existing unit tests; no live upstream ask is authorized.
- existing_evidence: `OpenApiAgentReadinessService` already resolves the Directory Worker and verifies `WORKER_HOST_ROLE_ROUTING` against `CLAUDE_WORKER_CODEX_CONFIG`; `CodexBusinessAgentWorkerTaskLauncher` already contains a direct-Physical-Worker fallback when no Pool owner is present and the route ID equals the requested Worker.
- regression_protection: required.

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
| --- | --- | --- | --- |
| Direct readiness route | critical | `OpenApiAgentReadinessServiceTest` | valid direct Codex role is OK without a Pool selector call; wrong role remains FAIL |
| Pooled compatibility | critical | `OpenApiAgentReadinessServiceTest` and launcher tests | pooled Codex route retains Pool membership validation |
| Pre-token selection | critical | focused controller/service test | direct route emits Physical Worker route ID with null Pool owner and selects only that Worker |
| Module integrity | major | Maven dependency-chain tests | exact command, exit code and test result |
| Patch hygiene | low | `git diff --check` | passing result and changed-path review |

## Risks and Open Questions

- known_risks: the legacy launch request field is named `workerPoolId` even for the existing direct fallback; implementation must constrain it to equal the resolved Physical Worker and leave Pool owner fields empty. Readiness remains a preflight snapshot, so launch must preserve its existing final validation.
- open_questions: none

## Ultra Execution Contract

- Implement only this approved scope. Do not touch live resources or broaden this to app-server/external/production routes.
- Keep BUG-008 behavior for actual pooled Codex routes; do not duplicate or weaken Pool selector checks.
- Record changed paths, exact validation, deviations and residual risks below.
- Set status to `READY_FOR_SIGNOFF` only after implementation and required local checks complete; do not set `ACCEPTED`.

## Implementation Result

- implementation_summary: Readiness now derives the direct-route exception only after Physical Worker diagnostics have proved an execution `codex` role sourced by `CLAUDE_WORKER_CODEX_CONFIG`; it then omits the impossible BizWorkerPool membership check while retaining the existing role-routing, model, owner and Directory checks. Open API now labels a verified WorkerHost Codex capability route and emits the existing direct launcher representation: `workerPoolId` equals the resolved Physical Worker and both Pool-owner fields are null. Pooled Codex routes continue unchanged.
- changed_paths:
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/OpenApiAgentReadinessService.java`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiController.java`
  - `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/OpenApiAgentReadinessServiceTest.java`
  - `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiControllerMessageMappingTest.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexBusinessAgentWorkerTaskLauncherTest.java`
  - `launcher/pom.xml`
  - `launcher/src/main/resources/application.yml`
  - `launcher/src/test/java/com/foggy/navigator/launcher/BuildMetadataResourceTest.java`
  - `docs/version-tracker/1.4.2-SNAPSHOT/workitems/BUG-014-codex-physical-worker-readiness-route.md`
- tests_and_results:
  - `mvn -pl addons/claude-worker-agent,addons/codex-worker-agent -am -Dtest=OpenApiAgentReadinessServiceTest,OpenApiControllerMessageMappingTest,CodexBusinessAgentWorkerTaskLauncherTest -Dsurefire.failIfNoSpecifiedTests=false test`: PASS; readiness 24, Open API controller 44 and Codex launcher 6 tests had 0 failures / 0 errors before the final launcher regression was added.
  - `mvn -pl addons/claude-worker-agent,addons/codex-worker-agent -am test`: PASS; full affected dependency-chain suite completed with no Surefire failures or errors.
  - `mvn -pl addons/codex-worker-agent -am -Dtest=CodexBusinessAgentWorkerTaskLauncherTest -Dsurefire.failIfNoSpecifiedTests=false test`: PASS; 7 tests, 0 failures / 0 errors, including direct Physical Worker selection without any Pool service or member-repository call.
  - `git diff --check`: PASS; only pre-existing working-tree CRLF conversion warnings were printed, with no whitespace errors.
  - `mvn -pl launcher -am -Dtest=BuildMetadataResourceTest -Dsurefire.failIfNoSpecifiedTests=false test`: PASS; 1 test, 0 failures / 0 errors. The generated `META-INF/build-info.properties` contains version/time and `git.properties` is restricted to branch, abbreviated revision, commit time and dirty-worktree state.
  - `bash scripts/start-launcher.sh`: PASS; rebuilt and restarted the local 8112 launcher. `GET /actuator/health` returned `UP`; `GET /actuator/info` returned build version/time, abbreviated revision and dirty-worktree state.
- manual_or_experience_evidence: The direct readiness regression configures a Directory Worker with a Codex base URL and proves `WORKER_HOST_ROLE_ROUTING=OK`, no `WORKER_POOL_MEMBERSHIP` check and no selector invocation. The controller regression proves the same role produces a request bound to that exact Physical Worker with no Pool owner. The launcher regression proves that request resolves directly without a Pool lookup. A non-WorkerHost Codex route still produces the membership failure in the retained regression.
- deviations: none
- residual_risks: No live upstream ask was run because it would create task/token state and is outside this authorization. The direct fallback retains its legacy field name `workerPoolId`; correctness is guarded by equality with the resolved Physical Worker and null Pool-owner fields.
- deployment_traceability: `/actuator/info` is exposed in the existing local Actuator list. Packaged build metadata comes from Spring Boot `build-info`; Git metadata is generated at build time with only branch, abbreviated revision, commit time and dirty-worktree state included. Missing Git metadata does not fail source-archive builds, but then the endpoint will contain build metadata only.
- readiness: READY_FOR_SIGNOFF

## References

- issue: `https://github.com/foggy-projects/Foggy-Navigator/issues/151`
- supersedes_for_direct_route_only: `BUG-008-codex-readiness-pool-membership-parity.md`
- related guidance: `BUG-012-upstream-codex-physical-worker-guidance.md`
