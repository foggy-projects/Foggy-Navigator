# 08 Playwright 体验验证报告

## Date

- 2026-04-03

## Type

- Validation
- Playwright Smoke
- Technical Report

## Scope

本次使用 `playwright-cli 1.59.0-alpha-1771104257000` 对 `1.0.0-SNAPSHOT` 目录下已完成事项做一次真实环境体验验证。

验证环境：

- Frontend: `http://localhost:5174`
- Backend: `http://localhost:8112`
- 登录账号: `root / root123`
- 验证方式: 浏览器真实点击 + 少量页面内 `fetch` 辅助取证

本次重点尝试覆盖：

- Worker / 目录页真实可用性
- 文件浏览器 deeplink 打开链路
- 会话同步入口
- 任务创建基础链路

## Environment Facts

### Worker / Directory Inventory

实测环境中的关键目录如下：

- `本机测试(4bd44b86) / Foggy Navigator(20260310-7dfb)`
  - 路径：`D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev`
- `LocalDev(e80af483) / TestProject(20260312-94b5)`
  - 路径：`D:\foggy-projects\\Foggy-Navigator-wt-qd-win11-dev`

其中第二个目录记录存在明显路径异常：盘符后缺少 `/`，同时斜杠混用。

## Summary

结论先说：

1. `07-chat-doc-link-open-file-browser-deeplink` 主链路已在真实浏览器中跑通。
2. `LocalDev / TestProject` 目录配置存在路径错误，会直接阻断任务启动和文件浏览器读取。
3. `06-session-long-history-render-optimization` 本次只能做有限验证，未拿到足够长的大历史会话做压力体验。
4. `04` / `05` 当前没有明显前端入口，本次 Playwright 体验未覆盖其业务能力。
5. `01` / `03` 能看到相关 UI 入口和上下文，但没有在共享环境里执行破坏性 kill / abort 动作做全链路验证。

## Detailed Findings

### Finding 1: `07` 文件浏览器 deeplink 首次打开验证通过

在健康目录 `本机测试 / Foggy Navigator` 上，直接打开：

```text
/#/files?directoryId=20260310-7dfb&workerId=4bd44b86&filePath=docs%2Fversion-tracker%2F1.0.0-SNAPSHOT%2FREADME.md
```

实测结果：

- 左侧树自动展开到 `docs/version-tracker/1.0.0-SNAPSHOT`
- 目标文件 `README.md` 自动打开
- 右侧编辑器正确显示文件内容
- 面包屑定位正确

这说明 `FileBrowserView` 的首次挂载 deeplink 消费链路是成立的，符合 `07` 的核心目标。

### Finding 2: 已打开文件浏览器标签内切换 `filePath` 不会重新定位

在文件浏览器已经打开的前提下，继续对同一标签直接改 hash 中的 `filePath`，页面没有重新展开/跳转到新文件。

这说明当前行为更接近：

- 首次打开新窗口：可用
- 复用已有文件浏览器标签并仅切换 `filePath`：未生效

这不是 `07` 文档要求中的主场景失败，但属于明显边界行为，建议补一个对 `route.query.filePath` 的 watcher。

### Finding 3: `LocalDev / TestProject` 目录配置异常，阻断多项功能

在 `LocalDev / TestProject` 上，两个功能都直接失败：

#### 3.1 任务创建失败

从目录页发起任务后，界面进入会话，但 Agent 很快返回：

```text
[CLIConnectionError] Failed to start Claude Code: [WinError 267] 目录名称无效。
```

这说明失败点不是前端，而是 Worker 启动 CLI 时吃到了非法 cwd。

#### 3.2 文件浏览器失败

点击“浏览文件”后，后端返回：

```text
列出文件失败: 400 Bad Request from GET http://localhost:3031/api/v1/files
```

结合目录记录里的路径值：

```text
D:\foggy-projects\\Foggy-Navigator-wt-qd-win11-dev
```

可以基本判断这是同一个根因：目录路径持久化错误，导致 Worker 侧文件接口和任务执行共用失败。

