---
doc_type: test-record
work_item: BUG-009
scope: int001-host-namespace-socket-holder-offline
date: 2026-07-22
status: PASS_OFFLINE_INDEPENDENTLY_REVIEWED
runtime_authorization: none
production_enablement: no
---

# BUG-009 Host-Namespace Socket-Holder Offline Record

## Result

The bounded host-namespace socket-holder correction passes its offline gates. The production-like Java seam now completes the exact one-TERM path in both the host PID namespace and the existing isolated PID namespace. No disposable runtime, Docker stack, shared service, real upstream, credential, Worker, Gateway, Pool, identity, Codex route, external exposure or production target was used.

This evidence does not authorize Runtime 7 by itself. Runtime 4, 5 and 6 remain permanently consumed and must not be retried or replaced as though they were the same authorization.

## Corrected Boundary

- The exact Launcher candidate must belong to two identical, complete exercise-descendant-domain snapshots and retain its start ticks.
- Every in-domain process FD view is mandatory. Any unreadable in-domain FD directory/link, candidate without the exact inode, or second in-domain holder fails closed.
- All readable out-of-domain current-user FD views are checked; any visible exact-inode holder vetoes the proof.
- An unrelated out-of-domain unreadable or transient procfs entry is ignored only under the documented local disposable harness threat model that trusts the single same-UID operator.
- Candidate IPv4 loopback listener/FD proof, exact Java/argv/cwd/exe/lineage/startTicks, A/B/final reproof, pending-signal commit point, at-most-one TERM, receipt, reservation and residue gates remain mandatory.
- The redacted summary retains a previously observed `EXACT_CANDIDATE_FOUND` across later cleanup-time candidate absence, but this diagnostic never authorizes TERM or completion.

## Verification

```bash
INT001_TEST_PID_NAMESPACE=1 PYTHONDONTWRITEBYTECODE=1 python3 -W error::ResourceWarning -m unittest \
  tools.navigator-upstream.fixtures.synthetic-integration.test_forced_signal_supervisor.ProductionLikeFullSupervisorJavaSeamTest.test_real_harness_nesting_completes_full_supervisor_one_term_path -v
# PASS: 1/1 in the host PID namespace

PYTHONDONTWRITEBYTECODE=1 python3 -m unittest \
  tools.navigator-upstream.fixtures.synthetic-integration.test_forced_signal_supervisor.ProductionLikeFullSupervisorJavaSeamTest.test_real_harness_nesting_completes_full_supervisor_one_term_path -v
# PASS: 1/1 through the isolated PID-namespace wrapper

PYTHONDONTWRITEBYTECODE=1 python3 -m unittest \
  tools.navigator-upstream.fixtures.synthetic-integration.test_forced_signal_supervisor -v
# PASS: 93/93

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

PYTHONPYCACHEPREFIX=temp/test-artifacts/BUG-009-host-holder-pyc python3 -m py_compile \
  tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py \
  tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py
# PASS

git diff --check
# PASS

# Scoped high-confidence conventional-secret scan over tracked added lines and
# untracked BUG-009 durable records.
# PASS: 0 matches
```

## Regression Matrix

- exact candidate is the sole in-domain holder and an unrelated out-of-domain PID is unreadable: allow the holder predicate;
- in-domain FD directory or readlink unavailable: reject;
- second in-domain holder or candidate not holding the inode: reject;
- candidate absent from the stable domain or start ticks mismatch: reject;
- readable out-of-domain exact-inode holder: reject;
- `/proc` current-user enumeration failure: reject;
- descendant domain/start-tick drift between holder snapshots: reject;
- `EXACT_CANDIDATE_FOUND` followed by cleanup-time `NO_TRUSTED_JAVA_CANDIDATE`: preserve the exact diagnostic, keep zero TERM and failure;
- earlier `PROC_UNAVAILABLE` followed by a later exact proof: report exact progress without weakening the live proof gates.

## Remaining Gate

Independent read-only code/security, test/runtime-readiness and canonical-document reviews all passed. They found no blocking code, security, test-matrix or document-consistency issue and allowed the separate exact-freeze step. Runtime 7 is frozen in the canonical work item as one distinct one-shot authorization; it must still satisfy the complete strict completion contract and this offline record alone is not runtime acceptance.
