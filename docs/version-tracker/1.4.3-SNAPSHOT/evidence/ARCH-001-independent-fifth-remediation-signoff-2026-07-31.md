---
acceptance_scope: bug
version: 1.4.3-SNAPSHOT
target: ARCH-001-fifth-remediation
status: signed-off
decision: accepted
signed_off_by: independent-codex-reviewer
signed_off_at: 2026-08-01
reviewed_by: independent-codex-reviewer
blocking_items: []
closed_blocking_items:
  - ARCH001-R4-B1-BLOCKED-RECONCILIATION-CLEARS-PROOF-QUARANTINE
follow_up_required: no
evidence_count: 14
assurance_level: elevated
---

# ARCH-001 第五轮 bounded remediation 独立复签

## Document Purpose

- intended_for: signoff-owner / project-root-session
- purpose: 独立审计当前 dirty-worktree candidate 是否真实关闭第四轮唯一 blocker
  `ARCH001-R4-B1-BLOCKED-RECONCILIATION-CLEARS-PROOF-QUARANTINE`，不延续实现会话的
  `READY_FOR_SIGNOFF` 结论。
- scope_boundary: 只复签第五轮冻结语义，并确认已通过且输入未变的第四轮/第三轮证据
  没有因第五轮改动失效。
- audit_only: 除本 evidence 与 canonical 签收 marker 外未修改实现；未 clean、reset、
  revert、checkout、切换分支、提交或格式化既有 dirty changes。
- activation_gate: `CLOSED`；本次 source-level 接受不构成真实 `ENFORCED` activation、
  deployment 或 release 授权。

## Candidate Identity

- workspace: `/home/sa/workspace/Foggy-Navigator`
- branch: `main`
- baseline_head: `fdef79c9c55e7de9a5b01822c3c9dc0c75ca2e00`
- baseline_match: yes
- candidate_shape_before_signoff_writeback: 54 个 tracked changed paths（3331 additions、
  300 deletions），另有 6 个 untracked candidate entries；这是用户明确允许的合法 dirty
  worktree。
- tracked_patch_sha256_excluding_canonical:
  `246349cd061194f5aeb4de695a0b3ffa298bf9d201a25bced9d5d8432a14fe14`。
- fifth_regression_test_sha256:
  `6f68125afebb4d4d85c47f2b088bafd2bf98e1d092e28937c92030ab85f0bdd5`。
- preserved_fourth_rejection_sha256:
  `058f63a09e398e9acd2764585fb1576a36ea2a27e9c9530800902ee66a8e85f9`。
- changed_surface_review: 审查了 baseline 到当前 worktree 的完整 status/name/stat/diff
  边界和全部 untracked entries；对第五轮声明的两个 production paths、两个 test paths
  逐行复核。第三、第四轮未变 surface 以第四轮独立审计及当前 candidate-wide reactor
  交叉复用，不把实现摘要当作证据。

## Acceptance Basis

- approved contract: canonical work item 中 `Fifth-round Remediation Delivery Contract
  (2026-07-31)` 的 R5-AC1～R5-AC6 与冻结优先级：
  `LEGACY_WRITER_EXCLUSIVITY_LOST > WORKER_STATE_LOSS > EVIDENCE_CONFLICT > NONE`。
- rejection input:
  `ARCH-001-independent-fourth-remediation-signoff-2026-07-31.md`；只承接其中一个明确
  blocker，不复用其失败结论。
- decisive source:
  `LifecycleOperationalReducer`、`WorkerLifecycleReconciliationCommitService`、
  `WorkerLifecycleSentinelService`、`WriterExclusivityProofService` 与
  `LifecycleIngressGate` 的当前 production path。
- decisive current evidence: `temp/test-artifacts/ARCH-001-fifth-remediation/` 下原始 log 与
  对应 `.exit`；已核对 red/green 先后、测试数量、MySQL 版本/marker、reactor 模块汇总
  和进程 exit，不仅采信 canonical 摘要。
