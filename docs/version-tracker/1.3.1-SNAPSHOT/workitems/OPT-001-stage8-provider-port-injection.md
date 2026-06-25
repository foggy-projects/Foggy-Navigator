---
type: optimization
version: 1.3.1-SNAPSHOT
ticket: OPT-001
stage: 8
severity: medium
status: signed-off
owner: java-platform
created_at: 2026-06-25
---

# OPT-001 Stage 8: Provider Port Injection Narrowing

## Background

Stage 6 已拆出 `TaskLookupProvider`、`TaskCommandProvider`、`TaskListingProvider`、`WorkerSessionQueryProvider` 四类窄端口，Stage 7 已为 listing/search envelope 建立 typed 主路径。但 session 侧 `TaskDispatchFacade` 构造期仍只注入 `List<TaskQueryProvider>`，`TaskQueryProviderRegistry` 内部也以宽聚合接口作为唯一 Provider 集合，再向窄端口 cast。

这意味着调用点虽然开始使用窄端口类型，但 Provider 注册与发现仍被宽接口绑住。后续若希望某个 Provider 只实现 lookup 或 listing 独立端口，当前 registry 无法直接接收。

## Scope

本阶段只治理 session 侧 Provider port 注入与 registry 内部集合：

- `TaskDispatchFacade` 构造期接收 lookup / command / listing / worker-session 四类端口集合。
- `TaskQueryProviderRegistry` 内部按窄端口分别维护集合。
- capability filtering 在具体端口集合内执行，并保持 legacy empty capability fallback。
- `findCommandProviderForTask` 改为先通过 lookup 端口识别任务归属，再按 providerType 匹配 command 端口。
- 保持现有 Provider 实现继续实现 `TaskQueryProvider`，不要求本阶段批量拆 bean。
- 保持 REST / OpenAPI / SDK payload 不变。

## Non-Goals

- 不删除 `TaskQueryProvider` 兼容聚合接口。
- 不把 Claude / Codex / Gemini / LangGraph Provider 拆成多个 Spring bean。
- 不删除 `TaskCommandProvider#cancelTask` legacy direct-provider fallback。
- 不改造 LangGraph worker session endpoint。
- 不改变 Provider capability 枚举语义。

## Review Findings

| Finding | Risk | Planned action |
| --- | --- | --- |
| `TaskDispatchFacade` 仍注入 `List<TaskQueryProvider>` | session 构造边界仍表达为宽接口，Stage 6 窄端口只停留在方法调用层 | 构造期改为注入四类窄端口列表 |
| `TaskQueryProviderRegistry` 内部存 `List<TaskQueryProvider>` 并向窄端口 cast | 无法支持未来 Provider 只实现某个独立端口 | registry 改为按 port 类型维护集合 |
| `findCommandProviderForTask` 依赖宽接口 `getTaskById` + cast | lookup 与 command 职责未解耦，后续独立 bean 难接入 | 先 lookup 定位 providerType，再匹配 command port |
| capability fallback 对所有宽 Provider 生效 | fallback 粒度偏粗 | 在具体端口集合内做 declared capability 优先，否则 fallback 到该端口全部 provider |

## Implementation Plan

1. 重构 `TaskQueryProviderRegistry` 构造函数和字段：
   - `List<? extends TaskLookupProvider>`
   - `List<? extends TaskCommandProvider>`
   - `List<? extends TaskListingProvider>`
   - `List<? extends WorkerSessionQueryProvider>`
2. 将 capability filtering 抽成按端口列表工作的泛型 helper。
3. 将 `findByType` 拆为 `findLookupProviderByType`、`findCommandProviderByType` 等窄端口查询。
4. 调整 `findCommandProviderForTask`：lookup 端口识别任务归属，command 端口按 providerType 路由。
5. 更新 `TaskDispatchFacade` 构造函数和单测构造 helper。
6. 补充 registry 单测，覆盖独立 lookup/command bean 接入形态。

## Acceptance Criteria

- `TaskDispatchFacade` 生产构造边界不再依赖 `List<TaskQueryProvider>` 作为唯一 Provider 集合。
- `TaskQueryProviderRegistry` 内部按窄端口维护集合，不再通过宽接口统一 cast。
- 现有 `TaskQueryProvider` 实现仍可作为四类窄端口被 Spring 注入，行为保持兼容。
- `findCommandProviderForTask` 支持 lookup bean 和 command bean 分离但 providerType 相同的形态。
- 现有 list/search/worker-session/create/resume/cancel 关键回归通过。

