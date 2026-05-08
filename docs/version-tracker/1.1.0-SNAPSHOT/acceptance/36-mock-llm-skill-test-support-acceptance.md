---
acceptance_scope: workitem
version: 1.1.0-SNAPSHOT
target: 36-mock-llm-skill-test-support
doc_role: acceptance-record
doc_purpose: 36 Mock/真实 LLM Skill Agent 测试支持轻量验收
status: signed-off
decision: accepted-with-followups
signed_off_at: 2026-04-28
blocking_items: []
follow_up_required: yes
---

# 36 Acceptance

## Document Purpose

- doc_type: acceptance
- intended_for: signoff-owner / reviewer
- purpose: 记录 36 Mock/真实 LLM Skill Agent 测试支持的验收结论

## Acceptance Basis

- Workitem: `docs/version-tracker/1.1.0-SNAPSHOT/36-mock-llm-skill-test-support.md`
- Quality: `docs/version-tracker/1.1.0-SNAPSHOT/quality/36-mock-llm-skill-test-support-quality.md`
- Coverage: `docs/version-tracker/1.1.0-SNAPSHOT/coverage/36-mock-llm-skill-test-support-coverage.md`

## Checklist

- [x] Mock LLM Service 支持 Skill tool-call loop 场景
- [x] Biz Worker 提供默认关闭的 `LlmSkillAgent` 执行路径
- [x] env 文件可切换 mock LLM 与真实 LLM
- [x] schema reject/retry 闭环已测试
- [x] Frame 完成后只上浮结果，私有上下文清理已验证
- [x] 真实 OpenAI-compatible LLM 已完成联调

## Risks / Follow-up

| # | 风险 | 是否阻塞 | 处理方式 |
|---|---|---|---|
| R1 | Anthropic tool_use 未真实联调 | 否 | provider 适配阶段验证 |
| R2 | 真实业务工具注册表未实现 | 否 | 真实业务系统接入阶段实现 |
| R3 | `SKILL.md` 正文未完整注入 frame prompt | 否 | Skill Registry 后续增强 |

## Final Decision

**accepted-with-followups**

36 达到 1.1.0 的测试能力补强目标，无阻塞问题。后续项均不影响当前版本核心 Skill Runtime 封版。
