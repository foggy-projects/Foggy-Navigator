---
doc_type: test-record
version: 1.4.3-SNAPSHOT
ticket: BUG-009
date: 2026-07-21
result: offline-pass-runtime-not-authorized
---

# BUG-009 authoritative Launcher argv offline verification

## Boundary

- This record covers only the approved offline diagnostic slice. No rehearsal, new runId, historical failed-run artifact, Docker object, real profile/credential, shared `8112`, TMS/SIM, Worker, Gateway, Pool, identity or Codex route was accessed.
- The test-owned Java process and its temporary JAR lived under `temp/test-artifacts/BUG-009/` and were removed by the test. No PID, argv, cwd, executable path or process value is retained in this record or any supervisor artifact.

## Failing regression before the fix

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest \
  tools.navigator-upstream.fixtures.synthetic-integration.test_forced_signal_supervisor.ListenerCandidateIdentityTest.test_non_trusted_executable_is_not_reported_as_an_argv_mismatch
```

- Result before correction: FAIL, expected `NO_TRUSTED_JAVA_CANDIDATE` but received `NO_EXACT_ARGV_MATCH`.
- Meaning: the previous scan classified an ordinary current-user non-Java process as an argv mismatch before proving it used the trusted Java executable. It did not establish that a trusted Launcher Java candidate had mismatched argv.

## Correction and positive/negative contract proof

- Candidate identity now proves the trusted Java executable before reading argv, then requires exact argv and cwd, and re-reads the executable before lineage/stability completion.
- The new fixed enum `NO_TRUSTED_JAVA_CANDIDATE` is non-authorizing and redacted. It adds no process value to stdout, projection or receipt.
- A real test-owned process used `setsid -> env -i -> resolved Java -> -Dint001.run-id -> -jar -> --spring.profiles.active=mock`. Its `/proc` identity reached `EXACT_CANDIDATE_FOUND`.
- Missing application argument, reordered JVM argument and extra application argument variants all returned `NO_EXACT_ARGV_MATCH`; exact matching was not weakened.
- The production harness library capture now asserts the complete five-element Java argv, including `--spring.profiles.active=mock`, so a future harness-side drift fails the offline synthetic suite.

## Offline validation

- `PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py` — PASS, 67/67.
- `(cd business-agent-module/integration-tests && env -u INT001_SYNTHETIC_UPSTREAM_HARNESS -u INT001_RUNTIME_PROBE npm run test:synthetic)` — PASS, 94 passed / 1 skipped.
- `(cd business-agent-module/integration-tests && npm run typecheck)` — PASS.
- `bash -n tools/navigator-upstream/scripts/synthetic-upstream-harness.sh tools/navigator-upstream/scripts/synthetic-upstream-bootstrap.sh tools/navigator-upstream/scripts/synthetic-upstream-runtime-audit.sh` — PASS.
- `python3 -m py_compile tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py` — PASS.
- `git diff --check` — PASS.
- Scoped added-line high-confidence secret scan — PASS, `0 matches`.
- Independent read-only security review — PASS, no blocking finding; its complete-five-element harness capture suggestion was incorporated before final verification.

## Conclusion

- The authoritative five-element Launcher argv contract is internally consistent. The recorded runtime value `NO_EXACT_ARGV_MATCH` was over-broad because it could be produced without any trusted Java candidate.
- The correction improves diagnostic truth and reduces process inspection while preserving fail-closed exact identity, socket, start-tick and one-TERM gates.
- This offline result does not satisfy BUG-009 AC-2/AC-3, does not authorize another runtime, and does not make INT-001, Provider, Worker Gateway or production ready.
