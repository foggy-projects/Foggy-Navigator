---
runbook_scope: workitem
version: 1.4.2-SNAPSHOT
target: GOV-004-cli-non-termination-and-lifecycle-observability
status: verification-blocked
owner: platform-owner | worker-owner | operation-owner
last_reviewed_at: 2026-07-16
production_routing_changed: no
external_enablement: no
---

# GOV-004 CLI Lifecycle Operations Runbook

## Purpose and Safety Boundary

This runbook operates the GOV-004 policy: automated timeout, watchdog, stream/PID uncertainty, scan error, stall, drain and deployment conditions may add attention and diagnostics, but may not abort, interrupt, close an active runtime or kill a managed CLI. A CLI termination request is allowed only through an authorized, signed `REMOTE_CANCEL` or `MANUAL_PID_KILL` operation.

This is a deployment and verification guide, not authorization to change production routing. At the time of this document, formal status is `verification-blocked`; use it first in an explicitly authorized isolated environment.

## Never Record These Values

- Never paste `X-Navigator-Termination-Operation`, its signature, Worker token/credential, model/API key, prompt, raw command arguments or unredacted environment into tickets, logs, alerts or acceptance evidence.
- Store only operation ID, task/session/provider-task ID, stable Worker ID, sanitized status/attention, timestamp, reason code, correlation ID and safe receipt outcome.
- Use a dedicated evidence directory such as `temp/test-artifacts/GOV-004-<date>/`; keep durable acceptance records under this version directory. Do not copy test secrets into either location.

## Required Runtime Configuration

### Stable identity and durable receipt lanes

| Worker | Stable identity | Receipt lane | Non-negotiable requirement |
|---|---|---|---|
| Codex SDK | `CODEX_NAVIGATOR_WORKER_ID` | `CODEX_TERMINATION_OPERATION_LEDGER_DIR`, absolute path; default package `logs/termination-operations/` | Use a private persistent directory that survives restart of this exact PhysicalWorker. |
| Claude | `AGENT_WORKER_NAVIGATOR_WORKER_ID` | `AGENT_WORKER_TERMINATION_OPERATION_LEDGER_DIR`, absolute path; default `logs/termination-operations/` | Use a private persistent directory that survives restart of this exact PhysicalWorker. |
| Codex app-server | `CODEX_APP_SERVER_NAVIGATOR_WORKER_ID` | `${CODEX_APP_SERVER_STATE_DIR}/termination-operations/receipts`; default state root is package `logs/state/` | Keep the complete state directory private and persistent; do not mount only a temporary task directory. |

The stable identity is the Navigator-assigned **PhysicalWorker** resource ID. Do not substitute a hostname, display name, pool runtime ID, process PID, `instanceId` or deployment revision.

Before starting a Worker:

1. Create the receipt directory/state root on a durable private volume, owned by the Worker account and not writable by model/tool subprocess users. Use restrictive OS permissions (for example, mode `0700` where applicable) and platform-equivalent ACLs.
2. Configure the stable Worker ID and existing Worker authentication secret through the approved secret store. Do not place a plaintext secret in tracked configuration or this runbook.
3. Verify that the Worker will reuse the same receipt lane after a restart. A fresh empty directory makes a captured, still-valid operation replayable.
4. Verify writable capacity and inode space. A full, corrupt or unavailable receipt ledger must fail closed; it must never fall back to in-memory replay tracking.

### Multi-instance rule

One stable Worker ID plus one Worker secret may only be served by processes that share the same durable, atomic receipt store. **Never** deploy the same ID/secret to two hosts, separate container writable layers or separate local volumes and assume the Java operation table coordinates them.

Choose one approved topology before scale-out:

1. Give each physical Worker its own stable ID and exact route; or
2. Use a shared, durable and atomically claimed receipt store whose locking, availability and crash behavior have been independently verified.

The current local receipt implementation uses `SHA-256(worker_id + NUL + operation_id)`, exclusive creation and fsync. It protects a single shared ledger volume; it is not a distributed consensus mechanism.

## Authorized Operation Flow

1. Confirm task ownership and the requested action in the platform control plane. Do not call a raw Worker abort/kill route as a substitute for authorization.
2. For a user/upstream cancel, create an explicit `REMOTE_CANCEL`; for an operator PID action, create a distinct `MANUAL_PID_KILL`. Manual PID action must include the expected PID and immutable process identity, not PID alone.
3. Java persists `TerminationOperation v1` before dispatching the signed capability. If audit persistence fails, do not dispatch.
4. Worker verifies signature, expiry, stable Worker ID, task ID, operation kind/origin and, when applicable, PID/process identity. It writes a one-use receipt before the side effect.
5. Treat a `202`/ACK as `CANCEL_REQUESTED`, not as `ABORTED`. Wait for actual Provider terminal or precisely correlated process exit.
6. Query the owner-scoped audit surface `GET /api/v1/tasks/{taskId}/termination-operations` and sanitized Worker/task status to reconcile dispatch and observed result.

An automated component is not an authorized actor. A timeout, reconciler, watchdog, deployment drain, retry loop or diagnostic failure must instead create/update attention such as `TIMEOUT_PENDING_DECISION`, `PROCESS_UNVERIFIED`, `WORKER_DRAINING_PENDING_DECISION` or `TERMINATION_UNCONFIRMED`.

## Database Migration Procedure

### Preconditions

