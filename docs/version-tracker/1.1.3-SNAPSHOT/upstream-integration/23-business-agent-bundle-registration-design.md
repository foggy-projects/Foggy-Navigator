# Business Agent Bundle Registration Design

## 文档作用

- doc_type: design-and-implementation
- version: 1.1.3-SNAPSHOT
- status: completed
- date: 2026-05-12
- source_type: integration-feedback
- source: upstream readiness feedback for `world-sim.bug-coordinator.decision.v1`
- intended_for: Navigator maintainer | upstream backend developer | upstream LLM coding agent
- purpose: 设计并落地正式业务 Agent 注册与授权能力，避免只同步 Skill 后 readiness 出现 `AGENT_REGISTERED=FAIL`

## 背景

Navigator Upstream CLI 已验证 ClientApp 凭证、runtime token、upstream user grant、model config grant 与 skill 文件读取链路可用，但真实目标：

```text
agentCode / skillId = world-sim.bug-coordinator.decision.v1
```

仍缺 Navi 侧正式注册：

- `UnifiedAgentResolver` 解析不到 Agent，`AGENT_REGISTERED=FAIL`。
- `SkillRegistryService` 找不到或未授权目标 Skill，`CLIENT_APP_SKILL_GRANT=FAIL`。

临时注册 echo skill 只能验证文件读取，不能代表真实 ask 能力。因此需要一个正式 control-plane 能力，一次性同步：

1. 可被 `UnifiedAgentResolver` 解析的 Agent。
2. 与 Agent code 对齐的 public Skill Bundle。
3. 当前 ClientApp 对该 Skill 的 grant。
4. Agent 默认 LangGraph worker 与默认 model config。

## 设计结论

新增控制面接口：

```http
POST /api/v1/business-agent/agent-bundles/sync
```

该接口由 `TENANT_ADMIN` 调用，输入为一个 agent bundle manifest。核心字段：

```json
{
  "clientAppId": "external-llm-agent-dev",
  "agentId": "world-sim.bug-coordinator.decision.v1",
  "skillId": "world-sim.bug-coordinator.decision.v1",
  "name": "World Sim Bug Coordinator",
  "description": "Decision agent",
  "workerId": "<langgraphWorkerId>",
  "defaultModelConfigId": "<modelConfigId>",
  "contextVisibility": "summary",
  "markdownBody": "# Skill instructions",
  "resources": [],
  "functions": [],
  "materialize": true
}
```

约定：

- `agentId` 是上游 `.navigator/upstream.env` 中的 `NAVI_AGENT_CODE`。
- `agentId` 的数据库唯一边界为 `tenantId + agentId`。不同租户可注册同名逻辑 Agent；同一租户内仍应使用项目级命名空间，如 `tms-x3-agent-v305`、`world-sim.bug-coordinator.decision.v1`。
- 手工同步业务 Agent Bundle 时，`skillId` 默认等于 `agentId`。上游租户初始化的 root agent 场景允许 `rootAgentId` 与内部 `skillId` 分离，由 Navigator 在 OpenAPI ask/preflight 时从 agent profile 派生 effective skill。
- `contextVisibility` 是同步给该 Agent 默认 public Skill Bundle 的上下文可见性策略；不传时默认为 `isolated`，普通业务 skill 首版可使用 `isolated` 或 `summary`。
- Agent 运行时复用现有 `CodingAgentEntity + LanggraphWorkerAgentProvider`，`agentType=LOCAL_LANGGRAPH_WORKER`。`CodingAgentEntity` 是历史类名，当前按通用 Agent 注册行使用；业务 Agent 会写入 `agent_profile` JSON，例如 `domain=BUSINESS_AGENT`、`kind=CLIENT_APP_RUNTIME_AGENT`、`clientAppId` 与 `skillId`。
- Skill 交付复用 `SkillRegistryService.syncSkillBundle(... CLIENT_APP_PUBLIC ...)`，不新增第二套 Skill/Grant 权限模型。
- `defaultModelConfigId` 必须已授权给当前 ClientApp，且 backend 为 `LANGGRAPH_BIZ`。
- `workerId` 指向实际执行 OpenAPI ask 的 LangGraph worker。
- `functions` 是 Skill allowlist 引用，不承载完整 Function Manifest。同步 agent 前，上游应先用 SDK 导入 Business Function Manifest，并把这些 function grant 到当前 ClientApp；该步骤可使用 `NAVI_CONTROL_API_KEY`，不需要租户级 admin。

> 2026-05-24 owner-aware resource governance 更新：本页保留的是 `agent-bundles/sync` 的早期落地记录。新接入上游不应把 `workerId` 理解成业务调用方可选择的 WorkerPool。Agent 应作为稳定 runtime profile，默认绑定 `LlmConfigModel`、`WorkingDirectory` 和 backend policy；`WorkerPool` 仅作为 Navigator 内部 routing artifact 或短期兼容字段。真实发布前以 `owner-smoke resources OK` 为准，不能只看 Agent / Skill 注册成功。

## CLI 与 SDK

SDK 增加：

```java
client.businessAgent().syncBusinessAgentBundle(form);
```

上游函数同步链路使用同一个 ClientApp-scoped control credential：

```java
NavigatorClient client = NavigatorClient.builder()
    .baseUrl(navigatorBaseUrl)
    .tenantId(tenantId)
    .controlApiKey(naviControlApiKey)
    .build();

client.businessAgent().importBusinessFunctionManifest(functionManifest);
client.businessAgent().grantFunctionToClientApp(clientAppId, functionGrant);
client.businessAgent().syncBusinessAgentBundle(agentBundle);
```

CLI 增加：

```powershell
.\tools\navigator-upstream\navi.ps1 upstream agent sync --manifest .\agent-bundle.json
```

该命令优先使用 `NAVI_CONTROL_API_KEY`，即 ClientApp-scoped 控制面凭证；`NAVI_ADMIN_TOKEN` 仅作为 Navigator 内部管理员 fallback。`NAVI_ADMIN_API_KEY` 不再作为普通 `X-API-Key` fallback。普通上游调用 ask/readiness 仍只使用 project-local runtime profile。

## 验收标准

- 同步后 `verify-agent-readiness --agent-code <agentId>` 的 `AGENT_REGISTERED` 可通过。
- 同步后同一 `agentId` 的 `CLIENT_APP_SKILL_GRANT` 可通过。
- `skill tree/read` 可读取该 Skill Bundle materialized artifact。
- `ask` 在未显式传 `modelConfigId` 时使用 Agent 默认 `defaultModelConfigId`。
- CLI/SDK 覆盖新增接口路径，后端服务覆盖创建与跨租户冲突。

## Progress

| Item | Status | Notes |
| --- | --- | --- |
| 设计落档 | completed | 本文档 |
| 后端 `agent-bundles/sync` | completed | 同步 `CodingAgentEntity` + public Skill Bundle |
| SDK `syncBusinessAgentBundle` | completed | 控制面 API 封装 |
| CLI `upstream agent sync` | completed | manifest 驱动 |
| LangGraph 默认模型传递 | completed | 未显式 metadata 时使用 Agent default |
| 测试验证 | completed | 后端、SDK、LangGraph worker 单测已通过 |
