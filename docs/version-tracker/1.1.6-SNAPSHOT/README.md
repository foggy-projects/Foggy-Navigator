# 1.1.6-SNAPSHOT

本目录用于跟踪 `1.1.6-SNAPSHOT` 阶段的 BizWorker 会话上下文、运行时记忆与上游契约调整。

## 版本目标

1. BizWorker 成为 LLM runtime context 的 source of truth；Java / 上游继续负责完整 UI transcript。
2. 普通多轮对话、Skill 完成、`AWAITING_USER` 续接、中断恢复都统一进入 `ContextRuntimeMemory` / control state 规则。
3. `recentConversation` 降级为 deprecated external compatibility input，只允许空 memory bootstrap，不允许覆盖 BizWorker memory。
4. Tool call、Skill private messages、report/log/journal 作为 execution evidence 保留，但不默认进入下一轮 semantic conversation。
5. 上下文压缩采用 head-tail + lazy LLM summarizer，并提供 deterministic fallback，避免 runtime context 无界增长。
6. 同一 `contextId` 的 Root frame / runtime memory 写入必须由 BizWorker 自身提供排他保护。

## 版本验收基线

1. 没有 Java `recentConversation` 时，BizWorker 仍能维持普通多轮语义连续。
2. 正常 Skill 完成后，下一轮 prompt 默认看到 `U1 -> A1 -> U2`，而不是 raw tool call / tool result。
3. `AWAITING_USER` 的下一条用户消息确定性接回原 child frame。
4. recoverable interruption 通过 control state 恢复或搁置，不伪造成普通 assistant turn。
5. 同一 `contextId` 不允许真正并发 LLM loop；Phase 1 未实现 queue 前必须有明确 busy / conflict / 上游串行契约，Phase 3 起进入 pending queue + checkpoint。
6. `AWAITING_USER` 支持用户取消/换题 escape hatch，不能把用户困在 child frame。
7. UI transcript rollback / regenerate 不作为 Phase 1-4 默认能力；后续必须通过 revision / turnId / fork 契约单独设计。

## 当前条目

- [01-biz-worker-context-owned-runtime-memory.md](./01-biz-worker-context-owned-runtime-memory.md) - BizWorker 按 `contextId` 自主管理运行时上下文与执行摘要，降低对 Java 会话层 `recentConversation` 的依赖
- [02-runtime-context-governance-framework-comparison.md](./02-runtime-context-governance-framework-comparison.md) - 对比 Claude Code / Codex / OpenAI Agents SDK / LangGraph / AutoGen / OpenHands 的上下文与压缩策略，收口 BizWorker 运行时上下文治理基线
- [03-skill-internal-context-isolation-and-promotion.md](./03-skill-internal-context-isolation-and-promotion.md) - 明确 Skill 内部上下文隔离、`AWAITING_USER` 自动恢复、完整证据保留、受控结果提升与 Parent runtime context 的边界
- [04-runtime-visible-conversation-and-recovery-design.md](./04-runtime-visible-conversation-and-recovery-design.md) - 统一定义 `runtimeVisibleConversation`、tool call 可见性、Skill 完成投影与中断恢复控制态
- [05-business-function-upstream-ref-error-feedback-bug.md](./05-business-function-upstream-ref-error-feedback-bug.md) - 记录 BusinessFunction adapter `upstream_ref` 配置错误被误当成 LLM 可修复入参并折叠为 max-iterations 的 BUG
- [06-normal-turn-runtime-context-design.md](./06-normal-turn-runtime-context-design.md) - 明确普通用户消息在 BizWorker 内写入、恢复、压缩和组装为下一轮 LLM runtime context 的设计
- [07-normal-turn-runtime-context-implementation-plan.md](./07-normal-turn-runtime-context-implementation-plan.md) - 将普通消息 `ContextRuntimeMemory` 设计拆解为分阶段开发任务、测试清单和验收闸门

## 当前签收记录

- [quality/runtime-context-phase1-implementation-quality.md](./quality/runtime-context-phase1-implementation-quality.md) - BizWorker Runtime Context Phase 1 实现质量检查
- [acceptance/runtime-context-phase1-acceptance.md](./acceptance/runtime-context-phase1-acceptance.md) - BizWorker Runtime Context Phase 1 功能验收签收
- [quality/runtime-context-phase2-5-implementation-quality.md](./quality/runtime-context-phase2-5-implementation-quality.md) - BizWorker Runtime Context Phase 2-5 实现质量检查
- [acceptance/runtime-context-phase2-5-acceptance.md](./acceptance/runtime-context-phase2-5-acceptance.md) - BizWorker Runtime Context Phase 2-5 功能验收签收
