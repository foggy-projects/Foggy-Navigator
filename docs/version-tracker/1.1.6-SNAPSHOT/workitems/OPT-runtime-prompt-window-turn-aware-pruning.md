# OPT: Runtime Prompt Window Turn-Aware Pruning

## 文档作用

- doc_type: workitem
- intended_for: execution-agent | reviewer
- purpose: 记录真实会话暴露出的 prompt 裁剪边界问题，作为后续消息裁剪、压缩策略和工具结果预算参数设计的跟踪项

版本：`1.1.6-SNAPSHOT`
状态：第四版配置闭环已落地；待补 runtime warning 与真实会话验收
类型：runtime context governance / prompt pruning

## 背景

真实上游 smoke 会话：

```text
tools/langgraph-biz-worker/data/runtime/sessions/by-date/2026/05/22/fa/bctx_20260522_fa_faaf9f248b094fa7b8ecf3fedeb69ab3
```

`logs/llm-submissions/000011_...json` 显示当前实现已经正确保存 Root runtime memory，并在后续 prompt 中恢复 Root-visible tool protocol。但由于 `maxPromptMessages=12` 的 prompt 组装上限按 raw message tail 切片，在未触发 `compactedSummary` / pinned head 的情况下，prompt 可能从一条孤立 assistant 语义消息开始。

这不是 provider tool protocol 非法问题，但会让 LLM 看到的近期上下文变成语义半轮，不利于稳定推理，也不符合“head-tail + summary”的设计意图。

## 当前问题复盘

真实上游会话：

```text
tools/langgraph-biz-worker/data/runtime/sessions/by-date/2026/05/22/4b/bctx_20260522_4b_4b0f4945823f4bcea7c1657b99010dcc
```

`logs/llm-submissions/000011_...json` 显示 `runtime_context_memory.visibleMessages` 仍保存从 `hi` 开始的完整 Root-visible protocol，但 `build_prompt_view()` 由于历史保存的 `maxPromptMessages=12`，只投影了最近 12 条 provider messages：

```text
system -> U3/tool... -> U4/agent... -> U5
```

这会在未达到 128k 模型预算、也未触发 compaction 的情况下过早丢弃前两轮 `hi` 和 `随便调个工具`。该行为不符合“模型预算优先，未超预算前尽量保留完整 runtime window；超预算后再 summary + tail”的目标。

## 当前实现基线

第一版 `ContextRuntimeMemory.build_prompt_view()` 行为：

1. 如果已有 `compactedSummary` 或 `pinned_head_messages`，使用 `pinned head + summary + visible messages`。
2. 否则使用 `messages[-maxPromptMessages:]`。
3. 最后通过 `_ensure_valid_tool_protocol_window(...)` 清理孤立 tool result。

该逻辑能保护 tool protocol，但没有保护普通 user / assistant 语义 turn。

第一版默认参数：

