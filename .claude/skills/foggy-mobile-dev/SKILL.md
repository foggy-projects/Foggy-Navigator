---
name: foggy-mobile-dev
description: Foggy Navigator 移动端（uni-app）开发指导。当用户需要迭代 foggy-mobile 模块、添加移动端页面/组件、修改 SSE 通信、调整 API 层、或排查 uni-app 构建问题时使用。触发词：/mobile, /foggy-mobile, 提及"移动端"、"uni-app"、"小程序"、"H5"。
---

# Foggy Mobile 开发指导

Foggy Navigator 移动端，基于 uni-app + Vue 3 + TypeScript + Pinia + Wot Design Uni。

## 架构概览

### 三层包依赖

```
@foggy/chat-core   → 纯 TS 类型 + chatState（无 UI 依赖）
  ├── @foggy/chat   → Web 端组件库（Element Plus）
  │   └── navigator-frontend
  └── @foggy/mobile → 移动端（uni-app + Wot Design Uni）
```

**核心原则**：共享类型和状态逻辑在 `chat-core`，UI 各端独立实现。

### 目录结构

```
packages/foggy-mobile/
├── index.html              # Vite 入口（H5 必需）
├── package.json            # 无 "type": "module"（uni-app CJS 插件兼容）
├── vite.config.ts          # 仅 uni() 插件
├── tsconfig.json           # paths: @/* → ./src/*
├── src/
│   ├── main.ts             # createSSRApp + Pinia
│   ├── App.vue             # onLaunch 登录检查
│   ├── pages.json          # 路由 + TabBar 配置
│   ├── manifest.json       # 平台配置 + H5 proxy
│   ├── uni.scss            # 全局样式变量
│   ├── env.d.ts            # @dcloudio/types 声明
│   ├── pages/              # 页面（7 个）
│   ├── components/         # 移动端组件（10 个）
│   ├── api/                # HTTP 接口层（镜像 Web 端）
│   ├── sse/                # 跨平台 SSE 客户端
│   ├── stores/             # Pinia 状态管理
│   ├── composables/        # 组合式函数
│   ├── adapters/           # 事件适配器
│   ├── utils/              # 工具函数
│   └── static/             # 图标资源
```

## 关键文件清单

### 页面

| 页面 | 路径 | 类型 | 功能 |
|------|------|------|------|
| 会话列表 | `pages/chat/index.vue` | Tab | 会话 CRUD + 下拉刷新 |
| 对话详情 | `pages/chat/detail.vue` | 子页 | SSE 流式 + 消息列表 |
| Worker 列表 | `pages/worker/index.vue` | Tab | Worker 卡片 + 目录展开（PROJECT → 子目录 → 独立目录） |
| 任务列表 | `pages/worker/tasks.vue` | 子页 | 按目录分页 + 创建任务 |
| 任务详情 | `pages/worker/task-detail.vue` | 子页 | SSE 流式 + 中止/续对 |
| 设置 | `pages/settings/index.vue` | Tab | 账号 + 服务器 + 退出 |
| 登录 | `pages/login/index.vue` | 子页 | 用户名密码 + 服务器地址 |

### 组件

| 组件 | 功能 |
|------|------|
| `MessageBubble.vue` | 聊天气泡（用户蓝/助手白），Markdown 渲染，工具/系统/任务消息 |
| `MessageList.vue` | scroll-view 消息列表，自动滚底，思考动画 |
| `ChatInput.vue` | 底部固定输入栏，safe-area 适配，confirm 发送，auto-height textarea |
| `ThinkingDots.vue` | 三点弹跳动画 |
| `ToolCallCard.vue` | 工具调用折叠卡片，点击展开详情 |
| `SessionItem.vue` | 会话列表项（标题 + 时间 + 状态点） |
| `WorkerCard.vue` | Worker 状态卡片（名称 + 状态 + hostname） |
| `TaskCard.vue` | 任务列表项（状态 + prompt + 费用 + 耗时） |
| `StatusBadge.vue` | 通用状态指示（彩色点 + 可选文字） |
| `EmptyState.vue` | 空状态占位（图标 + 标题 + 描述 + 插槽） |

