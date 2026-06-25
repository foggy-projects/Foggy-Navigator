---
audit_scope: feature
audit_mode: pre-acceptance-check
version: v1.0
tracked_version: 1.3.1-SNAPSHOT
target: OPT-001-stage3-provider-state-schema
status: reviewed
conclusion: ready-with-gaps
reviewed_by: Codex
reviewed_at: 2026-06-25
follow_up_required: yes
---

# OPT-001 Stage 3 Test Coverage Audit

## Background

本审计覆盖 `OPT-001` Stage 3 Provider 状态 schema 化切片：

- Stage 3.1：共享 `ProviderStateCodec` 基线。
- Stage 3.2：Claude/Codex/Gemini `providerStateJson` 迁移。
- Stage 3.3：Claude/Codex/Gemini/LangGraph `taskStateJson` 迁移。
- Stage 3.4：schema v1 `DispatchTaskDTO` 直接投影回归、实现质量门和覆盖审计。

本审计不覆盖 Stage 4 SSE 部署边界、Stage 5 运行配置硬化、`TaskQueryProvider` 窄端口拆分或 LangGraph worker session endpoint 拆分。以上作为后续架构项保留。

## Audit Basis

参考文档：

- `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
- `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage3-provider-state-schema.md`
- `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-001-stage3-implementation-quality.md`

测试证据：

- `navigator-common/target/surefire-reports/com.foggy.navigator.common.util.ProviderStateCodecTest.txt`
- `session-module/target/surefire-reports/com.foggy.navigator.session.service.TaskDispatchFacadeTest.txt`
- `addons/claude-worker-agent/target/surefire-reports/com.foggy.navigator.claude.worker.service.ClaudeTaskServiceAuthTest.txt`
- `addons/codex-worker-agent/target/surefire-reports/com.foggy.navigator.codex.worker.service.CodexTaskServiceTest.txt`
- `addons/gemini-worker-agent/target/surefire-reports/com.foggy.navigator.gemini.worker.service.GeminiTaskServiceAuthResolutionTest.txt`
- `addons/langgraph-biz-worker/target/surefire-reports/com.foggy.navigator.langgraph.worker.service.LanggraphTaskServiceTest.txt`

本轮新增或强化的单测位置：

- `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java`
  - `toDispatchTaskDTO_readsSchemaVersionedTaskStateProviderFields`

## Coverage Matrix

| Requirement / Acceptance Item | Risk | Validation layer | Evidence path | Coverage |
| --- | --- | --- | --- | --- |
| `STAGE3-REQ-1` 新写入 Provider state JSON 包含 `schemaVersion=1` 与 `providerType` | critical | unit-test, reactor-regression | `ProviderStateCodecTest`；`TaskDispatchFacadeTest`；四个 Provider service 测试 | covered |
| `STAGE3-REQ-2` legacy JSON 字段仍可读取，不破坏 session/task 恢复 | critical | unit-test | `ProviderStateCodecTest`；`ClaudeTaskServiceAuthTest`；`CodexTaskServiceTest`；`GeminiTaskServiceAuthResolutionTest` | covered |
| `STAGE3-REQ-3` 坏 JSON 按空状态降级，不扩散恢复异常 | major | unit-test | `ProviderStateCodecTest`；Claude provider session state 测试 | covered |
| `STAGE3-REQ-4` merge 时保留未知字段并支持空值移除 | major | unit-test | `ProviderStateCodecTest`；Claude/Codex/Gemini/LangGraph Provider service 测试 | covered |
| `STAGE3-REQ-5` Provider session id 清空时移除对应字段 | major | unit-test | Claude/Codex/Gemini provider session state 测试 | covered |
| `STAGE3-REQ-6` Claude/Codex/Gemini/LangGraph `taskStateJson` 写入迁移到共享 codec | critical | unit-test, reactor-regression | `ClaudeTaskServiceAuthTest`；`CodexTaskServiceTest`；`GeminiTaskServiceAuthResolutionTest`；`LanggraphTaskServiceTest` | covered |
| `STAGE3-REQ-7` `DispatchTaskDTO` 兼容 schema v1 `taskStateJson` 直接投影 | critical | unit-test | `TaskDispatchFacadeTest#toDispatchTaskDTO_readsSchemaVersionedTaskStateProviderFields` | covered |
| `AC-CONTRACT` REST / OpenAPI / SDK payload 与 `DispatchTaskDTO` 对外字段不变 | critical | unit-test, manual-evidence | schema v1 保留同名业务 key；session facade 投影回归；受影响 Java reactor | covered |
| `AC-DOC` Stage 3 计划、质量门、覆盖审计和总治理文档同步 | minor | manual-evidence | Stage 3 workitem；main OPT workitem；本 quality gate；本 coverage audit | covered |
| `FOLLOW-UP-LG-SESSION` LangGraph worker session endpoint 与 task state schema 逻辑拆分 | major | planning-evidence | Stage 3 quality gate non-blocking finding | partially-covered |
| `FOLLOW-UP-TYPED-ENVELOPE` typed provider state envelope / typed projection constants | major | planning-evidence | Stage 3 quality gate non-blocking finding；schema v1 direct projection regression | partially-covered |

