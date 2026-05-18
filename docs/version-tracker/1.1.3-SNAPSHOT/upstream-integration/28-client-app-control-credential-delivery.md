# ClientApp-Scoped Control Credential Delivery

## 文档作用

- doc_type: design-and-implementation
- version: 1.1.3-SNAPSHOT
- status: completed
- date: 2026-05-13
- source_type: upstream-delivery-hardening
- intended_for: Navigator maintainer | upstream backend developer | upstream LLM coding agent
- purpose: 用 ClientApp 绑定的控制面凭证替代上游项目持有租户级管理员凭证或普通 `X-API-Key` fallback

## 结论

上游项目正式交付不再使用租户级 admin key 或普通 `X-API-Key` fallback。Navigator 侧给每个 ClientApp 发放一个项目本地控制面凭证：

```properties
NAVI_CONTROL_API_KEY=<client-app-scoped-control-key>
```

该凭证只解析为：

```text
tenantId
clientAppId
credentialId
scopes
effectiveNavigatorUserId
```

它不是上游用户身份，不参与 Business Agent 会话归属。会话仍由 runtime token + `X-Upstream-User-Id` 归属到 `tenantId + clientAppId + upstreamUserId`。

## 后端能力

新增表：

```text
client_app_control_credential
```

核心字段：

```text
credentialId
tenantId
clientAppId
controlKeyHash
issuedByUserId
effectiveUserId
scopes
status
expiresAt
lastUsedAt
```

签发入口仍需 Navigator `TENANT_ADMIN`：

```http
POST /api/v1/client-apps/{clientAppId}/control-credentials
```

响应只在创建时返回明文 `controlApiKey`，后续仅存 hash。

## Scope

默认发放以下 scope：

```text
AGENT_BUNDLE_SYNC
SKILL_BUNDLE_SYNC
FUNCTION_MANIFEST_IMPORT
FUNCTION_GRANT_MANAGE
E2E_MODEL_ENSURE
UPSTREAM_USER_GRANT
MODEL_CONFIG_GRANT_MANAGE
```

也支持 `CONTROL_PLANE_ALL` 作为内部兜底。

## 已接入接口

这些接口现在接受二选一身份：

1. Navigator `TENANT_ADMIN` / `SUPER_ADMIN`
2. `X-Client-App-Control-Key: <NAVI_CONTROL_API_KEY>`

```http
POST /api/v1/business-agent/agent-bundles/sync
POST /api/v1/business-agent/skill-bundles/sync
POST /api/v1/business-agent/functions/import
POST /api/v1/business-agent/client-apps/{clientAppId}/function-grants
PUT  /api/v1/business-agent/client-apps/{clientAppId}/function-grants/{grantId}/status
GET  /api/v1/business-agent/client-apps/{clientAppId}/visible-functions
POST /api/v1/business-agent/client-apps/{clientAppId}/upstream-users
PUT  /api/v1/business-agent/client-apps/{clientAppId}/upstream-users/{upstreamUserId}/status
GET  /api/v1/client-apps/{clientAppId}/model-config-grants
POST /api/v1/client-apps/{clientAppId}/model-config-grants
PUT  /api/v1/client-apps/{clientAppId}/model-config-grants/{grantId}/status
PUT  /api/v1/client-apps/{clientAppId}/model-config-grants/{grantId}/default
POST /api/v1/client-apps/{clientAppId}/model-configs
PUT  /api/v1/client-apps/{clientAppId}/model-configs/{modelConfigId}
PUT  /api/v1/client-apps/{clientAppId}/model-configs/{modelConfigId}/key
POST /api/v1/business-agent/client-apps/{clientAppId}/e2e-model-config/ensure
```

服务端强制校验路径或 body 中的 `clientAppId` 必须等于 credential 绑定的 `clientAppId`。`skill-bundles/sync` 在 control key 模式下固定为 `CLIENT_APP_PUBLIC`，并清空 `accountId`。

Function import 本身是租户级 function manifest upsert；后续 function grant 仍强制限定到 control key 绑定的 ClientApp。上游使用 SDK 时应改用：

```java
NavigatorClient client = NavigatorClient.builder()
    .baseUrl(navigatorBaseUrl)
    .tenantId(tenantId)
    .controlApiKey(naviControlApiKey)
    .build();
```

然后继续调用现有 `importBusinessFunctionManifest(...)` 与 `grantFunctionToClientApp(...)`。

