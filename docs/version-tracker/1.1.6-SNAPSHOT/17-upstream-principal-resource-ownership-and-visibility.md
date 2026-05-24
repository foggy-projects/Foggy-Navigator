# Upstream Principal Resource Ownership and Visibility

Version: `1.1.6-SNAPSHOT`

Status: design record

Purpose: 从上游系统视角统一 `ADMIN_KEY`、`ClientApp`、`UpstreamUser` 与 Worker / LLMConfigModel / WorkingDirectory / Agent 的创建、归属、授权和运行时可见性边界，避免把可轮换 credential 当成稳定资源 owner，也避免 A2Agent 运行时跨 App / 跨用户串用资源。

## 1. 核心结论

Navigator 面向上游接入时，应区分三类稳定主体：

| 主体 | 说明 | 典型 credential |
| --- | --- | --- |
| `UpstreamSystemPrincipal` | 上游系统主体，代表一个被 Navigator 授权接入的上游系统或上游系统命名空间 | `NAVI_ADMIN_API_KEY` |
| `UpstreamClientApp` | 上游系统下的应用主体，代表一个业务应用、助手入口或 Agent 容器 | `NAVI_CONTROL_API_KEY` |
| `UpstreamUser` | 上游应用内的业务用户主体，代表一次运行时调用的真实业务用户 | upstream user token / grant |

`ADMIN_KEY`、`CONTROL_KEY`、upstream user token 都是 credential，不是资源 owner。credential 可以轮换、撤销、过期、按 scope 限权；资源 owner 必须指向稳定主体。

## 2. 资源归属模型

资源默认使用四类 owner scope：

| Owner scope | Owner id | 说明 |
| --- | --- | --- |
| `PLATFORM` | Navigator tenant / platform owner | Navigator 平台或租户管理员维护的共享资源 |
| `UPSTREAM_SYSTEM` | `UpstreamSystemPrincipal.id` | `ADMIN_KEY` 背后的稳定主体创建或管理的上游系统共享资源 |
| `CLIENT_APP` | `clientAppId` | 某个 ClientApp 创建或独占管理的 App 级资源 |
| `UPSTREAM_USER` | `clientAppId + upstreamUserId` | 某个上游用户在某个 ClientApp 下的私有资源 |

资源创建者只决定管理权，运行时使用权必须通过 grant / binding / policy 再解析。

## 3. 资源矩阵

| 资源 | 推荐 owner | 可授权 / 绑定给 | 运行时说明 |
| --- | --- | --- | --- |
| `PhysicalWorker` / backend capability | `PLATFORM` 或 `UPSTREAM_SYSTEM` | `UpstreamClientApp` / `Agent` | Worker 是物理机或固定工作环境，不应直接归属 UpstreamUser。一个 PhysicalWorker 可以声明 Claude Code / Codex / Gemini / LangGraph Biz 等 backend capability；WorkerPool 退化为内部 routing artifact，不作为上游标准接入资源 |
| `LlmConfigModel` | `PLATFORM` / `UPSTREAM_SYSTEM` / `CLIENT_APP` | `UpstreamClientApp` / `Agent` / 可选 `UpstreamUser` policy | Admin 创建的模型可作为上游系统共享模型；ClientApp 创建的模型只对该 ClientApp 及其用户可见；`workerBackend` 决定 runtime backend，`modelName` / `availableModels` 决定同一 config 下的具体模型变体 |
| `WorkingDirectory` | `UPSTREAM_SYSTEM` / `CLIENT_APP` / `UPSTREAM_USER` | `UpstreamClientApp` / `Agent` / `UpstreamUser` | 目录允许多种模式：系统共享、App 共享、用户私有。具体使用哪种由上游通过 workspace policy 决定 |
| `Agent` | `UPSTREAM_SYSTEM` / `CLIENT_APP` | `UpstreamUser` | Agent 是运行时能力入口，绑定可用 Worker、模型、目录策略、工具集合和 Skill / function 权限 |

