---
type: optimization
version: 1.3.1-SNAPSHOT
ticket: OPT-001
stage: 17
severity: medium
status: signed-off
owner: java-platform
created_at: 2026-06-26
---

# OPT-001 Stage 17: Legacy Provider Method Deprecation Gate

## 文档作用

- doc_type: workitem
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 记录 Stage 17 对 `TaskListingProvider` / `WorkerSessionQueryProvider` legacy Map/Object 方法的调用面审计、deprecation 标记、验证和后续 removal gate。

## Background

Stage 14 已让 listing/search provider fan-out 主链路迁移到 typed methods；Stage 15 已让 worker-session provider fan-out 主链路迁移到 typed DTO / envelope；Stage 16 已将 Claude worker-session 查询拆到独立 provider bean。

当前遗留风险是 legacy `Object` / `Map` 方法仍作为 SPI 默认兼容面保留。如果直接删除，可能破坏外部插件、测试 stub 或尚未显式迁移的 provider；如果没有显式 deprecation，又会让后续实现继续误用旧契约。

## Compatibility Audit

当前扫描结论：

- session-module 生产 provider fan-out 已走 typed methods，不再直接调用 provider legacy listing / worker-session 方法。
- `TaskController` / `TaskDispatchFacade` 的同名 public 方法属于 REST / service 对外兼容入口，不属于本阶段删除目标。
- Claude / Codex / Codex Biz listing provider 仍保留 legacy wrapper，返回 typed result 或 Map-compatible envelope。
- Claude / LangGraph worker-session provider 仍保留 legacy wrapper，返回 REST-compatible Map payload。
- `navigator-spi` 的 `TaskQueryProvider` 聚合接口仍作为外部兼容面继承 listing 与 worker-session legacy defaults。
- `navigator-spi` 的 `ClaudeWorkerFacade#listWorkerSessions` 是另一个对外 SPI，不属于 `TaskListingProvider` / `WorkerSessionQueryProvider` 本阶段 removal 范围。

## Scope

本阶段只做低风险 deprecation gate：

- 在 `TaskListingProvider` legacy 方法上标注 `@Deprecated(since = "1.3.1", forRemoval = false)`。
- 在 `WorkerSessionQueryProvider` legacy 方法上标注 `@Deprecated(since = "1.3.1", forRemoval = false)`。
- 在当前 provider legacy wrapper overrides 上同步标注 deprecation，避免直接引用具体 service 时绕过提示。
- 补充反射回归，确保 SPI legacy methods 保持 deprecated 且 `forRemoval=false`。
- 更新版本文档，明确后续 removal 前置条件。

## Non-Goals

- 不删除任何 legacy SPI 方法。
- 不改变 REST API 方法名、路径或 payload。
- 不改变 `TaskDispatchFacade` / `TaskController` 对外方法名。
- 不 deprecate `navigator-spi` 的 `ClaudeWorkerFacade#listWorkerSessions`。
- 不把 `forRemoval` 改成 `true`。
- 不要求外部插件在本阶段完成迁移。

## Removal Gate

后续进入 removal 阶段前至少满足：

- 仓库内生产代码扫描确认没有 provider legacy method fan-out 调用。
- 所有内置 provider 已实现 typed methods，legacy wrapper 仅为兼容保留。
- 外部插件 / SDK / 上游接入方有迁移窗口和 release note。
- 至少一个版本周期保留 `@Deprecated(forRemoval=false)` 后，再单独评估 `forRemoval=true`。
- removal 阶段必须另起 workitem，并跑 broader Java worker reactor 或仓库级全量测试。

## Implementation Plan

1. 给 `TaskListingProvider` / `WorkerSessionQueryProvider` legacy default methods 增加 deprecation annotation，并在 typed default adapter 上 suppress 兼容调用告警。
2. 给 Claude / Codex / Codex Biz listing legacy wrappers 增加 deprecation annotation。
3. 给 Claude / LangGraph worker-session legacy wrappers 增加 deprecation annotation。
4. 新增 `TaskProviderLegacyContractTest` 反射断言 SPI legacy methods 的 deprecation 契约。
5. 运行 targeted regression、affected reactor、静态扫描与 diff check。

