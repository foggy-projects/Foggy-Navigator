# 普通消息 Runtime Context 实施计划

## 文档作用

- doc_type: implementation-plan
- intended_for: execution-agent | reviewer
- purpose: 将普通消息 `ContextRuntimeMemory` 设计拆解为可执行开发阶段、代码触点、测试清单和验收顺序

## Version

- `1.1.6-SNAPSHOT`

## 状态

- status: phase-2-5-completed
- date: 2026-05-21
- coding_status: phase-2-5-implemented
- test_status: phase-2-5-passed

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
8. BizWorker API 入口必须按 `contextId` 提供物理排他保护，不能只依赖 prompt 逻辑或上游串行约定。
9. UI transcript rollback / regenerate 不属于 Phase 1-4 默认能力；后续必须通过显式 revision / turnId / fork 契约支持。

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

### Phase 1 入口锁要求

Phase 1 虽然不实现 `pendingUserInputs`，但必须避免同一 `contextId` 同时进入两条 Root loop。

开发要求：

1. 在请求进入 Root graph 前按 `contextId` 建立互斥保护。
2. 锁内完成 Root frame 选择、runtime memory load、running marker 检查和 begin turn 写入。
3. 如果发现同一 `contextId` 已有 running loop，返回 busy / conflict / retryable，不创建新 Root loop。
4. 单进程可先使用进程内 lock；多进程/共享磁盘部署需要文件锁或外部锁服务，不能把上游串行作为长期边界。

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
6. 不支持 UI transcript rollback / regenerate 自动回滚 BizWorker memory。

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
6. `test_same_context_running_loop_returns_busy_before_queue_phase`
   - 同一 `contextId` 已有 running loop 时，不进入第二条 Root loop。
   - 返回明确 busy / conflict / retryable。

Java 测试暂不改断言，只要现有测试继续通过。

### Phase 1 验收

1. 可在测试中证明 BizWorker 不依赖 Java `SessionMessageRepository` 也能维持普通两轮语义连续。
2. `ContextRuntimeMemory` 写入 Root frame，frame report/log 不受影响。
3. 新逻辑对旧 `recentConversation` 调用方兼容。
4. Phase 1 对同一 `contextId` 执行中追加消息有明确临时行为，不会静默并发运行。
5. API 入口具备 `contextId` 级别互斥保护，避免 Root frame / runtime memory 并发写入。

## Phase 2：Skill 与状态投影

### 目标

把 Skill 结果、awaiting-user、中断和不可恢复错误纳入同一套 visible message / control state 规则。

### 开发任务

1. Normal Skill completion：
   - assistant message content 使用 Root 最终回复或 Skill promoted user message。
   - metadata 写入 `skillId`、`skillFrameId`、`reportRef`、`promotedResultDigest`。
2. `AWAITING_USER`：
   - Skill 的用户可见追问写为 assistant visible message。
   - 下一条用户消息仍通过 active focus resume 进入 focus stack 的 deepest leaf frame。
   - nested leaf 进入 `AWAITING_USER` 时，immediate parent 和 Root owner 都必须记录同一个 active focus stack，避免下轮从 `contextId` 恢复时只能定位到 Root `WAITING_CHILD`。
   - child Skill 必须识别用户取消、停止、换题等 escape hatch，并通过 frame 退出/终止工具把 active focus 交还 Parent。
3. Recoverable interruption：
   - 不写 fake assistant message。
   - `pendingTurn` 标记为 interrupted，Root frame 继续维护 focus stack control state。
   - 用户中止、TIMEOUT、ERROR 后的下一条普通用户消息默认直达 deepest recoverable leaf；Root LLM 只在没有可恢复 leaf 或 leaf 主动交还 Parent 时接管。
4. Non-recoverable visible error：
   - 写入 assistant error projection。
   - metadata 写 compact `errorCategory`、`reportRef`，不泄露敏感细节。

### Phase 2 测试

