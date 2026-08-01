---
doc_type: delivery-spec
delivery_type: cross-module
version: 1.4.3-SNAPSHOT
ticket: ARCH-001-ACT-001
status: ACCEPTED
canonical: true
execution_mode: ultra
assurance_level: elevated
approved_by: project-owner-user
approved_at: 2026-08-01
parent_work_item: ARCH-001-unified-session-task-lifecycle-owner.md
source_candidate_head: fdef79c9c55e7de9a5b01822c3c9dc0c75ca2e00
source_signoff_status: ACCEPTED
activation_candidate_digest: aa7ad663b6ca931a65e95f10b121df0e28374b4acd9be17b99afdd339da08c0c
activation_gate: CLOSED
exact_activation_target_class: ISOLATED_LOCAL_NON_FIXTURE
dedicated_codex_provider_evidence_lane: REAL_CODEX_MODEL
actual_activation_authorization: consumed-08-no-further-submission-authorized
independent_activation_signoff_status: R4_ACCEPTED_WITH_RISKS_AND_CONSUMED
replan_reason: PRESTART_BOOTSTRAP_AND_EXACT_RUNTIME_PROVISIONING_UNDEFINED
latest_bounded_execution: MODEL_AND_LIFECYCLE_COMPLETED_TARGET_DESTROYED
latest_execution_bug: P1_EMPTY_ADMISSION_FACT_PAYLOAD_RESOLVED_LIVE
real_model_retest_status: COMPLETED_ON_08_TERMINAL_CONVERGED_NO_RETRY
residual_execution_event: UNCLASSIFIED_TRANSIENT_CONTROLLER_OBSERVATION_AFTER_TERMINAL
open_questions: []
---

# Delivery Spec: ARCH-001 First ENFORCED Canary Activation Readiness

## Document Purpose

- intended_for: normal-analysis / ultra-implementation / authorized-closed-provisioning /
  independent-activation-signoff
- purpose: 为 ARCH-001 第一个非 fixture `ENFORCED` aggregate 建立一个可独立签核的、
  exact-target、fail-closed activation control plane 与 disposable canary 运行包。
- canonical_path:
  `docs/version-tracker/1.4.3-SNAPSHOT/workitems/ARCH-001-ACT-001-enforced-canary-activation-readiness.md`
- current_state: `ACCEPTED_WITH_RISKS`。2026-08-01 的第一次 execution preparation 在首次
  target start 前发现 fresh-schema bootstrap 与 exact runtime provisioning 未定义并安全
  停止；此前的 activation signoff 与 execution packet 已被本 replan 取代，不得再用于
  打开 gate。Project Owner 于 2026-08-01 授权本 replan；新的 implementation 仍只可交付
  source/tooling、closed provisioning protocol 与不触发模型调用的验证，并必须重新停在
  `READY_FOR_SIGNOFF`。
  随后的 exact `-04` bounded execution 在模型提交前又复现两个 fail-closed 边界缺口；
  已完成最小修复与定向回归，但因此原 `-04` signoff/seal 不再覆盖当前候选。该 target
  已 quarantine、销毁并清除六类 credential profiles，模型提交与 provider effect 均为零。
  fresh `-05` target 随后证明 V2 manifest 与 clean-restart epoch rebinding 已在真实
  registration/proof/admission-readiness 链路生效；唯一 task-create POST 又在 provider-effect
  原子事务入口暴露 Sentinel 的旧 epoch fence 缺口，并在 Worker/model 请求前 fail closed。
  该 P1 已完成最小源码修复、严格 fence 回归、Session reactor 回归与 Launcher 全模块打包，
  但没有突破 `-05` 的 no-retry 边界，因此仍需新候选独立 signoff 与新的单次 canary 做
  最终实机确认。`-05` 已 quarantine、精确销毁并清除六类 credential profiles。
  独立 signoff R2 随后授权 `-06`。该 run 的 Sentinel epoch 修复通过真实周期验证，唯一
  canary 也已真实调用 `gpt-5.6-sol` 并完成；但 target watcher 把 Worker 合法 Codex 子进程
  误判为未知控制器，在任务运行中触发 authority quarantine，导致 ENFORCED Task/Session
  aggregate 没有完成终态收敛。该新的 activation-blocking P1 已完成最小 harness 修复及
  21-test 回归，但不在 `-06` sealed candidate 内，R2 授权也已消耗，因此当前重新停在
  `READY_FOR_SIGNOFF`，不得复用 `-06` 或进行第二次模型提交。
  独立 signoff R3 随后授权 fresh `-07`。Watcher 修复在合法 Worker/Codex 子进程与 git
  子进程存活期间保持 unknown controller 为零，证明 `-06` 的 watcher P1 已解决。唯一模型
  提交真实完成，但 Sentinel 在消费 Worker 终态事实时稳定报
  `LIFECYCLE_FACT_PAYLOAD_INVALID`：production admission 预先持久化的 reserved/dispatched
  facts 使用 `{}` payload，而 owner 在终态批次中把 aggregate 的全部 facts 反序列化为
  `TaskLifecycleFact`。两个旧 fact 因缺少必填字段导致整个事务回滚，Task/Session 留在 OPEN。
  该新的 activation-blocking P1 已以失败优先回归复现并完成向后兼容修复；后续 admission
  也改为持久化完整 content-free envelope。聚焦 24-test、全 lifecycle 70-test 与完整
  Session 515-test 均通过。`-07` 已 operator quarantine、精确销毁并清除六类 profiles；
  R3 与单次提交授权均已消耗，修复不在旧 seal 内，因此当前仍为 `READY_FOR_SIGNOFF`，
  不得把 `-07` 的模型成功描述为生命周期成功，也不得再次提交模型。
  同会话独立 signoff R4 在 Project Owner 明确豁免 reviewer-session separation 后授权 fresh
  `-08`。唯一一次真实 `gpt-5.6-sol` 提交随后同时完成 provider 与 Navigator lifecycle
  终态：Task projection、Task lifecycle snapshot、terminal tombstone、outbox 和五项 cleanup
  checkpoint 均为 `COMPLETED`，三个 admission/terminal fact 都带合法非空 content-free
  envelope，确认 `-07` 的 P1 已在真实链路解决。任务终态提交约 0.1 秒后 watcher 捕获一次
  未留存进程身份的短暂 unknown controller 并按契约 fail closed；后续 live scan 为 0，且该
  事件未回滚已经提交的终态。它作为 `UNCLASSIFIED_TRANSIENT_SAFETY_EVENT` 保留，不在证据
  不足时臆断为已确认代码 BUG。`-08` 已显式 operator quarantine、精确销毁并清除六类
  credential profiles；R4 和单次提交授权均已消耗，activation 回到 `CLOSED`。本事项以
  `ACCEPTED_WITH_RISKS` 完成 bounded-canary 目标，不授权第二次提交或 production rollout。
- parent_boundary: ARCH-001 source candidate 已独立 `ACCEPTED`，但其 activation gate
  仍为 `CLOSED`；source acceptance 不构成本事项的 activation authority。

## Baseline Facts and Readiness Gap

### 2026-08-01 prestart replan facts

- Exact run `arch001-act001-canary-20260801-01` 在首次 MySQL/Navigator/Worker start 前停止；
  target Docker/process/listener/PID、submission、provider effect 与模型成本均为零，两个
  activation switches 保持 `false`。本次停止不构成一次 canary submission。
- Provider 注入后的 live doctor 返回 `ACTIVATION_TARGET_DOCTOR_READY`、
  `writesPerformed=0`，说明 ownership preflight 正常；阻断来自 runbook 可执行性，而非
  doctor warning。
- 现有 runbook 要求 fresh MySQL 8.0.44、additive migrations 与 Hibernate `validate`，但
  没有冻结完整 forward migration 集合、顺序、digest 与执行/重放协议。临时采用
  Hibernate `update/create` 不可接受。
- 现有 frozen manifest 写死 synthetic user/physical Worker/modelConfig IDs；生产 task
  path 会先验证 Worker access 与 modelConfig grant，而正常 user、Worker、modelConfig API
  生成 server-owned IDs。现有 runbook 没有安全的 generated-ID seal 阶段，direct SQL 或
  E2E fixture 均不在已批准边界内。
- 原独立 activation signoff 只覆盖旧 candidate/target assumptions，现标记为
  `SUPERSEDED_BY_2026-08-01_PRESTART_REPLAN`；后续必须形成新 candidate digest、closed
  provisioning evidence、sealed target manifest 与新的独立 activation signoff。

- `navigator.lifecycle.activation-evidence-present` 默认 `false`，非 fixture enrollment
  继续返回 `ENFORCED_DISABLED_PENDING_ACTIVATION_EVIDENCE`。
- `LifecycleEnrollmentService` 当前只有 test callers；没有 production admission caller
  把新 Session/Task enrollment 与真实 dispatch 串成原子链。
- writer generation、instance registration 与 exclusivity proof 有持久化结构和消费/
  quarantine 逻辑，但没有真实 target controller inventory、generation activation、
  proof acquire/renew observer 或 operator activation adapter。
- `LifecycleEnrollmentGate.EnrollmentRequest` 当前由调用方携带 `repoOwnedFixture`、
  `activationEvidencePresent`、tuple/readiness booleans。它适用于 source fixture，不可直接
  暴露给真实 operator/client 作为自证 authority。
- 单独把环境变量改为 `true` 不会建立 controller exclusivity、writer generation、proof
  lease、exact allowlist 或 production enrollment caller，因此不是 activation procedure。
- 当前 8112/3031/3051/3061 是长期运行的共享开发栈；3053 与 3151 属于受保护的独立
  Worker ownership domain。它们都没有被授权作为首次 canary target。
- `GOV-001-P3` 仍是 `DRAFT/BLOCKED`，所以本事项不能声称 production、external、
  dev-kvm promotion 或生产安全边界已就绪。

## Goal

- version_goal: 在不触碰真实业务数据、共享栈或 production boundary 的前提下，让一个
  exact disposable target 可以被独立 reviewer 判定为“是否允许最多一次 bounded
  non-fixture ENFORCED canary”。
- target_outcome:
  - implementation session 只交付 activation control plane、target-owned harness/runbook、
    automated safety tests 与脱敏 preflight evidence，并停在 `READY_FOR_SIGNOFF`；
  - 另行授权的 closed-provisioning session 只建立 fresh schema、通过 production APIs
    创建 synthetic runtime resources、seal generated IDs、停止 target 并清除短期 bootstrap
    material；两个 activation switches 始终为 false，且不调用模型/activation mutation；
  - 随后的独立 activation signoff 只给出 `AUTHORIZED_FOR_ONE_BOUNDED_CANARY` 或
    `REJECTED`，signoff 本身不执行 canary；
  - 只有 signoff 接受且用户再次明确授权 exact runId/target/最大一次尝试后，后续执行
    会话才可以临时打开该 disposable target 的 gate。
