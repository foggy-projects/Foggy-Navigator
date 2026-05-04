# 后端 SDK 快速上手

## 文档作用

- doc_type: integration-guide
- version: 1.1.3-SNAPSHOT
- status: draft
- date: 2026-05-04
- intended_for: upstream-backend-developer
- purpose: 指导上游后端开发者使用 `navigator-open-sdk` 接入 Navigator

## 前提

- 已获取 Navigator 服务地址和 API Key。
- 已安装 JDK 17+。
- 已将 `navigator-open-sdk` 添加为 Maven/Gradle 依赖。

## SDK 现状（1.1.3）

`navigator-open-sdk` 当前已封装以下能力：

| 模块 | 类 | 已有能力 | 说明 |
| --- | --- | --- | --- |
| 系统注册 | `NavigatorClient.register()` | ✅ 已有 | 注册第三方系统，返回 `tenantId` + `apiKey` |
| Worker 管理 | `WorkerApi` | ✅ 已有 | 创建 Worker、健康检查 |
| 目录管理 | `DirectoryApi` | ✅ 已有 | 更新环境变量 |
| 员工管理 | `EmployeeApi` | ✅ 已有 | Provision 员工、绑定 Worker |
| Agent 交互 | `AgentApi` | ✅ 已有 | 发起任务、轮询状态、增量消息、会话管理 |

> **Business Agent 新能力**（1.1.3 新增的 ClientApp、Skill、BusinessFunction、Approval 等控制面 API）**当前 SDK 尚未封装**。这些能力目前需通过 REST 控制面 API 完成，后续 SDK 版本将补齐。

## 快速开始

### 1. 系统注册

```java
import com.foggy.navigator.sdk.NavigatorClient;
import com.foggy.navigator.sdk.model.RegisterResult;

// 注册系统（无需 API Key）
RegisterResult reg = NavigatorClient.register(
    "http://navigator.example.com:8112",
    "TMS",          // 系统名称
    "admin",        // 管理员用户名
    "password123"   // 管理员密码
);

String tenantId = reg.getTenantId();
String apiKey   = reg.getApiKey();
```

### 2. 创建客户端

```java
NavigatorClient client = NavigatorClient.builder()
    .baseUrl("http://navigator.example.com:8112")
    .apiKey(apiKey)
    .timeout(Duration.ofSeconds(30))
    .build();
```

### 3. Worker 和员工 Provisioning

```java
// 创建 Worker
Worker worker = client.workers().create(
    "GPU-01",                    // Worker 名称
    "http://10.0.1.1:3031",     // Worker 地址
    "worker-auth-token"          // 认证 token
);

// Provision 员工
ProvisionResult emp = client.employees().provision(
    "EMP-001",                   // 员工 ID
    "张三",                       // 显示名
    worker.getWorkerId(),        // 绑定的 Worker
    "/workspace/emp-001",        // 工作目录
    "my-project",                // 项目名
    Map.of("CLAUDE.md", "# Project Context")
);
```

### 4. 发起任务

```java
// 发起异步任务
AgentTask task = client.agents().ask(
    emp.getAgentId(),
    "请帮我分析最近三天的销售异常"
);
String taskId    = task.getTaskId();
String contextId = task.getContextId();
```

### 5. 轮询任务状态和增量消息

```java
String cursor = null;

while (true) {
    // 查询任务状态
    AgentTask current = client.agents().getTask(agentId, taskId);

    // 拉取增量消息
    TaskMessagesPage page = client.agents()
        .getTaskMessages(agentId, taskId, 50, cursor);
    for (SessionMessage msg : page.getMessages()) {
        System.out.println(msg.getRole() + ": " + msg.getContent());
    }
    cursor = page.getNextCursor();

    // 任务终态退出
    if (current.isTerminal()) break;

    Thread.sleep(3000);
}
```

### 6. 同步等待（便捷方法）

```java
// 发起任务并同步等待完成
AgentTask result = client.agents().askAndWait(
    agentId, "帮我写一个 REST API", Duration.ofMinutes(5)
);
System.out.println("结果: " + result.getResult());

// 多轮对话（传入上次 contextId）
AgentTask result2 = client.agents().askAndWait(
    agentId, "加个单元测试",
    result.getContextId(), Duration.ofMinutes(5)
);
```

### 7. 会话回放

```java
// 获取会话列表
SessionListPage sessions = client.agents()
    .listSessions(agentId, 10, null);

// 获取会话完整消息
String sessionCursor = null;
do {
    SessionMessagesPage page = client.agents()
        .getSessionMessages(agentId, contextId, 50, sessionCursor);
    for (SessionMessage msg : page.getMessages()) {
        System.out.println(msg.getRole() + ": " + msg.getContent());
    }
    sessionCursor = page.getNextCursor();
    if (!page.isHasMore()) break;
} while (true);
```

## Business Agent 能力（SDK 待补齐）

以下 1.1.3 Business Agent 能力当前 **SDK 尚未封装**，需使用 REST 控制面 API：

| 能力 | REST API 路径 | SDK 状态 |
| --- | --- | --- |
| 创建 Client Application | `POST /api/v1/client-apps` | ❌ SDK 待补齐 |
| 签发 Runtime Credential | `POST /api/v1/client-apps/{id}/runtime-credentials` | ❌ SDK 待补齐 |
| 签发 Provisioning Credential | `POST /api/v1/admin/client-apps/provisioning-credentials` | ❌ SDK 待补齐 |
| 授权 LLM 模型 | `POST /api/v1/client-apps/{id}/model-config-grants` | ❌ SDK 待补齐 |
| 注册 Skill | `POST /api/v1/business-agent/skills` | ❌ SDK 待补齐 |
| 授权 Skill 给 ClientApp | `POST /api/v1/business-agent/client-apps/{id}/skill-grants` | ❌ SDK 待补齐 |
| 授权 upstream user | `POST /api/v1/business-agent/client-apps/{id}/upstream-users` | ❌ SDK 待补齐 |
| 注册 BusinessObject | `POST /api/v1/business-agent/business-objects` | ❌ SDK 待补齐 |
| 导入 BusinessFunction | `POST /api/v1/business-agent/functions/import` | ❌ SDK 待补齐 |
| 授权函数给 ClientApp | `POST /api/v1/business-agent/client-apps/{id}/function-grants` | ❌ SDK 待补齐 |
| 创建 Business Task | `POST /api/v1/business-agent/tasks` | ❌ SDK 待补齐 |
| Resume Suspension | `POST /api/v1/business-agent/suspensions/{suspendId}/resume` | ❌ SDK 待补齐 |

> 上述 REST API 的详细参数请参考 [08-rest-api-reference.md](./08-rest-api-reference.md)。

## 完整 Demo

SDK 提供了一个完整的接入示例：

- 文件位置：`navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/example/UpstreamIntegrationDemo.java`
- 涵盖：系统注册 → 创建客户端 → 发起任务 → 轮询状态与增量消息 → 会话回放

运行方法：

```bash
export NAVIGATOR_BASE_URL=http://localhost:8112
export NAVIGATOR_API_KEY=sk-xxx
export NAVIGATOR_AGENT_ID=your-agent-id

java -cp navigator-open-sdk.jar \
  com.foggy.navigator.sdk.example.UpstreamIntegrationDemo
```

## 下一步

- SDK 后续版本将封装 Business Agent 控制面 API（ClientApp CRUD、Skill/Function/Model Grant、Task、Approval）。
- 当前过渡期请参考 [08-rest-api-reference.md](./08-rest-api-reference.md) 和 [10-demo-checklist.md](./10-demo-checklist.md)。
