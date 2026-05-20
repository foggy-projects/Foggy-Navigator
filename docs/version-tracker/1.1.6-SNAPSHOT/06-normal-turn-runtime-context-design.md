# 普通消息 Runtime Context 设计

## 文档作用

- doc_type: design
- intended_for: architecture-discussion | execution-agent | reviewer
- purpose: 明确普通用户消息在 BizWorker 内如何写入、恢复、压缩和组装为下一轮 LLM runtime context，避免继续依赖 Java `recentConversation` 作为 prompt source of truth

## Version

- `1.1.6-SNAPSHOT`

## 状态

- status: design-discussion
- date: 2026-05-21
- coding_status: not-started
- test_status: not-run

## 与已有文档的关系

本设计承接：

1. [01-biz-worker-context-owned-runtime-memory.md](./01-biz-worker-context-owned-runtime-memory.md)
   - 明确 BizWorker 按 `contextId` 自主管理 LLM runtime context。
2. [03-skill-internal-context-isolation-and-promotion.md](./03-skill-internal-context-isolation-and-promotion.md)
   - 明确 Skill 内部上下文隔离和 promoted result 规则。
3. [04-runtime-visible-conversation-and-recovery-design.md](./04-runtime-visible-conversation-and-recovery-design.md)
   - 明确 `runtimeVisibleConversation`、tool call 可见性、中断恢复控制态。

本文件进一步把“普通消息”从进入 BizWorker 到下一轮 prompt 的完整生命周期落成可执行设计。

## 设计原则

1. `contextId` 是 BizWorker runtime context 的唯一会话入口。
2. Java / 上游保存完整 UI transcript；BizWorker 不保存完整历史消息栈。
3. BizWorker 只维护 bounded runtime context，包括近期语义窗口、压缩摘要、active focus、恢复控制态和报告引用。
4. Java `recentConversation` 只作为迁移期兼容输入，不作为目标架构的 prompt source of truth。
5. Tool call、tool result、Skill 内部 LLM 消息、日志和 report 默认是 execution trace，不是下一轮普通 semantic conversation。
6. 同一 `contextId` 不允许真正并发执行。用户可在会话执行中继续发送消息，但这些消息只能进入 BizWorker 队列，并在当前 LLM loop 到达安全 checkpoint 后插入处理。

## 当前实现事实

以下事实只作为迁移约束，不作为目标设计依据：

1. Java `LanggraphTaskService.buildProviderContext(...)` 当前会从 `SessionMessageRepository` 读取最近 12 条消息并写入 `context.recentConversation`。
2. Python `root_graph._recent_conversation_for_runtime(...)` 当前会把该字段转为 `_visible_recent_conversation`。
3. `llm_agent_prompts._build_visible_recent_conversation_prompt(...)` 当前会把 `_visible_recent_conversation` 注入 LLM prompt。
4. Root frame 已经是 persistent frame，并已有 `turn_results`、`root_context_summary`、`active_plan`、`recoverable_interruption`、active focus 等运行态字段。

目标设计不要求 Java session history 消失；它仍可以服务 UI 展示、会话导出和上游审计。但 BizWorker prompt assembly 的权威数据应迁回 BizWorker。

## 目标抽象

建议引入逻辑抽象：

```text
ContextRuntimeMemory
```

它是 BizWorker 按 `contextId` 维护的 runtime memory，不等同于完整会话历史。

第一阶段可以把它落在 Root frame `private_working_state` / `root_context_summary` 中，以减少改造面；但代码上应通过独立接口访问，避免长期把 frame 内部结构暴露给 prompt 组装逻辑。后续可迁移到独立文件，例如 `runtime-memory.json` / `recent-window.jsonl`，而不改变上层语义。

## 术语与字段映射