- critical_outcomes:
  - 不存在 caller-supplied boolean、普通配置开关或端口状态冒充 activation authority；
  - controller/process exclusivity、candidate identity、schema、generation、proof、Worker
    identity/capability 与 exact allowlist 全部 fail closed；
  - provider effect 前已原子建立 Worker/Session/Task proof references 与 durable command；
  - proof/observer/identity drift 立即停止新 enrollment，并让既存 aggregate quarantine；
  - 默认配置、共享开发栈与 production/external gate 保持不变。
- success_is_sufficient_when: ACT-AC1～ACT-AC15 全部有当前 candidate 的可审查证据，
  closed provisioning target 已 seal 并停止，无 critical blocker/waiver，且没有执行真实
  non-fixture enrollment 或 model submission。

## Scope

- in_scope:
  - Navigator 仓库内的 activation authority resolver、generation/proof lifecycle、
    controller inventory digest/observer、production enrollment integration 和 read-only
    readiness/inspect surface；具体局部结构由 Ultra 决定。
  - 一个 unique runId、独立端口、独立 MySQL 8.0.44 database/Compose project、独立日志/
    evidence 目录与专用 Worker process 的 disposable local target。
  - target ownership manifest、preflight、late-relaunch fixture、activation dry-run、stop-new-
    enrollment、proof-loss quarantine、target-owned cleanup 与脱敏运行手册。
  - 只使用 synthetic tenant/Worker/Session/Task identifiers 和 content-free/static-no-tool
    prompt；不得读取或调用真实 TMS/SIM 业务数据。
  - additive config/API/CLI 仅在确有 operator/readiness 所需时加入，并保持默认 closed、
    server-side authorization 与稳定 reason codes。
  - 一个 target-owned、content-free 的 schema plan，冻结 fresh MySQL 8.0.44 所需的完整
    forward SQL 列表、严格顺序、每项 SHA-256、整体 digest、空库/归属前置检查、幂等重放
    与 Hibernate `validate` 结果；禁止 Hibernate `update/create` 参与 canary bootstrap。
  - 一个 activation-disabled closed provisioning phase：只通过现有 production auth、
    Worker 与 modelConfig APIs 创建 synthetic tenant user、Physical Worker、restricted
    modelConfig grant 和 runtime credential；接受 server-generated IDs，并把它们 seal 到
    final target manifest，禁止 direct application-table DML 与 test/E2E fixture。
  - sealed target 在任何 activation signoff 前必须停止全部 target processes；signoff 只
    检查 content-free IDs/hashes/status/counts 与 credential lane 形状，不读取 credential。
- affected_modules:
  - `session-module`
  - `launcher`（只允许装配、配置、readiness/authorization contract；不得承载业务编排）
  - `addons/codex-worker-agent` 与 `tools/codex-agent-worker`（仅受真实 production route/
    dedicated target contract 影响时）
  - `tools/navigator-upstream` 或新的本仓 activation operator tooling
  - `docs/version-tracker/1.4.3-SNAPSHOT/{workitems,runbooks,test-records,evidence}`
- external_dependencies:
  - local Docker/Compose 与 MySQL 8.0.44；只允许操作 runId 明确归属的资源。
  - dedicated repo-source Codex Worker 与 real Codex model；credential/model/network 只从
    target-owned gitignored runtime profile 注入。实现与独立 signoff 不读取或输出 secret，
    actual canary execution 前由 owner 另行确认 exact model、profile path 与成本边界。

## Non-Goals

- out_of_scope:
  - production、dev-kvm-x3、Gateway external、public ingress/TLS/CORS、KMS/broker、Worker
    sandbox/egress、immutable audit 或 GOV-001-P3 promotion。
  - 当前共享 8112/MySQL、3031/3051/3061，受保护的 3053/3151 Worker，真实 SIM/TMS、
    sibling repositories、真实账号/凭据/业务 Task。
  - 第二个 target/tenant/Worker、existing aggregate migration、provider expansion、Claude/
    app-server、rolling upgrade、legacy binary coexistence 或全面 rollout。
  - authority recovery protocol。首次 disposable canary 若发生 proof loss，期望结果是
    quarantine + 停止；不得自动 clear conflict。整个 owned target 可在证据封存后销毁。
  - commit、push、tag、publish、release、部署或真正创建 non-fixture ENFORCED aggregate，
    除非后续分别取得对应明确授权。
  - 为满足旧 manifest 的 synthetic ID 而修改 production user/Worker/modelConfig ID 生成
    语义，或新增允许 caller 指定任意 primary key 的公共 API。
- do_not_touch:
  - 不 clean/reset/revert/checkout/switch 当前合法 dirty worktree，不修改历史 signoff
    evidence，不重写 ARCH-001 Fifth-round Implementation Result。
  - 不读取 `accounts/`，不打印或写入 admin/control/runtime/Worker/LLM secret。
  - 不以 direct SQL 创建/改写 user、Worker、modelConfig、grant、directory、Agent 或
    lifecycle application rows；schema migration 与只读 verification 是唯一允许的 DB
    bootstrap access。
- non_blocking_or_waivable_items:
  - raw evidence portability 可以由 project owner 接受并记录；authority、data safety、
    secret boundary、exact target ownership 与 zero-provider-effect negatives 不可 waiver。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| activation 分为 implementation、independent authorization、one bounded execution 三个会话 | 防止实现者自行批准或 signoff 顺手执行真实 mutation | 每个阶段只获得自身 authority；默认 gate 始终 closed |
| 首次 canary 只允许新 aggregate 和 exact `codex-biz-worker` tuple | 继承 ARCH-001 Stage 3 | receipt enabled、Worker lifecycle v1、identity/capability/proof 全部 must-pass |
| activation evidence 必须由 server-side trusted state/resolver 产生 | caller boolean 或 env flag 不是 authority | fixture bypass 不得进入 production surface；exact target/runId/commit/generation/digest 绑定 |
| canary target 必须 disposable、独占且与当前开发栈隔离 | 当前本机栈包含长期运行和跨 workspace 资源 | unique ports/database/Compose project/cwd/process manifest；未知归属即拒绝 |
| proof loss 无隐式 recovery | 继承第五轮已签收的 conflict precedence | quarantine 后不得恢复 READY/NONE；recovery 另立契约 |
| production promotion 继续由 GOV-001-P3 阻断 | local canary 不能证明 production security/operations | 不修改 production/external flags 或 P3 状态 |
| target lifecycle 增加 `PROVISIONING_CLOSED -> SEALED_STOPPED -> ACTIVATION_AUTHORIZED` 阶段 | generated runtime IDs 必须先由真实 API 创建，再成为 exact authority input | provisioning 时 control/admission 均 false；sealed 后任何 ID/profile/schema drift 都使 signoff 失效 |
| fresh schema 由 versioned schema plan 建立 | 避免凭文件名猜顺序或用 Hibernate 偷建表 | exact MySQL 8.0.44、empty/owned DB、forward-only digests、reapply、最终 `validate` 全部 must-pass |
| runtime IDs 由 server 生成并 seal，不再预写 synthetic primary keys | 保持现有生产 API/identity 语义 | tenant/name 可 synthetic；userId、physicalWorkerId、modelConfigId 以 API 响应为准并只记录 content-free 值 |
| closed provisioning 只使用现有 production APIs | 避免新增 activation bootstrap 后门或 direct DML | synthetic user runtime lane、Worker lifecycle lane、provider lane、activation control lane、DB migration lane 继续分离 |
| 旧 runId/target execution packet 已退休 | 它绑定旧 candidate 与未 provision 的 exact tuple | 新 signoff 后必须由 owner 重新确认新的 exact runId、targetId、model、profile paths、window、cost 与 maximumSubmissions=1 |

## Confirmed Target Decisions

### Q1 — Exact activation target class: `ISOLATED_LOCAL_NON_FIXTURE`

- owner decision: 新建 disposable local Navigator/MySQL/Worker namespace；不复用当前
  8112、现有数据库、当前 Docker project 或任何现存 Worker。
- rejected alternatives: `EXISTING_LOCAL_DEV_STACK` 与
  `DEV_KVM_OR_PRODUCTION_LIKE` 不属于本批准范围；若改选必须 `NEEDS_REPLAN`。

### Q2 — Dedicated Codex provider evidence lane: `REAL_CODEX_MODEL`

- owner decision: dedicated repo-source Worker 在后续一次性 canary execution 中使用 real
  Codex model；不得复用 3053/3151 或当前 3051 的进程、state、port、credential carrier。
- credential lane: exact provider credential 仅存在于 target-owned gitignored、权限受限
  profile；tooling 只验证 presence/permissions/allowlisted variable names，不读取值，不把
  profile 投影给 admin/control/runtime evidence collector。
- model/prompt/cost boundary: actual execution 必须在单独授权中声明 exact model，使用
  synthetic static no-tool prompt，最多一次 model submission；任何 tool/business access
  request 都 fail closed。模型名、submission count、terminal status 可进入脱敏 evidence，
  credential、prompt body 与 model response 不得进入。

### Q3 — Fresh schema bootstrap: `SEALED_FORWARD_SCHEMA_PLAN`

- implementation 必须产出一个 non-secret schema plan；plan 只允许列出 forward SQL，按
  dependency-safe 顺序绑定相对路径与 SHA-256，并计算整体 digest。不得自动扫描后直接
  执行未知新增文件，也不得包含 rollback、test fixture 或 Hibernate-generated DDL。
- executor 必须在写入前证明 exact Compose project、MySQL `8.0.44`、loopback、unique
  database 与空库/owned state；任一不匹配零写入拒绝。每个 SQL 的开始/完成/失败只记录
  path hash、schema digest、计数和 reason code，不记录 DB credential。
- fresh apply、同 plan reapply 与 candidate launcher Hibernate `validate` 必须在 disposable
  MySQL 上通过；schema plan 或 entity/migration inputs 变化会使对应 evidence 失效。

### Q4 — Runtime provisioning and identity seal: `PRODUCTION_API_GENERATED_IDS`

- closed provisioning 首先以 activation control/admission 均 false 启动 schema-valid 的
  loopback Navigator；不得调用 lifecycle activation control mutation，也不得创建
  non-fixture `ENFORCED` aggregate。
- 使用随机 synthetic username/password 注册指定 synthetic tenant 的用户，通过其
  authenticated production lane 注册 dedicated Physical Worker；把 server-generated userId
  与 workerId 写入 target-owned seal。Worker profile 随后绑定该 workerId，专用 Worker
  才可启动并通过 authenticated health/readiness。
- 使用同一 tenant/user production config API 创建 `OPENAI_CODEX` restricted modelConfig，
  provider key/base URL 从 provider profile 在进程内传输且不进入 argv/log；allowed worker
  仅为上述 generated physical Worker。把 server-generated modelConfigId 写入 seal。
- bootstrap JWT/password 仅在 provisioning 期间存在于独立 `0600` profile；sealed 前转为
  窄 runtime credential 或等价受支持 lane，并删除短期 bootstrap bearer material。runtime、
  Worker lifecycle、provider、activation control 与 database profiles 不得合并。
