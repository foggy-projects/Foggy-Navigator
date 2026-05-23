# Runtime Visible Conversation 与中断恢复上下文设计

## 文档作用

- doc_type: design
- intended_for: architecture-discussion | execution-agent | reviewer
- purpose: 统一定义 BizWorker 的 `runtimeVisibleConversation`、tool call 可见性、Skill 完成后的上下文投影，以及中断后恢复状态如何参与下一轮 LLM prompt

## Version

- `1.1.6-SNAPSHOT`

## 状态

- status: design-discussion
- date: 2026-05-22
- coding_status: root-visible-protocol-implemented

> 2026-05-22 收口：本文中的 runtime context、tool protocol 和恢复控制态仍有效；
> 但 “Skill frame” 语义已由
> [12-agent-frame-and-skill-tool-boundary.md](./12-agent-frame-and-skill-tool-boundary.md)
> 替换为 “Agent frame”。Skill 是普通工具/能力材料，不再默认创建 frame。

## 与已有文档的关系

本设计承接：

1. [01-biz-worker-context-owned-runtime-memory.md](./01-biz-worker-context-owned-runtime-memory.md)
   - 明确 BizWorker 只维护 LLM runtime context，不维护完整 UI 会话历史。
2. [02-runtime-context-governance-framework-comparison.md](./02-runtime-context-governance-framework-comparison.md)
   - 对齐 Claude Code / Codex / OpenAI Agents SDK / LangGraph 等框架的 bounded context、compaction、session evidence 分层思路。
3. [03-skill-internal-context-isolation-and-promotion.md](./03-skill-internal-context-isolation-and-promotion.md)
   - 明确 Skill 内部上下文不默认外溢，Parent 只消费 promoted result / digest / refs。

本文件进一步把 `recentConversation` 语义收敛为 BizWorker-owned `runtimeVisibleConversation`，并把当前代码中的中断恢复逻辑纳入同一套上下文治理规则。

## 已确认基线

1. 上游保存完整 UI 会话历史，包括用户消息、LLM 每次回复和 UI 需要展示的 transcript。
2. BizWorker 保存完整 frame/report/log/journal/diagnostic evidence，用于排障、审计和恢复。
3. BizWorker 提交给 LLM 的上下文必须是 bounded runtime context。
4. `recentConversation` 不应继续表示“上游传来的最近完整历史”，目标语义应升级为 BizWorker 维护的 `runtimeVisibleConversation`。
5. Root frame 可见的 tool call / tool result 是 LLM runtime protocol，不是 UI transcript，但需要进入后续 bounded runtime context，直到压缩或裁剪。
6. Agent frame 内部过程仍是 child-private execution trace；正常 Agent 完成后，Root 只能看到 `invoke_business_agent` 的 promoted result / digest / refs，不展开 child 内部 raw tool call 协议。普通 Skill 调用不创建 child frame，属于当前 frame 的 tool protocol。
7. 中断恢复状态是 runtime control state，应按规则进入 prompt，但不等同于一条普通 assistant/user 对话。

## 命名建议

### 废弃语义

`recentConversation` 容易被理解成“上游最近完整聊天历史”，并且容易混入 tool call、tool result、内部执行过程。

目标上不再建议把它作为 LLM prompt 的 source of truth。

### 目标语义

推荐新语义：

```text
runtimeVisibleConversation = BizWorker 维护的、下一轮 LLM 可见的语义对话窗口
```

它不是完整历史，也不是执行轨迹。它只表达 LLM 继续对话所需的近期语义轮次。

迁移期可以保留字段兼容：

```text
recentConversation = deprecated external compatibility input
runtimeVisibleProtocol = BizWorker-owned prompt assembly source of truth
runtimeVisibleConversation = runtimeVisibleProtocol 中的 user/assistant 语义投影
```

## 上下文分层

### 1. UI Transcript

所有用户可见消息的完整历史。

所属方：

1. Java / 上游应用
2. 其他调用 BizWorker 的客户端

用途：

1. UI 展示
2. 用户历史查询
3. 会话导出

不作为 BizWorker prompt assembly 的权威来源。

### 2. Runtime Visible Protocol

