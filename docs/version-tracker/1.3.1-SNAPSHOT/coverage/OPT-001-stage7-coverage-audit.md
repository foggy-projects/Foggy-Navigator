---
audit_scope: feature
audit_mode: pre-acceptance-check
version: 1.3.1-SNAPSHOT
target: OPT-001-stage7-provider-listing-envelope
status: reviewed
conclusion: ready-with-gaps
reviewed_by: Codex
reviewed_at: 2026-06-25
follow_up_required: yes
---

# Test Coverage Audit

## Background

- 审计对象：OPT-001 Stage 7 Provider listing/search typed envelope 治理。
- 当前阶段：实现质量门已完成，准备进入功能级验收。
- 审计目标：确认 typed envelope 主路径、legacy fallback、Provider 迁移和对外响应兼容性有足够自动化证据承接。

## Audit Basis

- requirement: `workitems/OPT-001-java-architecture-risk-governance.md`
- implementation plan: `workitems/OPT-001-stage7-provider-listing-envelope.md`
- quality gate: `quality/OPT-001-stage7-implementation-quality.md`
- acceptance basis: Stage 7 workitem acceptance criteria
- test records:
  - `mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent -am "-Dtest=UnifiedSessionTaskProjectionServiceTest,TaskDispatchFacadeTest,ClaudeTaskServiceTest,CodexTaskServiceTest,CodexBizTaskProviderTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: session-module 58 tests pass; codex-worker-agent 28 tests pass.
  - `mvn test -pl navigator-spi,session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am`: affected reactor pass; Surefire XML total 221 reports / 1524 tests / 0 failures / 0 errors / 0 skipped.
  - `git diff --check`: no whitespace errors; only CRLF normalization warnings.
- manual evidence: 本审计记录和实现质量门的代码路径核对。

## Coverage Matrix

| Item | Risk | Unit | Integration | E2E | Playwright | Manual | Evidence Path | Coverage |
|------|------|------|-------------|-----|------------|--------|---------------|----------|
| AC-1: SPI 提供 typed listing/search envelope | major | yes | no | no | no | yes | `TaskPageResult`; `TaskSearchResult`; affected reactor compile | covered |
| AC-2: `UnifiedSessionTaskProjectionService` 优先解析 typed envelope | critical | yes | no | no | no | yes | `UnifiedSessionTaskProjectionServiceTest` typed page/search cases | covered |
| AC-3: 旧 Map / JavaBean getter fallback 保持可用 | critical | yes | no | no | no | yes | `UnifiedSessionTaskProjectionServiceTest` legacy Map/bean cases | covered |
| AC-4: Codex listing 返回 typed envelope | major | yes | no | no | no | yes | `CodexTaskServiceTest` typed `TaskPageResult` assertion | covered |
| AC-5: Claude/Codex/Codex Biz SPI 返回迁移后编译兼容 | major | yes | no | no | no | yes | targeted command and affected reactor pass | partially-covered |
| AC-6: 对外 list/search response 字段和语义保持不变 | critical | yes | no | no | no | yes | `TaskDispatchFacadeTest`; no controller/schema contract changes review | covered |

## Evidence Summary

- 已有自动化测试：
  - `UnifiedSessionTaskProjectionServiceTest` 覆盖 typed page、typed search、legacy Map fallback、legacy JavaBean getter fallback。
  - `TaskDispatchFacadeTest` 保持统一 list/search 聚合行为回归。
  - `CodexTaskServiceTest` 已断言 `listTasksPaged` 返回 `TaskPageResult`。
  - targeted provider/session regression 覆盖 session 聚合层和 Codex Provider 关键路径。
  - affected reactor 覆盖 `navigator-spi`、session-module、Claude/Codex/Gemini/LangGraph worker provider 编译与主回归。
- 已有手工验证：
  - 质量门核对新增 SPI record、projection typed-first 解析、Claude/Codex/Codex Biz SPI wrapper 和 controller DTO 兼容边界。
- 已有回归保护：
  - 受影响 Java reactor 1524 tests 通过，能防止 SPI 编译、Provider 兼容和 session 聚合路径大面积回归。

## Gaps

- 未新增 Claude SPI wrapper 直接行为单测；当前通过编译兼容、controller DTO 保留和 affected reactor 间接覆盖，不阻断验收。
- Codex/Codex Biz search typed envelope 没有单独断言返回类型；`UnifiedSessionTaskProjectionServiceTest` 已覆盖 typed search 解析，Provider 侧通过 affected reactor 和接口编译承接。
- 未补 REST / OpenAPI / SDK 层 E2E；本阶段未改变 controller path、外部 payload 或前端交互，缺口不阻断验收。

## Recommended Next Skills

- `integration-test`: 当前不需要；Stage 7 未改变 API 或跨进程协议。
- `playwright-cli`: 当前不需要；无前端交互变化。
- `foggy-bug-regression-workflow`: 当前不需要；未发现 BUG 修复项。
- `foggy-acceptance-signoff`: 建议执行 Stage 7 功能级验收签收。
- `plan-evaluator`: 不需要；测试层级与改动风险匹配。

## Conclusion

- conclusion: `ready-with-gaps`
- can_enter_acceptance: yes
- follow_up_required: yes
