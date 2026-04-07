# 07 - 聊天文档超链接打开文件浏览器并定位文件

## 日期

- 2026-04-03

## 背景

当前任务对话中会出现指向本地文档的 Markdown 超链接，例如：

- `D:/foggy-projects/foggy-data-mcp/docs/v1.0/P0-Odoo模型唯一来源与本地注册中心治理-执行规划.md`

从实际效果看，这类链接目前仍按浏览器默认 `<a>` 行为处理。结果是：

1. 链接会被当作普通 URL 或同源相对路径打开，而不是进入 Navigator 文件浏览器。
2. 即使用户已经在某个 `workerId + directoryId` 工作目录下，也不会自动复用该目录上下文。
3. 文件浏览器页当前只消费 `directoryId` / `workerId`，不会在启动时自动展开目录树、定位目标文件、保持选中态。

用户新增需求：点击任务消息中的文档超链接后，系统应自动打开类似 `/#/files?directoryId=20260305-fe66&workerId=9bb67974` 的文件浏览器窗口，并直接打开目标文件；左侧树结构需要自动展开到该文件所在目录，并保持该文件为当前选中项。

## 目标

1. 在任务聊天区点击“工作区内文件文档链接”时，不再走浏览器默认跳转。
2. 自动打开 Navigator 文件浏览器窗口，并携带精确的 `workerId`、`directoryId`、目标文件路径。
3. 文件浏览器加载后自动展开左侧目录树、打开目标文件、保持目标文件高亮/选中。
4. 方案要保持 `@foggy/chat` 作为通用聊天组件的边界，不直接硬编码 Navigator 业务 URL。

## 非目标

1. 本次不处理工作区外的任意本地路径跳转。
2. 本次不处理跨域站点、HTTP/HTTPS 外链、图片链接等普通网页跳转行为。
3. 本次不扩展文件浏览器为可编辑模式；仍然只做查看、搜索、diff。
4. 本次不要求一次性支持“自动定位到行号”，但可为后续预留 `line` 参数。

## 现状调研

### 1. 聊天消息里的链接目前没有业务拦截

任务面板通过 `TaskPane` 使用共享聊天组件：

```ts
// packages/navigator-frontend/src/components/worker/TaskPane.vue
import { ChatPanel } from '@foggy/chat'
```

Markdown 渲染来自共享包：

```ts
// packages/foggy-chat/src/utils/markdownRenderer.ts
const mdFull = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
  highlight: fullHighlight,
})
```

消息气泡当前只拦截“代码块复制按钮”，普通超链接没有专门事件：

```ts
// packages/foggy-chat/src/components/MessageBubble.vue
function handleContentClick(e: Event) {
  const target = e.target as HTMLElement
  if (!target.classList.contains('code-copy-btn')) return
  // ...
}
```

这意味着当前 `<a>` 标签仍由浏览器默认处理。

### 2. 文件浏览器窗口已经支持独立打开，但只带目录上下文

`ClaudeWorkerView.vue` 已有“浏览文件”按钮，可打开新窗口：

