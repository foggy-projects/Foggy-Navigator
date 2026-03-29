---
name: scheduled-task
description: 创建 AI 定时任务（如每日报告、定期分析），一站式完成 Agent 选择/创建、defaultModelConfigId 配置、Sharing Key 配置、Prompt 设计、contextId 多轮会话、cron 脚本生成。触发词：/scheduled-task, /st-cron, 提及"定时任务"、"cron"、"定期执行"、"scheduled"。
---

# 定时 AI 任务开发指导

指导用户通过 Linux cron + Foggy Navigator Sharing Key 机制实现定时 Agent 任务（拉代码、分析进度、发邮件等）。

## 使用场景

当用户需要以下操作时激活：
- 创建每日/每周定时执行的 AI 任务（分析、报告、通知）
- 配置 Agent 默认 LLM 配置（defaultModelConfigId）
- 创建和管理 Sharing Key（外部调用 Agent）
- 设计任务 Prompt（含 skill 引用、contextId 会话续传）
- 生成 Linux cron 脚本调用 Foggy Navigator API

## 架构概览

```
Linux cron
  │  curl -H "X-Sharing-Key: sk-xxx" POST /api/v1/shared/ask
  │       { agentId, question, contextId }
  ▼
SharedAskController（Sharing Key 鉴权）
  │  查找 SharingKeyEntity → 验证 agentId / rateLimit
  │  ensureNavigatorSession() → 建立/复用 Navigator Session
  ▼
A2aAgentRegistry → ClaudeWorkerA2aAgent
  │  resolveEffectiveModelConfigId() — 6 级优先链
  │  contextStore.get(contextId) → claudeSessionId（多轮续传）
  ▼
ClaudeTaskService.createTask()
  │  resolveAuth() → 注入 API Key / BaseURL
  │  发布 ClaudeTaskStartEvent
  ▼
Python Worker → Claude Agent SDK → 执行任务
```

## Agent 标识与多 Agent 协作

**前端 `@` 选择**：用户在输入框 `@agentName` 时，系统自动插入 `@agentName(agentId:xxx)` 格式，可直接复制 agentId 用于 Sharing Key 配置。

**后端支持 agentName 查找**：`POST /api/v1/agents/{identifier}/ask` 和 Sharing Key 中的 agentId 字段，都支持传入 agentName（后端自动匹配）。

**多 Agent 协作**：
1. **串行编排**：为每个 Agent 分别创建 Sharing Key，cron 脚本中按顺序调用
2. **Prompt 内 `@agent`**：在 Prompt 中使用 `@otherAgentName 请帮我...`，当前 Agent 委派子任务

## 完整配置流程

### 第一步：确认或创建 Agent

要让定时任务无需每次手动指定 LLM 配置，**必须为 Agent 配置 defaultModelConfigId**。

在 Foggy Navigator 前端（Agent 管理页）：
1. 进入"Claude Worker"页面 → 选择对应 Agent
2. 点击编辑 → 在"默认 LLM 配置"下拉中选择要使用的模型配置
3. 保存

或通过 API 创建/更新：
```bash
# 更新 Agent 默认 LLM 配置
curl -H "Authorization: Bearer $JWT_TOKEN" \
     -H "Content-Type: application/json" \
     -X PUT http://localhost:8112/api/v1/coding-agents/$AGENT_ID \
     -d '{"defaultModelConfigId": "your-model-config-id"}'
```

**LLM 配置优先级链**（调用时按顺序查找，找到即停止）：
```
1. 请求中显式传入 modelConfigId       （cron 脚本中指定）
2. AgentModelOverride 表             （租户管理员覆盖）
3. CodingAgentEntity.defaultModelConfigId  ← 此步骤配置
4. WorkingDirectory.defaultModelConfigId
5. WorkingDirectory.defaultAuthMode（手动 auth）
6. Worker 全局 .env / claude login
```

### 第二步：创建 Sharing Key

Sharing Key 是外部系统（cron）调用 Agent 的凭证。

在前端 → "Sharing Key" 页面 → 创建新 Key：
- 关联 Agent（绑定 agentId）
- 设置速率限制（如每分钟最多 10 次）
- 复制生成的 `sk-xxx...` 值

### 第三步：设计任务 Prompt

**基础结构**：
```
[任务目标描述]
[skill 引用（可选）]
[contextId 持久化指令（多轮任务可选）]
```

**示例：每日进度分析 + 邮件通知**：
```
分析 /src 目录下最近 24 小时的代码变更，生成简洁的进度报告：
- 新增/修改的主要功能
- 待完成的 TODO 项
- 任何需要关注的风险

/send-email
收件人：team@example.com
主题：每日进度报告 {{ date }}
正文：使用上面分析结果填充

---
CONTEXT_ID_FILE: /tmp/foggy-daily-report.ctx
本次 contextId 请保存到上面文件，下次运行时读取并在请求中传入，以延续会话历史。
```

**Skill 使用规范**：
- 技能名称写在 question 中，格式为 `/skill-name`
- Claude Worker 会自动从项目 `.claude/skills/` 目录读取技能内容
- 常见内置技能：`/send-email`、`/git-pull`、`/write-report`

**contextId 多轮会话**（可选）：
- 相同的 `contextId` → Worker 恢复同一 Claude CLI session，保持上下文连贯
- 任务 Prompt 中可指导 Agent 将 contextId 保存到文件，下次运行时读取
- 不传 contextId → 每次启动全新会话

### 第四步：生成 cron 脚本

