---
doc_type: architecture-design | retrospective
intended_for: execution-agent | reviewer | platform-owner | worker-owner
purpose: 固化 BizWorker root skill、业务函数虚拟 frame、上下文穿透与暂停授权的设计，并按主流 Agent/Skill 做法复盘可优化点
type: design
source_type: user-discussion
version: 1.3.0-SNAPSHOT
ticket: DESIGN-013
priority: high
status: design-recorded
owner: claude-worker-agent | coding-agent-backend | session-module
---

# BizWorker Root Skill 与业务函数虚拟 Frame 设计

## 文档作用

- doc_type: architecture-design | retrospective
- intended_for: execution-agent | reviewer | platform-owner | worker-owner
- purpose: 固定本轮关于 BizWorker root skill、业务函数调用、上下文穿透、上下文收敛、用户级授权与暂停恢复的设计口径

## 背景

当前 BizWorker 的 root agent 层更接近一次性路由器：root 层先让模型选择 `invoke_business_skill`，进入 skill frame 后再由 `LlmSkillAgent` 提供工具循环和 `invoke_business_function`。这带来两个限制：

1. root agent 本身不能直接调用业务函数。
2. 业务函数调用缺少统一的可暂停、可审计、可恢复的 frame 边界。
3. skill 嵌套后，上下文是否可见、如何裁剪、何时收敛没有稳定契约。

本设计方向是：所有 BizWorker 任务先进入一个极特殊的系统 root skill；root skill 具备业务函数调用能力。root skill 或普通 skill 调用业务函数时，由运行时自动创建一个“虚拟 function frame”包装本次函数调用，用作授权、暂停、审计、恢复和上下文快照边界。

## 当前实现基线

按当前实现理解：

1. root agent 层不是通用 tool-call loop，主要能力是调用 `invoke_business_skill`。
2. skill frame 内的 `LlmSkillAgent` 才有通用工具循环，并绑定 `invoke_business_function`、资源读取、提交结果等工具。
3. 业务函数的实际调用应走 Worker Gateway；当前运行时已将 `SkillFunctionAllowlist` 降级为非硬阻断，硬边界是用户/ClientApp function 授权。
4. Java 到 Python Worker 的 `/api/v1/query` 请求只带当前 prompt、context、runtimeContext、attachments、session_id、userId、tenantId 等运行输入，不自动注入完整会话历史。
5. skill 进入时会构建自己的 system prompt、skill markdown、账号上下文/记忆、用户请求和 skill input；tool-call loop 内部消息只在本次 skill run 中持续。
6. 当前没有明确的全局 token budget 和上下文收敛机制；超出模型窗口时主要依赖模型/请求失败或局部截断逻辑。

## 术语

### root skill

`root skill` 是 BizWorker 每个任务启动时自动进入的系统内置 skill。它不是普通用户 skill，不应被普通 ClientApp 创建、删除或覆盖。

建议稳定 ID：

```text
__root_business_skill__
```

职责：

1. 承接主任务的首层推理、工具选择和子 skill 调度。
2. 可以直接调用业务函数。
3. 持有 root 层的长期工作上下文，并触发上下文收敛。
4. 作为任务级暂停、恢复、审计的顶层归属。

### normal skill

普通业务 skill，由 root skill 或上级 skill 显式触发。普通 skill 默认上下文隔离，只接收结构化输入、必要摘要、artifact/resource 引用和自身 skill prompt。

### virtual function frame

`virtual function frame` 是业务函数调用的运行时包装 frame。它不建议叫“虚拟 skill”，避免和由 LLM 执行的普通 skill 混淆。

职责：

1. 记录业务函数名、版本、参数、调用方 frame、idempotencyKey、traceId。
2. 在函数执行前完成用户级授权与必要的人审暂停。
3. 在暂停后持久化恢复所需状态。
4. 在恢复后继续同一次业务函数调用，而不是重新让模型猜测。
5. 将函数结果、错误、审批决定、审计信息归并回调用方 frame。

### context passthrough

上下文穿透是显式策略，表示子 frame 可以看到父级或 root 的压缩后上下文快照。它不是默认行为。

