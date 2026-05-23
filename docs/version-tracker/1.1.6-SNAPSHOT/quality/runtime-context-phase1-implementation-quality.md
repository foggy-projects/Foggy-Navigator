---
quality_scope: feature
quality_mode: pre-coverage-audit
version: 1.1.6-SNAPSHOT
target: bizworker-runtime-context-phase1
status: reviewed
decision: ready-with-risks
reviewed_by: Codex
reviewed_at: 2026-05-21
follow_up_required: yes
---

# Implementation Quality Gate

## Background

本次实现目标是完成 BizWorker-owned `ContextRuntimeMemory` Phase 1：普通多轮语义窗口由 BizWorker Root frame 维护，Java `recentConversation` 只作为空 memory bootstrap，不再作为 Root prompt 的事实源。同时附带闭环 BusinessFunction `upstream_ref` 配置错误反馈 BUG。

## Check Basis

- [../06-normal-turn-runtime-context-design.md](../06-normal-turn-runtime-context-design.md)
- [../07-normal-turn-runtime-context-implementation-plan.md](../07-normal-turn-runtime-context-implementation-plan.md)
- [../05-business-function-upstream-ref-error-feedback-bug.md](../05-business-function-upstream-ref-error-feedback-bug.md)

## Changed Surface

- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/context_memory.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_agent_prompts.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/routes/query.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_agent.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_tool_dispatcher.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/tools/business_function_tools.py`
- `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/tool/InvokeBusinessFunctionTool.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/OpenApiAgentReadinessService.java`
- 对应 Python / E2E / Maven 测试。

## Quality Checklist

- scope conformance: Phase 1 已限制在 Root frame runtime memory、prompt view、commit/abandon、进程内互斥锁；未提前实现 pending queue 或 LLM summarizer。
- code hygiene: 未发现 debug 输出、临时分支或无边界 TODO；Root frame 存储通过 `ContextRuntimeMemory` helper 访问。
- duplication and consolidation: BusinessFunction 配置错误 marker 在 Python 和 Java 各保留一份，属于跨运行时重复但当前可接受；后续如共享错误码协议可再收敛。
- complexity and abstraction: runtime memory 独立模块化，Root graph 只负责调用 begin / commit / abandon；当前 Root graph 仍偏集中，后续 Phase 2/3 可继续拆投影策略。
- error handling and edge cases: empty memory bootstrap、revision > 0 忽略 external recent、raw tool message 过滤、busy 返回、non-recoverable function error 停止重试均已覆盖。
- readability and maintainability: 新增代码命名和字段与设计文档一致；关键兼容点有注释。
- critical logic documentation: 实施计划、BUG 文档、验收记录已回写 Phase 1 实现和测试证据。
- contract and compatibility: Root 不再优先消费 `_visible_recent_conversation`；非 Root / 迁移路径仍保留兼容 fallback。
- test alignment: Python 全量测试、scripted E2E、两个 Java 目标测试均已通过，覆盖改动主路径。
- release readiness: 可进入 Phase 1 验收；Phase 3 前仍需承认同会话执行中追加消息只返回 busy，不排队。

## Findings

未发现阻断 Phase 1 合入的问题。

## Risks / Follow-ups

- 多进程部署时，当前进程内 `contextId` lock 不能替代文件锁或外部锁服务。
- Phase 1 对同会话执行中追加消息返回 busy；真正的伪并发排队需 Phase 3 完成。
- runtime memory 目前只做固定窗口裁剪，不做 head-tail summarizer；长会话压缩需 Phase 4。
- BusinessFunction 配置错误 marker 后续应考虑沉淀为跨 Java/Python 的稳定错误码契约。

## Recommended Next Skills

- `foggy-acceptance-signoff`
- Phase 2/3 开发前可再次使用 `plan-evaluator` 或轻量质量检查收口实现边界。

## Decision

decision: ready-with-risks

Phase 1 实现质量可以进入功能验收。残余风险均已在设计中列为后续阶段范围，不阻断当前阶段签收。
