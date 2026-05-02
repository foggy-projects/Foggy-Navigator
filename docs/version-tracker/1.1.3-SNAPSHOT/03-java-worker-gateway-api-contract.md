# Java Worker Gateway API 契约

## 文档作用

- doc_type: api-contract
- version: 1.1.3-SNAPSHOT
- status: draft
- date: 2026-05-02
- intended_for: java-service-owner | worker-owner | platform-owner | security-reviewer | execution-agent
- purpose: 定义 LangGraph Biz Worker 调用 Navigator Java Business Function Registry 的内部 API、鉴权上下文、租户隔离、审计和错误模型

## 核心边界

Worker 只能通过 Navigator Java Worker Gateway 调用业务函数。Worker 不能直接访问上游业务 REST，不能持有上游 credential，不能根据 Skill 文本自行构造 curl、HTTP path 或任意网络请求。

```text
LangGraph Biz Worker
  -> Navigator Java Worker Gateway
      -> Business Function Registry
      -> Permission / Approval / Audit / Idempotency
      -> REST Adapter
          -> Upstream Business System
```

Java 服务是业务执行控制面，Worker 是自然语言推理、Skill 路由、脚本编排和暂停恢复执行面。

## API 总览

首版内部能力映射为 5 个稳定工具/API：

| Worker 工具名 | Java API | 用途 |
| --- | --- | --- |
| `list_business_functions` | `GET /internal/worker-gateway/v1/business-functions` | 查询当前 task/session/skill 可见函数摘要 |
| `get_business_function_schema` | `GET /internal/worker-gateway/v1/business-functions/{functionId}/schema` | 获取脱敏 schema、风险等级和审批摘要 |
| `invoke_business_function` | `POST /internal/worker-gateway/v1/business-functions/{functionId}/invoke` | 调用 Java 注册函数或触发 suspension |
| `run_business_script` | `POST /internal/worker-gateway/v1/business-scripts/run` | 请求 Java 记录并调度受控脚本运行上下文 |
| `resume_suspension` | `POST /internal/worker-gateway/v1/suspensions/{suspendId}/resume` | 审批完成后恢复 Worker/脚本暂停点 |

这组 API 仅供受信 Worker 调用，不对上游业务系统、浏览器或普通插件开放。

## 鉴权模型

首版建议使用二者组合：

1. `worker identity token`：证明调用方是已注册的 LangGraph Biz Worker。
2. `task scoped token`：证明本次调用绑定到某个 Java 创建或授权的 task/session，带有租户、用户、Skill 和过期时间。

请求头建议：

```text
Authorization: Bearer <worker_identity_token>
X-Task-Scoped-Token: <task_scoped_token>
X-Worker-Id: langgraph-biz-wsl
X-Worker-Provider: langgraph-biz-worker
X-Request-Id: req_xxx
```

校验顺序：

1. 校验 worker identity 是否为已注册 Worker。
2. 校验 task scoped token 未过期、未撤销，且绑定当前 `task_id`。
3. 校验 `tenant_id`、`user_id`、`session_id` 与 Java task/session 记录一致。
4. 校验当前 `skill_id` 是否允许访问目标函数。
5. 校验用户和租户是否拥有函数 `auth_scope`。
6. 对副作用函数执行审批、幂等和审计拦截。

## 统一调用上下文

所有 POST 请求体都应包含 `context`。GET 请求通过 query/header 或 token 解析得到同等上下文。

```json
{
  "context": {
    "task_id": "lgt_001",
    "session_id": "worker-session-001",
    "skill_id": "order-close-apply",
    "script_run_id": "sr_001",
    "tenant_id": "tenant-a",
    "user_id": "user-a",
    "source_system": "tms",
    "request_id": "req_001",
    "idempotency_key": "close_apply_submit:tenant-a:APP-001"
  }
}
```

约束：

1. `tenant_id` 和 `user_id` 不能只信请求体，Java 必须与 token/session 绑定值比对。
2. `skill_id` 是函数可见性校验输入，不等同于最终授权。
3. `idempotency_key` 可由 Worker 提供候选值，但最终以 Java 生成和校验为准。
4. `script_run_id` 可为空；非脚本场景由 Java 记录为空或生成内部引用。

## `list_business_functions`

### 请求

```text
GET /internal/worker-gateway/v1/business-functions?domain=order&intent=close_apply
```

可选 query：

| 参数 | 说明 |
| --- | --- |
| `domain` | 业务域过滤 |
| `intent` | 自然语言意图提示，仅用于候选过滤 |
| `risk_level` | 风险等级过滤 |
| `skill_id` | 当前 Skill，优先从 token/context 解析 |

### 响应