## 设计决策

### 1. BizWorker 任务一开始进入 root skill

运行流调整为：

```text
task request
  -> create root skill frame
  -> run LlmSkillAgent(root skill)
  -> root skill may call business function
  -> root skill may invoke normal child skill
  -> child skill may call business function
  -> submit final result
```

root Graph 的职责从“让模型先选 skill”降级为 bootstrap：

1. 初始化 root skill frame。
2. 装载 root skill 的系统 prompt、账号上下文、记忆、当前任务输入。
3. 进入 `LlmSkillAgent` 工具循环。
4. 处理最终结果、暂停状态和错误边界。

兼容期可以保留旧的 `invoke_business_skill` 入口，但不再要求 root agent 第一跳必须选普通 skill。

### 2. root skill 是极特殊系统 skill

root skill 的特殊性：

1. 系统内置，不由用户上传的 `SKILL.md` 完全决定。
2. 可以有独立 manifest/policy，例如 `builtin=true`、`system=true`、`context_visibility=passthrough`。
3. 工具权限由运行时策略授予，不依赖普通 skill allowlist。
4. root skill 的第一层工作内容会长期存在于任务上下文，应纳入上下文收敛机制。

root skill 不应变成“万能全局权限绕过点”。实际业务函数执行仍必须经过用户级授权、ClientApp/function 授权、函数启用状态和人审暂停策略。

### 3. 业务函数调用自动创建 virtual function frame

当 root skill 或 normal skill 发起 `invoke_business_function` 时，运行时自动创建：

```text
FrameKind = FUNCTION_CALL
syntheticSkillId = __function__:<functionName>
parentFrameId = callerFrameId
```

或者引入独立模型：

```text
BusinessFunctionCallFrame
```

推荐优先使用 `FrameKind=FUNCTION_CALL`，不要把它伪装成普通 skill。这样 UI、审计、恢复、权限判断可以复用 frame 树，又不会混淆“LLM skill”和“函数调用 frame”。

该 frame 的最小持久化字段：

1. `frameId`
2. `parentFrameId`
3. `frameKind=FUNCTION_CALL`
4. `callerSkillId`
5. `functionName`
6. `functionVersion`
7. `arguments`
8. `argumentHash`
9. `idempotencyKey`
10. `status`
11. `suspendId`
12. `approvalState`
13. `contextSnapshotRef`
14. `resultRef`
15. `error`

### 4. 业务函数授权以用户级授权为硬边界

`FunctionAllowlist` 当前不需要过度严格校验。运行时硬边界是：

1. ClientApp 有效。
2. UpstreamUser 或平台用户身份有效。
3. 用户/ClientApp 对该业务函数具备授权。
4. 业务函数和版本处于启用状态。
5. 需要审批的函数必须能暂停、展示审批项、持久化 pending state、审批后恢复。

`SkillFunctionAllowlist` 定位调整为：

1. prompt 可见性提示。
2. skill 推荐工具范围。
3. UI/治理上的软约束。
4. 审计时的风险提示。

它不作为当前阶段的硬阻断条件。硬阻断只放在用户级授权和运行时安全策略。

### 5. 上下文可见性分层

建议引入三档策略：

| 策略 | 默认对象 | 可见内容 |
| --- | --- | --- |
| `isolated` | normal skill 默认 | system prompt、skill markdown、账号上下文/记忆、结构化 input、显式 artifact/resource refs |
| `summary` | 需要父级背景的 normal skill | `isolated` 内容 + 父级/会话摘要 + 关键事实 |
| `passthrough` | root skill、virtual function frame、少量系统可信 skill | root 的压缩后上下文快照 + 当前调用输入 |

设计原则：

1. normal skill 默认不看完整主上下文。
2. root skill 可以看任务主上下文，但应看“收敛后的上下文”，不是无限 transcript。
3. virtual function frame 可以拿到调用当时的上下文快照，用于审批解释、审计和恢复，但不应向业务函数泄露不必要的大段对话。
4. 是否穿透必须是 manifest/policy 明确声明或系统内置规则，不能由普通 skill 任意打开。

