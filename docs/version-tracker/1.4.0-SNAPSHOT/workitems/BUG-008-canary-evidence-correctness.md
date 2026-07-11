---
type: bug
bug_source: review-found
version: 1.4.0-SNAPSHOT
ticket: BUG-008
severity: major
status: fixed-isolated
reproduction_status: confirmed
test_strategy: unit-and-integration
automation_decision: required
owner: codex-worker-agent | codex-app-server-worker
---

# Canary Evidence Correctness

## Background

P3 collector review found three paths that could corrupt a production decision without changing task execution:

1. APP_SERVER progress/completion could replace the requested model such as `codex-ultra` with the Worker-reported base model, moving a valid Ultra task out of the exact cohort.
2. A terminal task with a missing or unknown runtime instance could be counted in the terminal denominator even though it is an affinity violation and not a valid cohort sample.
3. A stale local collector lease could be reclaimed without an exclusive reclaim claim, allowing two collectors to race on one checkpoint.

## Correctness Contract

- APP_SERVER tasks preserve the requested model for cohort attribution; SDK tasks retain the legacy Worker-reported model behavior.
- Missing/unknown runtime instances are deduplicated into a sanitized `affinity_violations` collection. They fail the zero-tolerance affinity gate but never increase the terminal-task denominator.
- Lease reclaim fails closed for live local owners, cross-host owners, and an existing reclaim claim. Only a dead local PID with an exclusive claim may be reclaimed.
- Checkpoints store digests and stable reasons only; raw task IDs, instance IDs, endpoints, prompts and credentials remain excluded.

## Code Inventory

- `addons/codex-worker-agent/.../CodexTaskService.java`
- `addons/codex-worker-agent/.../CodexTaskServiceTest.java`
- `tools/codex-app-server-worker/src/operations/canary-soak.ts`
- `tools/codex-app-server-worker/tests/canary-soak.test.ts`

## Fix Checklist

- [x] Preserve the requested model for APP_SERVER progress and completion.
- [x] Preserve legacy SDK Worker-reported model updates.
- [x] Isolate and deduplicate invalid runtime instances outside the terminal denominator.
- [x] Keep affinity violations fail-closed and sanitized.
- [x] Add an exclusive reclaim claim tied to the observed lease identity.
- [x] Refuse live-local, cross-host and already-claimed lease reclaim.
- [x] Java Codex addon `259/259` passed; the raw full reactor is blocked by Windows Surefire fork/path infrastructure while affected scoped tests pass.
- [ ] Collect production evidence; P3 remains `0/50`, `0/72h`, `0/2`.

## Verification

- Model regression proves APP_SERVER retains `codex-ultra` while SDK_EXEC continues accepting the reported model.
- Canary regression proves one valid terminal task remains denominator `1` while two invalid-instance records produce two sanitized affinity violations and fail the affinity gate.
- Lease regression proves only an unclaimed dead local PID is reclaimed; live, remote and claimed owners remain untouched.
- Final Worker automation passes `200 total / 193 passed / 7 platform-skipped / 0 failed`; v5 release SHA-256 is `b6271e5a3220b0253d97b6d05c9fe5f5561331655e27faccd8ad254fbf6c31d9`. P3 production evidence remains unstarted.

## References

- [OPT-001 progress](./OPT-001-independent-codex-app-server-worker-progress.md#acceptance-defect-closure)
