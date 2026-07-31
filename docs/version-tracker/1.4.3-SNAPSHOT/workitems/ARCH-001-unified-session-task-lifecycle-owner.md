---
doc_type: delivery-spec
delivery_type: cross-module
version: 1.4.3-SNAPSHOT
ticket: ARCH-001
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
assurance_level: elevated
approved_by: project-owner-user
approved_at: 2026-07-30
previous_architecture_direction_confirmed_by: project-owner-user
previous_contract_approval_at: 2026-07-30
independent_review_at: 2026-07-30
independent_review_verdict: NEEDS_REPLAN
independent_rereview_at: 2026-07-30
independent_rereview_verdict: NEEDS_REPLAN
independent_round_3_review_at: 2026-07-30
independent_round_3_review_verdict: NEEDS_REPLAN
independent_round_4_review_at: 2026-07-30
independent_round_4_review_verdict: NEEDS_REPLAN
independent_round_5_review_at: 2026-07-30
independent_round_5_review_verdict: NEEDS_REPLAN
independent_round_6_review_at: 2026-07-30
independent_round_6_review_verdict: NEEDS_REPLAN
independent_round_7_review_at: 2026-07-30
independent_round_7_review_verdict: APPROVED
replan_round: 7
replan_decisions_confirmed_by: project-owner-user
replan_decisions_confirmed_at: 2026-07-30
replan_review_status: approved-after-round-7
execution_start_authorized: true
decision_stage: approved-source-slices-authorized-activation-separate
implementation_completed_at: 2026-07-31
remediation_completed_at: 2026-07-31
remediation_status: READY_FOR_SIGNOFF
independent_signoff_at: 2026-07-31
independent_signoff_verdict: REJECTED
acceptance_record: ../evidence/ARCH-001-independent-signoff-2026-07-31.md
open_questions: []
deferred_topics:
  - worker-generated-physical-id-claim-and-recovery
  - claude-worker-sentinel-convergence-mvp-b
  - admin-logical-close-and-permanent-loss
  - rolling-mixed-version-database-writer-guard
  - session-transfer-protocol
  - frontend-retry-draft-retention
---

# Delivery Spec: Unified Session and Task Lifecycle Owner MVP-A

## Document Purpose

- intended_for: ultra-implementation / independent-signoff
- purpose: 将已确认的 Session、Task、termination、Worker connectivity、token 和
  active registration projection 设计冻结为第一阶段可执行交付契约。
- canonical_path:
  `docs/version-tracker/1.4.3-SNAPSHOT/workitems/ARCH-001-unified-session-task-lifecycle-owner.md`
- execution_status: `READY_FOR_SIGNOFF`；2026-07-31 独立拒签发现的 B1–B6 已完成
  remediation implementation 和 repo-owned 验证，等待新的独立签收。原
  `REJECTED` verdict 与 evidence 保留；真实 controller/process、首次非 fixture
  `ENFORCED` aggregate、live SIM、部署和发布继续需要单独授权。

## Independent Review Disposition

- round_1_verdict: `NEEDS_REPLAN`
- round_2_verdict: `NEEDS_REPLAN`
- round_3_verdict: `NEEDS_REPLAN`
- round_4_verdict: `NEEDS_REPLAN`
- round_5_verdict: `NEEDS_REPLAN`
- round_6_verdict: `NEEDS_REPLAN`
- round_7_verdict: `APPROVED`
- implementation_gate: open-for-source-slices-0-through-8
- activation_gate: closed-pending-separate-owner-authorization
- accepted_blockers:
  1. authorization-authoritative terminal tombstone 必须是 canonical terminal commit 的
     同步 fail-closed 围栏，不能降级为异步 cleanup effect。
  2. 第一次 `ENFORCED` 必须移到 Codex provider direct writers、terminal security、
     Sentinel、offline gate、Session lane、schema readiness 和 writer fence 全部完成
     shadow 验证之后。
  3. 当前 Worker contract 不具备已设计的
     `physicalWorkerId/stateGeneration/instanceEpoch`、fenced inventory、coverage/ACK
     durability 语义；必须先冻结 additive Worker v1 contract。
  4. 混合 Java 版本和旧 binary direct writer 必须有真实 startup/writer fencing；
     文档声明“禁止回滚”本身不是 gate。
  5. 当前 UI `force` 是 task-owner provider cancellation，不是管理员 logical close；
     是否新增该 authority 及其调用/权限面必须由 owner 决定。
- accepted_major_corrections:
  - terminal 时冻结 participant-specific cleanup plan，使用
    `REQUIRED/NOT_APPLICABLE/COMPLETED`，不得无条件等待不存在的 token、registration
    或 receipt。
  - provider-neutral Worker lifecycle snapshot/lease owner 与 SPI port 方向必须在避免
    Maven 循环的前提下冻结。
  - production schema 必须预应用并通过 readiness；现有
    `ApplicationReadyEvent` warning-only migration runner 不能作为 enforcement 前置保证。
  - 必须增加 cross-surface status mapping 和 review 指出的 crash、stale lease、
    epoch/cursor gap、mixed-version、receipt-disabled 等 failure-first 场景。
  - frontend `localStorage` 章节降为 non-normative deferred design；本 MVP 只冻结 server
    no-queue/no-auto-replay。
- confirmed_replan_resolutions:
  1. 首个 canary 只覆盖 BUG-036 所在的 Codex SDK business-runtime lane：
     `providerType=codex-biz-worker`。同 runtime 的 `codex-worker` 先完成 SHADOW parity，
     但不进入首次 enforcement；`codex-app-server-worker` 与 Claude 不进入 MVP-A。
  2. admin logical close、Worker permanent-loss confirmation 和 Session transfer
     延后；现有 task-owner `force` 保持 provider cancellation 语义，不能形成管理员
     terminal authority。
  3. MVP-A 以现有配置的 Navigator `workerId` 作为 transitional
     `physicalWorkerId`，Worker 必须 exact echo；新增持久 `stateGeneration` 与每进程
     `instanceEpoch`。Worker 首启自动生成 Physical Worker ID 另立后续工作。
  4. `session-module` 拥有 Worker/Session/Task lifecycle orchestration、state 和
     Sentinel lease/scheduling；provider-neutral port/value contract 位于
     `navigator-spi`；addon 与 `business-agent-module` 只实现 adapter/participant。
  5. 首次 enforcement 只支持 homogeneous、disable-old-deployment-controllers +
     stop-all-old-instances 的非滚动 Java cutover；无法连续证明旧 controller 不会恢复、
     旧实例全部停止时禁止 enrollment。滚动升级所需数据库级 stale writer guard 延后。
  6. 当前 Codex/Claude 的“active registration”是从 Task status/terminal 推导的
     projection，不是独立资源；cleanup plan 对该 participant 固定
     `NOT_APPLICABLE(DERIVED_PROJECTION_NO_RESOURCE)`，并由 canonical/compatibility
     projection 验证其变为 false。
- accepted_round_3_findings:
  1. Worker v1 的 command/effect half 必须冻结 exact HTTP/SSE wire、duplicate phase
     response、one-use termination capability 与 durable dispatch 的原子顺序，以及
     `safe_binding_digest` 算法；Codex SDK Worker 不存在的 interaction route 必须明确
     `NOT_APPLICABLE`。
  2. 非耐久的 lifecycle store failure response 不能证明 Task definitively never
     accepted；只有可由 fenced dispatch-status endpoint 重读的 durable
     `REJECTED/PRE_EFFECT` disposition 才具备该资格。
  3. Java legacy-writer exclusivity proof 必须以 lease 形式覆盖所有 live
     `ENFORCED` aggregate，而不是止于首次 enrollment；proof 丢失时既存 aggregate
     必须 quarantine。
  4. receipt-disabled duplicate 继续严格保持 BUG-035 的 one-shot 行为；receipt enabled
     但创建失败必须在 provider effect 前 fail closed，不能被 cleanup
     `NOT_APPLICABLE` 掩盖。
  5. 真实 controller/process cutover rehearsal 需要与 source implementation 分离授权
     和预算；未获授权时不得把 fixture evidence 冒充真实 deployment evidence。
  6. failure-first matrix 必须补 exact `codex-biz-worker` typed closure、command
     duplicate wire、late negative/redelivery、post-enrollment relaunch 和 receipt
     persistence failure。
- accepted_round_4_findings:
  1. durable disposition 与 dispatch-status 必须在 exact wire 上回带并校验
     `safe_binding_digest_version/safe_binding_digest`；只有同一 durable record 中的
     `never_accepted_proof=true` 才能参与 never-accepted 决策。
  2. `TASK_CREATE|TASK_RESUME` 的 provider Task ID 必须在 accepted `PREPARED`
     disposition 中原子分配并持久化，不能继续依赖后续业务 SSE 才恢复 termination
     identity。
  3. writer proof 必须为 Worker、Session、Task 分别维护 reference；OPEN Session 或
     仍为 ENFORCED 的 Worker 不能因最后一个 Task cleanup 就释放 proof。
  4. outbox claim 不是 provider effect authority；proof-loss 与 provider call 必须通过
     同一 proof row 上的 `lifecycle_effect_outbox.effectState=EFFECT_STARTED`
     authorization CAS 形成唯一线性化顺序。
  5. Task/Session/Worker snapshot 统一使用一个 `availability` enum 和一个
     `conflictState` 字段，不再混用 bare `QUARANTINED`、`authorityConflict` 或未声明的
     configuration state。
- accepted_round_5_findings:
  1. `ownership_mode` 决定 SHADOW/ENFORCED authority 和 effect semantics，必须进入
     binding digest、durable disposition 与 status expected/actual validation；同一
     dispatch/body 的 cross-mode record 绝不能复用。
  2. legacy Worker operational strings 不能成为第二套 target state machine；必须删除
     或明确规范化到唯一 `availability/conflictState`，并冻结多 blocker 同时存在时的
     single-value precedence、合法组合和 clear/reveal 规则。
- accepted_round_6_finding:
  1. round-5 独立复审已确认所有 blocker 闭合，但 exact command surface 汇总表遗漏
     required expected ownership-mode header，且场景 36 错误允许 cross-mode 使用
     generic binding mismatch。command/status 两个入口及 SHADOW→ENFORCED、
     ENFORCED→SHADOW 两个方向必须唯一返回
     `409 LIFECYCLE_OWNERSHIP_MODE_MISMATCH`，不能用其他 409 code 通过验收。
- accepted_round_7_finding:
  1. round-6 独立复审确认 command binding、status、AC-26 和场景 36 已一致，但
     Fenced Dispatch Status 的并行/晚到 attempt 仍保留未限定 mode 的旧 generic
     binding-mismatch 表述。该路径同样必须先比较 durable mode，只有 mode exact
     match 后才允许返回 prior record 或
     `LIFECYCLE_DISPATCH_BINDING_MISMATCH`。
- round_7_final_review:
  - verdict: `APPROVED`
  - remaining_findings: `0 BLOCKER / 0 MAJOR / 0 MINOR`
  - r5_m1_01: `CLOSED`
  - source_slices_0_through_8: authorized-in-contract-order
  - real_activation: separately-authorized-and-currently-disabled

### Review Finding Closure Matrix

原独立审查共有 `5 BLOCKER + 5 MAJOR + 1 MINOR = 11` 项 finding，不是七项。以下 ID
固定沿用第一次审查的原始分类，避免后续轮次因重新编号丢失追踪关系：

| ID | Original Finding | Current Contract Disposition |
|---|---|---|
| B1 | terminal/tombstone 提交窗口 | CLOSED：canonical terminal、authorization tombstone、cleanup plan 同事务，失败整体回滚 |
| B2 | 首次 `ENFORCED` 早于完整 vertical chain | CLOSED：Slice 6 全链 SHADOW、Slice 7 readiness、Slice 8 才首次 enforcement |
| B3 | Worker v1 identity/inventory/ACK contract 不可执行 | CLOSED BY ROUND-7 REVIEW：identity/inventory/ACK、mode-bound exact command wire/status、provider Task identity、one-use ordering 和 durable negative 已冻结 |
| B4 | mixed-version/旧 writer 无真实 fence | CLOSED BY ROUND-5 REVIEW：homogeneous controller lock + DB proof lease、逐 Worker/Session/Task reference 与 effect authorization linearization 已确认 |
| B5 | admin logical close 无现有高权限边界 | CLOSED：从 MVP-A 延后，现有 `force` 仍是 provider cancellation |
| M1 | cleanup applicability 无 N/A 语义 | CLOSED：terminal transaction 冻结逐 participant `REQUIRED/NOT_APPLICABLE` |
| M2 | WorkerLifecycleOwner 模块/SPI 方向未冻结 | CLOSED：`session-module -> navigator-spi <- addon/business participant` |
| M3 | termination/cross-surface compatibility mapping 未闭合 | CLOSED BY ROUND-7 REVIEW：public receipt-disabled one-shot、enabled persistence failure、legacy operation mapping、唯一 availability/conflict vocabulary 与 precedence 已冻结 |
| M4 | production migration/startup gate 不安全 | CLOSED：schema pre-apply、validate、readiness fail closed |
| M5 | failure-first 验收矩阵不完整 | CLOSED BY ROUND-7 REVIEW：exact cross-mode code、provider identity、state precedence、proof-reference/effect-loss races、receipt failure/codex-biz typed lane 已纳入 |
| m1 | 前端 localStorage 草案超出阶段范围 | CLOSED：降为 non-normative deferred design |

### Round-3 Review Closure Matrix

| ID | Round-3 Finding | Round-3 Contract Disposition |
|---|---|---|
| F-01 | Worker command/duplicate wire 未冻结 | CLOSED BY ROUND-7 REVIEW：disposition/status digest/proof、route-specific binding、durable provider Task ID required/null matrix 已冻结 |
| F-02 | 非耐久 negative 被当作 never-accepted proof | CLOSED BY ROUND-7 REVIEW：store unavailable 永远 frozen；只有 expected-binding matched durable `never_accepted_proof=true` 可证明 |
| F-03 | exclusivity proof 止于首次 enrollment | CLOSED BY ROUND-7 REVIEW：proof lease 持续 observer、逐 Worker/Session/Task reference、proof-loss quarantine 与 effect authorization linearization 已冻结 |
| F-04 | disabled duplicate 与 BUG-035 冲突 | CLOSED BY ROUND-4 REVIEW：保留同 request ID 每次都是新 one-shot、provider 可调用两次的既有行为 |
| F-05 | enabled receipt persistence failure 未冻结 | CLOSED BY ROUND-4 REVIEW：receipt + owner intent 同事务、effect 前置；失败 stable `REJECTED` 且 provider 调用为零 |
| F-06 | cutover drill 授权/预算矛盾 | CLOSED BY ROUND-4 REVIEW：repo-owned fixture 与未获批真实 controller rehearsal/activation 已分离 |
| F-07 | failure-first matrix 缺关键竞态/codex-biz lane | CLOSED BY ROUND-7 REVIEW：provider identity/binding、proof-reference、effect-loss races 与唯一 enum tests 已纳入 |

### Round-3 Replan Resolutions

1. Worker lifecycle v1 复用现有 Codex runtime endpoint credential，也就是 Worker
   `CODEX_WORKER_TOKEN` 与 Navigator 加密保存的对应 auth token；但 lifecycle endpoint
   和携带 lifecycle context 的 command 独立 fail closed，不能继承“token 为空则放行”
   的 legacy 行为。`/health` 仍公开且只返回 content-free readiness。
2. inventory、events 与 ACK 使用 required expected physical Worker ID/state generation
   headers；缺失、实际值不匹配或 credential 不可用时，在任何 fact 消费或 provider
   effect 前 definitive reject。Worker v1 同时定义 `SHADOW` 与 `ENFORCED`：
   `SHADOW` 只记录观察事实/proposed effect，durability gap 不改变 legacy effect，但会
   阻止 parity 通过与 enrollment；`ENFORCED` persist-before-effect、失败即 fail closed。
3. public typed termination/reconciliation 完整保留 BUG-035 receipt-disabled contract：
   disabled 时仍返回真实单次 termination outcome；相同 client request ID 的每次 HTTP
   termination 都是新的 one-shot attempt，服务端不得用 owner ledger 抑制第二次
   provider call。reconciliation 必须是
   `AMBIGUOUS + TERMINATION_REQUEST_RECEIPT_DISABLED`，availability/replay flags 保持
   fail closed。首次 `codex-biz-worker` canary 额外要求 receipt enabled。
4. exact authenticated Worker pre-effect rejection 只有在 Worker lifecycle store 已
   durable 保存、可由 fenced dispatch-status endpoint 重读、stable code 位于冻结
   allowlist，且 Navigator 已排除 stale delivery response 时，才可形成
   `TASK_NEVER_ACCEPTED_CONFIRMED`。store unavailable、timeout、response loss、404、
   inventory absence 和仅来自原 command response 的 negative 都只能
   `AMBIGUOUS/FROZEN`。
5. initial Task dispatch 使用 Worker durable `dispatch_id` 做 exact technical
   redelivery 去重。ENFORCED 下相同 `dispatch_id` 只能返回同一 durable
   acceptance/rejection，不能触发第二次 provider effect；SHADOW 只记录
   `WOULD_DEDUPE` 而不改变 legacy invocation。不得换 dispatch ID、Task ID、
   termination operation 或 client request ID 伪装为重试。
6. homogeneous cutover 的 exclusivity proof 覆盖旧 deployment unit、replica、
   restart policy、supervisor、timer/job 与 launcher，而不只是某一时刻进程为零。它以
   DB lease 从 admission drain 连续覆盖到所有 Worker/Session/Task proof reference
   释放且 unfinished outbox 归零；proof 过期或 drift 时，禁止新 enrollment 并
   quarantine 既存 aggregate。
   无法技术性枚举、锁定和持续观察任何自动/人工重启来源时，必须实现 database-level
   legacy-writer guard 后重审。
7. Worker v1 command wire 使用 exact route-specific contract：query/create/resume
   保留 `200 text/event-stream`，abort 保留 `202 application/json`，新增只读 fenced
   dispatch-status；现有 mutating termination-reconcile 不进入 ENFORCED authority，
   Codex SDK Worker 的 permission/input response 明确 N/A。
8. receipt enabled 的 termination admission 必须在同一 Navigator transaction 中
   durable 创建 public receipt、owner operation/intent 和 outbox binding，commit 后才
   能调用 provider；任一持久化失败返回
   `TERMINATION_REQUEST_RECEIPT_PERSISTENCE_FAILED` 且 provider invocation count 为零。
9. 当前 source implementation 授权只包含 repo-owned、可销毁、无共享 process/controller
   mutation 的 automated fixture rehearsal；真实 deployment controller disable、
   stop/start、late-relaunch 和首次实际 enrollment 均需要后续单独授权。

### Round-4 Review Closure Matrix

| ID | Round-4 Finding | Round-4 Contract Disposition |
|---|---|---|
| R4-B1 | dispatch reread 无法证明 exact local binding | CLOSED BY ROUND-7 REVIEW：binding/disposition/status exact 包含 `ownership_mode`；command/status cross-mode mismatch 的唯一 HTTP/code/test 已冻结 |
| R4-B2 | accepted query 无 durable provider Task identity | CLOSED BY ROUND-7 REVIEW：accepted `PREPARED` 原子保存 provider Task ID，并由 mode-bound status 安全恢复 |
| R4-B3 | proof 未覆盖 Worker/Session 且 effect-claim/loss 无顺序 | CLOSED BY ROUND-5 REVIEW：逐 aggregate reference 与两种 CAS 顺序已确认 |
| R4-M1 | availability/conflict vocabulary 矛盾 | CLOSED BY ROUND-7 REVIEW：删除第二套 target Worker states，blocker mapping、single-value precedence、合法组合与 clear/reveal rule 已冻结 |
| R4-m1 | never-accepted 标题疑似重复 | CLOSED：当前 canonical 文件只有一个对应 heading，并改为唯一名称 `Mode Semantics and Durable Never-accepted Proof` |

### Round-4 Replan Resolutions

1. durable disposition 对所有 durable phase 都 required
   `ownership_mode`、`safe_binding_digest_version=JCS_SHA256_V1` 与
   `safe_binding_digest`；read-only status 必须由 Navigator 提交 expected
   mode/digest/version 并由 Worker exact compare 后才返回 record。未匹配 record 不能
   形成 fact。
2. query create/resume 的 `provider_task_id` 是 accepted disposition 的 durable output，
   不进入调用方可预计算的 binding digest；它必须与 `PREPARED` 在同一 Worker-local
   atomic record 中创建，后续 provider call、SSE、inventory、duplicate 和 status
   只能使用该值。
3. `never_accepted_proof` 是 durable disposition 的 required boolean，只有 initial
   create/resume 的 allowlisted `REJECTED/PRE_EFFECT` 可为 true；SHADOW、503、generic
   error、termination rejection 和所有 accepted phase 均不能提供该 proof。
4. proof reference 按 Worker、Session、Task 独立记录。Worker 处于 ENFORCED ownership、
   Session 仍 OPEN、Task cleanup/outbox 未完成任一条件成立时，proof 均不能释放。
5. `EXTERNAL_PROVIDER_ONCE` outbox 的 `CLAIMED` 仅表示 handler ownership。真正
   provider-effect authorization 是
   与 proof-loss 共用 row lock/CAS 的
   `lifecycle_effect_outbox.effectState=EFFECT_STARTED` commit：loss 先提交则调用为
   零；authorization 先提交则该 exact effect 最多调用一次，随后 loss 只允许
   quarantine，不得重投。
6. snapshot field 固定为 `availability` 与 `conflictState`。配置、存储、离线和 writer
   authority 分别投影到唯一 enum及冻结 precedence；public SDK availability flags
   不因此改变 wire。

### Round-5 Review Closure Matrix

| ID | Round-5 Finding | Round-5 Contract Disposition |
|---|---|---|
| R5-B1 | ownership mode 未进入 exact binding | REVISED：JCS binding object、route input、durable disposition、expected status header/response 均 required mode；same-dispatch cross-mode 返回 409且不调用 provider |
| R5-M1 | Sentinel/Worker 第二套 state 与组合 precedence 未冻结 | REVISED：删除 target `ONLINE/SUSPECTED/RECOVERING_RECONCILIATION/STORAGE_PRESSURE_*` enum；仅保留 ephemeral backoff metadata，补 normalized fact mapping、合法 pair 和全序 precedence |

### Round-5 Replan Resolutions

1. `ownership_mode` 是 binding authority input。它与 command kind、route、Task、
   dispatch、payload/capability digest 一起进入 `safe_binding_digest`，并作为 durable
   disposition required field。相同 payload 的 SHADOW/ENFORCED digest 必须不同。
2. dispatch-status required expected mode header。record mode mismatch 使用
   `LIFECYCLE_OWNERSHIP_MODE_MISMATCH`；digest mismatch 继续使用
   `LIFECYCLE_DISPATCH_BINDING_MISMATCH`。任一 mismatch 不返回 actual
   mode/digest/provider Task ID，不产生 fact/effect。
3. provider Task ID recovery 只有在 expected mode + digest + identity 全部匹配后可信；
   SHADOW record 无论字段多完整都不能被 ENFORCED reducer摄取。
4. target schema 只有 `availability/conflictState`。Sentinel attempt/backoff/circuit
   breaker 是可重建的 lease metadata；legacy operational strings 只允许在 adapter
   source side出现，规范化后不得持久化为 owner state。
5. conflict precedence 固定为 writer exclusivity loss > Worker state loss > evidence
   conflict > none；availability precedence 固定为 authority quarantine > storage >
   configuration > offline > recovering > ready。所有 active blocker facts 保留，最高项
   clear 后 full recompute 显露下一项。
6. `conflictState != NONE` 与 `availability=AUTHORITY_QUARANTINED` 必须同时成立；
   其他 availability 只能搭配 `NONE`。unknown input不得创建新 enum。

### Round-5 Independent Rereview Closure Matrix

| ID | Round-5 Rereview Finding | Round-6 Contract Disposition |
|---|---|---|
| R5-B1 | ownership-mode binding | CORE CLOSED：mode 已进入 JCS、route inputs、disposition、facts/inventory、status 与 ENFORCED never-accepted proof；其 exact mismatch 验收歧义由 R5-M1-01 单独追踪 |
| R5-M1 | target lifecycle 双状态机与多 blocker reducer | CLOSED：target 只保留 `availability/conflictState`；legacy state、precedence、合法 pair、clear/reveal、scope 与 incremental/full parity 均已冻结 |
| R5-M1-01 | exact command/status mismatch 验收口径不唯一 | CLOSED BY ROUND-7 REVIEW：汇总表、command/status、AC-26、场景 36 与并行/晚到 attempt 全部采用 mode-first；cross-mode 仅接受 exact `409 LIFECYCLE_OWNERSHIP_MODE_MISMATCH` |

### Round-6 Replan Resolutions

1. exact dispatch reread 的 request surface 必须同时列出 expected identity、required
   expected ownership mode 与 expected binding digest/version；汇总表不得省略 mode。
2. 已存在相同 `dispatch_id`、但 durable `ownership_mode` 与 command context 或 status
   expected-mode 不同，无论 `SHADOW -> ENFORCED` 还是
   `ENFORCED -> SHADOW`，都必须在 digest comparison、duplicate handling、capability
   consumption、fact ingestion 和 provider effect 前唯一返回
   `409 LIFECYCLE_OWNERSHIP_MODE_MISMATCH`。不得降级或归并为
   `LIFECYCLE_DISPATCH_BINDING_MISMATCH`。
3. `409 LIFECYCLE_DISPATCH_BINDING_MISMATCH` 只适用于 durable mode 已 exact 匹配，
   但其余 route-specific binding input/digest 不同的情况。所有 cross-mode negative
   response 均不得回显 actual mode/digest/provider Task ID，不得新增 owner fact，
   provider invocation count 相对已有 record baseline 不增加。

### Round-6 Independent Rereview Closure Matrix

| ID | Round-6 Rereview Finding | Round-7 Contract Disposition |
|---|---|---|
| R5-M1-01 | 并行/晚到 attempt 的旧句未限定 mode-first | CLOSED BY ROUND-7 REVIEW：先比较 durable ownership mode；mode 不同 exact 返回 ownership-mode mismatch，只有 mode exact match 才可返回 prior record 或 binding mismatch |

### Round-7 Replan Resolution

Worker 对相同 `(physicalWorkerId,stateGeneration,dispatchId)` 的并行或晚到 attempt
必须先比较 durable `ownership_mode`。mode 不同必须在 digest comparison、duplicate
handling、capability consumption 和 provider effect 前返回
`409 LIFECYCLE_OWNERSHIP_MODE_MISMATCH`；只有 mode exact match 后，才可按其余 binding
决定返回同一 durable record 或 `409 LIFECYCLE_DISPATCH_BINDING_MISMATCH`。晚到的旧
client-side response 仍只记 audit，不能生成 owner fact。

### Round-7 Independent Rereview Approval

- verdict: `APPROVED`
- remaining_findings: `0 / 0 / 0`
- R5-M1-01: `CLOSED`
- compatibility: BUG-035 receipt-disabled one-shot、public SDK wire 和现有 provider
  mapping 保持不变。
- implementation_authority: Source Slice 0–8 可按本文依赖顺序执行；批准本 spec 不等于
  授权跳过 SHADOW、直接 enrollment 或执行真实 activation。
- activation_authority: Slice 7/8 只限 repo-owned ephemeral fixture；真实
  controller/process、首次非 fixture `ENFORCED` aggregate 与 live SIM 仍未授权。

## Goal

- source_bug: `BUG-036`
- version_goal: 消除 Session/Task lifecycle 的多点 authority 和无界悬挂，使正常终态、
  offline freeze、Worker reconnect、termination reconciliation 与 cleanup 由一个可审计、
  有界且兼容现有调用方的决策链收敛。
- target_outcome: hierarchical lifecycle owners + authoritative facts +
  deterministic reducers + durable inbox/outbox + reconciliation loop
- critical_outcomes:
  - Worker/runtime execution facts 与 Navigator canonical/control-plane facts 的 authority
    边界不再被 ACK、文本、断连、超时或局部表状态穿透。
  - accepted termination 最终基于可信 evidence 收敛到 canonical terminal，或明确
    rejected/ambiguous；receipt-enabled canary 的同 request ID 查询不产生第二次
    provider command，receipt-disabled public caller 继续得到 BUG-035 定义的明确
    `AMBIGUOUS`。
  - authorization tombstone 与 canonical terminal 同事务形成 fail-closed safety
    fence；token row revoke、compatibility projection 和 receipt update 再按冻结的
    cleanup applicability plan 异步收敛，typed `TERMINAL` 只在全部 required
    checkpoint 齐备后成立。
  - Worker 离线只冻结 Worker-dependent 操作；统一 Sentinel 完成身份、能力、inventory
    和 stream cursor 对账后，owner 才重新计算 Worker/Task/Session gate。
  - 同一 Session 只能有一个 foreground Task；兼容状态/API 保留，第一阶段不要求历史
    aggregate 全量迁移。
- success_is_sufficient_when: 第一阶段 additive shadow 与 isolated new-aggregate
  ENFORCED fixture 的全部 must-pass 自动化证据通过，无 unexplained parity diff、重复
  provider effect、伪 terminal 或 terminal cleanup 残缺；真实 controller cutover、
  first deployment enrollment、live SIM、发布或历史 Task mutation不作为 source
  implementation 完成前提，且必须保持明确 disabled/unperformed。

## Delivery Scope

- in_scope:
  - Task/Session/Worker lifecycle normalized fact、deterministic reducer、snapshot、
    ownership mode、durable inbox/outbox 与 compatibility projection 的最小实现。
  - BUG-036 相关 Codex SDK termination/typed reconciliation/terminal cleanup vertical
    slice 接入 owner。
  - provider-neutral Worker Sentinel core、DB lease/fencing 与 Codex SDK
    probe/inventory/lifecycle-stream adapter。
  - Codex SDK Worker additive lifecycle v1 identity/inventory/event/ACK/durability
    contract；首个 canary 只允许 `providerType=codex-biz-worker`，`codex-worker` 仅
    SHADOW。
  - Worker offline command gate、reconnect reconciliation、Session foreground
    single-flight 与 late evidence 规则。
  - additive schema、forward/rollback migration、configuration、focused/affected tests、
    shadow parity 与 canary feature gate。
- affected_modules:
  - `session-module`
  - `business-agent-module`
  - `addons/codex-worker-agent`
  - `addons/claude-worker-agent` only for shared Open API/runtime typed
    reconciliation/compatibility adapters used by Codex business runtime
  - `navigator-common` migration support
  - `launcher` configuration and affected-reactor validation
  - `navigator-spi` provider-neutral lifecycle ports/value contracts
  - `tools/codex-agent-worker` Worker lifecycle v1 implementation
  - `navigator-open-sdk` published typed compatibility contract tests；除非实现发现 public
    DTO/wire 无法保持，否则不修改 SDK product surface
- dependency_direction:
  - `session-module` owns Session/Task lifecycle orchestration and persistence；不得反向依赖
    provider addon 或 `business-agent-module`。
  - 必须跨模块共享的 participant/fact/effect contract 才放入 `navigator-spi`，且保持
    provider-neutral、无 JPA/Spring business implementation。
  - provider addon 与 `business-agent-module` 实现 participant/adapter 并提交
    fact/checkpoint；它们不能把 repository direct write 暴露为 owner API。
  - `navigator-common` 只承载通用 migration support，`launcher` 只做 wiring/config，
    两者不承载 lifecycle business orchestration。
- external_dependencies:
  - MySQL-compatible lifecycle persistence.
  - Existing Codex SDK Worker health/task/SSE contracts plus the additive Worker
    lifecycle v1 contract frozen below；不得假设未验证的 provider behavior。

## Non-Goals

- 不建设通用 Event Sourcing/event bus、Actor runtime 或跨产品 workflow 平台。
- 不一次迁移所有历史 Task，不删除旧 status/API/DTO，不让 legacy column 反向成为
  enforced owner authority。
- 不实现 Session transfer、跨 Worker 自动迁移、provider/modelConfig 自动切换。
- 不在本交付实现 Physical Worker claim/reclaim、旧 ID 迁移或 Worker 首次启动自动生成
  Physical Worker ID；MVP-A 只使用已配置且经 endpoint binding 验证的现有 Worker ID。
- 不在本交付实现 admin logical Task close、Worker permanent-loss confirmation 或
  privileged Session close/transfer；现有普通 `force` API 不改变语义。
- 不在 MVP-A 迁移 Claude `WorkerHealthChecker`、Claude `WorkerStreamRelay`、
  `TaskStateReconciler` 三条 Worker lifecycle 循环，也不修改 Python Worker lifecycle
  contract；它们进入 MVP-B 独立复审切片。位于该 addon 的共享 Open API/typed runtime
  adapter 仍可在 Codex MVP-A 的直接影响范围内修改。
- 不在第一阶段实现前端 `localStorage` retry draft/向上箭头 UX；其服务端
  no-pending/no-auto-replay contract 保持冻结，前端保留为独立后续切片。
