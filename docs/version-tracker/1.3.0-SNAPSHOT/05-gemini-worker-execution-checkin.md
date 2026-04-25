# Gemini Worker 执行回写

## 文档作用

- doc_type: execution-checkin
- intended_for: execution-agent | reviewer | follow-up-owner
- purpose: 记录 `GeminiWorker` 首轮规划与实现落地结果、验证状态、阻塞项与下一步

## Version

- `1.3.0-SNAPSHOT`

## 当前状态

- status: `IN_PROGRESS`
- delivery_mode: `single-root-delivery`
- operation_mode: `execution-checkin`

## 本轮已完成

### 1. 文档基线

已建立以下版本文档：

1. `01-gemini-worker-requirement.md`
2. `02-gemini-worker-module-responsibility.md`
3. `03-gemini-worker-code-inventory.md`
4. `04-gemini-worker-implementation-plan.md`

### 2. Node Worker MVP

已新增独立目录 `tools/gemini-agent-worker/`，完成首版最小 worker：

1. `POST /api/v1/query`
2. `GET /api/v1/tasks/:id/subscribe`
3. `GET /api/v1/tasks/:id/status`
4. `POST /api/v1/tasks/:id/abort`
5. `GET /api/v1/tasks`
6. `GET /api/v1/sessions`
7. `GET /health`

已实现：

1. `gemini -p ... --output-format stream-json` 调用封装
2. Gemini JSON 事件到统一 `WorkerEvent` 的映射
3. 本地事件缓存与重放
4. `.gemini/settings.json` / `GEMINI.md` / `~/.gemini/agents` 初始化与技能软链接

### 3. Java Addon MVP

已新增独立模块 `addons/gemini-worker-agent/`，完成：

1. `GeminiWorkerClient` / `GeminiWorkerClientFactory`
2. `GeminiTaskEntity` / DTO / Form / Repository
3. `GeminiTaskService`
4. `GeminiStreamRelay`
5. `GeminiWorkerAgentProvider`
6. `GeminiWorkerInnerA2aAgent`
7. 自动装配与 launcher 模块接入

### 4. 统一调度与配置接入

已补齐以下能力：

1. `GEMINI_CLI` backend 枚举
2. `gemini-worker` provider 路由映射
3. `DispatchTaskDTO.geminiSessionId`
4. Worker 管理接口中的 `GeminiConfig`
5. 前端模型后端识别与 Gemini 模型选项

## 本轮关键实现判断

### 1. 接入策略

本轮坚持独立 provider 路线，没有把 Gemini 硬塞进 `codex-worker`：

1. 降低对现有 Claude/Codex 逻辑的回归风险
2. 保留后续把多个 CLI provider 再抽象成统一层的空间

### 2. 会话恢复策略

本轮已将 `geminiSessionId` 同时写入：

1. `DispatchTaskDTO`
2. `SessionTaskEntity.taskStateJson`
3. `SessionEntity.providerStateJson`

这保证后续 resume 不依赖前端透传私有字段。

### 3. 流式事件策略

Java relay 当前按统一 `WorkerEvent` 处理：

1. `assistant_text`
2. `tool_use`
3. `tool_result`
4. `result`
5. `error`

其中 `result` 映射为会话结束消息，并驱动任务完成落库。

## 验证结果

### 1. 已完成验证

执行命令：

```powershell
mvn -pl addons/gemini-worker-agent -am -DskipTests compile
```

结果：

1. `gemini-worker-agent` 模块编译通过
2. 统一依赖链路编译通过
3. 当前仅存在一个已有接口的 deprecation warning，不阻塞首版接入

本轮代码简化后再次验证：

```powershell
mvn -pl addons/gemini-worker-agent -am -DskipTests compile
```

结果：

1. Java 侧简化重构后仍保持编译通过
2. `GeminiTaskService` 已抽出 worker metadata 更新与 session task projection 填充逻辑
3. `GeminiStreamRelay` 与 Node 路由层已减少重复分支和重复 SSE 输出代码

本机安装 Gemini CLI 后再次验证：

```powershell
gemini --version
npm run typecheck
gemini -p "ping" --output-format stream-json --yolo
```

结果：

1. 当前主机已成功安装 Gemini CLI，版本 `0.39.1`
2. `tools/gemini-agent-worker` 已安装 npm 依赖，TypeScript `typecheck` 通过
3. 完成 Google 登录后，真实 CLI headless 调用已成功执行
4. 已确认当前 CLI 在自动化场景下需要额外携带 `--skip-trust`
5. 已确认 Windows 下不能继续使用 `spawn(..., { shell: true })` 调 Gemini，否则 `-p` 会被误解析