LLM 下一轮需要看到的 bounded protocol window。

所属方：

1. BizWorker

内容：

1. 最近用户消息。
2. 最近面向用户的 assistant 最终回复。
3. Root frame 可见的 assistant tool_call 和匹配 tool result。
4. 必要 metadata，例如 skillId、frameId、reportRef、artifactRefs、promoted digest。
5. compacted summary 引用或摘要。

不包含：

1. child Skill 内部 raw tool call / tool result。
2. child Skill 内部 LLM 消息栈。
3. diagnostic logs。
4. 未经压缩的大块工具输出。

### 3. Execution Trace

完整执行证据。

所属方：

1. BizWorker

内容：

1. frame
2. report
3. log
4. journal
5. tool call / tool result
6. LLM diagnostic request / response

用途：

1. 排障
2. 审计
3. 恢复
4. 用户追问“刚才怎么执行的”时按需读取并摘要

### 4. Recovery Control State

中断和恢复需要的控制态。

所属方：

1. BizWorker

内容：

1. `active_focus_frame_id`
2. `active_focus_status`
3. `pending_awaiting_user_child_frame_id`
4. `pending_recoverable_child_frame_id`
5. `recoverable_focus_frame_id`
6. `recoverable_focus_summary`
7. `recoverable_focus_stack`
8. `continuation_state`
9. `recoverable`
10. `interrupt_reason`
11. `last_error`
12. `last_task_id`
13. continuation summary / active plan

这些字段可以影响下一轮 prompt 的路由和决策，但不是普通 conversation turn。

## Visible Turn 结构草案

BizWorker 侧建议维护 `runtimeVisibleProtocol` bounded list；其中 `user` / `assistant` 文本消息形成 `runtimeVisibleConversation` 语义投影，`assistant.tool_calls` / `tool` 消息用于保留 provider 协议连续性。

```json
{
  "role": "assistant",
  "content": "已为你创建工单，请在工单列表查看。",
  "taskId": "lgt_xxx",
  "frameId": "frm_xxx",
  "createdAt": "2026-05-21T00:00:00Z",
  "metadata": {
    "source": "skill",
    "skillId": "tms-ticket-agent",
    "skillFrameId": "frm_child",
    "reportRef": "runtime/sessions/...",
    "artifactRefs": [],
    "promotedResultDigest": {
      "status": "COMPLETED",
      "businessStatus": "created"
    }
  }
}
```

字段规则：

| 字段 | 规则 |
| --- | --- |
| `role` | 允许 `user` / `assistant` / `tool`；`system` 仍由 prompt builder 当前轮组装，不作为历史消息持久化 |
| `content` | 面向下一轮 LLM 的自然语言语义内容，应接近用户可见最终回复 |
| `metadata.source` | `root` / `skill` / `compaction` / `recovery` 等 |
| `metadata.reportRef` | 指向完整执行证据，不展开 report |
| `metadata.promotedResultDigest` | 对 child Skill 只放 compact digest，不展开 child raw tool result |
| `metadata.skillFrameId` | 调试定位用，普通用户回复不直接暴露 |

## Tool Call 可见性规则

### 同一轮内部工具协议

在同一轮 LLM 执行中，工具协议可以完整存在于当前 model messages 中：

```text
U1 -> tool_call(invoke_business_skill) -> tool_result(S1) -> A1
```

这是为了让 Parent/Root 在同一轮内拿到工具结果并继续推理。

这些信息应完整落到 frame/report/log/journal 中。

同时，provider 协议消息需要单独有可恢复事实源。当前实现新增：

```text
logs/runtime-message-events/<taskId>_<frameId>.jsonl
```

该 JSONL 记录同一 frame 内真实参与 LLM loop 的协议事件，包括初始 messages、assistant response、assistant tool_call、tool_result 和 checkpoint。它不是 UI transcript，也不是下一轮普通 semantic conversation；它用于未完成 frame 的恢复，避免恢复时只拿到孤立 tool_result 而丢失前置 assistant tool_call。

### 下一轮 runtimeVisibleProtocol

下一轮默认保留 Root frame 可见 tool protocol，但不展开 child-private trace。

正常 Skill 完成后，下一轮应看到：