| 名称 | 所属层 | 目标语义 | 状态 |
| --- | --- | --- | --- |
| `ContextRuntimeMemory` | BizWorker runtime abstraction | `contextId` 维度的 runtime memory 容器，包含可见语义窗口、压缩摘要、pending turn、队列和控制索引 | 目标新增 |
| `runtime_context_memory` | Root frame storage backend | 第一阶段落在 Root frame `private_working_state` 下的事实存储字段 | Phase 1 backend |
| `visibleMessages` | `ContextRuntimeMemory` 内部字段 | 下一轮 LLM 默认可见的 bounded user/assistant semantic turns | 目标字段 |
| `_runtime_visible_conversation` | prompt runtime context | 从 `ContextRuntimeMemory.build_prompt_view()` 派生的 prompt 注入视图 | 目标 prompt input |
| `runtimeVisibleConversation` | 设计概念 / 对外说明 | BizWorker-owned semantic window，不是完整会话历史，不是 raw execution trace | 推荐术语 |
| `recentConversation` | Java / 上游兼容字段 | 迁移期 external compatibility input；只允许空 memory bootstrap | deprecated |
| `_visible_recent_conversation` | Python 旧 prompt input | 由 Java `recentConversation` 派生的旧 prompt 输入 | deprecated compatibility |

实现时应避免让 prompt 代码直接读取 Root frame 内部字段。prompt 只能消费 `_runtime_visible_conversation`、`compactedSummary` 等由 `ContextRuntimeMemory` 生成的视图。

## 存储建议

第一阶段建议以 Root frame 为事实存储，暂不直接引入独立 runtime memory 文件。

原因：

1. Root frame 已经按 `contextId` / conversation 复用，并且是 persistent frame。
2. active focus、recoverable interruption、turn results、root context summary 已经在 Root frame 生命周期内维护。
3. 普通消息 runtime memory 与 Root frame 状态强相关，第一阶段放在 Root frame 内可以避免 frame state 与 memory file 双写不一致。
4. 当前最重要的是先把 prompt source of truth 从 Java `recentConversation` 迁回 BizWorker，独立文件可以作为后续存储演进，不应阻塞语义收口。

约束：

1. prompt 组装逻辑不直接读写 `private_working_state`。
2. 新增 `ContextRuntimeMemory` 接口或等价 helper，封装 load / begin turn / commit turn / compact / build prompt view。
3. Root frame 内部结构只是第一阶段 backend，后续可以迁移到 session 目录下的 `runtime-memory.json` / `recent-window.jsonl`。
4. 可选地在后续阶段生成只读 mirror 文件，便于排查和备份，但 mirror 不作为第一阶段事实源。

## 数据结构草案

```json
{
  "schemaVersion": 1,
  "contextId": "bctx_20260521_ab_xxx",
  "revision": 12,
  "updatedAt": "2026-05-21T10:00:00Z",
  "compactedSummary": {
    "summary": "此前用户主要在处理 TMS 工单创建与上下文治理讨论。",
    "coveredMessageCount": 18,
    "updatedAt": "2026-05-21T09:55:00Z",
    "reportRefs": []
  },
  "pinnedHeadMessages": [],
  "visibleMessages": [
    {
      "messageId": "rtm_lgt_001_user",
      "role": "user",
      "content": "帮我生成一个工单",
      "taskId": "lgt_001",
      "rootFrameId": "frm_root",
      "createdAt": "2026-05-21T09:58:00Z",
      "metadata": {
        "source": "user"
      }
    },
    {
      "messageId": "rtm_lgt_001_assistant",
      "role": "assistant",
      "content": "请提供工单类型、标题、详细描述和关联运单号。",
      "taskId": "lgt_001",
      "rootFrameId": "frm_root",
      "createdAt": "2026-05-21T09:58:05Z",
      "metadata": {
        "source": "skill_projection",
        "skillId": "tms-ticket-agent",
        "skillFrameId": "frm_child",
        "reportRef": "frame-report://lgt_001/frm_child",
        "promotedResultDigest": {
          "turnStatus": "WAITING_FOR_USER_INPUT"
        }
      }
    }
  ],
  "pendingTurn": null,
  "pendingUserInputs": [],
  "limits": {
    "headTurnCount": 2,
    "tailTurnCount": 8,
    "maxMessageChars": 1200,
    "maxSummaryChars": 4000
  }
}
```

### 字段规则

| 字段 | 规则 |
| --- | --- |
| `visibleMessages` | 只保存下一轮 LLM 默认可见的语义消息，主要是 `user` / `assistant` |
| `pinnedHeadMessages` | 保留会话开头的少量语义轮次，通常用于最初目标、约束和身份定位 |
| `compactedSummary` | 超出窗口后的压缩摘要，不保留被压缩前的完整 raw message stack |
| `pendingTurn` | 当前用户消息已进入执行但尚未形成最终可见 assistant 结果时使用 |
| `pendingUserInputs` | 当前 loop 执行中用户继续发送的消息队列，只在安全 checkpoint 插入 |
| `metadata.reportRef` | 指向完整执行证据，不展开 report/log |
| `metadata.promotedResultDigest` | 只放 compact digest，不放 raw tool result |
| `revision` | 用于并发保护和调试定位 |