1. Skill 完成后下一轮 prompt 是 `U1 -> A1 -> U2`。
2. prompt 不包含 raw `tool_call` / `tool_result`。
3. `AWAITING_USER` 后 `U2` 直接恢复 child frame。
4. `AWAITING_USER` 后用户明确取消/换题时，child 通过退出工具交还 Parent，Parent active focus 被清理。
5. recoverable interruption 不产生普通 assistant message。
6. nested recoverable interruption 后，下一条普通用户消息直达 deepest leaf，而不是先进入 Root LLM。
7. nested leaf `AWAITING_USER` 会冒泡到 Root owner，且 LLM submission log 只记录 leaf frame 的真实提交。
8. non-recoverable visible error 进入 assistant projection。

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
   - queue 写入必须在 `contextId` 互斥锁内完成，避免多个请求同时覆盖队列。
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
2. commit turn 后先做预算检查；只有超过窗口/字符/token 阈值时才执行 compaction。
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
5. 未超过预算时不调用 LLM summarizer，只追加 visible turn。

## Rollback / Regenerate 非目标与后续契约

Phase 1-4 默认不处理上游 UI transcript 的删除、重新生成或回滚。

如果上游要支持这类能力，必须另起契约设计：

1. 请求携带 `baseRuntimeRevision` / `turnId`，让 BizWorker 判断是否基于当前 runtime memory。
2. 支持 `forkContextId` 或 `rollbackToTurnId`，避免静默覆盖当前 `ContextRuntimeMemory`。
3. 明确 Root frame checkpoint / journal replay 是否包含 runtime memory 回滚。
4. 增加冲突处理：当上游 transcript 旧于 BizWorker revision 时，拒绝、fork 或进入显式 repair，不允许自动覆盖。

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
| 同一 `contextId` 并发请求覆盖 Root frame | API 入口必须加 `contextId` 互斥锁；Phase 1 busy，Phase 3 queue |
| `AWAITING_USER` 把用户困在 child frame | child Skill contract 必须支持取消/换题 escape hatch，并清理 active focus |
| UI regenerate 导致 transcript 与 memory 不一致 | Phase 1-4 声明为非目标，后续通过 revision / turnId / fork 契约处理 |

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

