# 主流 Agent 运行时上下文治理对比报告

## 文档作用

- doc_type: research-report
- intended_for: architecture-discussion | execution-agent | reviewer
- purpose: 对比 Claude Code、OpenAI Codex、OpenAI Agents SDK、LangGraph、AutoGen、OpenHands 的上下文与压缩策略，收口 BizWorker 运行时上下文治理基线

## Version

- `1.1.6-SNAPSHOT`

## 状态

- status: design-discussion
- date: 2026-05-21
- coding_status: not-started

## 本次确认的 BizWorker 基线

本报告以以下前提为准：

1. 上游自行保存完整会话历史，包括用户消息、LLM 每次回复、UI 需要展示的 transcript。
2. 上游调用 BizWorker 时提供 `contextId`。
3. BizWorker 只负责维护 LLM 运行时上下文，即当前模型调用实际需要看到的 bounded context。
4. BizWorker 发生上下文压缩后，只维护压缩后的摘要态和必要近期窗口，不维护压缩前完整历史消息栈。
5. BizWorker 可以完整保存 frame、report、tool log 等排障/审计产物；限制只作用于 LLM runtime context，不作用于排障/审计数据完整度。

## 术语约定

| 术语 | 含义 | 所属方 |
| --- | --- | --- |
| 完整会话历史 | 用户消息、助手消息、工具过程、UI 需要回放的 transcript | 上游 |
| 运行时上下文 | 每次提交给 LLM 的 prompt-visible context | BizWorker |
| 压缩态 | 旧上下文被摘要、裁剪或合并后的 bounded runtime state | BizWorker |
| 排障产物 | frame、report、tool log、LLM 调试日志 | BizWorker，可完整保留 |
| `contextId` | 上游传给 BizWorker 的运行时上下文定位键 | 上游生成/提供，BizWorker使用 |

## 调研来源与边界

本次只采用官方文档、官方工程文章或官方仓库配置说明，不采用社区猜测作为设计依据。

1. Anthropic / Claude Code 官方文档：context window、memory、session、agent loop。
2. OpenAI 官方 Codex 工程文章与 Agents SDK 文档。
3. LangChain 官方 LangGraph persistence / memory 文档。
4. Microsoft AutoGen 官方文档。
5. OpenHands 官方文档、官方博客和官方配置模板。

Codex 的产品内部实现并非完全公开，本报告仅引用 OpenAI 已公开的 Codex agent loop 与 Agents SDK session/compaction 机制，不推断未公开细节。

## 主流做法摘要

### Claude Code / Claude Agent SDK

Claude Code 的公开文档把 context window 描述为会话中模型能看到的全部内容，包括对话历史、文件内容、命令输出、项目记忆、技能和系统指令。Claude Code 还提供 `/compact`：当上下文过长时，用结构化摘要替换旧对话，从而释放上下文窗口。

Claude Agent SDK 的 session 文档进一步说明：session 是 SDK 累积的 conversation history，包含 prompt、tool call、tool result 和 response，并可写入磁盘用于 resume / fork。Agent loop 文档也说明长会话会积累大量上下文，接近限制时会自动压缩，保留最近交换和关键决策。

对 BizWorker 的启发：

1. 需要明确区分“完整 session 历史”和“当前 context window”。
2. 压缩不是丢失任务连续性，而是把旧消息栈替换成可继续工作的结构化摘要。
3. 项目/技能/环境类上下文应该按需加载，避免长期塞满运行时上下文。

与我们的差异：

1. Claude Agent SDK 自身可以拥有完整 session 历史。
2. 我们已决定完整历史由上游保存，BizWorker 只维护运行时上下文和压缩态。
3. 因此 BizWorker 更接近 Claude Code 的“当前 context window + compacted summary”，而不是完整 session store。

### OpenAI Codex

OpenAI 的 Codex agent loop 工程文章说明：Codex harness 会把用户输入、工具定义、指令、环境变化等组织成 Responses API 请求；当模型要求 tool call 时，执行工具并把输出追加进下一轮模型输入，直到模型给出最终 assistant message。

