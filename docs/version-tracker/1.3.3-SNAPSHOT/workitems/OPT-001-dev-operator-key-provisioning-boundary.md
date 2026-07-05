# OPT-001 Dev Operator Key Provisioning Boundary

## 文档作用

- doc_type: optimization
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 记录开发期 dev provisioning credentials 与正式环境 provisioning 授权策略，收敛 SIM / TMS 上游反复 request/approve 的流程成本。

## 基本信息

- version: `1.3.3-SNAPSHOT`
- priority: high
- status: in-progress
- source_type: optimization
- owner_modules: `business-agent-module`, `navigator-open-sdk`, `addons/claude-worker-agent`, upstream project profiles
- related_runtime_case: TMS UI Experience Reviewer `tms-ui-experience-reviewer-a` Navigator runtime provisioning

## 背景

TMS UI Experience Reviewer runtime provisioning 已完成 model / Agent / workspace 阶段，但在 readiness / owner-smoke 阶段阻塞于 `WORKER_HOST_ROLE_ROUTING`：Biz execution worker 期望来自 `BIZ_WORKER_IDENTITY`，实际回退到 `AGENT_WORKSPACE_BINDING:CLIENT_APP_SHARED`。

本地 `worker-host verify` 能识别 `school-sim-wsl-biz` 的 Biz role，但 `worker-host apply` 需要有效 admin / operator credential。当前流程依赖临时 `admin-key request -> operator approve -> claim -> apply`，开发期频繁出现审批往返，增加 SIM / TMS 接入、Actor Home smoke 和上游验收成本。

## 问题陈述

当前存在四类问题：

1. 开发期 provisioning 操作需要反复临时申请 admin key，阻断 modelConfig、worker-host、Agent、workspace、upstream user grant 的连续收口。
2. 上游之间需要更清晰的隔离边界，避免一组 dev provisioning credentials 操作其他 upstream system / ClientApp 的资源。
3. 正式上线后需要判断哪些变更仍应 request/approve，哪些应通过预授权 operator key 或平台自动化完成，避免生产使用时出现大量人工往返。
4. operator / admin credential 过期不能影响正常业务运行；过期只应阻断需要管理面权限的 provisioning / repair 操作，并且必须有提前预警、轮换和续期路径。

## 目标结果

1. 为 `foggy-world-sim`、`tms` 等开发期上游分别提供专用 dev provisioning credentials。
2. dev provisioning credentials 只绑定自己的 namespace / upstream system / ClientApp 资源域，不具备跨上游或 SUPER_ADMIN 能力。
3. `worker-host apply` 能幂等刷新 `school-sim-wsl-biz` 等 upstream-system-owned Biz Worker identity，使 readiness 输出 `workerRole role=biz ... source=BIZ_WORKER_IDENTITY`。
4. 正式环境形成分层授权策略：初始化与基础设施变更需要审批；日常已授权范围内的 Agent sync、grant refresh、owner-smoke 不应反复人工 approve。
5. 正式 runtime 不依赖 admin key；业务调用、readiness、owner-smoke、live smoke 使用 runtime key 或自动换取的短期 runtime token。
6. admin / operator key 具备可观测的到期时间、提前告警、轮换 SOP 和过期后的恢复路径。
7. CLI / 文档 / smoke 输出禁止落盘或打印 admin key、claim token、API key、cookie、真实账号和密码。

## 术语与边界

- `dev provisioning credentials`: 开发/沙箱环境专用 provisioning 凭据组合，不是 runtime credential；当前实现拆为 upstream admin key 与 ClientApp control key 两条 lane。
- `upstream admin key`: 使用 `X-Navi-Admin-Key`，绑定 upstream system / namespace / tenant 授权域，用于 ClientApp 管理、签发 control/runtime key、upstream-system-owned modelConfig、WorkerHost apply、Worker/Biz Worker identity、WorkerPool、UPSTREAM_SYSTEM_SHARED directory 等管理动作。
- `ClientApp control key`: 使用 `X-Client-App-Control-Key`，绑定单个 ClientApp，用于 ClientApp-owned modelConfig、model grant、Agent sync、model/workspace/worker binding、CLIENT_APP_SHARED / USER_PRIVATE directory、upstream user grant 等日常 provisioning 动作。
- `runtime credential`: ClientApp key-secret 或其换取的短期 runtime token，只用于业务调用、readiness、owner-smoke、live smoke，不具备创建 modelConfig、grant、binding、worker-host apply 等管理能力。
- `namespace / upstream system`: 从 Navigator 视角看注册的外部业务系统边界，例如 `foggy-world-sim`、`tms`。
- `ClientApp`: namespace 下的调用方应用身份，是 model grant、Agent、directory、worker binding 的主要资源归属边界。
- `Biz Worker identity`: `LANGGRAPH_BIZ` 物理执行 worker 身份；正式 runtime 只接受 `PLATFORM` 或当前 `UPSTREAM_SYSTEM` 可见的 identity。
- `request/approve`: 临时提升权限流程，用于不应长期下放给 dev provisioning credentials 的变更。

