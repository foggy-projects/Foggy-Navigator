---
audit_scope: feature
audit_mode: pre-acceptance-check
version: 1.1.3-SNAPSHOT
target: upstream-client-app-admin-key
status: reviewed
conclusion: ready-for-acceptance
reviewed_by: Codex
reviewed_at: 2026-05-18
follow_up_required: no
---

# Test Coverage Audit

## Background

本审计覆盖 issue #121 对应的上游系统级 ClientApp 管理凭证需求，目标是在正式验收前确认 requirement、acceptance item 与测试证据是否可以相互映射。

核心链路是：无 Navigator 凭证提交申请，Navigator admin/operator 审批，上游一次性领取 `NAVI_ADMIN_API_KEY`，多租户上游在授权范围内创建或复用多个 ClientApp，并为每个 ClientApp 签发自己的 `NAVI_CONTROL_API_KEY`。

## Audit Basis

- Requirement: `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/33-upstream-client-app-admin-key-request.md`
- Implementation plan: `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/34-upstream-client-app-admin-key-implementation-plan.md`
- Quality gate: `docs/version-tracker/1.1.3-SNAPSHOT/quality/upstream-client-app-admin-key-implementation-quality.md`
- Live HTTP/CLI smoke: `docs/version-tracker/1.1.3-SNAPSHOT/coverage/upstream-client-app-admin-key-live-http-cli-smoke.md`
- Usage guide: `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/18-navigator-upstream-cli-usage-guide.md`

## Coverage Matrix

| Requirement / acceptance item | Risk | Existing validation layer | Evidence | Coverage |
| --- | --- | --- | --- | --- |
| 无 Navigator 凭证可提交 admin key 申请，但只能得到 request/claim 信息，不得到密钥 | critical | unit-test, SDK-test, CLI-test | `UpstreamBootstrapRequestServiceTest#createRequestReturnsOneTimeCodesButStoresHashesOnly`; `BusinessAgentApiSmokeTest#testRequestUpstreamAdminKeyUsesNoAuthAndReturnsClaimToken`; `UpstreamCliTest#adminKeyRequestWritesClaimTokenWithoutPrintingIt` | covered |
| 审批/拒绝只能由 Navigator admin/operator 执行，上游 LLM 或申请方不能自批 | critical | unit-test, SDK-test, CLI-test | `UpstreamBootstrapRequestServiceTest#tenantAdminCannotApproveRequestFromAnotherTenant`; `BusinessAgentApiSmokeTest#testApproveUpstreamBootstrapRequestWithOperatorKey`; `UpstreamCliTest#adminKeyApproveUsesOperatorKeyAndNotUpstreamAdminKey` | covered |
| claim token 一次性领取，hash-only 存储，不在列表、日志或 CLI 输出中泄露完整 key | critical | unit-test, CLI-test | `UpstreamBootstrapRequestServiceTest#approveAndClaimIssuesAdminKeyOnce`; `UpstreamBootstrapRequestServiceTest#claimRejectsWrongTokenWithoutIssuingCredential`; `UpstreamCliTest#adminKeyClaimWritesAdminKeyAndClearsClaimTokenWithoutPrintingIt` | covered |
| 多租户上游使用 `NAVI_ADMIN_API_KEY` 管理授权范围内多个 ClientApp，并签发各自 `NAVI_CONTROL_API_KEY` | critical | service-level integration-test, unit-test, SDK-test, CLI-test | `UpstreamBootstrapEndToEndServiceTest#requestApproveClaimEnsureClientAppAndIssueControlKey`; `UpstreamClientAppManagementServiceTest#ensureClientAppCreatesUpstreamScopedClientApp`; `UpstreamClientAppManagementServiceTest#issueControlCredentialDelegatesForManagedClientApp`; `BusinessAgentApiSmokeTest#testUpstreamAdminApiKeyEnsuresClientAppAndIssuesControlCredential`; `UpstreamCliTest` client-app ensure/issue-control-key cases | covered |
| 非多租户上游可跳过 `NAVI_ADMIN_API_KEY`，直接交付单个 `NAVI_CONTROL_API_KEY` | major | unit-test, existing flow evidence | Existing ClientApp/control credential service tests and CLI control key paths remain available; Stage 3 did not remove the admin-created ClientApp path | covered |
| 文档友好 scope alias `CLIENT_APP_ADMIN`、`CONTROL_KEY_ISSUE` 与后端 enforcement scope 对齐 | major | unit-test, service-level integration-test | `UpstreamBootstrapRequestServiceTest` scope alias approval assertions; `UpstreamBootstrapEndToEndServiceTest#requestApproveClaimEnsureClientAppAndIssueControlKey` | covered |
| `NAVI_ADMIN_API_KEY` 默认 24h TTL，支持撤销、轮换，revoked/expired fail-closed | critical | unit-test, CLI-test | `UpstreamBootstrapRequestServiceTest#revokeAdminCredentialMarksCredentialInactiveAndAudits`; `UpstreamBootstrapRequestServiceTest#rotateAdminCredentialRevokesOldCredentialAndReturnsNewOneTimeKey`; `UpstreamClientAppAdminCredentialServiceTest#requireAccessRejectsRevokedCredential`; `UpstreamCliTest#adminKeyRevokeUsesOperatorKeyAndNotUpstreamAdminKey`; `UpstreamCliTest#adminKeyRotateWritesNewAdminKeyWithoutPrintingIt` | covered |
| 上游 admin key 使用专用 header，不混用普通 `X-API-Key` 或 bearer auth | critical | SDK-test, CLI-test | `BusinessAgentApiSmokeTest#testUpstreamAdminApiKeyDoesNotMixConfiguredApiKey`; upstream admin SDK smoke assertions for missing `X-API-Key`; `UpstreamCliTest` header assertions | covered |
| 跨 upstream system、跨 tenant、跨 ClientApp 的管理请求必须失败 | critical | unit-test, service-level integration-test | `UpstreamClientAppManagementServiceTest#ensureClientAppRejectsUnauthorizedTenant`; `UpstreamClientAppManagementServiceTest#issueControlCredentialRejectsOtherUpstreamSystemClientApp`; `UpstreamClientAppAdminCredentialServiceTest#requireAccessRejectsMissingScope`; `UpstreamBootstrapEndToEndServiceTest#requestApproveClaimEnsureClientAppAndIssueControlKey` cross-tenant rejection assertion | covered |
| DB schema 发布准备：新增 upstream ClientApp 字段、bootstrap request/admin credential/audit tables 可在目标环境创建 | major | manual-evidence, live-smoke | `launcher/src/main/resources/application.yml`, `launcher/src/main/resources/application-docker.yml.example`, `user-auth-module/src/main/resources/application.yml` use Hibernate `ddl-auto:update`; live launcher startup against MySQL succeeded with JPA initialization and no schema errors | covered |
| 管理台 UI/Playwright 体验验收 | minor | manual-evidence | 当前需求明确首批不实现 Navigator 管理台 UI，暂无页面变更 | covered |

