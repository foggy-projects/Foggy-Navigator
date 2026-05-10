# Upstream Auto Bootstrap Contract

## 文档作用

- doc_type: integration-contract
- version: 1.1.3-SNAPSHOT
- status: draft
- date: 2026-05-05
- intended_for: upstream-llm-coding-agent | upstream-backend-developer | navigator-reviewer
- purpose: 将上游 Business Agent 接入从手工提示词操作收敛为可重复执行的 bootstrap 契约，支持 TMS 等业务系统自动注册对象、函数、授权和联调检查

## 目标

上游 LLM 不应长期手工拼接 Navigator REST 请求。推荐方式是：

1. 上游仓库维护一个**非敏感 manifest**，描述 BusinessObject、Skill、Function、REST Adapter 映射和 LLM-facing schema。
2. 上游本地或 CI 环境维护一个**本地 secret env**，保存 Navigator admin key、tenant、model config、worker pool 和上游用户 token。
3. 上游后端提供一个**bootstrap runner**，通过 `navigator-open-sdk` 自动创建或复用 Navigator 控制面资源。
4. Navigator 侧通过 E2E 或真实 Worker 链路验证 REST Adapter 调用，不把 Worker Gateway 暴露给上游。

## 自动化边界

| 步骤 | 上游 bootstrap 可自动处理 | Navigator 内部处理 | 人工确认 |
| --- | --- | --- | --- |
| 安装 `navigator-open-sdk` | yes | no | 首次本地环境 |
| 读取 manifest/env | yes | no | env 值来源 |
| 创建/复用 ClientApp | yes | no | 首次授权策略 |
| 注册 BusinessObject | yes | no | 命名规范 |
| 导入 BusinessFunction | yes | no | schema 审核 |
| Skill allowlist | yes | no | Skill 范围 |
| Function/Skill/User/Model grant | yes | no | 授权范围 |
| 保存上游用户 token | yes，服务端提交 | no | token 来源 |
| 创建 BusinessAgentTask | yes | no | worker pool/model config |
| 调 Worker Gateway | no | yes | 不允许上游直接调用 |
| 真实 Worker invoke 验证 | no | yes | 联调窗口 |

## Upstream User Grant 策略

上游系统不应在启动或初始化时枚举系统内所有用户并批量创建 Navigator upstream-user grant。推荐策略是：

1. bootstrap runner 只创建或复用应用级资源，例如 ClientApp、runtime credential、BusinessObject、Function、Skill、Function/Skill/Model grant。
2. BFF 在当前登录用户第一次发起 Navigator Agent 对话或业务 task 前，按当前登录态执行一次 `ensureUpstreamUserGrant`。
3. `ensureUpstreamUserGrant` 只处理当前用户，不枚举全量用户；它以 `tenantId + clientAppId + upstreamUserId` 为幂等键，调用 `grantUpstreamUserAccess` upsert 为 `ENABLED`。
4. 如果业务函数需要调用上游 REST API，BFF 应同时提交当前用户可用的上游用户 token；该 token 只进入 Navigator 服务端 grant 存储，不进入前端、LLM、manifest、日志或返回 DTO。
5. Navigator OpenAPI `ask` 阶段仍保持 fail-closed：缺少 upstream-user grant 时拒绝请求，不自动创建授权。

推荐 BFF 流程：

```text
Browser sends message
  -> Upstream BFF resolves current logged-in user
  -> BFF derives upstreamUserId and current user token
  -> BFF calls Navigator control plane ensureUpstreamUserGrant
  -> BFF exchanges ClientApp runtime credential for short-lived access token
  -> BFF calls Navigator OpenAPI ask with X-Upstream-User-Id
  -> Browser polls BFF every 4s for task messages
```

该设计避免启动期全量预授权，同时不降低 Navigator 对 ClientApp/upstreamUser 绑定关系的校验强度。

## 文件拆分

### 非敏感 manifest

建议上游项目提交：

