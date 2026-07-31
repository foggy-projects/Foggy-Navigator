---
acceptance_scope: feature
version: 1.4.3-SNAPSHOT
target: ARCH-001-second-rejection-remediation
status: rejected
decision: rejected
signed_off_by: independent-reviewer-codex
signed_off_at: 2026-07-31
reviewed_by: independent-reviewer-codex
blocking_items:
  - ARCH001-R3-B1-VERTICAL-CHAIN-NOT-EXECUTED
  - ARCH001-R3-B2-ADMISSION-FENCE-INCOMPLETE
  - ARCH001-R3-B3-NEVER-ACCEPTED-AUTHORITY-NOT-PRODUCIBLE
  - ARCH001-R3-B4-REAL-CODEX-COMMAND-CONTRACT-NOT-EXECUTED
  - ARCH001-R3-B5-SLICE8-NOT-CONNECTED
follow_up_required: yes
evidence_count: 15
assurance_level: elevated
---

# ARCH-001 Second-remediation Independent Resignoff

## Document Purpose

- intended_for: signoff-owner / reviewer / project-root-session
- purpose: 对 ARCH-001 第二轮拒签 remediation 候选形成新的、独立、可复核的
  elevated assurance 签收结论。
- independence: 本审查不继承实现会话的 `READY_FOR_SIGNOFF` 结论，只复用经候选身份、
  源码、断言、报告和环境前提核对后仍适用的证据。
- historical_evidence_preserved:
  - `ARCH-001-independent-signoff-2026-07-31.md`
  - `ARCH-001-independent-resignoff-2026-07-31.md`

## Background

- delivery_spec:
  `docs/version-tracker/1.4.3-SNAPSHOT/workitems/ARCH-001-unified-session-task-lifecycle-owner.md`
- target_outcome: 独立判断最新 B1–B6 和 public compatibility must-pass 是否真实关闭。
- signoff_scope: 当前 `main` 分支、HEAD
  `ce3e5022ed06be601e89c5ae4251796b0c859e0b` 之上的未提交工作区候选。
- critical_outcomes: B1–B6、public compatibility、activation/security/authority 边界。
- non_blocking_or_waivable_items: 无；本轮没有 owner waiver。
- review_start_state:
  - cwd: `/home/sa/workspace/Foggy-Navigator`
  - branch: `main`
  - HEAD: `ce3e5022ed06be601e89c5ae4251796b0c859e0b`
  - canonical input status: `READY_FOR_SIGNOFF`
  - tracked changed files: 47（其中 1 个为 canonical 文档）
  - untracked files: 8
  - product/test candidate files: 54
  - tracked diff: 2,084 insertions / 304 deletions
  - product/test candidate fingerprint:
    `ca6341f3fcb8c7353865fa96dab0c4275952eb1b8a8563bf40c57d96a78e39e5`

## Acceptance Basis

- approved delivery spec: canonical work item 全文，包括批准边界、AC、此前拒签、首次
  remediation、第二轮 remediation 和验证预算。
- changed paths / diff:
  - provider-neutral lifecycle SPI and Worker discovery;
  - Session lifecycle owner, Sentinel, proof/outbox, terminal/cleanup and enrollment;
  - public runtime termination receipt/coordinator/dispatcher;
  - Codex Java adapter/service and Node lifecycle store/router fixture;
  - MySQL migration executable fixture;
  - tests and canonical delivery record.
- source and contract review:
  - root and relevant module `AGENTS.md`;
  - production Java/TypeScript, repositories, entities, tests, Maven/npm manifests;
  - forward/rollback SQL and MySQL Testcontainers fixture;
  - both historical rejection evidence files.
- test records:
  current Surefire XML, implementation canonical command ledger, and retained npm/Maven artifacts
  were cross-checked. Raw-artifact retention gaps are classified separately below.
- experience evidence: not applicable within the approved no-live/no-real-controller boundary.
- migration / compatibility evidence: production SQL, Testcontainers fixture, Open SDK diff and
  receipt-disabled compatibility tests were inspected.

## Goal and Scope Conformance

