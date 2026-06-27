---
audit_scope: feature
audit_mode: pre-acceptance-check
version: 1.3.1-SNAPSHOT
target: OPT-001-stage14-task-listing-typed-method
status: reviewed
conclusion: ready-with-gaps
reviewed_by: Codex
reviewed_at: 2026-06-26
follow_up_required: yes
---

# Test Coverage Audit

## Background

Stage 14 的目标是让 `TaskListingProvider` listing/search 主链路以 `TaskPageResult` / `TaskSearchResult` typed methods 表达，同时继续兼容旧 Map / JavaBean envelope 和 legacy `Object` 方法。

## Audit Basis

- Work item: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage14-task-listing-typed-method.md`
- Quality gate: `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-001-stage14-implementation-quality.md`
- Code:
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/TaskListingProvider.java`
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/TaskPageResult.java`
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/TaskSearchResult.java`
  - `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexBizTaskProvider.java`

## Coverage Matrix

| Requirement / acceptance item | Risk | Evidence layer | Evidence | Coverage |
| --- | --- | --- | --- | --- |
| `TaskListingProvider` 提供 typed listing/search 主方法 | major | compile/test | affected reactor 编译通过；targeted tests pass | covered |
| `TaskDispatchFacade` fan-out 改为 typed methods | major | unit-test/static-check | `TaskDispatchFacadeTest#listTasksPaged_aggregatesSessionsAcrossProviders`；static scan 无 legacy provider 调用 | covered |
| Claude / Codex / Codex Biz Provider typed override | major | compile/test | affected reactor；Claude/Codex focused targeted tests | covered |
| legacy `Object` 方法继续兼容旧调用方 | major | unit-test | Provider legacy wrapper 编译通过；typed methods 默认适配 legacy 方法 | covered |
| legacy Map / JavaBean envelope 可被 typed adapter 读取 | major | unit-test | `UnifiedSessionTaskProjectionServiceTest#taskListingProviderTypedMethodsAdaptLegacyEnvelopes` | covered |
| Codex listing service 可直接走 typed method | medium | unit-test | `CodexTaskServiceTest#listTasksPaged_groupsCodexTasksBySessionAndSupportsInteractionStateFilter` | covered |
| REST / OpenAPI / SDK payload 兼容 | major | regression | session/controller payload 未改；affected reactor pass | covered |
| 受影响 reactor 回归通过 | major | integration-test | `mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent -am`，1380 tests pass | covered |
| 全量仓库测试 | minor | integration-test | 未运行全量 `mvn test` | partially-covered |

## Evidence Summary

- First targeted run exposed a test-only SPI reflection visibility gap for private legacy bean; fixed by making the test legacy bean public.
- `mvn test -pl session-module,addons/codex-worker-agent,addons/claude-worker-agent -am "-Dtest=TaskDispatchFacadeTest,UnifiedSessionTaskProjectionServiceTest,TaskQueryProviderRegistryTest,CodexTaskServiceTest,CodexBizTaskProviderTest,ClaudeTaskServiceAuthTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：121 tests pass。
- `mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent -am`：affected reactor pass；Surefire XML 合计 191 suites / 1380 tests，0 failures，0 errors，0 skipped。
- `rg -n "provider\.(listTasksPaged|searchSessions|listTasksByDirectoryPaged)\(" session-module/src/main/java addons/claude-worker-agent/src/main/java addons/codex-worker-agent/src/main/java navigator-spi/src/main/java`：无匹配。
- `git diff --check`：无 whitespace error，仅 CRLF normalization warnings。

## Gaps

- 未运行仓库级全量 `mvn test`。本阶段影响面集中在 navigator-spi、session-module、Claude 与 Codex addon，affected reactor 已覆盖直接依赖链。
- 未新增完整 Spring ApplicationContext 启动测试断言真实 Provider bean list；本阶段未改变 bean 类型集合，只改变 listing method contract。
- 未新增旧外部插件二进制兼容测试；保留 legacy `Object` default methods 以降低风险。

## Recommended Next Skills

- `foggy-acceptance-signoff`

## Conclusion

conclusion=`ready-with-gaps`。Stage 14 核心验收项已有单元测试、静态扫描和 affected reactor 证据承接；缺口为全仓全量测试、启动级 bean list 和外部插件二进制兼容测试，均不阻断当前切片验收。
