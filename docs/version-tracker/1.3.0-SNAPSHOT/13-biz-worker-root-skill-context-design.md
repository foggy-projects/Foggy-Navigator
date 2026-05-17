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

### Stage 4: conversation-scoped root frame and interrupted-frame continuation

用户视角下，“继续”不要求恢复旧的 LLM HTTP 调用或旧的 Python call stack，但必须延续当前业务 frame 的上下文。也就是说：

```text
new user turn / new lgt_* task
  -> locate current conversation root frame
  -> append new user instruction into that frame context
  -> restart LLM loop on the same frame state
  -> let model continue planning from preserved frame context
```

#### 已修正的实现偏差

修正前 `system.root` 实现仍是 task-scoped：

1. `SkillFrameState` 只有 `task_id`，没有稳定的 `session_id` / `context_id` / `conversation_id` 归属字段。
2. `FrameStore` 只维护 `frame_id` 和 `task_id` 索引。
3. `FileFrameJournal` 的目录结构是 `frames/<task-id>/<frame-id>.json`。
4. `root_graph._get_or_create_system_root_frame(task_id, context)` 只调用 `_runtime.get_frames_by_task(task_id)` 和 `_journal.load_by_task(task_id)`。
5. 因此同一会话中每次新建 `lgt_*` task，都会默认创建新的 `system.root` frame。只有同一个 taskId 才会复用 root frame。

这与“root skill 是 conversation/context 级常驻入口”的目标口径不完全一致。更准确地说，修正前实现已完成“root skill 持久不 close”，但它的持久范围仍绑定到单次 task，而不是绑定到 conversation/context。

#### 目标语义

1. `task` 表示一次用户输入触发的一轮执行，可以每次新建。
2. `root frame` 表示同一业务 conversation/context 的长期工作上下文，应跨 task 复用。
3. `normal skill frame` 表示一次子技能执行。正常完成后可以 close；非正常中断时应保留为可恢复上下文。
4. `function frame` 表示一次业务函数调用实例。审批、拒绝、执行成功或失败都应可审计，并向 caller frame 写入确定性结果。
5. 用户输入“继续”只是新 task 的 prompt；系统不应依赖关键词本身，而应根据 session/context 找到当前 active/recoverable frame。

#### root frame 作用域

root frame 查找键建议按优先级确定：

```text
conversation_key =
  contextId
  || foggy_session_id
  || session_id
  || task_id fallback
```

推荐在 `SkillFrameState` 增加：

```text
conversation_id: str | None
session_id: str | None
current_task_id: str | None
origin_task_id: str
last_task_ids: list[str]
```

其中：

1. `origin_task_id` 是创建 root frame 的第一轮 task。
2. `current_task_id` 是当前正在驱动该 frame 的 task。
3. `last_task_ids` 用于审计和排查，避免丢失跨 task 关联。
4. 旧 `task_id` 字段短期可保留，兼容已有 journal、approval resume 和测试。

#### frame 状态建议

当前 `FrameStatus` 为：

```text
CREATED, RUNNING, WAITING_CHILD, AWAITING_APPROVAL, COMPLETED, FAILED, CANCELLED
```

为支持用户中断后继续，建议补充或等价表达：

```text
PAUSED
INTERRUPTED
RECOVERABLE_FAILED
```

第一阶段可以不立刻扩枚举，而是在 `private_working_state` 中记录：

```json
{
  "continuation_state": "INTERRUPTED",
  "interrupt_reason": "user_cancelled | model_error | approval_rejected | stream_error",
  "last_error": "...",
  "last_task_id": "lgt_xxx",
  "recoverable": true
}
```

这样可以减少状态机改动，但要保证 root frame 不被 close，也不清理 `private_messages`、`private_working_state`、`tool_calls`。

#### 中断处理规则

用户中止、模型调用失败、Worker stream error、审批拒绝都应写入当前 frame：

1. Java task 可以进入 `ABORTED` 或 `FAILED`。
2. Python root frame 不应直接销毁上下文。
3. 当前 active frame 应记录中断原因、最近工具调用、pending child/function frame、错误摘要。
4. 如果是普通 child skill 中断，root frame 应记录该 child 的 recoverable 状态。
5. 如果是审批拒绝，function frame 可进入 `CANCELLED`，caller/root frame 记录拒绝事实和下一步待用户确认事项。

#### 新 task 继续规则

当同一 conversation/context 收到新 task：

1. 先查找已有 `system.root` frame。
2. 如果 root frame 为 `RUNNING`、`AWAITING_APPROVAL`、`RECOVERABLE_FAILED` 或带 `recoverable=true` 的中断状态，则复用该 frame。
3. 将新 task 的 prompt 作为新的 user turn 写入 root frame private context。
4. prompt 中注入上次中断摘要：

```text
Previous execution was interrupted.
Reason: <interrupt_reason>
Last active frame: <skill_id/frame_id>
Last tool call: <tool/function>
Pending action: <pending_action>
User's new instruction: <new prompt>
```

5. 重新启动 LLM tool loop，让模型基于 frame 中保留的信息判断继续、搁置或澄清。
6. 新 task 的消息、状态和终态仍写到新的 `lgt_*`，但 frame 上记录 `current_task_id` 和 `last_task_ids` 关联。

其中“可恢复中断”只表示旧 frame 是候选上下文，不表示本轮必须继续旧任务。root prompt 需要明确：

1. 用户明确继续、补充、修正上一任务时，沿用中断 frame 的上下文继续工作。
2. 如果上一任务中断点位于 child skill，root prompt 会带入 `pending_recoverable_child` 摘要；用户明确继续时，模型应调用 root-only 工具 `resume_recoverable_child_skill`，恢复同一个 child frame，而不是重新创建 child skill。
3. 用户明确中止，或输入与上一任务无关的新任务时，不继续旧任务；模型应总结被搁置的工作，并调用 root-only 工具 `shelve_interrupted_frame`，写入 `decision = ABANDON_PREVIOUS | START_UNRELATED_NEW_TASK` 和 `abandoned_interruption` 摘要。
4. 对涉及审批、业务副作用或高风险函数的模糊指令，先要求澄清，不擅自继续。
5. 成功提交本轮 persistent turn 后，runtime 清除 active interruption，并把中断摘要归档到 `root_context_summary.interruption_history`，方便用户后续“回到刚才”。

#### 不恢复旧 loop

本设计明确不要求：