同一篇文章还明确提到，Codex 为避免 context window 超限，会在 token 超过阈值时 compact conversation，把 `input` 替换成更小、但代表当前会话进展的新列表，让 agent 能继续理解之前发生的事情。

对 BizWorker 的启发：

1. tool result 可以进入模型运行时上下文，但不能无限保留。
2. 压缩后运行时输入列表应被替换为较小的等价表示。
3. 配置变化、环境变化可以作为增量 runtime message 进入上下文，而不是回写历史消息。

与我们的差异：

1. Codex harness 是 coding agent 的完整 agent loop，完整 conversation 与 runtime input 由同一 harness 管理。
2. BizWorker 要拆开：完整 transcript 在上游，runtime input 在 Worker。
3. 因此 BizWorker 的压缩对象应是 Worker 自己维护的 runtime context，不是上游完整会话历史。

### OpenAI Agents SDK

OpenAI Agents SDK 的 session 文档说明：当传入 session 时，runner 会自动取出之前保存的 conversation items，prepend 到下一轮输入，并在每轮结束后持久化新的 user input 和 assistant output。文档还提供 `OpenAIResponsesCompactionSession`，用于自动压缩 stored conversation history；压缩会把底层 session 清空并用压缩后的 output 重写。

对 BizWorker 的启发：

1. session/memory 应通过明确接口接入 agent runner。
2. compaction 应是可替换的 session decorator / runtime context policy，而不是散落在 prompt 组装里。
3. 压缩后的内容可以成为 session 的新事实来源。

与我们的差异：

1. Agents SDK 的 session 默认是 conversation history store。
2. 我们不希望 BizWorker 成为完整 history store。
3. 可借鉴的是 `CompactionSession` 的思想：BizWorker runtime context store 被压缩后，旧 runtime items 被 compacted representation 替换。

### LangGraph

LangGraph 官方 persistence 文档以 `thread_id` 为核心：checkpointer 会在每个 super-step 保存 graph state checkpoint，线程包含一系列运行的累计状态。它支持 human-in-the-loop、resume、time travel 和 fault-tolerant execution。memory 文档还区分 thread-level short-term memory 与跨 session long-term memory。

对 BizWorker 的启发：

1. `contextId` 可以类比 LangGraph 的 `thread_id`，作为 Worker runtime state 的定位键。
2. frame journal / report 类似执行状态与审计轨迹，但还不是 prompt-ready memory。
3. 需要单独定义“哪些 checkpoint/state 会进入下一次 LLM runtime context”。

与我们的差异：

1. LangGraph checkpoint 主要是 graph state，可用于恢复执行。
2. BizWorker 还需要额外的 prompt memory 层：把 frame/report/tool outcome 压缩成 LLM 可读 runtime summary。
3. 如果只保留 checkpoint 而没有 runtime digest，下一轮 LLM 仍然不知道上一轮关键过程。

### Microsoft AutoGen

AutoGen 官方文档说明 AgentChat 的 agent 是 stateful 的；memory 通过 `query`、`update_context`、`add` 等接口，在特定 step 前把相关信息加入 agent 的 model context。`ListMemory` 示例是按时间顺序维护记忆，并把近期或相关记忆注入模型上下文。

对 BizWorker 的启发：

1. memory 应通过显式接口进入 model context，而不是让上游直接拼 prompt。
2. 运行时上下文可以是“当前任务 + 查询到的相关记忆”，不必是完整历史。
3. 内部消息、工具过程和最终回复可以分层管理。

与我们的差异：

1. AutoGen agent 自身可维护状态和 memory。
2. 我们的上游已保存完整 transcript，Worker 只维护 runtime context。
3. 因此 BizWorker memory 接口应偏向 bounded runtime memory，不偏向全量 transcript memory。

### OpenHands

OpenHands 官方资料显示两层设计：conversation history 会保存到本地或服务端 conversation；同时通过 context condenser 管理长会话运行时上下文。OpenHands 的 condenser 会在上下文增长超过阈值时摘要旧交互、保留近期交互，并把摘要作为上下文中的记忆继续使用。其配置模板还提供多种 condenser 类型：保留全量、mask older observations、recent、LLM summarizing、amortized forgetting、attention-based 等。

