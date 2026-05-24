# Upstream CLI / Skill Runtime Contract Alignment

## 文档作用

- doc_type: design-addendum
- intended_for: upstream-integrator | cli-maintainer | execution-agent
- purpose: 收口 Navigator Upstream CLI、配套 `navigator-upstream-cli` skill 与 BizWorker 1.1.6 runtime context 新契约的对齐口径，并记录模型 token 预算 preset 字段落地方式

版本：`1.1.6-SNAPSHOT`
状态：设计收口 / 字段落地
类型：upstream integration / runtime context contract / model budget

## 背景

BizWorker 1.1.6 已经把 LLM runtime context 调整为 Worker-owned bounded memory。上游继续保存完整 UI transcript，CLI / SDK / BFF 只负责发起任务、携带用户身份、复用已有 `contextId` 和读取展示态消息。

因此，CLI 与配套 skill 需要避免继续暗示上游要自己维护或拼接 LLM prompt 历史，也不能把 `clientContext`、Skill 列表或模型 token 预算混在用户消息里。

## 当前对齐结论

### 1. `contextId` 由 BizWorker 生成，CLI 只负责复用

新会话：

```powershell
.\tools\navigator-upstream\navi.ps1 upstream ask --upstream-user-id <id> --message "..."
```

上游不需要传 `--context-id`。BizWorker / OpenAPI 会生成标准 `bctx_yyyyMMdd_<hash>_<id>`，返回的 `contextId` 用于后续续聊、UI 会话归属和 runtime session 目录定位。

续聊：

```powershell
.\tools\navigator-upstream\navi.ps1 upstream ask `
  --upstream-user-id <id> `
  --context-id <returnedContextId> `
  --message "继续"