```text
U1 -> assistant.tool_call(invoke_business_skill) -> tool_result(promoted S1) -> A1 -> U2
```

其中：

1. `A1` 是面向用户的 assistant 最终回复。
2. 如果 `A1` 来源于 Skill，可以在 metadata 中挂 `skillFrameId`、`reportRef`、`promotedResultDigest`。
3. `tool_call(invoke_business_skill)` 和匹配 `tool_result(promoted S1)` 是 Root frame 的 provider protocol，应保留给下一轮 LLM。
4. `promoted S1` 只能包含 child frame 提升后的受控结果，不包含 child 内部 raw tool chain。

因此不采用只保留语义轮次的旧口径：

```text
U1 -> A1 -> U2
```

也不采用把 child 内部过程直接外泄为 Root 历史：

```text
U1 -> child.internal.tool_call -> child.internal.tool_result -> U2
```

如果用户明确追问执行过程，Root 可以通过 `reportRef` / `logRef` 按需读取、裁剪和摘要更多 execution evidence。

## Skill 正常完成场景

### 目标上下文

用户发送 `U1`，Root LLM 调用 Skill，Skill 返回 `S1`，最终对用户输出 `A1`。用户再发送 `U2`。

目标下一轮 prompt 的 Root-visible protocol window：

```text
user: U1
assistant: tool_call(invoke_business_skill)
tool: promoted S1
assistant: A1
user: U2
```

如果 Skill 是 direct final：

```text
A1 ~= S1.user_message / S1.result_summary
```

如果经过 Parent synthesis：

```text
A1 = Parent 基于 S1.promoted_result 生成的最终回复
```

`S1` 在 Root 侧只能是 child promoted result。child raw structured output、内部 tool result 和 private LLM stack 不直接展开；需要进入 Root 的信息必须先经过 promotion / digest / refs。

### Nested completion unwind

如果恢复的是 focus stack 的 deepest leaf，且该 leaf 在本轮正常完成，BizWorker 不应停在 `NESTED_FOCUS_PARENT_PENDING` 这类中间态。

目标运行路径：

1. close leaf frame，并通过 `complete_child_and_resume_parent(...)` 把 promoted result 写回 immediate parent。
2. 如果 immediate parent 不是 Root，则用同一条用户消息恢复该 parent frame 的 LLM loop。
3. parent prompt 通过 system context 注入“刚完成的子技能提升结果”，同时可从 runtime message event log 恢复该 parent frame 既有 provider 协议消息。
4. parent 如果继续完成，则重复 close / promote / resume，逐层向 Root 回灌。
5. 到达 Root 后，若 promoted result 已是 direct final，则直接提交 Root persistent turn result；否则 Root 再做 synthesis。

这条路径保证：

1. deepest leaf 的完成结果不会丢在中间 parent。
2. parent 有机会基于 child promoted result 继续业务决策。
3. 最终对用户可见的 `A1` 仍只进入 runtime visible conversation 的 assistant semantic turn，不展开 raw child tool trace。
4. `llm-submissions` 会分别保存 leaf、parent、必要时 Root 的真实提交 body，便于复盘。

### 证据保留

完整信息保留在：

1. child frame
2. parent/root frame
3. frame execution report
4. tool log
5. journal

下一轮需要深挖时，通过 refs 按需读取。

## `AWAITING_USER` 恢复场景

### 当前代码实现

当前实现已经具备确定性恢复：

1. Child Skill 进入 `AWAITING_USER`。
2. `SkillRuntime.mark_child_awaiting_user(...)` 在 Parent 上记录 active focus 和 pending awaiting-user child。
3. 下一条用户消息到来时，Root graph 先执行 `_run_active_focus_before_root(...)`。
4. `_run_active_focus_before_root(...)` 调用 `SkillRuntime.prepare_active_focus_resume(...)`。
5. 如果 focus 是 `AWAITING_USER`，调用 `resume_from_user_input(...)`，把 child frame 恢复为 `RUNNING` 并绑定当前 task。
6. 当前用户消息直接作为 prompt 进入同一个 child Skill frame。
7. Root LLM 不先重新判断是否调用 Skill。