- provisioning 完成后验证 exact tenant/user/Worker/modelConfig grant、Worker build/protocol/
  capabilities、working directory 与 provider configuration presence，随后停止 Navigator、
  Worker、MySQL，生成 immutable content-free seal 与 final target manifest。任何后续 drift
  都要求重新 provision/seal/signoff，不允许原地修补后继续。

## Acceptance Criteria

- [x] ACT-AC1: default config、共享栈和 protected Worker domains 保持 closed/untouched；
  任一 non-fixture activation authority 缺失时返回稳定 deny，provider effect 为零。
- [x] ACT-AC2: production activation request 不接受 client/operator 自报
  `repoOwnedFixture`、`activationEvidencePresent`、tuple/readiness/proof booleans；所有
  authority 从 server-side exact target state 解析。
- [x] ACT-AC3: target manifest 枚举 controller/supervisor/manual launcher/CI/timer，计算
  canonical digest，并证明 disable/scale-zero 与 late-relaunch 结果；未知或只凭端口为零
  均 fail closed。
- [x] ACT-AC4: unique active writer generation、instance registration、target commit/protocol
  与 DB-time proof lease 可 acquire/renew/expire/loss；observer drift 原子 quarantine，继承
  `LEGACY_WRITER_EXCLUSIVITY_LOST > WORKER_STATE_LOSS > EVIDENCE_CONFLICT > NONE`。
- [x] ACT-AC5: 新 Session/Task 的 real production admission 在 provider effect 前原子完成
  lane reservation、initial fact/snapshot、Worker/Session/Task proof references、durable
  dispatch/outbox；任一失败无 provider effect、无半 enrollment。
- [x] ACT-AC6: exact allowlist 只接受新建 synthetic aggregate、`codex-biz-worker`、receipt
  enabled、matched identity/generation/epoch/build/protocol/capabilities/binding；所有 cross-
  tuple、receipt-disabled、SHADOW/ENFORCED mismatch 和 stale proof 均稳定拒绝。
- [x] ACT-AC7: proof loss、lease expiry、identity drift、Worker unavailable 与 late controller
  relaunch 在 quarantine 后继续 fail closed；successful checkpoint 不恢复 READY/NONE，
  provider effect 不增加。
- [x] ACT-AC8: disposable harness 的 doctor 默认零写入，拒绝 8112、共享 DB、非 loopback、
  protected Worker ports/homes、无法证明 cwd/runId 的进程与非 owned Docker resource；
  cleanup 只处理 exact owned target。
- [x] ACT-AC9: exact MySQL 8.0.44 验证 forward/reapply、Hibernate validate、activation
  metadata、proof/reference/outbox、rollback floor 与 destroyed-target cleanup；不访问共享 DB。
- [x] ACT-AC10: implementation 只到 `READY_FOR_SIGNOFF`；生成脱敏 candidate/evidence
  manifest 和独立 activation signoff prompt，但不打开 gate、不执行真实 canary、不部署/
  restart/publish/push/tag/release。
- [x] ACT-AC11: fresh exact MySQL 8.0.44 只按 sealed schema plan 建库；plan 的
  ordered paths/per-file digests/aggregate digest、empty-owned preflight、fresh apply、reapply
  和 candidate Hibernate `validate` 均可审查；Hibernate `create` 只在 Ultra implementation
  的隔离 baseline 生成 fixture 使用一次，execution 只消费 tracked SQL，禁止 Hibernate
  `update/create`。
- [x] ACT-AC12: closed provisioning 在两个 activation switches 为 false 时，只通过现有
  production APIs 创建 synthetic user、dedicated Physical Worker、restricted
  `OPENAI_CODEX` modelConfig/grant 与 runtime credential；server-generated IDs 被捕获，
  direct application-table DML、fixture 与 activation mutation 均为零。
- [x] ACT-AC13: final target manifest/seal 绑定 generated userId/workerId/modelConfigId、
  tenant、Worker endpoint/build/protocol/capabilities、schema/candidate/controller digests 与
  profile path digests；sealed target 全部停止，doctor/cleanup-plan 零写入且任何 drift 拒绝。
- [x] ACT-AC14: bootstrap bearer/password 在 seal 前被清除；database、runtime、Worker、
  provider 与 activation-control lanes 保持独立 `0600` regular files，credential 值、prompt
  body 和 model response 不进入 argv/log/manifest/evidence。
- [x] ACT-AC15: 旧 activation signoff、旧 execution packet 与 runId/targetId 被明确标记为
  retired/superseded；新 candidate 完成 focused/affected validation 后重新生成 digest、
  evidence manifest、independent prompt，并停在 `READY_FOR_SIGNOFF + activation_gate=CLOSED`。

## Contract / Data / Security Constraints

- API or event contract: 优先使用内部 operator/readiness contract；若新增 HTTP route，必须
  采用独立 control authority、RX structured error、授权配置与 deny contract tests，且
  runtime/admin credential 不得调用。不得改变 public Open SDK/Worker-v1 wire，除非
  `NEEDS_REPLAN`。本 replan 不批准新增 exact-ID bootstrap HTTP route；closed provisioning
  必须组合现有 production auth/Worker/modelConfig API。
- data and migration: 只允许 additive schema；exact MySQL 验证后仍不得在共享/production
  DB 自动迁移。fresh target 只执行 sealed forward schema plan；禁止 Hibernate
  `update/create`、direct application-row DML 与未列入 plan 的 SQL。activation metadata
  必须绑定 target/runId/candidate，不存 secret。
- compatibility and rollback: 真实 enrollment 前可通过关闭 target gate并销毁 owned
  target 回退；首次 enrollment 后不得回滚 legacy binary或把 ENFORCED 改回 LEGACY。
- permissions and secrets: upstream admin、ClientApp control、runtime、Worker lifecycle、
  provider credential lanes 分离；evidence 只记录 hash/ID/status/reason，不记录 secret、
  prompt body、模型回复或真实业务数据。closed provisioning 的短期 bearer/password 是独立
  bootstrap lane，seal 前必须清除，不得升级为 activation authority。

## Test and Evidence Obligations

| Item | Classification | Risk | Required Validation | Reusable Evidence | Required Evidence |
|---|---|---|---|---|---|
| trusted activation authority | must-pass | critical | unit + integration negative matrix | ARCH-001 gate/source tests only as baseline | caller spoof attempts, exact deny codes, effect=0 |
| generation/proof observer | must-pass | critical | deterministic DB-time integration + concurrency | fifth-round precedence/MySQL evidence | acquire/renew/loss/drift/quarantine counts |
| atomic production enrollment | must-pass | critical | failure-first production-topology integration | ARCH-001 Slice 8 topology partially reusable | W/S/T refs, transaction rollback, provider count |
| target ownership doctor | must-pass | critical | offline shell/Node/Python tests | INT-001 safety patterns only | rejects shared/protected/unowned targets |
| MySQL activation state | must-pass | critical | exact MySQL 8.0.44 targeted integration | ARCH-001 migration evidence where inputs unchanged | version, schema, rows, rollback/cleanup markers |
| Worker lifecycle contract | must-pass | critical | focused real-router contract; affected Node tests/typecheck/build if changed | fourth/fifth Node and Codex evidence when inputs unchanged | exact test counts/exits and reuse rationale |
| public/receipt/SHADOW compatibility | must-pass | critical | affected Java/public SDK tests when inputs change | accepted ARCH-001 evidence | no wire/config regression |
| candidate hygiene | must-pass | major | diff check, secret scan, activation default/source audit | none | exact commands/results |
| sealed fresh-schema plan | must-pass | critical | plan contract tests + fresh/reapply/validate on exact MySQL 8.0.44 | prior entity/migration tests only where inputs unchanged | ordered file/digest manifest, empty-owned preflight, SQL counts, validate result |
| closed production-API provisioning | must-pass | critical | local loopback integration with real auth/Worker/modelConfig controllers and a non-model-calling Worker | existing API unit tests only as baseline | generated IDs, grant/readiness result, zero activation mutation/effect, no direct DML |
| target seal and drift rejection | must-pass | critical | stopped-target doctor/plan/seal tests including tamper matrix | existing doctor safety tests where inputs unchanged | seal/manifest digests, stopped counts, drift reason codes |
| credential lane lifecycle | must-pass | critical | profile shape/permissions, argv/log redaction, bootstrap-material purge tests | prior secret scan patterns | lane inventory by variable names only, purge counts, secret scan zero |

## Validation Budget and Evidence Sufficiency

- assurance_level: elevated
- lightweight_validation (`<5m`): schema-plan/provision/seal contract tests、focused authority
  resolver/gate tests、doctor safety tests、config/default audit、diff check、changed-surface
  secret scan。
- medium_validation (`5-30m`): exact MySQL 8.0.44 fresh plan apply/reapply/validate、closed
  production-API provisioning integration、production enrollment/proof concurrency topology、
  affected Session/Codex/Node lanes；只在对应 inputs 改变时重跑。
- expensive_validation (`>30m`): real target controller disable/stop/start、late-relaunch、
  first non-fixture enrollment rehearsal；implementation 阶段 `not-approved/not-run`。
- large_authority_or_replay_policy: prohibited-unless-user-approved
- full_chain_recommendation_trigger: 仅在 implementation 已 `READY_FOR_SIGNOFF`、独立 reviewer
  确认 exact target/tooling/evidence 后，才可建议一次 30–60 分钟 bounded canary execution。
- estimated_full_chain_wall_clock: 30–60 分钟，依据 ARCH-001 原 activation rehearsal 预算；
  只含一个 disposable target、一个新 Session/Task 和 fault/stop checks，不含 live SIM。
- full_chain_prerequisites: new independent authorization、sealed exact runId/ports/database/
  generated user/Worker/modelConfig IDs、Worker cwd、candidate/schema/controller/target-seal
  identity、credential/provider lane、owned cleanup、用户对最大一次执行的重新明确授权。
- user_approval_status: 2026-08-01 replan and non-model-calling implementation validation
  approved; prior activation execution authorization superseded before first start; new actual
  activation/rehearsal/model submission not-requested/not-approved
- decision_if_not_approved: 保持 gate CLOSED，只完成 focused/affected validation 和
  signoff package，不执行 controller/process mutation或 non-fixture enrollment。
- expensive_validation_trigger: independent signoff verdict explicitly authorizes one run and
  user separately confirms execution target/window
- maximum_expensive_attempts: 1；环境失败或 cleanup/ownership 不确定时停止并
  `NEEDS_REPLAN`，不得自动第二次。
- reusable_evidence: ARCH-001 fifth signoff、第四/第五轮 raw logs、connected Slice 8、Node
  267/Codex 498 与旧 activation authority tests，只在 source/test assumptions 未变化时复用；
  旧 target manifest、旧 activation signoff、旧 execution packet、旧 preflight readiness 与
  依赖旧 bootstrap assumptions 的 MySQL/target evidence 不可用于新 go/no-go。