| Item | Expected | Delivered / observed | Result |
|---|---|---|---|
| approved authority | unchanged | no approved authority expansion was accepted by this review | pass |
| Worker lifecycle v1 wire | unchanged | additive Java/Node lifecycle changes remain inside v1 shape | pass |
| Open SDK public DTO/wire | unchanged | `git diff HEAD -- navigator-open-sdk` is empty | pass |
| additive migration strategy | unchanged | production forward/rollback migration files are not in the candidate diff | pass |
| security boundary | unchanged | no credential/token material was accessed or recorded | pass |
| activation boundary | CLOSED | no activation config change and no non-fixture ENFORCED evidence | pass |
| B1–B6 closure | every non-waivable must-pass proven | B1–B5 retain concrete production/test-topology failures | **fail** |
| historical aggregate migration/repair | prohibited | none found | pass |

The candidate does not require reviewer-approved boundary changes. The rejection is against the
already approved contract, not a request to redefine it; `NEEDS_REPLAN` is therefore not used.

## Implementation Quality

- scope and changed surface: changes are concentrated in the declared lifecycle, receipt,
  Codex and evidence surfaces. No unrelated sibling repository was accessed.
- maintainability and duplication: real scheduled Sentinel and termination outbox components now
  exist, but the claimed integrated evidence is assembled from separate fixtures whose seams
  bypass the exact production adapters/handlers under review.
- error handling and edge cases: several fail-closed branches exist. However receipt admission
  does not fence all required identity fields, and never-accepted normalization trusts a
  fabricated fact type/reason without a production durable REJECTED/PRE_EFFECT producer.
- contract, data and compatibility: public compatibility is retained. The internal exact-binding
  and continuous Slice 8 proof obligations are not met.
- terminology and documentation: the canonical implementation summary overstates the executed
  paths. In particular, it calls the Slice 8 evidence continuous when the test performs negative
  Node route probes and then manually seeds a separate JPA enrollment path.

## B1–B6 Evidence Matrix

