# 03 Foggy Mobile 会话驱动视图与模型选择设计

## Date

- 2026-04-05

## Type

- Requirement
- Interaction Design
- Technical Decision

## Requirement Snapshot

新增要求已经明确：

1. APP 端主视图不要再按任务列表展示，而是和 PC 一样按会话列表展示
2. 创建会话或进入会话后，允许用户选择 `modelConfigId` 和 `model`

其中用户口头上说的 `configModelId`，结合当前仓库实现，实际对应字段是 `modelConfigId`。

## Why The Current APP Structure Is Not Enough

当前移动端虽然已经有 `pages/chat/index.vue`，但它并不能直接承担 Worker 会话主视图：

- 它调用的是 `/api/v1/sessions`
- `session-module/src/main/java/com/foggy/navigator/session/controller/SessionController.java` 在 `listSessions` 中明确过滤掉了 `claude-worker`
- 当前 `createSession` 也只支持 `title + agentId + parentSessionId`，并不支持 `modelConfigId` 或 `model`

这意味着：

- 现在的聊天会话列表拿不到 Worker 会话
- 现在的“新建会话”接口也不是 PC 端 Worker 会话的真实创建入口

而 PC 端 Worker 主链路的真实语义是：

- 用统一任务接口创建第一条任务
- 后端在任务创建时产出 `sessionId`
- 历史面板按 `sessionId` 聚合任务，最终展示为“会话列表”

所以 APP 如果要对齐 PC，不应继续以旧的 `/sessions` 聊天列表为基础扩展，而应转成“任务驱动创建、会话驱动展示”。

## Current PC Semantics

从 `packages/navigator-frontend/src/views/ClaudeWorkerView.vue` 和 `packages/navigator-frontend/src/composables/useClaudeWorker.ts` 可以确认：

- PC 历史面板不是直接显示 task row，而是按 `sessionId` 聚合成 `ConversationGroup`
- 当前会话切换时，会根据最新任务恢复 `modelConfigId` 和 `model`
- 会话配置由 `SessionConfigController` 管理 pin/title/auth/archive/hold
- `modelConfigId` 已经进入统一任务创建/恢复链路

因此移动端应复用 PC 的业务语义，而不是复用旧移动端的页面结构。

## Decision

`packages/foggy-mobile` 中与 Worker 相关的主入口，应从“任务列表页”切换成“会话列表页”。

推荐做法：

- 将 `pages/worker/tasks.vue` 重定义为会话列表页，而不是任务列表页
- 会话列表数据源改为统一任务分页接口提供的 session 维度结果
- 任务详情页改造成会话详情页，底部发送栏负责在当前会话上续对
- “新建会话”不再先调用 `/sessions`，而是弹出创建面板，提交首条任务后由后端返回 `sessionId`

## Data Source Decision

### 1. Worker 会话列表不要再使用 `/api/v1/sessions`

原因：

- 该接口当前明确过滤 `claude-worker`
- 它也不返回 PC 端 Worker 会话所需的任务态信息

### 2. 会话列表优先使用统一任务 session 分页能力

推荐优先基于：

- `GET /api/v1/tasks/page`
- `GET /api/v1/tasks/directory/{directoryId}/page`

理由：

- 当前统一任务分页已经是按 session 聚合返回
- 返回结果天然带有最新任务的运行信息
- 更接近 PC 当前历史面板的真实来源

如果后续需要进一步降低前端聚合复杂度，再考虑新增移动端专用 session-list DTO，但不是本轮前置条件。

## APP Information Architecture

建议结构如下：

### 1. 会话列表页

显示内容建议包括：

- 会话标题，优先 `customTitle`，否则回退首轮 prompt
- 最新一轮摘要
- `interactionState`
- 最近活动时间
- 当前 `model`
- 当前 `modelConfigId` 对应的平台配置名
- pin 状态

列表维度建议保持与 PC 语义一致：

- 当前目录会话
- 当前 Worker 会话
- 需要关注的会话，如 `PROCESSING`、`AWAITING_REPLY`

### 2. 会话详情页

主区域显示消息流，底部输入栏负责继续当前会话。

会话详情顶部或输入栏附近应暴露最小上下文配置：

- 当前 `modelConfigId`
- 当前 `model`
- 当前权限模式
- 当前目录/Worker

