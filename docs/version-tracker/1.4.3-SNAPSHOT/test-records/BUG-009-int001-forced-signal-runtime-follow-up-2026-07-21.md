---
doc_type: test-record
version: 1.4.3-SNAPSHOT
workitem: BUG-009
status: NEEDS_REPLAN
run_ids:
  - int001-bug009-20260721-r6a1p9k4
  - int001-bug009-20260721-v2m8q4z7
scope: fresh-loopback-disposable-only
recorded_at: 2026-07-21
---

# BUG-009 Forced-SIGNAL Follow-up Runtime Evidence

## Result

Two owner-authorized, fresh loopback-only disposable supervisor runs were attempted. Neither is a forced-SIGNAL success and neither satisfies BUG-009 AC-2 or AC-3.

| Run | Allowed observation | Result |
| --- | --- | --- |
| `int001-bug009-20260721-r6a1p9k4` | The execution wrapper returned no redacted supervisor summary to this session. The root-only snapshot found no `cleanup-report.json`, retained `private/`, and had seven non-receipt entries. | Fail closed; no receipt, no TERM/cleanup success may be inferred. |
| `int001-bug009-20260721-v2m8q4z7` | The execution wrapper again returned no redacted supervisor summary to this session. The root-only snapshot found no `cleanup-report.json`, retained `private/`, and had seven non-receipt entries. | Fail closed; no receipt, no TERM/cleanup success may be inferred. |

No contents under `private/` or `children/` were read. No log, profile, payload, process detail, Docker object, retry, or manual cleanup was accessed. The generic root-name snapshot does not establish the cause, resource ownership, TERM dispatch, cleanup state, or a permission to inspect further.

## Offline Verification After Parent-adoption Parser Correction

- `PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py` — PASS, 39 tests.
- `bash -n tools/navigator-upstream/scripts/synthetic-upstream-harness.sh tools/navigator-upstream/scripts/synthetic-upstream-bootstrap.sh tools/navigator-upstream/scripts/synthetic-upstream-runtime-audit.sh` — PASS.
- `cd business-agent-module/integration-tests && env -u INT001_SYNTHETIC_UPSTREAM_HARNESS -u INT001_RUNTIME_PROBE npm run test:synthetic` — PASS, 93 passed / 1 skipped.
- `cd business-agent-module/integration-tests && npm run typecheck` — PASS.
- `git diff --check` — PASS.

The correction makes the parent-adoption receipt reader reject duplicate JSON object keys with `object_pairs_hook`; the regression uses an otherwise-valid v4 receipt whose duplicate `result` would be silently overwritten by the former `json.load` behavior.

## Boundary and Next Action

- No real TMS/SIM, shared `8112`, real profile/credential, Worker, Gateway, Pool, identity or Codex route was read, modified, or used. `NAVIGATOR_EXTERNAL_ENABLED=true` remains only the disposable target's Open API route gate; it is not Provider, Worker Gateway, or production readiness.
- Do not retry these run IDs or manually clean them. BUG-009 now requires a separately approved plan for a fixed-enum, root-level diagnostic surface or an independently verified redacted execution-wrapper projection. That plan must not broaden access to private artifacts or relax owner/listener, capability, credential, Gateway, or production boundaries.
