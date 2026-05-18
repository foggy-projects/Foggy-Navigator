# Upstream ClientApp Admin Key Implementation Plan

## 文档作用

- doc_type: implementation-plan
- version: 1.1.3-SNAPSHOT
- status: recorded
- date: 2026-05-18
- priority: P0
- source_type: requirement-implementation-plan
- source_requirement: [33-upstream-client-app-admin-key-request.md](./33-upstream-client-app-admin-key-request.md)
- source_issue: https://github.com/foggy-projects/Foggy-Navigator/issues/121
- intended_for: execution-agent | Navigator maintainer | reviewer | signoff-owner
- purpose: 将上游系统级 ClientApp 管理凭证申请需求拆成可执行阶段、模块职责、代码清单、测试门槛和验收流程

## 总体结论

本需求按分阶段交付，不直接从 CLI 或 UI 开始。

优先级顺序：

1. 先收口安全底座，避免在已有用户/API Key 管理面未受控时引入更高权限凭证。
2. 再实现申请、审批、领取状态机，先跑通后端闭环。
3. 然后补 Navigator admin/operator 工具和上游 CLI。
4. 最后实现多租户上游通过 `NAVI_ADMIN_API_KEY` 管理多个 ClientApp，并签发每个 ClientApp 的 `NAVI_CONTROL_API_KEY`。

首批开工范围建议只包含 Stage 0 和 Stage 1。Stage 2 之后需要在 Stage 1 自检通过后再继续。

## 需求基线

### 已确认语义

- `NAVI_OPERATOR_API_KEY`：Navigator 内部 operator 凭证，只供 Navigator 管理员 Agent 或运维环境使用，用来审批/拒绝申请和签发上游系统级凭证。
- `NAVI_ADMIN_API_KEY`：上游系统级 ClientApp 管理凭证，不是完整 Navigator 租户管理员 key。它只能管理授权 upstream system 与授权租户范围内的 ClientApp。
- `NAVI_CONTROL_API_KEY`：单个 ClientApp 的控制面凭证，只能管理绑定 ClientApp 自己的技能、函数、模型授权、上游用户授权等资源。

### 多租户上游

```text
TMS system admin credential
  -> TMS tenant A -> Navigator ClientApp A -> NAVI_CONTROL_API_KEY A
  -> TMS tenant B -> Navigator ClientApp B -> NAVI_CONTROL_API_KEY B
```

多租户上游可以申请 `NAVI_ADMIN_API_KEY`，用于创建/复用多个 ClientApp 并为每个 ClientApp 签发自己的 `NAVI_CONTROL_API_KEY`。

### 非多租户上游

```text
single upstream system -> Navigator ClientApp -> NAVI_CONTROL_API_KEY
```

非多租户上游默认不分配 `NAVI_ADMIN_API_KEY`，由 Navigator 管理员直接创建 ClientApp 并交付 `NAVI_CONTROL_API_KEY`。

## 非目标

1. 不把 `NAVI_ADMIN_API_KEY` 做成通用 `TENANT_ADMIN` 或 `SUPER_ADMIN`。
2. 不允许上游项目、TMS 后端或上游 LLM 审批自己的申请。
3. 首批不实现 Navigator 管理台 UI 审批页，先交付 API + CLI。
4. 不在数据库中保存可直接还原的明文密钥。
5. 不把 `NAVI_OPERATOR_API_KEY` 写入上游项目 profile。
6. 不在本需求中重构全部用户体系；只处理本链路必须的安全边界。

## 模块职责

| 模块 | 职责 | 当前是否可开工 | 依赖 |
| --- | --- | --- | --- |
| `user-auth-module` | 修复/复核用户与 API Key 管理权限；提供 operator/user API key 解析能力；避免高权限接口裸露 | 是，Stage 0 | 无 |
| `navigator-common` | 放置跨模块共享 DTO/enum/context 字段；只有确需跨模块共享时才新增 | 视实现需要 | Stage 0 设计确认 |
| `navigator-spi` | 如 business-agent 需要调用 user-auth 能力，通过 SPI 扩展，避免模块间直接循环依赖 | 视实现需要 | Stage 0 设计确认 |
| `business-agent-module` | 承载 upstream bootstrap request、admin credential、授权范围校验、ClientApp 管理编排 | Stage 1 后端可开工 | Stage 0 安全底座 |
| `navigator-open-sdk` | 增加申请/审批/领取 API wrapper 与 CLI 命令 | Stage 2 可开工 | Stage 1 API 稳定 |
| `tools/navigator-upstream-cli` | 更新安装包 env 示例、wrapper、使用说明 | Stage 2 可开工 | SDK/CLI 命令完成 |
| `docs/version-tracker/1.1.3-SNAPSHOT` | 需求、计划、进度、验收证据 | 是 | 无 |

