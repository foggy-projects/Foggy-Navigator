# 11 Session Scroll To Bottom — 开发报告

## 基本信息

| 项目 | 内容 |
|------|------|
| 需求编号 | 11 |
| 需求标题 | 会话消息区"回到底部"悬浮按钮 |
| 版本 | `1.0.1-SNAPSHOT` |
| 完成阶段 | Phase 1（最小可用版） |
| 完成日期 | 2026-04-05 |
| 变更文件数 | 1 |

## 需求回顾

在 Worker 会话页中，用户向上滚动查看历史消息后，缺少显式的"回到底部"入口。用户需要手动拖动滚动条才能回到最新消息并恢复自动跟随，体验成本较高。

## 实现方案

### 修改文件

| 文件 | 变更说明 |
|------|----------|
| `packages/foggy-chat/src/components/MessageList.vue` | 新增悬浮按钮 DOM、控制逻辑、样式 |

### 实现细节

#### 1. 展示条件

```typescript
const showJumpToBottom = computed(() => {
  return userScrolledUp.value && props.messages.length > 0
})
```

- 复用已有 `userScrolledUp` 状态（距底部 > 80px 时为 `true`）
- 消息列表为空时不显示

#### 2. 容器宽度感知（窄 Pane 自适应）

```typescript
const containerRef = ref<HTMLElement>()
const iconOnly = ref(false)

// ResizeObserver 监测容器宽度
// 宽度 < 300px 时切换为仅图标模式
```

- 桌面正常宽度：圆角胶囊按钮，显示 `↓ 回到底部`
- 窄 Pane（< 300px）：圆形图标按钮，通过 `title` 属性提供 tooltip

#### 3. 点击行为

```typescript
function forceScrollToBottom() {
  userScrolledUp.value = false
  nextTick(() => {
    scrollerRef.value?.scrollToBottom()
  })
}
```

- 先清除 `userScrolledUp` 状态
- 再触发 `scrollToBottom()`
- 由于 `userScrolledUp` 已被重置，后续流式输出会继续自动跟随

#### 4. 按钮样式

- 绝对定位于 `.message-list-container` 右下角（`right: 16px; bottom: 12px`）
- 白底 + 浅灰边框 + 阴影，hover 时变为蓝色主题
- Vue `<Transition>` 动画：淡入 + 上移效果，250ms

#### 5. 定位上下文

`.message-list-container` 增加 `position: relative`，使按钮相对于消息区容器定位，不受外部布局影响。

## 架构决策

| 决策 | 选择 | 原因 |
|------|------|------|
| 实现位置 | `MessageList.vue` 共享组件内闭环 | 避免 ChatView、ClaudeWorkerView 等多处重复实现 |
| 窄宽度检测 | ResizeObserver | 比 CSS media query 更精确，响应容器宽度而非视口宽度 |
| 按钮图标 | 内联 SVG | 无需引入额外图标库依赖 |
| 动画方案 | Vue `<Transition>` | 原生支持，无额外依赖 |

## 验证结果

### 构建验证

| 包 | 结果 |
|----|------|
| `foggy-chat-core` | ✅ 构建成功（68ms） |
| `foggy-chat` | ✅ 构建成功（709ms，含 vue-tsc 类型检查） |
| `navigator-frontend` | ✅ 构建成功（37.49s） |

### 待人工验收场景

- [ ] 用户上滑后按钮出现
- [ ] 点击后恢复到底部
- [ ] 流式输出期间点击后继续自动跟随
- [ ] 多 Pane 窄宽度布局下按钮退化为图标模式
- [ ] 长日志工具调用会话中使用
- [ ] 连续多轮问答会话中使用
- [ ] 按钮不遮挡消息内容主阅读区
- [ ] 按钮不与输入区发送按钮冲突

## Phase 2 增强方向（未实现）

根据需求文档规划，后续可增强：

1. **新消息计数**：`pendingNewMessageCount` 状态，在按钮上显示未读消息数或小红点
2. **距底距离**：`distanceFromBottom` 状态，支持更精细的展示策略
3. **文案强化**：按钮文案从"回到底部"变为"有 N 条新消息"

## 变更影响范围

- **直接影响**：所有使用 `MessageList` 组件的页面自动获得此能力
  - `ChatPanel.vue` → `ChatView`（通用聊天页）
  - `TaskPane.vue` → `ClaudeWorkerView`（Worker 会话页）
- **无破坏性变更**：按钮是纯新增 UI，不影响现有滚动逻辑和消息渲染
