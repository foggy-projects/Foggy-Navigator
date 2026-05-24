# Physical Worker Backend Capability Contract

Version: `1.1.6-SNAPSHOT`

Status: design record

Type: optimization / upstream contract clarification

Purpose: 从上游接入视角收口 Worker、WorkerPool、WorkingDirectory、LlmConfigModel 与 Agent 的关系。上游面对的资源应是物理 Worker 及其 backend capability；WorkerPool 退化为 Navigator 内部实现细节，不再作为上游 SDK / CLI / 前端必须理解或手动选择的资源。

## 1. 背景

当前实现和管理页面里存在三类历史概念混用：

1. 旧管理台中的 `Worker` 实际表示一台物理机或一套工作环境，上面可配置 Claude Code、Codex、Gemini、code-server、SSH 和本地工作目录。
2. A2Agent owner-aware 改造中引入了 `BizWorkerPool` / Agent worker binding，运行时通过 Agent 默认 WorkerPool 选择 launcher。
3. `LlmConfigModel` 已经包含 `workerBackend`，天然表达任务希望由 `CLAUDE_CODE`、`OPENAI_CODEX`、`GEMINI_CLI` 或 `LANGGRAPH_BIZ` 哪类 runtime 驱动。

这会导致上游心智不一致：上游传入 Codex 类型的 `configModelId` 时，自然会期待任务由同一物理工作目录所在机器上的 Codex backend 执行；但当前代码仍主要由 Agent 默认 WorkerPool 决定 launcher。

## 2. 目标模型

上游视角只暴露以下核心资源：

```text
PhysicalWorker
  workerId
  name
  machine / endpoint metadata
  capabilities:
    CLAUDE_CODE
    OPENAI_CODEX
    GEMINI_CLI
    LANGGRAPH_BIZ
  codeServer / ssh metadata

WorkingDirectory
  directoryId
  physicalWorkerId
  path
  workspaceScope
  owner policy

LlmConfigModel
  modelConfigId
  workerBackend
  provider / default modelName / available model names / budget preset
  owner policy

Agent
  agentId
  defaultModelConfigId
  optional defaultModelName / modelVariant preference
  defaultDirectoryId or workspace policy
  allowed model bindings
  allowed directory bindings
  allowed backend policy
  tools / skills / business functions
```

`WorkerPool` 不再作为上游一等资源暴露。若短期服务端仍需要 `workerPoolId`，它应由 Navigator 根据 `physicalWorkerId + workerBackend` 自动解析或生成，作为内部 routing artifact。

## 3. 核心不变量

1. 一个 `PhysicalWorker` 代表一台物理机或一套固定工作环境。
2. 一个 `WorkingDirectory` 必须绑定一个 `PhysicalWorker`，因为目录实际存在于该机器。
3. 一个 `PhysicalWorker` 对同一种 `workerBackend` 通常只需要一个 capability endpoint；当前阶段不支持同机同 backend 多实例选择。
4. `LlmConfigModel.workerBackend` 决定本次任务希望使用的 runtime backend。
5. `LlmConfigModel.modelName` 表示默认具体模型；`availableModels` 表示该 config 下允许的模型变体，例如 Claude Code 的 `sonnet` / `opus` / `opus[1m]`。
6. Agent 负责约束可用模型、目录、backend 和业务能力，不负责让上游手动选择 WorkerPool。
7. 新任务创建后必须固定 `modelConfigId + effectiveModelName + workerBackend + physicalWorkerId + directoryId`；继续任务时不得切换。
8. 如果默认模型、请求模型、目录所在 worker 和 Agent policy 无法形成唯一有效交集，必须 fail-fast，不允许随机选择。

## 3.1 Agent 作为 Runtime Profile

从上游使用视角，推荐把 Agent 当成一组稳定 runtime profile：

```text
Agent
  defaultModelConfigId
  defaultModelName / modelVariant preference
  defaultDirectoryId
  tool / skill / business function policy
  workspace policy
```