## `contextId` 单线程与伪并发

同一个 `contextId` 下不允许多个 LLM loop 同时运行。

所谓“伪并发”只表示：用户可以在当前会话执行中继续发送消息，但 BizWorker 不能立即开启第二条并发执行链。新消息应进入 `pendingUserInputs`，等待当前 loop 到达安全 checkpoint。

### 安全 checkpoint

建议 checkpoint 定义为：

1. 每次 provider model call 返回之后。
2. 当前 model response 要求的同步工具调用已经完成之后。
3. 即将发起下一次 provider model call 之前。
4. frame 进入 `AWAITING_USER`、`AWAITING_APPROVAL`、`SUSPENDED`、`COMPLETED`、`FAILED` 等暂停或终态时。

不应在以下位置强行插入用户消息：

1. provider model call 还未返回时。
2. 已发出的不可中断副作用工具调用执行过程中。
3. BusinessFunction 正处于提交/审批状态且没有形成一致状态边界时。

### 插入规则

当 checkpoint 发现 `pendingUserInputs` 非空：

1. 将队列中的消息按时间顺序合并为一个 runtime user update，或逐条作为 user visible message 写入。
2. 在下一次 LLM prompt 中明确标记这些消息是在执行中追加的用户输入。
3. 由当前 active frame 的 LLM 决定其语义：补充信息、修正指令、取消当前任务、切换任务或无关新请求。
4. 处理完成后再提交到 `visibleMessages`，并递增 `revision`。

如果当前存在 `AWAITING_USER` active focus，则新消息仍优先进入该 child frame 的恢复路径；这属于确定性路由，不需要 Root 先判断。

### Phase 1 临时并发契约

`pendingUserInputs` 队列和 checkpoint 插入属于目标能力，但不进入 Phase 1 最小闭环。

因此 Phase 1 必须显式声明临时行为：

1. 不允许同一 `contextId` 在 BizWorker 内启动第二条 LLM loop。
2. 如果上游/Java 已经保证同一 `contextId` 串行提交，则 Phase 1 继续沿用该保护。
3. 如果 BizWorker 在 Phase 1 检测到同一 `contextId` 已有 running loop，但还没有实现 `pendingUserInputs`，应返回明确 busy / conflict / retryable 状态，而不是悄悄开启并发执行。
4. Phase 3 实现后，该临时 busy 行为升级为 queued / accepted，并在 loop checkpoint 插入。

## 普通消息生命周期

### 1. 请求进入

上游调用 BizWorker 时必须提供：

1. `contextId`
2. 当前用户消息
3. 当前任务需要的权限、模型、附件、显式业务 context

BizWorker 执行：

1. 校验并解析 `contextId`。
2. 加载或创建对应 Root frame。
3. 通过 `ContextRuntimeMemory` 加载当前 runtime memory。
4. 将当前用户消息登记为 `pendingTurn`，但不从 `visibleMessages` 再重复注入 prompt。

`pendingTurn` 示例：

```json
{
  "taskId": "lgt_002",
  "rootFrameId": "frm_root",
  "userMessage": "今天能继续刚才那个工单吗？",
  "createdAt": "2026-05-21T10:01:00Z",
  "status": "RUNNING"
}
```

### 2. Prompt 组装

Root LLM prompt 的目标顺序：

1. system / account / safety / tool contract
2. recovery control state，例如 `_recoverable_interruption`
3. active focus / awaiting-user control state
4. `compactedSummary`
5. bounded `visibleMessages`
6. 当前用户消息
7. 当前请求显式 context

注意：

1. 当前用户消息作为本轮 prompt 的主输入，不依赖 `visibleMessages`。
2. `visibleMessages` 只表示本轮之前已经提交成功的语义窗口。
3. `recentConversation` 不再参与默认 prompt assembly。
4. Tool call 不进入默认语义窗口；需要解释执行过程时按 `reportRef` 读取。

### 3. Root 直接回复

场景：

```text
U1 -> Root -> A1
```

完成时提交：

```text
visibleMessages += [U1, A1]
pendingTurn = null
```

