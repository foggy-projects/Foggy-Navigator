# Upstream Principal Resource Implementation Plan

Version: `1.1.6-SNAPSHOT`

Status: design plan

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