```

`--context-id` 只用于复用一个已存在、且属于同一 ClientApp + upstream user 的会话。上游不再建议自行构造 `bctx_...`，除非是在迁移或诊断中复用已有会话 id。

### 2. 上游保存完整 UI transcript，BizWorker 保存 bounded runtime context

CLI 的 `messages` / `session-messages` 读取的是展示态消息，不是下一轮提交给 LLM 的事实来源。

BizWorker 内部维护：

- Root `ContextRuntimeMemory`
- Root-visible user / assistant / tool protocol
- lazy compaction 后的 summary + retained window
- frame focus / interruption control state

上游不应再通过 `recentConversation` 或 `clientContext` 传完整历史给 BizWorker。`recentConversation` 只保留为 deprecated compatibility bootstrap，并且不能覆盖已有 Worker memory。

### 3. `clientContext` 是会话元数据，不是 LLM 配置

`--client-context-json` / `--client-context-file` 只能承载上游会话、业务对象、trace 等元数据，例如：

```json
{
  "upstreamConversationId": "tms-ai-10001",
  "bizObjectType": "order",
  "bizObjectId": "SO-10001"
}
```

它不用于：

- 注入完整 prompt 历史
- 覆盖 system prompt
- 配置模型 token 预算
- 指定 workspace root
- 暴露 skill / function adapter 私有配置

### 4. Skill 与 Agent 的运行时边界

1. Skill 是当前 frame 内的材料 / 工具能力，不再默认打开 child frame。
2. Root 调用 `invoke_business_skill` 后，tool call / tool result 作为 Root-visible provider protocol 进入 bounded runtime context，直到压缩或裁剪。
3. 只有显式调用 Agent 能力时才创建 child Agent frame。
4. Agent frame 有独立 system prompt、独立 runtime-message-events 和独立恢复边界；默认不继承 Root 完整 runtime history。
5. Agent 完成后，Root 只接收 promoted result / digest / refs，不展开 Agent 内部 raw tool trace。

CLI skill 在指导上游时应把 `skill sync` / `agent sync` 区分清楚：Skill bundle 是材料和工具授权，Agent bundle 才代表可进入 frame 的子 Agent 能力。

### 5. A2Agent 资源绑定是运行时可见性边界

CLI / SDK 已补齐 A2Agent 资源绑定控制面，运行时不再只依赖 Agent 默认字段做资源解析。Agent 默认字段与显式 binding 集合必须保持一致：

- `defaultModelConfigId` 对应 `agent model-bindings` / `agent system-model-bindings`。
- `defaultDirectoryId` 对应 `agent workspace-bindings` / `agent system-workspace-bindings`。
- `workerId` 历史上对应 `agent worker-bindings` / `agent system-worker-bindings`，当前新口径中仅作为内部 route / compatibility binding；上游标准路径应优先通过 `defaultModelConfigId + defaultDirectoryId` 推导 backend 和 PhysicalWorker。

创建或更新 Agent 后，服务端会把非空默认资源自动 materialize 到对应 binding 表。`set-default-*` 命令也必须先确保 binding 存在，再更新 Agent 默认字段。这样 `inspect runtime` / `verify-agent-readiness` / A2Agent resolver 后续可以用同一套 binding 数据解释“为什么这个模型、目录、backend capability 和 PhysicalWorker 对当前 Agent 可见”。若输出 `workerPoolId`，它只表示 internal route debug。

身份边界：

1. `NAVI_ADMIN_API_KEY` 是 `UpstreamSystemPrincipal` 的可轮换凭据，用于 system-owned Agent、共享模型、共享工作目录、PhysicalWorker/backend capability，以及为其可管理 ClientApp 签发 runtime credential / control credential。
2. `NAVI_CONTROL_API_KEY` 是 `UpstreamClientApp` 的控制面凭据，用于 ClientApp-owned Agent 和该 ClientApp 范围内的模型、目录、Agent binding。
3. Upstream user token 只进入 runtime ask / sessions / account-context，不参与资源创建与绑定。
4. `ask` / `clientContext` 不允许临时携带模型、目录、WorkerPool、backend route 或裸 filesystem path 作为授权绕过；只能携带业务元数据。新 task 如需选择模型，必须使用显式 `--model-config-id` / `--model-variant` 参数，而不是塞进 `clientContext`。

模型变体边界：

1. `NAVI_MODEL_CONFIG_ID` / `--model-config-id` 选择同一个 Agent policy 允许的模型配置。
2. `NAVI_MODEL_VARIANT` / `--model-variant` 只选择该配置下的具体模型名，例如 `sonnet`、`opus`、`codex-mini`。
3. `modelVariant` 不能改变 `LlmConfigModel.workerBackend`，也必须在 `availableModels` allowlist 内。
4. 同一 task / context 继续调用时，Navigator 使用 task 已冻结的 `modelConfigId + effectiveModelName`；即使请求再次传入不同 `modelVariant` 也会 fail-fast。
5. 推荐上游把常用组合固化成不同 Agent，正常 ask 只传 `agentId` 和用户消息。

上游发布前推荐执行：

```powershell
.\tools\navigator-upstream\navi.ps1 version
.\tools\navigator-upstream\navi.ps1 upstream client-app issue-runtime-key --client-app-id <clientAppId> --write-profile
.\tools\navigator-upstream\navi.ps1 upstream runtime-token --write-profile
.\tools\navigator-upstream\navi.ps1 upstream owner-smoke
```

`issue-runtime-key` 需要 `navigator-upstream-cli` / `navigator-open-sdk` `1.0.6` 或更高版本。

该命令是只读 release gate：检查 profile gitignore、安全换取 runtime token、执行 OpenAPI readiness，并额外要求当前 `Agent + UpstreamUser + ClientApp` 组合能解析到 `effectiveModelConfigId`、`effectiveModelName`、`effectiveWorkerBackend`、`agentId`、`effectiveDirectoryId`、`effectivePhysicalWorkerId`。如果目标 Agent 确认不需要 workspace，可显式使用 `--no-directory-required`，但这应作为例外记录在上游验收说明中。

### 6. Account workspace / memory 进入 Root system context

BizWorker 会根据 account / upstream user 身份解析 workspace，并把受控记忆文件注入 Root system prompt。

当前设计支持两种模式：

1. managed account mode：默认 `<data_root>/accounts/<accountId>/...`
2. delegated workspace mode：上游在创建 upstream user 或绑定关系时配置受托工作目录

CLI 的 `account-context list/read/write-policy` 仍是当前可用的检查入口。后续如果允许上游自定义 workspace root，应通过受控绑定 API 配置，不应通过 `ask` 的 `clientContext` 每次临时传路径。

### 7. LLM submission 复盘日志是 Worker 诊断产物

开启 `BIZ_WORKER_LLM_SUBMISSION_LOG_ENABLED=true` 后，BizWorker 在 session 目录下保存真实提交给 ChatModel 的 body：

```text
data/runtime/sessions/by-date/yyyy/MM/dd/<hash>/<contextId>/logs/llm-submissions/
```

该目录用于诊断“这一轮到底发给 LLM 什么”，不是 CLI 直接读取的公开 OpenAPI 契约。

## 模型 token 预算配置状态

2026-05-22 已在 Java `LlmModelConfig` 一等字段中补充 LangGraph Biz runtime budget preset 配置。当前稳定字段包括：

- `name`
- `category`
- `baseUrl`
- `modelName`
- `apiKey`
- `isDefault`
- `scope`
- `workerBackend`
- `envVars`
- `availableModels`
- `runtimeBudgetPresetKey`
- `runtimeBudgetOverrideJson`
- `sortOrder`

仍不建议把所有 token 数字都一等字段化。以下数值由 preset resolver 解析产生，后续可按治理需要逐步迁移：

- `contextWindowTokens`
- `maxInputTokens`
- `maxOutputTokens`
- `defaultOutputTokens`
- `maxToolResultTokens` / `maxToolResultChars`
- `projectHistoricalToolResults`
- `rawToolResultTailTurnCount`
- `autoCompactInputTokenThreshold`

前端只在 worker backend 环境变量说明中展示了 Codex / Claude 相关参考项，例如 `model_context_window`、`model_auto_compact_token_limit`、`tool_output_token_limit`。这些属于特定 worker backend 的 env var，不是 LangGraph Biz runtime context governance 的稳定模型字段。

## 模型预算字段

`LlmModelConfig` 采用“预算预设引用 + 自动匹配 + 可选覆盖”的配置方式，而不是要求每个上游每次手填完整 token 参数。

已新增字段：

| 字段 | 用途 |
| --- | --- |
| `runtimeBudgetPresetKey` | 可选。指向一个预置模型预算，例如 `openai.gpt-4.1-128k`、`qwen.qwen3-235b-128k`、`generic.128k` |
| `runtimeBudgetOverrideJson` | 可选。只覆盖少量特殊字段，正常上游不需要填写 |

预设值不要求全部存在数据库中。第一版可以使用代码内置或配置文件，例如：

```text
config/model-runtime-budget-presets.json
```

预算解析顺序：

1. 如果 `runtimeBudgetPresetKey` 存在，优先使用它指向的预设。
2. 如果没有显式 preset，则按 `workerBackend` + `provider/envVars.NAVI_LLM_PROVIDER` + `modelName` 自动匹配内置预设。
3. 如果自动匹配失败，使用 backend 默认兜底预算，并在 readiness / logs 中给出 warning。
4. 如果存在 `runtimeBudgetOverrideJson`，只覆盖 preset 中指定字段。

当前 BizWorker 内置预设至少包括：

- `generic.32k`
- `generic.128k`
- `generic.200k`
- `generic.1m`

Codex / Claude Code / Gemini CLI 这类 native worker backend 默认由各自 worker 自主管理上下文。Navigator 只在以下场景使用预算预设：

1. `workerBackend=LANGGRAPH_BIZ`，BizWorker 自己组装 ChatModel prompt。
2. Codex / Claude Code 等 worker 被配置为代理第三方或非原生模型，且需要 Navigator 写入对应 worker env / hint。
3. 诊断、展示或 readiness 需要说明当前模型预算来源。

预设解析后的逻辑模型：

```json
{
  "presetKey": "generic.128k",
  "contextWindowTokens": 128000,
  "maxInputTokens": 100000,
  "maxOutputTokens": 8192,
  "defaultOutputTokens": 4096,
  "autoCompactInputTokenThreshold": 80000,
  "promptReserveOutputTokens": 8192,
  "promptReserveSystemTokens": 4096,
  "maxSingleToolResultTokens": 12000,
  "maxSingleToolResultChars": 48000,
  "projectHistoricalToolResults": true,
  "rawToolResultTailTurnCount": 6
}
```

字段含义：

| 字段 | 用途 |
| --- | --- |
| `presetKey` | 解析后的预算预设 key，用于诊断和日志 |
| `contextWindowTokens` | 模型总上下文窗口，作为所有预算的上限来源 |
| `maxInputTokens` | 单次请求最多允许提交给 LLM 的输入 token |
| `maxOutputTokens` | 单次请求允许的最大输出 token，映射 provider `max_tokens` / `max_completion_tokens` |
| `defaultOutputTokens` | 未显式指定时的默认输出预算 |
| `autoCompactInputTokenThreshold` | prompt 估算输入 token 超过该阈值时触发 lazy compaction |
| `promptReserveOutputTokens` | 为输出预留的输入窗口余量 |
| `promptReserveSystemTokens` | 为 system / account context / skill descriptions 预留的预算 |
| `maxSingleToolResultTokens` | 单个历史 tool result 进入 prompt projection 的 token 上限 |
| `maxSingleToolResultChars` | tokenizer 不可用或估算失败时的历史 tool result projection 字符兜底上限 |
| `projectHistoricalToolResults` | 是否对历史大 tool result 做 digest/refs/selected fields projection；默认 `true` |
| `rawToolResultTailTurnCount` | 最近多少个语义 turn 的 tool result 保持 raw，不做 digest/refs projection；默认 `6` |
| `tokenEstimator` | token 统计策略；当前 BizWorker 内置 `heuristic-v1`，后续可替换为模型精确 tokenizer |

实现上已先采用 `runtimeBudgetOverrideJson` 结构化 JSON 字段承载少量特殊覆盖。不要继续把 LangGraph Biz 的核心预算只塞进 `envVars`。

## CLI 参数

CLI 已补充 preset 参数，而不是优先暴露所有数字字段：

```powershell
.\tools\navigator-upstream\navi.ps1 upstream model create `
  --name "<displayName>" `
  --model-base-url "https://llm.example/v1" `
  --model-name "<modelName>" `
  --api-key-env NAVI_LLM_API_KEY `
  --runtime-budget-preset generic.128k
```

