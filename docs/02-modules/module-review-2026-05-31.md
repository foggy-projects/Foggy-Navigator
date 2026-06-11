# 模块与文档过时项 Review（2026-05-31）

> 本文用于承接“先看是否过时，再按模块 review”的复盘入口。它不替代模块设计文档，只记录当前代码结构与文档体系之间的漂移点。

## 1. 结论摘要

1. 系统级一层模块/功能架构文档已经存在，主入口是 [系统架构概览](../00-system-overview.md) 与 [功能架构说明](./functional-architecture.md)，本轮不需要另起一套总览文档。
2. `CLAUDE.md`、系统总览与功能架构说明此前仍停留在 2026-03 左右的模块口径，已补齐当前仓库中的 `business-agent-module`、Codex/Gemini/LangGraph Biz Worker、Open SDK、上游 CLI、嵌入式聊天组件与移动端入口。
3. `docs/01-overview/*` 已被标记为历史参考，定位正确；`docs/02-modules` 下仍有一批早期规划文档需要显式标注“待复核/部分过时”，避免后续误用为当前实现。
4. PC 顶部旧独立“会话”入口已下线，`/chat` 只做回到 Workers 的兼容跳转；`/c/:id` 暂保留给跨项目阶段回跳等深链场景，兼容页不再提供新建旧独立会话入口。
5. 后续逐模块 review 应优先看“任务分发/会话/Worker/开放集成”这条主链路，再看平台治理、监控与历史规划类模块。

## 2. 本轮核对依据

- 根模块清单：`pom.xml`
- 前端包清单：`pnpm-workspace.yaml` 与 `packages/*`
- 前端功能入口：`packages/navigator-frontend/src/router/index.ts`
- Agent Provider 实现：`addons/*/src/main/java/**/*Provider.java`
- 工具与 Worker 脚本：`tools/*`
- 文档状态入口：`docs/documentation-status.md`

## 3. 工程模块 Review

| 模块/目录 | 当前判断 | 后续动作 |
|------|------|------|
| `launcher` | 当前是聚合启动层，文档只需在系统总览中说明即可 | 保持轻量，不单独扩写 |
| `navigator-common`、`navigator-spi` | 平台底座，主要提供公共模型和 SPI | 后续 review 时只核对是否有废弃接口 |
| `agent-framework` | 仍是 Agent 调用、工具执行、Skill/上下文编排底座 | `agent-framework-guide.md` 较早，建议后续专项核对 |
| `session-module` | 当前主链路核心，承担会话、消息、任务路由、SSE、分享和 Agent 发现 | 优先 review，重点看 TaskDispatch/A2A/Session 边界 |
| `business-agent-module` | 已进入根 `pom.xml`，但系统文档此前漏写 | 建议补一份模块级说明，覆盖上游接入资源、业务动作和开放集成关系 |
| `user-auth-module` | 与用户、角色、API Key 管理一致 | 低风险，按访问控制文档核对即可 |
| `metadata-config-module`、`metadata-query-module` | 平台设置读写底座，仍在使用 | 后续 review 配置项归属，避免设置页说明散落 |
| `monitoring-module` | 监控事件与统计仍是平台治理能力 | 与通知、SSE、Observer BFF 一起核对 |
| `tutor-agent` | 旧独立会话入口的引导 Agent，已从源码目录、根 `pom.xml` 与 `launcher` 摘除 | 无需继续按当前模块 review |
| `addons/claude-worker-agent` | Worker 工作区、目录、文件、跨项目、Open API 主模块 | 优先 review，模块职责很宽，建议拆清“Worker API / 文件 / Git / 跨项目”边界 |
| `addons/codex-worker-agent` | 当前已作为 Provider/Worker 接入 | 需要核对文档是否只停留在 Claude Worker 口径 |
| `addons/gemini-worker-agent` | 当前已作为 Provider/Worker 接入 | 需要补齐与 Codex/Claude 的差异说明 |
| `addons/langgraph-biz-worker` | 当前已作为 Provider/业务 Worker 接入 | 建议与 `business-agent-module` 合并 review |
| `addons/task-assistant` | 任务生命周期通知与摘要助手 | 现有说明偏配置层，后续可补模块边界 |
| `addons/echo-agent` | 示例/测试型 Agent | 无需重文档化，只需在总览中说明非主线 |
| `addons/code-review-agent` | 目录存在，但未进入根 `pom.xml` 模块清单 | 暂按实验/待确认模块处理，review 前先确认是否要纳入主构建 |
| `navigator-open-sdk` | 当前开放集成基础 SDK | 与上游 CLI、Open API、Worker API 一起 review |
| `tools/navigator-upstream`、`tools/navigator-upstream-cli` | 上游系统接入与命令行工具 | 需要与 `business-agent-module`、Open SDK 统一口径 |
| `tools/navigator-chat-observer-bff` | 聊天观察/嵌入场景 BFF | 需要纳入开放集成与可观测性文档 |
| `packages/navigator-frontend` | 主前端入口，路由与当前一级功能基本一致 | 后续按页面模块 review 文档与路由一致性 |
| `packages/foggy-chat`、`packages/foggy-chat-core` | 聊天体验与复用核心 | 总览此前漏写，后续应补组件/协议说明 |
| `packages/navigator-chat-widget` | 嵌入式聊天组件 | 需要纳入开放集成文档 |
| `packages/foggy-mobile` | 移动端入口 | 需要补移动端与主系统能力映射 |
| `tools/claude-agent-worker`、`tools/codex-agent-worker`、`tools/gemini-agent-worker`、`tools/langgraph-biz-worker` | Worker 运行时工具，与 Java addon 配套 | 后续 review 要同时看端口、启动脚本和 Java Provider 配置 |