## 依赖方向原则

1. 不建议让 `business-agent-module` 直接依赖 `user-auth-module`。
2. 若需要由 business-agent 触发服务用户或 operator key 相关能力，优先在 `navigator-spi` 增加最小接口，由 `user-auth-module` 实现。
3. `NAVI_ADMIN_API_KEY` 的授权策略优先由 `business-agent-module` 管理，因为它的边界是 upstream system、tenant list、ClientApp namespace 和 ClientApp scopes。
4. 如果 `NAVI_ADMIN_API_KEY` 复用 `X-API-Key` header，必须通过 key prefix、resolver 顺序和测试确保它不会被误解析成普通用户 API Key。
5. 新增 `NAVI_ADMIN_API_KEY` 默认按 hash 存储，领取时只返回一次明文。

## Stage 0：安全底座

### 目标

在引入上游系统级管理凭证前，先确保现有用户/API Key 管理面和凭证存储策略不会放大风险。

### 执行项

1. 修复或确认 `/api/v1/users/**` 的访问控制。
   - 用户管理、创建 API Key、撤销 API Key 不能匿名访问。
   - 管理他人用户/API Key 至少需要 `TENANT_ADMIN` 或 `SUPER_ADMIN`。
   - 用户自助能力如果需要保留，必须拆出明确的 self endpoint，不能复用管理端点裸放行。
2. 复核 `AuthInterceptor` 中 `X-API-Key` 解析。
   - 普通用户 API Key、operator API Key、upstream admin key 不得混淆。
   - 失败日志不得包含完整 key。
3. 明确 API Key 存储策略。
   - 新增高权限 key 必须 hash 存储。
   - 既有普通 API Key 明文存储如无法一次性迁移，必须在计划中标注兼容期，并确保新链路不复用明文存储。
4. 定义 operator 凭证最小权限。
   - `UPSTREAM_BOOTSTRAP_REQUEST_APPROVE`
   - `UPSTREAM_CLIENT_APP_ADMIN_KEY_ISSUE`
   - `CLIENT_APP_CONTROL_KEY_ISSUE`
5. 增加安全回归测试。

### 完成标准

- 匿名请求不能调用用户管理或 API Key 管理端点。
- operator/admin endpoint 只能由 Navigator 管理员或 operator 凭证访问。
- 高权限 key 的完整明文不会进入日志、DTO 列表、异常信息或测试输出。

## Stage 1：申请生命周期后端闭环

### 目标

实现无凭证申请、Navigator 侧审批/拒绝、申请方一次性领取的后端状态机。

### 状态机

```text
PENDING -> APPROVED -> CONSUMED
PENDING -> DENIED
PENDING -> EXPIRED
APPROVED -> EXPIRED
```

禁止转换：

- `DENIED -> APPROVED`
- `CONSUMED -> APPROVED`
- `EXPIRED -> APPROVED`
- `CONSUMED -> claim again`

### 建议数据模型

最终类名由执行 agent 按目录结构确定，但语义至少覆盖以下字段。

`upstream_bootstrap_request`：

```text
requestId
requestCodeHash
requestCodeSuffix
claimTokenHash
upstreamSystemId
requestedTenantId
multiTenant
reason
applicantLabel
sourceIpHash
status
requestExpiresAt
approvedAt
approvedByUserId
approvedByOperatorCredentialId
deniedAt
deniedReason
claimExpiresAt
consumedAt
createdAt
updatedAt
```

`upstream_client_app_admin_credential`：

```text
credentialId
credentialKeyHash
credentialKeyPrefix
upstreamSystemId
authorizedTenantIdsJson
authorizedClientAppNamespace
scopesJson
status
expiresAt
revokedAt
lastUsedAt
sourceRequestId
issuedByUserId
issuedByOperatorCredentialId
createdAt
updatedAt
```