- stop_when_evidence_is_sufficient: ACT-AC1～ACT-AC15 均映射到当前 source + raw exit/log，
  fresh schema plan、closed provisioning、generated-ID seal、stopped target doctor/
  cleanup-plan 通过，default gate closed，无 blocker/secret/deviation；此时停止并设
  `READY_FOR_SIGNOFF`，不得在 implementation 会话执行 canary。
- validation_not_required: frontend/UI、live SIM/TMS、真实业务 ask、production P3、release/
  package/OBS、全 provider smoke、历史 Task replay。
- non_product_failure_stop_rule: 同一 medium lane 连续两次因环境/依赖失败则停止并
  `NEEDS_REPLAN`，不自动第三次。

## Waiver Policy

- waivable_items: raw evidence tracked portability、与本 contract 无依赖的 environment-gated
  skips；必须记录且不能影响 authority conclusion。
- authorized_role: project owner
- non_waivable_guards: exact target ownership、server-side authority、controller exclusivity、
  generation/proof monotonicity、atomic enrollment、provider-effect zero negatives、MySQL data
  safety、sealed forward schema plan、production-API-only provisioning、generated-ID seal、
  credential separation/purge、activation default CLOSED。
- required_risk_record: 每个省略项写明受影响 AC、现有证据是否仍有效和 go/no-go 影响。

## Risks and Open Questions

- known_risks:
  - 当前 source fixture 与 persistence primitives 不能直接外推为可操作 activation；若
    实现发现需要改变 ARCH-001 authority/state/wire/migration/security 决策，必须
    `NEEDS_REPLAN`。
  - 当前本机已有多个长期运行 Worker 和 sibling Docker resources；ownership doctor
    误判可能造成越权停止或数据删除，因此任何不确定都必须拒绝。
  - controlled provider substitute 只能证明 lifecycle/effect chain；不能证明真实 Codex
    provider、external network 或 production readiness。
  - historical forward migrations 可能不能直接形成 dependency-safe fresh plan；若必须修改
    既有 migration 语义、引入 destructive/backfill SQL 或依赖 Hibernate-generated DDL，
    设置 `NEEDS_REPLAN`，不得用本地顺序猜测继续。
  - 现有 production API 若无法在 activation disabled 状态完成最小 synthetic user/
    Worker/modelConfig/grant provisioning，应报告精确缺口并 `NEEDS_REPLAN`；本批准不允许
    新增任意-ID bootstrap endpoint、direct DML 或复用 shared admin resources。
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、根 `AGENTS.md`、ARCH-001 canonical/第五轮签收、GOV-001-P3、
  `navigator-runtime-provisioning` 与相关模块指引。
- Q1～Q4 已由 owner replan 授权；按 `ISOLATED_LOCAL_NON_FIXTURE + REAL_CODEX_MODEL +
  SEALED_FORWARD_SCHEMA_PLAN + PRODUCTION_API_GENERATED_IDS` 实施。
- 在 scope 内自主定位实现文件；不得把业务编排放入 launcher，不得创建第二套 lifecycle
  状态机或 recovery protocol。
- regression-first 验证 caller-spoof、半 enrollment、proof drift/relaunch 与 shared-target
  reject，并新增 schema plan 顺序/digest/tamper、closed provisioning、generated-ID seal、
  bootstrap credential purge 的失败测试，再实现并跑绿。
- 不得读取 secret 内容、连接真实业务数据、停止/重启任何非 runId-owned process，或操作
  当前 8112/3031/3051/3053/3061/3151/3161。
- 未经独立 signoff + 用户单独执行授权，不得运行 >30 分钟 rehearsal、修改真实 controller、
  打开 activation gate 或创建 non-fixture ENFORCED aggregate。
- implementation 可运行不调用模型的 disposable closed-provisioning integration，但只允许
  target-owned loopback resources；两个 activation switches 必须保持 false，结束时 target
  全停且 bootstrap bearer/password 已清除。不得使用当前遗留 target root 的旧 manifest
  作为新 signoff target。
- 达到 evidence sufficiency 后回写 `Implementation Result`、changed paths、精确命令/
  counts/exits、reuse、deviations、residual risks，并设 `READY_FOR_SIGNOFF`；不得设
  `ACCEPTED` 或 `AUTHORIZED_FOR_ONE_BOUNDED_CANARY`。

## Implementation Result

### Status and implementation summary

- replan_status: `APPROVED_FOR_ULTRA_IMPLEMENTATION`
- prior_implementation_status: `READY_FOR_SIGNOFF`（以下记录是被 replan 前候选的历史结果，
  仅供 evidence reuse 判断；不得解释为新 ACT-AC11～ACT-AC15 已实现。）
- activation_gate: `CLOSED`
- actual_activation_authorization: `superseded-before-first-start`
- source_identity: candidate baseline HEAD
  `fdef79c9c55e7de9a5b01822c3c9dc0c75ca2e00` on the unchanged `main`
  worktree; the full dirty candidate digest and digest command are recorded in
  `temp/test-artifacts/ARCH-001-ACT-001/candidate-evidence-manifest.json` to
  avoid a self-referential digest in this canonical file.
- implementation_summary:
  - Added a server-side exact-target authority whose inputs are restricted to
    target-owned manifest/observation artifacts, persisted target/generation/
    instance/proof state, database time and authenticated Worker readiness.
    Missing or mismatched authority returns stable deny codes; request bodies
    cannot carry fixture/evidence/readiness/proof booleans.
  - Added unique active generation and Navigator instance registration,
    canonical six-controller inventory digest verification, proof acquire/
    renew/expiry/loss observation and irreversible target/generation/instance/
    proof-reference quarantine. Existing conflict precedence remains
    `LEGACY_WRITER_EXCLUSIVITY_LOST > WORKER_STATE_LOSS > EVIDENCE_CONFLICT >
    NONE`; a successful checkpoint still cannot clear a conflict.
  - Connected only a new synthetic `codex-biz-worker` Session/Task production
    lane. Reservation occurs in the task-create transaction and dispatch waits
    for `AFTER_COMMIT`; before the lazy provider subscription the admission
    transaction establishes Worker/Session/Task `ENFORCED` snapshots, an
    initial fact, all three proof references and a durable authorized outbox
    effect. Accepted Worker disposition is re-bound to exact identity,
    dispatch and JCS binding digest before the one-shot target is consumed.
  - Added authenticated Worker health/readiness adaptation without changing
    public Open SDK or Worker-v1 wire. Receipt-disabled, SHADOW, wrong tuple,
    stale proof, identity/capability/build/protocol mismatch and non-new
    aggregates remain fail closed; default non-activation paths remain SHADOW.
  - Added additive activation metadata migration and rollback floor, an
    internal no-body RX control surface protected by a target-owned constant-
    time control token, route-catalog authorization records, and an isolated
    harness/runbook. Doctor is live-inspection-only by default and rejects the
    current/shared/protected ports, databases, Docker projects, homes,
    processes and resources; cleanup is exact-owned and separately confirmed.

### Changed paths

- exact-target authority and production admission:
  - `session-module/src/main/java/com/foggy/navigator/session/controller/LifecycleActivationController.java`
  - `session-module/src/main/java/com/foggy/navigator/session/lifecycle/{DatabaseLifecycleAuthorityClock,FileLifecycleActivationArtifactSource,LifecycleActivationArtifactSource,LifecycleActivationAuthorityService,LifecycleActivationControlAuthorizer,LifecycleActivationDeniedException,LifecycleActivationManifest,LifecycleActivationObserver,LifecycleActivationProperties,LifecycleActivationReason,LifecycleAuthorityClock,LifecycleProductionAdmissionService}.java`
  - `session-module/src/main/java/com/foggy/navigator/session/lifecycle/persistence/LifecycleActivationTargetEntity.java`
  - `session-module/src/main/java/com/foggy/navigator/session/lifecycle/repository/LifecycleActivationTargetRepository.java`
  - `session-module/src/main/java/com/foggy/navigator/session/{config/SessionModuleAutoConfiguration.java,lifecycle/LifecycleEnrollmentGate.java,lifecycle/LifecycleSchemaReadiness.java}`
  - `session-module/src/test/java/com/foggy/navigator/session/lifecycle/{LifecycleActivationAuthorityContractTest,LifecycleProductionActivationIntegrationTest,WorkerLifecycleReconciliationConflictPrecedenceIntegrationTest,LifecycleMigrationContractTest,LifecycleMigrationMySqlIntegrationTest,IsolatedEnforcedLifecycleContractTest,LifecycleSchemaReadinessTest}.java`
- Worker readiness and pre-effect dispatch integration:
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/lifecycle/{WorkerLifecycleActivationReadiness,WorkerLifecyclePort}.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/{client/CodexWorkerClient.java,lifecycle/CodexLifecycleBindingDigest.java,lifecycle/CodexWorkerLifecycleHttpAdapter.java,service/CodexStreamRelay.java,service/CodexTaskService.java,spi/CodexWorkerFacadeImpl.java}`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/{lifecycle/CodexLifecycleBindingDigestTest.java,lifecycle/CodexWorkerLifecycleHttpAdapterTest.java,lifecycle/CodexWorkerLifecycleNodeContractIntegrationTest.java,service/CodexStreamRelayTest.java,service/CodexTaskServiceTest.java}`
- schema, configuration and authorization catalog:
  - `docs/migration/{2026-07-30-arch-001-lifecycle-owner.sql,2026-07-30-arch-001-lifecycle-owner-rollback.sql,2026-08-01-arch-001-activation-readiness.sql}`
  - `launcher/src/main/resources/application.yml`
  - `user-auth-module/src/main/java/com/foggy/navigator/auth/config/SecurityConfig.java`
  - `navigator-common/src/main/{java/com/foggy/navigator/common/authorization/AuthorizationRouteCatalog.java,resources/authorization/route-manifest-v1.csv}` and their common/launcher route-catalog tests
  - `docs/version-tracker/1.4.3-SNAPSHOT/evidence/GOV-001-p0.5-method-route-manifest.csv`
- disposable package and delivery records:
  - `tools/arch001-activation/{activation_target.py,README.md,templates/**,tests/test_activation_target.py}`
  - `docs/version-tracker/1.4.3-SNAPSHOT/runbooks/ARCH-001-ACT-001-disposable-activation-target.md`
  - this canonical work item.
- baseline_note: all other dirty paths belong to the accepted ARCH-001 parent
  candidate and were preserved. They remain part of the full candidate digest;
  no existing dirty change was cleaned, reset, reverted, formatted or
  overwritten.

### Validation commands and results

All raw logs and one-line exit files are under
`temp/test-artifacts/ARCH-001-ACT-001/`. The commands below were run from the
repository root unless a working directory is stated. Every listed final run
has exit `0`.

