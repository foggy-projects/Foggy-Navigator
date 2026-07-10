---
acceptance_scope: feature-code
version: 1.3.1-SNAPSHOT
target: OPT-005
doc_role: acceptance-record
doc_purpose: 说明本文件用于 OPT-005 功能代码验收、风险签收与生产启用门禁记录
status: signed-off
decision: accepted-with-risks
production_enablement: reopened-in-1.4.0
signoff_scope: retained-alias-auth-java-session-pc-projection-only
runtime_architecture_status: superseded
superseded_by: docs/version-tracker/1.4.0-SNAPSHOT/workitems/OPT-001-independent-codex-app-server-worker-requirement.md
signed_off_by: codex
signed_off_at: 2026-07-10
reviewed_by: codex
blocking_items: []
follow_up_required: yes
evidence_count: 9
---

# Feature Acceptance

## Signoff Scope Reclassification

- 原签收继续有效的范围为：Max / Ultra alias、模型显式授权，以及 Java / Session / PC native-subtask projection、隐私、恢复和删除语义。
- 旧 Codex Worker 混合 SDK / app-server lane、`CODEX_APP_SERVER_ULTRA_ENABLED`、CLI 精确门控和 pre-turn SDK fallback 已由 1.4.0 OPT-001 supersede；原签收不再批准该执行架构、对应 Worker 包或生产启用。
- 下文旧 lane 的测试数字和 smoke 原样保留为历史证据，不得解读为当前 release evidence。
- 独立 app-server Worker、幂等受理、Runtime Registry、affinity、canary、回滚和生产启用在 [1.4.0 OPT-001](../../1.4.0-SNAPSHOT/workitems/OPT-001-independent-codex-app-server-worker-requirement.md) 重新签收。

## Document Purpose

- doc_type: acceptance
- intended_for: signoff-owner / reviewer / release-owner
- purpose: 保留 Max / Ultra alias、授权及 Java / Session / PC 原生子任务投影签收，并声明旧执行架构已 supersede

## Background

- Version: `1.3.1-SNAPSHOT`
- Target: `OPT-005`
- Historical Worker target release: `1.0.11`，当时包版本为 `1.0.10`；该混合 lane 候选不再作为当前发布目标
- Owner: Codex Worker / Codex Java addon / Session module / Navigator frontend
- Goal: 保留稳定 Max / Ultra alias、显式授权与脱敏 native-subtask projection 的验收；执行 runtime 已转入 1.4.0 独立 Worker

## Acceptance Basis