### Finding 4: 健康目录下文件浏览器根树可正常加载

在 `本机测试 / Foggy Navigator` 上点击“浏览文件”：

- 文件树能正常返回仓库根目录
- 能看到 `docs/`、`packages/`、`tools/` 等真实目录

说明文件浏览器实现本身不是整体不可用，而是对目录配置质量敏感。

### Finding 5: 会话同步入口工作，但长历史体验本次未充分覆盖

在 `本机测试 / Foggy Navigator` 上点击“同步会话”，前端收到提示：

```text
已同步 106 个新会话，共 106 个
```

说明“同步会话”入口与后端返回是通的。

但本次浏览器体验里，没有在当前右侧历史面板路径中稳定拿到一个可直接打开的超长会话样本，因此：

- 无法对 `06` 的“长历史滚动流畅度”给出强结论
- 也没有对 `loadMore / loadAll` 做完整交互回归

本次最多只能说明：

- 会话同步入口没挂
- 未见明显首屏渲染回退
- 但性能优化价值场景尚未被充分压测

### Finding 6: `01` / `03` 只能做到非破坏性观察

本次在 `本机测试` Worker 页看到了：

- CLI 进程表
- 一个 `Codex` 孤儿进程
- `终止 / 强制` 操作按钮

这说明与 `01-codex-task-abort-kill-failure` 相关的观测/操作入口已经暴露到 UI。

但考虑到当前是共享运行环境，本次没有直接点击 kill，也没有构造一个长运行中的 Codex 任务再执行中止，因此：

- 不能把本次体验视为 `01` 的完整回归证明
- 只能视为“入口存在、上下文合理、未做破坏性闭环验证”

`03-abort-task-entry-flow-analysis` 属于架构/入口分析项，本身也不是纯前端体验项，本次仅能确认前端仍有统一的任务中止相关入口，不足以覆盖其全部结论。

### Finding 7: `04` / `05` 本次未被前端体验覆盖

本次未发现明显的前端管理页或调试页可以直接验证：

- `04-shared-ask-external-url-propagation`
- `05-shared-agent-api-expansion`

因此这两项不适合通过当前这轮 Playwright UI 体验给出“通过/失败”结论，更适合：

- API 集成测试
- 或专门补一个前端调试页后再做浏览器验证

## Coverage Matrix

| 条目 | 结论 | 说明 |
|------|------|------|
| `01-codex-task-abort-kill-failure` | 部分验证 | 看到 CLI 进程和 kill 入口，未执行破坏性 kill/abort 闭环 |
| `03-abort-task-entry-flow-analysis` | 部分验证 | 仅观察到前端相关入口，架构结论仍以代码/测试为准 |
| `04-shared-ask-external-url-propagation` | 未覆盖 | 当前无明显前端体验入口 |
| `05-shared-agent-api-expansion` | 未覆盖 | 当前无明显前端体验入口 |
| `06-session-long-history-render-optimization` | 有限验证 | 同步会话成功，但未拿到足够长样本做性能体验 |
| `07-chat-doc-link-open-file-browser-deeplink` | 通过 | 新窗口首次 deeplink 打开、自动展开、自动打开文件均成功 |

## Evidence

本次关键证据来自以下浏览器快照与日志：

- `.playwright-cli/page-2026-04-03T14-04-42-999Z.yml`
  - `LocalDev / TestProject` 任务启动后出现 `WinError 267`
- `vt100-filebrowser-valid-root.yml`
  - 健康目录文件树正常加载
- `vt100-filebrowser-deeplink-afterwait.yml`
  - deeplink 首次打开后，自动展开并打开 `README.md`
- `.playwright-cli/console-2026-04-03T14-03-13-655Z.log`
  - 异常目录文件浏览器失败日志

## Retest Update

### Date

- 2026-04-03（同日复测）

### Background

用户已修正 `LocalDev / TestProject(20260312-94b5)` 的目录配置，因此对原先失败链路做了二次 Playwright 验证。

### Retest Results

#### Update 1: 目录路径已修正，文件浏览器恢复

