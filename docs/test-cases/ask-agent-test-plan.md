# navigator-ops scheduled-task A2A 功能测试用例

> 初始测试日期：2026-03-10；本轮更新：2026-07-16
> 测试目标：验证 `navigator-ops` 的 scheduled-task 显式 A2A 分支（API → Agent 路由 → Worker 执行 → 结果返回）。该分支仅在 Prompt 同时包含 `[NAVIGATOR_SCHEDULED_A2A]` 和精确目标时使用；普通会话和 Provider 原生子 Agent 委派不得触发。底层 A2A 对外 API、Agent Card、Sharing Key 和轮询能力继续保留。

---

## 前置条件

| # | 条件 | 验证命令 | 期望结果 |
|---|------|---------|---------|
| P1 | 后端运行中 | `curl -s http://localhost:8112/actuator/health` | `{"status":"UP"}` |
| P2 | Claude Worker 运行中 | `curl -s http://localhost:3031/health` | `claude_cli_available: true` |
| P3 | 至少存在 1 个 LOCAL_CLAUDE_WORKER 类型的 Agent | 通过 TC-01 验证 | Agent 列表非空 |
| P4 | SecurityConfig 已放行 `/api/v1/agents/**` | 通过 TC-01 验证 | 非 403 |

### 获取 Token（后续测试全部复用）

```bash
# 登录获取 JWT token
curl -s -X POST http://localhost:8112/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"root","password":"root123"}' | jq -r '.data.token'

# 将 token 存为变量（后续命令用 $TOKEN 引用）
TOKEN="<上一步返回的 token>"
```

---

## 测试用例

### TC-01：Agent 发现 — 列出所有可用 Agent

**目的**：验证 Agent 发现 API 能正常返回 Agent 列表

```bash
curl -s http://localhost:8112/api/v1/agents \
  -H "Authorization: Bearer $TOKEN" | jq
```

**期望结果**：
```json
{
  "code": 200,
  "data": [
    {
      "id": "agent-xxx",
      "name": "Agent名称",
      "description": "...",
      "url": "...",
      "skills": [...]
    }
  ]
}
```

**检查项**：
- [ ] HTTP 200（非 403）
- [ ] `data` 是数组
- [ ] 每个 Agent 有 `id`、`name`、`description`、`url` 字段
- [ ] 直接记录一个准确的 `id` 为 `AGENT_ID`，后续调用不得用名称替代或从 URL 猜测

---

### TC-02：Agent 发现 — 按类型过滤

**目的**：验证 `?type=claude-worker` 过滤器

```bash
curl -s "http://localhost:8112/api/v1/agents?type=claude-worker" \
  -H "Authorization: Bearer $TOKEN" | jq
```

**检查项**：
- [ ] 返回的 Agent 列表仅包含 claude-worker 类型
- [ ] 结果是 TC-01 的子集

---

### TC-03：获取 Agent Card

**目的**：验证单个 Agent 详情查询

```bash
AGENT_ID="<从 TC-01 获取的 agentId>"
curl -s http://localhost:8112/api/v1/agents/$AGENT_ID/card \
  -H "Authorization: Bearer $TOKEN" | jq
```

**检查项**：
- [ ] HTTP 200
- [ ] 返回完整的 AgentCard（name, description, skills）
- [ ] 不存在的 agentId 返回失败信息

---

### TC-04：向 Agent 提问 — 异步提交（英文）

**目的**：验证基本的异步 ask 提交链路，使用纯英文避免编码干扰

```bash
AGENT_ID="<从 TC-01 获取的 agentId>"
curl -s -X POST http://localhost:8112/api/v1/agents/$AGENT_ID/ask \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"question":"What is the main tech stack of this project?","sessionId":""}' | jq
```

**期望结果**：
```json
{
  "code": 200,
  "data": {
    "id": "task-xxx",
    "contextId": "20260310-xxxx",
    "status": { "state": "SUBMITTED" }
  }
}
```