1. 恢复旧 HTTP/SSE 连接。
2. 恢复旧 LangGraph invoke 调用栈。
3. 恢复旧模型 streaming response。
4. 依赖“继续”关键词做特殊分支。

需要恢复的是：

1. root frame 的工作记忆。
2. 当前 active/recoverable child frame 信息。
3. pending approval/function/child skill 的结构化状态。
4. 已完成工具调用和业务事实摘要。

#### 完成标准

1. 同一 session/context 下连续创建两个 `lgt_*` task，第二个 task 复用第一个 task 创建的 `system.root` frame。
2. 第一个 task 因模型错误、用户取消或审批拒绝中断后，第二个 task 输入“继续”或任何新指令，LLM prompt 中能看到上一轮 frame 中断摘要和关键上下文。
3. Java task 终态仍按单轮执行独立收敛，不影响 root frame 继续存在。
4. Worker journal 能按 conversation/context 找到 root frame，而不是只能按 taskId 找到。
5. approval resume 仍能通过 taskId 找到 pending frame；跨 task continuation 不破坏已有审批恢复链路。

#### 本轮实现进展

已完成：

1. `SkillFrameState` 增加 `conversation_id`、`session_id`、`current_task_id`、`origin_task_id`、`last_task_ids`。
2. `FrameStore` 增加 conversation 索引，并支持 frame 重新绑定到当前 task。
3. `FileFrameJournal` 保留 `frames/<task-id>/<frame-id>.json`，同时增加 `frames/by-conversation/<conversation-id>/<frame-id>.json` 镜像，兼容旧 taskId resume。
4. `root_graph` 优先按 `contextId/context_id/conversationId/conversation_id/foggy_session_id/session_id` 查找并复用 `system.root`，复用时将 frame 绑定到当前 `lgt_*` task。
5. `SkillRuntime.record_recoverable_interruption` 支持把模型错误、审批拒绝等记录为可恢复中断；`LlmSkillAgent` 在 persistent frame 新 turn prompt 中注入上次中断摘要、用户新指令和 continuation decision policy。
6. `submit_persistent_turn_result` 成功后清理中断标记，避免后续 turn 继续携带已处理的中断提示；如果本轮处理了中断，会把中断原因、处理决策和搁置摘要归档到 `root_context_summary.interruption_history`。
7. Python Worker 增加 `POST /api/v1/frames/interruption`，用于 Java 在用户取消、SSE 断流等旧 loop 已终止的场景下，确定性记录 root frame 的可恢复中断状态。
8. Java `LanggraphWorkerClient` 增加 `recordInterruption`；`LanggraphTaskService.cancelTask` 会在任务置为 `ABORTED` 后通知 Worker；`LanggraphStreamRelay` 在 SSE stream error 时先记录中断再将任务置为 `FAILED`。
9. 如果 root frame 正处于 `AWAITING_APPROVAL` 且收到用户取消/审批拒绝类中断，Worker 会把该 approval 归一为 rejected，使 root frame 回到可复用的 `RUNNING + recoverable=true` 状态。
10. Python Worker 增加 root-only `shelve_interrupted_frame` 工具，只在 `system.root` persistent frame 暴露；普通 child skill 不可见。该工具会确定性结束当前 turn，清理 active interruption，并把旧中断任务归档到 `root_context_summary.interruption_history`。
11. 如果用户在 child skill 执行期间中止，root frame 处于 `WAITING_CHILD` 时，`POST /api/v1/frames/interruption` 会先把 active child 标记为 recoverable，并把 root 从 `WAITING_CHILD` 恢复为 `RUNNING`，避免后续新 task 找到 root 后卡在等待 child 的状态。
12. root frame 会记录 `pending_recoverable_child_frame_id` 和 `pending_recoverable_child` 摘要；新 turn 的 prompt 会显式提示“可恢复 child 是候选，不是强制继续”。
13. Python Worker 增加 root-only `resume_recoverable_child_skill` 工具。用户明确“继续”时，root 可恢复同一个 child frame，child 完成后按原有 `complete_child_and_resume_parent` 路径 close/promote，并清理 root 上的 pending child 引用。
14. 当用户明确中止或切换到无关任务时，`shelve_interrupted_frame` 会清理 root 上的 pending child 引用，并把未完成 child frame 标记为 `CANCELLED + continuation_state=SHELVED`，避免 stale child 在后续 turn 被误恢复。

测试证据：

1. `tests/test_root_graph.py::test_route_skill_reuses_system_root_frame_across_tasks_in_same_session`
2. `tests/test_root_graph.py::test_route_skill_restores_system_root_frame_by_session_for_new_task`
3. `tests/test_llm_skill_agent.py::test_llm_agent_persistent_frame_prompt_includes_recoverable_interruption_context`
4. `tests/test_llm_skill_agent.py::test_llm_agent_persistent_frame_model_error_records_recoverable_interruption`
5. `tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_root_skill_reuses_frame_across_tasks`
6. `tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_root_skill_continues_after_recoverable_model_loop_failure`
7. `tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_root_skill_continues_after_user_cancelled_interruption`
8. `tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_root_skill_shelves_interruption_for_unrelated_task`
9. `tests/test_frame_lifecycle.py::TestPersistentTurnResult::test_persistent_turn_archives_interruption_when_user_switches_task`
10. `tests/test_llm_skill_agent.py::test_llm_agent_persistent_root_exposes_shelve_interrupted_frame_tool`
11. `tests/test_llm_skill_agent.py::test_llm_agent_non_persistent_frame_does_not_expose_shelve_tool`
12. `tests/test_llm_skill_agent.py::test_llm_agent_persistent_frame_prompt_includes_pending_recoverable_child`
13. `tests/test_llm_skill_agent.py::test_llm_agent_root_resumes_pending_recoverable_child_frame`
14. `tests/test_llm_skill_agent.py::test_llm_agent_shelve_clears_pending_recoverable_child_frame`
15. `tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_root_skill_resumes_interrupted_child_frame`
16. `tests/test_frame_interruption.py`
   - running root frame 记录 recoverable interruption
   - journal 按 conversation 恢复并 rebind 到新 task
   - 用户取消 awaiting root frame 后回到可复用状态
   - 用户取消 waiting-child root frame 后，active child 记录为 recoverable，root 回到 `RUNNING`
   - frame 未找到时返回 `not_found`，不阻断 Java cancel/fail 链路