补充真实联调结果：

```powershell
gemini -p "Respond with exactly OK" --output-format stream-json --yolo --skip-trust
```

输出特征：

1. `init` 事件包含 `session_id`
2. `message` 事件使用 `role=user|assistant`
3. `result` 事件的 token / duration 主要位于 `stats` 字段
4. `model` 需要优先从 `stats.models` 推导

基于这轮真实联调，已完成以下修正：

1. `tools/gemini-agent-worker/src/gemini/cli-wrapper.ts`
   将 Windows 启动方式改为 `cmd.exe /c gemini ...`，移除原先有问题的 `shell: true` 直调
2. `tools/gemini-agent-worker/src/gemini/cli-wrapper.ts`
   默认附加 `--skip-trust`
3. `tools/gemini-agent-worker/src/gemini/event-mapper.ts`
   适配真实 `init/message/result/stats/models` 结构
4. `tools/gemini-agent-worker/src/gemini/event-mapper.ts`
   过滤 `role=user` 的 message，避免把用户 prompt 当成 assistant 输出

本机 smoke run 结果：

1. 已通过 `runQuery(...)` 跑出统一事件
2. 当前观测到的最小有效事件序列为：
   - `assistant_text`
   - `result`
3. `result` 已带 `session_id`、`model`、`input_tokens`、`output_tokens`、`duration_ms`
4. 已补 `result.content/result` 回填逻辑，避免真实 Gemini `result` 只带统计字段时丢失最终文本

继续完成的真实能力验证：

```powershell
node --import tsx
```

通过 `runQuery(...)` 直接做了 3 组本机联调：

1. smoke 验证：
   `Respond with exactly OK`
   已确认最终事件为：
   - `assistant_text: "OK\n"`
   - `result.content/result: "OK\n"`
2. abort 验证：
   对长输出任务在约 1.5 秒后调用 `abortTask(taskId)`
   已确认 worker 最终落出：
   - `error: "Task aborted"`
   - `abortTask(...)` 返回 `true`
3. resume 验证：
   第一轮输出 `SESSION_ONE`
   第二轮携带同一 `session_id` 继续提问
   已确认 Gemini CLI 能基于同一 `session_id` 恢复上下文，并返回 `SESSION_ONE`
4. tool_use/tool_result 验证：
   通过真实 prompt 触发 Gemini CLI 使用 `read_file`
   已确认原始 `stream-json` 事件包含：
   - `tool_use.tool_name`
   - `tool_use.tool_id`
   - `tool_use.parameters`
   - `tool_result.tool_id`
   - `tool_result.status`
   - `tool_result.output`

基于这轮真实工具事件样本，已完成以下修正：

1. `tools/gemini-agent-worker/src/gemini/event-mapper.ts`
   `tool_use` 事件优先映射真实 `parameters`，避免把整条原始 JSON 当工具输入
2. `tools/gemini-agent-worker/src/gemini/event-mapper.ts`
   `tool_use_id` 支持从 `tool_id` 提取
3. `tools/gemini-agent-worker/src/gemini/event-mapper.ts`
   `tool_result` 事件支持真实 `output/status/tool_id`
4. `tools/gemini-agent-worker/src/gemini/cli-wrapper.ts`
   新增 `tool_use_id -> tool_name` 关联回填，保证 `tool_result.tool` 与前序 `tool_use.tool` 保持一致

### 2. 未完成验证

以下验证尚未完成：

1. 尚未验证前端创建任务到真实流回放的完整路径

### 3. 本轮新增真实 Worker API 验证

本轮已直接启动 `tools/gemini-agent-worker` 进程并做真实 HTTP/SSE 测试。

启动参数：

```powershell
GEMINI_WORKER_PORT=3052
GEMINI_WORKER_HOST=127.0.0.1
GEMINI_WORKER_NAME=gemini-worker-test
GEMINI_ALLOWED_CWDS=D:\foggy-projects\Foggy-Navigator-wt-qd-win11-dev
GEMINI_DEFAULT_MODEL=gemini-2.5-flash-lite
```

健康检查：

```powershell
GET http://127.0.0.1:3052/health
```

结果：

1. `status=ok`
2. `gemini_cli_available=true`
3. `gemini_auth_configured=true`
4. `gemini_auth_mode=local_login`

真实接口验证一：最小 prompt

