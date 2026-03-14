# Claude Code Agent Teams — 内部机制详解

> 本文档是 [claude-agent-teams-guide.md](claude-agent-teams-guide.md)（使用指南）的深度补充，
> 聚焦 Agent Teams 在 Claude Code CLI 层面的**运行原理**，以及 Foggy Navigator 的支持方式。

---

## 1. Feature Flag 启用机制

Agent Teams 功能由环境变量 `CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS=1` 控制。
该变量有**两个来源**，任一生效即可：

### 来源 A — Foggy Navigator Worker 自动注入

当 Java 后端通过 API 传递了 agents 配置时，Python Worker 的 `_apply_agents_config()` 会**自动设置**该变量：

```python
# sdk_wrapper.py L634-638
env["CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS"] = "1"
```

**触发条件**：`extra_args` 中包含 `"agents"` 键且值非空。
**不传 agents = 不设置**，Worker 端不会主动开启。

### 来源 B — Claude Code 自身的 settings.json

CLI 进程启动后会读取 `~/.claude/settings.json`，如果其中有：

```json
{
  "env": {
    "CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS": "1"
  }
}
```

CLI 会自行应用该变量。这与 Worker 传入的 env 无关——是 CLI 进程自己读取的。

Worker 代码中显式指定了加载用户级配置：

```python
# sdk_wrapper.py L814
options_kwargs["setting_sources"] = ["user", "project", "local"]
```

### 两种来源的关系

| 场景 | Feature Flag 生效？ | 来源 |
|------|---------------------|------|
| API 传了 agents | ✅ | Worker 自动注入（来源 A） |
| API 没传 agents，但 `settings.json` 有配置 | ✅ | CLI 自行读取（来源 B） |
| 两者都没有 | ❌ | Task 工具不可用 |

**结论**：即使 Foggy Navigator 没传 agents，只要目标机器的 `settings.json` 配了 flag，CLI 也会启用 Agent Teams，模型可以使用内置 agent 类型（`general-purpose`、`Explore`、`Plan`）。

---

## 2. Agent 定义的本质 — 模板，不是实例

传入的 agents JSON 是**模板定义**，不会导致任何进程预创建。

```
传入配置:
{
  "tester": {
    "description": "Writes unit and integration tests",
    "prompt": "You are a test engineer..."
  }
}

CLI 启动后的状态:
┌─────────────────────────────────────┐
│  主 Claude Code CLI 进程 (PID 1234) │
│                                     │
│  Task 工具可用的 subagent_type:      │
│    - general-purpose (内置)          │
│    - Explore         (内置)          │
│    - Plan            (内置)          │
│    - tester          (你定义的) ← 新增│
│                                     │
│  此时没有任何子进程                    │
└─────────────────────────────────────┘
```

只有当模型**主动调用 Task 工具**时，CLI 才会用该模板 spawn 子进程：

```
模型推理: "代码写完了，需要测试"
    ↓
调用: Task(subagent_type="tester", prompt="为刚才的功能写测试")
    ↓
CLI 行为:
  1. 取出 tester 的 description + prompt 作为子 agent 的系统提示词
  2. Spawn 一个新的 Claude Code 子进程
  3. 子进程独立执行（有自己的上下文窗口）
  4. 执行完成后，结果返回主进程
  5. 子进程退出
```

---

## 3. 模型决策驱动 — 不是确定性逻辑

**最重要的认知**：Agent Teams 的使用是 **LLM 推理决策**，不是 `if agents != null then use(agents)` 的确定性程序逻辑。

### 模型看到的是什么？

CLI 将 agent 定义注入到 Task 工具的描述中。模型在推理时看到类似这样的工具描述：

```
Task 工具 — 启动子 agent 处理子任务

可用的 subagent_type:
- general-purpose: 通用 agent，拥有所有工具
- Explore: 快速代码搜索（只读）
- Plan: 架构规划（只读）
- tester: Writes unit and integration tests    ← 你的 description
- reviewer: Reviews code for bugs and security ← 你的 description
```

模型基于 **当前任务** 和 **agent 的 description** 自主判断是否调用。

### 使用概率分析

| 场景 | 使用概率 | 原因 |
|------|---------|------|
| 复杂任务 + description 与任务高度相关 | **高** | 模型判断分派有明确价值 |
| 简单任务 + description 相关 | **中低** | 模型可能判断自己处理更高效 |
| 任务与 description 不太相关 | **低** | 模型不认为该 agent 能帮上忙 |
| 主 prompt 中**显式要求**使用某个 agent | **几乎 100%** | 明确指令 > 模型自主判断 |

### 如何提高使用率

**方法 1 — 优化 description（影响模型判断）**

```json
// ❌ 模糊，模型不确定何时该用
{ "reviewer": { "description": "Code reviewer" } }

// ✅ 精准，模型能明确判断触发时机
{ "reviewer": { "description": "Reviews ALL code changes for bugs, security issues, and style violations. Should be used after any implementation work." } }
```

**方法 2 — 在任务 prompt 中显式编排（最有效）**

```
实现用户注册功能。

执行步骤：
1. 先用 Plan agent 分析现有代码结构
2. 实现后端 API 和前端界面
3. 完成后使用 tester agent 编写测试
4. 最后使用 reviewer agent 审查所有改动
```

**方法 3 — 在项目 CLAUDE.md 中写入全局指令**

```markdown
## Agent Teams 使用规范
- 所有涉及代码修改的任务，完成后必须交给 reviewer agent 审查
- 前后端同时修改的任务，必须分派给对应的专属 agent
```

---

## 4. 会话隔离 — 并发安全

