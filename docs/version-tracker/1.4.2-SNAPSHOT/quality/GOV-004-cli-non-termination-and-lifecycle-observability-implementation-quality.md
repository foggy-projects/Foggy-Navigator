---
quality_scope: workitem
quality_mode: implementation-quality-review
version: 1.4.2-SNAPSHOT
target: GOV-004-cli-non-termination-and-lifecycle-observability
status: reviewed
decision: verification-blocked
reviewed_by: root-controller
reviewed_at: 2026-07-16
follow_up_required: yes
---

# GOV-004 Implementation Quality Record

## Document Purpose

- doc_type: implementation-quality
- intended_for: implementation-owner | reviewer | release-owner | signoff-owner
- purpose: 审查 GOV-004 已实施的 Java 与三类 Worker 非主动终止、显式终止 operation、审计和本地防重放边界；不把本地质量审查写成正式验收或生产批准。

## Background and Decision Boundary

- GOV-004 的实现目标是：timeout、watchdog、PID/线程不匹配、stream 断开、scan/stall/drain 异常只能形成 attention / pending-decision，不能主动终止受管 CLI。
- 受控终止必须由 Java 持久化授权 operation 后，以短时、task/worker/process-bound signed capability 派发；Worker receipt 和实际 exit observation 共同防止“请求 ACK 即终态”。
- 本审查确认本地实现、定向自动化和迁移脚本具备可追踪的范围证据，但未运行真实隔离 CLI 五态、目标环境 DB、告警部署或多实例演练。因此结论是 `verification-blocked`，不是 `accepted`、`ready-for-signoff` 或 production enablement。

## Check Basis

- [GOV-004 workitem](../workitems/GOV-004-cli-non-termination-and-lifecycle-observability.md)
- [Implementation Plan](../implementation-plan.md)
- [Progress / EXEC-142-022](../progress.md)
- [GOV-004 coverage audit](../coverage/GOV-004-cli-non-termination-and-lifecycle-observability-coverage-audit.md)
- [GOV-004 acceptance record](../acceptance/GOV-004-cli-non-termination-and-lifecycle-observability-acceptance.md)
- [GOV-004 runbook](../runbooks/GOV-004-cli-non-termination-and-lifecycle-observability-runbook.md)
- [DB forward migration](../../../migration/2026-07-16-termination-operations.sql) and [destructive rollback](../../../migration/2026-07-16-termination-operations-rollback.sql)

## Reviewed Changed Surface

| Surface | Reviewed implementation responsibility | Local quality conclusion |
|---|---|---|
| Java control plane | `TerminationOperation` entity/repository/service/controller/capability; Codex/Claude task services and clients | Explicit operation is persisted before dispatch; owner-scoped query exists; terminal transitions require correlated observation instead of an ACK. |
| Java schema | `termination_operations` table and indexes; startup migration and operator SQL | Additive operation audit table is documented; rollback is explicitly destructive rather than presented as routine undo. |
| Codex SDK Worker | config, `termination-operation.ts`, task/process routes, watchdog/lifecycle models and tests | Signed operation validation, receipt-before-side-effect, task ID projection and attention semantics are present; ledger location is configurable only by absolute path. |
| Codex app-server Worker | task routes, `termination-operation.ts`, task/runtime models, state identity and contract tests | Receipt is inside private `stateDir`, distinct from transient task activity; stable Navigator worker ID is deliberately not runtime/instance display identity. |
| Claude Worker | `config.py`, `termination.py`, query/process routes, integration fixtures and tests | Receipt is independent from event persistence; invalid/corrupt/unavailable receipt paths fail closed. |
| Operational documents | workitem, plan, progress, migration files and runbook | Multi-instance and rollback caveats are recorded instead of hidden behind local test success. |

## Quality Checklist

| Check | Result | Review evidence / limitation |
|---|---|---|
| No automatic termination authority | passed-local | Automatic sources are limited to attention/diagnostics; no watchdog, timeout or drain source may issue `REMOTE_CANCEL`/`MANUAL_PID_KILL`. Real CLI path still requires live verification. |
| Explicit authorization boundary | passed-local | Operation kind/origin, actor, authorization decision, target worker/task/PID/process identity and expiry are validated; request-body provenance is not trusted. |
| ACK versus terminal distinction | passed-local | `CANCEL_REQUESTED` is non-terminal. Java/Worker transitions require observed Provider/process exit and exact correlation before `ABORTED`. |
| Durable Java audit | passed-local / target-db-pending | `termination_operations` is durable in the target design and is queried owner-scoped; target DB migration and `ddl-auto=validate` have not run. |
| Durable Worker replay fence | passed-local | All three Worker paths persist only safe receipt metadata before side effect, use exclusive create and fsync, and reject replay/corruption/unavailability. |
| Secret and sensitive-data hygiene | passed-local | Capability/signature/prompt/token/credential are not persisted in receipt or migration table; live log/alert payload review remains pending. |
| Error handling | passed-local | Missing, invalid, expired, wrong-bound, replayed, corrupt, full or unavailable ledger paths fail closed; no in-memory fallback is used. |
| Cross-instance behavior | blocked | A filesystem receipt ledger is not a distributed claim store. Same `navigatorWorkerId` across different local volumes/hosts remains an unsafe deployment and has not been exercised. |
| Migration/rollback safety | partially-reviewed | Disposable Docker MySQL forward/index/assert/rollback passed; target migration, validation, retention/export and rollback approval path remain unexecuted. |
| Release readiness | blocked | Live CLI five-state, alert deployment/delivery and target environment evidence are missing. |

