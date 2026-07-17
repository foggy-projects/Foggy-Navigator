---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.2-SNAPSHOT
ticket: BUG-012
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
approved_by: user
approved_at: 2026-07-17
open_questions: []
---

# Delivery Spec: Upstream Codex Physical Worker Guidance and Legacy Guard

## Document Purpose

- intended_for: implementation / independent-signoff
- purpose: Prevent an upstream operator from treating a Codex capability on an existing Physical Worker as a new BizWorkerIdentity or WorkerPool membership.
- canonical_path: docs/version-tracker/1.4.2-SNAPSHOT/workitems/BUG-012-upstream-codex-physical-worker-guidance.md

## Goal

- target_outcome: Existing Physical Workers receive Codex through the WorkerHost capability route, while legacy worker-pool registration cannot be mistaken for the supported `OPENAI_CODEX` onboarding route.

## Scope

- in_scope: `navigator-runtime-provisioning` operator instructions; Navigator upstream CLI help and unsupported-command guard; focused Open SDK CLI regression tests.
- affected_modules: `.agents/skills/navigator-runtime-provisioning/`; `navigator-open-sdk/`; this work item.
- external_dependencies: none; all verification is local and must not call live upstream-admin APIs.

## Non-Goals

- out_of_scope: creating, changing, deleting or joining a real Worker/Pool; replacing a Directory Worker; changing runtime resolver routing; enabling external or production modes; retiring generic WorkerPool compatibility for supported LangGraph Biz workflows.
- do_not_touch: `.navigator/`, `accounts/`, credentials, upstream SIM workspace, live Worker processes, deployment configuration and database data.

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
| --- | --- | --- |
| Reuse an existing Codex-capable Physical Worker through `worker-host verify` then `worker-host update --worker-id`. | `apply` can create a Worker when the ID is absent; the explicit update path preserves the existing resource identity. | `worker-host apply` remains available for new-host bootstrap. |
| Model `OPENAI_CODEX` as `claudeCode.codexConfig`, not as a direct BizWorkerIdentity. | The current CLI routes Codex through the existing Claude/Directory Physical Worker. | `workers.codex.workerId` remains unsupported. |
| Treat `worker-pool register-worker` with `OPENAI_CODEX` or `OPENAI_CODEX_APP_SERVER` as unsupported. | The legacy command otherwise creates a misleading standalone identity that is not the standard Codex onboarding contract. | Preserve the WorkerPool commands for compatible legacy Biz Worker use. |
| Make the distinction explicit in both the runtime-provisioning skill and CLI help. | The operator-facing flow and the command surface must agree. | Keep the skill concise; do not add redundant runbooks. |

## Acceptance Criteria

- [ ] AC-1: The runtime-provisioning skill has a concise backend/onboarding decision table that identifies existing Physical Worker + Codex as `verify` then `update`, and excludes direct Codex BizWorkerIdentity / WorkerPool registration.
- [ ] AC-2: Top-level and `worker-host` CLI help distinguish new-host `apply`, existing-worker `update`, and legacy WorkerPool compatibility; they state the Codex capability route and prohibited direct identity route.
- [ ] AC-3: `worker-pool register-worker` rejects `OPENAI_CODEX` and `OPENAI_CODEX_APP_SERVER` before issuing an upstream-admin API request, with an actionable `worker-host update` alternative.
- [ ] AC-4: Focused automated tests cover the help text and both rejected Codex backends, including the absence of a remote registration request.
- [ ] AC-5: No live Worker/Pool/API resource is changed; no external or production setting is enabled.

## Contract / Data / Security Constraints

- API or event contract: no endpoint, persistence or task-routing change; this only adds client-side validation and operator guidance.
- data and migration: none.
- compatibility and rollback: generic legacy WorkerPool registration remains available for non-Codex backends; reverting the CLI/skill patch restores prior guidance only.
- permissions and secrets: tests use fixture secrets only; do not read or write real profiles or credentials.

## Bug Context