17. Java 单测：
   - `LanggraphWorkerClientTest::recordInterruption_postsRecoverableFramePayload`
   - `LanggraphTaskServiceTest::cancelTask_records_recoverable_interruption_on_worker`
   - `LanggraphStreamRelayTest::streamErrorRecordsRecoverableInterruptionBeforeFailingTask`
18. `tools/langgraph-biz-worker` 全量 pytest：`388 passed, 6 skipped`
19. Java reactor 到 `addons/langgraph-biz-worker`：`mvn -pl addons/langgraph-biz-worker -am -Dsurefire.failIfNoSpecifiedTests=false test` 通过

### Stage 5: recoverable focus frame and intent resolution

Stage 4 已经解决了 root frame 复用、child skill 中断后继续、以及用户切换任务时搁置旧 child 的基础问题。Stage 5 的目标是把这些能力从“child skill 专用分支”提升为更通用的 frame focus 机制，并让 root prompt 明确区分用户新输入到底是在继续旧任务、放弃旧任务、开启无关新任务，还是需要澄清。

#### 1. 通用 recoverable focus frame

当前实现以 `pending_recoverable_child_frame_id` 和 `pending_recoverable_child` 表达“当前可恢复 child”。该字段可以保留为兼容层，但后续主语义应迁移到 `recoverable_focus_*`：

1. `recoverable_focus_frame_id`：当前最值得恢复的 frame ID。
2. `recoverable_focus_kind`：`ROOT | CHILD_SKILL | FUNCTION_CALL | APPROVAL | NESTED_SKILL`。
3. `recoverable_focus_status`：`INTERRUPTED | AWAITING_USER | AWAITING_APPROVAL | FAILED | SHELVED | RESUMED`。
4. `recoverable_focus_interrupted_at`：中断发生时间。
5. `recoverable_focus_summary`：给 root LLM 使用的压缩摘要，包含 skill/function、reason、error、last tool call、pending action、用户可见风险。
6. `recoverable_focus_stack`：从 root 到最深 active frame 的 stack 快照，用于后续支持嵌套 skill 恢复。

短期实现要求：

1. child skill 中断时，同时写入旧字段和新字段。
2. `resume_recoverable_child_skill` 继续使用旧字段兼容现有 E2E，但 prompt 优先展示 `recoverable_focus_summary`。
3. 搁置、完成、恢复 focus 时，同时清理旧字段和新字段。
4. 不在 Stage 5 直接删除任何 `pending_recoverable_child_*` 字段，避免破坏上游验证脚本和旧 journal。

#### 2. 显式 intent resolution

用户在中断后输入新内容时，root LLM 需要先判断新输入与上一个 focus frame 的关系。Stage 5 固化以下枚举：

1. `CONTINUE_PREVIOUS`：用户明确继续、补充、修正上一任务。执行策略是恢复 `recoverable_focus_frame_id`，child skill 场景调用 `resume_recoverable_child_skill`。
2. `ABANDON_PREVIOUS`：用户明确中止、放弃、不再处理上一任务。执行策略是总结旧 focus 并调用 `shelve_interrupted_frame`。
3. `START_UNRELATED_NEW_TASK`：用户提出明显无关的新任务。执行策略是先搁置旧 focus，再按新任务继续 root loop。
4. `ASK_CLARIFICATION`：用户意图不明，且继续可能触发审批、业务副作用或错误业务动作。执行策略是向用户澄清，不恢复旧 focus，也不擅自搁置。

实现口径：

1. root prompt 明示这四种 intent，并要求模型在调用 `shelve_interrupted_frame` 或提交 persistent result 时写入 `intent_resolution`。
2. `shelve_interrupted_frame` 继续接受旧的 `decision = ABANDON_PREVIOUS | START_UNRELATED_NEW_TASK`，并可附加 `intent_resolution`，runtime 内部归一后写入 `structured_output.intent_resolution`。
3. 对 `CONTINUE_PREVIOUS`，工具结果需要回写 `intent_resolution=CONTINUE_PREVIOUS`，方便审计和 E2E 断言。
4. 对 `ASK_CLARIFICATION`，不新增强制工具；模型可以直接通过 `submit_skill_result` 回复澄清问题，但结构化输出中应保留该 intent。

#### 3. 分层 context summary

root frame 会长期存在，不能简单把所有历史全部注入每次 LLM prompt。Stage 5 将 root 侧可压缩上下文明确分层：

1. `root_context_summary.recent_turns`：最近若干轮用户输入、root 输出和关键 tool result。
2. `root_context_summary.business_facts`：已确认的业务实体、参数、约束、用户偏好。
3. `root_context_summary.pending_actions`：待用户确认、待审批、待恢复、待重试的动作。
4. `root_context_summary.focus_history`：曾经中断、恢复、搁置、完成的 focus 摘要。
5. `root_context_summary.artifact_refs` / `evidence_refs`：大对象、附件、执行证据只保留引用，不重复灌入 prompt。
6. `root_context_summary.audit_refs`：审批、授权、业务函数执行记录的审计引用。

实现口径：

1. Stage 5 先补 focus 相关 summary 写入，不立刻实现完整 token-budget 收敛器。
2. 之后当 root prompt 超预算时，按上述层级做压缩：recent window 优先保留，旧 turn 收敛为 business facts、pending actions、focus history 和 refs。
3. Java 侧仍保留完整会话、事件、审批与业务执行记录；Worker summary 只作为模型工作上下文，不作为权威历史。

#### 4. 嵌套 focus stack

由于 skill 支持嵌套，未来中断点可能不是 root 的直接 child，而是 `root -> skill A -> skill B -> function/approval`。Stage 5 明确以下路线：

1. 中断发生时，runtime 应寻找最深的 non-terminal active descendant，作为 `recoverable_focus_frame_id`。
2. `recoverable_focus_stack` 记录 root 到该 frame 的路径，每一层包含 `frame_id`、`skill_id`、`frame_kind`、`status`、`input` 摘要和 `parent_frame_id`。
3. 继续时，root prompt 看到的是“当前 focus 在某个嵌套 frame 内”，而不是只看到直接 child。
4. 第一阶段恢复执行仍可沿用“恢复 root 的直接 child frame”的实现，只要 stack 元数据已经存在；真正递归恢复到最深 frame 可以作为后续 Stage。
5. 搁置时，需要把 stack 上未完成 frame 都标记为已搁置或可审计的取消状态，防止后续旧 frame 被误恢复。

