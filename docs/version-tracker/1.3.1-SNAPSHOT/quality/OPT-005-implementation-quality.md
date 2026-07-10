---
quality_scope: feature
quality_mode: pre-coverage-audit
version: 1.3.1-SNAPSHOT
target: OPT-005
status: reviewed
decision: ready-for-coverage-audit
reviewed_by: codex
reviewed_at: 2026-07-10
follow_up_required: yes
scope_validity: retained-alias-auth-java-session-pc-projection-only
runtime_architecture_status: superseded
superseded_by: docs/version-tracker/1.4.0-SNAPSHOT/workitems/OPT-001-independent-codex-app-server-worker-requirement.md
---

# Implementation Quality Gate

## Scope Reclassification

- 本质量门的历史检查结果继续适用于 Max / Ultra alias、显式授权，以及 Java / Session / PC native-subtask projection。
- 旧 Codex Worker 内的混合 SDK / app-server lane、flag、精确 CLI 门控和 pre-turn SDK fallback 已由 1.4.0 OPT-001 supersede；对应实现质量结论不再构成当前执行架构或发布证据。
- 下文涉及旧 Worker runtime / bridge / tracker 的检查与 smoke 原样保留为历史证据，用于继承隐私、防重放和事件契约约束，不表示继续发布旧 lane。
- 独立 app-server Worker、幂等受理、Runtime Registry、affinity 和生产 rollout 的质量门在 [1.4.0 OPT-001](../../1.4.0-SNAPSHOT/workitems/OPT-001-independent-codex-app-server-worker-requirement.md) 重新执行。

## Background

- 检查对象：OPT-005 Codex Sol Max / Ultra 与 Ultra 原生子任务 PC 进度展示
- 当前阶段：历史质量门已完成；签收范围已按 1.4.0 架构迁移重新分类
- 本次目标：确认 app-server opt-in 通道、原生事件隐私边界、Java 最新状态投影、可恢复删除和 PC 重连展示闭环，不与 Navigator 内部 Agent/A2A 语义混合

## Check Basis

- requirement/progress: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-005-codex-sol-max-ultra-support.md`
- migration: `docs/migration/2026-07-10-native-subtask-states.sql`
- Worker contract: `tools/codex-agent-worker/docs/upstream-integration.md`
- test result summary: Worker unit/typecheck/build、Java focused tests、frontend unit/type-check/build、真实 Worker Ultra smoke、MySQL 8 migration smoke、Playwright

## Changed Surface

- Worker：alias / model / authorization 相关改动属于保留范围；`app-server-runtime.ts`、`app-server-event-bridge.ts`、`native-subtask-tracker.ts`、`sdk-wrapper.ts` 双 lane、health flag 及对应测试属于已 supersede 的历史实现证据
- Java common / Codex / Session：`NATIVE_SUBTASK_UPDATE`、native subtask DTO/entity/repository、状态 upsert、SSE relay、用户归属快照 API、删除路由与测试
- PC：独立 native-subtask reducer/API/types、`NativeSubtaskBar.vue`、Task Pane/SSE 订阅 epoch 管理及 Vitest/Playwright
- 数据与文档：显式 MySQL 迁移、Worker upstream integration、OPT-005 version tracker

## Quality Checklist

- scope conformance: pass，Codex 原生子任务只作为 Provider 状态投影，不创建 Navigator Agent、Session 或顶层 Task
- code hygiene: pass，Worker runtime/bridge/tracker、Java projection/query、PC reducer/view 职责分离
- duplication and consolidation: pass，PC 状态不注入 `@foggy/chat`，Java 只维护最新状态而不复制聊天事件历史
- complexity and abstraction: pass，跨层 contract 固定 `contract_version=1`，按完整单项快照和 seq 收敛乱序/重复事件
- error handling and edge cases: pass，覆盖 CLI 不兼容、stdin EPIPE、同步 spawn 失败、订阅重连、旧快照响应、重复回调、删除后迟到事件
- replay safety: pass，`turn/start` 是明确提交边界；提交前可回 SDK，提交后禁止同 prompt SDK 重放
- privacy boundary: pass，只投影状态白名单，失败消息收敛为稳定通用码；任意 Provider 错误文本、子 prompt、输出、reasoning、工具参数/输出、凭据和原始 app-server 事件不进入 Java/PC
- authorization: pass，快照 API 复用统一任务归属解析；Provider missing fallback 仅允许仍拥有统一 projection 的当前用户
- deletion semantics: pass，Provider/native cleanup 先提交，统一 session projection 最后删除作为可恢复重试标记；父任务悲观锁阻止 late event 重建
- migration discipline: pass，显式 SQL 声明生产先迁移再 `ddl-auto=validate`，没有依赖运行时自动建表
- UI quality: pass，折叠摘要、展开层级、状态、错误摘要、内部 ID 隐藏、窄 Pane 和多 Pane 隔离均有自动化
- documentation and writeback: pass，scope、验收标准、测试证据、风险和发布门禁已回写
- release readiness: retained-scope only。alias / authorization / Java / Session / PC projection 可进入覆盖审计；旧混合 lane 不具备当前发布资格，生产启用和执行架构门禁转入 1.4.0

## Findings

- 在保留范围内未发现阻断代码验收的问题。默认关闭和 CLI `0.144.1` 精确门控是旧混合 lane 的历史隔离证据，不再是当前架构的发布门控。
- “重复执行”不是 Ultra 自身行为。风险只存在于 app-server 已接受 `turn/start` 后，Worker 又错误地用 SDK 执行相同 prompt；实现已把该时点设为不可回退提交边界。
- 两阶段删除不声称跨 Provider 原子性；它先完成 Provider 清理，并把统一 projection 保留到最后，使中间失败仍可确认用户归属和重试路由。

## Risks / Follow-ups

- risk 1: 尚无单条自动化覆盖独立 app-server Worker -> Java -> unified SSE -> PC；旧 Worker 协议 smoke 不可替代 1.4.0 当前架构的 canary 全链路验证。
- risk 2: 实际生产库尚未执行 `native_subtask_states` 迁移，也未运行生产 profile `ddl-auto=validate`；该项仍约束保留的 Java / Session / PC projection，但是否启用 app-server Ultra 由 1.4.0 门禁决定。
- risk 3: Codex CLI/app-server 协议升级必须重新验证 fixture 和真实委派事件后更新精确版本，不能把门控改为宽松最低版本。
- follow-up 1: 后续可增加 MySQL/JPA 多实例锁集成测试和顶层 post-turn fault 注入测试，但不阻断当前覆盖审计。

## Recommended Next Skills

- `foggy-test-coverage-audit`: 执行
- `foggy-acceptance-signoff`: 覆盖审计后执行
- back to implementation: 不需要

## Decision

- decision: ready-for-coverage-audit
- can_enter_coverage_audit: yes
- follow_up_required: yes
- decision_scope: Max / Ultra alias、授权及 Java / Session / PC projection；不包含旧 Worker 混合 app-server lane
- runtime_architecture_acceptance: reopened-in-1.4.0

## Lightweight Self-Check Note

- self_check_summary: alias、授权、隐私投影、状态恢复、删除语义和 UI 在保留范围内闭环；旧 flag / 精确门控 / fallback 仅保留历史证据，当前生产架构在 1.4.0 重验。
- self_check_decision: needs-formal-quality-gate
- self_check_follow_up: complete target-database migration, production validate startup and full-chain canary before broad enablement