| Evidence | Exact command | Result |
|---|---|---|
| `04-node-focused` | `(cd tools/codex-agent-worker && node --import tsx --test tests/lifecycle-contract.test.ts tests/query-route-paths.test.ts)` | 44 tests; 44 pass, 0 fail, 0 skip |
| `05-java-authority-focused` | `mvn test -pl session-module -am -Dtest=LifecycleActivationAuthorityContractTest,LifecycleProductionActivationIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false` | 20 tests; 0 failure/error/skip |
| `06-node-typecheck` | `(cd tools/codex-agent-worker && npm run typecheck)` | TypeScript check passed |
| `07-node-build` | `(cd tools/codex-agent-worker && npm run build)` | Worker build passed |
| `08-node-full` | `(cd tools/codex-agent-worker && npm test)` | 267 tests; 265 pass, 0 fail, 2 existing Windows-only skips |
| `09-codex-java-focused` | `mvn test -pl addons/codex-worker-agent -am -Dtest=CodexWorkerLifecycleHttpAdapterTest,CodexLifecycleBindingDigestTest,CodexWorkerLifecycleNodeContractIntegrationTest,CodexStreamRelayTest,CodexTaskServiceTest -Dsurefire.failIfNoSpecifiedTests=false` | 202 tests; 0 failure/error/skip |
| `10-session-affected-focused` | `mvn test -pl session-module -am -Dtest=WriterExclusivityProofConcurrencyIntegrationTest,LifecycleMigrationContractTest,IsolatedEnforcedLifecycleContractTest,WorkerLifecycleReconciliationConflictPrecedenceIntegrationTest,LifecycleActivationAuthorityContractTest,LifecycleSchemaReadinessTest,WriterExclusivityProofServiceTest,LifecycleProductionActivationIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false` | 32 tests; 0 failure/error/skip |
| `11-mysql-8.0.44` | `ARCH001_ACTIVATION_MYSQL_TEST_ENABLED=true mvn test -pl session-module -am -Dtest=LifecycleMigrationMySqlIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false` | 1 test; exact `mysql:8.0.44`, DB reports 8.0.44; forward/reapply, third remediation/reapply, Hibernate validate, activation target/generation/instance/proof, 3 proof refs, completed outbox, unique generation, destroyed-target cleanup and rollback floor passed |
| `12-activation-harness` | `PYTHONPATH=tools/arch001-activation python3 -m unittest discover -s tools/arch001-activation/tests -p 'test_*.py' -v` | 10 tests; all passed, including protected/shared/unowned/late-relaunch rejects, live observation/watch and exact cleanup argv |
| `13-session-full` | `mvn test -pl session-module -am` | Session 512 tests; 0 failure/error, 1 existing environment-gated skip; reactor success |
| `14-codex-full` | `mvn test -pl addons/codex-worker-agent -am` | Codex 501 tests; 0 failure/error/skip; reactor success |
| `15b-route-catalog-focused` | `mvn test -pl launcher -am -Dtest=AuthorizationContractTest,AuthorizationRequiredSectionCatalogRegressionTest,AuthorizationRouteManifestCoverageTest -Dsurefire.failIfNoSpecifiedTests=false` | 14 tests (common 11 + launcher 3); all passed |
| `15-launcher-affected` | `mvn test -pl launcher -am` | all 14 reactor modules success; launcher 23 tests, 0 failure/error, 2 environment-gated skips; includes Session 512 and Codex 501 |
| `16-target-doctor-preflight` | `PYTHONPATH=tools/arch001-activation python3 tools/arch001-activation/activation_target.py doctor --manifest /home/sa/workspace/Foggy-Navigator/temp/test-artifacts/ARCH-001-ACT-001/target-skeleton-arch001-act001-impl-20260801/activation-target-manifest.json` | stopped credential-free skeleton; live inspection ready, `phase=preflight`, `writesPerformed=0`; no PID/container/listener/observation created |
| `17-cleanup-plan` | `PYTHONPATH=tools/arch001-activation python3 tools/arch001-activation/activation_target.py cleanup-plan --manifest /home/sa/workspace/Foggy-Navigator/temp/test-artifacts/ARCH-001-ACT-001/target-skeleton-arch001-act001-impl-20260801/activation-target-manifest.json` | `execute=false`, `executionAuthorized=false`, `writesPerformed=0` |

The final candidate hygiene evidence additionally records `git diff --check`,
default/source/wire/catalog audit, changed-surface secret-shaped assignment
scan, full candidate digest and a repeat of doctor/cleanup-plan after binding
that digest. Those files are named `18-*` through `22-*` plus
`candidate-evidence-manifest.json` in the same evidence directory.

### Acceptance mapping

- ACT-AC1/AC2: default/source audit, authority contract and production
  integration negatives prove disabled/missing/spoofed authority rejects before
  Worker resolution/provider effect. Security tests prove runtime/admin/Worker/
  provider credentials are not the activation control token.
- ACT-AC3/AC7/AC8: harness 10-test matrix plus live stopped-target doctor prove
  the canonical six-controller contract, late/unknown-controller and protected/
  shared/unowned rejection, zero-write preflight, loss observation and exact
  revalidated cleanup boundary.
- ACT-AC4: authority integration, concurrency/precedence tests and exact MySQL
  state verify generation/registration/DB-time proof lifecycle, expiry/drift/
  loss quarantine and non-recovery after successful checkpoints.
- ACT-AC5/AC6: production activation and Codex relay tests verify reservation,
  `AFTER_COMMIT`, atomic W/S/T snapshots + three refs + fact + durable outbox
  before subscription, exact JCS/identity/disposition matching, one-shot target
  consumption and provider-effect-zero negative paths.
- ACT-AC9: `11-mysql-8.0.44` is a fresh isolated exact-version run against the
  final migration/entity inputs.
- ACT-AC10: the prior candidate state was only `READY_FOR_SIGNOFF`; both switches
  and gate were closed, and no real target/model/controller/non-fixture
  aggregate operation was performed. This historical mapping does not satisfy
  the new ACT-AC11～ACT-AC15.

### Evidence reuse, deviations and residual risks

- reused_evidence:
  - `ARCH-001-independent-fifth-remediation-signoff-2026-07-31.md` remains the
    accepted source baseline for unchanged parent ownership/conflict semantics.
  - Parent dirty-tree Node/Claude/business/runtime evidence was reused only
    where inputs were unchanged. Activation-sensitive Node, Session, Codex,
    route-catalog and full Launcher lanes were rerun on this candidate; MySQL
    evidence was not reused because migration/entity inputs changed.
- regression_first_and_remediation_record:
  - `01-*` and `02-*` are the expected missing-harness/missing-authority red
    baselines; `03-*` preserves compile/integration red iterations.
  - `05a-*` records a stable capability-mismatch expectation correction;
    `12a-*` records a wrong test entrypoint cwd and `12b-*` a transient syntax
    error while removing an unapproved Worker-wire experiment.
  - `13a-*`/`13b-*` exposed and then fixed test-configuration leakage;
    `15a-*` exposed five missing activation ingress catalog rows. Final
    `05`, `12`, `13`, `15b` and `15` runs are green. Failed logs were retained.
- deviations: none from approved goal, target class, provider lane, public SDK
  or Worker-v1 compatibility. A provisional activation-specific Worker header/
  epoch extension was removed before final validation because the frozen
  contract requires Worker-v1 unchanged; final source contains no such flag or
  wire field.
- residual_risks:
  - No real Codex credential was read and no model was called. The original
    implementation skeleton had empty credentials. The later superseded
    prestart target received an owner-injected provider profile, but its value
    was never read/logged, it remains mode `0600`, and it must be purged or
    explicitly replaced before new provisioning. Both activation switches
    remain false. A later execution owner must reconfirm a new exact runId,
    target, model, permission-safe target-owned profiles, one-submission
    cost/window and the new candidate/seal digests; any mismatch invalidates
    authorization.
  - No controller mutation, running observation, proof acquisition, target
    start, or non-fixture `ENFORCED` aggregate was performed. Those are the
    deliberately omitted expensive canary actions and require independent
    signoff plus a separate user execution instruction.
  - Raw evidence and the stopped skeleton are local/gitignored portability
    evidence. They contain variable names and hashes only, not credential
    values, prompt body or model response.
  - The successful launcher run emits the repository's existing Surefire fork
    shutdown warning after completion; exit is 0 and all reactor modules/tests
    report success, so it is not an acceptance blocker.
  - GOV-001-P3 remains `DRAFT/BLOCKED`; this local readiness package grants no
    production, external, dev-kvm, deployment or rollout authority.
- omitted_validation_and_reason: real activation/rehearsal/model submission,
  live controller disable/late relaunch and non-fixture enrollment were
  explicitly prohibited before independent signoff; no authorized check was
  omitted.
- prior_readiness: `READY_FOR_SIGNOFF`
- current_readiness: `READY_FOR_SIGNOFF`

### Replan implementation result (2026-08-01)

- implementation_state: `READY_FOR_SIGNOFF`
- activation_gate: `CLOSED`
- actual_activation_authorization: `not-requested/not-approved after replan`
- implementation_outcome:
  - Added `activation_bootstrap.py` with explicit `schema-plan-verify`,
    `schema-apply`, `schema-validate`, `provision`, `verify-readiness`, `seal`
    and separately confirmed `purge-credentials` phases. It has no target
    launcher, activation mutation, proof mutation or task/model submission
    command.
  - Schema apply now requires one live run-labelled Compose resource set
    (`mysql:8.0.44`, restart `no`, one container/network/volume), empty first
    schema, exact candidate HEAD, exact ordered plan and plan-digest confirmation
    for reapply. It rejects rollback/destructive/DML SQL and directory scanning.
  - Added a tracked 93-table current-schema baseline and a four-file plan whose
    aggregate digest is
    `04dd9964a037a283fb39754594a6317125b3cfa7d550d8780d0dc7a06ef28a47`.
    On an implementation-only isolated MySQL 8.0.44 fixture, fresh apply,
    full reapply and candidate launcher Hibernate `validate`/health all passed;
    the exact labelled container/network/volume and listeners were then removed.
  - Closed provisioning accepts null caller IDs only, uses the existing auth,
    Physical Worker, modelConfig and API-key routes, captures server-generated
    IDs, rewrites only the dedicated Worker profile, and deletes the bootstrap
    profile before writing a successful result. It never invokes a connection
    test or model/task route.
  - Readiness re-reads the exact production Worker/modelConfig records through
    the narrow runtime API key, verifies the restricted `OPENAI_CODEX` grant,
    then requires Worker build/protocol/capabilities/state-generation/instance-
    epoch and authenticated complete lifecycle inventory. Seal binds all result,
    schema-plan, candidate, generated-ID and profile-path digests; doctor reloads
    the sealed inputs and rejects post-seal result/artifact/profile drift.
- replan_changed_paths:
  - `tools/arch001-activation/activation_bootstrap.py`
  - `tools/arch001-activation/schema-plan-v1.json`
  - `tools/arch001-activation/activation_target.py`
  - `tools/arch001-activation/tests/{test_activation_bootstrap.py,test_activation_target.py}`
  - `tools/arch001-activation/templates/{activation-target-manifest.json.example,bootstrap.env.example,runtime-credential.env.example,worker.env.example,provider.env.example}`
  - `tools/arch001-activation/README.md`
  - `docs/migration/2026-08-01-arch-001-current-schema-baseline.sql`
  - `docs/version-tracker/1.4.3-SNAPSHOT/runbooks/ARCH-001-ACT-001-disposable-activation-target.md`
  - this canonical work item.