### 6. 系统提示词和记忆文件随 skill 注入

不启用上下文穿透时，skill 仍应获得以下基础内容：

1. 平台系统提示词。
2. skill 自身 `SKILL.md` / manifest 指令。
3. 账号级上下文提示。
4. 用户长期记忆或当前策略允许的记忆摘要。
5. 本次 skill 的结构化 input。

区别在于：不穿透时不带 root/main 的完整会话上下文，只带必要摘要、显式输入和引用。

### 7. root skill 触发上下文收敛

root skill 第一层内容会在任务生命周期内长期存在，值得优先引入上下文收敛。

建议增加 `ContextBudgetManager`，第一阶段可以做轻量实现：

1. 按 token 估算或字符数限制预算。
2. 保留当前用户请求、最新执行计划、用户关键选择、业务事实、pending approval/suspend 状态。
3. 保留最近 N 次工具调用和结果摘要。
4. 保留子 skill 的 promoted result。
5. 大 payload、完整函数返回、附件内容、历史 tool output 外置为 artifact/resource ref。
6. 对完成的子任务细节做摘要，不再完整塞回 root skill。

上下文收敛产物建议拆成：

```text
root_context_summary
business_facts
pending_actions
recent_events
artifact_refs
memory_refs
```

## 推荐运行时流程

### root skill 调用业务函数

```text
root skill LLM emits invoke_business_function(args)
  -> runtime validates function exists/enabled
  -> runtime validates user/function grant
  -> runtime creates virtual function frame
  -> runtime snapshots compacted root context
  -> if approval required: persist Run/Suspend state and pause
  -> if approved or no approval required: execute function
  -> persist function result/error
  -> return compact result to root skill tool-call loop
```

### normal skill 调用业务函数

```text
normal skill LLM emits invoke_business_function(args)
  -> runtime creates virtual function frame under normal skill frame
  -> runtime uses caller frame policy to decide context snapshot shape
  -> authorization and approval use the same function-call path
  -> compact result returns to normal skill
  -> promoted result returns to parent/root according to manifest
```

### root skill 调用 normal skill

```text
root skill LLM emits invoke_business_skill(args)
  -> runtime creates normal skill frame
  -> runtime prepares scoped input
  -> context_visibility defaults to isolated or summary
  -> child skill runs
  -> child closes and promotes manifest-defined result
  -> root context receives promoted result, not full child transcript
```

### 审批后确定性消息

审批通过或拒绝后，不让 LLM 再生成一段“继续处理”的自然语言回复。恢复链路由运行时返回一个确定性 `resume_message`，Java 侧收到 Worker resume 响应后把它投递为会话消息。

消息来源优先级：

1. 本次业务函数调用参数或 FSScript 调用时注入的 `post_approval_message`。
2. 函数注册或 Gateway 返回的默认审批后文案。
3. 平台兜底文案。

推荐模板结构：

```json
{
  "post_approval_message": {
    "approved": "审批已通过，已继续提交关单申请。",
    "rejected": "审批已拒绝，关单申请未提交。",
    "expired": "审批已超时，本次操作未继续执行。",
    "default": "审批结果已更新。"
  }
}
```

FSScript 场景下，脚本可在暂停 summary 中携带同名字段；Worker resume 会从 `summary.post_approval_message` 解析并返回给 Java。普通业务函数场景下，调用入参或 Gateway 返回中也使用同名字段。

这条消息表达的是“审批结果已进入系统恢复链路”。真实业务副作用的执行结果由 Java 业务函数/暂停服务负责。当前实现中，`BusinessFunctionSuspensionService` 会在业务函数 adapter 成功或失败后再投递第二条确定性会话消息：

```json
{
  "subtype": "business_function_result_message",
  "content": "业务函数执行完成。",
  "status": "SUCCESS",
  "executionStatus": "COMPLETED",
  "suspendId": "sus_xxx",
  "functionId": "close_order",
  "version": "v1"
}
```

因此审批恢复链路通常产生两类用户可见消息：

1. `post_approval_message`：审批通过/拒绝后，Worker 恢复链路确认“已继续/已停止”。
2. `business_function_result_message`：Java 侧真实业务函数执行完毕后，确认业务副作用成功或失败。

