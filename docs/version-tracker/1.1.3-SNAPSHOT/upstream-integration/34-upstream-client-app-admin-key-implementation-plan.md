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
navi upstream admin-key request
navi upstream admin-key status
navi upstream admin-key claim
navi upstream admin-key bootstrap
```

Navigator 管理侧：

```bash
navi admin upstream-bootstrap requests list
navi admin upstream-bootstrap requests show
navi admin upstream-bootstrap requests approve
navi admin upstream-bootstrap requests deny
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

- [ ] `/api/v1/users/**` 权限修复或复核完成。
- [ ] 普通 API Key、operator key、upstream admin key 解析边界确认。
- [ ] 新增高权限 key hash 存储策略确认。
- [ ] `user-auth-module` 测试通过。
- [ ] Progress Tracking 已更新。

### Gate B：Stage 1 完成

- [ ] 申请状态机实现完成。
- [ ] public request/status/claim API 实现完成。
- [ ] admin approve/deny API 实现完成。
- [ ] claim token、TTL、一次性消费测试通过。
- [ ] 无完整 key 进入日志或 DTO 列表。
- [ ] `business-agent-module` 测试通过。
- [ ] 轻量实现自检完成。

### Gate C：Stage 2 完成

- [ ] SDK wrapper 完成。
- [ ] 上游 CLI request/status/claim 完成。
- [ ] Navigator admin CLI approve/deny 完成。
- [ ] CLI 脱敏与 gitignored profile 写入测试通过。
- [ ] `navigator-open-sdk` 测试通过。
- [ ] 正式实现质量检查完成。

### Gate D：Stage 3 完成

- [ ] `NAVI_ADMIN_API_KEY` 能管理授权范围内多个 ClientApp。
- [ ] 非多租户直接交付 `NAVI_CONTROL_API_KEY` 路径保留。
- [ ] 跨租户、跨 upstream system、跨 ClientApp 拒绝。
- [ ] 端到端 bootstrap 测试通过。
- [ ] 正式实现质量检查完成。

### Gate E：最终验收

- [ ] 测试覆盖审计完成。
- [ ] 验收签收完成。
- [ ] 需求文档、实施计划、使用文档、CLI 示例全部同步。

## Progress Tracking

### Development Progress

| Stage | Status | Notes |
| --- | --- | --- |
| Stage 0：安全底座 | pending | 下一步建议从这里开工 |
| Stage 1：申请生命周期后端闭环 | pending | 依赖 Stage 0 |
| Stage 2：Operator/Admin 工具与上游 CLI | pending | 依赖 Stage 1 API 稳定 |
| Stage 3：多租户 ClientApp 管理 | pending | 依赖 Stage 2 CLI/API |
| Stage 4：测试、文档与验收 | pending | 每阶段都需持续更新 |

### Testing Progress

| Test area | Status | Notes |
| --- | --- | --- |
| Auth/security regression | pending | Stage 0 必须补 |
| Request lifecycle tests | pending | Stage 1 必须补 |
| SDK/CLI tests | pending | Stage 2 必须补 |
| Multi-tenant isolation tests | pending | Stage 3 必须补 |
| End-to-end bootstrap test | pending | Stage 3/4 必须补 |

### Experience Progress

experience: N/A

原因：首批计划不包含管理台 UI。若后续新增审批页面，必须补 Navigator 管理台体验检查清单与 Playwright evidence。

## 当前开工建议

下一步只执行 Stage 0：

1. 修复或复核 `/api/v1/users/**` 管理面权限。
2. 确认 operator/upstream admin key 不复用普通用户 API Key 的完整权限语义。
3. 先补安全回归测试，再进入 Stage 1 后端申请生命周期。
