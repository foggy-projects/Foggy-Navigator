# Owner-Aware Agent Runtime Contract

## 文档作用

- doc_type: contract
- version: 1.1.3-SNAPSHOT
- status: supported
- date: 2026-05-24
- priority: P1
- source_type: issue-129-school-sim-validation
- intended_for: upstream-backend-developer | upstream-llm-coding-agent | navigator-owner | worker-owner
- purpose: 固化 BizWorker owner-aware Agent runtime 的支持契约，避免再回退到 Java launch `skill_markdown` 作为运行时事实来源

## 支持契约

BizWorker owner-aware Agent runtime 的事实来源是本地物化后的 skill 与 account context：

```text
<skills_root>/public/apps/<client_app_id>/<skill_id>/SKILL.md
<data_root>/accounts/<account_id>/agent/skills/<skill_id>/SKILL.md
<data_root>/accounts/<account_id>/agent/ACCOUNT_POLICY.md
<data_root>/accounts/<account_id>/agent/AGENT.md
<data_root>/accounts/<account_id>/agent/MEMORY.md
```

Java 控制面职责：

1. 维护 ClientApp、upstream user grant、SkillBundle、Function grant 等注册与授权信息。
2. 校验上游凭证和用户归属。
3. 将 `markdownBody`、resources、functions 等注册输入物化到 Worker 目录。
4. 发起 Agent ask 时传递 owner 相关 runtime context，例如 `clientAppId`、`upstreamUserId/accountId`。

BizWorker runtime 职责：

1. 每次路由或执行时按 `accountId + clientAppId` 重新加载本地 SkillRegistry。
2. 同名 skill 加载优先级固定为：

```text
legacy < builtin < public < client_app_public < account_private
```

3. routing 与 LLM skill execution 使用同一个 owner identity。`upstreamUserId/accountId`
   优先于 Navigator 内部 task owner。
4. `ACCOUNT_POLICY.md`、`AGENT.md`、`MEMORY.md` 按权限顺序注入 routing 和 skill execution prompt。
5. Java launch context 中不再需要也不应依赖 `skill_markdown` 作为运行时事实来源。

## 上游调用契约

ClientApp public skill 使用控制面同步：

```http
POST /api/v1/business-agent/skill-bundles/sync
X-Client-App-Control-Key: <control-key>
```

Account private skill 使用 runtime-token 同步：

```http
POST /api/v1/open/accounts/me/skill-bundles/sync
X-Client-App-Key: <client-app-key>
X-Client-App-Access-Token: <access-token>
X-Upstream-User-Id: <upstream-user-id>
```

Account policy 使用 runtime-token 写入：

```http
PUT /api/v1/open/accounts/me/context-files/ACCOUNT_POLICY.md
X-Client-App-Key: <client-app-key>
X-Client-App-Access-Token: <access-token>
X-Upstream-User-Id: <upstream-user-id>
```

内部预检可使用 Worker token：

```http
POST /api/v1/skills/resolve
Authorization: Bearer <worker-token>
```

## School Sim 验证证据

Issue comment: `https://github.com/foggy-projects/Foggy-Navigator/issues/129#issuecomment-4529094925`

School Sim 已验证 PM actor smoke：

1. `upstream skill sync --scope CLIENT_APP_PUBLIC` 返回 `materializeStatus=MATERIALIZED`，`workerStatusCode=200`。
2. `upstream skill sync --scope ACCOUNT_PRIVATE` 返回 `materializeStatus=MATERIALIZED`，`workerStatusCode=200`。
3. `upstream account-context write-policy` 成功写入 `ACCOUNT_POLICY.md`。
4. `upstream skill read --agent-code school-sim.actor.pm.m2.v1 --path SKILL.md`
   返回 account-private skill 内容。
5. direct `ask --agent-code school-sim.actor.pm.m2.v1` 返回 actor private workspace、
   shared root 和 PM responsibility，说明 account/private skill context 已加载。

Smoke evidence:

```text
taskId: lgt_a704840af1a74115
contextId: bctx_20260524_f6_f6b5c5b084aa4fb989e50da857317da4
terminal status: COMPLETED
```

## Regression Coverage

Owner-aware runtime regression 必须覆盖：

1. 同名 ClientApp public skill 与 account private skill 同时存在时，runtime 命中 account private。
2. `upstreamUserId/accountId` 优先于 Navigator task owner。
3. `ACCOUNT_POLICY.md` 注入到 LLM system prompt，并位于 skill instructions 之前。
4. LLM skill execution 使用 frame 冻结的 account-private manifest，不受后续 registry reload 或 Java launch `skill_markdown` 影响。

当前轻量覆盖放在 BizWorker Python 测试中；Java 到 Worker 的完整链路可在后续 L3/E2E 中按同一契约扩展。