| Item | Classification | Risk | Evidence | Reused / New | Result |
|---|---|---|---|---|---|
| B1 lifecycle owner vertical chain | core-blocker | critical | Production reservation and scheduled Sentinel pieces exist. `TaskDispatchFacade` reserves before provider create/resume effect. `WorkerLifecycleSentinelScheduler` can discover configured Workers and the reconciliation service has probe/inventory/events/commit/ACK stages. But `TaskLifecycleOwnerVerticalIntegrationTest` seeds Session/Task directly, invokes `owner.enrollInventoryTask(...)` in `@BeforeEach`, and feeds hand-built `NormalizedLifecycleFact` objects directly into `owner.ingestNormalizedBatch(...)`; it does not execute scheduler/Sentinel/`WorkerLifecyclePort`. `IsolatedEnforcedLifecycleContractTest` executes Sentinel with its own test `HttpLifecyclePort`, then separately calls `persistCanonicalTaskAndProof()` and `enrollment.enroll(...)`; it does not carry a Worker fact through reducer/terminal/cleanup. The required one-path scheduler → port → repository → reducer → snapshot → terminal → tombstone → cleanup → typed projection proof is absent. | source/Assertions new; 484-test report reused | **fail** |
| B2 receipt, outbox and writer proof | core-blocker | critical | A real repository-backed scheduled `RuntimeTerminationOutboxDispatcher` and proof authorization service exist; the public business vertical also proves transaction rollback/provider count zero in a JPA fixture. However `RuntimeTerminationIntent` contains no ownership mode, state generation or instance epoch. `requireOwnerAdmission` compares Task and Worker generation but does not compare instance epoch, and it does not require Session availability/conflict readiness. The receipt binding digest is a separate five-string hex SHA-256 over Task/provider/Worker/providerTask/request ID, not the exact Worker-v1 JCS binding containing mode/generation/epoch/dispatch/operation/capability. Therefore the required exact receipt admission fence is not complete. | source new; Claude 464-test and business vertical reports reused | **fail** |
| B3 terminal authority and cleanup | core-blocker | critical | Reducer/commit code can consume `TASK_NEVER_ACCEPTED_CONFIRMED`, and direct-owner tests cover terminal projection/conflict behavior. No production source emits that fact: repository-wide non-test search finds only the Java enum/reducer/consumer; Node only constructs `ACCEPTED/PREPARED` dispositions with `never_accepted_proof:false`. Owner normalization checks an allowlisted reason but does not receive or validate durable `acceptance_disposition=REJECTED`, `effect_phase=PRE_EFFECT` or `never_accepted_proof=true`. The test fabricates the normalized fact. Cleanup applicability is also not exact for the receipt participant: `hasDurableTaskOperationReceipt(taskId, operation)` and `refreshCompletedTaskOperation(...)` select the latest receipt by Task plus generic operation type, not the terminal operation/client request identity. Thus exact durable never-accepted authority and exact cleanup resource binding are not proven. | source/static producer search new; vertical reports reused | **fail** |
| B4 Codex Java/Node lifecycle v1 | core-blocker | critical | Node store has PREPARED → EFFECT_STARTED → RESULT_OBSERVED phases and durable facts. The fixture mounts production routers. But `CodexWorkerLifecycleNodeContractIntegrationTest` only executes Java adapter probe/inventory/events/PUT ACK; it never invokes query, POST abort or dispatch status. `CodexTaskServiceTest#exactCodexBizWorkerRunsReadinessTerminationAndSameRequestReplay` is still a Mockito test with `@Mock CodexWorkerClient` and verifies the mock abort. The only real Node command probes in `IsolatedEnforcedLifecycleContractTest` are an invalid query expecting 400 plus missing Task/dispatch expecting 404; they do not prove accepted create/resume/termination, disposition persistence, provider-once, result facts or Sentinel ingestion. The exact codex-biz test therefore violates the explicit “cannot mock `CodexWorkerClient`” must-pass. | source/Assertions new; Codex 496-test report reused | **fail** |
| B5 connected Slice 8 | core-blocker | critical | The claimed Slice 8 test does GET inventory, negative query/abort/status probes, constructs a test-only `HttpLifecyclePort`, then manually seeds canonical Task/proof and calls enrollment. It never traverses owner/outbox authorization, real Java command adapter, successful Node provider effect, durable disposition/fact, Sentinel ingestion and terminal cleanup as one chain. `BusinessLifecycleTerminalVerticalIntegrationTest` uses a local `ProviderFixture`; `WriterExclusivityProofConcurrencyIntegrationTest` represents provider effects with `AtomicInteger`, and its compatibility authorization command contains only a Task reference. These are all patterns explicitly disallowed as decisive Slice 8/loss-first/authorization-first evidence. | source/Assertions new; focused and module reports reused | **fail** |
| B6 MySQL runtime | process-gap (evidence retention), otherwise core criterion | major | The repo-owned Testcontainers fixture executes fresh/repeated forward SQL, legacy row retention, Hibernate validate, metadata checks, empty-state rollback, and independent Worker/Session/Task/writer/reference/PREPARED/CLAIMED/EFFECT_STARTED rollback blockers. Canonical records one current-candidate MySQL 8.0.44 run, 1/0/0/0. The later ordinary Session suite overwrote its Surefire XML with the expected opt-in skip, so the raw positive XML was not retained. The user-provided execution record, current fixture assertions, unchanged production SQL and canonical command ledger are sufficient to reuse B6 for this rejection decision; raw report retention remains a process gap. | reused | pass |
| public compatibility | core criterion | critical | Open SDK has no candidate diff and its current report is 203/0/0/0. `RuntimeTaskTypedContractServiceTest` retains the receipt-disabled same-request two provider calls and AMBIGUOUS/fail-closed reconciliation assertions. Receipt-disabled code still calls the provider directly per HTTP request. No SDK/CLI version or publication surface changed; no production migration/repair changed. SHADOW remains gated from lifecycle-owner provider effects. | source + current reports reused | pass |

## Detailed Blocking Evidence and Minimal Reproduction

### ARCH001-R3-B1-VERTICAL-CHAIN-NOT-EXECUTED

1. Open
   `session-module/src/test/java/com/foggy/navigator/session/lifecycle/TaskLifecycleOwnerVerticalIntegrationTest.java`.
