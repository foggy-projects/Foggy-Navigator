# Skill 业务能力说明模板

## 文档作用

- doc_type: skill-template
- version: 1.1.3-SNAPSHOT
- status: draft
- date: 2026-05-02
- intended_for: skill-owner | worker-owner | java-service-owner | upstream-business-owner | execution-agent | reviewer
- purpose: 定义 Skill/SKILL.md 如何描述上游业务能力、函数 allowlist、风险等级、审批规则、数据探查策略和禁止事项

## 核心定位

Skill/SKILL.md 是 LLM 理解业务能力的入口，不是上游 API 文档，也不是凭证或网络访问说明。1.1.3 中 Upstream App 可以维护自己的 App 作用域 Skill，但不能把它发布、升级或复制为平台公共技能。

Skill 应描述：

1. 用户什么意图会触发该 Skill。
2. 当前 Skill 能使用哪些业务函数。
3. 每个函数的业务用途、风险等级和审批要求。
4. 多步骤业务流程如何探查数据、生成草稿、请求审批和恢复。
5. 哪些行为被禁止。

Skill 不得描述：

1. upstream `base_url`。
2. credential、token、cookie、签名算法或 header 注入细节。
3. curl、裸 HTTP、任意 URL 请求或绕过 Java 的调用方式。
4. 未经 Java Registry allowlist 授权的函数。
5. 确认码、approval token 或任何可让 LLM 自批准的内容。

## Skill 来源与作用域

1.1.3 只考虑三类 Skill 来源：

| 来源 | 维护方 | 可见范围 | 说明 |
| --- | --- | --- | --- |
| `account_skill` | Navigator 内部用户 | 当前账号 | 个人账号目录技能，只服务内部人类用户会话 |
| `upstream_app_skill` | Upstream App owner / collaborator | 当前 `upstream_app_id` | App 私有技能，可授权给该 App 下的 upstream user |
| `builtin_public_skill` | Navigator Java / Biz Worker 平台团队 | 平台内置 | 上游 App 可以被授权使用，但不能维护或发布 |

`role_skill` 本版本不考虑，不参与 Skill 暴露、合并或授权计算。

内部人类用户会话可见技能：

```text
account_skill
  + authorized upstream_app_skill
  + builtin_public_skill
```

上游 App 发起调用可见技能：

```text
upstream_app_skill
  intersect upstream_user_skill_grant
  intersect business_function_grant
  intersect Java Registry enabled functions
```

约束：

1. Upstream App Skill 只在当前 App 作用域内可见。
2. App 下的 upstream user 能否使用某个 Skill，由 Java 侧 App user grant 决定。
3. Skill 告诉 LLM 业务语义和边界，Java Registry/Grant 决定最终能否执行。
4. Upstream App 不能维护 `builtin_public_skill`，也不能影响其他 App 的 Skill。

## 推荐 Skill 文档结构

```markdown
# <业务 Skill 名称>

Use this skill when <自然语言触发意图>.

## Scope

- domain: <业务域>
- skill_source: upstream_app_skill | account_skill | builtin_public_skill
- upstream_app_scope: <仅 App Skill 填写 upstream_app_id 或占位>
- supported_intents:
  - <意图 1>
  - <意图 2>
- non_goals:
  - <不处理的请求>

## Available Business Capabilities

| function_id | purpose | risk_level | approval | notes |
| --- | --- | --- | --- | --- |
| <function_id> | <业务用途> | <risk> | <required/none> | <限制> |

## Function Allowlist

Only these business functions may be used by this skill:

- `<function_id>`

## Data Discovery Strategy

- Prefer FSScript/DSL readonly queries before state-changing calls.
- Use `list_business_functions` and `get_business_function_schema` when schema is unclear.
- Ask the user when required fields cannot be inferred from prompt or context.

## Approval Rules

- Summarize business keys and expected effect before state-changing actions.
- Wait for Java-owned approval or confirmation code.
- Never approve, fabricate approval, or continue after rejection.

## Example Flow

1. <查询或确认对象>
2. <创建草稿或校验>
3. <提交前摘要>
4. <等待审批>
5. <恢复并返回结果>

## Prohibited Actions

- Do not call raw HTTP, curl, or upstream REST directly.
- Do not expose base_url, credentials, headers, or adapter details.
- Do not call functions outside this skill allowlist.
- Do not use this skill outside its App scope.
- Do not self-approve side-effect operations.
- Do not access another tenant's data.
```

## 必填章节

| 章节 | 目的 |
| --- | --- |
| `Use this skill when` | 帮助 root planner 判断触发意图 |
| `Scope` | 明确业务域、支持意图和非目标 |
| `Skill Source` | 明确 Skill 是账号技能、App 作用域技能还是内置公共技能 |
| `Available Business Capabilities` | 以业务语义列出函数能力 |
| `Function Allowlist` | 明确函数白名单，防止 Skill 泛化调用全量 Registry |
| `Risk and Approval` | 告诉 LLM 哪些动作必须暂停等待 Java 审批 |
| `Data Discovery Strategy` | 指导 LLM 先查询、校验、追问，而不是猜参数 |
| `Example Flow` | 给出可复用流程，减少每次重新发明编排 |
| `Prohibited Actions` | 明确禁止裸 HTTP、绕过审批、越权和泄露凭证 |

## 数据探查策略

Skill 应鼓励 LLM 按以下顺序工作：