**最小 curl 调用示例**：
```bash
#!/bin/bash
# /opt/scripts/daily-report.sh

FOGGY_URL="http://your-server:8112"
SHARING_KEY="sk-your-sharing-key-here"
AGENT_ID="your-agent-id"
CTX_FILE="/tmp/foggy-daily-report.ctx"

# 读取上次保存的 contextId（多轮续传）
CONTEXT_ID=""
if [ -f "$CTX_FILE" ]; then
  CONTEXT_ID=$(cat "$CTX_FILE")
fi

# 构建请求体
PAYLOAD=$(cat <<EOF
{
  "agentId": "$AGENT_ID",
  "question": "分析最近24小时代码变更并通过 /send-email 发送报告到 team@example.com",
  "contextId": "$CONTEXT_ID"
}
EOF
)

# 调用 Foggy Navigator
curl -s -X POST "$FOGGY_URL/api/v1/shared/ask" \
  -H "X-Sharing-Key: $SHARING_KEY" \
  -H "Content-Type: application/json" \
  -d "$PAYLOAD" | jq .
```

**每天早上 8:30 执行**（Linux crontab）：
```cron
30 8 * * * /opt/scripts/daily-report.sh >> /var/log/foggy-daily.log 2>&1
```

**仅工作日执行**：
```cron
30 8 * * 1-5 /opt/scripts/daily-report.sh >> /var/log/foggy-daily.log 2>&1
```

**配置 crontab**：
```bash
crontab -e
# 粘贴上面的 cron 表达式
```

### 第五步：验证

```bash
# 1. 手动触发一次，检查响应
bash /opt/scripts/daily-report.sh

# 2. 查看 Worker 历史会话（确认任务有执行记录）
# 前端 → Claude Worker → 会话列表 → 找到该任务

# 3. 检查任务状态
curl -H "Authorization: Bearer $JWT_TOKEN" \
     http://localhost:8112/api/v1/claude-tasks?page=0&size=5 | jq '.data[0]'

# 4. 查看日志
tail -f /var/log/foggy-daily.log
```

## API 参考

### POST /api/v1/shared/ask

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `agentId` | string | 是 | CodingAgent 的 agentId |
| `question` | string | 是 | 任务提示词（可含 skill 引用） |
| `contextId` | string | 否 | 多轮会话标识，相同值复用 Claude session |
| `sessionId` | string | 否 | 显式指定 Navigator Session（通常留空） |

**请求头**：
```
X-Sharing-Key: sk-your-key
Content-Type: application/json
```

**响应示例**：
```json
{
  "code": 200,
  "data": {
    "taskId": "task-abc123",
    "status": "RUNNING",
    "contextId": "my-daily-report",
    "agentId": "agent-xyz"
  }
}
```

> 注意：任务异步执行，响应为提交成功，非执行完成。如需轮询结果，使用 `GET /api/v1/claude-tasks/{taskId}`。

## 常见场景模板

### 场景 A：每日代码分析报告

```json
{
  "agentId": "your-agent-id",
  "question": "使用 git log --since='24 hours ago' 分析今日提交，整理成日报格式：\n1. 今日完成的功能\n2. 代码质量问题\n3. 明日计划\n\n/write-report output=/tmp/daily-$(date +%Y%m%d).md",
  "contextId": "daily-report-2026"
}
```

### 场景 B：定时拉代码 + 运行测试

```json
{
  "agentId": "your-agent-id",
  "question": "1. git pull origin main\n2. mvn test -pl launcher -am\n3. 如有失败，输出具体失败原因和修复建议\n4. /send-email 将结果发送到 dev@example.com"
}
```

### 场景 C：项目进度追踪（多轮）

```json
{
  "agentId": "your-agent-id",
  "question": "检查 docs/roadmap.md 中的进度，对比上周记录（在会话历史中），标注哪些项目完成了，哪些延期了",
  "contextId": "project-progress-tracker"
}
```

## 故障排查

### Sharing Key 认证失败（401）

检查：
- `X-Sharing-Key` 请求头是否正确（区分大小写）
- Key 是否已过期或被禁用
- 前端 → Sharing Key 管理页面确认状态

### Agent 无法找到（400）

检查：
- `agentId` 是否正确（在 Agent 详情页复制）
- 该 Sharing Key 是否绑定了该 agentId

### 任务执行但 LLM 未配置（500）

Agent 未设置 `defaultModelConfigId`，且调用中也未传 `modelConfigId`。解决方案：
- 在 Agent 编辑页设置"默认 LLM 配置"（推荐）
- 或在每次请求中传入 `modelConfigId`（不推荐，不利于统一管理）

### 多轮会话未续传

检查：
- `contextId` 是否每次传入相同值
- Worker 日志是否有 `Resuming A2A context: contextId=xxx`
- 会话存活 24 小时，超期需重新开始

### Worker 历史会话看不到记录

Worker 会话基于 Claude CLI session 存储在 Worker 本机。检查：
- 前端 → Claude Worker → 会话 tab → 点击"同步"
- 或确认 Worker 本机 `~/.claude/projects/` 下有对应 JSONL 文件

## 决策规则

- 任务需要 LLM → 确认 Agent 已配置 defaultModelConfigId
- 任务需要上下文连贯 → 传入固定 contextId（如项目名+任务名）
- 任务需要每次独立执行 → 不传 contextId
- 任务结果需要归档 → Prompt 中指导 Agent 写文件 `/write-report`
- 任务结果需要通知 → Prompt 中引用 `/send-email` skill
- 需要查看执行情况 → 前端 Worker 会话历史（需先同步）