## Acceptance Criteria

- SPI legacy listing 方法均标记 `@Deprecated(since = "1.3.1", forRemoval = false)`。
- SPI legacy worker-session 方法均标记 `@Deprecated(since = "1.3.1", forRemoval = false)`。
- Provider legacy wrapper overrides 已同步标记 deprecated。
- typed provider methods 与 REST payload 兼容行为不变。
- 反射回归覆盖 deprecation 契约。
- targeted regression 与 affected reactor 回归通过。
- 静态扫描确认生产 fan-out 仍不直接调用 legacy provider methods。

## Verification Plan

```powershell
mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/langgraph-biz-worker -am "-Dtest=TaskProviderLegacyContractTest,TaskDispatchFacadeTest,TaskQueryProviderRegistryTest,ClaudeTaskServiceAuthTest,ClaudeWorkerSessionQueryServiceTest,CodexTaskServiceTest,CodexBizTaskProviderTest,LanggraphWorkerSessionQueryServiceTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"
mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/langgraph-biz-worker -am
rg -n "provider\.(listTasksPaged|searchSessions|listTasksByDirectoryPaged|listWorkerSessions|getWorkerSessionMessageCount|getWorkerSessionMessages|syncWorkerSessions)\(" session-module/src/main/java addons/claude-worker-agent/src/main/java addons/codex-worker-agent/src/main/java addons/langgraph-biz-worker/src/main/java navigator-spi/src/main/java
git diff --check
```

## Progress Tracking

### Development Progress

- [x] Legacy provider method 调用面审计完成。
- [x] SPI legacy method deprecation annotation 完成。
- [x] Provider wrapper deprecation annotation 完成。
- [x] 反射回归测试完成。
- [x] README / governance 回写完成。

### Testing Progress

- [x] Stage 17 targeted regression：131 tests pass。
- [x] Stage 17 affected reactor regression：Surefire XML 合计 219 reports / 1528 tests，0 failures，0 errors，0 skipped。
- [x] Stage 17 static scan：production provider fan-out legacy method scan 无匹配；deprecated annotation scan 命中 24 处 expected annotations。
- [x] `git diff --check`：无 whitespace error，仅 CRLF normalization warnings。

### Experience Progress

- N/A。该切片为 Java 后端 SPI 兼容契约治理，未新增或修改 UI 页面、表单、按钮、权限可见性或前端交互。

## Execution Check-in

- status: completed
- completed work summary: 已完成 legacy listing / worker-session provider method 调用面审计；SPI legacy default methods 与当前内置 provider legacy wrapper overrides 已标记 `@Deprecated(since = "1.3.1", forRemoval = false)`；新增反射回归锁定 deprecation 契约；补齐质量门、覆盖审计和功能级验收记录。
- touched code paths:
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/TaskListingProvider.java`
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/WorkerSessionQueryProvider.java`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeWorkerSessionQueryService.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexBizTaskProvider.java`
  - `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphWorkerSessionQueryService.java`
  - `session-module/src/test/java/com/foggy/navigator/session/service/TaskProviderLegacyContractTest.java`
- test status: pass。targeted regression 131 tests pass；affected reactor 1528 tests pass；static scan 无生产 fan-out legacy 调用；`git diff --check` 无 whitespace error。
- remaining risks / blockers: 无阻断项。剩余风险是外部插件/SDK 调用方仍可能使用 legacy methods；removal 前必须另起 workitem、提供迁移窗口并评估 `forRemoval=true`。
- quality gate: `quality/OPT-001-stage17-implementation-quality.md`，decision=`ready-with-risks`。
- coverage audit: `coverage/OPT-001-stage17-coverage-audit.md`，conclusion=`ready-with-gaps`，can_enter_acceptance=`yes`。
- acceptance readiness: ready-with-risks

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-26
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage17-legacy-provider-method-deprecation-acceptance.md
- blocking_items: none
- follow_up_required: yes