## 权限策略草案

### 开发期

每个上游系统一组 dev provisioning credentials：

- `foggy-world-sim` credentials 只能管理 `foggy-world-sim` namespace / upstream system / ClientApp 下资源。
- `tms` credentials 只能管理 `tms` namespace / upstream system / ClientApp 下资源。
- 不给跨 namespace 查询、repair、grant、bind 能力。
- 不给 SUPER_ADMIN。
- key / secret 只放 gitignored profile 或平台 secret，不进入提交文档、聊天记录或 smoke evidence。

建议 upstream admin scopes：

- `CLIENT_APP_MANAGE`
- `CLIENT_APP_RUNTIME_KEY_ISSUE`
- `CLIENT_APP_CONTROL_KEY_ISSUE`
- `WORKER_MANAGE`
- `WORKING_DIRECTORY_MANAGE`
- `WORKER_POOL_MANAGE`
- `MODEL_CONFIG_MANAGE`

建议 ClientApp control scopes：

- `AGENT_BUNDLE_SYNC`
- `AGENT_MODEL_BINDING_MANAGE`
- `AGENT_WORKSPACE_BINDING_MANAGE`
- `AGENT_WORKER_BINDING_MANAGE`
- `MODEL_CONFIG_MANAGE`
- `MODEL_CONFIG_GRANT_MANAGE`
- `WORKING_DIRECTORY_MANAGE`
- `UPSTREAM_USER_GRANT`

### 正式环境

正式环境不应把开发期 dev provisioning credentials 原样扩大为生产万能凭据。建议分层：

1. `bootstrap operator`: 仅平台管理员或受控 CI 使用，用于创建 namespace、ClientApp、首个 runtime/control key、首个 worker-host identity。
2. `provisioning operator`: 绑定单一 upstream system，用于该系统名下 Agent sync、model grant refresh、workspace binding、worker-host heartbeat/apply refresh。
3. `runtime credential`: 用于业务调用、readiness、owner-smoke、live smoke，不具备创建或跨资源授权能力。
4. `break-glass admin`: 短 TTL、强审计，仅用于 owner repair、跨系统资源迁移、误授权清理等非常规操作。

生产约束：runtime key 与 provisioning operator 必须分离。admin / operator credential 过期时，已授权的业务 runtime 不应失败；只有新增 ClientApp、刷新 worker-host 注册、创建 modelConfig、grant/binding 变更、owner repair 等管理动作需要有效 provisioning credential。

## 任务拆分

1. 梳理现有 admin-key、client-app control key、worker-host apply、Agent sync、grant API 的权限校验点。
2. 明确 dev provisioning credentials 的创建、轮换、失效和 profile 写入方式。
3. 增加或确认 namespace / upstream system / ClientApp 级别的权限约束，阻止跨上游操作。
4. 梳理 `worker-host apply/update/verify` 的幂等语义，确保 Biz Worker identity owner 为当前 upstream system 或 platform。
5. 明确生产分层授权策略，并更新 CLI / 管理文档。
6. 增加 smoke / regression，覆盖跨上游隔离、过期 key、权限不足、正常 provisioning 闭环。

## 下一步执行计划

### Stage 0 - Baseline Inventory

- status: done
- goal: 先确认现有权限链路和数据模型，不急着发新 key。
- actions:
  - 盘点 admin-key、ClientApp control key、runtime key、worker-host apply、modelConfig create/grant、Agent sync/bind 的 Controller / Service 入口。
  - 标出每个入口当前校验的主体：tenantId、namespace / upstreamSystemId、clientAppId、resource owner、grant target。
  - 标出已知风险点：旧 modelConfig owner/grant 不一致、Agent 跨 ClientApp 归属、Biz Worker identity 未按 upstream system 可见。
- output:
  - 权限入口清单。
  - dev provisioning credentials 需要覆盖的最小 action scope 清单。
  - 必须补强的资源过滤点清单。

### Stage 1 - Dev Provisioning Credentials Contract

