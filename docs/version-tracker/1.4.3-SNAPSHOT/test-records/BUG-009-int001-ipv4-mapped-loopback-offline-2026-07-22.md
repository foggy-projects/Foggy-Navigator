---
record_type: offline-test-record
version: 1.4.3-SNAPSHOT
ticket: BUG-009
date: 2026-07-22
result: PASS
---

# BUG-009 IPv4-Mapped Loopback Listener Offline Record

## Scope

Runtime 8 reached exact Launcher identity but never observed an accepted listener. Static review found that the supervisor accepted only `/proc/<pid>/net/tcp` literal IPv4 loopback, while a no-argument JDK server channel may represent the same literal `127.0.0.1` bind as canonical IPv4-mapped loopback in `tcp6`.

This offline-only correction accepts exactly two case-insensitive procfs address representations for the requested LISTEN port:

- `tcp`: `0100007F`
- `tcp6`: `0000000000000000FFFF00000100007F`

Native IPv6, `::1`, wildcard, non-loopback mapped addresses, malformed rows, duplicate `tcp`/`tcp6` listeners and multiple inodes remain rejected. Candidate FD ownership, descendant-domain holder proof, exact identity, A/B/final reproof, pending-signal mask, one-TERM, receipt, reservation and residue gates are unchanged.

## Regression Evidence

- Before the correction, the new `strict-mapped-loopback-listener` and `tcp-and-mapped-tcp6-ambiguous` assertions failed as expected.
- After the correction, `ListenerSocketProbeTest` passed `1/1`, including strict mapped-loopback acceptance and all negative/ambiguity cases.
- The production-like Java seam now uses no-argument `ServerSocketChannel.open()` rather than forcing `StandardProtocolFamily.INET`.
- A real-procfs negative seam holds exact Java identity behind `TEST_BIND_GATE` without binding the listener. It remains at `EXACT_IDENTITY_FOUND`, reports health false and `listenerProofEverEligible=false`, sends `0 TERM`, keeps `dispatchSafe=false`, and tears down only its test-owned session.

## Verification

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -W error::ResourceWarning -m unittest \
  tools.navigator-upstream.fixtures.synthetic-integration.test_forced_signal_supervisor.ListenerSocketProbeTest -v
# PASS: 1/1

INT001_TEST_PID_NAMESPACE=1 PYTHONDONTWRITEBYTECODE=1 python3 -W error::ResourceWarning -m unittest \
  tools.navigator-upstream.fixtures.synthetic-integration.test_forced_signal_supervisor.ProductionLikeFullSupervisorJavaSeamTest.test_real_harness_nesting_completes_full_supervisor_one_term_path \
  tools.navigator-upstream.fixtures.synthetic-integration.test_forced_signal_supervisor.ProductionLikeFullSupervisorJavaSeamTest.test_real_harness_nesting_exact_identity_without_listener_fails_closed -v
# PASS: host PID namespace 2/2

PYTHONDONTWRITEBYTECODE=1 python3 -W error::ResourceWarning -m unittest \
  tools.navigator-upstream.fixtures.synthetic-integration.test_forced_signal_supervisor.ProductionLikeFullSupervisorJavaSeamTest.test_real_harness_nesting_completes_full_supervisor_one_term_path \
  tools.navigator-upstream.fixtures.synthetic-integration.test_forced_signal_supervisor.ProductionLikeFullSupervisorJavaSeamTest.test_real_harness_nesting_exact_identity_without_listener_fails_closed -v
# PASS: isolated PID namespace 2/2

PYTHONDONTWRITEBYTECODE=1 python3 -W error::ResourceWarning -m unittest \
  tools.navigator-upstream.fixtures.synthetic-integration.test_forced_signal_supervisor -v
# PASS: 97/97

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

PYTHONPYCACHEPREFIX=temp/test-artifacts/BUG-009-r8-mapped-loopback-pyc python3 -m py_compile \
  tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py \
  tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py
# PASS

git diff --check
# PASS

# Scoped high-confidence secret scan: PASS, 0 matches.
# Exact test-owned production-like Java residue check: PASS, 0 processes.
```

## Test-Owned Residue Handling

Before the final gate, two earlier happy-seam Java processes were found. Exact test runId, trusted Java executable, repository test-artifact path, UID, process group/session and start-tick proof established that both belonged only to prior production-like tests. TERM was sent only to those two test-owned process groups; both exited. The final residue check found zero matching test-owned Java processes. No Runtime 8 process, shared service, Worker, TMS or SIM process was touched.

## Boundary

This record is offline repair evidence only. Runtime 4/5/6/7/8 remain permanently consumed, Runtime 9 is not yet authorized, and BUG-009 AC-2/AC-3 remain open. It does not prove Provider readiness, Worker Gateway external readiness or production readiness. `NAVIGATOR_EXTERNAL_ENABLED=true` remains only the disposable loopback `/api/v1/open/**` route gate, and `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false` remains mandatory.
