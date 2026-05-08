# 安全边界

## 文档作用

- doc_type: security-guide
- version: 1.1.3-SNAPSHOT
- status: draft
- date: 2026-05-04
- intended_for: upstream-backend-developer | upstream-frontend-developer | security-reviewer
- purpose: 集中说明 Business Agent 接入的安全边界和约束

## 前端安全边界

上游前端**不得**保存或接触以下 token/credential：

| 凭据 | 说明 | 前端可见？ |
| --- | --- | --- |
| Admin token | 平台管理员令牌 | ❌ 不可见 |
| Provisioning credential | 创建 ClientApp 用的一次性凭据 | ❌ 不可见 |
| Runtime credential (`app_key` + `app_secret`) | ClientApp 运行时凭据 | ❌ 不可见 |
| `task_scoped_token` | Java 签发给 Worker 的内部运行时凭据 | ❌ 不可见 |
| 确认码 | 审批确认码 | ❌ 不可见（确认界面由 Java 控制） |

前端应通过上游 BFF 或平台公开会话 API 与 Navigator 交互。

## LLM 不可见字段

以下字段**不进入** LLM 上下文、Skill 文本或 Worker retained messages：

| 字段 | 说明 |
| --- | --- |
| `task_scoped_token` | 内部运行时凭据，由框架注入 `runtimeContext` |
| `transport` | 底层调用方式（REST path、method、upstream_ref） |
| `adapter` / `adapterConfigJson` | 参数映射、header 注入、响应裁剪 |
| `manifestJson` | 完整 Manifest JSON |
| `auth_scope` | 权限校验域 |
| `idempotency` 细节 | 幂等键模板和策略 |
| `audit` 细节 | 审计业务键和脱敏规则 |
| 确认码 | 审批确认码明文 |
| `approval_token` | 审批令牌 |
| 上游 credential | App Secret、upstream REST 凭证 |

## Upstream User Token 注入

- 上游用户 token 可在 `grantUpstreamUserAccess` 时提交给 Navigator 服务端。
- token 绑定在 `tenantId + clientAppId + upstreamUserId` 授权关系上，控制面 DTO 不返回 token。
- REST Adapter 只有在配置 `foggy.navigator.business.agent.upstreams.<upstream-ref>.user-token-header` 时才注入该 token。
- Manifest 不允许声明或覆盖该 token header，也不允许声明 `X-Navigator-*` 上下文 header。
- 当前推荐 TMS 使用 `X-TMS-Agent-Token`；`Authorization` 仍禁止由 Manifest 写入。

## Worker Gateway 是内部 API

Worker Gateway（`/internal/worker-gateway/v1/**`）是**内部 API**，仅供受信 Biz Worker 调用。

**不对以下方开放**：

- 上游前端
- 上游后端
- 浏览器
- 普通插件

上游系统只与 Java Control Plane 交互（`/api/v1/**`）。

## `adapterConfigJson` / `manifestJson` 不泄露

- Worker Gateway 的 schema 响应（`/internal/worker-gateway/v1/business-functions/{functionId}/schema`）**不返回** `adapterConfigJson` 和 `manifestJson`。
- DTO 清洗在 Stage 4A 已实现，通过 `BusinessFunctionAuthorizationService` 裁剪。
- Worker 只能看到 LLM 可见的裁剪字段：`function_id`、`description_for_llm`、`risk_level`、`input_schema`、`output_schema`、`approval_policy` 摘要。

## `upstream_ref` 防 SSRF 约束

- 上游 REST 地址通过 `upstream_ref`（Java 配置或注册表中的引用名）解析，**不是 LLM 可见的 URL**。
- REST Adapter 实现了基于属性配置的 SSRF 防护：
  - URL 白名单/引用名解析
  - 禁止任意 URL 构造
  - 非 2xx 响应 fail-closed
  - JSON path 评估限制
- LLM 不知道上游 path、header、body 的真实映射。

## Audit 不保存敏感信息

`BusinessFunctionRuntimeAuditEntity` 审计记录**不保存**以下内容：

| 不保存字段 | 原因 |
| --- | --- |
| App Secret / Runtime Credential | 凭证不应进入审计日志 |
| `task_scoped_token` | 内部运行时凭据 |
| 确认码明文 | 安全敏感 |
| `adapterConfigJson` | 包含上游 REST 映射细节 |
| `manifestJson` | 包含完整 Manifest 配置 |
| 上游错误堆栈 / SQL / URL / Header | 防止内部信息泄露 |

审计记录保存的是：业务键、`clientAppId`、`upstreamUserId`、`functionId`、`riskLevel`、`result_status`、`error_code` 等可追踪但不敏感的信息。

## 凭证分离原则

| 凭证 | 用途 | 约束 |
| --- | --- | --- |
| Provisioning credential | 创建 ClientApp | 不能用于运行时调用 |
| Runtime credential | 运行时业务调用 | 不能创建新 App |
| Worker identity token | Worker 注册和心跳 | 不能用于业务调用 |
| Task scoped token | Worker 调 Gateway | 绑定 task/session/skill/过期时间 |

每种凭证**不能**越界使用。

## BusinessObject 不是授权主体

BusinessObject 是用于组织函数的逻辑分组，**不参与授权判定**。授权链仅包含：

1. ClientApp 状态
2. upstream user grant
3. Skill grant + Function allowlist
4. Function grant
5. Model Config grant

## 上游回调安全

- 上游 callback credential 按 `clientAppId` 隔离配置
- 不允许一个上游系统级密钥横跨多个 App
- 回调必须有签名、token 或可信网络边界校验
- Java 校验回调归属的 `clientAppId`、签名和审批状态
