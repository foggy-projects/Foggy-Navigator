# 普通消息 Runtime Context 实施计划

## 文档作用

- doc_type: implementation-plan
- intended_for: execution-agent | reviewer
- purpose: 将普通消息 `ContextRuntimeMemory` 设计拆解为可执行开发阶段、代码触点、测试清单和验收顺序

## Version

- `1.1.6-SNAPSHOT`

## 状态

- status: planned
- date: 2026-05-21
- coding_status: not-started
- test_status: not-run

## 关联设计

本实施计划基于：

1. [01-biz-worker-context-owned-runtime-memory.md](./01-biz-worker-context-owned-runtime-memory.md)
2. [04-runtime-visible-conversation-and-recovery-design.md](./04-runtime-visible-conversation-and-recovery-design.md)
3. [06-normal-turn-runtime-context-design.md](./06-normal-turn-runtime-context-design.md)

## 已确认约束

1. 同一 `contextId` 不允许真正并发 LLM loop。
2. 目标状态下，用户执行中继续发送消息时，进入 BizWorker pending queue，只能在当前 loop 安全 checkpoint 插入。
3. 第一阶段存储以 Root frame 为事实源，不立即引入独立 runtime memory 文件。
4. 必须通过 `ContextRuntimeMemory` 接口封装 Root frame 内部读写，prompt 组装逻辑不直接读写 `private_working_state`。
5. 压缩目标采用 head-tail + LLM summarizer，并提供 deterministic fallback。
6. Java `recentConversation` 只作为空 memory bootstrap，不允许每轮覆盖 BizWorker runtime memory。
7. Phase 1 尚未实现 pending queue 前，同一 `contextId` 的执行中追加消息只能由上游串行保护，或由 BizWorker 返回明确 busy / conflict / retryable 状态，不能启动第二条 LLM loop。

## 总体阶段

```text
Phase 1: BizWorker-owned 普通语义窗口最小闭环
Phase 2: Skill / awaiting-user / interruption / error 投影
Phase 3: pendingUserInputs 队列与 loop checkpoint
Phase 4: head-tail + LLM summarizer 压缩
Phase 5: Java recentConversation 退场
```

建议按阶段独立提交。Phase 1 必须先形成可运行闭环，后续阶段在此基础上补治理能力。

## Phase 1：最小闭环

### 目标

在不依赖 Java `recentConversation` 的情况下，BizWorker 能完成普通两轮对话连续性：

```text
Turn 1 prompt: U1
Turn 1 commit: U1 -> A1
Turn 2 prompt: U1 -> A1 -> U2
```

其中 `U1 -> A1` 来自 BizWorker `ContextRuntimeMemory`，不是 Java `SessionMessageRepository`。

### 开发任务

1. 新增 `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/context_memory.py`。
2. 定义 `ContextRuntimeMemory` 数据结构和 helper：
   - `load_from_root_frame(frame)`
   - `bootstrap_from_external_recent_conversation(...)`
   - `begin_turn(task_id, root_frame_id, user_message, ...)`
   - `commit_turn(task_id, root_frame_id, assistant_message, metadata, ...)`
   - `build_prompt_view()`
   - `save_to_root_frame(frame)`
3. Root frame `private_working_state` 中新增事实存储字段：

```text
runtime_context_memory
```

4. `root_graph.run_skill(...)` 在调用 Root LLM 前：
   - 加载 Root frame memory。
   - 如果 memory revision 为 0 且 Java `recentConversation` 存在，则 bootstrap 一次。
   - 将 prompt view 写入 `runtime_context["_runtime_visible_conversation"]`。
   - 不再优先使用 `_visible_recent_conversation`。
5. `llm_agent_prompts.py`：
   - 新增 `_build_runtime_visible_conversation_prompt(...)`。
   - 保留 `_build_visible_recent_conversation_prompt(...)` 作为迁移兼容，但优先级低于 runtime memory。
6. Root persistent turn 成功提交时：
   - 从当前 user prompt 生成 user visible message。
   - 通过 `assistantVisibleContent` 规则生成 assistant visible message。
   - 调用 `ContextRuntimeMemory.commit_turn(...)`。
7. frame report 继续完整保留，不改变 report/log/journal 结构。

### `assistantVisibleContent` 提取规则

Phase 1 需要先固定 assistant 可见内容来源，避免 runtime memory 与前端用户可见回复出现语义分裂。

提取优先级：

1. Root / Skill 明确返回的 user-facing final message。
2. structured output 中声明为用户可见的 `message` / `userMessage` / `finalResponse` 等字段。
3. `submit_skill_result.summary` 或 promoted result 的 compact user-facing summary。
4. 不可恢复错误时的标准化 user-visible error message。