```ts
// packages/navigator-frontend/src/views/ClaudeWorkerView.vue
function openFileBrowser() {
  if (!selectedDirectoryId.value) return
  const url = `${window.location.origin}/#/files?directoryId=${selectedDirectoryId.value}&workerId=${selectedWorkerId.value}`
  window.open(url, '_blank', 'width=1400,height=900')
}
```

这说明“打开文件浏览器独立窗口”链路已存在，不需要新建页面或新路由。

### 3. 文件浏览器页当前不会消费目标文件 deeplink

文件浏览器当前只从 query 中读取目录上下文：

```ts
// packages/navigator-frontend/src/views/FileBrowserView.vue
const directoryId = computed(() => (route.query.directoryId as string) || '')
const workerId = computed(() => (route.query.workerId as string) || '')
```

初始化时只加载根目录树：

```ts
// packages/navigator-frontend/src/views/FileBrowserView.vue
onMounted(async () => {
  // ...
  if (directoryId.value) {
    loadDirectory()
    loadIgnoredPatterns()
  }
})
```

当前仅在用户点击树节点时更新选中路径：

```ts
// packages/navigator-frontend/src/views/FileBrowserView.vue
async function handleNodeClick(data: TreeNode) {
  selectedTreePath.value = data.fullPath
  if (data.isDir) {
    await loadDirectoryForNode(data)
  } else {
    await loadFile(data.fullPath)
  }
}
```

`loadFile()` 自身不会回写树节点选中状态，也没有“按目标路径递归展开目录”的启动逻辑。

### 4. 当前窗口间消息机制已经存在，可复用设计思路

文件浏览器页已通过 `window.opener.postMessage(...)` 与 Worker 主页通信：

```ts
// packages/navigator-frontend/src/views/FileBrowserView.vue
window.opener.postMessage({
  type: 'run-in-terminal',
  directoryId: directoryId.value,
  workerId: workerId.value,
  filePath: getSubPath(node.fullPath),
}, window.location.origin)
```

主页已有消息监听：

```ts
// packages/navigator-frontend/src/views/ClaudeWorkerView.vue
window.addEventListener('message', handleFileBrowserMessage)
```

说明“共享窗口通信”和“基于 directoryId / workerId 切换上下文”在现有架构中是成立的。

## 问题归纳

要满足本需求，必须同时补齐三件事：

1. 任务消息里的文档链接点击需要被业务层接管，而不是走浏览器默认 `<a>`。
2. 点击端需要能拿到当前工作目录上下文，并把目标路径转换为文件浏览器可识别的 deeplink。
3. 文件浏览器需要新增“按目标文件路径启动”的初始化流程，而不仅仅是加载根目录。

## 需求范围

### V1 必须支持

1. 在 `ClaudeWorkerView -> TaskPane -> @foggy/chat` 的任务聊天区点击工作区文档链接。
2. 使用当前任务所在目录的 `workerId`、`directoryId` 作为打开上下文。
3. 当链接路径位于当前工作目录根路径之下时，自动计算相对路径并打开文件。
4. 新窗口首次打开时完成目录树展开、文件打开、文件选中。
5. 若文件浏览器窗口已存在，允许复用同一 deeplink 重新打开或刷新到目标文件，具体实现可二选一，但用户感知必须是“点击后直接定位文件”。

### V1 建议支持

1. deeplink 预留 `line` 参数，便于后续从搜索结果或代码引用直接跳到行号。
2. 对无法解析的路径给出明确提示，例如“该链接不在当前工作目录下，无法自动定位”。

### V1 暂不要求

1. 从任意 `D:/...` 绝对路径全局反查所有目录注册记录。
2. 自动跨 Worker 匹配目录。
3. 同一聊天消息中混合多个不同工作目录的链接自动分流。

## 推荐方案

### 方案原则

Navigator 业务层负责“链接语义解释和窗口打开”；共享聊天组件只负责“把链接点击事件抛出来”。

不建议把 `/#/files?...`、`window.open(...)`、`directoryId` 这类 Navigator 语义直接写进 `@foggy/chat`，否则共享包会和宿主业务强耦合。

### 分层设计

#### A. `@foggy/chat` 提供可选链接点击事件

在共享聊天组件中新增事件透传链：

- `MessageBubble` 识别点击目标是否为 `<a>`
- 将 `href`、`textContent`、原始事件信息通过 emit 向上抛出
- `MessageList` / `ChatPanel` 继续透传
- `TaskPane` 将事件交给宿主页面 `ClaudeWorkerView`

建议事件载荷：

```ts
type ChatLinkClickPayload = {
  href: string
  text: string
}
```

#### B. `TaskPane` / `ClaudeWorkerView` 负责解析“是否为工作区文件链接”

宿主层拥有当前任务上下文，可拿到：

- `task.workerId`
- `task.directoryId`
- 当前选中目录对象 `selectedDirectory.path`

推荐识别逻辑：

1. 若 `href` 已经是显式文件浏览器 deeplink，例如 `/#/files?...&filePath=...`，则直接打开。
2. 若 `href` 或链接文本是 Windows 绝对路径，且该路径位于当前目录根路径下，则转换为相对路径。
3. 若路径无法归属当前目录，则提示失败，并保留默认打开能力作为兜底。

#### C. 文件浏览器页新增 deeplink 参数

建议新增 query 参数：

- `filePath`: 相对当前 `directoryId` 根目录的文件路径
- `line`: 可选，预留

示例：

```text
/#/files?directoryId=20260305-fe66&workerId=9bb67974&filePath=docs/v1.0/P0-Odoo模型唯一来源与本地注册中心治理-执行规划.md
```

#### D. 文件浏览器页新增启动定位流程

进入 `/files` 页时，如果存在 `filePath`：

1. 根目录 `loadDirectory()` 完成。
2. 按 `filePath` 逐级拆分目录。
3. 依次执行 `loadDirectoryForNode()` 展开父目录链。
4. 找到目标文件节点后：
   - 设置树当前选中项
   - 更新 `selectedTreePath`
   - 执行 `loadFile(fullPath)`