- replan_validation:
  - `PYTHONPATH=tools/arch001-activation python3 -m unittest discover -s
    tools/arch001-activation/tests -p 'test_*.py' -v`: 17 tests, 17 pass.
    Raw log/exit: `temp/test-artifacts/ARCH-001-ACT-001-replan/23-*`.
  - `activation_bootstrap.py schema-plan-verify ...`: four ordered files,
    exact HEAD/MySQL/file/plan digests, exit 0. Raw log/exit: `24-*`.
  - exact implementation fixture: 93 tables/1508 columns; fresh/reapply and
    candidate Hibernate validate/health pass; cleanup counts all zero. Sanitized
    raw record/exit: `25-*`.
  - `mvn -pl session-module -am
    -Dtest=LifecycleMigrationContractTest,LifecycleMigrationMySqlIntegrationTest
    -Dsurefire.failIfNoSpecifiedTests=false test`: build success; contract 1 pass,
    MySQL test 1 environment-gated skip. The required exact MySQL lane was run
    separately as `25-*`, so the skip is non-blocking. Raw log/exit: `26-*`.
  - `python3 -m py_compile ...` and scoped `git diff --check`: exit 0.
  - Post-recovery closed-provisioning harness: 18 tests, 18 pass, including
    partial production-API recovery without duplicate user/Worker creation.
    Raw log/exit: `temp/test-artifacts/ARCH-001-ACT-001-replan/27-*`.
- acceptance_progress:
  - ACT-AC11 is complete at implementation level with exact MySQL evidence.
  - ACT-AC12 passed on exact run
    `arch001-act001-provisioning-20260801-04`: production auth/Physical Worker/
    restricted modelConfig/runtime-key APIs generated all three IDs, while both
    activation switches stayed false. The Worker reached authenticated complete
    lifecycle inventory with zero active tasks; no provider or task route was
    called. Raw evidence: final target `evidence/07-*` through `09-*`.
  - ACT-AC13 passed: exact schema/provisioning/readiness results were produced,
    the exact Navigator, Worker and MySQL container were stopped; the one exact
    stopped Compose container/network/volume and its provisioned database were
    preserved for later sealed restart. Raw evidence: final target `evidence/06-*`
    through `10-*` and the sealed manifest/seal.
  - ACT-AC14 passed: the short-lived bootstrap profile was deleted before the
    provisioning result; remaining target-owned credential lanes are separate
    `0600` files and were neither printed nor treated as evidence.
  - ACT-AC15 passed at implementation handoff: the old packet remains retired;
    the post-replan candidate/evidence manifest and independent-signoff prompt
    are generated only after the final tracked candidate digest is fixed.
- blocker_to_ready_for_signoff: none. Independent activation signoff remains a
  separate fresh-session gate and this implementation/provisioning session is
  not independent.
- deviations: none from the approved replan. The implementation generated the
  tracked baseline once with Hibernate `create`; runtime `create/update` remains
  forbidden and was not used for fresh/reapply validation.
- retained_failure_iterations:
  - `03a` exposed unsafe shell quoting in the schema-count query before any
    schema object existed; the corrected query then passed fresh apply (`03`),
    full reapply (`04`) and live Hibernate validation (`08`).
  - `05a` and `05c` show that shell-sourcing a JDBC URL containing `&` lost the
    exact environment. The final launcher used an in-process profile loader,
    absolute artifact path and target cwd; `07`/`08` close both failures. A
    target-owned database runtime password was rotated once through stdin after
    the failed attempt; `05b` records only the safe action/result.
  - `06` rejected a relative artifact path as intended; the absolute-artifact
    relaunch passed as `08`.
  - `09a` retained the first production-controller rejection of invalid model
    category `CHAT`. Recovery `09b` required exactly one matching server-side
    user/Worker and prevented duplicate registration; final `09` used `GENERAL`,
    reused those generated IDs and passed. Focused recovery tests are part of
    the 18-test `27-*` result.
  - Provisioning run `arch001-act001-provisioning-20260801-02` completed schema,
    API and Worker readiness but was invalidated before signoff because cleanup
    used `docker compose down --volumes`; that destroyed the provisioned DB and
    made sealed restart impossible. Its `12-*` cleanup and `13-*` seal are
    retained failure evidence only. Fresh run `-03` repeated every critical lane
    with new server-generated IDs and used Compose `stop`, preserving exactly
    one stopped container/network/volume. `03a/04a` on `-03` also retain doctor
    self-cwd rejection; final `03/04` ran from outside the target root and pass.
  - Run `-03` then exposed a source/runbook mismatch: sealed preflight treated
    even an exact stopped MySQL container as late relaunch because live Docker
    inventory did not record running state. The final doctor now records and
    requires `running=false` for a preserved sealed 1/1/1 Compose resource set,
    still rejects resources during initial provisioning preflight, and requires
    `running=true` during running observation. The new 19-test `31-*` harness
    closes this regression; fresh run `-04` is the final sealed target.
- residual_risks:
  - The former `-04` independent signoff no longer covers the current candidate.
    Its exact Docker/database was destroyed and all six credential profiles were
    purged after the pre-submission stop; no credential lane remains reusable.
  - The owner explicitly authorized byte-for-byte reuse of the prior target's
    provider profile into the new target-owned provider lane. No credential
    value was read, logged or copied into tracked files/evidence.
  - No model, controller mutation, proof mutation or non-fixture ENFORCED
    aggregate was created. GOV-001-P3 remains blocked.

## 2026-08-01 Bounded Execution Stop and Edge Fixes

- authorized tuple: run `arch001-act001-provisioning-20260801-04`, target
  `arch001-act001-target-provisioning-20260801-04`, Worker `f8f32eeb`, modelConfig
  `0d2ed67c-97f5-4a11-b731-2cb16b264030`, model `gpt-5.6-sol`, maximum one
  submission, no retry, 60-minute window.
- outcome: `BLOCKED_BEFORE_MODEL_SUBMISSION`; task/session creation, provider
  effect, model submission and model cost all remained zero.
- observed closure gaps:
  - the `tsx` executable is a wrapper whose child owns the listener, so the
    sealed PID-file ownership check correctly rejected it; execution must use
    the direct Node loader form so the PID-file process owns the listener;
  - the sealed harness emits `NAVIGATOR_ARCH001_ACTIVATION_TARGET_V2`, while the
    server authority accepted only V1;
  - a clean stopped-target Worker restart preserves `stateGeneration` but creates
    a new `instanceEpoch`; proof acquisition rejected the retained clean
    `SHADOW/OFFLINE_FROZEN/NONE` snapshot before it could bind the new epoch.
- implementation adjustment:
  - authority now accepts only the sealed V2 schema;
  - proof acquisition permits epoch rebinding only for a same-generation,
    conflict-free `SHADOW/OFFLINE_FROZEN` snapshot. Any generation drift,
    non-SHADOW owner, non-frozen epoch change or conflict remains rejected.
  - added `cleanFrozenShadowWorkerCanBindItsRestartedInstanceEpoch` regression.
- validation:
  - `mvn -pl session-module -am -DskipTests=false
    -Dtest=LifecycleActivationAuthorityContractTest,LifecycleProductionActivationIntegrationTest
    -Dsurefire.failIfNoSpecifiedTests=false test`: 22 tests passed, 0 failed.
  - first schema-only correction run: 20 tests passed, 0 failed.
- cleanup:
  - explicit control-plane quarantine returned
    `LIFECYCLE_ACTIVATION_OPERATOR_STOP`; authority/admission/proof were closed;
  - database inspection before destruction showed 0 Session snapshots, 0 Task
    snapshots, 0 lifecycle effects, 0 SessionTasks and 0 proofs;
  - exact cleanup removed the `-04` container/network/volume and all three
    listeners; credential purge removed six profiles with no credential value
    logged. The preserved target root retains content-free records; none of the
    six credential profiles remains.
- evidence:
  `temp/test-artifacts/ARCH-001-ACT-001-replan/target-arch001-act001-provisioning-20260801-04/evidence/activation-execution-2026-08-01.json`.
- next gate: the pre-documentation candidate digest was
  `6611cf177cf584e501bba4f00fdea72945036984d73637c0a4a26cf05cfd84a5` before
  this documentation delta. A fresh disposable target, new final candidate
  digest/seal and independent signoff are required before any later real-model
  submission. The activation gate remains `CLOSED`.

## 2026-08-01 Fresh `-05` Bounded Execution and Sentinel Fence Remediation

- authorized boundary: run `arch001-act001-provisioning-20260801-05`, target
  `arch001-act001-target-provisioning-20260801-05`, model `gpt-5.6-sol`, one
  Navigator task-create POST, no retry, window
  `2026-08-01T14:30:35+08:00` through `15:30:35+08:00`.
- fresh target result:
  - exact MySQL `8.0.44` fresh apply/reapply and Hibernate `validate` passed;
    closed production-API provisioning generated user
    `773fe550-e153-41e7-a665-ef7cd9cba34d`, Worker `9f246202` and modelConfig
    `fe496dec-dc0a-44df-9899-a190d78f4b90` with activation switches false;
  - stopped manifest V2 and seal passed. Activation registration, proof acquire
    and readiness then all passed with `authorityReady=true`,
    `admissionGateOpen=true`, `proofActive=true`; this is live end-to-end
    evidence that the two `-04` authority fixes are effective;
  - exactly one task-create POST created task `20260801-79ca` and new Session
    `83826d21-5263-48e4-bdcd-e1b4e93e5e1f`. It terminated `FAILED` with safe
    code `LIFECYCLE_IDENTITY_FENCE_REJECTED`; no retry was attempted.
- fail-closed and rollback evidence:
  - `CodexStreamRelay` failed in
    `admitAndAuthorizeProviderEffect(...)` before `client.streamQuery(...)`;
    Worker query POST, provider-effect authorization and model submission are
    all zero. Token/cost/turn fields stayed null and result bytes stayed zero;
  - database inspection before destruction showed 0 Task lifecycle snapshots,
    0 Session lifecycle snapshots, 0 lifecycle facts, 0 outbox effects and 0
    proof references. The canonical failed Task has no providerTaskId. This
    proves the provider-effect admission transaction rolled back atomically.
- confirmed P1 root cause:
  - activation authority safely rebound the retained same-generation
    `SHADOW/OFFLINE_FROZEN/NONE` Worker snapshot from the provisioning epoch to
    the restarted epoch and set it READY at DB time `06:37:10.674654`;
  - the background Sentinel still held the old in-memory instance epoch. At
    `06:37:16.098789` it sent that stale expected fence to the real Worker's
    inventory endpoint, received the identity rejection and persisted
    `IDENTITY_CHANGED/OFFLINE_FROZEN`; the later provider admission correctly
    rejected that snapshot.
