---
type: implementation-plan
version: 1.3.1-SNAPSHOT
ticket: OPT-001-stage3
severity: major
status: signed-off
owner: java-platform
created_at: 2026-06-25
---

# OPT-001 Stage 3: Provider 状态 Schema 化

## 文档作用

- doc_type: workitem
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 将 `providerStateJson` / `taskStateJson` 从隐式 JSON key 迁移到有版本、可测试、可渐进替换的 Provider 状态 codec。

## Background

Stage 1/2 已完成 Provider 路由治理与 `TaskDispatchFacade` 职责边界拆分，并通过功能级验收签收。剩余最高风险是 Provider 私有状态仍散落在各模块的 JSON 字符串读写中：

- `SessionEntity.providerStateJson`
- `SessionTaskEntity.taskStateJson`

当前 Claude、Codex、Gemini、LangGraph Biz 分别有私有 `mergeJsonValue`、`readJsonValue`、`parseTaskStateJson` 或等价逻辑，恢复、回退、checkpoint、contextId 等字段缺少统一 schema/version 约束。

## Target Outcome

- Provider 状态 JSON 写入统一带 `schemaVersion` 和 `providerType`。
- 读取逻辑兼容 legacy JSON，不要求一次性迁移历史数据。
- 当前关键字段有统一常量和 codec 入口：`claudeSessionId`、`codexThreadId`、`geminiSessionId`、`contextId`、`agentTeamsConfigId`、`checkpoints`。
- Provider 内部私有字段继续允许透传，但必须保留未知字段，不能破坏旧数据。
- 完成后再评估是否拆分 `TaskQueryProvider` lifecycle/recovery 窄端口。

## Implementation Plan

### Stage 3.1 - Shared Codec Baseline

- [x] 在 `navigator-common` 新增 `ProviderStateCodec`。
- [x] 定义 schema version、providerType 和核心字段常量。
- [x] 支持 legacy JSON 读取、坏 JSON 降级为空对象、未知字段保留、空值移除、嵌套 checkpoint payload 保留。
- [x] 先让 `TaskDispatchFacade` 在写入 context/diagnostic metadata 时通过共享 codec 合并 `taskStateJson`，开始为新写入状态补 `schemaVersion` / `providerType`。

### Stage 3.2 - Provider Session State Migration

- [x] Codex/Gemini 的 `providerStateJson` 读写迁移到 `ProviderStateCodec`。
- [x] Claude 的 `providerStateJson` 读写迁移到 `ProviderStateCodec`。
- [x] `codexThreadId`、`geminiSessionId` 保持 legacy 兼容读取，并覆盖 schema v1 读取。
- [x] `agentTeamsConfigId`、`claudeSessionId` 保持 legacy 兼容读取。
- [x] Codex/Gemini Provider 服务级单测覆盖旧 JSON、schema v1 JSON、未知字段保留、清空 session id。
- [x] Claude 迁移后补 Provider 服务级单测，覆盖旧 JSON、坏 JSON、未知字段保留、清空 session id。

### Stage 3.3 - Provider Task State Migration

- [x] Claude/Codex/Gemini/LangGraph 的 `taskStateJson` 生成迁移到 `ProviderStateCodec`。
- [x] 复核 `UnifiedSessionTaskProjectionService` 投影读取链路；schema v1 继续保留同名业务 key，当前按普通 Map 读取仍兼容。
- [x] 补充 `DispatchTaskDTO` 对 schema v1 `taskStateJson` 的直接投影回归；legacy JSON 已由既有投影测试覆盖。

### Stage 3.4 - Review / Test / Audit

- [x] 执行受影响模块定向回归。
- [x] 如 Provider 服务迁移超过单模块，执行受影响 Java reactor 回归。
- [x] 执行 `foggy-implementation-quality-gate`。
- [x] 执行 `foggy-test-coverage-audit`。

## Acceptance Criteria

- 新写入的 Provider state JSON 至少在已迁移路径中包含 `schemaVersion=1`。
- legacy JSON 字段仍可读取，不破坏已有 session/task 恢复。
- 坏 JSON 不导致恢复链路异常扩散，按空状态降级。
- 未知字段在 merge 时被保留。
- 清空 Provider session id 时能移除对应字段。
- 单测覆盖共享 codec 与至少一个实际写入路径。

## Progress Tracking

### Development Progress

