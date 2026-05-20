---
quality_scope: feature
quality_mode: phase2-5-final
version: 1.1.6-SNAPSHOT
target: bizworker-runtime-context-phase2-5
status: reviewed
decision: ready-with-followups
reviewed_by: Codex
reviewed_at: 2026-05-21
follow_up_required: yes
---

# Implementation Quality Gate

## Background

本次质量检查覆盖 BizWorker Runtime Context Phase 2-5：Skill 结果投影、执行中追加用户消息排队与 checkpoint、lazy head-tail 压缩、以及 Java `recentConversation` 默认退场。目标是让 BizWorker 成为 LLM runtime context 的事实源，同时保留完整 frame / report / log 作为执行证据。

## Check Basis

- [../03-skill-internal-context-isolation-and-promotion.md](../03-skill-internal-context-isolation-and-promotion.md)
- [../04-runtime-visible-conversation-and-recovery-design.md](../04-runtime-visible-conversation-and-recovery-design.md)
- [../06-normal-turn-runtime-context-design.md](../06-normal-turn-runtime-context-design.md)
- [../07-normal-turn-runtime-context-implementation-plan.md](../07-normal-turn-runtime-context-implementation-plan.md)

## Changed Surface

- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/context_memory.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_agent.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/routes/query.py`
- `tools/langgraph-biz-worker/tests/test_context_memory.py`
- `tools/langgraph-biz-worker/tests/test_root_graph.py`
- `tools/langgraph-biz-worker/tests/test_llm_skill_agent.py`
- `tools/langgraph-biz-worker/tests/test_query.py`
- `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphTaskService.java`
- `addons/langgraph-biz-worker/src/test/java/com/foggy/navigator/langgraph/worker/service/LanggraphTaskServiceTest.java`
- `addons/langgraph-biz-worker/src/test/java/com/foggy/navigator/langgraph/worker/e2e/BusinessAgentLanggraphLaunchE2ETest.java`

## Quality Checklist

- scope conformance: Phase 2-5 改动集中在 runtime memory、Root graph 入口、Skill agent checkpoint、Java recentConversation 兼容开关；未把 UI transcript 或 raw tool trace 混入 runtime semantic memory。
- code hygiene: 新增字段和 helper 命名与设计文档一致；未发现临时 debug 输出、无边界宽泛异常吞没或未使用的实验入口。
- concurrency behavior: 同 `contextId` 执行中追加消息进入 `queuedUserMessages`，在 LLM / tool checkpoint 注入；idle / closing window 返回可重试 busy，避免写入无人消费的队列。
- skill projection: 正常 Skill 完成只提升受控结果摘要和 frame/report 引用；non-recoverable BusinessFunction 配置错误会作为可见错误 turn 提交，不再折叠为 max-iterations。
- compaction behavior: lazy 阈值触发、head pinned、tail retained、中段 summary、summarizer 输入脱敏、敏感字段 fallback redaction 均有单元测试；当前默认路径使用 deterministic fallback，已提供 summarizer callable hook。
- compatibility contract: Java 默认不再读取 `SessionMessageRepository` 注入 `recentConversation`；仅在 `foggy.navigator.langgraph.worker.include-recent-conversation=true` 时走兼容路径。
- evidence retention: frame / report / log 不受 runtime memory compaction 影响，仍作为排障证据保留。
- test alignment: Python 目标测试、Python 全量测试、Java 目标测试、Java reactor 测试均已通过。

## Findings

未发现阻断 Phase 2-5 合入的问题。

## Risks / Follow-ups

- 当前 `contextId` 锁和队列仍是单进程内治理；多实例部署应补文件锁、数据库行锁或 Redis 分布式锁。
- Phase 4 已实现 summarizer hook 与 deterministic fallback，但默认生产路径尚未接入独立 LLM summarizer provider；如需更高质量长对话摘要，应单独接入并做成本/延迟开关。
- UI transcript rollback / regenerate / fork 仍不在本阶段范围，需要后续以 `turnId` / `revision` 契约单独设计。
- 真实 TMS 工单链路仍建议在 `upstream_ref` 配置修复后做一次端到端复验，确认 BusinessFunction 配置错误与正常调用路径都符合预期。

## Recommended Next Skills

- `foggy-test-coverage-audit`
- `foggy-acceptance-signoff`

## Decision

decision: ready-with-followups

Phase 2-5 实现质量可以进入功能验收。列出的 follow-up 不阻断当前 runtime context governance 主线合入。