```powershell
POST /api/v1/query
{
  "prompt": "Respond with exactly OK",
  "cwd": "D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev",
  "model": "gemini-2.5-flash-lite"
}
```

SSE 结果：

1. 收到 `assistant_text`
2. 收到 `result`
3. `result.content/result = "OK"`
4. `session_id / model / input_tokens / output_tokens / duration_ms` 全部存在

真实接口验证二：工具调用

```powershell
POST /api/v1/query
{
  "prompt": "Use tools to inspect the file tools/gemini-agent-worker/package.json in the current workspace, then reply with only the package name and version.",
  "cwd": "D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev",
  "model": "gemini-2.5-flash-lite"
}
```

SSE 结果：

1. 收到 `tool_use`
2. 收到 `tool_result`
3. `tool_use.tool = read_file`
4. `tool_result.tool = read_file`
5. `tool_use_id` 前后一致
6. 最终收到 `assistant_text` 与 `result`

事件落盘验证：

1. `tools/gemini-agent-worker/logs/events/*.jsonl` 已正确写入
2. 工具调用任务的落盘顺序为：
   - `tool_use`
   - `tool_result`
   - `assistant_text`
   - `assistant_text`
   - `result`

### 4. 本轮新增 alias 模型映射设计与实现

本轮补了一层 Gemini 模型 alias 设计，目标是让上游 Java / 前端不再依赖具体版本号。

当前实现策略：

1. 上游可以继续传稳定别名，例如：
   - `gemini-pro`
   - `gemini-flash`
   - `gemini-flash-lite`
2. `tools/gemini-agent-worker` 在运行时本地完成 alias -> 实际 CLI 模型名解析
3. `GEMINI_DEFAULT_MODEL` 现在也支持配置 alias，而不是必须写死版本号
4. 如需调整别名映射，可通过 `GEMINI_MODEL_ALIASES` 环境变量覆盖

当前内置默认映射：

1. `gemini-pro -> gemini-2.5-pro`
2. `gemini-flash -> gemini-2.5-flash`
3. `gemini-flash-lite -> gemini-2.5-flash-lite`
4. `pro -> gemini-2.5-pro`
5. `flash -> gemini-2.5-flash`
6. `flash-lite -> gemini-2.5-flash-lite`
7. `latest-pro -> gemini-2.5-pro`
8. `latest-flash -> gemini-2.5-flash`
9. `latest-flash-lite -> gemini-2.5-flash-lite`

本轮代码落点：

1. `tools/gemini-agent-worker/src/config.ts`
   新增 `modelAliases` 配置解析，支持 `GEMINI_MODEL_ALIASES`
2. `tools/gemini-agent-worker/src/gemini/model-alias.ts`
   新增 alias 解析入口
3. `tools/gemini-agent-worker/src/routes/query.ts`
   query 请求在进入 CLI 前先完成 alias 解析
4. `tools/gemini-agent-worker/src/routes/health.ts`
   health 返回 `default_model` 与 `model_aliases`
5. `packages/navigator-frontend/src/utils/llmModelOptions.ts`
   前端 Gemini 模型选项改为展示稳定 alias，而不是具体版本号

设计判断：

1. 版本升级时，优先修改 worker 的 alias 映射，不要求 Java 调度层改模型名
2. 这样可以把“最新可用 Gemini 版本”的决策收敛到 worker/CLI 侧
3. 若后续接入动态模型目录，也可继续保留 alias 作为平台稳定契约

### 5. 本轮新增 session-module Gemini 路由测试

本轮继续把验证往统一调度层上推，先锁定 `session-module -> gemini-worker` 的 direct route 与 resume 契约。

新增测试落点：

1. `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java`

新增覆盖点：

1. `createTask_usesDirectProviderRouteWhenModelConfigTargetsGemini`
   验证 `modelConfig.workerBackend=GEMINI_CLI` 时，会直接路由到 `gemini-worker`
2. `resumeTask_routesGeminiSessionResumeToProvider`
   验证 `providerType=gemini-worker` 的 resume 请求会走 provider resume 分支
3. `resumeTask_prefersSessionBoundGeminiProviderTypeOverLegacyModelConfigLookup`
   验证 session 已绑定 `gemini-worker` 时，即使前端仍携带旧的 `cfg-codex`，也会优先使用 session 绑定 provider，并在 normalize 阶段清空冲突的 `modelConfigId`

本轮额外确认的契约结论：