### 3.1 PhysicalWorker 与 backend capability

上游标准契约中，`Worker` 应理解为 `PhysicalWorker`：一台物理机、一台开发机、一个容器宿主，或一套固定工作环境。该物理 Worker 上可以同时存在多个 backend capability：

```text
PhysicalWorker(worker-119)
  CLAUDE_CODE: http://192.168.31.119:3032
  OPENAI_CODEX: http://192.168.31.119:3052
  GEMINI_CLI: http://192.168.31.119:3072
  LANGGRAPH_BIZ: http://192.168.31.119:3061
  codeServer / ssh / workspace metadata
```

当前阶段默认同一台 PhysicalWorker 对同一种 `workerBackend` 只配置一个 capability endpoint，不引入同机同 backend 多实例调度。`WorkerPool` 因此不应作为上游必须创建、选择或理解的资源；如果服务端短期仍需要 `workerPoolId`，应由 Navigator 根据 `physicalWorkerId + workerBackend` 内部解析。

`LlmConfigModel.workerBackend` 决定任务使用哪类 runtime backend；`WorkingDirectory.workerId` 决定目录所在 PhysicalWorker。运行时 resolver 应取二者交集，并校验该 PhysicalWorker 是否具备对应 backend capability。

## 4. Credential 与主体的关系

### 4.1 Admin credential

`NAVI_ADMIN_API_KEY` 映射到 `UpstreamSystemPrincipal`：

```text
UpstreamSystemPrincipal
  id: usp_xxx
  tenantId
  upstreamSystemId
  namespace
  status

UpstreamAdminCredential
  keyId
  principalId: usp_xxx
  keyHash
  scopes
  expiresAt
  status
```

Admin credential 创建的资源不应记录 `keyId` 为 owner，只应记录：

```text
ownerType = UPSTREAM_SYSTEM
ownerId = usp_xxx
createdByCredentialId = key_xxx   # 审计字段，不参与资源归属
```

这样 key 轮换、撤销、重新签发后，已有 Worker / LLMConfigModel / 共享目录仍由同一个上游系统主体管理。

### 4.2 ClientApp control credential

`NAVI_CONTROL_API_KEY` 映射到 `UpstreamClientApp`：

```text
UpstreamClientApp
  clientAppId
  upstreamSystemPrincipalId
  tenantId
  namespace
  status

ClientAppControlCredential
  keyId
  clientAppId
  scopes
  expiresAt
  status
```

ClientApp 创建的资源默认：

```text
ownerType = CLIENT_APP
ownerId = clientAppId
```

这类资源只对同一个 ClientApp 下的 upstream user grant 可见，不能因为另一个 ClientApp 也存在相同字符串的 `upstreamUserId` 而跨 App 共享。

### 4.3 Upstream user grant

`UpstreamUser` 是运行时 subject，不是基础设施 owner。它可以拥有用户私有目录、用户私有记忆和偏好设置，但不应直接拥有 Worker 或系统级 LLMConfigModel。

Upstream user 的唯一边界应至少包含：

```text
tenantId + upstreamSystemPrincipalId + clientAppId + upstreamUserId
```

不要把裸 `upstreamUserId` 当成全局用户 id、Navigator user id、Worker owner、Model owner 或目录 owner。

## 5. 推荐授权规则

### 5.1 Admin 创建的 Worker / LLMConfigModel

Admin 创建的资源属于 `UPSTREAM_SYSTEM`，可被同一 `UpstreamSystemPrincipal` 管理范围内的 ClientApp 使用。

为了避免敏感资源自动扩散，建议支持两种策略：

| 策略 | 说明 |
| --- | --- |
| explicit grant | 默认推荐。Admin 创建资源后显式 grant 给某些 ClientApp / Agent |
| auto grant policy | 可选。上游明确配置某类资源自动授予新建 ClientApp |

即使采用 auto grant，也应记录 policy 来源，并允许后续按 ClientApp / Agent disable。

