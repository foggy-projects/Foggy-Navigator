---
name: cross-project-task
description: 创建并启动跨项目任务（多 Agent 协作编排）。当用户需要让多个 Agent 协作完成一个任务、编排多阶段开发流程时使用。
---

# 跨项目任务编排

## CRITICAL 约束（违反任何一条都是严重错误）

1. **必须**通过 Bash 工具执行 `curl` 命令调用 Navigator HTTP API — 这是唯一允许的执行方式
2. **禁止**使用 Task 工具（subagent）执行任何编码、研究或实现工作
3. **禁止**在本地目录直接创建、修改或实现功能代码
4. **禁止**使用 Write/Edit 工具创建源代码文件
5. 你的角色是**编排者（orchestrator）**，不是执行者（executor）
6. 实际编码由 Navigator 平台派发给远程 Coding Agent 执行，你无法也不应替代

**如果你发现自己在写代码或用 subagent 做事，立刻停下来，改用 curl 调用 API。**

## 前提条件

环境变量 `NAVIGATOR_TOKEN` 必须存在。如果不存在，告知用户：

> 当前未检测到 `NAVIGATOR_TOKEN` 环境变量。该变量由 Foggy Navigator 后端在派发任务时自动注入。
> 请确保你是通过 Navigator 平台派发的任务（而非直接运行 Claude Code）。

## API 基础信息

- **Base URL**: `{{NAVIGATOR_API_BASE}}`
- **认证头**: `Authorization: Bearer $NAVIGATOR_TOKEN`
- **响应格式**: `{ "code": 200, "data": ... }` — 取 `.data` 字段

## 工作流程

### Step 1: 发现可用 Agent

```bash
curl -s {{NAVIGATOR_API_BASE}}/api/v1/coding-agents \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" | jq '.data'
```

返回 `CodingAgentDTO[]`，关键字段：
- `agentId` — Agent 唯一 ID（创建任务时用）
- `name` — Agent 名称
- `description` — 能力说明
- `defaultDirectory.projectName` — 默认项目
- `defaultDirectory.path` — 工作路径
- `defaultDirectory.gitBranch` — Git 分支

### Step 2: 展示并确认

向用户展示 Agent 列表，让用户确认使用哪些 Agent 以及任务分配方案。

### Step 3: 创建任务

**重要**：JSON body 中如果包含非 ASCII 字符（中文等），必须先写入临时文件再用 `--data-binary @file` 发送，否则 Windows 终端编码会损坏内容导致 400 错误。

```bash
# 1) 用 python 写 JSON 到临时文件（保证 UTF-8 编码）
python3 -c "
import json
data = {
    'title': '任务标题',
    'description': '任务描述',
    'phases': [
        {
            'phaseName': '阶段1名称',
            'prompt': '详细的任务说明...',
            'agentId': 'agent-uuid-here'
        }
    ]
}
with open('/tmp/_cross_task.json', 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False)
"

# 2) 用 curl 发送文件
curl -s -X POST {{NAVIGATOR_API_BASE}}/api/v1/cross-project-tasks \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-binary @/tmp/_cross_task.json
```

`phases` 字段说明：
- `phaseName` — 阶段名称（简短）
- `prompt` — 发给 Agent 的详细任务说明（必须独立可执行，包含充分上下文）
- `agentId` — 执行此阶段的 Agent ID
- `directoryId` — 工作目录 ID（可选，默认用 Agent 的 defaultDirectoryId）
- `worktreeBranch` — Git worktree 分支名（可选，建议 `feat/xxx`）

返回值中取 `contextId`。

### Step 4: 启动任务

```bash
curl -s -X POST {{NAVIGATOR_API_BASE}}/api/v1/cross-project-tasks/{contextId}/start \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" | jq '.data'
```

### Step 5: 返回结果

告知用户：
- 任务 contextId
- 各阶段分配的 Agent
- 查看地址：`http://localhost:5174/#/cross-tasks`

## 其他可用 API

```bash
# 查询任务状态
curl -s {{NAVIGATOR_API_BASE}}/api/v1/cross-project-tasks/{contextId} \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" | jq '.data'

# 推进到下一阶段
curl -s -X POST {{NAVIGATOR_API_BASE}}/api/v1/cross-project-tasks/{contextId}/advance \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" | jq '.data'

# 取消任务
curl -s -X POST {{NAVIGATOR_API_BASE}}/api/v1/cross-project-tasks/{contextId}/cancel \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" | jq '.data'
```

## 注意事项

- 每个阶段的 `prompt` 应独立可执行，包含足够上下文
- 如果用户没指定分支，建议使用 `feat/{task-keyword}` 格式
- 只有一个 Agent 时可创建单阶段任务
- 优先使用 Agent 的默认目录