禁止直接写入 `visibleMessages`：

1. raw tool call / tool result。
2. Skill private messages。
3. report/log/journal 原文。
4. 仅供调试的内部 summary。

### Phase 1 暂不做

1. 不做 pendingUserInputs 队列。
2. 不做 LLM summarizer 压缩。
3. 不移除 Java `recentConversation` 注入。
4. 不改 Skill child context 规则。
5. 不迁移到独立 `runtime-memory.json`。

### Phase 1 临时并发契约

Phase 1 只解决普通多轮语义连续性，不实现执行中追加消息入队。

因此：

1. 如果现有 Java / 上游已经按 `contextId` 串行提交，Phase 1 可继续依赖该外层保护。
2. 如果请求进入 BizWorker 时能检测到同一 `contextId` 已有 running loop，必须返回明确 busy / conflict / retryable 状态。
3. 不允许为了兼容而在 Phase 1 静默创建第二个 Root loop。
4. Phase 3 完成后，该临时行为切换为 queued / accepted，并由 loop checkpoint 消费。

### Phase 1 测试

Python 自动化测试：

1. `test_context_memory_begin_commit_turn`
   - begin 后有 `pendingTurn`。
   - commit 后生成 `[user, assistant]`，清空 `pendingTurn`，revision +1。
2. `test_root_prompt_uses_bizworker_memory_without_recent_conversation`
   - 第二轮没有 Java `recentConversation`，prompt 仍包含第一轮 `U1 -> A1`。
3. `test_recent_conversation_bootstrap_only_when_memory_empty`
   - memory 为空时导入一次 Java recent conversation。
   - memory revision > 0 时忽略 Java recent conversation。
4. `test_runtime_memory_does_not_include_raw_tool_messages`
   - 即使 frame/tool log 有 tool trace，prompt view 只包含语义 user/assistant。
5. `test_assistant_visible_content_prefers_user_facing_output`
   - assistant visible message 优先取用户可见 final message。
   - 不把 raw tool result 或内部 summary 写入 prompt view。

Java 测试暂不改断言，只要现有测试继续通过。

### Phase 1 验收

1. 可在测试中证明 BizWorker 不依赖 Java `SessionMessageRepository` 也能维持普通两轮语义连续。
2. `ContextRuntimeMemory` 写入 Root frame，frame report/log 不受影响。
3. 新逻辑对旧 `recentConversation` 调用方兼容。
4. Phase 1 对同一 `contextId` 执行中追加消息有明确临时行为，不会静默并发运行。

## Phase 2：Skill 与状态投影

### 目标

把 Skill 结果、awaiting-user、中断和不可恢复错误纳入同一套 visible message / control state 规则。

### 开发任务

1. Normal Skill completion：
   - assistant message content 使用 Root 最终回复或 Skill promoted user message。
   - metadata 写入 `skillId`、`skillFrameId`、`reportRef`、`promotedResultDigest`。
2. `AWAITING_USER`：
   - Skill 的用户可见追问写为 assistant visible message。
   - 下一条用户消息仍通过 active focus resume 进入 child frame。
3. Recoverable interruption：
   - 不写 fake assistant message。
   - `pendingTurn` 标记为 interrupted，Root frame 继续维护 `_recoverable_interruption`。
4. Non-recoverable visible error：
   - 写入 assistant error projection。
   - metadata 写 compact `errorCategory`、`reportRef`，不泄露敏感细节。

### Phase 2 测试

1. Skill 完成后下一轮 prompt 是 `U1 -> A1 -> U2`。
2. prompt 不包含 raw `tool_call` / `tool_result`。
3. `AWAITING_USER` 后 `U2` 直接恢复 child frame。
4. recoverable interruption 不产生普通 assistant message。
5. non-recoverable visible error 进入 assistant projection。

## Phase 3：pendingUserInputs 队列与 checkpoint

### 目标

同一 `contextId` 运行中收到新消息时，不启动并发 LLM loop，而是进入队列，并在 loop checkpoint 插入。

### 开发任务

1. 在 `ContextRuntimeMemory` 中新增 `pendingUserInputs`。
2. 增加 context-level running marker：
   - `runningTaskId`
   - `runningFrameId`
   - `loopStatus`
   - `revision`
3. 请求进入时：
   - 如果同一 `contextId` 无 running loop，正常开始。
   - 如果已有 running loop，将消息写入 `pendingUserInputs`，返回 queued / accepted 状态。
