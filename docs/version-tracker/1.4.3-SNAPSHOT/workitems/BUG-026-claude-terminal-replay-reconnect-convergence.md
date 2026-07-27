---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-026
status: ULTRA_EXECUTING
canonical: true
execution_mode: ultra
assurance_level: standard
bug_source: user-report
approved_by: project-owner-explicit-implementation-and-release-request
approved_at: 2026-07-27
open_questions: []
---

# Delivery Spec: Claude terminal replay and reconnect convergence

## Document Purpose

- intended_for: ultra-implementation / independent-signoff
- purpose: 修复 Claude 任务已产生并回放成功终态后，Navigator 仍重连已释放的
  Worker SSE 任务并向会话重复发布 HTTP 404 transport error 的问题。
- canonical_path:
  `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-026-claude-terminal-replay-reconnect-convergence.md`

## Goal

- version_goal: 让 Claude Worker 与 Codex SDK Worker 在终态事件回放和重连收敛语义上对齐。
- target_outcome: Worker 内存任务释放后仍可按 durable ESN 回放终态；Navigator
  对终态后的迟到 stream error 静默收敛，并对真正未确认的 transport interruption
  保持单任务、一次性恢复状态。
- critical_outcomes:
  - 已持久化事件可通过现有 `/subscribe?ack_seq=` 契约回放；
  - 明确终态提交后不再产生重复错误或重连；
  - 404 仍保持非终态语义，不被无条件解释为成功；
  - 同一任务最多一个计划恢复，终态时取消；
  - 不影响模型、网关、凭据、任务取消或权限交互契约。
- success_is_sufficient_when: Python 与 Java failure-first regression、受影响模块测试、
  clean-source 发布归档审计、OBS 远端回读和 main push 均通过。

## Scope

- in_scope:
  - Claude Worker durable Event Store 的 subscribe fallback 与 alias 解析；
  - Navigator Claude stream error/completion 的终态复查、异步恢复去重和通知去重；
  - `0.1.11` 三平台 Worker 发布包、OBS `latest.json` 和安装器；
  - 本任务及已发布但尚未提交的 Claude SDK-only updater 相关源码/文档提交。
- affected_modules:
  - `tools/claude-agent-worker`
  - `addons/claude-worker-agent`
  - `docs/version-tracker/1.4.3-SNAPSHOT`
- external_dependencies: 既有 MySQL task state、Worker JSONL Event Store、华为云 OBS。

## Non-Goals

- out_of_scope:
  - Gemini、Codex 或 LangGraph Worker 修改；
  - Codex app-server tombstone/completion receipt 的完整移植；
  - 删除任务 `20260727-7af4` 已持久化的历史错误消息；
  - 自动升级或重启 `/home/sa/.claude-worker` 或当前 Java；
  - 模型映射、9443 网关或 credential 配置变更。
- do_not_touch:
  - `tools/claude-agent-worker/src/agent_worker/routes/files.py` 及其测试等现有无关改动；
  - 当前工作树中其他模块和用户改动；
  - 明文 API key、Worker token 或 OBS credential。
- non_blocking_or_waivable_items: 用户负责目标机 Worker 和 Java 更新；本轮不做运行实例 smoke。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| 对齐 Codex SDK Worker 的 durable subscribe fallback | Claude 已持有 JSONL events、alias 和 terminal evidence | 只有确实无内存/磁盘证据才返回 404 |
| Java error callback 必须复查本地终态 | 终态提交与迟到 HTTP callback 存在竞态 | 终态后静默清理，不发布 ERROR |
| 恢复使用异步、单任务去重调度 | 当前 `Thread.sleep` 阻塞回调且难以取消 | 保持无限后台恢复但每任务最多一个计划项 |
| transport interruption 使用一次性 STATE_SYNC | 未确认传输不是业务失败 | 明确 Worker terminal failure 仍发布 ERROR |
| Worker 先发布，Java 后更新 | 新 Worker 对旧 Java 向后兼容 | 回滚可独立执行 |

## Acceptance Criteria

- [x] AC-1: `/subscribe` 在 registry 缺失时按 alias 从 Event Store 回放
  `seq > ack_seq`，并以 SSE 200 正常结束。
- [x] AC-2: 真正未知任务保持 HTTP 404；closed-only 或无明确 terminal evidence
  不被伪造为成功或失败。