## 主流做法复盘

这里的“主流”不是单一标准，而是当前 Agent 框架中较稳定的几个设计方向。

### 1. Skill/子 Agent 倾向隔离上下文

Anthropic Claude Code 的 subagents 官方文档强调子 agent 使用独立上下文窗口，并可配置自己的系统提示词和工具集。这说明“普通 skill 默认隔离”更符合主流方向。

本设计匹配点：

1. normal skill 默认 `isolated`。
2. 子 skill 只返回 promoted result，不把完整 transcript 泄回父级。
3. 不把主上下文无条件塞进每个 skill。

优化点：

1. 将“上下文穿透”限制为系统策略，而不是普通 skill 自声明即可生效。
2. 为 normal skill 优先提供摘要和 artifact refs，避免全量上下文污染。

### 2. Handoff 可以带历史，但应支持 input filter

OpenAI Agents SDK 的 handoff 模型中，接收 agent 默认可能看到先前对话历史，但官方也提供 `input_filter` 和 handoff history mapper 来过滤或改写传给下游 agent 的输入。

本设计的结论：

1. root skill 可以看主上下文，因为它是任务顶层接管者。
2. normal skill 不应默认继承完整历史。
3. 需要继承时，用 `summary` 或 `passthrough` 显式策略表达。
4. 实现上应提供类似 `input_filter` 的上下文映射接口，而不是把“是否穿透”散落在各调用点。

### 3. Tool approval 应是运行时级暂停恢复能力

OpenAI Agents SDK 的 HITL 设计把敏感工具调用标记为需要审批，执行会暂停，产生 interruption，审批后用持久化 state 恢复。审批可以发生在当前 agent、handoff 后的 agent、或 nested agent tool 内，但暂停项会回到外层 run 处理。

本设计匹配点：

1. 业务函数调用用 virtual function frame 包装。
2. 审批暂停不是 prompt 约定，而是运行时状态。
3. suspend/resume 要保存原始函数参数、调用 ID、审批状态、上下文快照和版本信息。

优化点：

1. 不要让业务函数直接在 tool handler 内“半执行半等待”。
2. 把 pending approval 作为任务级 interruption 暴露给 UI/上游。
3. 恢复时继续原 root skill run/frame，而不是创建一个新任务重新推理。

### 4. root skill 不宜变成普通 skill 的特例堆叠

主流框架通常区分：

1. 顶层 run/runner。
2. agent/subagent。
3. tool/function call。
4. approval/interruption state。

因此本设计中建议把 `virtual function skill` 正式命名为 `virtual function frame`，并引入 `FrameKind=FUNCTION_CALL`。这样可以保持概念清晰：

1. root skill 是特殊 agent/skill。
2. normal skill 是可嵌套 agent/skill。
3. function frame 是工具调用实例，不是一个 LLM skill。

## 风险与约束

1. 如果 root skill 工具过多，模型选择会变差；需要按用户授权、ClientApp、场景动态裁剪工具描述。
2. 如果 passthrough 滥用，会削弱 skill 隔离，增加 token 成本和提示污染。
3. 如果 function frame 不持久化原始参数和状态，暂停恢复会不可靠。
4. 如果 SkillFunctionAllowlist 从硬校验降级，需要确保用户级授权、函数启用状态和审计足够清晰。
5. 如果 root context summary 没有结构化字段，后续很难稳定恢复业务事实。

## 分阶段落地建议

### Stage 1: root skill bootstrap

1. 增加系统 root skill 定义和 registry 识别。
2. BizWorker task 启动时直接创建 root skill frame。
3. root skill 复用 `LlmSkillAgent` 工具循环。
4. root skill 能调用 `invoke_business_function` 和 `invoke_business_skill`。

完成标准：

1. root skill 可直接调用一个已授权业务函数。
2. root skill 仍可调起普通 child skill。
3. 旧入口兼容或有明确迁移策略。

当前实现进展：

