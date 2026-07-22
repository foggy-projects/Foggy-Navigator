---
doc_type: test-record
version: 1.4.3-SNAPSHOT
ticket: BUG-009
date: 2026-07-21
result: offline-pass-runtime-not-authorized
---

# BUG-009 exercise-descendant candidate-domain offline verification

## Boundary

- This record covers only the approved offline candidate-domain implementation and review. No rehearsal, runId creation/replacement, historical failed-run artifact, process/Docker detail, real profile/credential, shared `8112`, TMS/SIM, Worker, Gateway, Pool, identity, Codex route, external or production target was accessed.
- No run's `private/`, artifact `children/`, log, profile, payload or process detail was read. The live procfs task-child relation used by test-owned topology proof is not a historical run artifact and no process value is retained here.
- `NAVIGATOR_EXTERNAL_ENABLED=true` remains only a disposable Open API route gate. `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false` remains mandatory. Neither is Provider, Gateway external or production readiness evidence.

## Implemented proof boundary

- Candidate discovery is attempted only after a fresh exact proof of the exercise parent.
- The candidate domain is the bounded transitive descendant set rooted at that parent, collected from every live task's procfs child relation rather than from a global current-user process identity scan.
- Each domain node is bound to PID, parent edge and start ticks. Task-set churn, unavailable/malformed in-domain data, PID reuse, parent/start-tick drift, cycles and task/process/depth bounds fail closed.
- Two complete domain snapshots must be identical before any Launcher candidate identity is evaluated.
- Out-of-domain process identities are not probed by candidate discovery. Candidate FD proof and the global current-user unique socket-holder check remain unchanged, as do exact argv/cwd/executable/lineage, A/B/final reproof, the signal-mask commit point and the at-most-one parent TERM rule.

## Offline validation

- `python3 -m unittest tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py` — PASS, 79/79.
- `(cd business-agent-module/integration-tests && env -u INT001_SYNTHETIC_UPSTREAM_HARNESS -u INT001_RUNTIME_PROBE npm run test:synthetic)` — PASS, 94 passed / 1 skipped.
- `(cd business-agent-module/integration-tests && npm run typecheck)` — PASS.
- `bash -n tools/navigator-upstream/scripts/synthetic-upstream-harness.sh tools/navigator-upstream/scripts/synthetic-upstream-bootstrap.sh tools/navigator-upstream/scripts/synthetic-upstream-runtime-audit.sh` — PASS.
- `PYTHONPYCACHEPREFIX=temp/test-artifacts/BUG-009-descendant-domain-pyc python3 -m py_compile tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py tools/navigator-upstream/fixtures/synthetic-integration/test_forced_signal_supervisor.py` — PASS.
- `git diff --check` — PASS.
- Scoped high-confidence secret scan over BUG-009 changed surfaces — PASS, `0 matches`.

## Independent review

- Security review: PASS, no blocking finding. It confirmed full-task rooted traversal, two identical snapshots, exact domain identity binding, in-domain fail-closed behavior, unchanged global socket-holder proof, A/B/final reproof, signal-mask commit point and at-most-one TERM.
- Test-matrix review: PASS. It independently reran 79/79 Python tests and confirmed the prior blocking gaps for task-set churn, domain limits/cycle, double-snapshot drift/failure and domain failure mapping were closed.
- Non-blocking follow-ups are limited to symmetric task-disappearance churn, duplicate child-edge defense-in-depth and clearer out-of-domain holder test naming. They do not change current authorization or denial behavior.

## Conclusion

- The approved offline exercise-descendant candidate-domain slice is complete and preserves fail-closed ownership and dispatch semantics.
- This result does not satisfy BUG-009 AC-2/AC-3, does not authorize a fresh rehearsal, and does not make INT-001, real SIM/TMS integration, Provider, Worker Gateway or production ready.
- Overall BUG-009 remains `NEEDS_REPLAN`. The next step requires a separate Project Owner decision on exactly one fresh loopback-only rehearsal; absent that decision, no runtime action is permitted.
