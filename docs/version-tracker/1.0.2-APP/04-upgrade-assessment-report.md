# 04 Foggy Mobile 1.0.2-APP 升级评估报告

## Date

- 2026-04-05
- 2026-04-06（补充 H5 Playwright 验收结论）
- 2026-04-07（重启相关服务后 H5 复验）
- 2026-04-07（后端修复后全新会话 H5 复验）
- 2026-04-07（再次全新会话 H5 复测）

## Type

- Assessment Report
- Feasibility Review
- H5 Acceptance Update
- H5 Revalidation Update
- H5 Fresh Session Revalidation Update
- H5 Fresh Session Retry Update

## Executive Summary

本报告基于对 `docs/version-tracker/1.0.2-APP/` 三份规划文档与 `packages/foggy-mobile` 实际代码库的深度比对，并补充 2026-04-06 初验、2026-04-07 重启服务后的 H5 复验、2026-04-07 后端修复后基于全新会话的 H5 Playwright 复验，以及同日再次独立全新会话复测结果，给出可行性评估、完成度分析和验收结论。

**结论：计划可行，主体重构已基本完成，H5 新会话主链路在最新复测中已通过，但 APP 版本级验收暂不能直接签收。** H5 主链路已经可以跑通，且统一任务接口、会话列表、分页历史加载、resume 基础能力均已落地。2026-04-07 的再次独立全新会话复测确认：

1. 创建会话和会话续对时，`model` 选择已在全新会话中正确落库为 `sonnet`。
2. 续对后的 URL 漂移在多轮 2026-04-07 复验中均未再复现。
3. 全新会话重进详情页未再出现消息重复；`messages/latest` 结果与页面表现一致。

因此，当前状态更准确地应定义为：**“代码改造基本完成，H5 新会话核心链路已验通，但 APP 整体验收仍需补齐剩余场景和跨端证据后再签收。”**

---

## 1. 可行性评估

### 1.1 方案设计评估：✅ 合理可行

| 评估维度 | 评级 | 说明 |
|---------|------|------|
| 目标清晰度 | ⭐⭐⭐⭐⭐ | 六阶段递进，P0/P1/P2 优先级明确，每个阶段有明确验收标准 |
| 技术路径正确性 | ⭐⭐⭐⭐⭐ | 从 `/claude-tasks` 迁移到 `/api/v1/tasks`、从任务视图切到会话视图，与 PC 主链路语义一致 |
| 后端 API 就绪度 | ⭐⭐⭐⭐⭐ | 统一任务接口、会话配置接口、分页查询、SSE 均已就位 |
| 风险识别准确度 | ⭐⭐⭐⭐☆ | 四项风险均为真实风险，第 4 项（跨端渲染差异）仍需实际验证 |
| 范围控制 | ⭐⭐⭐⭐⭐ | 明确排除了 SSH 终端、File Browser、多 Pane 栅格等桌面端能力，移动端聚焦"必要闭环" |
| 复用策略 | ⭐⭐⭐⭐⭐ | 正确识别了 useUnifiedSse、MessageBubble 等可复用组件，避免推倒重写 |

### 1.2 潜在风险补充

| 风险 | 等级 | 建议 |
|------|------|------|
| 跨端渲染差异（App-Plus / 微信小程序） | 🟡 中 | 目前所有验证均在 H5，需要专门的多端回归周期 |
| 通用聊天链路 useSession.ts 仍用全量加载 | 🟢 低 | 非 Worker 主链路，可在后续迭代处理 |
| shared 类型来源无文档约束 | 🟡 中 | 长期来看 types.ts 仍可能与 navigator-frontend 再次漂移 |

---

## 2. 完成度分析

### 2.1 里程碑完成度总览