兼容说明：早期已签发的 control key 可能没有显式 `FUNCTION_MANIFEST_IMPORT` / `FUNCTION_GRANT_MANAGE` scope。为避免交付中断，当前版本允许带 `AGENT_BUNDLE_SYNC` 的旧 key 完成同一交付链路中的 function import/grant；新签发 key 会包含细分 scope。

## CLI 方案

上游 `.navigator/upstream.env` 增加：

```properties
NAVI_CONTROL_API_KEY=<client-app-scoped-control-key>
NAVI_UPSTREAM_USER_TOKEN=<optional-current-upstream-user-token>
```

CLI 控制面命令优先使用 `NAVI_CONTROL_API_KEY`，仍保留以下内部 fallback：

```properties
NAVI_ADMIN_TOKEN=<internal-only>
```

`NAVI_ADMIN_API_KEY` 是上游系统级 ClientApp 管理凭证，仅用于多租户上游 bootstrap 阶段创建/复用 ClientApp 和签发该 ClientApp 的控制面凭证；不再作为控制面命令的普通 `X-API-Key` fallback。

涉及命令：

```powershell
.\tools\navigator-upstream\navi.ps1 upstream ensure-grant --upstream-user-id <id>
.\tools\navigator-upstream\navi.ps1 upstream agent sync --manifest .\.navigator\agent-bundle.json
.\tools\navigator-upstream\navi.ps1 upstream skill sync --scope client-app-public --manifest .\.navigator\skill-bundle.json
.\tools\navigator-upstream\navi.ps1 upstream model grants
.\tools\navigator-upstream\navi.ps1 upstream model grant --model-config-id <modelConfigId> --set-default --write-profile
.\tools\navigator-upstream\navi.ps1 upstream model set-default --model-config-id <modelConfigId> --write-profile
.\tools\navigator-upstream\navi.ps1 upstream model create --name <name> --model-base-url <llmBaseUrl> --model-name <modelName> --api-key-env NAVI_LLM_API_KEY --set-default --write-profile
.\tools\navigator-upstream\navi.ps1 upstream model update --model-config-id <modelConfigId> --model-base-url <llmBaseUrl> --model-name <modelName>
.\tools\navigator-upstream\navi.ps1 upstream model rotate-key --model-config-id <modelConfigId> --api-key-env NAVI_LLM_API_KEY
.\tools\navigator-upstream\navi-e2e.ps1 model ensure --standard biz-worker --set-default --write-profile
```

`ensure-grant` 只要求 `NAVI_CONTROL_API_KEY` 与 `upstreamUserId`。`model` 命令组只维护当前 ClientApp 的 model config grant 与本地 `NAVI_MODEL_CONFIG_ID`，不修改租户默认模型。`model create` 创建的模型会以 `CLIENT_APP_OWNED` grant 绑定到当前 ClientApp；`model update` 和 `model rotate-key` 只能维护这种自有模型，不能修改管理员预置后授权的共享模型。`--api-key-env` 从环境变量读取上游自有 LLM key，避免 key 进入命令历史或 CLI 输出。

注意：`--base-url` 是 Navigator 服务地址；上游 LLM/OpenAI-compatible 地址使用 `--model-base-url`。

`NAVI_UPSTREAM_USER_TOKEN` 可选：需要 Worker 代表当前上游用户回调上游系统时再提供；SIM/E2E 或纯 Navi 会话授权可先省略。`TMS_STAFF_SESSION_TOKEN` 仅保留为 TMS sandbox 旧别名。

上游不应把 `NAVI_CONTROL_API_KEY` 写入源码、文档、issue、日志或截图，只能放在项目本地 gitignored `.navigator/upstream.env`。

## Progress

| Item | Status | Notes |
| --- | --- | --- |
| Credential entity/repository | completed | `client_app_control_credential` |
| Admin issue API | completed | `POST /api/v1/client-apps/{clientAppId}/control-credentials` |
| Control-plane resolver | completed | `X-Client-App-Control-Key` |
| ClientApp scope enforcement | completed | cross-clientApp reject |
| Function import/grant control key support | completed | SDK 使用 `controlApiKey(...)` |
| SDK/CLI header support | completed | `NAVI_CONTROL_API_KEY` |
| Multi-tenant bootstrap handoff | completed | `NAVI_ADMIN_API_KEY` 通过 `X-Navi-Admin-Key` 签发 tenant profile 中的 `NAVI_CONTROL_API_KEY` |
| CLI `upstream agent sync` | completed | manifest driven, uses control key |
| CLI `upstream model` | completed | `grants` / `grant` / `set-default` / `create` / `update` / `rotate-key` |
| Tests | completed | service boundary + module tests |
