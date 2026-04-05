# 01 Foggy Mobile 升级方案

## Date

- 2026-04-05

## Type

- Requirement
- Investigation
- Solution Plan

## Background

当前 `packages/foggy-mobile` 不是单纯“少几个页面”的问题，而是它仍然基于上一阶段的移动端契约在运行：

- Worker 主入口仍偏向“任务列表”视角，没有和 PC 一样按会话聚合展示
- `pages/chat/index.vue` 虽然是会话列表，但它走的是通用 `/api/v1/sessions`，并不能承接 Worker 会话
- Worker 任务链路仍主要调用 `/api/v1/claude-tasks`
- 数据类型仍停留在旧版 `ClaudeTask` / `WorkingDirectory` / `Message` 结构
- 会话配置、交互状态、重连/重同步/回退、平台 API 凭证等能力已经在 Web 和后端主链路中成型，但移动端没有完成接入

仓库中的事实已经很明确：

- `packages/foggy-mobile/src/api/claudeWorker.ts` 仍直接调用 `/claude-tasks`
- `session-module/src/main/java/com/foggy/navigator/session/controller/SessionController.java` 的 `/api/v1/sessions` 列表明确过滤了 `claude-worker`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/ClaudeTaskController.java` 已明确标注旧接口只保留给兼容客户端，推荐迁移到 `/api/v1/tasks`
- `session-module/src/main/java/com/foggy/navigator/session/controller/TaskController.java` 已提供统一任务入口
- `session-module/src/main/java/com/foggy/navigator/session/controller/SessionConfigController.java` 已承接会话配置
- `metadata-config-module/src/main/java/com/foggy/navigator/metadata/query/config/controller/PlatformConfigController.java` 已提供模型、记忆、API 凭证等配置入口

因此，`1.0.2-APP` 不建议做“点修补丁”，而应按移动端主链路完成一次有边界的结构升级。

## Goal

本次升级的目标不是把 Web 端全部照搬到移动端，而是让移动端重新具备可持续迭代的主能力：

1. Worker 主入口切换成和 PC 一致的会话列表视图，而不是任务列表视图
2. 新建会话与会话内续对都允许显式选择 `modelConfigId` 和 `model`
3. 任务创建、恢复、取消、回复权限等能力切到统一任务接口
4. 移动端类型系统与后端当前返回结构重新对齐
5. 会话/任务详情能消费当前 SSE 与交互状态模型
6. 平台模型配置、目录默认配置、必要的 API 凭证能力能在移动端闭环
7. 长会话、失败恢复、续对等核心体验不再依赖旧兼容接口

## Confirmed Findings

### 1. 当前 APP 的“会话列表”并不能承接 Worker 会话

当前移动端确实已经有：

- `packages/foggy-mobile/src/pages/chat/index.vue`
- `packages/foggy-mobile/src/stores/session.ts`
- `packages/foggy-mobile/src/api/session.ts`

但这条链路有两个硬限制：

- 它依赖 `/api/v1/sessions`
- 后端当前在 `SessionController` 中明确过滤 `claude-worker`

也就是说，如果想把 APP 改成和 PC 一样的 Worker 会话列表，不能直接在现有 chat session list 上补功能，而要切换数据来源。

### 2. 任务 API 仍停留在兼容层

当前移动端以下能力仍走旧接口：

- `createTask`
- `resumeTask`
- `getTask`
- `listTasksByDirectoryPaged`
- `abortTask`
- `deleteTask`
- `respondToPermission`

对应文件：

- `packages/foggy-mobile/src/api/claudeWorker.ts`

而当前主链路已经迁移到：

- `POST /api/v1/tasks`
- `POST /api/v1/tasks/resume`
- `POST /api/v1/tasks/{taskId}/cancel`
- `POST /api/v1/tasks/{taskId}/respond`
- `POST /api/v1/tasks/{taskId}/reconnect`
- `POST /api/v1/tasks/{taskId}/resync`
- `POST /api/v1/tasks/{taskId}/rewind`

这意味着当前移动端虽然“还能用”，但本质上还挂在兼容分支上，无法稳定承接统一任务路由、Codex/未来 Agent 扩展以及新的任务恢复能力。

### 3. 类型定义已经明显漂移

`packages/foggy-mobile/src/api/types.ts` 与 `packages/navigator-frontend/src/types/index.ts` 存在明显差距：

- `ClaudeTask` 缺少 `workerTaskId`、`codexThreadId`、`providerType`、`modelConfigId`、`checkpoints`、`fileCheckpointingEnabled`、`agentTeamsConfigId`、`directoryName`
- `WorkingDirectory` 缺少 `agentId`、`agentName`、`defaultAuthMode`、`defaultAuthConfigured`、`defaultBaseUrl`、`maskedDefaultAuthToken`、`defaultModelConfigId`
- `Message` 仍只支持 `USER | ASSISTANT`，缺少 `TOOL | SYSTEM` 与 `metadata`
- `LlmModelConfig` 缺少 `category`、`scope`、`allowedWorkerIds`、`availableModels`、`workerBackend`、`sortOrder`
- `SetupStatus` 缺少 `credentialConfigured`

这会导致两个问题：

- 新字段即使后端返回，移动端也没有显式消费能力
- 后续移动端页面升级时，开发会继续围绕旧模型打补丁

### 4. 任务详情能力只覆盖了旧 Claude 流程

当前 `packages/foggy-mobile/src/pages/worker/task-detail.vue` 只支持：

- 中止任务
- 续对
- 权限/提问/方案审批响应

但 Web 端当前主链路已经包含：

- `reconnect`
- `resync`
- `rewind`
- 基于统一任务模型的 Claude/Codex 恢复
- 任务与交互状态联动

同时 `packages/foggy-mobile/src/composables/useTaskStream.ts` 只在本地处理：

- `TEXT_COMPLETE`
- `ERROR`
- `CONFIRMATION_REQUEST`

仍缺少对以下升级点的承接：

- `codexThreadId`
- reconnect/resync 后的 UI 收敛
- checkpoint / rewind 数据
- 更完整的任务状态恢复

### 5. 会话状态模型没有跟上当前主链路

移动端虽然已经接入统一 SSE：

- `packages/foggy-mobile/src/composables/useUnifiedSse.ts`

但实际页面层仍缺少会话配置能力：

- 没有 `ConversationConfig`
- 没有 pin/title/auth/tags 的移动端接入
- 没有 `interactionState` 的本地状态维护
- 没有归档 / 搁置 / 待回复会话的真实会话级收敛

现在 `packages/foggy-mobile/src/pages/worker/tasks.vue` 里的 `AWAITING_REPLY / PROCESSING / ON_HOLD / ARCHIVED` 过滤更像“借用了服务端筛选参数”，但移动端本地没有完整会话配置与交互状态模型来支撑这些能力。

### 6. 历史消息加载仍是全量加载

移动端会话与任务详情仍调用：

- `GET /api/v1/sessions/{id}/messages`

对应文件：

- `packages/foggy-mobile/src/composables/useSession.ts`
- `packages/foggy-mobile/src/composables/useTaskStream.ts`

而后端已经提供：

- `GET /api/v1/sessions/{id}/messages/latest`

Web 端也已经有对应分页类型：

- `packages/navigator-frontend/src/api/session.ts`

这意味着移动端在长会话和长任务上仍然会一次性拉全量历史，后续很容易再次碰到性能和渲染问题。

### 7. 创建会话与会话级模型选择语义尚未建立

当前移动端在 Worker 链路里还缺少一套清晰的“会话创建与模型配置”方案：

- 通用 `createSession` 只有 `title + agentId + parentSessionId`
- Worker 主链路的真实会话创建其实发生在首条任务创建后
- 当前后端里 `modelConfigId` 已进入统一任务创建/恢复链路
- 当前 `SessionConfigDTO` 只持有 auth 绑定信息，并没有单独持有 `model`

这意味着移动端如果想支持“创建会话时选择 `configModelId/model`、进入会话后还能切换”，就必须采用和 PC 类似的语义：

- `modelConfigId` 作为会话运行上下文的一部分，在 create/resume 和 bind-auth 链路里生效
- `model` 作为当前会话的运行时模型偏好，由最新任务和前端 session cache 共同恢复

### 8. 平台配置面板能力落后于当前后端

当前移动端 `packages/foggy-mobile/src/api/platform.ts` 只接了：

- setup-status
- llm model configs
- agent model overrides

但当前后端已经具备：

- Git provider
- User memories
- API credentials
- 更完整的 setup-status

而移动端 `packages/foggy-mobile/src/pages/settings/index.vue` 目前只做了：

- 账号信息
- 服务器切换
- 版本与升级
- 退出登录

如果 APP 仍要承接真实任务创建入口，那么至少要考虑：

- 如何在移动端看到当前模型/凭证是否已准备好
- 如何处理目录默认 Auth / 默认模型配置
- 是否需要提供最小化 API 凭证管理入口

## What Is Already Reusable

移动端并不是完全不可用，以下基础可直接复用，升级时不建议推翻重写：

- `useUnifiedSse.ts` 已切到单连接模型
- `useInputMemory.ts` 已完成草稿和输入历史持久化
- `MessageBubble.vue`、`PlanReviewCard.vue`、`UserQuestionCard.vue`、`PermissionRequestCard.vue` 已具备交互式消息卡片基础
- Worker 列表页已经适配 PROJECT / child / worktree 分层目录结构

结论是：本次升级应优先做“契约与页面逻辑升级”，而不是推倒 UI 重新做。

## Upgrade Principles

### 1. 先统一契约，再改主视图

如果继续沿用旧 `types.ts` 和旧 `/claude-tasks` 包装层，后续每个页面都会继续重复兼容逻辑，维护成本只会继续上升。

### 2. 会话列表要复用 PC 语义，不要直接复用旧 `/sessions`

移动端想做 Worker 会话列表，数据源应该复用统一任务 session 分页能力，而不是继续建立在过滤过 `claude-worker` 的通用 session list 上。

### 3. 保持移动端简化，不追求桌面端全量复制

桌面端以下能力不应作为本轮移动端必做项：

- 多 Pane 任务栅格
- SSH 终端
- Code Server
- File Browser
- 复杂的拖拽式附件流

移动端应承接“必要闭环”，而不是“全量同构”。

### 4. 优先打通会话主链路，而不是补边角功能

本次优先级应是：

1. 统一任务接口
2. Worker 会话列表替换任务列表
3. `modelConfigId + model` 选择链路
4. 类型同步
5. 会话状态模型与长历史加载

而不是先做 UI 美化或零散功能扩展。

## Recommended Upgrade Phases

## Phase 1: 契约层与会话数据源收口

目标：让移动端停止直接依赖旧兼容接口、旧类型和错误的 Worker 会话数据来源。

### 建议动作

- 新增移动端 `api/unifiedTask.ts`，与 Web 端统一任务封装保持同一语义
- 重写 `packages/foggy-mobile/src/api/claudeWorker.ts` 中的任务相关方法，使其内部改走 `/api/v1/tasks`
- 为移动端定义 `ConversationGroup` 视图模型，按 `sessionId` 聚合统一任务分页结果
- 停止把 Worker 会话建立在 `/api/v1/sessions` 列表之上
- 对齐 `packages/foggy-mobile/src/api/types.ts` 与 `packages/navigator-frontend/src/types/index.ts` 的共享字段
- 扩展 `packages/foggy-mobile/src/api/platform.ts`，至少补齐：
  - `credentialConfigured`
  - `listModelConfigs(workerId?)`
  - API credentials / memories 的最小访问接口

### 本阶段产出

- 移动端新的统一任务 API 封装
- 移动端新的会话视图数据模型
- 更新后的 `types.ts`
- 不再以 `/claude-tasks` 作为新逻辑默认入口

### 验收标准

- 移动端代码搜索中，不再把 `/claude-tasks` 作为 create/resume/cancel/respond/delete/get/list 的主实现
- Worker 会话列表主数据源不再是 `/api/v1/sessions`
- 任务与目录类型能完整接住后端当前字段

## Phase 2: Worker 会话列表主视图升级

目标：让移动端 Worker 主入口和 PC 一样，按会话而不是按任务展示。

### 建议动作

- 升级 `pages/worker/tasks.vue`
  - 改造成会话列表页，而不是任务列表页
  - 改走统一任务 session 分页接口
  - 展示 `interactionState`、pin、最新模型、最近活动时间
  - 支持目录维度或 Worker 维度的会话列表
- 新增移动端会话分组/排序策略，与 PC 端 `ConversationGroup` 语义保持一致
- 支持会话级筛选：
  - `PROCESSING`
  - `AWAITING_REPLY`
  - `ON_HOLD`
  - `ARCHIVED`
- 评估是否在移动端保留旧 task row 作为次级调试视图，而不是主入口

### 本阶段产出

- 新版 Worker 会话列表页
- 会话聚合视图模型
- 与 PC 语义一致的会话排序与状态展示

### 验收标准

- APP Worker 主页面不再以 `taskId` 作为首层展示单元
- Claude/Codex 会话都能按 session 维度查看
- 会话列表能展示当前会话的状态和模型上下文

## Phase 3: 会话创建、续对与模型上下文升级

目标：让移动端在创建会话和进入会话后，都能管理 `modelConfigId` 与 `model`。

### 建议动作

- 新建会话不再先调用通用 `createSession`
- 新增“新会话创建面板”，至少包含：
  - `directoryId`
  - `prompt`
  - `modelConfigId`
  - `model`
  - `permissionMode`
- 提交首轮消息时直接走 `createTaskUnified`，由后端返回 `sessionId`
- 会话详情页顶部或输入栏附近增加当前会话运行上下文展示：
  - `modelConfigId`
  - `model`
  - `permissionMode`
- 切换会话时，根据最近任务恢复 `modelConfigId` 和 `model`
- 用户在当前会话中切换 `modelConfigId` 时，接入 `bindConversationAuth` / `updateConversationAuth`
- 用户在当前会话中切换 `model` 时，使用移动端 per-session cache 持久化前端选择
- 增加移动端 `ConversationConfig` 类型和 API 封装

### 本阶段产出

- 会话创建与会话级模型选择最小闭环
- 当前会话模型上下文展示与恢复机制

### 验收标准

- 新建会话前可以显式选择 `modelConfigId` 和 `model`
- 进入已有会话后，可以看到并调整当前会话的模型上下文
- 切换会话后，模型选择能跟随会话恢复

## Phase 4: 会话配置、详情与恢复能力升级

目标：让移动端会话能力与当前 Session 配置模型和统一任务恢复模型对齐。

### 建议动作

- 接入：
  - `listConversationConfigs`
  - `updateConversationPin`
  - `updateConversationTitle`
  - `bindConversationAuth`
  - `updateConversationAuth`
  - `archiveConversation`
  - `unarchiveConversation`
  - `holdConversation`
  - `unholdConversation`
- 升级 `pages/worker/task-detail.vue`
  - 从“任务详情”视角切到“会话详情”视角
  - 支持 `resume`
  - 支持 `reconnect`
  - 支持 `resync`
  - 评估 `rewind` 入口
- 升级 `useTaskStream.ts`
  - 同步 `codexThreadId`
  - 同步更完整的任务完成/失败/重连状态
  - 为 resync 导入消息留出状态收敛点
- 在统一 SSE 的 `task_update` / `assistant_notification` 消费中补充 `interactionState` 同步
- 将 `chat/detail.vue` 与 `useSession.ts` 的历史消息加载切换到 `/messages/latest`
- 为长会话增加“向上加载更早消息”机制

### 本阶段产出

- 会话配置与交互状态最小闭环
- 新版会话详情页
- 长历史消息分页加载

### 验收标准

- 移动端能正确显示待回复/处理中/已搁置/已归档语义
- 失败任务可在移动端发起 reconnect 或 resync
- 长会话首次进入不再拉全量历史

## Phase 5: 平台配置与设置页补齐

目标：让移动端具备最小可运维能力，避免任务入口和配置入口割裂。

### 建议动作

- 扩展设置页，至少展示：
  - `setup-status`
  - `credentialConfigured`
  - 当前平台模型配置情况
- 评估加入移动端最小化能力：
  - 查看 API credentials
  - 查看/维护 user memories
- Worker 会话创建页使用 `listModelConfigs(workerId)`，而不是简单拉全量模型

### 本阶段产出

- 新版 settings 能承接当前后端平台配置状态
- 会话创建页模型选择更贴近实际可用范围

### 验收标准

- 用户能在移动端判断当前是否具备任务运行所需配置
- 不兼容 worker 的模型配置不会继续被默认展示给用户

## Phase 6: 测试与发版收口

目标：保证升级不是“代码看起来对了”，而是真能运行。

### 建议动作

- 增加移动端 API 层和 composable 层测试
- 补最小回归清单：
  - 登录
  - 服务器切换
  - Worker 列表加载
  - 会话列表加载
  - 创建会话并选择 `modelConfigId + model`
  - 续对
  - 权限审批
  - 长历史消息加载
  - reconnect / resync
  - 模型配置切换
- 至少验证三端：
  - H5
  - App-Plus
  - MP-Weixin

### 验收标准

- 主链路回归清单可执行
- 不再依赖“旧接口还没删所以能跑”的状态

## Recommended Scope Split

建议将本次升级拆成两个交付层：

### P0 必做

- 统一任务接口迁移
- Worker 会话列表替换任务列表
- `modelConfigId + model` 选择链路
- 类型同步
- `/messages/latest` 分页加载

### P1 应做

- 会话配置与交互状态接入
- 会话详情页 reconnect / resync / rewind 入口
- 平台设置页最小化补齐
- worker 兼容模型筛选

### P2 可延期

- 移动端附件上传
- Agent Teams 选择
- Rewind 完整交互
- 更复杂的会话搜索和批量操作
- 会话级 `model` 后端持久化字段

## Risks

### 1. 继续保留旧接口包装会掩盖问题

如果只在页面层打补丁，不处理 API 包装层，后续迁移成本会继续滚大。

### 2. 把 Worker 会话继续建立在 `/api/v1/sessions` 上会走错方向

因为该接口当前过滤了 `claude-worker`，继续围绕它扩展会导致移动端结构从一开始就偏离 PC 主链路。

### 3. `modelConfigId` 和 `model` 不是同一层语义

`modelConfigId` 当前具备后端会话 auth 绑定语义，而 `model` 主要是任务级运行选择。实现时如果混成一个字段，后续恢复逻辑会很乱。

### 4. 长消息和跨端渲染差异会在 App-Plus / 微信端暴露

尤其是：

- 富文本渲染
- 大消息列表
- SSE 重连
- 附件能力

这些不能只在 H5 验证。

## Decision Snapshot

结论如下：

- 当前 `packages/foggy-mobile` 的主要问题已经不只是“契约层过旧”，还包括主视图仍停留在任务视角
- APP 若要和 PC 对齐，Worker 主入口必须切到会话列表，而不是继续维护 task list
- 不建议继续围绕 `/api/v1/claude-tasks` 或 `/api/v1/sessions` 的旧 Worker 语义做增量维护
- 建议在 `1.0.2-APP` 版本按分阶段方式推进，先打通 P0 主链路，再补 P1 能力