2. Observe direct fixture setup at lines 90–121 and direct owner ingestion at lines 124–169.
3. No scheduler, Sentinel, `WorkerLifecyclePort`, inventory/events or ACK is used.
4. Open
   `session-module/src/test/java/com/foggy/navigator/session/lifecycle/IsolatedEnforcedLifecycleContractTest.java`.
5. Observe Node negative route probes at lines 92–166; a custom test port at lines 276 onward;
   and the separate manual `persistCanonicalTaskAndProof()` plus `enrollment.enroll(...)` at
   lines 168–205.
6. Result: no test executes the required continuous vertical owner chain. Existing tests can pass
   while a production seam between Sentinel/port and owner/reducer/terminal remains broken.

### ARCH001-R3-B2-ADMISSION-FENCE-INCOMPLETE

1. Open
   `navigator-spi/src/main/java/com/foggy/navigator/spi/lifecycle/RuntimeTerminationIntentPort.java`.
2. Its intent record carries IDs and one digest, but not ownership mode, state generation or epoch.
3. Open
   `session-module/src/main/java/com/foggy/navigator/session/lifecycle/TaskTerminationIntentRecorder.java`
   lines 385–453.
4. Admission compares Task generation to Worker generation, but not Task/Worker epoch; Session
   readiness/conflict is not fenced.
5. Open
   `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/RuntimeTerminationAcceptanceCoordinator.java`
   lines 120–140.
6. The stored receipt digest omits mode, generation, epoch, dispatch, operation and signed
   capability binding. It is not the exact Worker-v1 JCS binding used by the Node command.
7. Result: a stale/mismatched epoch or non-ready Session can pass the receipt-admission fields
   that are actually checked.

### ARCH001-R3-B3-NEVER-ACCEPTED-AUTHORITY-NOT-PRODUCIBLE

1. Run:
   `rg -n "TASK_NEVER_ACCEPTED_CONFIRMED" --glob '!**/src/test/**' --glob '!docs/**' --glob '!temp/**' .`
2. Only Java fact type/reducer/consumer sites are returned; there is no Worker producer.
3. Run:
   `rg -n "never_accepted_proof\\s*:\\s*true|acceptance_disposition\\s*:\\s*'REJECTED'|effect_phase\\s*:\\s*'PRE_EFFECT'" tools/codex-agent-worker/src tools/codex-agent-worker/tests addons/codex-worker-agent/src/main session-module/src/main`
4. No producer is returned; only the PRE_EFFECT union type is present.
5. `TaskLifecycleOwnerVerticalIntegrationTest#exactDurableNeverAcceptedFactUsesSameTerminalCleanupAndLaneRelease`
   constructs the normalized fact in memory.
6. `TaskLifecycleOwnerService.normalizedFact(...)` checks only exact Task binding and an allowlisted
   reason before creating `exactPreEffectRejection`; it cannot prove a durable Worker
   REJECTED/PRE_EFFECT disposition.
7. Result: the approved never-accepted source cannot be reached from production Worker evidence.

### ARCH001-R3-B4-REAL-CODEX-COMMAND-CONTRACT-NOT-EXECUTED

1. `CodexWorkerLifecycleNodeContractIntegrationTest` contains one test and calls only
   `probe`, `inventory`, `events` and `acknowledge`.
2. `CodexTaskServiceTest` declares `@Mock CodexWorkerClient`; the exact codex-biz test stubs and
   verifies that mock.
3. The isolated test only expects HTTP 400/404 from the real command routes.
4. Result: there is no successful Java ↔ real Node query/POST-abort/status test proving the
   accepted disposition/providerTask/outbox/fact contract.

### ARCH001-R3-B5-SLICE8-NOT-CONNECTED

1. Follow the isolated test from its inventory GET through its final assertion.
2. The Node route traffic and the JPA enrollment/proof path are separate; no successful command,
   provider once-effect, durable result fact, Sentinel owner ingestion or terminal cleanup joins
   them.
3. Open
   `WriterExclusivityProofConcurrencyIntegrationTest.java` lines 43 and 146–161.
4. Provider invocation is an `AtomicInteger`, not the real outbox dispatcher and Worker handler.
5. Result: the exact disallowed evidence substitutions remain the decisive evidence for Slice 8.

