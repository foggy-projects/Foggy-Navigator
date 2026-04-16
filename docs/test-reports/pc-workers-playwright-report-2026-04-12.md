# PC Workers Playwright 报告

## 1. 基本信息

- 日期：2026-04-12
- 范围：桌面端 `Workers` 页面
- 执行方式：`playwright-cli` 真实浏览器交互
- 对照清单：
  - [PC Workers 操作与体验检查清单](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/docs/test-cases/pc-workers-experience-checklist.md)

## 2. 环境状态

执行时环境如下：

- Frontend：`http://localhost:5174`
- Backend：`http://localhost:8112`
- Claude Worker：`http://localhost:3031`
- 登录账号：`root / root123`

运行前确认结果：

- `http://localhost:5174` 可访问，返回 `200`
- `http://localhost:8112/actuator/health` 返回 `UP`
- `http://localhost:3031/health` 返回正常 JSON，`claude_cli_available=true`

## 3. 预跑测试

本轮执行前后补做了最小验证：

### 3.1 通过项

命令：

```powershell
pnpm --dir packages/navigator-frontend type-check
mvn -pl addons/claude-worker-agent -am "-Dtest=WorkerStreamRelayTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：

- 前端 `vue-tsc --noEmit` 通过
- `WorkerStreamRelayTest` 4 个测试全部通过

### 3.2 本轮修复点

- 目录头部 Auth 标识改为优先显示当前生效的模型配置，如 `API: test`
- `ClaudeWorkerView` 中多个 `ElTag type=""` 已改为合法值
- `el-pagination` 的 `small` 废弃写法已改为 `size="small"`

## 4. Playwright 执行链路

本轮按清单执行的主链路如下：

1. 打开 `http://localhost:5174/#/login`
2. 使用 `root / root123` 登录
3. 进入 `http://localhost:5174/#/`
4. 展开 Worker `本机测试`
5. 选择目录 `foggy-assistant`
6. 校验目录头部动作区与生效 Auth 展示
7. 输入 prompt：`只回复 PC_WORKERS_OK`
8. 点击 `运行任务`
9. 等待流式输出

## 5. 结果结论

### 5.1 通过项

- 登录链路正常
- `Workers` 页面实际入口为 `/#/`，可正常进入
- Worker 树可展开
- 目录 `foggy-assistant` 可正常选中
- 目录头部正确显示：
  - 目录名
  - 实际路径
  - 生效 Auth 标识 `API: test`
- 目录头部动作区完整展示：
  - `同步 Git`
  - `同步会话`
  - `创建 Worktree`
  - `浏览文件`
  - `VS Code`
  - `终端`
  - `⤢`
  - `里程碑`
  - `编辑`
  - `删除`
- Prompt 输入后，`运行任务` 按钮从禁用变为可点击
- 点击后新任务面板成功创建，进入 `处理中`
- 本轮新开的浏览器会话控制台仅剩 1 条 `favicon.ico 404`，未再出现 `ElTag type=""` 或 `el-pagination small` 警告
- 在 `tools/claude-code-proxy` 可用后重试，新的任务 `只回复 PC_WORKERS_PROXY_RETRY_OK` 已在 2026-04-12 10:23:29 成功返回正文 `PC_WORKERS_PROXY_RETRY_OK`
- Claude Worker 日志确认本次任务使用 `auth_mode=API_KEY`、`base_url=http://localhost:8082`，并收到 `AssistantMessage` 与 `ResultMessage`
- Proxy 日志确认 2026-04-12 10:23:23 至 10:23:29 期间已成功代理 `claude-opus-4-6 -> B(glm-5)`，上游返回 `HTTP/1.1 200 OK`

### 5.2 阻塞项

- 当前主链路已无阻塞
- 早前 09:51 的失败样本仍然存在，说明当时执行时代理不可达或任务启动时机异常；该问题未在本次重试中复现

### 5.3 失败/异常项