**检查项**：
- [ ] 提交立即返回，`status.state` 为 `SUBMITTED` 或 `WORKING`
- [ ] 返回非空 `id`，记录为 `TASK_ID` 并在 TC-09 轮询，不要求提交响应立即包含 artifacts
- [ ] `contextId` 已自动生成（非 null）
- [ ] 记录返回的 `contextId`，TC-06 使用

---

### TC-05：向 Agent 提问 — 中文内容（Windows 编码验证）

**目的**：验证中文内容在 Windows 终端下是否能正确传递

```bash
# 方式 A：直接 curl（可能失败，用于对比）
curl -s -X POST http://localhost:8112/api/v1/agents/$AGENT_ID/ask \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json; charset=UTF-8" \
  -d "{\"question\":\"这个项目用了什么技术栈？\",\"sessionId\":\"\"}" | jq

# 方式 B：通过 Python 写文件绕过编码问题（SKILL.md 推荐方式）
python3 -c "
import json
data = {'question': '这个项目用了什么技术栈？', 'sessionId': ''}
with open('_ask_agent_test.json', 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False)
"
curl -s -X POST http://localhost:8112/api/v1/agents/$AGENT_ID/ask \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-binary @_ask_agent_test.json | jq
```

**检查项**：
- [ ] 方式 B 成功返回任务 `id`，再按 TC-09 轮询到 `COMPLETED`，回答内容正确
- [ ] 对比方式 A 是否也能成功（如果成功则 SKILL.md 的 workaround 可以标注为可选）
- [ ] 回答内容中中文显示正常，无乱码

---

### TC-06：多轮对话 — contextId 延续

**目的**：验证多轮对话的上下文保持

```bash
# 第一轮（使用 TC-04 返回的 contextId，或发起新对话）
python3 -c "
import json
data = {'question': 'What authentication method does this project use?', 'sessionId': ''}
with open('_ask_agent_test.json', 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False)
"
curl -s -X POST http://localhost:8112/api/v1/agents/$AGENT_ID/ask \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-binary @_ask_agent_test.json | jq

# 记录返回的 task id，并按 TC-09 轮询到终态后再继续；同时记录 contextId
CONTEXT_ID="<第一轮返回的 contextId>"

# 第二轮（带 contextId 追问）
python3 -c "
import json
data = {'question': 'What is the token expiry time?', 'sessionId': '', 'contextId': '$CONTEXT_ID'}
with open('_ask_agent_test.json', 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False)
"
curl -s -X POST http://localhost:8112/api/v1/agents/$AGENT_ID/ask \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-binary @_ask_agent_test.json | jq
```

**检查项**：
- [ ] 第二轮返回的 `contextId` 与传入的一致
- [ ] 第二轮回答能理解上下文（知道"token"指的是第一轮讨论的认证 token）
- [ ] 如果没有上下文延续，Agent 可能会问"什么 token？"——以此判断是否成功

---

### TC-07：异常情况 — 不存在的 Agent

**目的**：验证错误处理

```bash
curl -s -X POST http://localhost:8112/api/v1/agents/non-existent-id/ask \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"question":"hello","sessionId":""}' | jq
```

**检查项**：
- [ ] 返回错误信息（非 500 服务器错误）
- [ ] 错误信息包含 "Agent not found"

---

### TC-08：异常情况 — 空 question

```bash
curl -s -X POST http://localhost:8112/api/v1/agents/$AGENT_ID/ask \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"question":"","sessionId":""}' | jq
```

**检查项**：
- [ ] 返回 `"question is required"` 错误
- [ ] 不会触发 Worker 调用

---

### TC-09：按准确 agentId 轮询 A2A 任务

**目的**：验证 ask 的异步轮询链路，并确认提交和轮询始终使用同一个准确 `agentId`