- status: in-progress
- goal: 定义每个上游系统一组受限 dev provisioning credentials 的正式契约。
- actions:
  - 明确 upstream admin key 绑定字段：tenantId、namespace / upstreamSystemId、scope set、expiresAt / rotation policy。
  - 明确 ClientApp control key 绑定字段：tenantId、clientAppId、scope set、expiresAt / rotation policy。
  - 明确凭据能做的动作：本 upstream system 下 ClientApp 管理、control/runtime key 签发、upstream-system-owned modelConfig、WorkerHost apply；本 ClientApp 下 modelConfig、grant/set-default、Agent sync、model/workspace/worker binding、working directory、upstream user grant。
  - 明确凭据不能做的动作：跨 namespace 操作、SUPER_ADMIN、跨 owner repair、platform-owned 资源创建、读取其他 upstream 的私有资源。
  - 明确 profile / secret 写入规则：只写 gitignored profile 或平台 secret，不进入文档和日志。
- output:
  - dev provisioning credentials contract。
  - SIM / TMS 两套示例 scope 与隔离规则。

## Stage 0 Baseline Inventory Result

### Credential Lanes

| Lane | Header / Profile | Main Scope Source | Resource Boundary | Runtime Role |
| --- | --- | --- | --- | --- |
| Upstream admin | `X-Navi-Admin-Key` / `NAVI_ADMIN_API_KEY` | `UpstreamBootstrapRequestService` | upstream system + authorized tenant + optional ClientApp namespace | 管理面 provisioning，不参与业务 runtime |
| ClientApp control | `X-Client-App-Control-Key` / `NAVI_CONTROL_API_KEY` | `ClientAppControlCredentialService` | single ClientApp + tenant | ClientApp provisioning，不参与业务 runtime |
| Runtime credential | ClientApp key-secret / runtime token | runtime resolver | ClientApp runtime identity | ask / readiness / owner-smoke / live smoke |

### Reviewed Entrypoints

| Area | Entrypoint | Credential Lane | Current Boundary Check | Review Result |
| --- | --- | --- | --- | --- |
| ClientApp management | `UpstreamClientAppAdminController` + `UpstreamClientAppManagementService` | Upstream admin | tenant authorization, upstreamSystemId, upstreamClientAppNamespace, active ClientApp | OK |
| Upstream-system modelConfig | `UpstreamAdminModelConfigController` + `UpstreamAdminModelConfigService` | Upstream admin | ownerType=`UPSTREAM_SYSTEM`, ownerId=current upstreamSystemId, tenant match | OK |
| ClientApp-owned modelConfig | `ClientAppOwnedModelConfigController` + `ClientAppOwnedModelConfigService` | ClientApp control | clientAppId-bound control credential, ownerType=`CLIENT_APP`, owned grant | OK |
| Model grants/default | `ClientAppModelConfigGrantController` + `ClientAppModelConfigGrantService` | ClientApp control | clientAppId-bound control credential, effective grant resolution | OK, needs regression evidence |
| Agent sync | `BusinessAgentBundleController` + `BusinessAgentBundleService` | ClientApp control | form clientAppId must match credential, agent ownerType=`CLIENT_APP` | OK |
| Agent model binding | `AgentModelBindingController` + `AgentModelBindingService` | ClientApp control | agent owner/clientApp match, effective modelConfig grant | OK |
| Agent workspace binding | `AgentWorkspaceBindingController` + `AgentWorkspaceBindingService` | ClientApp control | agent owner/clientApp match, directory visible to ClientApp | OK |
| Agent worker binding | `AgentWorkerBindingController` + `AgentWorkerBindingService` | ClientApp control | agent owner/clientApp match, platform/upstream/clientApp visible worker pool | OK |
| ClientApp directory | `ClientAppWorkingDirectoryController` | ClientApp control | clientAppId-bound directory visibility, no `UPSTREAM_SYSTEM_SHARED` creation | OK |
| Upstream worker / directory | `UpstreamAdminWorkerDirectoryController` | Upstream admin | tenant authorization, ownerType=`UPSTREAM_SYSTEM` for shared directory | OK |
| Biz Worker identity | `UpstreamAdminWorkerIdentityController` + `BizWorkerPoolService` | Upstream admin | ownerType=`UPSTREAM_SYSTEM`, ownerId=current upstreamSystemId | OK, needed for `BIZ_WORKER_IDENTITY` readiness |
| Worker pool | `UpstreamAdminWorkerPoolController` + `BizWorkerPoolService` | Upstream admin | ownerType=`UPSTREAM_SYSTEM`, tenant authorization | OK, add cross-upstream negative tests |

### Review Conclusions

