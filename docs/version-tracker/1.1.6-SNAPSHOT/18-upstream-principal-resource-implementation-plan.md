# Upstream Principal Resource Implementation Plan

Version: `1.1.6-SNAPSHOT`

Status: implementation in progress

Depends on: [17-upstream-principal-resource-ownership-and-visibility.md](./17-upstream-principal-resource-ownership-and-visibility.md)

Purpose: 将上游主体、资源 owner、grant / binding 与 A2Agent runtime resolver 的关系拆成可开发、可测试、可验收的实施计划。本计划明确不考虑旧数据兼容和迁移；上线前允许清空或重建相关上游接入数据。

## 0. Compatibility Boundary

本项目尚未正式上线，本轮按局部重构处理，不背旧包袱：

1. 不兼容旧数据。
2. 不兼容旧接口。
3. 不保留 legacy fallback。
4. 不保留旧 DTO 的隐式字段语义。
5. 不允许 runtime 绕过 resolver 直接指定任意 model / worker / path。

如果旧 API path、旧 SDK 方法或旧 CLI 命令无法自然映射到新主体-资源模型，应直接废弃、隐藏或替换，不做兼容层。测试环境和上游 demo 允许按新契约重建 ClientApp、Agent、模型、目录和 WorkerPool。

## 1. Scope

本阶段覆盖：

1. `UpstreamSystemPrincipal`、`UpstreamClientApp`、`UpstreamUser` 三类主体的稳定边界。
2. `Worker`、`LlmConfigModel`、`WorkingDirectory`、`Agent` 四类资源的 owner / grant / binding。
3. `NAVI_ADMIN_API_KEY`、`NAVI_CONTROL_API_KEY`、upstream user token 作为 credential 的解析规则。
4. A2Agent runtime 调用时的统一资源解析。
5. SDK / CLI / OpenAPI 的最小对齐。

本阶段不覆盖：

1. 旧数据迁移。
2. 已存在无 owner 字段资源的自动补齐。
3. 旧 OpenAPI / SDK / CLI 契约兼容。
4. 旧 runtime request 字段兼容。
5. 跨 Navigator tenant 的资源共享。
6. 复杂组织层级、部门层级、角色继承。
7. marketplace 式公开资源分发。

## 2. Non-Migration Baseline

因为不考虑旧数据，实施时采用 fail-closed 策略：

1. 新 runtime 路径只接受带完整 owner / grant / binding 的资源。
2. 缺少 `ownerType` / `ownerId` 的资源不可用于 A2Agent runtime。
3. 缺少 upstream system principal 绑定的 `ADMIN_KEY` 不可继续管理资源。
4. 缺少 ClientApp 绑定的 `CONTROL_KEY` 不可继续管理 App 级资源。
5. 缺少 workspace policy 的 Agent 不默认继承固定 accounts 目录。

如果本地或测试环境存在旧资源，允许通过重建 ClientApp、Agent、模型、目录和 WorkerPool 来恢复。

生产 runtime 不应正常出现 `RESOURCE_OWNER_MISSING`。该错误只作为开发期或灰度期诊断，长期目标是在创建 / 更新阶段就拒绝无 owner 资源。

## 2.1 No Legacy API Compatibility Baseline

无旧接口兼容的具体边界：

1. 旧 endpoint 如果不能表达 `UpstreamSystemPrincipal / UpstreamClientApp / UpstreamUser`，直接下线或改为返回明确错误。
2. 旧 DTO 中把 `clientContext`、`accountId`、`modelConfigId`、`workerId`、`workingDirectoryId` 当隐式 runtime selector 的行为不再保留。
3. 旧 control credential fallback 到 admin credential 的代管路径不再作为新实现默认路径。
4. 旧 ask / A2Agent runtime request 不再接受裸 `modelConfigId`、`workerId`、filesystem path 作为最终执行资源。
5. 旧 “缺字段时自动使用默认 accounts 目录 / 默认模型 / 默认 worker” 的行为不再保留。
6. 旧 SDK / CLI 命令如果语义含混，应替换为 owner-aware 新命令，而不是在旧命令里拼兼容参数。

允许保留的只有显式废弃提示：

```text
410 Gone / 404 Not Found / 403 Forbidden
with diagnostic code: LEGACY_API_REMOVED
```

具体返回码由模块实现时统一，但必须 fail-closed，不允许降级到旧权限模型。

## 3. Target Domain Model

### 3.1 Stable principals

```text
UpstreamSystemPrincipal
  id
  tenantId
  upstreamSystemId
  namespace
  displayName
  status
  createdAt
  updatedAt

UpstreamClientApp
  clientAppId
  tenantId
  upstreamSystemPrincipalId
  namespace
  displayName
  status
  createdAt
  updatedAt

UpstreamUserGrant
  tenantId
  upstreamSystemPrincipalId
  clientAppId
  upstreamUserId
  status
  upstreamUserTokenHash / encryptedToken
  policyJson
  createdAt
  updatedAt
```

`upstreamUserId` 只在 `tenantId + upstreamSystemPrincipalId + clientAppId` 下唯一。

### 3.2 Credentials

```text
UpstreamAdminCredential
  keyId
  tenantId
  upstreamSystemPrincipalId
  keyHash
  scopes
  expiresAt
  status

ClientAppControlCredential
  keyId
  tenantId
  clientAppId
  keyHash
  scopes
  expiresAt
  status
```

credential 只作为 actor 和审计来源，不作为 resource owner。

### 3.3 Resource common fields

四类资源统一补齐：

```text
ownerType: PLATFORM | UPSTREAM_SYSTEM | CLIENT_APP | UPSTREAM_USER
ownerId
tenantId
enabled
disabledReason
createdByCredentialId
createdByPrincipalType
createdByPrincipalId
updatedByCredentialId
metadataJson
```

`ownerId` 的含义：

| ownerType | ownerId |
| --- | --- |
| `PLATFORM` | platform / tenant owner id |
| `UPSTREAM_SYSTEM` | `UpstreamSystemPrincipal.id` |
| `CLIENT_APP` | `clientAppId` |
| `UPSTREAM_USER` | `clientAppId + upstreamUserId` 或独立 normalized id |

每类资源必须限制合法 owner：

| 资源 | 合法 ownerType |
| --- | --- |
| `Worker` / WorkerPool | `PLATFORM` / `UPSTREAM_SYSTEM` |
| `LlmConfigModel` | `PLATFORM` / `UPSTREAM_SYSTEM` / `CLIENT_APP` |
| `WorkingDirectory` | `UPSTREAM_SYSTEM` / `CLIENT_APP` / `UPSTREAM_USER` |
| `Agent` | `UPSTREAM_SYSTEM` / `CLIENT_APP` |

不要因为字段统一就允许所有 ownerType 组合。非法 owner 应在 create / update 阶段拒绝。

## 4. Resource-Specific Design

### 4.1 LlmConfigModel

模型资源先收口，因为它已有 ClientApp-owned model 和 grant 逻辑，风险最小。

Target:

1. Platform model：平台管理员创建，owner 为 `PLATFORM`。
2. Admin shared model：`NAVI_ADMIN_API_KEY` 创建，owner 为 `UPSTREAM_SYSTEM`。
3. ClientApp model：`NAVI_CONTROL_API_KEY` 创建，owner 为 `CLIENT_APP`。

Grant / binding:

```text
ClientAppModelGrant
  tenantId
  clientAppId
  modelConfigId
  grantSource: PLATFORM | UPSTREAM_SYSTEM | CLIENT_APP
  defaultForClientApp
  enabled
  policyJson
```

Runtime 规则：

1. ClientApp-owned model 自动只对本 ClientApp 可见。
2. Admin shared model 需要 explicit grant 或 auto grant policy 后才对 ClientApp 可见。
3. upstream user 不直接拥有模型，只能通过 ClientApp / Agent policy 使用模型。
4. provider key、baseUrl secret、token 不出现在 list / resolve 响应中。

### 4.2 WorkingDirectory

目录显式支持三种上游 scope：

```text
WorkspaceScope = USER_PRIVATE | CLIENT_APP_SHARED | UPSTREAM_SYSTEM_SHARED
```

Target fields:

```text
WorkingDirectory
  directoryId
  tenantId
  ownerType
  ownerId
  workspaceScope
  resolverType: MANAGED | DELEGATED
  rootRef / resolverKey
  readOnly
  allowedPathPrefixesJson
  quotaJson
  retentionPolicyJson
  concurrencyPolicyJson
  enabled
```

Runtime 规则：

1. Agent 必须绑定 workspace policy。
2. 不再从固定 `<data_root>/accounts/<accountId>` 隐式推断。
3. `USER_PRIVATE` 目录按 `clientAppId + upstreamUserId` 隔离。
4. `CLIENT_APP_SHARED` 目录允许同 App 用户共享，但必须显式标注读写策略。
5. `UPSTREAM_SYSTEM_SHARED` 目录默认倾向只读，写入必须显式允许。

### 4.3 Agent

Agent 是运行时入口，负责聚合模型、Worker、目录和工具能力。

Target fields:

```text
Agent
  agentId
  tenantId
  ownerType
  ownerId
  clientAppId optional
  displayName
  status
  defaultModelConfigId
  workerPoolRef
  workspacePolicyJson
  allowedToolsJson
  allowedSkillsJson
  allowedFunctionsJson
```

Binding:

```text
ClientAppAgentGrant
  tenantId
  clientAppId
  agentId
  enabled
  policyJson

AgentUserGrant
  tenantId
  clientAppId
  upstreamUserId
  agentId
  enabled
  policyJson

AgentModelBinding
AgentWorkerBinding
AgentWorkspaceBinding
AgentToolBinding
```

Runtime 规则：

1. A2Agent runtime 默认只传 `clientAppId + upstreamUserId + agentId/contextId`。
2. 不接受浏览器或上游 runtime 直接指定任意 Worker、模型或目录路径。
3. Agent 绑定的资源仍要经过 owner / grant / enabled 校验。
4. `UPSTREAM_SYSTEM` owner 的 Agent 必须先 grant 给 ClientApp，再 grant 或开放给该 ClientApp 下的 upstream user；不能只凭 upstream user grant 绕过 ClientApp 边界。

### 4.4 Worker / WorkerPool

Worker 是执行边界，不应被 upstream user 直接拥有。

Target:

```text
Worker
  workerId
  tenantId
  ownerType: PLATFORM | UPSTREAM_SYSTEM
  ownerId
  capabilitiesJson
  labelsJson
  enabled

WorkerPool
  poolId
  tenantId
  ownerType
  ownerId
  selectorJson
  enabled
```

Grant / binding:

```text
ClientAppWorkerGrant
  tenantId
  clientAppId
  workerId / poolId
  enabled
  policyJson

AgentWorkerBinding
  tenantId
  agentId
  workerId / poolId
  enabled
```

Runtime 规则：

1. Worker 可以由平台或 admin principal 创建。
2. ClientApp 或 Agent 必须显式 grant / bind 后才可使用。
3. Worker capability 必须参与 tool / command / workspace policy 校验。

## 5. Runtime Resolver

新增或收口统一 resolver：

```text
A2AgentResourceResolver.resolve(input)
```

Input:

```json
{
  "tenantId": "...",
  "clientAppId": "...",
  "upstreamUserId": "...",
  "agentId": "...",
  "requestedModelConfigId": "...",
  "contextId": "..."
}
```

Output:

```json
{
  "tenantId": "...",
  "upstreamSystemPrincipalId": "usp_xxx",
  "clientAppId": "...",
  "upstreamUserId": "...",
  "agentId": "...",
  "model": {
    "modelConfigId": "...",
    "ownerType": "CLIENT_APP",
    "grantSource": "ClientAppModelGrant:..."
  },
  "worker": {
    "workerId": "...",
    "poolId": "...",
    "grantSource": "AgentWorkerBinding:..."
  },
  "workspace": {
    "directoryId": "...",
    "workspaceScope": "USER_PRIVATE",
    "readOnly": false,
    "grantSource": "AgentWorkspaceBinding:..."
  },
  "tools": [],
  "skills": [],
  "functions": [],
  "diagnostics": []
}
```

Resolver invariant:

```text
tenant active
+ upstreamSystemPrincipal active
+ clientApp active and belongs to upstreamSystemPrincipal
+ runtime credential valid
+ upstreamUser grant active
+ agent visible to upstreamUser
+ resource owner / grant / binding valid
+ resource enabled
+ runtime policy allows requested use
```

所有 A2Agent / BizWorker runtime 路径必须使用 resolver 输出，不得自行拼接模型、目录或 Worker。

## 6. API Plan

### 6.1 Admin plane

Admin plane 使用 `NAVI_ADMIN_API_KEY`，解析为 `UpstreamSystemPrincipal`。

Endpoints / CLI capabilities:

1. create / rotate / revoke admin credential。
2. ensure ClientApp。
3. create shared LLMConfigModel。
4. create Worker / WorkerPool。
5. create system shared workspace。
6. grant shared model / Worker / workspace to ClientApp or Agent。
7. inspect effective resources by ClientApp / Agent。

### 6.2 ClientApp control plane

ClientApp plane 使用 `NAVI_CONTROL_API_KEY`，解析为 `UpstreamClientApp`。

Endpoints / CLI capabilities:

1. create ClientApp-owned LLMConfigModel。
2. create / update Agent under ClientApp。
3. bind model / workspace / WorkerPool to Agent within visible resource set。
4. ensure upstream user grant。
5. manage user private workspace policy。
6. inspect effective resources by upstream user / Agent。

### 6.3 Runtime plane

Runtime plane 使用 runtime credential + upstream user grant。

Rules:

1. ask / A2Agent 只消费 resolved resources。
2. runtime request 不接受明文 model provider key。
3. runtime request 不接受任意 filesystem path。
4. runtime request 不接受未绑定 Worker id。
5. runtime request 不接受最终执行用的裸 `modelConfigId` / `workerId` / `workingDirectoryId`。如确需用户选择模型，只允许传受 policy 约束的 alias / preference，由 resolver 决定最终资源。

## 7. Implementation Phases

### Phase 1: LlmConfigModel owner / grant 收口

Tasks:

1. 新增或明确 `UpstreamSystemPrincipal`。
2. Admin credential 只解析为 `UpstreamSystemPrincipal`，不再作为 owner。
3. ClientApp control credential 只解析为 `UpstreamClientApp`。
4. 为 model config 补 owner 字段，并在 create / update 阶段强校验合法 owner。
5. Admin 创建模型时写 `UPSTREAM_SYSTEM` owner。
6. ClientApp 创建模型时写 `CLIENT_APP` owner。
7. ClientApp model grant 校验 owner 和 upstream system boundary。
8. 模型 list / resolve 响应脱敏。
9. 移除旧 model runtime selector / fallback 路径。

