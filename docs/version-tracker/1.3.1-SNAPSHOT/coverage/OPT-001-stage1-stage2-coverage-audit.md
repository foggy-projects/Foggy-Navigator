---
audit_scope: feature
audit_mode: pre-acceptance-check
version: v1.0
tracked_version: 1.3.1-SNAPSHOT
target: OPT-001-stage1-stage2-java-dispatch-governance
status: reviewed
conclusion: ready-for-acceptance
reviewed_by: Codex
reviewed_at: 2026-06-25
follow_up_required: no
---

# Test Coverage Audit

## Background

本审计覆盖 `OPT-001` 中已经实现并完成质量门的 Stage 1 与 Stage 2 Java 侧治理切片：

- Stage 1：Provider route / worker backend / modelConfig 兼容规则收口到 `ProviderRouteRegistry`。
- Stage 2：`TaskDispatchFacade` 内部职责拆分到 Provider 查找、创建目标推导、统一投影和任务操作路由。

本审计不覆盖尚未实现的 Stage 3 Provider 状态 schema、Stage 4 SSE 部署边界和 Stage 5 运行配置硬化。以上阶段继续保留在 `OPT-001` 后续规划中。

质量门曾指出 `reconnectTask`、`resyncTask`、`scanCheckpoints` 三类 pass-through 操作缺少独立断言。本轮审计前已补齐这些单测，并补充 direct route 创建时 `attachments` 的透传断言。

## Audit Basis

参考文档：

- `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
- `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-method-responsibility-inventory.md`
- `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-001-implementation-quality.md`
- `docs/a2a-agent-architecture.md`

测试证据：

- `navigator-common/target/surefire-reports/com.foggy.navigator.common.util.ProviderRouteRegistryTest.txt`
- `session-module/target/surefire-reports/com.foggy.navigator.session.service.TaskDispatchFacadeTest.txt`
- `session-module/target/surefire-reports/com.foggy.navigator.session.service.TaskQueryProviderRegistryTest.txt`
- `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md` 中记录的 Stage 0、Stage 1、Stage 2.1、Stage 2.2~2.4、Stage 2.5 与质量门测试结果。

本轮新增或强化的单测位置：

- `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java`
  - `createTask_usesDirectProviderRouteWhenModelConfigTargetsLangGraphBiz`
  - `reconnectTask_routesViaUnifiedSessionStoreProviderType`
  - `resyncTask_routesViaUnifiedSessionStoreProviderTypeAndReturnsProviderPayload`
  - `scanCheckpoints_routesViaUnifiedSessionStoreProviderTypeAndReturnsProviderPayload`

## Coverage Matrix

| Requirement / Acceptance Item | Risk | Existing validation layer | Evidence path | Coverage |
| --- | --- | --- | --- | --- |
| `STAGE1-REQ-1` Provider route / backend / modelConfig 统一入口 | critical | unit-test, manual-evidence | `ProviderRouteRegistryTest`；`TaskDispatchFacadeTest`；四个 `*WorkerAgentProviderTest`；workitem Stage 1 results | covered |
| `STAGE1-REQ-2` backend capability migration 到 metadata、business、OpenAPI readiness / diagnostics | major | unit-test, manual-evidence | `LlmModelManagerImplTest`；`ClientAppModelConfigGrantServiceTest`；`BusinessAgentTaskServiceTest`；`OpenApiAgentReadinessServiceTest`；`OpenApiControllerMessageMappingTest`；workitem Stage 1 Follow-up results | covered |
| `STAGE2-REQ-1` Provider lookup 与 capability fan-out 收敛 | major | unit-test, manual-evidence | `TaskQueryProviderRegistryTest`；`TaskDispatchFacadeTest` list/search/worker session 相关测试；workitem Stage 2.2~2.4 results | covered |
| `STAGE2-REQ-2` create target resolver 与 direct provider route 保持兼容 | critical | unit-test, manual-evidence | `TaskDispatchFacadeTest` createTask Codex、Gemini、LangGraph、Codex Biz direct route 测试；`attachments`、`images`、`context`、modelConfig 透传断言 | covered |
| `STAGE2-REQ-3` unified session-store projection 与 DTO 兼容 | major | unit-test, manual-evidence | `TaskDispatchFacadeTest` list/search/getTask/compact item/agentId preservation 相关测试；workitem Stage 2.3 results | covered |
| `STAGE2-REQ-4` task operation router 承接生命周期和恢复类操作 | critical | unit-test, manual-evidence | `TaskDispatchFacadeTest` resume/cancel/respond/rewind/reconnect/resync/scan/delete 相关测试；本轮新增 pass-through 路由断言 | covered |
| `AC-CONTRACT` REST / OpenAPI / SDK payload 与 `DispatchTaskDTO` 对外兼容 | critical | unit-test, manual-evidence | `createTask_imagesPassedAsStringNotListInA2aMessage`；`submitTask_routesThroughCreateTaskAndPreservesA2aMetadata`；direct route `attachments/images/context` 断言；DTO projection 测试 | covered |
| `AC-DOC` Stage 1/2 架构说明、方法职责和进度记录同步 | minor | manual-evidence | `docs/a2a-agent-architecture.md`；method responsibility inventory；quality gate；本 coverage audit | covered |

## Evidence Summary

本轮覆盖审计前补齐了 Stage 2 质量门指出的 pass-through 操作测试缺口：

- 新增 `reconnectTask_routesViaUnifiedSessionStoreProviderType`，验证按 unified session-store 中的 providerType 路由，不回退到 `getTaskById` 探测。
- 新增 `resyncTask_routesViaUnifiedSessionStoreProviderTypeAndReturnsProviderPayload`，验证 provider 路由和 provider payload 返回。
- 新增 `scanCheckpoints_routesViaUnifiedSessionStoreProviderTypeAndReturnsProviderPayload`，验证 checkpoint 查询走归属 provider，并返回 provider payload。
- 强化 `createTask_usesDirectProviderRouteWhenModelConfigTargetsLangGraphBiz`，验证 `attachments` 通过 direct provider route 透传。

最新验证结果：

| Scope | Command summary | Result |
| --- | --- | --- |
| Stage 2 覆盖补测定向回归 | `mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest,TaskQueryProviderRegistryTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"` | PASS：56 tests，0 failures，0 errors，0 skipped |
| Stage 2 覆盖补测 session 全量回归 | `mvn test -pl session-module -am` | PASS：242 tests，0 failures，0 errors，0 skipped |

