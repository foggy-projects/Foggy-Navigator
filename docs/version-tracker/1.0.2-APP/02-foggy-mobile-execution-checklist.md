# 02 Foggy Mobile 执行清单

## Date

- 2026-04-05

## Type

- Execution Checklist

## Milestone A: 契约层升级

- [x] 新增 `packages/foggy-mobile/src/api/unifiedTask.ts`
- [x] 将 `packages/foggy-mobile/src/api/claudeWorker.ts` 的任务相关调用切到 `/api/v1/tasks`
- [x] 为移动端定义 `ConversationGroup` 视图模型，按 `sessionId` 聚合会话
- [x] Worker 会话主列表停止依赖 `/api/v1/sessions`
- [x] 对齐 `packages/foggy-mobile/src/api/types.ts` 的以下类型：
  - `Message`
  - `ClaudeTask` (→ DispatchTask + ClaudeTask 别名)
  - `WorkingDirectory`
  - `LlmModelConfig`
  - `SetupStatus`
  - `ConversationConfig`
- [x] 扩展 `packages/foggy-mobile/src/api/platform.ts`，支持 `listModelConfigs(workerId?)`
- [ ] 补最小 shared 类型来源说明，避免后续继续和 `packages/navigator-frontend/src/types/index.ts` 漂移

## Milestone B: 会话列表主视图替换

- [x] 将 `packages/foggy-mobile/src/pages/worker/tasks.vue` 重构为会话列表页
- [x] 会话列表主行数据改为 `sessionId` 维度，而不是 `taskId`
- [x] 展示会话级 `interactionState`
- [x] 展示当前 `modelConfigId` 与 `model`
- [x] 展示最近活跃时间、首轮 prompt、最后一轮摘要
- [x] 支持 pin / 自定义标题 / 状态筛选
- [x] 统一 Worker 维度与目录维度会话加载行为

## Milestone C: 会话创建与模型选择

- [x] 新建会话入口改成"创建面板 + 首轮任务创建"
- [x] 创建时允许选择 `modelConfigId`
- [x] 创建时允许选择 `model`
- [x] 创建时允许选择 `permissionMode`
- [x] 创建后根据返回的 `sessionId` 进入详情页
- [x] 会话详情页显示当前 `modelConfigId`
- [x] 会话详情页显示当前 `model`
- [x] 会话切换时按最新任务恢复 `modelConfigId + model`
- [ ] 会话内修改 `modelConfigId` 时接入 `bindConversationAuth` / `updateConversationAuth`（用户决策：移动端不编辑凭证，跳过）
- [x] 会话内修改 `model` 时建立 per-session 本地缓存

## Milestone D: 会话详情、续对与恢复

- [x] 升级 `packages/foggy-mobile/src/pages/worker/task-detail.vue`
- [x] 升级 `packages/foggy-mobile/src/composables/useTaskStream.ts`
- [x] 统一 create/resume/cancel/respond/delete/get/list 行为语义
- [x] 支持当前会话的 resume
- [x] 支持 task detail 的 reconnect
- [x] 支持 task detail 的 resync
- [ ] 评估并预留 rewind UI 入口（P2 延期）
- [ ] 评估并预留 `images` / `agentTeamsConfigId` 字段能力（P2 延期）

## Milestone E: 会话配置与历史消息

- [x] 新增移动端 `ConversationConfig` 类型
- [x] 封装会话配置 API：
  - `listConversationConfigs`
  - `updateConversationPin`
  - `updateConversationTitle`
  - ~~`bindConversationAuth`~~（用户决策：移动端不编辑凭证）
  - ~~`updateConversationAuth`~~（用户决策：移动端不编辑凭证）
  - `archiveConversation`
  - `unarchiveConversation`
  - `holdConversation`
  - `unholdConversation`
- [x] 在统一 SSE 通知消费中同步 `interactionState`
- [ ] 将 `packages/foggy-mobile/src/composables/useSession.ts` 历史加载切到 `/messages/latest`（通用聊天链路，非 Worker 主链路，后续迭代）
- [x] 将 `packages/foggy-mobile/src/composables/useTaskStream.ts` 历史加载切到 `/messages/latest`
- [x] 为聊天页和任务页增加向上分页加载更早消息的能力

## Milestone F: 后端废弃标记 + 移动端清理

- [x] `ClaudeTaskController` 类级别标记 `@Deprecated`
- [x] `claudeWorker.ts` 删除所有任务相关函数，仅保留 Worker/Directory 函数
- [x] 移动端代码中不再有 `/claude-tasks` 实际调用
- [x] `pages.json` 页面标题从"任务列表/任务详情"改为"会话列表/会话详情"

## Milestone F-orig: 设置与配置闭环

- [ ] 扩展 `packages/foggy-mobile/src/pages/settings/index.vue` 的平台状态展示（P1 后续迭代）
- [ ] 展示 `credentialConfigured`
- [ ] 展示当前可用模型配置状态
- [x] 决策是否在移动端开放 API 凭证查看/维护 → **不开放**
- [x] 决策是否在移动端开放用户记忆查看/维护 → **不开放**
- [x] 会话创建页按 `workerId` 过滤模型配置（`listModelConfigs(workerId?)` 已支持）

## Milestone G: 测试与验收

- [ ] API 层测试覆盖 unified task 主调用
- [ ] composable 层测试覆盖：
  - `useTaskStream`
  - `useSession`
  - `useUnifiedSse`
- [ ] H5 回归
- [ ] App-Plus 回归
- [ ] MP-Weixin 回归

## Core Regression Cases

- [ ] 登录并切换服务器后仍能正常重新鉴权
- [ ] Worker 列表与目录树正常加载
- [ ] Worker 主页面按会话列表展示而不是任务列表
- [ ] Worker 会话不依赖 `/api/v1/sessions` 仍可正常展示
- [ ] 创建会话时选择 `modelConfigId` 成功
- [ ] 创建会话时选择 `model` 成功
- [ ] 切换会话后恢复当前会话的 `modelConfigId + model`
- [ ] 创建 Claude 任务成功
- [ ] 恢复任务成功
- [ ] 权限审批、方案审批、问题回答成功
- [ ] 长会话首次打开不再全量加载全部消息
- [ ] 失败任务可 reconnect 或 resync
- [ ] 模型配置选择与目录默认配置不冲突
- [ ] 旧兼容接口不是主链路必经路径

## Exit Criteria

- [x] 移动端主任务链路基于 `/api/v1/tasks`
- [x] 移动端 Worker 主展示链路基于"会话列表"而不是"任务列表"
- [x] 移动端类型定义与当前后端返回结构基本对齐
- [x] 创建会话和会话续对都支持 `modelConfigId + model`
- [x] 长历史加载、任务恢复、交互状态具备最小闭环
- [x] `packages/foggy-mobile` 可以继续在当前架构上迭代，而不是继续依赖兼容分支
