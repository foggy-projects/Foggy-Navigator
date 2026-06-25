---
acceptance_scope: feature
version: 1.3.1-SNAPSHOT
target: OPT-001-stage8-provider-port-injection
doc_role: acceptance-record
doc_purpose: 说明本文件用于 Stage 8 Provider port 注入收窄的功能级正式验收与签收结论记录
status: signed-off
decision: accepted-with-risks
signed_off_by: Codex
signed_off_at: 2026-06-25
reviewed_by: Codex
blocking_items: []
follow_up_required: yes
evidence_count: 7
---

# Feature Acceptance

## Document Purpose

- doc_type: acceptance
- intended_for: signoff-owner / reviewer / owning-module
- purpose: 记录 OPT-001 Stage 8 Provider port 注入收窄的功能级正式验收结论与证据摘要。

## Background

- Version: 1.3.1-SNAPSHOT
- Target: OPT-001 Stage 8 Provider port 注入收窄
- Owner: session-module / Worker Provider
- Goal: 在保持现有 Provider 实现兼容的前提下，让 session 构造边界和 registry 内部集合按 lookup / command / listing / worker-session 四类窄端口表达，降低继续依赖 `TaskQueryProvider` 聚合接口的维护风险。

## Acceptance Basis

- Requirement: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
- Implementation plan / progress: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage8-provider-port-injection.md`
- Quality gate: `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-001-stage8-implementation-quality.md`
- Coverage audit: `docs/version-tracker/1.3.1-SNAPSHOT/coverage/OPT-001-stage8-coverage-audit.md`
- Test records:
  - `mvn test -pl session-module -am "-Dtest=TaskQueryProviderRegistryTest,TaskDispatchFacadeTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: 63 tests pass.
  - `mvn test -pl addons/claude-worker-agent -am "-Dtest=ClaudeWorkerAgentProviderTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: 10 tests pass.
  - `mvn test -pl navigator-spi,session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am`: affected reactor pass; Surefire XML total 221 reports / 1525 tests / 0 failures / 0 errors / 0 skipped.
  - `git diff --check`: no whitespace errors; only CRLF normalization warnings.

## Checklist

- [x] scope 内功能点已全部交付
- [x] 原始 acceptance criteria 已逐项覆盖
- [x] 关键测试已通过
- [x] 体验验证已完成，或明确标记 `N/A`
- [x] 文档、配置、依赖项已闭环

## Evidence

- Requirement:
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage8-provider-port-injection.md`
- Implementation:
  - `session-module/src/main/java/com/foggy/navigator/session/service/TaskQueryProviderRegistry.java`
  - `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`
  - `session-module/src/main/java/com/foggy/navigator/session/agent/AbortCoordinatingA2aAgent.java`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/adapter/ClaudeWorkerAgentProvider.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/adapter/CodexWorkerAgentProvider.java`
  - `addons/gemini-worker-agent/src/main/java/com/foggy/navigator/gemini/worker/adapter/GeminiWorkerAgentProvider.java`
- Test:
  - `session-module/src/test/java/com/foggy/navigator/session/service/TaskQueryProviderRegistryTest.java`
  - `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java`
  - `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/adapter/ClaudeWorkerAgentProviderTest.java`
  - affected Java reactor command listed above
- Quality:
  - `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-001-stage8-implementation-quality.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/coverage/OPT-001-stage8-coverage-audit.md`
- Experience:
  - N/A。该切片为 Java SPI 注入边界和 session registry 治理，未新增或修改 UI 页面、表单、按钮、权限可见性或前端交互。

## Failed Items

- none

## Risks / Open Items

- `TaskQueryProvider` 聚合接口仍保留。Owner: navigator-spi / java-platform。Follow-up: 后续阶段按 Provider 复杂度逐步拆独立窄端口 bean。
- 未新增专门的 Spring ApplicationContext 测试验证四类泛型列表注入。Owner: session-module。Follow-up: 如后续拆独立 bean 或引入第三方 Provider，可补启动级回归。
- `TaskListingProvider` strictly typed method 迁移仍未处理。Owner: navigator-spi / session-module。Follow-up: 可另起阶段评估兼容迁移。

## Final Decision

本功能签收结论为 `accepted-with-risks`。

Stage 8 已完成 `TaskDispatchFacade` 四类窄端口列表注入、`TaskQueryProviderRegistry` 分集合维护、lookup/command 分离路由支持、worker adapter lookup-port 构造迁移和兼容构造保留。遗留风险均为后续兼容收敛项，不阻断当前版本验收。

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-25
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage8-provider-port-injection-acceptance.md
- blocking_items: none
- follow_up_required: yes