- 不改变 BUG-035 已发布的 receipt-disabled typed termination/reconciliation 语义，不让
  owner 内部 ledger 暗中把 `requestReconciliationAvailable` 改为 true；唯一新增的是
  live ENFORCED aggregate 配置漂移时在 operation acceptance 前的 stable safety gate，
  不产生 provider effect。
- 不新增 Worker lifecycle credential 或复用 `CODEX_NAVIGATOR_WORKER_CREDENTIAL`；
  MVP-A 只对现有 Codex runtime endpoint auth token 增加 lifecycle 专用 fail-closed
  要求。
- 不覆盖 Gemini、LangGraph、Claude、Codex app-server 等其他 runtime；未接管
  aggregate 保持 `LEGACY`。
- 不支持 mixed-version rolling Java cutover；未来若需要滚动升级，必须先另行批准
  database-level stale-writer guard。
- 不修改或重放历史 Task `20260730-0e01`，不访问 TMS 业务数据，不修改
  `foggy-world-sim`。
- 不部署、重启、push、tag、release 或发布 SDK/CLI/Worker；live canary/SIM 重跑需要
  后续明确授权。

## Confirmed Principles

1. Worker/runtime 是 provider turn、CLI/process 和 verified exit 等执行域事实的
   authority；Navigator 不得自行推测这些事实。
2. Navigator 是 request idempotency、owner/binding、canonical Session/Task、token、
   registration projection、receipt 和 cleanup 的 authority。
3. Worker 断连、SSE 断开、heartbeat stale 或 status query timeout 只说明观测不可用，
   不代表 Worker Task aborted、failed 或 terminal。
4. Worker 断连后，相关 Session/Task 进入可逆的 operational freeze；等待 Worker
   重新上线并完成身份、能力和 Task 对账后重新计算并决定是否解冻。
5. 正常 execution-derived terminal 必须具有可信 Worker/runtime terminal evidence。
   admin logical close 与 permanent-loss authority 不属于 MVP-A，当前不存在可复用的
   高权限调用面。
6. absence of evidence 不是 evidence of absence；长期不可观测只更新 operation
   `AMBIGUOUS` 和适用 aggregate 的 `availability=OFFLINE_FROZEN`，可信事实互相冲突
   则设置 `availability=AUTHORITY_QUARANTINED +
   conflictState=EVIDENCE_CONFLICT`，均不得制造 terminal。
7. Worker `availability=OFFLINE_FROZEN` 时，所有依赖 Worker 的写操作明确拒绝且不在
   服务端保存 pending intent；前端可以保留本地失败项/草稿，由用户恢复后显式重新发起。
8. Worker recovery 采用两层解冻：identity、capability 和 inventory 对账完成后 Worker
   可以接收新 Task；重连前的旧 Task 分别重算，未明确的 Task 继续保持 frozen，不阻塞
   已恢复 Worker 的新 Task。
9. MVP-A 的 transitional `physicalWorkerId` 是 Navigator 中已配置并绑定 endpoint 的
   现有 Worker ID，Codex Worker 必须 exact echo；缺失或与本机 lifecycle record 冲突时
   lifecycle v1 不 ready。`stateGeneration` 随 durable lifecycle store 生命周期稳定，
   `instanceEpoch` 每次进程启动更新。
10. `CANCEL_REQUESTED` 只作为 SDK/UI compatibility status；owner 内部真相是
    `canonicalPhase=OPEN` 加非终态 `terminationState`。它不能作为 Worker 已停止、
    Task terminal 或 cleanup 可执行的证据。
11. termination 的“接受前拒绝”不创建 operation/receipt/pending intent；“接受后拒绝”
    固定原 operation 为 `REJECTED`，相同 client request ID 始终返回同一结果。新的
    termination 不能靠更换 request ID 绕过，只能在相关 Worker/Task/capability context
    发生可信变化且策略重新允许后由用户显式发起。
12. input/permission response 发送前遇到 Worker offline 时直接拒绝且不排队；发送后、
    definitive Worker result 返回前断连时必须返回 `AMBIGUOUS`。Navigator 和前端均不
    自动重发，只在 Worker 重连后根据 exact pending interaction inventory 重算。
13. 每个用户可见 Session 同时只允许一个 foreground Task 占用执行通道。
    `canonicalPhase=OPEN` 或 canonical terminal 后 cleanup 尚未完成的 Task 都继续占用；
    并行委派必须进入 child session/subtask aggregate，不能靠 provider 状态字符串或新
    request ID 绕过。
14. Worker reconnect 使用稳定 `physicalWorkerId`、持久 `stateGeneration` 和进程级
    `instanceEpoch` 三层身份，并通过 fenced inventory + transaction-after-commit
    monotonic ACK 对账。inventory absence、state generation 变化或 cursor coverage gap
    均不能直接产生 terminal；旧 Task 逐项重算，Worker gate 可独立恢复。
15. lifecycle owner 通过每 aggregate 单调
    `LEGACY -> SHADOW -> ENFORCED` 接管。`SHADOW` 不执行 effect；`ENFORCED` 是唯一
    canonical authority 且不可退回 legacy direct writes。rollout 先覆盖新 Session/Task
    canary，存量 active/ambiguous Task 只在 Worker inventory 对账后逐项接管。
16. Worker lifecycle retention 将安全 replay receipt 与 lifecycle evidence 分离；未
    ACK、active、frozen、ambiguous facts 无时间 TTL。磁盘压力只能压缩 eligible
    detail 或冻结新工作，不能删除 protected facts；任何外部副作用必须先 durable
    persist。
17. Java 与 Worker 的连接恢复采用 Worker-scoped Sentinel/Supervisor participant：
    Sentinel 管 health probe、断流检测、退避和 stream resume，lifecycle owner 只决定
    operational state、command gate 和 reconciliation effect。重连是技术恢复，不是
    Task、termination、permission 或 input command replay；单个 Task stream 断开也不
    自动等同整个 Worker offline。
18. MVP-A 只把 Codex SDK 纳入上述 owner/Sentinel chain；Claude 三条循环归一仍是确认
    的架构方向，但必须在 MVP-B 中实现同一 Worker v1 contract 并独立复审，不能作为
    MVP-A 的隐含兼容层。
19. 已由 Navigator 持久接受的 Task 只有在 fenced reread/fact 取得 exact durable、
    allowlisted Worker `REJECTED/PRE_EFFECT` disposition 后，才可以
    `WORKER_PRE_EFFECT_REJECTION` 为 terminal source 收敛到 `FAILED`；原 command
    response、store failure、response loss、timeout、404 和 inventory absence 均不满足。
20. `SHADOW` 与 `ENFORCED` 使用相同的 Worker v1 identity、wire shape、fact schema、
    dispatch identity 和 ACK contract；差别只在 effect/durability disposition：
    SHADOW gap 保留 legacy behavior 并阻止 parity/enrollment，ENFORCED gap 在 effect
    前 fail closed。
21. lifecycle v1 的鉴权不允许 optional：Worker token 未配置、Navigator binding 无
    credential 或请求 credential 无效时，lifecycle readiness false，不能以 legacy
    unauthenticated fallback 继续。
22. owner internal termination ledger 与 public receipt 是两个契约层。ENFORCED
    receipt-enabled admission 将二者同事务绑定；LEGACY/SHADOW receipt-disabled
    one-shot 不创建 owner intent，内部 ledger 不能 suppress provider，也不能改变
    BUG-035 public availability、reason 或 replay flags。

## Hierarchical Ownership

统一指每个 aggregate boundary 只有一个 canonical decision owner，不是所有状态集中到
一个类。

```text
WorkerLifecycleOwner --------+
  owns Worker gate/lease     |
                             v
SessionLifecycleOwner --> TaskLifecycleOwner --> TerminationProcessManager
  owns foreground lane       owns one Task       owns immutable operation
                             |
                             v
Provider / Worker / Token / Projection / Receipt participants
  report authoritative facts and execute idempotent effects
```

- Session owner 不读取 Worker 原始事件。
- Task owner 不读取 provider-specific payload 或日志。
- Termination owner 不直接撤销 token、修改 receipt 或调用任意模块 repository。
- participant 不直接写 lifecycle phase，只提交 fact 或 effect result。

### Module Ownership and Port Direction

| Module | Owns | Must Not Own |
|---|---|---|
| `session-module` | Worker/Session/Task owner、Termination process orchestration、normalized fact/snapshot/outbox persistence、Sentinel scheduling/DB lease、writer exclusivity proof lease/live-reference/quarantine | provider payload parser、deployment-controller shell/API、business token repository、Worker HTTP implementation |
| `navigator-spi` | provider-neutral immutable value contract 与 owner-to-participant ports：Worker probe/inventory/lifecycle-stream、writer-exclusivity observation、cleanup participant、normalized result | JPA entity/repository、Spring orchestration、provider/deployment-specific status |
| provider addon | Worker wire adapter、provider result normalization、legacy compatibility projector | canonical reducer、独立 lifecycle timer/lease、enforced direct writer |
| `business-agent-module` | authorization tombstone/token/receipt cleanup participant | Task/Session canonical status |
| `navigator-common` / `launcher` | migration support / wiring、configuration、readiness aggregation；test profile 可装配 ephemeral writer-proof fixture | lifecycle business orchestration、真实 controller mutation |
| separately authorized activation adapter/tooling | exact target 的 controller/process inventory observation 与 disable/drill action | 直接写 owner snapshot/proof row、读取业务 Task、绕过 target authorization |

依赖方向固定为 `session-module -> navigator-spi <- addon/business participant`；Spring
wiring 由 `launcher` 聚合。`session-module` 不得为了读取现有 Worker repository 而反向
依赖 addon；provider inventory 必须经 SPI port 返回 provider-neutral snapshot。writer
proof 也只能消费 SPI 的 content-free controller/process observation；proof lease、live
aggregate references 和 quarantine decision 仍由 `session-module` 事务维护。真实
activation adapter 的 credential/call surface 在取得 exact target 授权时作为 deployment
input 冻结；当前 source implementation 不新增公开管理 endpoint。

## Fact, Command, Effect and Projection

| Term | Definition |
|---|---|
| Command | 请求 participant 执行动作；在被接受前不是事实 |
| Fact | 某个 authority 已确认发生的不可变、幂等记录；纠正使用 superseding fact |
| Observation | 有 freshness/validity 的可过期事实；失效只表示 unknown |
| Evidence | 与 exact aggregate/operation/identity 关联、可验证且可重放的执行事实 |
| Decision Fact | Navigator 在其 control-plane authority 内作出的持久决定；MVP-A 不含管理员 decision |
| Effect | reducer 请求独立 handler 执行的副作用 |
| Checkpoint Fact | effect handler 幂等完成后返回的结果事实 |
| Projection | reducer 根据事实计算出的当前 canonical/operational read model |

Command、fact 和 effect 必须使用不同类型，禁止用一个模糊的 `status` 或自由文本同时
表达三者。

## Canonical Projection Dimensions

当前实现中的单一 Task `status` 同时承载 `PENDING`、`RUNNING`、
`AWAITING_PERMISSION`、`CANCEL_REQUESTED`、`COMPLETED`、`FAILED` 和 `ABORTED`；
Session `ACTIVE/PAUSED/COMPLETED/DELEGATED/DELETED` 也同时混合 canonical lifecycle、
当前活动和 operational availability。继续向这些枚举追加 offline、recovering、
ambiguous 等值，会让每个局部服务再次各自解释状态。

owner 内部确认重算正交状态维度，并把现有 `status` 保留为 compatibility/display
projection：

内部 snapshot 的 operational vocabulary 固定如下；Worker、Task、Session 使用同一
字段名和枚举，不得由 adapter 自行追加近义值：

| Field | Frozen Values | Rule |
|---|---|---|
| `availability` | `READY`, `OFFLINE_FROZEN`, `RECOVERING`, `STORAGE_FROZEN`, `CONFIGURATION_FROZEN`, `AUTHORITY_QUARANTINED` | 只表达当前是否允许 Worker-dependent mutation；不表达 canonical terminal |
| `conflictState` | `NONE`, `EVIDENCE_CONFLICT`, `WORKER_STATE_LOSS`, `LEGACY_WRITER_EXCLUSIVITY_LOST` | 只表达需要人工/重新对账的 authority conflict 原因 |

`QUARANTINED` 不是独立 `availability` 值，必须写为
`availability=AUTHORITY_QUARANTINED`；`authorityConflict` 不是第二个 snapshot 字段，
必须使用 `conflictState`。config drift、proof loss 或 late evidence 不得再创造未列出的
enum。public DTO 中既有 availability/reconciliation booleans 不直接暴露这些内部
枚举，因此本次统一不改变 SDK wire。

每个 aggregate 保留所有仍有效的 normalized blocker facts，reducer 再按下表计算一个
`availability` 和一个 `conflictState`；后到 fact 不能覆盖或删除另一个仍有效条件：

| Active condition/fact | Availability candidate | Conflict candidate | Clear condition |
|---|---|---|---|
| exact writer proof `LOST` | `AUTHORITY_QUARANTINED` | `LEGACY_WRITER_EXCLUSIVITY_LOST` | proof restored + exact legacy-drift scan complete |
| durable Worker state generation loss/reset or unrecoverable protected coverage gap | `AUTHORITY_QUARANTINED` | `WORKER_STATE_LOSS` | new generation baseline explicitly established and affected aggregate reconciliation complete |
| mutually contradictory exact lifecycle evidence or Worker/provider Task/mode identity conflict | `AUTHORITY_QUARANTINED` | `EVIDENCE_CONFLICT` | superseding evidence/conflict decision explicitly resolves exact conflict |
| `WORKER_LIFECYCLE_STORAGE_FROZEN` | `STORAGE_FROZEN` | `NONE` | storage recovered + durability probe complete |
| required lifecycle/receipt/capability configuration unavailable | `CONFIGURATION_FROZEN` | `NONE` | exact configuration readiness restored |
| disconnect or observation freshness expired | `OFFLINE_FROZEN` | `NONE` | reconnect fact received；仍先进入 recovering |
| reconnect/storage recovery observed but identity/capability/inventory reconciliation incomplete | `RECOVERING` | `NONE` | `WORKER_RECONCILIATION_COMPLETED` for exact affected scope |
| no active blocker | `READY` | `NONE` | 任一新 blocker fact 生效 |

单值 precedence 固定为：

```text
conflictState:
  LEGACY_WRITER_EXCLUSIVITY_LOST
  > WORKER_STATE_LOSS
  > EVIDENCE_CONFLICT
  > NONE

availability:
  AUTHORITY_QUARANTINED
  > STORAGE_FROZEN
  > CONFIGURATION_FROZEN
  > OFFLINE_FROZEN
  > RECOVERING
  > READY
```

合法组合只有：

- `conflictState != NONE` 当且仅当
  `availability=AUTHORITY_QUARANTINED`；
- `STORAGE_FROZEN|CONFIGURATION_FROZEN|OFFLINE_FROZEN|RECOVERING|READY` 必须搭配
  `conflictState=NONE`；
- 多个非 conflict blocker 同时存在时显示最高 precedence，较低 blocker 事实继续保留；
  最高项解除后在同一次 full recompute 中显露下一项；
- Task/Session/Worker 分别只计算作用于自身 scope 的 facts。一个 Task
  `EVIDENCE_CONFLICT` 不把已恢复 Worker 或其他 Session 置为 quarantine；
- canonical terminal/cleanup 与 operational pair 正交。Task 可以在 terminal safety
  fence 已提交后仍因 proof/storage conflict 保持 cleanup frozen，但不能 reopen。

任何 unknown/future input 都不能生成新 enum：无法分类的 authenticated evidence 设置
exact aggregate `conflictState=EVIDENCE_CONFLICT`；无法鉴权或无法绑定的 input 不进入
fact set，只保留 stable rejection/audit。

### Task Lifecycle Snapshot

| Dimension | Initial Values | Semantics |
|---|---|---|
| `canonicalPhase` | `OPEN`, `TERMINAL` | logical Task 是否已经不可逆关闭 |
| `terminalOutcome` | null, `COMPLETED`, `FAILED`, `CANCELLED` | 仅 `TERMINAL` 时存在 |
| `terminalSource` | null, `WORKER_EVIDENCE`, `WORKER_PRE_EFFECT_REJECTION` | 正常执行终态只允许可信 Worker evidence；第二值仅用于 exact never-accepted proof；外部 logical-close authority 未定义 |
| `dispatchState` | `NONE`, `RESERVED`, `DISPATCHED`, `WORKER_ACCEPTED`, `REJECTED` | dispatch 子流程事实 |
| `executionObservation` | `UNKNOWN`, `NOT_STARTED`, `RUNNING`, `STOPPED` | Worker/runtime 最近可信执行观察 |
| `interactionState` | `NONE`, `AWAITING_INPUT`, `AWAITING_PERMISSION`, `RESPONSE_AMBIGUOUS` | exact Worker interaction request projection |
| `interactionRef` | null or exact `requestId/type/version` | response 必须绑定的当前 pending request |
| `terminationState` | `NONE`, `REQUEST_ACCEPTED`, `DISPATCHED`, `ACKNOWLEDGED`, `REJECTED`, `AMBIGUOUS`, `CONFIRMED` | termination process projection |
| `availability` | 使用上述 frozen enum | Worker-dependent command gate |
| `cleanupState` | `NOT_REQUIRED`, `PENDING`, `RETRYING`, `COMPLETED` | tombstone、token、compatibility、receipt 等 terminal participant |
| `conflictState` | 使用上述 frozen enum | 可信事实冲突时阻止自动 mutation |

`CANCEL_REQUESTED` 不再是 owner 内部某个字段的唯一真相，而是已确认的兼容视图：

```text
canonicalPhase=OPEN
and terminationState in
  {REQUEST_ACCEPTED, DISPATCHED, ACKNOWLEDGED, AMBIGUOUS}
=> compatibilityStatus=CANCEL_REQUESTED
```

因此现有 SDK/API 仍可看到 `CANCEL_REQUESTED`，但 reducer 不会把它误读成 Worker 已停止。
terminal compatibility status 只能由
`canonicalPhase=TERMINAL + terminalOutcome` 得出。

compatibility/display projection 使用固定优先级：

1. `TERMINAL` 按 terminal outcome 显示 `COMPLETED/FAILED/CANCELLED`。
2. 非 terminal 且 Worker unavailable 时显示原 lifecycle status，并附加
   `availability in {OFFLINE_FROZEN, RECOVERING}`；不得把 freeze 覆盖成 terminal。
3. 非 terminal 且存在 termination progress 时显示 `CANCEL_REQUESTED`。
4. 其余按 interaction/execution/dispatch 显示
   `AWAITING_INPUT/RUNNING/SUBMITTED`。

### Frozen Status and Reconciliation Mapping

MVP-A 的 canonical cancellation spelling 固定为 `CANCELLED`。`ABORTED` 与 `CANCELED`
只保留为既有 surface 的兼容表示，不得反向成为 owner fact：

| Surface | Cancellation Value | Rule |
|---|---|---|
| Codex SDK Worker native lifecycle/terminal evidence | `ABORTED` | adapter 只有在 `terminal_observed=true`、可信 `terminal_source` 和 exact identity/correlation 同时满足时映射 |
| existing Codex/Session Java task row and frontend `ClaudeTask.status` | `ABORTED` | compatibility projector 从 canonical `CANCELLED` 写回；不能作为 reducer input |
| owner Task snapshot | `terminalOutcome=CANCELLED` | 唯一 canonical cancellation value |
| owner termination child aggregate | `terminationState=CONFIRMED` + `observedOutcome=CANCELLED` | `CONFIRMED` 只表示 exact operation 的最终 result 已确认 |
| A2A | `CANCELED` | 固定协议映射 |
| Open API、typed runtime task status、SDK/CLI | `CANCELLED` | public wire 保持现有 spelling |
| typed reconciliation | `reconciliationState=TERMINAL`, `canonicalTerminal=true`, `currentTaskStatus=CANCELLED` | 还必须满足 canonical Task 与 required cleanup gate |

现有 `termination_operations` 继续是兼容 child aggregate，不能只声明“保留”而不定义
新旧状态关系。owner -> legacy compatibility projection 固定为：

| Owner Termination Projection | Legacy `status/dispatchState` | Constraint |
|---|---|---|
| `REQUEST_ACCEPTED` | `ACCEPTED/PENDING` | 仍不是 Worker acceptance |
| `DISPATCHED` | `RUNNING/PENDING` | 只表示 exact dispatch attempt |
| `ACKNOWLEDGED` | `CANCEL_REQUESTED/ACKNOWLEDGED` | ACK 不成为 Task terminal |
| `AMBIGUOUS` before ACK | `RUNNING/UNCONFIRMED` | 保留 stable attention/failure code |
| `AMBIGUOUS` after ACK | `CANCEL_REQUESTED/ACKNOWLEDGED` | 保留 `TERMINATION_UNCONFIRMED`，不能改成 `ABORTED` |
| `REJECTED` | `REJECTED/REJECTED` | Task 仍按当前 execution facts 保持 `OPEN` |
| `CONFIRMED + observedOutcome=CANCELLED` | `ABORTED/OBSERVED` | 只有 exact correlated terminal evidence 才允许投影 |
| `CONFIRMED + observedOutcome=COMPLETED` | `COMPLETED/OBSERVED` | Task canonical outcome 由 Task owner 决定 |
| `CONFIRMED + observedOutcome=FAILED` | `FAILED/OBSERVED` | Task canonical outcome 由 Task owner 决定 |

legacy -> SHADOW adapter 使用同一语义，但 legacy 行本身不是 authority：

- `ABORTED/OBSERVED` 只有同时存在 exact Worker terminal/process-exit fact 与可信
  provenance 时才规范化为
  `terminationState=CONFIRMED + observedOutcome=CANCELLED`。仅由 target missing、
  status text 或旧 `markTargetAbsentForTask` 路径写出的相同枚举，分类为
  `OWNER_MISSING_FACT/AUTHORITY_CONFLICT`，不能产生 canonical terminal。
- `FAILED/UNCONFIRMED` 规范化为 `AMBIGUOUS`，不能把 operation delivery failure 误映射
  为 Task `FAILED`。
- `COMPLETED|FAILED|ABORTED` 搭配 `OBSERVED` 也必须通过 exact operation/task/Worker
  correlation；未知组合保持 `UNKNOWN/AMBIGUOUS`，不得 default-to-confirmed。
- ENFORCED 后这些 legacy 列只能由 compatibility projector 写；typed
  `currentTaskStatus` 永远读取 canonical Task projection，而不是
  `termination_operations.status`。

termination request result 与 Task terminal 是两个维度：

- 首次成功请求继续返回
  `terminationOutcome=ACCEPTED + canonicalTerminal=false`；ACK 不改变该语义。
- 同一 request ID 后续成功收敛时，原 request outcome 仍为 `ACCEPTED`，同时返回
  `reconciliationState=TERMINAL + canonicalTerminal=true`；不得新增 public
  `CONFIRMED` outcome。
- `terminationState=AMBIGUOUS` 可以在同一 operation 的晚到 exact evidence 到达后单调
  推进为 `CONFIRMED`；不能重发 provider termination。definitive `REJECTED` 不再转为
  `CONFIRMED`。
- Task 因独立可信 terminal evidence 进入 `COMPLETED/FAILED/CANCELLED` 时，typed
  reconciliation 可以如实返回 canonical `TERMINAL`；只有 evidence 与 exact
  termination operation 相关时，内部 termination state 才能标记 `CONFIRMED`。
- 未知或未来 Worker status 使相关 operation 保持 `AMBIGUOUS`；若它来自已鉴权但无法
  归类的 exact evidence，同时设置
  `availability=AUTHORITY_QUARANTINED +
  conflictState=EVIDENCE_CONFLICT`。禁止 default-to-running、default-to-terminal 或
  创建新 enum 掩盖。

receipt-disabled public matrix 固定沿用 BUG-035；唯一额外 gate 是 live ENFORCED aggregate
发生 receipt configuration drift 时，在新 operation acceptance 前 fail closed，见表后：

| Call | Public Result |
|---|---|
| termination receipt enabled 且 exact receipt persisted | 返回真实 outcome；`terminationRequestReceiptEnabled=true`、`terminationRequestReceiptPersisted=true`、`requestReconciliationAvailable=true` |
| termination receipt enabled 但 admission transaction 未能持久化 | 在任何 provider/Worker effect 前返回现有 typed termination DTO：`outcome=REJECTED`、`reasonCode=TERMINATION_REQUEST_RECEIPT_PERSISTENCE_FAILED`、`terminationDispatched=false`、`idempotentReplay=false`、`reconcileRequired=false`、`terminationRequestReceiptEnabled=true`、`terminationRequestReceiptPersisted=false`、`requestReconciliationAvailable=false`；`canonicalTerminal/currentTaskStatus` 只反映当前 Task，不因此失败改变；不得裸抛 500 后继续 effect |
| termination receipt disabled，且 aggregate 未被 ENFORCED receipt-required gate 冻结 | 仍执行并返回真实单次 outcome；上述 availability/persisted 字段均为 false；相同 client request ID 的每次 HTTP termination 都是新的 one-shot attempt |
| reconciliation while receipt disabled | `reconciliationState=AMBIGUOUS`、`terminationOutcome=UNKNOWN`、`reasonCode=TERMINATION_REQUEST_RECEIPT_DISABLED`、`requestReconciliationAvailable=false`、所有 replay/new-ID flags fail closed |

public reconciliation 在 disabled 分支不得读取 owner internal ledger 来改变上述结果。
公开文档也继续声明 disabled 时没有 receipt-backed request-ID idempotency guarantee；
owner dispatch ledger 不得介入该 public admission 来 suppress 第二次 provider call；现有
`RuntimeTaskTypedContractServiceTest` 的 `provider.times(2)` 是必须保留的兼容基线，不是
待清理行为。

receipt enabled 时，public request receipt、owner termination operation/intent、exact
client-request/Task binding 和 provider-effect outbox 必须在同一 Navigator transaction
提交；只有 commit 成功后 effect handler 才能调用 Worker。任一写入或 commit 失败时，
transaction 整体回滚、provider invocation count 必须为零，且不能把“receipt 从未创建”
记为已接受 operation 的 cleanup `NOT_APPLICABLE`。收到上述 definitive `REJECTED` 后，
用户可在 readiness 恢复后显式发起新 command；SDK/server 不做自动 retry。若 rejection
response 丢失，caller 仍按 unavailable/fail-closed 处理，不能推断 provider 已执行。
`POST /runtime/task-terminate` 继续使用现有 HTTP/RX success envelope
`RX.ok(RuntimeTaskClosureDTO)`，在 DTO 内表达该 business rejection；不新增 raw HTTP
error shape，也不得让 persistence exception 逃逸成 500。

首次 `codex-biz-worker` ENFORCED canary enrollment 必须验证
`terminationRequestReceiptEnabled=true`；配置关闭时以 stable readiness reason
`TERMINATION_REQUEST_RECEIPT_REQUIRED_FOR_CANARY` 拒绝 enrollment，而不是改变已发布
SDK contract。若 live ENFORCED aggregate 期间配置被关闭：

- 已接受的 termination operation 仍由 owner 完成内部 terminal/cleanup，public
  reconciliation 立即按 disabled matrix 返回 `AMBIGUOUS`；
- 尚未接受的新 termination command 在 receipt admission 前返回 typed
  `REJECTED + TERMINATION_REQUEST_RECEIPT_REQUIRED_FOR_ENFORCED`，provider invocation
  count 为零；每次请求独立执行同一 command gate，不声称 receipt idempotency；
- aggregate 进入 `availability=CONFIGURATION_FROZEN`，禁止其他 Worker-dependent
  mutation，直到
  receipt readiness 恢复；不得回退 legacy one-shot effect、换 client request ID 或
  自动重发 termination。

### Session Lifecycle Snapshot

| Dimension | Initial Values | Semantics |
|---|---|---|
| `canonicalPhase` | `OPEN`, `CLOSED` | 仅显式 Session facts 和 Task canonical projection 可改变 |
| `availability` | 使用上述 frozen enum | 是否允许 Worker-dependent 写操作 |
| `conflictState` | 使用上述 frozen enum | Session 聚合其 foreground Task 与 Worker authority conflict；不覆盖 canonical phase |
| `foregroundTaskId` | null or exact Task ID | 用户可见主交互通道当前 Task；不是 latest-by-time |
| `foregroundLaneState` | `FREE`, `OCCUPIED`, `FINALIZING` | 是否允许同一 Session 创建下一个 foreground Task |
| `activity` | `READY_FOR_USER`, `TASK_ACTIVE`, `USER_ACTION_REQUIRED`, `TASK_FINALIZING` | 从 foreground Task canonical projection 聚合 |
| `childTaskSummary` | open/frozen/ambiguous counts | child session/subtask 摘要，不覆盖 foreground truth |
| `visibility` | `VISIBLE`, `ARCHIVED`, `DELETED` | UI/retention metadata；不能作为 Task terminal evidence |
| `transferState` | `NONE`, `PENDING`, `COMPLETED`, `FAILED` | 预留；当前禁止自动 transfer |

Session owner 只消费 Task owner 发布的 canonical projection change，不遍历
provider/Worker 原始状态。一个旧 Task frozen 不反向冻结已恢复 Worker，但其所属 Session
在该 Task 未对账前仍显示 frozen；新 Session/Task 可使用已恢复的 Worker gate。

这组维度是 reducer 的内部模型；在 shadow rollout 期间可以与现有 status 列并存并做
parity audit，不要求一次性修改全部 SDK/UI 状态枚举。

### Session Aggregation and Foreground Lane

当前代码使用多组分散的 active-status list，部分包含 `CANCEL_REQUESTED`、部分不包含；
部分 Session projection 又按 latest Task timestamp 推导 interaction state。该模式无法
可靠表达 frozen/ambiguous/finalizing Task，也可能在 termination 尚未收敛时错误放行
第二个 Task。

用户可见 Session 的 foreground lane 确认采用 single-flight：

1. 一个 Session 同时最多绑定一个 foreground Task，其占用资格由
   `foregroundTaskId` 和 Task owner projection 决定，不由 status 字符串列表决定。
2. `canonicalPhase=OPEN` 的 Task 始终占用 lane，包括 termination requested/rejected
   后仍执行、offline frozen、recovering 和 ambiguous。
3. foreground Task 已 canonical terminal 但 cleanup 未完成时，lane 为 `FINALIZING`；
   required token revoke、compatibility projection 和 receipt checkpoint 完成后才变为
   `FREE`；derived active-registration projection 必须同时为 false。
4. 新消息/Task command 仅在 Session `OPEN + READY + lane FREE` 时可接受；否则在接受前
   拒绝，前端可以保存 local draft。
5. Worker 可恢复接收其他 Session 的新 Task，不代表旧 frozen Session 的 foreground
   lane 自动释放。
6. Task terminal 只使 Session 回到 `READY_FOR_USER`，不自动 `CLOSED`。
7. 普通 Session close 在 lane occupied/finalizing 时拒绝；MVP-A 不提供管理员
   logical-close/cascade 旁路，现有 provider `force` 也不能释放 lane。
8. 并行委派、子 Agent 或跨项目工作通过 child session/subtask aggregate 表达；其
   counts 可以投影到 parent，但不能覆盖 parent foreground Task 或自动关闭 parent。
9. `latestTaskId` 仅是排序/展示指针；`DELETED/ARCHIVED` 仅是 visibility metadata，二者
   都不能成为 lifecycle reducer input。

Codex app-server Worker 当前已经通过 thread reservation 和 active root turn guard
拒绝同一 thread 的并发 Task；Codex SDK Worker 尚没有同等级的 per-thread reservation。
因此 Session owner 的 single-flight 是 provider-independent 第一约束，Worker/provider
guard 只是 defense-in-depth，不能替代 Navigator 的原子 lane ownership。

建议聚合规则：

```text
Session CLOSED
  -> activity unaffected for audit, all ordinary commands rejected

Session OPEN + foreground lane FREE + bound Worker READY
  -> READY_FOR_USER

foreground Task OPEN + pending interaction
  -> USER_ACTION_REQUIRED

foreground Task OPEN + no pending interaction
  -> TASK_ACTIVE

foreground Task TERMINAL + cleanup not complete
  -> TASK_FINALIZING

foreground Task TERMINAL + cleanup complete
  -> release lane
  -> READY_FOR_USER
```

## Standard Fact Envelope

所有 lifecycle facts 至少需要以下公共字段；不得包含 credential、Authorization、
prompt、模型回复或业务 payload。

| Field | Semantics |
|---|---|
| `factId` | 全局唯一事实 ID |
| `factType` | versioned stable fact code |
| `schemaVersion` | fact payload schema |
| `aggregateType/aggregateId` | Worker、Session、Task 或 TerminationOperation |
| `sessionId/taskId/operationId` | 存在时使用 exact correlation |
| `physicalWorkerId/providerTaskId` | 存在时使用 durable identity |
| `ownershipMode/dispatchId/safeBindingDigestVersion/safeBindingDigest` | Worker command-derived fact 必须回带 initial durable dispatch binding；mode/digest exact match 后才可摄取 |
| `sourceType/sourceId/sourceEpoch` | authority 与 Worker instance/restart epoch |
| `sourceStateGeneration` | Worker durable lifecycle store generation；本机 state 丢失/重建时变化 |
| `sourceSequence` | 同一 source/aggregate 内的单调序号 |
| `idempotencyKey` | inbox/outbox 去重键 |
| `causationId/correlationId` | command、effect 与 fact 因果关系 |
| `observedAt/recordedAt` | source observation 与 Navigator persistence time |
| `validUntil` | observation freshness；durable evidence/decision 可为空 |
| `supersedesFactId` | 显式纠正旧 observation，不原地篡改 |
| `safeReasonCode` | fixed/sanitized code |