- status: phase-1-completed
- 2026-05-21: 已完成 `ContextRuntimeMemory` Phase 1 最小闭环。
- 已新增 `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/context_memory.py`，封装 Root frame `private_working_state.runtime_context_memory` 的 load / bootstrap / begin / commit / prompt view / abandon。
- Root `run_skill(...)` 已改为优先使用 BizWorker-owned `_runtime_visible_conversation`；Java `recentConversation` 仅在 memory 为空且 revision 为 0 时 bootstrap 一次。
- `llm_message_builder.py` 已集中组装初始 LLM messages；旧 `_visible_recent_conversation` 保留为兼容 fallback，并以独立 `user` / `assistant` role messages 注入。
- Root turn 成功完成、`AWAITING_USER` 可见追问、interruption / approval / error abandon 均已接入 runtime memory 的 Phase 1 提交或清理路径。
- API `/api/v1/query` 已增加进程内 `contextId` 互斥锁；Phase 1 中同会话并发请求返回 `CONTEXT_RUNTIME_BUSY` / `retryable=true`，不静默启动第二条 Root loop。
- 附带闭环 [05-business-function-upstream-ref-error-feedback-bug.md](./05-business-function-upstream-ref-error-feedback-bug.md)：BusinessFunction 配置类 gateway 错误会被标记为 non-recoverable configuration error，OpenAPI readiness 会前置校验可见函数 adapter `upstream_ref`。
- 2026-05-21: 已完成 Phase 2 Skill / error projection。正常 child Skill 结果以 `skill_result` metadata 投影到 runtime visible conversation；BusinessFunction non-recoverable configuration error 会提交可见错误投影，不再折叠为 max iterations。
- 2026-05-21: 已完成 Phase 3 `pendingUserInputs` 队列与 checkpoint。执行锁被占用时，仍处于 RUNNING 的同 `contextId` 请求进入队列；LLM loop 在 model 前、tool 后等 checkpoint 消费并插入当前 active frame。若执行流已 closing / memory 非 RUNNING，则返回 retryable busy，避免消息入队后无人消费。
- 2026-05-21: 已完成 Phase 4 lazy head-tail compaction。未超预算不调用 summarizer；超预算后保留 pinned head、compacted summary、tail recent messages；summarizer 失败时使用 deterministic fallback，并对 token / bearer / secret 等敏感片段脱敏。
- 2026-05-21: 已完成 Phase 5 Java `recentConversation` 退场。`LanggraphTaskService` 默认不再读取 `SessionMessageRepository` 注入 `recentConversation`；仅保留 `foggy.navigator.langgraph.worker.include-recent-conversation=true` 兼容开关。
- 2026-05-21: 已补充 session root 定位索引。标准 `contextId` session 目录写入 `session.json`，记录 canonical `rootFrameId` 和小型 `rootFrameHistory`；新任务恢复 Root frame 时优先直达 `frames/<rootFrameId>.json`，历史多 root supersede 也按索引直读，索引缺失或失效时才扫描当前 session 并重建。
- 2026-05-21: 已补充 LLM submission 复盘文件。开启 `BIZ_WORKER_LLM_SUBMISSION_LOG_ENABLED=true` 后，每次真实 ChatModel 调用保存一个独立 JSON 到 `logs/llm-submissions/000001_...json`，默认最多保留 100 个文件。
- 2026-05-21: 已完成 LLM submission messages 契约收口。运行时治理上下文、业务上下文、active plan、`AWAITING_USER` 和 recoverable interruption 进入 system message；runtime-visible conversation 以独立 `user` / `assistant` role messages 注入；human message 以当前用户原文为主，显式 `current_time` 只作为当前请求尾部信息追加。Root prompt、通用工具治理说明和附件上下文已中文化，`allowed_skills` 会从 registry 补齐 name / description 并渲染为 Markdown；初始 messages 数组由 `llm_message_builder.py` 统一组装。详见 [09-llm-submission-message-contract.md](./09-llm-submission-message-contract.md)。
- 2026-05-21: 已新增 runtime message event JSONL 写入和读取恢复入口。`llm_skill_agent` 在初始 messages、assistant response、assistant tool_call、tool_result 和 checkpoint 处写入 `logs/runtime-message-events/<taskId>_<frameId>.jsonl`；`AWAITING_USER` 与 recoverable child interruption 读取恢复复用同一事件源，只选择不同恢复入口。工具执行完成后立即写 `after_tool_call` checkpoint，避免恢复时重复已完成的副作用工具调用。
- 2026-05-21: 已完成 nested focus completion unwind。恢复 deepest leaf 后，如果 leaf 正常完成，BizWorker 会逐层 close/promote/resume parent；parent LLM submission 的 system context 会包含“刚完成的子技能提升结果”，直到回到 Root 后直接返回 promoted result 或执行 Root synthesis。
- 2026-05-21: 已补充 scripted E2E log parity 断言。普通多轮、BusinessFunction tool protocol、`AWAITING_USER` child resume、nested completion unwind 现在会同时校验 `llm-submissions` 与 `runtime-message-events`。详见 [10-runtime-context-e2e-matrix-and-log-parity.md](./10-runtime-context-e2e-matrix-and-log-parity.md)。
- 2026-05-21: scripted E2E log parity 已扩展到 nested recoverable leaf direct resume 与 interrupted child resume，覆盖 deepest leaf 直达恢复不触发 Root LLM、child 恢复完成后 Root synthesis 的 event JSONL 对账。
- 2026-05-21: 已实现 child-only `handoff_to_parent`。`AWAITING_USER` 或 recoverable leaf 直达恢复时，用户取消/停止/换题可由当前 child LLM 受控退出；简单取消可 direct result 返回，需父级重新判断时可设置 `requires_parent_synthesis=true`。
- 2026-05-21: 已补齐 child handoff 后 Root 同 turn synthesis 的 scripted E2E 覆盖。mock LLM 支持同一 cursor 按注册顺序返回多次响应，用于模拟 child 与 Root 共享同一用户消息但产生两次真实 ChatModel 调用的恢复链路。
- 2026-05-21: 已补齐 recoverable ERROR/TIMEOUT 后 deepest leaf handoff 的 scripted E2E 覆盖。`model_timeout` / `model_error` 中断后，下一条用户消息默认直达 leaf；leaf 可调用 `handoff_to_parent`，parent 在同一 turn 接管并提交最终结果。