Acceptance:

1. Admin model grant 给 ClientApp A/B 后都可 resolve。
2. 未 grant 的 ClientApp 不可 resolve。
3. ClientApp A 自建模型，ClientApp B 不可见。
4. key rotation 后 model owner 不变。
5. 旧 ask / A2Agent path 不能通过裸 `modelConfigId` 绕过 grant。
6. 缺 owner 的 model 在创建阶段不可写入，在 runtime 阶段不可使用。

### Phase 2: Resolver skeleton

Tasks:

1. 引入 `A2AgentResourceResolver`。
2. 接入 model resolve。
3. 输出脱敏 diagnostics。
4. A2Agent ask 初步改为从 resolver 获取 model。
5. 移除 ask / A2Agent 中直接读取 request model / worker / directory 的旧逻辑。

Acceptance:

1. 每次 runtime task 可记录 resolved model。
2. 无有效 model 时 fail-closed，返回可诊断错误。
3. 单元测试覆盖旧字段存在但不被直接信任的场景。

### Phase 3: WorkingDirectory scope

Tasks:

1. 给 directory 补 owner / workspaceScope / policy。
2. Agent workspace policy 显式指定目录模式。
3. 实现 `USER_PRIVATE` / `CLIENT_APP_SHARED` / `UPSTREAM_SYSTEM_SHARED` resolve。
4. 禁止 runtime 通过裸 path 选择目录。

Acceptance:

1. 同 ClientApp 下不同 upstream user 的私有目录隔离。
2. ClientApp shared 目录可按 policy 共享。
3. 未绑定 workspace policy 的 Agent 不隐式使用固定 accounts 目录。

### Phase 4: Agent binding

Tasks:

1. Agent 补 owner / clientApp / default model / workspace policy。
2. Agent user grant 接入 upstream user。
3. Agent model / workspace binding 接入 resolver。
4. A2Agent runtime 只通过 agentId 获取绑定资源。

Acceptance:

1. upstream user 只能调用被授权 Agent。
2. Agent 默认模型和目录来自 binding / policy。
3. ClientApp A 的 Agent 不可引用 ClientApp B 私有模型。

### Phase 5: Worker / WorkerPool grant

Tasks:

1. Worker / WorkerPool 补 owner 字段。
2. Admin 创建 shared Worker / WorkerPool。
3. ClientApp / Agent 显式 grant / binding。
4. resolver 输出 Worker / capability / allowed tools。

Acceptance:

1. 未 grant 的 ClientApp 不可使用 Worker。
2. Agent 只能使用绑定 WorkerPool。
3. Worker capability 能限制 command / file / shell 类工具。

### Phase 6: SDK / CLI / Docs 对齐

Tasks:

1. CLI 增加 principal / model / workspace / agent / worker inspect。
2. SDK 暴露 Admin plane 和 ClientApp plane 的 owner-aware DTO。
3. 更新 upstream integration docs。
4. 补 TMS / generic upstream onboarding 示例。
5. 移除或隐藏旧 SDK / CLI 命令；无法移除时必须输出 deprecated / removed 诊断，不再调用旧权限模型。

Acceptance:

1. 上游可通过 CLI 完成从 admin bootstrap 到 runtime ask 的最小闭环。
2. CLI inspect 能解释某个 upstream user 调用某个 Agent 时使用了哪些资源及来源。
3. 旧 CLI / SDK smoke 不再作为验收基线；新 owner-aware smoke 必须通过。

## 8. Test Matrix

| 场景 | 期望 |
| --- | --- |
| Admin key rotate | 旧 key 失效，新 key 仍能管理同一 `UpstreamSystemPrincipal` 的资源 |
| Admin model grant A/B | A/B 都可 resolve；C 不可 resolve |
| ClientApp model isolation | A 创建的模型 B 不可见 |
| Same upstreamUserId under two apps | 记忆、目录、Agent grant、模型可见性均隔离 |
| User grant disabled | ask / A2Agent fail-closed |
| Agent disabled | runtime 不可调用，历史报告仍可读 |
| Directory user private | 用户 A 不能读用户 B 私有目录 |
| Directory shared read-only | shared 目录可读不可写 |
| Worker not granted | runtime 不可选择该 Worker |
| Secret redaction | list / resolve / diagnostics 不返回 provider key 或 token |
| Resolver diagnostics | 失败时能区分 no grant、disabled、not owner、policy denied |
| Legacy model selector bypass | 旧 request 字段传入裸 `modelConfigId` 时不能绕过 resolver |
| Legacy API removed | 旧 endpoint / 命令返回明确 removed / forbidden 诊断，不落入旧权限模型 |

## 9. Diagnostics and Error Codes

建议新增或规范化错误分类：

| Code | 含义 |
| --- | --- |
| `RESOURCE_OWNER_MISSING` | 资源缺少 owner，当前非迁移模式下不可用 |
| `RESOURCE_NOT_GRANTED` | ClientApp / Agent 未被授权使用该资源 |
| `RESOURCE_DISABLED` | 资源已禁用 |
| `PRINCIPAL_DISABLED` | upstream system / ClientApp / upstream user grant 已禁用 |
| `POLICY_DENIED` | workspace / model / worker / tool policy 拒绝 |
| `SECRET_NOT_READABLE` | 调用了不允许读取 secret 的接口 |
| `WORKSPACE_POLICY_REQUIRED` | Agent 缺少 workspace policy |
| `LEGACY_API_REMOVED` | 调用了本轮已移除的旧 API / SDK / CLI 语义 |

这些错误应返回给控制面或 BFF 作为可诊断信息，但不能泄露 secret。

## 10. Open Questions

1. Admin shared resource 是否默认 explicit grant，还是允许某些 resource type 配置 auto grant。
2. `UPSTREAM_USER` owner 是否需要独立 normalized id，还是先使用 `clientAppId + upstreamUserId`。
3. Agent 是否允许同时绑定多个模型并由 runtime policy 选择，还是 Phase 1 只支持默认模型。
4. `CLIENT_APP_SHARED` 目录是否默认允许写入，还是默认只读。
5. WorkerPool 是否优先于单 Worker 暴露给 Agent。
6. 是否需要在 runtime submission 日志中记录完整 resolver output，还是只记录 model / worker / workspace 的脱敏摘要。
7. 旧 API 移除时统一返回 `410 Gone`、`404 Not Found` 还是 `403 Forbidden`。

## 11. Recommended First Implementation Cut

第一刀建议只做：

1. `UpstreamSystemPrincipal` 与 Admin credential 绑定。
2. ClientApp control credential 与 `UpstreamClientApp` 绑定。
3. LLMConfigModel owner / grant 收口。
4. `A2AgentResourceResolver` skeleton 只解析 model。
5. ask / A2Agent runtime 使用 resolver 的 model。
6. 移除旧 model selector / fallback 逻辑。
7. 单测覆盖 admin model、clientApp model、跨 App 隔离、key rotation、legacy bypass fail-closed。

这样能最快验证主体 / owner / grant 模型，不会同时引入工作目录和 Worker 的复杂边界。

## 12. Phase 1 Implementation Checkpoint

2026-05-23 已完成第一刀实现收口：

1. `LlmModelConfig` 增加 `ownerType` / `ownerId` / creator principal / credential / `enabled` 字段。
2. `LlmModelManager.saveModelConfig(...)` 增加 owner-aware 重载；ClientApp 创建和 E2E ensure 均写入 `CLIENT_APP` owner。
3. `ClientAppModelConfigGrantService` 增加模型 owner 可见性校验：
   - `PLATFORM` 可见；
   - `UPSTREAM_SYSTEM` 仅同 system 的 ClientApp 可见；
   - `CLIENT_APP` 仅资源所属 ClientApp 可见；
   - disabled 或 owner 缺失资源 fail-closed。
4. `A2AgentResourceResolver` 已建立第一版，只负责 model resolve。
5. `BusinessAgentTaskService`、OpenAPI ask、readiness preflight 已改为通过 resolver 解析模型，不再直接信任请求里的 `modelConfigId`。
6. ClientApp control credential 去掉 admin credential 代管 fallback，scope 匹配改为 exact / `CONTROL_PLANE_ALL`。
7. Upstream admin credential 去掉旧 `X-Navi-Admin-Api-Key` 别名，只接受 `X-Navi-Admin-Key`。

验证：

```powershell
mvn -pl business-agent-module,addons/claude-worker-agent -am "-Dtest=ClientAppControlCredentialServiceTest,UpstreamClientAppAdminCredentialServiceTest,ClientAppModelConfigGrantServiceTest,ClientAppOwnedModelConfigServiceTest,E2eModelConfigEnsureServiceTest,OpenApiAgentReadinessServiceTest,BusinessAgentTaskServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：76 tests passed, 0 failures, 0 errors。

未进入本 checkpoint：

1. Worker / WorkerPool owner 与 grant。
2. WorkingDirectory owner / policy。
3. Agent owner / runtime policy。
4. SDK / CLI 新 owner-aware 命令。
5. 旧 endpoint 的统一 `LEGACY_API_REMOVED` 响应。

## 13. Phase 2 Resolver Checkpoint

2026-05-23 已完成 resolver skeleton 的工程收口：

1. `A2AgentResourceResolver` 保留 `resolveRequiredModelConfigId(...)` / `resolveOptionalModelConfigId(...)` 字符串出口，避免调用方机械改动。
2. 新增结构化 `ResolvedModelResource`，包含：
   - `modelConfigId`；
   - `requestedModelConfigId`；
   - `category`；
   - `source`，当前为 `REQUESTED_MODEL_GRANT` 或 `DEFAULT_MODEL_GRANT`。
3. resolver 统一输出脱敏诊断日志，只记录 tenant / ClientApp / category / grant source / modelConfigId，不输出 API key 或 provider secret。
4. 新增 `A2AgentResourceResolverTest`，覆盖 requested model、default model 和 optional model absent 三类路径。
5. 修复 `start-launcher.ps1` 全量构建暴露出的测试编译问题：`BusinessAgentLanggraphLaunchE2ETest` 已改为通过 `A2AgentResourceResolver` 构造 `BusinessAgentTaskService`，并按 `GENERAL` category stub model resolve。
6. 顺手修复 `stop-launcher.ps1` 的 PID 显示错误，避免 PowerShell 自动变量 `$pid` 被误打印成当前 shell PID。

验证：

```powershell
mvn -pl addons/langgraph-biz-worker -am "-Dtest=BusinessAgentLanggraphLaunchE2ETest" "-Dsurefire.failIfNoSpecifiedTests=false" test

mvn -pl business-agent-module,addons/claude-worker-agent -am "-Dtest=A2AgentResourceResolverTest,ClientAppControlCredentialServiceTest,UpstreamClientAppAdminCredentialServiceTest,ClientAppModelConfigGrantServiceTest,ClientAppOwnedModelConfigServiceTest,E2eModelConfigEnsureServiceTest,OpenApiAgentReadinessServiceTest,BusinessAgentTaskServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test

powershell -NoProfile -ExecutionPolicy Bypass -File .\start-launcher.ps1
```

结果：

1. `BusinessAgentLanggraphLaunchE2ETest`: 2 tests passed, 0 failures, 0 errors。
2. Phase 1 + resolver target suite: 76 tests passed, 0 failures, 0 errors。
3. `start-launcher.ps1` full build / restart 成功，`launcher-1.0.0-SNAPSHOT.jar` 重新生成，`http://localhost:8112/actuator/health` 返回 `UP`。

下一阶段进入更大资源边界，不再继续扩展 model-only resolver：

1. WorkingDirectory owner / workspace policy。
2. Agent owner / model / workspace / Worker binding。
3. Worker / WorkerPool owner / grant / capability。
4. SDK / CLI 的 owner-aware 命令与 inspect effective resource。

## 14. Phase 3 WorkingDirectory Scope Checkpoint

2026-05-23 已完成 WorkingDirectory scope 的最小工程闭环：

1. `WorkingDirectoryEntity` 增加 owner / scope / resolver / policy 字段：
   - `ownerType` / `ownerId`；
   - `clientAppId` / `upstreamUserId`；
   - `workspaceScope`：`USER_PRIVATE` / `CLIENT_APP_SHARED` / `UPSTREAM_SYSTEM_SHARED`；
   - `resolverType`：`MANAGED` / `DELEGATED`；
   - `rootRef` / `resolverKey`；
   - `readOnly` / `allowedPathPrefixesJson` / quota / retention / concurrency / `enabled`。
2. `CreateWorkingDirectoryForm`、`UpdateWorkingDirectoryForm`、`WorkingDirectoryDTO` 已对齐上述字段。
3. `WorkingDirectoryService` 在 create / update / worktree copy 时统一落 owner policy：
   - 默认 `UPSTREAM_USER + USER_PRIVATE + DELEGATED`；
   - 拒绝 `PLATFORM` owner；
   - `CLIENT_APP_SHARED` 必须由 `CLIENT_APP` owner 持有并声明 `clientAppId`；
   - `UPSTREAM_SYSTEM_SHARED` 必须由 `UPSTREAM_SYSTEM` owner 持有。
4. `A2AgentResourceResolver` 增加 workspace resolve：
   - 通过 `directoryId` 查找目录；
   - 校验 tenant、enabled、owner、scope、ClientApp、upstream user 与 upstream system boundary；
   - 输出 `ResolvedWorkspaceResource`，包含 `workdir`、`allowedDirs`、scope、resolver type、readOnly 等脱敏信息。
5. A2Agent runtime 不再接受上游直接传 `workdir` / `allowedDirs` 作为最终执行目录：
   - `CreateBusinessAgentTaskForm` 新增 `directoryId`；
   - 如果 runtime request 继续传 legacy `workdir` / `allowedDirs`，直接 fail-closed；
   - `BusinessAgentTaskEntity` / DTO 持久化 `directoryId`；
   - `BusinessAgentWorkerTaskLaunchRequest` 只使用 resolver 输出的目录和 allowed dirs。
6. LangGraph launcher 已将 resolved workspace 写入 worker context 与 execution policy：
   - `directoryId` / `workingDirectoryId`；
   - `workspaceScope`；
   - `workspaceResolverType`；
   - `workspaceReadOnly`；
   - `execution_policy.directory_id` / `workspace_scope` / `workspace_resolver_type` / `read_only`。

验证：