### API 草案

Public upstream side：

```http
POST /api/v1/upstream-bootstrap/admin-key-requests
GET  /api/v1/upstream-bootstrap/admin-key-requests/{requestCode}/status
POST /api/v1/upstream-bootstrap/admin-key-requests/{requestCode}/claim
```

Navigator admin/operator side：

```http
GET  /api/v1/admin/upstream-bootstrap-requests
GET  /api/v1/admin/upstream-bootstrap-requests/{requestCode}
POST /api/v1/admin/upstream-bootstrap-requests/{requestCode}/approve
POST /api/v1/admin/upstream-bootstrap-requests/{requestCode}/deny
```

### 执行项

1. 新增 request entity/repository/service。
2. 新增 admin credential entity/repository/service。
3. 新增 public request/status/claim controller。
4. 新增 admin list/show/approve/deny controller。
5. 审批只改变申请状态和授权范围；默认在 claim 阶段生成并一次性返回 `NAVI_ADMIN_API_KEY`。
6. claim 必须校验 request code、claim token、状态、TTL、一次性消费。
7. 审计记录覆盖申请、审批、拒绝、领取、过期、撤销。

### 完成标准

- 未登录上游可以提交申请，但只能得到非密钥信息。
- 上游凭 request code 只能查到安全摘要，不能枚举敏感租户信息。
- 只有 Navigator admin/operator 可以 approve/deny。
- claim 只返回一次明文，过期/拒绝/已消费均不返回。

## Stage 2：Operator/Admin 工具与上游 CLI

### 目标

在后端 API 稳定后，补 SDK wrapper 和 CLI 命令，让上游 LLM 使用受控命令而不是手写 curl。

### 命令草案

上游侧：

```bash
navi upstream admin-key request --upstream-system-id <id> --requested-tenant-id <tenantId> --multi-tenant --write-profile
navi upstream admin-key status
navi upstream admin-key claim --write-profile
```

Navigator 管理侧：

```bash
navi upstream admin-key list --status PENDING
navi upstream admin-key approve --request-code <code> --authorized-tenant-ids <tenantId>
navi upstream admin-key deny --request-code <code> --reason "..."
```

### 执行项

1. `navigator-open-sdk` 增加 public/admin API wrapper。
2. CLI 配置增加 `NAVI_OPERATOR_API_KEY`，并保持 `NAVI_ADMIN_API_KEY`、`NAVI_CONTROL_API_KEY` 脱敏。
3. `claim --write-profile` 默认只写 gitignored profile。
4. 上游 CLI 默认不打印完整 `NAVI_ADMIN_API_KEY`。
5. admin CLI 默认不接收上游 profile 中的 `NAVI_ADMIN_API_KEY` 作为审批凭证。

### 完成标准

- 上游 LLM 可以用 CLI 提交申请、查状态、领取已批准凭证。
- Navigator 管理员 Agent 可以用 operator 凭证 approve/deny。
- CLI 测试证明输出和错误信息不泄露完整 key。

## Stage 3：多租户 ClientApp 管理

### 目标

让获批的 `NAVI_ADMIN_API_KEY` 能在授权范围内管理多个 ClientApp，并为每个 ClientApp 生成自己的 `NAVI_CONTROL_API_KEY`。

### 执行项

1. 定义 upstream system 与 ClientApp 归属关系。
2. 新增或扩展 ClientApp 创建/复用接口，支持 upstream admin credential。
3. 新增或扩展 control credential 签发接口，支持 upstream admin credential 管理授权范围内 ClientApp。
4. 支持多租户 profile 写入策略，例如：

```text
.navigator/tenants/<tenant-code>.env
```

5. 保留非多租户路径：Navigator 管理员直接创建 ClientApp 并交付 `NAVI_CONTROL_API_KEY`。

### 完成标准

- TMS 可以为授权租户 A、B 创建或复用各自 ClientApp。
- A 租户的 `NAVI_CONTROL_API_KEY` 不能管理 B 租户 ClientApp。
- `NAVI_ADMIN_API_KEY` 不能管理其他 upstream system 或未授权租户。
- 轮换/撤销某租户 ClientApp 的 control key 不影响其他租户。

