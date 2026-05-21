# LLM Submission Message Contract

## 文档作用

- doc_type: design
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 收口 BizWorker 真实提交给 LLM 的 messages 数组契约，统一 system / human 边界、Skill 描述注入和 submission 复盘日志规则

版本：`1.1.6-SNAPSHOT`
状态：已实现并通过回归
类型：runtime context governance / prompt contract

## 背景

Phase 1-5 已经把 BizWorker 调整为 LLM runtime context 的事实源：上游保存完整 UI transcript，BizWorker 维护模型运行所需的 bounded runtime context。

在随后联调中发现，旧 prompt 组装仍把大量运行时治理信息拼进 `human` message，例如：

- 当前 root / skill turn context
- runtime-visible conversation
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

这些内容中，治理说明进入 `system`；BizWorker 已维护的历史用户/助手语义轮次不进入 system 文本，而是作为独立 role messages 注入。

### 3. runtime-visible conversation 使用 role messages

BizWorker-owned runtime-visible conversation 按主流 Agent 对话结构注入为独立消息，而不是折叠进 system 文本。

当前顺序：

```text
system
历史 user
历史 assistant
...
当前 human
```

这样 LLM submission body 能直接复盘“上一轮用户说了什么、助手最终答了什么”，也更接近 Claude Code / Codex / OpenAI Agents 的多轮 messages 结构。

注意：

- 这里只注入 BizWorker runtime memory 中受控保留的 semantic user / assistant messages。
- raw tool call、tool result、Skill private messages、frame report/log/journal 仍作为 execution trace 保留，不默认变成下一轮普通语义 role messages。
- 若上下文压缩发生，压缩摘要按后续 compaction 设计进入受控上下文；不会恢复完整历史栈。
- 当前初始 messages 由 `runtime/llm_message_builder.py` 统一组装。执行 loop 只在模型返回后追加 assistant / tool messages，不再负责 system / history / current-human 的契约拼接。

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
   - 异常中断恢复：从最后一个安全 checkpoint 恢复，必要时回退到 summary-based recoverable prompt。
3. 正常完成后的下一轮普通对话默认不重放 raw tool trace。
   - 下一轮默认看到 `U1 -> A1 -> U2`。
   - `A1` 可携带 promoted digest、reportRef、artifactRefs 等受控信息。
   - raw tool call / raw tool result 保留在 execution trace 和 runtime message event log 中。

### 4. system message 内容边界

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
   - 完成时必须调用 `submit_skill_result`。
5. 当前运行时上下文：
   - 运行时日期上下文：时区、业务日期、当前月份范围、相对日期解析规则。
   - active plan 与持久 root plan 策略。
   - `AWAITING_USER` 续接上下文。
   - recoverable interruption 续跑 / 搁置决策上下文。
   - frame result contract。
   - 业务上下文。
   - 附件上下文。
   - 可见父级 / root summary。

精确到秒或毫秒的 `current_time` 不进入 system message。原因是它会让 system prompt 前缀在每次调用时变化，降低 LLM prompt cache 命中率。相对日期推理所需的低频信息保留在 system 中：业务日期通常每日变化，当前月份范围通常每月变化，缓存影响可控。

### 5. Skill 列表必须包含描述，并使用 Markdown

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

### 6. system.root 不作为业务 Skill 暴露

`system.root` 是历史内部实现标识，不是业务 Skill。

当前规则：

- 前端事件不显示 `Opening frame for skill: system.root`。
- LLM submission meta 使用 `conversation.root`。
- LLM-visible context 不出现 `system.root`。
- Root prompt 只表达“根编排 Agent”职责，不表达 `system.root`。

业务 Skill、BusinessFunction、frame report ref、artifact ref 仍可按需进入 system context，因为这些对业务回溯和后续决策有价值。

### 7. 附件上下文进入 system

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
- `04` 定义 runtime-visible conversation 与恢复。
- `06` 定义普通消息进入 runtime memory 的生命周期。
- `07` 定义 Phase 1-5 实施计划与进度。
- `08` 定义 `system.root` 退场和 conversation root frame。
- `09` 定义真实提交给 LLM 的 `messages` 数组契约。

若旧文档出现“runtime context 拼入 user prompt”这类表述，以本文件为当前实现口径。

## 验收标准

1. 当前 `human` message 以用户原文为主；只有显式传入 `current_time/currentTime` 时，允许在尾部追加 `当前请求时间` 块。
2. runtime governance context、business context、active plan、recoverable interruption、awaiting user context 都进入 `system` message。
3. BizWorker-owned runtime-visible conversation 以独立 `user` / `assistant` role messages 注入，不折叠进 system 文本。
4. root system prompt 为中文。
5. `allowed_skills` 在 LLM-visible context 中包含 description，并以 Markdown `可用业务技能` 列表呈现。
6. LLM submission 中不出现 `system.root` 作为业务 Skill 身份。
7. 附件上下文标题为中文，并进入 system message。
8. submission log 能按序保存真实 ChatModel body。
9. runtime message event JSONL 能记录同一 frame 的初始 messages、assistant response、assistant tool_call、tool_result 和 checkpoint。
10. 精确 `current_time` 不进入 `system` message；仅时区或业务日期存在时，不生成 `human` 尾部精确时间块。

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
- 2026-05-21: 新增 runtime message event JSONL 写入，记录同一 frame 的 provider 协议消息事件；`AWAITING_USER` 与 recoverable child interruption 恢复已接入同一日志 reader，按 `frameId` 找最新事件文件并重建 provider 协议 messages。

### Testing

- status: passed
- `cd tools/langgraph-biz-worker; .\.venv\Scripts\python.exe -m pytest tests/test_llm_message_builder.py tests/test_llm_skill_agent.py tests/test_e2e_scripted_tool_call_streaming.py tests/test_llm_submission_log.py`
  - result: `74 passed`
- `cd tools/langgraph-biz-worker; .\.venv\Scripts\python.exe -m pytest tests/test_config.py tests/test_llm_skill_agent.py tests/test_llm_message_builder.py tests/test_llm_submission_log.py`
  - result: `69 passed`
- `cd tools/langgraph-biz-worker; .\.venv\Scripts\python.exe -m pytest`
  - result: `589 passed, 6 skipped, 11 warnings`

### Experience

- status: N/A
- 原因：本条目为 Worker LLM submission 组装契约，不涉及 UI 交互变更。真实体验验证可通过 `logs/llm-submissions/*.json` 直接检查提交给 LLM 的 body。
