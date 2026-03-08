# Claude Code Agent Teams — 使用指南

## 概述

Agent Teams 是 Claude Code SDK 提供的特性，允许定义多个子 Agent（团队成员），Claude 在执行任务时会自动将子任务分派给团队成员并行处理。

Foggy Navigator 已完整支持该功能。本文档说明如何在 PC 版页面中配置和使用。

---

## 快速开始

### 1. 进入工作目录编辑

1. 在左侧 Worker 树中展开目标 Worker
2. 选中要配置的工作目录
3. 点击目录工具栏上的 **"编辑"** 按钮

### 2. 填写 Agent Teams JSON

在编辑弹窗的 **"Agent Teams"** 文本框中填入配置。格式为 JSON 对象，key 是 Agent 名称，value 包含 `description` 和 `prompt`：

```json
{
  "reviewer": {
    "description": "Code reviewer for quality assurance",
    "prompt": "You are a code reviewer. Review the code changes and provide feedback on quality, security, and maintainability."
  },
  "tester": {
    "description": "Test writer for unit and integration tests",
    "prompt": "You are a test engineer. Write comprehensive unit tests and integration tests for the given code."
  }
}
```

点击 **"保存"** 完成配置。

### 3. 验证配置

保存后选中该目录，工具栏下方会出现 **"Agent Teams:"** 标签栏，显示所有 Agent 名称（如 `reviewer`、`tester`）。标签出现即表示配置已生效。

### 4. 创建任务

正常在该目录下创建任务即可。系统会自动将 Agent Teams 配置附带到任务中，无需额外操作。

---

## 配置说明

### JSON 格式

```json
{
  "<agent-name>": {
    "description": "该 Agent 的职责描述（用于 Claude 理解分工）",
    "prompt": "该 Agent 的系统提示词（定义其行为和专长）"
  }
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| `agent-name` (key) | 是 | Agent 名称，Claude 用来标识和调用 |
| `description` | 是 | 职责描述，帮助主 Agent 决定何时分派任务 |
| `prompt` | 是 | 系统提示词，定义子 Agent 的行为和专长 |

### 配置层级

- Agent Teams 配置存储在 **工作目录** 级别
- 同一目录下的所有任务（创建、恢复）共享同一配置
- 不同目录可以配置不同的团队组合

---

## 运行机制

### 任务执行流程

1. 用户在已配置 Agent Teams 的目录下创建任务
2. 主 Claude Agent 接收任务后分析需求
3. 主 Agent 根据各子 Agent 的 `description` 判断是否需要分派
4. 需要时，主 Agent 自动启动子 Agent 进程并分配子任务
5. 子 Agent 独立执行后将结果返回主 Agent
6. 主 Agent 汇总结果，继续推进

### 数据流

```
前端 (ClaudeWorkerView)
  │  selectedDirectory.agentTeamsConfig → form.agentTeamsJson
  ▼
后端 (ClaudeTaskService)
  │  从 WorkingDirectory 解析 agentTeamsJson
  │  封装到 ClaudeTaskStartEvent
  ▼
WorkerStreamRelay → ClaudeWorkerClient
  │  HTTP POST body: { extra_args: { agents: "<json>" } }
  ▼
Python Worker (SdkWrapper.run_query)
  │  ClaudeCodeOptions(extra_args={"agents": "<json>"})
  ▼
Claude Code SDK
  │  启动子 Agent 进程
  ▼
多个 Claude Code 子进程并行执行
```

---

## 实用配置模板

### 开发 + 审查

```json
{
  "developer": {
    "description": "Implements features and fixes bugs",
    "prompt": "You are a senior developer. Implement the assigned feature with clean, maintainable code. Follow existing project conventions."
  },
  "reviewer": {
    "description": "Reviews code quality and security",
    "prompt": "You are a code reviewer. Review all changes for bugs, security issues, and code quality. Provide actionable feedback."
  }
}
```

### 全栈团队

```json
{
  "backend": {
    "description": "Backend API development",
    "prompt": "You focus on backend code: APIs, database queries, business logic, and server-side validation."
  },
  "frontend": {
    "description": "Frontend UI development",
    "prompt": "You focus on frontend code: Vue components, styling, user interactions, and client-side state management."
  },
  "tester": {
    "description": "Test coverage",
    "prompt": "You write tests for both frontend and backend changes. Ensure edge cases are covered."
  }
}
```

### TDD 工作流

```json
{
  "tdd-guide": {
    "description": "Writes tests first following TDD methodology",
    "prompt": "You are a TDD specialist. Write failing tests FIRST that describe the expected behavior, then let the developer implement the code to pass them."
  },
  "implementer": {
    "description": "Implements code to pass tests",
    "prompt": "You implement the minimum code needed to pass the tests written by the TDD guide. Follow YAGNI and KISS principles."
  }
}
```

### 文档 + 开发

```json
{
  "developer": {
    "description": "Core implementation",
    "prompt": "You implement the requested feature or fix. Write clean, well-structured code."
  },
  "docs": {
    "description": "Documentation writer",
    "prompt": "You write and update documentation: README, API docs, code comments for complex logic. Keep docs in sync with code changes."
  }
}
```

---

## 注意事项

### Token 消耗

- 每个子 Agent 是独立的 Claude Code 进程，拥有独立的上下文窗口
- 使用 Agent Teams 会 **显著增加** token 消耗（大致为 `主 Agent + N × 子 Agent`）
- 所有消耗会汇总反映在任务的 `costUsd`、`inputTokens`、`outputTokens` 统计中
- 建议仅在确实需要并行分工的复杂任务上使用

### 配置要求

- 必须是有效的 JSON 对象，格式错误时 Agent Teams 标签栏不会显示（静默忽略）
- Agent 名称（key）建议使用英文小写 + 连字符，如 `code-reviewer`
- `description` 要准确描述职责，主 Agent 依赖它来决定分派策略
- `prompt` 是子 Agent 的系统提示词，直接影响其输出质量

### 适用场景

| 场景 | 是否推荐 | 原因 |
|------|----------|------|
| 复杂功能开发 + 需要代码审查 | 推荐 | 开发与审查并行，提升质量 |
| 全栈任务（前后端同时改） | 推荐 | 分工明确，减少上下文切换 |
| 简单 bug 修复 | 不推荐 | 单 Agent 足够，额外开销不值得 |
| 纯文档编写 | 不推荐 | 单一任务类型，无需分工 |
| 需要严格 TDD 流程 | 可选 | 如果需要强制 test-first 可使用 |

### 与 Auth 配置的关系

- Agent Teams 配置与 Auth 配置互相独立
- 子 Agent 使用与主 Agent 相同的 Auth 凭据（来自会话绑定的 auth 配置或 Worker 默认配置）
- 无需为子 Agent 单独配置认证
