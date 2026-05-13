# REST API 参考

## 文档作用

- doc_type: api-reference
- version: 1.1.3-SNAPSHOT
- status: draft
- date: 2026-05-04
- intended_for: upstream-backend-developer | platform-admin
- purpose: 汇总 1.1.3 Business Agent 主要控制面 API，作为底层协议参考

> **⚠️ 定位说明**：REST API 仅作为协议参考和 SDK 未覆盖能力的临时兜底。推荐优先使用 [Backend SDK](./01-backend-sdk-quickstart.md) 和 [Frontend Component](./02-frontend-component-quickstart.md)。

## Client Application

| 方法 | 路径 | 用途 | 权限 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/client-apps` | 查询 ClientApp 列表 | `TENANT_ADMIN` |
| `POST` | `/api/v1/client-apps` | 创建 ClientApp（需 provisioning credential） | `TENANT_ADMIN` |
| `POST` | `/api/v1/client-apps/{clientAppId}/runtime-credentials` | 签发 runtime credential | `TENANT_ADMIN` |
| `PUT` | `/api/v1/client-apps/{clientAppId}/status` | 更新 App 状态 | `TENANT_ADMIN` |

## Provisioning Credential

| 方法 | 路径 | 用途 | 权限 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/admin/client-apps/provisioning-credentials` | 签发 provisioning credential | `TENANT_ADMIN` |

## Model Config Grant

| 方法 | 路径 | 用途 | 权限 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/client-apps/{clientAppId}/model-config-grants` | 查询模型授权列表 | `TENANT_ADMIN` |
| `POST` | `/api/v1/client-apps/{clientAppId}/model-config-grants` | 授权模型给 ClientApp | `TENANT_ADMIN` |
| `PUT` | `/api/v1/client-apps/{clientAppId}/model-config-grants/{grantId}/status` | 启停模型授权 | `TENANT_ADMIN` |
| `PUT` | `/api/v1/client-apps/{clientAppId}/model-config-grants/{grantId}/default` | 设置默认模型 | `TENANT_ADMIN` |

## Skill

| 方法 | 路径 | 用途 | 权限 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/business-agent/skills` | 创建 Skill | `TENANT_ADMIN` |
| `POST` | `/api/v1/business-agent/skills/{skillId}/functions` | 绑定函数到 Skill allowlist | `TENANT_ADMIN` |
| `POST` | `/api/v1/business-agent/client-apps/{clientAppId}/skill-grants` | 授权 Skill 给 ClientApp | `TENANT_ADMIN` |

## User Grant

| 方法 | 路径 | 用途 | 权限 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/business-agent/client-apps/{clientAppId}/upstream-users` | 授权 upstream user | `TENANT_ADMIN` |
| `PUT` | `/api/v1/business-agent/client-apps/{clientAppId}/upstream-users/{upstreamUserId}/status` | 启停用户授权 | `TENANT_ADMIN` |

## BusinessObject

| 方法 | 路径 | 用途 | 权限 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/business-agent/business-objects` | 创建业务对象 | `TENANT_ADMIN` |
| `GET` | `/api/v1/business-agent/business-objects/{objectId}` | 查询业务对象 | `TENANT_ADMIN` |
| `PUT` | `/api/v1/business-agent/business-objects/{objectId}` | 更新业务对象 | `TENANT_ADMIN` |

## BusinessFunction / Function Grant

| 方法 | 路径 | 用途 | 权限 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/business-agent/functions/import` | 导入 Function Manifest | `TENANT_ADMIN` |
| `POST` | `/api/v1/business-agent/client-apps/{clientAppId}/function-grants` | 授权函数给 ClientApp | `TENANT_ADMIN` |
| `PUT` | `/api/v1/business-agent/client-apps/{clientAppId}/function-grants/{grantId}/status` | 启停函数授权 | `TENANT_ADMIN` |
| `GET` | `/api/v1/business-agent/client-apps/{clientAppId}/visible-functions` | 查询 ClientApp 可见函数 | `TENANT_ADMIN` |

## Task

| 方法 | 路径 | 用途 | 权限 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/business-agent/tasks` | 创建 Business Task | `TENANT_ADMIN` |
| `GET` | `/api/v1/business-agent/tasks/{taskId}` | 查询 Task | `TENANT_ADMIN` |
| `GET` | `/api/v1/business-agent/sessions/{sessionId}/tasks` | 查询 Session 下的 Task | `TENANT_ADMIN` |

## OpenAPI Account Context Files

