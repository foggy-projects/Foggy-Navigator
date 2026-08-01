---
acceptance_scope: bug
version: 1.4.3-SNAPSHOT
target: ARCH-001-fourth-remediation
status: rejected
decision: rejected
signed_off_by: independent-codex-reviewer
signed_off_at: 2026-07-31
reviewed_by: independent-codex-reviewer
blocking_items:
  - ARCH001-R4-B1-BLOCKED-RECONCILIATION-CLEARS-PROOF-QUARANTINE
follow_up_required: yes
evidence_count: 10
assurance_level: elevated
---

# ARCH-001 第四轮 remediation 独立签收

## Document Purpose

- intended_for: signoff-owner / project-root-session
- purpose: 对 ARCH-001 第四轮 remediation 的 dirty-worktree 候选执行独立、可复核的
  elevated-assurance 签收，只判断第三轮遗留的 B3、B5 与 MySQL quarantine/checkpoint
  blocker。
- audit_only: 未修改实现；未部署、重启、提交、push、tag、publish、release 或创建真实
  `ENFORCED` aggregate。
- activation_gate: `CLOSED`；本拒签记录不得用于解除 gate。

## Candidate Identity

- workspace: `/home/sa/workspace/Foggy-Navigator`
- branch: `main`
- baseline_head: `fdef79c9c55e7de9a5b01822c3c9dc0c75ca2e00`
- baseline_match: yes
- candidate_shape_before_signoff_writeback: 53 个 tracked changed paths，另有 4 个
  untracked candidate files；候选合法 dirty worktree，审查期间未 clean、reset、revert、
  checkout、切分支或提交。
- tracked_product_patch_sha256: `d5689808f1bf2af113c6b77db231efd58a3926313b26a0a4ab2ae15b8ebffce1`
  （排除 canonical 文档，避免本次 marker writeback 改变产品 patch identity）。
- untracked_candidate_sha256:
  - third-remediation migration:
    `f910f37db228743abe10fa483e6328338b20db7f6ff28da09baf1d3e20075b92`
  - connected Slice 8 test:
    `62e89bff5cdb22bcf01d490aa9894ed70b2a1301fd2b5658413791790063ba64`
  - Node Codex fixture:
    `5af533732b6843395d6724ca523cda0a11ea37d03ad364489be836150f2f1b04`
  - historical third-remediation rejection evidence:
    `dd1f6293ae04181a8a2af7975f44774c68abac8f9573501a1925e7924d0890ef`

## Acceptance Basis

- approved delivery spec: canonical work item 中
  `Fourth-round Remediation Delivery Contract (2026-07-31)`，状态
  `READY_FOR_SIGNOFF`、assurance level `elevated`。
- rejection input:
  `ARCH-001-independent-third-remediation-resignoff-2026-07-31.md`。
- changed surface: 审查 baseline 至当前 dirty worktree 的完整 tracked diff、全部 4 个
  untracked candidate files，并重点审查第四轮声明的 14 个 changed paths。
- evidence directory: `temp/test-artifacts/ARCH-001-fourth-remediation/` 下全部 `.exit`
  状态、关键 raw logs、保留 red 和最终 green 的先后关系。
- evidence rule: 以源码、真实测试拓扑、原始日志和 exit code 为准，不把 canonical 的
  `[x]` 或实现自报 `READY_FOR_SIGNOFF` 当作签收结论。

## Blocker Closure Judgment

| Third-round blocker | Independent result | Basis |
|---|---|---|
| `ARCH001-R3-B3-RESUME-NEVER-ACCEPTED-PRODUCER-NOT-WIRED` | **closed** | production query router 在 `PREPARED/EFFECT_STARTED` 前调用 `inspectCodexResumeTarget()`；三个 frozen reason 均经 production route 写入 exact `REJECTED/PRE_EFFECT/never_accepted_proof=true` 与 `TASK_NEVER_ACCEPTED_CONFIRMED`。最终 route test 两次 delivery、重开 durable store 后 duplicate，Node provider registry 不变。目录 I/O、identity、symlink 或并发不确定性返回非权威 503。 |
| `ARCH001-R3-B5-CONCURRENT-DISPATCHER-PROOF-NOT-EXECUTED` | **closed** | connected Slice 8 的 loss-first/authorization-first 调用 production acceptance coordinator、repository outbox dispatcher、proof service、Codex provider/client 与 mounted production Node routes；没有 `AtomicInteger`、direct `authorizeEffect()` 或 fake Java provider 作为决定性证据。authorization-first 等待内层 `WORKER_LIFECYCLE_COMMAND` durable `EFFECT_STARTED`，loss-first 的 Node inventory 无对应 dispatch。 |
| `ARCH001-R3-MYSQL-QUARANTINE-CHECKPOINT-LOST-UPDATE` | **not closed** | 第四轮修复阻止直接的 quarantine-before-successful-checkpoint 覆盖，但同一 production reconciliation commit service 的 `recordBlocked()` 仍可清除 proof-loss conflict；随后成功 checkpoint 可恢复 `READY/NONE`。见核心反例。 |

