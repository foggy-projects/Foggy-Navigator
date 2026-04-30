# 业务脚本引擎与 Business Function Manifest 设计规划

## 文档作用

- doc_type: architecture-plan | contract-design
- intended_for: platform-owner | worker-owner | upstream-business-owner | execution-agent
- purpose: 定义 LangGraph Biz Worker 侧业务脚本编排、Business Function Manifest、审批确认码与脚本复用机制，明确 Worker 不作为业务实现方

## Version

- `1.1.2-SNAPSHOT`

## 背景

业务类工具不能简单等同于 LLM 基础工具。文件 IO、Skill 调用、Artifact、账号目录写入等属于 Worker runtime 基础能力，本文件不继续展开。

当前讨论聚焦业务能力接入：Worker 需要支撑自然语言业务任务，例如：

```text
帮我把订单 ORD-1001 提交关单申请
```

但 Worker 不应成为订单、关单、调度、库存等业务实现方。业务实现仍属于上游业务系统。Worker 负责理解、编排、暂停、审批、恢复、审计事件转发和脚本资产沉淀。

## 核心结论

1. 业务能力不建议直接以 curl、裸 REST 或裸 CLI 形式暴露给 LLM。
2. Worker 可内置 Script Engine，用于执行受控脚本；真实业务函数仍调用上游系统。
3. LLM 负责生成脚本或选择已有脚本，不负责决定绕过审批。
4. 审批不是脚本里的普通 `approve()` 自愿调用，而是由 Script Engine 根据函数 Manifest 强制拦截。
5. 高风险操作必须要求用户授权确认码或业务审批流，确认凭证不得暴露给 LLM。
6. 调试稳定的脚本应可保存、复用、发布为账号/项目/公共脚本资产，后续优先复用而不是每次重新生成。
7. Skill 可以暴露“如何创建脚本”和“有哪些业务函数可用”，但业务函数本身不直接批量注册为 LLM tool。

## 总体架构

```text
User Prompt
  -> LangGraph Biz Worker
      -> Skill / Planner
          -> generate_script / load_script
          -> Script Engine
              -> AST / schema / permission validation
              -> Business Function Registry
                  -> Upstream Adapter
                      -> REST / RPC / MQ / Workflow / MCP / CLI
```

边界：

1. Worker 侧承载脚本执行、安全沙箱、事件流、暂停恢复、脚本资产管理。
2. 上游业务系统承载真实业务规则、数据写入、权限最终判定和业务状态变更。
3. Business Function Registry 保存函数声明和调用适配配置，不保存业务实现逻辑。
4. Script Engine 调用函数时带上由平台注入的用户、租户、会话和审批上下文。

## REST 直连与脚本编排对比

### 上游 REST 直连

适合：

1. 少量原子只读查询
2. 临时验证上游能力
3. 内部固定流程的简单调用

问题：

1. LLM 需要理解太多接口细节。
2. 参数校验、审批、幂等、审计容易分散。
3. Worker 容易演化为业务编排层。
4. 调试经验难沉淀，后续复用困难。

### Script Engine 编排

适合：

1. 多步骤业务流程
2. 查询、判断、草稿、审批、提交组合
3. 需要保存和复用用户调试经验的场景
4. 有暂停/恢复/审批/审计要求的业务动作

优势：

1. LLM 输出的是受限脚本，不是任意 HTTP。
2. 执行引擎统一处理风险控制。
3. 业务函数可集中声明、校验、授权和审计。
4. 脚本可沉淀为可复用资产。

## 脚本语言建议

可选方向：

1. FSScript / SemanticDSL 风格：限制更强，适合查询分析和固定 DSL。
2. Python 子集：LLM 更熟悉，适合业务编排表达，但必须做强沙箱。

初步建议：

1. 查询分析类优先复用现有 `compose_script_m2` 思路。
2. 业务动作类使用受限 Python 子集或 FSScript 扩展均可，但必须统一落到 Script Engine 的 AST 校验和 host function 白名单。
3. 禁止脚本直接使用文件 IO、网络、动态 import、反射、eval、系统命令。
4. 只允许调用 Engine 注入的业务函数对象，例如 `biz.order.get(...)`、`biz.order.close_apply.submit(...)`。

示例脚本：

```python
order = biz.order.get(order_id="ORD-1001")

application = biz.order.close_apply.draft(
    order_id=order["id"],
    reason="delivery_failed"
)

result = biz.order.close_apply.submit(
    application_id=application["application_id"]
)

return result
```

这里 `submit` 是否需要审批不由 LLM 决定，而由函数 Manifest 决定。

## Business Function Manifest

每个业务函数必须有结构化声明：

```yaml
id: biz.order.close_apply.submit
name: 提交关单申请
description: 提交已生成的订单关单申请
category: order
risk_level: state_change
transport: rest
upstream_ref: tms-order-service.closeApplySubmit
input_schema:
  type: object
  properties:
    application_id:
      type: string
  required:
    - application_id
output_schema:
  type: object
  properties:
    application_id:
      type: string
    status:
      type: string
auth_scope: order:close_apply:submit
approval_policy:
  mode: user_confirm_code
  reason_template: 提交订单关单申请
idempotency:
  key_template: close_apply_submit:${application_id}
audit:
  fields:
    - application_id
    - order_id
    - operator_id
    - tenant_id
exposure:
  root_planner: false
  script_engine: true
  skill_allowlist_required: true
```

