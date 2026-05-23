# BizWorker contextId 运行时上下文自主管理设计

## 文档作用

- doc_type: optimization
- intended_for: architecture-discussion | execution-agent | reviewer
- purpose: 记录 BizWorker 运行时上下文从 Java 会话层迁移到 Worker 按 `contextId` 自主管理的设计方向，作为后续讨论和开发前置文档

## Version

- `1.1.6-SNAPSHOT`

## 状态

- status: design-discussion
- priority: P1
- source_type: optimization / architecture-alignment
- coding_status: not-started

## 2026-05-21 已确认基线

1. 上游负责自行保存完整会话历史，包括用户消息、LLM 每次回复和 UI 展示需要的完整 transcript。
2. 上游调用 BizWorker 时必须提供 `contextId`；缺失 `contextId` 视为上游契约错误，不再作为 BizWorker 运行时记忆设计的正常分支。
3. BizWorker 只负责维护 LLM 的运行时上下文，即当前模型调用需要看到的 bounded context。
4. BizWorker 可以完整保存 frame、report、tool log 等排障/审计产物；不限制这些产物的信息完整度。
5. 当运行时上下文发生压缩时，BizWorker 只维护压缩后的运行态和必要的近期窗口，不维护压缩前完整历史消息栈。
6. Java 的 `SessionMessageRepository` 可以继续作为上游/UI 会话历史存储存在，但不应成为 BizWorker 组装 LLM prompt 的核心依赖。

## 背景

当前 LangGraph BizWorker 已经按 `contextId` 组织 frame、task、log、report，并把同一个会话的数据落到同一个目录下，便于排查、备份和治理。

但在普通用户消息进入 LLM 前，历史对话上下文仍有一部分来自 Java 层组装的 `recentConversation`：

1. Java `LanggraphTaskService.buildProviderContext(...)` 会从 `SessionMessageRepository` 读取最近消息。
2. Java 将这些消息放入 `context.recentConversation`。
3. BizWorker `root_graph` 在组装 prompt 时读取 `recentConversation`，拼入当前 LLM 请求。

这意味着当前“提交给 LLM 的运行时上下文”并非完全由 BizWorker 基于 `contextId` 恢复。Java 会话层不仅是入口代理和 UI 展示缓存，也参与了模型上下文组装。

## 当前边界判断

### Java / 上游职责

Java 或其他上游在调用 BizWorker 时应提供：

1. `contextId`
2. 当前用户请求
3. 当前任务相关的账号、权限、模型、技能、附件等显式上下文
4. 必要的调用链标识和 UI 会话标识

其中 `contextId` 是 Worker 查询、恢复和定位会话数据的入口参数。上游负责在后续请求中带回这个值。

### BizWorker 职责

BizWorker 应按 `contextId` 自主管理运行时上下文，而不是完整用户可见会话历史。职责包括：

1. runtime-visible conversation window
2. task / frame 生命周期
3. skill 调用过程
4. 完整工具调用日志
5. 完整 frame report
6. 每轮执行摘要
7. active frame 的恢复状态
8. 提交给 LLM 的运行时上下文组装

Java 或其他上游负责保留完整 UI 会话历史。BizWorker 可以持有“模型下一轮需要看到的近期窗口/压缩摘要”，但不维护完整历史消息栈。

补充边界：frame、report、tool log 属于排障/审计数据，可以完整保留；它们是否进入 LLM prompt 由 runtime context policy 控制。上下文压缩只约束“模型运行时可见内容”，不要求删除或截断排障/审计产物。

## 问题陈述

当前普通任务完成后，BizWorker 会落盘 frame、log、report，但下一轮普通任务不会自动把上一轮完整执行过程带回 LLM。

同时，下一轮 prompt 中的“最近对话”主要依赖 Java 传入的 `recentConversation`。这会带来几个问题：

1. BizWorker 的核心会话语义分散在 Java session 与 Worker frame 两边。
2. 换入口、换上游或绕过 Java 调用 Worker 时，对话连续性可能不一致。
3. Java 只能提供用户可见消息，不能稳定表达上轮执行过程、工具结果、决策和待办。
4. Worker 已经落盘的 frame/report/log 没有形成下一轮 LLM 可用的压缩记忆。

## 目标结果

目标不是每轮把历史工具调用和日志原样回放给 LLM，而是让 BizWorker 形成类似 Codex/Claude 的“同一 session 连续上下文”语义：

1. 上游通过 `contextId` 指定会话。
2. BizWorker 根据 `contextId` 读取自己的运行时上下文。
3. BizWorker 将必要的历史对话、执行摘要和 active frame 状态注入 prompt。
4. 完整日志和报告保留为按需检索数据，不默认塞进每次 LLM 请求。

