---
type: bug
bug_source: user-report
version: 1.1.6-SNAPSHOT
ticket: BUG-root-agentic-routing-missing-llm-submission-log
severity: major
status: ready-for-verification
reproduction_status: confirmed
test_strategy: e2e-test
automation_decision: required
owner: langgraph-biz-worker
---

# BUG Work Item

## Background

用户验收 `Skill 不再进入 frame，Agent 才进入 frame` 后，检查真实 TMS 会话目录时先后发现两个同源问题：

- 普通 root 回合没有生成统一的 `logs/llm-submissions/*.json`。
- 隐式或显式 skill alias 触发了遗留 root 直连 LLM 分支，导致提交给 LLM 的 body 回退为英文系统提示词，并把 `allowed_skills` 混入 human 消息。

复现会话：

- 缺少 submission log：`tools/langgraph-biz-worker/data/runtime/sessions/by-date/2026/05/22/cf/bctx_20260522_cf_cf8b9d56c9d9460f82f8ad6b8b46018e`
- 旧提示词回退：`tools/langgraph-biz-worker/data/runtime/sessions/by-date/2026/05/22/72/bctx_20260522_72_72b2bd1c0e2540df866ea26adf15410f`

## Reproduction

1. 启动 `langgraph-biz-worker`，开启 LLM 执行与 submission log。
2. 上游创建新会话并发送普通消息，例如 `hi`。
3. 上游 context 中包含 `businessSkillName` / `skillName` 或 `allowed_skills`。
4. 检查对应 `bctx_.../logs/llm-submissions/*.json`。

实际结果：

- 部分路径只出现旧 conversation log，或者 submission log 中出现旧 root 直连 LLM body。
- system message 为英文。
- human message 被包装成 `Current user message + Context`，并混入技能列表。

期望结果：

- 所有真实提交给 LLM 的完整请求 body 都写入 `logs/llm-submissions/<seq>_...json`。
- root 回合统一进入 persistent `system.root` / `LlmSkillAgent` 路径。
- system prompt 使用中文，并把可见业务技能以 Markdown 放在 system 上下文中。
- human message 保持用户原文，最多附加运行时时间，不混入 skill 列表或其他业务上下文。
- 遗留 root 直连 LLM 路径和对应开关彻底移除，避免后续再次被触发。

## Impact Scope

- 影响真实上游验收和线上排障。
- 影响带 skill alias / allowed skills 的普通 root 回合。
- 影响 LLM prompt 缓存命中率、提示词一致性，以及用户消息语义边界。

## Test Strategy

补充脚本化 E2E：

- 覆盖上游传入 `businessSkillName` 和 `allowed_skills` 的普通 root 回合。
- 开启 `llm_submission_log_enabled`。
- 断言生成 `logs/llm-submissions`。
- 断言 operation 为统一 skill agent 调用。
- 断言 system prompt 为中文，包含 Markdown 格式的可用技能说明。
- 断言 human message 等于用户原文，不包含 `allowed_skills`、`Context:` 或技能 id。

## Code Inventory

- `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py`
  - 移除遗留 root 直连 LLM 分支。
  - 先判定 persistent `system.root`，再进入非 LLM legacy fallback。
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_router.py`
  - 删除未使用的旧 LLM skill router 类，仅保留 chat model factory。
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/config.py`
  - 移除旧 root 直连 LLM 开关。
- `tools/langgraph-biz-worker/tests/test_e2e_scripted_tool_call_streaming.py`
  - 新增/调整回归覆盖。

## Fix Checklist

- [x] 确认真实会话目录缺少统一 submission log。
- [x] 确认旧 root 直连 LLM body 会回退到英文提示词并污染 human 消息。
- [x] 建立 BUG work item。
- [x] 移除旧 root 直连 LLM 分支。
- [x] 移除旧配置开关。
- [x] 补充 E2E 回归测试。
- [x] 运行目标测试。
- [x] 重启本机 3061 BizWorker。
- [x] 真实冒烟生成新会话并确认 prompt body 契约。

## Verification

- 静态检查：`ruff check src/langgraph_biz_worker/graphs/root_graph.py src/langgraph_biz_worker/runtime/llm_skill_router.py src/langgraph_biz_worker/config.py tests/test_e2e_scripted_tool_call_streaming.py tests/test_root_graph.py tests/test_account_skill_routing.py tests/test_llm_skill_router.py` passed。
- 目标测试：`pytest tests/test_root_graph.py tests/test_account_skill_routing.py tests/test_llm_skill_router.py tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_root_prompt_contract_ignores_skill_alias_and_keeps_user_message_clean -q`，32 passed。
- 完整脚本化 E2E：`pytest tests/test_e2e_scripted_tool_call_streaming.py -q`，29 passed。
- `git diff --check` passed。
- 真实冒烟 contextId：`bctx_20260522_aa_smoke-root-prompt-1585badca7d0490d`。
- `logs/llm-submissions` 文件名：`000001_conversation.root_lgt_smoke_root_prompt_1585badca7d0490d_frm_7fd83ad3d09b_iter01_attempt01.json`。
- 真实冒烟结果：`operation=skill_agent.invoke`，`roles=system,human`，system 为中文并包含 Markdown 技能列表，human 等于用户原文，未包含 `Context:`、`allowed_skills` 或技能 id。

## References

- 相关设计：`docs/version-tracker/1.1.6-SNAPSHOT/09-llm-submission-message-contract.md`
