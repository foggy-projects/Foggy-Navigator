---
acceptance_scope: bug
version: 1.4.3-SNAPSHOT
target: BUG-009
status: rejected
decision: rejected
signed_off_by: Independent Signoff Reviewer (Codex)
signed_off_at: 2026-07-22
reviewed_by: Independent code, security, contract and evidence audit
blocking_items:
  - BUG-009-AC-1-AC-3-process-group-residue-proof
follow_up_required: yes
evidence_count: 8
---

# BUG-009 INT-001 Forced-SIGNAL Owned Cleanup Independent Signoff

## Document Purpose

- intended_for: Project Owner / future harness repair owner / release reviewers
- purpose: Record the independent BUG-009 acceptance decision without rerunning Runtime 10, inspecting restricted run details, or changing implementation.

## Background

- delivery_spec: `../workitems/BUG-009-int001-forced-signal-owned-cleanup.md`
- target_outcome: A newly created, provably harness-owned disposable stack receives one controlled parent `TERM`, publishes a redacted `CLEANED/SIGNAL` receipt, and retains no run-owned process, container, volume, private carrier, reservation, or file resource.
- signoff_scope: BUG-009 only. This record does not accept INT-001, real SIM/TMS integration, Provider or Worker Gateway readiness, external exposure, or production readiness.

## Acceptance Basis

- approved delivery spec: The canonical BUG-009 work item was `READY_FOR_SIGNOFF` at review start and defines AC-1 through AC-15.
- changed paths / diff: The shared dirty worktree at HEAD `ff1166dbfde55c18bb6545859e56f2d92ecdf1e6` was reviewed without modifying implementation. The relevant cleanup path is in `tools/navigator-upstream/scripts/synthetic-upstream-harness.sh`; the completion projection is in `tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py`; lifecycle coverage is in the synthetic TypeScript and Python fixtures.
- runtime evidence: Runtime 10 durable record `../test-records/BUG-009-int001-runtime10-success-2026-07-22.md`, runId `int001-bug009-20260722-r10-9047a550`, records the one already-consumed command as exit `0` with exact outer `EXIT_128`, redacted `CLEANED/SIGNAL`, private/root/reservation/Docker zero gates and `SUCCESS_GATE_MET`. It was not rerun.
- confidentiality and authority boundary: No runtime, Docker or network command was executed; no historical `private/children/log/profile/payload/process/Docker` detail was read; no retry, replacement runId or cleanup was attempted. Runtime authorization remains `none`.
- independent offline execution: targeted synthetic safety `88/88`, complete synthetic suite `112 passed / 1 skipped`, Python supervisor suite `97/97`, TypeScript typecheck, three shell syntax checks, Python compile, `git diff --check`, and the scoped high-confidence secret scan all passed; secret scan result was `0` matches.

## Contract Conformance

| Item | Expected | Delivered / evidence | Result |
|---|---|---|---|
| AC-1 | Stable parent-TERM ownership/cleanup regression; unproven ownership remains fail closed | Existing tests cover exact child ownership, TERM observation and several fail-closed races, but do not cover a process-group leader exiting while a TERM-resistant descendant remains. The implementation can classify that state as clean. | **fail** |
| AC-2 | Fresh healthy loopback run proves exact parent, sends exactly one TERM and emits redacted `CLEANED/SIGNAL` | Runtime 10 records exact parent/listener proof, one TERM, `EXIT_128` and schema-v4 `CLEANED/SIGNAL`. | pass |
| AC-3 | AC-2 run leaves no private carrier or run-owned process/container/volume/file resource | Private/root/reservation/Docker gates passed, but the cleanup and supervisor success gates do not prove the signaled host process group is empty after its leader exits. A TERM-resistant descendant can remain while metadata is removed and `CLEANED` is published. | **fail** |
| AC-4 | Disposable Open API route gate only; Gateway external false; no real/shared target | Runtime 10 record and scoped review preserve the disposable loopback-only boundary and Gateway external false. | pass |
| AC-5 | Required checks and exact evidence recorded; implementation stops at `READY_FOR_SIGNOFF` | Required offline commands and durable Runtime 10 evidence exist and were independently rechecked where permitted. | pass |
| AC-6 | Candidate-first listener proof preserves or strengthens ownership/listener/one-TERM constraints | Python and synthetic suites passed; source review found no weakening in the candidate/listener authorization path. | pass |
| AC-7 | Historical listener-ineligible combination remains explicitly fail closed | Existing regression and current suites preserve zero-TERM failure for the historical combination. | pass |
| AC-8 | Exact exercise parent is re-proved before discovery, health and TERM | Source and regression review support the parent reproof gate. | pass |
| AC-9 | Bounded identical descendant snapshots admit exactly one exact in-domain Launcher | Python supervisor suite `97/97` passed, including descendant-domain coverage. | pass |
| AC-10 | Out-of-domain failures do not pollute discovery; in-domain malformed state fails closed | Python supervisor coverage passed and no contrary source path was found. | pass |
| AC-11 | Churn, limits, cycles, drift and snapshot disagreement fail before TERM | Deterministic Python coverage passed. | pass |
| AC-12 | FD/socket holder proof, A/B/final reproof, signal-mask commit and at-most-one TERM are enforced | Source review and passing suites support these pre-TERM constraints. This does not substitute for post-TERM process-group emptiness. | pass |
| AC-13 | Historical descendant-domain offline gate and reviews passed | Canonical records the historical gate and reviews; current full suites also passed. | pass |
| AC-14 | Historical slice created no runtime authority; runtime authorization remains separately bounded | Canonical and Runtime 10 record show the one-shot authorization is consumed and current authorization is `none`. | pass |
| AC-15 | Port-reservation isolation gate and historical reviews passed before runtime eligibility | Canonical and durable port-reservation evidence record the completed offline gate; current secret/diff checks passed. | pass |