## Durable Ledger and Multi-instance Finding

The three Workers use separate durable local receipt ledgers, not one shared database ledger:

| Worker | Receipt location | Required deployment property |
|---|---|---|
| Codex SDK | `CODEX_TERMINATION_OPERATION_LEDGER_DIR`, absolute override or package `logs/termination-operations/` | Preserve and reuse the directory for the exact stable PhysicalWorker. |
| Claude | `AGENT_WORKER_TERMINATION_OPERATION_LEDGER_DIR`, absolute override or `logs/termination-operations/` | Preserve and reuse the directory for the exact stable PhysicalWorker. |
| Codex app-server | `${CODEX_APP_SERVER_STATE_DIR}/termination-operations/receipts` | Keep the complete state directory private, persistent and bound to the same stable Worker identity. |

Every receipt is keyed as `SHA-256(worker_id + NUL + operation_id)`. An `O_EXCL` write and fsync happen before interrupt/kill dispatch; a replay remains a replay even after expiry, while only unrelated expired receipts can be pruned. This is correct for processes sharing one durable volume, but it is not an inter-host transaction.

**Finding Q-GOV004-01 (release blocker):** do not run two independent host-local receipt directories with the same stable `navigatorWorkerId` and Worker secret. Before horizontal scaling, pin each physical Worker to an independent ID/route, or provide a separately validated shared atomic claim store. The Java DB operation audit does not replace that Worker-side one-use fence.

## Test and Migration Evidence

| Evidence | Result | What it does not prove |
|---|---|---|
| Codex SDK focused operation/replay matrix | 100 passed | Real Codex CLI natural/abnormal/cancel/manual/timeout matrix. |
| Codex app-server focused operation/replay matrix | 30 passed | An active model task; the init/idle-close smoke used `codex-cli 0.144.4` without a model request. |
| Claude focused operation/replay matrix | 16 passed | Real Claude CLI; it is unavailable in this environment. |
| Codex SDK full Worker suite and typecheck | `npm test`: 207 passed, 1 skipped; typecheck passed | Target routing, real CLI or multi-instance operation. |
| Codex app-server full Worker suite and typecheck | `npm test`: 292 passed, 1 skipped; typecheck passed | Target routing, active real CLI or multi-instance operation. |
| Claude full Worker suite | `.venv/bin/python -m pytest -q`: 542 passed, 11 skipped | Real Claude CLI, target deployment or alert delivery. |
| Java final relevant reactor | `mvn -pl addons/codex-worker-agent,addons/claude-worker-agent -am -Dsurefire.failIfNoSpecifiedTests=false test`: `BUILD SUCCESS`; Claude addon 383 and Codex addon 384 tests | The unrun live environment matrix. |
| Java manual correlation regression | `CodexTaskServiceTest`: 84 tests, 0 failures, 0 errors, 0 skipped | Worker subprocess lifecycle in a real deployment. |
| Isolated DB migration | Docker MySQL forward, index/structure assertions and rollback passed | Target DB migration, `ddl-auto=validate`, retention/export or approved destructive rollback. |

## Findings and Follow-up

1. No local implementation defect was found that requires reverting the non-termination policy. The operation/audit/receipt design consistently separates authorization, dispatch and observed exit.
2. The app-server's state-dir receipt and the SDK/Claude configurable ledgers must be treated as critical runtime data, not disposable logs. Container rebuild, disk cleanup or rollback that deletes them weakens one-use protection.
3. A manual PID request is authorized only when its immutable process identity matches; a PID alone is not sufficient evidence of the intended process.
4. Status/SSE and log/alert behavior is only locally contract-tested. It has not yet shown that operators can distinguish the five requested real-world outcomes without exposing sensitive values.
5. Alert rules and delivery paths are a deployment obligation, not satisfied by adding attention/result fields in code.

## Decision

- decision: `verification-blocked`
- implementation_quality: `passed-local-with-deployment-blockers`
- can_enter_formal_acceptance: no
- accepted_by: none
- required_before_re-review: isolated real CLI five-state matrix, target DB migration/validate/rollback evidence, alert deploy/trigger/delivery evidence, and a validated multi-instance receipt/identity topology.
- production_routing_changed: no
- external_enablement: no