```json
{
  "functions": [
    {
      "function_id": "tms.order.close_apply.submit",
      "version": "v1",
      "domain": "order",
      "display_name": "提交关单申请",
      "description_for_llm": "提交已创建的订单关单申请，调用前必须确认订单号、申请号和提交理由。",
      "risk_level": "state_change",
      "approval_required": true,
      "schema_ref": "/internal/worker-gateway/v1/business-functions/tms.order.close_apply.submit/schema?version=v1"
    }
  ]
}
```

约束：

1. 只返回当前 Worker、task、tenant、user、skill 可见的函数摘要。
2. 不返回 `transport`、`adapter`、`auth_scope`、上游 path、header 或 credential。
3. 不触发任何业务动作。

## `get_business_function_schema`

### 请求

```text
GET /internal/worker-gateway/v1/business-functions/{functionId}/schema?version=v1
```

### 响应

```json
{
  "function_id": "tms.order.close_apply.submit",
  "version": "v1",
  "domain": "order",
  "description_for_llm": "提交已创建的订单关单申请。调用前必须确认订单号、申请号和提交理由。",
  "risk_level": "state_change",
  "input_schema": {
    "type": "object",
    "required": ["order_id", "application_id", "submit_reason"],
    "properties": {
      "order_id": { "type": "string", "description": "订单号" },
      "application_id": { "type": "string", "description": "关单申请 ID" },
      "submit_reason": { "type": "string", "description": "提交原因摘要", "maxLength": 300 }
    }
  },
  "output_schema": {
    "type": "object",
    "properties": {
      "status": { "type": "string" },
      "application_id": { "type": "string" },
      "submitted_at": { "type": "string" }
    }
  },
  "approval_policy_summary": {
    "required": true,
    "mode": "user_confirm_code",
    "confirmation_text": "提交订单关单申请"
  }
}
```

约束：

1. Schema 是 LLM 可见的裁剪版，不包含 Java-only 字段。
2. 返回前必须再次做 Skill allowlist 和租户隔离校验。
3. 对不可见函数返回 `auth/not-visible`，不要泄露函数是否存在于其他租户。

## `invoke_business_function`

### 请求

```text
POST /internal/worker-gateway/v1/business-functions/{functionId}/invoke
```

```json
{
  "version": "v1",
  "input": {
    "order_id": "ORD-001",
    "application_id": "APP-001",
    "submit_reason": "客户取消运输需求"
  },
  "context": {
    "task_id": "lgt_001",
    "session_id": "worker-session-001",
    "skill_id": "order-close-apply",
    "script_run_id": "sr_001",
    "tenant_id": "tenant-a",
    "user_id": "user-a",
    "idempotency_key": "close_apply_submit:tenant-a:APP-001"
  }
}
```

### 成功响应

```json
{
  "status": "completed",
  "function_id": "tms.order.close_apply.submit",
  "version": "v1",
  "result": {
    "status": "submitted",
    "application_id": "APP-001",
    "submitted_at": "2026-05-02T16:20:00+08:00"
  },
  "audit_ref": "audit_001",
  "idempotency_key": "close_apply_submit:tenant-a:APP-001"
}
```

### 暂停响应

```json
{
  "status": "suspended",
  "function_id": "tms.order.close_apply.submit",
  "version": "v1",
  "suspend_id": "sus_001",
  "approval_id": "ap_001",
  "approval_required": {
    "mode": "user_confirm_code",
    "title": "提交关单申请",
    "summary": {
      "order_id": "ORD-001",
      "application_id": "APP-001",
      "effect": "订单将进入关单申请审批流程"
    },
    "expires_at": "2026-05-02T16:25:00+08:00"
  },
  "audit_ref": "audit_001"
}
```

处理规则：

1. Java 在调用上游前完成 schema 校验、权限校验、allowlist 校验和幂等检查。
2. 如果 `approval_policy.required=true` 且尚无有效审批，Java 返回 `status=suspended`，不调用上游提交接口。
3. Worker 收到 `suspended` 后必须暂停当前脚本或 Skill Frame，不能自行构造 approval result。
4. 审批通过后由 Java resume 原 suspension；Worker 不能通过再次 invoke 绕过确认。

## `run_business_script`

### 请求

```text
POST /internal/worker-gateway/v1/business-scripts/run
```

```json
{
  "script": "const order = biz.order.get({ order_id });\nreturn biz.order.close_apply.submit({ order_id, application_id, submit_reason });",
  "language": "fsscript",
  "dry_run": false,
  "declared_functions": [
    "tms.order.get",
    "tms.order.close_apply.submit"
  ],
  "context": {
    "task_id": "lgt_001",
    "session_id": "worker-session-001",
    "skill_id": "order-close-apply",
    "tenant_id": "tenant-a",
    "user_id": "user-a"
  }
}
```