对 BizWorker 的启发：

1. 完整 conversation store 和 runtime condenser 可以分离。
2. 压缩策略应可配置，并可按成本、质量、可解释性选择。
3. 旧工具 observation 不应默认长期进入 prompt，可以 mask、recent-only 或 summarize。

与我们的差异：

1. OpenHands 仍保留完整 conversation history 作为产品侧会话历史。
2. 我们将完整 history 放在上游，不放在 BizWorker。
3. BizWorker 可借鉴的是 condenser：只维护当前 runtime window、summary 和必要 recent context。

## 与我们当前实现的对比

### 当前实现

当前链路存在三层上下文：

1. Java 层 `LanggraphTaskService.buildProviderContext(...)` 从 `SessionMessageRepository` 读取最近 12 条消息，写入 `context.recentConversation`。
2. Worker `root_graph._recent_conversation_prompt(...)` 从 `context.recentConversation` 生成 prompt 片段。
3. Worker frame/report/log 按 `contextId` 落盘，但普通下一轮 prompt 不会自动从 Worker 自有 runtime memory 恢复上一轮执行摘要。

此外，Worker `root_graph` 当前还有 `llm_request` / `llm_response` 级别日志写入逻辑。该日志适合排障，可以完整保留；但需要明确归类为 diagnostic logs，不能被下一轮 prompt 默认当作完整历史消息栈注入。

### 当前差距

| 维度 | 主流 Agent 常见做法 | 当前 BizWorker | 差距 |
| --- | --- | --- | --- |
| 会话定位 | session/thread/conversation id | `contextId` | 已具备 |
| 完整历史 | agent/session store 或产品 conversation store | Java/upstream 保存，Worker 部分记录日志 | 需要明确 Worker 不做完整 history store |
| 运行时上下文 | agent runtime 自己组装 | Java `recentConversation` + Worker prompt | 普通对话历史仍依赖 Java |
| 压缩 | compact/condenser/checkpoint summary | 尚未形成统一 runtime compaction | 需要新增 runtime context policy |
| 工具过程 | 可进入短期上下文，长期压缩/裁剪 | frame/report/log 落盘，不自动形成 digest | 需要 task/frame digest |
| active task 恢复 | checkpoint/session resume | frame state 支持部分恢复 | 需要与 runtime memory 明确衔接 |
| 排障日志 | 与 prompt context 分离 | 有 frame/report/tool log/LLM log | 需要保留策略与边界 |

## 建议的 BizWorker 目标模型

### 1. 上游完整历史，Worker 运行时上下文

上游保存：

1. 完整 transcript。
2. LLM 每次可见回复。
3. UI 需要展示的消息状态。
4. 用户会话级元数据。

BizWorker 保存：

1. 当前 runtime window。
2. compacted summary。
3. active frame state。
4. execution digest。
5. report/log 引用。

### 2. Worker runtime context store

建议在 `contextId` session 目录下引入逻辑上的 runtime context store。具体文件名待定，但建议至少分成：

1. `runtime-context/current.json`：当前可直接注入 LLM 的运行时上下文。
2. `runtime-context/compacted-summary.md` 或 `.json`：压缩后的长期运行态。
3. `runtime-context/recent-window.jsonl`：压缩后仍保留的少量近期消息或事件。
4. `runtime-context/digests.jsonl`：每轮 task/frame 的执行摘要。
5. `runtime-context/index.json`：active frame、最新 digest、report/log 引用索引。

如果后续希望更简单，可以先只做 `current.json` + `digests.jsonl`，再演进。

### 3. Prompt 组装顺序

建议 Worker 组装 prompt 时按如下顺序：

1. system prompt / skill registry / account context。
2. active frame state，如果存在未完成 frame。
3. compacted runtime summary。
4. recent runtime window。
5. 最近 task/frame execution digest。
6. 当前用户消息。
7. 当前请求显式 context。

`recentConversation` 迁移期可以继续接收，但目标状态应不依赖它。