1. 不建议把 runtime credential 扩权成“能创建 / 绑定 modelConfig 的万能 key”；这会破坏运行面和管理面的隔离。
2. 当前代码已经具备两条 provisioning lane：upstream admin key 处理 upstream/system 资源，ClientApp control key 处理 ClientApp 资源。
3. `WORKER_HOST_ROLE_ROUTING` 的修复路径应是让 upstream admin lane 幂等执行 `worker-host apply`，把 `school-sim-wsl-biz` 注册/刷新为当前 upstream system 可见的 `BIZ_WORKER_IDENTITY`。
4. 正式上线后只要上游保有可轮换的 provisioning credentials 与 runtime credential，日常 Agent sync、binding、owner-smoke 不应频繁 request/approve；request/approve 应保留给首次 bootstrap、跨边界 repair、break-glass、权限扩大等高风险动作。
5. 主要剩余工作不是再设计一个新超级 key，而是补齐凭据契约、到期轮换、CLI profile 流程、跨上游负向测试和 smoke evidence。

## Stage 1 Contract Draft

### Dev Credential Package

每个上游系统在开发 / 沙箱环境持有一组凭据，而不是一个混合 runtime / admin 的万能 key：

| Credential | Required For | Profile Key | Must Not Be Used For |
| --- | --- | --- | --- |
| Upstream admin key | ClientApp ensure、issue control/runtime key、system modelConfig、worker-host apply/update、Worker/Biz Worker identity、WorkerPool、UPSTREAM_SYSTEM_SHARED directory | `NAVI_ADMIN_API_KEY` | 业务 ask、owner-smoke、live smoke |
| ClientApp control key | ClientApp-owned modelConfig、model grant/default、Agent sync、model/workspace/worker binding、CLIENT_APP_SHARED / USER_PRIVATE directory、upstream user grant | `NAVI_CONTROL_API_KEY` | 跨 ClientApp 操作、upstream-system-owned resource repair |
| Runtime credential | runtime-token exchange、ask、readiness、owner-smoke、live smoke | ClientApp key-secret / runtime token fields | modelConfig create、grant、binding、worker-host apply |

### Command Lane Matrix

| CLI Action | Lane | Expected Boundary |
| --- | --- | --- |
| `client-app ensure` | Upstream admin | current upstream system + authorized tenant / namespace |
| `client-app issue-control-key` | Upstream admin | target ClientApp must be managed by current upstream system |
| `client-app issue-runtime-key` | Upstream admin | target ClientApp must be managed by current upstream system |
| `model create/update/rotate-key/clear-key` | ClientApp control | creates or updates ClientApp-owned modelConfig |
| `model system-create/system-update/system-rotate-key/system-clear-key` | Upstream admin | creates or updates upstream-system-owned modelConfig |
| `model grant/set-default` | ClientApp control | target ClientApp grant only |
| `agent sync` | ClientApp control | agent ownerType=`CLIENT_APP`, ownerId=current ClientApp |
| `agent bind-model/bind-workspace/bind-worker` | ClientApp control | target resource must be visible to current ClientApp |
| `agent system-create/system-bind-*` | Upstream admin | system-owned agent and system-owned resources only |
| `directory client-init/client-env/client-files` | ClientApp control | CLIENT_APP_SHARED or USER_PRIVATE only |
| `directory init/env/files` | Upstream admin | UPSTREAM_SYSTEM_SHARED only |
| `worker-host apply/update` | Upstream admin | upstream-system-owned worker, worker pool, Biz Worker identity |
| `verify-agent-readiness` / `owner-smoke` / Actor Home live smoke | Runtime credential | no provisioning write capability |

### SIM / TMS Isolation Rule

- SIM upstream admin key may create and refresh SIM-owned WorkerHost / Biz Worker identity such as `school-sim-wsl-biz`; it must not create, bind, grant, or repair TMS ClientApp resources.
- TMS upstream admin key may create and refresh TMS-owned provisioning resources; it must not reuse SIM-owned Biz Worker identity unless a later design explicitly promotes that worker to platform-owned shared infrastructure.
- SIM and TMS ClientApp control keys are not interchangeable. A control key bound to one ClientApp must fail against another ClientApp path or manifest `clientAppId`.

### Stage 2 - Server-Side Enforcement

- status: in-progress
- goal: 确保 scope 只是动作权限，真正隔离由服务端资源过滤保证。
- actions:
  - 对 modelConfig create/grant/set-default、Agent sync、workspace/worker binding、worker-host apply、upstream user grant 增加或确认 namespace / upstreamSystemId / ClientApp owner 校验。
  - 对 owner repair、跨 upstream 迁移、platform-owned worker/model 创建保留 break-glass admin。
  - 保证 Biz Worker identity runtime 可见性只接受 `PLATFORM` 或当前 `UPSTREAM_SYSTEM`，不接受 `CLIENT_APP` owner 作为执行 identity。
- output:
  - 服务端权限校验变更或确认记录。
  - 跨上游资源访问失败的错误语义。
