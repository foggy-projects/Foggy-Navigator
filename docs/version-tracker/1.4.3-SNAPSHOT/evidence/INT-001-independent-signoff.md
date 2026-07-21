---
acceptance_scope: feature
version: 1.4.3-SNAPSHOT
target: INT-001
status: rejected
decision: rejected
signed_off_by: Independent Signoff Reviewer (Codex)
signed_off_at: 2026-07-21
reviewed_by: Independent code and evidence audit
blocking_items:
  - INT-001-AC-2-forced-SIGNAL-cleanup
  - BUG-009
follow_up_required: yes
evidence_count: 10
---

# INT-001 Synthetic Upstream Integration Harness Independent Signoff

## Document Purpose

- intended_for: Project Owner / future harness fix owner / release reviewers
- purpose: Record the independent acceptance decision for the disposable synthetic upstream harness without reading private carriers, logs, or child artifacts.

## Background

- delivery_spec: `../workitems/INT-001-synthetic-upstream-integration-harness.md`
- target_outcome: An owned, disposable runtime proves positive and negative Open API task paths and cleans all owned resources for both normal and forced-failure paths.
- signoff_scope: INT-001 only. This record does not sign off BUG-008, real SIM/TMS integration, Gateway external, Provider readiness, or production.

## Acceptance Basis

- approved delivery spec: The canonical INT-001 spec was `READY_FOR_SIGNOFF` at review start and defines AC-1 through AC-6.
- reviewed snapshot: `c2780ba0f353e601901a0b8e0c6a2558ca50c34e`; [snapshot record](./INT-001-BUG-008-signoff-snapshot-2026-07-21.md) binds 11 tracked diffs and 19 untracked source/document paths, aggregate `3d5f67d9b5cfc216c3d341622b286f7b29f2009c775953f7b4592f69b0524f8a` under its documented `foggy-navigator-inventory-v1` serialization.
- post-document-correction integrity: all 30 listed hashes were independently rechecked with `git diff --binary | sha256sum` for tracked paths and `sha256sum` for untracked paths; `30/30` matched. `git diff --check` passed (only shared-tree CRLF warnings).
- root receipts only: normal owned replay `int001-evidence-20260721-d6e7f8` reported audit SHA-256 `ce41aa6f76ffdfa91831f7984491798e7d6df69eb53abd9f786889791955bb7b` and cleanup SHA-256 `f8891f98b4d739f94001d22658ff214fa84c3fea3db61e8155a4a75f7783b7fe`, both mode `0600`; forced owned SIGNAL replay `int001-signal-20260721-a9b0c1` reported cleanup SHA-256 `917c868fa520ebccb7b9a3b7a6b1ace00f3b2c5b6039d4129cd73fbb6b68016e`, mode `0600`.
- confidentiality boundary: no failed-run `private/`, process log, `children/`, credential, profile, payload, or manual cleanup/retry was read or performed.

## Contract Conformance

| Item | Expected | Delivered / evidence | Result |
|---|---|---|---|
| AC-1 | default zero-write, target and profile safety | disposable-only doctor/prepare safety tests and source review preserve loopback, ownership and profile fail-closed checks | pass |
| AC-2 | owned normal and forced-failure cleanup both reach `CLEANED` | normal replay is `CLEANED`, redacted, and has no private carrier; forced parent-TERM receipt is `FAILED_CLEANUP`, `failureStage=SIGNAL`, even though it is redacted and `private/` is absent | **fail** |
| AC-3 | runtime child receives only allow-listed projection | synthetic config/audit tests passed; the normal root receipt reports the expected redacted runtime projection result | pass |
| AC-4 | positive runtime path and seven deny paths dispatch correctly or fail closed | normal replay reports readiness/owner-smoke pass, positive `true / 1 / 1`, and all seven deny probes `false / 0 / 0` | pass |
| AC-5 | no secret/profile/payload evidence escapes durable records | root receipts are redacted, `private/` absent, and filename-only literal-secret scan found no high-entropy/literal secret candidate | pass |
| AC-6 | required focused checks and hygiene actually run | post-snapshot Maven, Node, Python, shell and whitespace checks all passed | pass |

## Evidence Matrix