5. 如果存在 `line`，则在编辑器 ready 后滚动到对应行。

## 推荐交互语义

### 成功路径

1. 用户点击聊天中的文档链接。
2. 系统打开文件浏览器新窗口。
3. 左侧目录树自动展开到目标文件所在目录。
4. 目标文件在树中保持选中状态。
5. 右侧编辑区直接显示文件内容。

### 失败路径

1. 链接不是本地工作区文件：按默认浏览器行为打开。
2. 链接看起来像本地路径，但不在当前工作目录下：提示“无法在当前目录上下文定位该文件”。
3. deeplink 指向的文件不存在：文件浏览器页提示“文件不存在或已被移动”，但仍保留已打开的目录上下文。

## 验收标准

1. 在某个目录任务的聊天区点击工作区内文档链接，能打开 `/#/files?directoryId=...&workerId=...` 文件浏览器页。
2. 文件浏览器页启动后，右侧能直接展示目标文件内容。
3. 左侧树结构自动展开到文件所在目录，不要求用户手动点击父目录。
4. 左侧树中目标文件保持选中态，而不是只打开右侧内容。
5. 同一目录下重复点击不同文件链接时，能够稳定切换到新目标文件。
6. 对不在当前目录下的绝对路径，系统能给出明确失败提示，不得静默无反应。

## 实施建议

### Phase 1: 共享聊天组件事件透传

1. `MessageBubble.vue` 增加 `<a>` 点击识别与 `link-click` emit。
2. `MessageList.vue` 透传 `link-click`。
3. `ChatPanel.vue` 暴露 `link-click` 给宿主。

### Phase 2: Navigator 业务层链接解析

1. `TaskPane.vue` 接收 `link-click`，向上抛给 `ClaudeWorkerView`。
2. `ClaudeWorkerView.vue` 基于当前 pane/task/selectedDirectory 解析本地文件链接。
3. 统一生成文件浏览器 deeplink 并 `window.open(...)`。

### Phase 3: 文件浏览器 deeplink 定位

1. `FileBrowserView.vue` 新增 `filePath` / `line` query 解析。
2. 增加“按目标文件路径递归展开目录树”的 helper。
3. 增加“设置当前选中节点”的逻辑。
4. 若需要，补充树控件当前节点高亮支持。

### Phase 4: 测试

1. `@foggy/chat` 组件测试：点击 `<a>` 是否正确 emit。
2. `navigator-frontend` 视图测试：收到 `link-click` 后是否生成正确 deeplink。
3. `FileBrowserView` 测试：携带 `filePath` 初始化时是否展开目录并打开文件。

## 代码清单

```yaml
code_inventory:
  - repo: Foggy-Navigator
    path: packages/foggy-chat/src/components/MessageBubble.vue
    role: Markdown 链接点击入口
    expected_change: update
    notes: 当前只处理 code-copy-btn，需新增 a 标签点击分支与 emit

  - repo: Foggy-Navigator
    path: packages/foggy-chat/src/components/MessageList.vue
    role: 消息事件透传
    expected_change: update
    notes: 透传 MessageBubble link-click 事件

  - repo: Foggy-Navigator
    path: packages/foggy-chat/src/components/ChatPanel.vue
    role: 宿主接入共享聊天组件的事件出口
    expected_change: update
    notes: 对外暴露 link-click 事件

  - repo: Foggy-Navigator
    path: packages/navigator-frontend/src/components/worker/TaskPane.vue
    role: 当前任务上下文与共享聊天组件的桥接层
    expected_change: update
    notes: 将 link-click 上抛给 ClaudeWorkerView

  - repo: Foggy-Navigator
    path: packages/navigator-frontend/src/views/ClaudeWorkerView.vue
    role: 目录上下文解析、deeplink 生成、窗口打开
    expected_change: update
    notes: 复用已有 openFileBrowser 风格，不建议在共享包中直接写 Navigator URL

  - repo: Foggy-Navigator
    path: packages/navigator-frontend/src/views/FileBrowserView.vue
    role: deeplink 消费、目录树自动展开、文件选中、文件加载
    expected_change: update
    notes: 当前仅消费 directoryId/workerId，需新增 filePath 初始化流程

  - repo: Foggy-Navigator
    path: packages/navigator-frontend/src/api/fileBrowser.ts
    role: 文件浏览器前端 API
    expected_change: read-only-analysis
    notes: 当前 list/read 已够用，原则上 V1 不必新增后端 API

  - repo: Foggy-Navigator
    path: addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/WorkingDirectoryController.java
    role: 工作目录管理接口
    expected_change: read-only-analysis
    notes: 若后续要支持“绝对路径全局反查 directoryId”，这里可能新增 resolve 接口；V1 可先不改

  - repo: Foggy-Navigator
    path: addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/WorkingDirectoryService.java
    role: directoryId 到 path/workerId 的服务层
    expected_change: read-only-analysis
    notes: 当前前端已有所需 directory.path 信息，V1 可以不改服务端
```

