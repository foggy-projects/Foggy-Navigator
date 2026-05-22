# LLM Submission Message Contract

## 文档作用

- doc_type: design
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 收口 BizWorker 真实提交给 LLM 的 messages 数组契约，统一 system / human 边界、Skill 描述注入和 submission 复盘日志规则

版本：`1.1.6-SNAPSHOT`
状态：已实现并通过回归
类型：runtime context governance / prompt contract

> 2026-05-22 收口：本文的 messages 数组契约继续有效，但 non-root lifecycle
> frame 的主语从 “Skill frame” 收口为 “Agent frame”。Skill 调用本身不再创建
> frame；`invoke_business_skill` 返回 Skill 材料并留在当前 frame 的 tool protocol 中。
> Agent 调用才创建 child frame，详见
> [12-agent-frame-and-skill-tool-boundary.md](./12-agent-frame-and-skill-tool-boundary.md)。

## 背景

Phase 1-5 已经把 BizWorker 调整为 LLM runtime context 的事实源：上游保存完整 UI transcript，BizWorker 维护模型运行所需的 bounded runtime context。

在随后联调中发现，旧 prompt 组装仍把大量运行时治理信息拼进 `human` message，例如：

- 当前 root / skill turn context
- runtime-visible protocol / conversation projection
- business context
- `AWAITING_USER` 续接说明
- recoverable interruption 说明
- attachment context

这会让 `human` message 偏离用户真实输入，也会让 LLM submission 日志难以判断“用户到底说了什么”。因此本文件收口真实提交给 LLM 的 `messages` 数组契约。

## 设计结论

### 1. 默认使用单条 system message

BizWorker 默认只生成一条 `system` message，承载运行时治理上下文、能力说明和业务辅助上下文。

不采用多条连续 `system` message 作为默认结构，原因是不同 provider、SDK、代理层对多 system message 的兼容性不完全一致。单条 system message 更稳定，也便于 LLM submission 日志复盘。

### 2. human message 以用户原文为主

`human` message 只承载当前用户输入：

```text
用户输入什么，human content 就尽量是什么。
```

允许 BizWorker 在尾部追加极少量“当前请求上下文”，但必须是和当前用户消息强绑定、且不适合作为长期 system 前缀的信息。当前唯一允许的默认追加项是显式传入的精确请求时间：

```text
用户原文

---
当前请求时间:
- 当前时间: 2026-05-21T15:13:01+08:00
- 时区: Asia/Shanghai
```

只有入口层明确传入 `current_time/currentTime` 时才追加该块。仅传入 `timezone` 或 `business_date` 时，不生成新的精确时间戳，避免每次调用都改变 prompt 内容。

BizWorker 不再把以下运行时治理内容拼入当前 `human`：

- `User request: ...`
- `Business context: ...`
- `Current user reply: ...`
- `Runtime-visible conversation before this turn: ...`
- `Previous execution was interrupted.`
- `Previous child skill turn is waiting for user input.`

这些内容中，治理说明进入 `system`；BizWorker 已维护的 Root-visible protocol 不进入 system 文本，而是作为独立 role messages 注入。

### 3. runtime-visible protocol 使用 role messages

BizWorker-owned runtime-visible protocol 按主流 Agent 对话结构注入为独立消息，而不是折叠进 system 文本。`runtime-visible conversation` 仅表示其中 user / assistant 语义消息的投影。

当前顺序：

```text
system
历史 user / assistant / tool protocol messages
...
当前 human
```

这样 LLM submission body 能直接复盘“上一轮用户说了什么、助手最终答了什么、Root frame 调用了哪些工具并拿到什么结果”，也更接近 Claude Code / Codex / OpenAI Agents 的多轮 messages 结构。

注意：

- 这里注入 BizWorker runtime memory 中受控保留的 Root-visible protocol messages，包含 semantic user / assistant messages，也包含 Root frame 自己产生的 assistant tool_call 和匹配 tool result。
- Agent frame private messages、child-private raw tool chain、frame report/log/journal 仍作为 execution trace 保留，不默认变成 Root 下一轮 role messages。普通 Skill 调用没有 child-private frame，属于当前 frame 的 tool protocol。
- 若上下文压缩发生，压缩摘要按后续 compaction 设计进入受控上下文；不会恢复完整历史栈。
- 当前初始 messages 由 `runtime/llm_message_builder.py` 统一组装。执行 loop 只在模型返回后追加 assistant / tool messages，不再负责 system / history / current-human 的契约拼接。
- completed root turn 的本轮协议先暂存在 Root frame `private_working_state._pending_root_turn_protocol_messages`，随后由 Root close/commit 写入 `private_working_state.runtime_context_memory.visibleMessages`；该字段当前承载 bounded provider protocol，而不是仅承载 user/assistant 语义投影。

