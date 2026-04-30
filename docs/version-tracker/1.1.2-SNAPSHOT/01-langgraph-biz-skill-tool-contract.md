# LangGraph Biz Worker Skill 与标准工具契约规划

## 文档作用

- doc_type: architecture-plan | contract-design
- intended_for: platform-owner | worker-owner | execution-agent
- purpose: 定义 LangGraph Biz Worker 中 Skill、标准工具、自然语言触发与业务工具边界，避免把业务参数硬编码到平台通用层

## Version

- `1.1.2-SNAPSHOT`

## 背景

LangGraph Biz Worker 已具备 `SkillRegistry`、`SkillManifest`、`SkillRuntime`、Skill Frame 生命周期、账号级 Skill 热加载、Artifact 与账号文件工具等基础能力。当前集成验证主要依赖以下路径：

1. `context.skill` 显式指定 Skill
2. LLM router 只返回一个 `skill_id`
3. `context.order_id` 作为示例兜底触发 `exception_triage`

这能支撑冒烟测试和明确业务调用，但不足以覆盖自然语言聊天场景。例如用户输入：

```text
帮我把订单 ORD-1001 提交关单申请
```

在这个场景里，用户不会知道 `skill_id`，也不应被要求构造结构化 JSON。Worker 应由 LLM 依据自然语言、环境上下文与 Skill Manifest 决定是否调用 Skill、调用哪个 Skill、如何抽取参数，以及是否需要追问或审批。

## 核心结论

1. `context` 是平台到 Worker 的通用环境上下文容器，不是某个业务领域的固定参数模型。
2. `order_id` 是当前订单类示例 Skill 的业务入参，不是平台通用字段，也不是 `skill_id`。
3. `skill_id` 是 Skill 标识，例如 `exception_triage`、`close_order_apply`，应由 Worker 侧解释和执行。
4. 自然语言聊天入口应以 `prompt` 为一等输入，`context` 只补充当前页面、选中对象、用户身份、租户等环境信息。
5. Worker 侧应引入 root-level LLM planner，通过标准工具调用 Skill，而不是只让 LLM 输出一个字符串形式的 `skill_id`。
6. 业务类工具暂不直接注册给 root LLM。业务工具应由 Skill 内部按 Manifest 白名单使用，或通过后续专门的业务工具设计文档定义暴露策略。

## 术语边界

### Skill

Skill 是可被 Worker 调度的业务能力单元。它应具备稳定的 Manifest：

1. `id`：稳定 Skill 标识
2. `name`：展示名称
3. `description`：供 LLM 判断适用场景
4. `input_schema`：结构化入参
5. `output_schema`：结构化出参
6. `allowed_tools`：该 Skill 执行时可用的工具白名单
7. `approval_tools`：需要审批或确认的工具白名单
8. `business_rules`：业务规则、幂等性、副作用说明

Skill 不等同于工具。Skill 是业务任务封装，工具是执行 Skill 时可调用的底层能力。

### 标准工具

标准工具是 Worker runtime 提供给 root-level planner 的工具，用于 Skill 发现、参数规划、Skill 调用、追问和审批。标准工具不直接操作外部业务系统。

### 业务工具

业务工具是访问外部系统、读写业务数据、提交业务动作的工具，例如查订单、提交关单申请、创建工单、改派车辆。它们有领域语义、权限要求、审计要求和副作用风险。

本规划先不定义业务工具的 LLM 直接暴露策略，只规定：业务工具不得默认暴露给 root-level planner。

## 入口契约

### 平台到 Worker

统一任务 API 和 Java addon 应只负责透传通用字段：

```json
{
  "prompt": "帮我把订单 ORD-1001 提交关单申请",
  "model": "biz-default",
  "context": {
    "currentPage": "order_detail",
    "selectedOrderId": "ORD-1001"
  }
}
```

约束：

1. Java 统一层不理解具体 Skill。
2. Java 统一层不硬编码 `order_id`、`ticket_id` 等领域字段。
3. Java 统一层不负责从自然语言中抽取业务参数。
4. `contextId` 仍表示平台会话/A2A 多轮上下文，不与 `context` 混用。
5. `context` 是一次任务的环境上下文，可为空。

### Worker 内部

Worker 接到请求后应形成 planner 输入：

```json
{
  "prompt": "帮我把订单 ORD-1001 提交关单申请",
  "context": {
    "currentPage": "order_detail",
    "selectedOrderId": "ORD-1001"
  },
  "availableSkills": [
    {
      "id": "close_order_apply",
      "description": "提交订单关单申请",
      "input_schema": {
        "type": "object",
        "properties": {
          "order_id": { "type": "string" },
          "reason": { "type": "string" }
        },
        "required": ["order_id"]
      }
    }
  ]
}
```