## 关键代码触点

### 1. 文件浏览器窗口入口

```ts
// packages/navigator-frontend/src/views/ClaudeWorkerView.vue
function openFileBrowser() {
  if (!selectedDirectoryId.value) return
  const url = `${window.location.origin}/#/files?directoryId=${selectedDirectoryId.value}&workerId=${selectedWorkerId.value}`
  window.open(url, '_blank', 'width=1400,height=900')
}
```

### 2. 文件浏览器当前 query 入口

```ts
// packages/navigator-frontend/src/views/FileBrowserView.vue
const directoryId = computed(() => (route.query.directoryId as string) || '')
const workerId = computed(() => (route.query.workerId as string) || '')
```

### 3. 文件树点击后才有选中态

```ts
// packages/navigator-frontend/src/views/FileBrowserView.vue
async function handleNodeClick(data: TreeNode) {
  selectedTreePath.value = data.fullPath
  if (data.isDir) {
    await loadDirectoryForNode(data)
  } else {
    await loadFile(data.fullPath)
  }
}
```

### 4. 共享聊天当前未拦截普通链接

```ts
// packages/foggy-chat/src/components/MessageBubble.vue
function handleContentClick(e: Event) {
  const target = e.target as HTMLElement
  if (!target.classList.contains('code-copy-btn')) return
  // 普通 a 标签未处理
}
```

## 风险与注意事项

1. `@foggy/chat` 是共享包，若直接内置 Navigator URL，会污染组件边界。
2. 现有文档链接很多是 Windows 绝对路径；若不约束来源，仅靠前端字符串判断会有误判风险。
3. `el-tree-v2` 当前代码里没有显式“当前节点”控制；实现选中保持时需要验证是用组件原生 current-key，还是用现有 `selectedTreePath` 自定义高亮。
4. 若用户在聊天中点击的是其他目录或其他仓库的绝对路径，V1 需要有清晰的失败提示，避免错误打开当前目录下的同名文件。

## 建议结论

建议按“共享包抛事件、宿主层解析链接、文件浏览器消费 deeplink”的三段式实现。

这个方案的优点是：

1. 改动面清晰，符合现有模块边界。
2. V1 可以只改前端，不必立即新增后端路径解析接口。
3. 后续如果要支持跨目录路径反查，再在 `WorkingDirectoryController` / `WorkingDirectoryService` 上补 resolve API 即可，不会推翻现有前端事件链。

## Implementation Status

**状态**: ✅ 已实施（2026-04-04）

### 已完成的改造

1. **共享聊天组件事件透传**
   - `MessageBubble.vue` 已拦截普通 `<a>` 点击并透传 `link-click`
   - `MessageList.vue` / `ChatPanel.vue` 已继续向宿主透传该事件

2. **Navigator 宿主层路径解析**
   - `ClaudeWorkerView.vue` 已接入 `handleLinkClick(...)`
   - 通过 `resolveChatLinkTarget(...)` 统一处理工作区绝对路径、相对路径、同源文件浏览器 deeplink、外链兜底和失败 warning

3. **文件浏览器 deeplink 消费**
   - `FileBrowserView.vue` 已支持首次挂载时读取 `filePath`
   - 已补充 `route.query.filePath` watcher，支持同标签内二次切换目标文件
   - 目录树选中态通过 `selectedTreePath` 保持

### 验证结果

1. `08-playwright-experience-report.md` 已验证首次 deeplink 打开、自动展开目录树、自动打开目标文件通过。
2. `11-playwright-revalidation-report.md` 已验证同一文件浏览器标签内仅修改 `filePath` 时，可以切换到新文件。
3. 当前补跑 `pnpm --filter @foggy/navigator-frontend test -- src/__tests__/chatLinkResolver.test.ts`，结果为 `7 passed`。

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted
- signed_off_by: engineering-signoff
- signed_off_at: 2026-04-07
- acceptance_record: docs/version-tracker/1.0.0-SNAPSHOT/acceptance/07-chat-doc-link-open-file-browser-deeplink-acceptance.md
- blocking_items: none
- follow_up_required: no