### Testing

- status: passed
- `cd tools/langgraph-biz-worker; $env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest tests/test_context_memory.py tests/test_root_graph.py tests/test_llm_skill_agent.py tests/test_query.py -q`
  - result: `94 passed`
- `cd tools/langgraph-biz-worker; $env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest tests -q`
  - result: `569 passed, 6 skipped`
- `cd tools/langgraph-biz-worker; $env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest tests/test_e2e_scripted_tool_call_streaming.py -q`
  - result: `23 passed`
- `mvn -pl addons/langgraph-biz-worker -am -Dtest=InvokeBusinessFunctionToolTest "-Dsurefire.failIfNoSpecifiedTests=false" test`
  - result: `10 tests, 0 failures, 0 errors`
- `cd tools/langgraph-biz-worker; .\.venv\Scripts\python.exe -m pytest tests/test_file_frame_journal.py tests/test_root_graph.py tests/test_llm_submission_log.py tests/test_config.py -q`
  - result: `60 passed`
- `mvn -pl addons/claude-worker-agent -am -Dtest=OpenApiAgentReadinessServiceTest "-Dsurefire.failIfNoSpecifiedTests=false" test`
  - result: `11 tests, 0 failures, 0 errors`
- `mvn -pl addons/langgraph-biz-worker -am "-Dtest=LanggraphTaskServiceTest,BusinessAgentLanggraphLaunchE2ETest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
  - result: `23 tests, 0 failures, 0 errors`
- `mvn -pl addons/langgraph-biz-worker -am test`
  - result: `BUILD SUCCESS`，surefire report 汇总 `1017 tests, 0 failures, 0 errors`
- `cd tools/langgraph-biz-worker; .\.venv\Scripts\python.exe -m pytest tests/test_llm_message_builder.py tests/test_llm_skill_agent.py tests/test_e2e_scripted_tool_call_streaming.py tests/test_llm_submission_log.py`
  - result: `74 passed`
- `cd tools/langgraph-biz-worker; .\.venv\Scripts\python.exe -m pytest tests/test_config.py tests/test_llm_skill_agent.py tests/test_llm_message_builder.py tests/test_llm_submission_log.py`
  - result: `69 passed`
- `cd tools/langgraph-biz-worker; .\.venv\Scripts\python.exe -m pytest`
  - result: `597 passed, 6 skipped, 11 warnings`
- `cd tools/langgraph-biz-worker; .\.venv\Scripts\python.exe -m pytest tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_nested_focus_completion_unwinds_to_parent_result -q`
  - result: `1 passed`
- `cd tools/langgraph-biz-worker; .\.venv\Scripts\python.exe -m pytest tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_llm_submission_log_matches_root_recent_conversation tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_llm_submission_log_captures_awaiting_child_resume_protocol tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_llm_submission_log_captures_business_function_tool_protocol tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_nested_focus_completion_unwinds_to_parent_result -q`
  - result: `4 passed`
- `cd tools/langgraph-biz-worker; .\.venv\Scripts\python.exe -m pytest tests/test_llm_skill_agent.py::test_llm_agent_child_can_handoff_to_parent_without_business_output_validation tests/test_llm_skill_agent.py::test_llm_agent_persistent_root_exposes_shelve_interrupted_frame_tool -q`
  - result: `2 passed`
- `cd tools/langgraph-biz-worker; .\.venv\Scripts\python.exe -m pytest tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_awaiting_child_can_handoff_cancel_to_parent -q`
  - result: `1 passed`
- `cd tools/langgraph-biz-worker; .\.venv\Scripts\python.exe -m pytest tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_root_skill_real_smoke_fixture -q`
  - result: `1 passed`
- `cd tools/mock-llm-service; $env:PYTHONPATH='src'; ..\langgraph-biz-worker\.venv\Scripts\python.exe -m pytest tests/test_openai_api.py -q`
  - result: `16 passed, 1 warning`
- `cd tools/langgraph-biz-worker; .\.venv\Scripts\python.exe -m pytest tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_child_handoff_can_request_root_synthesis -q`
  - result: `1 passed, 3 warnings`
- `cd tools/langgraph-biz-worker; .\.venv\Scripts\python.exe -m pytest tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_nested_recoverable_leaf_can_handoff_to_parent_after_error -q`
  - result: `2 passed, 3 warnings`
- `cd tools/langgraph-biz-worker; .\.venv\Scripts\python.exe -m pytest tests/test_e2e_scripted_tool_call_streaming.py -q`
  - result: `27 passed, 3 warnings`
- `cd tools/langgraph-biz-worker; .\.venv\Scripts\python.exe -m pytest -q`
  - result: `602 passed, 6 skipped, 11 warnings`

### Experience

- status: verified-by-tests
- 已通过 scripted E2E 验证第二轮 Root LLM submission 使用独立 `user` / `assistant` role messages 注入上一轮 BizWorker memory 中的语义消息；system 不再折叠展示 `本回合前的运行时可见对话:`，当前 human message 以当前用户原文为主。
- 已通过单元/边界测试验证 Skill 投影、non-recoverable error projection、执行中追加消息入队、checkpoint 插入、idle / closing 阶段 retryable busy、lazy compaction、Java recentConversation 默认退场。
- 已通过 scripted E2E 验证 deepest nested leaf 从 recoverable interruption 恢复后，leaf 完成结果会继续交给 parent LLM，parent 再完成并向 Root 提升结果；对应 `llm-submissions` 可复盘 leaf 与 parent 两次真实提交。
- 已通过 scripted E2E 验证关键 runtime context 场景的 `llm-submissions` 与 `runtime-message-events` 对账，包括普通多轮、业务函数工具协议、等待用户输入恢复和 nested completion unwind。
- 已通过 scripted E2E 验证 `AWAITING_USER` child 恢复后，用户取消会调用 `handoff_to_parent`，不重新打开同一业务 skill，且 Root active focus 被清理。
- 已通过 scripted E2E 验证 child 调用 `handoff_to_parent(requires_parent_synthesis=true)` 后，Root 会在同一用户 turn 继续生成最终答复；`llm-submissions` 分别保存 child 与 Root 的真实请求体，runtime message events 也可分别对账。
- 已通过 scripted E2E 验证 `model_timeout` / `model_error` recoverable leaf 恢复后可以调用 `handoff_to_parent` 交还 parent；parent 的 submission 能看到 leaf promoted handoff summary，并在同一 turn 生成最终结果。
- 已通过 captured real smoke replay 验证首轮 root/child 工具链、恢复继续、换题请求的 7 次真实 ChatModel 调用均保存 `llm-submissions`，并能与 root/child runtime message events 对账。
- 真实前端长会话与 TMS 工单链路仍建议在修复上游 `upstream_ref` 后做一次联调验收。

## Implementation Quality / Acceptance

- quality_record: [quality/runtime-context-phase1-implementation-quality.md](./quality/runtime-context-phase1-implementation-quality.md)
- acceptance_record: [acceptance/runtime-context-phase1-acceptance.md](./acceptance/runtime-context-phase1-acceptance.md)
- quality_record_phase2_5: [quality/runtime-context-phase2-5-implementation-quality.md](./quality/runtime-context-phase2-5-implementation-quality.md)
- acceptance_record_phase2_5: [acceptance/runtime-context-phase2-5-acceptance.md](./acceptance/runtime-context-phase2-5-acceptance.md)
