# 04 Foggy Mobile 1.0.2-APP 升级评估报告

## Date

- 2026-04-05

## Type

- Assessment Report
- Feasibility Review

## Executive Summary

本报告基于对 `docs/version-tracker/1.0.2-APP/` 三份规划文档与 `packages/foggy-mobile` 实际代码库的深度比对，给出可行性评估和完成度分析。

**结论：计划可行，且主体重构已基本完成。** P0 主链路和大部分 P1 能力已落地，剩余工作集中在测试验收、少量文档补充和 P1 设置页增强。

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

---

## 3. 剩余工作清单

### 3.1 必须完成（影响交付质量）

| # | 事项 | 来源 | 优先级 | 工作量预估 |
|---|------|------|--------|-----------|
| 1 | 补最小 shared 类型来源说明（避免 types.ts 再次与 navigator-frontend 漂移） | Milestone A | P1 | 0.5d |
| 2 | API 层测试覆盖 unified task 主调用 | Milestone G | P0 | 1-2d |
| 3 | Composable 层测试（useTaskStream, useSession, useUnifiedSse） | Milestone G | P0 | 1-2d |
| 4 | H5 回归测试 | Milestone G | P0 | 1d |
| 5 | App-Plus 回归测试 | Milestone G | P1 | 1d |
| 6 | MP-Weixin 回归测试 | Milestone G | P1 | 1d |
| 7 | Core Regression Cases 全部执行（共 14 项） | Milestone G | P0 | 含在回归中 |

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

**评估：数据流与 PC 端主链路语义一致，无设计缺陷。**

### 4.2 语义一致性验证

| 语义 | PC 端 | 移动端 | 一致性 |
|------|-------|--------|--------|
| 会话聚合 | ConversationGroup by sessionId | ✅ 相同 | ✅ |
| 任务创建 | createTaskUnified | ✅ 相同 | ✅ |
| modelConfigId 绑定 | 会话级后端绑定 | ✅ 相同 | ✅ |
| model 恢复 | 最新任务 + 前端缓存 | ✅ 相同（useSessionModelCache） | ✅ |
| 历史消息 | /messages/latest + 分页 | ✅ 相同 | ✅ |
| SSE | 统一连接 | ✅ 相同（useUnifiedSse） | ✅ |
| interactionState | 后端 + SSE 同步 | ✅ 相同 | ✅ |

---

## 5. 总结与建议

### 5.1 总体评价

1.0.2-APP 升级方案设计合理、分阶段清晰、风险可控。**更重要的是，核心重构工作（P0 全部 + P1 大部分）已经落地到代码中**，代码质量与计划文档高度一致。

六大退出标准全部满足：

- [x] 移动端主任务链路基于 `/api/v1/tasks`
- [x] 移动端 Worker 主展示链路基于"会话列表"而不是"任务列表"
- [x] 移动端类型定义与当前后端返回结构基本对齐
- [x] 创建会话和会话续对都支持 `modelConfigId + model`
- [x] 长历史加载、任务恢复、交互状态具备最小闭环
- [x] `packages/foggy-mobile` 可以继续在当前架构上迭代

### 5.2 下一步建议

| 优先级 | 行动 | 预计工作量 |
|--------|------|-----------|
| **P0** | 执行 H5 端核心回归测试（14 项 regression cases） | 1d |
| **P0** | 补充 API 层 + composable 层单元测试 | 2-3d |
| **P1** | App-Plus 和 MP-Weixin 多端回归 | 2d |
| **P1** | 补充 shared 类型来源说明文档 | 0.5d |
| **P1** | Settings 页平台状态增强 | 1d |
| **P2** | useSession.ts 通用聊天链路迁移到分页加载 | 0.5d |

**总剩余工作量预估：约 5-7 个工作日**，主要集中在测试验收阶段。
