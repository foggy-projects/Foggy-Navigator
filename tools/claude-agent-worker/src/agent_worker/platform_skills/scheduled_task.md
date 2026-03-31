---
name: scheduled-task
description: 创建 AI 定时任务（如每日报告、定期分析），一站式完成 Agent 选择/创建、defaultModelConfigId 配置、Sharing Key 配置、Prompt 设计、contextId 多轮会话、cron 脚本生成、报告投递。触发词：/scheduled-task, /st-cron, /cron, 提及"定时任务"、"每天执行"、"定期分析"、"自动报告"、"cron"、"scheduled"。
---

# AI 定时任务配置向导

帮助用户一站式完成定时 AI 任务的全部配置，从 Agent 选择到 cron 脚本部署。

## 使用场景

用户说类似以下内容时激活：

> "我想创建一个定时任务，每天 8 点分析昨天的代码提交"
> "帮我做一个每周五自动生成本周工作报告的任务"
> "我想让 AI 每天检查一下线上日志有没有异常"

## CRITICAL 约束

1. **必须**通过 Bash 工具执行 `curl` 命令调用 Navigator HTTP API
2. **逐步引导**：每一步先确认用户意图，再执行操作
3. **Sharing Key 仅展示一次**：创建后的明文 Key 必须立即告知用户保存，之后只显示掩码
4. **脚本可部署**：最终产出的 shell 脚本应可直接 `scp` 到服务器使用

## 前提条件

环境变量 `NAVIGATOR_TOKEN` 和 `NAVIGATOR_API_BASE` 必须存在。如果不存在，告知用户：

> 当前未检测到 `NAVIGATOR_TOKEN` 或 `NAVIGATOR_API_BASE` 环境变量。
> 这些变量由 Foggy Navigator 后端在派发任务时自动注入。
> 请确保你是通过 Navigator 平台派发的任务（而非直接运行 Claude Code）。

检测方式：
```bash
echo "NAVIGATOR_TOKEN=${NAVIGATOR_TOKEN:+OK}" && echo "NAVIGATOR_API_BASE=${NAVIGATOR_API_BASE:-NOT_SET}"
```

## API 基础信息

- **Base URL**: `{{NAVIGATOR_API_BASE}}`
- **认证头**: `Authorization: Bearer $NAVIGATOR_TOKEN`
- **响应格式**: `{ "code": 200, "data": ... }` — 取 `.data` 字段

---

## 配置流程

### Step 0: 理解用户需求

向用户确认以下关键信息：

| 要素 | 问题 | 示例 |
|------|------|------|
| **做什么** | 你想让 AI 分析/检查/生成什么？ | "分析昨天的 git 提交" |
| **多久一次** | 执行频率？ | "每天早上 8 点" |
| **报告给谁** | 结果发到哪里？ | "发邮件给我" / "记录就行" |

### 关于 Agent 标识

**前端 `@` 选择**：用户在输入框中 `@agentName` 选择 Agent 时，系统会自动插入 `@agentName(agentId:xxx)` 格式。如果用户已经通过 `@` 选择了 Agent，可以直接从消息中提取 agentId，**跳过 Step 1 的列表查询**。

**后端支持 agentName 查找**：`POST /api/v1/agents/{identifier}/ask` 的 `identifier` 既可以是 `agentId`，也可以是 `agentName`（后端自动按优先级匹配）。

**多 Agent 协作定时任务**：如果任务需要多个 Agent 协作（如 Agent A 拉代码分析 → Agent B 生成报告），有两种方式：
1. **串行编排**：为每个 Agent 分别创建 Sharing Key，cron 脚本中按顺序调用
2. **Prompt 内 `@agent`**：在单个任务的 Prompt 中使用 `@otherAgentName 请帮我...`，当前 Agent 会委派子任务给目标 Agent

### Step 1: 选择或创建 Agent

**如果用户已通过 `@agentName` 选择了 Agent**：从消息上下文提取 agentId，跳到 Step 1.5。

**否则，列出当前可用 Agent：**

```bash
curl -s {{NAVIGATOR_API_BASE}}/api/v1/agents \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" | jq '.data[] | {id, name, description}'
```

向用户展示列表，让用户选择一个 Agent。

