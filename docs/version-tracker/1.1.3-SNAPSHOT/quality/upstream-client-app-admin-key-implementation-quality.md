---
quality_scope: feature
quality_mode: post-live-smoke-update
version: 1.1.3-SNAPSHOT
target: upstream-client-app-admin-key
status: remediated
decision: ready-for-acceptance
reviewed_by: Codex
reviewed_at: 2026-05-18
follow_up_required: no
---

# Implementation Quality Gate

## Background

本次检查对象是 issue #121 对应的上游多租户 ClientApp admin key 需求。Stage 0-3 已完成实现，前一轮质量闸门发现的 blocking/medium 问题已完成修复并复跑模块测试。

核心契约：

- 多租户上游通过 `NAVI_ADMIN_API_KEY` 管理授权范围内的多个 Navigator ClientApp，并为每个 ClientApp 签发自己的 `NAVI_CONTROL_API_KEY`。
- 非多租户上游可跳过 `NAVI_ADMIN_API_KEY`，直接获得单个 ClientApp 的 `NAVI_CONTROL_API_KEY`。
- `NAVI_ADMIN_API_KEY` 不等同于 Navigator 租户管理员 key，不作为普通 `X-API-Key` fallback。
- 上游申请审批、撤销、轮换必须由 Navigator admin/operator 凭证完成，不能由申请生成的 `NAVI_ADMIN_API_KEY` 自管。

## Check Basis

- Requirement: `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/33-upstream-client-app-admin-key-request.md`
- Implementation plan: `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/34-upstream-client-app-admin-key-implementation-plan.md`
- Usage guide: `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/18-navigator-upstream-cli-usage-guide.md`
- Control credential guide: `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/28-client-app-control-credential-delivery.md`

## Test Evidence

- `mvn test -pl business-agent-module -am` passed, 284 tests.
- `mvn test -pl business-agent-module -am "-Dtest=UpstreamBootstrapEndToEndServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false"` passed, 1 test.
- `mvn test -pl navigator-open-sdk` passed, 78 tests.
- `mvn test -pl user-auth-module -am` passed, 68 tests.
- Live HTTP/CLI smoke passed against restarted launcher on `http://localhost:8112`; evidence: `docs/version-tracker/1.1.3-SNAPSHOT/coverage/upstream-client-app-admin-key-live-http-cli-smoke.md`.
- `git diff --check` passed; only existing LF/CRLF warnings were printed.

## Changed Surface

- `business-agent-module`: upstream admin key request lifecycle, operator approval, default TTL, revoke/rotate lifecycle, hash-only credential storage, upstream-admin ClientApp management APIs.
- `navigator-open-sdk`: SDK wrappers, upstream-admin-only auth helpers, upstream CLI `admin-key` and `client-app` commands.
- `user-auth-module`: Spring Security permit list for manually guarded admin/operator endpoints.
- Docs under `docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration`.

## Remediation

### 1. Scope alias contract fixed

The documented operator scopes `CLIENT_APP_ADMIN,CONTROL_KEY_ISSUE` are now accepted and canonicalized to backend enforcement scopes `CLIENT_APP_MANAGE,CLIENT_APP_CONTROL_KEY_ISSUE`.

Evidence:

- Backend approval normalizes scope aliases before persisting admin credential scope JSON.
- Credential validation canonicalizes incoming required scopes.
- Tests cover approving with documented aliases and using the resulting credential for downstream admin operations.

### 2. Admin credential lifecycle fixed

`NAVI_ADMIN_API_KEY` now has an operational lifecycle matching the requirement.

Evidence:

- Approval applies a default 24h admin credential TTL when `credentialExpiresAt` is omitted.
- Credential validation rejects non-active, expired, and `revokedAt != null` credentials.
- Admin revoke and rotate API/CLI paths are implemented and guarded by operator/admin credentials.
- Revoke and rotate write audit records; rotate returns the new plaintext key only once.
- Backend tests cover default expiry, revoked credential rejection, revoke audit, and rotate one-time key issuance.

### 3. SDK mixed-auth risk fixed

Upstream admin API calls now use dedicated SDK helpers that send `X-Navi-Admin-Key` without adding configured default `X-API-Key`.

Evidence:

- `HttpHelper` has upstream-admin-only request helpers.
- `BusinessAgentApi` upstream admin methods use those helpers for configured and explicit admin key paths.
- SDK smoke test covers a client configured with both `apiKey` and `upstreamAdminApiKey`, and verifies upstream admin calls do not send `X-API-Key`.

## Quality Checklist

- Scope conformance: aligned after alias canonicalization.
- Code hygiene: no debug code or temporary branch found in reviewed files.
- Duplication and consolidation: credential header handling is centralized in `HttpHelper`.
- Complexity and abstraction: service/controller split remains understandable; no new heavy abstraction added.
- Error handling and edge cases: tenant/upstream/clientApp mismatch checks remain covered; revoked/expired admin credentials now fail closed.
- Documentation and writeback: requirement, implementation plan, CLI usage guide, and this quality record are synchronized.
- Test alignment: unit and SDK/CLI tests cover the quality findings; service-level bootstrap evidence now covers request -> approve -> claim -> ensure ClientApp -> issue control key.

## Remaining Follow-ups

No quality-blocking follow-ups remain for issue #121.

- Live HTTP/CLI smoke is complete.
- DB schema policy is confirmed: all environments use Hibernate `ddl-auto:update` for additive schema changes; explicit migration is only required for delete/rename/data transformation changes outside `ddl-auto:update` coverage.
- Formal acceptance signoff is complete: `docs/version-tracker/1.1.3-SNAPSHOT/acceptance/upstream-client-app-admin-key-acceptance.md`.

## Decision

`ready-for-acceptance`.

The blocking contract issues from the initial quality pass are fixed and verified by module tests, service-level E2E evidence, and live HTTP/CLI smoke. The feature has completed formal acceptance signoff.
