---
workitem: NAVI-CORE-001-S4-03A3P
status: IMPLEMENTED_REVIEWED
date: 2026-08-04
baseline: c62ae3fb
coordination_freeze: 1d9415f
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: TERMINATION_CAPABILITY_ADMISSION_AND_AMBIGUITY_BOUNDARY
---

# NAVI-CORE-001 S4-03A3P termination capability admission

Canonical Provider termination now rejects known unsupported normal or force cancellation while
the immutable plan is being resolved, before a command receipt can be prepared. The additive
`FORCE_CANCEL_TASK` capability distinguishes Claude's real owner-force contract from Provider
implementations that only support normal cancellation. Empty capability sets retain the SPI's
normal-cancel legacy probe contract; force always requires explicit normal and force capabilities.

Canonical terminal Tasks still produce no-effect terminal plans before Provider availability or
capability checks. Provider-less A2A cancellation retains the required `A2aAgent.cancelTask`
contract, while A2A force remains unsupported before Agent resolution.

Once an effect permit has actually started, any subsequent effect, revalidation, or result-record
failure is no longer exposed as a definite unsupported or Provider error. The coordinator attempts
to mark the receipt with `TERMINATION_OUTCOME_UNKNOWN` and reports the stable outward code
`TERMINATION_EFFECT_AMBIGUOUS`; the original failure remains diagnostic cause only. A receipt or
effect permit that has started without a recorded result also returns that same code on retry, and
the retry cannot invoke the Provider effect a second time.

## Changed paths

- `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/TaskQueryCapability.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/ClaudeTaskServiceAbortGuardTest.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TaskOperationRouter.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TaskTerminationCommandCoordinator.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/TaskTerminationCommandCoordinatorTest.java`
- this work item

## Validation evidence

- After the first independent review correction, the affected SPI, session, and Claude production
  compile passed in `24.192 s`.
- Ten exact selectors passed `10/10` with zero failure, error, or skip in `36.039 s`: Claude force
  capability truth; known unsupported normal rejection; empty-set legacy normal admission; force
  rejection and admission; terminal no-op; pre-permit drift; started/ambiguous replay; terminal
  result; and post-permit effect/record/mark failure normalization, including same-ID retry with
  zero second Provider effect.
- The first independent final review identified that a failed ambiguity mark could otherwise expose
  `TERMINATION_EFFECT_AMBIGUOUS` initially and `TERMINATION_EFFECT_ALREADY_STARTED` on retry. The
  correction remained inside the frozen coordinator/test paths and normalized both receipt and
  begin-effect started states to the stable ambiguous code.
- No whole test class, module/reactor suite, Controller, Shared/OpenAPI path, Worker, database, E2E,
  live Provider, or final joint full-validation cycle was run for this slice.

## Independent final review

Three read-only reviewers independently accepted the corrected eight-path diff with no remaining
P1/P2. They confirmed terminal-first ordering, normal/force capability truth, the Provider-less A2A
boundary, pre-permit versus post-permit separation, stable started/ambiguous retry codes, and zero
second Provider effect for the same request. Reviewers did not modify files, run tests, inspect
historical data, or read the user-owned `BOOT-INF/` directory.

## Compatibility and residual risk

No production Controller calls the canonical coordinator yet. Shared and OpenAPI termination retain
their current paths until their dedicated ingress slices. A historical LangGraph projection with a
missing Provider identity could still enter the generic A2A contract and fail ambiguously; this
slice does not backfill or repair such data.

No service or Worker was started. No business/runtime or historical data was read or mutated, and
no repair, backfill, replay, reconciliation, cleanup, or deletion was performed. SIM, TMS, and the
user-owned `BOOT-INF/` directory were not changed.