## R4R Evidence Matrix

| Item | Classification | Risk | Evidence | Reused / New | Result |
|---|---|---|---|---|---|
| R4R-AC1 | core-blocker | critical | `query.ts`, `store.ts`, `query-route-paths.test.ts`; `node-never-accepted-route-final.{log,exit}`（1/0、exit 0） | current-candidate existing evidence | **pass** |
| R4R-AC2 | core-blocker | critical | `sdk-wrapper.ts` 两次稳定 catalog scan、symlink/identity/I/O fail-closed；production route 在 prepare/effect 前处理；`TaskLifecycleOwnerService` exact disposition/fact ingress；`session-focused-green.{log,exit}`（8/0、exit 0） | current-candidate existing evidence + independent source audit | **pass** |
| R4R-AC3 | core-blocker | critical | `Arch001ThirdRemediationSlice8IntegrationTest` production topology；`connected-slice8.{log,exit}`（4/0、exit 0）；Node durable inventory assertions | current-candidate existing evidence | **pass** |
| R4R-AC4 | core-blocker | critical | `mysql-8.0.44-concurrency.{log,exit}` 对直接双顺序、控制组和最终 W/S/T 状态为 green；但 production `recordBlocked()` → later `commit()` 形成未覆盖的解除 proof quarantine 路径 | existing evidence + new independent source counterexample | **fail** |
| R4R-AC5 | core-blocker | critical | 50/50/20 cursor、`QUARANTINING` restart continuation、active-transaction provider guard 均有 green；但 authority conflict 不满足“除显式 recovery 外单调” | current-candidate existing evidence + new independent source counterexample | **fail** |
| R4R-AC6 | core-blocker | major | `codex-real-node-contract`, `connected-slice8`, Node full 267、Session 488、Codex 498、final launcher 14/14 modules，均 exit 0；public SDK/config 无第四轮 diff；第三轮 receipt-disabled/SHADOW/B1/B2/B4 evidence 前提未变 | reused + current final-candidate lanes | **pass** |
| R4R-AC7 | core-blocker | critical | `application.yml` 默认 `${NAVIGATOR_LIFECYCLE_ACTIVATION_EVIDENCE_PRESENT:false}`，无 config diff；全部 ENFORCED 仅 disposable fixture；无部署/重启/发布动作 | source/config audit + existing evidence | **pass** |

## Core Blocker

### ARCH001-R4-B1-BLOCKED-RECONCILIATION-CLEARS-PROOF-QUARANTINE

当前生产源码存在如下合法序列：

1. `WriterExclusivityProofService.quarantine()` 将 proof/reference 及 Worker/Session/Task
   snapshot 提交为 `QUARANTINED` / `AUTHORITY_QUARANTINED` /
   `LEGACY_WRITER_EXCLUSIVITY_LOST`。
2. 随后 Sentinel 因普通 Worker unavailable、identity fence rejection 或 lease not acquired
   返回 blocked result。`WorkerLifecycleSentinelService.reconcile()` 对所有非 `READY`
   result 调用 `WorkerLifecycleReconciliationCommitService.recordBlocked()`。
3. `recordBlocked()` 对非 `STATE_GENERATION_RESET`、非 `COVERAGE_GAP` 的 result 无条件写
   `OFFLINE_FROZEN/NONE`，没有保留既有
   `LEGACY_WRITER_EXCLUSIVITY_LOST`，也没有核对 proof/reference 状态。
4. 下一次正常 reconciliation 的 `commit()` 看到当前 conflict 已为 `NONE`，因此合法写回
   `READY/NONE`；proof 仍是 `QUARANTINED`，Worker 却重新变为 ready。

这不只是展示字段错误：`LifecycleIngressGate.reserveBeforeEffect()` 对既有 `ENFORCED`
Session 的 provider-effect admission 只从 Worker 的 availability/conflict 计算 offline gate，
不重新核对 quarantined proof，也不把 Session 自身的 proof-loss conflict 纳入该 gate。
因此上述 Worker `READY/NONE` 可重新打开实际 ingress authority，而 Session/Task rows 仍
显示 proof-loss quarantine。

