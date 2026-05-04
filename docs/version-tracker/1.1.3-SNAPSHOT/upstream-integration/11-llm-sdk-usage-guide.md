# LLM SDK 使用指南

## 文档作用

- doc_type: llm-operation-guide
- version: 1.1.3-SNAPSHOT
- status: draft
- date: 2026-05-04
- intended_for: upstream-llm-coding-agent
- purpose: 指导上游项目中的 LLM coding agent 在本机开发阶段使用 Navigator SDK、前端组件和 REST 兜底能力完成接入

## LLM 执行原则

你是上游项目里的 LLM coding agent。接入 Navigator 时按以下顺序决策：

1. **优先使用 SDK / 组件**：后端优先使用 `navigator-open-sdk`，前端优先使用 `@foggy/chat` 或 `@foggy/navigator-chat-widget`。
2. **不得编造 SDK 方法**：先检查 SDK 源码，确认方法存在后再调用。
3. **SDK 未封装时才使用 REST**：Business Agent 控制面能力当前多数仍需 REST 兜底，REST 路径以 [08-rest-api-reference.md](./08-rest-api-reference.md) 为准。
4. **不得暴露内部凭据**：任何 token、secret、`task_scoped_token`、`adapterConfigJson`、`manifestJson` 都不能写入前端代码、LLM prompt、日志、测试快照或业务库明文字段。
5. **Worker Gateway 是内部 API**：上游前端和上游后端都不要直接调用 `/internal/worker-gateway/v1/**`。

## 本机安装 SDK

在 Navigator 工作区本机开发阶段，先把 SDK 安装到本地 Maven 仓库：

```powershell
cd D:\foggy-projects\Foggy-Navigator-wt-qd-win11-dev
mvn -pl navigator-open-sdk -am install -DskipTests
```

上游 Java 项目引入：

```xml
<dependency>
  <groupId>com.foggy.navigator</groupId>
  <artifactId>navigator-open-sdk</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

如果上游项目不是 Maven 项目，先确认其构建工具是否支持本地 Maven 仓库解析；不要复制 SDK 源码到上游项目。

## 使用 SDK 前必须检查

先在 Navigator 工作区检查当前 SDK 能力：

```powershell
rg -n "class .*Api|public .*\\(" navigator-open-sdk/src/main/java/com/foggy/navigator/sdk
Get-Content navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/NavigatorClient.java
```

当前已确认存在：

| 能力 | SDK 入口 |
| --- | --- |
| 第三方系统注册 | `NavigatorClient.register(...)` |
| Worker 管理 | `client.workers()` |
| 目录环境变量 | `client.directories()` |
| 员工 provision | `client.employees()` |
| 普通 Agent 任务、轮询、消息、会话 | `client.agents()` |

当前尚未封装，需 REST 兜底：

| Business Agent 能力 | 当前做法 |
| --- | --- |
| ClientApp / Credential | REST |
| Skill / User Grant / Model Grant | REST |
| BusinessObject / BusinessFunction / Function Grant | REST |
| Business Task | REST |
| Approval Resume | REST |

## 后端接入流程

### 1. 初始化 SDK Client

```java
NavigatorClient client = NavigatorClient.builder()
    .baseUrl(navigatorBaseUrl)
    .apiKey(navigatorApiKey)
    .timeout(Duration.ofSeconds(30))
    .build();
```

### 2. 优先使用已有 SDK 能力

普通 Agent 任务示例：

```java
AgentTask task = client.agents().ask(agentId, "分析订单异常");
AgentTask done = client.agents().pollUntilDone(agentId, task.getTaskId(), Duration.ofMinutes(5));
```

### 3. Business Agent 走 REST 兜底

当需要创建 ClientApp、注册 BusinessObject/Function、授权 Skill 或创建 Business Task 时，使用 REST 控制面。

调用 REST 前必须：

- 从 controller 或 [08-rest-api-reference.md](./08-rest-api-reference.md) 核对路径。
- 从 `business-agent-module/src/main/java/com/foggy/navigator/business/agent/model/form/` 核对请求字段。
- 不把 REST 请求写到前端。
- 不把控制面 token 写入日志。

## 前端接入流程

### `@foggy/chat`

用于深度定制聊天 UI。它提供 `ChatPanel`、消息列表、工具调用展示和内置审批渲染。

```typescript
import { ChatPanel, useChatStore } from '@foggy/chat'
```

前端通过上游 BFF 或平台公开会话 API 交互，不直接调用 Navigator 内部 API。

### `@foggy/navigator-chat-widget`

用于快速集成完整对话组件。

```typescript
import { NavigatorChat } from '@foggy/navigator-chat-widget'
```

浏览器端不要配置 Navigator admin/provisioning/runtime credential。推荐通过 cookie 或自定义 `fetch` 与上游 BFF 会话绑定。

## 安全红线

LLM 必须拒绝以下实现：

- 在前端代码中保存 admin token、provisioning credential、runtime credential、App Secret。
- 在 LLM 工具参数、prompt 或 schema 中加入 `task_scoped_token`。
- 从上游前端直接调用 `/internal/worker-gateway/v1/**`。
- 在 Worker schema 响应、前端状态或日志中展示 `adapterConfigJson` / `manifestJson`。
- 绕过 `upstream_ref` 直接让 LLM 或前端提供 REST URL。
- 在审批 resume 中省略 binding context 或 input hash。

## 推荐验证

完成接入代码后运行：

```powershell
mvn test
rg -n "task_scoped_token|adapterConfigJson|manifestJson|/internal/worker-gateway" src
rg -n "NavigatorClient|@foggy/chat|@foggy/navigator-chat-widget" src
```

如果是前端项目，还应运行对应构建命令，例如：

```powershell
pnpm test
pnpm build
```

## 交付说明

上游 LLM 完成接入后，应报告：

- 是否使用了 `navigator-open-sdk`，如果没有，说明原因。
- 哪些 Business Agent 能力因 SDK 未封装而使用 REST 兜底。
- 前端是否通过 BFF 或公开会话 API 交互。
- 是否确认没有暴露内部 token、secret、Worker Gateway、`adapterConfigJson`、`manifestJson`。
- 运行过的测试和检查命令。