- [x] AC-3: Java 对本地终态后的迟到 SSE error/completion 静默清理，不发布消息或安排恢复。
- [x] AC-4: 同一 Claude 任务最多一个 scheduled recovery，终态会取消；活跃订阅替换时释放旧 handle。
- [x] AC-5: 连续 transport interruption 最多发布一次 recovery-pending STATE_SYNC，
  不重复发布 ERROR。
- [x] AC-6: Python focused/full tests、Java addon focused tests 和 scoped diff check 通过。
- [ ] AC-7: 仅本任务和此前 Claude updater 的已批准文件提交并推送 main，不夹带现有无关改动。
- [ ] AC-8: Claude Worker `0.1.11` 从 clean commit 构建发布，OBS 远端摘要与本地一致。

## Contract / Data / Security Constraints

- API or event contract: 保持现有 route、query parameter 和 WorkerEvent 字段；只增强
  `/subscribe` 的 durable fallback，404 语义收窄为真正无证据。
- data and migration: 无数据库迁移；复用现有 JSONL 与 alias 文件布局。
- compatibility and rollback: Worker change 向后兼容；Java 可独立回滚；发布顺序为
  Worker `0.1.11` 后由用户更新 Java。
- permissions and secrets: 不读取、打印、提交或归档 credential；OBS 使用既有本机安全配置。

## Test and Evidence Obligations

| Item | Classification | Risk | Required Validation | Reusable Evidence | Required Evidence |
|---|---|---|---|---|---|
| AC-1/2 | must-pass | major | Python failure-first integration tests | 现有 JsonlEventStore/status tests | red/green output |
| AC-3/4/5 | must-pass | major | Java failure-first relay tests | Codex relay pattern | terminal race/dedup assertions |
| AC-6 | must-pass | major | Worker full pytest + addon Maven tests | existing suites | exact command/result |
| AC-7 | must-pass | major | staged diff audit + push result | current git status | committed paths and remote SHA |
| AC-8 | must-pass | major | isolated build/upload/download/hash | existing release scripts | remote latest and SHA-256 |

## Validation Budget and Evidence Sufficiency

- assurance_level: standard
- lightweight_validation: focused red/green tests、syntax、diff/status audit，单次 `<5m`。
- medium_validation: Worker full pytest、addon Maven tests、三平台 package/OBS 回读，单次 `5-30m`。
- expensive_validation: none。
- large_authority_or_replay_policy: prohibited-unless-user-approved。
- full_chain_recommendation_trigger: none。
- estimated_full_chain_wall_clock: not-estimated。
- full_chain_prerequisites: none。
- user_approval_status: approved-for-main-push-and-obs-release。
- decision_if_not_approved: N/A。
- expensive_validation_trigger: none。
- maximum_expensive_attempts: 0。
- reusable_evidence: 现场任务 `20260727-7af4` 的脱敏 Worker 日志和现有 Codex tests。
- stop_when_evidence_is_sufficient: AC-1 至 AC-8 均有实际证据，且未触及运行实例。
- validation_not_required: Maven 全 reactor、前端构建、目标机升级、Java restart、Gemini tests。

## Waiver Policy

- waivable_items: 目标机 Worker/Java smoke。
- authorized_role: project owner。
- non_waivable_guards: durable terminal replay、终态静默收敛、无凭据泄漏、提交范围隔离。
- required_risk_record: 用户更新后仍需发送一条短任务验证消息与终态无重复错误。

## Bug Context

- bug_source: user-report。
- severity: major。
- environment: 本机 Navigator 8112、`/home/sa/.claude-worker:3033`、Worker `0.1.10`。
- current_behavior: Worker 成功返回结果并释放内存任务后，Navigator 残留重连请求
  `/subscribe` 收到 404，向会话重复发布 transport error，任务 UI 显示失败/待定。
- expected_behavior: durable terminal event 被完整消费后任务完成，迟到回调静默收敛；
  尚未消费时可从磁盘回放。
- reproduction_steps:
  1. 发送短 prompt `test`。
  2. Worker 产生 AssistantMessage 与 ResultMessage，原 SSE consumer 断开。
  3. 第一次重连回放终态并释放 registry。
  4. 残留重连以旧 ACK 再次请求 subscribe，收到多个 404。
