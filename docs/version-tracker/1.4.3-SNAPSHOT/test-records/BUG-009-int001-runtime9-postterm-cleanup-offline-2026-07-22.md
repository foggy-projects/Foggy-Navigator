# BUG-009 Runtime 9 Post-TERM Cleanup Offline Record

Date: 2026-07-22
Scope: offline harness source/tests only
Runtime authorization: none

## Result

Runtime 9 remains permanently `CONSUMED_FAIL_CLOSED`. Its allowed redacted evidence established controlled health, exact parent/listener proof, `FULL_ELIGIBLE`, exactly one TERM and `dispatchSafe=true`, followed by outer `EXIT_2`, no accepted receipt, root residue `1` and Docker residue `2/1/1`. No Runtime 9 private, child, log, profile, payload, process or Docker detail was read.

Static control-flow review identified two independent post-TERM failure modes:

1. A delegated child cleanup that correctly retains its reservation can make receipt adoption call the strict reservation-absence assertion. That assertion uses `die()`, which escaped the signal handler and replaced the required terminal `EXIT_128` with `EXIT_2`.
2. After exact child ownership proof, the child can exit naturally before the TERM syscall commits. The previous path treated that syscall failure as cleanup failure without first proving whether the exact recorded PID was already dead.

## Regression-First Correction

- `assert_cleaned_cleanup_receipt()` still requires a strict safe registry and exact reservation absence, but now runs expected-stage validation, shared-lock acquisition, registry/reservation validation, receipt file validation and fixed-schema parsing in one controlled subshell. The lock covers the complete proof. A retained reservation or unsafe registry still rejects the receipt and is not repaired or released; an internal `die()` can no longer overwrite the outer signal exit contract.
- `stop_owned_child()` accepts the narrow TERM commit race only when the exact recorded PID is independently proven dead after the syscall failure. It then removes only that child's metadata. A live, inaccessible or substituted process remains fail closed, and no KILL is sent on this accepted dead-process path.
- A four-service lifecycle seam uses an isolated artifact root, a real reservation, four test-owned `setsid` services, and the real child start/stop plus receipt/reservation finalization path. Profile loading is replaced by fixed non-secret test values; Docker ownership/down/residue and manifest writing remain offline stubs. It proves one outer TERM, TERM observation by all four services, `CLEANED/SIGNAL + HOLD_SIGNAL_RECEIVED`, exact reservation absence and zero non-receipt run-root residue.

The two focused regressions were red before the corrections and green afterward:

- `keeps the outer signal exit at 128 when child cleanup fails closed with its reservation retained`
- `accepts a TERM commit race only after the exact owned child PID is proven dead`

The first independent code/security review then found that shared-lock acquisition remained outside the controlled boundary. A third regression, `keeps the signal exit at 128 when receipt-adoption lock or registry setup fails closed`, failed with actual `2` against expected `128`. After moving the complete adoption proof under one lock-holding controlled subshell, it passed with `128`.

## Final Offline Gate

```bash
pnpm --dir business-agent-module/integration-tests exec vitest run \
  --config vitest.synthetic.config.ts \
  tests/05-synthetic-upstream-bootstrap-safety.test.ts \
  -t "keeps the signal exit at 128 when receipt-adoption lock or registry setup fails closed"
# Before the final correction: FAIL, expected 128, actual 2.
# After the final correction: PASS, 1 passed / 87 skipped.

pnpm --dir business-agent-module/integration-tests exec vitest run \
  --config vitest.synthetic.config.ts \
  tests/05-synthetic-upstream-bootstrap-safety.test.ts
# PASS: 88/88

pnpm --dir business-agent-module/integration-tests run test:synthetic
# PASS: 112 passed / 1 skipped

PYTHONDONTWRITEBYTECODE=1 python3 -W error::ResourceWarning -m unittest \
  tools.navigator-upstream.fixtures.synthetic-integration.test_forced_signal_supervisor -v
# PASS: 97/97

pnpm --dir business-agent-module/integration-tests run typecheck
# PASS

bash -n \
  tools/navigator-upstream/scripts/synthetic-upstream-harness.sh \
  tools/navigator-upstream/scripts/synthetic-upstream-bootstrap.sh \
  tools/navigator-upstream/scripts/synthetic-upstream-runtime-audit.sh
# PASS

PYTHONPYCACHEPREFIX=temp/test-artifacts/BUG-009-runtime9-postterm-pyc python3 -m py_compile \
  tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py \
  tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py
# PASS

git diff --check
# PASS

SCOPED=(
  tools/navigator-upstream/scripts/synthetic-upstream-harness.sh
  tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py
  tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py
  business-agent-module/integration-tests/tests/05-synthetic-upstream-bootstrap-safety.test.ts
  docs/version-tracker/1.4.3-SNAPSHOT/README.md
  docs/version-tracker/1.4.3-SNAPSHOT/runbooks/INT-001-synthetic-upstream-integration-harness.md
  docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-009-int001-forced-signal-owned-cleanup.md
)
secret_matches="$({
  git diff --unified=0 -- "${SCOPED[@]}" | sed -n '/^+++ /d; s/^+//p'
  for f in docs/version-tracker/1.4.3-SNAPSHOT/test-records/BUG-009-*; do
    git ls-files --error-unmatch "$f" >/dev/null 2>&1 || sed -n '1,$p' "$f"
  done
} | rg -c -- '-----BEGIN ([A-Z ]+ )?PRIVATE KEY-----|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{35}|gh[pousr]_[0-9A-Za-z]{30,}|sk-[A-Za-z0-9_-]{20,}|Bearer[[:space:]]+[A-Za-z0-9._~+/-]{24,}|eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}' || true)"
test "${secret_matches:-0}" = 0
# PASS: 0 matches

java_residue=0
for proc in /proc/[0-9]*; do
  [ -r "$proc/cmdline" ] || continue
  exe="$(readlink "$proc/exe" 2>/dev/null || true)"
  case "$exe" in */java|*/java.exe) ;; *) continue ;; esac
  cmd="$(tr '\0' ' ' < "$proc/cmdline" 2>/dev/null || true)"
  case "$cmd" in
    *int001-test-production-like-java*|*int001-test-production-like-no-listener*|*int001-test-owned-authoritative-argv*)
      java_residue=$((java_residue + 1))
      ;;
  esac
done
test "$java_residue" = 0
# PASS: 0 processes
```

## Boundary and Next Gate

No Docker runtime, real Navigator service, shared `8112`, TMS/SIM system, credential, Worker, Gateway, Pool, identity, Codex route or production configuration was used. `NAVIGATOR_EXTERNAL_ENABLED=true` remains only the disposable loopback `/api/v1/open/**` route gate, and `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false` remains mandatory.

This offline result does not satisfy BUG-009 AC-2/AC-3 and does not authorize Runtime 10. The only next step is independent read-only code/security, test/runtime-readiness and canonical/docs review. A new runtime may be considered only after all three pass and a distinct exact one-shot freeze is recorded consistently.
