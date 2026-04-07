# 11 Session Scroll To Bottom Plan

## Date

- 2026-04-05

## Version

- `1.0.1-SNAPSHOT`

## Type

- Requirement
- UX Enhancement
- Solution Plan

## Priority

- P1

## Status

- Phase 1 Implemented → Code Verified (2026-04-07)
- 待人工体验验收

## Background

在 Worker 会话页中，长回复、连续工具调用、日志块和多轮追问会迅速拉长消息区。用户一旦手动向上滚动查看历史内容，当前视图就会脱离底部。

现状下，消息列表内部已经有“用户上滑后停止自动跟随”的机制，但没有显式的“回到底部”入口。结果是：

- 新消息持续到达时，用户很容易不知道最新输出已经滚到哪里
- 想继续跟随最新回复时，只能手动拖动滚动条到底部
- 在多工具调用、长日志输出场景下，恢复到底部的成本明显偏高

## Problem Statement

当前体验存在两个断点：

1. 用户主动离开底部后，系统不会再自动追踪最新消息
2. 但界面又没有提供一个足够近、足够明显、且只在需要时出现的“回到底部”控件

这会导致会话在“阅读历史”和“回到当前”之间缺少顺滑切换。

## Target Outcome

为会话消息区增加一个低干扰、高可达性的“回到底部”能力，使用户在查看历史后，可以一键回到最新消息，并重新进入自动跟随状态。

预期效果：

- 不打断正常阅读
- 不污染顶部工具栏
- 不与输入区主操作冲突
- 对长流式输出和多轮工具调用都有效

## Placement Decision

推荐位置：

- 放在消息滚动区域右下角
- 贴近输入框上沿，悬浮于消息层之上
- 与消息区右边缘保持 `16px` 左右安全间距

推荐原因：

- 这是“局部滚动动作”，应该跟随消息容器，而不是放到页面级工具栏
- 用户滚动后视线仍停留在消息区右下方，这里命中路径最短
- 不会和顶部的“同步 Git / 编辑 / 模式切换”等全局操作混淆
- 在单列会话和多 Pane 会话下都成立

不推荐的位置：

- 顶部工具栏：语义过重，且离当前阅读区域太远
- 输入框内部：会和“发送”主动作抢位置
- 右侧历史会话栏：这是当前会话内部动作，不应挂在跨会话导航区域

## Interaction Design

### 1. 展示时机

满足以下条件时显示按钮：

- 用户已离开底部一段距离
- 建议阈值：距离底部大于 `80px` 到 `120px`

满足以下条件时隐藏按钮：

- 用户回到底部
- 或距离底部小于阈值
- 或消息区为空

### 2. 控件形态

桌面优先建议：

- 默认使用圆角悬浮胶囊按钮
- 文案：`回到底部`
- 搭配向下箭头图标

窄 Pane 或空间不足时：

- 自动退化为仅图标圆形按钮
- 通过 `tooltip` 显示 `回到底部`

### 3. 点击行为

点击后执行：

1. 平滑滚动到最新消息
2. 清空“离底部”状态
3. 恢复自动跟随新消息

如当前有流式输出，点击后应继续跟随后续增量内容，而不是只滚一次。

### 4. 新消息提示

当用户停留在历史位置且底部继续出现新消息时，建议同一按钮承载轻量提醒：

- 初版至少显示高亮态或小红点
- 增强版可显示 `有新消息` 或未读条数

这样可以把“发现新消息”和“回到底部”合并为同一入口，减少额外 UI。

## Technical Design

从当前代码看，最佳落点是共享聊天组件，而不是只在某个业务页面单独补丁。

涉及模块：

- `packages/foggy-chat/src/components/MessageList.vue`
- `packages/foggy-chat/src/components/ChatPanel.vue`
- `packages/navigator-frontend/src/components/worker/TaskPane.vue`

当前已有基础：

- `MessageList.vue` 已存在 `scrollToBottom()`
- 已存在 `userScrolledUp` 状态
- 已在消息追加和流式更新时做底部跟随判断

因此本次实现建议直接在 `MessageList.vue` 内闭环，不把滚动控制继续上抛到业务层。

