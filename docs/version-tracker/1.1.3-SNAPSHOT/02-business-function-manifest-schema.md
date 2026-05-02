# Business Function Manifest 首版字段契约

## 文档作用

- doc_type: contract-design
- version: 1.1.3-SNAPSHOT
- status: draft
- date: 2026-05-02
- intended_for: platform-owner | upstream-business-owner | java-service-owner | worker-owner | skill-owner | execution-agent | reviewer
- purpose: 定义 Navigator Java Business Function Registry 首版 Manifest 字段、可见性分层和订单关单申请样例

## 背景

`1.1.3-SNAPSHOT` 的业务系统接入方向已经确定为：

```text
上游业务系统 -> Navigator Java 服务 -> LangGraph Biz Worker
```

Business Function Manifest 是上游 REST API 进入 Navigator 的受控声明。它不是给 LLM 执行的 HTTP 描述，也不是 Worker 的业务实现代码。Manifest 的核心作用是让 Java 服务可以统一管理业务函数的版本、Upstream App 授权、审批、幂等、审计、适配和 Skill 暴露边界。

## 设计目标

1. 把上游 REST API 转换为 Java 侧可治理的业务函数。
2. 明确哪些字段可以进入 LLM 上下文，哪些字段只能留在 Java。
3. 为 Worker Gateway 提供稳定的 schema、风险等级和审批摘要。
4. 为 Skill allowlist 提供函数可见性边界。
5. 保留底层 transport/adapter 能力，但不把 upstream URL、credential、curl 或任意 HTTP 能力暴露给 LLM。

## 可见性分层

| 可见性 | 含义 | 可进入 LLM 上下文 |
| --- | --- | --- |
| `llm_visible` | 业务语义、参数语义、风险和审批摘要 | 是，需经过裁剪 |
| `schema_visible` | 输入输出 schema，可用于参数抽取和结果解释 | 是，需脱敏和字段级过滤 |
| `registry_control` | 注册状态、Skill allowlist、版本和暴露策略 | 只返回摘要 |
| `java_only` | REST transport、adapter、凭证、header 映射、上游错误映射 | 否 |
| `audit_runtime` | 审计键、操作者、租户、幂等键、审批记录引用 | 否，最多返回摘要 |

默认规则：未明确标为 `llm_visible` 或 `schema_visible` 的字段不得进入普通 LLM prompt、Skill 文本或 Worker retained messages。

## 首版字段

| 字段 | 必填 | 可见性 | 说明 |
| --- | --- | --- | --- |
| `function_id` | 是 | `llm_visible` | 稳定函数标识，建议使用反向域名或业务域层级，如 `tms.order.close_apply.submit` |
| `version` | 是 | `registry_control` | Manifest 版本，首版使用 `v1`、`v2` 等字符串 |
| `domain` | 是 | `llm_visible` | 业务域，如 `order`、`dispatch`、`inventory` |
| `display_name` | 否 | `llm_visible` | 给 UI 和审批卡片展示的中文名 |
| `description_for_llm` | 是 | `llm_visible` | 业务语义说明，不包含 HTTP path、base_url、credential 或 curl 示例 |
| `risk_level` | 是 | `llm_visible` | `readonly`、`draft`、`state_change`、`external_side_effect`、`delete` |
| `transport` | 是 | `java_only` | 底层调用方式，首版至少支持 `rest`，后续可扩展 `rpc`、`mq`、`workflow`、`mcp`、`cli` |
| `adapter` | 是 | `java_only` | Java 侧参数映射、header 注入、响应裁剪、错误映射和重试策略 |
| `input_schema` | 是 | `schema_visible` | JSON Schema，Worker 只能看到脱敏后的业务字段 |
| `output_schema` | 是 | `schema_visible` | JSON Schema，Java 可按 exposure 裁剪返回字段 |
| `auth_scope` | 是 | `java_only` | Java 对 Upstream App、upstream user 和 Navigator effective subject 的权限校验域，不直接暴露给 LLM |
| `approval_policy` | 是 | `llm_visible` + `java_only` | LLM 可见是否需要确认；确认码生成、审批校验和 token 均为 Java-only |
| `idempotency` | 是 | `audit_runtime` | 状态变更和副作用函数必须声明幂等策略 |
| `audit` | 是 | `audit_runtime` | 审计业务键、脱敏字段、Upstream App、upstream user、Navigator effective subject 和 Worker 绑定规则 |
| `exposure` | 是 | `registry_control` | 控制是否可被 root planner、script engine、Skill、Upstream App 使用 |
| `skill_allowlist` | 是 | `registry_control` | 允许哪些 App Scoped Skill 使用该函数，默认空列表等价于不可见 |