### 3.1 tool protocol messages 的恢复边界

tool call / tool result 分三种场景处理：

1. 同一轮 LLM loop 内，必须完整传回模型。
   - 模型返回 `assistant tool_call` 后，BizWorker 执行工具。
   - BizWorker 将对应 `tool result` 追加回当前 `messages`，再继续下一次 model call。
   - 这属于 provider 工具协议，不是普通用户可见 transcript。
2. 未完成 frame 的恢复，包括异常中断恢复和 `AWAITING_USER` 恢复，需要能恢复该 frame 已有的协议消息。
   - 恢复时不能只传孤立的 `tool result`；必须保留它前面的 `assistant tool_call`，否则 provider 可能认为 messages 协议非法。
   - 两类恢复复用同一套底层 runtime message event log，只是恢复点选择不同。
   - `AWAITING_USER` 恢复：从该 frame 已完成的协议上下文恢复，再追加用户新回复。
   - 异常中断恢复：默认从 focus stack 的 deepest leaf frame 恢复，从最后一个安全 checkpoint 继续，必要时回退到 summary-based recoverable prompt。
   - 控制面 stop / cancel 只表示停止当前执行流并保留 focus stack；下一条普通用户消息仍按 deepest leaf 恢复，不自动丢弃旧 frame。
   - 如果 deepest leaf 在恢复后正常完成，BizWorker 会把其 promoted result 作为“刚完成的子技能提升结果”注入 immediate parent 的 system context，并继续运行 parent LLM；parent 再完成时继续向上 close/promote/resume，直到回到 Root。
3. 正常完成后的下一轮普通对话默认保留 Root-visible tool protocol。
   - 如果上一轮 Root 没有工具调用，下一轮看到 `U1 -> A1 -> U2`。
   - 如果上一轮 Root 发生工具调用，下一轮看到 `U1 -> assistant.tool_call(...) -> tool_result(...) -> A1 -> U2`。
   - Agent 完成时，Root 侧的 `tool_result` 只能包含 promoted digest、reportRef、artifactRefs 等受控信息。
   - child-private raw tool call / raw tool result 保留在 execution trace 和 runtime message event log 中，不进入 Root-visible protocol。

### 3.2 Agent frame 的 messages 隔离边界

显式 `invoke_business_agent` 打开的子 Agent frame 默认不是 Root conversation 的 fork。子 Agent 的首次 LLM submission 结构为：

```text
system(child Agent 身份、Agent manifest、必要执行上下文)
human(parent handoff instruction / 当前用户给该 Agent 的续接输入)
```

当子 Agent 处于 `AWAITING_USER`、TIMEOUT、ERROR 或 recoverable interruption 后恢复时，BizWorker 只从该 Agent frame 自己的 `runtime-message-events` 恢复 provider protocol：

```text
system(child Agent 身份、Agent manifest、必要执行上下文)
child frame 已恢复的 user / assistant / tool protocol messages
human(新的用户输入或续跑指令)
```

子 Agent 默认不接收：

- Root 已保存的完整 `runtime-visible protocol`。
- Root 的 `allowed_skills` 业务技能目录。
- Root runtime memory 的 checkpoint / finalizing 回调。
- Parent frame 的 raw tool chain。

只有 Agent manifest 明确配置 `context_visibility=summary` 时，BizWorker 才把 root summary 作为受控上下文注入 system；完整 parent fork 作为未来显式模式保留，不作为默认行为。

### 4. Root 与 Child 的完成契约分离

`conversation.root` 是会话级持久执行载体，不是每轮都需要关闭的业务 Skill frame。它负责普通用户回合、业务工具调度、运行时记忆 commit 与中断恢复。

Root 回合完成规则：