`A1` 来源于 Root 的最终用户可见回复。

### Assistant 可见内容来源

`A1` 必须接近前端最终用户可见回复，不能随意取内部 summary 或 raw tool result。建议统一抽象为：

```text
assistantVisibleContent
```

提取优先级：

1. Root / Skill 明确返回的 user-facing final message。
2. structured output 中声明为用户可见的 `message` / `userMessage` / `finalResponse` 等字段。
3. `submit_skill_result.summary` 或 promoted result 的 compact user-facing summary。
4. 不可恢复错误时的标准化 user-visible error message。

不应作为 `assistantVisibleContent` 的内容：

1. raw tool call / tool result。
2. Skill private LLM messages。
3. report/log/journal 的大块原文。
4. 仅供调试的内部摘要。

如果没有可靠的用户可见回复，不能伪造普通 assistant turn；应优先写 recovery/error control state，或在不可恢复错误场景写标准化错误 projection。

### 4. Root 调用 Skill 且正常完成

场景：

```text
U1 -> Root tool_call(invoke_business_skill) -> Skill S1 -> Root/Skill final A1
```

下一轮默认可见窗口：

```text
U1 -> A1
```

提交规则：

1. `U1` 作为 user message 写入。
2. `A1` 作为 assistant message 写入。
3. Skill 的 `frameId`、`reportRef`、`promotedResultDigest` 写入 `A1.metadata`。
4. raw `tool_call`、raw `tool_result`、Skill private messages 不写入 `visibleMessages`。

### 5. Child Skill 进入 `AWAITING_USER`

场景：

```text
U1 -> Root invokes Skill -> Skill asks A1(需要用户补充)
```

提交规则：

1. `visibleMessages += [U1, A1]`。
2. Root frame 保存 active focus / pending awaiting-user child。
3. 下一条用户消息 `U2` 到来时，路由优先走 active focus resume，而不是先让 Root 重新判断。
4. Child prompt 可以看到 `_awaiting_user_input`、当前 `U2`、child frame state，以及按策略允许的 root summary。

下一轮 UI 语义看起来是：

```text
U1 -> A1 -> U2
```

但执行路由上，`U2` 直接进入同一个 child Skill frame。

### 6. 可恢复中断

场景：

```text
U1 -> 执行中断，未产生可用 A1
```

提交规则：

1. 不制造假的 assistant message。
2. `pendingTurn` 进入 `INTERRUPTED` 或被 recovery control state 引用。
3. Root frame 写入 `continuation_state=INTERRUPTED`、`recoverable=true`、`last_task_id`、`interrupt_reason`、focus summary。
4. 下一条用户消息进入时，prompt 包含 recovery control state，由 Root 识别继续、放弃、开启新任务或询问澄清。

当用户明确放弃或开启新任务时，可把旧 pending turn 压成 interruption summary，而不是写成普通 assistant 对话。

### 7. 不可恢复错误

如果本轮已经形成用户可见错误，例如配置错误、权限错误、业务函数不可恢复失败：

```text
visibleMessages += [U1, A_error]
```

`A_error.metadata` 应包含 compact error category 和 `reportRef`，但不泄露敏感配置或完整堆栈。

如果本轮只是内部执行失败且没有合理用户可见答复，应以 recovery/error control state 保留，不强行写入普通语义窗口。

## 压缩策略

压缩建议一步到位采用 head-tail + LLM summarizer。

### Prompt 可见形态

下一轮 prompt 默认看到：

```text
pinned head turns
middle compacted summary
tail recent turns
current user message
```

其中：

1. `pinnedHeadMessages` 保留最早 N 轮语义 turn，用于保存初始目标、边界、长期约束。
2. `compactedSummary` 汇总中间历史。
3. `visibleMessages` 保留最近 N 轮语义 turn。
4. 当前用户消息单独放在 prompt 末尾，不从历史窗口重复注入。

这里的“轮”应按 user/assistant semantic turn 成对处理，不只保留用户消息。否则会丢失上一次 assistant 的承诺、澄清问题、执行结论和错误说明。

### 建议默认值

压缩阶段建议：

1. `headTurnCount = 2`
2. `tailTurnCount = 8`
3. `maxMessageChars = 1200`
4. `maxSummaryChars = 4000`

这些值应通过配置暴露，方便按模型上下文窗口调整。

### Summarizer 输入