#### Stage 5 完成标准

1. child skill 中断后，root working state 同时具备 `pending_recoverable_child_*` 和 `recoverable_focus_*` 字段。
2. root 新 turn prompt 能看到 `recoverable_focus_summary`、`recoverable_focus_stack` 和四类 `intent_resolution` 规则。
3. 用户输入“继续”时，mock LLM 可基于 prompt 调用 `resume_recoverable_child_skill`，同一个 child frame 被恢复，完成后新旧 focus 字段都被清理。
4. 用户明确中止或提出无关任务时，mock LLM 可调用 `shelve_interrupted_frame`，structured output 记录归一后的 `intent_resolution`，新旧 focus 字段都被清理。
5. waiting-child 中断的 frame interruption 回归测试断言 focus stack 存在，且至少包含 root 和 child。
6. 不破坏 BUG-021 及 follow-up 的审批恢复、业务函数结果回写和 task terminal status 收敛逻辑。

#### Stage 5 实现进展

已完成：

1. Python Worker runtime 新增 `recoverable_focus_frame_id`、`recoverable_focus_kind`、`recoverable_focus_status`、`recoverable_focus_interrupted_at`、`recoverable_focus_summary`、`recoverable_focus_stack` 写入和清理逻辑。
2. waiting-child 中断时，root 会同时保留旧的 `pending_recoverable_child_*` 字段和新的 `recoverable_focus_*` 字段；`record_recoverable_interruption` 不再覆盖 child interruption 已写入的 focus。
3. `recoverable_focus_stack` 已按 root 到最深 active descendant 生成。当前恢复执行仍保持兼容：`resume_recoverable_child_skill` 恢复 root 的直接 child frame；stack 元数据为后续深层递归恢复做准备。
4. `resume_recoverable_child_skill` 成功工具结果回写 `intent_resolution=CONTINUE_PREVIOUS`。
5. `shelve_interrupted_frame` 支持并归一 `intent_resolution`，structured output 同时保留旧的 `continuation_decision`，兼容现有前端和上游断言。
6. `submit_persistent_turn_result` 对 `intent_resolution=ASK_CLARIFICATION` 保留 recoverable focus 和 pending child，避免用户澄清后无法继续旧 frame。
7. root recoverable prompt 已注入 `Recoverable focus`、`Recoverable focus stack` 和四类 intent policy：`CONTINUE_PREVIOUS`、`ABANDON_PREVIOUS`、`START_UNRELATED_NEW_TASK`、`ASK_CLARIFICATION`。
8. root context summary 的 `interruption_history` 会记录 focus 摘要；同时维护 `focus_history`，作为后续分层上下文收敛的起点。

测试证据：

1. `tests/test_frame_interruption.py::test_user_cancelled_waiting_child_records_child_and_reuses_root`
   - 断言 waiting-child 中断后 root 具备 `recoverable_focus_*`，且 stack 包含 root 和 child。
2. `tests/test_llm_skill_agent.py::test_llm_agent_persistent_frame_prompt_includes_recoverable_interruption_context`
   - 断言 root prompt 包含 focus、focus stack、四类 intent 和 `intent_resolution`。
3. `tests/test_llm_skill_agent.py::test_llm_agent_persistent_frame_prompt_includes_pending_recoverable_child`
   - 断言 pending child 与 recoverable focus 同时出现在 prompt。
4. `tests/test_llm_skill_agent.py::test_llm_agent_root_resumes_pending_recoverable_child_frame`
   - 断言继续后同一个 child frame 被恢复，且新旧 focus 字段清理。
5. `tests/test_llm_skill_agent.py::test_llm_agent_shelve_clears_pending_recoverable_child_frame`
   - 断言搁置后 pending child 和 recoverable focus 均清理，child 标记为 `CANCELLED + SHELVED`。
6. `tests/test_llm_skill_agent.py::test_llm_agent_ask_clarification_keeps_recoverable_focus`
   - 断言 `ASK_CLARIFICATION` 不丢失中断 focus，用户后续仍可继续。
7. `tests/test_frame_interruption.py::test_nested_waiting_child_records_deepest_recoverable_focus`
   - 断言嵌套 skill 中断时 focus 指向最深 active descendant，搁置时 stack 上未完成 frame 均进入 `CANCELLED + SHELVED`。
8. `tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_root_skill_shelves_interruption_for_unrelated_task`
   - mock LLM E2E 断言无关新任务搁置旧 focus，result 带 `intent_resolution`。
9. `tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_root_skill_resumes_interrupted_child_frame`
   - mock LLM E2E 断言“继续”恢复同一个 child frame，prompt 具备 focus 与 intent 信息。
10. 相关定向回归：`40 passed, 3 warnings`。
11. `tools/langgraph-biz-worker` 全量 pytest：`390 passed, 6 skipped, 10 warnings`。

#### Stage 5 收口验证（2026-05-17）

本轮收口重点验证“用户中止 / 模型调用失败 / 审批拒绝后，下一轮用户输入可以在当前 recoverable frame 上继续规划”，并确认 Java 可见任务链路与 Python Worker frame runtime 的契约一致。

代码链路复核：

1. Java `LanggraphTaskService.cancelTask(...)` 会在任务标记 `ABORTED` 后调用 Worker `/api/v1/frames/interruption`，reason 为 `user_cancelled`。
2. Java `LanggraphStreamRelay.handleStreamError(...)` 会在任务 `FAILED` 前记录 recoverable interruption，reason 为 `stream_error`。
3. Java `LanggraphWorkerClient.recordInterruption(...)` 发送 `taskId`、`session_id`、`context_id`、`reason`、`error` 和上下文快照。
4. Python Worker `/api/v1/frames/interruption` 会优先按 `taskId`，再按 conversation/context/session 查找 `system.root`，并支持 `RUNNING`、`WAITING_CHILD`、`AWAITING_APPROVAL` root frame。
5. `WAITING_CHILD` root 会记录 child recoverable focus；`AWAITING_APPROVAL` root 在 `user_cancelled` / `approval_rejected` 下先归一为 rejected，再记录 root recoverable interruption。

验证结果：