1. 模型未产生 tool call，但返回了自然语言内容时，该内容可直接作为本回合最终答复。
2. BizWorker 仍会内部完成 runtime memory、report、log、LLM submission 的落档，不要求模型额外调用退出工具。
3. 普通寒暄、简单问答、无需保留结构化状态的答复，不应调用 `submit_skill_result`。
4. 如果 root 回合需要保存 `active_plan`、`artifact_refs`、`evidence_refs` 或其他结构化状态，模型应主动调用 `submit_skill_result` 提交这些结构化信息。
5. 如果 checkpoint 上存在 queued user input，BizWorker 会先把 queued input 追加进当前 loop，让模型继续处理，而不是提前把自然语言答复提交为最终结果。

Child / non-root Agent frame 完成规则：

1. 业务 Agent frame 有明确生命周期边界，完成、等待用户补充或需要返回父级时，优先主动调用 `submit_skill_result` 或 `handoff_to_parent`，以便提交结构化状态、refs 与受控退出意图。
2. BizWorker 不再在 child frame 返回自然语言但未调用终止工具时，追加伪 `human` 提醒消息要求重试。
3. child frame 未调用终止工具但返回自然语言时，按子 Agent 风格归一化处理：
   - 如果文本明显在追问或请求用户补充，则转为 `WAITING_FOR_USER_INPUT`，child frame 进入 `AWAITING_USER`，下一条用户消息继续直达该 frame。
   - 否则转为 `FINAL_FOR_USER`，child frame 完成并将摘要/结构化结果按 promoted result 提升给 parent。
4. child frame 无 tool call 且无自然语言内容，或自然语言归一化后无法通过输出契约，才视为运行时协议错误。

### 5. system message 内容边界

单条 system message 按以下语义组成：

1. Agent 身份：
   - root loop：当前业务会话的根编排 Agent。
   - child skill：正在执行某个业务技能。
2. Account context：
   - `ACCOUNT_POLICY.md`
   - `AGENT.md`
   - `MEMORY.md`
3. Skill instructions：
   - `技能说明:`
   - 来自当前 Skill manifest 的 `markdown_body`。
4. 通用工具与标识治理规则：
   - 内部 tracing id 不能当作订单号、运单号或业务单据号。
   - 只能使用已提供工具。
   - root 普通回合可直接用自然语言完成；普通寒暄、简单问答不要调用 `submit_skill_result`；需要结构化状态时才主动调用 `submit_skill_result`。
   - root 普通业务技能请求默认用 `invoke_business_skill` 加载 Skill 材料，并在 Root 当前上下文继续；不要仅因为 Skill bundle 名称包含 `agent` 就打开 Agent frame。
   - child frame 完成、等待用户补充或交还父级时，优先主动调用 `submit_skill_result` 或 `handoff_to_parent`；自然语言最终消息会被归一化为子 Agent 结果。
5. 当前运行时上下文：
   - 运行时日期上下文：时区、业务日期、当前月份范围、相对日期解析规则。
   - active plan 与持久 root plan 策略。
   - `AWAITING_USER` 续接上下文。
   - recoverable interruption 续跑 / 搁置决策上下文。
   - nested child completion 后，parent 继续执行时可见的“刚完成的子技能提升结果”。
   - frame result contract。
   - 业务上下文。
   - 附件上下文。
   - 可见父级 / root summary。

精确到秒或毫秒的 `current_time` 不进入 system message。原因是它会让 system prompt 前缀在每次调用时变化，降低 LLM prompt cache 命中率。相对日期推理所需的低频信息保留在 system 中：业务日期通常每日变化，当前月份范围通常每月变化，缓存影响可控。

### 6. Skill 列表必须包含描述，并使用 Markdown

上游传入的 `allowed_skills` 可能只有 Skill id。BizWorker 在 root prompt 组装前会根据本地 Skill registry 补齐：

- `id`
- `name`
- `description`

LLM 不应只看到技能名。缺少 description 会降低 root 编排选择 Skill 的可靠性，也会增加误调用风险。

该补齐只影响 LLM 可见的业务上下文，不要求上游重复维护 Skill 描述。最终 prompt 中，`allowed_skills` 不再作为 JSON 数组出现在 `业务上下文` 中，而是渲染为 Markdown：

```markdown
可用业务技能:
- `tms-ticket-agent`（TMS 工单 Agent）: 创建运单异常件、平台反馈、BUG 报告、系统优化、产品建议。
- `foggy-query-agent`（Foggy 查询 Agent）: 数量、合计、报表、统计、趋势和跨模型查询。
```