Worker JSON wire 的 `ownership_mode/safe_binding_digest_version/safe_binding_digest`
规范化为 fact envelope 的
`ownershipMode/safeBindingDigestVersion/safeBindingDigest`；这是固定 casing mapping，
不是两个不同概念。

跨 source 不依赖 wall-clock timestamp 建立全序；使用 aggregate version、source
sequence、causation 和明确 precedence。

## Fact Classes

### MVP-A Transitional Physical Worker Identity

当前 Navigator 在注册 Physical Worker 时生成 `workerId`，Codex SDK Worker 已通过
`CODEX_NAVIGATOR_WORKER_ID` 读取该值并将其用于签名 termination target binding。MVP-A
复用这一条已存在的 provisioning 链，不在 Worker 内生成新的远端资源 ID：

1. 首个 canary 只接受已配置非空 `CODEX_NAVIGATOR_WORKER_ID` 的 Codex SDK Worker；该值
   就是本阶段 wire contract 的 `physicalWorkerId`。
2. Worker `/health` 和所有 lifecycle v1 response/event 必须 exact echo 该值。Navigator
   同时验证数据库 Worker resource、configured endpoint 与现有 credential binding；
   任一缺失或不匹配都使 lifecycle v1 `ready=false`，禁止 `ENFORCED` enrollment。
3. 新增独立、绝对路径配置 `CODEX_LIFECYCLE_STORE_DIR`。该目录必须位于 exact Worker
   独占的持久卷；未配置、不可写、无法 fsync 或同时被冲突实例使用时，lifecycle v1
   fail closed。它不得与 one-use termination security receipt 混作同一 ledger。
4. lifecycle store 首次初始化时原子生成并 fsync collision-resistant
   `stateGeneration`；普通 Worker 重启必须复用。store 被删除、重建或错误挂载时产生新
   generation，Navigator 将旧 Task 标为 coverage gap/ambiguous，绝不推断 terminal。
5. Worker 每次进程启动、在暴露 lifecycle ready 前生成新的 `instanceEpoch`；它在该
   进程存活期间不变，只用于 live observation fencing，不替代 durable cursor。
6. durable identity record 至少保存
   `schemaVersion + physicalWorkerId + stateGeneration + createdAt`。配置 ID 与已有
   record 不一致时必须 fail closed，不能覆盖 record、生成第三个 ID 或沿用旧 cursor。
7. Worker 首启自动生成 Physical Worker ID、claim/reclaim 和 host-level 多 role identity
   共享属于后续 provisioning work；不得在 MVP-A 中顺带实现。

MVP-A 标准 facts：

| Fact Type | Authority | Meaning |
|---|---|---|
| `WORKER_TRANSITIONAL_IDENTITY_BOUND` | Navigator identity verifier | configured Worker ID、endpoint 与 Worker echo exact match |
| `WORKER_IDENTITY_MISSING_OBSERVED` | Worker bootstrap / Navigator verifier | configured ID 或 response identity 缺失，lifecycle v1 unready |
| `WORKER_IDENTITY_CONFLICT_OBSERVED` | Worker bootstrap / Navigator verifier | env、durable record、endpoint binding 或 response identity 冲突 |
| `WORKER_STATE_GENERATION_INITIALIZED` | durable Worker lifecycle store | exact physical Worker lifecycle store generation 已原子持久化 |
| `WORKER_INSTANCE_STARTED` | verified Worker instance | 相同 physical identity/state generation 下产生新 instance epoch |

identity record 不保存 credential、claim token、Authorization 或长期 secret。

### Worker Connectivity and Readiness Observations

| Fact Type | Authority | Durability | Permitted Inference |
|---|---|---|---|
| `WORKER_TRANSPORT_CONNECTED_OBSERVED` | Navigator connection observer | staleable | transport 可达；不能证明 runtime ready |
| `WORKER_HEARTBEAT_OBSERVED` | verified Worker instance | staleable | exact epoch 在观察时存活 |
| `WORKER_DISCONNECTED_OBSERVED` | Navigator connection observer | staleable | 进入/保持 offline freeze；不能改变 Task terminal status |
| `WORKER_HEARTBEAT_STALE` | lifecycle timer/policy | staleable | connectivity unknown/offline；不能推断 Worker process/task 已退出 |
| `WORKER_IDENTITY_VERIFIED` | Navigator identity verifier | epoch-bound | connected endpoint 与 durable physical Worker/instance 匹配 |
| `WORKER_STATE_GENERATION_VERIFIED` | Navigator identity verifier | durable-generation-bound | Worker lifecycle store 与上次已知 generation 一致 |
| `WORKER_STATE_GENERATION_CHANGED` | Navigator identity verifier | durable decision input | durable state 已重建/丢失；旧 Task 进入 reconciliation/ambiguous，不 terminal |
| `WORKER_LIFECYCLE_COVERAGE_GAP_CONFIRMED` | Navigator cursor verifier | durable decision input | protected fact interval 无法恢复；exact affected aggregate 进入 Worker state-loss conflict |
| `WORKER_STATE_BASELINE_REESTABLISHED` | Worker lifecycle owner after explicit baseline + affected-scope reconciliation | durable conflict resolution | 只 supersede exact `WORKER_STATE_LOSS` candidate；不清除 evidence/writer conflict |
| `WORKER_CAPABILITY_READY_OBSERVED` | Worker readiness adapter | staleable | 指定 capability 在观察时 ready |
| `WORKER_RECONNECTED_OBSERVED` | Navigator connection observer | staleable | 进入 recovering；不能立即解冻 |
| `WORKER_TASK_INVENTORY_OBSERVED` | verified Worker instance | epoch-bound evidence | exact epoch 的结构化 Task snapshot；missing 不自动等于 terminal |
| `WORKER_LIFECYCLE_CURSOR_ACKED` | Worker lifecycle owner | monotonic checkpoint | Navigator 已持久化 through-sequence；允许 Worker 按 retention policy 回收旧 facts |
| `WORKER_LIFECYCLE_STORAGE_PRESSURE_OBSERVED` | Worker storage monitor | staleable | lifecycle store 接近容量；只触发 compaction/alert，不改变 availability；不得丢失 protected facts |
| `WORKER_LIFECYCLE_STORAGE_FROZEN` | Worker lifecycle owner | operational decision | 无法安全持久新 fact，拒绝新 Task/effect |
| `WORKER_LIFECYCLE_STORAGE_RECOVERED` | Worker lifecycle owner | operational decision | compaction/扩容后重新通过 durability probe，可进入 reconciliation |
| `WORKER_RECONCILIATION_COMPLETED` | Worker lifecycle owner | decision/checkpoint | identity、capability 和受影响 Task 对账已完成，可重新计算 gate |

required configuration 使用 normalized、content-free facts，不允许 reducer 自行读取环境：

| Fact Type | Authority | Meaning |
|---|---|---|
| `LIFECYCLE_REQUIRED_CONFIGURATION_UNAVAILABLE` | configuration readiness adapter + aggregate owner | exact scope/configuration kind 当前不满足；不保存 credential/value |
| `LIFECYCLE_REQUIRED_CONFIGURATION_RESTORED` | configuration readiness adapter + aggregate owner | exact unavailable fact 已由新 readiness observation supersede |

这些 facts 只投影到前述唯一 `availability/conflictState`。不存在第二套持久 Worker
operational state enum：

| Observation/decision | Canonical projection |
|---|---|
| transport connected/heartbeat only | 不改变 snapshot；fresh observation 只是 READY 的必要非充分条件 |
| probe failure before freshness expiry | 不改变 snapshot；Sentinel 只更新本 lease 内的 ephemeral attempt/backoff counter |
| disconnect or freshness expiry fact | `availability=OFFLINE_FROZEN` |
| reconnect or storage-recovered fact | `availability=RECOVERING`，直到 exact reconciliation complete |
| storage pressure observation below freeze threshold | 不改变 snapshot；只触发 compaction/alert effect |
| durable storage frozen decision | `availability=STORAGE_FROZEN` |
| exact reconciliation complete and no higher-precedence blocker | `availability=READY` |

`ONLINE`、`SUSPECTED`、`RECOVERING_RECONCILIATION`、
`STORAGE_PRESSURE_DEGRADED`、`STORAGE_PRESSURE_FROZEN` 不再是 target schema、snapshot、
reducer 或 API 值；它们若出现在当前 legacy adapter/log，只能作为 source text，经上表
规范化后立即丢弃。Sentinel 的 attempt count、next probe time、backoff 和 circuit
breaker 是可重建的 effect-participant lease metadata，不是 lifecycle fact，也不能被
compatibility projector 读取。

### Connectivity Sentinel / Supervisor Boundary

这里的“由 lifecycle owner 管理重连”是指 owner 管理重连的状态、策略和结果，不是让
纯 reducer 自己持有 socket、启动线程、sleep 或调用 Worker：

```text
WorkerLifecycleOwner (pure decision owner)
  state/gate + emits PROBE/RECONNECT/FETCH_INVENTORY effects
                         |
                         v
WorkerConnectivitySentinel (effect participant, one per Physical Worker)
  health/identity probe + backoff/jitter + circuit breaker
  + bounded Task stream resume
                         |
                         v
TaskStreamWatcher (one logical watcher per affected Task)
  resume from last durable cursor + report normalized facts
```

最小 canonical fact/projection flow 为：

```text
fresh verified observations + reconciliation complete
  -> availability=READY
freshness expired or disconnected fact
  -> availability=OFFLINE_FROZEN
reconnected fact
  -> availability=RECOVERING
exact reconciliation complete + no higher-precedence blocker
  -> availability=READY
```

- 单个 Task SSE 断流而 Worker health/identity 仍可信时，只产生
  `TASK_STREAM_DISCONNECTED_OBSERVED` 并恢复该 Task 观察通道；不能立即把整个 Worker
  置为 offline。
- Worker probe 连续失败本身只改变 Sentinel ephemeral backoff；只有
  `WORKER_DISCONNECTED_OBSERVED` 或 freshness-expiry fact 才让 owner 投影
  `availability=OFFLINE_FROZEN`。具体阈值是 versioned policy input，不是新的 state
  value或 terminal rule。
- 一个 Physical Worker 只运行一个退避回路。Worker 已确认不可达时暂停各 Task 独立
  撞击 endpoint；probe 恢复后再以有界并发恢复受影响 stream，避免 Task 数量放大重连
  风暴。
- transport 再次可达后，Sentinel 依次执行 identity/epoch 校验、capability probe、
  inventory/cursor 获取和 stream resume。它只报告 facts/effect results；owner reduce
  后才重新开放 Worker gate，旧 Task 是否解冻仍逐项决定。
- 重连默认只恢复读取和观察，不创建或重构任何有业务副作用的 command。唯一允许的
  transport continuation 是：初始 Task dispatch 已有 owner outbox、复用同一
  `dispatch_id`，且 Worker durable effect phase 明确仍为 `PREPARED`；它只能原子推进
  `EFFECT_STARTED` 后执行一次。phase 为 `EFFECT_STARTED/UNKNOWN` 时只对账。同一
  termination operation 只能依靠原 operation identity 查询/回放 Worker facts，不能
  再次调用 provider termination。
- 多 Navigator 实例部署时，Sentinel 必须有按 `physicalWorkerId` 的 lease/fencing，
  保证同一时刻只有一个有效 supervisor 推进游标和 stream ownership。第一阶段可使用
  小型 DB lease，不建设通用集群 Actor 平台。

MVP-A 只实现 Codex SDK adapter。当前 Codex `ack_seq`/JSONL 和 Claude
`lastAckedSeq`/JSONL 可以复用 transport、UI replay 与 compatibility plumbing，但它们
没有统一 identity triple、coverage/ACK contract，普通写失败也可能被降级为 warning，
因此不能被声明为 lifecycle v1 durable authority。

#### MVP-B: Convergence of the Three Claude Loops (Deferred)

以下是已确认但非 MVP-A implementation/acceptance 的后续方向。MVP-B 必须先让 Python
Worker 实现同一 lifecycle v1 contract，再把三处逻辑归一为一个入口、一个 Worker
lane、三类 adapter：

| Current Component | Retained Capability | Removed Responsibility | Target Role |
|---|---|---|---|
| `WorkerHealthChecker` | health/identity/capability probe | 独立 `@Scheduled`、直接写 legacy `ONLINE/OFFLINE` flag | stateless `WorkerProbeAdapter`；target 只产 normalized facts |
| `WorkerStreamRelay` | SSE subscribe、durable cursor、event replay、subscription dedup | 自行无限调度重连、直接改 Task/Session/UI lifecycle | `TaskStreamAdapter` |
| `TaskStateReconciler` | process/task inventory、status/sequence gap 采集 | 独立 `@Scheduled`、自行判断状态和触发重连 | `WorkerInventoryAdapter` |
| new Worker Sentinel | keyed scheduling、backoff/jitter、bounded concurrency、startup recovery | 不解析 provider payload、不决定 Task terminal | `WorkerConnectivitySupervisor` |
| lifecycle owners | reduce normalized facts、决定 gate/state/effects | 不持有 socket/timer、不直接调用 Worker | canonical decision owners |

统一控制流：

```text
scheduled/startup/stream-loss trigger
  -> enqueue one reconcile intent by physicalWorkerId
  -> WorkerConnectivitySupervisor serializes the Worker lane
  -> execute probe / inventory / stream effects through adapters
  -> persist normalized facts
  -> Worker/Task lifecycle owners reduce facts and emit next effects
```

MVP-B 归一后必须满足：

1. 同一 `physicalWorkerId` 同时最多一个有效 probe/backoff/recovery coordinator。
2. health、inventory、stream 的 observation 使用同一 Worker identity/epoch context，
   不能把不同实例的结果拼成一次恢复。
3. adapter 可以单独调用和测试，但不能绕过 owner 修改 canonical status 或 command
   gate。
4. startup scan、定时 sanity scan 和人工“立即检查”都只产生 reconcile intent，走同一
   lane；不能形成第四套恢复路径。
5. relay 的单次 subscribe/resume 失败只返回 effect result；下一次何时执行由 Sentinel
   的统一退避策略决定。
6. inventory 中的进程缺失、Task 404 或 sequence gap 都只是 normalized evidence；
   terminal、ambiguous、freeze 和 reconnect decision 由对应 owner reducer 产生。
7. orphan-process 检测可以继续作为独立诊断 projection，但不再反向成为 Task canonical
   state authority。

### Task Dispatch and Execution Facts

| Fact Type | Authority | Meaning |
|---|---|---|
| `TASK_COMMAND_ACCEPTED` | Task lifecycle owner | Navigator 接受 logical Task command |
| `TASK_DISPATCH_RESERVED` | Task lifecycle owner | exact provider/Worker dispatch identity 已持久化 |
| `TASK_DISPATCHED` | provider adapter | dispatch attempt 已发生 |
| `TASK_ACCEPTED_BY_WORKER` | verified Worker | exact Worker 已接受 Task |
| `TASK_NEVER_ACCEPTED_CONFIRMED` | fenced reread of Worker v1 durable `REJECTED/PRE_EFFECT` dispatch disposition + Navigator exact dispatch/delivery correlation | exact Task 已由 Navigator 接受，但 Worker durable 证明从未接受/启动 provider effect；原 command response、store failure、timeout、404、response loss 或 inventory absence 不够 |
| `TASK_STREAM_DISCONNECTED_OBSERVED` | Navigator connection observer | exact Task 的观察通道中断；execution 变为 unknown，不改变 canonical status |
| `TASK_STREAM_RESUMED_OBSERVED` | verified stream observer | 已从 last durable cursor 恢复观察；本身不是 terminal 或 Task 解冻证据 |
| `TASK_EXECUTION_STARTED_OBSERVED` | verified Worker/provider | exact provider execution 已开始 |
| `TASK_RUNNING_OBSERVED` | verified Worker/provider | observation 时执行中；会 stale |
| `TASK_AWAITING_INPUT_OBSERVED` | verified Worker/provider | exact request/version 等待输入 |
| `TASK_AWAITING_PERMISSION_OBSERVED` | verified Worker/provider | exact request/version 等待 permission decision |
| `TASK_INTERACTION_RESPONSE_APPLIED` | verified Worker/provider | exact response 已应用到 exact pending request |
| `TASK_INTERACTION_RESPONSE_REJECTED` | verified Worker/provider | exact response 被 definitive reject/stale |
| `TASK_INTERACTION_REQUEST_INVALIDATED` | verified Worker/provider | pending request 已失效或被后续 state supersede |
| `TASK_INTERACTION_INVENTORY_OBSERVED` | verified Worker instance | reconnect 时 exact current pending interaction snapshot |
| `TASK_PROVIDER_TERMINAL_OBSERVED` | verified Worker/provider | provider 发出结构化 terminal result |
| `TASK_PROCESS_EXIT_VERIFIED` | process identity verifier | exact PID/start identity 已确认退出 |
| `TASK_EXECUTION_EVIDENCE_CONFLICT` | Task lifecycle owner | 多个可信 evidence 互相冲突；该 Task 设置 `availability=AUTHORITY_QUARANTINED + conflictState=EVIDENCE_CONFLICT` |
| `TASK_EXECUTION_EVIDENCE_CONFLICT_RESOLVED` | Task lifecycle owner from exact superseding evidence | 只清除 exact Task 的 evidence-conflict candidate并 full recompute；不能选择“较新文本” |

Worker 文本、普通日志、SSE 断开和 generic error 均不是上述 terminal evidence。

### Termination Facts

| Fact Type | Authority | Meaning |
|---|---|---|
| `TERMINATION_INTENT_ACCEPTED` | Termination owner | clientRequestId 已幂等绑定 exact Task/operation |
| `TERMINATION_DISPATCH_RESERVED` | Termination owner | immutable operation capability/outbox 已持久化 |
| `TERMINATION_DISPATCHED` | provider adapter | exact operation 的发送 attempt |
| `TERMINATION_ACKNOWLEDGED` | verified Worker | 只证明请求被接受，不是 terminal |
| `TERMINATION_REJECTED` | verified Worker/provider or Navigator preflight | definitive rejection，不制造 Task terminal |
| `TERMINATION_PROVIDER_TERMINAL_OBSERVED` | verified Worker/provider | exact operation 关联的 provider terminal evidence |
| `TERMINATION_PROCESS_EXIT_VERIFIED` | process identity verifier | exact operation/task process 已退出 |
| `TERMINATION_EVIDENCE_DEADLINE_ELAPSED` | lifecycle timer | caller disposition 可变为 `AMBIGUOUS`；Task 不 terminal |
| `TERMINATION_EVIDENCE_CONFLICT` | Termination owner | operation/task/Worker/evidence 不一致 |
| `TERMINATION_EVIDENCE_CONFLICT_RESOLVED` | Termination owner from exact superseding evidence | 只清除 exact operation/Task evidence-conflict candidate并 full recompute |

### Canonical Task and Cleanup Decision Facts

| Fact Type | Authority | Monotonicity |
|---|---|---|
| `TASK_CANONICAL_TERMINAL_COMMITTED` | Task lifecycle owner | irreversible |
| `TASK_TERMINAL_TOMBSTONE_RECORDED` | Task owner terminal transaction；capability participant 同步参与 applicable domain | irreversible/fail-closed；必须与 canonical terminal 同事务 |
| `TASK_TERMINAL_CLEANUP_PLAN_FROZEN` | Task lifecycle owner | terminal commit 时按 participant 固定 `REQUIRED/NOT_APPLICABLE` |
| `TASK_CAPABILITY_SUSPENDED` | authorization policy participant | reversible operational freeze；不是 revoke |
| `TASK_CAPABILITY_RESUMED` | authorization policy participant | 仅 Worker recovery reconciliation 后允许 |
| `TASK_TOKEN_REVOKED` | token lifecycle participant | irreversible for exact token |
| `TASK_COMPATIBILITY_PROJECTION_UPDATED` | compatibility projector | legacy Task/Session status 已投影到 canonical terminal |
| `TASK_ACTIVE_REGISTRATION_CLOSED` | future real registration participant | 当前 Codex/Claude 无独立 registration resource，不得在 MVP-A 伪造该 fact |
| `TERMINATION_RECEIPT_UPDATED` | receipt participant | idempotent checkpoint |
| `TASK_CLEANUP_PARTICIPANT_NOT_APPLICABLE` | owning participant / frozen plan | 对未签发 token、不存在 registration、自然终态无 termination 或 legacy receipt disabled 等精确原因作不可变确认；不能掩盖 accepted ENFORCED termination 的 missing receipt |
| `TASK_TERMINAL_CLEANUP_COMPLETED` | Task lifecycle owner | 所有 `REQUIRED` checkpoint 齐备且所有其余 participant 明确 `NOT_APPLICABLE` 后产生 |

terminal transaction 按当时可审计资源事实冻结以下 cleanup plan；后续 handler 不得自行
改变 applicability：

| Participant | `REQUIRED` | `NOT_APPLICABLE` |
|---|---|---|
| `TERMINAL_TOMBSTONE` | 所有 `ENFORCED` Task；Task 类型存在 task-scoped capability domain 时必须同步写其 authorization-authoritative tombstone | 不允许 N/A |
| `PHYSICAL_TOKEN_REVOKE` | exact Task 已签发 active/revocable token row | 无 token row，reason=`TOKEN_NOT_ISSUED` |
| `COMPATIBILITY_TASK_PROJECTION` | 所有 `ENFORCED` Task | 不允许 N/A；旧 status/API 必须得到 canonical projection |
| `TERMINATION_COMPAT_RECEIPT` | 任一 exact ENFORCED termination 已被接受时必须 `REQUIRED`，且 admission transaction 已创建 exact public receipt | 自然终态无 termination，或未进入 ENFORCED termination admission 的 legacy receipt-disabled one-shot；分别记录 `NO_TERMINATION_OPERATION` / `LEGACY_RECEIPT_FEATURE_DISABLED` |
| `ACTIVE_REGISTRATION_RESOURCE` | 未来存在独立 registration store 时才允许 | MVP-A 固定 `DERIVED_PROJECTION_NO_RESOURCE` |

`TERMINATION_INTENT_ACCEPTED` 与 exact public receipt 在 ENFORCED termination admission
的同一 transaction 中耐久绑定
`clientRequestId + taskId + operationId`。任一写入失败时该 intent 不成立、outbox 不存在、
provider 不调用；因此不存在“accepted ENFORCED operation 但 receipt 从未创建”可被标记
N/A 的合法状态。LEGACY/SHADOW receipt-disabled one-shot 不创建 owner intent，不由 owner
ledger dedupe；其公开 typed reconciliation 继续遵守
`AMBIGUOUS + TERMINATION_REQUEST_RECEIPT_DISABLED +
requestReconciliationAvailable=false`。二者不得互相冒充。

Typed `TERMINAL` 只从
`TASK_CANONICAL_TERMINAL_COMMITTED + TASK_TERMINAL_CLEANUP_COMPLETED` 投影得出。
任何已提交的 `TASK_CANONICAL_TERMINAL_COMMITTED` 必须在同一数据库事务中存在
authorization-authoritative `TASK_TERMINAL_TOMBSTONE_RECORDED`；墓碑持久化失败时 terminal
transaction 必须回滚，已持久 Worker evidence 留在 inbox 等待重算。物理 token row
revocation、compatibility projection 和 receipt update 才允许进入异步 outbox。

所有 `ENFORCED` Task 都必须在 owner terminal transaction 内写 content-free terminal
tombstone。对支持 task-scoped capability 的 Task，`business-agent-module` participant
还必须通过 SPI 在同一 transaction 同步写 authorization-authoritative tombstone；不得
使用 `AFTER_COMMIT`、`REQUIRES_NEW` 或吞掉 persistence exception。对不支持该
capability domain 的 Task，generic owner tombstone 仍是 REQUIRED，cleanup plan 只在
capability-domain metadata 上记录 `CAPABILITY_DOMAIN_UNSUPPORTED`，不能把 terminal
fence 本身标为 N/A。

当前 `RuntimeStateAuditService.activeTaskRegistrationPresent` 是由非终态 status 推导的
布尔 projection，而不是待删除的注册行。MVP-A 对其保证是：canonical terminal 提交且
`COMPATIBILITY_TASK_PROJECTION` checkpoint 完成后，该 projection 必须为 `false`；不得
创建虚构的 registration delete effect 或 checkpoint 来满足 gate。

### Session Facts

| Fact Type | Authority | Meaning |
|---|---|---|
| `SESSION_CREATED` | Session owner | Session durable identity 已建立 |
| `SESSION_TASK_ATTACHED` | Session owner | exact Task 归属 Session |
| `SESSION_TASK_CANONICAL_STATE_CHANGED` | Task-to-Session projection | Session 只消费 Task canonical projection |
| `SESSION_USER_INPUT_REQUIRED` | Session owner from normalized Task fact | 交互等待，不直接消费 provider payload |
| `SESSION_OPERATIONAL_FREEZE_ENTERED` | Session owner | 依赖 Worker offline，写操作受 gate 控制 |
| `SESSION_OPERATIONAL_FREEZE_EXITED` | Session owner | Worker reconciliation 后重新计算并解除 freeze |
| `SESSION_CLOSE_REQUESTED` | authorized user/admin | close intent，不等于已关闭 |
| `SESSION_CLOSED` | Session owner | canonical Session terminal |

Session transfer/rebind facts 延后到独立设计；不得在 Worker 断连时自动切换
`modelConfigId`、provider 或 physical Worker。

### Timer Facts and Deferred Administrative Boundary

| Fact Type | Authority | Meaning |
|---|---|---|
| `OBSERVATION_FRESHNESS_EXPIRED` | lifecycle timer | observation 变为 unknown |
| `WORKER_RECOVERY_WINDOW_ELAPSED` | lifecycle timer | 告警/人工处置入口；不等于永久失效 |
| `CLEANUP_DEADLINE_ELAPSED` | lifecycle timer | cleanup disposition 变为 AMBIGUOUS；继续本地重试 |

Writer exclusivity 使用独立的 control-plane facts，不伪装为 Worker connectivity：

| Fact Type | Authority | Meaning |
|---|---|---|
| `WRITER_EXCLUSIVITY_PROOF_ACQUIRED` | target cutover coordinator + DB lease | exact generation/inventory digest 已通过完整检查 |
| `WRITER_EXCLUSIVITY_PROOF_RENEWED` | proof observer | controller desired state 与 process inventory 在 DB time 的本轮仍一致 |
| `WRITER_EXCLUSIVITY_REFERENCE_ACQUIRED` | Worker/Session/Task owner transaction | exact ENFORCED aggregate 已绑定 proof；携带 aggregate type/id |
| `WRITER_EXCLUSIVITY_REFERENCE_RELEASED` | owning aggregate transaction | exact aggregate 满足冻结的 release predicate；不能由 count-only cleanup 猜测 |
| `WRITER_EXCLUSIVITY_PROOF_LOST` | proof observer/DB lease expiry | proof 过期或 drift；立即禁止新 enrollment/unsafe effect |
| `WORKER_WRITER_AUTHORITY_QUARANTINED` / `SESSION_WRITER_AUTHORITY_QUARANTINED` / `TASK_WRITER_AUTHORITY_QUARANTINED` | corresponding aggregate owner | exact live ENFORCED aggregate 引用的 proof 已失效 |
| `WRITER_EXCLUSIVITY_PROOF_RESTORED` | target cutover coordinator + conflict scan | 相同 target inventory 已重新验证；只允许 owner 重放 quarantine facts |

`WORKER_PERMANENT_LOSS_CONFIRMED`、`TASK_ADMIN_FORCE_CLOSE_CONFIRMED` 和
`SESSION_TRANSFER_AUTHORIZED` 不属于 MVP-A fact vocabulary，也不能作为 reducer input。
未来引入时必须另行冻结 principal/role、tenant scope、decisionId、审计与 late-evidence
规则。现有 `/tasks/{id}/cancel?force=...` 仍是 task-owner provider cancellation，
只允许产生 termination facts。

## Fact-to-Projection Reducer Rules

owner 对 normalized facts 使用以下第一版确定性规则；“保持”表示该 fact 无权改变对应
维度：

| Input Fact | Required Projection Change | Explicitly Forbidden |
|---|---|---|
| `TASK_COMMAND_ACCEPTED` | `canonicalPhase=OPEN` | 推断 Worker 已接受或执行已开始 |
| `TASK_DISPATCH_RESERVED` | `dispatchState=RESERVED` | 发起未持久化的旁路 dispatch |
| `TASK_DISPATCHED` | `dispatchState=DISPATCHED` | 推断 Worker ACK/RUNNING |
| `TASK_ACCEPTED_BY_WORKER` | `dispatchState=WORKER_ACCEPTED` | 单凭接受产生 RUNNING/TERMINAL |
| exact `TASK_NEVER_ACCEPTED_CONFIRMED` | `dispatchState=REJECTED`；形成受限 terminal candidate：`terminalOutcome=FAILED`、`terminalSource=WORKER_PRE_EFFECT_REJECTION`、`executionObservation=NOT_STARTED`；按正常 terminal transaction 写 tombstone/cleanup plan | 从 command response 本身、store unavailable、stale delivery attempt、timeout、response loss、HTTP 404、inventory absence、未鉴权 response 或不匹配 dispatch ID 推断 never accepted |
| `TASK_EXECUTION_STARTED_OBSERVED` / fresh `TASK_RUNNING_OBSERVED` | `executionObservation=RUNNING` | 改变 termination/terminal outcome |
| `TASK_AWAITING_INPUT_OBSERVED` / `TASK_AWAITING_PERMISSION_OBSERVED` | 设置 exact `interactionState/interactionRef`；Task 仍 `OPEN` | 把等待交互当失败或 Session close |
| `TASK_INTERACTION_RESPONSE_APPLIED` | 清除 exact pending interaction；按后续 Worker observation 重算 activity | 只凭 Navigator 已发送就声称 applied |
| `TASK_INTERACTION_RESPONSE_REJECTED` / invalidated | 清除或 supersede exact request；返回 definitive rejection | 自动复制 response 到新 request |
| `TERMINATION_INTENT_ACCEPTED` | `terminationState=REQUEST_ACCEPTED`；兼容显示 `CANCEL_REQUESTED` | 推断 dispatch、ACK 或 terminal |
| `TERMINATION_DISPATCHED` | `terminationState=DISPATCHED` | 产生第二 operation/client request ID |
| `TERMINATION_ACKNOWLEDGED` | `terminationState=ACKNOWLEDGED` | 改变 canonical phase、outcome 或 cleanup |
| `TERMINATION_REJECTED` | `terminationState=REJECTED`；Task 仍 `OPEN`；compatibility status 回落到当前 execution/interaction | 把 rejection 伪装成 Task failure/terminal |
| `TERMINATION_EVIDENCE_DEADLINE_ELAPSED` | `terminationState=AMBIGUOUS`；Task 仍 `OPEN` | 超时自动 `FAILED/CANCELLED` 或换 request ID |
| exact Worker/provider structured terminal evidence | 形成 terminal candidate，携带 exact outcome/source/correlation | 只凭文本、日志、ACK 或断连形成 candidate |
| exact termination-bound final result or verified exit evidence | `terminationState=CONFIRMED`、`executionObservation=STOPPED`；满足 operation/task/process correlation 时形成 exact terminal candidate | 使用泛化 PID 消失、missing Task 或无 operation correlation 推断 `CONFIRMED` |
| accepted terminal candidate | 同事务写 authorization tombstone、`TASK_CANONICAL_TERMINAL_COMMITTED` 和 immutable cleanup applicability plan；提交后才产生 required async effect outbox | terminal 先提交、墓碑后补；或 participant 旁路改 canonical state |
| terminal cleanup checkpoint facts | 单项幂等推进 cleanup | checkpoint 缺失时返回 typed `TERMINAL` |
| 所有 required cleanup checkpoint 齐备且其余 participant 明确 not-applicable | `cleanupState=COMPLETED`，产生 `TASK_TERMINAL_CLEANUP_COMPLETED` | 无条件等待不存在的 token/registration/receipt，或缺失 required cleanup 时伪装完成 |
| `WORKER_DISCONNECTED_OBSERVED` / stale heartbeat | 增加 `OFFLINE_FROZEN` candidate，full precedence 后投影；fresh execution observation 过期为 `UNKNOWN` | 改变 canonical phase、outcome 或 termination ACK |
| `WORKER_RECONNECTED_OBSERVED` | 清除 offline candidate并增加 `RECOVERING` candidate，full precedence 后投影 | 立即恢复 Worker-dependent writes |
| exact identity/capability/inventory reconciliation complete | 清除 recovering candidate；按 Task 独立 full recompute，无其他 blocker 才 `availability=READY` | 一个旧 Task ambiguous 冻结整个 Worker |
| `WORKER_LIFECYCLE_STORAGE_FROZEN` | 若无 authority conflict，`availability=STORAGE_FROZEN + conflictState=NONE` | 继续普通 Worker/provider effect或创造 storage pressure 新 enum |
| `WORKER_LIFECYCLE_STORAGE_RECOVERED` | 清除 storage candidate；若 reconciliation 未完成则 `availability=RECOVERING` | 无对账直接 READY |
| `LIFECYCLE_REQUIRED_CONFIGURATION_UNAVAILABLE` | 若无更高 precedence blocker，`availability=CONFIGURATION_FROZEN + conflictState=NONE` | 把 config failure 写成 storage/offline/terminal |
| matching `LIFECYCLE_REQUIRED_CONFIGURATION_RESTORED` | 清除 exact config candidate并 full recompute | 清除其他仍 active blocker |
| `TASK_EXECUTION_EVIDENCE_CONFLICT` | exact Task `availability=AUTHORITY_QUARANTINED + conflictState=EVIDENCE_CONFLICT` | 泛化为 Worker-wide freeze或自动选择一个 evidence |
| `WORKER_STATE_GENERATION_CHANGED` / `WORKER_LIFECYCLE_COVERAGE_GAP_CONFIRMED` | exact affected scope `availability=AUTHORITY_QUARANTINED + conflictState=WORKER_STATE_LOSS` | missing evidence 推断 terminal |
| matching `TASK_EXECUTION_EVIDENCE_CONFLICT_RESOLVED` / `TERMINATION_EVIDENCE_CONFLICT_RESOLVED` | 只清除 exact evidence-conflict candidate并 full recompute | 顺带清除 state/writer conflict |
| `WORKER_STATE_BASELINE_REESTABLISHED` | 只清除 exact affected scope 的 state-loss candidate并 full recompute | 缺 explicit baseline/coverage decision 就恢复 READY |
| `WRITER_EXCLUSIVITY_PROOF_LOST` | 引用 proof 的 live ENFORCED Worker/Session/Task 均设置 `availability=AUTHORITY_QUARANTINED`、`conflictState=LEGACY_WRITER_EXCLUSIVITY_LOST`；只保留单调 local safety action | 仅停止新 enrollment却让既存 aggregate 继续普通 effect，或回退 legacy |
| `WRITER_EXCLUSIVITY_PROOF_RESTORED` + exact conflict scan | 只清除 `LEGACY_WRITER_EXCLUSIVITY_LOST` candidate并 full recompute；若 state/evidence conflict 仍 active则继续 `AUTHORITY_QUARANTINED` | 未检查 legacy drift 就自动清除 conflict，或顺带清除较低 precedence blocker |