**如果没有合适的 Agent**，引导用户创建：

1. 确认目标项目的 Worker 和工作目录：
```bash
# 列出 Workers
curl -s {{NAVIGATOR_API_BASE}}/api/v1/claude-workers \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" | jq '.data[] | {workerId, name, status}'

# 列出 Worker 的工作目录
curl -s {{NAVIGATOR_API_BASE}}/api/v1/working-directories/worker/{workerId} \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" | jq '.data[] | {directoryId, projectName, path}'
```

2. 创建 Agent（绑定到 Worker + 工作目录）：
```bash
python3 -c "
import json, tempfile, os
data = {
    'name': 'Agent 名称（英文，如 my-project-analyzer）',
    'description': '描述这个 Agent 的能力',
    'workerId': 'Worker ID',
    'defaultDirectoryId': '工作目录 ID'
}
tmp = os.path.join(tempfile.gettempdir(), '_create_agent.json')
with open(tmp, 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False)
print('Written to', tmp)
" && curl -s -X POST {{NAVIGATOR_API_BASE}}/api/v1/coding-agents \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-binary @"$(python3 -c 'import tempfile,os;print(os.path.join(tempfile.gettempdir(),\"_create_agent.json\"))')" | jq '.data'
```

记下返回的 `agentId`。

### Step 1.5: 为 Agent 配置默认 LLM（defaultModelConfigId）

定时任务调用时不会手动指定 LLM 配置，因此 **Agent 必须有 `defaultModelConfigId`**，否则任务无法启动。

**检查 Agent 是否已配置 defaultModelConfigId：**

```bash
curl -s {{NAVIGATOR_API_BASE}}/api/v1/coding-agents/$AGENT_ID \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" | jq '.data | {agentId, name, defaultModelConfigId, defaultModelConfigName}'
```

如果 `defaultModelConfigId` 为 null，需要查询可用 LLM 配置并绑定：

```bash
# 1. 列出所有可用 LLM 配置
curl -s {{NAVIGATOR_API_BASE}}/api/v1/platform-configs/llm-models \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" | jq '.data[] | {id, name, provider}'
```

向用户展示列表，让用户选择合适的模型配置（通常选 claude-opus 或 claude-sonnet 等），然后：

```bash
python3 -c "
import json, tempfile, os
data = {'defaultModelConfigId': '选择的 LLM 配置 ID'}
tmp = os.path.join(tempfile.gettempdir(), '_update_agent.json')
with open(tmp, 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False)
print('Written to', tmp)
" && curl -s -X PUT {{NAVIGATOR_API_BASE}}/api/v1/coding-agents/$AGENT_ID \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-binary @"$(python3 -c 'import tempfile,os;print(os.path.join(tempfile.gettempdir(),\"_update_agent.json\"))')" | jq '.data | {agentId, name, defaultModelConfigId, defaultModelConfigName}'
```

**LLM 配置优先级**（供参考，调用时按顺序查找）：

```
1. 请求中显式传入 modelConfigId       ← cron 脚本中可指定
2. AgentModelOverride（租户管理员覆盖）
3. CodingAgentEntity.defaultModelConfigId  ← 本步骤配置
4. WorkingDirectory 的默认配置
5. Worker 全局 .env 中的 auth 配置
```

确认 Agent 已有 `defaultModelConfigId` 后继续。

### Step 2: 设计 Prompt

根据用户需求，帮用户设计两部分内容：

**systemPrompt（角色约束，写入 Sharing Key 配置）：**
- 定义 AI 的角色和边界
- 指定输出格式（markdown 报告/JSON/纯文本）
- 限制操作范围（只读分析 vs 可修改）

**Prompt 模板（写入 cron 脚本，每次执行时发送）：**
- 包含动态变量（如日期、时间范围）
- 明确分析维度和输出要求

**设计示例 — 每日代码提交分析：**

systemPrompt:
```
你是一个代码审查助手。你的职责是分析 Git 提交记录，生成结构化的日报。
输出格式：Markdown，包含以下章节：
1. 提交概览（总数、作者分布）
2. 关键变更（影响范围大的改动）
3. 潜在风险点（大文件修改、敏感文件变动）
4. 建议（代码质量改进建议）
只分析，不做任何修改操作。
```

