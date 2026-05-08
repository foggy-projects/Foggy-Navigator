# 上游 REST API 到业务函数注册与 Skill 能力暴露契约

## 元信息

- doc_type: architecture-plan
- version: 1.1.3-SNAPSHOT
- status: draft
- date: 2026-05-02
- intended_for: platform-owner | upstream-business-owner | java-service-owner | worker-owner | skill-owner | execution-agent | reviewer
- scope: 上游业务系统接入 Navigator Java 服务后，如何将 REST API 转换为内部业务函数注册，并通过 Skill 告诉 LLM 上游业务能力

## 核心结论

上游业务系统不直接调用 LangGraph Biz Worker，Worker 也不作为业务实现方。Navigator 侧以 `client_app_id` 作为上游接入、授权、审计和路由的最小隔离单元；对于上游自身的多租户模型，Navigator 不透传其层级结构，而是要求上游一个租户注册为一个 Client App。

标准交互方向固定为：

```text
上游业务系统
  -> Navigator Java 服务
      -> LangGraph Biz Worker
```

Navigator Java 服务是业务集成边界与控制平面，负责上游鉴权、业务函数注册、REST 适配、权限、审批、审计、幂等和错误归一化。LangGraph Biz Worker 是执行平面，负责基于用户自然语言、Skill、脚本和标准工具进行推理与编排。

Client App 与 Biz Worker 不是一对一关系。Java 服务可以根据 App Grant、Skill 作用域、Business Function scope、Worker 类型、版本、负载、灰度和故障转移策略，将请求分配到合适的 Biz Worker Pool，再选择具体 Worker 实例。

上游 REST API 不能被原样作为 curl 或裸 HTTP 指令暴露给 LLM。它们需要先被转换为受控的 Business Function Manifest，再由 Java 侧注册为内部函数。Skill 只向 LLM 暴露业务语义、函数摘要、约束、示例和审批规则，不暴露上游凭证、内部 URL 或任意 HTTP 调用能力。

## 架构链路

```text
Client App
  -> Navigator Java Business Integration API
      -> Client App Registry / Grant / Runtime Credential
      -> Business Function Registry
      -> REST Adapter / Credential / Permission / Audit / Approval
      -> Task / Session / Suspension Store
      -> Worker Routing / Biz Worker Pool
          -> LangGraph Biz Worker Instance
              -> Root Planner
              -> Skill Runtime
              -> FSScript / Script Engine
              -> Standard Tools
                  -> call_business_function(function_id, input)
                      -> Navigator Java Worker Gateway
                          -> Registered Function Executor
                              -> Upstream REST API
```

这条链路保留三个边界：

1. Client App 边界：只需要理解 Navigator Java 暴露的业务 Agent API、回调协议和能力注册协作要求。
2. Java 控制边界：所有真实业务动作都经由 Java 侧执行、审计和授权。
3. Worker 执行边界：Worker 只能调用 Java 提供的标准工具网关，不能越过 Java 直接访问上游业务 REST。

## Client App 与身份授权

上游接入首版按 App 维度建模：

```text
client_app
  -> upstream_user
```

约束：

1. `client_app_id` 是上游接入、运行时凭证、Skill、函数授权、审批策略、callback 配置和审计的主键。
2. `upstream_user_id` 只在当前 App 内唯一，不能直接等同于 Navigator 内部 `user_id`。
3. Java 必须将 `client_app_id + upstream_user_id` 映射为 `navigator_effective_user_id` 或 App 服务账号，再创建 task/session。
4. 正式业务集成不直接复用 Sharing Key 作为完整授权模型；Sharing Key 只作为轻量外部入口和 operation allowlist 的历史参考。
5. 上游创建 App 需要 provisioning credential；App 运行时调用使用 runtime credential。二者必须分离。

Worker 接收的是 Java 已校验的 task scoped context，不能直接信任上游用户声明，也不能获得 App secret、upstream credential 或上游 REST 地址。

## 上游 REST 到内部函数的转换

上游系统提供 REST API 后，Navigator 不直接把 endpoint 注册给 LLM，而是与上游共同整理为业务函数。
业务函数（Business Function）可以选择归属于某个业务对象（BusinessObject），从而形成对象和函数的组织关系，但 BusinessObject 本身不是授权主体。授权控制仍然只在 ClientApp、upstreamUser、Skill 和 Function 层面进行。

### 上游需要提供

- API 文档或 OpenAPI 描述。
- API 的业务语义、适用场景和禁止场景。
- 请求参数、响应结构、错误码、重试语义和幂等语义。
- 鉴权方式、租户隔离方式、操作者身份传递方式。
- 权限要求、数据脱敏要求和审计字段。
- 风险等级：只读、草稿、状态变更、外部副作用、删除或高危操作。
- 是否需要用户确认、审批单、确认码或二次校验。
- 沙箱环境、测试凭证和样例请求/响应。

### Navigator Java 需要产出

- Business Function Manifest。
- REST Adapter 映射：path/query/body/header/auth/response/error。
- Function Registry 记录：版本、状态、租户/账号范围、Skill allowlist。
- Worker Gateway API：供 Worker 查询 schema、调用函数、运行脚本、恢复暂停。
- 审批与 suspension 记录：由 Java 持有最终确认状态。
- 合约测试与验收样例。