- 页面仍缺少 `favicon.ico`，浏览器控制台固定报 `404`
- 根据 2026-04-12 晚间人工手测反馈，`Workers` 页面新增发现 2 个前端一致性问题：
  - 当前主会话区与右侧历史会话卡片状态可能不一致，例如主区域已可继续，但右侧卡片仍显示 `处理中`
  - 新建会话后，右侧历史会话列表不会立即刷新，通常要等任务完成后才出现新卡片
- 上述问题已单独登记为 BUG：
  - [18-workers-history-session-refresh-and-status-sync-bug.md](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/docs/version-tracker/1.0.1-SNAPSHOT/18-workers-history-session-refresh-and-status-sync-bug.md)
- 该 BUG 尚未在本轮 `playwright-cli` 中补做自动复现，已纳入后续 Playwright 固定回归清单
- 页面创建任务时浏览器控制台曾出现过一次 `/api/v1/tasks` 的 `500`；后续排查确认这不是创建任务失败，而是后台拉取活跃任务列表时，`directoryId=null` 导致 `TaskDispatchFacade` 在组装 DTO 时空指针
- 该空指针已在本轮修复：
  - [TaskDispatchFacade.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java)
  - [TaskDispatchFacadeTest.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java)
- 对应模块回归已通过：
  - `mvn -pl session-module -am "-Dtest=TaskDispatchFacadeTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
- 修复后使用 fresh 浏览器会话 `pcworkers4` 重新登录并打开 `/#/tasks`，任务看板已正常渲染，未再观察到新的 `/api/v1/tasks 500`
- Worker 列表中的孤儿进程在本机多实例场景下不作为本轮阻塞项处理

## 6. 关键判断

这轮验证后的判断是：

1. PC Workers 的页面结构、入口和目录头部状态表达已经明显收敛。
2. 前端这轮修复已解决可见的 Auth 展示不一致和两类 Element Plus 警告。
3. 在 `claude-code-proxy` 正常监听 `8082` 后，Worker/SDK 流式链路可完整跑通到正文输出和完成态。
4. 早前失败更接近环境依赖未就绪，而不是当前代码路径持续性故障。
5. 控制台里额外出现的 `/api/v1/tasks 500` 不是主执行链路失败，而是任务列表接口在 `directoryId=null` 时的后端空指针；该问题已完成代码修复、单测回归，并在 fresh 浏览器会话下不再复现。
6. 当前仍有一个未修复的前端一致性缺陷：新建会话后的历史列表即时刷新与状态同步不稳定，需在下一轮前端修复中优先收口。

## 7. 日志与证据

### 7.1 页面快照

- [page-2026-04-12T01-50-30-052Z.yml](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/.playwright-cli/page-2026-04-12T01-50-30-052Z.yml)
- [page-2026-04-12T01-50-52-179Z.yml](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/.playwright-cli/page-2026-04-12T01-50-52-179Z.yml)
- [page-2026-04-12T01-51-09-172Z.yml](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/.playwright-cli/page-2026-04-12T01-51-09-172Z.yml)
- [page-2026-04-12T01-51-29-231Z.yml](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/.playwright-cli/page-2026-04-12T01-51-29-231Z.yml)
- [page-2026-04-12T02-20-09-791Z.yml](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/.playwright-cli/page-2026-04-12T02-20-09-791Z.yml)
- [page-2026-04-12T02-21-07-054Z.yml](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/.playwright-cli/page-2026-04-12T02-21-07-054Z.yml)
- [page-2026-04-12T02-21-28-489Z.yml](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/.playwright-cli/page-2026-04-12T02-21-28-489Z.yml)
- [page-2026-04-12T02-23-28-962Z.yml](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/.playwright-cli/page-2026-04-12T02-23-28-962Z.yml)
- [page-2026-04-12T02-23-56-155Z.yml](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/.playwright-cli/page-2026-04-12T02-23-56-155Z.yml)
- [page-2026-04-12T06-32-09-934Z.yml](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/.playwright-cli/page-2026-04-12T06-32-09-934Z.yml)
- [page-2026-04-12T06-35-09-924Z.yml](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/.playwright-cli/page-2026-04-12T06-35-09-924Z.yml)
- [page-2026-04-12T06-38-14-364Z.yml](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/.playwright-cli/page-2026-04-12T06-38-14-364Z.yml)

