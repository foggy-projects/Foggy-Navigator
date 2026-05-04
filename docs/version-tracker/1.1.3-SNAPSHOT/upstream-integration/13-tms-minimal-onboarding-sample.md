# TMS 最小接入样例

## 文档作用

- doc_type: integration-sample
- version: 1.1.3-SNAPSHOT
- status: draft
- date: 2026-05-04
- intended_for: upstream-llm-coding-agent | upstream-backend-developer | navigator-reviewer
- purpose: 给 TMS 类上游系统提供一条最小 Business Agent 接入样例，覆盖 SDK 初始化、业务函数注册、task 创建、REST Adapter 调用与字段安全边界

## 适用边界

TMS 不作为 Biz Worker。TMS 只提供受控 HTTP API，Navigator 负责 ClientApp、Skill、User、Function、Model 授权、Worker Gateway、审批和平台审计。

本样例面向开发联调阶段：

1. 上游用户 token 由上游后端通过 SDK 提交到 `grantUpstreamUserAccess`。
2. Navigator 服务端在调用 TMS REST API 时注入 token header。
3. token 不进入 LLM prompt、tool schema、前端状态、Manifest header 或普通日志。
4. 运单对 LLM 可见的唯一标识是 `orderIdentifier`，类型为 `string`。

## Maven 本地 SDK

在 Navigator 工作区先安装 SDK：

```powershell
cd D:\foggy-projects\Foggy-Navigator-wt-qd-win11-dev
mvn install -pl navigator-open-sdk -am -DskipTests
```

上游项目引入：

```xml
<dependency>
  <groupId>com.foggy.navigator</groupId>
  <artifactId>navigator-open-sdk</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## 上游配置

Navigator 侧需要为 TMS upstream ref 配置受控调用目标和 token header：

```properties
foggy.navigator.business.agent.upstreams.tms.url=http://localhost:8080
foggy.navigator.business.agent.upstreams.tms.user-token-header=X-TMS-Agent-Token
```

TMS API 会收到 Navigator 服务端注入的 header：

```http
X-TMS-Agent-Token: <tms-user-token>
X-Navigator-Tenant-Id: <tenantId>
X-Navigator-Client-App-Id: <clientAppId>
X-Navigator-Upstream-User-Id: <upstreamUserId>
X-Navigator-Task-Id: <taskId>
X-Navigator-Session-Id: <sessionId>
X-Navigator-Function-Id: <functionId>
X-Navigator-Function-Version: <version>
```

Manifest 不允许声明或覆盖这些受控 header，也不允许声明 `Authorization`。

## SDK 初始化样例

```java
NavigatorClient client = NavigatorClient.builder()
        .baseUrl("http://localhost:8080")
        .apiKey("tenant-admin-api-key")
        .build();

// 1. 注册业务对象
CreateBusinessObjectForm objectForm = new CreateBusinessObjectForm();
objectForm.setObjectId("tms_order");
objectForm.setDomain("tms.order");
objectForm.setName("TMS Order");
objectForm.setStatus("ENABLED");
client.businessAgent().createBusinessObject(objectForm);

// 2. 注册业务函数
ImportBusinessFunctionManifestForm functionForm = new ImportBusinessFunctionManifestForm();
functionForm.setFunctionId("tms.order.submit");
functionForm.setBusinessObjectId("tms_order");
functionForm.setVersion("v1");
functionForm.setDomain("tms.order");
functionForm.setName("Submit TMS Order");
functionForm.setRiskLevel("state_change");
functionForm.setApprovalRequired(false);
functionForm.setIdempotencyRequired(true);
functionForm.setStatus("ENABLED");
functionForm.setInputSchemaJson("""
    {
      "type": "object",
      "required": ["orderIdentifier"],
      "properties": {
        "orderIdentifier": { "type": "string" },
        "reason": { "type": "string" }
      }
    }
    """);