每次 `query` 调用 spawn 的是**完全独立的 CLI 进程**，agents 配置是进程内存级别的注入，不同会话之间完全隔离：

```
会话 A (task_id=001, 配了 reviewer + tester)
  → CLI 子进程 A (PID 12345)
     Task 工具里有: reviewer, tester

会话 B (task_id=002, 配了 backend + frontend)
  → CLI 子进程 B (PID 12346)
     Task 工具里有: backend, frontend

会话 C (task_id=003, 没配 agents)
  → CLI 子进程 C (PID 12347)
     Task 工具里只有: 内置的 Explore, Plan, general-purpose
```

各进程拥有：
- 独立的进程空间（不同 PID）
- 独立的 agents 配置
- 独立的环境变量
- 独立的上下文窗口

**不存在跨会话污染。**

---

## 5. Foggy Navigator 完整数据流

### 配置存储

```
用户在前端填写 Agent Teams JSON
    ↓
存储到 AgentTeamsConfigEntity (数据库)
    ↓
绑定到 WorkingDirectory 级别
    ↓
同一目录下的所有任务共享该配置
```

### 任务执行时的传递链路

```
前端 (ClaudeWorkerView)
  │  selectedDirectory.agentTeamsConfig
  ▼
Java 后端 (ClaudeTaskService → ClaudeWorkerClient)
  │  agentTeamsJson → extra_args["agents"]
  │  HTTP POST /api/v1/query
  ▼
Python Worker (SdkWrapper.run_query)
  │  _apply_agents_config():
  │    1. 从 extra_args 提取 agents JSON 字符串
  │    2. 解析为 dict
  │    3. 转换为 AgentDefinition 对象
  │    4. 设置 options_kwargs["agents"] = {name: AgentDefinition(...)}
  │    5. 设置 env["CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS"] = "1"
  ▼
Claude Agent SDK
  │  claude_agent_sdk.query(options=ClaudeAgentOptions(
  │    agents={"tester": AgentDefinition(...)},
  │    env={"CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS": "1", ...}
  │  ))
  ▼
Claude Code CLI 主进程
  │  agents 注入到 Task 工具描述中
  │  模型推理 → 决定是否调用 Task 工具
  ▼
子 Agent 进程 (按需 spawn)
  │  用 description + prompt 作为系统提示词
  │  独立执行 → 结果返回主进程
```

### AgentDefinition 支持的字段

| 字段 | 必填 | 说明 |
|------|------|------|
| `description` | 是 | 职责描述，主 Agent 据此决定是否分派 |
| `prompt` | 是 | 子 Agent 的系统提示词 |
| `tools` | 否 | 限制子 Agent 可用的工具列表 |
| `model` | 否 | 为子 Agent 指定不同的模型（如用 haiku 降低成本） |

---

## 6. 内置 Agent 类型 vs 自定义 Agent

即使不传任何自定义 agents，只要 Feature Flag 启用，模型始终可以使用以下**内置类型**：

| 类型 | 能力 | 典型用途 |
|------|------|---------|
| `general-purpose` | 全部工具（读写文件、Bash、搜索等） | 复杂子任务的独立执行 |
| `Explore` | 只读（Glob、Grep、Read） | 快速搜索代码、理解结构 |
| `Plan` | 只读 | 设计实现方案、架构分析 |

自定义 agents 与内置类型**并列存在**，不会互相替代：

```
传了自定义 agents 后的完整列表:
  - general-purpose  (内置，始终存在)
  - Explore          (内置，始终存在)
  - Plan             (内置，始终存在)
  - reviewer         (自定义)
  - tester           (自定义)
  - backend          (自定义)
```

模型可以在同一任务中**混合使用**内置和自定义 agents。

---

## 7. 关键源码位置

| 组件 | 文件 | 关键方法/行号 |
|------|------|-------------|
| 环境变量构建 | `sdk_wrapper.py` | `_build_env()` L556-597 |
| Agents 配置解析 | `sdk_wrapper.py` | `_apply_agents_config()` L602-649 |
| SDK 调用入口 | `sdk_wrapper.py` | `run_query()` L784-827 |
| Java 端传参 | `ClaudeWorkerClient.java` | `streamQuery()` L120-124 |
| 配置存储服务 | `AgentTeamsConfigService.java` | CRUD 操作 |
| 配置实体 | `AgentTeamsConfigEntity.java` | 数据库模型 |

---

## 8. 常见问题

### Q: 传了 agents 配置，模型一定会用吗？

**不一定。** 使用与否是模型的推理决策。但可以通过以下方式大幅提高使用率：
1. 写精准的 `description`
2. 在任务 prompt 中显式要求使用特定 agent
3. 在 CLAUDE.md 中写入全局使用规范

### Q: settings.json 中配了 flag 但没传 agents，会发生什么？

CLI 启用 Agent Teams 功能，模型可以使用**内置 agent 类型**（general-purpose、Explore、Plan），但看不到任何自定义 agent。模型会根据任务复杂度自主判断是否 spawn 内置子 agent。

### Q: 自定义 agents 会不会影响其他并发会话？

**不会。** 每个会话是独立的 CLI 进程，agents 配置在进程内存中，完全隔离。

### Q: agents 是预创建的还是按需创建的？

**按需创建。** Agent 定义只是模板。只有模型调用 Task 工具时，CLI 才会 spawn 子进程。任务结束后子进程退出。

### Q: 子 Agent 的 token 消耗怎么算？

每个子 Agent 是独立的 Claude Code 进程，有独立的上下文窗口。
总消耗 ≈ 主 Agent + Σ(各子 Agent)，全部汇总到任务的 `costUsd` / `inputTokens` / `outputTokens` 中。