- reusable evidence: `temp/test-artifacts/ARCH-001-fourth-remediation/` 及第三、第四轮独立
  evidence 中已经通过、且第五轮 source/test assumptions 未变化的 Node、B1/B2/B3/B4/
  B5、public SDK、receipt-disabled、SHADOW 证据。
- assurance: elevated；authority precedence、fail-closed、exact MySQL 与 activation
  boundary 不可 waiver。

## Blocker Closure Judgment

| Fourth-round blocker | Independent result | Basis |
|---|---|---|
| `ARCH001-R4-B1-BLOCKED-RECONCILIATION-CLEARS-PROOF-QUARANTINE` | **closed** | `recordBlocked()` 与成功 `commit()` 都把已持久化 conflict 合并回同一个 canonical reducer；三种 ordinary blocked result 不能降低 writer-loss，incoming state loss 只能按冻结优先级升级，成功 checkpoint 不能在既有 conflict 下恢复 `READY/NONE`。production Sentinel → commit → ingress regression、exact MySQL 8.0.44 三阶段序列均通过。 |

## Frozen-Semantics Review

- `LifecycleOperationalReducer.reduceRetainingConflict(...)` 没有引入新 vocabulary；它把
  单一持久化 conflict 映射回现有 `LifecycleBlocker`，再调用原 canonical `reduce(...)`。
- `WorkerLifecycleReconciliationCommitService.recordBlocked(...)` 对 state reset/coverage gap
  输入 `WORKER_STATE_LOSS`，对 unavailable/identity/lease 输入 ordinary offline；两者都与
  locked row 上的 retained conflict 合并。
- 成功 checkpoint 也调用同一 helper 且 observation 为空，所以已有 writer-loss、state-loss
  或 evidence-conflict 不会被写成 `READY/NONE`。
- precedence controls 与冻结顺序一致：writer loss 压过 state loss；state loss 压过 evidence
  conflict；ordinary offline 不清除任何 authority conflict；只有 `NONE` 可落到 ordinary
  `OFFLINE_FROZEN/NONE` 或 successful `READY/NONE`。
- 当前 patch 没有新增 recovery endpoint/service、第二个 conflict 字段、表、migration、enum
  或旁路 reducer；也没有任何当前协议清除 retained conflict。未来 recovery 仍必须另行批准，
  本次签收不批准或预设该协议。
- production regression 从真实 fixture proof quarantine 进入 production Sentinel、
  reconciliation commit 和 `LifecycleIngressGate`；gate 在 effect reservation 之前拒绝
  ENFORCED ingress，三个 blocked result 后再成功 checkpoint 仍保持 provider effect 为零。

## R5 Acceptance Matrix

