---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-038
status: ULTRA_EXECUTING
canonical: true
execution_mode: ultra
assurance_level: elevated
approved_by: Project Owner
approved_at: 2026-08-01
open_questions: []
---

# Delivery Spec: Typed termination preflight rejection durable receipt

## Document Purpose

- intended_for: normal-analysis / ultra-implementation / independent-signoff
- purpose: 固定真实 typed termination attempt 在 fresh admission 拒绝、Task 瞬时终态和响应丢失场景下的 durable receipt、稳定结果与零重复 dispatch 契约。
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-038-typed-termination-preflight-rejection-receipt.md`

## Goal

- version_goal: 修复 BUG-035/BUG-036 typed termination 在 mutable preflight 拒绝发生于 request receipt 之前的审计与 reconciliation 缺口。
- target_outcome: 每个通过 ClientApp 鉴权、task ownership 与 request identity 基础校验的非 dry-run termination attempt 都能以原 clientRequestId 得到 durable、脱敏、可只读查询的权威结果；fresh admission 仍保持 fail closed。
- critical_outcomes:
  - readiness 和 dry-run 继续是 non-binding snapshot，不能替代真实 admission。
  - 真实 attempt 的 durable receipt 先于可变 Worker/provider admission observation，并在 admission 拒绝时仍然存在。
  - canonical terminal 变化返回 `ALREADY_TERMINAL`，其他拒绝返回稳定、可操作且持久化的 reasonCode。
  - 同一 clientRequestId 的重放或 reconciliation 不产生第二次有效 provider termination dispatch。
  - typed receipt/reconciliation 字段必须反映真实持久化事实，不得仅根据配置开关推断。
- success_is_sufficient_when: regression-first 测试、focused/affected Maven tests、clean launcher package 和一次经授权的 bounded disposable live smoke 全部满足本契约，且无 Worker/credential/provisioning 变更。

## Scope

- in_scope:
  - Navigator termination request-attempt receipt、fresh admission、intent/outbox 和 typed reconciliation 的生命周期边界。
  - canonical terminal、Worker active-task 缺失、Worker observation 不可达及 exact admission rejection 的稳定结果映射。
  - typed response 中 receipt persisted/reconciliation available 等字段的事实一致性。
  - deterministic 状态跃迁、admission failure、response-loss 和 duplicate request 自动化回归。
  - 必要的服务端、Open SDK 兼容测试与版本交付记录更新。
- affected_modules:
  - `addons/claude-worker-agent`
  - `addons/codex-worker-agent`
  - `business-agent-module`
  - `navigator-open-sdk`（仅在 typed compatibility/test 需要时）
  - `launcher`（构建与可控本机验证，不承载业务实现）
- external_dependencies: 现有 Navigator 数据库和 3151 Codex Worker 只作为最终受控验证依赖；预计不修改 Worker contract 或安装内容。

## Non-Goals

- out_of_scope:
  - 把 readiness/dry-run 提升为 mutation authorization 或保证后续必定 `ACCEPTED`。
  - 修改 Codex Worker abort 协议、发布 Worker、CLI/SDK 或创建新 runtime 能力。
  - 修复 SIM 代码、业务 prompt、模型行为或自然执行时长。
  - 重放历史 Task `20260801-e146`，或用其自然完成替代 termination 成功证据。
  - 新增自动 retry、recovery、redispatch 或换 clientRequestId 重试策略。
- do_not_touch:
  - `/home/sa/workspace/foggy-world-sim`
  - `/home/sa/workspace/tms-x3`
  - Physical Worker `ddc45293` 的 3151 binding、安装、配置与进程
  - Agent、modelConfig、Directory、grant、credential profile 和 TMS
- non_blocking_or_waivable_items:
  - 不改变 DTO shape 时不要求 CLI/SDK 版本升级或重新安装。
  - 不要求 release、OBS upload、tag 或 push；如需执行必须另获授权。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| readiness/dry-run 保持只读且 non-binding | Worker/Task 状态可能在调用间变化 | 真实 admission 必须重新读取权威 fresh fact |
| request-attempt receipt 与 admitted termination intent 是两个阶段 | admission 拒绝也必须可审计，但未获准时不得产生 effect intent | receipt 不构成 dispatch 授权；exact admission 与 intent/outbox 原子性继续保持 |
| 基础鉴权、ownership、request/task identity 校验通过后才建立可自查询 receipt | 防止未授权主体制造或枚举 task receipt | malformed/unauthorized 请求继续走现有安全审计，不暴露 self reconciliation |
| canonical terminal 优先映射为 `ALREADY_TERMINAL` | 自然完成不是 termination failure | 不伪造 `terminationDispatched=true` |
| 非终态 fresh blocker 映射为 durable `REJECTED` + stable reasonCode | 调用方需要安全停止并事后解释 | 不回显 Worker 原始错误、URL、token、credential 或 payload |
| typed availability 字段来自实际 registration/persistence | 配置 enabled 不等于本次 receipt 已落库 | 保持已有字段和 enum 向后兼容 |
| 原 clientRequestId reconciliation 严格只读 | response loss 后不得猜测重发 | `readOnly=true`、`newClientRequestIdAllowed=false`，不调用 provider |
| dry-run 继续保持零 durable runtime side effect | 避免预检本身改变系统 | 不创建 termination operation、intent 或 request receipt |
| 优先复用现有 audit/operation schema | 控制迁移和回滚风险 | 如证明必须新增或破坏性迁移，设置 `NEEDS_REPLAN` |

## Acceptance Criteria

- [ ] AC-1 每个通过基础鉴权、ownership 和 request identity 校验的非 dry-run termination attempt 恰好建立一个 clientRequestId keyed durable request receipt；随后 fresh preflight/admission 拒绝也不得回滚或删除该 receipt。
- [ ] AC-2 Task 在 readiness/dry-run 后变为 canonical terminal 时，真实请求及同 ID reconciliation 返回 `ALREADY_TERMINAL`/`TERMINAL` 权威语义，`terminationDispatched=false`。
- [ ] AC-3 Worker active task 缺失、Worker status/health 不可达或 exact admission 拒绝时返回 durable `REJECTED` 和对应稳定脱敏 reasonCode，不创建可执行 intent，不调用 Worker abort。
- [ ] AC-4 `terminationRequestReceiptPersisted` 和 `requestReconciliationAvailable` 仅在本次 receipt 实际持久化时为 `true`；API 字段与数据库事实一致。
- [ ] AC-5 同一 task/operation/clientRequestId 的重复请求只返回既有权威结果，不再次 inspect-for-effect、创建 intent 或 dispatch；ID 绑定冲突继续 fail closed。
- [ ] AC-6 同 ID typed reconciliation 覆盖 `IN_PROGRESS|ACCEPTED|REJECTED|TERMINAL|AMBIGUOUS|UNKNOWN`，保持 `readOnly=true`、`newClientRequestIdAllowed=false`，不创建 audit、repair、retry 或 provider 调用。
- [ ] AC-7 exact admission 与可执行 intent/outbox 继续保持原子性；request-attempt receipt 的提前持久化不得授权或扩大 provider effect。
- [ ] AC-8 `--dry-run` 保持零 request receipt、零 termination operation、零 intent、零 HTTP abort 和零 runtime mutation。
- [ ] AC-9 自动化回归至少覆盖 allowed→denied、allowed→canonical-terminal、exact-admission failure、rejected-response-loss reconciliation、duplicate/concurrent same-ID，以及 receipt 字段与持久化事实一致性。
- [ ] AC-10 一次 disposable live smoke 在不访问 TMS、不重放历史 Task 的前提下证明：最多一个模型提交、一个真实 termination attempt、无 retry/recovery/redispatch，最终 canonical terminal、token `REVOKED`、active registration `ABSENT`，且 receipt/reconciliation 可按原 ID 查询。
- [ ] AC-11 未修改或重绑 3151 Worker、Agent、modelConfig、Directory、grant 或 credential，未修改 SIM/TMS，未输出敏感信息。

## Contract / Data / Security Constraints

- API or event contract: 保持现有 endpoint、header、DTO field 和 enum 向后兼容；只修正 outcome/reason/receipt 字段语义。若实现必须改变公开 schema，进入 `NEEDS_REPLAN`。
- data and migration: 优先复用现有 runtime request audit、termination operation 和 intent/outbox 数据；不得把 preflight receipt 当作 provider effect proof。
- compatibility and rollback: 旧 Map 与 typed SDK 调用继续可用；服务端回滚应只需回滚代码并重启，不依赖不可逆数据迁移。
- permissions and secrets: 保持 ClientApp exact-self、upstream user/task ownership 和 selected durable Worker 约束；只持久化固定枚举或脱敏 reason，不记录 token、secret、Authorization、prompt、模型回复或业务数据。

## Test and Evidence Obligations

| Item | Classification | Risk | Required Validation | Reusable Evidence | Required Evidence |
|---|---|---|---|---|---|
| AC-1/3/4/7/9 | must-pass | critical | regression-first service/coordinator/repository tests，含真实事务边界 | BUG-035/036 不变的 typed serialization/idempotency tests | 失败测试、修复后命令与 pass 结果、receipt/intent/provider call counts |
| AC-2/5/6 | must-pass | critical | deterministic state-transition 与 response-loss tests | 现有 reconciliation state tests | typed outcome/reason/flags 和 no-provider/no-mutation assertions |
| AC-8 | must-pass | major | dry-run focused tests | 既有 dry-run fixture | 数据库/operation/intent/provider-effect 全零断言 |
| affected modules | must-pass | major | `mvn test -pl launcher -am` 或等价覆盖变更模块的 reactor test | 与改动无关且输入未变化的既有证据 | 精确命令、模块结果、未运行项理由 |
| package/runtime | must-pass | major | clean launcher package、SHA/provenance、8112 health/info、一次 bounded live smoke | BUG-037 provenance 机制 | artifact identity、health/info、脱敏 live projection |
| AC-11 | must-pass | critical | git diff、进程/绑定只读核对、boundary statement | 当前 3151 ownership facts | changed paths 与明确 no-touch 结论 |

## Validation Budget and Evidence Sufficiency

- assurance_level: elevated（公共 typed lifecycle、幂等与审计契约变更）。
- lightweight_validation: focused unit/transaction tests，单轮 `<5m`，每个失败原因最多修复后重跑 3 轮。
- medium_validation: affected reactor tests、clean package 和本地重启，单轮预计 `5-30m`，最多 2 轮；第二轮仅在产品代码或相关测试改变后执行。
- expensive_validation: none planned；不运行 authority/replay/rehearsal/full release chain。
- large_authority_or_replay_policy: prohibited-unless-user-approved
- full_chain_recommendation_trigger: none；affected reactor + bounded live 足以支持当前 go/no-go。
- estimated_full_chain_wall_clock: not-applicable
- full_chain_prerequisites: not-applicable
- user_approval_status: approved（包含一次不超过 10 分钟的 bounded disposable live smoke；不含 push/release）
- decision_if_not_approved: N/A
- expensive_validation_trigger: 若实现被迫改变公开 schema、Worker contract、数据库迁移或跨项目边界，先 `NEEDS_REPLAN`，不自动扩大验证。
- maximum_expensive_attempts: 0
- reusable_evidence: 当前 clean provenance、现有 Worker capability/binding 和 BUG-035/036 未受变更影响的 typed serialization/compatibility evidence 可复用；产品行为相关测试必须重跑。
- stop_when_evidence_is_sufficient: regression-first focused、affected reactor、clean artifact/runtime health 与一次 bounded live 均通过，且每个 AC 有脱敏证据后停止。
- validation_not_required: TMS、SIM 源码、第二 Task、第二 termination attempt、模型重试、Worker 更新/重启、CLI/SDK 发布、OBS、tag、push 或超过 30 分钟的全链。

## Waiver Policy

- waivable_items: 不改变公开 DTO 时的 Open SDK 重打包/重新安装；与当前行为无依赖的历史 evidence 重跑。
- authorized_role: Project Owner
- non_waivable_guards: durable attempt receipt、stable rejection reason、terminal mapping、same-ID no duplicate effect、dry-run zero effect、权限/秘密边界。
- required_risk_record: 任何未完成 live smoke 只能保持 `READY_FOR_SIGNOFF` 前的 incomplete 状态，不得宣称 runtime accepted。

## Bug Context

- bug_source: acceptance-found（SIM typed termination live smoke）
- severity: major（P1）
- environment: Navigator `802820a1674aec4d7d6031a085d888d70a645e9f` clean；launcher SHA-256 `5d01ebb26637f8f5183ad8e256dbf222057971c31bf809ec1b1fb90d10e8ea2b`；CLI `1.0.40-SNAPSHOT`；Open SDK `1.0.39-SNAPSHOT`；OPENAI_CODEX；3151 Worker `ddc45293`。
- current_behavior: readiness、dry-run 和 post-dry-run readiness 允许后，唯一真实请求返回 `REJECTED`；在 durable request receipt/provider termination operation 之前结束，无法按原 ID 事后取得权威 reason。Task 随后自然 `COMPLETED`。
- expected_behavior: fresh fact 可以使真实 admission fail closed，但真实 attempt 必须 durable；canonical terminal 返回 `ALREADY_TERMINAL`，其他拒绝返回 stable reason，并可按同 ID 只读 reconciliation。
- reproduction_steps: 使用 gitignored SIM runtime profile 创建一个 disposable fixture-only Task；依次执行 readiness、dry-run、post-dry-run readiness 和唯一真实 termination；只读查询原 request ID 与 Task audit。不得复用历史 Task。
- reproduction_status: confirmed（SIM live evidence、Navigator 本地日志和服务端 source path 相互一致；因缺少 receipt，原始瞬时 Worker blocker 无法事后精确恢复）。
- existing_evidence: Task `20260801-e146` runtime/model/BF dispatch `true/true/false`，dispatch/retry/recovery `1/0/0`，termination operation/receipt `0/0`，最终 `COMPLETED`、token `REVOKED`、registration absent；服务端 preflight 位于 receipt registration 之前。
- existing_tests: BUG-035 typed contract 和 BUG-036 convergence 覆盖静态 allowed/rejected、accepted response-loss、idempotency 与 terminal cleanup，但 provider inspect 使用稳定 mock，未覆盖跨调用状态跃迁及 preflight rejection receipt。
- regression_protection: required
- waiver_reason_and_risk: N/A

## Risks and Open Questions

- known_risks:
  - 若 attempt receipt 与 exact intent 没有清晰分层，可能把“已记录请求”误解释为“已授权 effect”。
  - 若在已存在同 ID receipt 前继续访问 mutable provider，可能重新引入重复观察或 effect 风险。
  - 短任务 live smoke 易受自然完成时序影响；离线回归必须用 deterministic barrier，live 只证明最终候选集成，不承担竞态定位。
- open_questions: none。

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md`、相关模块规范和 `navigator-runtime-provisioning`。
- 在 scope 内自主决定具体文件、类和实现结构；不要把 launcher 当作业务实现模块。
- 对该可稳定模拟的 BUG，先建立失败回归测试，再修复并运行通过。
- 如需改变目标、公开 schema、Worker contract、数据库迁移、跨项目范围或安全边界，设置 `NEEDS_REPLAN` 并停止扩展。
- 运行与改动面匹配的验证，不得声称未实际运行的测试通过。
- 未经用户明确批准，不得执行 live mutation；不得主动运行超过 30 分钟或包含 authority/replay/rehearsal/source-seal 的大型链路。
- 达到 evidence sufficiency 后停止，不追加第二 Task、重试或与签收决策无关的验证。
- 完成后填写 `Implementation Result`，并将状态改为 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