这样上游可以按自己的业务入口创建多个 Agent，例如“订单助手”“工单助手”“代码修复助手”“数据分析助手”。运行时大多数场景只需要传 `agentId`，由 Agent 默认模型和默认目录推导执行环境。

`configModelId` 仍允许在新任务首次请求中显式传入，用于在同一个 Agent policy 允许范围内临时选择 backend / credential / budget profile。`modelVariant` 或具体模型名只能在该 `configModelId` 的 allowlist 内选择；若为空，则按以下顺序得到 `effectiveModelName`：

```text
request.modelVariant / request.modelName
  -> Agent.defaultModelName
  -> LlmConfigModel.modelName
```

继续同一 task / context 时，即使请求再次传入 `configModelId` 或 `modelVariant`，也不能改变已冻结的 `effectiveModelName`、backend、目录或物理 Worker。需要切换模型或 backend 时，应创建新 task，或后续设计显式 fork / escalation 能力。

## 4. 运行时解析顺序

首次 `ask` / A2Agent task 创建时：

```text
1. 解析 ClientApp runtime credential。
2. 校验 upstream user grant。
3. 解析 Agent 可见性和 Agent policy。
4. 解析 requested configModelId；若为空则使用 Agent defaultModelConfigId。
5. 解析 requested modelVariant；若为空则使用 Agent defaultModelName，再回退到 LlmConfigModel.modelName。
6. 校验 effectiveModelName 在该 LlmConfigModel 允许范围内。
7. 从 LlmConfigModel 得到 workerBackend。
8. 解析 WorkingDirectory：
   - 优先使用请求允许的 directory preference（如后续开放 alias / preference）；
   - 否则使用 Agent defaultDirectoryId；
   - 否则使用 upstream user home directory / workspace resolver。
9. 从 WorkingDirectory 得到 physicalWorkerId。
10. 校验 PhysicalWorker 支持该 workerBackend capability。
11. 校验 Agent 允许该 model、directory、backend 和 tool/function set。
12. 内部解析或生成 workerPoolId / launcher route。
13. 创建 task 并固定 effective resources。
```

继续同一 task / context 时：

```text
1. 读取已有 task 固定资源。
2. 如果请求再次传入 configModelId / modelVariant 且与既有 task 不一致，fail-fast。
3. 不重新根据默认模型、默认目录或当前 Agent policy 随机切换 backend。
4. Agent / model / directory 已 disabled 时，新调用应 fail-closed；历史 report/log 仍可读。
```

## 5. WorkerPool 退化策略

短期兼容实现可以保留 `BizWorkerPool` 表和 launcher 接口，但上游契约改为：

1. SDK / CLI / 前端不要求上游创建或选择 WorkerPool。
2. WorkerPool 由 Navigator 内部根据 `physicalWorkerId + workerBackend` 管理。
3. `workerPoolId` 只允许出现在内部诊断、任务落库和 admin-only debug 输出中。
4. `owner-smoke` / readiness 面向上游输出 `physicalWorkerId`、`workerBackend`、`directoryId`、`modelConfigId`；如确需输出 `workerPoolId`，标注为 internal route。
5. 未来确实需要同 backend 多实例、灰度、HA、容量调度时，再把 WorkerPool 作为高级内部调度策略扩展，不提前暴露给普通上游接入流程。

## 6. 上游 SDK / CLI 口径

标准 bootstrap 不再要求上游理解 WorkerPool：

```text
1. client-app ensure
2. client-app issue-runtime-key
3. client-app issue-control-key
4. physical-worker ensure / inspect
5. model ensure / grant
6. working-directory ensure
7. agent ensure
8. agent bind-model / bind-workspace / bind-backend-policy
9. runtime-token
10. ensure-grant
11. owner-smoke
12. ask / messages
```

说明：