- requirement/progress: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-005-codex-sol-max-ultra-support.md`
- implementation quality: `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-005-implementation-quality.md`
- coverage audit: `docs/version-tracker/1.3.1-SNAPSHOT/coverage/OPT-005-coverage-audit.md`
- migration: `docs/migration/2026-07-10-native-subtask-states.sql`
- protocol documentation: `tools/codex-agent-worker/docs/upstream-integration.md`

## Checklist

- [x] Max / Ultra alias、reasoning 透传和显式授权完成
- [x] `[历史实现，已 superseded]` Ultra app-server lane 默认关闭，CLI 精确版本和 health 门控完成
- [x] `[历史约束证据]` `turn/start` 提交前/后故障边界完成，提交后禁止 SDK 重放
- [x] 原生子任务事件只包含状态白名单，失败消息只使用稳定通用码，不泄露任意 Provider 错误文本、prompt、输出、reasoning、工具数据或凭据
- [x] Java 最新状态持久化、seq 幂等/乱序保护、用户归属查询和聊天历史隔离完成
- [x] 删除使用 Provider/native 先清理、统一 projection 最后删除的可恢复两阶段语义，跨用户 fallback 被拒绝
- [x] PC 折叠/展开、层级、状态、重连快照、连接 epoch、多 Pane 和窄 Pane 适配完成
- [x] Worker、Java、Frontend 自动化与真实 Worker/MySQL/Playwright evidence 通过
- [x] projection 数据库迁移与 `ddl-auto=validate` 仍为保留范围门禁；执行 runtime canary 已转入 1.4.0

## Evidence

- Worker automated: 128 / 128 tests，typecheck/build passed。
- Java Session focused: 93 / 93 tests passed。
- Java Codex focused: 47 / 47 tests passed。
- Frontend automated: 133 / 133 tests，type-check/build passed。
- Max / Ultra live SSE: 两档均返回预期最终文本；Ultra resume/abort 保持现有任务语义。
- App-server live Worker historical evidence: 39 events，6 native updates，1 child，`running -> completed`，1 result，0 error，严格递增 seq，字段白名单通过；不作为当前独立 Worker 发布证据。
- Playwright: `codex-native-subtasks.spec.ts` 1 / 1，确认订阅后快照、折叠/层级/状态、内部 ID 隐藏和 320px Pane 无溢出。
- Migration: 临时官方 MySQL 8 同库连续执行脚本两次成功，表、20 列、`message VARCHAR(64)` 和索引符合预期。
- Static/review: `git diff --check` passed；Worker、Java、PC 分层 review 未发现 blocking issue。

## Accepted Semantics

- Ultra 子任务是 Codex Provider 原生执行线程的状态投影，不是 Navigator 内部 Agent/A2A 委派，也不创建新的 Navigator Task/Session。
- “重复执行”不是 Ultra 副作用。只有在 app-server 已接受 `turn/start` 后又错误地通过 SDK 重放同一 prompt 才会发生；该实现明确禁止提交后 fallback。
- `native_subtask_states` 只保存每个子线程的最新脱敏状态，统一 session SSE 只传状态更新，PC reducer 与 `@foggy/chat` 消息历史隔离。
- 删除顺序将统一 session task projection 保留到最后；Provider 或中间清理失败时，可用该 projection 确认用户归属并安全重试，而不是跨用户猜测路由。

## Failed Items

- none within retained signoff scope
- old mixed SDK / app-server runtime architecture: superseded and explicitly excluded from current acceptance

## Risks / Open Items

- 目标生产数据库尚未执行迁移，生产 `ddl-auto=validate` 尚未验证；该门禁继续约束保留的 native projection 数据面。
- 尚无真实独立 app-server Worker -> Java -> unified SSE -> PC 单条自动化；首次启用必须在 1.4.0 使用 canary 完成全链路 smoke。
- Codex CLI/app-server 协议升级后必须重新验证并更新精确版本，不能仅按 semver 最低版本自动放行。
- 后续建议增加真实 MySQL/JPA 并发锁测试和顶层 post-turn 故障注入测试；两项均不阻断本次代码验收。

## Historical Production Rollout Gate (Superseded)

以下步骤是原签收时针对旧混合 lane 的 rollout 记录，不再是当前可执行的生产上线清单：

1. 在目标库执行 `docs/migration/2026-07-10-native-subtask-states.sql`。
2. 以生产配置完成 `ddl-auto=validate` 启动。
3. 单台 canary Worker 启用 `CODEX_APP_SERVER_ULTRA_ENABLED=true`，核对 CLI `0.144.1`、protocol compatible 和 native supported。
4. 用真实 Ultra 委派核对 Worker event、Java snapshot/SSE、PC Task Pane、失败/中断与删除恢复。
5. canary 通过后才扩大启用；失败时关闭 flag 并保持 SDK lane，不放宽精确版本门控。

当前生产启用与执行架构签收统一转入 1.4.0 OPT-001；在其 P0-P7、Runtime Registry、任务/会话 affinity、canary 和回滚证据完成前，不得依据本文件开启旧 Worker app-server lane。

## Final Decision

OPT-005 原功能代码结论保留为 `accepted-with-risks`，但仅适用于 Max / Ultra 映射、授权，以及 Java / Session / PC 原生子任务投影、隐私、恢复和删除语义。旧 Worker 混合 app-server lane 及其 flag / fallback 已被 supersede，不属于当前签收范围，也不构成生产开启批准。目标数据库 projection 迁移仍需完成；当前执行架构、生产 validate、canary 和 rollout 必须在 1.4.0 OPT-001 重新验收。

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signoff_scope: retained alias / authorization / Java / Session / PC projection only
- old_mixed_lane_status: superseded
- production_enablement: reopened-in-1.4.0
- signed_off_by: codex
- signed_off_at: 2026-07-10
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-005-codex-sol-max-ultra-support-acceptance.md
- blocking_items: none for feature-code acceptance
- follow_up_required: yes