```bash
# 使用 TC-04/TC-05 返回的 TASK_ID；AGENT_ID 必须与提交时完全相同
while true; do
  POLL=$(curl -s "http://localhost:8112/api/v1/agents/$AGENT_ID/tasks/$TASK_ID" \
    -H "Authorization: Bearer $TOKEN")
  STATE=$(echo "$POLL" | jq -r '.data.status.state')
  case "$STATE" in
    COMPLETED|FAILED|INPUT_REQUIRED) echo "$POLL" | jq; break ;;
    *) sleep 5 ;;
  esac
done
```

**检查项**：
- [ ] 轮询请求路径使用配置阶段保存的 `AGENT_ID`，没有 `agentName`、模糊匹配或裸 `@名称`
- [ ] 运行中状态可持续查询，最终进入 `COMPLETED`、`FAILED` 或 `INPUT_REQUIRED`
- [ ] `COMPLETED` 时 `artifacts[].parts[].text` 包含有效结果
- [ ] 不存在的 `TASK_ID` 返回明确失败信息，而不是 500

---

### TC-10：navigator-ops 对账与旧 Skill 清理验证

**目的**：验证 Worker 启动部署或 PlatformSkillSyncer 请求对账后，`navigator-ops` 已完整部署，旧 Worker 生成的独立 Skill 不会复活

```bash
# 当前受管路由及 scheduled-task A2A 参考
cat ~/.agents/skills/navigator-ops/SKILL.md
cat ~/.agents/skills/navigator-ops/references/scheduled-a2a.md

# 三个历史发现根中不应再有旧 Worker 生成副本
for root in ~/.agents/skills ~/.agent/skills ~/.claude/skills; do
  for name in ask-agent navigator-admin scheduled-task sharing-key; do
    test ! -e "$root/$name/SKILL.md" || echo "legacy skill remains: $root/$name"
  done
done

# 也可通过 Worker API 查看；启用 Worker Token 时补 Authorization 请求头
curl -sG http://localhost:3031/api/v1/skills \
  --data-urlencode "cwd=$PWD" \
  -H "Authorization: Bearer $AGENT_WORKER_WORKER_TOKEN" | jq
```

**检查项**：
- [ ] `~/.agents/skills/navigator-ops/SKILL.md` 存在，包含受管 marker，并将 scheduled-task A2A 路由到 `references/scheduled-a2a.md`
- [ ] `scheduled-a2a.md` 包含 `[NAVIGATOR_SCHEDULED_A2A]` 和 `targetAgentId`，且不包含动态 Agent 列表、`{{AGENT_TABLE}}`、`targetAgentName` 或通用裸 `@Agent` 路由说明
- [ ] 路由包中的 `{{NAVIGATOR_API_BASE}}` 已替换为 Worker 当前配置地址
- [ ] `~/.agents/skills`、`~/.agent/skills` 与 `~/.claude/skills` 中已识别的 `ask-agent`、`navigator-admin`、`scheduled-task`、`sharing-key` 旧副本均被清理
- [ ] 旧控制面再次推送 retired 名称时，Worker 先执行对账并拒绝写回；新控制面的空 `skills` 请求可触发对账
- [ ] 同名但无法识别为 Navigator 管理的用户文件、目录内其他文件以及链接未被删除或覆盖

---

## 测试执行顺序

```
P1~P4 前置检查
  │
  ├─→ TC-01 Agent 发现（获取 agentId）
  ├─→ TC-02 类型过滤
  ├─→ TC-03 Agent Card
  │
  ├─→ TC-04 异步提交（获取 taskId/contextId）
  ├─→ TC-05 中文提问（编码验证）
  ├─→ TC-06 多轮对话（用 TC-04 的 contextId）
  │
  ├─→ TC-07 不存在的 Agent
  ├─→ TC-08 空 question
  │
  ├─→ TC-09 按准确 agentId 轮询任务
  └─→ TC-10 navigator-ops 对账与旧 Skill 清理验证
```

## 已知问题

| # | 问题 | 状态 | 说明 |
|---|------|------|------|
| BUG-01 | `/api/v1/agents` 返回 403 | ✅ 已修复 | SecurityConfig 未放行该端点 |
