---
type: optimization
version: 1.3.1-SNAPSHOT
ticket: OPT-001
stage: 7
severity: medium
status: signed-off
owner: java-platform
created_at: 2026-06-25
---

# OPT-001 Stage 7: Provider Listing Envelope Typed Contract

## Background

Stage 6 已将 `TaskQueryProvider` 拆出 lookup / command / listing / worker-session 窄端口，但 listing/search 分页结果仍通过 `Object` 返回，并由 `UnifiedSessionTaskProjectionService` 通过 `Map` key 或 JavaBean getter 反射读取：

- `listTasksPaged` / `listTasksByDirectoryPaged` 读取 `content`、`totalSessions`。
- `searchSessions` 读取 `results`、`total`。
- Claude SPI 路径返回模块内 `SessionPageDTO` / `SessionSearchResultDTO.Page`。
- Codex / Codex Biz SPI 路径返回 `Map.of(...)`。

这导致统一层对 Provider 响应结构缺少编译期约束，新增 Provider 或字段重命名时容易出现空结果、总数丢失或运行期隐性兼容问题。

## Scope

本阶段只治理 Provider listing/search 聚合链路：

- 在 `navigator-spi` 增加 typed listing/search envelope。
- 将 session 聚合层优先识别 typed envelope，同时保留旧 `Map` / JavaBean getter 兼容路径。
- 将 Claude、Codex、Codex Biz 的 SPI listing/search 返回迁移到 typed envelope。
- 保持 Claude 历史 controller/service DTO 返回不变，避免影响模块内 REST API。
- 不改变统一 REST / OpenAPI / SDK 对外响应字段：`content`、`totalSessions`、`results`、`total`、`page`、`size` 语义保持兼容。

## Non-Goals

- 不一次性删除 `TaskListingProvider` 的 `Object` 返回类型；该接口仍需兼容旧实现。
- 不拆分 Claude / Codex 大型 TaskService。
- 不改造任务 item DTO、搜索 result DTO 或前端响应字段。
- 不把 provider item 投影改为强 DTO；本阶段只收敛分页/search envelope。

## Review Findings

| Finding | Risk | Planned action |
| --- | --- | --- |
| `UnifiedSessionTaskProjectionService#toTaskPageEnvelope` / `toSearchEnvelope` 通过反射读取 Provider 返回 | 字段名漂移时编译期无法发现，容易静默返回空集合或 0 total | 新增 typed envelope，统一层优先按类型解析 |
| Claude SPI 路径返回 `SessionPageDTO` / `SessionSearchResultDTO.Page` | 模块 DTO 与统一 SPI contract 绑定，后续 DTO 重构会影响聚合链路 | SPI override 包装 typed envelope，历史 controller 继续返回原 DTO |
| Codex / Codex Biz 返回 `Map.of(...)` | key typo 无编译期保护，测试以外难发现 | 改为返回 typed envelope |
| 旧兼容路径仍有外部 Provider 潜在依赖 | 直接删除反射/Map 解析会扩大风险 | Stage 7 保留 fallback，并以测试固定兼容行为 |

## Implementation Plan

1. 新增 `TaskPageResult` 与 `TaskSearchResult` typed envelope，提供 `of(...)` / `empty(...)` 工厂方法。
2. 更新 `UnifiedSessionTaskProjectionService`：
   - `toTaskPageEnvelope` 优先识别 `TaskPageResult`。
   - `toSearchEnvelope` 优先识别 `TaskSearchResult`。
   - 保留旧 `Map` / getter 读取 fallback。
3. 迁移 Provider SPI 返回：
   - Claude `listTasksPaged` / `listTasksByDirectoryPaged` / `searchSessions` 的 SPI 返回包装 typed envelope。
   - Codex `buildSessionPage` / `searchSessions` / `searchSessionsForProvider` 返回 typed envelope。
   - Codex Biz 空搜索返回 typed envelope，其余委托 Codex typed 返回。
4. 补充 session 侧单测，覆盖 typed envelope 与 legacy fallback。
5. 执行定向与受影响 reactor 回归，并回写 execution-checkin。

## Acceptance Criteria

