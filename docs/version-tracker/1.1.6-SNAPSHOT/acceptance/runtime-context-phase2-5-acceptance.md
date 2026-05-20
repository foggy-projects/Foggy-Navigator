---
acceptance_scope: feature
version: 1.1.6-SNAPSHOT
target: bizworker-runtime-context-phase2-5
status: signed-off
decision: accepted-with-followups
signed_off_by: Codex
signed_off_at: 2026-05-21
reviewed_by: Codex
blocking_items: []
follow_up_required: yes
evidence_count: 7
doc_role: acceptance-signoff
doc_purpose: 签收 BizWorker Runtime Context Phase 2-5 实现，并记录测试证据、残余风险和后续增强项。
---

# Feature Acceptance

## Background

本次验收对象是 BizWorker Runtime Context Phase 2-5：在 Phase 1 的 Root frame runtime memory 基础上，补齐 Skill 结果投影、执行中用户消息排队、lazy 压缩，以及 Java `recentConversation` 默认退场。完成后，BizWorker 负责维护 LLM runtime context，上游继续保存完整 UI transcript。

## Acceptance Basis

- [../README.md](../README.md)
- [../03-skill-internal-context-isolation-and-promotion.md](../03-skill-internal-context-isolation-and-promotion.md)
- [../04-runtime-visible-conversation-and-recovery-design.md](../04-runtime-visible-conversation-and-recovery-design.md)
- [../06-normal-turn-runtime-context-design.md](../06-normal-turn-runtime-context-design.md)
- [../07-normal-turn-runtime-context-implementation-plan.md](../07-normal-turn-runtime-context-implementation-plan.md)
- [../quality/runtime-context-phase2-5-implementation-quality.md](../quality/runtime-context-phase2-5-implementation-quality.md)

## Checklist

- accepted: 正常 Skill 完成后，Parent runtime memory 记录的是受控 promoted result、Skill/frame/report 引用和最终 assistant 语义结果，不带入 raw tool call 堆栈。
- accepted: non-recoverable BusinessFunction 配置错误会提交为 runtime-visible error turn，最终可见错误保留真实原因，不再被 max-iterations 覆盖。
- accepted: 同 `contextId` 执行中追加用户消息会进入 pending queue，并在 LLM / tool checkpoint 注入当前 turn。
- accepted: closing / idle memory 不接收无消费者的 queued message，而是返回可重试 busy。
- accepted: runtime memory 压缩采用 lazy threshold、head pinned、tail retained、summary prompt 插入和 fallback redaction。
- accepted: Java 默认不再注入 `recentConversation`，兼容模式由 `foggy.navigator.langgraph.worker.include-recent-conversation` 显式打开。
- accepted: frame / report / log 继续完整保留，不受 runtime prompt compaction 限制。

## Evidence

1. `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py`：新增 Skill / error 投影 metadata、pending user input enqueue、runtime memory checkpoint、commit / abandon 互斥保护。
2. `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_agent.py`：新增 checkpoint 注入、non-recoverable error submit、private message 队列合并。
3. `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/context_memory.py`：新增 queuedUserMessages、lazy head-tail compaction、summary prompt、fallback redaction。
4. `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphTaskService.java`：`recentConversation` 默认不注入，仅显式兼容开关开启时读取。
5. `cd tools/langgraph-biz-worker; $env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest tests/test_context_memory.py tests/test_root_graph.py tests/test_llm_skill_agent.py tests/test_query.py -q`：`94 passed`。
6. `cd tools/langgraph-biz-worker; $env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest tests -q`：`569 passed, 6 skipped`。
7. `mvn -pl addons/langgraph-biz-worker -am test`：Surefire 汇总 `reports=135 tests=1017 failures=0 errors=0 skipped=0`。

## Failed Items

无阻断项。

## Risks / Open Items

- 多实例部署下仍需补充跨进程 / 跨节点锁。
- 默认 compaction 当前使用 deterministic fallback；真实 LLM summarizer provider 需要按成本、延迟和模型可用性单独接入。
- UI transcript rollback / regenerate / fork 需要后续设计 `turnId` / `revision` 契约。
- TMS 真实链路建议在 `upstream_ref` 配置修复后再跑一次端到端复验。

## Final Decision

accepted-with-followups

Phase 2-5 已满足当前设计验收标准，可以合入。后续增强项已明确记录，不阻断本次交付。

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted-with-followups
- signed_off_by: Codex
- signed_off_at: 2026-05-21
- acceptance_record: docs/version-tracker/1.1.6-SNAPSHOT/acceptance/runtime-context-phase2-5-acceptance.md
- blocking_items: none
- follow_up_required: yes
