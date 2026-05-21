# Skill 内部上下文隔离与受控提升设计

## 文档作用

- doc_type: design
- intended_for: architecture-discussion | execution-agent | reviewer
- purpose: 明确 BizWorker Skill 执行时内部上下文、frame/report/log、Parent runtime context 之间的边界，作为后续实现细节讨论与开发前置文档

## Version

- `1.1.6-SNAPSHOT`

## 状态

- status: design-discussion
- date: 2026-05-21
- coding_status: not-started

## 已确认基线

1. Skill 内部完整信息默认不带入外部 Parent runtime context。
2. Skill 内部 frame、report、tool log、LLM diagnostic log 可以完整保留。
3. Parent 只接收 Skill 的受控输出：最终结果、执行摘要、状态、需要用户补充的问题、artifact/report/frame 引用。
4. Parent 下一轮 LLM prompt 不默认注入 Skill 内部消息栈、工具输出和中间过程。
5. 如需深挖 Skill 内部过程，应通过 `frameId`、`reportRef`、`logRef` 按需读取，而不是自动展开到 Parent 上下文。
6. `AWAITING_USER` 状态下，用户下一条消息应优先恢复同一个 Skill frame，而不是先交给 Root LLM 重新判断。

## 背景

BizWorker 通过 Root / Parent frame 识别用户意图，并在命中业务技能时打开 Skill frame。Skill frame 内部可能包含：

1. Skill prompt
2. Skill 内部 LLM 消息
3. tool calls
4. tool outputs
5. 中间状态
6. awaiting-user / approval 状态
7. report 和 log

这些信息对排障和恢复很重要，但不适合默认进入 Root / Parent 的 runtime context。Parent 需要的是“这个 Skill 完成了什么、还缺什么、产出了什么、如何引用完整证据”，而不是 Skill 内部完整执行过程。

## 当前 BizWorker 实现快照

### 1. Skill 调用与 child runtime context

当前 LLM Root / Parent 触发业务 Skill 时，会通过 `invoke_business_skill` 工具打开 child frame，并调用 `_runtime_context_for_child_skill(...)` 构造 child runtime context。

相关代码：

1. `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_child_recovery.py`
   - `_invoke_business_skill_tool(...)`
   - `_runtime_context_for_child_skill(...)`
2. `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py`
   - `_run_active_focus_before_root(...)`

当前 `_runtime_context_for_child_skill(...)` 的行为：

1. 复制当前 `runtime_context`。
2. 写入 `_context_visibility`。
3. 移除旧的 `_visible_root_context_summary`。
4. 如果 Skill manifest 的 `context_visibility=summary`，则写入新的 `_visible_root_context_summary`。
5. 如果 `context_visibility=isolated`，不写入 root summary。
6. 如果 Skill 是 builtin/system 且允许 `passthrough`，保留透传语义。

当前需要注意：它目前只是控制 root summary 的可见性，并没有强制移除 `_visible_recent_conversation` 等 Java 会话层 recentConversation 透传产物。因此 Skill 自动恢复链路已经存在，但 child runtime context 的来源还需要继续治理。

### 2. `AWAITING_USER` 自动恢复链路

当前已经实现“Skill 等待用户后，下一个用户消息自动接回上一个 Skill frame”。

执行链路：

1. Child Skill 返回 `AWAITING_USER`。
2. Runtime 调用 `mark_child_awaiting_user(parent_frame_id, child_frame_id, awaiting_user_input)`。
3. Parent frame 保持 `WAITING_CHILD`，并在 `private_working_state` 中记录：
   - `pending_awaiting_user_child_frame_id`
   - `pending_awaiting_user_child`
   - `active_focus_frame_id`
   - `active_focus_status=AWAITING_USER`
   - `active_focus_summary.awaiting_user_input`
4. 同一个 `contextId` 下用户发送下一条消息时，Root graph 先执行 `_run_active_focus_before_root(...)`。
5. `_run_active_focus_before_root(...)` 调用 `prepare_active_focus_resume(root_frame_id, task_id)`。
6. `prepare_active_focus_resume(...)` 发现 active focus 是 `AWAITING_USER`，调用 `resume_from_user_input(...)`，把 child frame 从 `AWAITING_USER` 恢复为 `RUNNING`，并绑定当前 task。
7. Root graph 用原 child `frame_id` 调用 `llm_skill_agent.run(...)`，把当前用户消息作为 prompt 交给 child Skill。
8. 这一轮不会先交给 Root LLM 重新判断 Skill。