这不是“还可以继续测试”的未知项，而是当前源码可直接构造的 authority 反例。它违反
confirmed decision 的 proof-loss monotonic precedence、transaction-safety 的显式 recovery
要求以及 R4R-AC4/AC5 的 fail-closed must-pass。现有 MySQL green 只覆盖 proof quarantine
与 successful checkpoint 的直接双顺序，没有覆盖 blocked reconciliation 清 conflict 后再
successful checkpoint 的生产序列，因而不能抵消该反例。

## Finding Classification

### core-blocker

- `ARCH001-R4-B1-BLOCKED-RECONCILIATION-CLEARS-PROOF-QUARANTINE`：R4R-AC4、
  R4R-AC5 fail；原 MySQL quarantine/checkpoint blocker 未完整关闭。

### scoped-risk

- resume absence 依赖 pinned Codex SDK/CLI 0.145.0 的 session catalog layout。当前实现对
  未知、不稳定、不可访问或 symlink catalog fail closed 为 503；升级 SDK 时需重验，
  但不影响本候选 B3 closure。
- launcher 的 2 个 environment-gated skip 不覆盖 R4 must-pass 路径；边界明确。

### process-gap

- `codex-real-node-contract.log` 为空但 `.exit=0`；同一测试类的 2/0/0/0 执行记录可在
  `codex-module-full.log` 与 `launcher-final-reactor.log` 交叉验证，因此这是单独 raw-log
  携带性问题，不是 evidence truth blocker。
- 第四轮 raw evidence 位于 git-ignored `temp/test-artifacts`，当前工作区可复核但不具备
  tracked evidence portability；这不改变当前拒签结论。

### out-of-scope

- live/shared database、真实 Worker/controller/SIM、sibling repository、Task
  `20260730-0e01`、部署、重启、publication、release、UI/Playwright、首次 non-fixture
  `ENFORCED` activation 均未执行，也不作为本次拒签理由。

## Evidence Reused

- `node-never-accepted-route-final.{log,exit}`、`session-focused-green.{log,exit}`、
  `mysql-8.0.44-concurrency.{log,exit}`、`codex-real-node-contract.{log,exit}`、
  `connected-slice8.{log,exit}`。
- `node-{typecheck,build,full-suite}-final.{log,exit}`、
  `session-module-full.{log,exit}`、`codex-module-full.{log,exit}`、
  `launcher-final-reactor.{log,exit}`。
- 保留的 red/intermediate evidence 用于核对回归先后，不把它们误判为最终失败；最终
  green 与当前代码/test selection/candidate assumptions 匹配。
- 第三轮拒签中已通过且输入未变的 B1、B2、B4、public SDK、receipt-disabled、SHADOW、
  Worker-v1 evidence；第四轮 final reactor 和 affected lanes 提供当前候选兼容复核。

## Newly Executed Checks

- 只读执行 `git rev-parse HEAD`、branch/status、完整 dirty diff/untracked review、候选
  SHA-256、`git diff --check`、activation config/source grep 与 evidence exit/log audit。
- 没有重跑测试。源码反例已直接决定 R4R-AC4/5 fail，重跑现有 green suites不会改变
  签收结论；未启动任何 >30 分钟 authority/replay/rehearsal/source-seal/full-chain。

## Waivers

| Waived Item | Authority | Reason | Bounded Impact | Non-Waivable Guards | Follow-up |
|---|---|---|---|---|---|
| none | none | no waiver granted | none | proof-loss monotonicity、R4R must-pass、activation boundary | none |

## Final Decision

- decision: `rejected`
- rationale: B3 production producer 与 B5 production concurrent topology 已关闭，且现有
  evidence 对这些结论充分；但 MySQL/proof-loss blocker 仍可经 production blocked
  reconciliation → successful checkpoint 序列解除，导致 R4R-AC4/5 和非豁免 authority
  fail-closed guard 不成立。
- blocking_items:
  - `ARCH001-R4-B1-BLOCKED-RECONCILIATION-CLEARS-PROOF-QUARANTINE`
- follow_up_owner_and_due: implementation owner / next bounded remediation candidate
- activation_gate: `CLOSED`
- deployment_or_release: not performed
- real_enforced_activation: not performed

## Signoff Marker

- acceptance_status: `rejected`
- acceptance_decision: `rejected`
- signed_off_by: `independent-codex-reviewer`
- signed_off_at: `2026-07-31`
- acceptance_record:
  `docs/version-tracker/1.4.3-SNAPSHOT/evidence/ARCH-001-independent-fourth-remediation-signoff-2026-07-31.md`
- blocking_items: `ARCH001-R4-B1-BLOCKED-RECONCILIATION-CLEARS-PROOF-QUARANTINE`
- follow_up_required: yes
- activation_gate: `CLOSED`