## 4. 文档过时项判断

| 文档 | 当前判断 | 建议 |
|------|------|------|
| `docs/00-system-overview.md` | 当前有效，本轮已更新 | 作为总口径继续维护 |
| `docs/02-modules/functional-architecture.md` | 当前有效，本轮已补工程模块映射 | 作为功能域入口继续维护 |
| `docs/documentation-status.md` | 当前有效，本轮补充待复核文档 | 后续每次模块 review 后同步更新 |
| `docs/01-overview/*` | 历史参考，标注正确 | 不作为当前实现依据 |
| `docs/02-modules/session-module.md` | 较新，仍可作为会话模块参考 | 与代码做一次 TaskDispatch/A2A 专项核对 |
| `docs/02-modules/worker-workspace-center.md` | 当前有效，但 Worker 类型已增加 | 后续补 Codex/Gemini/LangGraph Biz 差异 |
| `docs/02-modules/observability-notification-integration.md` | 当前有效，但需纳入 Observer BFF | 与 `tools/navigator-chat-observer-bff` 一起核对 |
| `docs/02-modules/memory-system.md` | 早期规划色彩较重 | 标为待复核，确认当前“用户记忆”真实边界 |
| `docs/02-modules/memory-adapter-layer.md` | 早期规划色彩较重 | 标为待复核，避免直接引用为当前实现 |
| `docs/02-modules/rag-module.md` | 与当前主轴不完全一致 | 标为待复核/历史规划 |
| `docs/02-modules/orchestration-layer.md` | 早期编排抽象可能已被 TaskDispatch/A2A 替代 | 标为待复核 |
| `docs/02-modules/task-orchestration-module.md` | 与当前任务治理/跨项目编排可能重叠 | 标为待复核，后续合并或归档 |
| `docs/02-modules/claude-agent-teams-guide.md`、`docs/02-modules/claude-agent-teams-internals.md` | 仍可能有局部价值，但 Worker 类型已扩展 | 标为待复核，避免只按 Claude Teams 口径理解 |
| `docs/agent-framework-guide.md`、`docs/agent-framework-requirements.md` | 早期底座说明 | 后续按当前 Provider/TaskDispatch/Skill 实现重审 |
| `docs/tutor-agent-design.md` | 早期 Tutor 设计，已随旧模块删除 | 不再作为当前或历史入口维护 |

## 5. 建议的逐模块 Review 顺序

1. `session-module` + `agent-framework`：先确认统一会话、TaskDispatch、A2A Provider、SSE 的真实主链路。
2. `business-agent-module` + `navigator-open-sdk` + `tools/navigator-upstream*`：统一开放集成与上游接入口径。
3. `addons/claude-worker-agent` + Codex/Gemini/LangGraph Biz Worker：统一 Worker 类型、端口、启动脚本、Provider 能力边界。
4. `packages/navigator-frontend` + `foggy-chat-core` + `navigator-chat-widget` + `foggy-mobile`：按主前端、多端和嵌入式入口复核。
5. `metadata-config-module`、`metadata-query-module`、`user-auth-module`、`monitoring-module`：复核平台治理能力与页面入口一致性。
6. `memory`、`rag`、`orchestration`、`claude-agent-teams` 等早期规划文档：决定保留、改写或归档。