1. `physical-worker ensure` 表示创建或确认一台物理 worker 及其 backend capability。
2. `model ensure` 必须明确 `workerBackend`。
3. `working-directory ensure` 必须绑定 `physicalWorkerId`。
4. `agent ensure` 需要默认模型配置、默认模型变体建议和默认目录，或明确声明该 Agent 不需要 workspace。
5. `ask` 可以在新任务首次传 `configModelId` / `modelVariant` 选择 runtime backend 和具体模型；继续任务时不能切换。
6. 上游不传 `workerPoolId`、裸 `workdir` 或 filesystem path。

## 7. 前端文案口径

当前“Worker”页面应逐步改为“物理 Worker / 工作机器”心智：

1. Worker 类型不应只显示为 `Claude Code Worker`，而应表达这是一个 `PhysicalWorker`，Claude Code 是必备或默认 capability。
2. Codex / Gemini / LangGraph Biz 配置属于该物理 Worker 的 backend capability。
3. Agent 创建页面不应让用户困惑于 WorkerPool；应选择默认 LLM 配置和默认工作目录，必要时显示“将使用该目录所在 Worker 的对应 backend capability”。
4. Readiness / owner-smoke 应解释：
   - selected model backend；
   - selected directory；
   - selected physical worker；
   - backend capability 是否存在；
   - internal worker route 是否已就绪。

## 8. 当前实现偏差

截至本记录创建时，现有实现仍存在以下偏差：

1. `UpstreamAgentForm.workerId` 字段名仍是历史命名，但服务端当前按 generic worker reference 解释：先尝试 WorkerPool，再尝试 PhysicalWorker。
2. `CodingAgentEntity.workerId` 注释仍是旧 ClaudeWorker 外键语义，但 A2Agent resolver 已将其作为 generic worker reference。
3. `A2AgentResourceResolver` 已可解析 Agent 绑定的 PhysicalWorker，并从 `LlmConfigModel.workerBackend` 得到 launcher backend；完整 backend capability 表达仍待进一步产品化。
4. `ClientAppUpstreamUserGrantEntity` 当前没有 home worker / home directory 绑定；用户工作目录只能通过 WorkingDirectory scope / Agent default directory 间接表达。
5. readiness / owner-smoke 已补 `effectiveModelName`、`effectiveWorkerBackend`、`effectivePhysicalWorkerId`；`workerPoolId` 仅保留为 internal route debug。
6. request-level `modelVariant` / Agent `defaultModelName` 已进入 resolver；当前 `effectiveModelName` 由 request variant、Agent default model name、`LlmConfigModel.modelName` 三者按顺序解析。

这些偏差不需要做旧数据兼容；后续局部重构可以直接按新契约调整。

## 9. 后续实施建议

1. 命名收口：
   - 将上游表单和 SDK 中的 `workerId` 语义修正为 `physicalWorkerId` 或移除；
   - 将内部 `workerPoolId` 从普通上游接口隐藏。
2. Resolver 收口：
   - 先解析模型得到 `workerBackend`；
   - 再解析工作目录得到 `physicalWorkerId`；
   - 最后校验 backend capability 并生成内部 launcher route。
3. Agent 创建收口：
   - `defaultModelConfigId` 和 `defaultDirectoryId` 至少应能形成一个可执行默认 route；
   - `defaultModelName` / `modelVariant` 只能作为同一 config 下的具体模型偏好，不应改变 backend；
   - 缺少 workspace 的 Agent 必须显式声明 `no-directory-required`。
4. Upstream user 目录收口：
   - 支持为 upstream user 设置 home directory / workspace resolver；
   - 对 coding/agent 类任务，默认使用用户私有目录所在 physical worker。
5. Readiness / owner-smoke 收口：
   - 输出 `effectiveModelConfigId`、`effectiveModelName`、`workerBackend`、`physicalWorkerId`、`effectiveDirectoryId`、backend capability status；
   - `workerPoolId` 只作为 internal route debug 字段。
6. 前端和文档收口：
   - Worker 页面改为物理 Worker + capabilities；
   - Agent 页面围绕默认模型、默认目录和 policy 展示；
   - CLI skill 和 SDK 文档删除要求上游手动选择 WorkerPool 的主路径。

## 9.1 本轮落地记录

2026-05-24 已完成第一轮代码落地：