1. Java reactor：`mvn -pl addons/langgraph-biz-worker -am '-Dsurefire.failIfNoSpecifiedTests=false' test`
   - `820` tests, `0` failures, `0` errors, `0` skipped。
   - 覆盖 `LanggraphWorkerClientTest.recordInterruption_postsRecoverableFramePayload`、`LanggraphTaskServiceTest.cancelTask_records_recoverable_interruption_on_worker`、`LanggraphStreamRelayTest.streamErrorRecordsRecoverableInterruptionBeforeFailingTask`、approval resume/result listener 相关回归。
2. Python Worker：`.\.venv\Scripts\python.exe -m pytest`
   - `390 passed, 6 skipped, 10 warnings`。
   - 覆盖 mock LLM E2E：继续同一 interrupted child、无关任务搁置旧 focus、root prompt 注入 focus stack 与 intent policy。
3. `git diff --check` 已纳入收口检查；当前仅文档文件在 Windows 下提示 LF/CRLF 转换风险，无代码空白错误。

质量自检结论：

1. 本轮实现保持在 Python Worker runtime / root LLM prompt / mock E2E 测试范围，Java 侧未改业务代码，只做契约复核和测试验证。
2. `pending_recoverable_child_*` 仍保留，避免破坏现有上游脚本和旧 journal；新增 `recoverable_focus_*` 作为通用机制承载后续演进。
3. `ASK_CLARIFICATION` 明确保留 recoverable focus，避免用户澄清阶段丢失中断 frame。
4. 自检发现并修复一个嵌套 focus 清理边界：`shelve_recoverable_interruption` 先提交 turn result 会清掉 focus 字段，导致后续只清直接 child。修复后会在提交前捕获 focus frame ids，再清理整条 stack；`test_nested_waiting_child_records_deepest_recoverable_focus` 已改为走真实 shelve 路径验证。
5. 嵌套 frame 当前已能记录最深 active descendant 并在搁置时清理 stack；递归恢复到最深 frame 仍是后续增强项，当前恢复执行仍兼容恢复 root 的直接 child。
6. 本轮未执行真实 TMS 上游链路；真实环境复验仍需在包含本 Worker 代码的运行产物发布/重启后，由上游按 CLI + mock/真实 adapter 场景复测。

### Stage 6: agent delegation frame design

本节仅做设计落档，不进入本轮实现。当前系统进入 frame 的主要方式是 `SKILL` 和 `FUNCTION_CALL`。后续可以补一个受控的 agent delegation 能力，让 root skill 或普通 skill 在需要时主动创建“子 agent frame”，用于开放式分析、计划拆解、代码审查、并行资料核对等不适合建成固定业务 skill 的任务。

#### 设计结论

建议支持，但不应让 LLM 任意创建裸 frame。模型只能通过平台暴露的 delegation tool 创建受控 frame，例如：

1. `invoke_agent_frame`：同步调用一个子 agent，父 frame 进入等待，子 agent 结束后 promoted result 回到父 frame。
2. `invoke_parallel_agent_frames`：后续扩展，用一个 delegation group 并发创建多个子 agent frame，父 frame 等待聚合结果。
3. `create_detached_agent_run`：后续扩展，在当前会话外创建独立 agent run/session，通过 correlation ID 与当前 root frame 关联，完成后以结果消息、artifact refs 或 follow-up task 的方式回流。

这类能力与 `invoke_business_skill` 的关系：

1. `invoke_business_skill` 适合稳定、可注册、可授权、可复用的业务能力。
2. `invoke_agent_frame` 适合临时的、开放式的、偏推理/分析/审查/拆解的子任务。
3. 业务函数仍只应通过 skill/agent frame 内的受控工具调用，不能因为 agent delegation 绕过授权、审批和审计。

#### 两种执行域

这里必须区分两个容易混淆的能力：

1. `IN_SESSION_FRAME`：在当前 root/skill 会话内进入一个 child frame。
2. `DETACHED_SESSION`：创建一个独立于当前会话上下文的 agent run/session，但通过 delegation 关系与当前会话关联。

二者不是同一种实现的同步/异步版本，而是不同的上下文与生命周期模型。

##### IN_SESSION_FRAME

`IN_SESSION_FRAME` 适合“当前任务内的子推理/子执行”：

1. 子 agent 是当前 frame stack 的一部分。
2. 父 frame 通常进入 `WAITING_CHILD`。
3. 子 agent 只能看到父 frame 按 `context_visibility` 暴露的 summary / refs / passthrough 内容。
4. 子 agent 结果通过 close/promote 回到父 frame。
5. 中断、继续、搁置直接复用 `recoverable_focus_stack`。
6. 可见消息、tool audit、审批恢复默认归属当前 `lgt_*` task/context。

它的优点是语义强、上下文连续、结果确定性回到当前 loop。缺点是会占住当前 root/skill loop，不适合长时间后台任务或大量并发探索。

##### DETACHED_SESSION

`DETACHED_SESSION` 适合“委派一个独立 agent 去后台处理，然后把结果带回当前会话”：

1. runtime 创建新的 worker task/session/conversation，或调用平台现有 Agent 任务接口创建独立执行。
2. 当前 root frame 不必进入 `WAITING_CHILD`，可以继续响应用户。
3. 当前 root frame 只记录 `delegation_run_id`、`correlation_id`、`delegation_summary`、`handoff_artifact_refs` 和期望结果 schema。
4. detached agent 不默认看到当前 root private context，只能收到显式 handoff bundle：
   - task instruction
   - 必要业务事实
   - artifact/evidence refs
   - 权限快照
   - 输出契约
   - 回调/结果投递目标
5. detached agent 完成后，通过 deterministic result message 写回当前可见 context，或创建一条可被 root 下轮 prompt 读取的 `delegated_agent_result_message`。
6. 如果用户之后输入“继续”，root LLM 可以看到 delegation run 状态，再决定等待、读取结果、取消 detached run，或继续当前任务。

它的优点是可以长时间运行、并发运行、隔离上下文污染。缺点是结果回流、取消、权限快照、审计和 UI 展示都更复杂。

#### 推荐边界