### 基础设施

| 文件 | 功能 |
|------|------|
| `api/client.ts` | axios + `@uni-helper/axios-adapter`，401 自动跳登录 |
| `api/types.ts` | 所有后端类型定义（镜像 navigator-frontend/types） |
| `sse/UniSseClient.ts` | 跨平台 SSE（条件编译选择 transport），指数退避重试 |
| `sse/fetchSseTransport.ts` | H5: fetch + ReadableStream |
| `sse/wxSseTransport.ts` | 微信: uni.request + onChunkReceived |
| `stores/chat.ts` | Pinia 包装 `createChatState()`（来自 chat-core） |
| `stores/worker.ts` | Worker + 目录缓存（PROJECT/child/orphan 分类） |
| `composables/useSession.ts` | 会话 SSE 生命周期（chat/detail 用） |
| `composables/useTaskStream.ts` | 任务 SSE 流式输出（task-detail 用），跟踪 cost/duration/tokens |
| `utils/config.ts` | 服务器地址管理 + 条件编译 baseURL |
| `adapters/TutorAgentAdapter.ts` | SSE raw → AipMessage 转换 |

## PC 端已有、移动端待对齐的功能

> **参考对象**：`navigator-frontend` 的 ClaudeWorkerView、SlashCommandInput、TaskPane

| PC 功能 | 移动端现状 | 差距 |
|---------|-----------|------|
| **草稿持久化** — `useInputMemory` composable，按目录/会话 scope 存 localStorage | 无 | 刷新页面输入内容丢失 |
| **历史消息翻阅** — ArrowUp/Down 翻历史，发送后自动记录 | 无 | 需适配移动端交互（非箭头键） |
| **输入框自动伸缩** — SlashCommandInput `autoGrow` + el-input `autosize` | ChatInput 已有 `auto-height`; tasks.vue 原生 textarea 有 `auto-height` | 基本已对齐 |
| **SlashCommand 面板** — 输入 `/` 弹出命令/技能面板 | 无 | 移动端可简化为 ActionSheet 选模型/轮次 |
| **TaskPane 多面板** — 同时查看多个任务，Grid 布局 | 单任务详情页（task-detail） | 移动端屏幕小，单任务页面更合适 |
| **图片附加** — 粘贴/拖拽截图，压缩上传 | 无 | 可用 uni.chooseImage 选图 |
| **Rewind** — 回退到指定 turn | 无 | 可后续添加 |

## 开发命令

```bash
# H5 开发（端口 5175，自动 proxy 到后端 8112）
cd packages/foggy-mobile && pnpm dev:h5

# 微信小程序构建（输出到 dist/build/mp-weixin，用微信开发者工具打开）
cd packages/foggy-mobile && pnpm build:mp-weixin

# 构建共享包（修改 chat-core 后必须重建）
cd packages/foggy-chat-core && pnpm build

# 运行全部前端测试（验证 chat-core 重构未破坏现有功能）
pnpm test
```

## 开发规范

### uni-app 特有约束

1. **package.json 不能有 `"type": "module"`** — `@dcloudio/vite-plugin-uni` 是 CJS 模块
2. **vite.config.ts 不能用 ESM-only 插件**（如 UnoCSS）— 会报 `require` 错误
3. **条件编译语法**：`// #ifdef H5` / `// #ifdef MP-WEIXIN` / `// #ifndef`
4. **使用 `<view>` `<text>` `<scroll-view>`** 而非 `<div>` `<span>`
5. **路由用 `uni.navigateTo` / `uni.switchTab` / `uni.reLaunch`**，不用 vue-router
6. **存储用 `uni.setStorageSync` / `uni.getStorageSync`**，不用 localStorage
7. **页面生命周期用 `onLoad` / `onShow` / `onUnload`**（从 `@dcloudio/uni-app` 导入）
8. **TabBar 页面必须用 `uni.switchTab` 跳转**，不能用 `navigateTo`