```json
{
  "maxVisibleMessages": 24,
  "maxPromptMessages": 12,
  "maxMessageChars": 1200,
  "maxVisibleChars": 12000,
  "headTurnCount": 2,
  "tailTurnCount": 8,
  "maxSummaryChars": 4000
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
   - `tool` result 在 runtime memory 中保留 raw content，不再被普通 `maxMessageChars` 过早截断。
   - `maxToolResultChars` 用作 prompt projection 阈值，而不是运行时存储截断阈值。
   - assistant tool call args 超过 `maxToolCallArgsChars` 时投影为 `_truncated / _original_chars / preview`。
2. `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_agent.py`
   - 保存 Root-visible protocol 到 runtime memory 时，读取 Root memory limits。
   - protocol 序列化时保留 raw tool result，并使用独立 tool args 预算。
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
   - `projectHistoricalToolResults`
   - `rawToolResultTailTurnCount`
   - `maxToolResultItems`
   - `maxToolCallArgsChars`
   - `maxToolResultsPerTurn`
4. 大工具结果默认投影为 digest / refs / selected fields，完整内容保留在 runtime memory、frame report、runtime event log 或 artifact evidence 中。
   - `projectHistoricalToolResults` 默认打开；特殊排障或超大上下文模型可通过模型 runtime budget override 关闭。
   - 投影只作用于最近 N 个语义 turn 之外的历史 tool result。
   - 最近 N 个语义 turn 内的大 tool result 保持 raw content，以便 LLM 对最新证据做精确追问、修正或续跑。
   - N 指用户语义 turn / 业务任务回合，不是一次任务内部的 LLM loop iteration。一个用户消息触发多次工具调用时，仍算同一个最近 turn。

第一版已经完成方向 1、方向 2、方向 3 的核心实现：prompt tail 已按语义 turn 裁剪，commit 后 lazy compaction 已接入 LLM summarizer，并保留确定性 fallback。第三版已完成通用历史大 tool result prompt projection；后续仍可按具体业务工具补更精细的 selected fields。

## 第二版实现

第二版修复真实会话暴露的过早 tail-only 问题：

1. `ContextRuntimeMemory.DEFAULT_LIMITS`
   - `maxPromptMessages` 从 `12` 调整为 `128`。
   - `maxVisibleMessages` 从 `24` 调整为 `128`。
2. `model_runtime_budget.py`
   - 模型预算 preset 新增 message count 边界：
     - `generic.32k`: `max_prompt_messages=160`, `max_visible_messages=240`
     - `generic.128k`: `max_prompt_messages=512`, `max_visible_messages=768`
     - `generic.200k`: `max_prompt_messages=768`, `max_visible_messages=1024`
     - `generic.1m`: `max_prompt_messages=2048`, `max_visible_messages=3072`
   - `runtimeBudgetOverride` / `runtime_budget_override_json` 支持覆盖 `maxPromptMessages` 和 `maxVisibleMessages`。
3. `root_graph._sync_memory_limits_from_runtime_context(...)`
   - 每次 turn prepare / refresh / commit 时，从 `llm_config` 解析预算并同步 `maxPromptMessages` / `maxVisibleMessages`。
   - 这会修正已经落在旧 session root frame 中的 `maxPromptMessages=12`，下一轮提交即可恢复到模型预算对应窗口。

边界策略调整为：

1. message count 只作为安全上限，不再作为 128k/200k 模型下的主要裁剪依据。
2. 主要裁剪依据仍是 `maxPromptChars` / `maxVisibleChars`，后续可替换为真实 tokenizer。
3. 真正超过预算时，才由 lazy compaction 生成 `compactedSummary + pinned head + retained tail`。

## 第三版设计口径：裁剪触发压缩，不直接丢弃

后续实现必须区分三类边界：

1. `maxVisibleMessages` / `maxVisibleChars` / 后续 `maxVisibleTokens`
   - 这是 runtime memory 的压缩触发水位。
   - 达到水位时，不应直接丢弃中间消息。
   - 正确行为是保留 pinned head 与 tail，把中间可压缩区间交给 summarizer，生成或增量更新 `compactedSummary`。
2. `maxPromptMessages` / `maxPromptChars` / 后续 `maxPromptTokens`
   - 这是提交给 LLM 前的最后安全护栏。
   - 正常路径下不应依赖它做主要记忆裁剪。
   - 如果 `build_prompt_view()` 发现 prompt 会因为该护栏切掉历史，应优先在 commit / prepare 阶段触发压缩，再提交 `summary + tail`。
3. 单条消息 / 单个 tool result 预算
   - 这是 projection 边界。
   - 单个历史 tool result 过大时，应投影为 digest / selected fields / refs。
   - 该投影不得作用于最近 N 个语义 turn；最新窗口内的大 tool result 必须保持 raw content。
   - 当前参数为 `projectHistoricalToolResults` 和 `rawToolResultTailTurnCount`；前者默认打开，后者默认保留最近 6 个语义 turn 的 raw tool result。
   - 这里的 N 是用户语义 turn / 业务任务回合，不是 provider loop iteration。一个用户任务内的 assistant/tool 多轮循环作为同一语义 turn 的一部分处理。
   - 完整内容继续保留在 runtime memory、frame report、runtime event log、artifact 或业务证据文件中。

压缩触发条件至少包含两类：

1. 消息数量水位：适合发现长对话、小消息、多工具回合导致的 provider protocol 膨胀。
2. token/char 水位：适合发现少量大消息、大工具结果、长文本输入导致的上下文膨胀。

当前实现仍使用字符数近似 token。后续引入 tokenizer 后，应将 `maxPromptChars` / `maxVisibleChars` 替换或补充为真实 token 预算；在此之前，字符预算继续作为 fail-safe。

## 第四版实现：压缩参数进入 runtime budget

第四版把压缩窗口参数纳入 model runtime budget preset / override，避免 head / tail / summary 上限仍停留在不可观测的代码默认值：

1. `model_runtime_budget.py`
   - preset 新增：
     - `compaction_head_turn_count`
     - `compaction_tail_turn_count`
     - `max_compaction_summary_chars`
   - override 支持 camelCase / snake_case：
     - `compactionHeadTurnCount`
     - `compactionTailTurnCount`
     - `maxCompactionSummaryChars`
2. `root_graph._sync_memory_limits_from_runtime_context(...)`
   - 将上述 budget 字段同步到 Root runtime memory：
     - `compaction_head_turn_count -> headTurnCount`
     - `compaction_tail_turn_count -> tailTurnCount`
     - `max_compaction_summary_chars -> maxSummaryChars`
3. 默认值与 `06-normal-turn-runtime-context-design.md` 对齐：
   - `headTurnCount = 2`
   - `tailTurnCount = 8`
   - `maxSummaryChars = 4000`
4. 长上下文 preset 可适度放宽 summary 容量：
   - `generic.200k` 默认 `max_compaction_summary_chars = 6000`
   - `generic.1m` 默认 `compaction_tail_turn_count = 12`、`max_compaction_summary_chars = 8000`

这意味着压缩策略可以随模型能力自动匹配，也可以由上游在 `runtimeBudgetOverride` 中覆盖，不需要改 BizWorker 代码。

压缩时必须保护以下结构：

1. provider tool protocol 不可拆分：`assistant.tool_calls` 与对应 `tool` result 要么一起进入 tail，要么一起进入 summarizer，不允许只保留孤立 tool result。
2. 语义 turn 尽量不拆分：普通 user / assistant 回合应按 turn 进入 tail 或 summary。
3. head / tail 语义优先：保留最早的关键约束、身份、长期用户目标，以及最近 N 个业务回合。
4. summarized middle 必须可追溯：summary 中记录被压缩区间的 turn 范围、关键实体、已完成工作、未决动作、错误与恢复线索。
5. 大 tool result projection 只发生在 prompt assembly 阶段；runtime memory 不因 projection 丢失 raw tool result。

压缩失败时：

1. 不能静默丢弃中间消息。
2. 使用 deterministic fallback summary，至少记录被压缩区间的消息数量、role 分布、用户输入摘要和工具调用名称。
3. fallback summary 应标记 `summaryQuality=deterministic_fallback`，便于后续排查。

实现收口建议：

1. `commit_turn` 后，先根据 visible window 水位决定是否压缩。
2. `build_prompt_view` 只负责从已治理的 runtime memory 生成 prompt；如果它触发最后安全护栏，应记录 warning，并在下一次 commit/prepare 前推动压缩。
3. 对真实 LLM body 的 `llm-submissions` 日志要保留压缩前后的 evidence refs，便于复盘“为什么这条消息没有进入 prompt”。

## 第五版实现：prompt hard cap warning / event

第五版补齐 prompt 预算触顶的可观测性。正常路径下，BizWorker 应在提交 LLM 前先尝试压缩，避免 `build_prompt_view()` 直接按 hard cap 切掉 runtime-visible messages。若该路径发生，应同时写入本轮提交 body 和 runtime event log：

1. `ContextRuntimeMemory.prompt_budget_status()`
   - 计算 `maxPromptMessages` / `maxPromptChars` 下，当前 `head + summary + visible` 是否会被最终 prompt assembly 截断。
   - 输出 `wouldClip`、`projectedVisibleMessageCount`、`remainingMessages`、`projectedVisibleChars`、`remainingChars`、`hasCompactedSummary` 等诊断字段。
2. Root prepare / refresh 阶段
   - 调用 `compact_for_prompt_budget()` 前后各采样一次 budget status。
   - 如果压缩后不再触顶，记录 `PROMPT_BUDGET_PRE_COMPACTION`，表示本轮通过预压缩避免了 hard-cut。
   - 如果压缩后仍触顶，记录 `PROMPT_BUDGET_HARD_CAP_REMAINS`，表示最终 prompt assembly 仍可能执行最后护栏裁剪，需要继续调大预算、降低 tool 输出或优化 summary。
3. `logs/llm-submissions/*.json`
   - `meta.runtimeWarnings[]` 保存本次真实提交 LLM 前发生的 warning。
   - 该字段是复盘单次 provider body 的入口，不作为下一轮 prompt source。
4. `logs/runtime-message-events/*.jsonl`
   - 新增 `eventType=runtime_warning`。
   - 该事件与同一 `taskId/frameId` 的 provider protocol 事件并列，用于按时间线复盘“本轮 prompt 为什么已经被压缩”。

warning payload 统一包含：

| 字段 | 说明 |
| --- | --- |
| `schemaVersion` | warning schema 版本，当前为 `1` |
| `code` | `PROMPT_BUDGET_PRE_COMPACTION` 或 `PROMPT_BUDGET_HARD_CAP_REMAINS` |
| `severity` | `info` / `warning` |
| `taskId` / `frameId` | 触发 warning 的运行上下文 |
| `runtimeRevision` | 触发时的 runtime memory revision |
| `compacted` | 本次是否实际执行了压缩 |
| `before` / `after` | 压缩前后的 `prompt_budget_status()` 快照 |

## 验收建议

1. 已新增单元测试：`maxPromptMessages` 裁剪后 prompt 不以孤立 assistant 语义消息开头。
2. 已新增单元测试：裁剪后仍保留匹配的 `assistant.tool_calls -> tool` protocol，或一起裁掉。
3. 已新增单元测试：单条 tool result 和 tool call args 使用独立预算。
4. 已新增单元测试：Root commit 超过 visible 阈值时调用 LLM summarizer，并写入 `summaryQuality=llm`。
5. 已新增 scripted E2E：多轮 Root 回合在 `maxPromptMessages` 触顶前触发 prompt pre-compaction，并在 `llm-submissions.meta.runtimeWarnings` 与 `runtime-message-events` 中写入 `PROMPT_BUDGET_PRE_COMPACTION`。
6. 已新增单元测试：最近 N 个语义 turn 内的大 tool result 保持 raw，N 轮之外的历史大 tool result 在 prompt view 中投影为 digest / refs / selected fields。
7. 已新增单元测试：`projectHistoricalToolResults=false` 时不投影历史大 tool result。

## Progress Tracking

Development:

- [x] Runtime memory prompt tail 改为 turn-aware。
- [x] Tool result / tool call args 独立裁剪参数落地。
- [x] 模型 runtime budget preset 接入 Root memory limits。
- [x] Root commit lazy compaction 接入 LLM summarizer，并保留 fallback。
- [x] 第二版调大默认/preset message count，避免 128k 模型下未超预算就只取最近 N 条。
- [x] 第三版裁剪/压缩语义落档：裁剪水位触发压缩，不直接丢弃。
- [x] `build_prompt_view` 前新增 prompt budget 预压缩：如果完整 visible window 会被 hard cap 截断，先压缩中间区间再提交 summary + tail。
- [x] 历史大 tool result prompt projection：最近 `rawToolResultTailTurnCount` 个语义 turn 保持 raw，N 轮之前投影为 digest / refs / selected fields。
- [x] 历史大 tool result projection 增加 `projectHistoricalToolResults` 开关，默认打开，可由模型 runtime budget override 关闭。
- [x] 压缩 head / tail / summary 参数进入模型 runtime budget preset / override，并同步到 Root runtime memory。
- [x] 将 prompt hard cap 触发记录为 runtime warning / event，便于从日志追踪。
- [x] 脚本化 LLM smoke 后复核 `llm-submissions` 是否符合预期。
- [ ] 后续设计真实 tokenizer 与更精确 token 预算。

Testing:

- [x] `pytest tests/test_context_memory.py tests/test_llm_message_builder.py tests/test_model_runtime_budget.py tests/test_llm_skill_router.py -q`
- [x] `pytest tests/test_root_graph.py::test_runtime_memory_commit_uses_llm_compaction_summarizer tests/test_context_memory.py -q`
- [x] `pytest tests/test_context_memory.py tests/test_root_graph.py::test_runtime_memory_commit_uses_llm_compaction_summarizer -q`
- [x] `pytest -q` under `tools/langgraph-biz-worker`：650 passed, 6 skipped, 11 warnings。
- [x] `pytest tests/test_context_memory.py -q`：覆盖历史大 tool result projection 与最近 raw tail 保留。
- [x] `ruff check` 覆盖本次 Python 改动文件。
- [x] `pytest -q` 全量 BizWorker 测试：`649 passed, 6 skipped`。
- [x] `pytest tests/test_context_memory.py tests/test_root_graph.py::test_prompt_budget_pre_compaction_records_runtime_warning tests/test_llm_submission_log.py tests/test_llm_message_builder.py -q`：35 passed。
- [x] `pytest tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_root_prompt_budget_warning_is_logged -q`：覆盖 prompt pre-compaction warning 在真实 mock LLM body 与 runtime event log 中可复盘；该场景会额外生成一次 `runtime-memory.compaction` LLM submission，随后 Root submission 带 warning。
- [x] `pytest -q` 全量 BizWorker 测试：`654 passed, 6 skipped, 11 warnings`。

Experience:

- N/A。该 workitem 为 BizWorker runtime prompt 组装策略，无直接 UI 改动；体验验收通过真实会话与 `llm-submissions` 复盘完成。

## 非目标

1. 不把上游完整 UI transcript 重新拉回 BizWorker。
2. 不要求 frame/report/log 也跟随 runtime prompt 裁剪；它们仍作为完整 execution evidence 保留。
3. 不在本 workitem 中决定最终默认参数，参数需要结合真实 token 成本、模型上下文长度和 TMS 业务工具输出规模单独确认。