### 5.2 ClientApp 创建的 LLMConfigModel

ClientApp 创建的模型属于 `CLIENT_APP`：

```text
ownerType = CLIENT_APP
ownerId = clientAppId
```

运行时可被该 ClientApp 下的 upstream users 使用，但仍应经过：

```text
model enabled
+ clientApp active
+ upstreamUser grant active
+ agent model policy
+ model budget / capability policy
```

模型 provider API key、baseUrl secret、token 等敏感字段只能服务端使用，不应通过 grant / list 接口返回给上游用户或浏览器。

### 5.3 工作目录

WorkingDirectory 必须显式声明 scope：

| Scope | Owner | 适用场景 | 风险 |
| --- | --- | --- | --- |
| `USER_PRIVATE` | `clientAppId + upstreamUserId` | 用户个人工作区、个人记忆文件、个人上传/产物 | 目录数量和配额增长 |
| `CLIENT_APP_SHARED` | `clientAppId` | App 内共享资料、业务团队共享上下文 | 多用户并发写入、数据互见 |
| `UPSTREAM_SYSTEM_SHARED` | `UpstreamSystemPrincipal.id` | 上游系统级公共资料、模板、只读仓库 | 跨 App 误共享 |

上游可决定某个 Agent / 会话使用用户私有目录还是 ClientApp 共享目录，但必须通过 workspace policy 表达，不能由 BizWorker 根据路径或 account id 猜测。

workspace policy 至少应能表达：

```text
workspaceScope
workspaceId / resolverKey
readOnly
allowWrite
allowCreate
allowedPathPrefixes
quota
retentionPolicy
concurrencyPolicy
```

## 6. Agent 资源绑定

Agent 是运行时入口，应聚合可用资源而不是隐式继承全部资源。

一个 Agent 的运行时资源可通过以下方式解析：

```text
Agent
  allowedPhysicalWorkers / allowedBackendPolicy
  allowedModels / defaultModelConfigId
  workspacePolicy
  allowedTools
  allowedSkills
  allowedFunctions
```

Admin 创建的系统共享资源可以 grant 给多个 ClientApp，再由 ClientApp 或 Agent 选择；ClientApp 私有资源只能在该 ClientApp 内绑定。

Agent 不应要求上游直接选择 WorkerPool。Agent 可以绑定默认模型、默认目录、允许的模型、允许的目录和允许的 backend policy；最终物理 Worker 由目录解析得出，最终 backend 由 `LlmConfigModel.workerBackend` 得出。若二者不能形成唯一可执行 route，运行时必须 fail-fast。

从上游使用方式看，Agent 应被视为稳定 runtime profile：它固定默认 `LlmConfigModel`、默认 `WorkingDirectory`、可选默认 `modelVariant`、工具 / Skill / function policy。上游可以创建多个 Agent 来表达不同业务入口，运行时大多数场景只传 `agentId`；首次任务可在 Agent policy 允许范围内传 `configModelId` / `modelVariant` 覆盖默认值，但同一 task / context 后续不得切换。

### 6.1 上游推荐使用方式

上游接入时建议把 Agent 当成面向业务的稳定入口，而不是每次请求临时拼 `workerId + modelConfigId + directoryId`：

1. 为不同业务入口创建多个 Agent，例如 `ticket-agent`、`school-sim.developer`、`data-analysis-agent`。
2. 每个 Agent 绑定默认 `LlmConfigModel`、默认 `WorkingDirectory`、可见工具 / Skill / function，以及可用 backend policy。
3. 运行时优先只传 `agentId` / `agentCode` 和用户消息。
4. 新任务确实需要切换模型配置时，只在首次 ask 传 `configModelId`；同一 task / context 创建后，后续续聊即使再次传入也不得改变已冻结的 effective config。
5. `modelVariant` 只用于在同一个 `LlmConfigModel` 允许的模型集合内选择具体模型，例如同一 Claude Code config 下的 `sonnet` / `opus`，或同一兼容 provider config 下的 `qwen3.5-plus` / `qwen-max`。它不应改变 `workerBackend`、provider credential、workspace 或 physical worker。

