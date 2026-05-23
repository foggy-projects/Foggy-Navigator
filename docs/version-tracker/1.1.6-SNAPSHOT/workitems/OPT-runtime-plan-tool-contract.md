# OPT: Runtime Plan Tool Contract

## 文档作用

- doc_type: workitem
- intended_for: execution-agent | reviewer
- purpose: 记录 BizWorker 运行时 `plan` 工具函数的初始契约、边界和后续调研项；实现前需继续对齐 Claude Code / Codex 的 plan 机制

版本：`1.1.6-SNAPSHOT`
状态：设计记录，待 Claude Code / Codex plan 机制深入调研后实现
类型：runtime tool contract / planning state

## 背景

当前 BizWorker 已经将运行时上下文治理收口到：

1. Root conversation frame 负责主会话 runtime memory。
2. Agent frame 负责子 Agent 隔离运行。
3. Skill 退化为普通工具能力，不再默认打开 frame。
4. `llm-submissions` 保存真实提交给 LLM 的 body。

随着工具调用、子 Agent、文件操作和后续受限 `shell_command` 增多，LLM 需要一种显式方式维护“当前计划 / 进度状态”。这类信息不应长期混在普通 assistant 文本里，也不应暴露模型内部推理过程。

因此需要支持一个 `plan` 工具函数，用于把可见、可执行、可更新的任务计划写入 runtime state，并供 UI、report 和后续 prompt 复用。

## 非目标

1. 本 workitem 不立即实现代码。
2. 不在调研完成前断言 Claude Code / Codex 的最终机制完全一致。
3. 不记录或暴露私有 chain-of-thought。
4. 不把 plan 当成上游完整 transcript 的替代品。

## 初始工具契约

工具命名待最终确认，候选：

1. `update_plan`
2. `set_runtime_plan`
3. `plan`

当前倾向使用 `update_plan`，原因是它表达“更新外部可见计划状态”，比 `plan` 更像动作函数，也便于和 Codex 类工具语义对齐。

建议输入 schema：

```json
{
  "summary": "可选。当前计划的一句话摘要。",
  "scope": "current_frame",
  "items": [
    {
      "id": "optional-stable-id",
      "step": "短句描述一个可执行步骤",
      "status": "pending"
    }
  ],
  "active_step_id": "optional-stable-id",
  "note": "可选。简短说明本次变更原因。"
}
```

字段约束：

1. `items[].status` 初始允许：`pending`、`in_progress`、`completed`、`blocked`、`cancelled`。
2. 同一 plan 中最多一个 `in_progress`。
3. `step` 必须是用户可见的执行步骤，不允许写模型私有推理。
4. `scope` 默认 `current_frame`；后续可扩展为 `root` / `agent_frame`。
5. `note` 只用于简短变更说明，不承载长日志。

建议返回：

```json
{
  "ok": true,
  "plan_revision": 3,
  "active_step_id": "optional-stable-id"
}
```

## 运行时语义

`update_plan` 是状态工具，不是业务工具。

1. Root frame 调用时，写入 Root runtime control state / runtime memory 的 `activePlan`。
2. Agent frame 调用时，默认写入该 Agent frame 的私有 `activePlan`。
3. Agent frame 结束时，是否把 plan 摘要提升给 Root，由 handoff / promoted result 决定。
4. 普通 Skill 工具调用不应隐式创建 plan。

对 LLM 的后续可见性：

1. 当前 frame 的 `activePlan` 应作为 compact planning state 注入后续 prompt。
2. 不重复注入每次 plan 工具调用的完整历史。
3. `llm-submissions` 仍保存真实 provider body，便于复盘该轮是否携带 plan state。
4. frame report / runtime event log 可保留 plan revision 历史，用于审计和 UI 回放。

对 UI 的可见性：

1. Detail / debug 模式可以展示 plan 更新事件。
2. 简洁模式不应把 plan 工具调用渲染成普通 assistant 消息。
3. 后续如 UI 支持任务进度组件，可从 `activePlan` 读取当前状态。

## 何时调用

建议系统提示词约束：

1. 多步骤任务、跨工具任务、需要等待外部验证的任务，应在开始后调用一次 `update_plan`。
2. 每完成一个明显阶段，更新对应 step 状态。
3. 简单问答、单次业务查询、无需多步执行的请求，不必调用。
4. 遇到阻塞时，将当前 step 标记为 `blocked`，并在最终回复中说明需要用户或外部系统提供的信息。

## 中断与恢复

1. `AWAITING_USER` / TIMEOUT / ERROR 后恢复同一 frame 时，应恢复该 frame 的 `activePlan`。
2. 用户通过控制面 cancel 只暂停运行，不默认清除 plan。
3. 后续引入 UI rollback / regenerate 时，需要根据 revision / turnId / fork 决定是否回滚 plan。
4. 子 Agent 被 shelve / resume 时，plan 跟随该 frame 的 private state 恢复。

## 与压缩策略的关系

1. `activePlan` 是结构化状态，应优先作为独立 compact state 注入 prompt。
2. 历史 plan revision 可进入 frame report / event log，不应无限保留在 runtime prompt 中。
3. 当 summary 生成时，可以把未完成 plan item 写入 `pendingActions`，但不能替代 `activePlan`。
4. 如果 plan 过长，应按 status 和最近更新时间裁剪展示，但不能丢失当前 `in_progress` / `blocked` 项。

## 后续调研项

实现前需要专项复盘：

1. Codex `update_plan`：
   - 工具输入 schema。
   - 是否提交给下一轮 LLM。
   - 用户可见展示方式。
   - 多次更新是否保留完整历史。
2. Claude Code plan / todo 机制：
   - TodoWrite 或等价工具的 schema。
   - plan 是否进入模型上下文。
   - 子 Agent / subtask 中的 plan 是否隔离。
   - 中断恢复时是否保留。
3. OpenAI Agents SDK / LangGraph：
   - 是否有推荐的外部 plan state。
   - 如何与 checkpoint / state graph 对齐。

调研结论应回写本 workitem，之后再进入实现。

## 代码落点候选

后续实现可能涉及：

1. `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_tool_schemas.py`
2. `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_tool_dispatcher.py`
3. `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/context_memory.py`
4. `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/frame_runtime_state.py`
5. `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/frame_execution_report.py`
6. `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/runtime_message_event_log.py`
7. 后续 UI / SSE plan event 渲染层

## 验收标准

后续实现至少满足：

1. Root frame 可调用 `update_plan` 并持久化 `activePlan`。
2. Agent frame 可调用 `update_plan`，且默认与 Root 隔离。
3. 同一 plan 中多个 `in_progress` 会被拒绝或规范化。
4. `llm-submissions` 可复盘 plan state 是否进入本轮 prompt。
5. frame report 可看到 plan revision 历史。
6. 简洁模式不出现裸工具名消息。
7. 中断恢复后，当前 frame 的 plan 状态仍存在。

## Progress Tracking

Development:

- [x] 初始契约落档。
- [ ] 调研 Claude Code / Codex plan 机制。
- [ ] 确认工具命名与 schema。
- [ ] 实现 runtime state / dispatcher / prompt 注入。
- [ ] 实现 report / event log / UI 对账。

Testing:

- [ ] 单元测试：schema 校验、单 `in_progress` 约束、frame 隔离。
- [ ] E2E：Root 多步任务 plan 更新。
- [ ] E2E：Agent frame plan 隔离与恢复。
- [ ] E2E：中断恢复后 plan 可见。

Experience:

- [ ] 真实会话验证 detail/debug 展示正常。
- [ ] 简洁模式不暴露裸 plan 工具调用。