| Criterion | Class | Evidence | Provenance | Result |
|---|---|---|---|---|
| R5-AC1 | core-blocker / critical | `WorkerLifecycleReconciliationConflictPrecedenceIntegrationTest` 参数化执行 `WORKER_UNAVAILABLE`、`IDENTITY_CHANGED`、`LEASE_NOT_ACQUIRED`；每条在 proof loss 后均保持 `AUTHORITY_QUARANTINED/LEGACY_WRITER_EXCLUSIVITY_LOST`。focused green 4/0/0/0，exit 0。 | current source + current raw evidence | **pass** |
| R5-AC2 | core-blocker / critical | 同一 production-topology test 对每种 blocked result 再执行 successful checkpoint，Worker 仍为 writer-loss quarantine；proof 为 `QUARANTINED`、3 references 与 W/S/T 均 fail closed。 | current source + current raw evidence | **pass** |
| R5-AC3 | core-blocker / critical | 真实 `WriterExclusivityProofService` → production `WorkerLifecycleSentinelService` → `WorkerLifecycleReconciliationCommitService` → production `LifecycleIngressGate`；ENFORCED ingress 抛 `WORKER_DEPENDENT_MUTATION_NOT_READY`，effect counter 为 0。 | current production topology + current raw evidence | **pass** |
| R5-AC4 | core-blocker / critical | focused controls 覆盖 writer+state→writer、evidence+state→state、state+offline→state、none+offline→offline/none、none+success→ready/none；统一 reducer 源码与 4/0 green 相互印证。 | current source + current raw evidence | **pass** |
| R5-AC5 | core-blocker / critical | Testcontainers 明确拉起 MySQL `8.0.44`，JDBC 版本也是 `8.0.44`；marker `ARCH001_MYSQL_SEQUENCE quarantine-blocked-success` 最终 proof `QUARANTINED`、references=3、W/S/T 均 `AUTHORITY_QUARANTINED`。1/0/0/0，exit 0。 | current exact-MySQL raw evidence | **pass** |
| R5-AC6 | regression / critical | exact MySQL 同时保留 50/50/20 与 durable cursor；current connected Slice 8 4/0 验证 B5 和 active-transaction network guard；Session 492/0/0/1、final reactor 14/14 SUCCESS；第四轮 Node 267（265 pass、2 skip）、typecheck/build、never-accepted route、B1/B2/B3/B4、public SDK、receipt-disabled、SHADOW 在输入未变下复用；activation default 仍为 false 且无 config diff。 | current lanes + valid prior-candidate evidence reuse | **pass** |

## Raw Evidence Audit

| Artifact | Independently observed result |
|---|---|
| `reconciliation-precedence-red.{log,exit}` | exit 1；4 tests、4 failures。三个 blocked variants 把 writer-loss 降成 offline/none，writer+state 被 state-loss 覆盖，精确复现第四轮 blocker。 |
| `reconciliation-precedence-green.{log,exit}` | exit 0；同一 class 4 tests、0 failures/errors/skips。 |
| `session-focused.{log,exit}` | exit 0；21 tests、0 failures/errors/skips，包含 reducer、proof concurrency、ingress gate、Sentinel 与新 regression。 |
| `mysql-8.0.44-targeted.{log,exit}` | exit 0；1 test、0 failures/errors/skips；版本与三阶段 marker、ordinary control、50/50/20 cursor 均在 raw log 中。 |
| `connected-slice8.{log,exit}` | exit 0；4 tests、0 failures/errors/skips；production dispatcher concurrency 与 active-transaction provider guard。 |
| `session-module-full.{log,exit}` | exit 0；Session 492 tests、0 failures/errors、1 opt-in MySQL skip；exact MySQL 已单独成功执行。 |
| `launcher-final-reactor.{log,exit}` | exit 0；14/14 modules SUCCESS，4m52s；Session 492、Business 740、Claude 464、Codex 498、launcher 23，均 0 failures/errors；launcher 有 2 个 environment-gated skips。 |
| `candidate-audit.{log,exit}` | exit 0；HEAD/branch、第五轮 changed paths、activation source 与 `git diff --check` 符合 candidate 声明。 |

原始日志中 final launcher 在测试已汇总且 `System.exit(0)` 后出现一次 Surefire fork JVM
30 秒回收 warning；`.exit=0`、launcher 23/0/0/2、14/14 reactor SUCCESS 和 Maven
`BUILD SUCCESS` 一致，因此归类为 test-process shutdown note，不是产品失败或被忽略的
test failure。

## Evidence Reused

- 第四轮 `node-{typecheck,build,full-suite}-final.{log,exit}`：均 exit 0；Node full 为
  267 total、265 passed、2 skipped。
- 第四轮 `node-never-accepted-route-final.{log,exit}`：1 test、exit 0；B3 production route
  的 frozen never-accepted reasons 继续 fail closed。
- 第四轮 `codex-real-node-contract.exit=0`；独立 log 为空的 portability gap 由
  `codex-module-full.log` 和当前 final reactor 中
  `CodexWorkerLifecycleNodeContractIntegrationTest` 2/0/0/0 交叉验证。