Prompt 模板:
```
请分析工作目录中昨天（$(date -d 'yesterday' '+%Y-%m-%d')）的 Git 提交记录，生成日报。
使用 git log --since="yesterday" --until="today" 获取提交列表。
```

**设计示例 — 每周工作报告：**

systemPrompt:
```
你是一个项目经理助手。分析本周的代码变更和任务完成情况，生成周报。
输出格式：Markdown 周报，包含：本周完成、进行中的工作、下周计划建议。
```

Prompt 模板:
```
请分析本周（$(date -d 'last monday' '+%Y-%m-%d') 到 $(date '+%Y-%m-%d')）的所有提交和变更，生成周报。
```

将设计好的内容展示给用户确认后继续。

### Step 3: 创建 Sharing Key

```bash
python3 -c "
import json, tempfile, os
data = {
    'agentId': '用户选择的 Agent ID',
    'label': '定时任务名称（如 daily-commit-report）',
    'systemPrompt': '上一步设计的 systemPrompt',
    'maxTurns': 5,
    'maxDailyCalls': 10
}
tmp = os.path.join(tempfile.gettempdir(), '_create_key.json')
with open(tmp, 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False)
print('Written to', tmp)
" && curl -s -X POST {{NAVIGATOR_API_BASE}}/api/v1/sharing-keys \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-binary @"$(python3 -c 'import tempfile,os;print(os.path.join(tempfile.gettempdir(),\"_create_key.json\"))')" | jq '.data'
```

**关键参数说明：**

| 参数 | 说明 | 建议值 |
|------|------|--------|
| `maxTurns` | AI 最大思考轮数（越多越深入，但越慢） | 分析任务: 5-10，简单查询: 1-3 |
| `maxDailyCalls` | 每日最大调用次数 | 定时任务通常 1-5 |
| `expiresAt` | 过期时间（null=永不过期） | 按需设置 |

**重要**：响应中的 `sharingKey` 字段（`shk-...` 格式）是明文密钥，**仅此一次展示**。提醒用户立即保存！

### Step 4: 选择执行频率

帮用户生成 cron 表达式：

| 需求 | cron 表达式 | 说明 |
|------|-------------|------|
| 每天早上 8 点 | `0 8 * * *` | 周一到周日 |
| 每个工作日早上 9 点 | `0 9 * * 1-5` | 周一到周五 |
| 每周五下午 5 点 | `0 17 * * 5` | 仅周五 |
| 每小时 | `0 * * * *` | 整点执行 |
| 每 6 小时 | `0 */6 * * *` | 0,6,12,18 点 |

### Step 5: 选择报告投递方式

**方式 A: 仅记录（默认）**
- 执行结果自动出现在 Navigator Worker 页面的历史会话中
- 用户登录 Navigator 即可查看
- 无需额外配置

**方式 B: 邮件投递**
- 需要 SMTP 配置（服务器、端口、账号密码）
- 脚本中追加邮件发送逻辑
- 使用 `/send-email` 技能辅助配置

**方式 C: Webhook 投递**
- 推送到企业微信/飞书/钉钉群机器人
- 只需一个 Webhook URL

### Step 6: 生成部署脚本

根据以上所有配置，生成可直接部署的 shell 脚本。

**脚本模板（方式 A: 仅记录）：**

