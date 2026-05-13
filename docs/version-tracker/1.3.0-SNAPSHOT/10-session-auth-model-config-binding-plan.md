---
doc_type: requirement | implementation-plan | code-inventory
intended_for: execution-agent | reviewer | signoff-owner
purpose: 将旧的会话批量绑定 auth 能力调整为批量修改会话 authModelConfigId，并为后续运行时动态解析模型配置打基础
type: refactor
source_type: user-request
version: 1.3.0-SNAPSHOT
ticket: REFACTOR-010
priority: high
status: design-approved
owner: session-module | claude-worker-agent | navigator-frontend
---

# 会话 Auth 模型配置绑定重构方案

## 文档作用

- doc_type: requirement | implementation-plan | code-inventory
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 固定本轮会话 auth 绑定重构的目标、边界、模块职责、代码触点和验收标准

## 背景

当前会话 auth 绑定逻辑会把模型配置解析后的运行凭据快照写入 `SessionEntity`：

- `authBaseUrl`
- `authTokenCiphertext`
- `authModelConfigId`
- `authBoundAt`

继续会话时，`ClaudeTaskService.resolveAuth()` 优先读取会话级 token/baseUrl 快照。因此当平台模型配置的 Base URL 从 `http://A` 改为 `http://B` 后，已有会话仍可能继续使用旧快照 `http://A`。

新的设计方向是：会话只绑定模型配置引用，运行时永远基于会话绑定的模型配置读取最新凭据和 Base URL。

## 术语

- `authModelConfigId`：会话绑定的平台模型配置 ID，是本次设计的标准字段名。
- `modelConfigId`：任务创建/继续请求中传入的模型配置 ID，可用于新建会话初始绑定。
- `autoModelConfigId`：用户口头描述中的名称，本方案不新增该字段；落地时统一映射为现有 `authModelConfigId`。

## 目标

1. 旧的“批量绑定 auth”能力改为“批量修改会话 `authModelConfigId`”。
2. 批量操作不再写入会话级 `authTokenCiphertext` / `authBaseUrl`。
3. 会话配置 DTO 能返回当前绑定的 `authModelConfigId`，前端能展示和回填。
4. 为后续 `ClaudeTaskService.resolveAuth()` 改成动态读取模型配置提供兼容基础。

## 非目标

1. 本轮不要求立刻删除数据库字段 `authBaseUrl` / `authTokenCiphertext`。
2. 本轮不做数据库迁移清空历史 token/baseUrl。
3. 本轮不重构所有 Provider 的运行时 auth 解析，只先固定批量操作与会话配置契约。
4. 本轮不允许批量操作切换会话的 `providerType` 或 `agentId`。

## 规则

### 1. 批量操作口径

旧入口语义：

- 用户选择多个会话。
- 输入 API Key / Base URL / authMode 或选择平台模型配置。
- 后端把解析后的 token/baseUrl 写入每个会话。

新入口语义：

- 用户选择多个会话。
- 选择一个平台模型配置。
- 后端只把该配置 ID 写入每个会话的 `authModelConfigId`。
- 后续继续会话时由运行时根据 `authModelConfigId` 动态读取最新 API Key / Base URL。

### 2. 字段写入规则

批量修改 `authModelConfigId` 时：

- 必须写入 `SessionEntity.authModelConfigId`。
- 可以写入或刷新 `authBoundAt`，用于表示“该会话已经完成模型配置绑定”。
- 不得写入 `SessionEntity.authTokenCiphertext`。
- 不得写入 `SessionEntity.authBaseUrl`。
- 不得根据模型配置解密 API Key 后再保存到会话。

### 3. 兼容旧会话

历史会话可能存在旧快照字段：

- `authTokenCiphertext`
- `authBaseUrl`

本轮批量修改 `authModelConfigId` 后，执行 agent 应明确处理优先级：

1. 批量修改后的会话应以 `authModelConfigId` 为准。
2. 旧快照字段不应覆盖新的 `authModelConfigId`。
3. 若历史会话只有旧快照且没有 `authModelConfigId`，本轮不强制迁移；后续可通过批量修改配置补齐。

