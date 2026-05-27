# Upstream ClientApp Admin Key Request

## 文档作用

- doc_type: workitem
- version: 1.1.3-SNAPSHOT
- status: recorded
- date: 2026-05-17
- priority: P0
- source_type: requirement
- source_issue: https://github.com/foggy-projects/Foggy-Navigator/issues/121
- implementation_plan: [34-upstream-client-app-admin-key-implementation-plan.md](./34-upstream-client-app-admin-key-implementation-plan.md)
- intended_for: Navigator maintainer | upstream backend developer | upstream LLM coding agent | reviewer
- purpose: 记录上游按系统/租户申请 ClientApp 管理凭证的需求、审批权限边界、CLI/API 契约与验收标准

## 背景

上游系统接入 Navigator 时需要维护 ClientApp、ClientApp 控制面凭证和运行时凭证。对于非多租户上游系统，Navigator 管理员可以直接创建一个 ClientApp 并交付 `NAVI_CONTROL_API_KEY`。

对于 TMS 这类多租户上游系统，一个上游系统会在 Navigator 侧注册和维护多个 ClientApp。此时仅交付单个 `NAVI_CONTROL_API_KEY` 不够，上游需要一个系统级管理凭证来为不同租户创建或维护各自的 ClientApp，并签发各自的 `NAVI_CONTROL_API_KEY`。

## 术语与权限模型

### NAVI_ADMIN_API_KEY

`NAVI_ADMIN_API_KEY` 在本需求中不是通用 Navigator 租户管理员凭证，而是上游系统级 ClientApp 管理凭证。

它只允许管理授权范围内的 ClientApp：

```text
upstreamSystemId
authorizedNavigatorTenantIds
authorizedClientAppNamespace
allowedAdminScopes
expiresAt
revocationStatus
auditSubject
```

允许的典型能力：

- 创建或复用该上游系统名下的 ClientApp。
- 为这些 ClientApp 签发或轮换 `NAVI_CONTROL_API_KEY`。
- 为这些 ClientApp 签发或轮换 runtime credential。
- 查询该上游系统名下 ClientApp 的接入状态和审计摘要。

不允许的能力：

- 管理其他上游系统的 ClientApp。
- 管理未授权 Navigator 租户。
- 变更 Navigator 全局配置、用户、租户或公共资源。
- 作为 `SUPER_ADMIN` 或完整 `TENANT_ADMIN` 使用。

### NAVI_CONTROL_API_KEY

`NAVI_CONTROL_API_KEY` 永远绑定到单个 ClientApp。它只能管理该 ClientApp 自己的技能、函数、模型授权、上游用户授权等资源，不能跨 ClientApp。

### 多租户映射

```text
TMS system admin credential
  -> TMS tenant A -> Navigator ClientApp A -> NAVI_CONTROL_API_KEY A
  -> TMS tenant B -> Navigator ClientApp B -> NAVI_CONTROL_API_KEY B
  -> TMS tenant C -> Navigator ClientApp C -> NAVI_CONTROL_API_KEY C
```

非多租户上游系统：

```text
single upstream system -> Navigator ClientApp -> NAVI_CONTROL_API_KEY
```

非多租户场景不默认分配 `NAVI_ADMIN_API_KEY`。

## 申请与审批流程

### 上游提交申请

上游 CLI 可以在没有 Navigator 凭证的情况下提交申请：

```bash
navi upstream admin-key request \
  --upstream-system-id tms-x3 \
  --requested-tenant-id <navigator-tenant-code-or-id> \
  --multi-tenant \
  --reason "bootstrap TMS tenant ClientApps" \
  --write-profile
```

响应一次性返回申请引用和 claim token；CLI 默认将 claim token 写入 gitignored profile，不打印完整明文：

```text
requestCode
claimToken
status=PENDING
expiresAt
nextStep
```

`requestCode` 用于人工沟通和审计引用；领取密钥时不能只依赖可转发的短码，必须有高熵 claim token、一次性领取机制或等价证明。

### Navigator 侧审批

审批是 Navigator 管理面操作，不属于上游 CLI 的权限范围。上游 LLM、TMS 后端或申请者不能自己审批自己的申请。

推荐工具：

```bash
navi upstream admin-key list --status PENDING
navi upstream admin-key status --request-code <requestCode>
navi upstream admin-key approve \
  --request-code <requestCode> \
  --authorized-tenant-ids <tenantId> \
  --namespace tms-x3 \
  --scopes CLIENT_APP_ADMIN,CONTROL_KEY_ISSUE \
  --credential-expires-at 2026-05-19T10:00:00 \
  --claim-ttl-minutes 1440
navi upstream admin-key deny --request-code <requestCode> --reason "..."
navi upstream admin-key revoke --credential-id <credentialId>
navi upstream admin-key rotate --credential-id <credentialId> --write-profile
```