- minimal remediation:
  - `WorkerLifecycleSentinel` now treats readiness as the current fenced
    identity only when physical Worker and state generation remain exact. A
    clean same-generation instance-epoch rotation rebinds before inventory;
    physical identity or state-generation drift fails closed before inventory;
  - the Sentinel test port now enforces exact expected identity like the real
    Worker, so the same-generation/new-epoch regression fails on the old code
    and passes on the remediation;
  - changed paths for this edge fix are
    `session-module/src/main/java/com/foggy/navigator/session/lifecycle/WorkerLifecycleSentinel.java`
    and
    `session-module/src/test/java/com/foggy/navigator/session/lifecycle/WorkerLifecycleSentinelTest.java`.
- post-fix validation:
  - focused activation/Sentinel command:
    `mvn -pl session-module -am -DskipTests=false
    -Dtest=WorkerLifecycleSentinelTest,LifecycleProductionActivationIntegrationTest
    -Dsurefire.failIfNoSpecifiedTests=false test`: 22 tests, 0 failure/error/skip,
    reactor `BUILD SUCCESS`;
  - `mvn -pl session-module -am test`: reactor `BUILD SUCCESS`; Session module
    514 tests, 0 failure/error, 1 existing environment-gated skip;
  - `mvn -pl launcher -am -DskipTests package`: all 14 modules success and the
    repackaged Launcher was produced.
  - after adding the final null-readiness-identity fail-closed guard,
    `mvn -pl session-module -am -DskipTests=false
    -Dtest=WorkerLifecycleSentinelTest
    -Dsurefire.failIfNoSpecifiedTests=false test`: 6 tests, 0
    failure/error/skip, reactor `BUILD SUCCESS`.
- quarantine and cleanup:
  - target was explicitly quarantined with
    `LIFECYCLE_ACTIVATION_OPERATOR_STOP` immediately after the failed Task;
  - manifest-confirmed cleanup removed the exact container/network/volume and
    closed ports `18125/13055/13311`; six credential profiles were purged.
    Post-cleanup counts are zero and no credential value was read or logged;
  - sanitized local evidence:
    `temp/test-artifacts/ARCH-001-ACT-001-replan/target-arch001-act001-provisioning-20260801-05/evidence/12-canary-outcome-sanitized.json`.
- next gate: the remediation is source/test/build complete and remains
  `READY_FOR_SIGNOFF`, but it is not present in the sealed `-05` artifact. The
  `-05` no-retry authorization is consumed. A new candidate digest, fresh
  disposable target/seal, independent signoff and fresh explicit one-shot
  authorization are required before another real-model canary. Activation gate
  remains `CLOSED`; GOV-001-P3 remains blocked.

## 2026-08-01 Fresh `-06` Canary and Watcher Child-Process Remediation

- authorized boundary: run `arch001-act001-provisioning-20260801-06`, target
  `arch001-act001-target-provisioning-20260801-06`, model `gpt-5.6-sol`, one
  Navigator task-create POST, no retry, window
  `2026-08-01T16:08:05+08:00` through `17:08:05+08:00`.
- pre-submission evidence:
  - exact MySQL `8.0.44` fresh apply/reapply, Hibernate `validate`, production-
    API provisioning, authenticated Worker readiness, stopped seal and
    independent signoff R2 all passed;
  - target-root mode `775` initially caused the runtime authority to return
    `LIFECYCLE_ACTIVATION_MANIFEST_UNAVAILABLE`. Tightening this dedicated root
    to `0700` resolved the target-local preparation issue; registration, proof
    acquire and readiness then returned
    `LIFECYCLE_ACTIVATION_READY_FOR_ONE_BOUNDED_CANARY`;
  - after more than one Sentinel cycle, the Worker snapshot remained
    `SHADOW/READY/NONE`, retained state generation
    `0a18f401-3f4f-47d7-aa52-7a5383bc1720` and correctly rebound from the
    provisioning epoch to restart epoch
    `ba172a76-3eea-4776-9847-7531fd247600`. This is real-runtime confirmation
    that the `-05` stale-epoch P1 is resolved.
- unique canary result:
  - one and only one `POST /api/v1/tasks` created Task `20260801-e66e` and
    Session `a947c02b-b2ba-4efa-b383-1bc133b208a5`; no retry occurred;
  - the real Codex Worker/provider task was accepted and the task completed in
    one turn: status `COMPLETED`, 17055 input tokens, 48 output tokens,
    9796 ms, cost USD `0.034494`, 70 result bytes. Prompt and response bodies
    were not retained or logged as evidence;
  - the provider-effect outbox reached `COMPLETED`, provider Task ID was
    persisted, and the two durable facts were `TASK_DISPATCH_RESERVED` and
    `TASK_DISPATCHED`; this proves exactly one provider effect crossed the
    admission boundary.
- confirmed P1 lifecycle blocker:
  - while the Worker-owned Codex subprocess was running with cwd below the
    target workdir, `activation_target.py watch` classified every process below
    the target root that was not one of the two PID-file PIDs as an unknown
    controller;
  - the observer wrote `lateRelaunchDetected=true` and
    `unknownControllerCount=1` at `08:15:36Z`. The authority then quarantined
    the target with `LIFECYCLE_ACTIVATION_CONTROLLER_DRIFT` before the model
    result completed at `16:15:44+08:00`;
  - consequently Worker/Session/Task snapshots are
    `AUTHORITY_QUARANTINED/LEGACY_WRITER_EXCLUSIVITY_LOST`; Task and Session
    canonical phases remain `OPEN`, and all three proof references remain
    active. The real model success therefore does not satisfy lifecycle
    convergence acceptance.
- minimal remediation:
  - the live `/proc` scan now excludes a process only when its parent chain
    reaches an exact PID-file-owned Navigator or Worker runtime. Such processes
    are workload, not controllers;
  - independent, orphaned, re-parented, cyclic or otherwise unproven processes
    below the target root remain unknown and fail closed;
  - changed paths are
    `tools/arch001-activation/activation_target.py`,
    `tools/arch001-activation/tests/test_activation_target.py`, the harness
    README, this runbook and this canonical record.
- post-fix validation:
  - focused real-`/proc` regression launches an exact worker process with a
    child below the target root and separately launches an orphan controller:
    the child is excluded and the orphan remains unknown;
  - full harness suite: 21 tests passed, 0 failures/errors;
  - `py_compile` and scoped `git diff --check` passed.
- quarantine and cleanup:
  - the target was already fail-closed `QUARANTINED`; no second task/model
    submission was attempted;
  - manifest-confirmed cleanup stopped the exact Navigator and Worker and
    destroyed only the `-06` container/network/volume. Ports
    `18126/13056/13312` are closed and all six credential profiles were purged;
  - sanitized local evidence is under
    `temp/test-artifacts/ARCH-001-ACT-001-replan/target-arch001-act001-provisioning-20260801-06/evidence/`.
- next gate: the `-06` authorization is consumed and its target/database/
  credential lanes are destroyed. The watcher remediation is not part of the
  sealed `-06` candidate, so a new candidate digest, fresh stopped target,
  independent activation signoff and new explicit one-shot authorization are
  required before another real-model canary. Activation remains `CLOSED` and
  GOV-001-P3 remains blocked.

## 2026-08-01 Fresh `-07` Canary and Terminal-Fact Payload Remediation

- authorized boundary: run `arch001-act001-provisioning-20260801-07`, target
  `arch001-act001-target-provisioning-20260801-07`, model `gpt-5.6-sol`, one
  Navigator task-create POST, no retry, window
  `2026-08-01T16:28:15+08:00` through `17:28:15+08:00`.
- pre-submission evidence:
  - exact MySQL `8.0.44` fresh apply/reapply and Hibernate `validate` passed;
    closed production-API provisioning generated user
    `2b781cf5-be5f-4208-87ba-9896df62238b`, Worker `461a0f49` and modelConfig
    `4105150a-2c80-418d-b8b5-d9485cb4c93b` while activation remained closed;
  - stopped seal/doctor/cleanup-plan and independent signoff R3 passed;
    registration, proof acquire and readiness then returned
    `LIFECYCLE_ACTIVATION_READY_FOR_ONE_BOUNDED_CANARY`;
  - after a full Sentinel cycle the Worker stayed `SHADOW/READY/NONE`, retained
    state generation `51f2e830-c41c-49ad-bab6-a7fa554605b4` and rebound to the
    restart epoch `7105402d-6bd7-42f3-8771-685a48ee3f78`.
- unique model result:
  - exactly one `POST /api/v1/tasks` created Task `20260801-3ecd` and Session
    `b4cd3860-fd95-4d71-bd1b-3c78bed023b8`; no retry occurred;
  - provider Task `bfb3c31e-d9be-45c1-8eb3-63832313629d` completed on
    `gpt-5.6-sol`: 17060 input tokens, 64 output tokens, 11492 ms, one turn,
    USD `0.034632`, 70 result bytes. Prompt and response bodies were not
    retained or logged;
  - while the Worker-owned Codex and git child processes ran, the watcher kept
    `unknownControllerCount=0` and `lateRelaunchDetected=false`. This is live
    confirmation that the `-06` child-process classification P1 is resolved.
- confirmed P1 lifecycle blocker:
  - Worker authenticated inventory reached `COMPLETE` through sequence 3 and
    exposed one exact `TASK_PROVIDER_TERMINAL_OBSERVED/COMPLETED` fact with
    safe reason `PROVIDER_RESULT_OBSERVED`;
  - Navigator Sentinel repeatedly failed with
    `LIFECYCLE_FACT_PAYLOAD_INVALID`. Its database retained only
    `TASK_DISPATCH_RESERVED` sequence 0 and `TASK_DISPATCHED` sequence 2; both
    payloads were the valid two-byte JSON object `{}` with SHA-256
    `44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a`;
  - `TaskLifecycleOwnerService.ingestNormalizedBatch` saves the normalized
    terminal fact and then re-reads every aggregate fact as `TaskLifecycleFact`.
    The two empty admission payloads cannot satisfy that record's required
    `factId/type/sourceSequence`, so the transaction rolls back the new
    terminal fact and leaves Task/Session canonical phase `OPEN`.
- minimal remediation:
  - legacy empty content-free admission payloads are reconstructed only from
    their immutable fact ID/type/source-sequence columns, which are the only
    reducer inputs required for non-terminal admission facts;
  - new `TASK_DISPATCH_RESERVED` and `TASK_DISPATCHED` writes persist a full
    serializable content-free `TaskLifecycleFact` envelope instead of `{}`;
  - changed paths are
    `session-module/src/main/java/com/foggy/navigator/session/lifecycle/{TaskLifecycleOwnerService,LifecycleProductionAdmissionService}.java`,
    `session-module/src/test/java/com/foggy/navigator/session/lifecycle/{TaskLifecycleOwnerVerticalIntegrationTest,LifecycleProductionActivationIntegrationTest}.java`
    and this canonical record.
