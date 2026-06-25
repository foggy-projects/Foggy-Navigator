---
audit_scope: feature
audit_mode: pre-acceptance-check
version: 1.3.1-SNAPSHOT
target: OPT-001-stage6-task-query-provider-port-split
status: reviewed
conclusion: ready-with-gaps
reviewed_by: Codex
reviewed_at: 2026-06-25
follow_up_required: yes
---

# Test Coverage Audit

## Background

- 审计对象：OPT-001 Stage 6 `TaskQueryProvider` 窄端口治理。
- 当前阶段：实现质量门已通过，准备进入功能级验收。
- 审计目标：确认窄端口 SPI、session 调用点收窄、Provider 兼容性和既有任务行为有足够自动化证据承接。

## Audit Basis

- requirement: `workitems/OPT-001-java-architecture-risk-governance.md`
- implementation plan: `workitems/OPT-001-stage6-task-query-provider-port-split.md`
- quality gate: `quality/OPT-001-stage6-implementation-quality.md`
- acceptance basis: Stage 6 workitem acceptance criteria
- test records:
  - `mvn test -pl session-module -am "-Dtest=TaskQueryProviderRegistryTest,TaskDispatchFacadeTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: 62 tests pass.
  - `mvn test -pl navigator-spi,session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am`: affected reactor pass; Surefire XML total 220 reports / 1520 tests / 0 failures / 0 errors / 0 skipped.
- manual evidence: 本审计记录和实现质量门的代码路径核对。

## Coverage Matrix

| Item | Risk | Unit | Integration | E2E | Playwright | Manual | Evidence Path | Coverage |
|------|------|------|-------------|-----|------------|--------|---------------|----------|
| AC-1: 窄端口 SPI 已定义且 `TaskQueryProvider` 兼容旧实现 | major | yes | no | no | no | yes | `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/*Provider.java`; affected reactor test command | covered |
| AC-2: session 侧调用点开始依赖窄端口类型 | major | yes | no | no | no | yes | `TaskDispatchFacadeTest`; `TaskQueryProviderRegistryTest`; `TaskOperationRouter` / `TaskDispatchFacade` review | covered |
| AC-3: Provider 实现不需要批量重写 | critical | yes | no | no | no | yes | affected reactor: Claude/Codex/Gemini/LangGraph worker modules pass | covered |
| AC-4: create/resume/list/search/worker session 行为不变 | critical | yes | no | no | no | yes | `TaskDispatchFacadeTest` 54 tests; affected reactor 1520 tests pass | covered |
| AC-5: registry typed views 与 capability fallback 有自动化覆盖 | major | yes | no | no | no | yes | `TaskQueryProviderRegistryTest` 8 tests | covered |
| AC-6: 外部 REST / OpenAPI / SDK payload 不变化 | major | yes | no | no | no | yes | Facade 行为回归 + no controller/schema changes review | covered |

## Evidence Summary

- 已有自动化测试：
  - `TaskQueryProviderRegistryTest` 覆盖 capability filtering、legacy fallback、lookup/listing/worker-session typed views、command provider lookup 和按 task 归属查找。
  - `TaskDispatchFacadeTest` 覆盖任务创建、direct provider route、resume、cancel、delete、list/search、worker session 委派等既有核心行为。
  - affected reactor 覆盖 `navigator-spi` 编译兼容和 Claude/Codex/Gemini/LangGraph worker provider 回归。
- 已有手工验证：
  - 质量门核对新增 SPI、registry、Router、Facade、Resolver 和测试改动面。
- 已有回归保护：
  - session focused regression 与 provider affected reactor 均通过，能防止聚合接口兼容性、provider 编译和 Facade 行为回归。

## Gaps

- 未补 REST / OpenAPI / SDK 层 E2E：本阶段未改变 controller、payload 或 endpoint contract，缺口不阻断验收。
- 未覆盖“Provider 直接实现某个独立窄端口而不实现 `TaskQueryProvider`”的接入形态：该形态不在本阶段范围，当前 Spring 注入仍以兼容聚合接口为入口。
- `TaskCommandProvider#cancelTask` deprecated 兼容方法仍保留，已有现有回归保护；彻底移除需另起兼容迁移项。

## Recommended Next Skills

- `integration-test`: 当前不需要；Stage 6 未改变 API 或跨进程协议。
- `playwright-cli`: 当前不需要；无前端交互变化。
- `foggy-bug-regression-workflow`: 当前不需要；未发现 BUG 修复项。
- `foggy-acceptance-signoff`: 建议执行 Stage 6 功能级验收签收。
- `plan-evaluator`: 不需要；测试层级与改动风险匹配。

## Conclusion

- conclusion: `ready-with-gaps`
- can_enter_acceptance: yes
- follow_up_required: yes
