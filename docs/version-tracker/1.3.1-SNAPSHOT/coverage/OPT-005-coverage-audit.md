---
audit_scope: feature
audit_mode: pre-acceptance-check
version: 1.3.1-SNAPSHOT
target: OPT-005
status: reviewed
conclusion: ready-with-gaps
reviewed_by: codex
reviewed_at: 2026-07-10
follow_up_required: yes
scope_validity: retained-alias-auth-java-session-pc-projection-only
runtime_architecture_status: superseded
superseded_by: docs/version-tracker/1.4.0-SNAPSHOT/workitems/OPT-001-independent-codex-app-server-worker-requirement.md
---

# Test Coverage Audit

## Scope Reclassification

- 本审计继续证明 Max / Ultra alias、显式授权、Java latest-state、Session snapshot / SSE / delete 与 PC native projection 的历史覆盖。
- 旧 Worker 混合 SDK / app-server lane、`CODEX_APP_SERVER_ULTRA_ENABLED`、精确 CLI 门控和 SDK fallback 的测试仍保留，但仅为历史设计证据，不再是当前执行架构、发布或生产 rollout 的覆盖证据。
- 当前独立 `codex-app-server-worker`、幂等任务受理、Runtime Registry、affinity、路由 CAS、canary 和回滚覆盖在 [1.4.0 OPT-001](../../1.4.0-SNAPSHOT/workitems/OPT-001-independent-codex-app-server-worker-requirement.md) 重新审计。

## Background

- 审计对象：OPT-005 Codex Sol Max / Ultra 与 Ultra 原生子任务 PC 进度展示
- 当前阶段：历史覆盖审计已完成；当前仅用于保留范围证据追溯
- 审计目标：确认模型映射、app-server 门控、防重复执行、隐私边界、Java 状态恢复/删除和 PC 实时展示均有证据

## Audit Basis