复测时，`TestProject` 页面展示的目录路径已变为：

```text
D:\workspace\fsbi
```

再次点击“浏览文件”后，新标签页可正常打开文件浏览器，且根目录树成功加载，能看到：

- `.git`
- `foggy-fsbi-ai`
- `foggy-fsbi-common`
- `foggy-fsbi-domain`
- `foggy-fsbi-olap`
- `foggy-fsbi-ui`
- `services`
- `pom.xml`

这说明原报告中的“文件浏览器直接 400”问题已随目录修正一并解除。

#### Update 2: 任务启动链路恢复，不再出现 `WinError 267`

在 `LocalDev / TestProject` 上重新创建任务后，消息流变为：

- `Connecting to worker...`
- `Task started`
- Agent 正常返回正文

本次没有再出现：

```text
[CLIConnectionError] Failed to start Claude Code: [WinError 267] 目录名称无效。
```

因此，原报告中“目录错误导致任务完全无法启动”的结论已不再成立。

#### Update 3: “当前工作目录”回答不可靠，但根因不是 cwd 传递错误

第一次复测时，任务里 Agent 回答：

```text
当前工作目录是 C:\Users\oldse\.claude。
```

后续同样问法又出现过另一种回答：

```text
当前工作目录是：D:\foggy-projects\Foggy-Navigator-wt-qd-win11-dev
```

但 Worker 日志显示，这两次任务实际传给 SDK 的都是：

```text
cwd=D:\workspace\fsbi
```

同时日志里没有对应的 `ToolUse / ToolResult` 记录，说明模型并没有实际执行 `pwd / Get-Location` 之类的命令，而是在直接自然语言回答。

因此，这里的正确结论应是：

- `cwd` 参数传递本身看起来是正常的
- 问“当前工作目录”这种问题时，模型会在未调用工具的情况下直接猜答案
- 这个测试问法不能用来证明真实运行 cwd

如果要验证 cwd，应改成强约束问法，例如要求先执行 `pwd` / `Get-Location`，再原样贴出命令输出。

#### Update 4: 会话消息布局异常已复现

这次复测里，消息区确实存在布局问题，而且问题不只是“间距偏小”，而是更接近：

- 相邻消息卡片几乎贴在一起
- 首条用户消息被顶部会话状态条压住一截

从浏览器截图可直接看到：

- 顶部蓝色用户消息气泡被遮挡
- 后续 `Agent` 气泡之间基本没有可见垂直留白

页面内坐标也能说明同一件事：

- `message-list-scroller` 顶部约在 `248px`
- 首条 `.message-bubble.user` 顶部约在 `201px`
- 说明首条消息实际绘制到了滚动区可视顶部之上，被上层区域盖住

相邻消息气泡的边界值也几乎首尾相接：

- 第 1 条：`top=201`，`bottom=291.59`
- 第 2 条：`top=291`
- 第 3 条：`top=381`
- 第 4 条：`top=472`

虽然 `MessageBubble.vue` 里定义了 `margin-bottom: 12px`，但当前 `DynamicScroller` 实际呈现出来的结果并没有保住这段垂直间距。结合 DOM 结构看，问题大概率出在虚拟列表项高度测量与气泡外边距之间的配合，而不是单纯某一条 CSS 漏写。

### Impact

复测后，这份报告里的真实结论应更新为：

1. `LocalDev / TestProject` 的“路径错误导致完全不可用”问题已经修复。
2. 文件浏览器与任务启动链路已经恢复。
3. 但“请回答当前工作目录”这种自然语言测试不可靠，模型可能不经工具验证直接猜测路径。
4. 会话消息列表存在明确布局回退，表现为消息贴边和首条消息被遮挡。

### Additional Evidence

- `vt100-testproject-filebrowser.yml`
  - 修复后 `TestProject` 文件浏览器根树正常加载
- `vt100-testproject-chat-afterwait.yml`
  - 修复后任务成功启动并产出消息，同时可见消息列表布局异常
- `vt100-testproject-chat-afterwait.png`
  - 直接显示首条用户消息被顶部区域遮挡、消息间距缺失

