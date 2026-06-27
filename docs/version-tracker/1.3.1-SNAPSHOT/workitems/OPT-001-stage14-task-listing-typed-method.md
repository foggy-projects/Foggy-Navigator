---
type: optimization
version: 1.3.1-SNAPSHOT
ticket: OPT-001
stage: 14
severity: medium
status: signed-off
owner: java-platform
created_at: 2026-06-26
---

# OPT-001 Stage 14: TaskListingProvider Typed Method Contract

## 文档作用

- doc_type: workitem
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 记录 Stage 14 将 `TaskListingProvider` listing/search 主调用契约从弱类型 `Object` 迁移到 typed envelope 方法的范围、计划、验证和进度。

## Background

Stage 7 已引入 `TaskPageResult` 与 `TaskSearchResult`，并让统一投影层 typed-first 解析 listing/search envelope。Stage 8 到 Stage 13 已完成 session 侧窄端口注入和生产 Provider 退出聚合 `TaskQueryProvider`。

剩余问题是 `TaskListingProvider` 的三个主方法仍返回 `Object`：

- `listTasksPaged`
- `searchSessions`
- `listTasksByDirectoryPaged`

这让 session fan-out 主链路仍需要在调用端承接弱类型结果。Stage 14 的目标是增加 typed 方法作为新主路径，并把旧 `Object` 方法保留为兼容 fallback。

## Scope

本阶段只治理 listing/search 端口契约：

- `TaskListingProvider` 新增 typed methods：
  - `listTaskPage`
  - `searchSessionPage`
  - `listDirectoryTaskPage`
- `TaskPageResult` / `TaskSearchResult` 增加 legacy envelope adapter，支持旧 Map 与 public JavaBean getter。
- `TaskDispatchFacade` 的 provider fan-out 改为调用 typed methods。
- Claude / Codex / Codex Biz Provider 实现 typed methods，旧 `Object` 方法只保留委派兼容。
- 补充兼容测试，确认旧 Map / JavaBean envelope 仍可被 typed default 方法读取。

## Non-Goals

- 不删除 `TaskListingProvider` 的 legacy `Object` 方法。
- 不把 legacy listing 方法标记 `forRemoval`，避免本阶段引入额外编译告警噪音。
- 不改变 REST / OpenAPI / SDK payload。
- 不删除 `UnifiedSessionTaskProjectionService` 的 legacy fallback。
- 不改变 `TaskPageResult` / `TaskSearchResult` 的字段语义。
- 不处理 worker-session Map payload typed DTO / envelope。
- 不拆 Claude worker-session 物理 bean。

## Implementation Plan

1. 在 `TaskListingProvider` 中新增 typed listing/search 方法，并默认适配 legacy `Object` 方法。
2. 在 `TaskPageResult` 与 `TaskSearchResult` 中增加 legacy Map / JavaBean getter 转换入口。
3. 将 `TaskDispatchFacade` 的三处 listing/search fan-out 调用迁移到 typed methods。
4. 将 Claude / Codex / Codex Biz Provider 的 listing/search 实现改为 typed override，legacy `Object` 方法保留为委派 wrapper。
5. 补充/调整回归测试：
   - session facade 聚合测试走 typed methods。
   - legacy Map / JavaBean envelope 通过 typed default adapter。
   - Codex listing service 测试直接断言 typed method。
6. 运行 targeted regression、affected reactor、静态扫描与 diff check。

## Acceptance Criteria

- `TaskListingProvider` 提供 typed listing/search 主方法。
- `TaskDispatchFacade` provider fan-out 不再直接调用 legacy `Object` listing/search 方法。
- Claude / Codex / Codex Biz Provider 暴露 typed listing/search override。
- legacy `Object` 方法仍可用于旧调用方，并委派到 typed 方法。
- 旧 Provider 返回 Map / JavaBean envelope 时，typed default adapter 仍能读取内容、total、page 和 size。
- REST / OpenAPI / SDK listing/search 响应保持兼容。
- targeted regression 与 affected reactor 回归通过。
- 静态扫描确认生产 fan-out 不再直接调用旧 listing `Object` 方法。

## Verification Plan

```powershell
mvn test -pl session-module,addons/codex-worker-agent,addons/claude-worker-agent -am "-Dtest=TaskDispatchFacadeTest,UnifiedSessionTaskProjectionServiceTest,TaskQueryProviderRegistryTest,CodexTaskServiceTest,CodexBizTaskProviderTest,ClaudeTaskServiceAuthTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"
mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent -am
rg -n "provider\.(listTasksPaged|searchSessions|listTasksByDirectoryPaged)\(" session-module/src/main/java addons/claude-worker-agent/src/main/java addons/codex-worker-agent/src/main/java navigator-spi/src/main/java
git diff --check
```