### 存储 API 差异

移动端不能用 `localStorage`，必须用 `uni.setStorageSync` / `uni.getStorageSync`：
```typescript
// PC 端: localStorage.setItem('key', value)
// 移动端: uni.setStorageSync('key', value)
// PC 端: localStorage.getItem('key')
// 移动端: uni.getStorageSync('key')
// PC 端: localStorage.removeItem('key')
// 移动端: uni.removeStorageSync('key')
```

### 添加新页面

1. 在 `src/pages/` 下创建 `.vue` 文件
2. 在 `src/pages.json` 的 `pages` 数组中注册路径和样式
3. 如果是 Tab 页 → 同时在 `tabBar.list` 中添加
4. 如果需要 SSE → 使用 `useSession` 或 `useTaskStream` composable

### 添加新 API

1. 在 `api/types.ts` 添加类型定义
2. 在对应的 `api/*.ts` 文件中添加方法（参考 navigator-frontend 同名文件）
3. 使用 `client` 实例，返回类型用 `RX<T>` 解包

### 添加新组件

1. 在 `components/` 下创建 `.vue` 文件
2. 使用 `<view>` `<text>` 替代 HTML 标签
3. 样式使用 `rpx` 单位（750rpx = 屏幕宽度）
4. 底部固定元素加 `padding-bottom: calc(Xrpx + env(safe-area-inset-bottom))`

### 修改共享类型/逻辑

1. 修改 `packages/foggy-chat-core/src/` 下的文件
2. 重新构建：`cd packages/foggy-chat-core && pnpm build`
3. 运行测试验证：`pnpm test`（chatState.test.ts 覆盖全部分支）
4. 如果新增导出 → 在 `chat-core/src/index.ts` 中添加 export

## 决策规则

- 新增后端交互类型 → 先加到 `api/types.ts`，再写 API 方法
- 需要 Wot Design Uni 组件 → `<wd-button>` / `<wd-cell>` 等，无需注册（自动导入）
- 页面需要 SSE 流式 → `onLoad` 中 `connect`，`onUnload` 中 `disconnect`
- 跨平台差异处理 → 用 `// #ifdef PLATFORM` 条件编译，不用运行时判断
- 服务器地址变更 → `utils/config.ts` 的 `getApiBaseUrl()` 已处理 H5 proxy vs 非 H5 完整 URL
- 新增 Pinia store → 在 `stores/` 下创建，`defineStore` + Composition API 风格
- Markdown 渲染 → 使用 `utils/markdown.ts` 的 `renderMarkdown()`，输出给 `<rich-text :nodes="">`
- 移植 PC 功能到移动端 → 注意存储 API 差异（`uni.*Storage*` 替代 `localStorage`）
- 输入增强 → 移动端无箭头键，历史选择应用列表/弹窗交互替代

## 已知约束

- **Vue 版本必须为 3.4.21** — uni-app SDK 内置 `@dcloudio/uni-h5-vue` 是 Vue 3.4.21 分支
- **必须在 package.json 中显式固定所有 `@vue/*` 子包为 3.4.21** — pnpm workspace 会从根级提升 @vue/shared@3.5.x（来自 foggy-chat/navigator-frontend），导致 `updateSlots` 中 "Cannot assign to read only property '_'" 崩溃。原因：`@vue/shared` 3.5.x 的 `def()` 函数签名变更（显式 `writable: false`），与 uni-h5-vue 的 `Object.assign` 路径冲突
- **Pinia 版本 2.1.7**（非 2.2+/3.x）— Pinia 2.2+ 要求 Vue 3.5.11+
- **Vite 5.x**（非 7.x）— `@dcloudio/vite-plugin-uni` 要求
- **Tab 图标为占位 PNG** — 需替换为实际设计图标（81x81px）
- **暗色模式**：Wot Design Uni 原生支持，需在 App.vue 中配置 CSS 变量切换
- **避免 `<template v-else-if>` 在 scroll-view 内** — 用独立 `v-if` 块替代
