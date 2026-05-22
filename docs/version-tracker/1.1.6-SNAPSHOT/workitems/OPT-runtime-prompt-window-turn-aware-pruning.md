# OPT: Runtime Prompt Window Turn-Aware Pruning

## 文档作用

- doc_type: workitem
- intended_for: execution-agent | reviewer
- purpose: 记录真实会话暴露出的 prompt 裁剪边界问题，作为后续消息裁剪、压缩策略和工具结果预算参数设计的跟踪项

版本：`1.1.6-SNAPSHOT`
状态：第一版已实现，待真实会话验收
类型：runtime context governance / prompt pruning

## 背景

真实上游 smoke 会话：

```text
tools/langgraph-biz-worker/data/runtime/sessions/by-date/2026/05/22/fa/bctx_20260522_fa_faaf9f248b094fa7b8ecf3fedeb69ab3
```

`logs/llm-submissions/000011_...json` 显示当前实现已经正确保存 Root runtime memory，并在后续 prompt 中恢复 Root-visible tool protocol。但由于 `maxPromptMessages=12` 的 prompt 组装上限按 raw message tail 切片，在未触发 `compactedSummary` / pinned head 的情况下，prompt 可能从一条孤立 assistant 语义消息开始。

这不是 provider tool protocol 非法问题，但会让 LLM 看到的近期上下文变成语义半轮，不利于稳定推理，也不符合“head-tail + summary”的设计意图。

## 当前实现基线

`ContextRuntimeMemory.build_prompt_view()` 当前行为：

1. 如果已有 `compactedSummary` 或 `pinned_head_messages`，使用 `pinned head + summary + visible messages`。
2. 否则使用 `messages[-maxPromptMessages:]`。
3. 最后通过 `_ensure_valid_tool_protocol_window(...)` 清理孤立 tool result。

该逻辑能保护 tool protocol，但没有保护普通 user / assistant 语义 turn。

当前默认参数：

```json
{
  "maxVisibleMessages": 24,
  "maxPromptMessages": 12,
  "maxMessageChars": 1200,
  "maxVisibleChars": 12000,
  "headTurnCount": 2,
  "tailTurnCount": 6,
  "maxSummaryChars": 2400
}
```

## 目标

1. prompt 裁剪必须继续保证 provider tool protocol 合法。
2. prompt tail 应尽量从 `user` 或 summary message 开始，避免孤立 assistant 半轮。
3. 当近期 protocol 太大时，应优先触发压缩摘要，而不是盲目按消息条数截断。
4. 单工具调用结果需要独立预算，避免大型 tool result 挤占普通对话窗口。

## 第一版实现

代码落点：

1. `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/context_memory.py`
   - `ContextRuntimeMemory.build_prompt_view()` 从 raw tail slicing 改为 turn-aware tail。
   - prompt tail 按 `user` / compacted summary 起点分组，优先保留完整近期语义 turn。
   - 保留 `_ensure_valid_tool_protocol_window(...)` 作为最终 provider protocol 防线。
   - 新增边界参数：
     - `maxPromptChars`
     - `maxToolResultChars`
     - `maxToolCallArgsChars`
   - `tool` result 使用 `maxToolResultChars`，不再被普通 `maxMessageChars` 过早截断。
   - assistant tool call args 超过 `maxToolCallArgsChars` 时投影为 `_truncated / _original_chars / preview`。
2. `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_agent.py`
   - 保存 Root-visible protocol 到 runtime memory 时，读取 Root memory limits。
   - protocol 序列化时使用独立 tool result / tool args 预算。
3. `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py`
   - 从 `llm_config.runtimeBudgetPresetKey` / 自动 preset 解析模型预算。
   - 将模型预算映射到 Root runtime memory：
     - `max_input_tokens -> maxPromptChars ~= tokens * 4`
     - `auto_compact_input_token_threshold -> maxVisibleChars ~= tokens * 4`
     - `max_single_tool_result_chars -> maxToolResultChars`
   - Root 回合 commit 时构建 lazy compaction summarizer；只有 `ContextRuntimeMemory` 判定超过 `maxVisibleMessages` 或 `maxVisibleChars` 时才调用。
