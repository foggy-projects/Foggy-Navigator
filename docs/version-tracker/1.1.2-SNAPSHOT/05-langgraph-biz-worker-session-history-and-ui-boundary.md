# LangGraph Biz Worker 会话历史与 UI 边界收口

- doc_type: implementation-checkin
- version: 1.1.2-SNAPSHOT
- status: static-verified
- date: 2026-05-01
- intended_for: platform-owner | worker-owner | frontend-owner | reviewer
- purpose: 记录 LangGraph Biz Worker 会话历史链路、Worker Backend 分类、Claude Code 与 LangGraph UI 边界，以及 Skill / 标准工具 / 业务工具 / FSScript 编排层契约。

## 范围

本条目只收口 LangGraph Biz Worker 与 Navigator 当前实现的契约边界，不实现完整业务工具系统，不扩大到 `1.3.0-SNAPSHOT` 的 Gemini Worker 版本线。

## 会话历史链路

前端统一使用 Worker Session 语义展示 provider-native 会话标识。LangGraph Biz Worker task 的详情标识显示为 `Worker Session ID`，取值沿用当前任务上的 worker session 引用；`biz-default` 即使同时携带 `claudeSessionId`，也必须按 LangGraph Biz Worker 处理。

Java 统一端点位于 `/api/v1/tasks`：

1. `GET /workers/{workerId}/sessions`
2. `GET /workers/{workerId}/sessions/{sessionId}/message-count`
3. `GET /workers/{workerId}/sessions/{sessionId}/messages`
4. `POST /workers/{workerId}/sessions/sync`

`TaskDispatchFacade` 通过 `TaskQueryProvider` 分派 Worker Session 查询。LangGraph provider 为 `langgraph-biz-worker`，`LanggraphTaskService` 从统一 `SessionTaskRepository` / `SessionMessageRepository` 查询会话和消息；`syncWorkerSessions` 返回 `source = session-store`。这条链路不读取 Claude Code JSONL。

## Worker Backend 分类

前端分类以 `providerType` 为最强信号：

1. `langgraph-biz-worker` -> `LANGGRAPH_BIZ`
2. `claude-worker` -> `CLAUDE_CODE`
3. `codex-worker` -> `OPENAI_CODEX`
4. `gemini-worker` -> `GEMINI_CLI`

当 `providerType` 缺失时，才按模型名和 provider-native id 兜底推断。模型名包含 `biz` 或 `langgraph` 时优先识别为 `LANGGRAPH_BIZ`；因此 `model = biz-default` 且存在 `claudeSessionId` 的任务仍是 LangGraph Biz Worker，不是 Claude Code。

## UI 行为边界

LangGraph Biz Worker：

1. task detail / session 区域展示 `Worker Session ID`。
2. 不显示 Claude Code 的 `回退`。
3. failed task 不显示 Claude Code 的 `重新同步`。
4. 不触发 Claude JSONL delta fallback、checkpoint scan、context repair 或 JSONL resync。

Claude Code：

1. 保留 `Claude Session ID`、checkpoint、`回退`、`重新同步`、JSONL delta fallback 等既有行为。
2. 这些入口必须继续由 `isClaudeCodeTask(...)` 或等价分类结果保护。

## Skill / 工具 / FSScript 边界

现有聊天 UI 已有低风险可复用能力：

1. `approval_required` 与兼容事件 `skill_approval_request` 被解析为待审批消息。
2. `SkillApprovalCard` 展示审批状态、业务摘要、`scriptRunId`、`suspendId`、`timeoutAt`，并发出 approve / reject 响应。
3. `ToolCallBlock` / `ToolCallGroup` 负责标准 tool call 展示，适合作为 Skill/tool call display 的通用外壳。

契约边界如下：

1. 内置文件 IO、Skill 调用属于 LLM 基础工具，不纳入业务工具设计范围。
2. 自然语言触发 Skill 时，由 LLM 决定调用标准 Skill 工具。
3. 业务函数通过上游业务系统 API 或 FSScript 编排层暴露，Worker 不直接成为业务实现方。
4. FSScript 是业务系统 Agent 的安全数据探查和编排基础；有副作用操作必须经审批或确认机制。
5. suspension / resume card 应以 `script_run_id`、`suspend_id`、审批状态、过期时间和业务摘要为稳定字段；在缺少更明确跨端数据契约前，不在前端硬编码新的临时结构。
6. fsscript execution record 应展示脚本运行 ID、脚本/函数名、状态、审批 ID、脱敏输入输出或 artifact 链接；具体 schema 由 FSScript 编排层和 Java relay 固化后再进入组件实现。

## 静态确认

本轮静态确认点：

1. `TaskController` 已暴露统一 Worker Session 查询端点。
2. `TaskDispatchFacade` 可在 Claude provider 不支持该 worker 时跳过并交给 LangGraph provider。
3. `LanggraphTaskService` 从统一 session store 返回 session、message count、分页 messages，并声明 `source = session-store`。
4. 前端 `inferTaskWorkerBackend(...)` 将 `providerType` 作为最强信号，且 `biz-default` 优先识别为 LangGraph。
5. `useTaskPane` 的 Claude JSONL delta fallback 由 `isClaudeCodeTask(...)` 保护。
6. `ClaudeWorkerView` 的 `回退`、`重新同步`、checkpoint / context repair / JSONL resync 入口由 Claude Code 分类保护。

## 本地联调状态

本机只做只读探测，未启动新端口：

1. `http://127.0.0.1:3061/health` 返回 200，worker 为 `langgraph-biz-wsl`，`active_tasks = 0`。
2. Java API 8112 正在监听，但访问 `/api/v1/tasks/workers/{workerId}/sessions` 返回未登录，缺少可用登录态或凭证，不能完成端到端 history API 联调。

推荐手动验收步骤：

1. 使用已有 Java 端口登录 Navigator，不新开服务端口。
2. 确认 LangGraph worker 继续监听 3061。
3. 创建或选择 `providerType = langgraph-biz-worker`、`model = biz-default` 的任务。
4. 在任务详情确认显示 `Worker Session ID`。
5. 调用统一 Worker Session messages 端点，确认返回内容来自 Java session store。
6. 在失败态 LangGraph task 上确认没有 `回退`、`重新同步`。
7. 对 Claude Code task 回归确认原有 `回退`、`重新同步` 和 JSONL fallback 行为仍可用。

## 测试证据

已执行并通过：

1. `pnpm --filter @foggy/navigator-frontend build:check`
2. `pnpm --filter @foggy/navigator-frontend exec vitest run src/__tests__/workerBackend.test.ts`
3. `pnpm --filter @foggy/navigator-frontend exec vitest run src/components/worker/__tests__/taskPaneResume.test.ts`
4. `pnpm --filter @foggy/navigator-frontend exec vitest run src/views/__tests__/ClaudeWorkerView.integration.test.ts`

最终全量验证结果以本轮交付报告为准。