## Stage 4：测试、文档与验收

### 必跑测试

```powershell
mvn test -pl user-auth-module -am
mvn test -pl business-agent-module -am
cd navigator-open-sdk; mvn test
```

如后续改动穿过 launcher 级 Spring wiring，需要补跑：

```powershell
mvn test -pl launcher -am
```

### 测试覆盖

| 层级 | 覆盖内容 |
| --- | --- |
| Unit | 状态机、TTL、claim token、hash 存储、一次性领取、scope 校验 |
| Controller/Security | public request 允许匿名；admin approve/deny 需要 operator/admin；用户/API Key 管理端点不允许匿名 |
| Service | upstream system/tenant/clientApp 授权范围；跨租户和跨 ClientApp 拒绝 |
| SDK/CLI | request/status/claim/admin approve；profile 写入；脱敏输出；错误信息不泄密 |
| Integration | no key -> request -> approve -> claim -> create/reuse ClientApp -> issue `NAVI_CONTROL_API_KEY` |

### 验收门槛

1. Stage 0 通过后才能进入 Stage 1。
2. Stage 1 完成后必须执行轻量实现自检，并更新本文 Progress Tracking。
3. Stage 2 或 Stage 3 完成后必须执行正式 `foggy-implementation-quality-gate`。
4. 最终验收前必须执行 `foggy-test-coverage-audit` 和 `foggy-acceptance-signoff`。

## Code Inventory

| Repo | Path | Role | Expected change | Notes |
| --- | --- | --- | --- | --- |
| current | `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/33-upstream-client-app-admin-key-request.md` | requirement baseline | update | 与实施进度保持同步 |
| current | `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/34-upstream-client-app-admin-key-implementation-plan.md` | implementation plan | create | 本文 |
| current | `user-auth-module/src/main/java/com/foggy/navigator/auth/config/SecurityConfig.java` | security boundary | update | 修复 `/api/v1/users/**` 管理面权限 |
| current | `user-auth-module/src/main/java/com/foggy/navigator/auth/controller/UserController.java` | user/API key management API | update | 增加或拆分 `@RequireAuth` 权限边界 |
| current | `user-auth-module/src/main/java/com/foggy/navigator/auth/interceptor/AuthInterceptor.java` | auth resolver | read-only-analysis / update | 复核 `X-API-Key` 与 operator/upstream admin key 解析边界 |
| current | `user-auth-module/src/main/java/com/foggy/navigator/auth/service/UserAuthServiceImpl.java` | user API key service | update | 普通 API Key 安全修复；必要时支持 operator key |
| current | `navigator-common/src/main/java/com/foggy/navigator/common/entity/ApiKeyEntity.java` | existing API key entity | read-only-analysis / update | 评估明文存储兼容策略 |
| current | `navigator-common/src/main/java/com/foggy/navigator/common/dto/CurrentUser.java` | auth context | read-only-analysis / update | 仅当需要表达 operator 或细粒度 scope 时更新 |
| current | `navigator-common/src/main/java/com/foggy/navigator/common/enums/UserRole.java` | role constants | read-only-analysis / update | 避免把 upstream admin key 误做完整 tenant admin |
| current | `navigator-spi/src/main/java/com/foggy/navigator/spi/auth/UserAuthService.java` | cross-module auth SPI | read-only-analysis / update | 如需 business-agent 调用用户/key 能力，在此扩展 |
| current | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/controller` | bootstrap/admin controllers | create | public request/status/claim 与 admin approve/deny |
| current | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service` | lifecycle and authorization services | create / update | request 状态机、admin credential 校验、ClientApp 编排 |
| current | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/model/entity` | JPA entities | create | request 与 upstream admin credential 实体 |
| current | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/model/form` | API request forms | create | request/approve/deny/claim forms |
| current | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/model/dto` | API response DTOs | create | status、安全摘要、一次性 claim result |
| current | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/repository` | repositories | create | request/admin credential repositories |
| current | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/ClientAppService.java` | existing ClientApp lifecycle | update | Stage 3 复用 create/runtime/control credential 能力 |
| current | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/ClientAppControlCredentialService.java` | existing control key resolver | update | Stage 3 强化 ClientApp scope 校验复用 |
| current | `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/api/BusinessAgentApi.java` | SDK wrapper | update | 增加 bootstrap request/admin key API 方法或拆出新 API class |
| current | `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/NavigatorClient.java` | SDK client builder | update | 支持 operator key 或 admin bootstrap API 入口 |
| current | `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/internal/HttpHelper.java` | SDK headers | update | 支持 `NAVI_OPERATOR_API_KEY` 对应 header，保持脱敏 |
| current | `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/cli/UpstreamCli.java` | CLI commands | update | upstream request/status/claim/bootstrap 与 admin approve/deny |
| current | `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/cli/UpstreamCliConfig.java` | CLI config/profile | update | 新增 `NAVI_OPERATOR_API_KEY` 与 claim token 脱敏 |
| current | `tools/navigator-upstream-cli/.env.example` | CLI env example | update | 增加 operator/admin/bootstrap 字段说明 |
| current | `business-agent-module/src/test/java` | backend tests | create / update | 状态机、授权范围、跨租户拒绝 |
| current | `user-auth-module/src/test/java` | auth tests | create / update | 用户/API Key 管理端点安全回归 |
| current | `navigator-open-sdk/src/test/java` | SDK/CLI tests | create / update | CLI 命令、profile 写入、脱敏输出 |