- Obtain target-environment Owner authorization and a maintenance/change record.
- Confirm backup/retention policy for the new audit data and a rollback approver.
- Do not use a shared or production DB merely to obtain test evidence without explicit authorization.
- Ensure application configuration will start with `spring.jpa.hibernate.ddl-auto=validate`; do not depend on automatic schema creation as production proof.

### Forward migration

1. Announce the maintenance window and verify no unrelated schema migration is in progress.
2. Apply [2026-07-16-termination-operations.sql](../../../migration/2026-07-16-termination-operations.sql) using the approved deployment migration mechanism. The script creates `termination_operations`; it stores safe operation/audit metadata, not capability/signature/prompt/credential material.
3. Start the application with `ddl-auto=validate` and verify startup succeeds.
4. Validate the table and indexes with the approved DB client. Minimum inspection: table definition, indexes for task/provider-task/session/owner/worker/status/expiry, and an owner-scoped operation query using non-sensitive fixture data.
5. Record timestamp, target identifier approved by the Owner, migration artifact checksum/version, validation result and sanitized query result in the acceptance evidence. Do not record DB credentials or raw capabilities.

### Target migration acceptance condition

Forward migration is not accepted until all are true:

- the application validates the schema;
- a safe explicit operation writes and owner-scoped query reads its audit projection;
- attention/operation errors are observable through the deployed logging/alert path; and
- the rollback approver has reviewed retention/export requirements.

The disposable Docker MySQL result is useful preflight only; it does not satisfy these target conditions.

## Rollback Procedure

The SQL rollback is a destructive `DROP TABLE`. It is not an automatic undo and must not be run to clear a transient application issue.

1. Stop accepting new termination operations and drain or explicitly resolve outstanding `CANCEL_REQUESTED`/unconfirmed operations. Do not use automatic kill to drain them.
2. Export or retain the operation audit records according to the approved policy. Confirm a named approver accepts the loss of live queryability after drop.
3. Stop the application or otherwise guarantee no code path can dispatch a signed termination operation while the table is absent.
4. Apply [2026-07-16-termination-operations-rollback.sql](../../../migration/2026-07-16-termination-operations-rollback.sql) only after the above checks.
5. If rolling Worker code back, **retain every receipt directory/state root**. Do not delete receipts to make an old version start. Old code may ignore them, which weakens replay protection and must be documented as a security regression.
6. After rollback, verify the old application's supported behavior and record the loss/retention decision. Reapply the forward migration before re-enabling GOV-004 operation dispatch.

## Alerting and Operator Response

Deploy and test alerts for at least these categories:

| Signal | Required operator action |
|---|---|
| `TIMEOUT_PENDING_DECISION`, `PROCESS_UNVERIFIED`, `WORKER_DRAINING_PENDING_DECISION` | Inspect sanitized task/operation state; choose continue-wait, diagnostics or explicit authorized action. Do not kill automatically. |
| `TERMINATION_UNCONFIRMED` or dispatch/observation failure | Keep ownership/reservation until correlated exit or operator decision; reconcile Java audit, Worker receipt and Provider/process observation. |
| Receipt ledger replay | Treat as security/audit event; do not retry with a new implicit operation. Verify whether the original operation was executed. |
| Receipt ledger unavailable/full/corrupt | Treat as fail-closed availability incident; restore the private persistent lane, do not bypass with an in-memory receipt or delete files. |
| Migration/validation failure | Stop rollout before enabling operation dispatch; preserve logs and rollback only through the approved destructive procedure. |
| Stale operation nearing/after expiry | Reconcile actual process state; expiry does not authorize a later automatic kill. |

For every alert test capture rule ID, timestamp, sanitized event, route/receiver confirmation and operator acknowledgement. A rule existing in source/config without a delivery test is not deployed evidence.

## Isolated Real CLI Five-state Verification

Use an approved isolated Worker, database and CLI fixture. Do not run against a shared tenant/Worker or with production credentials. For every case retain sanitized status/SSE, Java operation projection, Worker receipt result, actual process/Provider observation and alert delivery.

| Scenario | Expected result | Must not happen |
|---|---|---|
| Natural completion | No termination operation; normal terminal result has actual Provider/process evidence. | No false cancel/manual origin. |
| CLI-native abnormal exit | Failure is tied to observed abnormal CLI/Provider result. | No watchdog attribution or hidden kill. |
| Authorized explicit cancel | Signed `REMOTE_CANCEL` is auditable; ACK is non-terminal until observed exit. | `ABORTED` immediately from request/ACK. |
| Authorized manual PID kill | Signed `MANUAL_PID_KILL` matches task, stable Worker, PID and immutable process identity; observed exit is audited. | PID-only/caller-origin-only authorization. |
| Timeout/unconfirmed | Attention remains visible and CLI continues unless a later explicit operation succeeds. | Automatic abort/kill, ownership release or invented terminal state. |

Run a restart/replay check and, for an approved scale topology, a second-instance/misrouting negative check. The latter must prove that a duplicate stable ID cannot bypass the one-use receipt fence.

## Evidence and Exit Criteria

The GOV-004 acceptance record may move from `blocked / verification-blocked` only when the five-state matrix, target migration/validate/approved rollback drill, alert deployment/delivery and multi-instance topology evidence are complete and independently reviewed.

Until then:

- `accepted_by: none`
- `production_routing_changed: no`
- `external_enablement: no`
- automatic CLI kill remains prohibited