如果 deepest leaf 不是 Root 的直接 child，等待态需要同时写回 immediate parent 和 Root owner：

1. immediate parent 用于局部 frame 恢复和 child 结果回灌。
2. Root owner 用于从 `contextId` 快速定位当前 focus leaf、生成用户可见追问，并在下一条用户消息到来时执行确定性路由。
3. Root owner 的 `active_focus_stack` / `recoverable_focus_stack` 必须保留完整 `root -> ... -> leaf` 路径；不能只更新 immediate parent，否则 Root 只能看到自己处于 `WAITING_CHILD`，无法把下一轮用户输入直达 leaf。

相关代码：

1. `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/skill_runtime.py`
   - `mark_child_awaiting_user(...)`
   - `mark_focus_awaiting_user(...)`
   - `prepare_active_focus_resume(...)`
   - `resume_from_user_input(...)`
2. `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py`
   - `_run_active_focus_before_root(...)`

### 目标上下文规则

`AWAITING_USER` 不是普通 recent conversation 决策，而是 active focus routing。

当用户补充 `U2` 时，child Skill prompt 应看到：

1. 当前用户回复 `U2`
2. `_awaiting_user_input`
3. 当前 child frame state
4. 该 child frame 尚未完成的 provider 协议消息上下文
5. 按 `context_visibility` 允许的 root summary

协议消息上下文从 `runtime-message-events/<taskId>_<frameId>.jsonl` 恢复。`AWAITING_USER` 恢复不是重新拼一套 child prompt 逻辑，而是在同一套事件源上选择“该 frame 已完成到等待用户输入前”的恢复点，再追加当前用户回复 `U2`。

确定性恢复仍必须保留 escape hatch：如果 `U2` 明确表示取消、停止、换题或放弃，child Skill 应识别该意图并调用 `handoff_to_parent`，而不是继续要求用户补齐原任务参数。该判断属于当前 leaf frame 的 LLM 语义职责，不依赖 Root LLM 预先拦截。

`handoff_to_parent` 的运行时语义：

1. 仅 child frame 可用，persistent root 不暴露该工具。
2. 调用成功后，当前 child frame 进入 `COMPLETED`，输出 `status=HANDOFF_TO_PARENT`、`handoff_reason`、`intent_resolution` 和 `requires_parent_synthesis`。
3. Runtime 继续使用 `complete_child_and_resume_parent(...)` 关闭 child、提升结果、清理 active focus / pending child，并恢复 Parent。
4. `requires_parent_synthesis=false` 时，Root 可以把 handoff summary 作为本回合 direct result 返回给用户。
5. `requires_parent_synthesis=true` 时，Root/Parent 继续执行 synthesis，用 `parent_instruction` 和 promoted result 重新判断后续动作。

不应默认看到：

1. Java `recentConversation`
2. raw Parent tool call history
3. 其他 Skill 内部过程

UI 语义历史可以仍表现为：

```text
user: U1
assistant: A1(询问用户补充信息)
user: U2
```

但执行路由上，`U2` 是直接送回 child Skill frame。

## Recoverable Interruption 恢复场景

### 当前代码实现

当前中断恢复逻辑主要用于 stream 丢失、任务被中止、模型异常、child skill 异常等场景。

关键代码：

1. `routes/frame_interruption.py`
   - Java 调用 `/api/v1/frames/interruption` 标记中断。
   - 如果 Root 正在 `WAITING_CHILD`，会调用 `record_recoverable_child_interruption(...)` 标记 child 可恢复。
   - 然后调用 `record_recoverable_interruption(...)` 标记 Root 可恢复。
2. `SkillRuntime.record_recoverable_interruption(...)`
   - 写入 `continuation_state=INTERRUPTED`。
   - 写入 `recoverable=True`。
   - 写入 `interrupt_reason`、`last_error`、`last_task_id`、`interrupted_at`。
   - 设置 `recoverable_focus_*` / `active_focus_*`。
3. `SkillRuntime.record_recoverable_child_interruption(...)`
   - 标记 active child / focus stack 为 interrupted recoverable。
   - 在 Parent 上写入 `pending_recoverable_child_frame_id` 和 `pending_recoverable_child`。