约束：确定性接回不等于强制继续原任务。Child Skill prompt / tool contract 必须能识别用户明确的退出、取消、换题或放弃意图，并通过受控结果把状态交还 Parent。否则用户可能被长期困在 `AWAITING_USER` child frame 中。

相关代码：

1. `SkillRuntime.mark_child_awaiting_user(...)`
2. `SkillRuntime.prepare_active_focus_resume(...)`
3. `SkillRuntime.resume_from_user_input(...)`
4. `root_graph._run_active_focus_before_root(...)`

### 3. Skill 完成后的受控提升

Child Skill 完成后，当前通过 `complete_child_and_resume_parent(child_frame_id)` 统一收口：

1. `close_frame(child)` 生成 promoted result。
2. `write_child_result_to_parent(parent, child, promoted)` 写入 Parent 的 `private_working_state.child_results`。
3. Runtime 记录 compact child continuation summary。
4. Runtime 把 execution report ref/digest 链接到 Parent context。
5. Parent 从 `WAITING_CHILD` 恢复为 `RUNNING`。

当前 `close_frame(...)` 已经体现了受控提升原则：promoted result 只包含 manifest `promote_to_parent` 允许的字段，并补充 `execution_report_ref` / `execution_report_digest` 作为完整证据入口。随后会清理 child frame 的 `private_messages`、`private_working_state`、`tool_calls`。

需要强调：这里清理的是 closed frame 的内存态私有上下文，不等于删除 frame/report/log 证据。完整排障证据应继续以 report/log/journal 形式保留。

### 4. 当前和目标模型的差距

当前实现已经满足：

1. `AWAITING_USER` 可以恢复同一个 Skill frame。
2. Parent 不需要重新识别用户补充消息属于哪个 Skill。
3. Child 完成后通过 promoted result / report digest 写回 Parent。
4. Parent 可以通过 execution report ref/digest 定位完整证据。

当前仍需收口：

1. Child runtime context 仍可能继承 `_visible_recent_conversation`。
2. Java provider context 仍可能把最近会话带到 Worker，再被 Root/Skill prompt 使用。
3. `context_visibility=isolated` 当前主要隔离 root summary，不等价于“只允许 Skill contract 白名单字段”。
4. Parent runtime context 中 `child_results` / `child_result_summaries` 的字段白名单需要继续明确，防止 structured output 过大或混入内部过程。

## Skill Runtime Context Contract 草案

后续针对 Skill 的实现治理，建议把 child Skill 运行时上下文收敛为白名单模型。

### 允许进入 child Skill prompt/runtime 的内容

| 字段 | 规则 |
| --- | --- |
| 当前用户输入 | 作为本轮 prompt 进入 child Skill |
| `task_id` / `frame_id` | 当前执行定位字段 |
| `llm_config` / `vision_llm_config` | 执行所需模型配置 |
| `attachments` | 当前任务附件，按现有附件策略传递 |
| `_context_visibility` | 标记 isolated / summary / passthrough |
| `_visible_root_context_summary` | 仅当 `context_visibility=summary` 时允许 |
| `_awaiting_user_input` | 仅当恢复 `AWAITING_USER` child frame 时允许 |
| execution policy | 仅保留执行约束，不包含会话历史 |
| account / client metadata | 仅保留执行路由必要字段 |

### 默认禁止进入 child Skill prompt/runtime 的内容

| 字段 | 原因 |
| --- | --- |
| Java `recentConversation` / `recent_conversation` | 上游完整会话历史不应成为 BizWorker Skill runtime source of truth |
| `_visible_recent_conversation` | 这是由 Java recentConversation 派生出的 Worker 可见历史 |
| Parent raw prompt history | Parent 历史应由 BizWorker runtime summary 控制 |
| Parent private messages | 属于 Parent 内部运行态，不应进入 child |
| 其他 child Skill 的完整过程 | 只能通过 promoted summary / refs 进入 |
| tool raw output history | 默认只保留 refs/digest，按需读取摘要 |

### `context_visibility` 语义建议

1. `isolated`
   - 默认值。
   - 不接收 Root/Parent 会话历史。
   - 不接收 `_visible_root_context_summary`。
   - 可以接收当前用户输入、当前 frame/task 元数据、执行配置、附件、`_awaiting_user_input`。
2. `summary`
   - 在 `isolated` 基础上，额外允许 `_visible_root_context_summary`。
   - 适合需要知道上层业务目标，但不需要完整聊天过程的 Skill。