- implementation_summary: 已将非 dry-run termination 拆分为独立 durable attempt receipt 与后续 fresh admission/intent 两阶段；receipt 在可变 Task/Worker observation 前以 `REQUIRES_NEW` 落库，exact admission/intent 继续在同一事务中原子提交。重复 request ID 在任何 fresh provider observation 前直接进入只读 replay；canonical terminal 映射为 durable `ALREADY_TERMINAL`，其他 fresh blocker 持久化稳定 `REJECTED` reason；typed receipt/reconciliation 字段改为依据本次实际 registration。
- changed_paths: `addons/claude-worker-agent` termination closure/coordinator 及回归；`addons/codex-worker-agent` exact terminal admission 与回归；`business-agent-module` receipt transaction contract/terminal stage；`launcher` lifecycle fixture；本 work item 与版本索引。
- tests_and_results:
  - regression-first: `mvn -B -pl addons/claude-worker-agent -am -Dtest=RuntimeTaskTypedContractServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` -> 21 tests，4 个预期失败，分别证明 receipt 顺序、terminal mapping、duplicate no-inspect 和 typed persistence flag 缺口。
  - focused services: `mvn -B -pl addons/claude-worker-agent -am -Dtest=RuntimeTaskTypedContractServiceTest,RuntimeTerminationAcceptanceCoordinatorTest,RuntimeTerminationDeliveryRecoveryTest -Dsurefire.failIfNoSpecifiedTests=false test` -> SUCCESS，27 tests。
  - focused affected: `mvn -B -pl addons/claude-worker-agent,addons/codex-worker-agent,business-agent-module -am -Dtest=RuntimeTaskTypedContractServiceTest,RuntimeTerminationAcceptanceCoordinatorTest,RuntimeTerminationDeliveryRecoveryTest,RuntimeRequestAuditServiceTest,CodexTaskServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` -> SUCCESS，198 tests。
  - transactional vertical: `mvn -B -pl addons/claude-worker-agent -am -Dtest=BusinessLifecycleTerminalVerticalIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` -> SUCCESS，3 tests。
  - launcher lifecycle fixture: `mvn -B -pl launcher -am -Dtest=Arch001ThirdRemediationSlice8IntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` -> SUCCESS，4 tests。
  - affected reactor: `mvn -B test -pl launcher -am` -> SUCCESS，14/14 reactor modules；launcher 24 tests，0 failures/errors，2 skipped；total 5:20。首轮 fixture 因新 pre-registered receipt guard 暴露 3 errors，修正 fixture 后完整重跑通过。
- manual_or_experience_evidence: pending clean package/runtime health and the single authorized bounded live smoke.
- deviations: none
- residual_risks: live timing 仍可能先自然终态；该安全结果必须是 durable `ALREADY_TERMINAL`，不能伪装成 dispatched termination。
- reused_evidence:
- omitted_validation_and_reason: 未运行 release/authority/replay/full-chain，均不在批准范围；未执行第二 Task/第二 termination attempt。
- readiness: ULTRA_EXECUTING

## References

- requirement / issue: SIM P1 typed termination live-smoke report for Task `20260801-e146`
- architecture / glossary: `docs/a2a-agent-architecture.md`
- related work items: `BUG-035-open-sdk-typed-termination-reconciliation-contract.md`、`BUG-036-typed-termination-terminal-convergence.md`、`FEAT-002-runtime-standard-task-termination-reconciliation.md`