```powershell
mvn -pl addons/claude-worker-agent -am "-Dtest=WorkingDirectoryServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test

mvn -pl business-agent-module,addons/langgraph-biz-worker,addons/claude-worker-agent -am "-Dtest=A2AgentResourceResolverTest,BusinessAgentTaskServiceTest,LanggraphBusinessAgentWorkerTaskLauncherTest,BusinessAgentLanggraphLaunchE2ETest,WorkingDirectoryServiceTest,BusinessAgentE2ESampleTest,RestAdapterUpstreamE2ETest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：

1. `WorkingDirectoryServiceTest`: 30 tests passed, 0 failures, 0 errors。
2. Phase 3 target suite:
   - business-agent-module: 33 tests passed；
   - claude-worker-agent: 30 tests passed；
   - langgraph-biz-worker: 9 tests passed；
   - 0 failures, 0 errors。

刻意不保留的旧能力：

1. 上游 runtime 直接传 filesystem path。
2. 上游 runtime 直接传 allowed dirs。
3. 缺 owner / scope / resolver 的目录被隐式当成可用目录。
4. 固定 `<data_root>/accounts/<accountId>` 的隐式兜底目录。

未进入本 checkpoint：

1. AgentWorkspaceBinding 独立表与 Agent workspace policy 完整接入。
2. Agent owner / ClientApp grant / upstream user grant 的完整收口。
3. Worker / WorkerPool owner、grant 与 capability policy。
4. SDK / CLI 的 owner-aware working directory 创建、绑定和 inspect 命令。
5. 旧 API / integration smoke 对 `workdir` / `allowedDirs` 的替换；后续应改为先创建 visible directory，再传 `directoryId`。

## 15. Phase 4 Agent Binding Checkpoint

2026-05-24 已完成 Agent binding 的最小工程闭环：

1. `CodingAgentEntity` 增加 owner / ClientApp / enabled 字段：
   - `ownerType` / `ownerId`；
   - `clientAppId`；
   - `enabled`；
   - owner 与 ClientApp 查询索引。
2. Agent 创建与同步路径写入明确归属：
   - ClientApp bundle sync 创建 `CLIENT_APP` owner 的 Agent；
   - TMS root Agent provisioning 写入 `CLIENT_APP` owner、`clientAppId`、`enabled=true`。
3. `A2AgentResourceResolver` 增加 Agent resolve：
   - 只允许 tenant 匹配且 enabled 的 Agent；
   - owner 缺失 fail-closed；
   - `CLIENT_APP` owner 只对本 ClientApp 可见；
   - `UPSTREAM_SYSTEM` owner 只对同 upstream system 下的 ClientApp 可见；
   - 暂不允许 `PLATFORM` / `UPSTREAM_USER` owner 的 Agent 进入 runtime；
   - 输出 `ResolvedAgentResource`，包含 agent、skill、worker pool、默认 model、默认 directory 与来源诊断。
4. A2Agent runtime 入口改为只接受 `agentId`：
   - `CreateBusinessAgentTaskForm` 增加 `agentId` / `agent_id`；
   - `BusinessAgentTaskEntity` / DTO 持久化 `agentId`；
   - runtime 直接传 `skillId` / `workerPoolId` 已 fail-closed；
   - `skillName` 只允许作为展示名，不能覆盖 Agent 绑定的 skill。
5. `BusinessAgentTaskService` 使用 Agent resolve 输出组装执行资源：
   - skill / worker pool 来自 Agent；
   - model 默认优先请求模型，其次 Agent 默认模型；
   - directory 优先请求 `directoryId`，其次恢复任务目录，再其次 Agent 默认目录；
   - resume 会校验同一 task 仍绑定同一 Agent。
6. LangGraph launch request 增加 `agentId`，并在 worker context 中写入 `businessAgentId`。
7. OpenAPI Agent route fail-closed：
   - 缺失 Agent 不再 fallback 为 legacy skill route；
   - route resolve 校验 Agent enabled、owner、ClientApp / upstream system boundary；
   - readiness 的 root binding 不再输出 legacy fallback 语义。

验证：

```powershell
mvn -pl addons/claude-worker-agent -am "-Dtest=OpenApiControllerMessageMappingTest" "-Dsurefire.failIfNoSpecifiedTests=false" test

mvn -pl business-agent-module,addons/claude-worker-agent,addons/langgraph-biz-worker -am "-Dtest=BusinessAgentTaskControllerTest,UpstreamTenantClientAppProvisioningServiceTest,OpenApiAgentRouteServiceTest,OpenApiAgentReadinessServiceTest,OpenApiControllerMessageMappingTest,A2AgentResourceResolverTest,BusinessAgentTaskServiceTest,BusinessAgentBundleServiceTest,LanggraphBusinessAgentWorkerTaskLauncherTest,BusinessAgentLanggraphLaunchE2ETest,BusinessAgentE2ESampleTest,RestAdapterUpstreamE2ETest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：

1. `OpenApiControllerMessageMappingTest`: 18 tests passed, 0 failures, 0 errors。
2. Phase 4 target suite:
   - business-agent-module: 48 tests passed；
   - claude-worker-agent: 33 tests passed；
   - langgraph-biz-worker: 9 tests passed；
   - 0 failures, 0 errors。

刻意不保留的旧能力：

1. OpenAPI 缺失 Agent 时自动把 `agentId` 当 skill route。
2. Runtime request 直接指定最终执行 `skillId`。
3. Runtime request 直接指定最终执行 `workerPoolId`。
4. Agent 缺 owner / disabled 时被 runtime 使用。

未进入本 checkpoint：

1. 独立 `ClientAppAgentGrant` / `AgentUserGrant` 表。
2. AgentModelBinding / AgentWorkspaceBinding / AgentWorkerBinding 独立表。
3. Worker / WorkerPool owner、grant 与 capability policy。
4. SDK / CLI 的 owner-aware Agent 创建、绑定和 inspect 命令。
5. OpenAPI DTO 的公开文档与上游示例更新。

## 16. Phase 5 Worker / WorkerPool Checkpoint

2026-05-24 已完成 Worker / WorkerPool owner 的最小工程闭环：

1. `BizWorkerIdentityEntity` 增加 Worker owner 与能力元数据：
   - `ownerType` / `ownerId`；
   - `capabilitiesJson`；
   - `labelsJson`；
   - owner 查询索引。
2. `BizWorkerPoolEntity` 增加 WorkerPool owner 与能力元数据：
   - `ownerType` / `ownerId`；
   - `capabilitiesJson`；
   - `labelsJson`；
   - owner 查询索引。
3. `BizWorkerIdentityDTO` / `BizWorkerPoolDTO` 输出 owner 与能力元数据，便于控制面 inspect。
4. `BizWorkerPoolService` 增加 owner-aware 创建入口：
   - 平台 UI / SUPER_ADMIN 入口默认创建 `PLATFORM` owner 的 worker identity；
   - 平台 UI / SUPER_ADMIN 创建 pool 时默认 `PLATFORM` owner，`ownerId=tenantId`；
   - upstream-admin 创建 pool 时写入 `UPSTREAM_SYSTEM` owner，`ownerId=principal.upstreamSystemId`；
   - 非 `PLATFORM` / `UPSTREAM_SYSTEM` owner 的 Worker / WorkerPool 在创建阶段拒绝。
5. WorkerPool 加成员时增加 owner 可见性校验：
   - `PLATFORM` worker identity 可加入可见 pool；
   - `UPSTREAM_SYSTEM` worker identity 只能加入同 owner 的 `UPSTREAM_SYSTEM` pool；
   - 缺 owner 的 worker / pool fail-closed。
6. `A2AgentResourceResolver` 校验 Agent 绑定的 WorkerPool：
   - pool 必须存在于同 tenant；
   - pool 必须 enabled；
   - pool 必须有 owner；
   - `PLATFORM` pool 作为 tenant scoped shared infrastructure 可见；
   - `UPSTREAM_SYSTEM` pool 只对同 upstream system 下的 ClientApp 可见；
   - `CLIENT_APP` / `UPSTREAM_USER` owner 的 pool 不能进入 runtime；
   - `ResolvedAgentResource` 输出 `workerPoolOwnerType` / `workerPoolOwnerId` / `workerPoolSource`。

验证：

```powershell
mvn -pl business-agent-module -am "-Dtest=A2AgentResourceResolverTest,BizWorkerPoolServiceTest,BusinessAgentTaskServiceTest,BusinessAgentE2ESampleTest,RestAdapterUpstreamE2ETest" "-Dsurefire.failIfNoSpecifiedTests=false" test

mvn -pl addons/langgraph-biz-worker -am "-Dtest=BusinessAgentLanggraphLaunchE2ETest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：

1. Business Agent Phase 5 suite: 46 tests passed, 0 failures, 0 errors。
2. LangGraph launch E2E: 2 tests passed, 0 failures, 0 errors。

刻意不保留的旧能力：

1. Agent 绑定无 owner 的 WorkerPool。
2. Agent 绑定其他 upstream system owner 的 WorkerPool。
3. `CLIENT_APP` / `UPSTREAM_USER` owner 的 WorkerPool 进入 runtime。
4. `UPSTREAM_SYSTEM` worker identity 随意加入其他 owner 的 WorkerPool。

未进入本 checkpoint：

1. 独立 `ClientAppWorkerGrant` 表。
2. 独立 `AgentWorkerBinding` 表。
3. Worker capability 对 command / file / shell 工具的运行时强约束。
4. upstream-admin 注册 `UPSTREAM_SYSTEM` owner worker identity 的独立公开 endpoint；当前服务层已具备 owner-aware primitive，控制面还未单独收口。
5. SDK / CLI 的 owner-aware Worker / WorkerPool 创建、绑定和 inspect 命令。

## 17. Phase 6 SDK / CLI Runtime Inspect Checkpoint

2026-05-24 已完成上游 runtime inspect 的最小工程闭环：

1. OpenAPI preflight / readiness 输出运行时资源归属：
   - Agent: `agentId`、`agentOwnerType`、`agentOwnerId`、`agentSource`、`skillId`；
   - Model: `requestedModelConfigId`、`defaultModelConfigId`、`effectiveModelConfigId`、`modelConfigSource`、`modelCategory`；
   - WorkerPool: `workerPoolId`、`workerPoolOwnerType`、`workerPoolOwnerId`、`workerPoolSource`；
   - Workspace: `requestedDirectoryId`、`defaultDirectoryId`、`effectiveDirectoryId`、`workspaceScope`、`workspaceResolverType`、`workspaceReadOnly`、`workspaceSource`。
2. Readiness 复用 `A2AgentResourceResolver`：
   - 增加 `RUNTIME_AGENT_RESOURCE` 检查；
   - Agent/WorkerPool 可见性与 runtime 创建任务保持同一套 fail-closed 规则；
   - Model 默认值与 runtime 创建任务对齐：请求模型优先，其次 Agent 默认模型，再进入 ClientApp grant resolve；
   - Workspace resolve 支持显式 `directoryId`，未传时使用 Agent 默认目录；无目录绑定时返回可诊断的 `WORKSPACE_RESOURCE=OK`。
3. `AgentReadinessPreflightForm` 增加 `directoryId`，用于上游 inspect 预检某个显式工作目录。
4. `navigator-open-sdk` 的 `AgentReadiness` 模型同步新增资源归属字段。
5. CLI 增加 `inspect runtime`：
   - 实现上复用 `/api/v1/open/agents/{agentId}/preflight`；
   - 支持 `--directory-id` / `NAVI_DIRECTORY_ID`；
   - 输出 Agent / Model / WorkerPool / Workspace 的实际来源，方便上游判断资源是否来自 Admin、ClientApp、UpstreamSystem 或 UpstreamUser。

验证：

```powershell
mvn -pl addons/claude-worker-agent -am "-Dtest=OpenApiAgentReadinessServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test

mvn -pl navigator-open-sdk "-Dtest=UpstreamCliTest,DirectoryApiTest" test
```

结果：

1. `OpenApiAgentReadinessServiceTest`: 12 tests passed, 0 failures, 0 errors。
2. `UpstreamCliTest`: 53 tests passed, 0 failures, 0 errors。

刻意不保留的旧能力：

1. inspect 通过旧 skill / workerPool / workdir runtime selector 计算资源。
2. readiness 只输出 `effectiveModelConfigId`，让上游无法判断资源归属来源。
3. workspace inspect 隐式接受 filesystem path；只允许 `directoryId`。

未进入本 checkpoint：

1. CLI / SDK 完整 owner-aware bootstrap 向导。
2. upstream-admin 注册 `UPSTREAM_SYSTEM` owner worker identity 的公开 endpoint。
3. Worker capability 对文件 / shell 工具的运行时强约束。
4. 独立 `ClientAppWorkerGrant`、`AgentWorkerBinding`、`AgentWorkspaceBinding` 表。
5. 前端管理页的资源归属展示。

## 18. Phase 7 Upstream Admin Worker Identity Checkpoint

2026-05-24 已补齐上游系统主体自助注册 BizWorker identity 的最小控制面闭环：

1. 新增 `POST /api/v1/upstream-admin/worker-identities`：
   - 使用 `X-Navi-Admin-Key`；
   - 要求 `WORKER_POOL_MANAGE` scope；
   - worker identity owner 固定写为 `UPSTREAM_SYSTEM`；
   - `ownerId=principal.upstreamSystemId`；
   - 继续复用 `BizWorkerPoolService.registerWorkerIdentity(ownerType, ownerId, form)`。
2. 保持平台侧原入口语义：
   - `POST /api/v1/business-agent/worker-identities` 仍是平台 / SUPER_ADMIN 入口；
   - 平台入口创建 `PLATFORM` owner worker identity；
   - 上游入口不能创建 `PLATFORM` worker。
3. SDK 增加 `registerUpstreamWorkerIdentity(form)`，用于 owner-aware bootstrap。
4. CLI 增加：
   - `worker-pool register-worker --file <json> [--write-profile]`；
   - `--write-profile` 写入 `NAVI_BIZ_WORKER_ID`；
   - `worker-pool add-member` 未传 `--worker-id` 时优先使用 `NAVI_BIZ_WORKER_ID`，再回退旧 `NAVI_WORKER_ID`。
5. 上游 CLI 使用文档补充 BizWorker identity -> WorkerPool -> member 的完整顺序。

验证：

```powershell
mvn -pl business-agent-module -am "-Dtest=UpstreamAdminWorkerIdentityControllerTest,BizWorkerControlPlaneAuthorizationTest,BizWorkerPoolServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test

