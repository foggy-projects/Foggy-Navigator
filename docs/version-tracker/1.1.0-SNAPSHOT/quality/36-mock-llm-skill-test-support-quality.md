# 36 Mock LLM Skill 测试支持质量检查

## 文档作用

- doc_type: quality
- intended_for: reviewer / signoff-owner
- purpose: 对 36 Mock/真实 LLM Skill Agent 测试支持进行轻量质量检查

## 检查结论

- version: 1.1.0-SNAPSHOT
- target: 36-mock-llm-skill-test-support
- status: reviewed
- decision: ready-for-acceptance
- checked_at: 2026-04-28
- blocking_items: none

## Scope Check

36 的范围是测试能力与可选执行路径补强，不替代真实业务工具适配：

1. Mock LLM Service 支持按 `tool` 角色消息推进多轮响应。
2. Biz Worker 增加默认关闭的 `LlmSkillAgent` 路径。
3. env 文件支持 mock/real LLM 切换。
4. 真实 LLM 联调用于验证 Skill Agent 完整 tool-call loop。

未发现范围蔓延。`BIZ_WORKER_LLM_EXECUTE_SKILLS=false` 默认关闭，现有程序化 Skill 子图不受影响。

## Quality Findings

| # | 检查项 | 结论 | 说明 |
|---|---|---|---|
| Q1 | 状态写入边界 | pass | 模型不能直接写 Frame 状态，仍由 Runtime `submit_result()` 写入 `COMPLETED` |
| Q2 | 上下文隔离 | pass | `close_frame()` 后 private messages/state/tool_calls 清空，仅 promoted result 上浮 |
| Q3 | 失败修正闭环 | pass | 不合格 `submit_skill_result` 会被 Runtime reject，Agent loop 继续要求模型修正 |
| Q4 | 配置安全边界 | pass | 真实 API Key 仅写入本地 ignored env 文件，文档不记录密钥 |
| Q5 | 默认兼容性 | pass | LLM Skill 执行默认关闭，不改变 31 已验收主链路 |

## Follow-up Risks

| # | 风险 | 级别 | 处理方式 |
|---|---|---|---|
| R1 | Anthropic tool_use 未单独真实联调 | low | 后续 provider 适配时验证 |
| R2 | 真实业务工具注册表未实现 | medium | 真实业务系统接入阶段处理 |
| R3 | `SKILL.md` 正文尚未完整注入 frame prompt | medium | Skill Registry 后续增强 |

## Decision

36 质量检查通过，可进入轻量验收。R1-R3 是后续能力扩展，不阻塞 1.1.0 封版。