- 第三/第四轮独立证据已经判定 B1、B2、B3、B4、B5、public SDK、receipt-disabled、
  SHADOW 对应项通过；第五轮只改变 Session reducer/commit 及其 tests，current final
  reactor 与 connected Slice 8 对受影响 Java surface 重新验证，因此这些证据仍有效。
- 旧 conflict-clearing green 或第四轮拒签 verdict 没有被用来证明第五轮 closure；该 closure
  只使用第五轮 red→same-test green、current source 和 exact MySQL sequence。

## Newly Executed Independent Checks

- 只读执行 HEAD/branch/status、完整 dirty surface inventory、tracked/untracked SHA-256、
  targeted/full diff、`git diff --check`、activation config/source grep、production call-path
  audit，以及第五/第四轮原始 log 与 `.exit` 复核。
- 没有机械重跑 Maven、Node full 或长链测试；当前 evidence 对 R5-AC1～R5-AC6 已充分且
  与 source/candidate identity 匹配。
- 未执行预计超过 30 分钟的 authority/replay/rehearsal/source-seal/full-chain。

## Finding Classification

### core-blocker

- none；第四轮唯一 blocker 已被当前 production path 和 decisive tests 关闭。

### acceptable residual risk

- resume absence 仍依赖 pinned Codex SDK/CLI 0.145.0 session catalog layout；未知、不可访问
  或 symlink catalog 继续 fail closed，SDK 升级时需重验。该风险继承自已接受的 B3 边界，
  不影响第五轮 authority closure。
- launcher 的 2 个 environment-gated skips 不覆盖 R5 must-pass；exact MySQL、production
  topology、Session/Codex/current reactor 均已实际执行。
- future authority recovery 尚无批准协议；这正是当前无 implicit clear 的安全边界。若将来
  引入 recovery，必须单独审查 conflict clear/reveal 及被较高优先级遮蔽的较低 blocker，
  不能从本签收推导授权。

### evidence-reuse / process note

- 第四轮 standalone real-Node log 为空，但独立 exit 与两份含测试计数的 reactor log
  交叉一致；不是 evidence truth blocker。
- raw logs 位于 git-ignored `temp/test-artifacts`，当前 workspace 可完整复核但不具备 tracked
  portability；正式 verdict 与证据映射保存在本 tracked-path evidence 文件中。
- final Surefire fork shutdown warning 已如上披露；它发生在成功汇总后，未改变 exit 或
  module verdict。

### out-of-scope / not performed

- 未访问真实业务数据、Task `20260730-0e01` 或 sibling repositories。
- 未部署、重启、publish、push、tag、release，也未创建真实或 non-fixture ENFORCED
  aggregate。
- activation gate 保持 `CLOSED`；`application.yml` 默认
  `${NAVIGATOR_LIFECYCLE_ACTIVATION_EVIDENCE_PRESENT:false}`，第五轮没有 activation/config
  diff。

## Waivers

| Waived Item | Authority | Reason | Bounded Impact | Non-Waivable Guards | Follow-up |
|---|---|---|---|---|---|
| none | none | no waiver granted | none | precedence、authority fail-closed、exact MySQL、activation boundary | none |

## Final Decision

- verdict: `ACCEPTED`
- acceptance_status: `signed-off`
- closed_blocker:
  `ARCH001-R4-B1-BLOCKED-RECONCILIATION-CLEARS-PROOF-QUARANTINE`
- rationale: R5-AC1～R5-AC6 全部有当前 candidate source、原始 exit/log 或仍有效的明确
  reuse 证据；不存在 core blocker、waiver 或未解释的 critical gap。
- canonical_writeback: 将 canonical 最终状态和第五轮独立复签元数据更新为 `ACCEPTED`，
  不重写 `Fifth-round Implementation Result`，不修改历史独立 evidence。
- activation_gate: `CLOSED`
- deployment_or_release_authorized: no
- real_enforced_activation_authorized: no