| 里程碑 | 总项 | 已完成 | 跳过/延期 | 未完成 | 完成率 |
|--------|------|--------|-----------|--------|--------|
| **A: 契约层升级** | 7 | 6 | 0 | 1 | 86% |
| **B: 会话列表主视图** | 7 | 7 | 0 | 0 | 100% |
| **C: 会话创建与模型选择** | 10 | 9 | 1 (用户决策) | 0 | 100% |
| **D: 会话详情与恢复** | 8 | 6 | 2 (P2 延期) | 0 | 100% |
| **E: 会话配置与历史** | 7 | 5 | 1 (用户决策) | 1 | 86% |
| **F: 后端废弃标记 + 清理** | 4 | 4 | 0 | 0 | 100% |
| **F-orig: 设置与配置** | 5 | 3 (含决策) | 0 | 2 | 60% |
| **G: 测试与验收** | 5 | 0 | 0 | 5 | 0% |
| **退出标准** | 6 | 6 | 0 | 0 | **100%** |

**总体完成率：约 85%**（按加权，测试尚未开始）

补充说明：2026-04-06 已执行 H5 初验，2026-04-07 完成三次 H5 复测（重启服务后复验、后端修复后全新会话复验、再次独立全新会话复测）；当前剩余问题主要是验收覆盖面和证据闭环，而不再是明确的 H5 主链路阻断缺陷。

### 2.2 代码实探验证

以下是通过代码库实际确认的关键迁移成果：

#### ✅ API 层完全迁移

| 文件 | 状态 | 说明 |
|------|------|------|
| `api/unifiedTask.ts` | ✅ 新增 | 完整封装 `/api/v1/tasks` 全部端点（create/resume/cancel/respond/reconnect/resync/rewind/delete/search/page） |
| `api/conversationConfig.ts` | ✅ 新增 | 封装 pin/title/archive/unarchive/hold/unhold/batch-configs |
| `api/claudeWorker.ts` | ✅ 清理 | 已删除所有任务相关函数，仅保留 Worker/Directory 管理 |
| `api/types.ts` | ✅ 对齐 | DispatchTask、ConversationGroup、ConversationConfig、WorkingDirectory、LlmModelConfig、SetupStatus 均已与后端对齐 |
| `api/platform.ts` | ✅ 扩展 | 支持 `listModelConfigs(workerId?)` |

#### ✅ 主视图从"任务列表"切换为"会话列表"

| 页面 | 状态 | 说明 |
|------|------|------|
| `pages/worker/tasks.vue` | ✅ 重构 | 已改为会话列表页，使用 `ConversationGroup` 模型，按 sessionId 聚合展示 |
| `pages/worker/task-detail.vue` | ✅ 升级 | 支持 resume/reconnect/resync，模型上下文展示，tail-based 分页历史加载 |

#### ✅ Composable 层完整实现

| Composable | 状态 | 说明 |
|------------|------|------|
| `useConversationGroup.ts` | ✅ 新增 | `buildConversationGroups()` 聚合 + 排序（pin 优先 → updatedAt） |
| `useTaskStream.ts` | ✅ 升级 | 使用 `getLatestMessages()` 分页加载，支持向上翻页 |
| `useSessionModelCache.ts` | ✅ 新增 | per-session 本地模型缓存，切换会话时恢复 |
| `useUnifiedSse.ts` | ✅ 保留 | 单连接模型，支持 session 级和全局通知路由 |
| `useInputMemory.ts` | ✅ 保留 | 草稿 + 发送历史持久化 |

#### ✅ 后端废弃标记

| 项目 | 状态 |
|------|------|
| `ClaudeTaskController` | ✅ 已标记 `@Deprecated(since = "1.0.2")` |
| 移动端代码中无 `/claude-tasks` 实际调用 | ✅ 已确认 |
| `pages.json` 标题已更新 | ✅ 已确认 |

### 2.3 H5 Playwright 验收结果（2026-04-06）

#### ✅ 已验证通过