## Evidence Summary

本轮审计前已补齐 Stage 3.3 后留下的直接投影测试缺口：

- 新增 `toDispatchTaskDTO_readsSchemaVersionedTaskStateProviderFields`，验证 schema v1 `taskStateJson` 中的 `codexThreadId`、`contextId`、`checkpoints` 和 `fileCheckpointingEnabled` 可直接投影到 `DispatchTaskDTO`。
- 使用 `ProviderStateCodec.mergeTaskValues` 构造 schema v1 JSON，避免测试只覆盖手写 legacy JSON。
- 同轮验证 directory name 投影未被 schema v1 task state 影响。

最新验证结果：

| Scope | Command summary | Result |
| --- | --- | --- |
| Stage 3.4 session facade 定向回归 | `mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"` | PASS：54 tests，0 failures，0 errors，0 skipped |
| Stage 3 受影响 Java reactor 回归 | `mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am` | PASS：219 suites / 1504 tests，0 failures，0 errors，0 skipped |

历史验证结果已在 workitem 中留存：

- Stage 3.1 shared codec targeted regression：common 14 tests、session 53 tests pass；session full reactor 242 tests pass。
- Stage 3.2 Codex/Gemini provider session state regression：ProviderStateCodec 5、CodexTaskService 20、GeminiTaskServiceAuthResolution 8 tests pass；Codex/Gemini addon full reactor pass。
- Stage 3.2 Claude provider session state regression：ProviderStateCodec 5、ClaudeTaskServiceAuth 20、ClaudeTaskServiceRewind 5、ClaudeTaskServiceSync 7、ConversationConfigService 13 tests pass；Claude addon full reactor 312 tests pass。
- Stage 3.3 provider task state targeted regression：ClaudeTaskServiceAuth 21、CodexTaskService 20、GeminiTaskServiceAuthResolution 8、LanggraphTaskService 24 tests pass；Provider addon reactor 1503 tests pass。

## Gaps

Stage 3 Provider 状态 schema 化切片内未发现阻断验收的测试覆盖缺口。

非阻断覆盖缺口与后续项：

- LangGraph worker session endpoints 仍未作为独立端口拆分，本轮只覆盖统一任务 `taskStateJson` 的 context/state schema 化。
- 投影层仍按普通 Map/string key 读取 schema v1 同名业务字段；当前已有直接投影回归，typed envelope / typed constants 仍需后续设计。
- `TaskQueryProvider` 窄端口拆分不在 Stage 3 范围内；若后续改动 provider lifecycle/recovery/worker session SPI，需要重新跑相关模块定向与 affected reactor。
- 本阶段为 Java 后端状态 schema 治理，不涉及 UI 体验验证或 Playwright/E2E。

## Recommended Next Skills

- `foggy-acceptance-signoff`：用于对 Stage 3 Provider 状态 schema 化切片做正式验收签收。
- `plan-evaluator`：用于评估下一步先推进 Stage 4 SSE 边界、LangGraph endpoint 拆分，还是 `TaskQueryProvider` 窄端口收敛。
- `session-integration-tests`：若下一步进入 API/SSE 级别行为验证，可用于补 L3 集成测试。

## Conclusion

conclusion: `ready-with-gaps`

Stage 3 Provider 状态 schema 化的关键 requirement、acceptance item 与测试证据已经形成可追溯映射。schema v1 直接投影缺口已补齐，受影响 Java reactor 回归通过。

can_enter_acceptance: `yes`

剩余 gap 是非阻断架构收敛项：typed provider state envelope、LangGraph worker session endpoint 拆分和 `TaskQueryProvider` 窄端口拆分。
