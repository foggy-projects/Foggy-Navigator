# PC Workers Playwright 报告

## 1. 基本信息

- 日期：2026-04-11
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

- 后端健康检查返回 `UP`
- 前端页面可正常访问
- Worker 健康检查返回正常 JSON

## 3. 预跑测试

执行过两轮前端测试：

### 3.1 通过项

命令：

```powershell
pnpm --filter @foggy/navigator-frontend test -- src/views/__tests__/ClaudeWorkerView.integration.test.ts
```

结果：

- `ClaudeWorkerView.integration.test.ts` 3 个测试全部通过

### 3.2 失败项

命令：

```powershell
pnpm --filter @foggy/navigator-frontend test -- src/views/__tests__/ClaudeWorkerView.integration.test.ts src/__tests__/useClaudeWorker.test.ts
```

结果：

- `useClaudeWorker.test.ts` 失败 7 项

判断：

- 该测试仍基于旧的 `@/api/claudeWorker` mock 口径
- 当前实现已经切到统一任务 API `/api/v1/tasks`
- 属于测试基线滞后，不宜把这 7 项直接视为本轮 PC Workers 改动引入的新功能回归

## 4. Playwright 执行链路

本轮实际完成的主链路如下：

1. 打开登录页并使用 `root / root123` 登录
2. 进入 `Workers` 首页
3. 选择 Worker `本机测试`
4. 选择目录 `foggy-assistant`
5. 确认目录头部动作区完整展示
6. 在目录任务输入区填入 prompt
7. 点击 `运行任务`
8. 观察任务进入执行态并等待流式结果

## 5. 结果结论

### 5.1 通过项

- 登录链路正常
- `Workers` 首页可正常进入
- Worker 列表正常展示
- Worker 详情区正常展示
- 目录树可展开并切换到目标目录
- 目录头部动作区已展示以下入口：
  - `同步 Git`
  - `同步会话`
  - `创建 Worktree`
  - `浏览文件`
  - `VS Code`
  - `终端`
  - `里程碑`
  - `编辑`
  - `删除`
- 目录态任务输入区可正常编辑
- `运行任务` 在填入 prompt 后可从禁用变为可点击
- 点击 `运行任务` 后，前端确实创建了任务面板并进入 `处理中`
- 会话流里能看到：
  - `Connecting to worker...`
  - `Task started`

### 5.2 阻塞项

- 本轮最小任务链路没有在观察窗口内得到最终助手回复
- UI 只持续收到重复的 `Task started`，未出现 `PC_WORKERS_OK`

### 5.3 失败/异常项

- 目录头部显示 `Auth 未配置`，但右侧选择器和标识又显示 `test / API: test`
  - 这是明显的状态表达不一致
- 页面控制台持续出现 `ElTag type=""` 校验警告
- 页面控制台出现 `el-pagination small` 即将废弃警告
- 页面缺少 `favicon.ico`

## 6. 关键判断

这轮验证说明：

1. PC Workers 的主页面结构和主要入口没有坏，用户仍能登录、选 Worker、选目录、发起任务。
2. 当前更像是“任务启动后流式结果异常”而不是“任务根本发不出去”。
3. 该问题不只是前端显示问题，Worker 日志也显示任务确实启动，但只不断产出 `SystemMessage`，没有稳定进入正文输出。

## 7. 日志与证据

### 7.1 页面快照

- [pcworkers-after-login.yml](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/.playwright-cli/pcworkers-after-login.yml)
- [pcworkers-worker-selected.yml](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/.playwright-cli/pcworkers-worker-selected.yml)
- [pcworkers-directory-selected.yml](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/.playwright-cli/pcworkers-directory-selected.yml)
- [pcworkers-after-fill.yml](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/.playwright-cli/pcworkers-after-fill.yml)
- [pcworkers-after-run-click.yml](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/.playwright-cli/pcworkers-after-run-click.yml)
- [pcworkers-after-run-wait.yml](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/.playwright-cli/pcworkers-after-run-wait.yml)

### 7.2 控制台与服务日志

- [console-2026-04-11T15-26-58-013Z.log](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/.playwright-cli/console-2026-04-11T15-26-58-013Z.log)
- [worker.log](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/tools/claude-agent-worker/logs/worker.log)
- [worker-error.log](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/tools/claude-agent-worker/logs/worker-error.log)
- [frontend-dev.err.log](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/.tmp-navigator-frontend-dev.err.log)

### 7.3 任务级证据

从 Worker 日志可确认：

- 任务 ID：`0d1ead5a-df8f-4af2-85ea-7722c8418ad7`
- 目录：`C:\Users\oldse\foggy-assistant`
- Prompt：`验收冒烟：只回复 PC_WORKERS_OK`
- SDK 进程 PID：`49956`

日志显示该任务已被真正启动，但在观察窗口内只持续产生 `SystemMessage`。

## 8. 本轮结论

本轮对 PC Workers 的判断是：

- 页面主骨架、目录树、动作区、任务创建入口：基本可用
- 任务执行闭环：阻塞

如果要继续推进下一轮修复/回归，建议优先排查：

1. Worker 侧 SDK 流式消息为什么只产出 `SystemMessage`
2. 前端目录头部 `Auth 未配置` 与 `API: test` 的状态来源是否不一致
3. `useClaudeWorker.test.ts` 需要迁移到统一任务 API 的测试口径
