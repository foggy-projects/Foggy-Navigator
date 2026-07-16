---
audit_scope: workitem
audit_mode: pre-acceptance-check
version: 1.4.2-SNAPSHOT
target: GOV-004-cli-non-termination-and-lifecycle-observability
status: reviewed
conclusion: verification-blocked
reviewed_by: root-controller
reviewed_at: 2026-07-16
follow_up_required: yes
---

# GOV-004 Test Coverage Audit

## Audit Purpose

- doc_type: coverage-audit
- intended_for: test-owner | reviewer | release-owner | signoff-owner
- purpose: 将 GOV-004 的七项验收条件映射到本地测试、隔离 migration 和尚缺的真实运行态证据。
- boundary: 本报告不把 unit、route contract、Docker MySQL 或无模型请求的 app-server smoke 误记为真实 CLI 生命周期或正式验收。

## Audit Basis

- [GOV-004 workitem](../workitems/GOV-004-cli-non-termination-and-lifecycle-observability.md)
- [GOV-004 implementation quality](../quality/GOV-004-cli-non-termination-and-lifecycle-observability-implementation-quality.md)
- [Progress / EXEC-142-022](../progress.md)
- [GOV-004 acceptance record](../acceptance/GOV-004-cli-non-termination-and-lifecycle-observability-acceptance.md)
- [GOV-004 runbook](../runbooks/GOV-004-cli-non-termination-and-lifecycle-observability-runbook.md)

## Local Test Evidence

| Lane | Result | Coverage boundary |
|---|---|---|
| Codex SDK operation/replay targeted matrix | 100 passed | Signed cancel/manual PID, wrong binding, single-use receipt, restart/corrupt/full/unavailable ledger and lifecycle route semantics. |
| Codex app-server operation/replay targeted matrix | 30 passed | Signed operation, state-dir receipt, process identity, pending-operation conflict and observed-exit semantics. |
| Claude operation/replay targeted matrix | 16 passed | Signed operation, persistent receipt, replay/restart and invalid ledger fail-closed semantics. |
| Codex SDK full suite/typecheck | 207 passed, 1 skipped; typecheck passed | Worker-local code only. |
| Codex app-server full suite/typecheck | 292 passed, 1 skipped; typecheck passed | Worker-local code only. |
| Claude full suite | 542 passed, 11 skipped | Worker-local code only; no Claude CLI available. |
| Java relevant reactor | `mvn -pl addons/codex-worker-agent,addons/claude-worker-agent -am -Dsurefire.failIfNoSpecifiedTests=false test` = `BUILD SUCCESS`; Claude addon 383, Codex addon 384 tests | Control-plane regression only. |
| Java manual-PID/terminal correlation | `CodexTaskServiceTest`: 84 tests, 0 failures/errors/skips | Exact operation/task/worker correlation; not a real Worker process exercise. |
| Isolated MySQL migration | forward + index/structure assertions + rollback passed | Disposable Docker only; no target database or launcher `ddl-auto=validate`. |
| app-server smoke | init/idle close with `codex-cli 0.144.4` passed, no model request | Only proves safe startup/idle close in that bounded condition. |

## Acceptance Coverage Matrix

| Item | Unit / contract | Worker integration | DB / environment | Real CLI / experience | Coverage conclusion |
|---|---|---|---|---|---|
| NT-AC-01 automatic timeout/watchdog/PID/stream/scan/retry never terminates CLI | yes | yes, three Worker targeted paths | N/A | no | partially-covered; local behavior is protected, but actual process paths remain unobserved. |
| NT-AC-02 only authorized explicit cancel or controlled manual PID kill | yes | yes, signed binding/replay/identity routes | partial; Java operation persistence local | no | partially-covered; target authorization/audit HTTP chain is unrun. |
| NT-AC-03 SDK late spawn/PID unavailable yields `PROCESS_UNVERIFIED` only | yes | yes, watchdog/association regression | N/A | no | partially-covered; no real late-spawn Codex CLI. |
| NT-AC-04 timeout yields `TIMEOUT_PENDING_DECISION` and preserves CLI | yes | yes, attention/non-abort contract | N/A | no | partially-covered; no live process/SSE/upstream action verification. |
| NT-AC-05 auditable, correlated and non-sensitive lifecycle record | yes | partial | partial; isolated migration only | no | partially-covered; real logs, queries, retention and alerts are unverified. |
| NT-AC-06 four execution planes automated plus real CLI five-state distinction | yes | yes, local targeted suite | N/A | no | blocked; the required real five-state matrix was not run. |
| NT-AC-07 documentation, alerts and release guidance prohibit automatic kill | docs/runbook yes | no alert test | no target deployment | no | blocked; deployment/trigger/delivery evidence for alerts is absent. |

`partially-covered` is not a pass for formal acceptance. The minimum of NT-AC-06 and NT-AC-07 is `blocked`, so the workitem's overall coverage conclusion is `verification-blocked`.

## Required Real CLI Matrix

The following five cases must run in a deliberately isolated environment, with a known Worker identity, persistent receipt ledger and evidence directory. For every row capture request/correlation IDs, sanitized status/SSE, attention, operation audit projection, Worker receipt result, actual process/Provider observation and alert delivery result.

| Case | Required observation | Prohibited inference |
|---|---|---|
| Natural completion | Provider/process terminal without termination operation; task completes normally. | Do not invent a cancel or manual-kill origin. |
| CLI-native abnormal exit | Actual non-system exit observation records abnormal failure without a termination operation. | Do not attribute it to watchdog or user cancel. |
| Authorized explicit cancel | Signed `REMOTE_CANCEL` is auditable; ACK remains pending until observed exit. | Do not mark `ABORTED` from request/ACK alone. |
| Authorized manual PID kill | Signed `MANUAL_PID_KILL` matches task, stable Worker, PID and immutable process identity; observed exit is recorded. | Do not authorize by PID or caller-controlled origin alone. |
| Unconfirmed / timeout | Attention/status announces pending decision; CLI continues unless a later explicit operation succeeds. | Do not auto abort/kill, release ownership or manufacture terminal state. |

## Uncovered Deployment Cases

1. Target DB forward migration, application startup with `ddl-auto=validate`, index/query checks and approved destructive rollback after audit export/retention.
2. Alert rule deployment and end-to-end delivery for attention, operation dispatch/observation failure, receipt replay/full/unavailable, migration failure and stale operation.
3. Restart during an unexpired operation and cross-instance replay using the actual topology.
4. Misconfiguration negative cases: relative/missing receipt path, deleted receipt directory, duplicate stable Worker ID on separate local volumes and wrong Worker route.
5. Owner-scoped audit query plus status/SSE compatibility in an upstream-facing isolated flow.

## Multi-instance Coverage Limitation

The current tests prove atomic receipt consumption on one local persistent ledger. They do not prove that two hosts, two containers with separate writable layers, or two independent volumes enforce one-use behavior when they reuse the same stable `navigatorWorkerId`. Before any multi-instance deployment, test one approved topology:

- distinct stable physical Worker IDs and exact routing; or
- a shared, durable, atomic claim store with locking/failure semantics explicitly exercised.

The Java `termination_operations` table is an audit source and does not by itself make multiple local Worker ledgers a distributed replay guard.

## Conclusion

- conclusion: `verification-blocked`
- local_automation: completed for the implemented operation/replay behavior
- can_enter_formal_acceptance: no
- blockers: real-cli-five-state-matrix, target-environment-migration-and-validate, alert-deployment-and-delivery, multi-instance-receipt-topology
- production_routing_changed: no
- external_enablement: no