`model update` 支持同一参数。CLI 输出只显示非敏感模型授权信息，不打印 API key。

 如果上游确实需要覆盖少量数值，可以使用：

```powershell
--runtime-budget-override-json '{"maxOutputTokens":8192,"maxSingleToolResultChars":48000,"projectHistoricalToolResults":true,"rawToolResultTailTurnCount":6,"maxPromptMessages":512,"maxVisibleMessages":768}'
```

不建议在第一版 CLI 暴露一长串 token 数字参数。多数场景应通过 `modelName` 自动匹配或显式 preset key 完成。配套 skill 与文档必须继续禁止把这些值塞入 `clientContext` 或用户消息。

## 与消息裁剪 / 压缩设计的关系

模型预算 resolver 的输出是 `ContextRuntimeMemory.build_prompt_view()` 和 compaction policy 的输入之一。

2026-05-23 实现更新：BizWorker 已把 preset token 字段同步进 Root `ContextRuntimeMemory`：

- `maxInputTokens -> maxPromptTokens`
- `autoCompactInputTokenThreshold -> maxVisibleTokens`
- `maxSingleToolResultTokens -> maxToolResultTokens`

当前 token 统计使用 `heuristic-v1`，并与 chars / message count 共同参与 prompt hard cap、lazy compaction 和历史大 tool result projection。`llm-submissions.meta.runtimeBudget` 会保存解析后的 budget，供上游验收真实提交 body 时复盘。