## 字段语义

### `function_id`

稳定业务函数 ID，必须满足：

1. 同一租户或同一上游集成下唯一。
2. 不能携带环境、base_url、credential 或临时部署信息。
3. 一旦进入脚本资产或审计记录，不应重命名；需要变更时创建新版本。

### `risk_level`

首版枚举：

| 值 | 含义 | 默认审批 |
| --- | --- | --- |
| `readonly` | 只读查询 | 不需要，但仍需权限和租户校验 |
| `draft` | 创建草稿或预校验，不改变最终业务状态 | 可按业务配置 |
| `state_change` | 改变业务状态，如提交、确认、改派 | 默认需要 |
| `external_side_effect` | 对第三方或外部对象产生影响，如发短信、推送 | 默认需要 |
| `delete` | 删除、作废、不可逆取消 | 必须需要确认码或审批 |

### `transport`

`transport` 描述底层调用方式，只能由 Java 使用。REST 示例：

```yaml
transport:
  type: rest
  method: POST
  upstream_ref: tms-order-service
  path: /api/orders/{orderId}/close-applications/{applicationId}/submit
  timeout_ms: 10000
  retry:
    enabled: false
```

`upstream_ref` 是 Java 配置或注册表中的上游引用，不是 LLM 可见的 URL。

### `adapter`

`adapter` 描述 Java 如何把标准函数输入转换为上游请求，并把响应转换为标准输出。

```yaml
adapter:
  path_params:
    orderId: $.input.order_id
    applicationId: $.input.application_id
  query_params: {}
  headers:
    X-App-Id: $.context.upstream_app_id
    X-Operator-Id: $.context.upstream_user_id
  body:
    submitReason: $.input.submit_reason
  response:
    include:
      - status
      - submitted_at
      - upstream_trace_id
  error_mapping:
    "ORDER_NOT_FOUND": business/not-found
    "NO_PERMISSION": auth/forbidden
```

约束：

1. `adapter` 只能存放在 Java Registry 或 Java 可信配置中。
2. Worker 调用时只提交 `function_id`、`input` 和上下文引用。
3. LLM 不知道上游 path/header/body 的真实映射。

### `input_schema` 和 `output_schema`

Schema 使用 JSON Schema 子集。首版要求：

1. `input_schema.required` 必须完整。
2. 每个 LLM 可见字段必须有业务描述。
3. 敏感字段不得设计为 LLM 输入；必须由 Java 从 task/session/user context 注入。
4. `output_schema` 必须配合 `adapter.response.include` 做返回裁剪。

### `approval_policy`

```yaml
approval_policy:
  required: true
  mode: user_confirm_code
  approval_owner: navigator_java
  confirmation_text_template: 提交订单 ${order_id} 的关单申请 ${application_id}
  expires_in_seconds: 300
  auto_approve: false
```

约束：

1. `approval_owner` 首版固定为 `navigator_java` 或 `upstream_approval_system`。
2. LLM 只能看到 `required`、`mode` 和面向用户的摘要。
3. 确认码、审批 token、审批人权限校验结果均不得进入 LLM 上下文。

### `idempotency`

```yaml
idempotency:
  required: true
  key_template: close_apply_submit:${upstream_app_id}:${application_id}
  conflict_policy: return_existing_result
```

约束：

1. `state_change`、`external_side_effect`、`delete` 必须 `required=true`。
2. Java 负责生成和校验最终幂等键，Worker 可提供候选 `idempotency_key`，但不能作为唯一可信来源。

### `audit`

```yaml
audit:
  business_keys:
    - order_id
    - application_id
  redact_fields:
    - customer_phone
  include_context:
    - upstream_app_id
    - upstream_user_id
    - navigator_effective_user_id
    - navigator_tenant_id
    - task_id
    - session_id
    - skill_id
    - skill_source
    - worker_pool_id
    - script_run_id
```

审计记录必须能回答：哪个 Upstream App、哪个上游用户、映射到哪个 Navigator 有效用户、通过哪个任务、哪个 Skill、哪个函数版本、用什么业务键、经过什么审批、调用了哪个上游结果。

### `exposure` 和 `skill_allowlist`