### Worker / Skill 需要产出

- Skill 能力说明：业务领域、触发意图、可用函数、边界、审批规则。
- 示例脚本或示例流程：让 LLM 知道如何组合只读查询、草稿创建和提交动作。
- 函数 allowlist：Skill 可用哪些业务函数，默认不允许 Skill 调用全量函数库。
- Planner 约束：禁止裸 HTTP、禁止自行伪造审批、禁止绕过 Java 侧确认。

## Business Function Manifest 草案

```yaml
function_id: tms.order.close_apply.submit
version: v1
display_name: 提交关单申请
domain: order
description_for_llm: 提交已创建的订单关单申请。调用前必须确认订单号、申请号和提交理由。
risk_level: state_change
transport:
  type: rest
  method: POST
  path: /api/orders/{orderId}/close-applications/{applicationId}/submit
adapter:
  path_params:
    orderId: $.input.order_id
    applicationId: $.input.application_id
  headers:
    X-App-Id: $.context.client_app_id
    X-Operator-Id: $.context.upstream_user_id
  body: {}
input_schema:
  type: object
  required:
    - order_id
    - application_id
  properties:
    order_id:
      type: string
      description: 订单号
    application_id:
      type: string
      description: 关单申请 ID
output_schema:
  type: object
  properties:
    status:
      type: string
    submitted_at:
      type: string
auth_scope: order:close_apply:submit
approval_policy:
  required: true
  mode: user_confirm_code
  confirmation_text: 提交订单关单申请
idempotency:
  required: true
  key_template: close_apply_submit:${client_app_id}:${application_id}
audit:
  business_keys:
    - order_id
    - application_id
  redact_fields: []
exposure:
  root_planner: false
  script_engine: true
  skill_allowlist_required: true
```

关键约束：

- `description_for_llm` 描述业务能力，不描述底层 REST 调用细节。
- `transport` 和 `adapter` 仅 Java 侧可见，不能进入普通 LLM 上下文。
- `risk_level` 和 `approval_policy` 决定调用前是否必须暂停并等待上游用户或 Navigator 审批人确认。
- `exposure.root_planner=false` 表示该函数不直接成为根 LLM 工具。
- `skill_allowlist_required=true` 表示必须通过具体 Skill 授权后才能调用。

## Java 与 Worker 的内部标准工具

Worker 不直接注册 N 个业务 API。Worker 只注册稳定的平台标准工具，由这些工具访问 Java Worker Gateway。

建议工具集合：

- `list_business_functions(domain?, intent?)`：查询当前任务、Client App、upstream user、Skill 可见的业务函数摘要。
- `get_business_function_schema(function_id, version?)`：获取单个函数的输入输出 schema、风险等级和审批要求。
- `invoke_business_function(function_id, input, idempotency_key?)`：调用 Java 侧注册函数，由 Java 执行权限、审批、审计和上游 REST 适配。
- `run_business_script(script, context, dry_run?)`：执行受控 FSScript/Compose Script 业务编排。
- `resume_suspension(suspend_id, approval_result)`：在用户确认或审批返回后恢复挂起流程。

这些工具是 Worker 的平台能力，不等同于上游业务工具。业务工具的增减应该主要发生在 Java Function Registry 和 Skill allowlist 中。

## Java Worker Gateway API 草案

面向 Worker 的内部 API 需要 task/session scoped token，不能复用用户浏览器 token，也不能暴露给上游系统直接调用。

```text
GET  /api/v1/worker/business-functions?domain=&intent=
GET  /api/v1/worker/business-functions/{functionId}/schema
POST /api/v1/worker/business-functions/{functionId}/invoke
POST /api/v1/worker/business-scripts/run
POST /api/v1/worker/suspensions/{suspendId}/resume
```

调用上下文建议统一包含：

```json
{
  "task_id": "lgt_xxx",
  "session_id": "worker-session-id",
  "skill_id": "order-close-apply",
  "script_run_id": "run_xxx",
  "client_app_id": "app_tms_tenant_a",
  "upstream_user_id": "u_001",
  "navigator_effective_user_id": "svc_app_tms_tenant_a",
  "navigator_tenant_id": "nav-tenant-a",
  "worker_pool_id": "bwp_order_default",
  "idempotency_key": "close_apply_submit:app_tms_tenant_a:app-001"
}
```

Java 侧必须基于该上下文做 App 凭证校验、upstream user 授权、函数可见性校验、审计落库、Worker Pool 路由和上游凭证注入。

## 上游与 Java 的外部协作 API 草案

上游业务系统只需要接入 Java 服务，不需要知道 Worker 的端口、会话实现或内部工具。

```text
POST /api/v1/business-agent/tasks
GET  /api/v1/business-agent/tasks/{taskId}
POST /api/v1/business-agent/tasks/{taskId}/messages
POST /api/v1/business-agent/approvals/{approvalId}/confirm
POST /api/v1/business-agent/callbacks/register
```

业务能力注册可以走管理 API 或离线配置导入：