1. Gemini resume 的 provider 解析优先级仍然以 `session.providerType` 为准
2. 当 session 已绑定 `gemini-worker`，而请求里携带 `cfg-codex` 这类跨 provider 的旧 `modelConfigId` 时，`normalizeResumeRequest(...)` 应静默清空该字段，而不是把请求误导回 Codex
3. 这条规则与现有 Codex/Claude 的 resume 规范保持一致，属于统一调度层行为，不应下沉到 Gemini provider 自己兜底

## 阻塞项

### 1. 仍缺整链路人工联调

影响：

1. 还未确认前端 -> Java addon -> Node worker -> Gemini CLI 的整链路交互展示
2. API key / Vertex AI 认证模式差异还未覆盖

### 1.1 3052 端口冲突已定位并绕开

本轮继续联调时，发现此前的 `ModelNotFoundError` 不是 Gemini alias 逻辑本身失效，而是 Gemini Worker 实际没有跑在预期端口上。

定位结果：

1. `http://127.0.0.1:3052/health` 实际返回的是旧工作区的 `codex-worker-3052`
2. 这导致前端与 Java addon 虽然走的是 Gemini provider，但实际请求被打到了错误的 worker
3. 因此此前观测到的 health 字段缺失、alias 不生效、`gemini-flash` 直传 CLI 等现象，本质上都是“请求到了错误服务”

本轮处理：

1. 按用户要求放弃 `3052`
2. 将当前工作区 `tools/gemini-agent-worker` 启动到 `3061`
3. 将 `本机测试` Worker 的 `Gemini Base URL` 更新为 `http://127.0.0.1:3061`
4. 将 `tools/gemini-agent-worker/src/config.ts` 的默认端口从 `3052` 调整为 `3061`
5. 将 `SettingsView.vue` 中 `Gemini Base URL` 占位提示从 `3052` 调整为 `3061`

新的本机启动参数：

```powershell
GEMINI_WORKER_PORT=3061
GEMINI_WORKER_HOST=127.0.0.1
GEMINI_WORKER_NAME=gemini-worker-test
GEMINI_ALLOWED_CWDS=D:\foggy-projects\Foggy-Navigator-wt-qd-win11-dev
GEMINI_DEFAULT_MODEL=gemini-flash-lite
```

新的健康检查结果：

```powershell
GET http://127.0.0.1:3061/health
```

结果：

1. `status=ok`
2. `gemini_cli_available=true`
3. `gemini_auth_configured=true`
4. `gemini_auth_mode=local_login`
5. `default_model=gemini-2.5-flash-lite`
6. `model_aliases` 已正确返回默认 alias 映射

新的 alias 验证：

```powershell
POST /api/v1/query
{
  "prompt": "只回复 OK",
  "cwd": "D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev",
  "model": "gemini-flash"
}
```

结果：

1. `assistant_text = "OK"`
2. `result.content/result = "OK"`
3. `result.model = gemini-2.5-flash`

这说明当前 `gemini-flash -> gemini-2.5-flash` 的 worker 侧 alias 映射已实际生效。

### 1.2 前端到 Gemini CLI 的整链路验证已完成

本轮继续完成了此前缺失的整链路人工联调，路径为：

1. 前端 `Workers` 页面
2. Java addon / unified dispatch
3. `gemini-worker`
4. 本机 `Gemini CLI`

联调动作：

1. 在前端保持 `API = gemini订阅`
2. 模型选择 `Gemini Flash`
3. 发起真实任务：`只回复 GEMINI_WORKER_OK`

联调结果：

1. Playwright 页面状态由失败变为 `COMPLETED`
2. 页面显示 `任务已完成`
3. Gemini worker 事件落盘已写出：
   - `assistant_text = GEMINI_WORKER_OK`
   - `result.content/result = GEMINI_WORKER_OK`
   - `model = gemini-2.5-flash`

关键证据文件：

1. `tools/gemini-agent-worker/logs/events/f1e51ddb-5599-4b0a-8d77-fcaca3d57da6.jsonl`
2. `gemini-after-rerun-3061.yml`
3. `gemini-task-detail-3061.yml`

因此当前可以确认：

1. 前端 -> Java addon -> Gemini worker -> Gemini CLI 已真实打通
2. Gemini alias 模式在整链路中生效
3. 本轮主线目标的最小可用验证已完成

### 2. 配置模型仍是增量式扩展

当前 `ClaudeWorkerEntity` 上新增了 `GeminiConfig`，这是为了快速落地 MVP。

影响：

1. 短期可用
2. 长期如果继续接更多 CLI，需要把 Worker 配置抽为更通用的 backend config 结构