- evidence:
  - 2026-07-05 review confirmed ClientApp model/workspace/worker binding services reject foreign ClientApp-owned agents and resources, and system-owned binding rejects foreign upstream-system resources.
  - Added targeted negative regression coverage in `AgentModelBindingServiceTest`, `AgentWorkspaceBindingServiceTest`, and `AgentWorkerBindingServiceTest`.

### Stage 3 - CLI / Profile Flow

- status: in-progress
- goal: 让上游能自助完成开发期 provisioning，不再频繁 request/approve。
- actions:
  - 增加或确认 dev provisioning credentials 写入 profile 的命令与脱敏输出。
  - 确认 runtime credential / client app key-secret 启动时自动 exchange 短期 token。
  - 确认 admin/operator key 过期只影响 provisioning，不影响 runtime ask / readiness / owner-smoke。
  - 在 CLI 报错中区分 runtime token 过期、operator key 过期、scope 不足、跨 namespace 拒绝。
- output:
  - CLI 使用说明。
  - 停运后重启自动恢复 runtime token 的 smoke 步骤。
- evidence:
  - 2026-07-05 verified `navi upstream --help`, `navi upstream worker-host --help`, and `navi upstream client-app --help` locally without credentials.
  - Current CLI has dedicated help for `worker-host` and `client-app`; `upstream model --help` and `upstream agent --help` return `Unknown command`, so the command matrix currently depends on top-level `upstream --help` and skill references.

### Stage 4 - Regression Matrix

- status: in-progress
- goal: 用自动化测试把隔离和 key 生命周期固定下来。
- actions:
  - SIM credentials 操作 SIM 资源成功。
  - TMS credentials 操作 TMS 资源成功。
  - SIM credentials 操作 TMS modelConfig / Agent / directory / Biz Worker identity / user grant 失败。
  - TMS credentials 操作 SIM 资源失败。
  - admin/operator key 过期时 provisioning 返回明确 401 / 403，且不打印敏感信息。
  - admin/operator key 过期时已有 runtime ask / readiness / owner-smoke 不受影响。
- output:
  - 单元测试 / 集成测试 / CLI smoke evidence。
- evidence:
  - 2026-07-05 unit regression: `mvn -pl business-agent-module -am "-Dtest=AgentModelBindingServiceTest,AgentWorkspaceBindingServiceTest,AgentWorkerBindingServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
  - Result: 26 tests run, 0 failures, 0 errors, 0 skipped.
  - 2026-07-05 expanded unit regression: `mvn -pl business-agent-module -am "-Dtest=AgentModelBindingServiceTest,AgentWorkspaceBindingServiceTest,AgentWorkerBindingServiceTest,ClientAppModelConfigGrantServiceTest,ClientAppUserGrantServiceTest,BizWorkerPoolServiceTest,UpstreamAdminModelConfigServiceTest,UpstreamAdminWorkerIdentityControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
  - Result: 85 tests run, 0 failures, 0 errors, 0 skipped.

### Stage 5 - SIM / TMS Provisioning Smoke

- status: blocked-by-credential
- goal: 用真实 sandbox profile 验证完整闭环。
- actions:
  - 给 SIM 发放受限 dev provisioning credentials，执行 `worker-host apply`，确认 `school-sim-wsl-biz` 解析为 `BIZ_WORKER_IDENTITY`。
  - 给 TMS 发放受限 dev provisioning credentials，完成自己的 modelConfig、Agent sync、workspace binding、worker-host apply。
  - 分别执行 `verify-agent-readiness`、`owner-smoke`、Actor Home live smoke。
  - 执行停运超过 access token TTL 后重启 smoke，确认 runtime credential 自动换 token。
- output:
  - SIM smoke 记录。
  - TMS smoke 记录。
  - secret leakage scan 记录。
- blocker:
  - 需要有效的 gitignored SIM / TMS sandbox provisioning credentials 和 runtime credentials。当前执行不读取 `.navigator/upstream.env` 内容，不访问真实 TMS，也不读取 `accounts/`。

### Stage 6 - Production Policy

- status: in-progress
- goal: 明确上线后哪些动作自助、哪些动作审批。
- actions:
  - 固化四层凭据：bootstrap operator、provisioning operator、runtime key、break-glass admin。
  - 明确生产 key TTL、轮换、提前告警、撤销和审计。
  - 明确 request/approve 只用于跨边界或高风险操作，不作为日常 provisioning 主路径。
- output:
  - 生产授权策略文档。
  - 上线前验收 checklist。

## CLI / Profile Flow Inventory

### Profile Files