## Verification Plan

```powershell
mvn test -pl session-module -am "-Dtest=TaskQueryProviderRegistryTest,TaskDispatchFacadeTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"
mvn test -pl navigator-spi,session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am
```

## Progress

- 2026-06-25: 子计划创建，范围限定为 Provider port 注入与 registry 集合收窄。
- 2026-06-25: `TaskDispatchFacade` 构造边界已改为接收 lookup / command / listing / worker-session 四类窄端口列表。
- 2026-06-25: `TaskQueryProviderRegistry` 内部已按四类窄端口分别维护集合，capability filtering 改为在具体端口集合内执行。
- 2026-06-25: `findCommandProviderForTask` 已支持 lookup provider 与 command provider 分离但 providerType 相同的接入形态，并补充 registry 单测。
- 2026-06-25: `AbortCoordinatingA2aAgent` 已切换为依赖 `TaskLookupProvider`，并保留 deprecated `TaskQueryProvider` 兼容构造器；Claude/Codex/Gemini worker adapter 已迁移到 lookup-port 构造。
- 2026-06-25: 定向回归、受影响 Java reactor 回归、质量门、覆盖审计和功能级验收签收已完成。

## Execution Check-in

Review 发现：

- `TaskDispatchFacade` 构造期仍以 `List<TaskQueryProvider>` 接收所有 Provider，Stage 6 的窄端口只在方法调用层生效。
- `TaskQueryProviderRegistry` 内部仍以宽聚合接口维护唯一集合，再按场景转换为窄端口。
- `findCommandProviderForTask` 依赖同一个宽接口同时完成 task lookup 与 command routing，未来独立 lookup / command bean 难接入。

已完成：

- `TaskDispatchFacade` 构造函数改为接收 `TaskLookupProvider`、`TaskCommandProvider`、`TaskListingProvider`、`WorkerSessionQueryProvider` 四类列表。
- `TaskQueryProviderRegistry` 内部字段改为四类窄端口集合，并提供 lookup、command、listing、worker-session 对应查找入口。
- capability filtering 改为在具体端口集合内执行，仍保持 empty capability 时 fallback 到该端口全部 provider。
- `findCommandProviderForTask` 改为先通过 lookup 端口识别任务归属，再按 providerType 匹配 command 端口。
- `TaskQueryProviderRegistryTest` 新增 lookup provider 与 command provider 分离但 providerType 相同的回归。
- `AbortCoordinatingA2aAgent` 主构造依赖改为 `TaskLookupProvider`；保留 deprecated `TaskQueryProvider` 兼容构造器，以降低构造签名变化风险。
- Claude/Codex/Gemini worker adapter 创建 abort wrapper 时改用 lookup-port 构造。

测试证据：

- `mvn test -pl session-module -am "-Dtest=TaskQueryProviderRegistryTest,TaskDispatchFacadeTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：63 tests pass。
- 初次 affected reactor 暴露 Claude adapter 测试仍链接旧构造签名；已通过兼容构造器和 adapter 迁移修复。
- `mvn test -pl addons/claude-worker-agent -am "-Dtest=ClaudeWorkerAgentProviderTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：10 tests pass。
- `mvn test -pl navigator-spi,session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am`：affected reactor 通过，Surefire XML 合计 221 reports / 1525 tests，0 failures，0 errors，0 skipped。
- `git diff --check`：无 whitespace error，仅 CRLF normalization warnings。

质量与覆盖：

- 实现质量门见 `quality/OPT-001-stage8-implementation-quality.md`，decision=`ready-with-risks`。
- 测试覆盖审计见 `coverage/OPT-001-stage8-coverage-audit.md`，conclusion=`ready-with-gaps`，can_enter_acceptance=`yes`。

签收结论：

- 验收记录见 `acceptance/OPT-001-stage8-provider-port-injection-acceptance.md`。
- acceptance_decision=`accepted-with-risks`，无阻断项。

剩余风险：

- `TaskQueryProvider` 聚合接口仍保留，现有 Provider 仍一次性实现四类端口；独立 bean 拆分留给后续阶段。
- 当前缺少专门的 Spring ApplicationContext 启动测试验证四类泛型列表注入；受影响 reactor 已覆盖编译和模块回归。
- `TaskListingProvider` strictly typed method 迁移仍未处理。