`CLIENT_APP_ADMIN` 与 `CONTROL_KEY_ISSUE` 是 operator/CLI 侧友好别名，服务端保存前会规范化为实际鉴权 scope：`CLIENT_APP_MANAGE` 与 `CLIENT_APP_CONTROL_KEY_ISSUE`。若审批时未显式设置 `--credential-expires-at`，服务端默认按 24 小时 TTL 签发 `NAVI_ADMIN_API_KEY`；长期有效必须由 operator 显式传入到期时间。阶段性允许 operator 用 `--claim-ttl-minutes 0` 或 `--claim-ttl-minutes -1` 表示审批确认签发无到期时间的 `NAVI_ADMIN_API_KEY`；这只影响 admin key 的有效期，claim token 的领取窗口仍使用默认 TTL。

对应后端 API 应位于 Navigator admin/control plane，例如：

```http
GET  /api/v1/admin/upstream-bootstrap-requests
GET  /api/v1/admin/upstream-bootstrap-requests/{requestCode}
POST /api/v1/admin/upstream-bootstrap-requests/{requestCode}/approve
POST /api/v1/admin/upstream-bootstrap-requests/{requestCode}/deny
POST /api/v1/admin/upstream-admin-credentials/{credentialId}/revoke
POST /api/v1/admin/upstream-admin-credentials/{credentialId}/rotate
```

### LLM 用什么工具和凭证审批

如果这里的 LLM 是上游项目里的 coding agent，它只能提交申请、查询状态、领取已批准结果，不能审批。

如果这里的 LLM 是 Navigator 侧管理员 Agent，它必须通过 Navigator admin 工具审批，并使用 Navigator 内部 operator 凭证：

```properties
NAVI_OPERATOR_API_KEY=<navigator-internal-operator-key>
```

`NAVI_OPERATOR_API_KEY` 是 Navigator 平台内部自动化凭证，不交付给上游，不写入上游项目 profile，不等同于本需求里的 `NAVI_ADMIN_API_KEY`。

该 operator 凭证应绑定以下最小权限之一：

- `SUPER_ADMIN`：仅用于跨租户平台运维 Agent。
- `TENANT_ADMIN` + target tenant：仅能审批本租户申请。
- 更推荐的细粒度权限：`UPSTREAM_BOOTSTRAP_REQUEST_APPROVE`、`UPSTREAM_CLIENT_APP_ADMIN_KEY_ISSUE`、`CLIENT_APP_CONTROL_KEY_ISSUE`。

LLM 审批时的默认安全策略：

1. LLM 可以读取申请、检查租户/上游系统/理由/历史审计，并生成审批建议。
2. 默认需要 Navigator 管理员确认后执行 `approve`。
3. 若允许全自动审批，必须使用专门的 operator service user，并配置 tenant allowlist、upstreamSystem allowlist、scope allowlist、TTL 上限和审计告警。
4. 审批工具默认不把最终密钥明文返回给 LLM；它只把申请置为 `APPROVED`。申请方随后用自己的 claim token 一次性领取。
5. 所有审批审计必须同时记录 operator service user、触发该审批的 LLM/session/task、人工确认人（如有）、请求来源和审批 payload。

## 领取与本地配置

申请被批准后，上游 CLI 查询状态：

```bash
navi upstream admin-key status --request-code <requestCode>
```

领取时默认不打印明文密钥，只写入 gitignored profile：

```bash
navi upstream admin-key claim \
  --request-code <requestCode> \
  --claim-token-env NAVI_ADMIN_KEY_CLAIM_TOKEN \
  --write-profile
```

多租户上游系统拿到 `NAVI_ADMIN_API_KEY` 后，应继续为每个租户创建或复用 ClientApp，并签发该租户自己的 `NAVI_CONTROL_API_KEY`。长期运行和控制面同步优先使用 `NAVI_CONTROL_API_KEY`，而不是继续扩大 `NAVI_ADMIN_API_KEY` 的使用面。

租户级 ClientApp bootstrap 示例：

```bash
navi upstream client-app ensure \
  --target-tenant-id <navigator-tenant-id> \
  --upstream-ref <upstream-tenant-code> \
  --name "<display-name>" \
  --tenant-profile .navigator/tenants/<upstream-tenant-code>.env \
  --write-profile

navi upstream client-app issue-control-key \
  --client-app-id <clientAppId> \
  --scopes SKILL_SYNC,MODEL_GRANT \
  --tenant-profile .navigator/tenants/<upstream-tenant-code>.env \
  --write-profile
```

这两个命令使用 `NAVI_ADMIN_API_KEY`，通过 `X-Navi-Admin-Key` 发送，不使用普通 `X-API-Key`。`issue-control-key` 返回的完整 `NAVI_CONTROL_API_KEY` 只写入目标租户 profile，控制台只显示脱敏摘要。

## 安全约束