| 方法 | 路径 | 用途 | 权限 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/open/accounts/me/context-files` | 查询当前 upstream user 的三层账号上下文文件元数据 | ClientApp runtime access token + upstream user grant |
| `GET` | `/api/v1/open/accounts/me/context-files/{fileName}` | 读取 `ACCOUNT_POLICY.md` / `AGENT.md` / `MEMORY.md` | ClientApp runtime access token + upstream user grant |
| `PUT` | `/api/v1/open/accounts/me/context-files/ACCOUNT_POLICY.md` | 上游 BFF 写入受控账号策略，支持 `expectedSha256` | ClientApp runtime access token + upstream user grant |

请求头：

```http
X-Client-App-Key: <clientAppKey>
X-Client-App-Access-Token: <runtimeAccessToken>
X-Upstream-User-Id: <upstreamUserId>
```

`accounts/me` 由 Navigator 根据 runtime token 与 `X-Upstream-User-Id` 校验，不接受 URL 中传任意 accountId。`AGENT.md` 和 `MEMORY.md` 首段只读；写入能力后续独立开放。响应不得包含物理路径、token、`adapterConfigJson` 或 `manifestJson`。

## OpenAPI Business Agent Sessions

| 方法 | 路径 | 用途 | 权限 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/open/business-agent/sessions` | 查询当前 upstream user 的 Business Agent 会话列表 | ClientApp runtime access token + upstream user grant |
| `GET` | `/api/v1/open/business-agent/sessions/{contextId}/messages` | 读取当前 upstream user 指定会话的消息 | ClientApp runtime access token + upstream user grant |

请求头同 Account Context Files。服务端按 `tenantId + clientAppId + upstreamUserId + contextId` 校验归属，不接受任意 `userId` 查询参数。`POST /api/v1/open/agents/{agentId}/ask` 传入已有 `contextId` 时也执行同一归属预校验，校验失败时不会派发任务。

## Approval / Resume

| 方法 | 路径 | 用途 | 权限 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/business-agent/suspensions/{suspendId}/resume` | Resume 挂起的审批 | `TENANT_ADMIN` |

## Worker Pool 管理（平台级）

| 方法 | 路径 | 用途 | 权限 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/business-agent/worker-identities` | 注册 Worker identity | `SUPER_ADMIN` |
| `GET` | `/api/v1/business-agent/worker-pools` | 查询 Worker Pool 列表 | `TENANT_ADMIN` |
| `POST` | `/api/v1/business-agent/worker-pools` | 创建 Worker Pool | `TENANT_ADMIN` |
| `POST` | `/api/v1/business-agent/worker-pools/{poolId}/members` | 添加 Pool 成员 | `TENANT_ADMIN` |
| `PUT` | `/api/v1/business-agent/worker-pools/{poolId}/status` | 更新 Pool 状态 | `TENANT_ADMIN` |

## Worker Gateway（内部 API — 不对外暴露）

> **⚠️ 以下 API 仅供受信 Worker 调用，不对上游系统开放。**

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/internal/worker-gateway/v1/business-functions` | 查询可见函数摘要 |
| `GET` | `/internal/worker-gateway/v1/business-functions/{functionId}/schema` | 获取函数 schema |
| `POST` | `/internal/worker-gateway/v1/business-functions/{functionId}/invoke` | 调用函数 |
| `POST` | `/internal/worker-gateway/v1/tool-messages` | 上报工具事件 |

## 错误模型

统一错误分类：

| 分类 | 示例 code | 说明 |
| --- | --- | --- |
| `auth` | `auth/unauthorized`, `auth/forbidden`, `auth/not-visible` | 权限不足 |
| `validation` | `validation/schema-invalid` | 入参校验失败 |
| `approval` | `approval/required`, `approval/rejected`, `approval/expired` | 审批相关 |
| `business` | `business/not-found`, `business/conflict` | 业务语义错误 |
| `upstream` | `upstream/timeout`, `upstream/unavailable` | 上游调用失败 |
| `system` | `system/internal-error` | 内部错误 |

## 数据来源

上述 API 路径均从 `business-agent-module` 的 controller 源码核实：

- `ClientAppController` — `/api/v1/client-apps`
- `AdminClientAppController` — `/api/v1/admin/client-apps/provisioning-credentials`
- `ClientAppModelConfigGrantController` — `/api/v1/client-apps/{clientAppId}/model-config-grants`
- `SkillRegistryController` — `/api/v1/business-agent`
- `ClientAppUserGrantController` — `/api/v1/business-agent/client-apps/{clientAppId}/upstream-users`
- `BusinessObjectController` — `/api/v1/business-agent/business-objects`
- `BusinessFunctionRegistryController` — `/api/v1/business-agent`
- `BusinessAgentTaskController` — `/api/v1/business-agent`
- `BusinessFunctionApprovalController` — `/api/v1/business-agent/suspensions`
- `BizWorkerPoolController` — `/api/v1/business-agent`
- `WorkerGatewayController` — `/internal/worker-gateway/v1`