### 4. Provider 兼容校验

批量修改前必须校验：

- `modelConfigId` 存在。
- 当前用户或 Worker 有权使用该模型配置。
- 模型配置的 `workerBackend` 与会话已绑定的 `providerType` 兼容。

如果某个会话不兼容，应采用 fail-safe 策略：

- 不修改该会话。
- 统计失败数量或返回失败明细。
- 不应部分写入 token/baseUrl 作为兜底。

## 模块职责

### `session-module`

负责会话配置契约和批量更新能力：

1. 将 `SessionMetadataService.batchBindAuth(...)` 调整为批量更新模型配置引用。
2. 新增或重命名更准确的服务方法，例如 `batchUpdateAuthModelConfig(...)`。
3. `SessionConfigDTO` 增加 `authModelConfigId`。
4. `SessionConfigController` 提供明确的批量更新入口。
5. 保留旧接口兼容时，应标记为 deprecated，并内部转发到新语义；不再接收或写入 token/baseUrl。

### `addons/claude-worker-agent`

负责后续运行时动态解析的对齐准备：

1. `ClaudeTaskService.resolveAuth()` 后续应优先从 `SessionEntity.authModelConfigId` 动态读取模型配置。
2. `ConversationConfigService` 中从旧 `ConversationConfigEntity` 同步到 `SessionEntity` 的逻辑，不应继续把 token/baseUrl 当作长期真相源。
3. 本轮若不改运行时解析，必须在 progress 中标明仍存在旧快照优先级的后续任务。

### `packages/navigator-frontend`

负责 UI 和 API 调用语义调整：

1. 将“批量绑定 Auth”界面改成“批量修改模型配置”。
2. 移除该批量操作中的 API Key、Token、Base URL 输入。
3. 只保留模型配置选择、是否跳过已绑定会话、确认操作。
4. API 方法从 `batchBindConversationAuth` 语义调整为 `batchUpdateConversationModelConfig` 或等价命名。
5. 操作完成后刷新会话配置，展示当前绑定的模型配置。

## API 契约建议

推荐新增 endpoint：

```text
POST /api/v1/sessions/configs/batch-update-auth-model-config
```

请求：

```json
{
  "sessionIds": ["session-1", "session-2"],
  "authModelConfigId": "cfg-xxx",
  "skipExisting": true
}
```

响应：

```json
{
  "updated": 2,
  "total": 2,
  "skipped": 0,
  "failed": 0
}
```

兼容策略：

- 旧 `POST /api/v1/sessions/configs/batch-bind-auth` 可以暂时保留。
- 如果旧请求携带 `modelConfigId`，后端按新语义更新 `authModelConfigId`。
- 如果旧请求只携带 `authToken/baseUrl` 且没有 `modelConfigId`，应返回明确错误，提示改用平台模型配置。

## Code Inventory

| repo | path | role | expected change | notes |
|---|---|---|---|---|
| workspace | `docs/version-tracker/1.3.0-SNAPSHOT/10-session-auth-model-config-binding-plan.md` | 需求与执行方案 | create | 本文档 |
| workspace | `docs/version-tracker/1.3.0-SNAPSHOT/README.md` | 版本目录索引 | update | 增加本事项链接 |
| `navigator-common` | `src/main/java/com/foggy/navigator/common/entity/SessionEntity.java` | 会话实体 | read-only-analysis | 本轮不删字段，确认字段语义 |
| `session-module` | `src/main/java/com/foggy/navigator/session/controller/SessionConfigController.java` | 会话配置 API | update | 新增/调整批量更新模型配置接口 |
| `session-module` | `src/main/java/com/foggy/navigator/session/service/SessionMetadataService.java` | 会话配置服务 | update | 批量更新 `authModelConfigId`，不再写 token/baseUrl |
| `session-module` | `src/main/java/com/foggy/navigator/session/dto/SessionConfigDTO.java` | 会话配置 DTO | update | 返回 `authModelConfigId` |
| `session-module` | `src/test/java/com/foggy/navigator/session/service/SessionMetadataServiceTest.java` | 后端单测 | update | 覆盖新批量更新语义 |
| `addons/claude-worker-agent` | `src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java` | Claude 任务运行时 auth | read-only-analysis / follow-up update | 后续动态解析模型配置的核心点 |
| `addons/claude-worker-agent` | `src/main/java/com/foggy/navigator/claude/worker/service/ConversationConfigService.java` | 旧会话配置兼容层 | update | 避免旧同步逻辑重新写入 token/baseUrl 快照 |
| `packages/navigator-frontend` | `src/api/claudeWorker.ts` | 前端 API 封装 | update | 调整批量操作 API 方法和请求字段 |
| `packages/navigator-frontend` | `src/composables/useClaudeWorker.ts` | Worker 状态组合逻辑 | update | 从 batchBindAuth 调整为批量更新模型配置 |
| `packages/navigator-frontend` | `src/views/ClaudeWorkerView.vue` | 批量操作 UI | update | UI 改名并移除 token/baseUrl 输入 |
| `packages/navigator-frontend` | `src/views/__tests__/ClaudeWorkerView.integration.test.ts` | 前端集成测试 | update | 覆盖批量修改模型配置请求 |

