---
acceptance_scope: feature
version: 1.4.3-SNAPSHOT
target: ARCH-001-third-remediation
status: rejected
decision: rejected
signed_off_by: independent-codex-reviewer
signed_off_at: 2026-07-31
reviewed_by: independent-codex-reviewer
blocking_items:
  - ARCH001-R3-B3-RESUME-NEVER-ACCEPTED-PRODUCER-NOT-WIRED
  - ARCH001-R3-B5-CONCURRENT-DISPATCHER-PROOF-NOT-EXECUTED
  - ARCH001-R3-MYSQL-QUARANTINE-CHECKPOINT-LOST-UPDATE
follow_up_required: yes
evidence_count: 18
assurance_level: elevated
---

# ARCH-001 第三轮 remediation 独立复签

## Document Purpose

- intended_for: signoff-owner / project-root-session
- purpose: 对 ARCH-001 第三轮 remediation 的 dirty-worktree 候选执行独立、可复核的
  elevated-assurance 复签。
- audit_only: 本次没有修复实现，没有部署、重启、提交、push、tag 或 release。
- activation_gate: `CLOSED`；本结论不得用于解除 gate。

## Candidate Identity

- workspace: `/home/sa/workspace/Foggy-Navigator`
- branch: `main`
- baseline_head: `fdef79c9c55e7de9a5b01822c3c9dc0c75ca2e00`
- baseline_match: yes
- candidate_shape: 51 个 tracked changed paths，另有 3 个 untracked candidate files；
  tracked diff 为 1821 insertions、184 deletions。
- tracked_patch_sha256:
  `e115609422ef292d093918e35bc8a064a31eda11518639e4defcf673f20e15b2`
- untracked_candidate_sha256:
  - migration:
    `f910f37db228743abe10fa483e6328338b20db7f6ff28da09baf1d3e20075b92`
  - Slice 8:
    `cbdd995e116d221098fcd14a64d82036db8cc0614babe1a16d17ca202fb0f7ff`
  - Node Codex fixture:
    `a0df32b43f4e5394be102a81c013bbdc0559b9f9c4ca33ae7b2c8529612bcc9f`
- identity_note: 以上 digest 在写入本 evidence 前计算，排除本 evidence 自身。

## Acceptance Basis

- approved delivery spec:
  `docs/version-tracker/1.4.3-SNAPSHOT/workitems/ARCH-001-unified-session-task-lifecycle-owner.md`
  中 “Third Independent Resignoff Rejection” 与 “Third-Rejection Remediation Delivery
  Contract / Execution Record”。
- latest independent rejection:
  `docs/version-tracker/1.4.3-SNAPSHOT/evidence/ARCH-001-independent-second-remediation-resignoff-2026-07-31.md`
- changed paths / diff: 审查 baseline 至当前 dirty worktree 的全部 51 个 tracked diff，
  并审查 3 个 untracked candidate files。
- test records: 审查
  `temp/test-artifacts/ARCH-001-third-remediation/` 下全部日志和全部 `.exit` 文件；没有把
  中间 red/failing 日志当作最终通过。
- migration / compatibility evidence: 审查 baseline migration、第三轮 additive
  migration、MySQL 8.0.44 日志、receipt-disabled/SHADOW 兼容测试和
  `navigator-open-sdk` unchanged 记录。
- evidence rule: 以当前源码、真实测试拓扑、原始日志和 exit code 为准，不继承实现记录
  中的 `[x]` 或 `READY_FOR_SIGNOFF` 自报结论。

## B1–B5 Independent Judgment