```yaml
exposure:
  root_planner: false
  script_engine: true
  worker_gateway: true
  skill_allowlist_required: true
  upstream_app_grant_required: true
  output_visibility: summary
skill_allowlist:
  - order-close-apply
```

约束：

1. `root_planner` 首版默认 `false`。
2. `script_engine=true` 只表示可由受控脚本调用，不表示任意 LLM 可直接调用。
3. `skill_allowlist_required=true` 时，Worker 必须带上当前 `skill_id`，Java 侧再次校验。
4. `upstream_app_grant_required=true` 时，Java 必须校验当前 `upstream_app_id` 被授权访问该函数。

## 订单关单申请示例

```yaml
function_id: tms.order.close_apply.submit
version: v1
domain: order
display_name: 提交关单申请
description_for_llm: >
  提交已创建的订单关单申请。调用前必须确认订单号、申请号和提交理由。
  该操作会让订单进入关单审批流程，不能由助手自行确认。
risk_level: state_change
transport:
  type: rest
  method: POST
  upstream_ref: tms-order-service
  path: /api/orders/{orderId}/close-applications/{applicationId}/submit
  timeout_ms: 10000
  retry:
    enabled: false
adapter:
  path_params:
    orderId: $.input.order_id
    applicationId: $.input.application_id
  headers:
    X-App-Id: $.context.upstream_app_id
    X-Operator-Id: $.context.upstream_user_id
    X-Request-Id: $.context.request_id
  body:
    submitReason: $.input.submit_reason
  response:
    include:
      - status
      - submitted_at
      - application_id
      - upstream_trace_id
  error_mapping:
    ORDER_NOT_FOUND: business/not-found
    CLOSE_APPLY_NOT_FOUND: business/not-found
    CLOSE_APPLY_ALREADY_SUBMITTED: business/conflict
    NO_PERMISSION: auth/forbidden
input_schema:
  type: object
  required:
    - order_id
    - application_id
    - submit_reason
  properties:
    order_id:
      type: string
      description: 订单号
    application_id:
      type: string
      description: 关单申请 ID
    submit_reason:
      type: string
      description: 提交关单申请的原因摘要
      maxLength: 300
output_schema:
  type: object
  required:
    - status
    - application_id
  properties:
    status:
      type: string
      enum:
        - submitted
        - already_submitted
    application_id:
      type: string
    submitted_at:
      type: string
      format: date-time
    upstream_trace_id:
      type: string
auth_scope: order:close_apply:submit
approval_policy:
  required: true
  mode: user_confirm_code
  approval_owner: navigator_java
  confirmation_text_template: 提交订单 ${order_id} 的关单申请 ${application_id}
  expires_in_seconds: 300
  auto_approve: false
idempotency:
  required: true
  key_template: close_apply_submit:${upstream_app_id}:${application_id}
  conflict_policy: return_existing_result
audit:
  business_keys:
    - order_id
    - application_id
  redact_fields: []
  include_context:
    - upstream_app_id
    - upstream_user_id
    - navigator_effective_user_id
    - navigator_tenant_id
    - task_id
    - session_id
    - skill_id
    - skill_source
    - worker_pool_id
    - script_run_id
exposure:
  root_planner: false
  script_engine: true
  worker_gateway: true
  skill_allowlist_required: true
  upstream_app_grant_required: true
  output_visibility: summary
skill_allowlist:
  - order-close-apply
```

## 版本兼容规则

1. 同一 `function_id` 的 `input_schema` 增加必填字段必须升级 `version`。
2. `risk_level` 升级为更高风险必须升级 `version` 并重新审核 Skill allowlist。
3. `transport` 或 `adapter` 变更不一定暴露给 Worker，但需要 Java 合约测试重新通过。
4. 删除输出字段需要升级 `version` 或提供兼容字段。
5. Skill 引用函数时必须绑定 `function_id` 和可接受版本范围。

## 当前验收口径

- status: draft
- verified: no
- implementation_required: yes
- development_progress: 文档字段契约已拆出
- testing_progress: 文档自检，未执行代码测试
- experience_progress: N/A，本文不涉及 UI 交互

## 待决策

1. Function Registry 首版使用 Java DB 表，还是使用版本化 YAML/JSON 配置导入。
2. `adapter.error_mapping` 是否需要统一错误码字典先行。
3. `skill_allowlist` 的真实来源是 SKILL.md、独立 manifest，还是 Java Registry 动态注入。
4. Upstream App Grant 首版与 Function Manifest 同表维护，还是独立授权表维护。