### 3. Node worker 本地依赖已补齐

当前 `tools/gemini-agent-worker` 已完成 `npm install`，`npm run typecheck` 已通过。

### 4. 前端设置页仍缺 Gemini 配置入口

本轮改为用 Playwright 做真实页面联调后，发现当前整链路最大的缺口不在后端，而在前端配置入口。

发现的问题：

1. `LLM 配置` 表格虽已有 `GEMINI_CLI` 类型，但 UI 标签未显示 `Gemini`
2. `添加 AI 模型` 弹窗在 `编程` 类别下缺少 `Gemini CLI` backend 选项
3. `Gemini` 的 alias 模型选择、订阅模式说明、可用模型勾选未暴露
4. `Claude Workers` 编辑弹窗未提供 `Gemini Base URL / Token / 默认模型`
5. `ClaudeWorkerView.vue` 的 `providerTypeFromWorkerBackend(...)` 未映射 `GEMINI_CLI -> gemini-worker`

本轮已完成修正：

1. `packages/navigator-frontend/src/views/SettingsView.vue`
   - 新增 `Gemini` 后端标签
   - `编程` 模型新增 `Gemini CLI` 选项
   - 增加 Gemini alias 模型下拉与可用模型勾选
   - 增加 Gemini 订阅模式说明与占位提示
   - Worker 编辑弹窗新增 Gemini worker 配置项
   - Worker 保存逻辑新增 `geminiConfig` 组装
2. `packages/navigator-frontend/src/api/claudeWorker.ts`
   - `registerWorker(...)` / `updateWorker(...)` 增加 `geminiConfig`
3. `packages/navigator-frontend/src/views/ClaudeWorkerView.vue`
   - 增加 `GEMINI_CLI -> gemini-worker` provider 映射

Playwright 复测结果：

1. `设置 -> LLM 配置 -> + 添加`
   - 选择 `模型类别=编程` 后，已可见 `Gemini CLI`
2. 选中 `Gemini CLI` 后，已可见：
   - Gemini alias 模型下拉
   - Gemini 订阅模式说明
   - Gemini 可用模型勾选项
   - Gemini 订阅模式的 Base URL / API Key 占位提示
3. `设置 -> Claude Workers -> 编辑 Worker`
   - 已可见 `Gemini Base URL / Gemini Token / Gemini 默认模型`

## 代码触点

本轮主要新增或修改：

1. `tools/gemini-agent-worker/`
2. `addons/gemini-worker-agent/`
3. `navigator-common`
4. `navigator-spi`
5. `session-module`
6. `metadata-config-module`
7. `packages/navigator-frontend`
8. `docs/version-tracker/1.3.0-SNAPSHOT/`

## 自检结论

### 已满足

1. 已完成版本化规划文档落盘
2. 已完成独立 `GeminiWorker` 代码路径实现
3. 已完成统一调度最小接入
4. 已完成编译级验证
5. 已完成真实 CLI 的 `prompt / stream / abort / resume / tool_use / tool_result` 验证
6. 已补 Java 侧 `GeminiStreamRelayTest`
7. 已补 `session-module` 的 Gemini direct route / resume route 契约测试
8. 已补前端 Settings/Worker 页面 Gemini 配置入口，并通过 Playwright 复测

### 尚未满足

1. 未完成前端创建 Gemini 模型配置并触发真实任务的整链路联调

## 下一步建议

等待你们验证新 worker 能力后，下一轮建议按这个顺序继续：

1. 在前端真实创建 Gemini 模型配置并绑定 Worker
2. 跑前端到 Java addon 到 Node worker 的完整任务创建与流式回放联调
3. 视整链路结果补 `GeminiTaskService` 侧测试
4. 再决定是否要把 Claude/Codex/Gemini 的 worker config 做统一抽象

## 本轮新增测试

新增测试文件：

1. `addons/gemini-worker-agent/src/test/java/com/foggy/navigator/gemini/worker/service/GeminiStreamRelayTest.java`
2. `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java`

覆盖点：

1. `result` 事件在只依赖 `result` 字段时，仍能正确发布 `SESSION_END` 并调用 `completeTask(...)`
2. `tool_result` 事件会映射为 `TOOL_CALL_RESULT`，并保持 `toolCallId / toolName / success`
3. `handleSseEvent(...)` 可从 `event.data.geminiSessionId` 回填会话 ID，并同步 `recordWorkerProgress(...)`
4. `GEMINI_CLI` modelConfig 会直接路由到 `gemini-worker`
5. Gemini resume 会优先走 session 绑定的 providerType
6. Gemini resume 遇到跨 provider 的旧 `modelConfigId` 时会在 normalize 阶段静默清空