- `TaskDispatchFacade` listing/search 聚合可消费 typed provider envelope。
- Claude/Codex/Codex Biz 的 SPI listing/search 返回不再依赖 Map/DTO 反射作为主路径。
- 旧 `Map` / JavaBean getter fallback 仍可解析，避免第三方/后续未迁移 Provider 立即破坏。
- 对外响应字段和分页/search 语义保持不变。
- 定向 session/provider 回归与受影响 Java reactor 回归通过。

## Verification Plan

```powershell
mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent -am "-Dtest=UnifiedSessionTaskProjectionServiceTest,TaskDispatchFacadeTest,ClaudeTaskServiceTest,CodexTaskServiceTest,CodexBizTaskProviderTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"
mvn test -pl navigator-spi,session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am
```

## Progress

- 2026-06-25: 子计划创建，已确认 Stage 7 范围限定为 Provider listing/search envelope typed contract。
- 2026-06-25: 新增 `TaskPageResult` / `TaskSearchResult` typed envelope，并将 `UnifiedSessionTaskProjectionService` 调整为 typed-first 解析。
- 2026-06-25: Claude/Codex/Codex Biz SPI listing/search 返回迁移到 typed envelope；Claude 历史 controller/service DTO 返回保持不变。
- 2026-06-25: 补充 `UnifiedSessionTaskProjectionServiceTest`，覆盖 typed page/search 与 legacy Map/JavaBean fallback；更新 Codex listing 返回类型断言。
- 2026-06-25: 定向回归、受影响 Java reactor 回归、质量门、覆盖审计和功能级验收签收已完成。

## Execution Check-in

Review 发现：

- `UnifiedSessionTaskProjectionService#toTaskPageEnvelope` / `toSearchEnvelope` 原先主要依赖 Map key 或 JavaBean getter 反射读取 Provider 返回。
- Claude SPI 路径返回模块内 DTO，Codex / Codex Biz SPI 路径返回 `Map.of(...)`，统一层缺少编译期结构约束。
- 旧 Provider 仍可能依赖 Map/bean 返回，直接删除 fallback 会扩大兼容风险。

已完成：

- `navigator-spi` 新增 `TaskPageResult` 与 `TaskSearchResult` record，提供 `of(...)` / `empty(...)` 工厂方法。
- `TaskListingProvider` 文档已标记新实现应优先返回 typed envelope，legacy DTO/Map 仅作为兼容路径。
- `UnifiedSessionTaskProjectionService` listing/search envelope 解析改为 typed-first，并保留 Map / JavaBean getter fallback。
- Claude SPI `listTasksPaged`、`listTasksByDirectoryPaged`、`searchSessions` 返回 typed envelope；controller 历史 search DTO 路径保留。
- Codex `buildSessionPage`、`searchSessions`、`searchSessionsForProvider` 返回 typed envelope；Codex Biz 空搜索返回 `TaskSearchResult.empty(...)`。
- 新增 session 侧投影单测，覆盖 typed page、typed search、legacy Map fallback 和 legacy JavaBean fallback。

测试证据：

- `mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent -am "-Dtest=UnifiedSessionTaskProjectionServiceTest,TaskDispatchFacadeTest,ClaudeTaskServiceTest,CodexTaskServiceTest,CodexBizTaskProviderTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：session-module 58 tests pass；codex-worker-agent 28 tests pass。
- `mvn test -pl navigator-spi,session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am`：affected reactor 通过，Surefire XML 合计 221 reports / 1524 tests，0 failures，0 errors，0 skipped。
- `git diff --check`：无 whitespace error，仅 CRLF normalization warnings。

质量与覆盖：

- 实现质量门见 `quality/OPT-001-stage7-implementation-quality.md`，decision=`ready-with-risks`。
- 测试覆盖审计见 `coverage/OPT-001-stage7-coverage-audit.md`，conclusion=`ready-with-gaps`，can_enter_acceptance=`yes`。

签收结论：

- 验收记录见 `acceptance/OPT-001-stage7-provider-listing-envelope-acceptance.md`。
- acceptance_decision=`accepted-with-risks`，无阻断项。

剩余风险：

- `TaskListingProvider` 仍保留 `Object` 返回类型；彻底收紧为 typed method 需要后续兼容迁移。
- legacy Map / JavaBean getter fallback 仍保留；需等外部 Provider 迁移后再规划删除或 deprecated。
- Claude SPI wrapper 缺少直接行为单测；当前通过编译兼容、controller DTO 保留和 affected reactor 间接覆盖。