| Profile | Owner | Contains | Required Rule |
| --- | --- | --- | --- |
| `.navigator/upstream.env` | project-local upstream system | base URL, upstream identity, optional system-level admin key, default ClientApp/runtime fields | must be gitignored; do not print secret values |
| `.navigator/tenants/<tenant>.env` | tenant / ClientApp profile | `NAVI_CLIENT_APP_ID`, runtime key-secret, `NAVI_CONTROL_API_KEY`, optional model / agent / directory defaults | must be gitignored; use when switching upstream tenants or ClientApps |
| `.navigator/worker-host.json` | worker-host manifest | workerHostId, hostUrl, role worker IDs / ports | may be committed only if it contains no tokens, private URLs, accounts, or passwords; otherwise keep local |

### Command Credential Matrix

| Command Family | Credential Lane | Writes Profile | Notes |
| --- | --- | --- | --- |
| `upstream admin-key request/status/claim` | request uses upstream metadata; claim writes upstream admin | yes, only with `--write-profile` | final `NAVI_ADMIN_API_KEY` must not be printed |
| `upstream admin-key approve/deny/revoke/rotate` | Navigator operator/admin environment | rotate writes only with `--write-profile` | upstream projects must not self-approve; approval belongs to Navigator ops |
| `upstream client-app ensure/list` | upstream admin | optional tenant profile | target ClientApp must belong to current upstream system / authorized tenant |
| `upstream client-app issue-runtime-key` | upstream admin | yes | writes ClientApp key-secret, clears stale runtime access token, prints only masked identifiers / digests |
| `upstream client-app issue-control-key` | upstream admin | yes | writes ClientApp-scoped `NAVI_CONTROL_API_KEY` |
| `upstream client-app ensure-tenant` | upstream admin | yes | aggregate bootstrap; refuses non-gitignored target profile |
| `upstream model create/update/rotate-key/clear-key` | ClientApp control | optional `NAVI_MODEL_CONFIG_ID` | ClientApp-owned modelConfig only |
| `upstream model system-create/system-update/system-rotate-key/system-clear-key` | upstream admin | optional | upstream-system-owned shared modelConfig only |
| `upstream model grant/set-default` | ClientApp control | optional `NAVI_MODEL_CONFIG_ID` | grant/default scoped to current ClientApp |
| `upstream agent sync` and ClientApp `agent bind-*` | ClientApp control | no secret write | Agent owner must be current ClientApp; model/workspace/worker resources must be visible to that ClientApp |
| `upstream agent system-*` | upstream admin | no secret write | system-owned Agent and resources only |
| `upstream directory client-*` | ClientApp control | no secret write | CLIENT_APP_SHARED / USER_PRIVATE only |
| `upstream directory init/env/files` | upstream admin | no secret write | UPSTREAM_SYSTEM_SHARED only |
| `upstream worker-host apply/update` | upstream admin | optional `NAVI_WORKER_HOST_ID`, `NAVI_WORKER_ID`, `NAVI_BIZ_WORKER_ID` | normal path for `BIZ_WORKER_IDENTITY` provisioning |
| `upstream worker-host verify/install` | local manifest only | no secret write | verify/install do not call Navigator admin APIs |
| `upstream runtime-token` | runtime key-secret | yes, only with `--write-profile` | access token is short-lived and can be re-exchanged from key-secret |
| `upstream owner-smoke`, `verify-agent-readiness`, `ask`, `messages`, `sessions`, `skill read/tree` | runtime credential | no provisioning write | should auto-exchange token when ClientApp key-secret is present |

### CLI Experience Gaps

1. `upstream model --help` and `upstream agent --help` currently return `Unknown command`; the top-level `upstream --help` contains the command list, but focused help should be added before formal production handoff.
2. Error messages should distinguish at least four cases: runtime token expired, provisioning key expired, insufficient scope, and cross-boundary resource rejection.
3. Profile-write commands must keep the current behavior of requiring `--write-profile` for credential material and refusing unsafe target profiles where implemented.

## Credential Lifecycle Policy

### Development / Sandbox

| Credential | Recommended TTL | Rotation Trigger | Runtime Impact |
| --- | --- | --- | --- |
| Upstream admin key | long enough for a dev cycle, with explicit expiry | lost key, scope change, staff handoff, sandbox reset, expiry warning | no direct runtime impact; blocks provisioning only |
| ClientApp control key | long enough for Agent/model/workspace iteration | ClientApp ownership change, scope reduction, suspected leak, expiry warning | no direct runtime impact; blocks ClientApp provisioning only |
| ClientApp runtime key-secret | longer-lived than access token; rotated deliberately | suspected leak, ClientApp rebuild, production rotation window | runtime can recover by exchanging new access token if key-secret is valid |
| Runtime access token | short TTL | automatic exchange | no manual approve required when key-secret is valid |

### Production

