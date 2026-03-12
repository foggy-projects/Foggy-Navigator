# ask-agent 功能测试用例

> 测试日期：2026-03-10
> 测试目标：验证 ask-agent 技能的完整链路（API → Agent 路由 → Worker 执行 → 结果返回）

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
- [ ] 每个 Agent 有 `name`、`description`、`url` 字段
- [ ] 记录一个 Agent 的名称和 URL（URL 中包含 agentId），后续用例使用

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

### TC-04：向 Agent 提问 — 首次提问（单轮，英文）

**目的**：验证基本的 ask 链路，使用纯英文避免编码干扰

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
    "status": { "state": "COMPLETED" },
    "artifacts": [{
      "parts": [{ "type": "text", "text": "回答内容..." }]
    }]
  }
}
```

**检查项**：
- [ ] `status.state` 为 `COMPLETED`（非 FAILED）
- [ ] `artifacts[0].parts[0].text` 包含有意义的回答
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
- [ ] 方式 B 返回 `COMPLETED`，回答内容正确
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

# 记录返回的 contextId
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

### TC-09：咨询记录 — 查询 consultation 历史

**目的**：验证咨询记录持久化

```bash
# 先用带 sessionId 的方式提问（需要一个真实的 sessionId）
# 可以从前端创建一个会话，或从数据库查一个

# 查询某会话的咨询记录
SESSION_ID="<真实的 sessionId>"
curl -s "http://localhost:8112/api/v1/agents/consultations?sessionId=$SESSION_ID" \
  -H "Authorization: Bearer $TOKEN" | jq
```

**检查项**：
- [ ] 返回该会话下所有 @agent 咨询记录
- [ ] 每条记录包含 question、answer、durationMs、contextId
- [ ] status 为 COMPLETED 或 FAILED

---

### TC-10：Skill 推送验证

**目的**：验证 PlatformSkillSyncer 是否成功将 ask-agent 技能推送到 Worker

```bash
# 检查 Worker 的 skills 目录
ls ~/.claude/skills/

# 或通过 Worker API 查看（如有）
curl -s http://localhost:3031/skills 2>&1
```

**检查项**：
- [ ] Worker 的 skills 目录中存在 ask-agent 相关文件
- [ ] 文件内容包含实际的 Agent 列表（非 `{{AGENT_TABLE}}` 占位符）
- [ ] `{{NAVIGATOR_API_BASE}}` 已替换为实际地址

---

## 测试执行顺序

```
P1~P4 前置检查
  │
  ├─→ TC-01 Agent 发现（获取 agentId）
  ├─→ TC-02 类型过滤
  ├─→ TC-03 Agent Card
  │
  ├─→ TC-04 首次英文提问（获取 contextId）
  ├─→ TC-05 中文提问（编码验证）
  ├─→ TC-06 多轮对话（用 TC-04 的 contextId）
  │
  ├─→ TC-07 不存在的 Agent
  ├─→ TC-08 空 question
  │
  ├─→ TC-09 咨询记录（需要 sessionId）
  └─→ TC-10 Skill 推送验证
```

## 已知问题

| # | 问题 | 状态 | 说明 |
|---|------|------|------|
| BUG-01 | `/api/v1/agents` 返回 403 | ✅ 已修复 | SecurityConfig 未放行该端点 |