mvn -pl navigator-open-sdk "-Dtest=UpstreamCliTest" test
```

结果：

1. `UpstreamAdminWorkerIdentityControllerTest` / `BizWorkerControlPlaneAuthorizationTest` / `BizWorkerPoolServiceTest`: 28 tests passed, 0 failures, 0 errors。
2. `UpstreamCliTest`: 53 tests passed, 0 failures, 0 errors。

刻意不保留的旧能力：

1. 上游用平台 SUPER_ADMIN worker identity 入口伪装注册自有 worker。
2. `worker-pool add-member` 只能读取通用 `NAVI_WORKER_ID`，导致 Coding Worker 与 BizWorker identity 混用。
3. 上游创建 worker identity 时自行指定 owner。

未进入本 checkpoint：

1. 独立 Worker identity list / disable endpoint。
2. Worker capability 对文件 / shell 工具的运行时强约束。
3. 独立 `ClientAppWorkerGrant`、`AgentWorkerBinding`、`AgentWorkspaceBinding` 表。
4. 前端管理页的资源归属展示。

## 19. Phase 8 Upstream Admin Model Config Checkpoint

2026-05-24 已补齐上游系统主体自助创建共享 LLMConfigModel 的最小控制面闭环：

1. 新增 `UpstreamAdminModelConfigController`：
   - `GET /api/v1/upstream-admin/model-configs`；
   - `POST /api/v1/upstream-admin/model-configs`；
   - `PUT /api/v1/upstream-admin/model-configs/{modelConfigId}`；
   - `PUT /api/v1/upstream-admin/model-configs/{modelConfigId}/key`；
   - 使用 `X-Navi-Admin-Key`；
   - 要求 `MODEL_CONFIG_MANAGE` scope；
   - 支持 `targetTenantId`，未传时仅在 upstream system 只绑定一个 tenant 时自动推断。
2. 新增 `UpstreamAdminModelConfigService`：
   - 创建模型时 owner 固定写为 `UPSTREAM_SYSTEM`；
   - `ownerId=principal.upstreamSystemId`；
   - `createdByType=UPSTREAM_SYSTEM`；
   - `createdById=principal.upstreamSystemId`；
   - `createdByCredentialId=principal.credentialId`；
   - list / update / rotate key 均 fail-closed 到同一个 upstream system owner；
   - 不接受上游自行指定 owner。
3. 保持 ClientApp 控制面语义：
   - `model create` / `model update` / `model rotate-key` 只使用 `NAVI_CONTROL_API_KEY`；
   - ClientApp 创建的模型 owner 为 `CLIENT_APP`；
   - 不再允许 `NAVI_ADMIN_API_KEY` fallback 到 ClientApp 模型控制面。
4. SDK 增加 UpstreamSystem 模型配置 API：
   - `listUpstreamSystemModelConfigs(targetTenantId)`；
   - `createUpstreamSystemModelConfig(form, targetTenantId)`；
   - `updateUpstreamSystemModelConfig(modelConfigId, form, targetTenantId)`；
   - `rotateUpstreamSystemModelConfigKey(modelConfigId, form, targetTenantId)`。
5. CLI 增加共享模型命令：
   - `model system-list`；
   - `model system-create`；
   - `model system-update`；
   - `model system-rotate-key`；
   - `model system-create --write-profile` 写入 `NAVI_MODEL_CONFIG_ID`，方便后续 grant 或 runtime smoke。
6. 上游 CLI 文档明确两条模型配置路径：
   - `model create/update/rotate-key`：ClientApp 私有模型配置，使用 `NAVI_CONTROL_API_KEY`；
   - `model system-create/system-update/system-rotate-key`：UpstreamSystem 共享模型配置，使用 `NAVI_ADMIN_API_KEY`；
   - 共享模型要进入 A2Agent runtime，仍需要 ClientApp grant 或后续绑定机制显式授权。

验证：

```powershell
mvn -pl business-agent-module -am "-Dtest=UpstreamAdminModelConfigServiceTest,UpstreamAdminModelConfigControllerTest,BizWorkerControlPlaneAuthorizationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test

