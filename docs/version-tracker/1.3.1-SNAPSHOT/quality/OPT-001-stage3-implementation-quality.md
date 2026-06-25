---
quality_scope: feature
quality_mode: pre-coverage-audit
version: v1.0
tracked_version: 1.3.1-SNAPSHOT
target: OPT-001-stage3-provider-state-schema
status: reviewed
decision: ready-with-risks
reviewed_by: Codex
reviewed_at: 2026-06-25
follow_up_required: yes
---

# OPT-001 Stage 3 Implementation Quality Gate

## Background

本质量门覆盖 `OPT-001` Stage 3 Provider 状态 schema 化实现，重点检查：

- `providerStateJson` / `taskStateJson` 是否通过统一 codec 写入 `schemaVersion` 与 `providerType`。
- legacy JSON、坏 JSON、未知字段保留、空值移除是否有一致策略。
- Claude、Codex、Gemini、LangGraph 的 Provider 状态迁移是否保持既有恢复、checkpoint、context 和诊断字段兼容。
- `DispatchTaskDTO` 对 schema v1 `taskStateJson` 的直接投影是否已被回归锁定。

## Check Basis

参考文档：

- `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
- `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage3-provider-state-schema.md`
- `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-method-responsibility-inventory.md`

代码检查范围：

- `navigator-common/src/main/java/com/foggy/navigator/common/util/ProviderStateCodec.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/UnifiedSessionTaskProjectionService.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java`
- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java`
- `addons/gemini-worker-agent/src/main/java/com/foggy/navigator/gemini/worker/service/GeminiTaskService.java`
- `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphTaskService.java`
- 对应 Provider 和 session 单测。

验证命令：

```powershell
mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"
mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am
```

结果：

- `TaskDispatchFacadeTest` 定向回归：54 tests，0 failures，0 errors，0 skipped。
- Stage 3 受影响 Java reactor：219 suites / 1504 tests，0 failures，0 errors，0 skipped。
- 轻量静态扫描未发现新增 `TODO`、`FIXME`、`System.out`、`printStackTrace` 或 `console.log`。

## Changed Surface

Stage 3 已完成以下实现边界：

- 新增 `ProviderStateCodec`，集中定义 `schemaVersion=1`、`providerType` 和核心字段常量，并统一 legacy/bad JSON/unknown field/null removal 语义。
- `TaskDispatchFacade` 写入 context、diagnostic metadata 时通过共享 codec 合并 `taskStateJson`。
- Claude/Codex/Gemini 的 `providerStateJson` 读写迁移到共享 codec。
- Claude/Codex/Gemini/LangGraph 的 `taskStateJson` 写入迁移到共享 codec，写入时保留既有未知字段。
- `DispatchTaskDTO` schema v1 直接投影已补 `TaskDispatchFacadeTest#toDispatchTaskDTO_readsSchemaVersionedTaskStateProviderFields`。

本阶段未改变 REST / OpenAPI / SDK payload，也未改变 `DispatchTaskDTO` 对外字段。

## Quality Checklist

| Dimension | Result | Evidence / Risk |
| --- | --- | --- |
| Scope conformance | pass | 实现聚焦 Provider state codec 与读写迁移，未扩散到 UI、API payload 或外部 SDK 契约。 |
| Code hygiene | pass | 共享 codec 替换 provider 私有 JSON helper；静态扫描未发现调试输出或新增 TODO/FIXME。 |
| Duplication / consolidation | pass | provider/session/task state 的 schema 标记、坏 JSON 降级、未知字段保留和空值移除已集中到 `ProviderStateCodec`。 |
| Complexity / abstraction | pass-with-risk | 状态读写规则已收敛；Provider service 仍偏大，LangGraph worker session endpoints 仍留在 service 内。 |
| Error handling | pass | codec 对 null/blank/bad JSON 按空状态降级，写入时保留未知字段，空值按删除处理。 |
| Readability | pass-with-risk | 核心字段已有常量；`UnifiedSessionTaskProjectionService` 仍按普通 Map/string key 投影读取，typed envelope 仍是后续项。 |
| Contract compatibility | pass | schema v1 保留同名业务 key，`DispatchTaskDTO` 直接投影回归已覆盖 `codexThreadId`、`contextId`、`checkpoints` 和 `fileCheckpointingEnabled`。 |
| Docs / writeback | pass | Stage 3 workitem、总治理 workitem、质量门和覆盖审计均已回写。 |
| Test alignment | pass | 覆盖共享 codec、session 写入/投影、provider session state、provider task state 和受影响 reactor 回归。 |
| Release readiness | ready-with-risks | Stage 3 可进入覆盖审计；剩余风险为非阻断架构收敛项。 |

## Findings

### Blocking Findings

未发现阻断进入测试覆盖审计的实现缺陷。

### Non-blocking Findings

1. `UnifiedSessionTaskProjectionService` 当前仍按普通 Map 与字符串 key 读取 `taskStateJson`。schema v1 保留同名业务 key，因此兼容性成立；后续引入 typed constants / typed envelope 前应继续保留直接投影回归。
2. LangGraph worker session endpoints 仍位于 `LanggraphTaskService`，本阶段只完成统一任务 `taskStateJson` 中 context/state 字段的 schema 化。
3. `GeminiTaskService` 的 `JsonSupport` 不再服务 task state 写入路径，后续 cleanup 前需要做模块内引用确认。
4. `TaskQueryProvider` 端口仍偏宽，查询、生命周期、恢复和 worker session passthrough 尚未拆成更窄 SPI。

## Risks / Follow-ups

- 继续推进 typed provider state envelope，减少投影层对字符串 key 的依赖。
- 拆分 LangGraph worker session endpoint 与 task state schema 相关逻辑，降低 provider service 复杂度。
- 评估 `TaskQueryProvider` lifecycle/recovery/worker-session 窄端口拆分顺序。
- 若清理 Gemini 私有 JSON helper，需要先确认非 task state 写入路径是否仍有引用。

## Recommended Next Skills

- `foggy-test-coverage-audit`：下一步执行，审计 Stage 3 requirement、acceptance item 与测试证据映射。
- `foggy-acceptance-signoff`：覆盖审计通过后可用于 Stage 3 功能级验收签收。
- `plan-evaluator`：可选，用于比较下一步先做 Stage 4 SSE 边界、LangGraph endpoint 拆分，还是 `TaskQueryProvider` 窄端口。

## Decision

decision: `ready-with-risks`

Stage 3 核心实现质量可以进入测试覆盖审计。当前剩余问题不阻断 schema v1 provider state 验收，但需要进入后续规划：typed envelope、LangGraph worker session endpoint 拆分、Gemini JSON helper cleanup 和 `TaskQueryProvider` 端口收敛。