## 执行顺序 Checklist

### Gate A：Stage 0 完成

- [x] `/api/v1/users/**` 权限修复或复核完成。
- [x] 普通 API Key、operator key、upstream admin key 解析边界确认。
- [x] 新增高权限 key hash 存储策略确认。
- [x] `user-auth-module` 测试通过。
- [x] Progress Tracking 已更新。

### Gate B：Stage 1 完成

- [x] 申请状态机实现完成。
- [x] public request/status/claim API 实现完成。
- [x] admin approve/deny API 实现完成。
- [x] claim token、TTL、一次性消费测试通过。
- [x] 无完整 key 进入日志或 DTO 列表。
- [x] `business-agent-module` 测试通过。
- [x] 轻量实现自检完成。

### Gate C：Stage 2 完成

- [x] SDK wrapper 完成。
- [x] 上游 CLI request/status/claim 完成。
- [x] Navigator admin/operator CLI list/approve/deny 完成。
- [x] CLI 脱敏与 gitignored profile 写入测试通过。
- [x] `navigator-open-sdk` 测试通过。
- [x] 正式实现质量检查完成；Stage 4 已完成发现项修复，当前结论为 ready-for-coverage-audit。

### Gate D：Stage 3 完成

- [x] `NAVI_ADMIN_API_KEY` 能管理授权范围内多个 ClientApp。
- [x] `NAVI_ADMIN_API_KEY` 能为每个 ClientApp 签发 `NAVI_CONTROL_API_KEY`。
- [x] 非多租户直接交付 `NAVI_CONTROL_API_KEY` 路径保留。
- [x] 跨租户、跨 upstream system、跨 ClientApp 拒绝。
- [x] SDK/CLI profile 写入不打印完整高权限 key。
- [x] 服务级端到端 bootstrap evidence 通过：request -> approve -> claim -> ensure ClientApp -> issue `NAVI_CONTROL_API_KEY`。
- [x] Live HTTP/CLI smoke 通过：重启 launcher 后以真实 HTTP 执行 request -> approve -> claim -> ensure ClientApp -> issue `NAVI_CONTROL_API_KEY`。
- [x] 正式实现质量检查完成；Stage 4 已完成发现项修复，当前结论为 ready-for-coverage-audit。

### Gate E：最终验收

- [x] 测试覆盖审计完成。
- [x] 验收签收完成。
- [x] 需求文档、实施计划、使用文档、CLI 示例全部同步。

## Progress Tracking

### Development Progress

| Stage | Status | Notes |
| --- | --- | --- |
| Stage 0：安全底座 | completed | 已收口用户/API Key 管理面权限；API Key 元信息查询不返回明文；高权限 key 在 Stage 1/3 按 hash 存储策略实现 |
| Stage 1：申请生命周期后端闭环 | completed | 已实现 request/status/claim、admin approve/deny、operator key 校验、hash 存储和审计记录 |
| Stage 2：Operator/Admin 工具与上游 CLI | completed | 已实现 SDK wrapper、上游 request/status/claim、operator list/approve/deny、profile 写入与脱敏测试 |
| Stage 3：多租户 ClientApp 管理 | completed | 已实现 upstream admin key 专用鉴权、ClientApp 创建/复用、control key 签发、SDK/CLI 命令与测试 |
| Stage 4：测试、文档与验收 | completed | 质量闸门、服务级端到端 bootstrap、live HTTP/CLI smoke、覆盖审计与正式验收签收均已完成 |