## Evidence Summary

- `mvn test -pl business-agent-module -am` passed, 284 tests.
- `mvn test -pl business-agent-module -am "-Dtest=UpstreamBootstrapEndToEndServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false"` passed, 1 test.
- `mvn test -pl navigator-open-sdk` passed, 78 tests.
- `mvn test -pl user-auth-module -am` passed, 68 tests.
- Service-level bootstrap evidence now covers request -> approve -> claim -> ensure ClientApp -> issue `NAVI_CONTROL_API_KEY`.
- Live HTTP/CLI smoke passed against restarted launcher on `http://localhost:8112`: request -> approve -> claim -> ensure ClientApp -> issue `NAVI_CONTROL_API_KEY`.
- DB schema policy is confirmed for this requirement: all environments use Hibernate `ddl-auto:update` for additive tables/columns. Explicit SQL migration is only required for delete/rename/data transformation changes outside `ddl-auto:update` coverage.

## Gaps

No blocking coverage gaps remain for issue #121.

Residual release note: if a future schema change deletes fields, renames fields, or migrates existing data, that change is outside Hibernate `ddl-auto:update` coverage and must include an explicit migration plan.

## Acceptance Follow-up

- Formal signoff completed: `docs/version-tracker/1.1.3-SNAPSHOT/acceptance/upstream-client-app-admin-key-acceptance.md`.

## Conclusion

Conclusion: `ready-for-acceptance`.

The critical security and multi-tenant bootstrap requirements have automated evidence across backend service tests, SDK smoke tests, CLI tests, and a live launcher HTTP/CLI smoke. Schema handling is aligned with the confirmed project policy: `ddl-auto:update` is used in all environments for additive changes, with explicit migration reserved for destructive or transformational schema changes.