| Item | Classification | Delivered evidence | Independent result |
|---|---|---|---|
| B1 production owner vertical | core-blocker | 新 Slice 8 从 public closure 经过 production coordinator/outbox/dispatcher、production Codex Java client、real Node route、durable fact、scheduled Sentinel、owner terminal、tombstone、business cleanup、lane release 和 typed projection；`slice8-integration-8.exit=0`。fixture 对初始已运行 Task/Worker/Session 作必要前置，但没有手工 seed termination outbox 或 proof references。 | **pass** |
| B2 exact admission fence | core-blocker | admission intent 与 PREPARED outbox 新增并核对 ownership mode、state generation、instance epoch、binding digest version/digest；锁定 Worker/Session/Task readiness/conflict、canonical binding、enrollment 和三类 proof reference；负例与事务回滚测试通过。 | **pass** |
| B3 never-accepted authority and exact cleanup | core-blocker | exact cleanup 已按 client request / Task / operation 绑定，Java 也验证 `REJECTED/PRE_EFFECT/never_accepted_proof`。但是 Node production query router 仅在 thread conflict 和 capacity rejection 两个分支调用 `rejectEnforcedBeforeEffect(...)`；第三个冻结 allowlist 原因 `WORKER_TASK_RESUME_TARGET_NOT_FOUND` 只存在于 store 常量和直接 store 测试。resume 路由会先持久化 `PREPARED`、再置 `EFFECT_STARTED` 后才调用 SDK `resumeThread`，不存在该 reason 的 production pre-effect producer。canonical 关于“三个 allowlisted reasons”的实现声明不成立。 | **fail** |
| B4 real Codex command contract | core-blocker | `CodexWorkerLifecycleNodeContractIntegrationTest` 使用 production `CodexWorkerClient` 和 mounted real Node routers，执行 successful create、resume、POST abort、dispatch status 及 terminal fact；focused 与单独 real-Node lane 均为 2/0/0/0。 | **pass** |
| B5 connected Slice 8 and representative proof race | core-blocker | connected Slice 8 本身已关闭。但 `WriterExclusivityProofConcurrencyIntegrationTest` 仍直接调用 `WriterExclusivityProofService.authorizeEffect(...)`，以 `AtomicInteger` 代替 provider call；它没有在 loss-first / authorization-first 竞争中执行 R3-AC4 要求的真实 repository dispatcher/handler。production dispatcher 仅在非并发 Slice 8 中执行，两个证据不能拼接成所需并发 must-pass。 | **fail** |

## R3-AC1～R3-AC11 Contract Conformance

| Acceptance | Evidence and judgment | Result |
|---|---|---|
| R3-AC1 | connected Slice 8 实际执行 scheduler/Sentinel、production port、repository、reducer、snapshot、terminal、tombstone、cleanup、lane release、typed projection。 | **pass** |
| R3-AC2 | exact mode/generation/epoch、三层 readiness/conflict、binding、enrollment、proof references 均在 provider effect 前验证；负例 provider count 为零。 | **pass** |
| R3-AC3 | receipt、accepted operation/fact、exact binding 与 parent/child PREPARED outbox 位于同一 admission transaction；失败路径回滚且 provider count 为零。 | **pass** |
| R3-AC4 | exact reference lock 的生产代码存在；但要求的 loss-first / authorization-first “real repository dispatcher/handler” 并发测试没有执行，当前测试仍是 direct service + `AtomicInteger`。 | **fail** |
| R3-AC5 | store 能原子持久化 never-accepted disposition/fact，Java ingress 校验完整；但 `WORKER_TASK_RESUME_TARGET_NOT_FOUND` 没有 production route producer，只有直接 store 测试，三原因声明和真实生产能力不一致。 | **fail** |
| R3-AC6 | tombstone/cleanup context 携带 exact client request；business cleanup 使用 exact `(clientRequestId, taskId, operationType)` receipt，same-Task newer receipt 负例已覆盖。 | **pass** |
| R3-AC7 | real Java client + real Node create/resume/abort/status lane 通过，覆盖 PREPARED、provider Task identity、EFFECT_STARTED、RESULT_OBSERVED 与 terminal fact。 | **pass** |
| R3-AC8 | continuous Slice 8 lane 通过；不再使用 lifecycle port、coordinator、client 或 provider-effect mock 作为主链替代。 | **pass** |
| R3-AC9 | receipt-disabled replay、SHADOW provider effect 与 public SDK wire 未改变；受影响模块和 launcher reactor 通过。 | **pass** |
| R3-AC10 | provider/network active-transaction guard 和最大 50 条 chunk 机械边界存在；但 fail-closed continuation/concurrency 不成立。reconciliation 最终 checkpoint 重新加锁后无条件写 `READY/NONE`，proof quarantine 在独立批次事务写 `AUTHORITY_QUARANTINED/LEGACY_WRITER_EXCLUSIVITY_LOST`。若 quarantine 先提交、checkpoint 后提交，后者读取最新 row 后仍覆盖 quarantine；`@Version` 和 pessimistic lock 只能串行化，不能阻止语义性后写覆盖。现有测试未执行 reconciliation-vs-quarantine 交错。 | **fail** |
| R3-AC11 | 未发现 activation 配置变化或 non-fixture ENFORCED 激活；isolated enforcement 仍受 gate 限制。 | **pass** |