```bash
#!/bin/bash
# ============================================
# AI 定时任务: {任务名称}
# Agent: {Agent 名称}
# 频率: {cron 描述}
# 生成时间: $(date '+%Y-%m-%d %H:%M')
# ============================================

NAVIGATOR_URL="{{NAVIGATOR_API_BASE}}"
SHARING_KEY="{用户的 Sharing Key}"
CONTEXT_ID="{agent名称}-{任务名称}-$(date '+%Y%m%d')"  # 按天分组会话（含 agent 标识避免冲突）
LOG_DIR="$HOME/.foggy-tasks"
LOG_FILE="$LOG_DIR/{任务名称}-$(date '+%Y%m%d_%H%M%S').log"

mkdir -p "$LOG_DIR"

echo "[$(date '+%Y-%m-%d %H:%M:%S')] 开始执行定时任务..." | tee -a "$LOG_FILE"

# 构建请求体
REQUEST_BODY=$(python3 -c "
import json
data = {
    'question': '{Prompt 模板，包含动态日期变量}',
    'contextId': '$CONTEXT_ID'
}
print(json.dumps(data, ensure_ascii=False))
")

# 调用 AI Agent
RESPONSE=$(curl -s -X POST "$NAVIGATOR_URL/api/v1/shared/ask" \
  -H "X-Sharing-Key: $SHARING_KEY" \
  -H "Content-Type: application/json; charset=UTF-8" \
  -d "$REQUEST_BODY" \
  --max-time 300)

# 解析结果
STATUS=$(echo "$RESPONSE" | python3 -c "
import sys, json
try:
    r = json.load(sys.stdin)
    task = r.get('data', {})
    state = task.get('status', {}).get('state', 'UNKNOWN')
    print(state)
except: print('ERROR')
")

RESULT=$(echo "$RESPONSE" | python3 -c "
import sys, json
try:
    r = json.load(sys.stdin)
    artifacts = r.get('data', {}).get('artifacts', [])
    if artifacts:
        parts = artifacts[0].get('parts', [])
        if parts:
            print(parts[0].get('text', '无内容'))
    else:
        desc = r.get('data', {}).get('status', {}).get('description', '')
        print(desc if desc else '无返回内容')
except Exception as e: print(f'解析失败: {e}')
")

echo "[$(date '+%Y-%m-%d %H:%M:%S')] 状态: $STATUS" | tee -a "$LOG_FILE"
echo "$RESULT" >> "$LOG_FILE"

if [ "$STATUS" = "COMPLETED" ]; then
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] 任务完成" | tee -a "$LOG_FILE"
else
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] 任务异常: $STATUS" | tee -a "$LOG_FILE"
fi
```

**脚本模板（方式 C: Webhook 投递 — 企业微信示例）：**

在方式 A 脚本的末尾追加：

```bash
# Webhook 投递（企业微信）
WEBHOOK_URL="{用户的 Webhook URL}"
if [ "$STATUS" = "COMPLETED" ]; then
    TITLE="AI 日报 - $(date '+%m/%d')"
    # 截断过长内容
    SHORT_RESULT=$(echo "$RESULT" | head -c 4000)
    WEBHOOK_BODY=$(python3 -c "
import json
data = {
    'msgtype': 'markdown',
    'markdown': {
        'content': '## $TITLE\n\n' + '''$SHORT_RESULT'''
    }
}
print(json.dumps(data, ensure_ascii=False))
")
    curl -s -X POST "$WEBHOOK_URL" \
      -H "Content-Type: application/json" \
      -d "$WEBHOOK_BODY"
fi
```

### Step 7: 部署指导

将脚本交给用户后，指导部署：

```bash
# 1. 保存脚本
chmod +x ~/foggy-daily-report.sh

# 2. 测试执行
bash ~/foggy-daily-report.sh

# 3. 添加到 crontab
crontab -e
# 添加一行：
# 0 8 * * * /bin/bash ~/foggy-daily-report.sh >> ~/foggy-daily-report-cron.log 2>&1

# 4. 验证 crontab
crontab -l
```

**Windows 用户使用 Task Scheduler：**

```powershell
# 创建计划任务（每天 8:00 执行）
schtasks /create /tn "Foggy-DailyReport" /tr "bash C:\Users\%USERNAME%\foggy-daily-report.sh" /sc daily /st 08:00
```

---

## contextId 策略

调用者可以在**首次调用时就自己生成 contextId**（如 UUID 或有意义的字符串），无需先调一次拿返回值再保存。后端行为：

- **contextId 无记录** → 自动新建会话
- **contextId 有记录且绑定同一 Agent** → 继续已有会话（多轮对话）
- **contextId 有记录但绑定不同 Agent** → 返回 FAILED 错误（`contextId is bound to agent X, cannot use with agent Y`），需换一个 contextId 或调用正确的 Agent

**重要**：contextId 与 Agent 是绑定关系，不同 Agent 不能复用同一个 contextId。建议在 contextId 中包含 Agent 标识以避免冲突。