4. `root_graph._get_or_create_system_root_frame(...)`
   - 按 `contextId` 通过 `select_latest_recoverable_root(...)` 优先选中可恢复 Root。
   - 将 Root rebind 到当前 task。
5. `LlmSkillAgent.run(..., persistent_frame=True)`
   - 从 Root `private_working_state` 构造 `_recoverable_interruption`。
   - 将 interruption context 注入 Root prompt。
6. `llm_agent_prompts._build_recoverable_interruption_prompt(...)`
   - 提醒 Root 先判断 `intent_resolution`。
   - 可选值：`CONTINUE_PREVIOUS`、`ABANDON_PREVIOUS`、`START_UNRELATED_NEW_TASK`、`ASK_CLARIFICATION`。
   - 若继续 pending child，要求调用 `resume_recoverable_child_skill`。
   - 若放弃或新任务，要求调用 `shelve_interrupted_frame`。
7. `llm_child_recovery._resume_recoverable_child_skill_tool(...)`
   - 通过 `prepare_recoverable_child_resume(...)` 恢复原 child frame。
   - 继续同一个 child frame，而不是新建 child。

### 目标上下文规则

Recoverable interruption 在路由层默认恢复到当前 focus stack 的 deepest leaf，而不是先交给 Root LLM 判断。业务假设是：用户中止、TIMEOUT、ERROR 后的下一条普通消息，大多数是在补充信息、纠正方向或要求继续；如果用户真正不想继续，后续应通过会话 rollback / regenerate 或 leaf LLM 调用 frame 退出工具完成。

下一轮用户消息 `U2` 到来时，BizWorker 的 frame event router 按以下优先级解析目标 frame：

1. 若存在 `active_focus_stack` / `recoverable_focus_stack` 且 leaf 可恢复，直接把 `U2` 送入 deepest leaf frame。
2. 若当前同一 `contextId` 仍有 RUNNING loop，`U2` 进入 `pendingUserInputs`，在 checkpoint 后送入当前 active frame。
3. 若没有可恢复 focus leaf，才进入普通 root turn。

直达 leaf 恢复时，leaf prompt 应包含：

1. 当前用户消息 `U2`
2. leaf frame 的 provider 协议消息恢复上下文
3. `_awaiting_user_input` 或 `_runtime_protocol_recovery`
4. 按 `context_visibility` 允许的父级 / root summary

只有当没有可直达的 focus leaf，或后续 leaf 明确交还 Parent 时，Root prompt 才需要包含：

1. 当前用户消息 `U2`
2. bounded `runtimeVisibleProtocol`
3. `_recoverable_interruption` control block
4. continuation summary / latest child promoted summary
5. active plan 或其他 compact working state

如果用户选择继续某个未完成 frame，恢复逻辑同样应从 `runtime-message-events/<taskId>_<frameId>.jsonl` 读取该 frame 的协议消息上下文，并从最后一个安全 checkpoint 继续。若事件日志不完整、checkpoint 不安全或 provider 协议无法重建，则降级使用 summary-based recoverable prompt，并保留原 frame/report/log 作为排障证据。

Root fallback 场景才需要判断：

| 用户意图 | 动作 |
| --- | --- |
| 明确继续、补充、修正上次中断任务 | `CONTINUE_PREVIOUS`，必要时调用 `resume_recoverable_child_skill` |
| 明确取消/停止上次任务 | `ABANDON_PREVIOUS`，调用 `shelve_interrupted_frame` |
| 明确开始无关新任务 | `START_UNRELATED_NEW_TASK`，调用 `shelve_interrupted_frame`，再处理新任务 |
| 语义不明确且存在审批/副作用风险 | `ASK_CLARIFICATION` |

`_recoverable_interruption` 不写成普通 visible turn。它是控制态，优先级高于一般历史摘要，但低于明确的当前用户指令和安全/审批规则。

控制面 stop / cancel 的语义是“停止当前执行流并保留 focus stack”，不是 terminal cancel。它应落为 `INTERRUPTED_BY_USER` / `user_cancelled` 这类 recoverable interruption。下一条普通用户消息仍然默认直达 deepest leaf。真正不再恢复旧 leaf 的能力边界是后续的会话 rollback / regenerate / discard，而不是普通停止按钮。

