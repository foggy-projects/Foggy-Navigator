---
audit_scope: feature
audit_mode: pre-acceptance-check
version: 1.3.1-SNAPSHOT
target: OPT-001-stage9-langgraph-worker-session-split
status: reviewed
conclusion: ready-with-gaps
reviewed_by: Codex
reviewed_at: 2026-06-25
follow_up_required: yes
---

# Test Coverage Audit

## Background

- 审计对象：OPT-001 Stage 9 LangGraph worker-session 端口拆分。
- 当前阶段：实现质量门已完成，准备进入功能级验收。
- 审计目标：确认 LangGraph worker-session 独立 provider、capability 迁移、payload 兼容、session facade 独立 worker-session provider 接入和 task lifecycle 回归有足够自动化证据承接。

## Audit Basis

- requirement: `workitems/OPT-001-java-architecture-risk-governance.md`
- implementation plan: `workitems/OPT-001-stage9-langgraph-worker-session-split.md`
- quality gate: `quality/OPT-001-stage9-implementation-quality.md`
- acceptance basis: Stage 9 workitem acceptance criteria
- test records:
  - `mvn test -pl addons/langgraph-biz-worker -am "-Dtest=LanggraphWorkerSessionQueryServiceTest,LanggraphTaskServiceTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: 26 tests pass.
  - `mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest,TaskQueryProviderRegistryTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: 64 tests pass.
  - `mvn test -pl session-module,addons/langgraph-biz-worker -am`: affected reactor pass; Surefire XML total 162 reports / 1148 tests / 0 failures / 0 errors / 0 skipped.
  - `git diff --check`: no whitespace errors; only CRLF normalization warnings.
- manual evidence: 本审计记录和实现质量门的代码路径核对。

## Coverage Matrix

| Item | Risk | Unit | Integration | E2E | Playwright | Manual | Evidence Path | Coverage |
|------|------|------|-------------|-----|------------|--------|---------------|----------|
| AC-1: `LanggraphTaskService` 不再声明 worker-session capabilities | major | yes | no | no | no | yes | `LanggraphTaskService#getCapabilities` source review; LangGraph focused regression | covered |
| AC-2: `LanggraphWorkerSessionQueryService` 独立实现 list/count/messages/sync | critical | yes | no | no | no | yes | `LanggraphWorkerSessionQueryServiceTest` behavior cases | covered |
| AC-3: worker-session Map payload 字段保持兼容 | critical | yes | no | no | no | yes | `lists_sessions_from_unified_session_store`; `returns_paginated_session_messages` | covered |
| AC-4: worker/session ownership 校验语义保持 not found 兼容 | major | yes | no | no | no | yes | `rejects_worker_owned_by_other_user`; existing facade worker-not-found fallback cases | covered |
| AC-5: `TaskDispatchFacade` 可使用独立 worker-session provider | critical | yes | no | no | no | yes | `listWorkerSessions_usesDedicatedWorkerSessionProviderList` | covered |
| AC-6: LangGraph task lifecycle 行为未被拆分影响 | critical | yes | no | no | no | yes | `LanggraphTaskServiceTest`; affected reactor 1148 tests | covered |

## Evidence Summary

- 已有自动化测试：
  - `LanggraphWorkerSessionQueryServiceTest` 覆盖 provider capabilities、session list 去重取最新、message count、message pagination、sync local projection total 和 worker ownership 拒绝。
  - `LanggraphTaskServiceTest` 继续覆盖 create task、recent conversation、task state projection、status transitions、cancel/delete 等任务生命周期行为。
  - `TaskDispatchFacadeTest` 新增独立 `WorkerSessionQueryProvider` 接入回归，并保留现有 worker-not-found fallback 与 sync/list/message fan-out 回归。
  - 受影响 reactor 覆盖 `navigator-common`、`navigator-spi`、`agent-framework`、`user-auth-module`、`session-module`、`business-agent-module`、`addons/langgraph-biz-worker`。
- 已有手工验证：
  - `rg` 核对 worker-session 方法只存在于新 service 与测试中。
  - 质量门核对 `SessionMessageRepository` 仍被 task service 主链路使用，避免误删依赖。
- 已有回归保护：
  - focused regression 合计 90 tests 通过。
  - affected reactor Surefire XML 合计 1148 tests 通过，能防止 LangGraph task lifecycle、session facade 和 provider SPI 兼容路径回归。

## Gaps

- 未新增完整 Spring ApplicationContext 启动测试验证两个 LangGraph provider bean 的真实注入列表；当前通过编译、构造单测和 affected reactor 间接覆盖，不阻断验收。
- 未补 REST / OpenAPI / SDK 层 E2E；本阶段未改变 controller path、外部 payload 或前端交互，缺口不阻断验收。
- 未将 worker-session Map payload 改为 typed DTO；本阶段目标是职责拆分，不改变外部字段。
- 未跑根仓所有 Maven 模块全量测试；本次改动集中在 `session-module` 与 `addons/langgraph-biz-worker`，未改 SPI 签名或外部契约，受影响 reactor 全量已覆盖依赖链和相关行为。

## Recommended Next Skills

- `integration-test`: 当前不需要；Stage 9 未改变 REST API 或跨进程协议。
- `playwright-cli`: 当前不需要；无前端交互变化。
- `foggy-bug-regression-workflow`: 当前不需要；未发现验收阻断 BUG。
- `foggy-acceptance-signoff`: 建议执行 Stage 9 功能级验收签收。
- `plan-evaluator`: 可用于后续 Stage 10 比较 Provider 独立 bean 迁移与 strictly typed listing method 的优先级；当前不阻断验收。

## Conclusion

- conclusion: `ready-with-gaps`
- can_enter_acceptance: yes
- follow_up_required: yes