LLM planner 输出不应是自由文本，而应通过标准工具调用表达决策。

## Root-Level 标准工具

### 1. `list_skills`

用途：列出当前账号、租户、Worker 可用的 Skill 摘要。

输入：

```json
{
  "domain": "order",
  "intent_hint": "close order"
}
```

输出：

```json
{
  "skills": [
    {
      "skill_id": "close_order_apply",
      "name": "提交关单申请",
      "description": "根据订单号和原因提交关单申请",
      "input_schema_summary": ["order_id", "reason"],
      "requires_approval": true
    }
  ]
}
```

约束：

1. 只返回摘要，不返回敏感实现细节。
2. 应按账号级 Skill 可见性过滤。
3. 不触发任何业务动作。

### 2. `get_skill_schema`

用途：获取某个 Skill 的完整输入、输出、副作用和审批要求。

输入：

```json
{
  "skill_id": "close_order_apply"
}
```

输出：

```json
{
  "skill_id": "close_order_apply",
  "input_schema": {},
  "output_schema": {},
  "requires_approval": true,
  "side_effects": ["submit_close_order_application"],
  "missing_input_policy": "ask_user"
}
```

约束：

1. 只能获取当前账号可见的 Skill。
2. Schema 用于参数抽取和追问，不等于授权执行。
3. 有副作用的 Skill 必须显式标记。

### 3. `invoke_skill`

用途：创建 Skill Frame 并执行指定 Skill。

输入：

```json
{
  "skill_id": "close_order_apply",
  "arguments": {
    "order_id": "ORD-1001",
    "reason": "delivery_failed"
  },
  "idempotency_key": "task-id:close_order_apply:ORD-1001"
}
```

输出：

```json
{
  "frame_id": "sf_xxx",
  "status": "RUNNING",
  "events": []
}
```

约束：

1. 必须走现有 `SkillRuntime.invoke_skill`，不得另起一套执行状态机。
2. 必须校验 `arguments` 符合 `input_schema`。
3. 对有副作用 Skill，若未满足审批要求，不得直接执行最终业务动作。
4. 调用过程必须产生 `skill_frame_open`、`skill_frame_close` 或失败事件。
5. `arguments` 是 Skill 入参，不能和 root `context` 概念混淆。

### 4. `ask_user`

用途：当自然语言和上下文无法满足 Skill 必填参数时，向用户追问。

输入：

```json
{
  "reason": "missing_required_fields",
  "missing_fields": ["reason"],
  "question": "请提供关单原因。"
}
```

输出：

```json
{
  "status": "WAITING_USER",
  "question": "请提供关单原因。"
}
```

约束：

1. 不能伪造用户回答。
2. 追问应能绑定当前 task/session，支持后续 resume。
3. 追问事件应映射为前端可展示的等待用户输入状态。

### 5. `request_approval`

用途：在有副作用业务动作前请求用户确认或审批。

输入：

```json
{
  "skill_id": "close_order_apply",
  "action": "submit_close_order_application",
  "summary": "将为订单 ORD-1001 提交关单申请，原因为 delivery_failed。",
  "arguments": {
    "order_id": "ORD-1001",
    "reason": "delivery_failed"
  }
}
```

输出：

```json
{
  "approval_id": "ap_xxx",
  "status": "AWAITING_APPROVAL"
}
```

约束：

1. 有副作用动作必须先产生审批事件。
2. 审批通过后才可恢复执行相关 Skill Frame。
3. 审批拒绝应让 Skill Frame 进入可解释的失败或取消状态。
4. 审批摘要必须可审计，不能只保存自然语言上下文。

### 6. `submit_root_result`

用途：当 planner 判断无需调用 Skill，或 Skill 已完成并需要归纳最终答复时，提交 root 任务结果。

输入：

```json
{
  "summary": "已提交关单申请。",
  "structured_output": {
    "order_id": "ORD-1001",
    "application_id": "CA-9001"
  },
  "refs": ["skill_frame:sf_xxx"]
}
```

输出：

```json
{
  "status": "COMPLETED"
}
```

约束：

1. 如果存在未关闭 Skill Frame、未决工具调用或未处理审批，不允许提交完成。
2. 输出必须经过现有 output contract 校验。
3. `structured_output` 不得包含内部 `content_ref`、密钥或未授权业务数据。

## Planner 决策流程

自然语言入口建议按以下顺序执行：