```text
docs/navigator-agent/navigator-agent.manifest.json
```

manifest 可以包含：

- `clientAppId` 或 `clientAppName`
- `businessObject`
- `skill`
- `functions`
- `upstreamRef`
- REST path/method/body mapping
- `orderIdentifier` 等 LLM-facing schema

manifest 不得包含：

- Navigator admin api key
- provisioning/runtime credential
- ClientApp secret
- upstream user token
- task scoped token
- `adapterConfigJson` 的对外泄露副本
- 内部主键，如 TMS 的 `expressOrderId` / `esOrderId`

### 本地 secret env

建议上游项目只提交模板：

```text
docs/navigator-agent/navigator-agent.env.example
```

真实值放在 gitignored 文件：

```text
docs/navigator-agent/navigator-agent.local.env
```

env 至少包含：

```properties
NAVIGATOR_BASE_URL=http://localhost:8112
# Use one of the following:
NAVIGATOR_ADMIN_API_KEY=
NAVIGATOR_ADMIN_TOKEN=
NAVIGATOR_TENANT_ID=
NAVIGATOR_ACTOR_USER_ID=
NAVIGATOR_MODEL_CONFIG_ID=
NAVIGATOR_WORKER_POOL_ID=
TMS_WEB_BASE_URL=http://localhost:12581
TMS_UPSTREAM_USER_ID=
TMS_USER_TOKEN=
```

## Bootstrap Runner 职责

上游 runner 可以是 JUnit integration test、Spring Boot command、Maven exec main 或独立 CLI。推荐 Java 项目优先使用 `navigator-open-sdk`。

runner 需要执行：

1. 读取 env，缺失敏感值时 fail-fast。
   - `NAVIGATOR_ADMIN_API_KEY` 用于 `sk-*` API key。
   - `NAVIGATOR_ADMIN_TOKEN` 用于当前登录态/admin JWT。
   - 两者至少提供一个；如果提供的是 JWT，必须走 `adminToken(...)`，不要走 `apiKey(...)`。
2. 读取 manifest，校验不含 `expressOrderId`、`esOrderId`、`task_scoped_token`。
3. 安装或检查 `navigator-open-sdk` 依赖。
4. 初始化 SDK：

```java
NavigatorClient client = NavigatorClient.builder()
        .baseUrl(navigatorBaseUrl)
        .apiKey(navigatorAdminApiKey)      // X-API-Key: sk-*
        .build();
```

或在本地 dev 环境使用登录态/admin JWT：

```java
NavigatorClient client = NavigatorClient.builder()
        .baseUrl(navigatorBaseUrl)
        .adminToken(navigatorAdminToken)   // Authorization: Bearer <token>
        .build();
```

5. 创建或复用 ClientApp。
6. 创建 BusinessObject。
7. 导入 BusinessFunction manifest。
8. 创建或复用 Skill。
9. 添加 Skill Function allowlist。
10. 授权 Function / Skill / model config。upstream user grant 可只为联调种子用户执行；真实用户应由 BFF 在当前用户发起对话前按需 ensure。
11. 创建 BusinessAgentTask。
12. 输出非敏感报告。

runner 不得执行：

1. 调用 `/internal/worker-gateway/v1/**`。
2. 打印或落盘 token 明文。
3. 把上游用户 token 写入 manifest。
4. 让 LLM 或前端提供任意 REST URL。
5. 让 Manifest 覆盖 `Authorization`、`X-TMS-Agent-Token` 或 `X-Navigator-*` header。

## TMS 推荐 Manifest 摘要

TMS v3.0.5 最小真实联调建议注册：

| Function | Path | Risk | Approval |
| --- | --- | --- | --- |
| `tms.order.getSummary` | `/x3-agent/tms/order/summary` | `LOW` | `false` |
| `tms.order.getExecutionStatus` | `/x3-agent/tms/order/execution-status` | `LOW` | `false` |
| `tms.finance.getPaymentSummary` | `/x3-agent/tms/finance/payment-summary` | `LOW` | `false` |

