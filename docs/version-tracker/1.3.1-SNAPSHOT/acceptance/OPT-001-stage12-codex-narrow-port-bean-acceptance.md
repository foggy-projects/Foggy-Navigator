---
acceptance_scope: feature
version: 1.3.1-SNAPSHOT
target: OPT-001-stage12-codex-narrow-port-bean
doc_role: acceptance-record
doc_purpose: 说明本文件用于 Stage 12 Codex / Codex Biz 窄端口 bean 迁移的功能级正式验收与签收结论记录
status: signed-off
decision: accepted-with-risks
signed_off_by: Codex
signed_off_at: 2026-06-26
reviewed_by: Codex
blocking_items: []
follow_up_required: yes
evidence_count: 10
---

# Feature Acceptance

## Document Purpose

- doc_type: acceptance
- intended_for: signoff-owner / reviewer / owning-module
- purpose: 记录 OPT-001 Stage 12 Codex / Codex Biz narrow port bean migration 的功能级正式验收结论与证据摘要。

## Background

- Version: 1.3.1-SNAPSHOT
- Target: OPT-001 Stage 12 Codex / Codex Biz narrow port bean migration
- Owner: codex-worker-agent / session-module
- Goal: 在 Stage 8 窄端口注入、Stage 10 LangGraph 迁移和 Stage 11 Gemini 迁移之后，让 Codex 与 Codex Biz provider 退出聚合 `TaskQueryProvider`，只按实际支持能力作为 lookup / command / listing 窄端口注册。

## Acceptance Basis

- Requirement: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
- Implementation plan / progress: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage12-codex-narrow-port-bean.md`
- Quality gate: `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-001-stage12-implementation-quality.md`
- Coverage audit: `docs/version-tracker/1.3.1-SNAPSHOT/coverage/OPT-001-stage12-coverage-audit.md`

## Checklist

- [x] scope 内功能点已全部交付
- [x] 原始 acceptance criteria 已逐项覆盖
- [x] 关键测试已通过
- [x] 体验验证已完成，或明确标记 `N/A`
- [x] 文档、配置、依赖项已闭环

## Evidence

- Requirement:
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage12-codex-narrow-port-bean.md`
- Implementation:
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexBizTaskProvider.java`
- Test:
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexTaskServiceTest.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexBizTaskProviderTest.java`
  - `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java`
  - `session-module/src/test/java/com/foggy/navigator/session/service/TaskQueryProviderRegistryTest.java`
  - Codex focused regression: 59 tests pass.
  - session focused regression: 64 tests pass.
  - affected reactor regression: 622 tests pass, 0 failures, 0 errors, 0 skipped.
- Quality:
  - `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-001-stage12-implementation-quality.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/coverage/OPT-001-stage12-coverage-audit.md`
- Experience:
  - N/A。该切片为 Java 后端 Provider 注册边界收敛，未新增或修改 UI 页面、表单、按钮、权限可见性或前端交互。

## Failed Items

- none

## Risks / Open Items

- Claude 仍直接实现 `TaskQueryProvider` 聚合接口。Owner: java-platform / claude-worker-agent。Follow-up: Stage 13 继续迁移。
- `TaskCommandProvider#cancelTask` 仍保留 deprecated fallback。Owner: navigator-spi / session-module / provider addons。Follow-up: 等 A2A abort 链路和 Provider command 迁移全部收口后统一删除。
- `TaskListingProvider` 仍使用 `Object` 返回签名。Owner: navigator-spi / session-module / provider addons。Follow-up: 规划 strictly typed method 兼容迁移。
- 未新增完整 Spring ApplicationContext 启动测试断言真实 provider bean list。Owner: session-module。Follow-up: 后续拆更多独立 bean 时补启动级回归。

## Final Decision

本功能签收结论为 `accepted-with-risks`。

Stage 12 已完成 Codex / Codex Biz provider 从聚合 `TaskQueryProvider` 到实际支持的 lookup / command / listing 窄端口迁移。测试证明两个 provider 不再作为 aggregate/worker-session provider 暴露，Codex 任务生命周期测试、session facade/registry 回归和受影响 reactor 均通过。遗留项均为后续架构收敛范围，不阻断当前阶段签收。

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-26
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage12-codex-narrow-port-bean-acceptance.md
- blocking_items: none
- follow_up_required: yes
