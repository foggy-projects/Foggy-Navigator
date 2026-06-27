---
type: optimization
version: 1.3.1-SNAPSHOT
ticket: OPT-001
stage: 18
severity: medium
status: signed-off
owner: java-platform
created_at: 2026-06-26
---

# OPT-001 Stage 18: Task Command Cancel Direct Method

## 文档作用

- doc_type: workitem
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 记录 Stage 18 对 `TaskCommandProvider#cancelTask` legacy fallback 的调用面审计、direct method 收敛、验证和后续 removal gate。

## Background

Stage 17 已给 listing / worker-session legacy provider methods 建立 deprecation gate。继续审计 command provider 后发现，`TaskCommandProvider#cancelTask(String, String)` 已标记 `forRemoval=true`，但 session provider-route 仍直接调用该方法，且内置 provider 的真实取消逻辑仍实现于该 legacy override 中。

这会造成两个问题：

- 主链路仍依赖 deprecated-for-removal 方法，后续删除不可执行。
- `TaskCommandProvider#cancelTask(String, String)` 与 A2A 的 `A2aAgent#cancelTask(String)` 名称相近，容易误判为同一条取消路径。

## Compatibility Audit

当前扫描结论：

- session-module provider direct cancel route 会调用 `TaskCommandProvider#cancelTask(String, String)`。
- Claude / Codex / Codex Biz / Gemini / LangGraph 内置 command provider 均有具体 cancel 行为。
- `CodexBizTaskProvider` 与 `LanggraphWorkerInnerA2aAgent` 存在 service 级 direct cancel 调用。
- `A2aAgent#cancelTask(String)` / `InnerA2aAgent#cancelTask(String)` 属于 A2A abort 链路，不属于本阶段迁移目标。
- REST controller / `TaskDispatchFacade#cancelTask` 属于对外 API / facade 命名，不属于本阶段 deprecation 范围。

## Scope

本阶段只做 provider command 侧低风险收敛：

- 在 `TaskCommandProvider` 增加非 deprecated `cancelTaskDirect(String taskId, String userId)`。
- 将 session provider-route 主链路迁移到 `cancelTaskDirect`。
- 将内置 provider 的真实取消逻辑迁移到 `cancelTaskDirect`。
- 保留 legacy `cancelTask(String, String)`，改为兼容 wrapper，并将 `forRemoval` 收敛为 `false`。
- 补充反射回归，确保新 direct method 不 deprecated，legacy method deprecated 但暂不 for removal。

## Non-Goals

- 不删除 `TaskCommandProvider#cancelTask(String, String)`。
- 不改变 A2A `A2aAgent#cancelTask(String)` / `InnerA2aAgent#cancelTask(String)` 语义。
- 不改变 REST `/tasks/{id}/cancel` API、controller 方法名或 facade 方法名。
- 不改变各 worker provider 的取消状态机、权限校验或 abort 行为。
- 不调整 listing / worker-session legacy method gate。

## Removal Gate

后续进入 legacy command cancel removal 前至少满足：

- 仓库内生产代码扫描确认 provider command 主链路均使用 `cancelTaskDirect`。
- 外部插件 / SDK / 上游接入方有迁移窗口和 release note。
- 至少一个版本周期保留 `@Deprecated(forRemoval=false)` 后，再单独评估 `forRemoval=true`。
- removal 阶段必须另起 workitem，并跑 broader Java worker reactor 或仓库级全量测试。

## Implementation Plan

1. 给 `TaskCommandProvider` 增加 `cancelTaskDirect` default method，并保留 legacy `cancelTask` wrapper。
2. 将 `TaskOperationRouter` provider cancel route 从 `cancelTask` 切到 `cancelTaskDirect`。
3. 将 Claude / Codex / Codex Biz / Gemini / LangGraph provider 真实取消逻辑迁移到 `cancelTaskDirect`，legacy override 委托给 direct method。
4. 将 provider/service 级 direct cancel 调用迁移到 `cancelTaskDirect`。
5. 更新 `TaskProviderLegacyContractTest` 与 provider-route 单测断言。
6. 运行 targeted regression、affected reactor、静态扫描与 diff check。

## Acceptance Criteria

- `TaskCommandProvider#cancelTaskDirect(String, String)` 存在且不标记 deprecated。
- `TaskCommandProvider#cancelTask(String, String)` 保留兼容，标记 `@Deprecated(forRemoval=false)`。
- session provider cancel route 调用 `cancelTaskDirect`。
- 内置 provider 真实取消逻辑位于 `cancelTaskDirect`，legacy `cancelTask` 仅委托 direct method。
- A2A cancel 链路未被重命名或改变语义。
- targeted regression 与 affected reactor 回归通过。
- 静态扫描确认 session provider-route 不再直接调用 legacy cancel。