## Implementation Quality

- scope and changed surface: The implementation stayed within the disposable harness/supervisor/test/document surface and did not alter Navigator permission, Worker, Gateway, credential, TMS/SIM or production contracts.
- maintainability and duplication: The receipt/reservation composite adoption and exact-PID TERM syscall race correction are localized and reviewable; no blocking duplication was found there.
- error handling and edge cases: Blocking defect: after `kill -TERM -- "-$pgid"`, `stop_owned_child()` waits only for the recorded leader PID. Once that PID is dead it removes the metadata and returns success without proving that the PGID has no remaining members. `cleanup_run()` may then publish `CLEANED` and release the reservation.
- contract, data and compatibility: The supervisor completion gate checks outer child exit, receipt, private/root, reservation and Docker state, but not host process-group or descendant residue. Runtime 10 therefore proves the implemented gate, not the stronger AC-3 resource-absence contract.
- terminology and documentation: The canonical and version index are updated to distinguish the consumed successful rehearsal from the rejected BUG-level acceptance decision. `NAVIGATOR_EXTERNAL_ENABLED=true` remains only a disposable Open API route gate and does not imply Provider, Gateway or production readiness.

## Evidence Matrix

| Item | Risk | Unit | Integration | E2E | Playwright | Manual | Evidence | Coverage |
|---|---|---|---|---|---|---|---|---|
| AC-1 | critical false-clean classification | Existing ownership/race tests pass, but no TERM-resistant descendant case | synthetic suite passed | Runtime 10 exercised only cooperative process termination | N/A | source audit | harness `stop_owned_child()` and current test fixture | **failed** |
| AC-2 | critical forced-SIGNAL contract | safety and supervisor suites pass | synthetic suite passed | consumed Runtime 10 passed implemented success gate | N/A | redacted record review only | Runtime 10 durable record | covered |
| AC-3 | critical host process residue | no PGID-empty/descendant-resistant regression | no host process-residue assertion | Runtime 10 gate omits this state | N/A | source audit | cleanup and supervisor completion gate | **failed** |
| AC-4 | major boundary expansion | scoped tests pass | synthetic suite passed | disposable Runtime 10 only | N/A | diff/evidence review | canonical and Runtime 10 record | covered |
| AC-5 | major evidence integrity | targeted `88/88` | `112 passed / 1 skipped`; Python `97/97` | no new runtime permitted | N/A | exact command review | independent command results | covered |
| AC-6 | critical listener false allow | Python topology coverage passed | synthetic suite passed | Runtime 10 exact listener proof | N/A | source audit | supervisor tests and durable record | covered |
| AC-7 | critical historical false success | fail-closed regression passes | synthetic suite passed | historical record only | N/A | record review | canonical test results | covered |
| AC-8 | critical parent substitution | Python parent-proof coverage passed | synthetic suite passed | Runtime 10 exact parent proof | N/A | source audit | supervisor source/tests | covered |
| AC-9 | critical incomplete descendant domain | Python domain coverage passed | N/A | Runtime 10 exact candidate evidence | N/A | source audit | Python suite | covered |
| AC-10 | major procfs classification | Python negative coverage passed | N/A | N/A | N/A | source audit | Python suite | covered |
| AC-11 | critical topology race | Python churn/limit coverage passed | N/A | N/A | N/A | source audit | Python suite | covered |
| AC-12 | critical listener/TERM authorization | Python and synthetic constraints pass | synthetic suite passed | Runtime 10 one TERM | N/A | source audit | source/tests/record | covered for pre-TERM authorization only |
| AC-13 | major historical gate integrity | historical/current suites pass | historical reviews recorded | N/A | N/A | record review | canonical and test records | covered |
| AC-14 | critical authority reuse | N/A | N/A | no runtime rerun | N/A | canonical review | consumed Runtime 10 / authorization none | covered |
| AC-15 | major reservation isolation | reservation regressions pass | synthetic suite passed | Runtime 10 reservation absent | N/A | record review | canonical and durable records | covered |