| Item | Status | Notes |
| --- | --- | --- |
| Stage 3.1 shared codec baseline | done | `ProviderStateCodec` 已新增，并接入 `TaskDispatchFacade` context/diagnostic metadata 写入路径；定向与 session 全量回归已通过。 |
| Stage 3.2 provider session state migration | done | Claude/Codex/Gemini 的 `providerStateJson` 读写已迁移到 `ProviderStateCodec`；legacy/schema v1 读取、未知字段保留、清空 session id 均已有回归覆盖。 |
| Stage 3.3 provider task state migration | done | Claude/Codex/Gemini/LangGraph 的 `taskStateJson` 写入已迁移到 `ProviderStateCodec`；未知字段保留、schema/provider 标记和 context 字段已有服务级回归。 |
| Stage 3.4 review/test/audit closure | done | 已补 schema v1 `DispatchTaskDTO` 直接投影回归，并完成 Stage 3 实现质量门与测试覆盖审计。 |

### Testing Progress

| Scope | Status | Notes |
| --- | --- | --- |
| Shared codec unit test | done | `ProviderStateCodecTest` 5 tests pass；同轮 targeted common 回归含 `ProviderRouteRegistryTest`，`navigator-common` 合计 14 tests pass。 |
| Session facade targeted regression | done | `TaskDispatchFacadeTest` 53 tests pass，覆盖 context/diagnostic metadata 写入 `schemaVersion=1` 与 `providerType`。 |
| Session full reactor regression | done | `mvn test -pl session-module -am` 通过，合计 242 tests。 |
| Codex/Gemini provider state targeted regression | done | `ProviderStateCodecTest` 5 tests、`CodexTaskServiceTest` 20 tests、`GeminiTaskServiceAuthResolutionTest` 8 tests 均通过。 |
| Codex/Gemini addon full reactor regression | done | `mvn test -pl addons/codex-worker-agent,addons/gemini-worker-agent -am` 通过：`navigator-common` 15 tests、`session-module` 242 tests、Codex 57 tests、Gemini 14 tests。 |
| Claude provider state targeted regression | done | `ProviderStateCodecTest` 5、`ClaudeTaskServiceAuthTest` 20、`ClaudeTaskServiceRewindTest` 5、`ClaudeTaskServiceSyncTest` 7、`ConversationConfigServiceTest` 13 tests pass；覆盖 legacy/schema v1、坏 JSON 降级、未知字段保留、Agent Teams 读取/写入和清空 `claudeSessionId`。 |
| Claude addon full reactor regression | done | `mvn test -pl addons/claude-worker-agent -am` 通过，合计 312 tests pass。 |
| Stage 3.3 provider task state targeted regression | done | `ClaudeTaskServiceAuthTest` 21、`CodexTaskServiceTest` 20、`GeminiTaskServiceAuthResolutionTest` 8、`LanggraphTaskServiceTest` 24 tests pass，合计 73 tests。 |
| Affected provider reactor regression | done | `mvn test -pl addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am` 通过，Surefire XML 合计 1503 tests，0 failures，0 errors，0 skipped。 |
| Stage 3.4 DispatchTaskDTO schema v1 projection regression | done | `mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"` 通过：54 tests，0 failures，0 errors，0 skipped。 |
| Stage 3 affected Java reactor regression | done | `mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am` 通过：219 suites / 1504 tests，0 failures，0 errors，0 skipped。 |

### Experience Progress

experience: N/A

原因：本阶段为 Java 后端状态 schema 与 codec 治理，不涉及 UI 页面或交互变更。

## Execution Checklist

- [x] Stage 1/2 acceptance 已签收，具备进入 Stage 3 的前置条件。
- [x] Stage 3 子计划已落档。
- [x] Stage 3.1 共享 codec 基础实现已提交到工作区。
- [x] Stage 3.1 测试执行并回写。
- [x] Stage 3.1 execution-checkin 回写。
- [x] Stage 3.2 Codex/Gemini provider session state 读写点复核完成。
- [x] Stage 3.2 Codex/Gemini provider session state 迁移测试执行并回写。
- [x] Stage 3.2 Codex/Gemini execution-checkin 回写。
- [x] Stage 3.2 Claude provider session state 迁移前复核 checkpoint / Agent Teams / rewind 状态边界。
- [x] Stage 3.2 Claude provider session state 迁移测试执行并回写。
- [x] Stage 3.2 Claude execution-checkin 回写。
- [x] Stage 3.3 Provider `taskStateJson` 写入点 review 完成。
- [x] Stage 3.3 Claude/Codex/Gemini/LangGraph `taskStateJson` 迁移完成。
- [x] Stage 3.3 定向回归与受影响 Provider reactor 回归完成。
- [x] Stage 3.3 execution-checkin 回写。
- [x] Stage 3.4 `DispatchTaskDTO` schema v1 直接投影回归完成。
- [x] Stage 3.4 实现质量门完成。
- [x] Stage 3.4 测试覆盖审计完成。
- [x] Stage 3 功能级验收签收完成。

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-25
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage3-provider-state-schema-acceptance.md
- blocking_items: none
- follow_up_required: yes