| 验收项 | 结果 | 说明 |
|--------|------|------|
| 登录并切换服务器后重新鉴权 | ✅ 通过 | 切到 `local / http://localhost:8112` 后可正常登录 |
| Worker 列表与目录树加载 | ✅ 通过 | Worker 卡片、目录展开、目录进入均正常 |
| Worker 主页面展示“会话列表”而非“任务列表” | ✅ 通过 | 页面标题、列表语义、卡片内容均为 session 维度 |
| 会话列表主链路不依赖 `/api/v1/sessions` | ✅ 通过 | 实测请求为 `/api/v1/tasks/directory/{id}/page` + `/api/v1/sessions/configs` |
| 创建会话成功 | ✅ 通过 | `POST /api/v1/tasks` 成功，能进入 `task-detail` |
| `modelConfigId` 选择生效 | ✅ 通过 | 实测落库 `modelConfigId=test` |
| resume 主链路成功 | ✅ 通过 | `POST /api/v1/tasks/resume` 成功返回新 task |
| 长会话初次打开非全量加载 | ✅ 通过 | 历史消息走 `/sessions/{id}/messages/latest?limit=50&offset=0` |
| 旧兼容接口不是主链路必经路径 | ✅ 通过 | 抓包未见 `/api/v1/claude-tasks` |

#### ❌ 阻断问题

| # | 问题 | 实测现象 | 影响 |
|---|------|----------|------|
| 1 | `model` 选择未真正生效 | 创建页显式选择 `Sonnet 4`，落库 task `model` 实际为 `glm-4.7`；续对后新 task 又变成 `glm-5` | 回归用例“创建会话时选择 model 成功”当前应判失败 |
| 2 | 续对后 URL 未同步到新 taskId | `/tasks/resume` 成功返回新 task，但页面 URL 仍保留旧 `taskId` | 刷新页面、分享链接、重新进入详情页会落到旧任务 |
| 3 | 会话详情重进后消息重复 | 重进已完成会话时，同一条 assistant 消息被渲染两次 | 影响历史消息可信度和详情页体验 |

#### ⚠️ 未覆盖项

| 验收项 | 状态 | 说明 |
|--------|------|------|
| 权限审批 / 方案审批 / 问题回答 | 未覆盖 | 本次未构造审批型任务 |
| reconnect / resync | 未覆盖 | 本次未构造失败任务 |
| App-Plus 回归 | 未覆盖 | Playwright 仅覆盖 H5 |
| MP-Weixin 回归 | 未覆盖 | Playwright 仅覆盖 H5 |

#### 验收证据

- 抓包日志：`.playwright-cli/network-2026-04-05T12-34-08-673Z.log`
- 登录页快照：`app102mobile-login.yaml`
- 会话详情快照：`app102mobile-detail-finished.yaml`
- 续对后快照：`app102mobile-resume.yaml`

### 2.4 重启相关服务后的 H5 复验结果（2026-04-07）

#### 复验前置动作

- 重启 Claude Worker：`3031`
- 重启 Launcher 后端：`8112`
- 重启 foggy-mobile H5 Dev Server：`5175`

#### ✅ 本轮复验通过项

| 验收项 | 结果 | 说明 |
|--------|------|------|
| 登录并切换到 `local / http://localhost:8112` | ✅ 通过 | 重启后可重新鉴权 |
| 创建会话与进入详情页 | ✅ 通过 | 新任务 `20260407-cb1b` 创建成功 |
| resume 主链路与 URL 同步 | ✅ 通过 | 续对后页面地址为 `#/pages/worker/task-detail?taskId=20260407-d504&sessionId=f4d3c8cf-9edc-4430-96d2-629e4ddd486f`，未再出现旧 `taskId` 漂移 |

#### ❌ 本轮复验未通过项

| # | 问题 | 实测现象 | 影响 |
|---|------|----------|------|
| 1 | `model` 选择仍未真正生效 | 创建时显式选择 `Sonnet`，但任务 `20260407-cb1b` 落库仍为 `model=glm-4.7`；续对后的新任务 `20260407-d504` 也仍为 `glm-4.7` | “创建会话/续对时 model 生效”验收项仍失败 |
| 2 | 会话详情重进后消息仍重复 | 重新进入 `taskId=20260407-d504` 的详情页后，页面全文本中 `MODEL_RECHECK` 和 `RESUME_RECHECK` 都出现 3 次（各含 1 次 prompt + 2 次 assistant reply），表明每轮 assistant 回复仍重复一次 | 历史消息可信度和详情页体验仍不合格 |

