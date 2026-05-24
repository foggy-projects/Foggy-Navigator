# Sim BizWorker Skill Handoff

## 文档作用

- doc_type: handoff
- version: 1.1.3-SNAPSHOT
- status: ready-for-sim-trial
- date: 2026-05-24
- priority: P1
- source_type: issue-129-followup
- intended_for: sim-upstream-developer | navigator-owner | worker-owner
- purpose: 给 sim 侧首轮体验 BizWorker skill 加载、账号私有 skill 和账号记忆的最小接入路径

## 当前结论

1. BizWorker 最终负责读取本地 skill，不需要 Java 在 launch context 中继续塞 `skill_markdown`。
2. Java 仍负责 ClientApp、upstream user grant、SkillBundle 注册信息、Function 授权和物化请求。
3. ClientApp 公共技能物化到：

```text
<skills_root>/public/apps/<client_app_id>/<skill_id>/SKILL.md
```

4. accountId 私有技能和账号上下文统一物化到：

```text
<data_root>/accounts/<account_id>/agent/skills/<skill_id>/SKILL.md
<data_root>/accounts/<account_id>/agent/ACCOUNT_POLICY.md
<data_root>/accounts/<account_id>/agent/AGENT.md
<data_root>/accounts/<account_id>/agent/MEMORY.md
```

5. 同名 skill 加载优先级为：

```text
legacy < builtin < public < client_app_public < account_private
```

正式支持契约见：
[39-owner-aware-agent-runtime-contract.md](./39-owner-aware-agent-runtime-contract.md)。

## Sim 侧体验步骤

### 1. 准备 ClientApp runtime token

使用既有 OpenAPI：

```http
POST /api/v1/open/client-apps/runtime-token
X-Client-App-Key: <client-app-key>
X-Client-App-Secret: <client-app-secret>
```

后续请求都带：

```http
X-Client-App-Key: <client-app-key>
X-Client-App-Access-Token: <access-token>
X-Upstream-User-Id: <sim-user-id>
```

### 2. 注册 ClientApp 公共 skill

如果 sim 先体验公共技能，由控制面同步：

```http
POST /api/v1/business-agent/skill-bundles/sync
X-Client-App-Control-Key: <control-key>
Content-Type: application/json
```

请求体：

```json
{
  "clientAppId": "sim-app",
  "scope": "CLIENT_APP_PUBLIC",
  "skillId": "sim-assistant",
  "name": "Sim Assistant",
  "description": "Sim public skill",
  "contextVisibility": "summary",
  "markdownBody": "# Sim Assistant\n公共技能说明。",
  "resources": [],
  "functions": [],
  "status": "ENABLED",
  "materialize": true
}
```

### 3. 注册当前用户私有 skill

如果 sim 要验证账号私有覆盖，调用 runtime-token 入口：

```http
POST /api/v1/open/accounts/me/skill-bundles/sync
Content-Type: application/json
```

请求体：

```json
{
  "skillId": "sim-assistant",
  "name": "Sim Assistant Private",
  "description": "Sim account private override",
  "contextVisibility": "summary",
  "markdownBody": "# Sim Assistant Private\n只对当前 upstream user 生效。",
  "resources": [],
  "functions": [],
  "status": "ENABLED",
  "materialize": true
}
```

服务端会把 `accountId` 固定为当前 `X-Upstream-User-Id`，上游不需要也不能通过请求体覆盖。

### 4. 写入账号策略

首轮只开放上游写 `ACCOUNT_POLICY.md`：

```http
PUT /api/v1/open/accounts/me/context-files/ACCOUNT_POLICY.md
Content-Type: application/json
```

请求体：

```json
{
  "content": "# Account Policy\n\n- 使用 sim 侧业务口径回答。\n- 不保存 token、secret 或运行时凭证。\n"
}
```

`AGENT.md` 和 `MEMORY.md` 当前由 Worker 读取注入；写入能力先不暴露给普通 runtime API。

### 5. Worker 预检

Navigator 内部或部署验证可直接调用 Worker token 预检：

```http
POST /api/v1/skills/resolve
Authorization: Bearer <worker-token>
Content-Type: application/json
```

请求体：

```json
{
  "skill_id": "sim-assistant",
  "client_app_id": "sim-app",
  "account_id": "sim-user-001"
}
```

期望：

```json
{
  "status": "resolved",
  "resolved": true,
  "manifest": {
    "description": "Sim account private override"
  },
  "locations": {
    "account_skill_exists": true,
    "client_app_public_skill_exists": true
  },
  "account_context_files": {
    "ACCOUNT_POLICY.md": true
  }
}
```

### 6. 发起对话

正常使用：

```http
POST /api/v1/open/agents/{agentId}/ask
```

Worker 会根据任务 runtime context 中的 `accountId/upstreamUserId` 和 `clientAppId` 加载：

1. 当前 account private skill。
2. 当前 ClientApp public skill。
3. account context files。
4. 全局 public/builtin skill。

## 交付边界

1. sim 侧不需要直接写 Worker 目录。
2. sim 侧不需要依赖 Java launch context 的 `skill_markdown`。
3. Java 保存注册与授权信息，BizWorker runtime 做最终 skill resolve。
4. 旧路径 `<data_root>/accounts/<account_id>/skills` 不再作为目标路径。
5. 旧 materialized `markdownBody` 仍可作为 Java 注册字段存在，但最终以物化后的 `SKILL.md` 为准。

## 验收建议

1. 先同步同名 public skill 和 account private skill。
2. 调 Worker `/api/v1/skills/resolve` 确认最终命中 account private。
3. 写入 `ACCOUNT_POLICY.md` 后发起 ask，观察回答是否体现账号策略。
4. 删除 account private skill 后再次 resolve，确认回退到 ClientApp public skill。