### 与 `AWAITING_USER` 的区别

| 场景 | 是否确定接回 child | 是否需要 Root 判断意图 | 进入 prompt 的形态 |
| --- | --- | --- | --- |
| `AWAITING_USER` | 是 | 否，除非用户明确取消/改题由 child 调用 `handoff_to_parent` | runtime message events + `_awaiting_user_input` + 当前用户回复 |
| recoverable child interruption | 是，默认直达 deepest leaf | 否，除非没有可恢复 leaf 或 leaf 调用 `handoff_to_parent` | runtime message events 或 summary fallback + `_runtime_protocol_recovery` |
| normal Skill completion | 否 | 不需要恢复 | visible turns + promoted metadata |

## Prompt 组装目标顺序

目标状态下，Root prompt 组装建议按以下顺序：

1. system / account / skill registry / policy
2. recovery control state
   - active focus / awaiting user 优先走确定性路由
   - recoverable interruption 进入 Root 意图判断
3. compacted runtime summary
4. bounded `runtimeVisibleProtocol`
5. current user message
6. current explicit request context
7. attachments / execution policy

对于 child Skill prompt：

1. Skill system instructions
2. current user instruction
3. `skill_input`
4. `_awaiting_user_input` 或 `_recoverable_interruption` 中与该 child 相关的 compact continuation summary
5. 按 `context_visibility` 允许的 root summary
6. attachments / execution policy

child Skill prompt 默认不读取 Java recentConversation。

## 数据落盘建议

在 `contextId` 对应 session 目录下，可以逐步引入：

```text
runtime/
  sessions/
    by-date/YYYY/MM/DD/<hash>/<contextId>/
      memory/
        runtime-visible-conversation.jsonl
        compacted-summary.json
        recovery-state.json
        execution-digests.jsonl
        memory-index.json
```

说明：

1. `runtime-visible-protocol.jsonl`
   - 保存 Root frame 可见的 bounded provider protocol window，包括 user / assistant / tool。
   - 不保存 child-private raw tool chain。
2. `runtime-visible-conversation.jsonl`
   - 可选 mirror，仅保存 user / assistant semantic projection，便于人工阅读和 UI 对账。
3. `compacted-summary.json`
   - 上下文压缩后的长期运行摘要。
4. `recovery-state.json`
   - 当前 active focus / recoverable interruption 的索引视图。
   - 权威状态仍以 frame/journal 为准。
5. `execution-digests.jsonl`
   - 每轮 task/frame 的 compact execution digest。
6. `memory-index.json`
   - 最近 task/frame/report/artifact 快速定位索引。

是否落成独立文件仍需结合现有 file layout 设计；本阶段先固定语义边界。

## 迁移策略

### 短期

1. 保留 Java `recentConversation` 字段兼容。
2. BizWorker prompt assembly 中标记它为 deprecated external input。
3. child Skill 的 `isolated` / `summary` context 默认过滤 `_visible_recent_conversation`。
4. 增加测试证明 Skill resume 不依赖 Java recentConversation。

### 中期

1. BizWorker 写入 `runtimeVisibleProtocol`，并可派生 `runtimeVisibleConversation` 语义投影。
2. Root prompt 优先读取 BizWorker memory。
3. Java `recentConversation` 只作为兼容兜底，不作为默认 prompt 输入。
4. 正常完成、awaiting-user、recoverable interruption 都写入统一 memory/digest。

### 目标状态

1. 上游只传 `contextId`、当前用户消息和显式请求上下文。
2. BizWorker 仅凭 `contextId` 恢复 runtime context。
3. Java/UI 完整 transcript 与 BizWorker runtime context 解耦。
4. Root-visible tool protocol 进入 bounded runtime context；Skill internal trace 全部留在 child evidence，不进入 Root semantic projection。

## 验收标准

进入开发后，至少满足：