## Public Compatibility

- navigator-open-sdk public DTO/wire: unchanged; current Surefire XML is 203 tests, 0 failures,
  0 errors, 0 skipped.
- receipt-disabled same request ID twice:
  - provider attempt count: two;
  - reconciliation: `AMBIGUOUS`;
  - `requestReconciliationAvailable=false`;
  - same-request replay/recommendation flags remain fail closed.
- SHADOW legacy Codex provider wire: no owner-authorized provider effect is added for SHADOW;
  legacy path remains selected.
- SDK/CLI publication required: false.
- historical aggregate bulk migration/repair: none.

## Reused Validation Evidence

Current Surefire XML was aggregated per module after verifying zero failure/error and inspecting
the relevant test methods:

| Scope | Tests | Failures | Errors | Skipped | Use in this signoff |
|---|---:|---:|---:|---:|---|
| Session Module | 484 | 0 | 0 | 1 | reused; assertions inspected, does not close B1/B3/B5 |
| Business Agent Module | 739 | 0 | 0 | 0 | reused |
| Claude Worker Agent | 464 | 0 | 0 | 0 | reused; business vertical uses provider fixture |
| Codex Worker Agent | 496 | 0 | 0 | 0 | reused; exact command test remains mock/negative-only |
| Open SDK | 203 | 0 | 0 | 0 | reused for public compatibility |

The canonical also records:

- Node typecheck/tests: 265 tests, 263 passed, 0 failed, 2 skipped;
- Node build: exit 0;
- MySQL 8.0.44 Testcontainers: 1 test, 0 failures/errors/skips;
- affected reactor: 2,901 tests, 0 failures/errors, 4 skipped;
- launcher reactor: 3,030 tests, 0 failures/errors, 6 skipped;
- `git diff --check`: exit 0.

Raw-artifact review found two retention limitations:

1. `temp/test-artifacts/arch-001-resignoff-2026-07-31/` predates the final second-remediation
   source edits for several files; its Node log contains 264 rather than 265 tests and therefore
   is not reused as the current-candidate Node must-pass artifact.
2. The current MySQL Surefire XML was overwritten by the subsequent ordinary Session run and now
   records the expected property-gated skip. The canonical current-candidate opt-in execution and
   the user-provided execution fact are reused for B6, but preserving the positive XML/log would
   improve future auditability.

These are process gaps, not the reason for rejection. B1–B5 have direct source/test-topology
counterevidence even if every recorded suite count is accepted as true.

## New Review and Minimal Revalidation

No product test was rerun. Re-executing the same selected or full suites could not change the
decision because the blocking tests pass while omitting or replacing the mandatory production
paths. Closing the blockers requires a changed candidate and new assertions/vertical evidence,
which this reviewer is not authorized to implement.

| Command / check | Exit | Result |
|---|---:|---|
| `pwd` | 0 | `/home/sa/workspace/Foggy-Navigator` |
| `git branch --show-current` | 0 | `main` |
| `git rev-parse HEAD` | 0 | expected HEAD |
| `git status --short` | 0 | 47 tracked changed, 8 untracked |
| `git diff --stat HEAD` | 0 | 47 files, 2,084 insertions, 304 deletions |
| `git diff --check` | 0 | no whitespace errors |
| current Surefire XML aggregation | 0 | module counts shown above, zero failures/errors |
| production never-accepted producer searches shown above | 0 | no durable Worker producer found |
| product/test candidate fingerprint | 0 | unchanged at review end |
| historical evidence SHA-256 verification | 0 | both historical files unchanged |

## Evidence Sufficiency

- assurance_level: elevated.
- why_existing_evidence_is_sufficient_or_not:
  - sufficient to reject: B1–B5 each has directly reviewable source/test-topology counterevidence;
  - insufficient to accept: passing suites exercise weaker paths than the non-waivable criteria.
- new_validation_that_could_change_decision: only a new candidate with the missing production
  fences/producers and a truly connected real Node/Java/Sentinel/owner/cleanup vertical test.
- expensive_validation_omitted_and_reason: launcher/full reactor and any >30-minute
  authority/replay/rehearsal were not rerun. They cannot repair missing assertions or production
  producers, and the approved launcher budget was already consumed once.

