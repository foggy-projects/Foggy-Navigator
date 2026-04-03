# 06 Session Long History Render Optimization

## Date

- 2026-04-03

## Type

- Requirement
- Optimization
- Deferred

## Background

当前 Worker 会话面板在长会话、长 Markdown 回复、包含大量代码块的场景下，会出现明显卡顿。

现状上，前端已经做了部分保护：

- 打开已同步会话时，默认只加载最近一页历史消息
- 重同步会话时，也只回填最近一页消息
- 支持继续向前加载历史和一次性加载更多历史

但渲染层仍存在明显热点：

- 消息列表仍按当前已加载消息做全量 DOM 渲染
- 分组结果、回退 turn 映射等派生数据会随消息变化整体重算
- 每条消息都执行 Markdown 解析和代码高亮
- streaming 阶段最后一条 assistant 消息持续变化，容易触发频繁重排和重绘

## Goal

以最小改造方式降低会话面板在“长消息 + 代码块 + streaming 回复”场景下的卡顿，优先改善以下体验：

- 打开历史较长的会话时，消息区首次展示更平滑
- 滚动消息区时不卡顿
- assistant streaming 回复过程中不明显掉帧
- 不改变现有后端协议、历史消息接口和会话数据结构

## Current Implementation Sync

当前代码已经有“历史分页加载”保护，但还没有“渲染层性能保护”。

### 已存在能力

1. `useTaskPane.ts` 已将 `PAGE_SIZE` 固定为 `800`
2. 初次进入会话会调用 `sessionApi.getLatestMessages(sessionId, PAGE_SIZE, 0)`
3. 已支持 `loadMoreHistory()` 和 `loadAllHistory(limit?)`
4. `TaskPane.vue` / `ChatPanel.vue` / `MessageList.vue` 已把 `loadMore` / `loadAll` 接口串起来
5. `SessionController` / `JpaSessionManager` 已提供 latest messages 分页读取接口

### 当前性能瓶颈

1. `packages/foggy-chat/src/components/MessageList.vue` 仍然全量渲染当前已加载的所有 `groupedItems`
2. 当前没有虚拟列表能力，仓库中也未见 `DynamicScroller` 等动态虚拟列表接入
3. `packages/foggy-chat/src/components/MessageBubble.vue` 使用 `markdown-it` + `highlight.js`
4. 未显式缓存 `message.id + content` 级别的 Markdown 渲染结果
5. 代码块未指定语言时会走 `hljs.highlightAuto(...)`，对 streaming 和长消息都偏重

因此这份文档当前已能和实现同步，适合作为前端优化需求交付技术。

## Related Code Checklist

### History Loading

- `packages/navigator-frontend/src/composables/useTaskPane.ts`
- `packages/navigator-frontend/src/api/session.ts`
- `packages/navigator-frontend/src/components/worker/TaskPane.vue`
- `session-module/src/main/java/com/foggy/navigator/session/controller/SessionController.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/JpaSessionManager.java`

### Chat Rendering

- `packages/foggy-chat/src/components/ChatPanel.vue`
- `packages/foggy-chat/src/components/MessageList.vue`
- `packages/foggy-chat/src/components/MessageBubble.vue`
- `packages/foggy-chat/src/components/ToolCallGroup.vue`
- `packages/foggy-chat/src/components/PlanReviewCard.vue`

## Minimal Proposal

本项按“最小改造”落地，范围限定在前端渲染层，建议拆为三步。

### 1. MessageList 改为虚拟列表

- 将消息区从“当前已加载消息全量 DOM 渲染”改为“仅渲染可视区附近消息”
- 虚拟化对象应为 `groupedItems`，而不是原始消息数组
- 需要兼容动态高度消息项，因为 Markdown、代码块、工具卡片高度不固定
- 推荐优先采用成熟的 Vue 动态虚拟列表方案，例如 `vue-virtual-scroller` 的 `DynamicScroller`
- 需保留：
  - 滚动到底部自动跟随
  - 用户手动上滑后停止强制跟随
  - 顶部触发 `loadMore`
  - prepend 历史后尽量保持阅读位置稳定

### 2. MessageBubble 增加 Markdown 渲染缓存

- 对历史消息增加 `message.id + content` 维度的渲染缓存
- 已完成内容的消息，避免在父组件更新、滚动、streaming 过程中重复执行 `md.render()`
- 缓存范围至少覆盖：
  - 清洗后的 Markdown 文本
  - 渲染后的 HTML

### 3. Streaming 阶段降级高亮策略

- `TEXT_CHUNK` 阶段仅做轻量渲染，避免对未完成内容反复执行高成本代码高亮
- `TEXT_COMPLETE` 到达后，再对最终内容执行完整 Markdown + highlight
- 若实现复杂度较低，可先采用：
  - streaming 阶段不做 `highlightAuto(...)`
  - 完成态再生成最终 HTML

## Suggested Analysis Checklist

以下清单只是建议技术优先从这些点开始分析，不作为强制拆解要求：

1. 先量化当前渲染热点，确认主要成本在 DOM 数量、Markdown 渲染还是代码高亮
2. 评估虚拟列表对 `loadMore`、prepend、自动滚动、工具卡片高度变化的影响
3. 评估 Markdown 缓存落点：
   - 组件内缓存
   - 独立 render cache
   - store 级缓存
4. 明确 streaming 降级策略是否只影响 `TEXT_CHUNK`
5. 评估 `PlanReviewCard` 等其他 Markdown 组件是否也要同步受益

## Acceptance Criteria

- 默认最近一页历史消息打开时，界面响应明显优于当前实现
- 长 Markdown 和代码块场景下，滚动卡顿明显下降
- assistant streaming 回复过程中，不再频繁触发整段历史消息重复 Markdown 解析
- `loadMore` / `loadAll` / 自动滚动 / 回退 / 权限卡片 / 工具卡片能力保持可用
- prepend 更早历史后，阅读位置保持基本稳定
- 不引入明显的消息跳动、错位或空白闪烁

## Test Scope

### Frontend Unit / Component

- `MessageList` 的虚拟列表滚动与 prepend 锚点测试
- `MessageBubble` 的 Markdown 缓存命中测试
- streaming 内容更新时仅最后一条消息重渲染的测试

### Integration / Regression

- 打开长历史会话时只加载最新分页
- `loadMore` / `loadAll` 后消息顺序正确
- 工具调用分组、权限卡片、错误卡片、回退能力不受影响

### Frontend / Playwright Validation

建议至少覆盖以下场景：

1. 打开一个包含大量历史消息和代码块的会话
2. 观察首屏渲染和滚动是否平滑
3. 触发 `loadMore`
4. 触发 `loadAll`
5. 启动一个 streaming 回复任务，观察回复过程中页面是否明显掉帧
6. 验证代码块复制、图片预览、工具卡片展开等交互仍正常

## Risks

- 虚拟列表与动态高度内容结合后，滚动锚点处理会更复杂
- 顶部 prepend 历史与虚拟列表并存时，需要额外验证滚动位置恢复
- 某些卡片类消息高度变化较大，可能需要二次测量或刷新虚拟项尺寸
- 代码块复制、图片预览、工具卡片展开等交互，需要验证在虚拟化场景下仍然正常

## Delivery Assessment

本项已经可以直接交付技术作为前端优化需求文档。

## Status

本项作为 `1.0.0-SNAPSHOT` 版本优化需求记录，可直接交付技术进入设计与性能验证。