三者 input schema 只允许：

```json
{
  "type": "object",
  "required": ["orderIdentifier"],
  "properties": {
    "orderIdentifier": {
      "type": "string",
      "description": "TMS public order number visible to users and LLMs"
    }
  },
  "additionalProperties": false
}
```

## 非敏感验收输出

runner 或联调报告只输出：

1. Navigator base URL。
2. upstream ref 与 TMS base URL，不含 token。
3. ClientApp id、Skill id、BusinessObject id、Function ids、Task id。
4. 三个函数注册/授权/task 创建是否成功。
5. TMS 侧是否收到受控 headers 的布尔结果。
6. schema/invoke/audit DTO 是否不含敏感字段的布尔结果。
7. 失败阶段、HTTP 状态、非敏感错误信息。

## 推荐交付顺序

1. 上游先提交 manifest/env.example/bootstrap runner。
2. 本地使用当前 Navigator 开发环境验证 bootstrap。
3. Navigator 侧运行真实 REST Adapter E2E 或 Worker invoke。
4. 通过后再拆正式联调环境和正式 skill。

## TMS P1 Approval-Required 联调补充

`tms.fulfillment.selfPickupSign` 属于 approval-required 状态变更函数。上游 bootstrap 仍只负责注册函数、授权 upstream user token、创建 task；业务函数执行必须由 Navigator Java Gateway/Suspension service 在审批通过后执行，上游不得直接调用 Navigator `/internal/worker-gateway/v1/**`。

本地 Navigator 启动建议：

```powershell
$env:JAVA_TOOL_OPTIONS = '-Dfoggy.navigator.business.agent.upstreams.tms-x3-agent.url=http://localhost:12580 -Dfoggy.navigator.business.agent.upstreams.tms-x3-agent.user-token-header=X-TMS-Agent-Token'
.\start-launcher.ps1
```

TMS 本地联调依赖：

```text
Basic baseUrl: http://localhost:10001
TMS Web baseUrl: http://localhost:12580
X-Tenant-Id: 88800
```

测试 StaffSessionToken 通过 Basic 测试签发接口获取，token 字段路径为 `data.sessionTokenId`。该值只允许进入 Navigator upstream-user credential 的服务端存储或当前本地 shell 运行态，不得写入 manifest、LLM prompt、前端 DTO、日志或联调报告。

生成可签收新单：

```bash
curl -X POST "http://localhost:12580/api/test/business-agent/orders/self-pickup-sign-ready" \
  -H "Authorization: Bearer <TMS_STAFF_SESSION_TOKEN>" \
  -H "X-Tenant-Id: 88800" \
  -H "Content-Type: application/json" \
  -d '{"scenario":"SELF_PICKUP_SIGN_P1","requestedBy":"navigator-p1-test","remark":"Navigator Business Agent approval-required retest"}'
```

readiness:

```bash
curl -X GET "http://localhost:12580/api/test/business-agent/orders/<orderIdentifier>/self-pickup-sign-readiness" \
  -H "Authorization: Bearer <TMS_STAFF_SESSION_TOKEN>" \
  -H "X-Tenant-Id: 88800"
```

Navigator 调用 `tms.fulfillment.selfPickupSign` 时，LLM/tool input 仍只能包含：

```json
{"orderIdentifier":"<fresh-orderIdentifier>"}
```

验收期望：

1. fresh order readiness 为 `ready=true`。
2. Gateway invoke 返回 `SUSPENDED`。
3. 首次 approve/resume 后，Java suspension service 调 TMS 一次，TMS 返回 `code=200`。
4. 重复 approve/resume 不重复调用 TMS。
5. `INVOKE_SUCCESS`、`BUSINESS_EXECUTION_REQUESTED/SKIPPED` 等 suspension 相关审计记录可通过 `suspendId` 串联。