历史验证结果已在 workitem 中留存：

- Stage 1 route registry 定向回归：87 tests pass；受影响 Java reactor：1475 tests pass。
- Stage 1 follow-up / Stage 2.1：session provider inference 28 tests pass；backend capability migration 118 tests pass；Stage 2.1 facade lookup extraction 78 tests pass；affected Java reactor 1522 tests pass。
- Stage 2.2~2.4：定向回归 111 tests pass；受影响 Java reactor 1525 tests pass。
- Stage 2.5：定向回归 53 tests pass；session 全量 239 tests pass。
- Stage 2 implementation quality gate：定向回归 53 tests pass；session 全量 239 tests pass。

## Gaps

Stage 1/2 验收切片内未发现阻断覆盖缺口。

非本审计范围但必须继续进入后续规划的风险：

- `providerStateJson` / `taskStateJson` 仍缺少版本化 schema、typed codec 和迁移策略；这是 Stage 3 主目标。
- `TaskQueryProvider` 仍承担查询、生命周期、恢复和 worker session 等多类职责，后续可评估拆分为更窄端口。
- `UnifiedSessionTaskProjectionService` 仍保留反射读取 Provider 私有 page/search envelope，这是兼容性选择；引入 typed envelope 前需要继续保留现有兼容测试。
- 若 Stage 3 改动 SPI、Provider 状态字段或跨模块 Provider 实现，建议重新运行受影响 Java reactor 全量回归，不只跑 session-module。

## Recommended Next Skills

- `foggy-acceptance-signoff`：可用于对 Stage 1/2 Java dispatch governance 切片做正式验收签收。
- `plan-evaluator`：可选，用于评估 Stage 3 先做 Provider 状态 schema 还是先拆 `TaskQueryProvider` 窄端口。
- `session-module` / 对应 worker 模块技能：用于继续推进 Stage 3 Provider 状态 schema 和相关回归测试。

## Conclusion

conclusion: `ready-for-acceptance`

Stage 1/2 Java 侧 dispatch governance 切片的关键 requirement、acceptance item 与测试证据已经形成可追溯映射。本轮已补齐质量门指出的 `reconnect`、`resync`、`scan checkpoints` pass-through 路由断言，并用 session 全量回归确认未引入回归。

可进入验收范围：`OPT-001` Stage 1 与 Stage 2。

不进入本次验收范围：Stage 3 Provider 状态 schema、Stage 4 SSE 部署边界、Stage 5 运行配置硬化。