1. “进入当前任务的子上下文”使用 `invoke_agent_frame`，属于 `IN_SESSION_FRAME`。
2. “开一个独立 agent 会话去办事”使用 `create_detached_agent_run`，属于 `DETACHED_SESSION`。
3. `invoke_parallel_agent_frames` 第一阶段应只表示当前会话内的并发 child frames；如果要后台并发，应另设 `create_parallel_detached_agent_runs` 或让 `create_detached_agent_run` 支持 batch。
4. detached run 不能继承完整主上下文，只能继承显式 handoff bundle。
5. detached run 的业务函数权限必须使用用户授权快照和函数级 policy，不允许因为独立会话而扩大权限。
6. detached run 的审批如果需要用户参与，应把 approval request 回写到原 visible context，避免审批卡在用户看不到的后台会话里。
7. detached run 完成、失败、取消都必须写一条 deterministic result message 回原 context；是否让 LLM 再总结是后续策略，不作为结果投递的前提。

#### FrameKind 规划

后续可扩展：

```text
FrameKind = SKILL | FUNCTION_CALL | AGENT_CALL | AGENT_GROUP
```

其中：

1. `AGENT_CALL` 表示一次子 agent 委派，拥有独立 system prompt、input、private messages、working state、tool audit 和 output contract。
2. `AGENT_GROUP` 表示一组并发 `AGENT_CALL` 的父级聚合 frame，也可以只作为 root/parent working state 中的 `delegation_group` 记录，是否实体化留到实现阶段决定。
3. `recoverable_focus_kind` 后续可扩展 `AGENT_CALL | AGENT_GROUP`，复用 Stage 5 的 focus stack、中断、继续、搁置机制。
4. `DETACHED_SESSION` 不一定要实体化为 `FrameKind`；更合理的是新增 `DelegationRun` / `AgentRunRef` 记录，并在 root frame summary 中保存引用。如果为了统一 UI 和审计，也可以在父 frame 下创建一个轻量 `AGENT_RUN_REF` proxy frame，但不应把 detached run 的完整私有上下文塞回当前 root frame。

#### Agent manifest / profile

子 agent 不应完全自由生成，应来自注册的 agent profile 或由 root 在受控 schema 下临时创建：

1. `agent_id` / `agent_name`
2. `instruction`：该 agent 的职责和边界。
3. `context_visibility`：默认 `summary`，只有平台内置或显式授权才能 `passthrough`。
4. `allowed_tools`：必须是父 frame allowlist 的子集，不能提权。
5. `allowed_business_functions`：必须继承用户授权和父 frame 授权，不能扩大权限。
6. `output_schema`：子 agent 必须提交结构化结果。
7. `max_turns` / `timeout` / `token_budget`
8. `side_effect_policy`：`read_only | approval_required | side_effect_allowed`。
9. `concurrency_policy`：是否允许与其他 agent 并发执行。

默认策略：

1. 临时 agent 默认 `read_only`。
2. 并发 agent 默认不能直接执行有副作用业务函数；如需执行，必须走 approvalRequired 或由父 frame 串行确认。
3. 子 agent 只能看到任务 prompt、父 frame 可见 summary、必要 artifact/evidence refs；不默认看到完整主上下文。

#### 同步调用语义

第一阶段建议只实现同步单子 agent：

1. 父 frame 调用 `invoke_agent_frame`。
2. runtime 创建 `AGENT_CALL` child frame。
3. 父 frame 进入 `WAITING_CHILD`。
4. 子 agent 在独立 loop 中运行，调用 `submit_agent_result` 或复用 `submit_skill_result` 提交结果。
5. runtime close child frame，按 output contract promote 到父 frame。
6. 父 frame 恢复 RUNNING，继续原 root/skill loop。

这一路径可最大化复用现有 child skill 生命周期、journal、recoverable focus 和审批恢复能力。

#### 并发调用语义

并发能力建议作为第二阶段，不直接混入同步实现：

1. 父 frame 调用 `invoke_parallel_agent_frames`，传入多个 agent spec。
2. runtime 创建 `delegation_group_id`，并创建多个 `AGENT_CALL` child frame。
3. 每个 child frame 独立运行，输出 `agent_result`。
4. 聚合策略必须显式声明：
   - `all_settled`：等待全部完成，失败也收集。
   - `fail_fast`：任一失败即停止剩余未完成 agent。
   - `first_success`：首个成功结果返回，其余取消。
   - `quorum`：达到 N 个成功后返回。
5. 父 frame 不直接读取 child private context，只接收 promoted result、artifact refs、evidence refs 和审计引用。
6. 并发子 agent 的 stream progress 统一归属到可见 root task/context，消息 metadata 需带 `delegation_group_id` 和 `agent_frame_id`。

需要注意：如果子 agent 可执行副作用函数，并发会引入幂等、重复审批、资源争用和顺序依赖问题。因此并发第一阶段应限制为 read-only 或 approvalRequired，禁止无审批副作用并发。

#### 中断、继续和搁置

agent delegation 必须复用 Stage 5：

1. 单个子 agent 中断时，root/parent 记录 `recoverable_focus_kind=AGENT_CALL`。
2. 并发组中断时，root/parent 记录 `recoverable_focus_kind=AGENT_GROUP`，summary 中列出每个 child 的状态。
3. 用户输入“继续”时，先按 intent resolution 判断是否恢复 agent frame/group。
4. 用户明确切换任务时，搁置 agent frame/group；未完成 child frame 进入 `CANCELLED + SHELVED`，已完成结果保留在 focus history。
5. `ASK_CLARIFICATION` 继续保持 recoverable focus，直到用户明确继续或搁置。
6. detached run 被取消或失败时，root frame 记录的是 delegation run 状态，而不是直接恢复 detached run 的内部 loop；用户要求继续时，可以选择重新派发、读取已有 partial artifacts，或转为当前会话内 frame 继续。

#### 非目标

1. 本轮不实现 `AGENT_CALL` / `AGENT_GROUP`。
2. 本轮不实现 `DETACHED_SESSION` / `DelegationRun`。
3. 不把模型升级成可任意创建 frame 或任意创建独立任务的低层 runtime controller。
4. 不允许子 agent 或 detached agent 绕过 user grant、function allowlist、approvalRequired 和 business audit。
5. 不在第一阶段支持无审批副作用函数并发执行。
6. 不要求并发 agent 共享私有上下文；共享只通过父 frame summary、artifact refs 和 promoted result。
7. 不把 detached run 的完整消息历史自动灌回当前 root prompt。

#### 后续实施建议

