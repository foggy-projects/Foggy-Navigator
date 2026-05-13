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
| 业务 Agent 控制面 | `BusinessAgentApi` | ✅ 已有 (1.1.3 新增) | ClientApp管理、授权(Model/Skill/Function)、创建Task、审批Resume |

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

控制面 bootstrap 如果拿到的是 admin/login JWT，而不是 `sk-*` API key，应使用：

```java
NavigatorClient client = NavigatorClient.builder()
    .baseUrl("http://navigator.example.com:8112")
    .adminToken(adminJwt)
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
    .listBusinessAgentSessionsWithClientAppAccessToken(
        10, null, clientAppKey, runtimeAccessToken, upstreamUserId);

// 获取会话完整消息
String sessionCursor = null;
do {
    SessionMessagesPage page = client.agents()
        .getBusinessAgentSessionMessagesWithClientAppAccessToken(
            contextId, 50, sessionCursor, clientAppKey, runtimeAccessToken, upstreamUserId);
    for (SessionMessage msg : page.getMessages()) {
        System.out.println(msg.getRole() + ": " + msg.getContent());
    }
    sessionCursor = page.getNextCursor();
    if (!page.isHasMore()) break;
} while (true);
```

Business Agent 会话读模型按当前 ClientApp runtime token 与 `upstreamUserId` 校验归属，不通过任意 `userId` 查询历史会话。

## Business Agent 能力

在 1.1.3 中，SDK 的 `BusinessAgentApi` 提供了完整的 Business Agent 控制面 API：

```java
// 创建 ClientApp
CreateClientAppForm appForm = new CreateClientAppForm();
appForm.setProvisioningToken(provisioningToken);
appForm.setName("MyTMS");
appForm.setDescription("TMS App");
ClientAppDTO app = client.businessAgent().createClientApp(appForm);

// 授权模型
GrantModelConfigForm modelGrant = new GrantModelConfigForm();
modelGrant.setModelConfigId(modelConfigId);
modelGrant.setIsDefault(true);
client.businessAgent().grantModelConfig(app.getClientAppId(), modelGrant);

// 授权 upstream user，并提交 TMS 侧用户 token。
// 该 token 只保存在 Navigator 服务端授权关系内，不会出现在 LLM schema 或前端 DTO。
GrantUpstreamUserForm userGrant = new GrantUpstreamUserForm();
userGrant.setUpstreamUserId(upstreamUserId);
userGrant.setUpstreamUserToken(tmsUserToken);
userGrant.setStatus("ENABLED");
client.businessAgent().grantUpstreamUserAccess(app.getClientAppId(), userGrant);

// 导入函数清单并授权
ImportBusinessFunctionManifestForm functionManifest = new ImportBusinessFunctionManifestForm();
functionManifest.setFunctionId("tms.create_order");
functionManifest.setVersion("1.0.0");
functionManifest.setDomain("tms");
functionManifest.setName("Create Order");
functionManifest.setRiskLevel("MEDIUM");
functionManifest.setManifestJson(manifestJson);
client.businessAgent().importBusinessFunctionManifest(functionManifest);

GrantBusinessFunctionForm functionGrant = new GrantBusinessFunctionForm();
functionGrant.setFunctionId("tms.create_order");
functionGrant.setVersion("1.0.0");
functionGrant.setStatus("ENABLED");
client.businessAgent().grantFunctionToClientApp(app.getClientAppId(), functionGrant);

// 创建 Business Task
CreateBusinessAgentTaskForm taskForm = new CreateBusinessAgentTaskForm();
taskForm.setClientAppId(app.getClientAppId());
taskForm.setSessionId(sessionId);
taskForm.setUpstreamUserId(upstreamUserId);
taskForm.setSkillId(skillId);
taskForm.setWorkerPoolId(workerPoolId);
CreatedBusinessAgentTaskDTO task = client.businessAgent().createBusinessAgentTask(taskForm);

// 恢复被挂起的审批
WorkerGatewayResumeForm resumeForm = new WorkerGatewayResumeForm();
resumeForm.setApprovalResult(approvalResult);
resumeForm.setBindingContext(bindingContext);
client.businessAgent().resumeSuspension(suspendId, resumeForm);
```

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

- 参考 [10-demo-checklist.md](./10-demo-checklist.md) 了解完整的从零到一接入流程。