```text
POST /api/v1/business-integrations
POST /api/v1/business-functions
PUT  /api/v1/business-functions/{functionId}/versions/{version}
POST /api/v1/business-functions/{functionId}/test
```

这组 API 的目标是让上游完成三类动作：

1. 发起自然语言业务任务。
2. 查询任务和审批状态。
3. 使用 provisioning credential 创建或初始化 Client App。
4. 与 Navigator 团队协作注册或验证业务函数。

## Skill 如何告诉 LLM 上游能力

Skill/SKILL.md 是 LLM 理解上游能力的主要入口，但它只描述业务语义和使用规则，不描述底层 REST。

示例：

```markdown
# 订单关单申请助手

Use this skill when the user asks to create, inspect, or submit order close applications.

Available business capabilities:
- `tms.order.get`: 查询订单详情，只读。
- `tms.order.close_apply.draft`: 创建关单申请草稿，低风险。
- `tms.order.close_apply.submit`: 提交关单申请，状态变更，必须等待用户确认码。

Rules:
- Do not call raw HTTP, curl, or upstream REST directly.
- Use `list_business_functions` and `get_business_function_schema` when function inputs are unclear.
- Use `run_business_script` for multi-step business flows.
- Before submit, summarize order_id, application_id, close reason, and expected effect.
- Never approve a state-changing operation by yourself.
```

Skill 需要包含：

- 触发意图：什么自然语言需求应该进入该 Skill。
- 能力目录：函数 ID、业务用途、风险等级、是否审批。
- 数据探查策略：优先通过 FSScript/DSL 查询确认对象，而不是猜参数。
- 编排示例：从查询到草稿再到提交的典型脚本。
- 禁止事项：裸 HTTP、绕过确认、构造未授权字段、越权访问其他租户数据。

1.1.3 只考虑三类 Skill 来源：

1. `account_skill`：Navigator 内部用户个人维护的技能。
2. `client_app_skill`：Client App 自己维护、只在 App 作用域内可见的技能。
3. `builtin_public_skill`：Navigator Java / Biz Worker 侧提供的平台内置公共技能。

`role_skill` 本版本延期。Client App 不能新增、修改或发布平台公共技能，只能维护自己的 App 作用域 Skill，并由 Java 授权给该 App 下的 upstream user 使用。

## 审批与暂停恢复

副作用操作不能由 LLM 单方面决定完成。Java 是审批和确认码的所有者，Worker 只负责把需要确认的业务摘要传回并暂停执行。

当函数需要审批时，`invoke_business_function` 可以返回：

```json
{
  "status": "suspended",
  "suspend_id": "sus_001",
  "approval_required": {
    "title": "提交关单申请",
    "summary": {
      "order_id": "ORD-001",
      "application_id": "APP-001",
      "effect": "订单将进入关单申请审批流程"
    },
    "confirmation_required": true,
    "expires_at": "2026-05-02T18:00:00+08:00"
  }
}
```

恢复路径：

```text
用户或上游系统确认
  -> Navigator Java approval API
      -> Java 更新 suspension/approval 状态
      -> Worker resume_suspension
          -> Script Engine / Skill Runtime 继续执行
```

确认码、审批人、权限校验和最终允许执行的判断必须在 Java 侧完成。

## 职责边界

| 角色 | 职责 |
| --- | --- |
| 上游业务系统 | 提供 REST 契约、业务语义、权限要求、风险分级、沙箱和样例 |
| Navigator Java 服务 | 承载 Client App Registry、Function Registry、REST Adapter、凭证、权限、审批、审计、幂等、Worker Pool 路由和错误归一化 |
| LangGraph Biz Worker | 执行自然语言推理、Skill 路由、脚本编排、标准工具调用与暂停恢复；不直接持有 Client App 凭证 |
| Skill Owner | 维护 LLM 可读的能力说明、函数 allowlist、示例脚本和禁止事项 |
| 前端/UI | 展示任务、消息、审批卡片、暂停状态和恢复结果 |

## 下一步决策

1. 是否将 Business Function Registry 首版落在 Java DB 表，还是先使用版本化 YAML/JSON 配置导入。
2. Java Worker Gateway 的鉴权方式：worker identity token、task scoped token 或二者组合。
3. Function Manifest 的必填字段和版本兼容规则。
4. Skill allowlist 是写入 SKILL.md、独立 manifest，还是由 Java Registry 动态注入。
5. 审批确认码由 Navigator Java 内置实现，还是优先对接上游已有审批系统。
6. FSScript 业务函数门面由 Worker 内存态注册，还是由 Java Registry 动态下发。
7. Client App provisioning credential 和 runtime credential 的首版形态。
8. Biz Worker Pool 首版落 DB 表，还是作为现有用户绑定 Worker 之上的逻辑配置层。

## 当前验收口径

- status: draft
- verified: no
- implementation_required: yes
- primary_risk: 如果直接把上游 REST 或 curl 暴露给 LLM，会绕过 Java 侧权限、审批、审计与租户隔离
- acceptance_target: 形成 Java Function Registry、Worker Gateway、Skill 能力文档模板和一个订单关单申请样例闭环