1. Stage 6A：实现同步 `AGENT_CALL`，复用 child skill 生命周期，只新增 agent profile、tool schema 和 output contract。
2. Stage 6B：接入 recoverable focus，对 `AGENT_CALL` 的中断、继续、搁置做单测和 mock LLM E2E。
3. Stage 6C：实现 `DETACHED_SESSION` 单 agent run，只支持显式 handoff bundle、deterministic result message 和取消。
4. Stage 6D：实现 read-only 并发 `AGENT_GROUP`，先不允许副作用业务函数。
5. Stage 6E：补 detached/parallel 的聚合策略、资源上限、UI/SSE 展示和审计查询。

## 真实冒烟与 Mock 固化计划

### 目标

本轮交付前需要做一次真实 LLM 冒烟采样，先确认模型在真实 prompt 下能按 root skill 设计自然选择工具、复用 frame、处理“继续/切换任务”意图。真实 LLM 采样通过后，再把模型返回的 tool-call 序列固化为 LLM Mock 脚本，后续 E2E 默认只依赖 Mock，不依赖真实模型可用性。

真实冒烟不直接替代自动化 E2E。它的定位是：

1. 采集真实模型返回。
2. 验证提示词和工具契约是否足够清晰。
3. 产出可审核的 SSE、frame journal、tool audit 证据。
4. 从通过样本中提炼稳定的 mock `turns`。

### 真实冒烟用例

#### RS-01：root skill 调用子 skill 并提交结果

输入：用户要求通过 `exception_triage` 分析异常订单 `ORD-REAL-SMOKE-001`。

预期：

1. Worker 打开 `system.root` frame。
2. root LLM 调用 `invoke_business_skill(skill_id=exception_triage)`。
3. child skill 调用 `mock_get_order`、`mock_get_vehicle_status` 收集证据。
4. child skill 调用 `submit_skill_result`，输出 `classification/recommended_action/confidence`。
5. root skill 收到 child promoted result 后调用 `submit_skill_result` 完成本轮。

采集重点：

1. root LLM 的 `invoke_business_skill` 参数。
2. child LLM 的证据工具调用顺序。
3. root final summary 是否未泄漏内部 frame/task id。

#### RS-02：同一 context 下 root frame 复用

输入：在同一 `session_id/contextId` 下发起第二个 task。

预期：

1. 第二个 task 的 `skill_frame_open` 为 `Reusing frame for skill: system.root`。
2. frame journal 中 `system.root` 的 `frame_id` 保持不变。
3. `current_task_id` 更新为新 task，root 私有上下文继续保留。

采集重点：

1. 两次 SSE 的 `skill_frame_id`。
2. `data/frames/by-conversation/<contextId>` 的 root frame 快照。

#### RS-03：用户输入“继续”后沿用 recoverable 上下文

输入：在第一轮后通过 `POST /api/v1/frames/interruption` 记录一次 `user_cancelled`，随后用户输入“继续刚才被中断的异常订单处理”。

预期：

1. interruption endpoint 返回 `recorded`。
2. root prompt 中注入 recoverable interruption 描述。
3. LLM 将意图识别为继续前一任务；若存在 child focus，使用 `resume_recoverable_child_skill`，否则在当前 root frame 中继续规划并 `submit_skill_result`。
4. `ASK_CLARIFICATION` 也可接受，但必须是有业务副作用或意图不清时的明确澄清，而不是丢失上下文。

采集重点：

1. root LLM 对“继续”的真实意图判断。
2. 是否保留中断前 root/child 上下文。
3. 终态是否仍保持 root frame 可复用。

#### RS-04：用户切换到不相关任务时搁置中断上下文

输入：再次记录 `user_cancelled` 后，用户明确说“先不用处理刚才那个异常了”，并提出新异常订单。

预期：

1. root LLM 调用 `shelve_interrupted_frame`。
2. `decision/intent_resolution` 为 `START_UNRELATED_NEW_TASK` 或 `ABANDON_PREVIOUS`。
3. `abandoned_interruption` 包含被搁置工作的摘要。
4. root frame 的 `interruption_history` 留存搁置记录，新任务继续使用当前 root frame。

采集重点：

1. `shelve_interrupted_frame` 参数质量。
2. `interruption_history` 是否可用于后续“再继续刚才那个”类需求。

#### RS-05：真实业务函数审批链路

输入：通过 TMS public skill 触发 `tms.vehicle.create v1` approvalRequired 链路。

预期：

1. root skill -> TMS child skill -> `invoke_business_function`。
2. 审批前 root/child/function frame 状态正确。
3. 审批后 deterministic `post_approval_message` 与 `business_function_result_message` 写回可见 `lgt_*` task/context。
4. adapter 成功和失败均能触发 task terminal status 收敛。

说明：此用例依赖 Java 后端、TMS grant、adapter token 和上游服务状态，作为第二阶段真实集成冒烟；第一阶段先用 builtin mock business tools 采集真实 LLM tool-call 行为。

### 采集脚本

新增脚本：

```text
tools/langgraph-biz-worker/scripts/real_llm_smoke_capture.py
```

默认调用 `http://localhost:3061` 上已经运行的 Worker，输出到：

```text
docs/version-tracker/1.3.0-SNAPSHOT/test-records/real-llm-root-skill/<run-id>/
```

每次采集保存：

1. 每个 step 的原始 SSE 文本。
2. 每个 step 解析后的 SSE events。
3. interruption endpoint 响应。
4. `data/frames/<taskId>` 与 `data/frames/by-conversation/<contextId>` frame journal 快照。
5. `data/logs/skill-tool-calls/<taskId>.jsonl` 工具调用审计。
6. `summary.json`，汇总 terminal events、tool events、artifact 路径。

### Mock 固化规则

真实样本只有同时满足以下条件才允许固化为 LLM Mock：

1. 通过 RS-01 至 RS-04 的核心断言。
2. LLM tool-call 参数符合当前工具 schema。
3. 最终 summary 不暴露内部 frame/task/session id。
4. `intent_resolution` 明确，且与用户输入一致。
5. 工具调用链可复现，没有依赖随机文本或真实外部状态。

固化时只保留必要的 LLM 返回：

1. root turn 的 `invoke_business_skill` / `submit_skill_result` / `shelve_interrupted_frame` / `resume_recoverable_child_skill`。
2. child skill 的 `mock_get_order` / `mock_get_vehicle_status` / `submit_skill_result`。
3. 每个 turn 使用 `next:<traceId>:NNN` cursor 注册到 mock-llm-service。

后续 E2E 启动前先注册这些 mock turns，再执行 Worker API 测试，避免真实 LLM 波动影响回归。

### 2026-05-17 真实冒烟采样结果