### Testing Progress

| Test area | Status | Notes |
| --- | --- | --- |
| Auth/security regression | passed | `mvn test -pl user-auth-module -am`，68 tests passed |
| Request lifecycle and ClientApp admin tests | passed | `mvn test -pl business-agent-module -am`，284 tests passed |
| SDK/CLI tests | passed | `mvn test -pl navigator-open-sdk`，78 tests passed |
| Multi-tenant isolation tests | passed | 覆盖授权租户、upstream system/namespace、ClientApp 归属校验，以及 upstream admin key 专用 header |
| End-to-end bootstrap test | passed | `UpstreamBootstrapEndToEndServiceTest#requestApproveClaimEnsureClientAppAndIssueControlKey` 已覆盖服务级 request -> approve -> claim -> ensure -> issue；live HTTP/CLI smoke 已在 `temp\navi-admin-smoke\run-20260518123552` 通过 |

### Experience Progress

experience: N/A

原因：首批计划不包含管理台 UI。若后续新增审批页面，必须补 Navigator 管理台体验检查清单与 Playwright evidence。

### Stage 0 Execution Check-in

completed work:

- `UserController` 增加 `@RequireAuth` 与资源级权限校验，限制用户/API Key 管理接口只能由本人、同租户管理员或超级管理员按场景访问。
- `UserAuthService` 增加 API Key 元信息查询 SPI，`UserAuthServiceImpl` 列表和元信息查询均不返回明文 API Key。
- `SecurityConfig` 保持 `/api/v1/users/**` 由 `AuthInterceptor + @RequireAuth` 保护，避免 Spring Security 在没有 JWT Authentication filter 的情况下误拦截有效 token。
- 新增 `UserControllerAuthorizationTest`，补充 `UserAuthServiceTest` 明文不返回断言。

self-check:

- 普通用户 API Key 仍只代表用户身份，不承担 operator/upstream admin 语义。
- `NAVI_OPERATOR_API_KEY` 与 `NAVI_ADMIN_API_KEY` 后续不得复用普通用户 API Key 表的明文存储模型；新增高权限 key 必须按 hash 存储、脱敏展示、一次性明文交付。

test evidence:

- `mvn test -pl user-auth-module -am` passed，68 tests passed。

### Stage 1 Execution Check-in

completed work:

- 新增 upstream bootstrap request、upstream client app admin credential、bootstrap audit 三类实体和 repository。
- 新增公共 API：`POST /api/v1/upstream-bootstrap/admin-key-requests`、`GET /api/v1/upstream-bootstrap/admin-key-requests/{requestCode}/status`、`POST /api/v1/upstream-bootstrap/admin-key-requests/{requestCode}/claim`。
- 新增管理 API：`GET/GET by code/approve/deny /api/v1/admin/upstream-bootstrap-requests`，由管理员会话或 operator key 手动鉴权。
- `NAVI_OPERATOR_API_KEY` 通过 `X-Navi-Operator-Key` 或 `X-Navi-Operator-Api-Key` 传入；服务端只配置 hash：`foggy.navigator.operator.api-key-hash`、`foggy.navigator.business-agent.operator.api-key-hash` 或 `NAVI_OPERATOR_API_KEY_SHA256`。hash 格式与 `SecretTokenSupport.sha256` 一致：SHA-256 bytes -> URL-safe Base64 without padding，不是 hex。
- `NAVI_ADMIN_API_KEY` 在 claim 阶段生成，hash 入库，只在领取响应中返回一次明文。

self-check:

- public status DTO 不返回 requestId、requestedTenantId、reason、授权租户、scopes 或任何完整 key。
- approve/deny 接口不使用申请生成的 `NAVI_ADMIN_API_KEY` 审批，只接受 Navigator 管理员会话或 operator key。
- tenant admin 只能审批本租户申请，operator/super admin 可跨租户审批；多租户授权范围在 Stage 3 继续用于 ClientApp 管理隔离。