### 3. 新建会话入口

点击“新建会话”后，不应立即创建空会话，而是先弹出创建表单，至少包含：

- `directoryId`
- 首条 `prompt`
- `modelConfigId`
- `model`
- `permissionMode`

提交后调用统一任务创建接口，后端返回带 `sessionId` 的任务，再进入会话详情。

## Model Selection Semantics

这里要区分两个不同层次：

### 1. `modelConfigId`

这是平台配置层选择，决定：

- 使用哪套 API 凭证
- 由哪个 provider 执行
- 是否命中目录默认配置或平台 override

在当前后端里，`modelConfigId` 已经进入：

- `createTaskUnified`
- `resumeTaskUnified`
- `batchBindAuth`

因此移动端应在以下两个时机支持它：

1. 新建会话时显式选择
2. 会话详情中切换“当前会话默认 API 配置”

会话级持久化建议：

- 若用户在会话内显式切换 `modelConfigId`，优先走 `bindConversationAuth` 或 `updateConversationAuth`
- 同时后续续对请求继续显式带上 `modelConfigId`

### 2. `model`

`model` 是更细粒度的运行时模型选择，例如 Claude/Codex 的具体模型名。

当前仓库事实是：

- `model` 会出现在任务创建/恢复表单
- 当前 `SessionConfigDTO` 并没有单独的 `model` 持久化字段
- PC 端是通过“当前会话最新任务 + 前端缓存”恢复模型选择

因此移动端建议采用同样策略：

1. 会话切换时，优先从最新任务恢复 `model`
2. 用户在当前会话手动切换 `model` 时，写入移动端 per-session 本地缓存
3. 后续续对时通过 `resumeTaskUnified` 显式传回 `model`

结论是：

- `modelConfigId` 具备会话级后端绑定语义
- `model` 当前更适合作为“会话上下文中的前端持久选择”

如果未来要求 `model` 也成为真正的后端会话配置字段，需要单独扩展 `SessionConfigDTO` 与对应 controller/service。

## Recommended Upgrade Steps

### Step 1. 改掉会话列表数据来源

- 不再把 Worker 会话建立在 `sessionStore.listSessions()` 上
- 新增移动端 `ConversationGroup` 视图模型
- 以统一任务分页结果按 `sessionId` 聚合，生成移动端会话列表

### Step 2. 将 `worker/tasks.vue` 改造成会话列表页

- 原先 task row 改为 conversation row
- 支持 `PROCESSING / AWAITING_REPLY / ON_HOLD / ARCHIVED`
- 支持 pin、标题、最后活跃时间、模型信息展示

### Step 3. 新建会话改成“带配置的首轮创建”

- 创建前先弹出表单
- 用户选择 `modelConfigId` 和 `model`
- 首条提交走 `createTaskUnified`
- 根据返回的 `sessionId` 跳转详情页

### Step 4. 会话详情页支持会话级模型上下文

- 顶部显示当前会话的 `modelConfigId` 和 `model`
- 切换会话时根据最近任务恢复选择
- 用户手动改配置后，后续续对默认沿用该选择

### Step 5. 会话配置面板补齐

- pin/unpin
- 改标题
- bind/update auth
- archive/unarchive
- hold/unhold

## Execution Notes

实现时建议新增以下移动端本地状态：

- `conversationConfigs: Map<sessionId, ConversationConfig>`
- `sessionModelCache: Map<sessionId, string>`
- `selectedModelConfigIdBySession: Map<sessionId, string>`

其中：

- `ConversationConfig` 来源于后端会话配置接口
- `sessionModelCache` 用于恢复 `model`
- `selectedModelConfigIdBySession` 用于前端快速恢复 UI，但真实提交仍以最新后端配置和当前表单值为准

## Acceptance Criteria

- APP Worker 主页面按会话列表展示，不再按任务列表展示
- Worker 会话不再依赖 `/api/v1/sessions` 列表接口
- 新建会话前可选择 `modelConfigId` 和 `model`
- 进入已有会话后，顶部或输入栏能看到并修改当前 `modelConfigId` 和 `model`
- 会话切换后可恢复当前会话的模型上下文
- 后续续对请求会显式携带用户当前选择的 `modelConfigId` 和 `model`
