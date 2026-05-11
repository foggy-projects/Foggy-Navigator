# Biz Worker 相对时间 Runtime Context 优化

## 文档作用

- doc_type: optimization
- version: 1.1.3-SNAPSHOT
- status: ready-for-verification
- date: 2026-05-11
- source: user-report
- priority: high
- owner: langgraph-biz-worker
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 记录 Biz Worker 在解释“本月”等相对时间查询时缺少当前时间锚点的问题，并跟踪动态时间上下文注入方案

## 背景

用户在 TMS 会话中询问“本月运费”。Biz Worker 根路由日志显示传给 LLM 的 system prompt 只包含静态角色、技能列表和工具调用说明，没有当前日期、时区或业务日期。

对应日志：

- `tools/langgraph-biz-worker/data/logs/llm-conversations/1a70bbbc-7f05-4267-b7cb-365017a3de07/0001_lgt_15202333286a4bf6.jsonl`
- `tools/langgraph-biz-worker/data/logs/skill-tool-calls/lgt_15202333286a4bf6.jsonl`

在子技能 `foggy-query-agent` 执行过程中，模型曾尝试多个时间范围，包括正确的 `[2026-05-01, 2026-06-01)`，但也出现了 `[2026-07-01, 2026-08-01)` 等偏移范围。当前日期为 2026-05-11，业务语义上的“本月”应解析为 2026 年 5 月。

## 问题陈述

`LlmSkillAgent` 当前构造 prompt 时只传入：

1. 静态 skill system prompt。
2. 用户请求。
3. skill input。

缺少每次请求动态变化的运行时日期信息，导致 LLM 对“本月”“今天”“昨日”“近 7 天”等相对时间表达缺少可靠锚点。

## 目标结果

1. 静态 system prompt 保持稳定，避免每次请求更新当前时间导致 prompt cache 前缀失效。
2. 在 skill 执行层注入短小的动态 runtime context，包含当前时间、时区、业务日期和当前月范围。
3. `foggy-query-agent` 等真实解释查询条件的子技能可以用该上下文解析相对日期。
4. 上游未来可通过 `runtime_context` 显式传入 `current_time/timezone/business_date`；未传时 Worker 使用本机当前时间兜底。

## 修复范围

- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_agent.py`
  - 在 `_build_user_prompt()` 中追加动态 runtime context。
  - 新增运行时日期上下文构造逻辑。
  - 保持 `_build_system_prompt()` 不注入当前时间。
- `tools/langgraph-biz-worker/tests/test_llm_skill_agent.py`
  - 补充 runtime context 注入的回归测试。
  - 补充 system prompt 不包含动态当前时间的缓存友好性测试。

## 约束与非目标

1. 本次不改根路由 system prompt。
2. 本次不把所有相对时间解析完全下沉为确定性 DSL 预处理；该方向保留为后续增强。
3. 本次不改变 Java Worker Gateway 或 `tms.dataset.queryModel` 的 API 契约。
4. 本次不引入第三方日期解析库。

## 验收标准

1. `LlmSkillAgent` 发给模型的 user message 中包含 `Runtime context`。
2. 当 `runtime_context` 显式传入 `current_time=2026-05-11T10:37:10+08:00`、`timezone=Asia/Shanghai` 时，prompt 中包含：
   - `current_time: 2026-05-11T10:37:10+08:00`
   - `timezone: Asia/Shanghai`
   - `business_date: 2026-05-11`
   - `current_month_range: [2026-05-01, 2026-06-01)`
3. 静态 system prompt 不包含当前时间、当前月范围等动态字段。
4. 相关单元测试通过。

## Progress Tracking

### Development Progress

- [x] 定位缺失点：根路由日志与 `LlmSkillAgent` prompt 构造均缺少当前时间。
- [x] 明确设计：动态时间放入 skill 执行层 user/runtime context，静态 system prompt 保持缓存友好。
- [x] 完成 `llm_skill_agent.py` 实现。
- [x] 完成测试回补。

### Testing Progress

- status: passed
- `tools/langgraph-biz-worker`: `$env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest tests/test_llm_skill_agent.py`
  - Result: `8 passed`
- `tools/langgraph-biz-worker`: `$env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest tests`
  - Result: `327 passed, 6 skipped`

### Experience Progress

- status: N/A
- reason: 本项为 Biz Worker 后端 prompt/runtime context 行为优化，不涉及前端页面、交互或可视化体验变更。

## Execution Check-in

- status: completed
- completed_work: 已在 Biz Worker skill agent 用户消息中注入动态 Runtime context，包含 `current_time`、`timezone`、`business_date` 和 `current_month_range`；静态 system prompt 保持不含动态时间字段。
- touched_code_paths:
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_agent.py`
  - `tools/langgraph-biz-worker/tests/test_llm_skill_agent.py`
  - `docs/version-tracker/1.1.3-SNAPSHOT/README.md`
  - `docs/version-tracker/1.1.3-SNAPSHOT/10-biz-worker-relative-time-runtime-context.md`
- test_status: passed
- self_check: self-check-only
- acceptance_readiness: ready-for-verification
- remaining_risks: 当前仍由 LLM 使用 runtime context 解析相对时间；后续如需更强确定性，应把“本月/今日/近 N 天”等表达下沉到 query adapter 或参数构建层做代码级解析。
