# Sharing Key 管理向导

为 Navigator 平台上的 Agent 创建和管理 Sharing Key（API 访问令牌），让外部用户或 AI 无需登录即可调用 Agent。

## 使用场景

> "帮我开一个 ShareKey 给 CEO 的 AI 探查项目"
> "给外部合作方创建一个 API 访问令牌"
> "创建一个共享密钥让别人的 AI 可以调用我的 Agent"
> "查看/修改/禁用/删除现有的 Sharing Key"

## CRITICAL 约束

1. **必须**通过 Bash 工具执行 `curl` 命令调用 Navigator HTTP API，**不要直接查数据库**
2. **Sharing Key 明文仅展示一次**：创建后立即告知用户保存，之后只显示掩码
3. 所有 API 调用使用 `$NAVIGATOR_API_BASE`，**禁止硬编码 localhost**
4. JSON 请求体**必须用 python3 写入 `/tmp/` 文件**再 `--data-binary @/tmp/file.json`，禁止在 curl 中内联中文或用 `$(python3 -c '...\"...')` 模式（Windows 转义 BUG）

## 前提条件

环境变量 `NAVIGATOR_TOKEN` 和 `NAVIGATOR_API_BASE` 必须存在。

检测方式：
```bash
echo "NAVIGATOR_TOKEN=${NAVIGATOR_TOKEN:+OK}" && echo "NAVIGATOR_API_BASE=${NAVIGATOR_API_BASE:-NOT_SET}"
```

**如果不存在（手动场景）**：

1. 向用户确认目标 Navigator 平台地址
2. 手动登录获取 Token：
```bash
NAVIGATOR_API_BASE="用户提供的平台地址"
LOGIN_RESULT=$(curl -s -X POST "$NAVIGATOR_API_BASE/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"用户名","password":"密码"}')
NAVIGATOR_TOKEN=$(echo "$LOGIN_RESULT" | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('token',''))")
echo "Login: $(echo "$LOGIN_RESULT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['data']['user']['username'] if d.get('code')==200 else 'FAILED: '+d.get('msg',''))")"
```

> **多环境陷阱**：WSL 中 localhost 会打到本地开发实例（不同数据库）。用 `curl $NAVIGATOR_API_BASE/actuator/health` 验证目标服务器。

---

## 创建 Sharing Key

### Step 1: 选择 Agent

**如果用户已通过 `@agentName` 选择了 Agent**：直接从消息提取 agentId，跳到 Step 2。

**否则，列出可用 Agent：**

```bash
curl -s $NAVIGATOR_API_BASE/api/v1/agents \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" | python3 -c "
import sys, json
agents = json.load(sys.stdin).get('data', [])
for a in agents:
    print(f'{a.get(\"name\",\"?\")} | id={a.get(\"id\",\"?\")}')
"
```

### Step 1.1: 确认 Agent 归属

创建 ShareKey 时后端校验 `resolveAgent(agentId, userId)`，**要求当前登录用户是 Agent 的 owner**。

```bash
# 列出当前用户自己的 coding agents（按 userId 过滤）
curl -s $NAVIGATOR_API_BASE/api/v1/coding-agents \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" | python3 -c "
import sys, json
agents = json.load(sys.stdin).get('data', [])
for a in agents:
    print(f'{a[\"name\"]} | agentId={a[\"agentId\"]} | workerId={a.get(\"workerId\",\"?\")}')
"
```

- 目标 Agent 出现在列表中 → 可以创建 ShareKey
- 不在列表中 → 需要切换到 Agent 实际 owner 的账号操作

### Step 2: 检查 Agent 能力

#### 2.1 检查 defaultModelConfigId

```bash
curl -s $NAVIGATOR_API_BASE/api/v1/coding-agents/$AGENT_ID \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" | python3 -c "
import sys, json
a = json.load(sys.stdin).get('data', {})
print(f'name={a.get(\"name\")}  defaultModelConfigId={a.get(\"defaultModelConfigId\")}  defaultModelConfigName={a.get(\"defaultModelConfigName\")}')
dd = a.get('defaultDirectory', {})
if dd: print(f'defaultDirectory: id={dd.get(\"directoryId\")}, path={dd.get(\"path\")}')
"
```

如果 `defaultModelConfigId` 为 null，需要绑定 LLM 配置：

```bash
# 列出可用 LLM 配置
curl -s $NAVIGATOR_API_BASE/api/v1/platform-configs/llm-models \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" | python3 -c "
import sys, json
for m in json.load(sys.stdin).get('data', []):
    print(f'{m.get(\"name\")} | id={m.get(\"id\")} | provider={m.get(\"provider\")}')
"

# 绑定选定的 LLM 配置
python3 -c "
import json
data = {'defaultModelConfigId': '选择的 LLM 配置 ID'}
with open('/tmp/_update_agent.json', 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False)
"

curl -s -X PUT $NAVIGATOR_API_BASE/api/v1/coding-agents/$AGENT_ID \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-binary @/tmp/_update_agent.json | python3 -c "
import sys, json
a = json.load(sys.stdin).get('data', {})
print(f'OK: defaultModelConfigId={a.get(\"defaultModelConfigId\")}  name={a.get(\"defaultModelConfigName\")}')
"
```

