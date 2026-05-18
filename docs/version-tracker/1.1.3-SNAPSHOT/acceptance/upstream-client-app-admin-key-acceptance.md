---
acceptance_scope: feature
version: 1.1.3-SNAPSHOT
target: upstream-client-app-admin-key
doc_role: acceptance-record
doc_purpose: 说明本文件用于 issue #121 上游系统级 ClientApp 管理凭证功能的正式验收与签收结论记录
status: signed-off
decision: accepted
signed_off_by: Codex execution-agent
signed_off_at: 2026-05-18
reviewed_by: N/A
blocking_items: []
follow_up_required: no
evidence_count: 10
---

# Feature Acceptance

## Background

- Version: `1.1.3-SNAPSHOT`
- Target: `upstream-client-app-admin-key`
- Source issue: `https://github.com/foggy-projects/Foggy-Navigator/issues/121`
- Owner: Navigator upstream integration / business-agent-module / navigator-open-sdk
- Goal: 让多租户上游系统通过受限的 `NAVI_ADMIN_API_KEY` 管理授权范围内多个 Navigator ClientApp，并为每个 ClientApp 签发自己的 `NAVI_CONTROL_API_KEY`；非多租户上游仍可跳过 `NAVI_ADMIN_API_KEY`，直接获得单个 ClientApp 的 `NAVI_CONTROL_API_KEY`。

与版本目标的对齐判断：本功能直接支撑 `1.1.3-SNAPSHOT` 中以 `client_app_id` 作为上游接入、授权、审计和路由最小隔离单元的目标，并补齐多租户上游系统的 ClientApp bootstrap 管理链路。本记录只签收该功能，不代表整个 `1.1.3-SNAPSHOT` 版本目录整体签收。

## Acceptance Basis

- Requirement: `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/33-upstream-client-app-admin-key-request.md`
- Implementation plan: `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/34-upstream-client-app-admin-key-implementation-plan.md`
- Quality gate: `docs/version-tracker/1.1.3-SNAPSHOT/quality/upstream-client-app-admin-key-implementation-quality.md`
- Coverage audit: `docs/version-tracker/1.1.3-SNAPSHOT/coverage/upstream-client-app-admin-key-coverage-audit.md`
- Live HTTP/CLI smoke: `docs/version-tracker/1.1.3-SNAPSHOT/coverage/upstream-client-app-admin-key-live-http-cli-smoke.md`
- OBS CLI release smoke: `docs/version-tracker/1.1.3-SNAPSHOT/coverage/upstream-client-app-admin-key-cli-release-smoke.md`
- Usage guide: `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/18-navigator-upstream-cli-usage-guide.md`
- Control credential guide: `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/28-client-app-control-credential-delivery.md`

## Checklist

- [x] Scope 内功能点已全部交付：Stage 0 安全底座、Stage 1 申请生命周期、Stage 2 SDK/CLI、Stage 3 多租户 ClientApp 管理、Stage 4 测试与证据均已完成。
- [x] 原始 acceptance criteria 已逐项覆盖：无凭证申请、operator/admin 审批、一次性 claim、多租户 ClientApp 管理、非多租户直发 control key、错误状态不泄露明文、审计、跨边界拒绝均已有证据。
- [x] 关键测试已通过：`user-auth-module`、`business-agent-module`、`navigator-open-sdk` 模块测试及服务级 bootstrap E2E 均通过。
- [x] Live HTTP/CLI smoke 已通过：重启 launcher 后真实访问 `http://localhost:8112`，完成 request -> approve -> claim -> ensure ClientApp -> issue control key。
- [x] OBS CLI release smoke 已通过：从远端 `install.ps1` 安装 `1.0.4`，完成 admin-key 与 client-app 全链路，并确认上游系统级 profile 与租户级 profile 分别写入所需凭证键名。
- [x] 体验验证已处理：本功能首批不包含 Navigator 管理台 UI，experience 为 `N/A`。
- [x] 文档、配置、依赖项已闭环：需求、实施计划、使用指南、质量门禁、覆盖审计、live smoke 证据均已同步。
- [x] DB schema 策略已确认：本次新增表/字段属于 additive schema change，所有环境使用 Hibernate `ddl-auto:update`；删除、重命名、数据迁移等不在 `ddl-auto:update` 范围内的变更另行迁移。

## Evidence

- Requirement and acceptance criteria:
  - `33-upstream-client-app-admin-key-request.md` 已记录 `NAVI_OPERATOR_API_KEY`、`NAVI_ADMIN_API_KEY`、`NAVI_CONTROL_API_KEY` 的边界，以及 8 项验收标准。
- Implementation progress:
  - `34-upstream-client-app-admin-key-implementation-plan.md` 已记录 Stage 0-4 执行结果、Gate A-D 完成状态、测试证据和 schema policy。
- Quality gate:
  - `upstream-client-app-admin-key-implementation-quality.md` 结论为 `ready-for-acceptance`，无质量阻断 follow-up。
- Coverage audit:
  - `upstream-client-app-admin-key-coverage-audit.md` 结论为 `ready-for-acceptance`，无 blocking coverage gap。
- Module tests:
  - `mvn test -pl user-auth-module -am` passed, 68 tests.
  - `mvn test -pl business-agent-module -am` passed, 284 tests.
  - `mvn test -pl navigator-open-sdk` passed, 79 tests.
- Service-level E2E:
  - `mvn test -pl business-agent-module -am "-Dtest=UpstreamBootstrapEndToEndServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false"` passed, 1 test.
- Live HTTP/CLI smoke:
  - `upstream-client-app-admin-key-live-http-cli-smoke.md` passed against restarted launcher on `http://localhost:8112`; smoke run path is `temp\navi-admin-smoke\run-20260518123552`.
- OBS CLI release smoke:
  - `upstream-client-app-admin-key-cli-release-smoke.md` passed with OBS latest CLI `1.0.4`; release package SHA256 is `3b7737d28a1ab9654fe07e76f4c6821f417a21432a8fb786502298aab7286113`; smoke run path is `temp\navi-obs-release-smoke\run-20260518131735`.
- Runtime evidence:
  - Launcher PID `61032` listened on `8112`; logs confirmed MySQL Hikari connection, Hibernate/JPA initialization, and Tomcat startup.
- Repository hygiene:
  - `git diff --check` passed with only existing LF/CRLF warnings and no whitespace errors.

## Failed Items

- none

## Risks / Open Items

- No blocking risk remains for issue #121.
- Operational note: future destructive or transformational schema changes, such as deleting fields, renaming fields, or migrating existing data, remain outside Hibernate `ddl-auto:update` coverage and must include an explicit migration plan.
- UI note: Navigator 管理台审批页面不在本次首批范围内；如后续新增 UI，需要单独补 experience checklist 和 Playwright evidence。

## Final Decision

Decision: `accepted`.

The feature satisfies the confirmed issue #121 requirements. The security boundary is explicit, credentials are separated by role and header, sensitive keys are hash-stored and one-time delivered, multi-tenant ClientApp management is covered by automated tests and live HTTP/CLI smoke, and no blocking quality or coverage gaps remain.

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted
- signed_off_by: Codex execution-agent
- signed_off_at: 2026-05-18
- acceptance_record: docs/version-tracker/1.1.3-SNAPSHOT/acceptance/upstream-client-app-admin-key-acceptance.md
- blocking_items: none
- follow_up_required: no