因此，`configModelId` 决定 runtime backend 类型，`modelVariant` 决定同一 backend/config 内的具体模型名，`WorkingDirectory` 决定物理工作目录所在 worker。resolver 必须把三者解析为唯一可执行路线；无法唯一解析时 fail-fast。

## 7. Runtime Resolver

A2Agent 调用时，最终可见资源应取交集，不应只看某个 credential：

```text
tenant scope
+ upstreamSystemPrincipal active
+ clientApp belongs to upstreamSystemPrincipal
+ clientApp active
+ runtime credential valid
+ upstreamUser grant active
+ agent visible to upstreamUser
+ resource owner / grant / binding valid
+ resource enabled
+ runtime policy allows this use
```

建议运行时 resolver 输出显式结构，便于审计和排查：

```json
{
  "tenantId": "...",
  "upstreamSystemPrincipalId": "usp_xxx",
  "clientAppId": "...",
  "upstreamUserId": "...",
  "agentId": "...",
  "resolvedPhysicalWorkerId": "...",
  "resolvedWorkerBackend": "OPENAI_CODEX",
  "resolvedModelConfigId": "...",
  "resolvedModelName": "opus[1m]",
  "resolvedWorkspaceScope": "USER_PRIVATE",
  "resolvedWorkspaceId": "...",
  "grantSources": ["clientAppModelGrant:...", "agentBinding:..."],
  "internalRoute": {
    "workerPoolId": "..."
  }
}
```

`internalRoute.workerPoolId` 仅用于 admin debug、任务落库或短期兼容诊断；上游标准 SDK / CLI / 前端不应要求调用方传入该字段。

## 8. 上游视角容易漏掉的点

### 8.1 Key rotation 不应影响资源 owner

Admin / control / user token 都会轮换。资源 owner 必须指向稳定主体，key id 只能作为审计字段。

### 8.2 Admin 资源共享不应无条件扩散

“Admin 创建的资源可给所有 ClientApp 用”是能力上限，不应等同于默认全部开放。至少要有 explicit grant 或 auto grant policy。

### 8.3 Worker 是执行边界，不只是普通资源

Worker 可能拥有本地文件、网络、进程和 shell 能力。共享 Worker 时必须检查 capability、allowed tools、工作目录隔离和日志归属。

### 8.4 Directory 比 model 更容易造成数据串用

共享目录会带来多用户互见、并发写冲突、历史产物泄漏和备份/清理责任。必须让上游显式选择 `USER_PRIVATE` / `CLIENT_APP_SHARED` / `UPSTREAM_SYSTEM_SHARED`。

### 8.5 UpstreamUserId 只在 ClientApp 内唯一

相同 `upstreamUserId` 字符串可能出现在不同 ClientApp。任何 grant、记忆、目录和审计都必须带上 `clientAppId`。

### 8.6 Secret 可用不等于可读

上游用户、浏览器、普通 list 接口只能看到脱敏后的 model / worker / directory 元数据。provider key、control key、admin key、upstream user token 不应返回。

### 8.7 资源删除需要区分 disable 与 hard delete

模型、Worker、目录、Agent 被停用后，历史 task/report/log 仍可能引用它们。默认应先 `disabled`，只有满足 retention / no-reference 条件后才 hard delete。

### 8.8 计费、限流和预算应挂在主体层级

LLM token budget、并发数、Worker 使用量、目录容量可以分别挂在 `UPSTREAM_SYSTEM`、`CLIENT_APP`、`UPSTREAM_USER` 或 Agent policy 上。运行时要能解释是哪一层拒绝。

### 8.9 Legacy Navigator userId 不能泄漏到上游模型