测试命令：

```powershell
mvn -pl addons/gemini-worker-agent -am "-Dtest=GeminiStreamRelayTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
mvn -pl session-module -am "-Dtest=TaskDispatchFacadeTest#createTask_usesDirectProviderRouteWhenModelConfigTargetsGemini+resumeTask_routesGeminiSessionResumeToProvider+resumeTask_prefersSessionBoundGeminiProviderTypeOverLegacyModelConfigLookup" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：

1. `GeminiStreamRelayTest` 3 个测试全部通过
2. `TaskDispatchFacadeTest` 中新增的 3 个 Gemini 路由测试全部通过

## 2026-04-24 补充收口：3061 启停脚本与文档固化

### 本轮目标

将 Gemini worker 从“手工启动成功”收口为“仓库内有明确启停入口”，并把 `3061` 的默认约定固化到项目文档。

### 本轮实现

新增脚本：

1. `tools/gemini-agent-worker/start.ps1`
2. `tools/gemini-agent-worker/stop.ps1`

脚本行为：

1. 默认端口使用 `3061`
2. 支持从 `tools/gemini-agent-worker/.env` 读取 `GEMINI_WORKER_PORT / GEMINI_WORKER_HOST / GEMINI_WORKER_NAME`
3. `start.ps1` 会自动检查 `node_modules`
4. 若 `3061` 已被当前 Gemini worker 占用，会先清理旧 Gemini 进程再启动
5. 若 `3061` 被非 Gemini 进程占用，会直接失败并打印冲突进程信息，避免误杀其他服务

文档同步：

1. `CLAUDE.md` 新增 `tools/gemini-agent-worker/start.ps1`
2. `CLAUDE.md` 新增 `tools/gemini-agent-worker/stop.ps1`
3. `CLAUDE.md` 明确标注：当前 `Gemini Worker` 与 `LangGraph Biz Worker` 文档默认端口都为 `3061`，不能同时占用，若要并存需要在 `.env` 中调整其一

### 风险与结论

当前 Gemini 主线已收口到“脚本可启动 + 平台可联调”的状态，但仓库文档层仍存在一个显式冲突：

1. 用户已要求 Gemini 固定到 `3061`
2. `langgraph-biz-worker` 现有脚本和 README 也默认使用 `3061`

本轮没有擅自修改 LangGraph 端口，只在 Gemini 脚本和总文档里把冲突暴露出来，并让 Gemini 启动脚本在端口冲突时安全失败。后续如要让两者长期共存，需要单独决定 LangGraph 端口迁移方案。

## 2026-04-24 补充收口：Gemini 默认端口改为 3071

### 调整原因

1. `LangGraph Biz Worker` 的既有默认端口是 `3061`
2. 为避免两个 worker 默认端口冲突，按用户确认将 `Gemini Worker` 默认端口调整为 `3071`

### 本轮修改

1. `tools/gemini-agent-worker/src/config.ts`
   - Gemini 默认端口从 `3061` 改为 `3071`
2. `tools/gemini-agent-worker/start.ps1`
   - 默认端口改为 `3071`
   - 端口冲突提示同步改为 `3071`
3. `tools/gemini-agent-worker/stop.ps1`
   - 默认端口改为 `3071`
4. `packages/navigator-frontend/src/views/SettingsView.vue`
   - `Gemini Base URL` 占位提示改为 `http://192.168.1.100:3071`
5. `CLAUDE.md`
   - 启动脚本表格中 Gemini Worker 端口改为 `3071`
   - 删除与 LangGraph 的 `3061` 冲突提示
6. `addons/gemini-worker-agent/src/test/java/com/foggy/navigator/gemini/worker/service/GeminiTaskServiceAuthResolutionTest.java`
   - 测试夹具 URL 从 `3071` 对齐到新的默认端口

### 下一步

在 `3071` 上重新验证 Gemini worker 启停与健康检查，然后继续做 skill / agent 实调用验证。

## 2026-04-24 补充验证：Gemini skill / agent 实调用

### 1. 3071 端口重新验证

已完成以下验证：

1. `tools/gemini-agent-worker/src/config.ts` 默认端口为 `3071`
2. `tools/gemini-agent-worker/start.ps1` / `stop.ps1` 已按 `3071` 生效
3. `GET http://127.0.0.1:3071/health` 返回正常
4. `GeminiTaskServiceAuthResolutionTest` 已改用 `http://127.0.0.1:3071`，并通过：

