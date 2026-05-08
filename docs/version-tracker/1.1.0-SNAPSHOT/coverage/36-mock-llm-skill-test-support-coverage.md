# 36 Mock LLM Skill 测试支持覆盖审计

## 文档作用

- doc_type: coverage
- intended_for: reviewer / signoff-owner
- purpose: 审计 36 对 Skill Agent tool-call loop、真实 LLM 联调、上下文清理的测试覆盖

## 覆盖结论

- version: 1.1.0-SNAPSHOT
- target: 36-mock-llm-skill-test-support
- status: reviewed
- conclusion: ready-for-acceptance
- reviewed_at: 2026-04-28

## Coverage Matrix

| 能力点 | 覆盖状态 | 证据 |
|---|---|---|
| Mock LLM 按 user/tool 消息匹配响应 | covered | `tools/mock-llm-service/tests/test_strategies.py` |
| Mock LLM Skill 场景完整 tool-call loop | covered | `tools/mock-llm-service/tests/test_openai_api.py` |
| Worker `LlmSkillAgent` 调用业务工具并提交结果 | covered | `tools/langgraph-biz-worker/tests/test_llm_skill_agent.py` |
| `submit_skill_result` schema reject 后重试 | covered | `tools/langgraph-biz-worker/tests/test_llm_skill_agent.py` |
| env 文件切换 mock/real LLM | covered | `tools/langgraph-biz-worker/tests/test_config.py` |
| 真实 OpenAI-compatible LLM 联调 | covered-manual | 36 主文档 §6 记录 |
| FileFrameJournal 数据一致性比对 | covered-manual | 36 主文档 §6.2 记录 |
| Anthropic tool_use 真实联调 | not-covered | 后续 provider 适配验证 |
| 真实业务工具注册表 | not-covered | 超出 1.1.0 范围 |

## Test Evidence

已记录并通过：

1. `tools/langgraph-biz-worker`: `202 passed`
2. `tools/mock-llm-service`: `17 passed, 1 warning`
3. Skill 相关定向覆盖：`31 passed`
4. 真实 LLM 联调：Frame `COMPLETED`
5. Journal 比对：`all_checks_passed = true`

## Decision

36 覆盖满足 1.1.0 测试补强目标。未覆盖项均属于后续 provider/真实业务工具接入范围，不阻塞当前验收。