mvn -pl navigator-open-sdk "-Dtest=UpstreamCliTest" test
```

刻意不保留的旧能力：

1. `model create` 通过 `NAVI_ADMIN_API_KEY` 或旧 `NAVI_ADMIN_TOKEN` 进入 ClientApp 控制面。
2. 上游在创建模型配置时自行传入 owner。
3. UpstreamSystem A 更新或轮换 UpstreamSystem B / ClientApp / Platform owner 的模型配置。
4. 未明确 tenant 时，在 upstream system 绑定多个 tenant 的情况下猜测目标 tenant。

未进入本 checkpoint：

1. 独立 Model grant 表。
2. AgentModelBinding 独立表。
3. 前端管理页的共享模型与 ClientApp 私有模型展示。
4. 共享模型被 ClientApp grant 后的可见性审计 UI。

## 20. Phase 9 Upstream Admin Working Directory Checkpoint

2026-05-24 已补齐上游系统主体自助初始化共享工作目录的最小控制面隔离：

1. 收口 `UpstreamAdminWorkerDirectoryController`：
   - `POST /api/v1/upstream-admin/directories/init` 使用 `X-Navi-Admin-Key`；
   - 要求 `WORKING_DIRECTORY_MANAGE` scope；
   - 初始化后目录 owner 固定写为 `UPSTREAM_SYSTEM`；
   - `ownerId=principal.upstreamSystemId`；
   - `workspaceScope=UPSTREAM_SYSTEM_SHARED`；
   - `resolverType=DELEGATED`；
   - `rootRef` 默认写入 resolved path；
   - 上游不能通过 init 请求自行指定 owner。
2. 目录可见性 fail-closed：
   - `GET /api/v1/upstream-admin/directories` 只返回当前 `UpstreamSystemPrincipal` 拥有的目录；
   - 按 `workerId` 列表时同时校验 Worker tenant 和目录 tenant；
   - `GET / DELETE / env / files` 均要求目录属于当前 upstream system；
   - `ownerType` 缺失、`ownerId` 不匹配、或 `CLIENT_APP` / `UPSTREAM_USER` owner 的目录不会被 upstream-admin 目录控制面访问。
3. 初始化幂等路径收口：
   - 如果同 worker/path 已有目录，只有当前 upstream system owner 的目录允许复用；
   - 其他 owner 的同路径目录直接拒绝，不继续进入目录控制面；
   - facade 内部创建出的未盖章目录只在本次 init 返回路径中被立即盖章，普通查询不做 legacy 自动补 owner。
4. DTO / SDK / CLI 对齐：
   - `WorkingDirectoryDTO` 在 upstream-admin 目录响应中返回 owner / scope / resolver 关键字段；
   - `navigator-open-sdk Directory` 增加 owner / workspace scope / resolver / enabled 等字段；
   - `navi upstream directory list/get/init` 输出 `ownerType`、`ownerId`、`workspaceScope`、`resolverType`、`enabled`，便于上游排查资源边界。
5. 旧 open directory API 直接退役：
   - `/api/v1/open/directories/*` 不再执行租户级目录创建、列表、读取、删除、env 或 files 更新；
   - SDK 旧方法 `client.directories().init/list/listByWorker/get/delete/updateEnvVars/updateFiles` 本地直接抛出 `LEGACY_API_REMOVED`；
   - 上游必须使用 `X-Navi-Admin-Key` 调用 upstream-admin 目录 API，或后续 ClientApp owner-aware workspace API；
   - 不保留旧接口兼容，避免绕过 `UpstreamSystemPrincipal / UpstreamClientApp / UpstreamUser` 资源归属模型。

验证：

```powershell
mvn -pl addons/claude-worker-agent -am "-Dtest=UpstreamAdminWorkerDirectoryControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test

mvn -pl navigator-open-sdk "-Dtest=UpstreamCliTest" test
```

结果：

1. `UpstreamAdminWorkerDirectoryControllerTest`: 3 tests passed, 0 failures, 0 errors。
2. `UpstreamCliTest`: 54 tests passed, 0 failures, 0 errors。
3. `DirectoryApiTest`: 旧 open directory SDK 方法本地退役断言通过。

刻意不保留的旧能力：

1. UpstreamSystem A 通过同租户目录列表看到 UpstreamSystem B 的目录。
2. UpstreamSystem 管理面读写 `CLIENT_APP` / `UPSTREAM_USER` owner 的目录。
3. 上游通过目录初始化请求自行指定 owner。
4. 普通查询自动为历史缺 owner 目录补 owner。
5. 继续开放 `/api/v1/open/directories/*` 作为租户级绕行入口。

未进入本 checkpoint：

1. ClientApp control plane 创建 `CLIENT_APP_SHARED` / `USER_PRIVATE` 工作目录。
2. `AgentWorkspaceBinding` 独立表。
3. runtime workspace resolver 对绑定目录的读写策略执行。
4. Worker capability 对文件 / shell 类工具的强约束。

## 21. Phase 10 ClientApp Working Directory Checkpoint

2026-05-24 已补齐 ClientApp 控制面自助初始化应用共享 / 用户私有工作目录的最小闭环：

1. 新增 `ClientAppWorkingDirectoryController`：
   - `POST /api/v1/client-apps/{clientAppId}/directories/init`；
   - `GET /api/v1/client-apps/{clientAppId}/directories`；
   - `GET /api/v1/client-apps/{clientAppId}/directories/{directoryId}`；
   - `DELETE /api/v1/client-apps/{clientAppId}/directories/{directoryId}`；
   - `PUT /api/v1/client-apps/{clientAppId}/directories/{directoryId}/env`；
   - `PUT /api/v1/client-apps/{clientAppId}/directories/{directoryId}/files`；
   - 使用 `X-Client-App-Control-Key`；
   - 要求 `WORKING_DIRECTORY_MANAGE` scope。
2. 初始化规则：
   - `workspaceScope=CLIENT_APP_SHARED` 时，owner 固定写为 `CLIENT_APP`，`ownerId=clientAppId`，不允许传 `upstreamUserId`；
   - `workspaceScope=USER_PRIVATE` 时，owner 固定写为 `UPSTREAM_USER`，`ownerId=clientAppId:upstreamUserId`，必须传 `upstreamUserId`；
   - ClientApp 控制面拒绝创建 `UPSTREAM_SYSTEM_SHARED`，该 scope 只能由 upstream admin 目录 API 创建；
   - `resolverType` 默认 `DELEGATED`，`rootRef` 默认目录 path；
   - 上游不能通过请求自行指定 owner。
3. 可见性规则：
   - ClientApp 控制面只返回同 tenant、同 `clientAppId` 的目录；
   - 只暴露 `CLIENT_APP_SHARED` 与 `USER_PRIVATE`；
   - 不暴露 `UPSTREAM_SYSTEM_SHARED`；
   - `GET / DELETE / env / files` 都先执行 owner-aware 解析，不允许跨 ClientApp 访问。
4. 路径冲突规则：
   - 如果同 worker/path 已存在目录，只有当前 ClientApp 可见的目录允许复用并盖章；
   - 其他 owner 的同路径目录直接拒绝，避免 ClientApp 抢占 upstream system 或其他 ClientApp 的工作目录。
5. SDK / CLI 对齐：
   - `DirectoryApi` 增加 `initWithClientAppControl(...)`；
   - 增加 `listWithClientAppControl(...)`、`getWithClientAppControl(...)`、`deleteWithClientAppControl(...)`；
   - 增加 `updateEnvVarsWithClientAppControl(...)`、`updateFilesWithClientAppControl(...)`；
   - 认证使用 `NavigatorClient.builder().controlApiKey(...)` 注入的 `X-Client-App-Control-Key`；
   - `navi upstream directory client-init/client-list/client-get/client-delete/client-env/client-files` 走 ClientApp owner-aware 目录 API；
   - `client-init --write-profile` 保存 `NAVI_DIRECTORY_ID`；
   - `client-list` 只在显式传入 `--upstream-user-id` 时按用户过滤，不使用 profile 中的 `NAVI_UPSTREAM_USER_ID` 隐式收窄列表。

验证：

```powershell
mvn -pl addons/claude-worker-agent -am "-Dtest=ClientAppWorkingDirectoryControllerTest,UpstreamAdminWorkerDirectoryControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test

mvn -pl navigator-open-sdk "-Dtest=DirectoryApiTest,UpstreamCliTest" test
```

结果：

1. `ClientAppWorkingDirectoryControllerTest` / `UpstreamAdminWorkerDirectoryControllerTest`: 7 tests passed, 0 failures, 0 errors。
2. `DirectoryApiTest` / `UpstreamCliTest`: 60 tests passed, 0 failures, 0 errors。

刻意不保留的旧能力：

1. ClientApp 控制面创建 `UPSTREAM_SYSTEM_SHARED` 目录。
2. ClientApp 控制面通过裸 path 或旧 open directory API 绕过 owner/scope。
3. ClientApp A 读取、删除或修改 ClientApp B / UpstreamSystem 的目录。
4. `USER_PRIVATE` 目录缺少 `upstreamUserId`。
5. `CLIENT_APP_SHARED` 目录混入 `upstreamUserId`。

未进入本 checkpoint：

1. `AgentWorkspaceBinding` 独立表。
2. Runtime 对目录读写策略的执行层审计。
3. Worker capability 对文件 / shell 类工具的强约束。

## 22. Phase 11 Agent Workspace Binding Runtime Checkpoint

2026-05-24 已补齐 A2Agent runtime 对 Agent 工作目录绑定的最小强约束：

1. `AgentDirectoryBindingEntity` 收口为 tenant-scoped binding：
   - 新增 `tenantId`；
   - 唯一键调整为 `tenantId + agentId + directoryId`；
   - 查询索引按 `tenantId + agentId`、`tenantId + directoryId` 建立；
   - 不再允许跨 tenant 复用同一个裸 `agentId + directoryId` 判断绑定。
2. Agent 管理侧绑定 API 改为 tenant-aware：
   - Agent 注册时，如果存在 `defaultDirectoryId`，自动写入同 tenant 的目录绑定；
   - bind / unbind / list / delete 均先解析 Agent tenant，再按 `tenantId + agentId + directoryId` 操作；
   - `toDTO` 返回绑定目录时只读取同 tenant 绑定。
3. A2Agent resource resolver 增加 agent-aware workspace 解析：
   - `resolveOptionalWorkspaceForAgent(...)`；
   - `resolveRequiredWorkspaceForAgent(...)`；
   - 先执行目录 owner/scope 可见性校验；
   - 再执行 Agent workspace policy 校验；
   - Agent 默认目录允许直接使用，source 标记为 `AGENT_DEFAULT_DIRECTORY:<scope>`；
   - 非默认目录必须存在显式 Agent 目录绑定，source 标记为 `AGENT_WORKSPACE_BINDING:<scope>`；
   - 目录虽然对 ClientApp / UpstreamUser 可见，但未绑定到 Agent 时，runtime fail-closed。
4. A2Agent runtime 接入：
   - `BusinessAgentTaskService` 创建任务时改用 agent-aware workspace resolver；
   - `OpenApiAgentReadinessService` preflight / readiness 也改用同一套 agent-aware workspace resolver；
   - Worker launch request 中继续只携带 resolver 已确认的目录资源，避免 worker 侧再次猜测目录可用性。
5. 测试覆盖：
   - 覆盖无目录参数时返回 empty；
   - 覆盖 Agent 默认目录无需显式 binding；
   - 覆盖非默认目录存在 binding 时允许；
   - 覆盖非默认目录未绑定时拒绝；
   - 覆盖 task service、readiness、langgraph launch e2e 的 agent-aware workspace 解析链路；
   - 覆盖 Agent bind / unbind / list / delete 的 tenant-aware repository 调用。

验证：

```powershell
mvn -pl business-agent-module -am "-Dtest=A2AgentResourceResolverTest,BusinessAgentTaskServiceTest,BusinessAgentE2ESampleTest,RestAdapterUpstreamE2ETest" "-Dsurefire.failIfNoSpecifiedTests=false" test

mvn -pl addons/claude-worker-agent -am "-Dtest=CodingAgentServiceTest,OpenApiAgentReadinessServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test

mvn -pl addons/langgraph-biz-worker -am "-Dtest=BusinessAgentLanggraphLaunchE2ETest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：

1. `business-agent-module` targeted suite: 42 tests passed, 0 failures, 0 errors。
2. `claude-worker-agent` targeted suite: 31 tests passed, 0 failures, 0 errors。
3. `langgraph-biz-worker` launch e2e suite: 2 tests passed, 0 failures, 0 errors。

刻意不保留的旧能力：

1. runtime 只要目录对 ClientApp / UpstreamUser 可见就可被任意 Agent 使用。
2. readiness 只校验目录可见性，不校验 Agent 绑定关系。
3. Agent 目录绑定缺失 tenant 仍参与 runtime 授权判断。
4. 跨 tenant 通过相同 `agentId` / `directoryId` 命中绑定。

未进入本 checkpoint：

1. Runtime 对目录 `readOnly`、quota、concurrency policy 的执行层审计。
2. Worker capability 对文件 / shell 类工具的强约束。
3. Agent model binding 独立表。

## 23. Phase 12 Workspace Execution Policy Payload Checkpoint

2026-05-24 已补齐工作目录执行策略从资源解析层到 LangGraph worker launch payload 的结构化透传。

目标：

1. 上游创建目录时已经可以配置 `quotaJson`、`retentionPolicyJson`、`concurrencyPolicyJson`。
2. A2Agent runtime 选择工作目录后，不应只把 `workdir` / `allowedDirs` 交给 worker，还应把与该目录绑定的执行策略一并交给 worker。
3. 策略 payload 保持结构化 JSON object，避免 downstream 再解析字符串。

实现：

1. `A2AgentResourceResolver.ResolvedWorkspaceResource` 增加：
   - `quotaPolicy`；
   - `retentionPolicy`；
   - `concurrencyPolicy`。
2. `resolveRequiredWorkspace(...)` 在目录资源解析阶段解析：
   - `WorkingDirectoryEntity.quotaJson`；
   - `WorkingDirectoryEntity.retentionPolicyJson`；
   - `WorkingDirectoryEntity.concurrencyPolicyJson`。
3. 策略解析采用 fail-closed：
   - 空值返回 `null`；
   - 非 JSON object 或非法 JSON 直接抛出 `IllegalStateException`；
   - 不允许把损坏策略静默降级为无策略。
4. `BusinessAgentWorkerTaskLaunchRequest` 增加：
   - `workspaceQuotaPolicy`；
   - `workspaceRetentionPolicy`；
   - `workspaceConcurrencyPolicy`。
5. `BusinessAgentTaskService` 将 resolver 返回的策略对象放入 worker launch request。
6. `LanggraphBusinessAgentWorkerTaskLauncher` 在 runtime context 的 `execution_policy` 中写入：
   - `quota_policy`；
   - `retention_policy`；
   - `concurrency_policy`。

边界：

1. 本 checkpoint 只完成策略透传，不实现实际 quota enforcement。
2. 本 checkpoint 只完成策略透传，不实现 worker 侧并发文件锁。
3. 本 checkpoint 只完成策略透传，不实现 retention 清理任务。
4. Worker 收到策略后，后续文件 / shell 工具必须按该策略执行；未接入前不能宣称具备强隔离能力。

验证：

```powershell
mvn -pl business-agent-module,addons/claude-worker-agent,addons/langgraph-biz-worker -am "-Dtest=A2AgentResourceResolverTest,BusinessAgentTaskServiceTest,OpenApiAgentReadinessServiceTest,LanggraphBusinessAgentWorkerTaskLauncherTest,BusinessAgentLanggraphLaunchE2ETest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：

1. `A2AgentResourceResolverTest`: 18 tests passed, 0 failures, 0 errors。
2. `BusinessAgentTaskServiceTest`: 17 tests passed, 0 failures, 0 errors。
3. `OpenApiAgentReadinessServiceTest`: 12 tests passed, 0 failures, 0 errors。
4. `LanggraphBusinessAgentWorkerTaskLauncherTest`: 8 tests passed, 0 failures, 0 errors。
5. `BusinessAgentLanggraphLaunchE2ETest`: 2 tests passed, 0 failures, 0 errors。

未进入本 checkpoint：

1. `readOnly` 在 worker 文件 / shell tool 层的强制执行。
2. `quota_policy` 的磁盘占用实时统计与拒绝写入。
3. `concurrency_policy` 的进程内 / 跨进程锁。
4. `retention_policy` 的异步清理与归档。
5. shell command allowlist / denylist 的执行层实现。

## 24. Phase 13 Worker Execution Policy Enforcement Checkpoint

2026-05-24 已补齐 LangGraph Biz Worker 侧的最小执行策略强约束。

目标：

1. Java runtime 已经把目录执行策略放入 worker `execution_policy`。
2. Worker 不能只记录策略，还必须在文件 / 命令工具入口执行第一层 fail-closed 约束。
3. 首期只实现风险最高、边界最清晰的约束：
   - `read_only`；
   - 单次写入大小上限；
   - read-only workspace 禁用 command tool。

实现：

1. `ExecutionPolicy` 增加结构化策略解析：
   - `read_only` / `readOnly`；
   - `quota_policy` / `quotaPolicy` / `quota`；
   - `retention_policy` / `retentionPolicy` / `retention`；
   - `concurrency_policy` / `concurrencyPolicy` / `concurrency`。
2. `ExecutionPolicy.max_write_bytes(hard_limit)` 从 quota policy 中读取单次写入上限：
   - 支持 `max_write_bytes`、`maxWriteBytes`、`max_bytes`、`maxBytes`、`max_file_bytes`、`maxFileBytes`；
   - 值必须为正整数；
   - 最终值不能超过 worker 内置硬上限。
3. `AccountPathGuard.resolve_write(...)` 在 read-only workspace 下直接拒绝写路径解析：
   - 错误码：`workspace_read_only`；
   - 覆盖新建、覆盖、替换、编辑、patch 等所有写入口。
4. `AccountFileTools` 在写入前统一检查单次写入大小：
   - `write_file`；
   - `str_replace`；
   - `edit_file`；
   - `patch_file`。
5. `command_tool_available(...)` 在 read-only workspace 下返回不可用。
6. `run_command_tool(...)` 即使被直接调用，也在 read-only workspace 下返回：
   - `ok=false`；
   - `code=COMMAND_READ_ONLY`。

边界：

1. 本 checkpoint 只实现单次写入大小限制，不实现目录总容量 accounting。
2. `quota_policy` 仍未覆盖文件数量、目录总大小、历史版本占用统计。
3. `concurrency_policy` 仍未实现进程内 / 跨进程文件锁。
4. `retention_policy` 仍未实现异步清理、归档、TTL。
5. command tool 仍未实现 allowlist / denylist；当前只在 read-only workspace 下整体禁用。

验证：

```powershell
cd tools/langgraph-biz-worker
.\.venv\Scripts\python.exe -m pytest tests/test_execution_policy.py tests/test_account_file_tools.py tests/test_command_tool.py
.\.venv\Scripts\python.exe -m ruff check src\langgraph_biz_worker\runtime\execution_policy.py src\langgraph_biz_worker\runtime\account_path_guard.py src\langgraph_biz_worker\runtime\account_file_tools.py src\langgraph_biz_worker\runtime\command_tool.py tests\test_execution_policy.py tests\test_account_file_tools.py tests\test_command_tool.py
```

结果：

1. targeted pytest: 49 passed, 2 skipped。
2. targeted ruff: All checks passed。

未进入本 checkpoint：

1. Agent model binding 独立表。
2. Upstream principal resource admin API 的最终统一收口。
3. Worker command allowlist / denylist。
4. retention / concurrency / 总容量 quota 的执行层实现。

## 25. Phase 14 Agent Model Binding Checkpoint

2026-05-24 已补齐 Agent 维度的模型绑定授权闭环。

目标：

1. `ClientApp` / `UpstreamSystem` 负责决定 LLMConfigModel 对上游主体是否可见。
2. `Agent` 负责决定自己运行时是否允许使用某个已可见模型。
3. 不能把 “ClientApp 可见模型” 直接等同于 “任意 Agent 可用模型”。
4. Agent 的 `defaultModelConfigId` 继续作为首选模型，并视为隐式绑定。

实现：

1. 新增 `AgentModelBindingEntity`：
   - 表名：`coding_agent_models`；
   - 唯一键：`tenantId + agentId + modelConfigId`；
   - 用于表达某个 Agent 可使用的非默认模型。
2. 新增 `BusinessAgentModelBindingRepository`。
3. `A2AgentResourceResolver` 增加 Agent-aware 模型解析：
   - `resolveRequiredModelConfigIdForAgent(...)`；
   - `resolveRequiredModelForAgent(...)`；
   - `resolveOptionalModelForAgent(...)`。
4. 模型解析顺序固定为两层：
   - 第一层：`ClientAppModelConfigGrantService` 判断 ClientApp / UpstreamSystem 是否可见；
   - 第二层：`Agent.defaultModelConfigId` 或 `coding_agent_models` 判断 Agent 是否可用。
5. `BusinessAgentTaskService.createTask(...)` 在新建 task 时使用 Agent-aware 模型解析。
6. `BusinessAgentTaskService` 的可选 vision model 也必须同时满足：
   - ClientApp 默认 VISION model grant；
   - Agent 默认模型或显式模型绑定。
7. `OpenApiAgentReadinessService` 的 `MODEL_CONFIG_GRANT` check 改为执行 Agent-aware 模型解析，readiness 返回的 `modelConfigSource` 会包含：
   - `AGENT_DEFAULT_MODEL:*`；
   - 或 `AGENT_MODEL_BINDING:*`。
8. `BusinessAgentBundleService.syncAgentBundle(...)` 在同步 Agent bundle 后，为 `defaultModelConfigId` 写入显式绑定行，便于后续 inspect / list / 管理界面展示；运行时仍把 default model 视为隐式可用，避免绑定表缺失导致默认模型不可用。

边界：

1. 本 checkpoint 不实现模型绑定管理 API。
2. 本 checkpoint 不实现绑定列表 DTO / 管理页面。
3. 本 checkpoint 不清理 Agent 旧 default model 的历史绑定。
4. OpenAPI skill-scoped token 路径当前没有 Agent resource，仍只执行 ClientApp model grant；后续如统一到 A2Agent task 路径，再接入 Agent binding。
5. 不考虑旧数据兼容；项目尚未正式上线，后续可以直接补齐 DDL / migration。

验证：

```powershell
mvn -pl business-agent-module,addons/claude-worker-agent,addons/langgraph-biz-worker -am "-Dtest=A2AgentResourceResolverTest,BusinessAgentTaskServiceTest,BusinessAgentBundleServiceTest,OpenApiAgentReadinessServiceTest,OpenApiControllerMessageMappingTest,BusinessAgentLanggraphLaunchE2ETest,BusinessAgentE2ESampleTest,RestAdapterUpstreamE2ETest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：

1. `business-agent-module`: 49 tests passed, 0 failures, 0 errors。
2. `claude-worker-agent`: 30 tests passed, 0 failures, 0 errors。
3. `langgraph-biz-worker`: 2 tests passed, 0 failures, 0 errors。

未进入本 checkpoint：

1. Upstream principal resource admin API 的最终统一收口。
2. Agent model binding 的显式创建 / 删除 / 列表接口。
3. Worker command allowlist / denylist。
4. retention / concurrency / 总容量 quota 的执行层实现。

## 26. Phase 15 Agent Model Binding Management Checkpoint

2026-05-24 已补齐 ClientApp 控制面的 Agent 模型绑定管理能力。

目标：

1. 上游可以在 `NAVI_CONTROL_API_KEY` 权限内查看、绑定、解绑当前 ClientApp 自己 Agent 的可用模型。
2. 绑定模型前必须先满足 ClientApp / UpstreamSystem 层面的模型可见性。
3. ClientApp 控制面不能管理其他 ClientApp 或 system-owned Agent。
4. Agent 的默认模型仍由 Agent 自身配置维护，不能通过普通解绑接口误删。

实现：

1. 新增 `AgentModelBindingController`：
   - `GET /api/v1/client-apps/{clientAppId}/agents/{agentId}/model-bindings`；
   - `POST /api/v1/client-apps/{clientAppId}/agents/{agentId}/model-bindings`；
   - `DELETE /api/v1/client-apps/{clientAppId}/agents/{agentId}/model-bindings/{modelConfigId}`。
2. 新增 `AgentModelBindingService`：
   - 强制 `clientAppService.requireActiveClientApp(...)`；
   - 强制 Agent owner 为 `CLIENT_APP` 且 `ownerId/clientAppId` 等于当前 `clientAppId`；
   - `bind(...)` 先调用 `ClientAppModelConfigGrantService.resolveEffectiveModelConfigId(...)`，确认模型对当前 ClientApp 可见；
   - `unbind(...)` 禁止删除 `agent.defaultModelConfigId` 对应的默认模型绑定。
3. `ClientAppControlCredentialService.defaultScopes()` 增加：
   - `AGENT_MODEL_BINDING_MANAGE`。
4. `navigator-open-sdk` 增加：
   - `listAgentModelBindings(...)`；
   - `bindAgentModel(...)`；
   - `unbindAgentModel(...)`；
   - `AgentModelBindingDTO`；
   - `BindAgentModelForm`。
5. `navi upstream` CLI 增加：
   - `agent model-bindings`；
   - `agent bind-model --agent <agentId> --model-config-id <modelConfigId>`；
   - `agent unbind-model --agent <agentId> --model-config-id <modelConfigId>`。

边界：

1. 本 checkpoint 只开放 ClientApp-owned Agent 的模型绑定管理。
2. System-owned Agent / platform-owned Agent 的模型绑定管理留给后续 upstream system admin plane。
3. 默认模型的更新仍应走 Agent bundle / Agent 管理接口，不通过 `unbind-model` 隐式修改。
4. 不考虑旧接口兼容；项目尚未正式上线，旧的资源授权路径可以继续删除。

验证：

```powershell
mvn -pl business-agent-module,navigator-open-sdk -am "-Dtest=AgentModelBindingServiceTest,AgentModelBindingControllerTest,ClientAppControlCredentialServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：

1. `business-agent-module`: 14 targeted tests passed, 0 failures, 0 errors。
2. `navigator-open-sdk`: compile / test phase passed。

补充回归：

```powershell
mvn -pl business-agent-module,addons/claude-worker-agent,addons/langgraph-biz-worker,navigator-open-sdk -am "-Dtest=AgentModelBindingServiceTest,AgentModelBindingControllerTest,A2AgentResourceResolverTest,BusinessAgentTaskServiceTest,BusinessAgentBundleServiceTest,OpenApiAgentReadinessServiceTest,OpenApiControllerMessageMappingTest,BusinessAgentLanggraphLaunchE2ETest,BusinessAgentE2ESampleTest,RestAdapterUpstreamE2ETest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：

1. `business-agent-module`: 55 tests passed, 0 failures, 0 errors。
2. `claude-worker-agent`: 30 tests passed, 0 failures, 0 errors。
3. `langgraph-biz-worker`: 2 tests passed, 0 failures, 0 errors。
4. `navigator-open-sdk`: compile / test phase passed。

未进入本 checkpoint：

1. Agent model binding 的前端管理页面。
2. System-owned Agent 的管理 API。
3. Agent 默认模型更新 API 的统一收口。
4. Worker command allowlist / denylist。
5. retention / concurrency / 总容量 quota 的执行层实现。

## 27. Phase 16 System-owned Agent Model Binding and Default Model Checkpoint

2026-05-24 已补齐 system-owned Agent 的模型绑定管理入口，并为 ClientApp-owned / System-owned Agent 同时提供默认模型切换路径。

目标：

1. `AgentModelBinding` 不只服务 ClientApp-owned Agent，也能服务 `UPSTREAM_SYSTEM` owner 的共享 Agent。
2. `defaultModelConfigId` 不再只能通过 bundle sync 写入；控制面必须能显式切换默认模型。
3. 解绑默认模型继续 fail-closed；必须先切换默认模型，再解绑旧模型。
4. Upstream admin credential 解析为 `UpstreamSystemPrincipal` 后，只能管理自己拥有的 system-owned Agent 和 system-owned model。

实现：

1. `AgentModelBindingService` 增加两套显式路径：
   - ClientApp control plane：`list / bind / setDefault / unbind`，只允许管理 `ownerType=CLIENT_APP && ownerId=clientAppId` 的 Agent。
   - Upstream admin plane：`listSystemOwned / bindSystemOwned / setSystemOwnedDefault / unbindSystemOwned`，只允许管理 `ownerType=UPSTREAM_SYSTEM && ownerId=principal.upstreamSystemId` 的 Agent。
2. `setDefault(...)` / `setSystemOwnedDefault(...)` 会先校验模型可见性，再 upsert `coding_agent_models`，最后更新 `coding_agents.defaultModelConfigId`。
3. 新增 ClientApp endpoint：
   - `PUT /api/v1/client-apps/{clientAppId}/agents/{agentId}/model-bindings/default`
4. 新增 upstream admin endpoint：
   - `GET /api/v1/upstream-admin/agents/{agentId}/model-bindings`
   - `POST /api/v1/upstream-admin/agents/{agentId}/model-bindings`
   - `PUT /api/v1/upstream-admin/agents/{agentId}/model-bindings/default`
   - `DELETE /api/v1/upstream-admin/agents/{agentId}/model-bindings/{modelConfigId}`
5. 新增 upstream admin scope：
   - `AGENT_MODEL_BINDING_MANAGE`
   - 新 admin credential 默认授予。
   - alias：`AGENT_MODEL_MANAGE`、`MODEL_BINDING_MANAGE`。
6. `navigator-open-sdk` 增加：
   - ClientApp：`setDefaultAgentModel(...)`
   - Upstream admin：`listUpstreamSystemAgentModelBindings(...)`、`bindUpstreamSystemAgentModel(...)`、`setDefaultUpstreamSystemAgentModel(...)`、`unbindUpstreamSystemAgentModel(...)`
7. `navi upstream` CLI 增加：
   - `agent set-default-model`
   - `agent system-model-bindings`
   - `agent system-bind-model`
   - `agent system-unbind-model`
   - `agent system-set-default-model`

边界：

1. 本 checkpoint 不实现 system-owned Agent 的创建 / 更新 API，只补齐已存在 system-owned Agent 的模型绑定管理。
2. Upstream admin 只能绑定自己拥有的 `UPSTREAM_SYSTEM` model；暂不允许绑定 `PLATFORM` model，避免绕过共享资源授权策略。
3. ClientApp control plane 仍通过 ClientApp model grant 校验模型可见性，不直接信任裸 `modelConfigId`。
4. 不考虑旧数据兼容；缺少 owner 的 Agent / model 在新路径中不可管理。

验证：

```powershell
mvn -pl business-agent-module,navigator-open-sdk -am "-Dtest=AgentModelBindingServiceTest,AgentModelBindingControllerTest,UpstreamAdminAgentModelBindingControllerTest,UpstreamClientAppAdminCredentialServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：

1. `business-agent-module`: 16 tests passed, 0 failures, 0 errors。
2. `navigator-open-sdk`: compile / test phase passed。

补充回归：

```powershell
mvn -pl business-agent-module,addons/claude-worker-agent,addons/langgraph-biz-worker,navigator-open-sdk -am "-Dtest=AgentModelBindingServiceTest,AgentModelBindingControllerTest,UpstreamAdminAgentModelBindingControllerTest,A2AgentResourceResolverTest,BusinessAgentTaskServiceTest,BusinessAgentBundleServiceTest,OpenApiAgentReadinessServiceTest,OpenApiControllerMessageMappingTest,BusinessAgentLanggraphLaunchE2ETest,BusinessAgentE2ESampleTest,RestAdapterUpstreamE2ETest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：

1. `business-agent-module`: 61 tests passed, 0 failures, 0 errors。
2. `claude-worker-agent`: 30 tests passed, 0 failures, 0 errors。
3. `langgraph-biz-worker`: 2 tests passed, 0 failures, 0 errors。
4. `navigator-open-sdk`: compile / test phase passed。

未进入本 checkpoint：

1. system-owned Agent 创建 / 更新 API。
2. system-owned Agent 前端管理页面。
3. Agent default model 历史绑定清理。
4. Agent model selection policy（多模型优先级、按类别、按任务类型选择）。

## 28. Phase 17 System-owned Agent Management Checkpoint

2026-05-24 已补齐 upstream admin 创建和维护 system-owned Agent 的最小控制面闭环。

目标：

1. `UPSTREAM_SYSTEM` 主体可以创建自己拥有的共享 Agent。
2. system-owned Agent 可被同 upstream system 下的 ClientApp 运行时解析，但不能被其他 upstream system 管理。
3. Agent 创建/更新时不再走 ClientApp bundle sync，也不隐式创建 ClientApp public skill。
4. 默认模型、默认目录、WorkerPool 选择必须经过 owner-aware 校验。

实现：

1. 新增 `UpstreamAdminAgentService`：
   - `list / get / create / update` 只处理 `ownerType=UPSTREAM_SYSTEM && ownerId=principal.upstreamSystemId` 的 Agent。
   - 创建时写入 `ownerType=UPSTREAM_SYSTEM`、`ownerId=upstreamSystemId`、`clientAppId=null`。
   - 默认 `agentType=LOCAL_LANGGRAPH_WORKER`。
   - `skillsJson` 与 `agentProfileJson` 只接受合法 JSON；未传时使用空 skills 与 system-owned agent 默认 profile。
2. 新增 upstream admin endpoint：
   - `GET /api/v1/upstream-admin/agents`
   - `POST /api/v1/upstream-admin/agents`
   - `GET /api/v1/upstream-admin/agents/{agentId}`
   - `PUT /api/v1/upstream-admin/agents/{agentId}`
3. 新增 upstream admin scope：
   - `AGENT_MANAGE`
   - 新 admin credential 默认授予。
   - alias：`SYSTEM_AGENT_MANAGE`、`AGENT_ADMIN`。
4. Agent 创建/更新时校验：
   - WorkerPool 必须属于同 tenant，状态为 `ENABLED`，且 owner 为 `PLATFORM` 或同一 `UPSTREAM_SYSTEM`。
   - default model 如存在，必须属于同 tenant 且 owner 为同一 `UPSTREAM_SYSTEM`。
   - default directory 如存在，必须属于同 tenant、enabled，且 owner 为同一 `UPSTREAM_SYSTEM`。
5. 设置 default model 时自动确保 `coding_agent_models` 中存在对应绑定。
6. `navigator-open-sdk` 增加：
   - `listUpstreamSystemAgents(...)`
   - `createUpstreamSystemAgent(...)`
   - `getUpstreamSystemAgent(...)`
   - `updateUpstreamSystemAgent(...)`
7. `navi upstream` CLI 增加：
   - `agent system-list`
   - `agent system-create --file <json>`
   - `agent system-get --agent-code <id>`
   - `agent system-update --agent-code <id> --file <json>`

边界：

1. 本 checkpoint 不实现删除 Agent；先通过 `enabled=false` 停用。
2. system-owned Agent 不自动 materialize skill，也不写 ClientApp public skill registry。
3. system-owned Agent 仍不能绑定 ClientApp-owned / UpstreamUser-owned 目录作为默认目录。
4. system-owned Agent 的 ClientApp 可见性仍由 resolver 基于 ClientApp.upstreamSystemId 判定，不在 Agent API 中重复维护 grant 表。
5. 不考虑旧数据兼容；缺 owner 的 Agent 不可通过新 upstream-admin Agent API 管理。

验证：

```powershell
mvn -pl business-agent-module,navigator-open-sdk -am "-Dtest=UpstreamAdminAgentServiceTest,UpstreamAdminAgentControllerTest,UpstreamAdminAgentModelBindingControllerTest,AgentModelBindingServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：

1. `business-agent-module`: 14 tests passed, 0 failures, 0 errors。
2. `navigator-open-sdk`: compile / test phase passed。

扩展回归：

```powershell
mvn -pl business-agent-module,addons/claude-worker-agent,addons/langgraph-biz-worker,navigator-open-sdk -am "-Dtest=UpstreamAdminAgentServiceTest,UpstreamAdminAgentControllerTest,AgentModelBindingServiceTest,AgentModelBindingControllerTest,UpstreamAdminAgentModelBindingControllerTest,A2AgentResourceResolverTest,BusinessAgentTaskServiceTest,BusinessAgentBundleServiceTest,OpenApiAgentReadinessServiceTest,OpenApiControllerMessageMappingTest,BusinessAgentLanggraphLaunchE2ETest,BusinessAgentE2ESampleTest,RestAdapterUpstreamE2ETest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：

1. `business-agent-module`: 66 tests passed, 0 failures, 0 errors。
2. `claude-worker-agent`: 30 tests passed, 0 failures, 0 errors。
3. `langgraph-biz-worker`: 2 tests passed, 0 failures, 0 errors。
4. `navigator-open-sdk`: compile / test phase passed。

未进入本 checkpoint：

1. Agent 删除 / soft delete 独立 API。
2. system-owned Agent 前端管理页面。
3. AgentWorkerBinding 独立管理 API。
4. Agent model selection policy（多模型优先级、按类别、按任务类型选择）。

## 29. Phase 18 Agent Workspace Binding Management Checkpoint

2026-05-24 已补齐 A2Agent workspace binding 的显式管理控制面。

目标：

1. Agent 可用工作目录不再只依赖 `defaultDirectoryId`。
2. ClientApp-owned Agent 的 workspace binding 由 ClientApp 控制面维护。
3. system-owned Agent 的 workspace binding 由 upstream admin 控制面维护。
4. Runtime 已有的 agent-aware workspace resolver 继续 fail-closed：目录对用户可见但未绑定到 Agent 时仍不可用。

实现：

1. 新增 `AgentWorkspaceBindingService`：
   - ClientApp 控制面只管理 `ownerType=CLIENT_APP && ownerId=clientAppId` 的 Agent。
   - Upstream admin 控制面只管理 `ownerType=UPSTREAM_SYSTEM && ownerId=principal.upstreamSystemId` 的 Agent。
   - `bind / list / unbind / setDefault` 均按 `tenantId + agentId + directoryId` 操作 `coding_agent_directories`。
   - 解绑默认目录会拒绝，必须先改默认目录。
2. ClientApp workspace binding endpoint：
   - `GET /api/v1/client-apps/{clientAppId}/agents/{agentId}/workspace-bindings`
   - `POST /api/v1/client-apps/{clientAppId}/agents/{agentId}/workspace-bindings`
   - `PUT /api/v1/client-apps/{clientAppId}/agents/{agentId}/workspace-bindings/default`
   - `DELETE /api/v1/client-apps/{clientAppId}/agents/{agentId}/workspace-bindings/{directoryId}`
3. Upstream admin workspace binding endpoint：
   - `GET /api/v1/upstream-admin/agents/{agentId}/workspace-bindings`
   - `POST /api/v1/upstream-admin/agents/{agentId}/workspace-bindings`
   - `PUT /api/v1/upstream-admin/agents/{agentId}/workspace-bindings/default`
   - `DELETE /api/v1/upstream-admin/agents/{agentId}/workspace-bindings/{directoryId}`
4. 新增 scope：
   - ClientApp control: `AGENT_WORKSPACE_BINDING_MANAGE`
   - Upstream admin: `AGENT_WORKSPACE_BINDING_MANAGE`
   - alias：`AGENT_WORKSPACE_MANAGE`、`WORKSPACE_BINDING_MANAGE`、`AGENT_DIRECTORY_BINDING_MANAGE`
5. 目录 owner/scope 规则：
   - ClientApp-owned Agent 可绑定同 ClientApp 的 `CLIENT_APP_SHARED` 目录。
   - ClientApp-owned Agent 可绑定同 ClientApp 下已归属具体 upstream user 的 `USER_PRIVATE` 目录；运行时仍会按当前 `upstreamUserId` 再校验。
   - system-owned Agent 只允许绑定同 upstream system 的 `UPSTREAM_SYSTEM_SHARED` 目录。
   - 不允许 system-owned Agent 通过 admin API 直接绑定 ClientApp / User 私有目录，避免共享 Agent 与具体下游 App/用户耦合。
6. `navigator-open-sdk` 增加：
   - `listAgentWorkspaceBindings(...)`
   - `bindAgentWorkspace(...)`
   - `setDefaultAgentWorkspace(...)`
   - `unbindAgentWorkspace(...)`
   - `listUpstreamSystemAgentWorkspaceBindings(...)`
   - `bindUpstreamSystemAgentWorkspace(...)`
   - `setDefaultUpstreamSystemAgentWorkspace(...)`
   - `unbindUpstreamSystemAgentWorkspace(...)`
7. `navi upstream` CLI 增加：
   - `agent workspace-bindings`
   - `agent bind-workspace --directory-id <id>`
   - `agent unbind-workspace --directory-id <id>`
   - `agent set-default-workspace --directory-id <id>`
   - `agent system-workspace-bindings`
   - `agent system-bind-workspace --directory-id <id>`
   - `agent system-unbind-workspace --directory-id <id>`
   - `agent system-set-default-workspace --directory-id <id>`

验证：

```powershell
mvn -pl business-agent-module,navigator-open-sdk -am "-Dtest=AgentWorkspaceBindingServiceTest,AgentWorkspaceBindingControllerTest,UpstreamAdminAgentWorkspaceBindingControllerTest,AgentModelBindingServiceTest,UpstreamAdminAgentControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test

mvn -pl navigator-open-sdk "-Dtest=UpstreamCliTest" test
```

结果：

1. `business-agent-module`: 19 tests passed, 0 failures, 0 errors。
2. `navigator-open-sdk`: compile / test phase passed。
3. `UpstreamCliTest`: 57 tests passed, 0 failures, 0 errors。

扩展回归：

```powershell
mvn -pl business-agent-module,addons/claude-worker-agent,addons/langgraph-biz-worker,navigator-open-sdk -am "-Dtest=AgentWorkspaceBindingServiceTest,AgentWorkspaceBindingControllerTest,UpstreamAdminAgentWorkspaceBindingControllerTest,UpstreamAdminAgentServiceTest,UpstreamAdminAgentControllerTest,AgentModelBindingServiceTest,AgentModelBindingControllerTest,UpstreamAdminAgentModelBindingControllerTest,A2AgentResourceResolverTest,BusinessAgentTaskServiceTest,BusinessAgentBundleServiceTest,OpenApiAgentReadinessServiceTest,OpenApiControllerMessageMappingTest,BusinessAgentLanggraphLaunchE2ETest,BusinessAgentE2ESampleTest,RestAdapterUpstreamE2ETest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：

1. `business-agent-module`: 76 tests passed, 0 failures, 0 errors。
2. `addons/claude-worker-agent`: 30 tests passed, 0 failures, 0 errors。
3. `addons/langgraph-biz-worker`: 2 tests passed, 0 failures, 0 errors。
4. `navigator-open-sdk`: compile / test phase passed。

未进入本 checkpoint：

1. AgentWorkerBinding 独立表与显式管理 API。
2. Agent workspace binding 的前端管理页面。
3. Agent workspace binding 的批量替换 API。
4. 运行时目录 readOnly / quota / concurrency 的实际执行层强制。

## 30. Phase 19 Agent Worker Binding Management Checkpoint

2026-05-24 已补齐 A2Agent worker pool binding 的显式管理控制面。

目标：

1. Agent 可用 WorkerPool 不再只依赖 `CodingAgentEntity.workerId`。
2. `workerId` 继续表示默认 WorkerPool，并作为隐式绑定保留。
3. ClientApp-owned Agent 的 worker binding 由 ClientApp 控制面维护。
4. system-owned Agent 的 worker binding 由 upstream admin 控制面维护。

实现：

1. 新增 `coding_agent_workers` / `AgentWorkerBindingEntity`：
   - 唯一键：`tenantId + agentId + workerPoolId`。
   - 记录 Agent 可用 WorkerPool 集合。
   - `CodingAgentEntity.workerId` 仍为默认 WorkerPool。
2. 新增 `AgentWorkerBindingService`：
   - ClientApp 控制面只管理 `ownerType=CLIENT_APP && ownerId=clientAppId` 的 Agent。
   - Upstream admin 控制面只管理 `ownerType=UPSTREAM_SYSTEM && ownerId=principal.upstreamSystemId` 的 Agent。
   - `bind / list / unbind / setDefault` 均按 `tenantId + agentId + workerPoolId` 操作。
   - `setDefault` 会先确保 binding 存在，再更新 Agent 默认 `workerId`。
   - 解绑默认 WorkerPool 会拒绝，必须先切换默认 WorkerPool。
3. ClientApp worker binding endpoint：
   - `GET /api/v1/client-apps/{clientAppId}/agents/{agentId}/worker-bindings`
   - `POST /api/v1/client-apps/{clientAppId}/agents/{agentId}/worker-bindings`
   - `PUT /api/v1/client-apps/{clientAppId}/agents/{agentId}/worker-bindings/default`
   - `DELETE /api/v1/client-apps/{clientAppId}/agents/{agentId}/worker-bindings/{workerPoolId}`
4. Upstream admin worker binding endpoint：
   - `GET /api/v1/upstream-admin/agents/{agentId}/worker-bindings`
   - `POST /api/v1/upstream-admin/agents/{agentId}/worker-bindings`
   - `PUT /api/v1/upstream-admin/agents/{agentId}/worker-bindings/default`
   - `DELETE /api/v1/upstream-admin/agents/{agentId}/worker-bindings/{workerPoolId}`
5. 新增 scope：
   - ClientApp control: `AGENT_WORKER_BINDING_MANAGE`
   - Upstream admin: `AGENT_WORKER_BINDING_MANAGE`
   - alias：`AGENT_WORKER_MANAGE`、`WORKER_BINDING_MANAGE`、`AGENT_WORKER_POOL_BINDING_MANAGE`
6. WorkerPool owner 规则：
   - ClientApp-owned Agent 可绑定 `PLATFORM` WorkerPool。
   - ClientApp-owned Agent 可绑定同 upstream system 的 `UPSTREAM_SYSTEM` WorkerPool。
   - system-owned Agent 可绑定 `PLATFORM` WorkerPool。
   - system-owned Agent 可绑定同 upstream system 的 `UPSTREAM_SYSTEM` WorkerPool。
   - 不允许跨 upstream system 绑定 WorkerPool。
7. `navigator-open-sdk` 增加：
   - `listAgentWorkerBindings(...)`
   - `bindAgentWorker(...)`
   - `setDefaultAgentWorker(...)`
   - `unbindAgentWorker(...)`
   - `listUpstreamSystemAgentWorkerBindings(...)`
   - `bindUpstreamSystemAgentWorker(...)`
   - `setDefaultUpstreamSystemAgentWorker(...)`
   - `unbindUpstreamSystemAgentWorker(...)`
8. `navi upstream` CLI 增加：
   - `agent worker-bindings`
   - `agent bind-worker --worker-pool-id <id>`
   - `agent unbind-worker --worker-pool-id <id>`
   - `agent set-default-worker --worker-pool-id <id>`
   - `agent system-worker-bindings`
   - `agent system-bind-worker --worker-pool-id <id>`
   - `agent system-unbind-worker --worker-pool-id <id>`
   - `agent system-set-default-worker --worker-pool-id <id>`

边界：

1. 本 checkpoint 不实现 WorkerPool 的 ClientApp-owned 创建入口；当前主要支持 `PLATFORM` 与 `UPSTREAM_SYSTEM` WorkerPool。
2. Runtime 当前仍使用默认 WorkerPool；多 WorkerPool 选择策略后续再设计。
3. WorkerPool binding 的前端管理页面未进入本 checkpoint。
4. 不考虑旧数据兼容；缺 owner 的 WorkerPool / Agent 在新路径中不可管理。

验证：

```powershell
mvn -pl business-agent-module,navigator-open-sdk -am "-Dtest=AgentWorkerBindingServiceTest,AgentWorkerBindingControllerTest,UpstreamAdminAgentWorkerBindingControllerTest,AgentWorkspaceBindingServiceTest,AgentModelBindingServiceTest,UpstreamAdminAgentControllerTest,UpstreamCliTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：

1. `business-agent-module`: 27 tests passed, 0 failures, 0 errors。
2. `navigator-open-sdk`: `UpstreamCliTest` 59 tests passed, 0 failures, 0 errors。
3. Reactor `BUILD SUCCESS`。

扩展回归：

```powershell
mvn -pl business-agent-module,addons/claude-worker-agent,addons/langgraph-biz-worker,navigator-open-sdk -am "-Dtest=AgentWorkerBindingServiceTest,AgentWorkerBindingControllerTest,UpstreamAdminAgentWorkerBindingControllerTest,AgentWorkspaceBindingServiceTest,AgentWorkspaceBindingControllerTest,UpstreamAdminAgentWorkspaceBindingControllerTest,UpstreamAdminAgentServiceTest,UpstreamAdminAgentControllerTest,AgentModelBindingServiceTest,AgentModelBindingControllerTest,UpstreamAdminAgentModelBindingControllerTest,A2AgentResourceResolverTest,BusinessAgentTaskServiceTest,BusinessAgentBundleServiceTest,OpenApiAgentReadinessServiceTest,OpenApiControllerMessageMappingTest,BusinessAgentLanggraphLaunchE2ETest,BusinessAgentE2ESampleTest,RestAdapterUpstreamE2ETest,UpstreamCliTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：

1. `business-agent-module`: 88 tests passed, 0 failures, 0 errors。
2. `addons/claude-worker-agent`: 30 tests passed, 0 failures, 0 errors。
3. `addons/langgraph-biz-worker`: 2 tests passed, 0 failures, 0 errors。
4. `navigator-open-sdk`: `UpstreamCliTest` 59 tests passed, 0 failures, 0 errors。
5. Reactor `BUILD SUCCESS`。

## 31. Phase 20 Agent Default Binding Materialization and Upstream Docs Checkpoint

2026-05-24 已补齐 Agent 默认资源字段与显式 binding 表的一致性，并同步更新上游 CLI / SDK 使用文档。

目标：

1. Agent 创建或更新时，默认模型、默认目录、默认 WorkerPool 不再只是 `CodingAgentEntity` 字段。
2. 非空默认字段必须自动进入对应 binding 表，便于列表、审计、readiness 和 runtime resolver 使用同一套可见性数据。
3. ClientApp-owned Agent、system-owned Agent 和多租户 provisioning 产生的 root Agent 都走同一套默认 binding materialization 逻辑。
4. 上游文档明确 `NAVI_ADMIN_API_KEY` / `NAVI_CONTROL_API_KEY` / upstream user token 的调用边界，以及 Agent model / workspace / worker binding 命令与 SDK 入口。

实现：

1. 新增 `AgentDefaultBindingService`：
   - `defaultModelConfigId -> coding_agent_models`。
   - `defaultDirectoryId -> coding_agent_directories`。
   - `workerId -> coding_agent_workers`。
   - 仅处理非空默认字段；已有 binding 直接复用；缺少 `tenantId` / `agentId` 时 fail fast。
2. `BusinessAgentBundleService`：
   - `agent sync` / ClientApp-owned Agent 保存后调用 `ensureDefaults(...)`。
   - 原先只补默认模型 binding 的局部逻辑移除，避免 model / workspace / worker 三套口径分裂。
3. `UpstreamAdminAgentService`：
   - `system-create` / `system-update` 保存 UpstreamSystem-owned Agent 后调用 `ensureDefaults(...)`。
   - 默认字段仍先按 owner / tenant 规则校验，保存后再 materialize binding。
4. `UpstreamTenantClientAppProvisioningService`：
   - `ensure-tenant` 创建或复用 root Agent 后调用 `ensureDefaults(...)`。
   - 首轮至少确保 root Agent 的 default model 和 WorkerPool binding 与字段一致；未配置默认目录时跳过目录 binding。
5. 上游文档：
   - `11-llm-sdk-usage-guide.md` 增加 UpstreamSystem Agent 与 Agent model / workspace / worker binding SDK 入口。
   - `18-navigator-upstream-cli-usage-guide.md` 增加 Agent 绑定命令清单、ClientApp-owned / system-owned 示例和默认字段 materialization 约定。
   - `16-upstream-cli-skill-runtime-contract-alignment.md` 增加 A2Agent 资源绑定作为运行时可见性边界的口径。

边界：

1. 本 checkpoint 不实现多 WorkerPool 选择策略；runtime 仍使用解析出的默认 WorkerPool。
2. 本 checkpoint 不实现批量替换 binding API。
3. 本 checkpoint 不迁移旧数据；缺少 binding 的旧 Agent 需通过新创建、新更新或显式绑定路径补齐。
4. 默认字段 materialization 不放宽资源 owner 校验；校验仍由 Agent 创建/更新服务和各 binding service 负责。

验证：

```powershell
mvn -pl business-agent-module -am "-Dtest=AgentDefaultBindingServiceTest,BusinessAgentBundleServiceTest,UpstreamAdminAgentServiceTest,UpstreamTenantClientAppProvisioningServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test

mvn -pl business-agent-module,navigator-open-sdk -am "-Dtest=AgentDefaultBindingServiceTest,BusinessAgentBundleServiceTest,UpstreamAdminAgentServiceTest,UpstreamTenantClientAppProvisioningServiceTest,AgentModelBindingServiceTest,AgentWorkspaceBindingServiceTest,AgentWorkerBindingServiceTest,AgentModelBindingControllerTest,AgentWorkspaceBindingControllerTest,AgentWorkerBindingControllerTest,UpstreamAdminAgentModelBindingControllerTest,UpstreamAdminAgentWorkspaceBindingControllerTest,UpstreamAdminAgentWorkerBindingControllerTest,UpstreamCliTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：

1. `business-agent-module`: 16 tests passed, 0 failures, 0 errors。
2. 扩展 targeted suite：`business-agent-module` 50 tests passed, 0 failures, 0 errors。
3. `navigator-open-sdk`: `UpstreamCliTest` 59 tests passed, 0 failures, 0 errors。
4. Reactor `BUILD SUCCESS`。

扩展回归：

```powershell
mvn -pl business-agent-module,addons/claude-worker-agent,addons/langgraph-biz-worker,navigator-open-sdk -am "-Dtest=AgentDefaultBindingServiceTest,BusinessAgentBundleServiceTest,UpstreamAdminAgentServiceTest,UpstreamTenantClientAppProvisioningServiceTest,AgentModelBindingServiceTest,AgentWorkspaceBindingServiceTest,AgentWorkerBindingServiceTest,AgentModelBindingControllerTest,AgentWorkspaceBindingControllerTest,AgentWorkerBindingControllerTest,UpstreamAdminAgentControllerTest,UpstreamAdminAgentModelBindingControllerTest,UpstreamAdminAgentWorkspaceBindingControllerTest,UpstreamAdminAgentWorkerBindingControllerTest,A2AgentResourceResolverTest,BusinessAgentTaskServiceTest,OpenApiAgentReadinessServiceTest,OpenApiControllerMessageMappingTest,BusinessAgentLanggraphLaunchE2ETest,BusinessAgentE2ESampleTest,RestAdapterUpstreamE2ETest,UpstreamCliTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：

1. `business-agent-module`: 99 tests passed, 0 failures, 0 errors。
2. `addons/claude-worker-agent`: 30 tests passed, 0 failures, 0 errors。
3. `addons/langgraph-biz-worker`: 2 tests passed, 0 failures, 0 errors。
4. `navigator-open-sdk`: `UpstreamCliTest` 59 tests passed, 0 failures, 0 errors。
5. Reactor `BUILD SUCCESS`。

## 32. Phase 21 Upstream Owner-aware Release Gate and Handoff Checkpoint

2026-05-24 已补齐上游改造前的 CLI / skill / 对外资料收口入口。

目标：

1. 上游在真实 `ask` 前有一条只读命令检查 `UpstreamSystemPrincipal + UpstreamClientApp + UpstreamUser + Agent` 的运行时资源闭环。
2. 检查项同时覆盖 profile 安全、ClientApp runtime token 自动换取、Agent readiness、模型 / Agent / WorkerPool / Workspace 解析结果。
3. 配套 `navigator-upstream-cli` skill 和对外安装/使用文档同步推荐新命令，避免上游只跑分散命令后遗漏 workspace 或 binding。
4. 输出一份可直接交付给上游 Agent 的改造提示词，不要求上游理解 Navi 内部目录或旧数据迁移细节。

实现：

1. `navigator-open-sdk` 新增 `navi upstream owner-smoke`：
   - 默认读取项目本地 `.navigator/upstream.env`。
   - profile 存在时必须被 git ignore 覆盖。
   - 复用现有 runtime token 自动交换逻辑。
   - 复用 OpenAPI `preflight` / `AgentReadiness`。
   - 额外校验 `effectiveModelConfigId`、`agentId`、`workerPoolId`、`effectiveDirectoryId`。
   - `--no-directory-required` 只用于明确不需要 workspace 的 Agent。
2. `UpstreamCliTest` 增加：
   - `ownerSmokeValidatesProfileReadinessAndResolvedRuntimeResources`。
   - `ownerSmokeRequiresDirectoryUnlessExplicitlyDisabled`。
3. `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/18-navigator-upstream-cli-usage-guide.md` 增加 owner-aware release smoke 章节。
4. `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/19-navigator-upstream-cli-install-update.md` 更新常用命令和故障说明。
5. 本地 `navigator-upstream-cli` skill 增加 `owner-smoke` 推荐入口。
6. `tools/navigator-upstream-cli/dist/package.ps1` 的 build features 增加 `owner-smoke`，便于对外包自描述。
7. `workitems/HANDOFF-20260524-upstream-owner-aware-resource-migration.md` 提供给上游的改造提示词。

边界：

1. `owner-smoke` 只读，不创建 task，不调用 Worker，不创建或修改资源。
2. 缺资源时 fail-closed；本轮仍不考虑旧数据迁移和旧接口兼容。
3. `--no-directory-required` 是例外开关，不改变默认资源模型。

验证：

```powershell
mvn -pl navigator-open-sdk -Dtest=UpstreamCliTest -DfailIfNoTests=false test
$env:PYTHONPATH=(Resolve-Path .\src).Path; pytest tests/test_openai_api.py
npm run typecheck
powershell -ExecutionPolicy Bypass -File tools\navigator-upstream-cli\dist\package.ps1
git diff --check
```

结果：

1. `navigator-open-sdk`: `UpstreamCliTest` 61 tests passed, 0 failures, 0 errors。
2. `tools/mock-llm-service`: `test_openai_api.py` 17 tests passed, 0 failures, 0 errors。
3. `business-agent-module/integration-tests`: TypeScript typecheck passed。
4. CLI package generated: `tools/navigator-upstream-cli/dist/output/navigator-upstream-cli-1.0.5-windows.zip`, SHA256 `948399ee76ab8739b8875f04045571f54e157af8f76fd27c1e10ecc193c5d75c`。
5. `git diff --check` passed; only CRLF normalization warnings were reported。

未进入本 checkpoint：

1. Agent WorkerPool 多选策略。
2. WorkerPool 级别 quota / concurrency / cost policy。
3. WorkerPool binding 的批量替换 API。
4. WorkerPool binding 前端管理页面。

## 33. Phase 22 Upstream-admin ClientApp Runtime Credential Checkpoint

2026-05-24 补齐 owner-aware bootstrap 中 ClientApp runtime credential 的 upstream-admin 管理能力。

目标：

1. 上游系统不再需要 root 登录、租户管理员 API key 或普通租户管理 API 来生成 `NAVI_CLIENT_APP_KEY` / `NAVI_CLIENT_APP_SECRET`。
2. `NAVI_ADMIN_API_KEY` 可以在其授权的 upstream system、namespace 和 tenant 范围内为已管理 ClientApp 签发 runtime credential。
3. CLI 标准 bootstrap 顺序明确为：
   - `client-app ensure`
   - `client-app issue-runtime-key`
   - `client-app issue-control-key`
   - `runtime-token --write-profile`
   - `model grant` / `ensure-grant`
   - `owner-smoke`
   - `ask` / `messages`
4. 完整 runtime secret 只落到 gitignored tenant profile；控制台只输出 masked app key、credentialId、clientAppId、tenantId 和 sha256 摘要。

实现：

1. `UpstreamBootstrapRequestService` 增加 scope：
   - `CLIENT_APP_RUNTIME_KEY_ISSUE`
   - alias: `RUNTIME_KEY_ISSUE` / `RUNTIME_CREDENTIAL_ISSUE`
   - 默认 upstream-admin approval scopes 包含该 scope。
2. `UpstreamClientAppAdminController` 增加：
   - `POST /api/v1/upstream-admin/client-apps/{clientAppId}/runtime-credentials`
   - 使用 `X-Navi-Admin-Key`，要求 `CLIENT_APP_RUNTIME_KEY_ISSUE`。
3. `UpstreamClientAppManagementService` 增加 `issueRuntimeCredential(...)`：
   - 复用现有 `requireManagedActiveClientApp(...)` 校验 tenant / upstreamSystemId / namespace / ACTIVE。
   - 复用 `ClientAppService.issueRuntimeCredential(...)` 生成实际 credential。
4. `navigator-open-sdk` 增加：
   - `BusinessAgentApi.issueUpstreamClientAppRuntimeCredential(...)`
   - `navi upstream client-app issue-runtime-key`
   - alias: `issue-runtime-credential`
5. CLI profile 行为：
   - 目标 `--tenant-profile` 必须 gitignored。
   - 写入 `NAVI_CLIENT_APP_KEY`、`NAVI_CLIENT_APP_SECRET`。
   - 清空 `NAVI_CLIENT_APP_ACCESS_TOKEN`，避免旧 token 与新 key/secret 混用。
   - 支持 `--rotate-runtime-credential`；当前语义是 repeat issue，重新签发一组 runtime credential。
6. 对外资料同步：
   - `18-navigator-upstream-cli-usage-guide.md`
   - `19-navigator-upstream-cli-install-update.md`
   - 本地 `navigator-upstream-cli` skill
   - CLI package feature list

边界：

1. 本 checkpoint 不实现自动 revoke 旧 runtime credential；轮换采用 repeat issue 并更新 profile。
2. 不通过控制台输出 secret 明文，也不把 secret 写入非 gitignored 文件。
3. 不恢复旧的 root / tenant admin 绕行流程作为标准 upstream bootstrap。
4. 旧 ClientApp 若没有匹配 `upstreamSystemId` / namespace，继续对 upstream-admin 不可见，需要按新模型重建或补齐数据。

验证：

```powershell
mvn -pl business-agent-module,navigator-open-sdk -am "-Dtest=UpstreamClientAppManagementServiceTest,UpstreamBootstrapEndToEndServiceTest,UpstreamBootstrapRequestServiceTest,UpstreamCliTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
powershell -ExecutionPolicy Bypass -File tools\navigator-upstream-cli\dist\package.ps1
```

结果：

1. Business Agent targeted tests: 14 passed, 0 failures, 0 errors。
2. `UpstreamCliTest`: 63 passed, 0 failures, 0 errors。
3. `navigator-open-sdk`: candidate version `1.0.6`。
4. CLI package generated: `tools/navigator-upstream-cli/dist/output/navigator-upstream-cli-1.0.6-windows.zip`。
5. 候选包 SHA 由最终提交后执行 `dist/package.ps1` 生成；不要在提交前固化 SHA，避免包内 `BUILD_INFO.gitCommit` 与最终提交不一致。

## 34. Phase 22 Follow-up 1.0.6 Bootstrap Smoke Feedback

2026-05-24 上游 School Sim owner-aware smoke 反馈了 `1.0.6` fresh bootstrap 的三个收口问题。

问题：

1. 本地提供的 `1.0.6` zip 不是最终提交后构建，包内 `BUILD_INFO.gitCommit` 仍指向旧提交，导致 `issue-runtime-key` 不存在。
2. 上游项目覆盖安装时 `tools/navigator-upstream/lib` 中残留旧 `navigator-open-sdk-1.0.5.jar`，wrapper 把所有 jar 放进 classpath 后加载了旧 CLI。
3. 既有 `NAVI_ADMIN_API_KEY` 是 `1.0.6` 之前签发，缺少新增 `CLIENT_APP_RUNTIME_KEY_ISSUE` scope，导致 `issue-runtime-key` 返回 403；项目尚未上线，但已接入的本地 upstream-admin profile 需要低成本继续验证。

调整：

1. `navi.ps1` classpath 只选择一份 `navigator-open-sdk` jar：
   - 优先匹配安装目录 `VERSION`。
   - 找不到时选择最新 jar 作为 fallback。
   - 其他 `navigator-open-sdk-*.jar` 不进入 classpath。
2. `install.ps1` 复制完成后按 `VERSION` 清理旧 `navigator-open-sdk-*.jar`，降低手工覆盖安装遗留风险。
3. upstream-admin scope 校验增加兼容桥：
   - `CLIENT_APP_ADMIN_ALL` 仍覆盖全部。
   - 显式 `CLIENT_APP_RUNTIME_KEY_ISSUE` 仍是新标准。
   - 旧 `CLIENT_APP_MANAGE` 可覆盖 `CLIENT_APP_RUNTIME_KEY_ISSUE`，但 controller 仍会继续校验 tenant / upstreamSystemId / namespace / ClientApp ACTIVE。
4. 文档和本地 `navigator-upstream-cli` skill 说明：
   - 新申请或重新审批应显式包含 `CLIENT_APP_RUNTIME_KEY_ISSUE`。
   - 旧 admin key 的 `CLIENT_APP_MANAGE` 仅作为兼容期 runtime key 签发覆盖。
   - 候选包 SHA 在最终提交后生成并随交付说明提供，不提前写入设计文档。

验证要求：

```powershell
mvn -pl business-agent-module,navigator-open-sdk -am "-Dtest=UpstreamClientAppAdminCredentialServiceTest,UpstreamClientAppManagementServiceTest,UpstreamBootstrapEndToEndServiceTest,UpstreamBootstrapRequestServiceTest,UpstreamCliTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
powershell -ExecutionPolicy Bypass -File tools\navigator-upstream-cli\dist\package.ps1
```

上游 clean profile 验收结果：

1. 使用包：
   - `navigator-upstream-cli 1.0.6`
   - SHA256 `216f309d42356e9e66ebce71c4a45dede4d57e000634b630c355568d286fbda8`
   - `BUILD_INFO.gitCommit=097ddbeb7c6d42a8f51521e96258ca8319a9447d`
2. 安装结果：
   - project-local `tools/navigator-upstream/lib` 仅剩 `navigator-open-sdk-1.0.6.jar`。
   - `upstream client-app --help` 包含 `issue-runtime-key`。
3. 新 profile：
   - `.navigator/tenants/school-owner-smoke-107.env`
   - profile 位于 `.navigator/` 且已被 git ignore 覆盖。
4. 新 ClientApp：
   - `clientAppId=capp_ee98641a-e91f-4538-97d2-97e24c7c1cc9`
5. bootstrap 命令链：
   - `client-app ensure`: passed
   - `issue-runtime-key`: passed，确认旧 admin key 兼容桥生效
   - `issue-control-key`: passed
   - `runtime-token`: passed
   - `model grant`: passed，默认模型 `9311f5b4-81a8-4619-9dfc-58712a8da12b`
   - `skill sync`: passed，`skillId=school-sim.developer.codex.v1`
   - `ensure-grant`: passed，`upstreamUserId=sim-upstream-user-local`
   - `owner-smoke`: readiness OK / resources OK / ready
   - `verify-agent-readiness`: OK
6. 真实 ask/messages smoke：
   - `taskId=lgt_4c5dc5f6fdd5446d`
   - `contextId=bctx_20260524_b3_b3349fad68e44e5d9bd0397699a2442e`
   - terminal status `COMPLETED`
   - assistant response `Hello! How can I assist you today?`
7. 安全与噪声：
   - 全程未输出 secret 明文。
   - `SLF4J no-provider` warning 仍存在，功能不受影响，作为后续低优先级 CLI 日志噪声治理项。