裁剪顺序建议：

1. 先计算 system / account / business context 的基础预算。
2. 再给当前 user message 和必要附件 digest 留预算。
3. 对 Root-visible provider protocol 做 turn-aware + tool-protocol-aware tail selection。
4. 单工具结果先按 `projectHistoricalToolResults` 和 `maxSingleToolResult*` 判断是否投影成 digest / refs；最近 `rawToolResultTailTurnCount` 个语义 turn 保持 raw。
5. 如果 prompt 仍超过 `autoCompactInputTokenThreshold`，触发 lazy compaction。
6. compaction 后写回 `ContextRuntimeMemory`，下一轮以 summary + retained tail 为事实来源。

`rawToolResultTailTurnCount` 里的 turn 指用户语义 turn / 业务任务回合，不是一次任务内部的 LLM loop iteration。一个用户消息触发多次 assistant/tool 调用时，这些 protocol messages 仍归属于同一最近 turn。

详见 [workitems/OPT-runtime-prompt-window-turn-aware-pruning.md](./workitems/OPT-runtime-prompt-window-turn-aware-pruning.md)。

## 验收标准

1. 配套 CLI skill 明确：新会话不传 `contextId`，续聊复用返回的 `contextId`。
2. CLI 文档不再暗示 `clientContext` 可承载 LLM runtime prompt 或模型预算。
3. CLI 文档明确 Skill 工具化、Agent frame 化。
4. CLI 文档明确 `LlmModelConfig` 已支持预算 preset 字段，并继续禁止把预算写入 `clientContext`。
5. CLI 的 `--runtime-budget-preset` / `--runtime-budget-override-json` 能写入后端模型配置，并经 LangGraph relay 进入 BizWorker `llm_config`。
6. LangGraph Biz 可按 `modelName` 自动匹配预算预设；无法匹配时有明确 fallback。
7. CLI / SDK 文档明确 A2Agent model / workspace / worker binding 命令和方法，且说明默认字段会自动 materialize 到 binding 集合。