每次提交 turn 后，如果超过预算，压缩输入应包含：

1. 旧 `compactedSummary`
2. 即将移出 tail 窗口的中间 semantic turns
3. 相关 `reportRef` / `artifactRefs` / `promotedResultDigest`

不应输入：

1. raw tool call
2. raw tool result
3. Skill private messages
4. 大块日志
5. secrets、token、签名 URL 等敏感信息

### Summary 结构

LLM summarizer 输出应是结构化摘要，而不是纯自然语言长段落：

```json
{
  "durableUserIntent": "",
  "decisionsAndConstraints": [],
  "businessEntities": [],
  "completedWork": [],
  "openQuestions": [],
  "pendingActions": [],
  "errorsAndRecovery": [],
  "reportRefs": [],
  "coveredMessageIds": []
}
```

### 失败兜底

LLM summarizer 是目标能力，但不应成为 runtime correctness 的单点失败。

如果 summarizer 调用失败：

1. 使用 deterministic fallback，把中间 turns 截断拼接为 compact text。
2. 标记 `summaryQuality=fallback`。
3. 不阻断当前用户回合完成。
4. 完整 evidence 仍在 frame/report/log/journal 或上游 UI transcript 中保留。

压缩发生在 turn commit 之后、下一轮 prompt 之前。不要在 prompt 组装时临时做不可追踪压缩。

## 与 Skill Context 的关系

普通 Root prompt 可以读取 `ContextRuntimeMemory`。

Child Skill prompt 默认不读取完整 `visibleMessages`。它只接收：

1. 当前 Skill instruction / input
2. 当前用户补充消息
3. `_awaiting_user_input` 或恢复控制态
4. 允许透传的 root summary / promoted result digest
5. 当前任务显式 context、附件和权限

如果某个 Skill 明确需要会话摘要，应通过 manifest / context visibility 策略申请 compact summary，而不是默认拿到完整近期对话。

## 与 Java 的迁移边界

### 目标边界

Java / 上游继续负责：

1. UI transcript
2. 会话列表、导出、搜索
3. 上游业务态和权限态
4. 调用 BizWorker 时传入 `contextId` 和当前用户消息

BizWorker 负责：

1. LLM runtime visible window
2. runtime compaction summary
3. active focus / recoverable interruption
4. prompt assembly
5. frame/report/log evidence

### 迁移期兼容

短期保留 `recentConversation` 读取能力，但只能作为兼容兜底：

1. 如果 BizWorker 当前 `contextId` 下没有任何 runtime memory，可用 Java `recentConversation` 做一次性 bootstrap。
2. bootstrap 后写入 `ContextRuntimeMemory`，metadata 标记 `source=external_compat_bootstrap`。
3. 一旦 BizWorker 有自己的 `revision > 0`，默认忽略 Java `recentConversation`。
4. 不允许每轮都用 Java `recentConversation` 覆盖 BizWorker runtime memory。

目标阶段：

1. Java 不再默认注入 `recentConversation`。
2. BizWorker prompt 组装完全基于 `ContextRuntimeMemory`。
3. `recentConversation` 字段可保留一段时间用于兼容旧调用方，但标记 deprecated。

## 建议开发分期

### Phase 1：建立 BizWorker-owned 普通语义窗口

目标：

1. 新增 `ContextRuntimeMemory` 访问接口。
2. 第一阶段存储落在 Root frame `private_working_state.runtime_context_memory`，由 `ContextRuntimeMemory` 接口封装读写。
3. 请求进入时登记 `pendingTurn`。
4. Root persistent turn 成功结束时提交 `[user, assistant]`。
5. Prompt 使用 BizWorker memory，不再优先读取 Java `recentConversation`。
6. Java `recentConversation` 仅作为空 memory bootstrap。
7. Phase 1 暂不实现 `pendingUserInputs`；执行中追加消息必须由上游串行保护，或由 BizWorker 返回明确 busy / conflict / retryable 状态。

### Phase 2：补齐恢复和 Skill 投影

目标：

1. `AWAITING_USER` 时把 Skill 的用户可见问题提交为 assistant visible message。
2. normal Skill completion 时把 promoted result 投影到 assistant metadata。
3. recoverable interruption 不写假 assistant message，而是进入 recovery control state。
4. non-recoverable user-visible error 写成 assistant error projection。

### Phase 3：pendingUserInputs 队列与 checkpoint