### 响应

```json
{
  "status": "running",
  "script_run_id": "sr_001",
  "allowed_functions": [
    "tms.order.get",
    "tms.order.close_apply.submit"
  ],
  "audit_ref": "audit_script_001"
}
```

首版定位：

1. Worker 仍负责实际脚本引擎执行和 FSScript suspension bridge。
2. Java 可通过该 API 记录脚本运行、下发函数 allowlist、生成 task scoped token 或建立审计上下文。
3. 如果首版实现不需要 Java 调度脚本，也可以将该 API 收敛为 `validate/register script run context`，但工具名保持稳定。

## `resume_suspension`

### 请求

```text
POST /internal/worker-gateway/v1/suspensions/{suspendId}/resume
```

```json
{
  "approval_result": {
    "approval_id": "ap_001",
    "status": "approved",
    "approved_by": "user-a",
    "approved_at": "2026-05-02T16:22:00+08:00"
  },
  "context": {
    "task_id": "lgt_001",
    "session_id": "worker-session-001",
    "script_run_id": "sr_001",
    "tenant_id": "tenant-a",
    "user_id": "user-a"
  }
}
```

### 响应

```json
{
  "status": "resume_dispatched",
  "suspend_id": "sus_001",
  "script_run_id": "sr_001",
  "resume_ref": "resume_001"
}
```

约束：

1. 该 API 只能由 Java 审批所有者或 Java 内部调度路径触发。
2. Worker 接收 resume 时只能恢复同一 `task_id`、`script_run_id`、`suspend_id` 绑定的暂停点。
3. 拒绝或超时使用同一 API 的 `status=rejected|expired`，Worker 应让脚本进入取消或失败态。

## 租户隔离与权限校验

Java 必须执行：

1. task/session 属于当前租户。
2. Worker 注册关系允许服务该租户。
3. 当前用户对 `auth_scope` 有权限。
4. 当前 Skill 在 `skill_allowlist` 中。
5. 函数版本处于 enabled 状态。
6. 上游 credential 由 Java 根据租户和 source system 注入。
7. 函数输出按租户、用户、Skill 和 exposure 做字段裁剪。

Worker 不能把 `tenant_id`、`user_id`、`auth_scope` 或 approval 状态当作可信事实自行判定。

## 审计要求

每次 list/schema/invoke/script/resume 都应产生审计事件。最低字段：

1. `event_type`
2. `task_id`
3. `session_id`
4. `worker_id`
5. `tenant_id`
6. `user_id`
7. `skill_id`
8. `function_id` 和 `version`
9. `risk_level`
10. `idempotency_key`
11. `approval_id` / `suspend_id`
12. `upstream_trace_id`
13. `result_status`
14. `error_code`

审计日志不得保存上游 credential、确认码明文或未脱敏敏感响应。

## 错误模型

统一错误响应：

```json
{
  "status": "failed",
  "error": {
    "code": "auth/forbidden",
    "message": "当前用户无权提交关单申请",
    "retryable": false,
    "category": "authorization",
    "details_ref": "err_001"
  },
  "audit_ref": "audit_001"
}
```

首版错误码分类：

| 分类 | 示例 code | 说明 |
| --- | --- | --- |
| `auth` | `auth/unauthorized`、`auth/forbidden`、`auth/not-visible` | Worker、用户、租户或 Skill 无权访问 |
| `validation` | `validation/schema-invalid` | 入参不符合 schema |
| `approval` | `approval/required`、`approval/rejected`、`approval/expired` | 审批相关状态 |
| `idempotency` | `idempotency/conflict` | 幂等键冲突或重复提交 |
| `business` | `business/not-found`、`business/conflict` | 上游业务语义错误 |
| `upstream` | `upstream/timeout`、`upstream/unavailable` | 上游调用失败 |
| `system` | `system/internal-error` | Java 或 Worker 内部错误 |

对 Worker 的返回应避免泄露上游内部错误堆栈、SQL、URL、header 或 credential。

## 当前验收口径

- status: draft
- verified: no
- implementation_required: yes
- development_progress: 内部 API 契约已拆出
- testing_progress: 文档自检，未执行代码测试
- experience_progress: N/A，本文不涉及 UI 交互

## 待决策

1. Worker Gateway 鉴权首版是否强制采用 worker identity token + task scoped token 组合。
2. `run_business_script` 是 Java 调度脚本，还是仅建立脚本运行审计上下文。
3. Worker Gateway 路径使用 `/internal/worker-gateway/v1` 还是复用现有 `/api/v1/tasks` 体系下的内部端点。