```powershell
mvn -pl addons/gemini-worker-agent -am "-Dtest=GeminiTaskServiceAuthResolutionTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

### 2. 最小 skill 验证样本

为避免直接拿复杂 skill 做首轮验证，新增了一个最小 smoke skill：

1. `.claude/skills/gemini-link-smoke/SKILL.md`

内容约束：

1. 被调用时只允许输出 `GEMINI_SKILL_LINK_OK`
2. 不允许额外文本
3. 不允许调用工具

同时在 `GEMINI.md` 中补了一条上下文提示：

1. 当明确要求验证 workspace agent link 时，优先尝试使用本地 `gemini-link-smoke`

### 3. 直接 Gemini CLI 验证结果

先用 CLI 直接验证 skill 发现：

```powershell
cmd /c "set GEMINI_CLI_TRUST_WORKSPACE=true&& gemini skills list"
```

结果：

1. 项目级 skills 可被 Gemini 发现
2. 输出中已出现 `gemini-link-smoke [Enabled]`
3. 位置为 `D:\foggy-projects\Foggy-Navigator-wt-qd-win11-dev\.agents\skills\gemini-link-smoke\SKILL.md`

再用 headless 直接验证激活：

```powershell
gemini -p "You are verifying workspace agent linkage. If you can access the local agent named gemini-link-smoke, use it. Otherwise say NO_SKILL. Return only the final answer." --output-format stream-json --yolo --skip-trust
```

结果：

1. 出现 `tool_use = activate_skill`
2. `parameters.name = gemini-link-smoke`
3. `tool_result` 明确返回已从 `D:\foggy-projects\Foggy-Navigator-wt-qd-win11-dev\.agents\skills\gemini-link-smoke` 加载
4. 最终输出包含 `GEMINI_SKILL_LINK_OK`

结论：

1. Gemini 的项目级 agent/skill 发现链路是通的
2. `.gemini/agents -> .claude/skills` 的项目链接在真实 CLI 下有效

### 4. 通过 Gemini worker 的 HTTP/SSE 验证结果

首轮通过 worker 直接调用时，若显式关闭 `skip_trust`：

```powershell
POST http://127.0.0.1:3071/api/v1/query
{
  "prompt": "...",
  "cwd": "D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev",
  "model": "gemini-flash-lite",
  "skip_trust": false
}
```

结果：

1. Gemini CLI 报 trusted folder 错误
2. 说明在 headless worker 场景下，若不额外注入信任环境，不能直接依赖项目目录已被交互式 trust

针对这个问题，本轮补充了 worker 入参：

1. `tools/gemini-agent-worker/src/models.ts` 增加 `skip_trust?: boolean`
2. `tools/gemini-agent-worker/src/validation/query.ts` 增加 `skip_trust` 校验
3. `tools/gemini-agent-worker/src/routes/query.ts` 将 `skip_trust` 透传到 CLI wrapper
4. `tools/gemini-agent-worker/src/gemini/cli-wrapper.ts` 改为按 `skip_trust` 决定是否追加 `--skip-trust`

随后通过 worker 再做一次真实请求，这次额外注入：

```powershell
env_vars = { GEMINI_CLI_TRUST_WORKSPACE = "true" }
```

结果：

1. worker SSE 正常出现 `tool_use = activate_skill`
2. `tool_result` 成功回传 skill 激活结果
3. 最终 `assistant_text` 与 `result` 中都包含 `GEMINI_SKILL_LINK_OK`
4. 事件落盘文件：
   - `tools/gemini-agent-worker/logs/events/3a3f93ed-2602-448d-92bc-1fc7ef39fa55.jsonl`

### 5. 当前观察

已确认的能力：

1. Gemini CLI 可以发现项目级 `.gemini/agents`
2. `.gemini/agents` 链到 `.claude/skills` 后，Gemini 可以真实激活 skill
3. Gemini worker 已能把 `activate_skill` 映射为统一 `tool_use / tool_result` 事件

仍需后续观察的差异：

1. Gemini 虽然激活了 skill，但不会像我们期望那样严格遵守“只输出最终词”的约束，仍会先解释再输出
2. 这说明“skill 可发现/可激活”已经成立，但“行为严格等价于 Claude/Codex skill”尚未成立
3. 在 worker/headless 场景下，要使用项目级 skills，当前还需要注入 `GEMINI_CLI_TRUST_WORKSPACE=true`；否则 trusted folder 机制会拦截


## 2026-04-24 补充验证：平台整链路 skill 调用

### 1. 前提环境

本轮联调时，以下服务均为可用状态：

1. 前端 `http://127.0.0.1:5174`
2. launcher `http://127.0.0.1:8112/actuator/health`
3. Claude worker `http://127.0.0.1:3031/health`
4. Gemini worker `http://127.0.0.1:3071/health`