目标：

1. 增加 context-level running marker 和 `revision` 并发保护。
2. 明确 `pendingUserInputs` 队列和 checkpoint 插入。
3. 执行中新消息不启动第二条 LLM loop，而是返回 queued / accepted。
4. `AWAITING_USER` active focus 优先消费 queued input。

### Phase 4：head-tail + LLM summarizer 压缩

目标：

1. 增加 head-tail + LLM summarizer compaction。
2. 增加 deterministic fallback。
3. 配置 `headTurnCount`、`tailTurnCount`、`maxMessageChars`、`maxSummaryChars`。
4. 如需要，生成独立 `runtime-memory.json` / `recent-window.jsonl` mirror，但不改变事实源语义。

### Phase 5：Java recentConversation 退场

目标：

1. Java 默认不再查询 `SessionMessageRepository` 注入 `recentConversation`。
2. 更新 Java 测试，不再断言 provider context 包含 Java recent conversation。
3. 保留显式兼容开关或旧字段读取一段时间。

## 代码触点候选

1. `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/skill_runtime.py`
   - Root frame runtime memory 的读写、pending turn、commit、compaction。
2. `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/context_memory.py`
   - 建议新增，封装 `ContextRuntimeMemory` 逻辑，避免 prompt 代码直接读写 frame internals。
3. `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py`
   - 请求进入时 load/begin turn，prompt assembly 注入 BizWorker memory，迁移 `recentConversation` bootstrap。
4. `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_agent_prompts.py`
   - `_build_visible_recent_conversation_prompt` 迁移为 `_build_runtime_visible_conversation_prompt`。
5. `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_agent.py`
   - persistent turn completed / awaiting / error 时触发 memory commit 或返回可提交的投影信息。
6. `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphTaskService.java`
   - 迁移期后停止默认注入 `recentConversation`。

## 测试规划

需要补自动化测试：

1. 普通两轮对话：第二轮 prompt 包含 BizWorker memory 中的 `U1 -> A1`，不依赖 Java `recentConversation`。
2. Java `recentConversation` bootstrap：空 memory 时可导入一次；memory 已存在时不覆盖。
3. Skill 正常完成：下一轮 prompt 是 `U1 -> A1 -> U2`，不包含 raw `tool_call` / `tool_result`。
4. `AWAITING_USER`：`U2` 被送回同一个 child frame，Root prompt 不先消费它。
5. Recoverable interruption：不写假 assistant message，下一轮 prompt 包含 recovery control state。
6. Non-recoverable visible error：提交用户可见错误 projection，不折叠为普通 max-iterations。
7. Compaction：超过窗口后，中间 turns 进入 LLM summary，prompt 保留 head + summary + tail，不包含被压缩 raw messages。
8. Summarizer fallback：summarizer 失败时使用 deterministic fallback，不阻断 turn commit。
9. 伪并发队列：同一 `contextId` 执行中新用户消息不会启动第二条 LLM loop，只会在 checkpoint 插入。

## 验收标准

1. BizWorker 在没有 Java `recentConversation` 的情况下，能连续处理普通多轮对话。
2. 普通下一轮 prompt 的历史来源可追踪到 `ContextRuntimeMemory`。
3. Tool call / Skill 内部 trace 完整保留在 report/log/journal，但不默认进入语义窗口。
4. Skill 完成、awaiting-user、中断恢复、不可恢复错误都有明确的 visible message / control state 归属。
5. 上游完整 UI transcript 与 BizWorker bounded runtime context 职责清晰分离。
6. 实现完成后，Java `SessionMessageRepository` 不再是 BizWorker prompt 连续性的核心依赖。

## 待确认问题

1. `headTurnCount` / `tailTurnCount` 的默认值是否按 2 / 8 起步。
2. 伪并发队列中多条用户消息是否默认合并成一个 runtime update，还是逐条插入。
3. 用户追问“刚才怎么执行的”时，是否新增专用 report reader tool，还是先由 Root 根据 `reportRef` 读取摘要。

## Progress Tracking

### Development

- status: not-started
- 当前仅完成设计落档。

### Testing

- status: not-run
- 本次未改代码，未运行测试。

### Experience

- status: pending
- 普通多轮对话连续性、awaiting-user 续接和中断恢复都属于用户可感知行为，开发完成后需要通过端到端会话验证。