字段说明：

1. `id`：稳定函数标识，脚本调用和审计均使用它。
2. `risk_level`：至少包括 `readonly`、`draft`、`state_change`、`external_side_effect`、`delete`。
3. `transport`：底层适配方式，例如 `rest`、`rpc`、`mq`、`workflow`、`mcp`、`cli`。
4. `input_schema` / `output_schema`：执行前后强校验。
5. `auth_scope`：平台或上游授权域。
6. `approval_policy`：审批策略，由 Engine 强制执行。
7. `idempotency`：状态变更和外部副作用必须声明。
8. `audit`：审计字段，不能只保存 prompt。
9. `exposure`：控制是否允许 root planner、script engine 或 Skill 使用。

## 风险等级

### `readonly`

只读查询，例如查订单、查客户、查轨迹。

要求：

1. 仍需权限校验。
2. 需限制返回字段和条数。
3. 可考虑允许 root planner 间接使用，但默认仍通过脚本或 Skill。

### `draft`

生成草稿、校验可行性、预估结果，不改变业务最终状态。

要求：

1. 允许写入临时草稿或 Worker artifact。
2. 不触发真实业务动作。
3. 可作为审批前摘要来源。

### `state_change`

改变上游业务状态，例如提交关单、改派、取消任务。

要求：

1. 必须有幂等键。
2. 必须审计。
3. 默认需要用户确认码或审批流。

### `external_side_effect`

对外部产生影响，例如发短信、发邮件、调用第三方提交。

要求：

1. 必须审批或满足明确自动化策略。
2. 必须记录接收方、内容摘要和调用结果。
3. 失败重试策略必须由上游或 Engine 控制。

### `delete`

删除、作废、不可逆取消。

要求：

1. 必须要求用户授权确认码。
2. 确认码由平台生成和校验，不进入 LLM 上下文。
3. 必须记录删除对象、影响范围、操作者和审批凭证。

## 审批与确认码模型

不推荐脚本中出现由 LLM 自行决定的：

```python
approval = approve("提交关单申请", application)
```

推荐由 Engine 强制拦截：

```python
result = biz.order.close_apply.submit(application_id=application_id)
```

执行到 `submit` 时：

1. Engine 读取函数 Manifest。
2. 发现 `approval_policy.mode = user_confirm_code`。
3. Engine 暂停脚本，生成 `approval_request`。
4. Java/前端向用户展示操作摘要和确认要求。
5. 用户输入确认码。
6. 平台校验确认码，生成内部 approval token。
7. Engine 使用 token 恢复执行。
8. LLM 不接触确认码和 approval token。

审批事件示例：

```json
{
  "type": "approval_request",
  "approval_id": "ap_xxx",
  "function_id": "biz.order.close_apply.submit",
  "risk_level": "state_change",
  "summary": "将提交订单 ORD-1001 的关单申请",
  "required": "user_confirm_code",
  "resume_token_ref": "server_side_only"
}
```

审批通过事件示例：

```json
{
  "type": "approval_result",
  "approval_id": "ap_xxx",
  "status": "approved"
}
```

约束：

1. 确认码不得写入 prompt、script、artifact、Skill Frame private messages。
2. 审批摘要必须由 Engine 基于已校验参数和业务函数声明生成。
3. 审批通过后只能恢复同一脚本快照和同一函数调用，不允许替换参数。
4. 审批超时或拒绝时，脚本进入暂停失败或取消状态。

## Script Engine 放置位置

### 方案 A：上游提供 Script Engine

优点：

1. 上游最了解业务函数和权限。
2. Worker 更薄。
3. 审批与业务状态可在上游闭环。

缺点：

1. Worker 的 Skill Frame、artifact、脚本保存和 SSE 事件需要跨系统协同。
2. 多个上游系统会产生多个脚本运行时。
3. 脚本资产的账号级复用和平台治理不够统一。

### 方案 B：Worker 内置 Script Engine

优点：

1. 和 Skill Frame、审批事件、artifact、脚本保存、resume 更容易整合。
2. 用户调试出的脚本可作为 Worker 资产沉淀。
3. 多个上游 transport 可统一封装为 Business Function Adapter。

缺点：

1. Worker 需要提前加载或发现业务函数声明。
2. Worker 必须实现严格沙箱。
3. 上游业务权限仍需二次校验，不能只信 Worker。

建议：

首版采用方案 B：Worker 内置 Script Engine，业务函数通过 Manifest + Adapter 调用上游。这样 Worker 不实现业务逻辑，但能统一管理脚本生命周期、审批和复用。

## 上游适配方式

Business Function Adapter 可支持多种底层方式：