真正需要精确字段读取的其他业务输入仍可保留为 JSON `业务上下文`。

### 7. system.root 不作为业务 Skill 暴露

`system.root` 是历史内部实现标识，不是业务 Skill。

当前规则：

- 前端事件不显示 `Opening frame for skill: system.root`。
- LLM submission meta 使用 `conversation.root`。
- LLM-visible context 不出现 `system.root`。
- Root prompt 只表达“根编排 Agent”职责，不表达 `system.root`。

业务 Skill、BusinessFunction、frame report ref、artifact ref 仍可按需进入 system context，因为这些对业务回溯和后续决策有价值。

### 8. 附件上下文进入 system

附件上下文是运行时业务输入，不是用户自然语言本身，因此进入 system message。

标题统一为：

```text
上游系统提供的附件:
```

附件 URL 会去除 query / fragment；token、secret、password、credential、api_key 等敏感 metadata key 不进入 LLM-visible prompt。

### 8. LLM submission 复盘日志保存完整 body

开启：

```text
BIZ_WORKER_LLM_SUBMISSION_LOG_ENABLED=true
```

后，每次真实 ChatModel 调用都会保存一个独立 JSON 文件：

```text
logs/llm-submissions/000001_<role>_<task>_<frame>_iterXX_attemptXX.json
```

第一版默认最多保留 100 个文件。文件内容用于精确复盘“这一次到底向 LLM 提交了什么 body”，不作为下一轮 runtime context 的来源。

### 9. Runtime message event JSONL

BizWorker 同时维护一个 append-only runtime message event log，作为未来同一 frame 恢复协议消息的事实源：

```text
logs/runtime-message-events/<taskId>_<frameId>.jsonl
```

当前写入事件包括：

1. 初始 `system` / historical `user` / historical `assistant` / current `user` messages。
2. 每次 model response 的 `assistant` message。
3. 每个 `assistant_tool_call`。
4. 每个工具执行后的 `tool_result`。
5. checkpoint marker，例如 `before_model_call`、`after_tool_call`、`frame_completed`、`persistent_turn_completed`、`suspended`。

该 JSONL 和 `llm-submissions` 的区别：

| 文件 | 用途 | 是否作为恢复事实源 |
| --- | --- | --- |
| `llm-submissions/*.json` | 精确复盘每次真实提交给 LLM 的完整请求 body | 否 |
| `runtime-message-events/*.jsonl` | 保存同一 frame 内可恢复的 provider 协议消息事件 | 是，未完成 frame 恢复读取该文件 |

当前阶段已实现写入和读取入口接线。`AWAITING_USER` 与 recoverable child interruption 复用同一套 reader：先按 session + `frameId` 找到最新事件 JSONL，恢复最后安全 checkpoint 前的协议 messages，再追加当前用户消息或 Root 生成的续跑指令。

## 示例结构

```json
{
  "messages": [
    {
      "role": "system",
      "content": "你是当前业务会话的根编排 Agent。\n...运行时治理上下文、技能说明、可用业务技能、业务上下文..."
    },
    {
      "role": "user",
      "content": "this is a test"
    },
    {
      "role": "assistant",
      "content": "Test received. System is operational and ready for instructions."
    },
    {
      "role": "user",
      "content": "帮我提交一个工单\n\n---\n当前请求时间:\n- 当前时间: 2026-05-21T15:13:01+08:00\n- 时区: Asia/Shanghai"
    }
  ]
}
```

## 与旧文档的关系

本文件不替代 01-07 的上下文治理设计，而是补齐最终 prompt assembly 口径：

- `01` 定义 BizWorker-owned runtime memory。
- `02` 定义主流 Agent 对比与治理原则。
- `03` 定义 Skill 内部上下文隔离和提升。
- `04` 定义 runtime-visible protocol / conversation projection 与恢复。
- `06` 定义普通消息进入 runtime memory 的生命周期。
- `07` 定义 Phase 1-5 实施计划与进度。
- `08` 定义 `system.root` 退场和 conversation root frame。
- `09` 定义真实提交给 LLM 的 `messages` 数组契约。

若旧文档出现“runtime context 拼入 user prompt”这类表述，以本文件为当前实现口径。

## 验收标准

