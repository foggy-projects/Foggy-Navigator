---
audit_scope: feature
audit_mode: pre-acceptance-check
version: 1.3.1-SNAPSHOT
target: OPT-001 Stage 18 Task Command Cancel Direct Method
status: reviewed
conclusion: ready-with-gaps
reviewed_by: Codex
reviewed_at: 2026-06-26
follow_up_required: yes
---

# Test Coverage Audit

## Background

- 审计对象：Stage 18 provider command cancel direct method 收敛。
- 当前阶段：实现质量门已完成，准备进入功能级验收。
- 审计目标：确认 direct cancel 契约、legacy compatibility、session provider-route、内置 provider 行为和 A2A 非目标边界已有足够证据。

## Audit Basis

- requirement: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
- implementation plan: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage18-task-command-cancel-direct-method.md`
- progress: Stage 18 workitem `Progress Tracking`
- bug work items: N/A
- acceptance basis: Stage 18 workitem `Acceptance Criteria`
- test records:
  - targeted regression command in Stage 18 workitem `Verification Plan`
  - affected reactor command in Stage 18 workitem `Verification Plan`
  - targeted Surefire XML summary: 11 reports / 159 tests / 0 failures / 0 errors / 0 skipped
  - affected Surefire XML summary: 223 reports / 1545 tests / 0 failures / 0 errors / 0 skipped
- manual evidence:
  - `rg -n "provider\.cancelTask\(" session-module/src/main/java` no matches
  - `rg -n "cancelTaskDirect\(" ...` confirms direct method and route usage
  - `TaskCommandProvider` fixed-string scan confirms no `forRemoval=true` and one `forRemoval=false` legacy annotation
  - `git diff --check` no whitespace error, CRLF normalization warnings only

## Coverage Matrix

| Item | Risk | Unit | Integration | E2E | Playwright | Manual | Evidence Path | Coverage |
|------|------|------|-------------|-----|------------|--------|---------------|----------|
| Stage 18 AC-1 `TaskCommandProvider#cancelTaskDirect(String, String)` exists and is not deprecated | major | yes | no | no | no | yes | `TaskProviderLegacyContractTest`; `TaskCommandProvider.java`; direct method static scan | covered |
| Stage 18 AC-2 legacy `TaskCommandProvider#cancelTask(String, String)` remains deprecated with `forRemoval=false` | major | yes | no | no | no | yes | `TaskProviderLegacyContractTest`; fixed-string annotation scan | covered |
| Stage 18 AC-3 session provider cancel route invokes `cancelTaskDirect` | critical | yes | yes | no | no | yes | `TaskDispatchFacadeTest`; `TaskOperationRouter.java`; `provider.cancelTask(` static scan no matches | covered |
| Stage 18 AC-4 built-in providers move real cancel behavior to direct method and keep legacy wrapper | critical | yes | yes | no | no | yes | `ClaudeTaskServiceAuthTest`, `CodexTaskServiceTest`, `CodexBizTaskProviderTest`, `GeminiTaskServiceAuthResolutionTest`, `LanggraphTaskServiceTest`; direct method static scan | covered |
| Stage 18 AC-5 A2A cancel chain remains semantically unchanged | major | yes | yes | no | no | yes | `TaskDispatchFacadeTest` A2A route still verifies `agent.cancelTask`; A2A SPI untouched; LangGraph inner A2A service call updated only internally | covered |
| Stage 18 AC-6 provider/service internal direct calls no longer depend on legacy cancel | major | yes | no | no | no | yes | `CodexBizTaskProviderTest`, `LanggraphWorkerInnerA2aAgentTest`; `cancelTaskDirect(` static scan | covered |
| Stage 18 regression boundary across affected modules | critical | yes | yes | no | no | no | affected reactor 223 reports / 1545 tests pass | covered |
| UI/experience validation | minor | no | no | no | no | N/A | pure Java SPI compatibility governance | covered |

## Evidence Summary

- 已有自动化测试：
  - targeted regression：`TaskProviderLegacyContractTest`、`TaskDispatchFacadeTest`、`TaskQueryProviderRegistryTest`、`ClaudeTaskServiceAuthTest`、`CodexTaskServiceTest`、`CodexBizTaskProviderTest`、`GeminiTaskServiceAuthResolutionTest`、`LanggraphTaskServiceTest`、`LanggraphWorkerInnerA2aAgentTest`，Surefire XML 合计 11 reports / 159 tests / 0 failures / 0 errors / 0 skipped。
  - affected reactor：`mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am`，Surefire XML 合计 223 reports / 1545 tests / 0 failures / 0 errors / 0 skipped。
- 已有手工验证：
  - session provider-route legacy cancel static scan 无匹配。
  - direct method static scan 覆盖 SPI、session route、Claude/Codex/Gemini/LangGraph provider 和 LangGraph inner A2A internal service call。
  - `TaskCommandProvider` fixed-string annotation scan 确认无 legacy `forRemoval=true`，存在 expected `forRemoval=false`。
  - `git diff --check` 无 whitespace error。
- 已有回归保护：
  - `TaskProviderLegacyContractTest` 对 direct/legacy SPI cancel 契约形成长期保护。
  - `TaskDispatchFacadeTest` 覆盖 provider-route direct cancel 与 A2A cancel 分流。
  - provider/service 测试继续覆盖各 worker 取消行为和权限边界。

## Gaps

- gap 1: 未运行根目录仓库级全量 `mvn test`；当前以 direct affected reactor 覆盖 Stage 18 涉及模块及其 `-am` 依赖。
- gap 2: 外部插件、SDK、非本仓调用方的 legacy `cancelTask(String, String)` 使用情况无法由本仓测试证明，需 release note 和迁移窗口治理。
- gap 3: 未执行 UI / Playwright 验证，因为本阶段不涉及 UI。

## Recommended Next Skills

- `integration-test`: not required for Stage 18；后续 removal 或 REST/A2A 契约变化时再评估。
- `playwright-cli`: N/A，本阶段无 UI。
- `foggy-bug-regression-workflow`: not required，当前未发现 BUG。
- `foggy-acceptance-signoff`: required，依据本审计进入功能级验收。
- `plan-evaluator`: optional，进入 legacy command cancel removal 阶段前评估测试范围与兼容策略。

## Conclusion

- conclusion: `ready-with-gaps`
- can_enter_acceptance: yes
- follow_up_required: yes