1. 加载账号可见 Skill。
2. 将 `prompt`、`context`、Skill 摘要交给 root-level LLM planner。
3. LLM 可调用 `list_skills` 或 `get_skill_schema` 补充信息。
4. LLM 从 `prompt/context` 抽取候选参数。
5. 参数不足时调用 `ask_user`。
6. 参数满足且无需审批时调用 `invoke_skill`。
7. 参数满足但存在副作用时先调用 `request_approval`。
8. Skill 完成后调用 `submit_root_result` 汇总。

显式业务调用入口仍可保留：

```json
{
  "context": {
    "skill": "exception_triage",
    "order_id": "ORD-1001"
  }
}
```

但它应被定位为兼容入口或外部系统明确编排入口，不代表聊天入口的主路径。

## 业务工具边界

业务工具暂不直接注册给 root-level planner，原因：

1. 业务工具通常有副作用，直接暴露会扩大误调用风险。
2. 不同业务工具需要不同鉴权、审计、幂等和审批策略。
3. root planner 的职责是选择和编排 Skill，不应绕过 Skill 的业务规则。
4. Skill Manifest 已有 `allowed_tools` 与 `approval_tools`，更适合做业务工具白名单边界。

推荐原则：

1. root-level planner 只能调用标准工具。
2. Skill-level agent 只能调用该 Skill Manifest 白名单中的工具。
3. 业务工具必须声明读写类型、幂等键、审批要求、审计字段和错误模型。
4. 真正的外部系统调用应封装在工具适配层，不写进 prompt 或 Skill 文本。

业务工具设计另行讨论，不纳入本文件的实现范围。

## 事件映射要求

标准工具调用应映射为现有 Worker SSE 事件，便于 Java addon 和前端复用：

1. `tool_use`：root planner 调用标准工具
2. `tool_result`：标准工具返回结果
3. `skill_frame_open`：`invoke_skill` 创建 Skill Frame
4. `skill_frame_close`：Skill Frame 完成
5. `approval_request`：等待审批
6. `assistant_text`：追问或非 Skill 回答
7. `result`：root 任务最终结果
8. `error`：planner、工具、Skill 或审批错误

Java 侧不需要理解具体 Skill，只需保持事件透传与统一任务状态更新。

## 安全与治理

1. Skill 可见性按账号、租户、公共层级过滤。
2. `context` 进入 planner 前应做大小限制和敏感字段清理。
3. `invoke_skill.arguments` 必须按 Schema 校验。
4. 有副作用 Skill 必须显式审批或满足预设自动审批策略。
5. Artifact 的 `content_ref` 不得泄露给保留消息或最终输出。
6. Account file tools 仍受 `AccountPathGuard` 约束。
7. 所有工具调用和审批必须有审计记录。

## 分阶段实施建议

### Stage 1：契约落地

1. 新增 root-level 标准工具定义模块。
2. 为 `SkillManifest` 补足 planner 所需字段映射。
3. 将 `LlmSkillRouter` 从“返回 skill_id 字符串”升级为“标准工具调用 planner”。
4. 保留 `context.skill` 与 `context.order_id` 兼容路径，但文案中降级为 fallback。

### Stage 2：自然语言触发 Skill

1. 用 prompt `帮我分析订单 ORD-1001 异常` 验证 LLM 能抽取 `order_id` 并调用 `exception_triage`。
2. 增加参数缺失追问测试。
3. 增加无匹配 Skill 时的正常回答或拒绝测试。
4. 增加 Skill 不可见时的隔离测试。

### Stage 3：审批与副作用动作

1. 定义一个需要审批的示例 Skill。
2. 验证 `request_approval` 事件、审批通过恢复、审批拒绝取消。
3. 验证幂等键和审计记录。

### Stage 4：业务工具设计

业务工具单独建档，至少明确：

1. 工具分类：只读、写入、提交型、副作用型
2. 鉴权来源
3. 参数 Schema
4. 幂等策略
5. 审批策略
6. 审计字段
7. 错误模型
8. 是否允许 root planner 直接调用

## 验收标准

1. 文档明确区分 `context`、`contextId`、`skill_id`、Skill 入参。
2. 文档明确 `order_id` 只是示例业务入参，不是平台通用字段。
3. 文档定义 root-level 标准工具清单和每个工具的输入、输出、约束。
4. 文档明确业务工具暂不直接注册给 root LLM。
5. 后续实现可按本文分阶段推进，不需要 Java 侧理解具体业务 Skill。

## 当前未决问题

1. `ask_user` 在现有统一任务模型里应复用 resume，还是新增明确的 waiting-user 状态。
2. `request_approval` 的审批主体是当前用户、管理员，还是业务系统审批流。
3. root planner 与 Skill-level agent 是否共用同一个 LLM 配置。
4. 标准工具调用事件是否需要在 Java DTO 中增加更强类型字段。
5. 业务工具是否允许部分只读工具暴露给 root planner。