- bug_source: user-report
- severity: major
- environment: local upstream provisioning review for an existing Physical Worker and `foggy-world-sim`.
- current_behavior: visible legacy commands and broad provisioning language allow an operator to infer that `OPENAI_CODEX` should be registered as a BizWorkerIdentity and added to a WorkerPool.
- expected_behavior: the supported Physical Worker capability route is unambiguous and direct Codex legacy registration is blocked locally.
- reproduction_steps: inspect current help, then attempt `worker-pool register-worker` with a manifest containing `workerBackend=OPENAI_CODEX`.
- reproduction_status: confirmed by source review; current registration code forwards the manifest without a Codex-backend guard.
- existing_evidence: `worker-host` routes Codex through `claudeCode.codexConfig`; `worker-pool register-worker` forwards a generic identity manifest.
- existing_tests: `UpstreamCliTest` covers WorkerHost Codex routing and Biz identity registration separately, but not the invalid direct-Codex registration path.
- regression_protection: required.

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
| --- | --- | --- | --- |
| Skill guidance | medium | static decision-table inspection | changed SKILL excerpt and validation result |
| Help and local guard | major | focused `UpstreamCliTest` methods | exact Maven command and passing result |
| Java module integrity | medium | `mvn -pl navigator-open-sdk -am test` when practical | exact command and result, or recorded environment limit |
| Patch hygiene | low | `git diff --check` | exit status |

## Risks and Open Questions

- known_risks: an unpublished downstream CLI binary will not receive the new guard until it updates; this patch does not migrate or repair any already-created identity/pool records.
- open_questions: none

## Ultra Execution Contract

- Implement only the approved scope. Keep generic WorkerPool compatibility for non-Codex backends.
- Do not contact or modify SIM, a live Worker, a Pool or an upstream-admin API.
- Record changed paths, exact validation, deviations and residual risks below.
- Set status to `READY_FOR_SIGNOFF` when implementation and required local checks are complete; do not set `ACCEPTED`.

## Implementation Result

- implementation_summary: Added an explicit existing-Physical-Worker Codex decision table, clarified CLI help for `apply` versus `update`, and rejected direct WorkerPool registration for `OPENAI_CODEX` and `OPENAI_CODEX_APP_SERVER` before any upstream-admin request.
- changed_paths:
  - `.agents/skills/navigator-runtime-provisioning/SKILL.md`
  - `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/cli/UpstreamCli.java`
  - `navigator-open-sdk/src/test/java/com/foggy/navigator/sdk/cli/UpstreamCliTest.java`
  - `docs/version-tracker/1.4.2-SNAPSHOT/workitems/BUG-012-upstream-codex-physical-worker-guidance.md`
- tests_and_results:
  - `mvn -pl navigator-open-sdk -Dtest=UpstreamCliTest#workerHelpDistinguishesExistingCodexPhysicalWorkersFromWorkerPoolCompatibility,UpstreamCliTest#workerPoolRegisterWorkerRejectsDirectCodexIdentitiesBeforeRequest test` — PASS (2 tests).
  - `mvn -pl navigator-open-sdk test` — PASS (152 tests).
  - `mvn -pl navigator-open-sdk -am test` — PASS (152 tests).
  - `python3 /home/sa/.codex/skills/.system/skill-creator/scripts/quick_validate.py .agents/skills/navigator-runtime-provisioning` — PASS (`Skill is valid!`).
  - `git diff --check` — PASS.
- manual_or_experience_evidence: Regression test proves both rejected Codex backends return CLI error code 2 and leave the mock upstream request list empty. No live profile, Worker, Directory, WorkerPool, upstream-admin API, external mode or production mode was accessed or changed.
- deviations: none
- residual_risks: Downstream installations using an older CLI binary will not receive the local guard until upgraded; this change does not repair any pre-existing standalone identity or WorkerPool member.
- readiness: READY_FOR_SIGNOFF

## References

- `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/cli/UpstreamCli.java`
- `navigator-open-sdk/src/test/java/com/foggy/navigator/sdk/cli/UpstreamCliTest.java`
- `.agents/skills/navigator-runtime-provisioning/SKILL.md`