- validation:
  - the new vertical regression failed before the fix with the exact online
    exception chain `LIFECYCLE_FACT_ID_REQUIRED -> LIFECYCLE_FACT_PAYLOAD_INVALID`;
  - final focused production/owner tests: 24 tests, 24 passed, 0
    failure/error/skip；其中新增 negative 证明只有历史 RESERVED/DISPATCHED
    空 payload 可兼容，其他空 payload 继续 fail closed；
  - all 19 lifecycle test classes: 70 tests, 0 failure/error, one environment-
    gated MySQL skip; the required MySQL behavior is separately covered by the
    exact live `-07` MySQL 8.0.44 schema and fact-row evidence;
  - `mvn -pl session-module -am test`: reactor `BUILD SUCCESS`; Session module
    515 tests, 0 failure/error, one environment-gated skip. `git diff --check`
    for the changed Java/test paths passed. 70-test/515-test 全量结果产生在
    最终兼容分支收窄之前；收窄仅把非 admission 空 payload 从可重建改为拒绝，最终
    24-test 同时覆盖允许与拒绝分支，因此没有重复运行全量套件。
  - `mvn -pl launcher -am -DskipTests package`: all 14 reactor modules
    `SUCCESS`; repaired Launcher SHA-256
    `8c7edbd77d635b7b6a0812a3466a549f8d18ec83dc7014a0eb1e6b6318a3d3fa`.
- severity and scope:
  - severity is `P1 / activation blocker`: every real accepted ENFORCED task
    using these admission facts can finish at the provider but cannot commit
    canonical terminal state;
  - it is not P0: the gate is default closed, the canary remained isolated and
    fail-closed, no credential/business-data boundary was crossed, and no
    production rollout is authorized.
- quarantine and cleanup:
  - explicit operator quarantine changed the target to `QUARANTINED`, closed
    authority/admission/proof and preserved the observed OPEN/quarantined state
    before destruction;
  - manifest-confirmed cleanup stopped the exact Navigator and Worker, removed
    only the `-07` container/network/volume and closed ports
    `18127/13057/13313`; six credential profiles were purged and no credential
    value was logged;
  - sanitized local evidence:
    `temp/test-artifacts/ARCH-001-ACT-001-replan/target-arch001-act001-provisioning-20260801-07/evidence/{11-bounded-submission-ledger.json,12-canary-outcome-sanitized.json}`.
- next gate: the model submission and R3 authorization are consumed, while the
  fix is not part of the sealed `-07` artifact. Current implementation is
  `READY_FOR_SIGNOFF` with activation `CLOSED`. Any later real-model retest
  requires a newly computed candidate digest, fresh disposable target/seal,
  fresh signoff and a separately authorized exact one-shot boundary; there is
  no authorization for another submission in this run.

## 2026-08-01 Fresh `-08` Live Remediation Confirmation

- authorized boundary:
  - run `arch001-act001-provisioning-20260801-08`, target
    `arch001-act001-target-provisioning-20260801-08`;
  - model `gpt-5.6-sol`, window `17:02:25+08:00` through `18:02:25+08:00`;
  - one Navigator task-create maximum, no retry.
- frozen identity and signoff:
  - pre-signoff candidate digest
    `aa7ad663b6ca931a65e95f10b121df0e28374b4acd9be17b99afdd339da08c0c`;
  - Launcher SHA-256
    `8c7edbd77d635b7b6a0812a3466a549f8d18ec83dc7014a0eb1e6b6318a3d3fa`;
  - manifest `5c9d192ef32cc6aa53b068fcf0cafcab0095fd71a223b53041b6519e2c10e259`,
    seal `27818aa895d6882aa370f7cbc9201f288d4fb1b0aa35d35bc7508218ab0aea38`
    and controller digest
    `d0cfef3449e3e4ccb2734cbe6bda7a41b53188d2652fb96266f8eded8ab05ece`;
  - R4 decision `AUTHORIZED_FOR_ONE_BOUNDED_CANARY`, with only reviewer-session
    separation waived by the Project Owner.
- fresh target:
  - exact MySQL `8.0.44`, schema `0→93` and `93→93`, live Hibernate
    `validate`, Worker `1.0.30`, authenticated complete inventory and zero
    pre-run active tasks all passed;
  - production APIs generated user `ba56cf4e-77e4-41ea-ab1f-fcd2091162c4`,
    Worker `b4dbdc67` and modelConfig
    `1cde946c-a950-40b9-8b8d-a740fa72e4d6`; provisioning used 5 production
    API calls with zero direct DML, activation mutation, provider effect or
    model submission.
- one-shot result:
  - exactly one `POST /api/v1/tasks` created Task `20260801-12d8` and Session
    `52dd4452-7a03-4d45-852d-79ff9fba8614`; no retry was attempted;
  - provider task `c7141202-4101-419a-8738-459a1c1cc45f` completed in 8,908 ms,
    17,060 input tokens, 50 output tokens, one turn and USD `0.03452`; prompt
    and response bodies were not retained or logged;
  - Navigator `session_tasks` is `COMPLETED`; lifecycle Task snapshot is
    `ENFORCED/TERMINAL/COMPLETED`, terminal tombstone is `COMPLETED`, the
    provider-once outbox is `COMPLETED`, and all five terminal cleanup
    checkpoints are `COMPLETED`;
  - facts `TASK_DISPATCH_RESERVED`, `TASK_DISPATCHED` and
    `TASK_PROVIDER_TERMINAL_OBSERVED` all have valid, non-empty content-free
    JSON envelopes. `LIFECYCLE_FACT_PAYLOAD_INVALID` did not recur. This is
    direct live confirmation that the `-07` P1 is resolved.
- controller safety event:
  - pre-submission observation was fresh with unknown controller 0 and no late
    relaunch;
  - approximately 0.1 seconds after the terminal ledger timestamp, watcher
    recorded one short-lived unknown controller and stopped with the expected
    fail-closed rejection. Authority quarantined with
    `LIFECYCLE_ACTIVATION_CONTROLLER_DRIFT`; a later direct live scan found
    only the exact Navigator and Worker and unknown controller 0;
  - the transient process identity was not captured, so this is retained as an
    unclassified scoped safety event rather than asserted as a confirmed new
    code defect. It did not roll back Task/Session terminal convergence and no
    second submission is permitted. If continuous activation is later pursued,
    observation evidence must first record the unknown PID/PPID/cwd identity so
    normal Worker teardown can be distinguished from a real controller.
- quarantine and cleanup:
  - explicit operator quarantine returned
    `LIFECYCLE_ACTIVATION_OPERATOR_STOP` with authority, admission and proof
    all closed;
  - manifest-confirmed cleanup removed exactly the `-08` process set,
    container, network and volume; ports `18128/13058/13314` are closed;
  - all six exact-target credential profiles and the earlier bootstrap profile
    are absent; no credential value was logged.
- final decision: `ACCEPTED_WITH_RISKS`. The bounded terminal-convergence goal
  is met and the original P1 is resolved live. Activation remains `CLOSED`, R4
  and the one-shot authorization are consumed, and this record grants no
  second canary, production promotion, deployment or rollout authority.
- sanitized local evidence:
  `temp/test-artifacts/ARCH-001-ACT-001-replan/target-arch001-act001-provisioning-20260801-08/evidence/{00-preparation-boundary.json,11-bounded-submission-ledger.json,12-canary-outcome-sanitized.json}`.

## 2026-08-01 Local SIM Runtime Handoff

- Project Owner authorized merge to `main`, local stack restart and update of
  the same-machine WSL Worker used by SIM. This remains local/trusted
  development rollout only; activation and production promotion remain closed.
- Navigator implementation was pushed through `main@b7ec48b9`; Codex Worker
  release metadata and the packaged-stop correction were pushed through
  `main@558811ff`.
- SIM's existing WorkerHost `school-sim-wsl` still resolves Physical Worker
  `ddc45293` to Claude/Directory `3131` and the same-worker Codex role `3151`
  via `CLAUDE_WORKER_CODEX_CONFIG`. Independent local Workers `3031/3051` were
  not substituted for that frozen binding.
- `local-dev-stack.sh restart --skip-build` restarted `8112/3031/3051/3072/3061`
  and synchronized/restarted WSL Biz Worker `3161` at version `0.2.2`.
  SIM-owned `3131` was separately restarted with zero active tasks.
- Codex Worker `3151` was upgraded from `1.0.25` to `1.0.32`; SDK `0.145.0`,
  API-key readiness, termination identity, replay ledger and lifecycle-v1 are
  ready. Its lifecycle store is an exclusive persistent local directory and
  reports Physical Worker `ddc45293` with no reason codes.
- deployment defect and remediation:
  - release `1.0.31` exposed that archives still selected stale
    `release/stop.sh` / `release/stop.ps1`, which bypassed the canonical
    fail-closed ownership and quiescence checks; severity is `P1` because an
    upgrade could force-stop active work;
  - no active provider process existed during the observed update, so the
    defect caused no task or data loss;
  - release `1.0.32` now packages canonical root stop scripts and adds a
    regression that rejects any return to the stale release copies. The stale
    duplicate scripts were removed.
- release evidence:
  - `1.0.32` full smoke: 268 tests, 266 passed, two Windows-only skips, zero
    failures; typecheck, build, archive structure, forbidden-file scan,
    candidate `npm ci` and candidate `/health` all passed;
  - published `latest.json` points to `1.0.32` at commit `558811ff`; Linux and
    macOS SHA-256 are
    `2637d628f543499a578449f90961f1f82d93e0b041bdb78823554d74618424cb`,
    Windows SHA-256 is
    `4918888d392caeadaf9f0e0788b5322977bd82d391517f89e247ad93c65e1c05`;
  - remote archive/bootstrap verification and installed health/version/cwd
    checks passed; temporary target archives were removed.
- SIM may proceed with profile check, exact readiness, owner-smoke and one
  narrow safe ask. Those checks are still required and must not be replaced by
  Worker health alone.

## References

- superseded activation signoff:
  `docs/version-tracker/1.4.3-SNAPSHOT/evidence/ARCH-001-ACT-001-independent-activation-signoff-2026-08-01.md`
- prestart replan evidence (local, content-free):
  `temp/test-artifacts/ARCH-001-ACT-001/target-arch001-act001-canary-20260801-01/evidence/{08-final-doctor-after-provider-injection.log,09-prestart-execution-blocker.json,10-candidate-identity-post-signoff.json}`
- parent source contract:
  `docs/version-tracker/1.4.3-SNAPSHOT/workitems/ARCH-001-unified-session-task-lifecycle-owner.md`
- source acceptance:
  `docs/version-tracker/1.4.3-SNAPSHOT/evidence/ARCH-001-independent-fifth-remediation-signoff-2026-07-31.md`
- final activation signoff:
  `docs/version-tracker/1.4.3-SNAPSHOT/evidence/ARCH-001-ACT-001-independent-activation-signoff-2026-08-01-r4.md`
- production boundary:
  `docs/version-tracker/1.4.3-SNAPSHOT/workitems/GOV-001-p3-production-boundary-decision-gate.md`
- local topology: `docs/dev-specs/local-upstream-collaboration.md`
- provisioning boundary:
  `.agents/skills/navigator-runtime-provisioning/SKILL.md`