4. 在 `llm_skill_agent` loop 中增加 checkpoint：
   - model call 返回后。
   - 同步 tool call 完成后。
   - 下一次 model call 前。
   - frame 暂停或终态时。
5. checkpoint 发现 queued input 后：
   - 按时间顺序插入下一次 prompt。
   - 标记为 in-flight user update。
   - 当前 active frame 决定语义处理。

### Phase 3 测试

1. 同一 `contextId` 第二条消息不启动第二条 loop。
2. queued input 在 checkpoint 后进入下一次 model prompt。
3. BusinessFunction 副作用工具调用执行中不强行插入。
4. `AWAITING_USER` active focus 优先消费 queued input。

## Phase 4：head-tail + LLM summarizer 压缩

### 目标

超过预算时，prompt 保留：

```text
pinned head turns
middle compacted summary
tail recent turns
current user message
```

### 开发任务

1. `ContextRuntimeMemory` 增加配置：
   - `headTurnCount`
   - `tailTurnCount`
   - `maxMessageChars`
   - `maxSummaryChars`
2. commit turn 后执行 compaction。
3. summarizer 输入只包含语义 turns、旧 summary、refs/digests。
4. summarizer 输出结构化 `compactedSummary`：
   - `durableUserIntent`
   - `decisionsAndConstraints`
   - `businessEntities`
   - `completedWork`
   - `openQuestions`
   - `pendingActions`
   - `errorsAndRecovery`
   - `reportRefs`
   - `coveredMessageIds`
5. summarizer 失败时使用 deterministic fallback，标记 `summaryQuality=fallback`。

### Phase 4 测试

1. 超过窗口后 prompt 包含 head + summary + tail。
2. 中间 raw messages 不再进入 prompt。
3. summarizer 失败不阻断 turn commit。
4. summary 不包含 raw tool call、token、signed URL 等敏感信息。

## Phase 5：Java recentConversation 退场

### 目标

Java 不再默认从 `SessionMessageRepository` 读取最近消息注入 `recentConversation`，BizWorker prompt 连续性完全由 runtime memory 保证。

### 开发任务

1. `LanggraphTaskService.buildProviderContext(...)` 默认不写 `recentConversation`。
2. 保留兼容开关或旧字段路径，用于灰度。
3. 更新 Java 测试：
   - 不再断言 provider context 必含 `recentConversation`。
   - 保留兼容模式测试。
4. 更新接口/上游说明：
   - 上游负责 UI transcript。
   - BizWorker 负责 LLM runtime context。

### Phase 5 测试

1. Java 不注入 recent conversation 时，Python E2E 多轮仍连续。
2. 兼容开关打开时，空 memory bootstrap 仍可用。
3. 旧调用方不会破坏 BizWorker memory revision。

## 实施风险与规避

| 风险 | 规避 |
| --- | --- |
| Root frame 内部字段被 prompt 逻辑直接依赖 | 所有访问通过 `ContextRuntimeMemory` |
| Java `recentConversation` 每轮覆盖 Worker memory | revision > 0 时强制忽略 external recent conversation |
| assistant message 来源不稳定 | Phase 1 先使用 Root `submit_skill_result` 的 summary / user-facing output，Phase 2 再补 Skill projection |
| summarizer 失败阻断用户回合 | Phase 4 必须实现 deterministic fallback |
| 伪并发插入破坏副作用工具一致性 | 只在 checkpoint 插入，不在不可中断工具调用中插入 |
| prompt token 失控 | Phase 4 引入 head-tail 和 summary 预算 |

## 建议提交顺序

1. `context_memory.py` + 单元测试。
2. Root prompt assembly 使用 runtime memory prompt view。
3. Root persistent turn commit visible messages。
4. bootstrap Java `recentConversation` only when empty。
5. Phase 1 E2E/集成测试。
6. Phase 2 Skill projection。
7. Phase 3 queue/checkpoint。
8. Phase 4 compaction。
9. Phase 5 Java recentConversation 退场。

## 验收闸门

进入下一阶段前必须满足：

1. 当前阶段新增测试通过。
2. 现有 Python BizWorker 测试通过。
3. 涉及 Java 改动时，相关 Maven 模块测试通过。
4. 如果用户可见行为变化，需要提供前端或端到端会话证据。

## Progress Tracking

### Development

- status: not-started
- 本文件仅完成实施计划落档。

### Testing

- status: not-run
- 本次未改实现代码，未运行测试。

### Experience

- status: pending
- Phase 1 完成后需要验证普通多轮对话；Phase 2/3 完成后需要验证 Skill 续接和执行中追加消息体验。