## Verification Plan

```powershell
mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am "-Dtest=TaskProviderLegacyContractTest,TaskDispatchFacadeTest,TaskQueryProviderRegistryTest,ClaudeTaskServiceAuthTest,CodexTaskServiceTest,CodexBizTaskProviderTest,GeminiTaskServiceAuthResolutionTest,LanggraphTaskServiceTest,LanggraphWorkerInnerA2aAgentTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"
mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am
rg -n "provider\.cancelTask\(" session-module/src/main/java
rg -n "cancelTaskDirect\(" navigator-spi/src/main/java session-module/src/main/java addons/claude-worker-agent/src/main/java addons/codex-worker-agent/src/main/java addons/gemini-worker-agent/src/main/java addons/langgraph-biz-worker/src/main/java
git diff --check
```

## Progress Tracking

### Development Progress

- [x] command provider cancel 调用面审计完成。
- [x] SPI direct method 与 legacy wrapper 调整完成。
- [x] session provider-route 迁移完成。
- [x] 内置 provider direct method 迁移完成。
- [x] 反射回归与 provider-route 单测更新完成。
- [x] README / governance 回写完成。

### Testing Progress

- [x] Stage 18 targeted regression：Surefire XML 合计 11 reports / 159 tests，0 failures，0 errors，0 skipped。
- [x] Stage 18 affected reactor regression：Surefire XML 合计 223 reports / 1545 tests，0 failures，0 errors，0 skipped。
- [x] Stage 18 static scan：session provider-route legacy cancel scan 无匹配；`cancelTaskDirect` direct usage scan 覆盖 SPI、session route 和内置 provider；`TaskCommandProvider` legacy `forRemoval=true` annotation scan 无匹配，expected `forRemoval=false` 存在。
- [x] `git diff --check`：无 whitespace error，仅 CRLF normalization warnings。

### Experience Progress

- N/A。该切片为 Java 后端 SPI 兼容契约治理，未新增或修改 UI 页面、表单、按钮、权限可见性或前端交互。

## Execution Check-in

- status: completed
- completed work summary: 已完成 command provider cancel 调用面审计；新增 `TaskCommandProvider#cancelTaskDirect` 非 deprecated direct method；session provider-route 和内置 provider 真实取消逻辑已迁移到 direct method；legacy `cancelTask(String, String)` 保留兼容 wrapper 并收敛为 `forRemoval=false`；新增/更新反射回归与 provider-route 测试；补齐质量门、覆盖审计和功能级验收记录。
- touched code paths:
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/TaskCommandProvider.java`
  - `session-module/src/main/java/com/foggy/navigator/session/service/TaskOperationRouter.java`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexBizTaskProvider.java`
  - `addons/gemini-worker-agent/src/main/java/com/foggy/navigator/gemini/worker/service/GeminiTaskService.java`
  - `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphTaskService.java`
  - `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/adapter/LanggraphWorkerInnerA2aAgent.java`
  - `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java`
  - `session-module/src/test/java/com/foggy/navigator/session/service/TaskProviderLegacyContractTest.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexBizTaskProviderTest.java`
  - `addons/langgraph-biz-worker/src/test/java/com/foggy/navigator/langgraph/worker/service/LanggraphTaskServiceTest.java`
  - `addons/langgraph-biz-worker/src/test/java/com/foggy/navigator/langgraph/worker/adapter/LanggraphWorkerInnerA2aAgentTest.java`
- test status: pass。targeted regression 159 tests pass；affected reactor 1545 tests pass；static scan 无 session provider-route legacy cancel 调用；`git diff --check` 无 whitespace error。
- remaining risks / blockers: 无阻断项。剩余风险是外部插件/SDK 调用方仍可能使用 legacy `cancelTask(String, String)`；removal 前必须另起 workitem、提供迁移窗口并评估 `forRemoval=true`。
- quality gate: `quality/OPT-001-stage18-implementation-quality.md`，decision=`ready-with-risks`。
- coverage audit: `coverage/OPT-001-stage18-coverage-audit.md`，conclusion=`ready-with-gaps`，can_enter_acceptance=`yes`。
- acceptance readiness: ready-with-risks

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-26
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage18-task-command-cancel-direct-method-acceptance.md
- blocking_items: none
- follow_up_required: yes
