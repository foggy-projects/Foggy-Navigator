---
doc_type: test-record
version: 1.4.3-SNAPSHOT
related_workitems:
  - ../workitems/BUG-009-int001-forced-signal-owned-cleanup.md
  - ../workitems/INT-001-synthetic-upstream-integration-harness.md
scope: synthetic-disposable-local-only
status: BLOCKED
executed_at: 2026-07-21
---

# BUG-009 INT-001 forced-SIGNAL runtime 记录（2026-07-21）

## Result

唯一获准的 fresh disposable forced-SIGNAL 演练已实际执行，但在 controlled-health 与 strict listener ownership 前置条件处 fail closed；supervisor 退出 `1`，没有发送 TERM。这是一次明确的运行时失败，不是成功 cleanup 或可签收的 SIGNAL 证据。

| Allowed redacted evidence | Observed |
| --- | --- |
| runId | `int001-bug009-20260721-q7m2z9n4v6kc` |
| supervisor exit | `1` |
| controlled health precondition | `false` |
| parent / listener proof | `NOT_ATTEMPTED` / `socket-listener` |
| TERM dispatch / dispatch safety | `0` / `false` |
| exercise exit | `EXIT_0` |
| root receipt projection | `CLEANED` / `NONE` / `secretsRedacted=true` |
| private carrier / root non-receipt residue | absent / `1` |
| Docker residue counts | container, network and volume all `0` |
| supervisor interruption | `NONE` |

`exerciseExit=EXIT_0` and `CLEANED/NONE` describe the ordinary one-shot lifecycle's normal cleanup, not the required forced-SIGNAL outcome. The fresh run provides no evidence for exactly one TERM, `dispatchSafe=true`, `CLEANED/SIGNAL`, or root residue `0`.

Only the supervisor's redacted stdout and allowed root-level receipt/name-count projection were read. No `private/`, `children/`, log, profile, payload, credential, process, Docker object or historical failed-run artifact was inspected, retried, or manually cleaned.

## Static diagnosis and blocking effect

The one-shot `exercise` delegates `run` to a short-lived lifecycle child. That child starts the long-lived Launcher and then exits, so the Launcher is no longer a descendant of the outer exercise parent when the supervisor requires exact listener ancestry. The final `socket-listener` value is a last observation after normal cleanup and is not interpreted as permission to relax listener, parent, or ownership proof.

BUG-009 remains `BLOCKED`; AC-2 and AC-3 are not met and independent signoff must not start. A future recovery proposal must preserve fail-closed ownership proof, avoid shared/real targets, and receive new explicit authorization before any additional fresh run.

## Executed offline verification

| Command | Result |
| --- | --- |
| `PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py` | PASS — 31 offline tests |
| `bash -n tools/navigator-upstream/scripts/synthetic-upstream-harness.sh tools/navigator-upstream/scripts/synthetic-upstream-bootstrap.sh tools/navigator-upstream/scripts/synthetic-upstream-runtime-audit.sh` | PASS |
| `env -u INT001_SYNTHETIC_UPSTREAM_HARNESS -u INT001_RUNTIME_PROBE npm run test:synthetic` in `business-agent-module/integration-tests` | PASS — 84 passed, 1 skipped |
| `npm run typecheck` in `business-agent-module/integration-tests` | PASS |

## Boundary confirmation

- The sole target was a new loopback-only disposable namespace. It did not use shared `8112`, a real TMS/SIM profile, existing Worker, BizWorkerIdentity, WorkerPool, Codex route, or production setting.
- `NAVIGATOR_EXTERNAL_ENABLED=true` was limited to the disposable target's `/api/v1/open/**` route gate. It is not Provider ready, Worker Gateway external, or production ready.
- `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false` remained required; no Gateway external surface was enabled.
- No second run, retry, broad cleanup, or replacement Worker/identity/pool workaround is authorized by this record.