## Optional Full-Chain Recommendation

- recommendation: not-needed for this candidate
- qualifying_condition: only after a new remediation candidate closes all five blockers
- estimated_wall_clock_and_basis: not estimated; no request for an expensive run is made
- scope_and_prerequisites: connected repo-owned fixture first; live/production remains separately
  authorized and out of scope
- maximum_attempts: 1
- decision_impact: current decision unchanged
- user_approval: not-requested
- execution_status: not-run

## Waivers

| Waived Item | Authority | Reason | Bounded Impact | Non-Waivable Guards | Follow-up |
|---|---|---|---|---|---|
| none | none | no waiver granted | none | B1–B6 must-pass | none |

## Findings Classification

### Core blockers

- B1 required production vertical chain is not executed.
- B2 exact receipt admission omits required epoch/Session readiness and exact Worker-v1 binding.
- B3 never-accepted evidence has no production durable REJECTED/PRE_EFFECT producer and cleanup
  receipt lookup is not exact to the terminal operation.
- B4 exact Codex business test still mocks `CodexWorkerClient`; real Node command paths are only
  negative-probed.
- B5 Slice 8 remains disconnected and concurrency provider proof remains `AtomicInteger`-based.

### Scoped risks

- none accepted or waived. Post-acceptance deployment/pre-apply/restart risk is not reached because
  this candidate is rejected.

### Process gaps

- current positive npm and opt-in MySQL raw reports were not preserved in a candidate-specific
  evidence directory;
- retained `arch-001-resignoff` logs predate the final second-remediation edits and must not be
  presented as their raw execution record;
- current Surefire report directories were overwritten by later module runs, so they prove module
  counts but cannot independently reconstruct the exact launcher aggregate.

### Out of scope

- real/shared database pre-apply or rollback;
- Task `20260730-0e01`;
- business data, real controller/process, sibling repositories and live SIM;
- deployment, stop/start/restart/upgrade, push, tag, release;
- any first non-fixture ENFORCED aggregate.

## Deviations, Unrun Items and Residual Risk

- implementation deviations accepted by reviewer: none.
- review deviations: none; no product source/test/migration was modified.
- unrun:
  - no product test rerun;
  - no launcher/full reactor repetition;
  - no >30-minute authority/replay/rehearsal;
  - no live/production validation.
- residual risk: not a basis for an accepted-with-risks outcome. Five non-waivable blockers remain.
- activation_gate: `CLOSED`.
- first_non_fixture_enforced_aggregate: not created and remains prohibited.
- SDK/CLI publication: not required and not performed.
- runtime restart: not performed; no deployment or activation is implied.

## Final Decision

- decision: `rejected`
- rationale: B1, B2, B3, B4 and B5 are non-waivable must-pass criteria and remain unmet. The
  evidence does not merely lack detail: the current source/tests show the prohibited seams and
  substitutions directly. B6 and public compatibility passing cannot compensate for these
  lifecycle-authority blockers.
- blocking_items:
  - `ARCH001-R3-B1-VERTICAL-CHAIN-NOT-EXECUTED`
  - `ARCH001-R3-B2-ADMISSION-FENCE-INCOMPLETE`
  - `ARCH001-R3-B3-NEVER-ACCEPTED-AUTHORITY-NOT-PRODUCIBLE`
  - `ARCH001-R3-B4-REAL-CODEX-COMMAND-CONTRACT-NOT-EXECUTED`
  - `ARCH001-R3-B5-SLICE8-NOT-CONNECTED`
- follow_up_owner_and_due: implementation owner / no due date assigned by reviewer.

## Signoff Marker

- acceptance_status: rejected
- acceptance_decision: rejected
- signed_off_by: independent-reviewer-codex
- signed_off_at: 2026-07-31
- acceptance_record:
  `docs/version-tracker/1.4.3-SNAPSHOT/evidence/ARCH-001-independent-second-remediation-resignoff-2026-07-31.md`
- blocking_items: B1, B2, B3, B4, B5
- follow_up_required: yes
- canonical_status: `REJECTED`
- activation_gate: `CLOSED`