1. `rest`：调用上游 REST API。
2. `rpc`：调用内部 RPC。
3. `mq`：发布业务命令事件。
4. `workflow`：启动上游流程。
5. `mcp`：通过 MCP 访问上游工具。
6. `cli`：调用受控 CLI，适合已有运维或业务命令行能力。

LLM 不感知这些 transport。LLM 只看到业务函数 schema 和脚本 API。

## 业务函数发现

可提供以下查询能力给脚本生成 Skill：

1. `list_business_functions(domain, risk_filter)`
2. `get_business_function_schema(function_id)`
3. `get_business_function_examples(function_id)`
4. `validate_script(script)`
5. `execute_script(script)`
6. `save_script(name, script, metadata)`
7. `load_script(script_id)`

这些能力属于脚本编排层工具，不等同于直接注册全部业务函数给 LLM。

## Skill 与脚本的关系

Skill 可承担以下职责：

1. 告诉 LLM 如何写业务脚本。
2. 根据用户请求选择已有脚本。
3. 查询可用业务函数 schema。
4. 生成或修复脚本。
5. 调用 Script Engine 执行。
6. 将执行事件转成用户可理解的过程和结果。

Skill 不直接实现业务动作，也不绕过 Script Engine 调上游。

## 脚本资产沉淀

用户调试稳定的脚本应可保存为资产：

1. `account_script`：账号私有脚本。
2. `project_script`：项目或工作区脚本。
3. `published_script`：经审核后发布的公共脚本。
4. `skill_script`：绑定到特定 Skill 的脚本模板。

保存元数据：

```yaml
script_id: script_xxx
name: 订单关单申请
version: 1
owner_account_id: acc_xxx
scope: account
language: python_subset
hash: sha256:...
dependencies:
  functions:
    - biz.order.get
    - biz.order.close_apply.draft
    - biz.order.close_apply.submit
risk_level: state_change
approval_required: true
created_by: user_xxx
updated_by: user_xxx
```

复用规则：

1. 同一请求匹配到已保存脚本时，优先让 LLM选择和填参，而不是重写脚本。
2. 依赖函数 Manifest 版本变化时，脚本需要重新 validate。
3. 高风险脚本发布为公共资产前必须审核。
4. 脚本执行历史应可追溯到脚本 hash 和函数调用审计。

## 运行时状态

脚本执行建议引入独立状态：

1. `CREATED`
2. `VALIDATING`
3. `RUNNING`
4. `AWAITING_APPROVAL`
5. `PAUSED`
6. `COMPLETED`
7. `FAILED`
8. `CANCELLED`

与 Skill Frame 关系：

1. 一个 Skill Frame 可启动一个或多个 Script Run。
2. Script Run 暂停时，Skill Frame 应进入等待审批或等待用户输入状态。
3. Script Run 完成后，结果回填 Skill Frame。

## 事件契约

Script Engine 应产生可转发事件：

1. `script_start`
2. `script_validate`
3. `function_call`
4. `function_result`
5. `approval_request`
6. `approval_result`
7. `script_pause`
8. `script_resume`
9. `script_result`
10. `script_error`

函数调用事件示例：

```json
{
  "type": "function_call",
  "function_id": "biz.order.get",
  "risk_level": "readonly",
  "arguments_summary": {
    "order_id": "ORD-1001"
  }
}
```

对敏感参数和返回值必须做摘要化或脱敏。

## 首版实现范围建议

首版可以先实现最小闭环：

1. Worker 内置 Python 子集或 FSScript 风格 Script Engine。
2. Business Function Manifest 文件注册。
3. 只读函数和 draft 函数真实调用或 mock 调用。
4. 一个 `state_change` 示例函数，强制触发确认码审批。
5. 脚本保存、加载、hash、依赖函数记录。
6. Script Run 事件映射到现有 SSE。
7. Skill 使用脚本编排能力完成自然语言业务任务。

暂不包含：

1. 裸 curl 生成和执行。
2. LLM 直接调用全部业务函数。
3. 无审批的 delete。
4. 跨租户脚本共享。
5. 复杂审批流编排器。

## 验收标准

1. 文档明确 Worker 不作为业务实现方。
2. 文档明确业务函数通过 Manifest 声明，真实实现由上游承载。
3. 文档明确高风险函数由 Script Engine 强制审批，不能由 LLM 自行决定。
4. 文档明确 delete 类操作必须用户授权确认码。
5. 文档明确脚本可保存复用，并记录 hash、版本、依赖函数和风险等级。
6. 文档明确 Skill 负责脚本生成/选择/执行编排，不直接暴露全部业务函数给 LLM。

## 当前未决问题

1. 首版脚本语言使用 FSScript 扩展还是 Python 子集。
2. Business Function Manifest 存放在 Worker 本地、上游同步，还是 MCP/REST 动态发现。
3. 用户确认码由 Java 平台生成，还是 Worker 生成并交 Java 校验。
4. Script Run 状态是否需要独立落库，还是复用 Skill Frame journal。
5. 只读函数是否允许 root planner 直接间接调用，还是统一必须通过脚本。
