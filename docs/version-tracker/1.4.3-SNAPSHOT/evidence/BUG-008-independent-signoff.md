---
acceptance_scope: bug
version: 1.4.3-SNAPSHOT
target: BUG-008
status: signed-off
decision: accepted-with-risks
signed_off_by: Independent Signoff Reviewer (Codex)
signed_off_at: 2026-07-21
reviewed_by: Independent code and evidence audit
blocking_items: []
follow_up_required: yes
evidence_count: 10
---

# BUG-008 Open API Upstream Physical LangGraph Owner Context Independent Signoff

## Document Purpose

- intended_for: Project Owner / future owner-model reviewers / release reviewers
- purpose: Form an independent decision on the server-derived upstream scope repair without accepting the separate INT-001 forced-cleanup failure.

## Background

- delivery_spec: `../workitems/BUG-008-openapi-upstream-physical-langgraph-identity-context.md`
- target_outcome: For `UPSTREAM_SYSTEM` identity selection, a runtime-authenticated active ClientApp can resolve only its exact upstream-owned physical LangGraph identity, while caller-provided scope cannot influence selection and wrong/missing `UPSTREAM_SYSTEM` context remains fail closed; existing canonical `PLATFORM/platform` compatibility may still be accepted when upstream scope reaches lookup.
- signoff_scope: BUG-008's server-internal owner-context propagation, focused regressions and normal disposable runtime replay. No API/credential/schema change, Codex route, Gateway external, real upstream, Provider, or production claim is included.

## Acceptance Basis

- approved delivery spec: The canonical BUG-008 spec was `READY_FOR_SIGNOFF` at review start and enumerates AC-1 through AC-5.
- reviewed snapshot: [INT-001 / BUG-008 snapshot](./INT-001-BUG-008-signoff-snapshot-2026-07-21.md) binds HEAD `c2780ba0f353e601901a0b8e0c6a2558ca50c34e` and the 30-path changed surface, aggregate `3d5f67d9b5cfc216c3d341622b286f7b29f2009c775953f7b4592f69b0524f8a` under its documented `foggy-navigator-inventory-v1` serialization. Post-document-correction hash verification was `30/30` matched.
- implementation review: `BusinessAgentTaskService` re-reads the active ClientApp and unconditionally overwrites internal selection scope; `LanggraphBusinessAgentWorkerTaskLauncher` maps nonblank server scope to exact `UPSTREAM_SYSTEM/<id>` matching when that identity type is selected. Caller metadata is not promoted into selection, while existing canonical `PLATFORM/platform` compatibility may still be accepted when upstream scope reaches lookup. No WorkerPool/member, extra identity, Gateway, external or schema surface was added.
- normal runtime evidence: owned post-snapshot replay `int001-evidence-20260721-d6e7f8` passed readiness/owner-smoke, positive task/model/Biz-ingress `true / 1 / 1`, seven deny probes `false / 0 / 0`, normal cleanup `CLEANED`, and Gateway external remained false. Only root-level redacted receipts were read.

## Contract Conformance

| Item | Expected | Delivered / evidence | Result |
|---|---|---|---|
| AC-1 | active ClientApp scope overwrites caller/internal supplied scope | service code performs the overwrite at capability preparation; controller regression injects caller scope/worker metadata and passes | pass |
| AC-2 | exact `UPSTREAM_SYSTEM` identity resolves; mismatch or missing `UPSTREAM_SYSTEM` owner fails before persistence/dispatch | launcher and worker-service regressions passed for exact, mismatch, and absent `UPSTREAM_SYSTEM` cases | pass |
| AC-3 | canonical `PLATFORM/platform` compatibility remains available when upstream scope reaches lookup; no pool/member/identity workaround | source/diff review found no pool/member/identity creation; existing canonical platform compatibility remains accepted and is not blank-scope-only | pass |
| AC-4 | focused checks and fresh normal disposable path prove no false allow/deny | three focused Maven commands plus normal replay's positive and seven deny probes passed; Gateway external was false | pass |
| AC-5 | no contract or boundary expansion; hygiene is clean | path review, post-snapshot hash check, `git diff --check`, and filename-only secret scan passed | pass |

## Implementation Quality

- scope and changed surface: the trusted-source change is localized to task capability preparation, immutable launch context, and the second physical identity lookup. There is no caller-facing field or authorization-model rewrite.
- maintainability and duplication: the implementation reuses the active ClientApp source of truth and the existing `ResourceOwnerType.UPSTREAM_SYSTEM` lookup instead of adding a controller workaround, a compatibility fallback, or a pool/member surrogate.
- error handling and edge cases: for `UPSTREAM_SYSTEM` selection, absent or wrong upstream scope reaches exact-owner validation and fails before task/dispatch; existing canonical `PLATFORM/platform` compatibility remains eligible when upstream scope reaches lookup. The direct null guard on active ClientApp prevents accidental use of an unknown source.
- contract, data and compatibility: no Open API, credential lane, schema, migration, Gateway/external or production behavior changed. The normal task-launch construction carries the same server-resolved value for internal consistency only.
- terminology and documentation: the work item, test record and this signoff distinguish an upstream-owned physical identity from existing canonical platform compatibility.