全局 reducer invariants：

1. `canonicalPhase=TERMINAL` 单调不可逆；晚到 RUNNING observation 设置 exact Task
   `availability=AUTHORITY_QUARANTINED +
   conflictState=EVIDENCE_CONFLICT`，不 reopen。
2. `canonicalPhase=TERMINAL` 与 authorization tombstone 必须同事务；不存在“已 terminal
   但 capability 仍可用”的提交窗口。
3. `cleanupState` 只有在 canonical terminal 后才能从 `NOT_REQUIRED` 进入 `PENDING`，
   且 participant applicability 在 terminal transaction 内冻结。
4. 同一 termination `clientRequestId/operationId` 的重复 reconciliation 只读 facts 和
   projection，不产生第二次 provider termination。
5. compatibility status 不能反向成为 reducer input；旧表中的 `CANCEL_REQUESTED` 必须先
   通过 adapter 还原为可验证的 termination facts。
6. Worker 原始文本、日志、异常 message 和 UI display status 只能作为 diagnostics，
   不能进入 terminal decision input set。
7. `WORKER_PRE_EFFECT_REJECTION` 只在 snapshot 尚无
   `TASK_ACCEPTED_BY_WORKER/TASK_EXECUTION_STARTED_OBSERVED`，且 exact authenticated
   response 同时绑定 `dispatchId + navigatorTaskId + physicalWorkerId +
   stateGeneration + ownershipMode + safeBindingDigest` 时合法；否则设置 exact Task
   `availability=AUTHORITY_QUARANTINED +
   conflictState=EVIDENCE_CONFLICT`，不关闭 Task。
8. never-accepted `FAILED` 与 execution-derived terminal 使用完全相同的 terminal
   tombstone、cleanup、typed canonical gate 和 Session lane release；不得用删除 Task
   row或直接清空 `foregroundTaskId` 作为补偿。

### Termination Rejection and Retry

拒绝分属 command gate 和已接受 operation 两个边界：

#### Rejected Before Acceptance

- 例如 Worker 已处于 `availability=OFFLINE_FROZEN`、identity 未 claim、Task
  correlation 在 preflight 阶段明确不合法。
- Navigator 返回 stable command rejection，不产生 `TERMINATION_INTENT_ACCEPTED`、
  termination operation、dispatch outbox 或 server pending intent。
- 前端可以保存 local retry draft；恢复后用户重新点击属于新 command，使用新 client
  request ID。
- 这里允许新 ID 是因为旧 command 从未被接受，不是用新 ID 绕过一个已接受 operation。

#### Rejected After Acceptance

- `TERMINATION_INTENT_ACCEPTED` 已经把 client request ID 幂等绑定到 exact
  task/operation，之后 Worker/provider 才给出 definitive rejection。
- 原 operation 固定为 `REJECTED`；相同 client request ID 的任何 reconciliation 都只读
  并返回该结果，不 dispatch、不 replay。
- Task 保持 `canonicalPhase=OPEN`；compatibility status 从 `CANCEL_REQUESTED` 回落到
  当前可信 `AWAITING_INPUT/RUNNING/SUBMITTED`，UI 另行展示本次 termination receipt
  为 rejected。
- 新 operation 默认 blocked。只有 rejection policy 为
  `RETRYABLE_AFTER_CONTEXT_CHANGE`，且以下 relevant context digest 发生可信变化后，才
  能由用户显式创建：

```text
physicalWorkerId
workerInstanceEpoch
taskExecutionVersion
terminationCapabilityVersion
```

- identity/auth mismatch 必须先完成 provisioning/recovery；permanent rejection 只能走
  明确的配置修复或后续人工处置流程；`ALREADY_TERMINAL` 必须转入 terminal evidence
  reconciliation，不能作为普通 retry reason。
- context 变化只重新打开“用户可发起”资格，不自动 retry，也不复制旧 reason/payload。

### Input and Permission Response Policy

Worker 是当前 pending input/permission request 及 response 是否已应用的 authority。
Navigator 只保存 normalized projection、command audit 和 definitive Worker result，不在
Worker offline 时保存待执行 response。

#### Rejected Before Send

- command 必须携带 exact `taskId + interactionRequestId + interactionVersion`。
- Worker `OFFLINE_FROZEN/RECOVERING`、request 已过期或本地 projection 已明确不匹配时，
  Navigator 在发送前拒绝，不创建 server pending intent。
- 前端可以保留最小 local draft；Worker 恢复后必须先刷新 current pending interaction，
  exact request/version 仍存在时才允许用户重新提交。

#### Definitive Worker Result

- 只有 Worker 返回 `TASK_INTERACTION_RESPONSE_APPLIED` 后，Navigator/UI 才能显示
  response 已应用。
- Worker 返回 rejected/stale/invalidated 时，Navigator 返回 definitive rejection，
  清除旧 pending projection；用户只能针对 Worker 新公布的 request 重新操作。
- Navigator “HTTP 已发送”或 adapter 局部 ACK 不能代替 Worker applied result。

#### Disconnect After Send

- response 已发出但 definitive Worker result 未返回时，结果为
  `INTERACTION_RESPONSE_AMBIGUOUS`，不是 `REJECTED` 或 `APPLIED`。
- Navigator 不自动 resend，前端也不后台重发；local draft 可以暂留，但 UI 必须提示先
  等待重连刷新。
- Worker 重连后，使用 `TASK_INTERACTION_INVENTORY_OBSERVED` 重算：
  - exact request/version 仍 pending：允许用户重新确认并发送；
  - Worker 明确报告 response 已 applied：清除 draft，继续 Task；
  - request 已消失且没有 applied/invalidated evidence：保持 ambiguous，不猜测结果。
- interaction ambiguity 只影响该 Task/Session 的交互 gate，不冻结已恢复 Worker 的其他
  Task。

该策略不要求 Navigator 建立离线 response 队列；Worker 可以用 exact request/version
天然拒绝重复或过期 response。是否需要额外的短期 response receipt ledger，留到实现
风险评估时根据各 Worker 当前持久化能力决定。

## Offline Freeze Policy

Worker snapshot 从 `availability=READY|RECOVERING` 重算为
`availability=OFFLINE_FROZEN` 时：

- canonical Session/Task status 保持不变。
- execution observation 变为 `UNKNOWN`，disposition 为
  `FROZEN_WORKER_OFFLINE`。
- capability 使用通过可逆 operational gate 暂停；不把 disconnect 当作 token revoke。
- ordinary Worker-dependent mutation/effect 不执行。
- security TTL、credential expiry 和已记录 terminal tombstone 不暂停。

| Operation | Offline Policy |
|---|---|
| Session/Task/audit/receipt read | allow |
| typed read-only reconciliation | allow；返回 frozen/ambiguous facts |
| ingest late durable Worker evidence | allow + idempotent |
| cleanup for already canonical terminal | allow |
| new Task/message dispatch to Worker | reject；server 不排队，前端可保留本地重试项 |
| termination request | reject；不记录待执行 intent，不自动重试 |
| automatic provider termination retry | prohibit |
| new task-scoped capability issuance/use | suspend/freeze |
| provider resume/input/permission response | reject；不排队，恢复后刷新并由用户重做 |
| admin permanent-loss confirmation | not available in MVP-A |
| admin Task logical force close | not available in MVP-A；不得复用现有 task-owner provider force |
| automatic Worker/session/task transfer | prohibit until separately designed |

### Frontend Retry Semantics

- server 必须返回 stable rejection，例如 `WORKER_OFFLINE_FROZEN`，不能返回 accepted
  后静默丢弃。
- server 不创建 deferred command、dispatch outbox 或恢复后自动执行的 pending intent。
- 前端可以将原消息、termination reason 或 input draft 标为本地 retryable，并通过向上
  箭头恢复到编辑/确认区域。
- 用户恢复后点击发送/确认属于新的显式 command，使用新的 client request ID；不得由
  前端后台自动重发。
- permission/input retry 前必须重新读取当前 pending request；仅当 exact request
  identity/version 仍有效时才允许用户重新提交，否则提示原请求已失效。

### Frontend Local Retry Storage (Non-Normative Deferred Design)

本节只保存已讨论的后续 UX 方向，不属于当前 MVP scope、acceptance 或 Ultra implementation
contract。当前 normative 约束仅是 server 不排队、前端不自动重发、恢复后由用户显式
产生新 command。

- retry item 使用 `localStorage` 保存，只是客户端 draft，不进入 Navigator lifecycle
  fact、request receipt、pending command 或 server outbox。
- 建议使用 versioned namespace，并按当前 user、tenant、Session 隔离；item 至少包含
  local retry ID、action kind、Session/Task correlation、created/expires time 和恢复 UI
  所需的最小 draft。
- 不保存 token、credential、Authorization、Worker 原始 payload、provider response、
  permission secret 或不可安全持久化的临时能力。
- 清理不只依赖 interval，因为页面关闭时 timer 不运行；必须在应用启动、登录/用户
  切换、进入会话和周期任务中执行相同的 TTL/数量上限清理。
- logout、tenant/user 切换必须清理不再属于当前 scope 的 item；quota/storage failure
  只影响本地恢复能力，不能使 rejected command 变成 server accepted。
- 多 tab 可以使用 `storage` event 同步删除/更新，但任何 tab 都不得自动重发。
- 初始 retention/数量上限仍待前端产品确认；建议短期 TTL，并提供单项删除和清空入口。

## Reconnect Recalculation

Worker transport 恢复后先投影为 `availability=RECOVERING`：

1. 验证 physical Worker、instance/epoch 和 endpoint ownership。
2. 验证 required capability/readiness。
3. 拉取 exact active Task、termination operation 和 durable terminal evidence。
4. 对每个受影响 Task 执行 reducer；missing Task 只产生 unknown/ambiguous，除非存在
   `TASK_NEVER_ACCEPTED_CONFIRMED` 或 exact terminal/exit evidence。
5. 处理 reconnect 前后的重复、乱序和晚到 facts。
6. 只有 `WORKER_RECONCILIATION_COMPLETED` 后才能重新计算 capability/session gate。

解冻不是恢复旧 snapshot，而是使用最新事实重新计算新 projection。

解冻分为两层：

- Worker gate：完成 exact identity、required capability 和 inventory reconciliation 后，
  Worker 可恢复新 Task dispatch。
- Existing Task gate：每个旧 Task 独立重算；missing/unknown 使 operation 保持
  `AMBIGUOUS`，其 `availability` 按上述 active blockers 计算；exact evidence conflict
  则设置该 Task
  `availability=AUTHORITY_QUARANTINED +
  conflictState=EVIDENCE_CONFLICT`。其 Session gate 按 foreground Task聚合，但不反向
  冻结整个 Worker。

### Inventory, Epoch and ACK Protocol

MVP-A 冻结 additive internal wire contract
`NAVIGATOR_WORKER_LIFECYCLE_V1`。现有 `/health`、task status/subscribe、termination
endpoint 保持兼容；legacy caller 可以忽略新增字段，只有满足 v1 全部 capability 的
Codex SDK Worker 才能承载 `ENFORCED` aggregate。

Worker reconnect 区分三个 identity level：

| Identity | Lifetime | Purpose |
|---|---|---|
| `physicalWorkerId` | transitional configured Worker resource lifetime | exact echo `CODEX_NAVIGATOR_WORKER_ID` |
| `stateGeneration` | durable lifecycle store lifetime | 检测 Worker 本机 state 丢失、重建或错误挂载 |
| `instanceEpoch` | process lifetime | 区分每次启动及 stale live observation |

`sourceSequence` 在同一 `stateGeneration` 内持久单调递增，不因普通进程重启归零；
`instanceEpoch` 只用于 liveness/freshness。这样 Navigator 的 ACK cursor 可以跨正常重启
延续，同时能明确识别 state store 被清空。

#### Lifecycle Credential and Request Fencing

MVP-A 不新增 secret lane。Worker lifecycle v1 固定复用 Codex runtime endpoint 的现有
Bearer credential：

- Worker 端配置是非空 `CODEX_WORKER_TOKEN`；Navigator 端是 exact Worker/runtime
  binding 已加密保存并由 `CodexWorkerClient` 发送的对应 auth token。不得改用
  `CODEX_NAVIGATOR_WORKER_CREDENTIAL`、模型 API key 或 termination payload。
- `GET /health` 仍可匿名访问，但只返回 content-free readiness/identity metadata，不
  返回 credential、path、Task ID 或业务数据。
- `/api/v1/lifecycle/inventory`、`/api/v1/lifecycle/events`、
  `/api/v1/lifecycle/ack`、`/api/v1/lifecycle/dispatches/{dispatchId}` 以及任何带
  `lifecycle_context` 的 query/resume/termination command 必须经过 lifecycle 专用
  fail-closed auth guard。它不能继承当前通用 middleware 的
  “`CODEX_WORKER_TOKEN` 为空则放行”行为。
- Worker token 未配置时 `/health.lifecycle_contract.ready=false`，reason 必含
  `LIFECYCLE_AUTH_NOT_CONFIGURED`；lifecycle endpoint 返回 503
  `WORKER_LIFECYCLE_AUTH_UNAVAILABLE`。token 已配置但 header 缺失/格式错误返回 401
  `WORKER_LIFECYCLE_AUTH_REQUIRED`，值不匹配返回 403
  `WORKER_LIFECYCLE_AUTH_INVALID`。三类失败均发生在 cursor 读取、fact append 和
  provider effect 前。
- Navigator binding 没有 credential 时，Sentinel/owner readiness 直接记录
  `WORKER_LIFECYCLE_CREDENTIAL_BINDING_MISSING` 并禁止 SHADOW parity 与 ENFORCED
  enrollment；不得向无鉴权 endpoint 探测 lifecycle facts。
- credential 不得进入 fact、snapshot、outbox payload、HTTP error、日志或 evidence；
  lifecycle auth negative tests 只断言 stable code。

inventory、events 与 ACK 的 request fence 使用以下 required headers：

```text
X-Navigator-Expected-Physical-Worker-Id: {opaque configured id}
X-Navigator-Expected-State-Generation: {opaque generation}
```

- 任一 header 缺失/blank 返回 400
  `LIFECYCLE_EXPECTED_IDENTITY_REQUIRED`；actual physical ID 不同返回 409
  `LIFECYCLE_IDENTITY_MISMATCH`；actual generation 不同返回 409
  `LIFECYCLE_STATE_GENERATION_MISMATCH`。
- 所有成功 response 以 header 与 body/checkpoint exact echo actual
  `physicalWorkerId/stateGeneration/instanceEpoch`。Navigator 必须先验证 response
  identity，再消费 cursor、Task inventory 或 facts。
- ACK body 中的 physical ID/generation 必须与 expected headers 和 Worker actual
  record 三方一致；任一冲突不推进 watermark。
- query/resume/termination 使用 `lifecycle_context` 内的 expected fields，不重复使用
  上述 headers；adapter 必须对两类请求执行同一 exact-match policy。

鉴权通过后的 v1 rejection 使用固定 content-free envelope；不得混入通用 stack/error
message：

```text
{
  schema: NAVIGATOR_WORKER_LIFECYCLE_V1,
  code: STABLE_CODE,
  physical_worker_id?,
  state_generation?,
  instance_epoch?,
  navigator_task_id?,
  dispatch_id?,
  accepted?: false,
  provider_effect_started?: false
}
```

auth failure 只返回 `schema + code`；expected-identity/cursor/command-binding failure 可以
返回已验证 endpoint 的 actual identity 与相关 safe correlation，但绝不返回 facts、
Task payload 或 credential。HTTP status、stable code 和是否允许携带 safe correlation
都必须由 Node/Java contract tests 固定。

#### Version Negotiation and Health

`GET /health` 新增以下 content-free nested object：

```json
{
  "lifecycle_contract": {
    "schema": "NAVIGATOR_WORKER_LIFECYCLE_V1",
    "version": 1,
    "ready": true,
    "reason_codes": [],
    "physical_worker_id": "configured-worker-id",
    "state_generation": "opaque-generation-id",
    "instance_epoch": "opaque-process-epoch",
    "high_watermark": 42,
    "min_available_sequence": 1,
    "capabilities": [
      "AUTHENTICATED_LIFECYCLE_V1",
      "FENCED_INVENTORY_V1",
      "DURABLE_LIFECYCLE_FACTS_V1",
      "MONOTONIC_ACK_V1",
      "SHADOW_CONTEXT_V1",
      "EXACT_DISPATCH_DEDUPE_V1",
      "FENCED_DISPATCH_STATUS_V1",
      "DISPATCH_BINDING_PROOF_V1",
      "OWNERSHIP_MODE_BOUND_DISPATCH_V1",
      "DURABLE_PROVIDER_TASK_ID_V1",
      "QUERY_SSE_DISPOSITION_V1",
      "TERMINATION_ATOMIC_CAPABILITY_V1"
    ]
  }
}
```

- ID 字段是 opaque string；Navigator 只做 exact match、非空、长度和绑定校验，不解析
  UUID 时间或从 IP/端口推导。
- `ready=true` 只有在 configured ID 与 durable identity record 一致、非空 lifecycle
  credential 已配置、lifecycle store 已锁定且 durability probe 通过时成立。
- missing object、未知 schema/version、capability 缺失或任一 reason code 都表示
  Worker 不具备 v1；允许继续服务 legacy Task，但不能进入 SHADOW v1 parity 或
  `ENFORCED`。
- 已接受 `ENFORCED` Task 后不得因瞬时 contract probe 失败回退 legacy writer；owner
  进入 offline/storage freeze 并保留原 authority。

#### Fenced Inventory

新增：

```text
GET /api/v1/lifecycle/inventory?after_sequence={navigatorDurableAck}
X-Navigator-Expected-Physical-Worker-Id: {expectedPhysicalWorkerId}
X-Navigator-Expected-State-Generation: {expectedStateGeneration}
```

成功 response 固定包含：

```text
schema = NAVIGATOR_WORKER_LIFECYCLE_V1
physical_worker_id
state_generation
instance_epoch
inventory_id
min_available_sequence
through_sequence
coverage = COMPLETE
complete_active_task_set = true
tasks[] = {
  navigator_task_id,
  provider_task_id,
  ownership_mode,
  initial_dispatch_id,
  safe_binding_digest_version,
  safe_binding_digest,
  lifecycle_state,
  execution_observation,
  pending_interaction_ref,
  terminal_observed,
  terminal_status,
  terminal_source,
  last_sequence
}
terminal_tombstones[]
facts[]
```

- `through_sequence` 是 snapshot fence；`tasks/terminal_tombstones/facts` 只声明覆盖到该
  watermark，之后新 facts 由下一批或 lifecycle event stream 交付。
- `after_sequence` 是 required base-10 non-negative integer；首次同步使用 `0`。缺失、
  非整数或负数返回 400 `LIFECYCLE_CURSOR_INVALID`，不能隐式改用当前 head。
- `facts` 只含标准 fact envelope 和 content-free lifecycle metadata，不含 prompt、
  模型回复、tool payload、workspace path、credential 或业务数据。
- 每个 v1 `tasks[]`/terminal tombstone/fact 必须回带 initial
  ownership-mode-bound dispatch digest；Navigator 与本地 aggregate mode/binding
  不匹配时不消费该 Task evidence，并按 exact scope设置
  `conflictState=EVIDENCE_CONFLICT`。
- 若 requested cursor 小于 `min_available_sequence - 1`，返回 HTTP 409 与稳定 code
  `LIFECYCLE_CURSOR_COVERAGE_GAP`，同时返回 identity triple 和当前 cursor bounds；
  不得用当前 inventory 假装覆盖缺失区间。
- expected physical ID/state generation 不匹配时返回 409
  `LIFECYCLE_IDENTITY_MISMATCH` 或 `LIFECYCLE_STATE_GENERATION_MISMATCH`；Navigator
  不消费 body 中 Task facts。
- request fence header 缺失时返回 400；鉴权、request fence、cursor coverage 按该顺序
  fail closed，任何失败 response 都不能携带可消费 Task facts。

#### Worker-level Lifecycle Event Stream

新增：

```text
GET /api/v1/lifecycle/events?after_sequence={navigatorDurableAck}
X-Navigator-Expected-Physical-Worker-Id: {expectedPhysicalWorkerId}
X-Navigator-Expected-State-Generation: {expectedStateGeneration}
```

- 这是每 Physical Worker 一条的 content-free lifecycle SSE，不替代现有 Task
  message/content stream。
- 建连后第一条必须是 `sync_checkpoint`，携带 schema、identity triple、
  `min_available_sequence/through_sequence/coverage`；每个后续 fact 携带 durable global
  `sourceSequence`、original `instanceEpoch` 和 stable fact ID。
- `after_sequence` 使用 inventory 相同的 required parse、expected-identity 和 coverage
  规则；全部 preflight 通过后才发送 SSE headers/第一帧。
- requested cursor 存在 coverage gap 时在建立 SSE 前返回上述 409；不能静默从当前
  head 开始。
- 单 Task content stream 断开只影响 UI/content observation；Sentinel 依靠本
  Worker-level stream 与 inventory 判断 lifecycle coverage，二者都失效才升级 Worker
  connectivity disposition。

#### Monotonic ACK

新增：

```text
PUT /api/v1/lifecycle/ack
X-Navigator-Expected-Physical-Worker-Id: {expectedPhysicalWorkerId}
X-Navigator-Expected-State-Generation: {expectedStateGeneration}
{
  "schema": "NAVIGATOR_WORKER_LIFECYCLE_V1",
  "physical_worker_id": "...",
  "state_generation": "...",
  "through_sequence": 42
}
```

- Worker durability store 原子保存 accepted high watermark，并返回同一 identity scope
  与 `acked_through_sequence`。
- ACK 只允许单调前进且幂等；超出 Worker high watermark、identity/generation mismatch
  或 store persistence failure 必须 definitive reject，不能更新内存后返回成功。
- `through_sequence` 必须是 base-10 non-negative integer；missing/invalid 返回 400
  `LIFECYCLE_ACK_INVALID`。
- Navigator 必须先在一个数据库事务中完成 fact inbox 去重、reducer、snapshot、
  terminal safety fence 和 effect outbox；事务 commit 成功后才发送 ACK。
- ACK timeout 只导致重复 inventory/events/ACK；Navigator inbox 与 Worker ACK 均幂等，
  不重发 provider command。
- MVP-A 不因 ACK 立即删除 facts；compaction 仍须满足下文 retention window，避免短暂
  Navigator rollback 后失去 evidence。

#### Persist-before-effect and Failure Semantics

现有 Codex `EventBroadcast.emit` 普通 JSONL 与 completion receipt 允许 persistence
failure 降级为 warning，task registry 也是进程内 Map；它们可以继续服务 legacy replay
或 diagnostics，但不能满足 v1 authority。

##### Exact Command Surface

MVP-A 只批准以下 Worker v1 command/read surface；route、transport 和 applicability
不是实现者可选项：

| Action | Exact method/path | v1 request location | Success/duplicate transport | MVP-A disposition |
|---|---|---|---|---|
| Task create/resume | `POST /api/v1/query` | 现有 JSON body 的 additive `lifecycle_context` | `200 text/event-stream` | `SHADOW` + `ENFORCED` |
| termination/cancel | `POST /api/v1/tasks/{providerTaskId}/abort` | JSON body 只新增 `lifecycle_context`；继续携带既有 signed operation/signature headers | `202 application/json` | `SHADOW` + `ENFORCED` |
| exact dispatch reread | `GET /api/v1/lifecycle/dispatches/{dispatchId}` | expected-identity + required expected ownership-mode + expected-binding digest/version headers | `200 application/json` | read-only authority |
| termination readiness | `GET /api/v1/tasks/{providerTaskId}/termination-reconciliation-readiness?original_operation_id=...` | 现有 query | 现有 JSON | legacy diagnostic only；不能形成 owner terminal/never-accepted |
| mutating termination reconcile | `POST /api/v1/tasks/{providerTaskId}/termination-reconcile` | 现有 body/header | 现有 JSON | legacy only；携带 `SHADOW/ENFORCED` context 时在 capability consumption/effect 前返回 `409 LIFECYCLE_COMMAND_NOT_APPLICABLE` |
| permission/input response | Java 现有 `/api/v1/tasks/{taskId}/respond` 在 SDK Node Worker 无对应 route | none | none | `NOT_APPLICABLE(CODEX_SDK_INTERACTION_RESPONSE_SURFACE_ABSENT)`；Navigator 在 dispatch 前返回 `WORKER_INTERACTION_RESPONSE_UNSUPPORTED` |

因此本 MVP 不得为 SDK Worker 猜测或新增 `/respond` route，也不得把 mutating
termination-reconcile 当作 typed read-only reconciliation。typed
`task-reconcile` 只读 owner/receipt/canonical facts，不调用上述 Worker mutation。

Java shared typed adapter 的 applicability 同样冻结：

- `RuntimeTaskClosureProvider.supports()` 必须包含
  `CodexTaskService.CODEX_PROVIDER_TYPE`、
  `CodexTaskService.CODEX_BIZ_PROVIDER_TYPE` 和现有 app-server compatibility 支持；
  不得把 `codex-biz-worker` 归一成 public `codex-worker` 字符串。
- SDK completion/readiness inspection 对 `codex-worker` 与 `codex-biz-worker` 使用同一
  verified SDK runtime capability；app-server 是否支持该 inspection 继续保持现状，
  不因本 spec 扩张。
- providerType、runtimeType、physical Worker、Task/operation binding 任一不匹配都返回
  stable unsupported/mismatch result，不能落入其他 provider adapter。

`lifecycle_context` 固定为：

```json
{
  "schema": "NAVIGATOR_WORKER_LIFECYCLE_V1",
  "ownership_mode": "SHADOW",
  "command_kind": "TASK_CREATE",
  "navigator_task_id": "opaque-task-id",
  "dispatch_id": "opaque-dispatch-id",
  "delivery_attempt": 1,
  "expected_physical_worker_id": "opaque-worker-id",
  "expected_state_generation": "opaque-generation",
  "termination_operation_id": null
}
```

- `ownership_mode` 只允许 `SHADOW|ENFORCED`；`command_kind` 只允许
  `TASK_CREATE|TASK_RESUME|TERMINATION_CANCEL`。route 与 kind 不匹配返回
  `409 LIFECYCLE_COMMAND_KIND_MISMATCH`。mode 是 authority/effect 语义，不是
  transport hint；它必须进入下述 durable binding、disposition 和 status validation。
- create/resume 不携带 `termination_operation_id`；termination 必须携带并 exact 匹配
  signed capability claims。所有字段 required，明确为 null 的不适用字段除外。
- `delivery_attempt` 是 Navigator outbox 对同一 `dispatch_id` 持久单调递增的正整数；
  它区分 transport attempt，不创建新 business command。
- 缺失整个 object 保持现有 legacy semantics；一旦出现 `schema` 或 object，partial、
  unknown schema/mode/kind/field conflict 必须在 provider effect 前返回结构化 400/409，
  不得降级 legacy。
- Worker-generated terminal fact 必须回带 initial Task dispatch binding；termination
  final fact还必须回带 exact operation binding。
- 所有携带 context 的 command preflight 顺序固定为：lifecycle Bearer auth →
  context/schema/kind parse → expected physical ID/state generation fence → route-specific
  semantic validation → binding digest/dispatch record → provider effect。前一步失败时不得
  读取/写入后一步 record；termination 再在 binding 前验证 signed claims、在同一 durable
  transaction 中消费 capability，详见下文。

##### Binding Digest

Worker store 不保存原始 command body、prompt、tool payload、path、credential 或 signed
capability。`safe_binding_digest_version` 固定为 `JCS_SHA256_V1`：

1. 将解析后的 JSON body 移除 `lifecycle_context`，按 RFC 8785 JSON Canonicalization
   Scheme 生成 UTF-8 bytes；对 body 做 SHA-256，base64url-no-padding 得到
   `payload_digest`。没有业务 body 时使用 RFC 8785 的空 object `{}`。
2. termination 另对 exact encoded operation header 做 SHA-256/base64url-no-padding，
   得到 `capability_payload_digest`；不保存 header、signature 或 claims 原文。
3. 对以下 RFC 8785 object 再做 SHA-256/base64url-no-padding，得到
   `safe_binding_digest`：

```json
{
  "schema": "NAVIGATOR_LIFECYCLE_BINDING_V1",
  "ownership_mode": "SHADOW",
  "http_method": "POST",
  "route_template": "/api/v1/query",
  "command_kind": "TASK_CREATE",
  "navigator_task_id": "opaque-task-id",
  "provider_task_id": null,
  "dispatch_id": "opaque-dispatch-id",
  "termination_operation_id": null,
  "payload_digest": "base64url-sha256",
  "capability_payload_digest": null
}
```

route-specific digest input 固定如下：

| Command kind | `ownership_mode` input | Method/template | `provider_task_id` input | `termination_operation_id` input | Payload/capability rule |
|---|---|---|---|---|---|
| `TASK_CREATE` | exact context `SHADOW|ENFORCED` | `POST /api/v1/query` | null；ID 是 accepted disposition 的 durable output | null | query body 移除 lifecycle context 后做 payload digest；capability null |
| `TASK_RESUME` | exact context `SHADOW|ENFORCED` | `POST /api/v1/query` | null；新的 Worker Task ID 是 accepted disposition 的 durable output | null | resume body（含既有 session/thread selector）移除 lifecycle context 后做 payload digest；capability null |
| `TERMINATION_CANCEL` | exact context `SHADOW|ENFORCED` | `POST /api/v1/tasks/{providerTaskId}/abort` | exact decoded route segment | exact context/capability operation ID | body 移除 lifecycle context 后固定为空 object digest；signed operation header另做 capability digest |

`delivery_attempt`、accepted create/resume 后才分配的 `provider_task_id`、instance epoch、
Authorization 和 signature 不进入调用方预计算的 binding digest，使合法 technical
redelivery 保持同一 binding。`ownership_mode` 必须进入 digest；同一
dispatch/body/route 从 `SHADOW` 改为 `ENFORCED` 或反向改变时必须得到不同 digest，
不能复用或升级旧 record。command route 查到同 dispatch 的不同 durable mode 时必须在
duplicate handling/capability consumption/provider effect 前返回
`409 LIFECYCLE_OWNERSHIP_MODE_MISMATCH`；同 mode 但其他 binding input 不同才返回
`409 LIFECYCLE_DISPATCH_BINDING_MISMATCH`。Worker store 必须把 durable output
`provider_task_id` 与该 dispatch record 原子绑定，不能在同一 digest 下换 ID。store
只保存上述 digest/version、opaque correlation 和 opaque provider Task ID。Java 与
Node 使用相同 conformance fixtures，任何算法或 input 变更都需要 Worker contract
version 升级。