## MySQL Transaction-Boundary Review

| Boundary | Evidence | Result |
|---|---|---|
| additive DDL / exact MySQL | 第三轮 migration 使用 `information_schema.columns` 做幂等 additive columns；MySQL 8.0.44 fixture 两次应用 delta，Hibernate validate 与 rollback floor 通过，1/0/0/0，exit 0。 | pass |
| no provider/network in active DB transaction | acceptance coordinator 在 transaction 返回后 dispatch；Codex service 与 outbox dispatcher 有 active-transaction guard；Sentinel 在网络调用前检查 active transaction。 | pass |
| bounded reconciliation | enrollment、每 Task 最大 50 facts、checkpoint 分离为短事务。 | pass with blocker below |
| bounded proof quarantine | proof 先进入 durable `QUARANTINING`，每批最多 50 references，并持久化 cursor；120-reference `50/50/20` 恢复测试通过。 | pass with blocker below |
| fail-closed interleaving | `WorkerLifecycleReconciliationCommitService.commit()` 最终 checkpoint 无条件设置 Worker `READY/NONE`；`WriterExclusivityProofService.quarantineBatch()` 的 proof-loss Worker quarantine 可被该后提交 checkpoint 清除。没有 proof status/reference/CAS precedence check，也没有该真实竞争回归。 | **fail** |

### ARCH001-R3-MYSQL-QUARANTINE-CHECKPOINT-LOST-UPDATE

一个合法交错即可破坏 authority quarantine：

1. Sentinel 完成外部 inventory/events，并开始分段 owner commit。
2. proof-loss transaction 将 proof 置为 `QUARANTINING`，随后一个 quarantine batch 将
   Worker snapshot 写为
   `AUTHORITY_QUARANTINED/LEGACY_WRITER_EXCLUSIVITY_LOST` 并提交。
3. Sentinel 的最终 checkpoint transaction 随后取得同一 Worker row 的 pessimistic
   lock，读取已 quarantine 的最新 row。
4. checkpoint 不检查 proof status、writer reference 或已有 conflict precedence，仍无条件
   写回 `READY/NONE` 并提交。

因此这不是 stale-object 乐观锁可拦截的 lost update，而是串行事务中的语义性后写覆盖。
它会让 proof 已丢失且仍在 `QUARANTINING/QUARANTINED` 的 Worker 再次显示 ready，违反
fail-closed authority 和 R3-AC10 的 concurrency regression obligation。

## Executed Evidence Review

最终 green 记录均有 exit 0：

- Node full: 266 tests，264 pass，0 fail，2 skipped；build/typecheck exit 0。
- Session focused: 16/0/0/0；Session module: 486/0/0/1。
- Business focused: 22/0/0/0；Business module: 740/0/0/0。
- Claude focused: 8/0/0/0；Claude module: 464/0/0/0。
- Codex focused: 152/0/0/0；real Node contract: 2/0/0/0；Codex module:
  498/0/0/0。
- connected Slice 8: 1/0/0/0。
- MySQL 8.0.44: 1/0/0/0。
- final launcher reactor: 14 modules success；launcher 20 tests，0 failures/errors，
  2 environment skips；exit 0。
- `git diff --check`: exit 0。

候选目录也保留了中间失败：

- `codex-focused-final.exit=1`、`codex-module-full.exit=1`、
  `codex-module-full-2.exit=1`；
- `slice8-binding-diagnostic.exit=1` 与 `slice8-integration-3..7.exit=1`。

这些失败之后有同候选目录内、名称明确的最终 green run，且对应完整模块/launcher
reactor 后续通过；它们不是本次拒签原因，也没有被误报为 green。日志敏感信息抽查只见
`hasApiKey=true/false` 等布尔诊断和 SQL 占位符，没有发现明文 credential。

## Evidence Sufficiency