test evidence:

- `mvn test -pl business-agent-module -am` passed，284 tests passed。
- `mvn test -pl user-auth-module -am` passed，68 tests passed。

### Stage 2 Execution Check-in

completed work:

- `navigator-open-sdk` 新增 bootstrap request/status/claim 与 admin list/approve/deny API wrapper。
- `NavigatorClient` 与 `HttpHelper` 支持 `operatorApiKey`，通过 `X-Navi-Operator-Key` 传递 Navigator 内部 operator 凭证。
- `navi upstream admin-key request/status/claim/list/approve/deny` 已落地；request/claim 必须 `--write-profile`，避免一次性 claim token 或 `NAVI_ADMIN_API_KEY` 完整打印到控制台。
- CLI 配置支持 `NAVI_OPERATOR_API_KEY`、`NAVI_ADMIN_KEY_REQUEST_CODE`、`NAVI_ADMIN_KEY_CLAIM_TOKEN`，并继续脱敏 `NAVI_ADMIN_API_KEY` 与 `NAVI_CONTROL_API_KEY`。
- 既有 control-plane CLI/E2E fallback 不再把 `NAVI_ADMIN_API_KEY` 当普通 `X-API-Key` 使用；审批只接受 `NAVI_OPERATOR_API_KEY` 或 `NAVI_ADMIN_TOKEN`。

self-check:

- 上游项目 profile 不应写入 `NAVI_OPERATOR_API_KEY`；该 key 只供 Navigator 管理员 Agent 或运维环境使用。
- `NAVI_ADMIN_API_KEY` 已从既有 control-plane fallback 中移除，避免与普通用户 API Key 或 operator key 混淆。
- Stage 2 只完成申请、审批、领取工具；`NAVI_ADMIN_API_KEY` 管理多个 ClientApp 和签发 `NAVI_CONTROL_API_KEY` 仍属于 Stage 3。

test evidence:

- `mvn test -pl navigator-open-sdk` passed，78 tests passed。

### Stage 3 Execution Check-in

completed work:

- `business-agent-module` 新增 upstream admin credential 专用鉴权：`X-Navi-Admin-Key`，兼容别名 `X-Navi-Admin-Api-Key`，不复用普通 `X-API-Key`。
- `ClientApp` 增加 `upstreamSystemId`、`upstreamClientAppNamespace`、`upstreamRef` 归属字段，用于区分同一多租户上游系统下的不同租户 ClientApp。
- 新增 `POST /api/v1/upstream-admin/client-apps/ensure` 和 `GET /api/v1/upstream-admin/client-apps`，支持授权范围内创建/复用和查询 ClientApp。
- 新增 `POST /api/v1/upstream-admin/client-apps/{clientAppId}/control-credentials`，允许获批上游系统为授权范围内 ClientApp 签发 `NAVI_CONTROL_API_KEY`。
- `navigator-open-sdk` 支持 `upstreamAdminApiKey`，CLI 增加 `navi upstream client-app list/ensure/issue-control-key`。
- CLI 支持 `--tenant-profile .navigator/tenants/<tenant-code>.env --write-profile`，完整 `NAVI_CONTROL_API_KEY` 只写入 gitignored profile，不打印到控制台。

self-check:

- `NAVI_ADMIN_API_KEY` 只管理其 `upstreamSystemId + authorizedClientAppNamespace + authorizedTenantIds + scopes` 范围内的 ClientApp。
- 管理多个 ClientApp 和签发 control key 均走 upstream admin 专用 header；正常 ClientApp 控制面命令仍使用 `NAVI_CONTROL_API_KEY`。
- 非多租户路径未被移除，Navigator 管理员仍可直接创建 ClientApp 并交付单个 `NAVI_CONTROL_API_KEY`。
- Stage 3 已完成实现和模块测试；质量闸门发现项已在 Stage 4 修复，服务级端到端 bootstrap、live HTTP/CLI smoke、覆盖审计与验收签收已完成。

test evidence:

- `mvn test -pl business-agent-module -am` passed，284 tests passed。
- `mvn test -pl navigator-open-sdk` passed，78 tests passed。