##### Durable Disposition Envelope

query 的首个非 comment SSE frame、abort JSON response 和 dispatch-status response 共用
以下 content-free fields：

```text
schema = NAVIGATOR_WORKER_LIFECYCLE_V1
ownership_mode = SHADOW | ENFORCED
physical_worker_id
state_generation
instance_epoch
navigator_task_id
provider_task_id
dispatch_id
request_delivery_attempt
disposition_delivery_attempt
command_kind
acceptance_disposition = ACCEPTED | REJECTED
effect_phase = PRE_EFFECT | PREPARED | EFFECT_STARTED | RESULT_OBSERVED
disposition_version
duplicate
accepted
provider_effect_started
reconcile_required
safe_binding_digest_version
safe_binding_digest
never_accepted_proof
fact_cursor
code?
termination_operation_id?
```

- `fact_cursor` 与 `disposition_version` 必须来自已 fsync 的 record；store failure
  response 不得伪造这两个字段。二者均为同 state generation 内的 base-10
  non-negative integer；`disposition_version` 对 exact dispatch 单调递增，
  `fact_cursor` 对应生成该 disposition 的 Worker global source sequence。
- command response 的 `request_delivery_attempt` exact echo 当前 request；
  `disposition_delivery_attempt` 是首次形成该 durable disposition 的 winning attempt。
  dispatch-status GET 的前者为 null、后者 required，避免 duplicate 把旧 disposition
  伪装成当前 attempt 新决定。
- `safe_binding_digest_version` 与 `safe_binding_digest` 对每一个 durable disposition
  都 required，且必须来自同一 fsync record；本 protocol 只允许
  `JCS_SHA256_V1`。`never_accepted_proof` 也是 required boolean，不能由 Navigator
  根据 HTTP status 或 `accepted=false` 自行补算。
- `ownership_mode` 对每一个 durable disposition required，并且必须与形成该 record
  的 lifecycle context 和 binding object exact 相同；duplicate/status/fact 不允许修改
  mode。Navigator 只有在 mode、digest/version 和本地 aggregate ownership 全部 exact
  match 时才可摄取 record。SHADOW disposition 永远不能满足 ENFORCED authority。
- durable phase 的 required/null 规则固定如下；任何不满足的 envelope 都是
  `LIFECYCLE_DISPOSITION_SCHEMA_INVALID`，不得摄取为 fact：

| Command/phase | `provider_task_id` | `termination_operation_id` | `never_accepted_proof` | Other invariants |
|---|---|---|---|---|
| create/resume `REJECTED/PRE_EFFECT` | null | null | 仅下文 allowlisted durable business rejection 为 true；其余 false | `accepted=false`, `provider_effect_started=false` |
| create/resume `ACCEPTED/PREPARED` | required | null | false | `accepted=true`, `provider_effect_started=false` |
| create/resume `ACCEPTED/EFFECT_STARTED|RESULT_OBSERVED` | required，且与 PREPARED 相同 | null | false | `accepted=true`, `provider_effect_started=true` |
| termination `REJECTED/PRE_EFFECT` | required，exact route target | required | false | `accepted=false`, `provider_effect_started=false` |
| termination `ACCEPTED/PREPARED` | required，exact route target | required | false | `accepted=true`, `provider_effect_started=false` |
| termination `ACCEPTED/EFFECT_STARTED|RESULT_OBSERVED` | required，且与 PREPARED 相同 | required | false | `accepted=true`, `provider_effect_started=true` |

non-durable auth/context/fence/binding/store-unavailable error envelope 不得携带
`ownership_mode/disposition_version/fact_cursor/safe_binding_digest/
never_accepted_proof`，也不能伪装成上述 durable disposition。SHADOW proposed
disposition 使用同一字段形状，但
`never_accepted_proof` 固定为 false，永远不是 owner authority。

对 `TASK_CREATE|TASK_RESUME`，Worker 必须先完成 route-specific admission validation，
再在创建 accepted `PREPARED` disposition 的同一 Worker-local atomic
transaction/critical section 内分配并保存唯一 `provider_task_id`。该 ID 随后必须用于
thread reservation、task registry、provider invocation、business/lifecycle SSE、
inventory、terminal fact、duplicate response 与 dispatch-status；禁止在后续业务 SSE
才首次建立 identity。Navigator Java adapter 必须在消费任何业务 SSE 前，从第一帧
durable disposition 持久化
`navigatorTaskId + dispatchId + ownershipMode + providerTaskId + safe_binding_digest`
binding；后续 event ID 或 mode 不同则进入 evidence conflict。primary response 丢失
时，Sentinel/status reread 用同一 binding 恢复该 ID；termination outbox/capability
在 ID 未恢复前不得创建、不得猜测或新建 provider Task。若 `PREPARED` atomic commit
失败，尚未 durable 的 ID 必须
丢弃、thread/local admission reservation 必须释放，返回 non-durable 503；该 ID 不得
出现在 status/inventory，也不得调用 provider。

status 暂时不可用且本地尚无 ID 时使用
`LIFECYCLE_PROVIDER_TASK_ID_UNRESOLVED` 冻结 Worker-dependent command；同一
dispatch/digest 出现两个 ID 时使用 `LIFECYCLE_PROVIDER_TASK_ID_CONFLICT`、
`conflictState=EVIDENCE_CONFLICT`，且不得选择“较新”ID自动修复。二者都是 internal
stable reason string，不增加 SDK enum。
- query `200 text/event-stream` 的第一帧固定
  `event: lifecycle_disposition`，`data` 是上述 JSON。ENFORCED primary accepted request
  后继续现有 Task SSE；ENFORCED exact duplicate 在
  `PREPARED/EFFECT_STARTED/RESULT_OBSERVED` 时只发送一帧并关闭，不重放 provider
  effect或业务内容。`PREPARED` 仅在原 executor lease still live 时这样返回；lease 已
  随 instance death 失效时可由同 dispatch 原子接管并先推进 `EFFECT_STARTED` 再执行
  一次。
- ENFORCED query durable `REJECTED/PRE_EFFECT` duplicate 返回
  `409 application/json` 同一 disposition；lifecycle store unavailable 返回
  `503 application/json + WORKER_LIFECYCLE_STORE_UNAVAILABLE`，但不是 durable
  disposition。
- ENFORCED abort primary/exact duplicate accepted 均返回 `202 application/json` 同一
  disposition，加现有 content-free Task status/termination operation summary；
  `ACKNOWLEDGED` 只表示 accepted。durable pre-effect reject 返回
  `409 application/json`；store unavailable 返回上述 503。
- SHADOW query 仍执行 legacy provider path并继续完整 SSE；第一帧只报告 proposed
  disposition，duplicate 使用 `code=SHADOW_WOULD_DEDUPE`，不能据此关闭 stream或抑制
  legacy effect。SHADOW abort 首次 response 与现有 202 相同；相同 one-use capability
  duplicate 继续返回现有 replay 409，同时记录
  `SHADOW_WOULD_RETURN_PRIOR_DISPOSITION` parity diff。任何差异在切换前必须由
  ENFORCED contract tests闭合，不能在 SHADOW 静默改变 legacy effect count。
- auth、malformed context、identity fence 或 binding mismatch 使用前文固定的
  400/401/403/409 envelope，在创建/读取业务 disposition 或 provider effect 前返回。

##### Fenced Dispatch Status and Delivery Ordering

新增 read-only endpoint：

```text
GET /api/v1/lifecycle/dispatches/{dispatch_id}
X-Navigator-Expected-Physical-Worker-Id: ...
X-Navigator-Expected-State-Generation: ...
X-Navigator-Expected-Ownership-Mode: SHADOW | ENFORCED
X-Navigator-Expected-Safe-Binding-Digest-Version: JCS_SHA256_V1
X-Navigator-Expected-Safe-Binding-Digest: ...
```

- 使用 lifecycle Bearer credential 和与 inventory 相同的 expected-identity 顺序。
- expected ownership mode header required，只允许 `SHADOW|ENFORCED`。missing/invalid
  返回 `400 LIFECYCLE_EXPECTED_OWNERSHIP_MODE_REQUIRED`；找到 dispatch 但 record mode
  不同返回 `409 LIFECYCLE_OWNERSHIP_MODE_MISMATCH`，且不得继续返回 disposition。
- 两个 expected-binding header 都 required。missing/blank/invalid base64url 值返回
  `400 LIFECYCLE_EXPECTED_BINDING_REQUIRED`；version 不是
  `JCS_SHA256_V1` 返回
  `409 LIFECYCLE_BINDING_DIGEST_VERSION_MISMATCH`；找到 exact dispatch 但 digest
  不同返回 `409 LIFECYCLE_DISPATCH_BINDING_MISMATCH`。比较使用 decoded digest 的
  constant-time equality，不在 response/log 中回显调用方错误值。
- exact record 返回上述 durable disposition；`404
  LIFECYCLE_DISPATCH_NOT_FOUND` 只表示当前 durable store 中未找到，不能证明 never
  accepted、terminal 或 safe replay。
- preflight 顺序固定为 Bearer auth → expected identity syntax/match → expected
  ownership mode syntax → expected binding syntax/version → dispatch lookup → record
  mode match → constant-time binding match → durable envelope。
  identity/generation/mode/version/binding mismatch 返回上述 409；store unavailable
  返回 503。只有匹配成功的 200 response 才回带 record 中的
  `ownership_mode/safe_binding_digest_version/safe_binding_digest`；所有 negative
  response 不携带 Task payload、actual mode/digest 或 provider Task ID。
- Navigator 在创建 command outbox 时按 route-specific table 计算并持久保存 expected
  ownership mode/digest/version；status reread 不从 primary response 学习或替换
  expected mode/binding。create/resume 的 provider Task ID 是匹配成功后从 record
  恢复的 durable output，不是 status request 的 expected input。
- Navigator command adapter 禁用 HTTP client/reverse proxy 的自动 POST retry。每个
  `dispatch_id` 同时最多一个 client-side in-flight attempt；发送前先持久化新的
  `delivery_attempt`。低于当前 outbox attempt 的晚到 response 只记 audit，不能生成
  owner fact。
- Worker 按 `(physicalWorkerId,stateGeneration,dispatchId)` 串行化，使用 durable
  `disposition_version` 和 process-bound executor lease。并行/晚到 attempt 必须先比较
  durable `ownership_mode`：mode 不同 exact 返回
  `409 LIFECYCLE_OWNERSHIP_MODE_MISMATCH`；只有 mode exact match 时，才可得到同一
  record 或 `409 LIFECYCLE_DISPATCH_BINDING_MISMATCH`。上述 mode 判断发生在 binding
  digest comparison、duplicate handling、capability consumption 和 provider effect
  前，任何 attempt 都不能各自启动 effect。
- transport timeout 后，Navigator 先 fenced 读取 dispatch status。只有 exact
  `NOT_FOUND`、当前无 in-flight attempt 且 policy 仍允许时，才可用相同 dispatch ID、
  相同 ownership mode/binding、递增 attempt 做一次 technical redelivery；改变 mode
  不是 retry，而是 `LIFECYCLE_OWNERSHIP_MODE_MISMATCH`。status unavailable/
  `PREPARED/EFFECT_STARTED/RESULT_OBSERVED` 时只读对账。termination capability 必须仍在
  有效期内，且不能换 operation/client request ID。

##### Signed One-use Termination Ordering

现有 `validateTerminationOperation()` 在 ENFORCED path 上必须拆分“验证 claims”和
“耐久消费”：

1. lifecycle Bearer auth；
2. lifecycle context schema/kind、expected identity 和 binding digest；
3. signed header/signature/claims/expiry/task/Worker/operation 校验，但此时不得调用现有
   in-memory replay ledger `consume`；
4. 在 Worker lifecycle store 的同一 fsync transaction/critical section 中锁定
   `(worker,generation,dispatchId)` 与 `(worker,terminationOperationId)`：
   - exact dispatch + exact operation/binding 已存在时返回 prior disposition，不再次
     consume；
   - operation 已绑定其他 dispatch/binding 时返回
     `409 TERMINATION_OPERATION_REPLAY_DETECTED`；
   - 两者都不存在时，同时创建 dispatch record 和 durable one-use operation
     reservation；
5. reservation commit 后才可推进 `EFFECT_STARTED` 并调用 cancel/signal；ACK fact
   durable 后才返回 202。

ENFORCED transaction/fsync failure 时不产生 reservation、ACK 或 provider effect，并返回
`WORKER_LIFECYCLE_STORE_UNAVAILABLE`。这取代 ENFORCED path 当前“effect 前先消费 in-memory
receipt、合法 transport duplicate 随后被当 replay”的顺序；legacy path 保持原行为。
SHADOW 只计算/持久化 proposed atomic disposition；现有 one-use consume/effect 顺序继续
执行并作为 parity 输入，不能提前切换 authority。

##### Mode Semantics and Durable Never-accepted Proof

| Concern | `SHADOW` | `ENFORCED` |
|---|---|---|
| identity/auth/context validation | 与 ENFORCED 相同；失败是 parity blocker，但 legacy request 未带 v1 context 时仍走原 path | effect 前 definitive reject |
| lifecycle durable append | 在 legacy effect 前尝试相同 record/fact | effect 前必须成功 |
| append/fsync failure | 不改变 legacy response/effect；记录 `SHADOW_LIFECYCLE_DURABILITY_UNAVAILABLE`，不计入 parity | 503 `WORKER_LIFECYCLE_STORE_UNAVAILABLE`；`accepted=false/provider_effect_started=false` 只是 non-durable transport observation |
| duplicate dispatch | 记录 `WOULD_DEDUPE/WOULD_REJECT`，不得抑制现有 legacy invocation | 返回 durable prior disposition；provider invocation count 不增加 |
| Navigator owner effect | proposed only，硬性为零 | durable owner outbox |
| terminal/ACK fact | gap fail-visible | 先 durable append 再发布/返回 |

`TASK_NEVER_ACCEPTED_CONFIRMED` 只允许用于 initial `TASK_CREATE|TASK_RESUME`，并同时满足：

1. Worker durable record 为
   `acceptance_disposition=REJECTED + effect_phase=PRE_EFFECT +
   accepted=false + provider_effect_started=false + never_accepted_proof=true`；
2. stable code 只允许
   `WORKER_TASK_ADMISSION_CAPACITY_REJECTED`、
   `WORKER_TASK_ADMISSION_THREAD_CONFLICT`、
   `WORKER_TASK_RESUME_TARGET_NOT_FOUND`；它们必须在 auth/fence/semantic validation 和
   dispatch binding 成功后作为 Worker business-admission result 原子落盘，普通 request
   validation error 不在 allowlist；
3. Navigator 通过 authenticated lifecycle fact 或上述 fenced dispatch-status reread
   取得 record，且 `ownership_mode=ENFORCED`，exact 匹配
   Worker/generation/Task/dispatch/binding/disposition version，
   其中 disposition 的 `safe_binding_digest_version/safe_binding_digest` 必须与
   Navigator outbox 中原始 expected binding exact match，且
   `never_accepted_proof=true` 来自同一 durable version，
   `disposition_delivery_attempt` 不高于当前已持久 outbox attempt，且不存在更高
   disposition version 或任何 accepted/started fact；原 command response 的
   `request_delivery_attempt` 只用于 stale-response audit；
4. snapshot 中不存在任何 accepted/started/running/result fact。

原 command HTTP response 本身只能触发 fenced reread，不能直接形成该 fact。
`WORKER_LIFECYCLE_STORE_UNAVAILABLE`、generic 4xx/5xx、auth/fence/binding failure、
`LIFECYCLE_DISPATCH_NOT_FOUND`、timeout、response loss、Task 404 和 inventory absence
一律只使 operation 保持 `AMBIGUOUS`；它们自身不创建新 availability enum，snapshot
按仍有效的 offline/storage/configuration/conflict facts和冻结 precedence重算。若
attempt A 的 store-unavailable response 晚于 attempt B 的 durable acceptance，attempt
fencing 和 durable record precedence 必须忽略 A，不得把正在执行的 Task terminalize。

ENFORCED crash boundary：

- crash before durable dispatch record：provider effect 不得开始；status 为 NOT_FOUND，
  policy 可决定同 dispatch technical redelivery。
- crash at durable `PREPARED`：只有 executor lease 已随原 instance death 失效后，同
  dispatch 才能 CAS 接管；live executor 存在时 duplicate 只返回 PREPARED。
- crash/response loss at `EFFECT_STARTED`：无论 provider effect 是否真正开始都不得再次
  调用；operation 保持 `AMBIGUOUS`，Task availability 按 blocker precedence 重算并从
  durable Worker/provider facts 对账。
- response loss after `RESULT_OBSERVED`：duplicate 只返回 prior disposition/fact cursor，
  不重放 provider effect或业务 content。
- durable allowlisted never-accepted proof：Task 进入受限 `FAILED` branch，走同一
  tombstone、cleanup 和 foreground lane release；不得删除 Navigator accepted command
  fact来伪装“从未存在”。
- provider terminal/verified exit 必须先 durable append exact terminal fact，再发布
  lifecycle event；SSE failure 不丢 authority。

Task absence rules：

- Task 不在 `completeActiveTaskSet` 只证明它未出现在该次 active snapshot，不能单独证明
  terminal。
- exact terminal tombstone/evidence 可以形成 terminal candidate；只有上述 durable
  fenced rejection disposition 可以证明 Worker never accepted。
- Navigator 已有 Worker acceptance、但 inventory 中既无 active Task 又无 terminal
  tombstone时，产生 state-loss evidence，Task 设置
  `availability=AUTHORITY_QUARANTINED +
  conflictState=WORKER_STATE_LOSS`，operation 保持 `AMBIGUOUS`。
- `stateGeneration` 变化或 ACK cursor 已落到 `minAvailableSequence` 之前，表示 evidence
  coverage gap；受影响旧 Task 不 terminal，并按 exact scope 设置
  `availability=AUTHORITY_QUARANTINED +
  conflictState=WORKER_STATE_LOSS`，进入逐 Task recovery/人工处置。
- 旧 `instanceEpoch` 的 heartbeat/running observation 失效；同一 state generation 中
  带稳定 fact ID/sequence 的晚到 durable terminal evidence 仍可幂等接收。

Worker gate 只有在 identity、capability、fenced inventory 和 unowned-process scan 均
完成后才能为新 Session 恢复 `READY`。旧 Task 是否解冻逐个决定；一个 coverage gap
不能冻结整个 Worker，但任何疑似遗留进程必须先被识别或隔离，不能与新 Task 争用同一
thread/context。

### Lifecycle Retention and Storage Pressure Policy

安全 replay receipt 与 lifecycle evidence 使用不同 ledger/retention：

- replay receipt 只回答“这个有时效的 capability 是否已消费”，可以在
  `expiresAt + clockSkew` 后按安全策略回收。
- lifecycle fact/tombstone 回答“Worker 对 exact Task/operation 观察到了什么”，必须按
  Navigator ACK、Task resolution 和 evidence coverage 管理，不能随 capability expiry
  一起删除。
- reconciliation 不应长期依赖 replay receipt 是否仍在；应读取独立、content-free 的
  lifecycle operation fact/tombstone。

建议分层：

| Tier | Records | Retention/Compaction |
|---|---|---|
| P0 protected | 未 ACK facts、所有 active/frozen/ambiguous Task snapshot、未决 interaction/termination、未完成 terminal evidence | 无基于时间的删除；只能在 durable ACK/resolution 后降级 |
| P1 terminal tombstone | exact task/worker/operation、outcome/source、terminal sequence/time、evidence digest | Navigator ACK 后 MVP-A 最少保留 30 天；只保留 content-free compact record |
| P2 ACKed detail | 已被 tombstone/snapshot 覆盖的中间 dispatch/running/interaction facts | ACK + atomic checkpoint 后可 compact；默认保留 24 小时 recovery grace |
| P3 diagnostics | 非 authority 日志、文本和性能 observation | 独立短 TTL；永不作为 lifecycle recovery 前置 |
| Security receipt | one-use operation fence | 至少保留到 capability expiry + clock skew；与 P0-P2 分离 |

P0 不设置“到期即删除”。如果某 Task 多月 ambiguous，它仍占用极小 content-free snapshot
和必要 evidence；必须由 Worker evidence、管理员 decision 或正式 transfer/recovery
解除，不能靠 retention 制造 resolution。

terminal tombstone 的 30 天是 MVP-A Worker 侧硬性最低重连/恢复窗口，不限制 Navigator
对已 ACK Worker facts 和 canonical history 的长期保留。部署可以提高但不能降低；未来
调整下限必须重新评审 retention/rollback evidence。

#### Safe Compaction

1. 先写入并 fsync snapshot/tombstone，包含 covered sequence range、fact digest 和
   terminal provenance。
2. 原子发布 checkpoint；崩溃恢复必须看到旧 facts 或完整新 checkpoint，不能两者皆无。
3. 只有 Navigator ACK cursor 已越过 covered range，才清理 P2 detail。
4. compaction 不改变 `stateGeneration/sourceSequence`，也不把 missing detail解释成
   state reset。
5. prompt、模型回复、业务 payload 和 credential 不进入 lifecycle ledger；其业务保留
   策略独立。

#### Storage Pressure

Worker 同时观察 configured lifecycle quota 和文件系统可用空间，预留 emergency journal
budget 供 terminal/evidence/checkpoint 落盘：

| Level | Recommended Default | Behavior |
|---|---|---|
| normal | below 70% quota and above free-space floor | normal operation |
| degraded | 70%-85% | compact eligible P2/P3、告警；不删 P0/P1 |
| frozen | above 85% or below emergency free-space floor | 拒绝新 Task及会创建新 Worker effect 的 command；保留 read/reconcile/ACK/compaction |
| critical | emergency budget 也不足或 durability probe fails | fail-closed read-only；不得先执行外部副作用再补 fact |

- threshold 应可配置，但 invariant 不可配置：P0 不因压力删除，任何 authority fact/effect
  必须先 durable persist。
- storage recovered 后先做 durability probe、identity/state-generation verification 和
  inventory reconciliation，再恢复 `READY`。
- 自动删除优先级只能是 P3 → eligible P2；P1 仅在 minimum retention、ACK 和 compact
  coverage 同时满足时删除。
- quota full 不得静默旋转 state generation；若运维确实重建 store，必须显式生成新的
  `stateGeneration`，使旧 Task 进入 coverage-gap recovery。

## Deterministic Reducer Contract

```text
recompute(
  previousCanonicalSnapshot,
  authoritativeFactSet,
  policyVersion,
  evaluationTick
) -> {
  nextCanonicalSnapshot,
  operationalDisposition,
  requiredEffects,
  invariantViolations
}
```

这里的 `operationalDisposition` 是本次 reducer evaluation 的 command/effect allow/deny
结果对象，不是第三个持久状态字段；它只能引用 snapshot 的
`availability/conflictState` 与 stable reason code，不能再定义另一套 availability
枚举。

Reducer requirements:

- pure/deterministic，无 network、repository side effect 或 ambient current time。
- canonical terminal monotonic，不因 Worker unknown/restart 回退。
- disconnect 只改变 connectivity、freshness、freeze gate 和 disposition。
- deadline 只改变 disposition/required admin action，不制造 execution terminal。
- required effects 通过 durable outbox 交给独立 idempotent handler；handler 结果以
  checkpoint fact 返回。
- provider-specific adapter 先把原生结果规范化为标准 fact，reducer 不认识 Codex、
  Claude、Gemini 或 LangGraph 私有状态字符串。
- 每次 decision 记录 input fact cursor、policyVersion 和 aggregateVersion，支持审计与
  shadow replay。

### Incremental Reduce and Full Recompute

owner 同时提供两种使用同一规则内核的入口：

```text
applyFact(previousSnapshot, oneNormalizedFact, policyVersion)
recompute(authoritativeFactSet, irreversibleDecisions, policyVersion, evaluationTick)
```

- `applyFact` 用于正常低延迟处理。
- `recompute` 用于 Worker reconnect、进程重启、shadow audit 和 projection repair。
- 两者对相同 fact cursor 必须产生相同 canonical snapshot；使用 parity/property tests
  固定该不变量。
- full recompute 不直接扫描各模块业务表；只读取 owner 管理的 normalized fact set、
  irreversible decision facts 和 checkpoint facts，避免把 reducer 再次耦合到局部实现。

## Fact Precedence and Conflict Rules

precedence 按 authority domain 和 fact scope 决定，不设置一个粗暴的全局 source
优先级。

| Situation | Decision |
|---|---|
| stale connectivity observation vs newer Worker epoch | 新 epoch/freshness 仅更新 connectivity；不改历史 canonical terminal |
| Worker disconnected vs Task active | Task canonical 保持；execution unknown + offline frozen |
| verified Worker terminal vs active logical Task | 产生 canonical terminal candidate |
| canonical terminal vs later Worker running observation | exact Task 设置 `availability=AUTHORITY_QUARANTINED + conflictState=EVIDENCE_CONFLICT`；不 reopen、不自动 mutation |
| ACK/text/log vs any canonical state | 无 terminal authority |
| deadline elapsed vs active Task | disposition 变 ambiguous；canonical state 不变 |

必须分别保存以下唯一 snapshot 字段，不得建立同义字段：

```text
canonicalPhase + terminalOutcome
terminalSource
executionObservation
availability
conflictState
cleanupState
```

这样晚到执行事实可以补全历史，而不需要重写已经对用户生效的 logical decision。

## Persistence and Shadow Rollout

推荐使用“append-only lifecycle facts + current snapshots + durable outbox”的混合模型，
不采用全系统纯 Event Sourcing：

| Store | Purpose |
|---|---|
| `lifecycle_facts` | normalized append-only observations/evidence/decisions/checkpoints；unique idempotency key |
| `worker_lifecycle_snapshots` | Worker epoch、connectivity、readiness、freeze/recovery projection |
| `task_lifecycle_snapshots` | Task canonical/execution/termination/cleanup/disposition projection |
| `session_lifecycle_snapshots` | Session lifecycle/interaction/availability projection，只消费 Task canonical facts |
| `lifecycle_effect_outbox` | reducer 产生的 effect；记录 `effectClass=EXTERNAL_PROVIDER_ONCE|LOCAL_IDEMPOTENT` 与 `effectState=PENDING|CLAIMED|EFFECT_STARTED|CHECKPOINTED|FROZEN`，不承载用户离线 command queue |
| `lifecycle_writer_exclusivity_proofs` | target generation 的 controller/process inventory digest、proof lease 与 loss/quarantine disposition |
| `lifecycle_writer_exclusivity_references` | exact proof 到 ENFORCED Worker/Session/Task 的逐 aggregate durable reference；release predicate 可审计 |

现有 `termination_operations` 保留为 TerminationProcessManager 的 child aggregate，记录
operation identity、dispatch、ACK 和 evidence progress；不把全部 Session/Task/Worker
事实继续塞入该表。其 `status/dispatchState` 只按“Frozen Status and Reconciliation
Mapping”双向投影；旧 `ABORTED/OBSERVED` 行没有 exact provenance 时不能被当作
`CONFIRMED/CANCELLED` authority。

单次处理事务：

1. inbox/ledger 以 idempotency key 接受 normalized fact。
2. 锁定对应 aggregate snapshot/version。
3. reducer 计算 next snapshot、disposition、effects 和 conflict。
4. 同事务保存 snapshot、fact cursor 和 effect outbox。
5. 独立 handler 至少一次领取 outbox：`LOCAL_IDEMPOTENT` effect 可按 checkpoint
   至少一次重试；`EXTERNAL_PROVIDER_ONCE` 只在首次
   `effectState=EFFECT_STARTED` authorization 后调用一次，之后只读对账。结果作为
   checkpoint fact 回流。

多实例通过 aggregate optimistic version 或短 lease 串行化同一 aggregate，不建立跨
Worker/Task/Session 的全局事件顺序。

### Aggregate Ownership Mode

每个 Worker/Task/Session aggregate 显式记录单调 ownership mode：

| Mode | Legacy Writers | Owner Reducer | Effects |
|---|---|---|---|
| `LEGACY` | 现状 authority | off 或仅离线分析 | legacy path |
| `SHADOW` | 仍是 compatibility authority | 同步消费 facts、计算 snapshot 和 parity diff | 只记录 proposed effects，不执行 |
| `ENFORCED` | 禁止直接写 canonical lifecycle；只允许 compatibility projector 写旧列 | sole canonical authority | durable outbox 执行 |

合法迁移仅为：

```text
LEGACY -> SHADOW -> ENFORCED
```

- mode 按 aggregate 切换并记录 owner version/policy version，不使用一个瞬时全局开关。
- `ENFORCED` 不回退到 `LEGACY`，否则同一 Task 会重新出现双 authority。紧急止损只能暂停
  新 aggregate enrollment、暂停非安全 effect 或切换到 fail-closed read-only。
- direct writer 收到 enforced aggregate 时必须转交 normalized fact 或拒绝；不能继续写
  legacy status 后要求 owner“追认”。
- 旧 `status/interactionState/latestTaskId` 由 compatibility projector 从 owner snapshot
  写回，供现有 SDK/UI 使用，但这些列不能反向成为 enforced reducer input。

### MVP-A Java Writer Generation and Exclusive Cutover

MVP-A 不声称支持旧 binary 与 owner-capable binary 并存。数据库 fencing 与运维
exclusivity 分工如下：

1. additive schema 保存唯一 active lifecycle writer generation，至少包含
   `generationId + minimumOwnerProtocol + targetCommit + activatedAt`；每个
   owner-capable Navigator instance 以自身 stable instance ID 注册
   `generationId + protocol + commit + heartbeat`。
2. owner-capable binary startup/readiness 必须验证 schema version、
   `minimumOwnerProtocol <= binaryProtocol`、target commit 与 active generation
   一致。任何 mismatch 使 owner readiness 与 enrollment fail closed，但 legacy
   read-only API 可以继续。
3. 所有 `ENFORCED` aggregate 记录 enrollment writer generation；owner snapshot/fact/
   outbox conditional write 必须匹配当前 active generation。这样前一代 owner-capable
   stale instance 即使恢复，也无法推进新 generation。
4. 完全不识别新 schema 的旧 binary 无法靠上述 application-level check 自动 fencing。
   因此第一次 activation 必须先 drain admission，再枚举并记录所有可能拉起旧 binary
   的 deployment controller：systemd service/timer、Kubernetes
   Deployment/StatefulSet/Job/CronJob、replica/restart policy、Docker/进程 supervisor、
   CI/CD launcher、shell/PowerShell scheduled job 和人工运维入口。清单必须包含 exact
   unit/job identity、artifact/commit、cwd、desired replicas、restart policy 与配置
   revision；每一类明确标记 `PRESENT` 或带检查证据的 `NOT_APPLICABLE`，“当前没进程”
   不是完整 inventory。
5. 在停止旧进程前，必须先对上述旧 deployment unit 执行 disable/mask、pause、
   scale-to-zero、取消 queued job 或等价不可自动恢复的控制，并冻结变更窗口。无法确认
   controller state、共享 launcher 仍可拉起旧 artifact，或有未归属 supervisor 时，
   cutover 立即 fail closed。
6. exclusivity proof 不是一次性 evidence，而是 DB-backed lease，至少保存
   `proofId + generationId + controllerInventoryDigest + holderInstanceId +
   acquiredAt + lastVerifiedAt + expiresAt + status`；逐 aggregate reference 使用独立
   durable rows
   `proofId + aggregateType(WORKER|SESSION|TASK) + aggregateId + acquiredAt +
   releasedAt + releaseReason`，不得只依赖一个可漂移的总数。proof row 可维护
   Worker/Session/Task reference count 和 unfinished outbox count 作为校验缓存，但释放
   时必须查询 exact live rows。时间判断使用 DB server time；默认每 15 秒完成一次完整
   observer verification，lease TTL 45 秒，任一 probe 未完成/失败/发现 drift 时不得
   续租。配置可以缩短，不能在本 protocol version 下放宽该上限。
7. proof 从 admission drain 前获取，连续覆盖 controller disable、旧进程归零、
   schema/readiness、writer generation activation、target startup、首次 enrollment 和
   所有引用它的 aggregate 生命周期。reference 的建立与释放条件固定如下：

   | Aggregate | Reference acquired | Reference released |
   |---|---|---|
   | Worker | exact Worker aggregate 进入 `ENFORCED` 的同一 transaction | 仅显式 owner-capable Worker retirement 已提交、其下无 Session/Task reference 且无 unfinished outbox/effect authorization 时；MVP-A 未提供 retirement command，因此 canary Worker reference 不会因 Task 归零自动释放 |
   | Session | exact Session aggregate 进入 `ENFORCED`/绑定该 Worker proof 的同一 transaction | `canonicalPhase=CLOSED`、所有 child Task reference 已释放、foreground lane 为 `FREE` 且无 Session outbox 时 |
   | Task | exact Task aggregate 进入 `ENFORCED`/占用 Session lane 的同一 transaction | `TASK_TERMINAL_CLEANUP_COMPLETED`、Task compatibility projection 已 checkpoint、无 Task outbox/claim/effect authorization 时 |

   OPEN Session 在最后一个 Task terminal 后仍持有 reference；ENFORCED Worker 也继续
   持有 reference。proof 只有在三类 reference row 全部已 release、所有 lifecycle
   outbox 均 terminal-checkpointed 且不存在
   `CLAIMED`/`effectState=EFFECT_STARTED` authorization
   时才可释放。ownership mode 不回退，因此“释放 Worker reference”不能被实现为切回
   legacy。