### Recommended Implementation

#### Phase 1: 最小可用版

在 `MessageList.vue` 中新增：

- `showJumpToBottom` 计算状态
- 悬浮按钮 DOM
- `forceScrollToBottom()` 方法

按钮点击时：

- 直接调用 `scrollerRef.value.scrollToBottom()`
- 将 `userScrolledUp.value = false`

样式上：

- 让 `.message-list-container` 设为定位上下文
- 按钮绝对定位在右下角
- 不遮挡“加载更早消息”区域

#### Phase 2: 增强版

补充两个状态：

- `distanceFromBottom`
- `pendingNewMessageCount`

行为增强：

- 用户离底后若有新消息到达，累加未读计数
- 点击“回到底部”后清零
- 用户手动滚回底部也清零

### Why Shared Component First

如果把按钮只做在 `ClaudeWorkerView` 或 `TaskPane`：

- 会把通用滚动交互耦合到业务页面
- 未来 `ChatView` 等其他会话场景还要重复实现
- 与现有 `MessageList` 内部滚动状态分裂

因此更合理的边界是：

- 滚动检测、显示条件、滚动执行：放在 `MessageList`
- 业务页面只决定是否启用，必要时通过 prop 控制

## Ownership Split

- `foggy-chat`
  - 提供通用消息区“回到底部”能力
  - 维护展示逻辑、滚动状态、按钮样式

- `navigator-frontend`
  - 在 Worker 会话场景验证布局和交互
  - 如有必要，为特殊 Pane 布局补充样式适配

## Acceptance Criteria

1. 用户在会话中向上滚动离开底部后，界面会出现“回到底部”入口。
2. 按钮位置稳定，不遮挡消息内容主阅读区，也不与输入区发送按钮冲突。
3. 点击按钮后，消息区会回到最新消息，并恢复自动跟随流式输出。
4. 当用户已经位于底部时，按钮不显示。
5. 在窄 Pane 场景下，按钮不会导致布局抖动或遮挡主操作。

## Constraints And Non-Goals

本项当前不包含：

- 顶部工具栏新增同名入口
- 键盘快捷键设计
- 未读消息计数的后端持久化
- 跨页面统一的“滚动位置记忆”

说明：

- 首版目标是补齐会话内即时可达的滚动恢复能力，不扩展成复杂的消息导航系统。

## Open Decisions

当前建议已收敛为：

- 位置选“消息区右下角悬浮”
- 实现边界选“`MessageList` 共享组件内闭环”
- 首版以“按钮 + 恢复自动跟随”为主
- “新消息计数/文案强化”可作为第二阶段增强

## Tracking

### Development Progress

- Phase 1 已完成（2026-04-05）
- 在 `foggy-chat` MessageList.vue 中实现共享"回到底部"悬浮按钮
- 前端构建验证通过（foggy-chat-core、foggy-chat、navigator-frontend 均成功）

**代码验证（2026-04-07）：**

- [x] `showJumpToBottom` computed 存在且逻辑正确
- [x] `forceScrollToBottom()` 方法存在，先清 `userScrolledUp` 再调 `scrollToBottom()`
- [x] 悬浮按钮 DOM 包含 `<Transition>` 动画，`v-if="showJumpToBottom"`
- [x] `ResizeObserver` 检测容器宽度 < 300px 切换 `iconOnly` 模式
- [x] `.message-list-container` 已设 `position: relative` 作为定位上下文

### Testing Progress

- 当前状态：代码审查通过，待人工体验验收
- 自动化测试：N/A（纯前端 UX 增强，无后端逻辑变更）
- 人工验收项：
  - [ ] 用户上滑后按钮出现
  - [ ] 点击后恢复到底部
  - [ ] 流式输出期间再次跟随
  - [ ] 多 Pane 窄宽度布局显示正常（< 300px 退化为图标按钮）

### Experience Progress

- 当前状态：待人工体验验证
- 重点场景：
  - [ ] 长日志工具调用会话
  - [ ] 连续多轮问答会话
  - [ ] 右侧历史栏展开时的主区布局
  - [ ] 单 Pane 与多 Pane 模式