1. 从用户 prompt 和 task context 提取候选业务键。
2. 使用只读函数或 FSScript/DSL 查询确认对象存在、状态可操作、租户一致。
3. 当必填字段缺失时向用户追问。
4. 对草稿类函数先创建或校验草稿。
5. 对 `state_change`、`external_side_effect`、`delete` 操作生成业务摘要并等待 Java 审批。
6. 审批通过后恢复原脚本或 Skill Frame，不重新构造不同参数。

Skill 不应让 LLM 根据自然语言猜测订单状态、审批结果或上游默认值。

## 风险等级描述规则

Skill 中的风险说明只保留业务摘要：

| 风险等级 | Skill 可写内容 | Skill 不可写内容 |
| --- | --- | --- |
| `readonly` | 查询对象、字段范围、返回条数限制 | 查询 SQL、上游 URL |
| `draft` | 会生成草稿或预校验，不触发最终状态 | 草稿表名、内部状态机 |
| `state_change` | 会改变业务状态，必须审批 | 绕过审批的接口路径 |
| `external_side_effect` | 会影响外部对象，必须确认接收方和内容摘要 | 第三方 credential |
| `delete` | 不可逆或高危，必须确认码 | 删除接口 curl |

## 订单关单申请助手示例片段

```markdown
# 订单关单申请助手

Use this skill when the user asks to inspect, create, or submit an order close application.

## Scope

- domain: order
- skill_source: upstream_app_skill
- upstream_app_scope: app_tms_tenant_a
- supported_intents:
  - 查询订单是否可关单
  - 创建关单申请草稿
  - 提交关单申请
- non_goals:
  - 不处理订单删除、作废或越权改派
  - 不直接审批关单申请

## Available Business Capabilities

| function_id | purpose | risk_level | approval | notes |
| --- | --- | --- | --- | --- |
| `tms.order.get` | 查询订单详情和当前状态 | `readonly` | none | 只返回当前租户和用户可见字段 |
| `tms.order.close_apply.draft` | 创建或更新关单申请草稿 | `draft` | none | 需要订单号和关单原因 |
| `tms.order.close_apply.submit` | 提交已创建的关单申请 | `state_change` | user_confirm_code | 必须等待 Navigator Java 审批 |

## Function Allowlist

Only these business functions may be used by this skill:

- `tms.order.get`
- `tms.order.close_apply.draft`
- `tms.order.close_apply.submit`

## Data Discovery Strategy

- If `order_id` is missing, ask the user for the order number.
- Before submitting, call readonly query or FSScript/DSL to confirm the order exists and is visible.
- If the close reason is missing, ask the user for a clear reason.
- Use `get_business_function_schema` before calling a function when required inputs are unclear.
- Use `run_business_script` for the full query -> draft -> submit flow.

## Approval Rules

- Before `tms.order.close_apply.submit`, summarize `order_id`, `application_id`, close reason, and expected effect.
- Stop and wait for Java-owned confirmation code approval.
- If approval is rejected or expired, report cancellation and do not retry submission.
- Never approve the submission by yourself.
- This skill can only be used inside the authorized Upstream App scope.

## Example Flow

1. Extract `order_id` and close reason from user prompt or context.
2. Query `tms.order.get` to confirm order visibility and current status.
3. Call `tms.order.close_apply.draft` to create a draft application.
4. Present a concise summary and call `tms.order.close_apply.submit`.
5. If Java returns `status=suspended`, wait for resume.
6. After resume, return the submitted application status.

## Prohibited Actions

- Do not call raw HTTP, curl, or upstream REST directly.
- Do not expose upstream base_url, credentials, headers, or adapter details.
- Do not call business functions outside this allowlist.
- Do not self-approve `state_change` operations.
- Do not publish this App skill as a builtin public skill.
- Do not fabricate order status, application ID, approval result, or confirmation code.
- Do not access data outside the current tenant or user permission scope.
```

## Skill 与 Java Registry 的关系

首版建议形成多重约束：

1. Skill 文档写明 allowlist，供 LLM 理解边界。
2. Java Registry 保存同一 allowlist 或动态下发 allowlist，供运行时强制校验。
3. Upstream App Grant 决定当前 App 是否可见该 Skill。
4. App user grant 决定该 App 下的 upstream user 是否可使用该 Skill。

如果二者不一致，运行时以 Java Registry 为准，Skill 文档应在发布流程中被修正。Skill 不是最终授权源。

## Skill 与脚本的关系

Skill 可以包含示例流程和脚本编排策略，但不应内联上游 REST 细节。推荐表达：

```text
Use `run_business_script` to compose readonly query, draft creation, and submit.
```

不推荐表达：

```text
POST https://upstream.example.com/api/orders/{id}/close
```

脚本中可使用的业务函数必须来自 Java 返回的 schema 和当前 Skill allowlist。

## 当前验收口径

- status: draft
- verified: no
- implementation_required: yes
- development_progress: Skill 文档模板已拆出
- testing_progress: 文档自检，未执行代码测试
- experience_progress: N/A，本文不涉及 UI 交互

## 待决策

1. Skill allowlist 首版写入 SKILL.md、独立 manifest，还是由 Java Registry 动态注入。
2. Skill 发布流程是否需要自动校验文档 allowlist 与 Java Registry 一致。
3. 是否需要为高风险 Skill 增加人工审核和发布签名。
4. Upstream App Skill 的存储位置使用 App 私有目录、DB 记录，还是二者组合。
5. 内部用户访问 Upstream App Skill 的授权角色先用 App owner/collaborator，还是建立独立 App collaborator 表。
