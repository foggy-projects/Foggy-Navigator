---
acceptance_scope: feature
version: 1.3.2-SNAPSHOT
target: OPT-001-codex-biz-route-readiness
doc_role: acceptance-record
doc_purpose: 说明本文件用于 Codex Biz Route readiness 功能级正式验收与签收结论记录
status: signed-off
decision: accepted
signed_off_by: Codex
signed_off_at: 2026-06-29
reviewed_by: Codex
blocking_items: []
follow_up_required: no
evidence_count: 11
---

# Feature Acceptance

## Document Purpose

- doc_type: acceptance
- intended_for: signoff-owner / reviewer / owning-module
- purpose: 记录 `OPT-001: Codex Biz Route Readiness` 的功能级正式验收结论与证据摘要。

## Background

- Version: 1.3.2-SNAPSHOT
- Target: OPT-001-codex-biz-route-readiness
- Owner: codex-worker-agent | session-module | codex-agent-worker
- Goal: 将 `codex-biz-worker` 从已有可用路由推进到可交给上游业务继续联调的 readiness 状态，固定调用契约、补齐回归保护、暴露非敏感 Worker 诊断，并提供 scoped `CODEX_HOME` smoke 入口。

## Acceptance Basis

- [workitem](../workitems/OPT-001-codex-biz-route-readiness.md)
- [version index](../README.md)
- [implementation quality gate](../quality/OPT-001-implementation-quality.md)
- [test coverage audit](../coverage/OPT-001-coverage-audit.md)
- `tools/codex-agent-worker/docs/upstream-integration.md`

## Checklist

- [x] scope 内功能点已全部交付。
- [x] 原始 acceptance criteria 已逐项覆盖。
- [x] 关键测试已通过。
- [x] 体验验证已完成，或明确标记 `N/A`。
- [x] 文档、配置、依赖项已闭环。
- [x] 正式 implementation quality gate 已完成，结论为 `ready-for-coverage-audit`。
- [x] 正式 test coverage audit 已完成，结论为 `ready-for-acceptance`。
- [x] 部署前置项已作为 preflight checklist 回写到 workitem，不阻断功能签收。

## Evidence

- Requirement:
  - `docs/version-tracker/1.3.2-SNAPSHOT/workitems/OPT-001-codex-biz-route-readiness.md`
- Quality:
  - `docs/version-tracker/1.3.2-SNAPSHOT/quality/OPT-001-implementation-quality.md`
- Test:
  - `npm --prefix tools/codex-agent-worker run typecheck`: pass, 2026-06-29.
  - `npm --prefix tools/codex-agent-worker test`: pass, 70 tests, 2026-06-29.
  - `mvn test -pl navigator-common -am "-Dtest=ProviderRouteRegistryTest" ...`: pass, 9 tests, 2026-06-29.
  - `mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest" ...`: pass, 59 tests, 2026-06-29.
  - `mvn test -pl addons/codex-worker-agent -am "-Dtest=CodexBizTaskProviderTest,CodexTaskServiceTest,CodexStreamRelayTest,CodexWorkerClientTest,CodexTaskControllerTest" ...`: pass, 44 tests, 2026-06-29.
  - `git diff --check`: pass, 2026-06-29.
- Experience:
  - N/A. 本事项为后端/Worker/API readiness，不新增或修改 UI 页面、表单、按钮、弹窗、权限可见性或前端交互。
- Artifact:
  - `tools/codex-agent-worker/scripts/codex-biz-smoke.ps1`
  - prior local Worker health smoke evidence in workitem.
  - prior live actor A/B smoke evidence in workitem.
- Coverage:
  - `docs/version-tracker/1.3.2-SNAPSHOT/coverage/OPT-001-coverage-audit.md`

## Failed Items

- none

## Risks / Open Items

- blocking risks: none
- operational preflight:
  - Production deployment must configure absolute `CODEX_BIZ_HOME_ROOT` outside the repository.
  - Scoped `auth.json` files must be treated as credentials and protected by deployment environment permissions.
  - Target environment should run health smoke before exposing upstream integration; live actor A/B smoke remains opt-in.

## Final Decision

`OPT-001: Codex Biz Route Readiness` is accepted.

The feature satisfies the documented acceptance criteria. Automated verification passed on 2026-06-29, prior smoke evidence covers scoped home readiness and actor A/B live behavior, and no blocking implementation quality or coverage gaps remain. The remaining items are deployment preflight checks, not feature acceptance blockers.

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted
- signed_off_by: Codex
- signed_off_at: 2026-06-29
- acceptance_record: docs/version-tracker/1.3.2-SNAPSHOT/acceptance/OPT-001-codex-biz-route-readiness-acceptance.md
- blocking_items: none
- follow_up_required: no