### Stage 4 Quality Remediation Check-in

completed work:

- `CLIENT_APP_ADMIN`、`CONTROL_KEY_ISSUE` 审批 scope 别名已在服务端规范化为 `CLIENT_APP_MANAGE`、`CLIENT_APP_CONTROL_KEY_ISSUE`，并补充别名授权测试。
- `NAVI_ADMIN_API_KEY` 未显式传入到期时间时默认 24 小时过期；credential 校验增加 `revokedAt` fail-closed；新增 revoke/rotate API、SDK、CLI 和审计记录。
- SDK upstream admin 显式凭证路径改为只发送 `X-Navi-Admin-Key`，不混入默认 `X-API-Key`。
- 新增服务级端到端 bootstrap evidence，覆盖 request -> approve -> claim -> ensure ClientApp -> issue `NAVI_CONTROL_API_KEY`，并断言跨租户拒绝。
- 使用文档、需求文档、实施计划与质量报告已同步撤销/轮换、默认 TTL、scope 别名和测试证据。

test evidence:

- `mvn test -pl business-agent-module -am` passed，284 tests passed。
- `mvn test -pl business-agent-module -am "-Dtest=UpstreamBootstrapEndToEndServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false"` passed，1 test passed。
- `mvn test -pl navigator-open-sdk` passed，78 tests passed。
- `mvn test -pl user-auth-module -am` passed，68 tests passed。
- `git diff --check` passed；仅输出既有 LF/CRLF warning，无 whitespace error。

### Stage 4 Live HTTP/CLI Smoke Check-in

completed work:

- 重新构建 `launcher/target/launcher-1.0.0-SNAPSHOT.jar` 并重启本地 launcher。
- 使用 `NAVI_OPERATOR_API_KEY` 对应的服务端 hash 启动 launcher；hash 格式按 `SecretTokenSupport.sha256`：SHA-256 bytes -> URL-safe Base64 without padding。
- 通过 `navigator-open-sdk` CLI 对 `http://localhost:8112` 执行真实 HTTP 链路：config check -> admin-key request -> status -> operator list -> approve -> status -> claim -> client-app ensure -> client-app list -> issue-control-key。
- 完整 profile 只写入 `temp\navi-admin-smoke\run-20260518123552`，未在文档或输出中记录完整 `NAVI_ADMIN_API_KEY` 或 `NAVI_CONTROL_API_KEY`。

test evidence:

- `docs/version-tracker/1.1.3-SNAPSHOT/coverage/upstream-client-app-admin-key-live-http-cli-smoke.md` passed。
- Launcher PID `61032` 监听 `8112`；日志确认 MySQL Hikari 连接、Hibernate/JPA 初始化和 Tomcat 启动。

schema policy:

- 本需求涉及的新增表/字段属于 additive schema change；所有环境按项目规则使用 `ddl-auto:update` 自动创建或更新。
- 只有删除字段、重命名字段、字段语义迁移或数据迁移等 `ddl-auto:update` 不覆盖的变更，才需要单独 SQL migration 或迁移计划。

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted
- signed_off_by: Codex execution-agent
- signed_off_at: 2026-05-18
- acceptance_record: docs/version-tracker/1.1.3-SNAPSHOT/acceptance/upstream-client-app-admin-key-acceptance.md
- blocking_items: none
- follow_up_required: no

## 当前状态

Stage 0、Stage 1、Stage 2、Stage 3 和 Stage 4 均已完成。正式实现质量检查发现项已修复，记录见 `docs/version-tracker/1.1.3-SNAPSHOT/quality/upstream-client-app-admin-key-implementation-quality.md`；覆盖审计记录见 `docs/version-tracker/1.1.3-SNAPSHOT/coverage/upstream-client-app-admin-key-coverage-audit.md`；live HTTP/CLI smoke 记录见 `docs/version-tracker/1.1.3-SNAPSHOT/coverage/upstream-client-app-admin-key-live-http-cli-smoke.md`；验收记录见 `docs/version-tracker/1.1.3-SNAPSHOT/acceptance/upstream-client-app-admin-key-acceptance.md`。

后续若出现删除字段、重命名字段或数据迁移，再按变更另补 SQL migration 或迁移计划；本次 additive schema change 走 `ddl-auto:update`。