#### 2.2 检查 supportsSystemPrompt

```bash
curl -s $NAVIGATOR_API_BASE/api/v1/agents/$AGENT_ID/card \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" | python3 -c "
import sys, json
d = json.load(sys.stdin).get('data', {})
caps = d.get('capabilities', {})
print(f'supportsSystemPrompt={caps.get(\"supportsSystemPrompt\", False)}')
"
```

- `true`：可以在 ShareKey 中写 `systemPrompt`
- `false`/`null`：**不要在 ShareKey 中写 systemPrompt**；外部调用时用 `firstMsg` 替代

### Step 3: 创建 ShareKey

```bash
python3 -c "
import json
data = {
    'agentId': '$AGENT_ID',
    'label': '描述性标签（如 CEO-explore-project）',
    'maxTurns': 15,
    'maxDailyCalls': 200
}
# 仅当 supportsSystemPrompt=true 时添加：
# data['systemPrompt'] = '角色约束...'
with open('/tmp/_create_key.json', 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False)
print('Written to /tmp/_create_key.json')
"

curl -s -X POST $NAVIGATOR_API_BASE/api/v1/sharing-keys \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-binary @/tmp/_create_key.json | python3 -c "
import sys, json
d = json.load(sys.stdin)
sk = d.get('data', {})
print(f'=== ShareKey \u521b\u5efa\u6210\u529f ===')
print(f'Key ID:     {sk.get(\"id\")}')
print(f'\u660e\u6587\u5bc6\u94a5:   {sk.get(\"sharingKey\")}')
print(f'Agent:      {sk.get(\"agentName\")}')
print(f'maxTurns:   {sk.get(\"maxTurns\")}')
print(f'dailyCalls: {sk.get(\"maxDailyCalls\")}')
print(f'invokeUrl:  {sk.get(\"invokeUrl\")}')
print()
print('\u26a0\ufe0f  \u660e\u6587\u5bc6\u94a5\u4ec5\u6b64\u4e00\u6b21\u5c55\u793a\uff0c\u8bf7\u7acb\u5373\u4fdd\u5b58\uff01')
"
```

**参数说明：**

| 参数 | 说明 | 建议值 |
|------|------|--------|
| `maxTurns` | AI 最大思考轮数 | 探查类: 10-15, 简单问答: 1-3 |
| `maxDailyCalls` | 每日调用上限 | 外部探查: 50-200, 定时任务: 1-10 |
| `expiresAt` | 过期时间（null=永不过期） | 按需设置 |
| `systemPrompt` | 角色约束（仅 supportsSystemPrompt=true） | 按需 |

### Step 4: 输出使用指南

创建 ShareKey 后，生成完整的使用指南交给外部用户/AI。**必须包含以下要素**：

#### 使用指南模板

```
## API 访问信息

| 项目 | 值 |
|------|-----|
| 平台地址 | {用户提供的外部域名} |
| Sharing Key | {创建返回的 shk-... 明文} |
| 绑定 Agent | {Agent 名称} |
| 工作目录 ID | {defaultDirectory.directoryId} |

### 1. 提问（核心端点，异步）
POST {平台地址}/api/v1/shared/ask
Header: X-Sharing-Key: {key}
Content-Type: application/json; charset=UTF-8

请求体：
{
  "question": "你的问题",
  "contextAlias": "业务别名（相同 alias 续接同一会话）",
  "firstMsg": "首轮初始化上下文（可选，仅首次创建会话时生效）"
}

### 2. 轮询任务结果
GET {平台地址}/api/v1/shared/tasks/{taskId}
Header: X-Sharing-Key: {key}

状态流转：SUBMITTED → WORKING → COMPLETED / FAILED
完成后 artifacts[0].parts[0].text 即为 AI 回答。

### 3. 文件浏览
GET {平台地址}/api/v1/shared/files/list?directoryId={dirId}&path=
GET {平台地址}/api/v1/shared/files/read?directoryId={dirId}&path=README.md
GET {平台地址}/api/v1/shared/files/search?directoryId={dirId}&q=Controller
Header: X-Sharing-Key: {key}

### 4. 其他端点
POST {平台地址}/api/v1/shared/tasks/{taskId}/cancel    — 取消任务
GET  {平台地址}/api/v1/shared/tasks/{taskId}/artifacts  — 获取产物
GET  {平台地址}/api/v1/shared/sessions/{sessionId}      — 查看会话历史
```

