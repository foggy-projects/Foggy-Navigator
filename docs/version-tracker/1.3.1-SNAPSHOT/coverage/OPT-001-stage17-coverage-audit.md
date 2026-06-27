---
audit_scope: feature
audit_mode: pre-acceptance-check
version: 1.3.1-SNAPSHOT
target: OPT-001 Stage 17 Legacy Provider Method Deprecation Gate
status: reviewed
conclusion: ready-with-gaps
reviewed_by: Codex
reviewed_at: 2026-06-26
follow_up_required: yes
---

# Test Coverage Audit

## Background

- 审计对象：Stage 17 legacy provider method deprecation gate。
- 当前阶段：实现质量门已完成，准备进入功能级验收。
- 审计目标：确认 deprecation 契约、typed 主链路兼容、生产 fan-out 调用面和受影响模块回归已有足够证据。

## Audit Basis

- requirement: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
- implementation plan: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage17-legacy-provider-method-deprecation.md`
- progress: Stage 17 workitem `Progress Tracking`
- bug work items: N/A
- acceptance basis: Stage 17 workitem `Acceptance Criteria`
- test records:
  - targeted regression command in Stage 17 workitem `Verification Plan`
  - affected reactor command in Stage 17 workitem `Verification Plan`
  - Surefire XML summary: 219 reports / 1528 tests / 0 failures / 0 errors / 0 skipped
- manual evidence:
  - `rg provider\.(...)` static scan no production fan-out legacy calls
  - `rg --fixed-strings '@Deprecated(since = "1.3.1", forRemoval = false)'` found 24 expected annotations
  - `git diff --check` no whitespace error, CRLF normalization warnings only

## Coverage Matrix

| Item | Risk | Unit | Integration | E2E | Playwright | Manual | Evidence Path | Coverage |
|------|------|------|-------------|-----|------------|--------|---------------|----------|
| Stage 17 AC-1 SPI legacy listing methods are deprecated with `since=1.3.1` and `forRemoval=false` | major | yes | no | no | no | yes | `TaskProviderLegacyContractTest`; `TaskListingProvider.java`; annotation static scan | covered |
| Stage 17 AC-2 SPI legacy worker-session methods are deprecated with `since=1.3.1` and `forRemoval=false` | major | yes | no | no | no | yes | `TaskProviderLegacyContractTest`; `WorkerSessionQueryProvider.java`; annotation static scan | covered |
| Stage 17 AC-3 provider legacy wrapper overrides carry the same deprecation signal | major | yes | yes | no | no | yes | Claude/Codex/Codex Biz/LangGraph provider tests; annotation static scan count=24 | covered |
| Stage 17 AC-4 typed provider methods and REST payload compatibility remain unchanged | critical | yes | yes | no | no | no | `TaskDispatchFacadeTest`, `ClaudeTaskServiceAuthTest`, `ClaudeWorkerSessionQueryServiceTest`, `CodexTaskServiceTest`, `CodexBizTaskProviderTest`, `LanggraphWorkerSessionQueryServiceTest` | covered |
| Stage 17 AC-5 production provider fan-out does not directly call legacy provider methods | major | no | no | no | no | yes | `rg provider\.(listTasksPaged|searchSessions|listTasksByDirectoryPaged|listWorkerSessions|getWorkerSessionMessageCount|getWorkerSessionMessages|syncWorkerSessions)\(` no matches | covered |
| Stage 17 non-goal: no legacy SPI method deletion and no `forRemoval=true` | major | yes | no | no | no | yes | reflection test plus static annotation scan | covered |
| Removal gate and external compatibility policy recorded | major | no | no | no | no | yes | Stage 17 workitem `Removal Gate`; quality record | covered |
| UI/experience validation | minor | no | no | no | no | N/A | pure Java SPI compatibility governance | covered |

## Evidence Summary

- 已有自动化测试：
  - targeted regression：`TaskProviderLegacyContractTest`、`TaskDispatchFacadeTest`、`TaskQueryProviderRegistryTest`、`ClaudeTaskServiceAuthTest`、`ClaudeWorkerSessionQueryServiceTest`、`CodexTaskServiceTest`、`CodexBizTaskProviderTest`、`LanggraphWorkerSessionQueryServiceTest`，合计 131 tests pass。
  - affected reactor：`mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/langgraph-biz-worker -am`，Surefire XML 合计 219 reports / 1528 tests / 0 failures / 0 errors / 0 skipped。
- 已有手工验证：
  - production provider fan-out legacy method static scan 无匹配。
  - deprecated annotation fixed-string scan 确认 24 处 expected annotations。
  - `git diff --check` 无 whitespace error。
- 已有回归保护：
  - `TaskProviderLegacyContractTest` 对 SPI legacy method deprecation 契约形成长期保护。
  - 既有 facade/provider 测试继续覆盖 typed 主路径和 legacy adapter 兼容。

## Gaps

- gap 1: 未运行根目录仓库级全量 `mvn test`；当前以 direct affected reactor 覆盖 Stage 17 涉及模块及其 `-am` 依赖。
- gap 2: 外部插件、SDK、非本仓调用方的 legacy method 使用情况无法由本仓测试证明，需 release note 和迁移窗口治理。
- gap 3: 未执行 UI / Playwright 验证，因为本阶段不涉及 UI。

## Recommended Next Skills

- `integration-test`: not required for Stage 17；后续 removal 或 REST 契约变化时再评估。
- `playwright-cli`: N/A，本阶段无 UI。
- `foggy-bug-regression-workflow`: not required，当前未发现 BUG。
- `foggy-acceptance-signoff`: required，依据本审计进入功能级验收。
- `plan-evaluator`: optional，进入 removal 阶段前评估测试范围与兼容策略。

## Conclusion

- conclusion: `ready-with-gaps`
- can_enter_acceptance: yes
- follow_up_required: yes