1. `NAVI_ADMIN_API_KEY` 必须绑定 upstream system 与授权租户范围，不能是完整租户管理员 key。
2. `NAVI_ADMIN_API_KEY` 必须支持 TTL、撤销、轮换和审计；未显式传入到期时间时默认 24h，长期有效必须显式审批。
3. `NAVI_OPERATOR_API_KEY` 只存在于 Navigator 侧管理员 Agent 或安全运维环境，不允许进入上游项目。
4. request code 不应被当作唯一领取凭证；批准后领取必须一次性消费。
5. CLI、API、日志、LLM 输出不得打印任何完整 key。
6. A ClientApp 的 `NAVI_CONTROL_API_KEY` 必须无法管理 B ClientApp。
7. 现有 `/api/v1/users/**` 与 API Key 管理权限需要先完成安全复核，避免高权限凭证链路建立在开放管理接口之上。

## 实施拆分

1. 需求契约：明确 `NAVI_ADMIN_API_KEY`、`NAVI_CONTROL_API_KEY`、`NAVI_OPERATOR_API_KEY` 的术语和边界。
2. 安全底座：复核用户/API Key 管理接口权限、API Key 存储方式、脱敏日志和一次性展示规则。
3. 后端申请生命周期：新增申请实体、状态机、审批 API、领取 API、审计记录。
4. Operator 工具：新增 Navigator admin CLI/API 能力，用 operator 凭证审批或拒绝申请。
5. 上游 CLI：新增 request/status/claim 与 operator approve/deny/list 命令；多租户 ClientApp bootstrap 进入后续阶段。
6. Bootstrap 流程：多租户系统使用 `NAVI_ADMIN_API_KEY` 管理多个 ClientApp；非多租户系统直接接收 `NAVI_CONTROL_API_KEY`。
7. 测试与验收：补安全、状态机、CLI profile、跨租户隔离、日志脱敏和端到端 bootstrap 测试。

## 验收标准

1. 上游无 Navigator 凭证时可以提交申请，并拿到 `PENDING`、`requestCode`、过期时间和安全提示。
2. 上游项目自身不能审批申请；审批 API/CLI 只接受 Navigator operator/admin 凭证。
3. Navigator 侧 LLM 管理 Agent 使用 `NAVI_OPERATOR_API_KEY` 或管理员会话调用 admin 工具，不能使用申请生成的 `NAVI_ADMIN_API_KEY` 审批。
4. 多租户上游获批后可以为授权范围内多个 ClientApp 签发各自的 `NAVI_CONTROL_API_KEY`。
5. 非多租户上游可以跳过 `NAVI_ADMIN_API_KEY`，直接交付单个 ClientApp 的 `NAVI_CONTROL_API_KEY`。
6. 错租户、错 upstream system、过期、拒绝、已消费申请均不能返回密钥明文。
7. 审批、领取、撤销、轮换均有审计记录，且不记录完整密钥。
8. 跨 ClientApp、跨租户管理请求必须失败。

## Progress Tracking

### Development Progress

| Item | Status | Notes |
| --- | --- | --- |
| Requirement recorded | completed | 本文记录 issue #121 的修订语义和审批凭证模型 |
| API/CLI implementation | completed | Stage 1 后端闭环、Stage 2 SDK/CLI、Stage 3 多租户 ClientApp 管理已完成；Stage 4 质量修复已补齐 scope 别名、默认 TTL、撤销/轮换与 SDK 专用鉴权路径 |
| Security hardening | completed | Stage 0 已完成用户/API Key 管理面权限收口；高权限 key 按 hash 存储、一次性明文交付、默认 24h TTL，并支持撤销/轮换审计 |

### Testing Progress

| Item | Status | Notes |
| --- | --- | --- |
| Unit tests | passed | `mvn test -pl user-auth-module -am` 68 tests；`mvn test -pl business-agent-module -am` 284 tests；覆盖权限、状态机、operator key、TTL、撤销/轮换、一次性领取、scope 别名、授权范围和跨 upstream/tenant 拒绝 |
| Integration tests | passed | 新增服务级 bootstrap evidence：request -> approve -> claim -> ensure ClientApp -> issue `NAVI_CONTROL_API_KEY` 已通过；live HTTP/CLI smoke 已在重启 launcher 后通过 |
| CLI tests | passed | `mvn test -pl navigator-open-sdk`，78 tests passed；覆盖 admin-key request/claim/approve/revoke/rotate、client-app ensure/issue-control-key profile 写入、脱敏输出，以及 upstream admin 专用 header 不混入 `X-API-Key` |

### Experience Progress

experience: N/A

原因：本文是后端/API/CLI 契约记录，当前不包含 UI 改动。若后续实现 Navigator 管理台审批页面，需要单独补 experience checklist 和 Playwright evidence。

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted
- signed_off_by: Codex execution-agent
- signed_off_at: 2026-05-18
- acceptance_record: docs/version-tracker/1.1.3-SNAPSHOT/acceptance/upstream-client-app-admin-key-acceptance.md
- blocking_items: none
- follow_up_required: no