#### 复验证据

- 登录页快照：`app102mobile-recheck-0407-login.yaml`
- 会话列表快照：`app102mobile-recheck-0407-tasks.yaml`
- 首轮详情快照：`app102mobile-recheck-0407-detail1.yaml`
- 续对完成快照：`app102mobile-recheck-0407-resume.yaml`
- 重进详情快照：`app102mobile-recheck-0407-reopen.yaml`
- 抓包日志：`.playwright-cli/network-2026-04-07T01-48-35-213Z.log`
- 抓包日志：`.playwright-cli/network-2026-04-07T01-49-14-879Z.log`

### 2.5 后端修复后的全新会话 H5 复验结果（2026-04-07）

#### 复验前置条件

- 后端 `8112` 端口健康检查通过
- 按修复说明，使用一个全新的业务会话进行验证，避免历史会话中的重复消息脏数据干扰判断

#### ✅ 本轮复验通过项

| 验收项 | 结果 | 说明 |
|--------|------|------|
| 使用全新会话创建并进入详情页 | ✅ 通过 | 新任务 `20260407-c0d3` 创建成功，页面进入 `sessionId=5c477c71-f7a1-45e3-8d98-db9fbf7ec9da` |
| resume 主链路与 URL 同步 | ✅ 通过 | 续对后页面地址为 `#/pages/worker/task-detail?taskId=20260407-64de&sessionId=5c477c71-f7a1-45e3-8d98-db9fbf7ec9da`，URL 与新任务一致 |
| 全新会话重进详情页无消息重复 | ✅ 通过 | 重载后页面中文本仅出现 1 次 `FRESH_A` 回复和 1 次 `FRESH_B` 回复；`/messages/latest` 返回 8 条消息，与 2 轮对话应有记录完全一致，无重复 assistant 消息 |

#### ❌ 本轮复验未通过项

| # | 问题 | 实测现象 | 影响 |
|---|------|----------|------|
| 1 | `model` 选择仍未真正生效 | 创建时显式选择 `Sonnet`，新任务 `20260407-c0d3` 落库仍为 `model=glm-4.7`；续对后的新任务 `20260407-64de` 又变为 `model=glm-5` | “创建会话/续对时 model 生效”验收项仍失败，当前仍不能签收 |

#### 复验证据

- 首轮模型选择快照：`app102mobile-fresh-0407-model-selected.yaml`
- 首轮详情快照：`app102mobile-fresh-0407-detail-a-complete.yaml`
- 续对完成快照：`app102mobile-fresh-0407-resume-b-complete.yaml`
- 重进详情快照：`app102mobile-fresh-0407-reopen.yaml`
- 抓包日志：`.playwright-cli/network-2026-04-07T04-13-04-376Z.log`
- 任务接口校验：`GET /api/v1/tasks/20260407-c0d3`、`GET /api/v1/tasks/20260407-64de`
- 消息接口校验：`GET /api/v1/sessions/5c477c71-f7a1-45e3-8d98-db9fbf7ec9da/messages/latest?limit=50&offset=0`

### 2.6 再次全新会话 H5 复测结果（2026-04-07）

#### 复测方式

- 使用新的浏览器会话重新登录 `local / http://localhost:8112`
- 在 `LocalDev / test1` 下再次创建一个全新的业务会话，避免沿用上一轮任何业务数据

#### ✅ 本轮复测通过项

