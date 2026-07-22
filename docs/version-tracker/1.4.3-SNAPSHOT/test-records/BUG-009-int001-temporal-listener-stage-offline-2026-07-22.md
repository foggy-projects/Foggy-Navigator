---
record_type: offline-test-record
version: 1.4.3-SNAPSHOT
ticket: BUG-009
date: 2026-07-22
result: PASS
---

# BUG-009 Temporal Listener Proof Stage Offline Record

## Scope

Runtime 7 reached exact Launcher identity but never completed listener proof, while its terminal reason reflected cleanup-time candidate absence. This offline-only amendment adds a fixed-enum temporal stage diagnostic so a future separately authorized run can distinguish how far the listener proof progressed without reading restricted evidence or weakening authorization.

The only emitted values are:

- `NOT_OBSERVED`
- `EXACT_IDENTITY_FOUND`
- `LISTENER_SOCKET_FOUND`
- `INITIAL_OWNERSHIP_PROVED`
- `FULL_ELIGIBLE`

The diagnostic contains no PID, port, inode, argv, cwd, path, count, raw reason or exception. Unknown values collapse to `NOT_OBSERVED` and are not echoed. It is never consulted by TERM or completion decisions.

## Regression Matrix

- exact identity with no valid socket table listener retains `EXACT_IDENTITY_FOUND`;
- a discovered listener whose candidate FD or holder exclusivity fails retains `LISTENER_SOCKET_FOUND`;
- identity/inode/FD/holder failure after initial ownership retains `INITIAL_OWNERSHIP_PROVED`;
- the complete listener proof reports `FULL_ELIGIBLE`;
- later cleanup-time candidate absence cannot erase an earlier stage;
- a stage alone never sets `listenerProofEverEligible`, calls health, dispatches TERM or satisfies completion;
- unknown/sensitive stage input emits only `NOT_OBSERVED`.

## Verification

```bash
INT001_TEST_PID_NAMESPACE=1 PYTHONDONTWRITEBYTECODE=1 python3 -W error::ResourceWarning -m unittest \
  tools.navigator-upstream.fixtures.synthetic-integration.test_forced_signal_supervisor.ProductionLikeFullSupervisorJavaSeamTest.test_real_harness_nesting_completes_full_supervisor_one_term_path -v
# PASS: 1/1 in the host PID namespace

PYTHONDONTWRITEBYTECODE=1 python3 -m unittest \
  tools.navigator-upstream.fixtures.synthetic-integration.test_forced_signal_supervisor.ProductionLikeFullSupervisorJavaSeamTest.test_real_harness_nesting_completes_full_supervisor_one_term_path -v
# PASS: 1/1 through the isolated PID-namespace wrapper

PYTHONDONTWRITEBYTECODE=1 python3 -m unittest \
  tools.navigator-upstream.fixtures.synthetic-integration.test_forced_signal_supervisor
# PASS: 96/96

cd business-agent-module/integration-tests
npm run test:synthetic
# PASS: 109 passed / 1 skipped

npm run typecheck
# PASS

cd ../..
bash -n \
  tools/navigator-upstream/scripts/synthetic-upstream-harness.sh \
  tools/navigator-upstream/scripts/synthetic-upstream-bootstrap.sh \
  tools/navigator-upstream/scripts/synthetic-upstream-runtime-audit.sh
# PASS

PYTHONPYCACHEPREFIX=temp/test-artifacts/BUG-009-r7-stage-pyc python3 -m py_compile \
  tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py \
  tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py
# PASS

git diff --check
# PASS

# Scoped high-confidence secret scan
# PASS: 0 matches
```

## Independent Review

- code/security: PASS; fixed-enum redaction and all candidate/socket/FD/holder/A-B-final/one-TERM/completion gates remain fail closed.
- test/runtime-readiness: PASS; the complete offline matrix supports only the next exact-freeze/preflight step.
- canonical/docs: PASS after historical/current wording and Runtime 4/5/6/7 consumption were made consistent.

This offline record is not runtime acceptance. Runtime 4/5/6/7 remain consumed; AC-2/AC-3 remain open. A future runtime requires its own exact one-shot freeze, safe preflight and complete strict success gate.