| 策略 | contextId 格式 | 效果 |
|------|---------------|------|
| **按天分组** | `{agent-name}-daily-20260312` | 同一天的多次调用在同一会话中（适合日报） |
| **按周分组** | `{agent-name}-weekly-2026W11` | 一周的调用在同一会话中（适合周报） |
| **永续单会话** | `{agent-name}-permanent` | 所有调用共用一个会话（AI 可回顾全部历史） |
| **每次独立** | 不传 contextId | 每次调用都是全新会话 |

---

## 管理已有的定时任务配置

### 查看 Sharing Key 列表

```bash
curl -s {{NAVIGATOR_API_BASE}}/api/v1/sharing-keys \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" | jq '.data[] | {id, label, agentName, maskedKey, enabled, todayCalls, maxDailyCalls}'
```

### 修改配置（如调整调用限额）

```bash
python3 -c "
import json, tempfile, os
data = {
    'maxDailyCalls': 20,
    'maxTurns': 8
}
tmp = os.path.join(tempfile.gettempdir(), '_update_key.json')
with open(tmp, 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False)
" && curl -s -X PUT {{NAVIGATOR_API_BASE}}/api/v1/sharing-keys/{keyId} \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-binary @"$(python3 -c 'import tempfile,os;print(os.path.join(tempfile.gettempdir(),\"_update_key.json\"))')" | jq '.data'
```

### 禁用/启用 Key

```bash
python3 -c "
import json, tempfile, os
data = {'enabled': False}
tmp = os.path.join(tempfile.gettempdir(), '_update_key.json')
with open(tmp, 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False)
" && curl -s -X PUT {{NAVIGATOR_API_BASE}}/api/v1/sharing-keys/{keyId} \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-binary @"$(python3 -c 'import tempfile,os;print(os.path.join(tempfile.gettempdir(),\"_update_key.json\"))')" | jq '.data'
```

### 删除 Key

```bash
curl -s -X DELETE {{NAVIGATOR_API_BASE}}/api/v1/sharing-keys/{keyId} \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" | jq '.'
```

---

## 查看执行历史

定时任务的每次执行都会自动记录在 Navigator 的 Worker 页面中：

1. 登录 Navigator（默认 `http://localhost:5174`）
2. 进入 **Workers 页面**（`/#/claude-tasks`）
3. 在左侧会话列表中找到 `Shared: {任务标签}` 开头的会话
4. 点击查看 AI 的完整问答历史（包含 Prompt 和分析结果）

---

## 故障排查

| 问题 | 检查方式 | 解决方案 |
|------|----------|----------|
| 返回 "Invalid sharing key" | Key 是否正确？是否已删除？ | 重新查看 Sharing Key 列表 |
| 返回 "Sharing key is disabled" | Key 是否被禁用？ | 启用 Key |
| 返回 "Daily call limit exceeded" | 今日调用次数是否超限？ | 调大 `maxDailyCalls` |
| 返回 "Sharing key has expired" | Key 是否已过期？ | 创建新 Key 或更新过期时间 |
| 返回 "Shared agent not available" | Agent 是否存在？Worker 是否在线？ | 检查 Agent 列表和 Worker 状态 |
| 任务启动失败（LLM 未配置） | Agent.defaultModelConfigId 是否为 null？ | 执行 Step 1.5 为 Agent 绑定默认 LLM 配置 |
| 多轮会话未续传 | contextId 是否每次传入相同值？ | 确保 cron 脚本中 CONTEXT_ID 格式稳定（避免含时间戳） |
| contextId bound to agent X | 同一 contextId 被不同 Agent 使用 | contextId 与 Agent 绑定，需在 ID 中包含 agent 标识或换一个 ID |
| curl 超时 | AI 分析耗时过长 | 增加 `--max-time`，或降低 `maxTurns` |

## 注意事项

- Sharing Key 的明文仅在创建时展示一次，务必提醒用户保存
- `maxTurns` 越大 AI 分析越深入但耗时越长，定时任务建议 3-10
- 建议为定时任务创建**专用 Agent**（独立工作目录），避免影响日常开发
- 执行日志同时保存在本地文件和 Navigator Worker 页面，双重可观测
- contextId 策略影响 AI 是否能"回顾"之前的分析结果，按需选择