补充设计见 [04-runtime-visible-conversation-and-recovery-design.md](./04-runtime-visible-conversation-and-recovery-design.md)：该文档把 `recentConversation` 的目标语义收敛为 BizWorker-owned `runtimeVisibleConversation`，并明确 tool call、Skill 正常完成、`AWAITING_USER`、recoverable interruption 在下一轮 prompt 中的可见性边界。

## 建议设计方向

### 1. Worker 侧会话记忆

在 `contextId` 对应的 session 目录下维护 Worker 自有运行时记忆，例如：

1. `recent-window.jsonl`：模型运行时可见的近期窗口，不等同于完整用户会话历史。
2. `execution-digests.jsonl`：每个 task/frame 完成后的压缩执行摘要。
3. `memory-index.json`：可选索引，记录最近 task、active frame、report 引用、重要 artifacts。

具体文件名仍需结合现有 `runtime/sessions/by-date/...` 目录结构讨论确认。

### 2. 执行摘要内容

每轮 task 完成后生成 digest，建议包含：

1. `taskId`
2. `frameId`
3. 用户问题摘要
4. 最终回复摘要
5. 调用过的 skill/tool
6. 关键输入输出
7. 产生或修改的业务对象、文件、报告
8. 错误、重试、阻塞项
9. 后续待确认事项
10. `reportRef` / `logRef`

digest 是下一轮 prompt 的主要执行记忆来源。完整 report/log 只在需要深挖时读取。

### 3. Prompt 组装策略

BizWorker root graph 组装 LLM prompt 时，建议按优先级注入：

1. system prompt、account context、skills 列表
2. 当前用户消息
3. Worker 侧最近可见对话历史
4. Worker 侧最近执行摘要
5. active frame / awaiting user / awaiting approval 状态摘要
6. 当前请求显式 context

`recentConversation` 可以短期作为兼容输入保留，但目标状态下不应依赖 Java 从 `SessionMessageRepository` 读取历史消息来保证 Worker 连续性。

### 4. 原始过程按需检索

不建议把工具调用、LLM 中间消息、完整日志每轮都注入模型。更合适的方式是：

1. digest 中提供 report/log/frame 引用。
2. 当用户追问“刚才为什么这样做”、“继续上一步”、“查看执行过程”时，Worker 再读取对应 report/log。
3. 对 active frame 保持当前恢复逻辑，未闭环任务优先使用 frame state。

## 非目标

1. 不在本条目中实现具体代码。
2. 不取消 Java/UI 的消息展示缓存。
3. 不要求上游发送完整历史消息。
4. 不默认回放所有工具调用和日志到 LLM。
5. 不改变 `contextId` 作为 Worker 会话定位入口的基础契约。

## 需要继续讨论的问题

1. Worker 侧运行时上下文文件是否需要区分 `current-window`、`compacted-summary`、`active-frame-state`。
2. Worker 写入的 `llm_request` / `llm_response` 级别完整调试日志如何标记为 diagnostic logs，避免被误用为 runtime memory。
3. execution digest 由 root graph 生成，还是由 frame report 生成器统一产出。
4. digest 注入 token 预算：默认最近几轮、最大多少字符。
5. 当 Java `recentConversation` 与 Worker 侧 runtime memory 不一致时，目标状态应以 Worker 侧为准；迁移期如何灰度。
6. 是否需要提供显式接口读取 runtime context、frame report 和 digest，便于 UI 排查。

## 初步验收标准

进入开发后，至少满足：

1. 普通下一轮任务的历史对话上下文不再依赖 Java `SessionMessageRepository`。
2. BizWorker 可仅凭 `contextId` 恢复最近对话摘要和执行摘要。
3. task 完成后会产生可复用的 execution digest。
4. LLM prompt 中包含 bounded memory，不直接注入无限历史日志。
5. active frame 的恢复语义不退化。
6. Java 侧 `recentConversation` 兼容路径有明确降级或废弃策略。
7. 单元测试覆盖 Worker 侧记忆写入、读取和 prompt 注入。
8. 至少有一个跨轮对话测试验证“上一轮执行摘要能被下一轮使用”。

## 代码触点候选

开发前需要重点复核：

1. `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphTaskService.java`
2. `tools/langgraph-biz-worker/src/langgraph_biz_worker/routes/query.py`
3. `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py`
4. `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/file_frame_journal.py`
5. `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/frame_execution_report.py`
6. `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/file_layout.py`

## Progress Tracking

### Development Progress

- status: not-started
- 当前仅完成设计方向落档。
- 后续需先确认上下文所有权、缺失 `contextId` 策略、digest 文件结构，再进入实现。

### Testing Progress

- status: not-started
- 本次未改代码，未运行测试。
- 开发时需要补 Worker 单元测试、跨轮上下文集成测试，以及 Java 不再注入 `recentConversation` 后的兼容测试。

### Experience Progress

- status: N/A
- 原因：本条目为后端/Worker 运行时上下文治理设计，暂不涉及 UI 交互变更。