| Item | Risk | Unit | Integration | E2E | Playwright | Manual | Evidence | Coverage |
|---|---|---|---|---|---|---|---|---|
| AC-1 | accidental shared-resource write | synthetic safety tests passed | N/A — no separate integration-only check required | owned normal and forced runs used isolated loopback targets | N/A — no UI surface | root receipt review only | harness tests and receipt metadata | covered |
| AC-2 | residual process/container/profile or unprovable cleanup | lifecycle contract tests passed | N/A — lifecycle is exercised by the owned E2E runs | normal replay passed; forced SIGNAL replay explicitly failed cleanup | N/A — no UI surface | one owned parent TERM, no retry | forced root receipt and BUG-009 | **failed** |
| AC-3 | credential-lane leakage | synthetic projection/config tests passed | N/A — no separate integration-only check required | normal runtime audit passed | N/A — no UI surface | no carrier read | test run and redacted root receipt | covered |
| AC-4 | unauthorized task/Worker dispatch | focused Java and synthetic tests passed | N/A — no separate integration-only check required | positive plus seven deny probes passed | N/A — no UI surface | root receipt review only | normal replay / test record | covered |
| AC-5/6 | secret leak or scope drift | focused checks passed | N/A — hygiene checks are not an integration surface | N/A — no runtime-path E2E required | N/A — no UI surface | filename-only scan and diff review | commands below and snapshot hash proof | covered |

## Independent Execution Evidence

- `mvn -q -pl business-agent-module -am -Dtest=BusinessAgentTaskServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` — exit 0.
- `mvn -q -pl addons/langgraph-biz-worker -am -Dtest=LanggraphBusinessAgentWorkerTaskLauncherTest,LanggraphWorkerServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` — exit 0.
- `mvn -q -pl addons/claude-worker-agent -am -Dtest=OpenApiControllerMessageMappingTest#askAgent_doesNotForwardCallerUpstreamSystemIdIntoServerResolvedWorkerSelection -Dsurefire.failIfNoSpecifiedTests=false test` — exit 0.
- `env -u INT001_SYNTHETIC_UPSTREAM_HARNESS -u INT001_RUNTIME_PROBE npm run test:synthetic` — exit 0; 76 passed, 1 skipped.
- `npm run typecheck` — exit 0.
- `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest -v test_directory_facade test_biz_ingress_proxy` — exit 0; 13 tests passed.
- `bash -n tools/navigator-upstream/scripts/synthetic-upstream-{harness,bootstrap,runtime-audit}.sh` — exit 0.
- filename-only changed-surface literal-secret scan — no high-entropy/literal secret candidate; low-entropy documentation lexical candidates were not printed or treated as credentials.
- `git diff --check` — exit 0; existing CRLF normalization warnings only.

## Failed Items

1. **AC-2 failed.** The forced owned parent-TERM exercise is the required proof for cleanup under failure. Its root-level redacted receipt says `result=FAILED_CLEANUP` and `failureStage=SIGNAL`; normal cleanup and `private/` absence do not substitute for this missing proof.

## Risks / Follow-ups

- [BUG-009](../workitems/BUG-009-int001-forced-signal-owned-cleanup.md) is the canonical `DRAFT` acceptance-found work item. It requires a new approved, harness-only diagnosis/fix scope; it must not inspect or retry the failed run.
- Static review identifies a documentation ambiguity: on cleanup failure `children/` may retain `0600` non-secret ownership-remediation metadata while INT-001 says only a root-level redacted manifest/diagnostic summary is retained. No `children/` artifact was read. This is not a secret-leak finding and does not lower the failed SIGNAL cleanup result.
- The shared-tree `mvn test -pl launcher -am` remains non-passing because of non-INT route-catalog drift for the two termination endpoints. It is not asserted as a passing INT-001 check.

## Final Decision

- decision: `rejected`
- rationale: AC-2 is a critical explicit lifecycle acceptance criterion and is confirmed failed by the forced-SIGNAL root receipt. The redacted receipt, absent private carrier, normal runtime replay, and passing focused suites are valuable evidence but cannot convert `FAILED_CLEANUP/SIGNAL` into `CLEANED`.
- blocking_items: `INT-001 AC-2 forced-SIGNAL cleanup`; `BUG-009`
- follow_up_owner_and_due: Project Owner to approve a bounded BUG-009 contract before any diagnosis or retry; no date-based waiver.

## Signoff Marker

- acceptance_status: rejected
- acceptance_decision: rejected
- signed_off_by: Independent Signoff Reviewer (Codex)
- signed_off_at: 2026-07-21
- acceptance_record: `docs/version-tracker/1.4.3-SNAPSHOT/evidence/INT-001-independent-signoff.md`
- blocking_items: `INT-001 AC-2 forced-SIGNAL cleanup`, `BUG-009`
- follow_up_required: yes
