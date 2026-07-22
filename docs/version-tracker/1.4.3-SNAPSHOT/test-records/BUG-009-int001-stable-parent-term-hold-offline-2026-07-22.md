---
record_type: offline-test-record
version: 1.4.3-SNAPSHOT
ticket: BUG-009
date: 2026-07-22
result: PASS_WITH_RUNTIME_PENDING
runtime_authorization: none
---

# BUG-009 Stable Parent-Term Hold Offline Record

## Result

The bounded offline correction passed. `hold_for_parent_term` now performs one fixed-duration sleep instead of creating a new one-second descendant on every loop iteration. The strict parent, complete descendant-domain, exact Launcher identity, listener/socket ownership, A/B/final reproof, pending-signal and at-most-one-TERM gates were not relaxed.

This is not forced-SIGNAL runtime acceptance. Runtime 4 `int001-bug009-20260722-484c6216` remains `CONSUMED_FAIL_CLOSED`; no retry, replacement runId or new runtime was authorized or executed. BUG-009 AC-2/AC-3 remain open until a separately authorized fresh runtime proves exactly one TERM, `dispatchSafe=true`, `CLEANED/SIGNAL`, private absence and zero run-owned residue.

## Regression First

Before the harness correction, the new offline source-as-library regression shadowed `sleep`, advanced Bash `SECONDS` without waiting, and recorded every invocation. With a seven-second test duration the old loop produced seven `sleep 1` calls, so the targeted suite reported `84 passed / 1 failed`. The failing assertion required exactly `7\n`.

After the correction, the same regression records one `sleep 7` call and the targeted suite passes. Existing zero-duration timeout, nonzero sleep failure, outer-parent TERM forwarding, delegated child proof, `HOLD_SIGNAL_RECEIVED`, `CLEANED/SIGNAL`, fake Launcher lineage and fail-closed cleanup cases remain passing.

## Stable Real Topology

The Linux procfs topology test now creates three stable processes inside its isolated test-owned namespace:

- outer exercise parent;
- exact listener child;
- one fixed `sleep 30` hold child.

Two complete descendant-domain snapshots return `MATCH` and contain exactly those three PIDs. The exact listener identity and socket ownership proof remains successful. Existing deterministic negative tests continue to reject task-set churn and disagreement between complete descendant-domain snapshots with `IDENTITY_MISMATCH`; no timing-sensitive runtime churn test was added.

## Commands and Results

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest \
  tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py
# PASS: 82 tests
```

```bash
npm --prefix business-agent-module/integration-tests run test:synthetic -- \
  --run tests/05-synthetic-upstream-bootstrap-safety.test.ts
# PASS: 85 tests
```

```bash
cd business-agent-module/integration-tests
env -u INT001_SYNTHETIC_UPSTREAM_HARNESS \
    -u INT001_RUNTIME_PROBE \
    npm run test:synthetic
# PASS: 109 passed, 1 skipped

npm run typecheck
# PASS
```

```bash
bash -n \
  tools/navigator-upstream/scripts/synthetic-upstream-harness.sh \
  tools/navigator-upstream/scripts/synthetic-upstream-bootstrap.sh \
  tools/navigator-upstream/scripts/synthetic-upstream-runtime-audit.sh
# PASS
```

```bash
PYTHONPYCACHEPREFIX=temp/test-artifacts/BUG-009-stable-hold-pyc \
python3 -m py_compile \
  tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py \
  tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py
# PASS
```

```bash
git diff --check
# PASS

# Scoped high-confidence secret scan over the changed BUG-009 surfaces.
# PASS: 0 matches
```

## Boundary and Residual Risk

- Independent security/code, contract/test-matrix and canonical consistency reviews passed after correcting three historical port-reservation sentences that otherwise conflicted with Runtime 4's consumed status. The reviews authorize no runtime by themselves.
- No Runtime, Docker, shared service, real upstream, credential, Worker, Gateway, Pool, identity, Codex route, external or production target was accessed.
- No run `private/`, `children/`, log, profile, payload or process artifact was read. Runtime 4 was not retried or cleaned manually.
- A single sleep removes the harness-created descendant churn identified in Runtime 4, but there is not yet a new real Launcher runtime proof that the complete positive path reaches exactly one TERM and `CLEANED/SIGNAL`.
- Offline success does not imply Provider readiness, Worker Gateway external readiness or production readiness. `NAVIGATOR_EXTERNAL_ENABLED=true` remains only the disposable loopback Open API route gate, and `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false` remains mandatory.