- reproduction_status: confirmed。
- existing_evidence:
  - Foggy task `20260727-7af4` 映射 Worker task
    `d1d0eba7-8bc7-48ef-98ec-39addabc596f`；
  - 16:17:06 Worker 产生 ResultMessage，16:17:08 subscribe 200 回放终态；
  - 随后同任务多次 subscribe 404，而 status 仍可从 persistence 返回证据。
- existing_tests: live registry replay、status persistence terminal、closed/aligned Java reconnect；
  缺少 registry-missing subscribe replay 和 post-terminal HTTP error 回归。
- regression_protection: required。
- waiver_reason_and_risk: N/A。

## Risks and Open Questions

- known_risks:
  - 无条件吞掉 404 会掩盖 Worker restart/routing drift，因此必须结合本地/持久化终态；
  - 同步 Flux 可能在订阅 handle 注册前完成，需要避免残留 disposed handle；
  - 当前工作树包含大量无关改动，必须路径级 staging 和 clean-commit release。
- open_questions: none。

## Ultra Execution Contract

- 先读取本文件、根与 Claude Worker `AGENTS.md`。
- 对稳定复现问题先增加失败测试，再实现修复。
- 在 scope 内自主决定局部实现；不得修改 Gemini/Codex 或运行实例。
- 仅 stage 本任务及此前 Claude updater 的明确文件，提交前审计 staged diff。
- 从已推送 clean commit 隔离构建 `0.1.11`，不得打包 dirty worktree。
- 完成后填写 `Implementation Result` 并设置 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

- implementation_summary:
  - Claude Worker `/subscribe` 在 live registry 缺失时解析 persisted alias，并从既有
    JSONL Event Store 按 durable ESN 回放；真正无事件的任务仍返回 404。
  - Navigator Claude relay 在 stream callback 中复查本地终态，终态后静默清理；
    重连改为非阻塞、单任务去重调度，并将 transport interruption 降为一次性
    `STATE_SYNC/reconnect_pending`。
  - 恢复成功消息延后到实际收到首个 Worker SSE event；终态、abort 或新活跃订阅
    会清理/取消对应恢复状态。
- changed_paths:
  - `tools/claude-agent-worker/src/agent_worker/routes/query.py`
  - `tools/claude-agent-worker/tests/integration/test_subscribe_status.py`
  - `tools/claude-agent-worker/src/agent_worker/__init__.py`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/WorkerStreamRelay.java`
  - `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/WorkerStreamRelayTest.java`
  - 本 work item。
- tests_and_results:
  - FAILURE-FIRST: Worker persistence/alias subscribe 新增测试修复前 `2 failed, 2 passed`，
    两项均为预期 HTTP 200、实际 HTTP 404。
  - FAILURE-FIRST: Java post-terminal 404 新增测试修复前失败，证明仍调用
    `markLifecycleAttention(..., STREAM_TRANSPORT_UNCONFIRMED)`。
  - PASS: `tests/integration/test_subscribe_status.py`，`15 passed`。
  - PASS: `WorkerStreamRelayTest`，`22 tests`。
  - PASS: Claude Worker 全量 `546 passed, 11 skipped`。
  - PASS: Claude addon 全量 `424 tests`。
  - PASS: scoped `git diff --check`。
- manual_or_experience_evidence: 现场日志证明原任务先收到 ResultMessage 和一次
  subscribe 200，随后才出现多个 subscribe 404；修复测试精确覆盖该终态竞态。
- deviations: none
- residual_risks: 尚待 main push、clean-commit `0.1.11` 构建和 OBS 远端回读；
  用户仍需自行更新 Java 与 Worker 并执行短任务 smoke。
- reused_evidence: 既有 Claude terminal evidence/status persistence tests 与 Codex
  terminal-after-error 收敛模式。
- omitted_validation_and_reason: 未重启或升级运行中的 Worker/Java，按用户约定由其后续更新。
- readiness: ULTRA_EXECUTING

## References

- requirement / issue: 用户报告任务成功输出后出现多个
  `Worker stream transport is unconfirmed (HTTP 404)`。
- architecture / glossary: `tools/claude-agent-worker/AGENTS.md`
- related work items:
  - `BUG-013-codex-unverified-state-and-post-terminal-sse.md`
  - `OPT-claude-worker-sdk-only-updater.md`