3. `passthrough`
   - 仅允许 builtin/system Skill 或明确白名单 Skill 使用。
   - 允许更多 runtime context 透传，但仍不建议透传 Java recentConversation。

## 推荐后续实现收口

1. 在 `_runtime_context_for_child_skill(...)` 中增加白名单过滤。
2. 对 `isolated` 和 `summary` 默认移除：
   - `recentConversation`
   - `recent_conversation`
   - `_visible_recent_conversation`
3. 保留 `AWAITING_USER` 自动恢复链路不变。
4. 给 `AWAITING_USER` resume 增加测试：即使请求中携带 Java recentConversation，child Skill prompt/runtime 也不应该看到它。
5. 给 `context_visibility=summary` 增加测试：child 只能看到 root summary，看不到 Java recentConversation 和 Parent raw history。
6. 给 completion promotion 增加测试：Parent 只收到 promoted fields + execution report ref/digest，不收到 child tool calls / private messages / raw LLM diagnostic logs。

## 目标边界

### Parent 可见内容

Parent frame 默认只能看到：

1. Skill 调用意图
2. Skill 名称和 frame id
3. `promoted_result`
4. `execution_digest`
5. `business_result`
6. `pending_questions`
7. `artifact_refs`
8. `report_ref`
9. `log_ref`
10. `status`

这些内容可以进入 Parent runtime context，并参与后续 LLM prompt。

### Skill 内部内容

Skill frame 内部可以完整保留：

1. Skill system/developer instructions
2. Skill 内部 prompt
3. Skill 内部 LLM request / response diagnostic logs
4. Skill 内部 tool call logs
5. Skill tool outputs
6. 中间决策和状态
7. 完整 frame report
8. approval / awaiting-user 恢复状态

这些内容默认不进入 Parent runtime context。

## 设计原则

### 1. 隔离执行

Skill frame 是独立执行边界。Root / Parent 不应该继承 Skill 内部完整消息栈。

这样可以避免：

1. Parent prompt 膨胀
2. Skill 内部实现细节污染外层判断
3. 多个 Skill 的私有上下文互相串味
4. 压缩边界不清晰
5. 后续更换 Skill 实现时影响 Parent 语义

### 2. 受控提升

Skill 完成、暂停或失败时，只把结构化信息提升给 Parent。

推荐提升字段：

| 字段 | 含义 |
| --- | --- |
| `skillName` | 业务 Skill 名称 |
| `skillFrameId` | Skill frame id |
| `status` | completed / awaiting_user / awaiting_approval / failed |
| `promotedResult` | 面向 Parent 的主要结论 |
| `businessResult` | 可结构化消费的业务结果 |
| `executionDigest` | 压缩后的执行摘要 |
| `pendingQuestions` | 需要用户补充的问题 |
| `artifactRefs` | 产物引用 |
| `reportRef` | 完整报告引用 |
| `logRef` | 完整日志引用 |
| `errorSummary` | 失败时的错误摘要 |

### 3. 完整证据保留

Skill 内部完整过程可以保留在 frame/report/log 中，不受 Parent runtime context 压缩限制。

限制只作用于：

1. Parent 下一轮 LLM prompt
2. Parent runtime memory
3. Root 可见的 promoted digest

不限制：

1. Skill frame state
2. Skill report
3. Skill tool logs
4. Skill LLM diagnostic logs

### 4. 按需展开

当用户追问“刚才 Skill 内部为什么这么判断”、“工具输出是什么”、“继续上一步”时，Parent 可以通过引用读取 Skill report/log。

读取后的内容也不应无界注入 prompt，应按场景生成局部摘要或只提取相关片段。

## AWAITING_USER / APPROVAL 边界

当 Skill 进入 `AWAITING_USER`：

1. Parent 只需要知道哪个 Skill 在等待用户，以及等待什么信息。
2. Skill 内部状态继续由 Skill frame 自己持有。
3. 用户补充信息到来后，应恢复对应 Skill frame，而不是把 Skill 内部历史全部注入 Parent。

### `AWAITING_USER` Escape Hatch

`AWAITING_USER` 的默认路由是确定性接回当前 focus stack 的 deepest leaf frame。用户中止、TIMEOUT、ERROR 后只要 focus leaf 可恢复，下一条普通用户消息也遵循同一规则。child 必须提供退出机制：