4. `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/context_compaction_summarizer.py`
   - 新增 LLM-backed 运行时摘要器。
   - 使用中文 system prompt，要求输出固定 JSON object。
   - 摘要字段与 runtime memory 的 `compactedSummary` schema 对齐：`durableUserIntent`、`decisionsAndConstraints`、`businessEntities`、`completedWork`、`openQuestions`、`pendingActions`、`errorsAndRecovery`。
   - 失败、超时或返回非 JSON 时，由 `ContextRuntimeMemory` 自动降级到确定性 fallback summary，不影响当前回合提交。
   - LLM submission 日志中以 `runtime-memory.compaction` / `runtime_memory.compaction` 标识压缩调用。
5. `tools/langgraph-biz-worker/src/langgraph_biz_worker/config.py`
   - 新增 `BIZ_WORKER_RUNTIME_COMPACTION_LLM_ENABLED`。
   - 新增 `BIZ_WORKER_RUNTIME_COMPACTION_REQUEST_TIMEOUT_SECONDS`。
   - 新增 `BIZ_WORKER_RUNTIME_COMPACTION_EXECUTION_DEADLINE_SECONDS`。

当前仍是字符近似预算，后续如接入 tokenizer，可把 `maxPromptChars` / `maxVisibleChars` 替换为真实 token budget。

## 候选设计方向

1. `build_prompt_view()` 从 raw tail slicing 改为 turn-aware slicing。
   - 先按 provider protocol 分组。
   - 再按 semantic turn 选择 tail。
   - 最后再执行 tool protocol legality check。
2. 当没有 summary 且 raw tail 会切断语义 turn 时：
   - 如果完整 tail 仍在预算内，扩大到 turn 起点。
   - 如果超预算，触发 lazy compaction，生成 summary + 完整 tail。
3. 为 tool result 增加独立边界参数：
   - `maxToolResultChars`
   - `maxToolResultItems`
   - `maxToolCallArgsChars`
   - `maxToolResultsPerTurn`
4. 大工具结果默认投影为 digest / refs / selected fields，完整内容保留在 frame report、runtime event log 或 artifact evidence 中。

第一版已经完成方向 1、方向 2、方向 3 的核心实现：prompt tail 已按语义 turn 裁剪，commit 后 lazy compaction 已接入 LLM summarizer，并保留确定性 fallback。方向 4 的业务工具级 digest/ref 仍需后续按具体工具补 projection。

## 验收建议

1. 已新增单元测试：`maxPromptMessages` 裁剪后 prompt 不以孤立 assistant 语义消息开头。
2. 已新增单元测试：裁剪后仍保留匹配的 `assistant.tool_calls -> tool` protocol，或一起裁掉。
3. 已新增单元测试：单条 tool result 和 tool call args 使用独立预算。
4. 已新增单元测试：Root commit 超过 visible 阈值时调用 LLM summarizer，并写入 `summaryQuality=llm`。
5. 待补 scripted E2E：多轮 Root tool call 后，`llm-submissions` 中的 prompt tail 从 user/summary 开始。
6. 待补业务工具 projection 验收：大型 tool result 在具体工具层投影为 digest / refs，完整内容仍可在 evidence 中查到。

## Progress Tracking

Development:

- [x] Runtime memory prompt tail 改为 turn-aware。
- [x] Tool result / tool call args 独立裁剪参数落地。
- [x] 模型 runtime budget preset 接入 Root memory limits。
- [x] Root commit lazy compaction 接入 LLM summarizer，并保留 fallback。
- [ ] 真实 LLM smoke 后复核 `llm-submissions` 是否符合预期。
- [ ] 后续设计真实 tokenizer 与更精确 token 预算。

Testing:

- [x] `pytest tests/test_context_memory.py tests/test_llm_message_builder.py tests/test_model_runtime_budget.py tests/test_llm_skill_router.py -q`
- [x] `pytest tests/test_root_graph.py::test_runtime_memory_commit_uses_llm_compaction_summarizer tests/test_context_memory.py -q`
- [x] `ruff check` 覆盖本次 Python 改动文件。
- [ ] 全量测试待本轮后续阶段统一跑。

Experience:

- N/A。该 workitem 为 BizWorker runtime prompt 组装策略，无直接 UI 改动；体验验收通过真实会话与 `llm-submissions` 复盘完成。

## 非目标

1. 不把上游完整 UI transcript 重新拉回 BizWorker。
2. 不要求 frame/report/log 也跟随 runtime prompt 裁剪；它们仍作为完整 execution evidence 保留。
3. 不在本 workitem 中决定最终默认参数，参数需要结合真实 token 成本、模型上下文长度和 TMS 业务工具输出规模单独确认。