## Decision

从“版本是否已具备交付质量”的角度看：

- `07` 可以视为已具备真实可用性
- `06` 方向上没有看到明显回退，但缺少长会话压测证据
- 当前最大的真实阻塞不是功能逻辑，而是目录配置数据质量

也就是说，这批工作里最先要补的不是再改 UI，而是修正异常目录记录，否则会让本来已经完成的功能在体验层看起来像“没做完”。

## Recommended Follow-ups

### P0

1. 修正 `LocalDev / TestProject(20260312-94b5)` 的持久化路径值。
2. 补一条目录保存/编辑时的路径规范化与合法性校验，至少拦住 `D:\foo` / `D:/foo` 混写且盘符后缺斜杠的情况。

### P1

1. 给 `FileBrowserView` 增加 `route.query.filePath` watcher，使已打开标签也能响应 deeplink 更新。
2. 准备一份可重复使用的“长历史 + 大代码块”演示会话，便于浏览器性能回归。

### P2

1. 如果希望 `04` / `05` 也纳入 Playwright 冒烟，建议补一个 Shared API 调试页或前端入口。
2. 为 `01` 单独准备可安全中止的测试任务，再做一次专门的 abort/kill 浏览器回归。

## Fix Verification: Update 4 布局回退修复（2026-04-04）

### 根因

`DynamicScroller` 的 `DynamicScrollerItem` 通过 `offsetHeight` 测量子元素高度，而 CSS `margin` 不计入 `offsetHeight`。原 `MessageBubble.vue` 使用 `margin-bottom: 12px` 提供消息间距，在虚拟列表中被完全忽略，导致消息紧密贴边。

首条消息被遮挡的原因是 `load-more-area` 放在 `DynamicScroller` 外面，占用了 container 空间但 scroller 不感知，导致内容区起始位置偏移。

### 修复内容

1. **MessageList.vue** — 在 `DynamicScrollerItem` 内部添加 `.scroller-item-wrapper` div，使用 `padding-bottom: 12px` 代替子组件的 `margin-bottom`（padding 计入 `offsetHeight`）
2. **MessageList.vue** — 将 `load-more-area` 从 `DynamicScroller` 外部移入 `#before` slot，使其成为 scroller 滚动内容的一部分，不再遮挡首条消息
3. **MessageBubble.vue** — 移除 `margin-bottom: 12px`（由 wrapper 的 padding 统一提供间距）
4. **MessageList.vue** — `compression-hint` 和 `waiting-hint` 的 `margin: 8px 0` 也改为 padding（同理）

### Playwright 验证结果

验证环境：`http://localhost:5174`，打开含 3 条消息的会话。

**间距测量对比**：

| 指标 | 修复前（原报告数据） | 修复后 |
|------|---------------------|--------|
| 消息 1-2 间距 | 0px（`bottom=291, top=291`） | 11px |
| 消息 2-3 间距 | 0px（`bottom=381, top=381`） | 11px |
| 首条消息 top | 201px（scroller 顶部 248px，被裁切 47px） | 105px（完全在可视区内） |

**结论**：

- ✅ 消息间距恢复到接近设计值（12px，实测 11px 为 subpixel 差异）
- ✅ 首条消息不再被顶部区域遮挡
- ✅ 用户消息右对齐、Agent 消息左对齐、圆角、时间戳等视觉元素均正常
- ✅ 前端构建（TypeScript + Vite）通过

### 证据

- `chat-opened.png` — 修复后的会话页面截图，3 条消息布局正常
- Playwright 坐标测量：`bubbleCount=3, gaps=[11, 11]`

## Status

本报告可作为 `1.0.0-SNAPSHOT` 当前阶段的前端体验验证记录。

它的核心结论不是”所有事项都已完成体验验证”，而是：

- 已证明 `07` 主链路可用
- 已定位一个会影响多条体验链路的目录配置问题
- 已明确哪些事项还缺少适合浏览器验证的测试场景
- ✅ Update 4 虚拟列表布局回退已修复并通过 Playwright 验证