## Evidence Matrix

| Item | Risk | Unit | Integration | E2E | Playwright | Manual | Evidence | Coverage |
|---|---|---|---|---|---|---|---|---|
| AC-1 | critical caller-scope spoofing | `BusinessAgentTaskServiceTest` and OpenAPI composition regression passed | N/A — no separate integration-only check required | normal runtime ask passed | N/A — no UI surface | code/diff review | Maven exits 0; source review | covered |
| AC-2 | critical cross-owner false allow/deny | LangGraph launcher/worker-service tests passed | N/A — no separate integration-only check required | exact-owner normal replay and deny matrix passed | N/A — no UI surface | root receipt review only | Maven exits 0; normal replay | covered |
| AC-3 | major routing workaround/scope expansion | N/A — static scoped diff review | N/A — no integration path required | N/A — compatibility is covered by unit/source evidence | N/A — no UI surface | changed-path review | scoped diff | covered |
| AC-4 | critical real-chain regression | three focused Maven commands passed | N/A — no separate integration-only check required | positive `true / 1 / 1`; seven deny `false / 0 / 0` | N/A — no UI surface | root receipt review only | post-snapshot replay | covered |
| AC-5 | critical secret or boundary drift | focused safety tests passed | synthetic Node/Python/script checks passed | N/A — hygiene has no standalone runtime-path E2E | N/A — no UI surface | filename-only scan and whitespace review | all commands below | covered |

## Independent Execution Evidence

- `mvn -q -pl business-agent-module -am -Dtest=BusinessAgentTaskServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` — exit 0.
- `mvn -q -pl addons/langgraph-biz-worker -am -Dtest=LanggraphBusinessAgentWorkerTaskLauncherTest,LanggraphWorkerServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` — exit 0.
- `mvn -q -pl addons/claude-worker-agent -am -Dtest=OpenApiControllerMessageMappingTest#askAgent_doesNotForwardCallerUpstreamSystemIdIntoServerResolvedWorkerSelection -Dsurefire.failIfNoSpecifiedTests=false test` — exit 0.
- `env -u INT001_SYNTHETIC_UPSTREAM_HARNESS -u INT001_RUNTIME_PROBE npm run test:synthetic` — exit 0; 76 passed, 1 skipped; `npm run typecheck` — exit 0.
- `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest -v test_directory_facade test_biz_ingress_proxy` — exit 0; 13 tests passed; synthetic script `bash -n` — exit 0.
- 30 snapshot path hashes matched; `git diff --check` — exit 0 (existing CRLF warnings only); filename-only literal-secret scan printed no high-entropy/literal candidate.

## Failed Items

- none for BUG-008.

## Risks / Follow-ups

| Residual item | Owner | Required action | Gate / due |
|---|---|---|---|
| INT-001 forced-SIGNAL cleanup | Project Owner, then the approved BUG-009 harness fix owner | Approve and complete the bounded BUG-009 diagnosis/fix and obtain a new independent INT-001 signoff; do not inspect, retry, or manually clean the failed run. | Before any claim that INT-001 is accepted or reusable. |
| Canonical `PLATFORM/platform` compatibility | Owner-model maintainers | Preserve the distinction from exact `UPSTREAM_SYSTEM` matching in the next owner-model contract wording and regression review; do not reinterpret it as blank-scope-only. | Before the next owner-model contract revision or any production promotion, whichever comes first. |
| Unrelated shared-tree launcher route-catalog drift | Termination route-catalog owner and release owner | Isolate or resolve the drift and run the applicable full-reactor release gate; do not cite the unrelated failure as BUG-008 evidence. | Before any production promotion or claim of a passing full-reactor release gate. |
| No real-upstream, Gateway external, Provider, Codex, or production evidence | Release owner | Obtain separately approved scope and evidence for each claimed surface; retain this signoff's local/disposable boundary until then. | Before any claim or promotion covering those surfaces. |

## Final Decision

- decision: `accepted-with-risks`
- rationale: Every BUG-008 acceptance criterion has direct source/diff evidence plus post-snapshot passing focused checks and a normal owned disposable replay. The residual items are disclosed non-blocking boundaries: an independent harness cleanup defect, existing platform compatibility semantics, and unrelated shared-tree reactor drift. None is evidence of caller-controlled upstream scope, cross-owner dispatch, or a widened contract in BUG-008.
- blocking_items: none for BUG-008
- follow_up_owner_and_due: Per-item owner, action, and gate are recorded in `Risks / Follow-ups`; no residual risk has an open-ended waiver.

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Independent Signoff Reviewer (Codex)
- signed_off_at: 2026-07-21
- acceptance_record: `docs/version-tracker/1.4.3-SNAPSHOT/evidence/BUG-008-independent-signoff.md`
- blocking_items: none for BUG-008; BUG-009 follow-up recorded
- follow_up_required: yes