1. 当前 `human` message 以用户原文为主；只有显式传入 `current_time/currentTime` 时，允许在尾部追加 `当前请求时间` 块。
2. runtime governance context、business context、active plan、recoverable interruption、awaiting user context 都进入 `system` message。
3. BizWorker-owned runtime-visible protocol 以独立 `user` / `assistant` / `tool` role messages 注入，不折叠进 system 文本。
4. root system prompt 为中文。
5. `allowed_skills` 在 LLM-visible context 中包含 description，并以 Markdown `可用业务技能` 列表呈现。
6. LLM submission 中不出现 `system.root` 作为业务 Skill 身份。
7. 附件上下文标题为中文，并进入 system message。
8. submission log 能按序保存真实 ChatModel body。
9. runtime message event JSONL 能记录同一 frame 的初始 messages、assistant response、assistant tool_call、tool_result 和 checkpoint。
10. 精确 `current_time` 不进入 `system` message；仅时区或业务日期存在时，不生成 `human` 尾部精确时间块。
11. nested leaf 完成后的 parent continuation submission 能看到“刚完成的子技能提升结果”，且该信息只用于当前 parent 续跑；后续 Root 只能看到逐层 promotion 后的 Root-visible protocol，不污染为 child-private trace。
12. child frame 的 system context 包含“子技能退出策略”；persistent root 不暴露 `handoff_to_parent`，child frame 暴露 `handoff_to_parent`。
13. persistent root 在无 tool call 且有自然语言内容时可完成当前回合，不生成伪 `human` 提醒或 runtime retry instruction。
14. child frame 在无终止工具调用时不被自动补提示；测试覆盖该协议错误路径，并暴露真实 frame protocol error。

## Progress Tracking

### Development

- status: completed
- 2026-05-21: `llm_agent_prompts.py` 已将 runtime governance context 从 human prompt 迁入 system prompt。
- 2026-05-21: `llm_skill_agent.py` 已按新签名构造 system / human messages。
- 2026-05-21: `root_graph.py` 已补齐 root prompt 中 `allowed_skills` 的 name / description。
- 2026-05-21: `attachment_context.py` 已将附件上下文标题中文化。
- 2026-05-21: E2E 和单元测试已改为断言 system 承载运行时上下文、human 以用户原文为主。
- 2026-05-21: 为提高 prompt cache 命中率，精确 `current_time` 从 system 拆出；system 仅保留低频日期上下文，显式请求时间作为 human 尾部当前请求块。
- 2026-05-21: `allowed_skills` 已从业务上下文 JSON 中拆出并渲染为 Markdown `可用业务技能` 列表。
- 2026-05-21: runtime-visible conversation 已从 system 文本中移出，按 `user` / `assistant` role messages 注入 submission body；补充 E2E 断言覆盖。
- 2026-05-21: 新增 `llm_message_builder.py` 作为初始 messages 数组的唯一组装入口，固定 system -> runtime-visible role messages -> current human 的顺序。
- 2026-05-22: 收口并实现 root-visible tool protocol retention。下一轮 prompt 会保留 Root frame 自己产生的 tool_call / tool_result；Skill 内部 child-private tool trace 仍隔离在 child evidence 中，Root 只接收 promoted result / digest / refs。
- 2026-05-21: 新增 runtime message event JSONL 写入，记录同一 frame 的 provider 协议消息事件；`AWAITING_USER` 与 recoverable child interruption 恢复已接入同一日志 reader，按 `frameId` 找最新事件文件并重建 provider 协议 messages。
- 2026-05-21: nested leaf 正常完成后，parent continuation 已通过 system context 接收“刚完成的子技能提升结果”，并继续逐层向 Root unwind；`llm-submissions` 会分别保留 leaf 与 parent 的真实提交 body。
- 2026-05-21: scripted E2E 已补充 `llm-submissions` 与 `runtime-message-events` 对账断言，覆盖普通多轮、BusinessFunction tool protocol、`AWAITING_USER` child resume、nested completion unwind。
- 2026-05-21: child frame system context 新增“子技能退出策略”，并通过 `handoff_to_parent` 支持取消/停止/换题/回主对话；persistent root 工具列表不暴露该 child-only 工具。
- 2026-05-21: 收口 root / child 完成契约。Root 普通回合支持自然语言直接完成；`submit_skill_result` 改为 root 的可选结构化提交能力和 child frame 的强制退出契约；移除“无 tool call 后追加伪 human 提醒”的默认行为。
- 2026-05-21: 补齐 API 级 Root 普通自然语言完成 E2E，验证同 `contextId` 第二轮 LLM body 能恢复上一轮 `user` / `assistant`，并且不会写入伪 human retry instruction。