1. Bootstrap operator is used for first namespace / ClientApp / first credential creation and should remain tightly controlled.
2. Provisioning operator is upstream-system scoped and handles normal Agent sync, model grant, workspace/worker binding, and worker-host refresh.
3. Runtime credential is separate from provisioning and must never gain modelConfig create, grant, bind, or worker-host apply authority.
4. Break-glass admin remains short TTL and audited; use only for cross-owner repair, migration, or emergency revocation.
5. Operator credential expiry should be observable before failure through inspect/status or scheduled checks; expiry should not interrupt existing runtime calls.
6. After upstream service downtime, normal startup should exchange a fresh runtime token from stored ClientApp key-secret. It should not require a new admin approval unless provisioning credentials also expired and a provisioning action is needed.

## Sandbox Smoke Plan

### SIM First

1. Use SIM upstream admin key from gitignored profile.
2. Run `worker-host apply --file .navigator/worker-host.json --write-profile`.
3. Run `worker-host verify --file .navigator/worker-host.json`.
4. Run `owner-smoke`.
5. Run `verify-agent-readiness`.
6. Required evidence: readiness / owner-smoke shows `workerRole role=biz` with `source=BIZ_WORKER_IDENTITY`.

### TMS Second

1. Use TMS-specific upstream admin key and TMS ClientApp control/runtime credentials; do not reuse SIM credentials.
2. Create or verify TMS modelConfig through the correct lane.
3. Sync/register `world-sim.biz-worker-browser-smoke.v1` only after it is owned by or visible to the current ClientApp / upstream system.
4. Bind model, workspace, and worker to `directoryId=20260705-228b`.
5. Run `owner-smoke`, then `verify-agent-readiness`.
6. Run Actor Home live smoke with `docs/scopes/tms/tms-ltl-ui-qa/rehearsals/prompts/ui-experience-reviewer-actor-home-live-smoke-20260705-001.md`.
7. Do not access real TMS, do not read `accounts/`, and do not dispatch the first UI巡检 task before owner-smoke and live smoke pass.

## 立即行动清单

1. 对照 `navigator-open-sdk` 的 `worker-host apply`、`model`、`agent sync`、`bind` 命令，补齐 CLI 侧 action scope 与 profile 依赖清单。
2. 固化 SIM / TMS dev provisioning credentials 的 scope 模板和 profile 写入规则。
3. 先验证 SIM dev provisioning credentials，再复制到 TMS；不要先发一个跨上游大权限凭据。
4. 在补实现前先写隔离负向测试用例清单，尤其覆盖 modelConfig、Agent、directory、Biz Worker identity、upstream user grant。
5. 保留当前 TMS UI Experience Reviewer provisioning 作为首个端到端验收样本，但在 readiness / owner-smoke 通过前不派发首轮 UI 巡检任务。

## 验收标准

1. SIM 和 TMS 各有独立 dev provisioning credentials，互相不能读取、grant、bind、repair 对方私有资源。
2. 使用 SIM credentials 能完成 `worker-host apply`，并让 `verify-agent-readiness` / `owner-smoke` 解析到 `workerRole role=biz source=BIZ_WORKER_IDENTITY`。
3. 使用 TMS credentials 能完成 TMS 自身 model / Agent / directory / worker-host provisioning，不依赖人工反复 approve。
4. 跨 namespace 的 modelConfig、directory、Agent、Biz Worker identity、upstream user grant 操作被拒绝，并有自动化测试覆盖。
5. 正式环境文档明确哪些操作需要 request/approve，哪些属于已授权 provisioning operator 的日常范围。
6. admin / operator key 过期不会影响已有 runtime ask / readiness / owner-smoke；过期场景下 provisioning 操作返回明确 401 / 403，并提示续期路径。
7. provisioning operator 到期前有可验证的预警或巡检机制，轮换后不需要重新派发业务 runtime key。
8. 所有 CLI 输出、文档和测试 evidence 不包含 token、secret、cookie、真实账号或密码。

## 约束与非目标

- 不访问真实 TMS 业务系统。
- 不读取或提交 `accounts/` 内容。
- 不把 dev provisioning credentials 设计成 SUPER_ADMIN。
- 不把 `school-sim-wsl-biz` 这类 SIM worker 默认共享给 TMS，除非后续显式设计 platform-owned shared worker。
- 不在本 workitem 内替代正式安全审计；生产策略需要后续 quality / coverage / acceptance 链路确认。
- 不要求生产 admin key 长期不过期；目标是隔离 runtime 与管理面，并让管理面 credential 可续期、可轮换、可观测。

## 正式上线审批负担评估

