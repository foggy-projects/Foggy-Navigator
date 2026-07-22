---
test_record: BUG-010
version: 1.4.3-SNAPSHOT
date: 2026-07-22
scope: offline process-group-empty fail-closed repair
runtime_executed: false
status: PASS
---

# BUG-010 Process-Group-Empty Offline Evidence

## Boundary

- No Runtime 10 retry/replacement, new runId, Docker, network, shared `8112`, real TMS/SIM, credential, Worker, Gateway, Pool, identity, Codex route, external enablement, or production action was used.
- No historical `private/children/log/profile/payload/process/Docker` detail was read.
- The real-process regression used only a test-owned dedicated process group and performed bounded teardown by its recorded exact descendant PID/start ticks.

## Regression First

Command:

```bash
pnpm --dir business-agent-module/integration-tests exec vitest run --config vitest.synthetic.config.ts tests/05-synthetic-upstream-bootstrap-safety.test.ts -t "fails closed when the owned leader exits after TERM but its exact process group remains"
```

Before the harness correction: expected test exit `0`, received `72`; fixture output was `term-resistant-descendant=false-clean term=1 kill=0 groupProbes=0 metadata=absent`. This proves the old path accepted leader death without probing PGID absence and deleted ownership metadata.

After the correction, the focused five-case matrix passed: TERM commit race, dead-leader/live-group entry, owned-leader KILL escalation, deterministic resistant-descendant fail-close, and the real Linux same-PGID resistant-descendant fixture.

## Implementation Proof

- `process_group_is_proven_absent()` uses the exact kernel signal-0 process-group operation. Only `ESRCH` is absence; permission, malformed input, interpreter, and other syscall failures remain unproven.
- Every child-metadata deletion branch now requires exact leader death plus exact PGID absence.
- If the leader dies while the PGID remains, cleanup sends no later KILL, retains metadata, and returns failure.
- KILL escalation remains available only while the exact leader still passes the existing ownership re-proof; PGID absence is re-proved after escalation.
- No port, fuzzy PID, shared UID, `/proc` global inventory, or process-name cleanup was introduced.

## Executed Validation

- Targeted safety file: PASS, `92/92`.
- Complete synthetic TypeScript suite: PASS, `116 passed / 1 skipped`.
- Python supervisor suite: PASS, `97/97`.
- TypeScript typecheck: PASS.
- Three-script `bash -n`: PASS.
- Python compile for supervisor and fixture: PASS.
- `git diff --check`: PASS before documentation writeback; required again after final writeback.
- Scoped high-confidence conventional-secret scan: PASS, `0 matches` before documentation writeback; required again after final writeback.

## Readiness Boundary

This offline repair can support BUG-009 re-signoff. It does not independently accept BUG-009 or INT-001 and does not authorize any runtime. Runtime 4-9 remain `CONSUMED_FAIL_CLOSED`; Runtime 10 remains `CONSUMED_SUCCESS` and permanently non-retryable.