采样环境：

1. Worker：`tools/langgraph-biz-worker`，使用 `.env.real-llm.local` 重启后执行。
2. LLM：真实 OpenAI-compatible endpoint，模型配置来自本地 real LLM env。
3. 采样脚本：`tools/langgraph-biz-worker/scripts/real_llm_smoke_capture.py`。
4. 证据目录：`docs/version-tracker/1.3.0-SNAPSHOT/test-records/real-llm-root-skill/20260517-010136-4021a5/`。

执行结果：

1. RS-01 通过：root LLM 调用 `invoke_business_skill(exception_triage)`；child LLM 依次调用 `mock_get_order`、`mock_get_vehicle_status`、`submit_skill_result`；root 最终 `submit_skill_result`。
2. RS-02 通过：三次 task 在同一 `contextId` 下复用同一个 `system.root` frame：`frm_4ebd0b6c37b9`；后续 SSE 为 `Reusing frame for skill: system.root`。
3. RS-03 通过：记录 `user_cancelled` 后，用户输入“继续”时 root 沿用 recoverable 上下文继续处理，并最终提交 `TURN_COMPLETED`；本轮无 pending child focus，因此模型选择重新委派 `exception_triage`，没有丢失上下文。
4. RS-04 通过：用户明确说“先不用处理刚才那个异常了”时，root 调用 `shelve_interrupted_frame`，`intent_resolution=START_UNRELATED_NEW_TASK`，并在 `interruption_history` 留存 `abandoned_interruption`。
5. 额外观察：第二轮 child skill 第一次 `submit_skill_result` 漏传 `evidence_refs`，runtime 返回校验错误后，真实 LLM 自动补齐 `evidence_refs` 并重新提交成功。这说明 tool-call loop 的 validation feedback 自纠错链路有效，已纳入 Mock 回归。
6. RS-05 未在本轮执行：真实 TMS approvalRequired 依赖 Java 后端、TMS grant、adapter token 和上游服务状态，留作第二阶段真实集成冒烟。

采样产物：

1. `summary.json`：本次 run 的 step、terminal events、tool events、artifact 路径汇总。
2. `*.events.json`：每个 Worker query 的解析后 SSE。
3. `artifacts/tool-audit/*.jsonl`：真实 LLM tool-call request/response。
4. `artifacts/frames-by-conversation/*`：root frame 与 child frame journal 快照。

### 2026-05-17 Mock E2E 固化结果

已将通过采样中的 LLM tool-call 序列固化为：

```text
tools/langgraph-biz-worker/tests/fixtures/llm_scripts/root_skill_real_smoke.json
```

新增回归：

```text
tools/langgraph-biz-worker/tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_root_skill_real_smoke_fixture
```

覆盖内容：

1. E2E 启动时注册 fixture 到 mock-llm-service 的 `POST /__e2e/scripts`。
2. 通过 cursor `next:<traceId>:001..012` 重放真实采样中的 root/child tool-call loop。
3. 断言 root frame 复用。
4. 断言 root final summary 不泄漏 `frm_`、`lgt_` 等内部 ID。
5. 断言继续后能完成前一任务。
6. 断言用户切换任务时 `START_UNRELATED_NEW_TASK` 被写入 root `interruption_history`。

验证命令：

```powershell
cd tools/langgraph-biz-worker
.\.venv\Scripts\python.exe -m pytest tests\test_e2e_scripted_tool_call_streaming.py::test_scripted_root_skill_real_smoke_fixture -q
.\.venv\Scripts\python.exe -m pytest tests\test_e2e_scripted_tool_call_streaming.py -q
```

验证结果：

1. 单条新增 E2E：`1 passed, 3 warnings`。
2. scripted tool-call E2E 文件：`7 passed, 3 warnings`。

## 待确认问题

1. root skill ID 决定使用 `system.root`。`system.*` 命名空间为平台内置保留，普通 ClientApp 不能创建、覆盖或删除。
2. function frame 第一阶段扩展现有 `SkillFrameState`，增加 `frame_kind = SKILL | FUNCTION_CALL`，暂不新增独立 `BusinessFunctionCallFrame`。
3. `context_visibility=passthrough` 第一阶段只允许系统内置 frame 使用：`system.root` 和 `FUNCTION_CALL` frame。普通 skill 默认 `isolated`，最多通过服务端 policy 授权到 `summary`。
4. root context summary 第一阶段由 Python Worker 维护，作为模型工作上下文；Java 侧继续保留完整会话、事件、审批、审计和原始消息，是权威历史存储。
5. `system.root` 已升级为按 conversation/context 优先复用；`task_id` 仍作为当前执行 task 和审批恢复兼容字段保留。
6. 恢复 pending function call 时，语义上继续同一个 conversation/root frame/function frame；实现上不要求恢复同一次 LLM HTTP 调用，而是恢复持久化 frame 后重新进入 root skill tool loop，并把函数结果作为 tool result/恢复事件注入。

## 已确认设计口径

1. root skill 是特殊 skill，稳定 ID 为 `system.root`。
2. root skill 是 conversation/context 级常驻入口，没有普通 skill 的退出语义，也不需要 `close_frame` 销毁私有上下文。
3. 每个 BizWorker 请求先进入 root skill，再由 root skill 在同一个 tool-call loop 内决定回答、调普通 skill 或调用业务函数。
4. `invoke_business_function` 仍只出现在 skill loop 中；root skill 因为本身就是 skill，所以可以自然获得业务函数调用能力。
5. Java 侧保完整历史账本，Worker 侧保给模型使用的可压缩工作上下文。
6. pending function call resume 的目标是恢复当前执行链路；普通用户后续输入可以创建新 task，但必须复用当前 root frame 的工作上下文，而不是丢弃中断前信息。
7. 审批后的用户可见文案由 Worker/Java 恢复链路确定性投递，文案来源按调用注入、函数注册默认、平台兜底的顺序选择，不由 LLM 生成。

## 参考

1. Anthropic Claude Code Subagents: https://docs.anthropic.com/en/docs/claude-code/sub-agents
2. OpenAI Agents SDK Handoffs: https://openai.github.io/openai-agents-python/handoffs/
3. OpenAI Agents SDK Human-in-the-loop: https://openai.github.io/openai-agents-python/human_in_the_loop/
4. OpenAI Agents SDK RunConfig: https://openai.github.io/openai-agents-python/ref/run_config/