## Execution Check-in

### 2026-06-25 - Stage 3.1 Shared Provider State Codec

已完成：

- 在 `navigator-common` 新增 `ProviderStateCodec`，定义 `schemaVersion=1`、`providerType` 和核心字段常量。
- codec 支持 legacy JSON 读取、坏 JSON 降级为空对象、未知字段保留、空值移除和嵌套 checkpoint payload 保留。
- `TaskDispatchFacade` 的 context/diagnostic metadata 写入路径已改为通过共享 codec 合并 `taskStateJson`，新写入状态带 schema version 与 providerType。
- 新增 `ProviderStateCodecTest`，并补充 `TaskDispatchFacadeTest` 对 `schemaVersion` / `providerType` 的回归断言。

代码触点：

- `navigator-common/pom.xml`
- `navigator-common/src/main/java/com/foggy/navigator/common/util/ProviderStateCodec.java`
- `navigator-common/src/test/java/com/foggy/navigator/common/util/ProviderStateCodecTest.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java`

测试证据：

- `mvn test -pl navigator-common,session-module -am "-Dtest=ProviderStateCodecTest,ProviderRouteRegistryTest,TaskDispatchFacadeTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：`navigator-common` 14 tests pass，`session-module` 53 tests pass。
- `mvn test -pl session-module -am`：242 tests pass。

剩余风险：

- Claude/Codex/Gemini/LangGraph Provider 内部 `providerStateJson` / `taskStateJson` 私有读写尚未迁移，Stage 3.1 只完成共享 codec 和一个安全写入路径接入。
- `navigator-common` 新增 Jackson databind 依赖；当前合理性是 JSON codec 归 common 所有，后续若 common 依赖边界收紧，需要同步复核。
- `TaskQueryProvider` 端口拆分与 typed provider envelope 仍是 Stage 3 之后的后续架构项。

下一步：

- Stage 3.2 先迁移 Codex/Gemini 的 `providerStateJson` 读写，再处理 Claude 的 `agentTeamsConfigId`、checkpoint 和 rewind 相关复杂状态。

### 2026-06-25 - Stage 3.2 Codex/Gemini Provider Session State Migration

已完成：

- Codex `resumeTask` 从 `SessionEntity.providerStateJson` 读取 `codexThreadId` 时改用 `ProviderStateCodec`，兼容 legacy JSON 和 schema v1 JSON。
- Codex `syncSessionProjection` 与 rewind 清空 `codexThreadId` 改用 `ProviderStateCodec.mergeSessionValue`，写入时保留未知字段并补 `schemaVersion=1` / `providerType`。
- Gemini `resumeTask` 从 `providerStateJson` 读取 `geminiSessionId` 时改用 `ProviderStateCodec`，兼容 schema v1 JSON。
- Gemini session entity projection 写入 `geminiSessionId` 时改用 `ProviderStateCodec.mergeSessionValue`，写入时保留未知字段并补 schema。
- Codex/Gemini 测试补充 schema 写入、schema v1 读取和未知字段保留断言。

代码触点：

- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java`
- `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexTaskServiceTest.java`
- `addons/gemini-worker-agent/src/main/java/com/foggy/navigator/gemini/worker/service/GeminiTaskService.java`
- `addons/gemini-worker-agent/src/test/java/com/foggy/navigator/gemini/worker/service/GeminiTaskServiceAuthResolutionTest.java`

测试证据：