## Progress Tracking

### Development Progress

- [x] `TaskListingProvider` 新增 typed methods，并保留 legacy `Object` 方法。
- [x] `TaskPageResult` / `TaskSearchResult` 新增 legacy envelope adapter。
- [x] `TaskDispatchFacade` listing/search fan-out 迁移到 typed methods。
- [x] Claude / Codex / Codex Biz Provider typed override 完成。
- [x] session facade、projection compatibility、Codex listing 测试更新完成。
- [x] README / governance 回写完成。

### Testing Progress

- [x] Stage 14 targeted regression。
- [x] Stage 14 affected reactor regression。
- [x] Stage 14 static scan。
- [x] `git diff --check`。

### Experience Progress

- N/A。该切片为 Java 后端 SPI 契约收敛，未新增或修改 UI 页面、表单、按钮、权限可见性或前端交互。

## Execution Check-in

- status: completed
- completed work summary:
  - `TaskListingProvider` 已新增 `listTaskPage`、`searchSessionPage`、`listDirectoryTaskPage` typed methods。
  - `TaskPageResult.from(...)` 与 `TaskSearchResult.from(...)` 已支持旧 Map / public JavaBean getter envelope 适配。
  - `TaskDispatchFacade` 三处 provider fan-out 已迁移为 typed method 调用。
  - Claude / Codex / Codex Biz Provider 已实现 typed listing/search override，旧 `Object` 方法保留并委派到 typed 方法。
  - `UnifiedSessionTaskProjectionServiceTest#taskListingProviderTypedMethodsAdaptLegacyEnvelopes` 覆盖 legacy Map / JavaBean 兼容。
  - `TaskDispatchFacadeTest#listTasksPaged_aggregatesSessionsAcrossProviders` 已改为 stub typed listing port。
  - `CodexTaskServiceTest#listTasksPaged_groupsCodexTasksBySessionAndSupportsInteractionStateFilter` 已直接断言 typed method。
- touched code paths:
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/TaskListingProvider.java`
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/TaskPageResult.java`
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/TaskSearchResult.java`
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/TaskResultEnvelopeAdapters.java`
  - `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`
  - `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java`
  - `session-module/src/test/java/com/foggy/navigator/session/service/UnifiedSessionTaskProjectionServiceTest.java`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexBizTaskProvider.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexTaskServiceTest.java`
  - `docs/version-tracker/1.3.1-SNAPSHOT/README.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage14-task-listing-typed-method.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-001-stage14-implementation-quality.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/coverage/OPT-001-stage14-coverage-audit.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage14-task-listing-typed-method-acceptance.md`
- test status:
  - First targeted run exposed a test-only SPI reflection visibility gap for private legacy bean; fixed by making the test legacy bean public.
  - `mvn test -pl session-module,addons/codex-worker-agent,addons/claude-worker-agent -am "-Dtest=TaskDispatchFacadeTest,UnifiedSessionTaskProjectionServiceTest,TaskQueryProviderRegistryTest,CodexTaskServiceTest,CodexBizTaskProviderTest,ClaudeTaskServiceAuthTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：121 tests pass。
  - `mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent -am`：affected reactor pass；Surefire XML 合计 191 suites / 1380 tests，0 failures，0 errors，0 skipped。
  - `rg -n "provider\.(listTasksPaged|searchSessions|listTasksByDirectoryPaged)\(" session-module/src/main/java addons/claude-worker-agent/src/main/java addons/codex-worker-agent/src/main/java navigator-spi/src/main/java`：无匹配，生产 fan-out 不再直接调用 legacy listing `Object` 方法。
  - `git diff --check`：无 whitespace error，仅 CRLF normalization warnings。
- remaining risks / blockers:
  - blocking_items: none
  - legacy `Object` 方法仍保留，后续可在所有外部/插件调用方确认后再废弃或删除。
  - legacy adapter 只覆盖 Map 与 public JavaBean getter envelope；非标准对象返回仍不作为正式契约承诺。
  - `UnifiedSessionTaskProjectionService` 的 legacy fallback 仍保留，用于 REST/session store 兼容；后续可在外部契约稳定后再收缩。
  - worker-session payload 仍为 Map，typed DTO / envelope 待后续阶段。
  - Claude worker-session 查询仍在 task service 物理 bean 内，后续如需职责隔离应单独拆分。
- acceptance readiness: ready-with-risks

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-26
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage14-task-listing-typed-method-acceptance.md
- blocking_items: none
- follow_up_required: yes
