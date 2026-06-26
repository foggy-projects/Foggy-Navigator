---
audit_scope: feature
audit_mode: pre-acceptance-check
version: 1.3.1-SNAPSHOT
target: OPT-001-stage10-langgraph-narrow-port-bean
status: reviewed
conclusion: ready-with-gaps
reviewed_by: Codex
reviewed_at: 2026-06-26
follow_up_required: yes
---

# Test Coverage Audit

## Background

- 审计对象：OPT-001 Stage 10 LangGraph narrow port bean migration。
- 当前阶段：实现质量门已完成，准备进入功能级验收。
- 审计目标：确认 LangGraph task service 退出 `TaskQueryProvider` 聚合接口、仅作为 lookup/command 窄端口注册、worker-session provider 独立性和 session 路由兼容有足够测试证据承接。

## Audit Basis

- requirement: `workitems/OPT-001-java-architecture-risk-governance.md`
- implementation plan: `workitems/OPT-001-stage10-langgraph-narrow-port-bean.md`
- quality gate: `quality/OPT-001-stage10-implementation-quality.md`
- acceptance basis: Stage 10 workitem acceptance criteria
- test records:
  - `mvn test -pl addons/langgraph-biz-worker -am "-Dtest=LanggraphTaskServiceTest,LanggraphWorkerSessionQueryServiceTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: 27 tests pass.
  - `mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest,TaskQueryProviderRegistryTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: 64 tests pass.
  - `mvn test -pl session-module,addons/langgraph-biz-worker -am`: affected reactor pass; Surefire XML total 162 reports / 1149 tests / 0 failures / 0 errors / 0 skipped.
  - `git diff --check`: no whitespace errors; only CRLF normalization warnings.
- manual evidence: 本审计记录和实现质量门的代码路径核对。

## Coverage Matrix

| Item | Risk | Unit | Integration | E2E | Playwright | Manual | Evidence Path | Coverage |
|------|------|------|-------------|-----|------------|--------|---------------|----------|
| AC-1: `LanggraphTaskService` 不再实现 `TaskQueryProvider` | critical | yes | no | no | no | yes | `LanggraphTaskServiceTest#exposes_only_supported_task_provider_ports`; source review | covered |
| AC-2: `LanggraphTaskService` 只作为 lookup / command 窄端口注册 | critical | yes | no | no | no | yes | `LanggraphTaskServiceTest#exposes_only_supported_task_provider_ports`; session focused regression | covered |
| AC-3: `LanggraphWorkerSessionQueryService` 仍独立承接 worker-session | major | yes | no | no | no | yes | `LanggraphWorkerSessionQueryServiceTest`; Stage 9 regression retained | covered |
| AC-4: LangGraph task create/lookup/cancel/delete 行为保持兼容 | critical | yes | no | no | no | yes | `LanggraphTaskServiceTest`; affected reactor 1149 tests | covered |
| AC-5: session facade 继续通过窄端口完成 direct create 和 task operation routing | critical | yes | no | no | no | yes | `TaskDispatchFacadeTest`; `TaskQueryProviderRegistryTest` | covered |
| AC-6: 不改变外部 REST / SDK payload | major | no | no | no | no | yes | no controller/form/dto changes; source review | partially-covered |

## Evidence Summary

- 已有自动化测试：
  - `LanggraphTaskServiceTest#exposes_only_supported_task_provider_ports` 明确断言 task service 是 lookup/command，不是 aggregate/listing/worker-session。
  - `LanggraphTaskServiceTest` 保留 task create、recent conversation、projection、status transition、cancel/delete 等行为回归。
  - `LanggraphWorkerSessionQueryServiceTest` 保留 Stage 9 worker-session 独立 provider 行为回归。
  - `TaskDispatchFacadeTest` 与 `TaskQueryProviderRegistryTest` 覆盖 session 侧窄端口路由、lookup/command 分离和 direct provider route。
  - 受影响 reactor 覆盖 `navigator-common`、`navigator-spi`、`agent-framework`、`user-auth-module`、`session-module`、`business-agent-module`、`addons/langgraph-biz-worker`。
- 已有手工验证：
  - `LanggraphTaskService` import / implements 列表已从 `TaskQueryProvider` 改为 `TaskLookupProvider, TaskCommandProvider`。
  - README 和 Stage 10 workitem 已记录 scope、non-goals 和验证计划。

## Gaps

- 未新增完整 Spring ApplicationContext 启动测试来断言真实 bean list 中 LangGraph task service 不再出现在 listing/worker-session 集合；当前通过类型边界单测和 affected reactor 间接覆盖，不阻断验收。
- 未跑 REST / OpenAPI / SDK 层 E2E；本阶段未改 controller、form、DTO 或外部字段，缺口不阻断验收。
- 未扩展到 Claude/Codex/Gemini；这是后续阶段范围，不影响 Stage 10 LangGraph 切片签收。
- 未跑根仓所有 Maven 模块全量测试；改动集中在 `addons/langgraph-biz-worker` 与 session provider injection 兼容边界，受影响 reactor 已覆盖依赖链。

## Recommended Next Skills

- `integration-test`: 当前不需要；Stage 10 未改变 REST API 或跨进程协议。
- `playwright-cli`: 当前不需要；无前端交互变化。
- `foggy-bug-regression-workflow`: 当前不需要；未发现验收阻断 BUG。
- `foggy-acceptance-signoff`: 建议执行 Stage 10 功能级验收签收。
- `plan-evaluator`: 可用于后续阶段比较继续迁移 Claude/Codex/Gemini 与推进 typed listing method 的优先级。

## Conclusion

- conclusion: `ready-with-gaps`
- can_enter_acceptance: yes
- follow_up_required: yes