1. 普通下一轮 prompt 不依赖 Java `SessionMessageRepository` 生成的 `recentConversation`。
2. Skill 正常完成后，下一轮 Root visible protocol 包含 `U1 -> tool_call(invoke_business_skill) -> tool_result(promoted S1) -> A1 -> U2`。
3. `A1` 和 `tool_result(promoted S1)` 可以携带 Skill metadata / digest / refs，但不展开 child raw tool result。
4. `AWAITING_USER` 下一轮消息仍确定性接回原 child Skill frame。
5. recoverable interruption 下一轮默认直达 focus stack 的 deepest leaf；只有没有可恢复 leaf，或 leaf 主动交还 Parent 时，才由 Root 判断 `intent_resolution`。
6. `resume_recoverable_child_skill` 恢复原 child frame，不新建 child。
7. `shelve_interrupted_frame` 会清理或标记旧 recoverable state，避免污染无关新任务。
8. `runtimeVisibleProtocol` 有 token/条数上限和 compaction 策略；`runtimeVisibleConversation` 只是其 user/assistant 语义投影。
9. report/log/frame/journal 仍保留完整执行证据。
10. 测试覆盖 normal Skill completion、nested completion unwind、awaiting-user resume、recoverable child resume、abandon/new-task shelving。

## 代码触点候选

开发前重点复核：

1. `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphTaskService.java`
   - 当前 Java provider context 仍注入 `recentConversation`。
2. `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py`
   - Root frame selection、recentConversation prompt、active focus resume。
3. `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_agent.py`
   - persistent root 注入 `_recoverable_interruption`、active plan、tool loop。
4. `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_agent_prompts.py`
   - `_build_visible_recent_conversation_prompt(...)`
   - `_build_recoverable_interruption_prompt(...)`
   - `_build_awaiting_user_input_prompt(...)`
5. `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_child_recovery.py`
   - `_runtime_context_for_child_skill(...)`
   - `_resume_recoverable_child_skill_tool(...)`
6. `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/skill_runtime.py`
   - `record_recoverable_interruption(...)`
   - `record_recoverable_child_interruption(...)`
   - `prepare_active_focus_resume(...)`
   - `prepare_recoverable_child_resume(...)`
   - `shelve_recoverable_interruption(...)`
   - `_record_child_continuation_summary_on_parent(...)`
7. `tools/langgraph-biz-worker/src/langgraph_biz_worker/routes/frame_interruption.py`
   - 外部中断记录入口。
8. `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/file_frame_journal.py`
   - 恢复状态的持久化和按 contextId/journal 加载。

## Progress Tracking

### Development Progress

- status: implemented
- 已完成 `runtimeVisibleConversation`、tool call 可见性、Skill 完成、`AWAITING_USER`、recoverable interruption 的统一设计落档。
- 已实现 nested focus completion unwind：deepest leaf 完成后逐层 close/promote/resume parent，直到 Root direct final 或 Root synthesis。
- 已实现 child-only `handoff_to_parent`：`AWAITING_USER` 或 recoverable leaf 直达恢复时，用户取消/停止/换题可由当前 leaf LLM 受控交还 Parent，并清理 active focus。
- 已按 Codex recorder probe 结论实现 completed root turn 的 provider protocol 保留：Root 自己产生的 `assistant.tool_calls` 与匹配 `tool` result 写入 `ContextRuntimeMemory`，下一轮以独立 role messages 回放；child-private 工具链仍只保留在 child evidence / report / runtime event log 中。

### Testing Progress

- status: passed
- `cd tools/langgraph-biz-worker; .\.venv\Scripts\python.exe -m pytest tests/test_e2e_scripted_tool_call_streaming.py -q`
  - result: `23 passed`
- 新增覆盖：`test_scripted_nested_focus_completion_unwinds_to_parent_result`，验证 nested leaf 完成后 parent frame 会继续执行、提交给 LLM 的 parent system context 包含刚完成的子技能提升结果，Root 最终提交 direct result。
- 新增覆盖：`test_scripted_awaiting_child_can_handoff_cancel_to_parent`，验证 awaiting child 恢复后用户取消时不会重新调用 `invoke_business_skill`，child 调用 `handoff_to_parent`，Root active focus 被清理，runtime event JSONL 记录 `handoff_to_parent` 与 `frame_completed`。

### Experience Progress

- status: N/A
- 原因：本条目为 Worker 运行时上下文治理设计，暂不涉及 UI 交互变更。
