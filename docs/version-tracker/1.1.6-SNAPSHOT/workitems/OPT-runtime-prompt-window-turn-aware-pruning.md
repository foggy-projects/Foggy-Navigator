# OPT: Runtime Prompt Window Turn-Aware Pruning

## 文档作用

- doc_type: workitem
- intended_for: execution-agent | reviewer
- purpose: 记录真实会话暴露出的 prompt 裁剪边界问题，作为后续消息裁剪、压缩策略和工具结果预算参数设计的跟踪项

版本：`1.1.6-SNAPSHOT`
状态：待设计确认
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

## 验收建议

1. 新增单元测试：`maxPromptMessages` 裁剪后 prompt 不以孤立 assistant 语义消息开头。
2. 新增单元测试：裁剪后仍保留匹配的 `assistant.tool_calls -> tool` protocol，或一起裁掉。
3. 新增 scripted E2E：多轮 Root tool call 后，`llm-submissions` 中的 prompt tail 从 user/summary 开始。
4. 新增大工具结果测试：单条 tool result 超预算时，prompt 中只保留 digest / refs，完整内容仍可在 evidence 中查到。

## 非目标

1. 不把上游完整 UI transcript 重新拉回 BizWorker。
2. 不要求 frame/report/log 也跟随 runtime prompt 裁剪；它们仍作为完整 execution evidence 保留。
3. 不在本 workitem 中决定最终默认参数，参数需要结合真实 token 成本、模型上下文长度和 TMS 业务工具输出规模单独确认。