- `mvn test -pl navigator-common,addons/codex-worker-agent,addons/gemini-worker-agent -am "-Dtest=ProviderStateCodecTest,CodexTaskServiceTest,GeminiTaskServiceAuthResolutionTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：ProviderStateCodec 5 tests、CodexTaskService 20 tests、GeminiTaskServiceAuthResolution 8 tests，全部通过。
- `mvn test -pl addons/codex-worker-agent,addons/gemini-worker-agent -am`：`navigator-common` 15 tests、`session-module` 242 tests、Codex 57 tests、Gemini 14 tests，全部通过。

剩余风险：

- Claude `providerStateJson` 已在后续切片迁移完成；见下一节。
- Codex/Gemini 的 `taskStateJson` 仍保留 provider 私有 JSON helper，等待 Stage 3.3 统一迁移。
- `JsonSupport` 在 Gemini 仍用于 task state JSON；本阶段未触碰。

下一步：

- 执行 Claude provider session state 迁移并补充 legacy/schema/unknown/clear 状态单测；完成情况见下一节。

### 2026-06-25 - Stage 3.2 Claude Provider Session State Migration

已完成：

- Claude `syncLocalSessions` 删除会话过滤、`resumeTask(Map)` 恢复、`syncSessionProjection`、Agent Teams 锁定和 rewind 首轮清理 `claudeSessionId` 的 provider session state 读写切到 `ProviderStateCodec`。
- `ConversationConfigService` 读取/写入 `agentTeamsConfigId` 改为复用共享 codec，写入时保留未知字段并补 `schemaVersion=1` 与 `providerType`。
- 旧 JSON、schema v1 JSON、坏 JSON、未知字段保留、清空 `claudeSessionId` 和 Agent Teams 配置读取/写入均补了 Provider 服务级回归。

代码触点：

- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ConversationConfigService.java`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/ClaudeTaskServiceAuthTest.java`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/ClaudeTaskServiceRewindTest.java`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/ClaudeTaskServiceSyncTest.java`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/ConversationConfigServiceTest.java`

测试证据：

- `mvn test -pl navigator-common,addons/claude-worker-agent -am "-Dtest=ProviderStateCodecTest,ClaudeTaskServiceAuthTest,ClaudeTaskServiceRewindTest,ClaudeTaskServiceSyncTest,ConversationConfigServiceTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：ProviderStateCodec 5、ClaudeTaskServiceAuth 20、ClaudeTaskServiceRewind 5、ClaudeTaskServiceSync 7、ConversationConfigService 13 tests pass。
- `mvn test -pl addons/claude-worker-agent -am`：312 tests pass。

剩余风险：

- Claude `taskStateJson` 仍由 provider 私有 builder 生成，checkpoint/task projection 状态需在 Stage 3.3 统一迁移。
- Codex/Gemini/LangGraph 的 `taskStateJson` 以及 LangGraph worker session/context 状态仍待后续切片治理。
- `TaskQueryProvider` 窄端口拆分与 typed provider envelope 不纳入 Stage 3.2，继续作为后续架构项。

下一步：

- 进入 Stage 3.3，优先迁移 Provider 内部 `taskStateJson` 写入/读取；建议先从 Claude `buildClaudeTaskStateJson` 与统一投影读取链路开始，再同步 Codex/Gemini/LangGraph。

### 2026-06-25 - Stage 3.3 Provider Task State Migration

Review 发现：

- Claude/Gemini `taskStateJson` 写入每次重建 JSON，缺少 `schemaVersion` / `providerType`，并可能丢失调度层追加的诊断字段。
- Codex/LangGraph 虽会传入既有 JSON，但仍使用各自本地 parser/writer，与共享 codec 的坏 JSON 降级、空字段删除和 schema 标记规则不一致。
- `UnifiedSessionTaskProjectionService` 仍按普通 Map 读取 task state 业务 key；schema v1 当前保持同名业务 key，因此投影读取行为兼容，typed constants / envelope 可作为后续收敛项。

已完成：

- Claude `syncSessionTask` 改为在既有 `SessionTaskEntity.taskStateJson` 上通过 `ProviderStateCodec.mergeTaskValues` 合并 `contextId`、`agentTeamsConfigId`、`checkpoints`、`dedupKey` 和 `fileCheckpointingEnabled`。
- Codex `buildCodexTaskStateJson` 与 `resolveTaskContextId` 改为复用 `ProviderStateCodec`，删除本地 `parseTaskStateJson` / `readJsonValue`。
- Gemini `buildGeminiTaskStateJson` 改为复用 `ProviderStateCodec.mergeTaskValue`，写入 `geminiSessionId` 时保留既有未知字段。
- LangGraph `buildTaskStateJson` 改为通过 `ProviderStateCodec.mergeTaskValues` 合并 `contextId`、子状态、恢复字段、deadline 和 `structuredOutput`，保留既有任务状态元数据。

代码触点：

- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java`
- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java`
- `addons/gemini-worker-agent/src/main/java/com/foggy/navigator/gemini/worker/service/GeminiTaskService.java`
- `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphTaskService.java`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/ClaudeTaskServiceAuthTest.java`
- `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexTaskServiceTest.java`
- `addons/gemini-worker-agent/src/test/java/com/foggy/navigator/gemini/worker/service/GeminiTaskServiceAuthResolutionTest.java`
- `addons/langgraph-biz-worker/src/test/java/com/foggy/navigator/langgraph/worker/service/LanggraphTaskServiceTest.java`

