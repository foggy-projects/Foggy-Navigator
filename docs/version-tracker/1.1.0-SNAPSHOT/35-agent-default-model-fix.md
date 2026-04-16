# 35 - Agent 默认模型修复与即时响应补全

## 状态

**已修复，已完成本地联调验证**

## 问题说明

本项问题实际分成两层：

1. **默认模型解析链路**
   - 当请求没有显式传 `model` 时，平台需要按优先级从 Agent 默认模型或 LLM 配置中推断出最终模型名，再下发给 Worker。

2. **统一任务创建即时响应**
   - 即使 Worker 侧最终拿到了正确模型，`POST /api/v1/tasks` 的即时响应仍可能返回 `model = null`。
   - 这个残留问题**不只影响旧数据**，也影响**新创建任务**。
   - 具体来说，影响的是 **A2A 路由的新任务创建响应**，而不是任务落库后的最终详情读取。

## 根因

### 1. 默认模型名未完整透传

原始问题是 Java 侧虽然能解析 `defaultModelConfigId`，但如果请求里没有显式 `model`，就没有把最终模型名稳定下发给 Worker。

期望优先级应为：

1. 请求显式传入的 `model`
2. `CodingAgentEntity.defaultModel`
3. `LlmModelConfigEntity.modelName`
4. 仍为空时交给 Python Worker 默认逻辑

### 2. A2A 即时响应未携带解析后的模型

修复了 Worker 下发链路后，又发现 unified create 的即时响应仍有残留：

- `ClaudeWorkerInnerA2aAgent.sendTask(...)` 创建了真实任务，但返回给 A2A 的 metadata 里没有 `model` / `modelConfigId`
- `TaskDispatchFacade.toDispatchDTO(...)` 又只从 request 回填 `model`
- 如果请求本身没有显式 `model`，即时响应就会是 `null`

因此：

- **新任务立即返回值** 可能是空
- **后续 GET /api/v1/tasks/{taskId}** 又能读到正确值

## 实际修复

### 后端

1. `ClaudeTaskService` 在 create / resume 路径中：
   - 解析 `effectiveModel`
   - 若实体 `model` 为空，则立即持久化 `effectiveModel`
   - 再发布状态更新，保证后续任务详情和会话侧读取能拿到正确模型

2. `ClaudeWorkerInnerA2aAgent`：
   - 将 `workerTaskId`
   - `model`
   - `modelConfigId`
   写入 A2A task metadata

3. `TaskDispatchFacade.toDispatchDTO(...)`：
   - 优先从 A2A metadata 读取 `model` / `modelConfigId`
   - 只有 metadata 没值时才回退到 request

4. 新增回归测试：
   - 验证 A2A create 即时响应优先使用 metadata 中的 `model` / `modelConfigId`

### 前端

`ClaudeWorkerView.vue` 已确认：

1. 注册 Agent 弹窗包含：
   - `默认 LLM 配置`
   - `默认模型`

2. 编辑 Agent 弹窗包含：
   - `默认 LLM 配置`
   - `默认模型`

3. 注册与更新请求都会提交：
   - `defaultModelConfigId`
   - `defaultModel`

## 变更文件

### 后端

- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/adapter/ClaudeWorkerInnerA2aAgent.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java`

### 前端

- `packages/navigator-frontend/src/api/codingAgent.ts`
- `packages/navigator-frontend/src/composables/useCodingAgent.ts`
- `packages/navigator-frontend/src/views/ClaudeWorkerView.vue`

## 验证结果

### 服务状态

已重启并确认：

- `http://localhost:8112/actuator/health` => `UP`
- `http://localhost:3031/health` => Worker 健康，`claude_cli_available = true`
- `http://127.0.0.1:5174` => 前端可访问

### 后端接口验证

使用已有 Claude Worker Agent：

- `agentId = 6844c32e-728`
- `defaultModelConfigId = f3d3c2dd-a0fd-4112-bca6-05c754a27238`
- `defaultModel = null`

在**不显式传 task model** 的情况下调用：

- `POST /api/v1/tasks`

本地验证结果：

- 新任务 `taskId = 20260416-12d4`
- 即时响应已返回：
  - `model = "glm4.7"`
  - `modelConfigId = "f3d3c2dd-a0fd-4112-bca6-05c754a27238"`

这说明：

- 新建任务即时响应中的 `model` 已补齐
- 残留问题已不再出现在这条路径

### 前端页面验证

使用 `playwright-cli` 在 `http://127.0.0.1:5174` 登录并进入 Coding Agents 页面后，页面文本中已确认出现：

- `编辑 Agent`
- `默认 LLM 配置`
- `默认模型`

浏览器控制台唯一错误为：

- `favicon.ico 404`

与本功能无关。

### 构建 / 测试

- 前端：`npm run build` 成功
- 后端：`mvn -pl addons/claude-worker-agent -am -DskipTests compile` 成功
- 定向单测：`session-module` 单独执行时受本地 SNAPSHOT 依赖缺失影响，未能直接跑通

## 结论

本问题当前可认为已完成修复并完成本地验证。

需要特别说明：

1. “残留问题是否只影响旧数据”的答案是否定的。
   - 在修复前，它也影响**新建任务**，但主要体现在 **A2A create 的即时响应**。

2. 当前实现下：
   - Worker 下发模型正确
   - 任务详情持久化正确
   - 即时创建响应也已补齐 `model`
   - 前端注册 / 编辑弹窗都已支持 `默认模型`

3. 如果后续需要处理历史存量数据中的空 `model` 记录，应单独做一次数据回填，不属于本次功能修复必需项。