### 2. Playwright 平台联调结果

通过前端设置页先将 `本机测试` Worker 的 `Gemini Base URL` 改为：

1. `http://127.0.0.1:3071`

随后在主界面 `Foggy Navigator` 工作目录下，选择：

1. API：`gemini订阅`
2. 模型：`Gemini Flash`
3. Prompt：`You are verifying workspace agent linkage. If you can access the local agent named gemini-link-smoke, use it. Otherwise say NO_SKILL. Return only the final answer.`

结果：

1. 平台任务创建成功
2. 前端历史会话状态显示 `COMPLETED`
3. 主界面显示 `任务已完成`
4. Playwright 证据快照：
   - `packages/navigator-frontend/.playwright-cli/page-2026-04-24T12-09-50-331Z.yml`

### 3. Gemini worker 事件证据

紧接着检查 Gemini worker 最新事件落盘：

1. `tools/gemini-agent-worker/logs/events/c526e47a-1ed4-4a76-b0a6-fdd8c589af09.jsonl`

实际事件序列为：

1. `tool_use = activate_skill`
2. `tool_result` 明确加载 `D:oggy-projects\Foggy-Navigator-wt-qd-win11-dev\.agents\skills\gemini-link-smoke\SKILL.md`
3. `assistant_text = GEMINI_SKILL_LINK_OK`
4. `result = GEMINI_SKILL_LINK_OK`
5. 最终模型为 `gemini-2.5-flash`

结论：

1. 前端 -> Java addon -> Gemini worker -> Gemini CLI 全链路已经真实触发 skill 激活
2. 平台整链路最终结果已稳定返回 `GEMINI_SKILL_LINK_OK`
3. `.gemini/agents -> .claude/skills` 的项目级链接在平台整链路下同样成立


## 2026-04-24 补充验证：真实业务 skill 激活样本

### 1. 选择真实业务 skill

在完成 `gemini-link-smoke` 后，本轮改用已有业务 skill：

1. `file-browser-dev`

选择它的原因：

1. 这是仓库里已有的真实开发 skill，不是专门为 Gemini 写的 smoke 指令
2. skill 内容明确涉及 `.foggy-ignore`、Python Worker、Java 代理层、Vue 前端三层协作，适合验证 Gemini 对真实技能说明的发现与激活

### 2. 直接通过 Gemini worker 调用结果

本轮直接请求：

```powershell
POST http://127.0.0.1:3071/api/v1/query
{
  "prompt": "你在验证真实 workspace skill 调用。若本地 skill file-browser-dev 可用，请先激活它，再用中文回答两句：第一句只写 FILE_BROWSER_SKILL_OK；第二句说明 .foggy-ignore 是在哪一层管理与透传的。",
  "cwd": "D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev",
  "model": "gemini-flash-lite"
}
```

SSE 实际返回：

1. `tool_use = activate_skill`
2. `input.name = file-browser-dev`
3. `tool_result` 明确从 `D:\foggy-projects\Foggy-Navigator-wt-qd-win11-dev\.agents\skills\file-browser-dev` 加载成功
4. 最终 `result` 返回 `FILE_BROWSER_SKILL_OK`，并补充了 `.foggy-ignore` 的三层透传说明

结论：

1. Gemini 不只会激活 smoke skill，也能激活仓库里的真实业务 skill
2. 项目级 `.gemini/agents -> .claude/skills` 对真实业务 skill 同样成立
3. Gemini 的行为仍然是“激活 skill 后自行组织回答”，而不是严格只复述 skill 内容

### 3. 当前观察

1. `file-browser-dev` 激活成功，说明 Gemini 对已有业务 skill 的发现没有限定在专门命名的 smoke skill 上
2. 但 `session-module` 这类更偏复杂阅读型的 skill，本轮未观察到稳定 `activate_skill`，需要后续在 prompt 约束和技能格式上继续看
3. 因此当前更稳妥的结论是：Gemini 对“结构清晰、指令明确”的 skill 激活效果已经成立，对复杂技能的稳定触发仍需继续实践观察