- requirement/progress: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-005-codex-sol-max-ultra-support.md`
- implementation quality: `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-005-implementation-quality.md`
- migration: `docs/migration/2026-07-10-native-subtask-states.sql`
- test records: Worker 128 tests、Java 93 + 47 focused tests、Frontend 133 tests、build/type checks、真实 Worker smoke、MySQL 8 smoke、Playwright

## Coverage Matrix

| Item | Risk | Unit | Integration | Live / E2E | Playwright | Evidence | Coverage |
|---|---|---|---|---|---|---|---|
| `codex-max` / `codex-ultra` 映射、授权和 reasoning 透传 | critical | yes | yes | yes | yes | Worker/Java/frontend regression + Max/Ultra SSE smoke | covered |
| app-server Ultra 默认关闭 | critical | yes | no | yes | no | config/health tests；默认 SDK lane | historical-covered-superseded |
| bundled CLI 必须精确匹配 `0.144.1` | critical | yes | no | yes | no | runtime/health tests；flag-on health smoke | historical-covered-superseded |
| `turn/start` 前失败可 fallback，提交后禁止 SDK 重放 | critical | yes | no | partial | no | app-server runtime/sdk-wrapper fault tests；真实 lane 日志 | historical-constraint-input |
| 原生子任务状态归一化与终态权威顺序 | critical | yes | no | yes | yes | tracker fixture tests；真实 6 native events | covered |
| 不发送 prompt/output/reasoning/tool payload/credential，失败消息只用稳定通用码 | critical | yes | no | yes | yes | bridge/tracker/Java allowlist assertions；real smoke 字段检查；UI ID 隐藏 | covered |
| Java latest-state upsert、旧 seq 丢弃、同 seq 幂等 | critical | yes | no | no | no | `CodexNativeSubtaskServiceTest` | covered |
| 父任务锁串行化并阻止删除后 late event 重建 | critical | yes | no | no | no | service/repository review + focused tests | covered-with-gap |
| 用户归属快照 API，跨用户拒绝 | critical | yes | yes | no | yes | `TaskControllerTest` / query service / mocked PC API | covered |
| 原生事件不进入聊天历史，只走统一 session SSE | critical | yes | yes | no | yes | relay/listener tests + Task Pane event tests | covered |
| 两阶段可恢复删除，跨用户 fallback 禁止 | critical | yes | yes | no | no | `CodexTaskServiceTest` + `TaskDispatchFacadeTest` | covered |
| PC snapshot + SSE seq merge、旧响应/重复回调/重连 | major | yes | no | no | partial | reducer/useTaskPane/useUnifiedSse 覆盖 seq/epoch；E2E 只覆盖 mocked snapshot contract | covered-layered |
| 多 Pane 隔离、层级、窄 Pane、内部 ID 隐藏 | major | yes | no | no | partial | Vitest 覆盖多 Pane；`codex-native-subtasks.spec.ts` 覆盖单 Pane 层级、ID 和窄布局 | covered-layered |
| 显式 MySQL 迁移可重复执行 | critical | no | yes | yes | no | 临时官方 MySQL 8 连续执行两次 | covered |
| 生产目标库迁移与 `ddl-auto=validate` 启动 | critical | no | no | no | no | release gate | open-rollout-gate |
| 真实 Worker -> Java -> SSE -> PC 单链路 | critical | layer-only | layer-only | no | partial | real Worker + Java tests + mocked PC E2E | gap-canary-required |

## Evidence Summary

- Worker：`npm test` 128 / 128，`npm run typecheck`、`npm run build` passed。
- Java Session：`TaskDispatchFacadeTest,NativeSubtaskQueryServiceTest,TaskControllerTest,SessionEventListenerTest` 93 / 93 passed。
- Java Codex：`CodexNativeSubtaskServiceTest,CodexStreamRelayTest,CodexTaskServiceTest` 47 / 47 passed。
- Frontend：`pnpm test` 133 / 133，`pnpm type-check`、`pnpm build` passed；build 只有既有 bundle-size/dynamic-import warning。
- Playwright：`packages/navigator-frontend/e2e/codex-native-subtasks.spec.ts` 1 / 1 passed，覆盖 mocked 订阅确认后的单 Pane 快照、4 行状态/层级、ID 隐藏及 320px Pane 无溢出；没有发送真实 SSE event，也未覆盖浏览器多 Pane/断线重连。
- Real Worker historical evidence：flag-on health 显示 CLI/SDK `0.144.1`、protocol compatible/native supported；Ultra 只读任务得到 39 条事件、6 条 native update、1 个 child、1 result、0 error，seq 严格递增，工作区 Git 状态不变。该 smoke 不证明 1.4.0 独立 Worker 可发布。
- Migration：`docs/migration/2026-07-10-native-subtask-states.sql` 在临时官方 MySQL 8 同库执行两次成功，表、20 列、`message VARCHAR(64)` 及索引符合预期。
- Static check：`git diff --check` passed，仅报告既有 CRLF conversion warning。

## Gaps

- 缺口 1：没有真实独立 app-server Worker -> Java -> unified SSE -> PC 单条全栈自动化。旧混合 Worker 的逐层证据足以承接保留投影范围，但不能替代 1.4.0 canary。
- 缺口 2：没有在目标生产数据库执行迁移和生产 profile `ddl-auto=validate` 启动。这是发布门禁，不能由临时 MySQL smoke 替代。
- 缺口 3：尚无真实 MySQL/JPA 多实例并发测试验证父任务悲观锁；当前由事务实现、focused tests 和唯一键共同保护。
- 缺口 4：已有运行时测试验证 post-turn 分类，但可进一步增加顶层故障注入，直接断言提交后 SDK 调用次数为零。

## Historical Release Gate (Superseded)

以下步骤记录当时对旧混合 lane 的 rollout 设想，保留用于审计追溯，但不得作为当前发布操作清单执行：

1. 在目标数据库先执行 `docs/migration/2026-07-10-native-subtask-states.sql`。
2. 用生产配置启动 Java 并确认 `ddl-auto=validate` 通过。
3. 只在 canary Worker 设置 `CODEX_APP_SERVER_ULTRA_ENABLED=true`，确认 health 五个 app-server/native 字段符合预期。
4. 执行真实 Ultra 委派，核对 Worker native event、Java snapshot/SSE 和 PC Task Pane 同一 task 的状态收敛。
5. 验证失败/中断/删除及 SDK 稳定 lane 后再扩大启用；任一门禁失败时关闭 flag，不修改 CLI 版本门控。

当前生产启用门禁以 1.4.0 OPT-001 的 P0-P7、Runtime Registry、路由/affinity、canary 和回滚证据为准；本审计不批准任何旧 Worker app-server flag 上线。

## Conclusion

- conclusion: ready-with-gaps
- can_enter_acceptance: yes-for-retained-scope-only
- conclusion_scope: retained alias / authorization / Java / Session / PC projection only
- production_enablement: reopened-in-1.4.0
- old_mixed_lane_release_evidence: superseded
- follow_up_required: yes
