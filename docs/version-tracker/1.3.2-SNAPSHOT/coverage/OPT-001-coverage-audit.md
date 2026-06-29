---
audit_scope: feature
audit_mode: pre-acceptance-check
version: 1.3.2-SNAPSHOT
target: OPT-001-codex-biz-route-readiness
status: reviewed
conclusion: ready-for-acceptance
reviewed_by: Codex
reviewed_at: 2026-06-29
follow_up_required: no
---

# Test Coverage Audit

## Background

- 审计对象：`OPT-001: Codex Biz Route Readiness`
- 当前阶段：implementation quality gate passed, pre-acceptance check
- 审计目标：确认 workitem acceptance criteria 和主要风险点都有可复核证据，可进入正式验收签收。

## Audit Basis

- requirement: `docs/version-tracker/1.3.2-SNAPSHOT/workitems/OPT-001-codex-biz-route-readiness.md`
- implementation plan: workitem `Implementation Plan`
- progress: workitem `Progress Tracking`
- bug work items: N/A
- acceptance basis: workitem `Acceptance Criteria`
- test records:
  - `npm --prefix tools/codex-agent-worker run typecheck`: pass, 2026-06-29
  - `npm --prefix tools/codex-agent-worker test`: pass, 70 tests, 2026-06-29
  - `mvn test -pl navigator-common -am "-Dtest=ProviderRouteRegistryTest" ...`: pass, 9 tests, 2026-06-29
  - `mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest" ...`: pass, 59 tests, 2026-06-29
  - `mvn test -pl addons/codex-worker-agent -am "-Dtest=CodexBizTaskProviderTest,CodexTaskServiceTest,CodexStreamRelayTest,CodexWorkerClientTest,CodexTaskControllerTest" ...`: pass, 44 tests, 2026-06-29
  - `git diff --check`: pass, 2026-06-29
- manual evidence:
  - Workitem records prior PowerShell smoke helper parse check.
  - Workitem records prior local Worker health smoke with `bizHomeConfigured=True` and `bizReady=True`.
  - Workitem records prior live actor A/B smoke and actor A resume session reuse.

## Coverage Matrix

| Item | Risk | Unit | Integration | E2E | Playwright | Manual | Evidence Path | Coverage |
|------|------|------|-------------|-----|------------|--------|---------------|----------|
| AC-1: `1.3.2-SNAPSHOT` 文档入口和 workitem 已创建 | minor | no | no | no | no | yes | `docs/version-tracker/1.3.2-SNAPSHOT/README.md`; `workitems/OPT-001-codex-biz-route-readiness.md` | covered |
| AC-2: 调用契约明确 provider/model/cwd/account/policy/session/context 语义 | major | no | no | no | no | yes | workitem `Confirmed Invocation Contract`; `tools/codex-agent-worker/docs/upstream-integration.md` | covered |
| AC-3: session routing 覆盖 Codex Biz create/resume/model compatibility/metadata | critical | yes | no | no | no | no | `TaskDispatchFacadeTest`; `mvn test -pl session-module ...`: 59 tests | covered |
| AC-4: provider route registry 允许 `codex-biz-worker` 独立 provider route | major | yes | no | no | no | no | `ProviderRouteRegistryTest`; `mvn test -pl navigator-common ...`: 9 tests | covered |
| AC-5: Java addon 覆盖 provider filter、参数归一化、Worker body passthrough 和 controller entry | critical | yes | no | no | no | no | `CodexBizTaskProviderTest`; `CodexTaskServiceTest`; `CodexWorkerClientTest`; `CodexTaskControllerTest`; addon Maven command: 44 tests | covered |
| AC-6: Worker `/health` 暴露非敏感 scoped home readiness | major | yes | no | no | no | yes | `tools/codex-agent-worker/tests/health.test.ts`; prior smoke helper health check | covered |
| AC-7: 缺少 `CODEX_BIZ_HOME_ROOT` 时返回稳定错误 | major | yes | no | no | no | yes | `sdk-wrapper.test.ts`; `query.ts` stable error handling; workitem test status | covered |
| AC-8: smoke helper 支持 actor A/B scoped home，live query 为 opt-in | major | no | no | no | no | yes | `tools/codex-agent-worker/scripts/codex-biz-smoke.ps1`; prior live actor A/B evidence in workitem | covered |
| AC-9: execution check-in 记录测试命令、结果、未跑项和剩余风险 | minor | no | no | no | no | yes | workitem `Execution Check-in` | covered |
| AC-10: 无 UI 变更，体验验证明确 N/A | minor | no | no | no | no | yes | workitem `Experience Progress` | covered |

## Evidence Summary

- 已有自动化测试：
  - Worker TypeScript typecheck: pass.
  - Worker unit tests: pass, 70 tests.
  - Provider route registry regression: pass, 9 tests.
  - Session routing regression: pass, 59 tests.
  - Codex addon regression: pass, 44 tests.
- 已有手工验证：
  - Prior PowerShell smoke helper parse check.
  - Prior local Worker health smoke.
  - Prior live actor A/B smoke and actor A resume reuse.
- 已有回归保护：
  - `codex-biz-worker` explicit provider route and `OPENAI_CODEX` compatibility.
  - Session-bound resume provider precedence and metadata passthrough.
  - Java addon Biz provider filter isolation and parameter alias normalization.
  - Worker scoped home readiness and stable missing-root error.
  - Plain Codex Worker body not polluted by Biz-only fields.

## Gaps

- blocking gaps: none
- non-blocking gaps:
  - Live actor A/B smoke was not rerun on 2026-06-29 because it is explicitly opt-in and may perform real Codex calls. Existing 2026-06-28 workitem evidence is sufficient for feature acceptance; target-environment live smoke remains a deployment preflight item.

## Recommended Next Skills

- `integration-test`: not needed before acceptance; current Java and Worker regression evidence covers the feature risk surface.
- `playwright-cli`: not applicable; no UI surface changed.
- `foggy-bug-regression-workflow`: not needed; no uncovered defect found.
- `foggy-acceptance-signoff`: proceed.
- `plan-evaluator`: not needed; no coverage-level dispute identified.

## Conclusion

- conclusion: ready-for-acceptance
- can_enter_acceptance: yes
- follow_up_required: no