测试证据：

- `mvn test -pl addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am "-Dtest=ClaudeTaskServiceAuthTest,CodexTaskServiceTest,GeminiTaskServiceAuthResolutionTest,LanggraphTaskServiceTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：73 tests pass。
- `mvn test -pl addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am`：Surefire XML 合计 1503 tests，0 failures，0 errors，0 skipped。

剩余风险：

- `UnifiedSessionTaskProjectionService` 仍按普通 Map 和字符串 key 读取 `taskStateJson`；当前兼容 schema v1，但后续 typed envelope / typed constants 收敛时应补直接投影回归。
- LangGraph worker session endpoints 仍保留在 provider service 内，本轮只治理统一任务 `taskStateJson` 中的 context/state 字段。
- Gemini `JsonSupport` 在 `GeminiTaskService` 中已不再被 task state 写入路径使用，后续 cleanup 可删除前再做模块内引用确认。
- `TaskQueryProvider` 窄端口拆分与 typed provider envelope 仍是 Stage 3 后续架构项。

下一步：

- 补 `DispatchTaskDTO` 对 schema v1 `taskStateJson` 的直接投影回归，然后执行 `foggy-implementation-quality-gate` 与 `foggy-test-coverage-audit` 收口 Stage 3。

### 2026-06-25 - Stage 3.4 Projection Regression / Quality / Coverage Closure

已完成：

- 新增 `TaskDispatchFacadeTest#toDispatchTaskDTO_readsSchemaVersionedTaskStateProviderFields`，用 `ProviderStateCodec.mergeTaskValues` 构造 schema v1 `taskStateJson`。
- 回归验证 schema v1 `taskStateJson` 中的 `codexThreadId`、`contextId`、`checkpoints`、`fileCheckpointingEnabled` 和目录名称可正确投影到 `DispatchTaskDTO`。
- 执行 Stage 3 实现质量门，文档见 `quality/OPT-001-stage3-implementation-quality.md`，decision=`ready-with-risks`。
- 执行 Stage 3 测试覆盖审计，文档见 `coverage/OPT-001-stage3-coverage-audit.md`，conclusion=`ready-with-gaps`，can_enter_acceptance=`yes`。

代码触点：

- `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java`
- `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-001-stage3-implementation-quality.md`
- `docs/version-tracker/1.3.1-SNAPSHOT/coverage/OPT-001-stage3-coverage-audit.md`

测试证据：

- `mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：54 tests pass。
- `mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am`：219 suites / 1504 tests，0 failures，0 errors，0 skipped。

剩余风险：

- `UnifiedSessionTaskProjectionService` 仍按普通 Map/string key 读取 `taskStateJson`；schema v1 已有直接投影回归，typed envelope / typed constants 仍是后续收敛项。
- LangGraph worker session endpoints 仍保留在 provider service 内，后续可单独拆分。
- `TaskQueryProvider` lifecycle/recovery/worker-session 窄端口拆分不在本阶段范围内。

下一步：

- 对 Stage 3 Provider 状态 schema 化切片执行功能级验收签收，或进入下一架构切片规划：Stage 4 SSE 部署边界、LangGraph endpoint 拆分、`TaskQueryProvider` 窄端口收敛三选一。

### 2026-06-25 - Stage 3 Acceptance Signoff

已完成：

- 执行 Stage 3 功能级验收签收。
- 验收记录见 `acceptance/OPT-001-stage3-provider-state-schema-acceptance.md`。
- 签收结论为 `accepted-with-risks`，无阻断项。

签收说明：

- 当前 Provider 状态 schema 化切片满足验收标准，可作为 Stage 3 收口。
- 仍需后续跟进 typed provider envelope、LangGraph worker session endpoint 拆分、`TaskQueryProvider` 窄端口拆分和 Gemini JSON helper cleanup。

下一步：

- 在 Stage 4 SSE 部署边界、LangGraph endpoint 拆分、`TaskQueryProvider` 窄端口收敛中选择下一切片推进。