1. Python Worker 已内置 `system.root` manifest，`visibility=builtin`，普通 registry 路由不会把它当成用户技能暴露。
2. `route_skill` 在 `llm_execute_skills=true` 且存在可用模型时，直接进入 `system.root`，不再先走一次 root router 再强制选择普通 skill。
3. root frame 会按 `task_id` 复用；Worker 内存丢失后可从 `FileFrameJournal` 恢复 RUNNING 状态的 root frame。
4. root skill 复用 `LlmSkillAgent` 工具循环，显式具备 `invoke_business_function`、`invoke_business_skill`、`submit_skill_result`。
5. root skill 的 `submit_skill_result` 只结束当前 turn，frame 保持 `RUNNING`，不会触发普通 skill 的 close/销毁语义。
6. 已补测试覆盖：root frame 创建/复用/恢复、root 直接调用业务函数、root 调用 child skill 的 scripted E2E loop。

### Stage 2: function frame + pause/resume

1. 引入 `FrameKind=FUNCTION_CALL` 或等价模型。
2. 所有 `invoke_business_function` 自动创建 function frame。
3. 业务函数调用前统一做用户级授权。
4. 支持审批暂停、持久化、恢复。

完成标准：

1. pending approval 能在 UI/上游看见。
2. approve/reject 后能恢复同一函数调用。
3. 审计日志能看到 caller skill、function frame、用户授权和审批结果。

当前实现进展：

1. Python Worker 已在 `SkillFrameState` 上增加 `frame_kind = SKILL | FUNCTION_CALL`。
2. `invoke_business_function` 会自动创建 `FUNCTION_CALL` frame，并把它挂到 caller skill frame 下。
3. 非暂停结果会完成 function frame，并把函数结果归并到 caller frame 的 `private_working_state.function_results`。
4. `SUSPENDED` / `approvalRequired=true` / `approval_wait=true` 会同时挂起 function frame 和 caller skill frame，发出 `approval_required` SSE 事件，事件中携带 `suspend_id`、`function_id`、`function_frame_id` 和裁剪后的 approval payload。
5. `POST /api/v1/resume` 继续按 `taskId` 找到等待审批的 caller skill frame；恢复时会把 pending function frame 从 journal 拉回内存，并记录 `RESUME_DISPATCHED`。真实业务副作用仍由 Java Suspension service 负责。
6. `POST /api/v1/resume` 返回确定性 `resume_message`；Java `LanggraphWorkerResumeEventListener` 负责把该文案投递为会话 `TEXT_COMPLETE` 消息，避免审批后再交给 LLM 续写。
7. Java `BusinessFunctionSuspensionService` 会在 adapter 成功或失败后发布 `business_function_result_message` 类型的 `TEXT_COMPLETE` 会话消息，作为真实业务执行结果的确定性提示。
8. 已补跨模块闭环测试：审批通过后先由 Worker resume listener 投递 `post_approval_message`，再由 Java business suspension service 执行 adapter 并投递 `business_function_result_message`，同时断言两条消息落在同一个 worker session/task 下。
9. Java Worker Gateway 的 list/schema/invoke 运行时路径不再把 `SkillFunctionAllowlist` 作为硬门禁：函数列表来自 ClientApp 可见 function grant，schema/invoke 只要求 App/User/Skill 有效以及 function/version/grant 有效。

### Stage 3: context visibility + compaction

1. 引入 `context_visibility` 策略。
2. 增加 root context summary 结构。
3. normal skill 默认 isolated/summary。
4. virtual function frame 记录 compacted context snapshot ref。

完成标准：

1. normal skill 不再默认收到完整 root/main 上下文。
2. root skill 长任务不会无限增长 prompt。
3. function approval 页面能展示足够解释信息，但不会泄露无关上下文。

当前实现进展：