| 验收项 | 结果 | 说明 |
|--------|------|------|
| 创建会话时 `model` 选择生效 | ✅ 通过 | 创建任务 `20260407-d2a4` 时显式选择 `Sonnet`，页面显示 `sonnet`，后端接口也返回 `model=sonnet` |
| 会话续对时 `model` 选择继续生效 | ✅ 通过 | 续对任务 `20260407-96c2` 仍返回 `model=sonnet`，未再被覆盖为 `glm-*` |
| resume URL 同步 | ✅ 通过 | 续对后地址为 `#/pages/worker/task-detail?taskId=20260407-96c2&sessionId=42e53753-f1b7-409a-840e-ab70e1e92c5c` |
| 重进详情页无消息重复 | ✅ 通过 | 重载后页面中 `FRESH_C`、`FRESH_D` 均只出现 1 次 assistant reply；`messages/latest` 共 8 条，与两轮对话应有记录完全一致 |

#### ⚠️ 本轮复测结论边界

- 本轮结论只覆盖“全新会话”的 H5 主链路
- 历史会话中的重复消息属于既有脏数据，若发布范围要求覆盖存量会话体验，仍需决定是否做数据清理或发布说明
- App-Plus、MP-Weixin、审批类场景、失败任务恢复类场景仍未在本轮复测中覆盖

#### 复测证据

- 首轮详情快照：`app102mobile-fresh3-0407-detail-c.yaml`
- 首轮重载快照：`app102mobile-fresh3-0407-detail-c-reload.yaml`
- 续对详情快照：`app102mobile-fresh3-0407-detail-d.yaml`
- 重进详情快照：`app102mobile-fresh3-0407-reopen.yaml`
- 任务接口校验：`GET /api/v1/tasks/20260407-d2a4`、`GET /api/v1/tasks/20260407-96c2`
- 消息接口校验：`GET /api/v1/sessions/42e53753-f1b7-409a-840e-ab70e1e92c5c/messages/latest?limit=50&offset=0`

---

## 3. 剩余工作清单

### 3.1 必须完成（影响交付质量）

| # | 事项 | 来源 | 优先级 | 工作量预估 |
|---|------|------|--------|-----------|
| 1 | 补最小 shared 类型来源说明（避免 types.ts 再次与 navigator-frontend 漂移） | Milestone A | P1 | 0.5d |
| 2 | API 层测试覆盖 unified task 主调用 | Milestone G | P0 | 1-2d |
| 3 | Composable 层测试（useTaskStream, useSession, useUnifiedSse） | Milestone G | P0 | 1-2d |
| 4 | 补齐 H5 Core Regression Cases 未覆盖项（审批、reconnect、resync、失败任务等） | Milestone G | P0 | 1d |
| 5 | App-Plus 回归测试 | Milestone G | P1 | 1d |
| 6 | MP-Weixin 回归测试 | Milestone G | P1 | 1d |
| 7 | 形成历史会话脏数据处理策略（清理 / 忽略 / 发布说明） | Milestone G | P1 | 0.5d |

### 3.2 可延期（P1 后续迭代）

| # | 事项 | 来源 | 说明 |
|---|------|------|------|
| 1 | Settings 页展示 `credentialConfigured` 和模型配置状态 | Milestone F-orig | 当前不影响主链路 |
| 2 | `useSession.ts` 历史加载切换到 `/messages/latest` | Milestone E | 通用聊天链路，非 Worker 主链路 |

### 3.3 已明确延期到 P2

| 事项 | 决策来源 |
|------|---------|
| Rewind UI 完整交互入口 | 用户决策 |
| `images` / `agentTeamsConfigId` 字段能力 | 用户决策 |
| 移动端附件上传 | 01 计划 |
| Agent Teams 选择 | 01 计划 |
| 会话级 `model` 后端持久化字段 | 01 计划 |
| 复杂会话搜索和批量操作 | 01 计划 |

### 3.4 已确认跳过（用户决策）

| 事项 | 决策 |
|------|------|
| 移动端编辑凭证（bindConversationAuth / updateConversationAuth） | 不开放 |
| 移动端 API 凭证查看/维护 | 不开放 |
| 移动端用户记忆查看/维护 | 不开放 |

---

## 4. 架构合理性验证

### 4.1 数据流验证