#### contextAlias 会话续接策略

**务必在使用指南中说明，这是最容易被忽略但最有价值的功能：**

| 策略 | contextAlias 值 | 效果 |
|------|----------------|------|
| **永续单会话** | `"explore"` | 所有调用共用一个会话，AI 可回顾历史 |
| **按主题分组** | `"explore-arch"` / `"explore-api"` | 不同主题独立会话 |
| **每次独立** | 不传 | 每次全新会话 |

规则：后端按 `(contextAlias, userId, agentId)` 唯一匹配。

#### firstMsg（当 supportsSystemPrompt=false 时）

如果 Agent 不支持 systemPrompt，外部用户应在请求中用 `firstMsg` 传入角色定义和输出格式要求。`firstMsg` **仅在 contextAlias 首次使用（创建新会话）时注入**，后续同 alias 调用自动忽略。

---

## 管理已有的 Sharing Key

### 查看列表

```bash
curl -s $NAVIGATOR_API_BASE/api/v1/sharing-keys \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" | python3 -c "
import sys, json
for sk in json.load(sys.stdin).get('data', []):
    print(f'{sk.get(\"label\",\"?\")} | id={sk.get(\"id\")} | agent={sk.get(\"agentName\",\"?\")} | key={sk.get(\"maskedKey\")} | enabled={sk.get(\"enabled\")} | today={sk.get(\"todayCalls\")}/{sk.get(\"maxDailyCalls\")}')
"
```

### 修改配置

```bash
python3 -c "
import json
data = {
    'maxDailyCalls': 20,
    'maxTurns': 8
}
with open('/tmp/_update_key.json', 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False)
"

curl -s -X PUT $NAVIGATOR_API_BASE/api/v1/sharing-keys/{keyId} \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-binary @/tmp/_update_key.json | python3 -c "
import sys, json; print(json.dumps(json.load(sys.stdin).get('data',{}), indent=2, ensure_ascii=False))
"
```

### 禁用/启用

```bash
python3 -c "
import json
with open('/tmp/_update_key.json', 'w') as f:
    json.dump({'enabled': False}, f)  # True 启用 / False 禁用
"

curl -s -X PUT $NAVIGATOR_API_BASE/api/v1/sharing-keys/{keyId} \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-binary @/tmp/_update_key.json | python3 -c "
import sys, json; sk=json.load(sys.stdin).get('data',{}); print(f'enabled={sk.get(\"enabled\")}')
"
```

### 删除

```bash
curl -s -X DELETE $NAVIGATOR_API_BASE/api/v1/sharing-keys/{keyId} \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" | python3 -c "
import sys, json; print(json.dumps(json.load(sys.stdin), ensure_ascii=False))
"
```

---

## 故障排查

| 问题 | 检查方式 | 解决方案 |
|------|----------|----------|
| 返回 "Invalid sharing key" | Key 是否正确？是否已删除？ | 查看 Sharing Key 列表 |
| 返回 "Sharing key is disabled" | Key 是否被禁用？ | 启用 Key |
| 返回 "Daily call limit exceeded" | 今日调用次数超限 | 调大 `maxDailyCalls` |
| 返回 "Sharing key has expired" | Key 已过期 | 创建新 Key 或更新过期时间 |
| 返回 "Shared agent not available" | Agent 不存在或 Worker 离线 | 检查 Agent 列表和 Worker 状态 |
| 创建时返回 "Agent not found or not owned by you" | 当前用户不是 Agent owner | 用 `GET /api/v1/coding-agents` 确认归属；切换到 owner 账号 |
| curl 返回 Spring 默认 400（`{"timestamp":...}`，非 RX 格式） | 请求体为空或 JSON 编码错误 | 用 python3 写 `/tmp/file.json` 再 `--data-binary @/tmp/file.json` |
| localhost 返回的数据与预期不符 | WSL 中 localhost 打到本地开发实例 | 始终用 `$NAVIGATOR_API_BASE`；`curl .../actuator/health` 验证目标 |
| 任务启动失败（LLM 未配置） | Agent.defaultModelConfigId 为 null | 执行 Step 2.1 绑定默认 LLM 配置 |
| contextId bound to agent X | 同一 contextId 被不同 Agent 使用 | 改用 contextAlias（自动按 agent 隔离） |

## 注意事项

- Sharing Key 的明文仅在创建时展示一次，务必提醒用户保存
- `maxTurns` 越大 AI 分析越深入但耗时越长
- `/ask` 是异步端点（返回 SUBMITTED），需轮询 `/tasks/{taskId}` 等待 COMPLETED
- contextAlias 是外部用户最容易忽略但最有价值的功能，使用指南中务必说明