现有 Worker / WorkingDirectory 代码中可能仍有 Navigator 内部 `userId` 维度。OpenAPI / upstream runtime 不应把 `upstreamUserId` 直接当内部 `UserEntity.id`，需要通过 effective service user 或 resource resolver 过渡。

### 8.10 审计要同时记录 actor 和 subject

控制面操作的 actor 是 credential 所属主体，例如 `UpstreamSystemPrincipal` 或 `UpstreamClientApp`；运行时 subject 是 `UpstreamUser`。两者都要记录，避免排查时只看到“某用户用了某模型”，却看不到该模型是哪个 admin grant 进来的。

## 9. API / SDK 口径

### 9.1 Admin plane

Admin plane 负责：

1. 申请、审批、领取、轮换 `NAVI_ADMIN_API_KEY`。
2. 创建或复用 `UpstreamSystemPrincipal`。
3. 创建 / 管理授权范围内的 `UpstreamClientApp`。
4. 创建上游系统级共享 PhysicalWorker / backend capability / LLMConfigModel / WorkingDirectory。
5. 给 ClientApp / Agent 授权共享资源。

### 9.2 ClientApp control plane

ClientApp control plane 负责：

1. 创建 ClientApp-owned LLMConfigModel。
2. 管理该 ClientApp 的 routes / functions / skills / agents。
3. 管理该 ClientApp 下的 upstream user grants。
4. 选择该 ClientApp 下 Agent 的模型、工具和目录 policy。

### 9.3 Runtime plane

Runtime plane 负责：

1. 使用 runtime credential 发起 ask / A2Agent 调用。
2. 携带 `clientAppId`、`upstreamUserId`、`agentId` 或 root agent 信息。
3. 由 BizWorker / Navigator 解析可见资源，不接受客户端直接指定任意 WorkerPool、裸 worker route、model secret 或 filesystem path。

## 10. 验收不变量

后续实现应满足：

1. 轮换 `NAVI_ADMIN_API_KEY` 后，Admin 创建的资源 owner 不变。
2. ClientApp A 创建的模型，ClientApp B 默认不可见，即使二者有相同 `upstreamUserId`。
3. Admin 创建的模型可以 grant 给多个 ClientApp，但未 grant 或被 disabled 的 ClientApp 不能使用。
4. UpstreamUser grant 失效后，该用户不能再通过 A2Agent 使用 Agent、模型、Worker 或目录。
5. 用户私有目录不能被同 ClientApp 下其他 upstream user 读取，除非存在显式共享 policy。
6. 共享目录必须在 runtime resolver 中输出 scope 和 policy，便于排查。
7. 普通用户 list 接口不能返回 provider key、admin key、control key、upstream user token 明文。
8. Worker / model / directory 的 `createdByCredentialId` 只用于审计，不参与 owner 判断。
9. 历史 task/report/log 引用已 disabled 资源时，报告仍可读，新的 runtime 调用不得继续使用该资源。
10. Runtime submission 日志应能记录 resolved model / physical worker / workerBackend / workspace 的脱敏元数据，便于上游排查“为什么本次调用用了这个资源”。

## 11. 后续实现建议

1. 为 Admin credential 引入稳定的 `UpstreamSystemPrincipal` 显式实体或等价字段，停止把 key 当 owner。
2. 为 PhysicalWorker / backend capability / LLMConfigModel / WorkingDirectory / Agent 统一补齐 `ownerType`、`ownerId`、`createdByCredentialId`、`grantPolicy`、`enabled`、`disabledReason`。
3. 实现统一 `ClientAppResourceResolver` / `A2AgentResourceResolver`，把当前分散在模型、目录、Agent、Worker 的可见性判断收口。
4. 为工作目录补 `workspaceScope` 和 `workspacePolicy`，避免继续依赖固定 `<data_root>/accounts/<accountId>` 推断。
5. 为 SDK / CLI 增加可观测命令：查看某个 ClientApp / upstreamUser / Agent 最终可用的模型、PhysicalWorker、backend capability、目录和授权来源。