functionForm.setOutputSchemaJson("""
    {
      "type": "object",
      "properties": {
        "orderIdentifier": { "type": "string" },
        "result": { "type": "string" }
      }
    }
    """);
functionForm.setLlmVisibleSummary("Submit a TMS order by orderIdentifier.");
functionForm.setSchemaVisibleSummary("orderIdentifier: string");
functionForm.setManifestJson("""
    {"function_id":"tms.order.submit","input":{"orderIdentifier":"string"}}
    """);
functionForm.setAdapterConfigJson("""
    {
      "type": "rest",
      "upstream_ref": "tms",
      "method": "POST",
      "path": "/api/orders",
      "adapter": {
        "body": {
          "orderIdentifier": "$.input.orderIdentifier",
          "reason": "$.input.reason"
        }
      }
    }
    """);
client.businessAgent().importBusinessFunctionManifest(functionForm);

// 3. 绑定 Skill / Function / User
AddFunctionToSkillForm allow = new AddFunctionToSkillForm();
allow.setFunctionId("tms.order.submit");
allow.setStatus("ENABLED");
client.businessAgent().addFunctionToSkillAllowlist("tms_skill", allow);

GrantBusinessFunctionForm functionGrant = new GrantBusinessFunctionForm();
functionGrant.setFunctionId("tms.order.submit");
functionGrant.setVersion("v1");
functionGrant.setStatus("ENABLED");
client.businessAgent().grantFunctionToClientApp("app-tms", functionGrant);

GrantSkillToClientAppForm skillGrant = new GrantSkillToClientAppForm();
skillGrant.setSkillId("tms_skill");
skillGrant.setStatus("ENABLED");
client.businessAgent().grantSkillToClientApp("app-tms", skillGrant);

GrantUpstreamUserForm userGrant = new GrantUpstreamUserForm();
userGrant.setUpstreamUserId("tms-user-001");
userGrant.setUpstreamUserToken("tms-user-token-secret");
userGrant.setStatus("ENABLED");
client.businessAgent().grantUpstreamUserAccess("app-tms", userGrant);

// 4. 创建 Business Task
CreateBusinessAgentTaskForm taskForm = new CreateBusinessAgentTaskForm();
taskForm.setClientAppId("app-tms");
taskForm.setSessionId("session-tms-001");
taskForm.setUpstreamUserId("tms-user-001");
taskForm.setSkillId("tms_skill");
taskForm.setWorkerPoolId("langgraph-biz-pool");
CreatedBusinessAgentTaskDTO task = client.businessAgent().createBusinessAgentTask(taskForm);
```

## 字段红线

`orderIdentifier` 是 TMS 运单对用户和 LLM 可见的业务编号。它固定表示 TMS 业务运单号，类型必须是 `string`。

不要在以下位置暴露内部主键：

```text
expressOrderId
```

禁止位置：

1. LLM-facing schema。
2. BusinessFunction input schema。
3. 前端可填参数。
4. `llmVisibleSummary` / `schemaVisibleSummary`。
5. 测试快照和示例 prompt。

`expressOrderId` 只能留在 TMS 内部服务中，用于内部关联、详情回读、支付主体等受控场景。

## 验证证据

Stage 10C 已补充两类自动化证据：

1. `BusinessAgentApiSmokeTest.testTmsOnboardingSequence_usesOrderIdentifierAndCreatesTask`
   - 验证 SDK 能发出 TMS 初始化、函数注册、授权和 task 创建请求。
   - 验证 function import payload 中包含 `orderIdentifier`，不包含内部主键。
2. `RestAdapterUpstreamE2ETest.tmsRestAdapter_e2e_usesOrderIdentifier_injectsHeaders_and_writesAudit`
   - 启动真实本地 HTTP mock TMS 服务。
   - 通过 Worker Gateway invoke 触发 REST Adapter。
   - 验证 mock TMS 收到 `X-TMS-Agent-Token` 和 Navigator context headers。
   - 验证 schema、request body、response 不包含内部主键。