### 4. 压缩策略

BizWorker 压缩时应替换 Worker 自己的 runtime context，而不是修改上游完整 transcript：

1. 触发条件：token budget、事件条数、frame 数、单轮工具输出过大。
2. 压缩输入：Worker runtime window、execution digest、active frame summary。
3. 压缩输出：新的 compacted summary + retained recent window。
4. 压缩后：丢弃压缩前的 runtime raw message stack。
5. 排障日志：可以完整保留，但不参与下一轮 prompt 默认注入。

### 5. 排障日志边界

为避免 Worker 把完整排障数据误当作运行时上下文，需要给数据分级：

1. `runtime memory`：模型下一轮会看到，必须 bounded。
2. `diagnostic logs`：模型默认看不到，只用于排障，可完整保留。
3. `reports`：结构化验收/复盘材料，可完整保留并按需读取。

当前 `llm_request` / `llm_response` 日志应放入 `diagnostic logs` 类别。它们可以完整保留，但不能被当作下一轮默认 prompt 历史；是否脱敏、轮转或清理属于存储治理问题，不属于 runtime context 压缩边界。

## 推荐结论

本轮治理不应把 BizWorker 改造成完整会话历史系统。更合适的方向是：

1. `contextId` 由上游提供，BizWorker 以它作为 runtime context key。
2. Java/upstream 保留完整历史与 UI transcript。
3. BizWorker 维护 bounded runtime context。
4. Worker 生成 execution digest，让工具过程和 frame 结果以摘要形式进入后续上下文。
5. Worker 发生压缩时，旧 runtime stack 被 compacted summary 替代。
6. frame/report/tool log 可以完整保留为排障产物，但不默认进入模型上下文。
7. 逐步去除 Java `SessionMessageRepository -> recentConversation -> prompt` 这条运行时上下文依赖。

## 后续讨论点

1. Worker 完整 `llm_request` / `llm_response` 调试日志如何标记为 diagnostic，而不被误用为 runtime memory。
2. 第一版 runtime context store 采用几个文件，是否先做最小实现。
3. 压缩由规则触发、LLM 触发，还是二者结合。
4. execution digest 的 schema 是否需要对 UI 暴露。
5. Java `recentConversation` 去依赖时，是直接移除，还是做 feature flag 灰度。

## Sources

1. Claude Code context window: https://code.claude.com/docs/en/context-window
2. Claude Code memory: https://docs.anthropic.com/en/docs/claude-code/memory
3. Claude Agent SDK sessions: https://platform.claude.com/docs/en/agent-sdk/sessions
4. Claude Agent SDK agent loop: https://code.claude.com/docs/en/agent-sdk/agent-loop
5. OpenAI Codex agent loop: https://openai.com/index/unrolling-the-codex-agent-loop
6. OpenAI Agents SDK sessions: https://openai.github.io/openai-agents-python/sessions/
7. OpenAI Agents SDK JS sessions: https://openai.github.io/openai-agents-js/guides/sessions/
8. LangGraph persistence: https://docs.langchain.com/oss/python/langgraph/persistence
9. LangGraph memory: https://docs.langchain.com/oss/python/langgraph/add-memory
10. AutoGen memory: https://microsoft.github.io/autogen/dev/user-guide/agentchat-user-guide/memory.html
11. AutoGen agent reference: https://microsoft.github.io/autogen/stable/reference/python/autogen_agentchat.agents.html
12. OpenHands context condenser: https://www.openhands.dev/blog/openhands-context-condensensation-for-more-efficient-ai-agents
13. OpenHands CLI conversation history: https://docs.all-hands.dev/usage/how-to/cli-mode
14. OpenHands official config template: https://github.com/OpenHands/OpenHands/blob/main/config.template.toml

## Progress Tracking

### Development Progress

- status: not-started
- 本次仅完成官方资料调研与设计报告落档。

### Testing Progress

- status: not-run
- 本次未改代码，未运行自动化测试。

### Experience Progress

- status: N/A
- 原因：本报告为后端/Worker 运行时上下文治理设计，不涉及 UI 交互变更。
