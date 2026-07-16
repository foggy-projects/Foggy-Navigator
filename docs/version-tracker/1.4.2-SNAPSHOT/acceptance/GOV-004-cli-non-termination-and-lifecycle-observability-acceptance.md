---
acceptance_scope: workitem
version: 1.4.2-SNAPSHOT
target: GOV-004-cli-non-termination-and-lifecycle-observability
doc_role: acceptance-record
doc_purpose: 记录 GOV-004 的正式验收状态、已验证范围、阻断项和重验入口
status: blocked
decision: verification-blocked
accepted_by: none
reviewed_by: root-controller
reviewed_at: 2026-07-16
blocking_items:
  - isolated-cli-five-state-matrix-not-run
  - target-environment-migration-not-run
  - alert-deployment-and-delivery-not-evidenced
  - multi-instance-receipt-ledger-boundary-unverified
follow_up_required: yes
---

# GOV-004 Acceptance Record

## Document Purpose

- doc_type: acceptance
- intended_for: signoff-owner | reviewer | release-owner | operation-owner
- purpose: 对 GOV-004 的 Java/Worker 非主动终止和生命周期可观测性实施给出正式、可重验的结论。
- outcome: **blocked / verification-blocked**。本记录明确不是 accepted，不允许把本地实现或自动化结果解释为生产批准。

## Acceptance Basis

- [GOV-004 workitem](../workitems/GOV-004-cli-non-termination-and-lifecycle-observability.md)
- [Implementation plan](../implementation-plan.md)
- [Progress / EXEC-142-022](../progress.md)
- [Implementation quality record](../quality/GOV-004-cli-non-termination-and-lifecycle-observability-implementation-quality.md)
- [Coverage audit](../coverage/GOV-004-cli-non-termination-and-lifecycle-observability-coverage-audit.md)
- [Operation runbook](../runbooks/GOV-004-cli-non-termination-and-lifecycle-observability-runbook.md)

## Current Status

| Dimension | Status | Evidence boundary |
|---|---|---|
| Implementation | completed-local | Java DB operation audit, signed capabilities, three Worker receipt ledgers, attention semantics and observation-gated terminal transitions are implemented. |
| Local automation | passed-local | Targeted SDK 100, app-server 30, Claude 16; full SDK 207 passed/1 skipped + typecheck, app-server 292 passed/1 skipped + typecheck, Claude 542 passed/11 skipped; Java relevant reactor is `BUILD SUCCESS`. |
| Isolated migration | passed-isolated-only | Docker MySQL forward/index/assert/rollback completed; not target DB evidence. |
| Real CLI lifecycle | not-run | The required natural, CLI-abnormal, explicit-cancel, manual-PID and unconfirmed/timeout matrix is absent. |
| Alerting | not-run | Rules, routing and delivery have not been deployed or triggered. |
| Multi-instance deployment | not-run | Same-ID, same-volume/route or shared-claim semantics have not been exercised. |
| Formal acceptance | blocked / verification-blocked | `accepted_by: none`; no production routing or external enablement change. |

## Acceptance Checklist

- [~] NT-AC-01 — Local code and targeted tests show automatic timeout/watchdog/PID/stream/scan/retry paths produce attention rather than a termination operation. Real CLI process behavior remains unobserved.
- [~] NT-AC-02 — Local Java/Worker contracts require explicit signed authorization for remote cancel and manual PID kill, with audit fields and one-use receipt. Target environment authorization/audit flow is unrun.
- [~] NT-AC-03 — SDK late-spawn/PID-unavailable behavior is regression-covered as `PROCESS_UNVERIFIED`; no real SDK CLI late-spawn smoke exists.
- [~] NT-AC-04 — Timeout/uncertainty attention and non-terminal request semantics are contract-covered; no upstream/SSE/live-process proof exists.
- [~] NT-AC-05 — Safe audit/receipt projections are implemented and tested; real logs, alert payloads, retention and operator query experience are unverified.
- [ ] NT-AC-06 — The required real CLI five-state matrix has not run.
- [ ] NT-AC-07 — Runbook/documentation exists, but alert deployment, release operation and target migration evidence have not run.