8. 每次 ENFORCED enrollment 与 compatibility projection write 都必须验证 exact active
   proof ID/generation/status/expiry。outbox `CLAIMED` 只表示某个 handler 持有 work
   lease，不授予 Worker/provider call 权限；claim 时仍需保存 exact proof/ref binding，
   但 claimed-yet-not-authorized effect 在 proof loss 后必须调用为零。
9. 每个 `effectClass=EXTERNAL_PROVIDER_ONCE` 的外部 Worker/provider effect 在实际 call
   前必须执行独立短事务：以固定顺序锁定
   proof row、aggregate reference row 和 outbox row，验证 proof
   `ACTIVE`、未过期、generation/inventory digest/ref exact match，并以 conditional
   update 将 exact outbox 从 `CLAIMED` 推进为不可重放的
   `effectState=EFFECT_STARTED`，同时保存
   `effectAuthorizationProofVersion/authorizedAt`。该 commit 是 provider-effect
   authorization 的唯一线性化点；handler 随后只能在同一 claim execution 中立即调用
   一次 provider，不能把 authorization 交给另一进程或在 crash 后重新调用。
10. proof-loss transaction 使用同一 proof row lock/CAS，因而并发结果只有两种：
    - loss 先 commit：proof 为 `LOST`，未到 `effectState=EFFECT_STARTED` 的 claim
      authorization 失败并进入 frozen checkpoint，provider invocation count 为零；
    - exact effect 的 `effectState=EFFECT_STARTED` 先 commit：该 effect 已取得一次性 authority，
      随后的 proof loss quarantine 全部 aggregate并禁止任何新 effect/retry，但不能把
      已授权 call 改为未发生或再次投递。若进程在 commit 后、call/response 前崩溃，
      outcome 保持 ambiguous/frozen并只读对账，provider invocation 永不自动重试。

    `LOCAL_IDEMPOTENT` cleanup 继续使用 checkpoint-backed retry；其中
    terminal+tombstone 与 token deny/revoke 等明确列出的 local monotonic safety
    action不经过 provider-effect authorization，可在 proof lost 后按既有同事务/幂等
    规则继续。compatibility projection 虽可幂等，proof lost 时仍冻结；不得把普通
    create/resume/termination/projection effect 伪装成 safety action。
11. observer 同时检查 deployment desired state 与实际 process
    command line/cwd/artifact identity。端口空闲、DB heartbeat 缺失、瞬时“进程为零”
    或一次性截图均不充分。controller 已禁用后才停止全部旧 Navigator Java instances，
    并证明实际进程为零。
12. generation activation 前必须执行 late-relaunch drill：通过各真实 supervisor/
    deployment controller 发起一次普通 reconcile/restart，结果必须仍保持
    disabled/scale-zero 且不产生旧进程。真实 controller drill 属于后文单独授权的
    activation rehearsal；普通 source implementation 只能运行 repo-owned fixture。
13. 旧 controller 保持禁用后才激活 generation，再仅从已审计的 target deployment unit
    启动同一 target commit/protocol 的一个或多个 Navigator instance。每次 target
    startup 和每次 ENFORCED enrollment 都重验 controller inventory digest、连续 proof
    freshness 与 active process inventory。发现未知/旧实例、旧 unit 被启用、不同
    commit/protocol heartbeat、重复 active generation、observer lease expiry 或 inventory
    digest drift 时，以前述 proof row CAS 原子标记 `LOST`，立即禁止新 enrollment。
14. proof `LOST` 时，所有引用该 proof 的 live Worker/Session/Task 设置
    `conflictState=LEGACY_WRITER_EXCLUSIVITY_LOST`、
    `availability=AUTHORITY_QUARANTINED`：
    - 禁止新 Worker/provider command、Session lane release、compatibility projection 和
      legacy fallback；
    - 允许 content-free Worker facts 进入 durable quarantine inbox；
    - 允许 exact Worker terminal evidence 驱动 canonical terminal + authorization
      tombstone 的同事务 safety fence，以及已存在的 local token revoke；这些单调安全
      动作不能解除 quarantine；
    - proof 由相同 inventory重新建立并完成 conflict scan 后，owner 才重放 quarantined
      facts、恢复 projection/cleanup。发现 legacy row drift 只记录 conflict并由 owner
      重投影，绝不把 legacy value 作为 authority。
15. 旧 deployment controller 在任一 Worker/Session/Task proof reference 存活期间都必须
    维持 disabled/scale-zero，并由 proof observer 持续验证。共享人工 launcher、CI job、
    queued deployment 或 supervisor 若只能“约定不启动”而不能技术性 disable/lock 和
    观察，本阶段 operational fence 不成立；第一次 ENFORCED 前必须实现 database-level
    legacy-writer guard/trigger 或等价不可绕过机制并重新评审。
16. 第一个 aggregate 进入 `ENFORCED` 后，DB `minimumOwnerProtocol` 不得降到 legacy；
    rollback 只允许到仍理解 ownership mode、writer generation、terminal tombstone 和
    cleanup plan 的 owner-capable binary。回滚代码不能重启 legacy authority。
17. 若未来要求无停机 rolling upgrade，必须另行实现 database-level enforced-row writer
    guard/trigger 或等价不可绕过机制，并重新评审；不得把本阶段 operational stop-all
    gate 宣称为 rolling-safe。

### Rollout Stages

#### Stage 0: Inventory and Contract Freeze

- 枚举所有 Session/Task/termination/token/registration direct writers、status list 和
  terminal 判断；统一映射到 fact authority。
- 冻结 compatibility projection、cleanup applicability、Worker v1 和 stable reason
  codes。
- 先建立 reducer/property/adapter contract tests，不改变运行行为。

#### Stage 1: Complete Codex Vertical Chain in SHADOW

- 只新增 lifecycle facts、snapshots、outbox 和 ownership metadata，不删除/重命名旧列。
- provider adapters 在保留 legacy write 的同时提交 normalized shadow facts。
- shadow reducer 禁止执行 effect；仅持久化预期 snapshot、proposed effects 和 parity
  classification。
- Worker v1 SHADOW sample 必须通过专用 credential/expected-identity preflight 并携带
  完整 lifecycle context；未通过的请求继续归 legacy coverage，但不能计为 v1 parity。
- parity diff 至少区分 `MATCH`、`EXPECTED_MODEL_SPLIT`、`LEGACY_STALE`、
  `OWNER_MISSING_FACT`、`AUTHORITY_CONFLICT`，不在 shadow 阶段自动修表。
- Task owner、terminal tombstone/cleanup、Codex termination、Worker v1/Sentinel、
  offline gate 和 Session single-flight 必须全部在本阶段完成；不得只接管 termination
  子域后提前 enforcement。

#### Stage 2: Exclusive Cutover Readiness

- 本 stage 描述真实 activation，必须在 source signoff 后另行取得 exact target 的
  controller/process mutation 授权；source Slice 7 只用 ephemeral fixture 验证同一
  gate logic。
- schema 由部署流程预应用，owner schema readiness fail closed。
- 先禁用/scale-to-zero 所有旧 deployment unit、supervisor、restart policy、
  timer/job/launcher，并对真实 controller 完成 late-relaunch drill；随后停止所有旧
  Navigator Java 实例并由 process command line/cwd 与 deployment inventory 证明为零。
  端口空闲本身不构成证明。
- 只启动同一 target commit、同一 lifecycle writer protocol 的 Navigator Java
  instances；它们注册到唯一 active DB writer generation，Sentinel 仍按 Worker 使用
  独立 lease。
- 从 admission drain 到最后一个 Worker/Session/Task proof reference 释放且所有
  unfinished outbox归零，维持 DB-backed exclusivity proof lease；旧 controller
  inventory digest、desired state、实际进程或 proof freshness 任一漂移都阻止
  enrollment 并 quarantine 既存 aggregate。旧 controller 在任一 reference 存活期间
  保持禁用。
- 任何旧实例仍存活、未知实例无法归属、target instance build/protocol 不一致时，
  `ENFORCED` enrollment 保持关闭。
- 该 stage 不执行 rolling upgrade。DB minimum-writer metadata 约束 owner-capable
  target binary，但不能被描述为能魔法阻止一个仍在运行、完全不识别该表的旧 binary。

#### Stage 3: First Codex SDK New-Aggregate Canary

- 本 stage 的第一个非 fixture aggregate 不属于 source implementation 默认授权。
- 只对新建 Session/Task 按 exact tenant + physical Worker + provider type allowlist 启用
  `ENFORCED`。
- runtime 必须是 Codex SDK 且 provider type 必须为 `codex-biz-worker`。
  `codex-worker`、`codex-app-server-worker`、Claude 和其他 provider 明确拒绝首次
  enrollment。
- exact tuple 必须通过 lifecycle credential/identity/wire readiness，且
  `terminationRequestReceiptEnabled=true`；receipt disabled 仍按 BUG-035 服务 legacy
  caller，但不允许进入首次 canary。
- Session foreground lane reservation、Task command fact 和 initial snapshot 在同一事务
  完成，杜绝两个并发请求同时占用 lane。
- Worker/Session/Task ENFORCED ownership 分别在同一 enrollment transaction 建立 exact
  proof reference；不能只增加 Task count。
- provider/Worker callbacks 全部经 inbox/fact adapter；terminal cleanup 只经 owner
  transaction/outbox。
- 旧 API 继续读取 compatibility projection，因此 canary 不强制 SDK/CLI 同步升级。
- canary 可暂停新 enrollment；已 enforced aggregate 继续由 owner 收敛。

#### Stage 4: Codex SDK Expansion

- 只有在首个 `codex-biz-worker` exact tuple 的 parity、fault injection 和 controlled
  canary 证据通过后，才可单独评审把 `codex-worker` 加入 allowlist；共享 SDK runtime
  不代表自动纳入。
- 所有新 Codex SDK Session/Task 默认 `ENFORCED` 是后续 signoff/deployment decision，
  不是首次 canary 的默认行为。
- 仍有 legacy foreground Task 的 Session 保持 lane occupied，不能通过创建新 Task
  绕过。
- 已 enforced aggregate 继续由 owner 完成，即使暂停下一批 rollout，也不回退 authority。

#### Stage 5: Existing Aggregate Handling

- 历史 terminal Task 只导入 existing logical closure/provenance，建立 compatibility
  baseline；不重新执行 provider termination，也不无条件重放旧 cleanup effect。
- 正在运行、cancel requested、frozen 或 ambiguous 的 legacy Task 不批量猜测迁移：
  先冻结 mutation、拉取 Worker fenced inventory、生成 baseline facts、执行 shadow
  parity，再原子切换 ownership。
- 无 terminal evidence、存在 coverage gap 或 state-generation conflict 的 Task 保持
  frozen/ambiguous，交由逐项 recovery/人工处置。
- 可自然结束的短期 legacy Task 优先让其在 legacy lane 结束，不为追求迁移率增加在线
  takeover 风险。

#### Stage 6: Provider Expansion and Legacy Writer Retirement

- Claude MVP-B、Codex app-server 或其他 provider 必须先以相同 contract 完成独立
  SHADOW/复审，不能因 Codex SDK canary 自动获得 enrollment 资格。
- 只有在目标 provider adapter、terminal cleanup、Session gate 和 reconciliation 已
  enforced 且 parity gate 通过后，才移除该 provider 的 direct writers/status-list
  decisions。
- compatibility columns/API 可以长期保留为 projection；移除它们不是 owner 上线前置。
- 最终通过架构测试禁止 participant repository 直接修改 canonical lifecycle fields。

### Shadow and Cutover Gates

进入下一阶段至少要求：

1. 同一 fact set 的 incremental reduce 与 full recompute 结果一致。
2. duplicate/out-of-order/late fact、进程重启和 ACK 重放不产生重复 effect。
3. shadow proposed terminal 均能追溯到允许的 exact Worker/runtime evidence。
4. `CANCEL_REQUESTED`、offline、timeout、ACK 和文本均没有单独形成 terminal。
5. terminal cleanup plan 对 tombstone、token、compatibility projection、receipt 和
   derived registration applicability 完整分类。
6. 所有 unexplained parity diff 已分类；不能用 legacy status 覆盖 owner 结果消除告警。
7. canary 可以按新 aggregate enrollment 停止，但已 enforced aggregate 仍可安全收敛。

## Initial Inference Rules

```text
WORKER_DISCONNECTED_OBSERVED
  -> availability=OFFLINE_FROZEN
  -> preserve canonical Session/Task
  -> suspend Worker-dependent effects

WORKER_RECONNECTED_OBSERVED
  -> availability=RECOVERING
  -> do not resume effects yet

WORKER_RECONCILIATION_COMPLETED
  -> recompute all affected Task projections
  -> conditionally availability=READY

TERMINATION_ACKNOWLEDGED
  -> termination acknowledged only
  -> never Task terminal

TERMINATION_EVIDENCE_DEADLINE_ELAPSED
  -> terminationState=AMBIGUOUS
  -> availability full recompute from active blockers
  -> never Task terminal

exact authenticated TASK_NEVER_ACCEPTED_CONFIRMED
  + no Worker acceptance/execution fact
  -> FAILED / WORKER_PRE_EFFECT_REJECTION / NOT_STARTED terminal candidate
  -> same tombstone and cleanup gate

verified terminal evidence + exact correlation
  -> candidate canonical terminal effect

canonical terminal + cleanup checkpoints complete
  -> typed TERMINAL
```

## Target MVP-A Stop Boundary

MVP-A 只交付 Codex SDK 的首条完整 vertical chain，不扩展完整平台协议：

1. `session-module` 中 provider-neutral Worker/Task/Session owners、纯 reducer、
   normalized facts、durable inbox/outbox、Sentinel lease/scheduling 和 compatibility
   projection。
2. termination 从 accepted/ACK 收敛到 Worker-evidence terminal 或明确
   rejected/ambiguous；同 request ID 保持只读幂等。
3. canonical terminal 与 authorization tombstone 原子提交；token、compatibility
   projection、optional receipt 按 frozen cleanup plan 收敛，derived active registration
   变为 false。
4. Codex SDK Worker lifecycle v1、Codex Sentinel adapter、offline command gate 与 Session
   foreground single-flight；v1 包括专用 auth、expected identity fence、双 mode
   context、ownership-mode-bound digest/status reread、durable provider Task identity 和
   exact dispatch dedupe。
5. public receipt-disabled 保持 BUG-035；first canary 只允许 receipt-enabled exact tuple。
6. additive SHADOW 完成后，source delivery 先以 repo-owned ephemeral fixture 验证
   homogeneous stop-all/proof-lease gate 和 exact allowlisted Codex SDK aggregate 的
   isolated `ENFORCED` contract；真实 deployment/controller cutover 与首个实际
   enrollment 是后续独立授权的 activation gate。

MVP-A 明确不要求：

- 建设通用企业 Event Sourcing/event bus 平台。
- 一次迁移所有历史 Task 或删除现有 status/API。
- Session transfer、跨 Worker 自动迁移、provider 自动切换或 admin logical close。
- Physical Worker claim/reclaim 自动化或 Worker-generated first-boot remote ID。
- Claude、Codex app-server、Gemini、LangGraph Worker v1/adapter。
- mixed-version rolling Java rollout 或 database-level stale legacy-writer guard。
- 建设通用 Actor/Supervisor runtime；第一阶段只实现 Worker-scoped Sentinel 接口和
  Codex SDK adapter。
- 在架构阶段冻结每个表、类、线程、磁盘字节数和 UI 细节。

上述 Worker v1、安全围栏、cutover 与兼容映射是 MVP-A 的 normative contract，不是可选
演进建议。

## Deferred Topics

1. Claude Worker lifecycle v1 与三条 Java loop 的 MVP-B Sentinel convergence。
2. Physical Worker first-boot ID generation、enrollment/claim/reclaim credential lane 和旧
   server-generated ID 迁移。
3. admin logical close、Worker permanent loss 与 Session transfer 的 privileged
   authority/call surface。
4. mixed-version rolling Java deployment 的 database-level stale-writer guard。
5. `localStorage` retry draft 的最终 TTL/数量上限；不阻塞 lifecycle owner。
6. 跨所有 Worker 的统一长期 ledger、容量基准和运维自动化。

## Frozen Replan: Delivery Slices

以下顺序已关闭 open questions，但仍需独立复审和 owner 重新批准才形成 implementation
authorization。所有切片在 Slice 8 之前均为 inventory/contract/test 或 `SHADOW`，
不得执行 owner effects；Slice 8 只允许测试进程自己创建和销毁的 isolated fixture，
不授权操作任何现存 Navigator/Worker/controller。

### Slice 0: Contract Freeze

1. 完成 lifecycle direct writer、terminal predicate、scheduler/reconnect trigger、
   token/registration/receipt resource 和 compatibility surface inventory。
2. 冻结 cross-surface status mapping、cleanup applicability、Worker v1 contract、
   Worker lifecycle module/SPI direction、canary provider list、逐 aggregate proof
   reference、effect/proof-loss linearization、availability/conflict precedence、writer
   fence/rollback floor。
3. admin logical close、Claude MVP-B、rolling mixed-version 和 Worker-generated identity
   明确 defer。
4. 固定 public receipt-disabled compatibility、legacy `termination_operations` mapping、
   lifecycle credential/expected-identity/expected-mode/expected-binding fence、provider
   Task ID disposition recovery、SHADOW wire 和 exact dispatch dedupe。
5. 列出 review 要求的 failure-first test cases，但不新增 schema 或产品实现。

Gate：已满足。open questions 已清零，Round 7 独立复审通过且 owner 已将本文恢复
`APPROVED`；Source Slice 0 可开始，后续切片仍须满足各自前置 gate。

### Slice 1: Task Owner SHADOW Foundation

1. 只增加 Task fact/inbox/snapshot/outbox/ownership metadata 和纯 reducer；不同时建立
   全量 Worker/Session 平台。
2. 提供 forward/rollback SQL、生产 pre-apply contract、schema readiness 和
   shadow-enrollment fail-closed gate。
3. legacy writers 继续 authority；shadow owner 只记录 proposed snapshot/effect/parity，
   所有 effect 必须被硬性抑制。

Gate：incremental/full recompute 等价，duplicate/out-of-order/late fact 无重复 proposed
effect，migration/validate/readiness 通过。

### Slice 2: Terminal Security and Cleanup SHADOW

1. 将 authorization tombstone 固定为 canonical terminal 同事务 safety fence。
2. terminal transaction 冻结 tombstone、token、compatibility projection、receipt 和
   derived-registration participant 的 `REQUIRED/NOT_APPLICABLE` plan；异步 handler
   只处理 required cleanup。
3. 覆盖 terminal/tombstone crash window、未签发 token、registration 不存在、receipt
   disabled/absent、partial failure 和 Java restart。

Gate：不存在 terminal-without-tombstone projection；shadow cleanup plan 与 legacy
行为差异全部可解释，仍不执行 owner effect。

### Slice 3: Codex SDK Termination SHADOW

1. 将 BUG-036 Worker evidence、termination operation、same-request-ID reconciliation
   和 typed projection 规范化输入 Task owner。
2. Codex native direct writers 仍执行 legacy effect；owner 只做 shadow parity。
3. 固定 `ACCEPTED/ACK/AMBIGUOUS/CONFIRMED/canonical terminal/cleanup complete` 以及
   `ABORTED/CANCELED/CANCELLED` 的 surface mapping。
4. shared `RuntimeTaskClosureProvider` 必须 exact 支持
   `CodexTaskService.CODEX_BIZ_PROVIDER_TYPE`，并为 `codex-biz-worker` 跑通 typed
   readiness、terminate、same-request reconcile；不能只因 enrollment allowlist 命中就
   假设 provider adapter 支持。

Gate：termination、receipt 和 cleanup parity 无 unexplained diff；provider invocation
count 在 receipt-enabled same request replay 为一次，在 BUG-035 receipt-disabled 两次
one-shot baseline 为两次。

### Slice 4: Worker Sentinel SHADOW

1. 实现 provider-neutral Sentinel scheduling/lease/fencing core 和 Codex
   probe/lifecycle-stream/inventory adapter；不修改 Claude loop。
2. Codex Node Worker 实现已冻结的 identity/epoch/state-generation、inventory、
   Worker-level event cursor、monotonic ACK、专用 lifecycle auth、required expected-ID
   /mode/binding headers、exact query SSE/abort JSON/dispatch-status wire、
   ownership-mode-bound SHADOW context、exact dispatch dedupe、one-use capability
   ordering 和 durability failure contract。
3. startup、timer、stream-loss 和人工 probe 只生成 reconcile intent；Sentinel
   shadow 记录 proposed gate/reconnect，不改变 canonical state。

Gate：Codex SDK 的 Java restart、Worker restart、content-stream-only loss、lifecycle
stream loss、epoch mismatch、state reset、coverage gap、stale lease/takeover 和
no-storm tests 全部通过。

### Slice 5: Session Lane and Offline Gate SHADOW

1. 覆盖所有 Task create/resume ingress，验证 foreground lane reservation 与 Task
   acceptance 的原子性。
2. shadow 计算 offline pre-accept rejection、post-send ambiguity、frozen lane
   occupancy 和 reconnect 后逐 Task gate。
3. 继续由 legacy path 执行实际 command；owner shadow 不能独立拒绝或发送 command。
   只有通过 Worker v1 auth/readiness preflight 后，legacy request 才附加 observational
   `SHADOW` context；context gap 使 parity 失败，不能伪装为 owner decision。

Gate：所有 ingress parity 和并发 test 通过，未发现可绕过的第二 foreground Task。

### Slice 6: End-to-End SHADOW Parity

1. 串联 Worker v1 fact -> Sentinel -> Task owner -> terminal security/cleanup ->
   compatibility/typed reconciliation -> Session lane。
2. 对 `codex-worker` 与 `codex-biz-worker` 分别生成 parity 报告；首个 canary 固定选择
   `codex-biz-worker` 的一个 exact tenant/Worker tuple，`codex-worker` 不因共享 runtime
   自动 enrollment。
3. SHADOW create/resume/termination/final-result 都携带完整 lifecycle context；验证
   lifecycle durability failure 不改变 legacy effect，但一定产生 parity blocker，owner
   effect count 恒为零。
4. exact `codex-biz-worker` typed readiness/terminate/reconcile 与
   `codex-worker` parity 分别验证；SDK interaction response 明确 N/A，不能用 Java
   `/respond` client 的存在伪装 Worker route coverage。
5. 所有 unexplained diff、provider invocation count、cleanup applicability、public
   receipt-disabled matrix、legacy operation mapping 与 status mapping 必须归零/闭合。

Gate：完整 vertical chain 在 SHADOW 下零 owner effect，且全部 failure-first tests 通过。

### Slice 7: Cutover Gate Implementation and Ephemeral Rehearsal

1. schema 已由部署流程预应用并通过 fail-closed readiness；warning-only startup runner
   仅作幂等补充。
2. 所有 canary-provider direct writers 已识别 ownership mode；owner-capable target
   binaries 对 protocol/build mismatch fail closed。
3. Codex termination、terminal safety、Sentinel、offline gate、Session lane 和 cleanup
   形成完整 shadow vertical chain。
4. 实现 DB proof lease、逐 Worker/Session/Task reference、observer/drift quarantine、
   claim/effect-authorization linearization 和 enrollment/projection checks；用
   repo-owned ephemeral controller/process fixture 覆盖 disable/scale-zero、late
   relaunch、queued job/manual launcher 模拟、proof expiry 及 loss/CAS 两种 race
   ordering。fixture 不使用共享 port、现存 service unit、真实 deployment controller
   或业务 Task。
5. fixture 从 admission drain 到全部 isolated Worker/Session/Task reference 和
   unfinished outbox 归零持续维持 lease；OPEN Session/ENFORCED Worker 阻止提前
   release，post-enrollment relaunch 必须令既存 aggregate quarantine，而非只阻止新
   enrollment。
6. 首次 enforcement 后 rollback floor 固定为仍识别 ownership/terminal safety fence 的
   owner-capable binary；不得回滚 legacy binary。
7. 所有 required Codex Worker runtime/contract suites、Open SDK published contract
   suites 和 affected Java suites 通过。

Gate：在此之前没有任何 aggregate 可以进入 `ENFORCED`，完成后也只允许 Slice 8 的
isolated test aggregate；没有真实 controller evidence 时 deployment enrollment 始终
disabled。

### Slice 8: Isolated End-to-End ENFORCED Contract Test

1. 自动化测试在 test process 自己创建、可销毁的 DB/schema、fake deployment
   controller 和 Codex SDK Worker fixture 中，只接管新的 allowlisted
   `codex-biz-worker`/Session aggregate；不得连接共享 8112、现存 Worker、真实
   SIM/TMS 或任何 production/shared deployment。
2. enrollment transaction 验证 schema、cluster writer generation/protocol、Worker v1
   capability、lifecycle credential、exact identity、continuous exclusivity proof、
   `terminationRequestReceiptEnabled=true` 和完整 vertical-chain readiness。
3. 紧急止损只能暂停新 enrollment或进入 fail-closed read-only；已 enforced aggregate
   继续由 owner 收敛，不允许旧 binary 接管。test 必须覆盖 post-enrollment proof loss
   quarantine 和 proof 恢复后收敛。

Gate：source-level must-pass AC 和 affected validation 通过后才可进入
`READY_FOR_SIGNOFF`；该状态只表示 deployable source contract 可签核，不表示真实
cutover/first deployment enrollment 已执行。

### Separately Authorized Activation Gate

以下不属于当前 Ultra source implementation 授权：

1. 在 exact target environment 枚举并技术性 disable/scale-to-zero/mask 真实
   systemd/Kubernetes/Docker/supervisor/CI/timer/manual launch sources。
2. 停止/启动真实 Navigator/Worker、执行真实 controller late-relaunch drill、激活
   production/shared writer generation。
3. 创建第一个非 fixture `ENFORCED` aggregate 或运行 live SIM。

执行前必须由用户另行确认 exact environment、process/controller ownership、变更窗口、
回滚 floor、脱敏证据和最大一次尝试。没有该授权时，部署 gate 保持
`ENFORCED_DISABLED_PENDING_ACTIVATION_EVIDENCE`；fixture evidence 不能冒充真实 proof。

## Acceptance Criteria

- [ ] AC-1: owner reducer 对相同 normalized fact set、policy version 和 evaluation tick
  产生相同 snapshot/effects；incremental reduce 与 full recompute 等价，participant
  不能直接修改 enforced canonical lifecycle。
- [ ] AC-2: 正常 `ACCEPTED -> CANCEL_REQUESTED -> TERMINAL` 只能由 exact correlated
  Worker terminal evidence 推进；ACK、普通文本、局部日志、断连、超时和 Task 404
  均不能单独形成 terminal。
- [ ] AC-3: receipt-enabled 的同一 client request ID termination/reconciliation 保持
  幂等；重复查询、重启、fact replay 和 reconnect 不产生第二次 provider termination、
  新 operation 或新 request ID 绕过。public receipt disabled 时严格保持 BUG-035：
  同 ID 的两次 termination 是两次 one-shot provider attempt，reconciliation 始终
  `AMBIGUOUS/unavailable`，owner ledger 不得 suppress provider 或伪装 availability。
- [ ] AC-4: Worker 最终 evidence 到达时 reconciliation 从
  `ACCEPTED/IN_PROGRESS` 收敛到可信 `TERMINAL`；最终 evidence 缺失或冲突时有界收敛为
  `REJECTED/AMBIGUOUS`，而非永久 accepted 或伪 terminal。
- [ ] AC-5: authorization tombstone 与 canonical terminal status/
  `canonicalTerminal=true` 同事务提交；typed `TERMINAL` 还要求冻结 cleanup plan 中
  所有 `REQUIRED` participant complete，其他 participant 明确 `NOT_APPLICABLE`。
  当前 derived active-registration participant 必须是
  `NOT_APPLICABLE(DERIVED_PROJECTION_NO_RESOURCE)`，compatibility projection 完成后其
  audit value 为 false。
- [ ] AC-6: cleanup handler 在重复投递、部分失败和 Java 重启后最终完成且不重复产生
  不安全副作用；cleanup pending 期间 Task 仍占用 Session foreground lane。
- [ ] AC-7: Worker 断连只产生 observation unknown/offline freeze；Worker-dependent
  mutation 稳定拒绝且 server 不保存 pending command，已 canonical terminal 的本地
  cleanup 仍可继续。
- [ ] AC-8: 单 Task stream 断开不会立即把整个 Worker 置为 offline；Worker Sentinel
  统一 probe/backoff/reconnect，按 exact identity/epoch/inventory/cursor 对账并避免
  N-Task endpoint storm。
- [ ] AC-9: reconnect、startup recovery 和人工 probe 不创建新的 Task、termination、
  permission 或 input command；只有同 `dispatch_id` 且 durable phase=`PREPARED` 的
  initial Task transport continuation 可执行一次，`EFFECT_STARTED/UNKNOWN` 只读对账，
  termination 只有 fenced dispatch status exact `NOT_FOUND` 且 capability 仍有效时才可
  做同 binding technical redelivery；任一 durable phase 后永不再次调用 provider。
- [ ] AC-10: 同一 Session 并发创建/恢复时最多一个 foreground Task；不能借
  `CANCEL_REQUESTED`、offline、cleanup pending、provider status 或新 request ID 绕过。
- [ ] AC-11: MVP-A 不存在 admin logical-close/permanent-loss authority、fact reducer
  branch 或新增 endpoint；现有 task-owner provider `force` wire/permission/termination
  语义不变，且不能直接形成 canonical terminal。
- [ ] AC-12: `LEGACY -> SHADOW -> ENFORCED` 单调生效；旧 status/API/DTO 和相同 request
  ID wire semantics 保持兼容；receipt-disabled one-shot/reconciliation matrix 保持
  BUG-035，live ENFORCED receipt configuration drift 只允许在 operation acceptance 前
  stable reject；未接管历史 aggregate 不被批量迁移或隐式 repair。
- [ ] AC-13: additive migration、production pre-apply、schema readiness、validate 和
  rollback drill 通过；schema 未 ready 时禁止 enrollment，warning-only startup
  migration 不能解除 gate。
- [ ] AC-14: 历史 Task `20260730-0e01` 和 sibling SIM/TMS workspace 保持零 mutation；
  测试/日志/evidence 不包含 token、credential、Authorization、prompt、模型回复或业务
  数据。
- [ ] AC-15: canary Worker v1 contract 明确定义并实测 identity triple、
   exact query SSE/abort JSON/dispatch-status endpoint/field/duplicate phase shape、
  `JCS_SHA256_V1` binding（包含 exact `ownership_mode`）+ expected mode/digest reread、
  durable provider Task identity、phase-specific `never_accepted_proof`、atomic one-use
  capability、state-generation reset、fenced inventory、Worker-level cursor coverage、
  monotonic ACK、专用 lifecycle auth、required expected-ID/mode/binding headers、
  SHADOW/ENFORCED context、exact dispatch dedupe、persist-before-effect failure 和 version
  negotiation；旧 epoch/coverage gap、不鉴权或 identity/mode/binding fence mismatch
  不产生 terminal 或解冻。
- [ ] AC-16: source-level ephemeral fixture 证明 homogeneous non-rolling cutover gate、
  DB proof lease 从 drain 持续覆盖 Worker/Session/Task exact references 与 unfinished
  outbox、OPEN Session/ENFORCED Worker 不随 Task cleanup 提前 release、
  post-enrollment late-relaunch/proof expiry 使既存 aggregate quarantine、前一代
  owner-capable conditional write 被 generation fence 拒绝。真实 controller
  disable/stop/start/drill 和首个实际 enrollment 是 separately authorized deployment
  gate；缺真实 evidence 时 `ENFORCED_DISABLED_PENDING_ACTIVATION_EVIDENCE`。
- [ ] AC-17: internal operation、Task canonical projection、typed reconciliation、A2A、
  Java DTO、Open API 和 UI 的 `ABORTED/CANCELED/CANCELLED` 与
  `AMBIGUOUS/CONFIRMED` mapping 完整；legacy
  `termination_operations.status/dispatchState` 双向映射无未分类 surface。
- [ ] AC-18: `session-module -> navigator-spi <- addon/business participant` 依赖方向无
  Maven cycle；provider adapter、cleanup participant 和 Sentinel effect 不得直接写
  enforced canonical lifecycle。