### 7.2 控制台与服务日志

- [console-2026-04-12T01-49-15-893Z.log](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/.playwright-cli/console-2026-04-12T01-49-15-893Z.log)
- [console-2026-04-12T02-20-09-601Z.log](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/.playwright-cli/console-2026-04-12T02-20-09-601Z.log)
- [console-2026-04-12T06-32-09-751Z.log](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/.playwright-cli/console-2026-04-12T06-32-09-751Z.log)
- [worker.log](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/tools/claude-agent-worker/logs/worker.log)
- [worker-error.log](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/tools/claude-agent-worker/logs/worker-error.log)
- [proxy.log](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/tools/claude-code-proxy/logs/proxy.log)
- [backend.log](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/logs/backend.log)

### 7.3 任务级证据

早前失败样本：

- Task ID：`4492ab7c-7f31-40ca-8a4b-685015f06a01`
- Foggy Task ID：`20260412-9721`
- Session ID：`78fd6109-f99b-4581-8a66-b85ed3f044ad`
- 目录：`C:\Users\oldse\foggy-assistant`
- Prompt：`只回复 PC_WORKERS_OK`

代理恢复后的成功样本：

- Worker Task ID：`4975ca7e-6c5e-46f9-910c-90ae33284dcc`
- Foggy Task ID：`20260412-99a3`
- Session ID：`a9892664-9a04-4c64-890b-66326e52783e`
- Claude Session ID：`91c570c1-3434-43e8-8685-05afb73662d5`
- Prompt：`只回复 PC_WORKERS_PROXY_RETRY_OK`
- 正文返回：`PC_WORKERS_PROXY_RETRY_OK`

### 7.4 后续修复证据

- `/api/v1/tasks` 的瞬时 `500` 对应后端堆栈落点：
  - `TaskController.listTasks`
  - `TaskDispatchFacade.listActiveTasks`
  - `TaskDispatchFacade.toDispatchTaskDTO`
- 根因是 `directoryId=null` 时，对不可变 `directoryNames` Map 直接 `get(null)`。
- 本轮新增回归测试 `listActiveTasks_allowsNullDirectoryId()`，验证空目录任务不会再导致列表接口失败。
- 修复后使用 fresh 浏览器会话 `pcworkers4` 从登录页进入主页面，再进入 `/#/tasks`：
  - 登录页控制台仅剩 `favicon.ico 404`
  - `任务看板` 正常渲染，且包含历史任务行
  - `logs/backend.log` 未再出现新的 `TaskDispatchFacade` / `/api/v1/tasks` 空指针堆栈

## 8. 本轮结论

本轮对 PC Workers 的判断是：

- 页面主链路、目录头部展示和基础交互：可用
- 前端可见警告修复：基本完成
- Worker 执行闭环：在代理服务正常启动后可用
- 活跃任务列表接口的 `directoryId=null` 空指针：已修复，通过模块测试回归，并在 fresh 浏览器会话下不再复现
- 历史会话列表的“新建即出现”和“状态即时一致”仍存在前端侧待修缺陷，已转为独立 BUG 跟踪

下一轮建议直接聚焦：

1. 给 `tools/claude-code-proxy` 增加更明确的启动前检查，避免未启动时误判为代码缺陷
2. 把 fresh 浏览器复验纳入 PC Workers 固定回归步骤，后续每次大改后默认执行
3. 优先修复历史会话列表“创建后不即时刷新 / 状态不同步”问题，并补 Playwright 回归
4. 低优先级处理 `favicon.ico 404`