1. root/persistent frame 每次 `submit_persistent_turn_result` 会维护 `private_working_state.root_context_summary`。
2. `root_context_summary` 记录 `turn_count`、latest summary、裁剪后的 structured output、最近 turn 摘要、artifact refs、evidence refs。
3. persistent frame 会保守裁剪 `turn_results` 和 `private_messages`，避免 root frame 长期运行时无限增长。
4. 当前版本不调用 LLM 做语义摘要；先用结构化摘要和固定窗口裁剪落地第一阶段收敛。
5. `SkillManifest` 已增加 `context_visibility`，默认 `isolated`；`SKILL.md` 可通过 metadata `context-visibility` 声明。
6. child skill 调用时默认不会看到 root/main 上下文；只有 child manifest 标注 `context_visibility=summary` 时，`LlmSkillAgent` 才会在 user prompt 中注入 `Visible parent/root context summary`。
7. `system.root` 标注为 `context_visibility=passthrough`；业务 `FUNCTION_CALL` frame 创建时记录当前 root context summary 快照，作为审批/恢复/审计边界的上下文摘要。
8. 普通 skill 即使在 manifest 中声明 `context_visibility=passthrough`，第一阶段运行时也会降级为 `isolated`；`passthrough` 只对 `visibility=builtin` 或 `system.*` 内置 skill 生效。
9. Java 控制面 `SkillEntity` / `SkillBundleEntity` / 创建与同步表单 / DTO 已透出 `contextVisibility`；普通业务 skill 只保存和下发 `isolated` 或 `summary`，`passthrough` 在控制面和 Worker materialize 入口都会降级为 `isolated`。
10. Worker `/api/v1/skills/materialize` 已支持 `context_visibility` 入参，并写入 `SKILL.md` metadata `context-visibility`，由 `SkillRegistry` 加载成 `SkillManifest.context_visibility`。
11. `navigator-open-sdk` 的 skill、skill bundle、account skill bundle、agent bundle 表单与 DTO 已透出 `contextVisibility`；CLI manifest 可直接声明该字段并透传到控制面/runtime 同步接口。

## 待确认问题

1. root skill ID 决定使用 `system.root`。`system.*` 命名空间为平台内置保留，普通 ClientApp 不能创建、覆盖或删除。
2. function frame 第一阶段扩展现有 `SkillFrameState`，增加 `frame_kind = SKILL | FUNCTION_CALL`，暂不新增独立 `BusinessFunctionCallFrame`。
3. `context_visibility=passthrough` 第一阶段只允许系统内置 frame 使用：`system.root` 和 `FUNCTION_CALL` frame。普通 skill 默认 `isolated`，最多通过服务端 policy 授权到 `summary`。
4. root context summary 第一阶段由 Python Worker 维护，作为模型工作上下文；Java 侧继续保留完整会话、事件、审批、审计和原始消息，是权威历史存储。
5. 恢复 pending function call 时，语义上继续同一个 task/root frame/function frame 的 loop；实现上不要求恢复同一次 LLM HTTP 调用，而是恢复持久化 frame 后重新进入 root skill tool loop，并把函数结果作为 tool result/恢复事件注入。

## 已确认设计口径

1. root skill 是特殊 skill，稳定 ID 为 `system.root`。
2. root skill 是任务/会话级常驻入口，没有普通 skill 的退出语义，也不需要 `close_frame` 销毁私有上下文。
3. 每个 BizWorker 请求先进入 root skill，再由 root skill 在同一个 tool-call loop 内决定回答、调普通 skill 或调用业务函数。
4. `invoke_business_function` 仍只出现在 skill loop 中；root skill 因为本身就是 skill，所以可以自然获得业务函数调用能力。
5. Java 侧保完整历史账本，Worker 侧保给模型使用的可压缩工作上下文。
6. pending function call resume 的目标是恢复当前执行链路，而不是创建一个全新任务重新推理。
7. 审批后的用户可见文案由 Worker/Java 恢复链路确定性投递，文案来源按调用注入、函数注册默认、平台兜底的顺序选择，不由 LLM 生成。

## 参考

1. Anthropic Claude Code Subagents: https://docs.anthropic.com/en/docs/claude-code/sub-agents
2. OpenAI Agents SDK Handoffs: https://openai.github.io/openai-agents-python/handoffs/
3. OpenAI Agents SDK Human-in-the-loop: https://openai.github.io/openai-agents-python/human_in_the_loop/
4. OpenAI Agents SDK RunConfig: https://openai.github.io/openai-agents-python/ref/run_config/