- assurance_level: `elevated`
- why_existing_evidence_is_sufficient_or_not: 当前源码给出两个直接 must-pass 反证和一个
  事务交错反例；这些反例不会因再次运行现有 green suite 改变。已有日志足以确认其余
  条目和最终候选身份。
- new_validation_that_could_change_decision: 只有新的实现候选同时接通缺失的 production
  never-accepted route、以真实 dispatcher/handler 覆盖 proof-loss 双顺序，并证明
  reconciliation checkpoint 不会解除 proof quarantine，才可能改变结论。
- expensive_validation_omitted_and_reason: 未重跑测试，也未执行 live/full-chain、
  deployment、replay、release 或 activation；当前直接反证已决定拒签，额外昂贵验证
  没有决策价值，且超出 audit-only 授权。

## Waivers

| Waived Item | Authority | Reason | Bounded Impact | Non-Waivable Guards | Follow-up |
|---|---|---|---|---|---|
| none | none | no waiver granted | none | B1–B5、R3-AC1～R3-AC11、authority fail-closed | none |

## Failed Items

### ARCH001-R3-B3-RESUME-NEVER-ACCEPTED-PRODUCER-NOT-WIRED

- `WORKER_TASK_RESUME_TARGET_NOT_FOUND` 是冻结 allowlist reason，但 production source 没有
  调用 `rejectEnforcedBeforeEffect(...)` 产生它。
- 当前测试直接调用 `LifecycleStore.rejectBeforeEffect(...)`，不能证明 query/resume
  production route 会形成 durable `REJECTED/PRE_EFFECT`。
- impact: B3 和 R3-AC5 未关闭；canonical “three allowlisted reasons” 声明不准确。

### ARCH001-R3-B5-CONCURRENT-DISPATCHER-PROOF-NOT-EXECUTED

- loss-first / authorization-first 测试直接调用 proof service，并用 `AtomicInteger` 模拟
  provider count。
- 它没有执行 R3-AC4 明确要求的 real repository dispatcher/handler；connected Slice 8
  虽执行 dispatcher，但没有 proof-loss 并发交错。
- impact: B5 仅部分关闭，R3-AC4 未满足。

### ARCH001-R3-MYSQL-QUARANTINE-CHECKPOINT-LOST-UPDATE

- Sentinel checkpoint 与 proof quarantine 的独立事务没有 fail-closed precedence。
- 后提交 checkpoint 可把已 quarantine Worker 改回 `READY/NONE`。
- 当前 concurrency suite 不包含这组交错，MySQL fixture 只验证 DDL/JPA/rollback floor。
- impact: MySQL transaction boundary 的 authority 安全不成立，R3-AC10 未满足。

## Risks / Follow-ups

- canonical 当前保持 `READY_FOR_SIGNOFF`，本次拒签按用户要求不回写 canonical。
- 不应以本 evidence 为 deployment 或 activation 授权。
- activation gate 必须继续保持 `CLOSED`。

## Final Decision

- decision: `rejected`
- rationale: B3、B5、R3-AC4、R3-AC5、R3-AC10 仍包含不可豁免 must-pass 失败；
  MySQL transaction boundary 存在可构造的 proof-quarantine 解除路径。其余 green 日志和
  已关闭条目不能抵消 authority/transaction 核心失败。
- blocking_items:
  - `ARCH001-R3-B3-RESUME-NEVER-ACCEPTED-PRODUCER-NOT-WIRED`
  - `ARCH001-R3-B5-CONCURRENT-DISPATCHER-PROOF-NOT-EXECUTED`
  - `ARCH001-R3-MYSQL-QUARANTINE-CHECKPOINT-LOST-UPDATE`
- follow_up_owner_and_due: implementation owner / next remediation candidate

## Signoff Marker

- acceptance_status: `rejected`
- acceptance_decision: `rejected`
- signed_off_by: `independent-codex-reviewer`
- signed_off_at: `2026-07-31`
- acceptance_record:
  `docs/version-tracker/1.4.3-SNAPSHOT/evidence/ARCH-001-independent-third-remediation-resignoff-2026-07-31.md`
- blocking_items: B3, B5, R3-AC4, R3-AC5, R3-AC10, MySQL transaction boundary
- follow_up_required: yes
- activation_gate: `CLOSED`