1. 如果用户明确表示“取消”“停止”“换个任务”“先别做这个”等，child Skill 应识别为退出或改题意图。
2. child 不应继续要求用户补齐原任务参数，而应调用 frame 退出/终止工具，返回受控终止结果，例如 `skill_terminated_by_user` / `abandoned` / `handoff_to_parent`。
3. Parent 收到该结果后，应清理 active focus / pending awaiting child，并按用户新意图决定结束、切换任务或重新进入 Root 判断。
4. 如果 child 无法判断用户是在补充信息还是取消任务，应以澄清问题收口，不应继续盲目推进有副作用的动作。
5. 该退出机制属于 Skill system contract，不能依赖 Java `recentConversation` 或 Root LLM 先行拦截。

当 Skill 进入 `AWAITING_APPROVAL`：

1. Parent 只需要展示审批事项、风险摘要和必要引用。
2. 审批通过/拒绝后恢复 Skill frame。
3. 完整审批上下文留在 Skill frame/report/log。

## 与 Runtime Context 治理的关系

本设计是 [01-biz-worker-context-owned-runtime-memory.md](./01-biz-worker-context-owned-runtime-memory.md) 的 Skill 子场景。

统一规则是：

1. 完整证据可以完整保留。
2. 下一轮 LLM runtime context 必须 bounded。
3. Skill 内部上下文不自动外溢。
4. Parent 只消费 promoted result / digest / refs。
5. 压缩发生时，压缩的是 Parent runtime context 和 Skill runtime context 各自的可见窗口，不删除完整证据。

Skill 正常完成后，下一轮普通对话上下文的可见投影见 [04-runtime-visible-conversation-and-recovery-design.md](./04-runtime-visible-conversation-and-recovery-design.md)。原则是：同一轮内部可以存在 `tool_call(invoke_skill) -> tool_result(S1)`，但下一轮 `runtimeVisibleConversation` 默认只保留 `U1 -> A1 -> U2`，并通过 metadata/ref 关联 Skill report，而不重放 raw tool call。

## 当前实现核对结论

已核对：

1. `AWAITING_USER` 恢复已在 Root LLM 前执行，下一条用户消息会接回 active child Skill frame。
2. `complete_child_and_resume_parent(...)` 当前走 promoted result 写回 Parent。
3. `close_frame(...)` 当前会按 manifest `promote_to_parent` 控制提升字段，并附带 execution report ref/digest。
4. `write_child_result_to_parent(...)` 当前会记录 child result 和 compact continuation summary。
5. `context_visibility` 已有 `isolated` / `summary` / `passthrough` 三种语义雏形。

仍需继续设计/实现：

1. `_runtime_context_for_child_skill(...)` 的白名单过滤。
2. Java recentConversation 与 BizWorker Skill runtime context 的解耦。
3. Parent root_context_summary 的字段边界与大小上限。
4. Parent report 是只记录 child refs/digest，还是复制 child report 摘要，需要进一步明确。
5. Skill report/log 完整证据的读取 API 与按需摘要策略。
6. `AWAITING_USER` 的用户取消/换题 escape hatch，需要在 child Skill contract 与 active focus 清理逻辑中固化。
7. Nested Skill 的恢复以 focus stack 为准；`active_focus_frame_id` / `recoverable_focus_frame_id` 只作为 leaf 快捷索引，恢复路由必须能从 `active_focus_stack` / `recoverable_focus_stack` 定位 deepest leaf。

## 初步验收标准

进入开发后，至少满足：

1. Skill 内部 tool logs 不默认进入 Parent prompt。
2. Skill 内部 LLM diagnostic logs 不默认进入 Parent prompt。
3. Parent 能拿到 Skill 的 promoted result / digest / refs。
4. 用户追问时可以通过 refs 定位完整 Skill report/log。
5. `AWAITING_USER` 恢复仍能回到正确 Skill frame。
6. Parent runtime context 压缩不会删除 Skill 完整证据。
7. 有测试覆盖“Skill 内部过程不外溢到 Parent runtime context”。
8. 有测试覆盖 `AWAITING_USER` 中用户取消/换题时，child 能交还 Parent 且 active focus 被清理。

## Progress Tracking

### Development Progress

- status: design-updated
- 已补充当前 BizWorker Skill 实现快照，包括 `AWAITING_USER` 自动恢复、child runtime context 构造、completion promoted result 写回、report ref/digest 证据链。
- 已记录后续实现收口点：child runtime context 白名单过滤、Java recentConversation 解耦、Parent summary 字段边界和测试覆盖。

### Testing Progress

- status: not-run
- 本次未改代码，未运行测试。

### Experience Progress

- status: N/A
- 原因：本条目为 Worker 内部上下文治理设计，暂不涉及 UI 交互变更。