```
用户操作 → tasks.vue（会话列表）
         ↓
   listTasksByDirPagedUnified()  →  GET /api/v1/tasks/directory/{id}/page
         ↓
   buildConversationGroups()     →  按 sessionId 聚合为 ConversationGroup[]
         ↓
   listConversationConfigs()     →  GET /api/v1/sessions/configs (batch)
         ↓
   会话卡片渲染（title, interactionState, model, pin, 最近活跃时间）
```

```
创建会话 → createTaskUnified()  →  POST /api/v1/tasks
         ↓                          (workerId, prompt, directoryId, modelConfigId, model)
   后端返回 DispatchTask          →  含 sessionId
         ↓
   跳转 task-detail.vue          →  useTaskStream.connect(sessionId)
         ↓
   SSE 订阅 + 历史消息加载       →  getLatestMessages(sessionId, limit, offset)
```

**评估：数据流设计与 PC 端主链路语义一致；当前问题主要集中在实现细节和状态同步，而非架构路径本身。**

### 4.2 语义一致性验证

| 语义 | PC 端 | 移动端 | 一致性 |
|------|-------|--------|--------|
| 会话聚合 | ConversationGroup by sessionId | ✅ 相同 | ✅ |
| 任务创建 | createTaskUnified | ✅ 相同 | ✅ |
| modelConfigId 绑定 | 会话级后端绑定 | ✅ 相同 | ✅ |
| model 恢复 | 最新任务 + 前端缓存 | ⚠️ 设计对齐，但 H5 实测未稳定生效 | ⚠️ |
| 历史消息 | /messages/latest + 分页 | ✅ 相同 | ✅ |
| SSE | 统一连接 | ✅ 相同（useUnifiedSse） | ✅ |
| interactionState | 后端 + SSE 同步 | ✅ 相同 | ✅ |

---

## 5. 总结与建议

### 5.1 总体评价

1.0.2-APP 升级方案设计合理、分阶段清晰、风险可控。**更重要的是，核心重构工作（P0 全部 + P1 大部分）已经落地到代码中**，且 H5 主链路已经可以跑通。

但根据 2026-04-06 初验，以及 2026-04-07 三轮 H5 Playwright 复测，当前状态更准确地应定义为“**核心主链路已通过，但版本级 APP 验收尚未收口**”。原因不是主架构方向有误，也不是当前还存在明确的 H5 主链路阻断，而是正式签收所需的剩余场景和跨端证据还不完整。

退出标准重评估如下：

- [x] 移动端主任务链路基于 `/api/v1/tasks`
- [x] 移动端 Worker 主展示链路基于"会话列表"而不是"任务列表"
- [x] 移动端类型定义与当前后端返回结构基本对齐
- [x] 创建会话和会话续对都支持 `modelConfigId + model`（最新全新会话复测中，`model=sonnet` 已正确落库）
- [x] 长历史加载、任务恢复、交互状态具备最小闭环（主链路可跑通，`resume URL` 已通过；全新会话详情页重进不再重复消息）
- [x] `packages/foggy-mobile` 可以继续在当前架构上迭代

### 5.2 下一步建议

| 优先级 | 行动 | 预计工作量 |
|--------|------|-----------|
| **P0** | 重新执行 H5 端核心回归测试，补齐审批、reconnect、resync、失败任务等未覆盖场景 | 1d |
| **P0** | 补充 API 层 + composable 层单元测试 | 2-3d |
| **P1** | App-Plus 和 MP-Weixin 多端回归 | 2d |
| **P1** | 明确历史会话脏数据处理策略，并决定是否需要清理或发布说明 | 0.5d |
| **P1** | 补充 shared 类型来源说明文档 | 0.5d |
| **P1** | Settings 页平台状态增强 | 1d |
| **P2** | useSession.ts 通用聊天链路迁移到分页加载 | 0.5d |

**总剩余工作量预估：约 5-6 个工作日**，其中 P0 重点已从“修阻断 bug”切换为“补齐验收覆盖面和正式签收证据”。 