- 评审对象类型：总规划文档
- 评估结论：有条件通过。正式使用不应该出现开发期这种大量 request/approve 往返，但前提是平台提前完成分层授权和资源归属收敛。
- 替代方案：如果继续使用临时 admin-key request/approve 作为日常 provisioning 入口，生产上游每次新增 Actor、刷新 WorkerHost、修复 grant、绑定 directory 都会卡人工审批，不适合作为常规流程。更优方案是每个正式 upstream system 配受限 provisioning operator，配合审计、TTL / rotation、scope 限制和 break-glass 流程。
- 复杂度：中等。主要复杂度在权限边界和测试矩阵，不在 CLI 命令本身。不能只发一个大权限 key 解决，否则后续会形成跨上游资源污染风险。
- 风险点：跨 namespace 授权绕过、modelConfig owner/grant 不一致、Biz Worker identity 被错误设为 ClientApp owner、dev credential 泄漏、生产 credential 权限过大、request code / claim token 被写入文档。
- 证据缺口：需要补自动化测试证明 SIM credentials 不能操作 TMS 资源、TMS credentials 不能操作 SIM 资源；需要补 worker-host apply 后 readiness 的 `BIZ_WORKER_IDENTITY` evidence；需要补过期 credential / 权限不足的负面用例；需要补 runtime 不依赖 admin/operator key 的回归证据。
- 结论一致性：当前规划能解释 TMS UI Experience Reviewer 的阻塞原因，也能覆盖正式环境减少审批往返的目标，但必须以测试和审计证据收口后才能进入签收。
- 命名与术语：有风险但可控。`upstream`、`owner`、`client` 都是相对视角词，本文已限定观察视角；后续 API / DB / CLI 文档需要持续沿用 `upstream system`、`ClientApp`、`Biz Worker identity`、`provisioning operator` 这组术语。

## Progress Tracking

### Development Progress

| Item | Status | Notes |
| --- | --- | --- |
| 版本 workitem 落档 | done | 已记录 1.3.3 版本目标、权限策略草案与验收标准 |
| dev provisioning credentials 方案设计 | in-progress | 已确认应拆分 upstream admin lane 与 ClientApp control lane；待补 profile / rotation 细节 |
| 权限入口清单 | done | 已盘点 admin/control/runtime 三条 lane 与关键 controller/service 校验点 |
| 权限校验实现 / 调整 | review-complete | 初步 review 未发现需要先改代码的核心越权点；已补 binding 层跨 ClientApp / 跨 upstream 负向回归 |
| CLI / profile flow 梳理 | done | 已记录 admin/control/runtime lane 的命令矩阵、profile 写入规则和当前 CLI help 缺口 |
| worker-host apply 闭环验证 | not-started | 需要有效 operator credential 和 sandbox evidence |
| 正式环境授权文档 | planned | 需沉淀 production bootstrap / provisioning / runtime / break-glass 四层口径 |
| Credential lifecycle 设计 | draft-complete | 已记录 dev/sandbox 与 production TTL、轮换、停运后 runtime token 自动恢复策略；待实现预警 / scheduled check |
| Sandbox smoke plan | draft-complete | 已记录 SIM first、TMS second 的 smoke 顺序和禁止访问真实 TMS / accounts 的边界 |

### Testing Progress

| Test Area | Status | Required Evidence |
| --- | --- | --- |
| SIM dev credentials provisioning smoke | not-run | `worker-host apply` + readiness + owner-smoke |
| TMS dev credentials provisioning smoke | not-run | model / Agent / directory / worker-host provisioning |
| Cross-upstream isolation regression | partial | 已覆盖 model grant/default、user grant、upstream model、worker identity/pool、model/workspace/worker binding 层跨 ClientApp / 跨 upstream 资源拒绝；仍需 CLI / smoke 覆盖 SIM credential 操作 TMS、TMS credential 操作 SIM |
| Expired / insufficient key negative cases | not-run | 401 / 403 行为清晰且不打印敏感信息 |
| Runtime/admin credential separation regression | not-run | admin/operator key 过期时，已有 runtime ask / readiness / owner-smoke 不受影响 |
| Secret leakage scan | not-run | 可提交文件不包含 key、token、cookie、真实账号密码 |

### Experience Progress

- experience: N/A
- reason: 本 workitem 是平台授权、CLI 与 runtime provisioning 能力，不涉及用户 UI 页面、表单、列表、按钮交互或视觉体验变更。

## Acceptance Readiness

- current_status: not-ready
- blockers:
  - dev provisioning credentials 尚未正式创建和验证。
  - 跨上游隔离已有 service-level regression，但尚未补 CLI / live sandbox smoke evidence。
  - 正式环境 request/approve 分层策略已有 draft，尚未形成最终运维 SOP。
  - credential 到期预警、轮换和 runtime 隔离策略已有 draft，尚未补自动化预警和停运后重启 smoke 证据。
- required_follow_up:
  - implementation quality gate
  - test coverage audit
  - acceptance signoff