1. `A2AgentResourceResolver` 增加 `modelName`、`workerBackend`、`physicalWorkerId` 和 internal WorkerPool route 输出。
2. OpenAPI readiness / owner-smoke DTO、SDK DTO 和 CLI 输出增加 `effectiveModelName`、`effectiveWorkerBackend`、`effectivePhysicalWorkerId`。
3. CLI 主帮助将 `worker-pool` 标记为 internal compatibility；标准 bootstrap 不再推荐上游创建或选择 WorkerPool。
4. 前端 Worker / Agent 表单完成 PhysicalWorker/backend capability 文案调整。

2026-05-24 已完成第二轮模型变体落地：

1. `A2AgentResourceResolver` 输出 `requestedModelVariant`、`modelName`、`modelNameSource`，并校验 `availableModels`。
2. OpenAPI `ask` / readiness、BusinessAgent task form、Open SDK 和 upstream CLI 支持 `modelVariant`，并兼容 `model` / `modelName` 等 alias。
3. task 创建后落库并冻结 `modelConfigId + effectiveModelName`；继续同一 task / context 时不允许通过 `modelVariant` 切换具体模型。
4. LangGraph Biz Worker launch request 会接收冻结后的 `model`，Worker 侧不再重新按当前默认值选择具体模型。
5. upstream CLI skill 已补充 `NAVI_MODEL_VARIANT` / `--model-variant` 的使用边界：只用于新 task 首次选择同 config 下的模型变体，不用于 continuation。

2026-05-24 已完成第三轮 PhysicalWorker runtime resolver 落地：

1. 新增 `PhysicalWorkerRuntimeRegistry` / `ResolvedPhysicalWorker` 扩展点，用于把不同物理 Worker 注册表接入 A2Agent runtime resolver。
2. `ClaudeWorkerEntity` 已通过 `ClaudeWorkerPhysicalWorkerRuntimeRegistry` 暴露给 runtime resolver；`upstream worker list/get/create` 可见的物理 `workerId` 可被 Agent 使用。
3. 旧 `BizWorkerIdentityEntity` 已通过 `BizWorkerIdentityPhysicalWorkerRuntimeRegistry` 保留为一类 PhysicalWorker 来源。
4. `A2AgentResourceResolver` 对 `CodingAgent.workerId` 的解析顺序调整为：

```text
workerRef
  -> BizWorkerPool.poolId
  -> PhysicalWorkerRuntimeRegistry.resolve(...)
```

5. PhysicalWorker 路径下，Agent route 不再携带 WorkerPool backend；`effectiveWorkerBackend` 由 `LlmConfigModel.workerBackend` 决定。
6. `OpenApiAgentReadinessService` 与 upstream CLI owner-smoke 的 resource gate 已统一要求可解释的 `effectiveModelConfigId`、`agentId`、`effectiveWorkerBackend`、`effectiveDirectoryId` 和 `effectivePhysicalWorkerId`。
7. `BusinessAgentTaskService` 在 PhysicalWorker 路径下会把旧 `workerPoolId` 落库字段作为 internal worker route ref 使用，写入 `physicalWorkerId`，避免旧 not-null 约束阻断真实 ask/messages。该字段仍不作为上游标准输入。

## 10. 验收标准

1. 上游仅通过 PhysicalWorker、WorkingDirectory、LlmConfigModel 和 Agent 即可完成 bootstrap，不需要手动创建或绑定 WorkerPool。
2. 传入 Codex 类型 `configModelId` 时，任务必须在目录所在 physical worker 的 Codex capability 上运行。
3. 目录所在 worker 不支持所选 `workerBackend` 时，readiness 和 ask 都 fail-fast，并给出可诊断错误。
4. 继续同一 task / context 时，不能切换 `configModelId`、backend、directory 或 physical worker。
5. owner-smoke 能解释最终为何选择某个模型、目录、物理 worker 和 backend capability。
6. 旧的 `workerPoolId` 只在内部任务、日志或 admin debug 中出现，不进入上游标准接入文档主流程。