- [ ] AC-19: Navigator 已接受但 Worker exact definitive never-accepted 的 Task 以
  `FAILED + WORKER_PRE_EFFECT_REJECTION + NOT_STARTED` 收敛，并完成同一
  tombstone/cleanup/lane release；proof 必须是 allowlisted durable
  `ownership_mode=ENFORCED + REJECTED/PRE_EFFECT +
  never_accepted_proof=true`，并由 expected mode + `safe_binding_digest` fenced
  dispatch reread/fact exact 验证。store unavailable、原 command response、stale
  attempt、timeout、response loss、404、inventory absence 或 identity/mode/binding
  mismatch response 仍 fail closed，不产生 terminal。
- [ ] AC-20: 同一 Task dispatch 的 outbox/HTTP redelivery 复用同一 `dispatch_id`；
  `delivery_attempt` 持久递增且单 in-flight，late response 不越过较新 durable
  disposition；crash-before-effect、`EFFECT_STARTED` response loss、Java/Worker restart
  均不产生第二次 provider effect。changing dispatch/Task/termination/client request ID
  不能成为自动 replay。
- [ ] AC-21: published `navigator-open-sdk` receipt-enabled/disabled typed fixtures、
  availability/replay flags、unknown enum 与 same-request semantics 全部通过；首次
  ENFORCED canary 在 receipt disabled 时以
  `TERMINATION_REQUEST_RECEIPT_REQUIRED_FOR_CANARY` 拒绝 enrollment，而非修改 public
  DTO/wire。
- [ ] AC-22: receipt enabled 时 public receipt、owner intent/operation 和 effect outbox
  binding 同 transaction；persistence failure 返回
  `REJECTED + TERMINATION_REQUEST_RECEIPT_PERSISTENCE_FAILED`，receipt persisted/
  reconciliation available 为 false，provider invocation count 为零，cleanup N/A
  不能掩盖失败。
- [ ] AC-23: `RuntimeTaskClosureProvider` 对 exact `codex-biz-worker` 支持 typed
  readiness、termination 和 same-request reconciliation；首个 allowlist 的完整 public
  vertical lane 不返回 `RUNTIME_TASK_PROVIDER_UNSUPPORTED`。Codex SDK
  permission/input response 明确 N/A。
- [ ] AC-24: proof lease expiry、controller/queued deployment/manual launcher drift 在
  任一 Worker/Session/Task reference 期间停止新 unsafe effect并 quarantine 既存
  aggregate；outbox claim 与 proof-loss 竞争按同一 row-lock/CAS 线性化，claim 本身不
  授权 provider call。只允许 terminal+tombstone/token-deny 等单调 local safety action，
  永不回退 legacy。
- [ ] AC-25: create/resume accepted `PREPARED` disposition 与唯一
  `provider_task_id` 在同一 Worker-local durable record 中提交；primary、duplicate、
  status、inventory、SSE 和 terminal facts 使用同一 ID。Java 在业务 SSE 前持久化该
  binding，response loss 后可只读恢复；ID 缺失/冲突时 termination capability/provider
  invocation count 为零。
- [ ] AC-26: dispatch status 缺 expected mode/digest/version 时使用冻结的 400 code。
  已存在相同 dispatch record、但 command context 或 status expected-mode 与 durable
  mode 不同时，command/status 两入口及 `SHADOW -> ENFORCED`、
  `ENFORCED -> SHADOW` 两方向都必须 exact 返回
  `409 LIFECYCLE_OWNERSHIP_MODE_MISMATCH`；只有 mode exact 匹配而 version 或其余
  route-specific digest input 不同时，才使用各自冻结的 409 version/binding code。
  所有 negative 均不回显 actual mode/digest/provider Task ID、不新增 owner fact，
  cross-mode request 后 provider invocation count 相对已有 record baseline 不增加。
  同 dispatch/body 只改变 mode 时 digest 必须不同；匹配成功时 disposition 的
  mode/digest/proof/provider ID required/null matrix 全部通过 schema validation。
- [ ] AC-27: Worker、Session、Task proof reference 分别通过 transaction 建立和冻结的
  predicate 释放；只有三类 reference 全为零且无 unfinished outbox/claim/authorization
  才能释放 proof。Worker retirement 未实现时不得伪造 release；OPEN Session 始终持有
  reference。
- [ ] AC-28: proof-loss 与 claimed-yet-not-called effect 的两种顺序均可重复验证：
  loss 先 commit 时 provider count 为零；exact outbox
  `effectState=EFFECT_STARTED` authorization 先 commit 时最多调用一次且后续 loss
  禁止 retry。
- [ ] AC-29: snapshot 只使用 frozen `availability/conflictState`。legacy
  `ONLINE/SUSPECTED/RECOVERING_RECONCILIATION/STORAGE_PRESSURE_*` 不进入 target
  schema/reducer；offline、storage、configuration、proof loss、state loss 与 evidence
  conflict 并发组合按冻结 precedence 得到唯一 pair，最高 blocker 清除后显露下一
  active blocker，incremental/full recompute 完全一致。

## Contract, Data and Security Constraints

- API/event compatibility:
  - public typed termination/reconciliation DTO 与 stable reason semantics 保持兼容；
    内部新增 fact/effect 类型不得迫使现有 SDK/CLI 同步升级。
  - receipt disabled 时必须保持 BUG-035 的
    `AMBIGUOUS + TERMINATION_REQUEST_RECEIPT_DISABLED +
    requestReconciliationAvailable=false`；owner internal ledger 不得改变 public
    availability/replay flags，也不得 suppress 同 request ID 的新 one-shot provider
    attempt。首次 canary 可要求该 feature enabled；live enforced aggregate 的配置 drift
    以 receipt-required command gate 拒绝新 termination，不回退 legacy effect。
  - receipt enabled persistence failure 使用现有 typed DTO 返回 stable `REJECTED`，
    不新增/改变 public field；不得以 raw 500 后继续 dispatch。
  - `CANCEL_REQUESTED` 继续作为 compatibility projection，不成为 canonical terminal。
  - 本文已批准的 Worker lifecycle v1 是 additive internal Worker contract；如实现发现
    必须改变 public SDK wire shape、删除旧 status、采用 breaking Worker shape 或扩展到
    本文未列 provider/runtime，设置 `NEEDS_REPLAN`。
- data/migration:
  - schema 只做 additive change，并同步提供 `docs/migration` forward/rollback SQL、
    production pre-apply/validate 测试和索引/唯一约束。现有 startup migration runner
    只允许作为幂等补充，失败时 owner readiness/enrollment 必须 fail closed。
  - 不回填或重算历史 active/ambiguous Task；shadow facts 与 legacy rows 必须可明确
    区分 provenance。
  - shadow 阶段可停用并回滚代码；出现首个 enforced aggregate 后，紧急止损只能暂停新
    enrollment、保留 schema 并 fail-closed，不能退回 legacy authority。首次切换采用
    homogeneous deployment；所有旧 deployment controller/restart source 必须先禁用，
    actual instances 再归零，并以 proof lease 覆盖到全部 Worker/Session/Task reference
    和 unfinished outbox 归零。若旧 binary 仍在运行、旧 unit 可自动重启、proof
    过期或 launch inventory 不完整，不得开启/继续普通 enforcement effect。
- effect safety:
  - lifecycle outbox 只承载 owner 产生的 idempotent technical effect，不保存用户离线
    command。
  - Task dispatch technical redelivery 必须复用 durable exact `dispatch_id`、递增
    `delivery_attempt` 并保持单 in-flight；Worker 在 provider effect 前持久
    acceptance/effect phase。`EFFECT_STARTED` outcome unknown 时只对账、不重放。
  - Navigator outbox claim 不等于 provider-call authority；每次外部 effect 必须以
    proof/reference/outbox 同锁事务提交 exact `EFFECT_STARTED` authorization。proof
    loss 与该 commit 只有本文冻结的两个顺序结果，不能在 handler 中以非事务
    “再次检查”代替。
  - proof release 必须同时满足 Worker/Session/Task reference 全部释放且无 unfinished
    outbox/claim/authorization；Task cleanup count 为零不是充分条件。
  - receipt-enabled termination 的 public receipt、owner intent/operation 和 effect
    outbox binding 同事务；commit 前不允许 provider effect。
  - authorization tombstone 不是 cleanup outbox effect；它与 canonical terminal 同
    transaction，是后续异步 effect 延迟/失败时仍 fail-closed 的安全围栏。
  - provider command、token revoke、compatibility projection 和 receipt update 必须
    使用独立 idempotency key/checkpoint；persist-before-effect。
- permissions/secrets:
  - admin logical close 与 Worker permanent-loss decision 明确延后；MVP-A 不得实现
    相关 fact/API，也不得复用普通 task-owner `force`。
  - 不记录原始 Worker payload、Authorization、token、credential、prompt、response、
    workspace/business content；诊断只使用稳定 code、匿名 correlation 和计数。
  - Worker lifecycle endpoint 与携带 lifecycle context 的 command 必须使用现有 Codex
    runtime endpoint Bearer credential 且 token non-empty；未配置、未绑定、缺失或无效
    都 fail closed，不允许 legacy unauthenticated fallback。

## Test and Evidence Obligations

| Item | Classification | Required Validation | Required Evidence |
|---|---|---|---|
| reducer/ownership | must-pass | deterministic/property, duplicate/out-of-order/late fact, availability/conflict precedence and legal-combination matrix, incremental/full recompute parity | exact focused command and test count |
| termination authority | must-pass | accepted→cancel→terminal, ACK then final result, ACK/text/timeout/404 negative tests | failure-first and passing outputs |
| reconciliation/idempotency | must-pass | receipt-enabled same ID repeated reads/replay/reconnect provider count exactly one；receipt-disabled same-ID termination provider count exactly two and reconcile unavailable | integration assertions |
| terminal safety/cleanup | must-pass | terminal+tombstone atomic crash/race, participant required/not-applicable, partial failure/retry/restart | transaction/JPA/outbox fault-injection evidence |
| offline and Sentinel | must-pass | stream-only loss, Worker-wide loss, backoff cap/jitter, no storm, identity/epoch/state-generation mismatch, coverage gap, inventory/cursor resume | controlled adapter/integration tests |
| Session single-flight | must-pass | concurrent create/resume/cancel/frozen/cleanup-pending cases | concurrency test output |
| compatibility and rollout | must-pass | all-surface mapping, shadow zero-effect, exact Codex allowlist, homogeneous proof-lease gate and monotonic ownership in ephemeral fixture | contract/parity report plus fixture drill |
| migration | must-pass | clean forward, idempotent re-run, production pre-apply/`validate`, schema-not-ready reject, rollback before enforcement | migration test output |
| affected Java lane | must-pass | affected module tests, then one final launcher reactor | exact Maven commands/results |
| Codex Worker v1 auth/fence lane | must-pass before SHADOW parity | missing/unconfigured/invalid credential, required expected-ID/mode/binding headers, mismatch/coverage ordering, no fact/effect on negative response | Node contract outputs without credential values |
| Codex Worker v1 dispatch lane | must-pass before isolated canary | exact query SSE/abort JSON/dispatch-status phase wire, ownership-mode + expected-binding mismatch, SHADOW/ENFORCED cross-mode negative, provider Task ID durable recovery, mode/proof/digest required-null matrix, JCS digest fixtures, atomic capability, delivery-attempt fencing, dedupe/effect phase, persist-before-effect plus full tests/typecheck/build | exact commands/results, content type/envelope and provider invocation count |
| Open SDK published contract lane | must-pass | BUG-035 receipt-enabled/disabled typed fixtures, availability/replay flags, enum fallback, same-request semantics | exact SDK test command/result; no SDK publication |
| receipt admission | must-pass | enabled receipt/owner intent/outbox atomic success and injected persistence/commit/config-drift failure before provider effect | typed field assertions and provider count zero |
| codex-biz typed closure | must-pass | exact provider supports/readiness/terminate/reconcile; legacy provider non-regression; SDK interaction response N/A | focused Java/Open API integration output |
| legacy writer exclusivity source gate | must-pass before isolated canary | ephemeral controller inventory/disable, per-Worker/Session/Task proof references, proof lease through all references/outbox, post-enrollment relaunch/expiry quarantine, proof-loss/effect-authorization race ordering | test-owned fixture evidence |
| real writer exclusivity activation | separately authorized deployment gate | exact deployment controller disable/scale-zero, stop/start, continuous proof and real late-relaunch drill | sanitized target controller/process evidence; fixture evidence不可替代 |
| deferred authority absence | must-pass | no admin/permanent-loss reducer/API; existing task-owner force contract unchanged | architecture/endpoint contract tests |
| live SIM/deploy/release | non-goal for implementation | not run without later explicit authorization | explicit omission record |

Minimum regression scenarios include:

1. `ACCEPTED -> CANCEL_REQUESTED -> TERMINAL`。
2. Worker ACK 后最终 result 推进。
3. reconciliation 从 `ACCEPTED/IN_PROGRESS` 收敛到 `TERMINAL`。
4. terminal 后 required token revoked、compatibility Task projection completed、optional
   receipt 按 plan 完成或 N/A，derived active registration 为 false。
5. Worker 无最终响应的 timeout/fail-closed。
6. 重复 reconciliation/reconnect 不产生第二次 provider termination。
7. ACCEPTED、ACK、Worker 文本、日志、断连和 404 均不能单独成为 terminal。
8. 单 Task SSE 断开与 Worker offline 的不同投影。
9. Java/Worker 重启后从 last durable cursor 恢复且 effect 不重复。
10. offline command reject/no server queue 与 reconnect 后用户显式新 command。
11. 同 Session 并发请求只有一个 foreground Task。
12. shadow proposed effects 永不执行，enforced canary 才执行。
13. terminal transaction 在 tombstone 前失败/崩溃时不提交 canonical terminal；terminal
    提交后异步 revoke 失败时 tombstone 仍拒绝 capability。
14. 自然终态无 termination、legacy receipt disabled、token 未签发、active registration
    无独立 resource 时，以精确 `NOT_APPLICABLE` 完成 cleanup plan，不永久
    `FINALIZING`；accepted ENFORCED termination 的 missing receipt 和 compatibility
    Task projection 不允许 N/A。
15. stale Sentinel lease、lease takeover、旧 epoch 晚到 fact、state-generation reset
    和 cursor coverage gap 均 fail closed。
16. Codex SDK 覆盖 Java restart、Worker restart、Task content-stream-only loss、
    Worker lifecycle-stream loss 和 durable replay。
17. schema 未预应用、production-validate fixture 失败、旧 Java instance/proof fixture
    未证明全部停止、target build/protocol mismatch 或 active writer
    generation/instance registration 不一致时禁止 isolated enforced enrollment。
18. `codex-worker`、`codex-app-server-worker`、Claude 与其他 provider 无法被 MVP-A
    首次 allowlist 误接管；`codex-biz-worker` 必须逐 exact tenant/Worker tuple
    enrollment，并跑通 typed readiness/terminate/same-request reconcile，不能返回
    `RUNTIME_TASK_PROVIDER_UNSUPPORTED`。
19. Worker lifecycle store 在 Task accept、termination ACK 或 terminal fact 前 fsync
    失败时不先执行对应 provider effect；已运行 Task 中途 store failure 使 operation
    保持 `AMBIGUOUS` 并按 storage blocker 投影，不伪 terminal或创造新 state enum。
20. 前一代 owner-capable instance 使用旧 writer generation 的 fact/snapshot/outbox
    conditional write 被拒绝；模拟旧 binary 仍存活或归属未知时 cutover gate 不允许
    enrollment。
21. lifecycle endpoint 在 Worker token 未配置、Navigator credential binding 缺失、
    Authorization header 缺失/无效时分别返回冻结的 stable code，且不读取/ACK facts、
    不调用 provider。
22. inventory/events/ACK 缺 expected identity header、physical ID mismatch 或 state
    generation mismatch 时 definitive reject；SSE 不先建立、Navigator 不消费 response
    facts。
23. `SHADOW` create/resume/termination/final-result 使用完整 v1 context；lifecycle
    fsync 失败不改变 legacy effect/result，但 owner effect count 为零、parity 明确失败且
    aggregate 不能 enrollment。
24. Navigator 已接受 Task 后，只有 fenced dispatch status/fact 重读到 allowlisted
    durable `REJECTED/PRE_EFFECT + never_accepted_proof=true` 时，reducer 才产生
    `FAILED/WORKER_PRE_EFFECT_REJECTION/NOT_STARTED`，terminal tombstone 与 cleanup
    完成后 Session lane 释放。
25. never-accepted 的原 command response、store-unavailable、task/dispatch/Worker/
    generation mismatch、generic 4xx/5xx、timeout、response loss、Task 404 或 inventory
    absence 均不形成 terminal；attempt A delayed store-failure negative 不得越过 attempt
    B durable acceptance。
26. initial dispatch 覆盖 crash-before-binding、`PREPARED` 后 crash、
    `EFFECT_STARTED` 后 response loss、result 后 response loss；同 dispatch ID
    redelivery 的 provider invocation count 至多一次，不生成新 business command；
    query/abort 各 phase 的 HTTP status/content-type/SSE/JSON envelope 与
    dispatch-status reread exact 断言。
27. receipt disabled 时，同 request ID 的两次 public termination 都返回真实单次
    outcome且 provider invocation count 为二；重复 public reconciliation 始终返回
    BUG-035 的 `AMBIGUOUS` matrix，owner ledger 不能 suppression 或把 flags 改为 true，
    canary enrollment 被稳定拒绝。
28. legacy `termination_operations` 覆盖
    `ACCEPTED/PENDING`、`RUNNING/UNCONFIRMED`、
    `CANCEL_REQUESTED/ACKNOWLEDGED`、`REJECTED/REJECTED` 与
    `ABORTED/OBSERVED` 双向映射；无 exact evidence 的旧 `ABORTED` 不成为 terminal。
29. ephemeral cutover fixture 从 admission drain 到全部 Worker/Session/Task proof
    reference 与 unfinished outbox 归零持续 proof lease；first enrollment 后模拟
    systemd/Kubernetes/supervisor/timer/job/queued deployment/manual launcher relaunch
    或 lease expiry，立即阻止新 effect并 quarantine 既存 aggregate。
30. receipt enabled 时分别注入 receipt insert、owner intent/outbox write 和 transaction
    commit failure；typed response 为
    `REJECTED/TERMINATION_REQUEST_RECEIPT_PERSISTENCE_FAILED`、receipt persisted/
    reconciliation available false、provider invocation count 为零。
31. ENFORCED termination v1 覆盖 capability validate 后 lifecycle fsync failure、primary ACK
    response loss、exact duplicate和相同 operation 搭配不同 dispatch：合法 duplicate
    返回 prior 202 disposition且 provider count 一，replay mismatch 409，store failure
    不先消费 capability或调用 effect。
32. `safe_binding_digest` Java/Node RFC 8785/SHA-256 conformance fixtures覆盖 key order、
    Unicode、null、query payload、termination capability digest 和
    `ownership_mode`；同一 payload 的 SHADOW/ENFORCED digest 必须不同。日志/store 无
    原始 prompt、path、credential 或 capability。
33. SDK Worker 对 Java `/respond` 不做假覆盖；Navigator 在 dispatch 前返回
    `WORKER_INTERACTION_RESPONSE_UNSUPPORTED`。mutating termination-reconcile 携带 v1
    context 时在 capability/effect 前返回 `LIFECYCLE_COMMAND_NOT_APPLICABLE`。
34. source tests 只使用 repo-owned ephemeral fixture；没有 separately authorized
    target controller evidence 时，部署状态保持
    `ENFORCED_DISABLED_PENDING_ACTIVATION_EVIDENCE`。
35. create/resume 的 durable disposition phase matrix逐项断言
    `ownership_mode/provider_task_id/safe_binding_digest_version/safe_binding_digest/
    never_accepted_proof` required/null 值；accepted `PREPARED` 后 response loss，Java
    仅用 exact mode+binding dispatch-status 恢复同一 provider Task ID，再允许创建
    termination capability。
36. dispatch-status 缺 expected mode/digest、错误 digest version、同 mode 但同一
    dispatch 使用不同 create/resume/abort binding 时，分别返回冻结的 400、version
    mismatch 409、binding mismatch 409 code。同一 dispatch/body 已写 SHADOW record
    后，以 ENFORCED status reread 与 command 访问，以及已写 ENFORCED record 后以
    SHADOW status reread 与 command 访问，四种 cross-mode case 均必须 exact 返回
    `409 LIFECYCLE_OWNERSHIP_MODE_MISMATCH`，不得返回 binding mismatch；错误在
    digest comparison、duplicate handling、capability consumption、fact ingestion
    和 provider effect 前完成。cross-mode request 后 provider count 相对已有 record
    baseline 不增加，negative response 不返回 actual mode/digest/provider Task ID、
    不新增 owner fact；只有匹配 route-specific JCS fixture 才返回 200 prior
    disposition。
37. proof-reference fixture 分别覆盖 Worker enrollment、Session OPEN、Task active/
    terminal-cleanup、Session CLOSED 与 Worker retirement N/A；最后一个 Task cleanup
    不能释放仍被 OPEN Session/ENFORCED Worker 引用的 proof，unfinished outbox 也阻止
    release。
38. outbox 已 `CLAIMED` 但未 authorization 时并发 proof loss：loss 先 commit 的 provider
    count 为零；outbox `effectState=EFFECT_STARTED` 先 commit 时 exact provider effect
    最多一次，随后 loss quarantine 且 crash/response loss 不重投。两个测试必须使用
    同一 proof row lock/CAS，而非 timing sleep 推断顺序。
39. reducer/adapter test 证明 legacy
    `ONLINE/SUSPECTED/RECOVERING_RECONCILIATION/STORAGE_PRESSURE_*` 只产生规范化
    fact或 Sentinel ephemeral backoff metadata，不进入 snapshot/schema/API；
    `authorityConflict` 字段、bare `QUARANTINED` availability 和未知 target enum 均被
    拒绝。
40. reducer exhaustive/property test 覆盖 offline + storage + configuration + proof loss
    + state loss + evidence conflict 的单项和组合：先按 conflict precedence，再按
    availability precedence得到唯一合法 pair；逐个 clear 最高 blocker 时依次显露下一
    blocker，Task-local conflict 不扩大到 Worker，incremental reduce 与 full recompute
    完全相同。

## Validation Budget and Evidence Sufficiency

- assurance_level: `elevated`；原因是本项改变 terminal authority、task-token revoke、
  public typed reconciliation 的内部决策链和跨 runtime replay。
- lightweight_validation (`<5m`):
  - selected reducer/adapter/service tests、migration static checks、`git diff --check` 和
    changed-surface secret scan；相关编辑后可重复执行。
- medium_validation (`5-30m`):
  - affected Java module lane，候选完成后一次
    `mvn test -pl launcher -am`。
  - Worker v1 contract 是 canary 前置，因此必须运行
    `tools/codex-agent-worker` 的 `npm test`、`npm run typecheck` 和 `npm run build`。
    Python Worker 未变化且属于 MVP-B，不是本 canary 前置。
  - public receipt compatibility 是不可变约束，因此必须运行
    `navigator-open-sdk` typed contract tests 与共享
    `RuntimeTaskClosureService/RuntimeTaskTypedContractService` affected tests；预期不需要
    变更或重新发布 SDK。
  - final launcher reactor 预计 10–25 分钟，依据 BUG-036 同仓 2976-test reactor；
    最多两次，第一次产品失败后只先跑最小 affected subset，修复后再执行一次 final。
  - repo-owned ephemeral proof-lease/isolated ENFORCED fixture 预计 10–20 分钟，属于
    source implementation test；最多两次。它不得调用 systemd/Kubernetes/Docker、
    共享 port 或现存 Navigator/Worker。
- expensive_validation (`>30m`):
  - 真实 target controller inventory + disable/stop/start + late-relaunch + first actual
    enrollment rehearsal 预计 30–60 分钟、最多一次；当前 `not-authorized`，必须另行取得
    用户对 exact environment 和 process ownership 的明确授权。
- non_product_failure_stop_rule: 同一 final/contract lane 连续两次因环境、依赖源或其他
  非产品原因失败时停止，不自动第三次重跑；记录 exact blocker 并回到
  `NEEDS_REPLAN`/owner decision。
- large_authority_or_replay_policy: `prohibited-unless-user-approved`。
- full_chain_recommendation_trigger: 只有 source signoff 后准备激活真实 target cutover
  或 live SIM，且 fixture 无法提供 deployment exclusivity evidence 时才建议一次。
- estimated_full_chain_wall_clock: target activation rehearsal `30-60m`、最多一次；live
  SIM 仍需单独估时/授权，不能与 controller rehearsal 打包默认执行。
- full_chain_prerequisites: exact Navigator/Worker process ownership、test-owned new Task、
  deploy/restart 授权和脱敏 evidence boundary。
- ephemeral_fixture_authorization: `included-after-spec-approval`；仅限测试创建/销毁的
  repo-owned fixture。
- real_controller_rehearsal_authorization: `not-requested/not-approved`。
- live_sim_authorization: `not-requested/not-approved`。
- decision_if_not_approved: 使用 focused + affected module + controlled adapter/integration
  evidence进入 source `READY_FOR_SIGNOFF`，同时明确部署 gate 保持 disabled；不执行真实
  controller mutation、live/deploy/release。
- reusable_evidence:
  - BUG-036 的 read-only incident correlation、failure-first tests 和已通过的原始
    termination regression 可作为 baseline；owner 接管改变相关代码后只重跑被影响
    的产品证据。
- minimum_revalidation_radius:
  - reducer/authority/Worker wire/migration 输入改变时，只失效直接依赖该输入的 focused
    与 integration evidence；最终 affected reactor 候选仍须重新运行。
  - 文档、review pointer、evidence 搬运或 test-only 变更不自动使无依赖的产品测试失效；
    必须记录为何复用仍真实。
- stop_when_evidence_is_sufficient:
  - 所有 must-pass AC 有实际通过的自动化映射。
  - affected build/tests 通过，migration/compatibility/parity 无未解释差异。
  - provider/effect invocation count 证明无重复，terminal cleanup checkpoints 完整。
  - isolated ENFORCED fixture 通过，且真实 deployment gate 明确保持 disabled/未冒充
    已验证。
  - 没有 `NEEDS_REPLAN`、secret leak 或未披露残余风险。
- validation_not_required:
  - frontend build（第一阶段无前端变更）。
  - Worker release/package/OBS smoke（本交付不发布；源码 contract tests 必跑）。
  - 所有 provider 全量 live smoke、历史 Task replay、SIM/TMS 业务验收。

## Waiver Policy

- waivable_items:
  - live SIM/真实 activation evidence 不属于 source signoff，作为后续独立授权的
    deployment gate；省略时不得声称已完成实际 cutover/canary。
- authorized_role: project owner。
- non_waivable_guards:
  - terminal authority、receipt-enabled same-request-ID idempotency、BUG-035 disabled
    one-shot compatibility、no business replay、token/compatibility cleanup、
    derived-registration false、authorization tombstone atomicity、Worker v1 contract、
    source proof-lease gate、offline non-terminal、single foreground Task、schema/data
    safety 和 secret boundary。
- required_risk_record: 任何环境导致的省略必须记录 exact blocker、受影响 AC 和为何现有
  evidence 仍足以或不足以进入 signoff；不得把未运行写成通过。

## Risks and Open Questions

- known_risks:
  - shadow 与 legacy 双写期间可能发生 provenance/parity 漂移；未分类 diff 阻止 canary。
  - outbox 至少一次执行可能暴露现有 cleanup handler 非幂等；必须先修 handler 或保持
    shadow，不得以重复容忍掩盖。
  - multi-instance lease/fencing 错误可能形成双 stream owner；split-brain test 是
    Sentinel gate。
  - lifecycle fact retention 和索引可能增加存储压力；第一阶段只实现 protected facts
    的安全最小策略，不扩展完整运维自动化。
  - lifecycle v1 是新的 Worker-local authority store；错误实现 fsync/locking/coverage
    会扩大丢失事实风险，因此 persist-before-effect 与 crash tests 是 canary blocker。
  - homogeneous cutover 依赖真实运维 exclusivity；当前没有 database-level guard 能阻止
    完全不识别 owner schema 的旧 binary。必须持续证明旧 deployment controller、
    restart source 和实际实例从 drain 到所有 Worker/Session/Task reference 与
    unfinished outbox 归零均被约束；无法枚举/禁用、proof 中断或 post-enrollment
    drift 时必须 quarantine 并保持真实 `ENFORCED` activation disabled，升级为
    database-level guard replan。
  - Worker lifecycle auth 复用现有 runtime endpoint credential，避免新增 secret lane，
    但也意味着当前允许空 token 的 legacy 部署不能参与 v1 SHADOW/canary；这是明确的
    readiness blocker，不允许 fail-open waiver。
  - receipt-disabled public caller 无法使用 owner internal facts 做 request-ID
    reconciliation，且相同 ID duplicate 仍可能调用 provider 两次；首次 canary 要求
    receipt enabled，同时 receipt persistence failure 必须在 effect 前拒绝。
  - `codex-biz-worker` 当前 shared typed provider support 存在基线缺口；未补齐并通过
    exact vertical test 前，allowlist 不构成可用性证明。
  - Claude MVP-B 未随本切片迁移，短期内不同 runtime 的 lifecycle ownership mode 不同；
    provider allowlist 与 contract capability 必须 fail closed，避免误接管。
- open_questions: `[]`；原 11 项 finding、第二轮 4 项 blocker、第三轮 F-01–F-07、
  第四轮 findings、第五轮 ownership-mode/state findings，以及 round-5/round-6
  rereview 追踪的 exact mismatch 验收歧义和并行/晚到 attempt 旧表述，均已在 closure
  matrix 和对应 resolutions 中冻结。真实 activation 的 exact target 不是
  implementation open question，而是尚未授权、默认 disabled 的 deployment input。
  Round 7 独立复审已通过，Source Slice 0–8 的 implementation gate 已打开；切片顺序、
  SHADOW/ENFORCED gate 和 activation 单独授权边界不是实现者自由选择。

## Ultra Execution Contract

- 当前状态为 `READY_FOR_SIGNOFF`。Ultra 已按
  `0 -> 1 -> 2 -> 3 -> 4 -> 5 -> 6 -> 7 -> 8` 顺序完成 source implementation；
  后续只允许独立 signoff，不得据此执行真实 activation。
- 先读取本文件、根 `AGENTS.md` 与实际受影响模块指引；若修改共享 typed/Open API
  adapter，必须读取 `addons/claude-worker-agent/AGENTS.md`。MVP-A 不修改 Claude
  Worker lifecycle loops 或 Python Worker。
- 在 scope 内自主决定具体类、package 和局部实现；本文件中的组件名表达责任边界，不是
  强制一类一文件。
- Round 7 独立复审、review checklist 和 owner approval 已满足 source implementation
  开工门槛；不得据此跳过 shadow 直接 enforced，也不得把 repo-owned fixture evidence
  当作真实 deployment evidence。
- Slice 7/8 只允许 repo-owned ephemeral fixture；真实 systemd/Kubernetes/Docker/
  supervisor/CI controller mutation、现存 Navigator/Worker stop/start 和首个实际
  enrollment 不因 spec 批准而自动获权。
- 每完成一阶段，在本文件记录 changed paths、精确测试和 parity/deviation；不要创建
  模块级竞争计划或 prompt。
- 如需改变目标、MVP scope、public compatibility、authority、安全边界、数据迁移策略
  或接管全部 provider，设置 `NEEDS_REPLAN` 并停止扩展。
- 不修改 sibling repo，不操作历史 Task，不部署/重启/push/tag/release，不运行未经
  批准的 authority/replay/live chain。
- 达到 evidence sufficiency 后停止；填写 `Implementation Result` 并将状态改为
  `READY_FOR_SIGNOFF`，不得自行设置 `ACCEPTED`。

## Implementation Result

> 由 Ultra implementation session 填写。

### Slice Execution Record

#### Slice 0 — Contract and Failure-first Baseline

- status: `COMPLETED`
- source_inventory:
  - Task/Session compatibility writers and terminal predicates:
    `session-module/.../TaskController.java`,
    `AgentDiscoveryController.java`, `SharedAskController.java`,
    `SessionMetadataService.java`, `UnifiedSessionTaskProjectionService.java`,
    `addons/codex-worker-agent/.../CodexTaskService.java`,
    `CodexTaskRuntimeStateService.java`, `CodexStreamRelay.java` and
    `addons/claude-worker-agent/.../ClaudeTaskService.java`,
    `WorkerStreamRelay.java`, `TaskStateReconciler.java`.
  - termination authority/typed compatibility:
    `session-module/.../TerminationOperationService.java`,
    `TerminationOperationRepository.java`,
    `addons/claude-worker-agent/.../RuntimeTaskClosureService.java`,
    `RuntimeStateAuditService.java` and Codex `terminate/reconcile` paths.
  - token/receipt/registration resources:
    `BusinessTaskScopedTokenTerminalListener.java`,
    `BusinessTaskScopedTokenLifecycleService.java`,
    `BusinessTaskTerminalStateRepository.java`,
    `termination_operations`, Worker termination replay ledger and completion
    receipt store. `activeTaskRegistrationPresent` is confirmed as a derived
    status projection, not a separately deletable registration resource.
  - Worker connectivity/recovery triggers:
    Codex `CodexStreamRelay` startup/stream recovery and Claude
    `WorkerHealthChecker`, `WorkerStreamRelay`, `TaskStateReconciler`.
    Claude loops remain MVP-B and are not modified by ARCH-001.
  - Worker contract baseline:
    current SDK Worker has `/health`, `/api/v1/query`, task SSE/status/abort,
    JSONL event and one-use termination ledgers, but no lifecycle v1 identity
    store, fenced inventory/events/ACK/dispatch status, ownership-mode-bound
    digest, or persist-before-effect disposition.