## 分阶段计划

### Phase 1: 契约收口

- 明确标准字段名为 `authModelConfigId`。
- 新增 `SessionConfigDTO.authModelConfigId`。
- 新增批量更新模型配置请求结构。
- 旧 `batch-bind-auth` 接口进入兼容模式。

### Phase 2: 后端语义改造

- `SessionMetadataService` 新增批量更新方法。
- 方法只写 `authModelConfigId` 和必要的绑定状态字段。
- 增加 provider/backend 兼容校验。
- 单测覆盖：
  - 成功批量更新 `authModelConfigId`
  - 不写 `authTokenCiphertext`
  - 不写 `authBaseUrl`
  - `skipExisting=true` 时跳过已有绑定
  - backend 与 session provider 不兼容时不写入

### Phase 3: 前端入口改造

- 批量弹窗改名为“批量修改模型配置”。
- 删除 authMode/token/baseUrl 表单项。
- 请求改为只提交 `sessionIds`、`authModelConfigId`、`skipExisting`。
- 操作后刷新会话配置和历史列表展示。

### Phase 4: 运行时动态解析跟进

- 调整 `ClaudeTaskService.resolveAuth()`：
  - 先读取 `SessionEntity.authModelConfigId`
  - 动态调用 `LlmModelManager` 获取最新 API Key / Base URL
  - 不再优先解密会话级 token/baseUrl 快照
- 该阶段完成后，模型配置从 `http://A` 改为 `http://B`，老会话继续应立即使用 B。

## 验收标准

1. 前端批量操作已从“绑定 Auth”改为“修改模型配置”。
2. 批量操作请求不再包含 API Key / Token / Base URL。
3. 后端批量操作只修改 `SessionEntity.authModelConfigId`，不写入 `authTokenCiphertext` / `authBaseUrl`。
4. `SessionConfigDTO` 能返回会话当前绑定的 `authModelConfigId`。
5. 旧接口兼容行为清晰：携带 `modelConfigId` 可转新语义；只携带 token/baseUrl 应失败。
6. 单测覆盖批量更新语义和不写快照字段。
7. 前端测试覆盖批量修改模型配置的请求 payload。

## 风险与后续项

1. 若只完成批量操作改造而未完成 `ClaudeTaskService.resolveAuth()` 动态解析，老会话继续仍可能受旧快照优先级影响。
2. 历史会话中已经存在的 `authTokenCiphertext` / `authBaseUrl` 暂不清理，后续需要单独迁移或废弃策略。
3. 如果平台模型配置被删除、禁用或 workerBackend 被改动，老会话继续会失败；需要明确错误提示。
4. 未来可以考虑限制模型配置 `workerBackend` 不可变，避免破坏已绑定会话。

## 完成后回写要求

执行 agent 完成编码后必须回写本文件，至少补充：

- 实际修改文件清单
- 测试命令与结果
- 未完成项或后续迁移事项
- 是否已完成运行时动态解析 Phase 4