`[~]` means partial local coverage, not a signed acceptance item. Since NT-AC-06 and NT-AC-07 remain unfulfilled, this workitem cannot be accepted.

## Evidence Used

| Evidence | Acceptance use | Limitation |
|---|---|---|
| Java reactor: `mvn -pl addons/codex-worker-agent,addons/claude-worker-agent -am -Dsurefire.failIfNoSpecifiedTests=false test` | Confirms relevant Java code regressed cleanly (`BUILD SUCCESS`; Claude addon 383, Codex addon 384 tests). | No target DB, Worker network or real CLI. |
| `CodexTaskServiceTest` | 84 tests, 0 failure/error/skip protects exact task/operation/worker/exit correlation. | Does not observe an OS process or Provider. |
| Worker targeted replay matrices | SDK 100, app-server 30, Claude 16 test the signed operation and durable receipt contracts. | No model-backed CLI five-state. |
| Full Worker suites | SDK 207 passed/1 skipped plus typecheck; app-server 292 passed/1 skipped plus typecheck; Claude 542 passed/11 skipped. | Local-only suites. |
| Isolated Docker MySQL migration | Forward, index/structure assertion and rollback behavior are reproducible. | No target migration or `ddl-auto=validate`; rollback is destructive and needs retention/approval. |
| App-server safe smoke | `codex-cli 0.144.4` init/idle close passed with no model request. | Does not exercise active execution/cancel/kill/timeout. |

## Blocking Items and Re-verification Order

| Blocker | Why it blocks acceptance | Required closure evidence |
|---|---|---|
| `isolated-cli-five-state-matrix-not-run` | The policy's critical promise is about a real CLI not being accidentally killed and each true exit source being distinguishable. | Execute the five cases in the runbook; retain sanitized status/SSE/audit/receipt/process/Provider/alert evidence per case. |
| `target-environment-migration-not-run` | Java's durable audit is not proven in the target storage/runtime. | Forward migration, application `ddl-auto=validate`, indexes/owner query, retention/export and an approved rollback drill. |
| `alert-deployment-and-delivery-not-evidenced` | Attention without operational notification leaves stuck CLI decisions invisible. | Deploy, trigger and retain delivery evidence for attention, failed/unconfirmed operation, receipt failures/replay and migration/stale-operation alerts. |
| `multi-instance-receipt-ledger-boundary-unverified` | A local receipt ledger does not coordinate separate hosts/volumes sharing one stable Worker ID. | Demonstrate independent IDs/routes or a validated shared atomic claim topology, including restart/replay/failure behavior. |

## Security and Rollback Boundary

- Do not log, persist or paste the signed capability/header, Worker token, credential, prompt or raw command data into acceptance evidence.
- Do not delete receipt directories during a Worker code rollback. Retaining them preserves the replay fence; reverting to an old runtime means that runtime may ignore the fence, which is a known security regression and requires explicit release approval.
- Do not execute the SQL rollback as routine remediation. It drops audit data and requires stopped new termination requests plus retention/export and explicit approval.
- Do not reuse one stable PhysicalWorker ID and secret across independent local receipt volumes. Java's audit table cannot repair this topology.

## Formal Decision

**GOV-004 formal acceptance is `blocked / verification-blocked`.**

The implementation and local automation are complete enough to preserve as the baseline for re-verification. They are not sufficient to prove the non-termination guarantee in a real CLI runtime, the DB audit in the target environment, alert operational readiness, or safe multi-instance deployment. `accepted_by: none` remains authoritative until all four blockers are closed and this independent acceptance record is re-reviewed.

- acceptance_status: blocked
- acceptance_decision: verification-blocked
- accepted_by: none
- production_routing_changed: no
- external_enablement: no