- contract_inventory_result:
  - no existing lifecycle fact/snapshot/outbox/writer-proof schema or canonical
    reducer was found;
  - public BUG-035 receipt-disabled one-shot and existing provider strings are
    retained;
  - deferred authority/provider/rolling/identity topics remain unchanged.
- failure_first_commands:
  - `mvn -q -pl session-module -am -Dtest=TaskLifecycleReducerTest -Dsurefire.failIfNoSpecifiedTests=false test`
    — failed as expected at test compile because `TaskLifecycleReducer` did not
    exist.
  - `cd tools/codex-agent-worker && node --import tsx --test tests/lifecycle-contract.test.ts`
    — failed as expected with `ERR_MODULE_NOT_FOUND` for the lifecycle v1 store.
- failure_first_tests:
  `session-module/.../TaskLifecycleReducerTest.java` and
  `tools/codex-agent-worker/tests/lifecycle-contract.test.ts`.

- implementation_summary:
  - 已完成真实 writer/contract inventory；没有发现可复用的 canonical reducer、
    lifecycle schema、writer proof 或 Worker lifecycle v1 store。
  - 先固定缺失实现的失败基线，再进入 Slice 1；后续 terminal、typed closure 和
    `codex-biz-worker` 缺口也均先由回归测试暴露。
- changed_paths:
  - `session-module/src/test/java/com/foggy/navigator/session/lifecycle/TaskLifecycleReducerTest.java`
  - `tools/codex-agent-worker/tests/lifecycle-contract.test.ts`
- tests_and_results: 两条 failure-first 命令均按预期失败，失败原因分别是缺少 reducer
  和 lifecycle store，而不是环境或依赖问题。
- migration_and_compatibility_evidence: inventory 确认现有 public typed DTO 与
  receipt-disabled 语义必须保持不变。
- shadow_and_canary_evidence: Slice 0 未创建 aggregate、未执行 effect。
- deviations: none
- residual_risks: none beyond the approved activation boundary
- reused_evidence: BUG-036 baseline only
- omitted_validation_and_reason: live/deploy/release not authorized
- readiness: COMPLETED

#### Slice 1 — Canonical Reducer, Persistence and Schema Readiness

- status: `COMPLETED`
- implementation_summary:
  - 新增纯函数 Task lifecycle reducer、规范化 canonical dimensions、blocker precedence、
    duplicate/out-of-order/late fact 处理与 incremental/full recompute parity。
  - 新增 fact、Worker/Session/Task snapshot、effect outbox 的 JPA model/repository；
    SHADOW 只持久化 proposal，所有 owner effect 均保持 `SUPPRESSED`。
  - 新增 12 张表的 additive forward/rollback SQL 与 fail-closed schema readiness；
    schema ready 也不会解除独立 activation gate。
- changed_paths:
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/lifecycle/**`
  - `session-module/src/main/java/com/foggy/navigator/session/lifecycle/TaskLifecycle*.java`
  - `session-module/src/main/java/com/foggy/navigator/session/lifecycle/Lifecycle*.java`
  - `session-module/src/main/java/com/foggy/navigator/session/lifecycle/persistence/**`
  - `session-module/src/main/java/com/foggy/navigator/session/lifecycle/repository/**`
  - `session-module/src/test/java/com/foggy/navigator/session/lifecycle/TaskLifecycleReducerTest.java`
  - `session-module/src/test/java/com/foggy/navigator/session/lifecycle/LifecycleMigrationContractTest.java`
  - `session-module/src/test/java/com/foggy/navigator/session/lifecycle/LifecycleSchemaReadinessTest.java`
  - `docs/migration/2026-07-30-arch-001-lifecycle-owner.sql`
  - `docs/migration/2026-07-30-arch-001-lifecycle-owner-rollback.sql`
- gate_result: reducer、migration static contract 与 schema-not-ready rejection focused
  tests passed；进入 Slice 2。

#### Slice 2 — Terminal Tombstone and Frozen Cleanup Plan

- status: `COMPLETED`
- implementation_summary:
  - canonical terminal、authorization tombstone 与 participant-specific cleanup plan
    同事务提交；derived active registration 固定为
    `NOT_APPLICABLE(DERIVED_PROJECTION_NO_RESOURCE)`。
  - cleanup step 使用独立幂等 checkpoint，支持重复投递、部分失败后重试和重启恢复；
    cleanup pending 继续占用 Session lane。
- changed_paths:
  - `session-module/src/main/java/com/foggy/navigator/session/lifecycle/TaskTerminal*.java`
  - `session-module/src/main/java/com/foggy/navigator/session/lifecycle/Terminal*.java`
  - `business-agent-module/src/main/java/com/foggy/navigator/business/agent/lifecycle/BusinessTaskTerminalTombstoneParticipant.java`
  - `session-module/src/test/java/com/foggy/navigator/session/lifecycle/TaskTerminalCommitServiceTest.java`
  - `session-module/src/test/java/com/foggy/navigator/session/lifecycle/TerminalCleanupPlanFactoryTest.java`
  - `session-module/src/test/java/com/foggy/navigator/session/lifecycle/TerminalCleanupStepExecutorTest.java`
- gate_result: transaction、N/A、partial failure/retry/restart focused tests passed；进入 Slice 3。

#### Slice 3 — Typed Termination Admission and Codex Biz Closure

- status: `COMPLETED`
- implementation_summary:
  - receipt-enabled admission 通过 REQUIRED transaction 同步提交 public receipt、
    owner intent 与 suppressed owner outbox binding；commit/persistence 失败在 provider
    effect 前返回冻结的 typed rejection。
  - `RuntimeTaskClosureProvider` 精确支持 `codex-biz-worker`，未扩展其他 provider；
    public DTO、force authority 和 receipt-disabled BUG-035 matrix 未改变。
- changed_paths:
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/RuntimeTaskClosureService.java`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/RuntimeTerminationAcceptanceCoordinator.java`
  - `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/RuntimeTaskTypedContractServiceTest.java`
  - `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/RuntimeRequestAuditService.java`
  - `session-module/src/main/java/com/foggy/navigator/session/lifecycle/TaskTerminationIntentRecorder.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/spi/CodexWorkerFacadeImpl.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/spi/CodexWorkerFacadeRuntimeClosureProviderTest.java`
- failure_first_result: injected receipt persistence failure initially exposed missing atomic
  admission；exact `codex-biz-worker` focused test initially returned unsupported；两者修复后通过。
- gate_result: typed closure、Open SDK published contract 与 affected addon lanes passed；
  进入 Slice 4。

#### Slice 4 — Codex Worker Lifecycle v1 and Provider-neutral Sentinel

- status: `COMPLETED`
- implementation_summary:
  - Codex SDK Worker 新增 durable identity/state-generation/epoch、canonical binding digest
    （含 ownership mode）、fenced inventory/events/ACK/dispatch status、durable dispatch 与
    provider Task ID、termination reservation/effect-start，以及 persist-before-effect。
  - lifecycle route 使用 non-empty runtime credential 且严格校验 expected identity/mode/
    binding/version；cross-mode mismatch 先于 digest、duplicate、capability 与 effect。
  - Java 新增 provider-neutral Sentinel/lease/snapshot 与 Codex HTTP adapter；单 Task SSE
    断开不直接扩大为 Worker offline。
- changed_paths:
  - `tools/codex-agent-worker/src/lifecycle/**`
  - `tools/codex-agent-worker/src/routes/lifecycle.ts`
  - `tools/codex-agent-worker/src/routes/query.ts`
  - `tools/codex-agent-worker/src/routes/tasks.ts`
  - `tools/codex-agent-worker/src/routes/health.ts`
  - `tools/codex-agent-worker/src/auth.ts`
  - `tools/codex-agent-worker/src/config.ts`
  - `tools/codex-agent-worker/src/index.ts`
  - `tools/codex-agent-worker/src/models.ts`
  - `tools/codex-agent-worker/src/termination-operation.ts`
  - `tools/codex-agent-worker/src/validation/query.ts`
  - `tools/codex-agent-worker/.env.example`
  - `tools/codex-agent-worker/tests/lifecycle-contract.test.ts`
  - `tools/codex-agent-worker/tests/lifecycle-route.test.ts`
  - `tools/codex-agent-worker/tests/health.test.ts`
  - `session-module/src/main/java/com/foggy/navigator/session/lifecycle/WorkerLifecycleSentinel*.java`
  - `session-module/src/main/java/com/foggy/navigator/session/lifecycle/JpaSentinelLeaseStore.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/lifecycle/CodexWorkerLifecycleHttpAdapter.java`
  - corresponding focused tests under `session-module/.../lifecycle/` and
    `addons/codex-worker-agent/.../lifecycle/`.
- gate_result: Worker focused 16 tests、Worker full suite、typecheck/build、Sentinel and Java
  adapter tests passed；进入 Slice 5。

#### Slice 5 — Session Foreground Lane and Offline Command Gate

- status: `COMPLETED`
- implementation_summary:
  - 新增 Session row-lock foreground lane snapshot，create/resume 只能观察和提议；
    offline/cleanup pending/active Task 均不能通过新 request ID 绕过。
  - Task dispatch 仅在显式 `navigator.lifecycle.shadow-enabled=true` 时写 SHADOW observation；
    repository 默认配置为 false，不改变 legacy effect。
  - offline command gate 稳定拒绝且不保存 pending user command；local terminal cleanup
    可继续。
- changed_paths:
  - `session-module/src/main/java/com/foggy/navigator/session/lifecycle/SessionForegroundLaneService.java`
  - `session-module/src/main/java/com/foggy/navigator/session/lifecycle/SessionLaneDecision.java`
  - `session-module/src/main/java/com/foggy/navigator/session/lifecycle/OfflineCommandGate.java`
  - `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`
  - `session-module/src/main/java/com/foggy/navigator/session/config/SessionModuleAutoConfiguration.java`
  - `session-module/src/test/java/com/foggy/navigator/session/lifecycle/SessionForegroundLaneServiceTest.java`
  - `launcher/src/main/resources/application.yml`
- gate_result: lane/offline focused tests and session affected reactor passed；进入 Slice 6。

#### Slice 6 — SHADOW Parity and Exact Canary Enrollment Gate

- status: `COMPLETED`
- implementation_summary:
  - parity report 按 exact aggregate tuple 分类 legacy/owner diff，unclassified diff 阻止
    enrollment；SHADOW owner effect count 保持零。
  - canary gate 精确限定 `codex-biz-worker`、receipt、schema、auth、identity、protocol/
    build、capabilities、proof 和 tuple；缺少真实 activation evidence 时稳定返回
    `ENFORCED_DISABLED_PENDING_ACTIVATION_EVIDENCE`。
- changed_paths:
  - `session-module/src/main/java/com/foggy/navigator/session/lifecycle/LifecycleShadowParityService.java`
  - `session-module/src/main/java/com/foggy/navigator/session/lifecycle/LifecycleParityReport.java`
  - `session-module/src/main/java/com/foggy/navigator/session/lifecycle/LifecycleEnrollmentGate.java`
  - `session-module/src/test/java/com/foggy/navigator/session/lifecycle/LifecycleShadowParityServiceTest.java`
- gate_result: exact tuple separation、zero-effect 与 negative enrollment matrix passed；
  进入 Slice 7。

#### Slice 7 — Writer Exclusivity Proof and Reference Fencing

- status: `COMPLETED`
- fixture_scope: repo-owned H2/JPA fixture only
- implementation_summary:
  - 新增 writer generation、instance registration、proof、Worker/Session/Task reference
    与 outbox authorization persistence。
  - proof/reference/outbox 通过同 row-lock transaction 线性化；claim 本身不授权 provider
    call，只有 `EFFECT_STARTED` authorization commit 后才允许最多一次调用。
  - proof loss/expiry quarantine aggregate、阻止新 unsafe effect且不回退 LEGACY；
    OPEN Session/ENFORCED Worker/unfinished outbox 阻止 proof 提前释放。
- changed_paths:
  - `session-module/src/main/java/com/foggy/navigator/session/lifecycle/WriterExclusivityProofService.java`
  - `session-module/src/main/java/com/foggy/navigator/session/lifecycle/ProofAggregateType.java`
  - writer generation/proof/reference persistence and repository files under
    `session-module/src/main/java/com/foggy/navigator/session/lifecycle/`
  - `session-module/src/test/java/com/foggy/navigator/session/lifecycle/WriterExclusivityProofServiceTest.java`
- gate_result: proof reference predicate、expiry/quarantine 和两种 proof-loss/effect
  authorization ordering passed；进入 Slice 8。

#### Slice 8 — Isolated ENFORCED Source Fixture

- status: `COMPLETED`
- fixture_scope: repo-owned in-memory H2 + fake controller; no shared port/process
- implementation_summary:
  - isolated fixture 验证 homogeneous source gate、proof lease、late exclusivity loss
    quarantine、reference/outbox drain 后恢复，以及 non-fixture activation evidence 缺失时
    持续 disabled。
  - fixture 未访问 systemd/Kubernetes/Docker/supervisor/CI/manual launcher，未停止或
    启动任何 Navigator/Worker，未创建真实 ENFORCED aggregate。
- changed_paths:
  - `session-module/src/test/java/com/foggy/navigator/session/lifecycle/IsolatedEnforcedLifecycleContractTest.java`
- gate_result: isolated fixture passed；source implementation 可进入独立 signoff。

### Validation Evidence

- `mvn -q -pl session-module -am -Dtest='*Lifecycle*Test,TerminalCleanupPlanFactoryTest,TaskTerminalCommitServiceTest,TerminalCleanupStepExecutorTest' -Dsurefire.failIfNoSpecifiedTests=false test`
  — passed.
- `mvn -pl session-module -am test`
  — passed; Session Module 465 tests, 0 failures/errors.
- `mvn -pl addons/codex-worker-agent -am -Dtest=CodexWorkerFacadeRuntimeClosureProviderTest,CodexWorkerLifecycleHttpAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test`
  — passed; 2 tests.
- `mvn -q -pl addons/codex-worker-agent -am test`
  — passed.
- `mvn -pl addons/claude-worker-agent -am -Dtest=RuntimeTaskTypedContractServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`
  — passed; 17 tests.
- `mvn -q -pl addons/claude-worker-agent -am test`
  — passed.
- `mvn -q -pl navigator-open-sdk -am test`
  — passed.
- `cd tools/codex-agent-worker && npm run typecheck && node --import tsx --test tests/lifecycle-contract.test.ts tests/lifecycle-route.test.ts tests/health.test.ts`
  — passed; 16 tests.
- `cd tools/codex-agent-worker && node --import tsx --test --test-reporter=dot tests/*.test.ts`
  — passed; full Worker suite.
- `cd tools/codex-agent-worker && npm run build`
  — passed.
- `mvn -q -pl launcher -am test`
  — first candidate failed in launcher because the newly added lifecycle configuration used a
  duplicate top-level YAML key. The lifecycle block was merged into the existing key.
- `mvn -pl launcher -am -Dtest='ActuatorDiscoveryContractTest,ActuatorDiscoveryRouteContractTest,AuthorizationRouteManifestCoverageTest,CommonRepositoryOwnershipContextTest,RuntimeTimeBasisConfigurationTest' -Dsurefire.failIfNoSpecifiedTests=false test`
  — passed; 8 tests.
- `mvn -q -pl launcher -am test`
  — final post-fix reactor passed with exit code 0.
- `git diff --check`
  — passed.

### Changed-path Manifest

- SPI: `navigator-spi/src/main/java/com/foggy/navigator/spi/lifecycle/**`.
- lifecycle owner and tests:
  `session-module/src/main/java/com/foggy/navigator/session/lifecycle/**`,
  `session-module/src/test/java/com/foggy/navigator/session/lifecycle/**`,
  `session-module/src/main/java/com/foggy/navigator/session/config/SessionModuleAutoConfiguration.java`,
  `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`,
  `session-module/pom.xml`.
- terminal participants/admission:
  `business-agent-module/src/main/java/com/foggy/navigator/business/agent/lifecycle/**`,
  `business-agent-module/src/main/java/com/foggy/navigator/business/agent/config/BusinessAgentAutoConfiguration.java`,
  `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/RuntimeRequestAuditService.java`,
  `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/RuntimeTaskClosureService.java`,
  `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/RuntimeTerminationAcceptanceCoordinator.java`,
  `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/RuntimeTaskTypedContractServiceTest.java`.
- Codex Java:
  `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/lifecycle/**`,
  `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/lifecycle/**`,
  `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/config/CodexWorkerAutoConfiguration.java`,
  `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/spi/CodexWorkerFacadeImpl.java`,
  `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/spi/CodexWorkerFacadeRuntimeClosureProviderTest.java`.
- Codex SDK Worker:
  `tools/codex-agent-worker/.env.example`,
  `tools/codex-agent-worker/src/auth.ts`,
  `tools/codex-agent-worker/src/config.ts`,
  `tools/codex-agent-worker/src/index.ts`,
  `tools/codex-agent-worker/src/lifecycle/**`,
  `tools/codex-agent-worker/src/models.ts`,
  `tools/codex-agent-worker/src/routes/health.ts`,
  `tools/codex-agent-worker/src/routes/lifecycle.ts`,
  `tools/codex-agent-worker/src/routes/query.ts`,
  `tools/codex-agent-worker/src/routes/tasks.ts`,
  `tools/codex-agent-worker/src/termination-operation.ts`,
  `tools/codex-agent-worker/src/validation/query.ts`,
  `tools/codex-agent-worker/tests/health.test.ts`,
  `tools/codex-agent-worker/tests/lifecycle-contract.test.ts`,
  `tools/codex-agent-worker/tests/lifecycle-route.test.ts`.
- migration/config/delivery:
  `docs/migration/2026-07-30-arch-001-lifecycle-owner.sql`,
  `docs/migration/2026-07-30-arch-001-lifecycle-owner-rollback.sql`,
  `launcher/src/main/resources/application.yml`,
  this canonical work item.

### Deviations and Residual Risks

- deviations: none. No goal、scope、public compatibility、authority、安全边界、状态机或
  migration strategy change was required.
- residual_risks:
  - production/shared MySQL pre-apply/validate/rollback drill 未执行；当前证据是 additive
    SQL static contract、H2 schema readiness 与 launcher reactor。真实数据库操作属于后续
    separately authorized deployment gate。
  - real controller inventory/disable/late-relaunch、真实 process stop/start、首个
    non-fixture ENFORCED aggregate 和 live SIM 均未执行且未被暗示为通过。
  - `navigator.lifecycle.shadow-enabled=false` 与
    `activation-evidence-present=false` 为默认值；非 fixture enrollment 持续返回
    `ENFORCED_DISABLED_PENDING_ACTIVATION_EVIDENCE`。
- boundary_evidence:
  - 未操作历史 Task `20260730-0e01`；
  - 未访问或修改 sibling repo、SIM/TMS 业务数据；
  - 未操作真实 controller/process，未停止、重启、升级或发布 Navigator/Worker；
  - 未 push、tag 或 release，测试输出与交付记录不保存 credential、prompt、模型回复或
    业务数据。
- readiness: `READY_FOR_SIGNOFF`

## Remediation Implementation Result (2026-07-31)

### Disposition and Contract Boundaries

- remediation_status: `READY_FOR_SIGNOFF`
- rejected_implementation_commit:
  `fac98161d5e59b54d8f605061af1adae6f4b6415`
- independent_rejection_commit:
  `297c79160657d0413b608ba2f4f5386486e14837`
- original_baseline:
  `d3eb7f76d31d6dfd2a78009d30caff9f8307284d`
- independent_signoff_history: preserved; the prior `REJECTED` verdict and
  `ARCH-001-independent-signoff-2026-07-31.md` remain authoritative history until a new
  independent signoff is performed.
- deviations: none. Approved authority、Worker-v1 wire、public compatibility、additive
  migration strategy and activation boundary were not changed.
- activation_gate: closed. No real controller/process, non-fixture `ENFORCED` aggregate,
  live SIM, deployment, restart, release or production/shared database was used.

### B1–B6 Remediation Status

- `ARCH-001-B1-owner-vertical-chain-not-integrated`: **CLOSED IN SOURCE**.
  `TaskDispatchFacade` now performs foreground lane reservation and offline admission before
  provider effect. The production lifecycle owner connects Sentinel inventory/events/ACK,
  normalized facts, exact binding validation, reducer, snapshot, terminal commit, cleanup and
  typed projection. Normalized facts preserve physical Worker ID, generation, epoch, ownership
  mode, dispatch/operation IDs, binding digest and provider Task identity. `SHADOW` remains
  observation-only.
- `ARCH-001-B2-receipt-admission-not-recoverable`: **CLOSED IN SOURCE**.
  Exact Task/provider/Worker/enrollment/precondition validation precedes acceptance. Receipt,
  owner operation/fact, exact binding and effect outbox commit atomically. The real idempotent
  outbox dispatcher resumes commit-before-dispatch delivery and prevents a second provider
  termination after effect authorization or response loss. Persistence failure fails closed.
  Receipt-disabled repeated same-ID HTTP calls retain two one-shot provider attempts and the
  published disabled reconciliation matrix.
- `ARCH-001-B3-terminal-authority-and-cleanup-incomplete`: **CLOSED IN SOURCE**.
  Only exact validated Worker/runtime facts or approved exact sources can become terminal
  candidates. Conflicting evidence enters authority quarantine without canonical terminal,
  tombstone or terminal effect. Canonical Task terminal, authorization tombstone and frozen
  cleanup applicability plan commit together. Restart-safe checkpoint actions implement token
  revoke, compatibility projection, receipt checkpoint, registration deactivation and final
  foreground lane release. Typed `TERMINAL` requires both canonical lifecycle terminal and
  canonical Task terminal status.
- `ARCH-001-B4-worker-v1-cross-runtime-contract-broken`: **CLOSED IN SOURCE**.
  Java and Node now agree on mode-first fences, headers, binding digest, response envelope and
  PUT ACK route. The Java port implements probe, inventory/events, ACK, dispatch status and
  command context. Node query/create/resume/abort uses durable
  `PREPARED → EFFECT_STARTED → RESULT_OBSERVED`/terminal-fact transitions; only
  `EFFECT_STARTED` authorizes provider invocation, PREPARED can safely continue, and later
  duplicates cannot repeat the effect. A real Java adapter-to-Node router test and exact
  codex-biz-worker readiness/termination/reconciliation test cover the executable contract.
- `ARCH-001-B5-writer-proof-and-enforced-fixture-not-representative`: **CLOSED IN SOURCE**.
  Authorization locks proof → exact reference → outbox and verifies proof generation, inventory
  digest, aggregate reference, effect class and claim. Release is derived from durable aggregate
  references and proof-specific unfinished outbox state. Real concurrent transactions cover
  loss-first and authorization-first orderings. Slice 8 uses production entities, repositories,
  services and Worker route without touching a real controller/process or non-fixture
  `ENFORCED` aggregate.
- `ARCH-001-B6-schema-validation-obligation-not-met`: **CLOSED IN SOURCE**.
  The repo-owned Testcontainers fixture applies forward SQL to MySQL 8.0.44, reapplies it for
  compatibility, performs Hibernate/JPA validation, verifies critical unique/index/nullability/
  length contracts, confirms rollback with no enforced aggregate, and confirms fail-closed
  rollback with enforcement markers/references/outbox. Production schema remains pre-apply;
  the warning-only migration runner is not treated as readiness evidence.

### Changed Paths

- lifecycle SPI:
  `navigator-spi/src/main/java/com/foggy/navigator/spi/lifecycle/**`
- lifecycle owner, persistence, reducer, ingress, Sentinel, cleanup, writer proof and tests:
  `session-module/src/main/java/com/foggy/navigator/session/lifecycle/**`,
  `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`,
  `session-module/src/test/java/com/foggy/navigator/session/lifecycle/**`,
  `session-module/pom.xml`
- terminal cleanup participant and tests:
  `business-agent-module/src/main/java/com/foggy/navigator/business/agent/lifecycle/**`,
  `business-agent-module/src/test/java/com/foggy/navigator/business/agent/lifecycle/**`
- receipt admission, durable delivery and typed contract tests:
  `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/**`,
  `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/**`
- Codex Java lifecycle/client/service and executable Node contract tests:
  `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/**`,
  `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/**`
- Codex Worker lifecycle state machine/routes/tests:
  `tools/codex-agent-worker/src/lifecycle/store.ts`,
  `tools/codex-agent-worker/src/routes/query.ts`,
  `tools/codex-agent-worker/src/routes/tasks.ts`,
  `tools/codex-agent-worker/tests/lifecycle-contract.test.ts`,
  `tools/codex-agent-worker/tests/fixtures/lifecycle-router-server.ts`
- additive forward/rollback schema:
  `docs/migration/2026-07-30-arch-001-lifecycle-owner.sql`,
  `docs/migration/2026-07-30-arch-001-lifecycle-owner-rollback.sql`

### Failure-first Red/Green Evidence

- vertical owner integration initially committed terminal state while cleanup checkpoints remained
  `PENDING`; transaction-boundary correction moved after-commit work to independent transactions.
  The green test now proves Worker fact → reducer → canonical terminal → tombstone → cleanup →
  typed `TERMINAL`, including token revocation and registration deactivation.
- termination recovery initially failed when a restart path bypassed the proxied transaction;
  the green test exercises the production acceptance/outbox dispatcher through the transactional
  proxy and covers commit-before-dispatch crash, response loss, same-ID recovery and persistence
  failure.
- Codex full tests first exposed four failures and four errors caused by sending new lifecycle
  command context on legacy `SHADOW` wire paths. The final implementation retains the legacy
  overload in `SHADOW` and requires the v1 context in `ENFORCED`; focused and full suites are
  green.
- the MySQL fixture first failed on an assertion mismatch for the direct rollback-gate
  `SQLException`; the corrected assertion verifies the actual fail-closed database error and the
  complete fixture is green.
- business cleanup coverage first failed compilation because the test used non-record accessor
  names; corrected typed accessors then proved terminal tombstone/token cleanup behavior.
- new reducer/vertical/Worker tests also prove conflicting terminal evidence produces no terminal
  effect and that ACCEPTED, ACK, diagnostic text, disconnect and timeout do not independently
  establish terminal authority.

### Verification Results

- `mvn -q -pl session-module -am -Dtest=TaskLifecycleOwnerVerticalIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`
  — exit 0; 2 tests.
- `mvn -pl session-module -am test`
  — exit 0; Session Module 482 tests, 0 failures/errors, 1 skipped. The opt-in MySQL
  fixture is run separately below.
- `mvn -pl business-agent-module -am test`
  — exit 0; Business Agent Module 739 tests, 0 failures/errors.
- `mvn -q -pl addons/claude-worker-agent -am -Dtest=RuntimeTaskTypedContractServiceTest,RuntimeTaskClosureServiceTest,RuntimeTerminationDeliveryRecoveryTest,RuntimeTerminationAcceptanceCoordinatorTest -Dsurefire.failIfNoSpecifiedTests=false test`
  — exit 0; focused typed admission/recovery tests passed.
- `mvn -pl addons/claude-worker-agent -am test`
  — exit 0; Claude Worker Addon 461 tests, 0 failures/errors.
- `mvn -q -pl addons/codex-worker-agent -am -Dtest=CodexWorkerLifecycleHttpAdapterTest,CodexWorkerLifecycleNodeContractIntegrationTest,CodexTaskServiceTest#exactCodexBizWorkerRunsReadinessTerminationAndSameRequestReplay -Dsurefire.failIfNoSpecifiedTests=false test`
  — exit 0; Java adapter/Node router and exact codex-biz-worker contract passed.
- `mvn -q -pl addons/codex-worker-agent -Dtest=CodexStreamRelayTest,CodexTaskServiceTest test`
  — exit 0; 194 focused tests.
- `mvn -q -pl addons/codex-worker-agent test`
  — exit 0; 495 tests, 0 failures/errors.
- `cd tools/codex-agent-worker && npm run typecheck && npm test`
  — exit 0; typecheck passed; 264 tests, 262 passed, 2 skipped, 0 failed.
- `mvn -pl navigator-open-sdk -am test`
  — exit 0; Open SDK 203 tests, 0 failures/errors.
- `mvn test -pl launcher -am`
  — exit 0; all 14 reactor modules succeeded; Launcher 19 tests, 0 failures/errors,
  2 skipped. Surefire emitted a post-success fork-JVM shutdown warning without changing the
  successful result.
- `mvn -q -pl session-module -am -Darch001.mysql.integration=true -Dtest=LifecycleMigrationMySqlIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`
  — exit 0; 1 MySQL 8.0.44 forward/JPA/rollback-floor integration test.
- `mvn -pl addons/claude-worker-agent,addons/codex-worker-agent -am -DskipTests test-compile`
  — exit 0; affected Java test sources compiled.
- `mvn -pl addons/codex-worker-agent -am -Dmaven.test.skip=true install`
  — final exit 0 after a single-module diagnostic first exposed stale local snapshot
  dependencies and then a real static method-reference compile error; the source error was fixed
  before all Codex tests passed.
- `git diff --check`
  — exit 0 before documentation update; repeated after documentation and staging.

### Compatibility, Unrun Items and Residual Risks

- public compatibility: unchanged. No public SDK request/response wire changed; Open SDK tests
  pass. Receipt-disabled same-request behavior remains two one-shot provider attempts and disabled
  reconciliation. `SHADOW` retains legacy Codex provider wire and zero lifecycle-owner effect.
- not run by boundary: production/shared database pre-apply or rollback, real controller
  inventory/disable/late-relaunch, real process stop/start, live SIM, deployment, first
  non-fixture `ENFORCED`, release and production activation.
- residual risks: executable evidence is repo-owned ephemeral fixture evidence. A later authorized
  deployment still needs production schema pre-apply/readiness, controlled Navigator and Codex
  Worker rollout/restart, and independent signoff before activation.
- SDK/CLI publication: not required; no public SDK/CLI wire changed.
- runtime restart: required only when a later authorized deployment activates these Navigator Java
  and Codex Worker changes; none was performed during remediation.

## Acceptance Status

- acceptance_status: rejected
- acceptance_decision: rejected
- signed_off_by: Independent Signoff Reviewer (Codex)
- signed_off_at: 2026-07-31
- acceptance_record:
  `docs/version-tracker/1.4.3-SNAPSHOT/evidence/ARCH-001-independent-signoff-2026-07-31.md`
- blocking_items:
  `ARCH-001-B1-owner-vertical-chain-not-integrated`,
  `ARCH-001-B2-receipt-admission-not-recoverable`,
  `ARCH-001-B3-terminal-authority-and-cleanup-incomplete`,
  `ARCH-001-B4-worker-v1-cross-runtime-contract-broken`,
  `ARCH-001-B5-writer-proof-and-enforced-fixture-not-representative`,
  `ARCH-001-B6-schema-validation-obligation-not-met`
- remediation_disposition: all six blocker implementations are closed in current source and
  pending a new independent signoff
- remediation_follow_up_status: ready-for-independent-resignoff
- historical_rejection_preserved: true
- follow_up_required: yes

## References

- source incident/fix:
  [BUG-036 Typed Termination Terminal Convergence](./BUG-036-typed-termination-terminal-convergence.md)
- typed public contract:
  [BUG-035 Open SDK Typed Termination/Reconciliation](./BUG-035-open-sdk-typed-termination-reconciliation-contract.md)
- current architecture:
  [System Overview](../../../00-system-overview.md) and
  [Functional Architecture](../../../02-modules/functional-architecture.md)
- implementation baselines inspected during replan:
  - `business-agent-module/src/main/java/com/foggy/navigator/business/agent/event/BusinessTaskScopedTokenTerminalListener.java`
  - `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessTaskScopedTokenLifecycleService.java`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/RuntimeStateAuditService.java`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/RuntimeTaskClosureService.java`
  - `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/RuntimeTaskTypedContractServiceTest.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexStreamRelay.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexBusinessAgentWorkerTaskLauncher.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/client/CodexWorkerClient.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/spi/CodexWorkerFacadeImpl.java`
  - `tools/codex-agent-worker/src/routes/health.ts`
  - `tools/codex-agent-worker/src/routes/query.ts`
  - `tools/codex-agent-worker/src/routes/tasks.ts`
  - `tools/codex-agent-worker/src/termination-operation.ts`
  - `tools/codex-agent-worker/src/persistence/event-store.ts`
  - `tools/codex-agent-worker/src/codex/sdk-wrapper.ts`
  - `session-module/pom.xml`, `navigator-spi/pom.xml`,
    `business-agent-module/pom.xml`, `addons/codex-worker-agent/pom.xml`