## Independent Execution Evidence

- `pnpm --dir business-agent-module/integration-tests exec vitest run --config vitest.synthetic.config.ts tests/05-synthetic-upstream-bootstrap-safety.test.ts` — exit `0`; `88/88` passed.
- `pnpm --dir business-agent-module/integration-tests run test:synthetic` — exit `0`; `112 passed / 1 skipped`.
- `PYTHONDONTWRITEBYTECODE=1 python3 -W error::ResourceWarning -m unittest tools.navigator-upstream.fixtures.synthetic-integration.test_forced_signal_supervisor -v` — exit `0`; `97/97` passed.
- `pnpm --dir business-agent-module/integration-tests run typecheck` — exit `0`.
- `bash -n tools/navigator-upstream/scripts/synthetic-upstream-harness.sh tools/navigator-upstream/scripts/synthetic-upstream-bootstrap.sh tools/navigator-upstream/scripts/synthetic-upstream-runtime-audit.sh` — exit `0`.
- `PYTHONPYCACHEPREFIX=temp/test-artifacts/BUG-009-independent-signoff-pyc python3 -m py_compile tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py` — exit `0`.
- `git diff --check` — exit `0` before signoff writeback; repeated after writeback.
- scoped high-confidence conventional-secret scan over BUG-009 changed surfaces and durable records — exit `0`; `0` matches before signoff writeback; repeated after writeback.

## Failed Items

1. **AC-1 failed.** The regression matrix does not prove fail-closed behavior when a signaled process-group leader exits but a run-owned descendant remains alive.
2. **AC-3 failed.** The implementation and Runtime 10 success gate do not prove absence of run-owned host processes. A TERM-resistant descendant can survive while child metadata is removed, the receipt is published as `CLEANED`, and the reservation is released.

## Risks / Follow-ups

- This is a confirmed blocking lifecycle defect, not an unknown evidence gap and not an acceptable residual risk.
- A new approved `DRAFT` acceptance-found repair contract is required before implementation. It must add durable regression coverage for a surviving run-owned descendant and make successful cleanup prove the owned process group is empty before metadata removal and `CLEANED` publication. The implementation session must not infer any runtime authorization from Runtime 10 or this signoff.
- INT-001 remains rejected. No real TMS/SIM, Provider, Worker Gateway external or production readiness claim is unlocked.

## Final Decision

- decision: `rejected`
- rationale: Runtime 10 is valid evidence that the current implemented success gate completed, but AC-3 requires no run-owned process resource. Static source review establishes a reachable success shape in which the group leader exits, a descendant survives TERM, metadata is deleted, and cleanup publishes `CLEANED`. Because the critical contract can be falsely reported as satisfied and the corresponding regression is absent, BUG-009 cannot be accepted or accepted with risks.
- blocking_items: `BUG-009 AC-1/AC-3 process-group residue proof`
- follow_up_owner_and_due: Project Owner to approve a bounded acceptance-found repair contract before further implementation or any new runtime decision; no date-based waiver.

## Signoff Marker

- acceptance_status: rejected
- acceptance_decision: rejected
- signed_off_by: Independent Signoff Reviewer (Codex)
- signed_off_at: 2026-07-22
- acceptance_record: `docs/version-tracker/1.4.3-SNAPSHOT/evidence/BUG-009-independent-signoff-2026-07-22.md`
- blocking_items: `BUG-009 AC-1/AC-3 process-group residue proof`
- follow_up_required: yes