### Testing

- status: passed
- `cd tools/langgraph-biz-worker; .\.venv\Scripts\python.exe -m pytest tests/test_llm_message_builder.py tests/test_llm_skill_agent.py tests/test_e2e_scripted_tool_call_streaming.py tests/test_llm_submission_log.py`
  - result: `74 passed`
- `cd tools/langgraph-biz-worker; .\.venv\Scripts\python.exe -m pytest tests/test_config.py tests/test_llm_skill_agent.py tests/test_llm_message_builder.py tests/test_llm_submission_log.py`
  - result: `69 passed`
- `cd tools/langgraph-biz-worker; .\.venv\Scripts\python.exe -m pytest`
  - result: `597 passed, 6 skipped, 11 warnings`
- `cd tools/langgraph-biz-worker; .\.venv\Scripts\python.exe -m pytest tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_nested_focus_completion_unwinds_to_parent_result -q`
  - result: `1 passed`
- `cd tools/langgraph-biz-worker; .\.venv\Scripts\python.exe -m pytest tests/test_e2e_scripted_tool_call_streaming.py -q`
  - result: `27 passed, 3 warnings`
- `cd tools/langgraph-biz-worker; .\.venv\Scripts\python.exe -m pytest tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_awaiting_child_can_handoff_cancel_to_parent -q`
  - result: `1 passed`
- `cd tools/langgraph-biz-worker; .\.venv\Scripts\python.exe -m pytest tests/test_llm_skill_agent.py tests/test_llm_message_builder.py tests/test_llm_submission_log.py -q`
  - result: `62 passed`
- `cd tools/langgraph-biz-worker; .\.venv\Scripts\ruff.exe check src/langgraph_biz_worker/runtime/llm_agent_prompts.py src/langgraph_biz_worker/runtime/llm_skill_agent.py tests/test_llm_skill_agent.py tests/test_e2e_scripted_tool_call_streaming.py`
  - result: `All checks passed!`
- `cd tools/langgraph-biz-worker; .\.venv\Scripts\python.exe -m pytest -q`
  - result: `608 passed, 6 skipped, 11 warnings`
- `cd tools/langgraph-biz-worker; $env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_api_root_plain_final_commits_runtime_memory_without_retry_prompt -q`
  - result: `1 passed, 3 warnings`
- `cd tools/langgraph-biz-worker; $env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest tests/test_llm_skill_agent.py tests/test_llm_message_builder.py tests/test_llm_submission_log.py tests/test_e2e_scripted_tool_call_streaming.py -q`
  - result: `90 passed, 3 warnings`
- `cd tools/langgraph-biz-worker; .\.venv\Scripts\ruff.exe check src/langgraph_biz_worker/runtime/llm_agent_prompts.py src/langgraph_biz_worker/runtime/llm_skill_agent.py tests/test_llm_skill_agent.py tests/test_e2e_scripted_tool_call_streaming.py`
  - result: `All checks passed!`
- `cd tools/langgraph-biz-worker; .\.venv\Scripts\python.exe -m pytest -q`
  - result: `609 passed, 6 skipped, 11 warnings`

### Experience

- status: verified-by-tests-and-local-smoke
- 原因：本条目为 Worker LLM submission 组装契约，不涉及 UI 交互变更；体验证据以 `logs/llm-submissions/*.json` 和 `logs/runtime-message-events/*.jsonl` 为准。
- 本机 HTTP BizWorker + mock LLM smoke 已通过：Root 普通多轮 session `bctx_20260521_23_2372853b5366440cb39d3268e3ec1eab` 生成两次 LLM body，role 序列为 `["system","human"]`、`["system","human","ai","human"]`；child/frame session `bctx_20260521_2a_2a7d5bd415dd47adb18c7cf7305a6763` 同时在 runtime events / tool audit 中记录 frame 工具链。
- 真实 qwen3.5-plus provider smoke 在 provider 请求阶段超时，session `bctx_20260521_b1_b1fc1510f49742b5ad541c8903ce100c` 仅作为外部超时阻塞证据，不作为本契约验收通过证据。
