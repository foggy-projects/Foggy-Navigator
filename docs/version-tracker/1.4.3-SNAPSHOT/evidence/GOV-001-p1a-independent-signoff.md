---
acceptance_scope: feature
version: 1.4.3-SNAPSHOT
target: GOV-001-P1A
status: rejected
decision: rejected
signed_off_by: Independent Signoff Reviewer
signed_off_at: 2026-07-19
reviewed_by: N/A
blocking_items:
  - BUG-002
  - P1A-6-observer-bff-runtime-shadow-disposition
follow_up_required: yes
evidence_count: 8
---

# GOV-001 P1A Independent Signoff

## Document Purpose

- intended_for: Project Owner / authorization owner / implementation session
- purpose: 对 GOV-001 P1A foundation/shadow 形成独立、可复核的正式签核结论。

## Background

- delivery_spec: [GOV-001 上游权限体系与多场景信任边界](../workitems/GOV-001-upstream-permission-and-trust-boundary.md)
- target_outcome: 完成不改变 legacy enforcement 的授权持久化基础、server-owned deployment identity、canonical sparse context/decision、415 条 route/action catalog、legacy lane adapter、shadow diff 和脱敏审计。
- signoff_scope: 仅 P1A-1 至 P1A-8；不验收 P1B/P1C、S1/S2 typed credential、CLI/SKILL、external、Worker Gateway strict、Worker external 或 production。

## Acceptance Basis

- approved delivery spec: GOV-001 `READY_FOR_SIGNOFF` submission，含 2026-07-19 Owner-approved `/actuator` amendment。
- changed paths / diff: canonical `Implementation Result` 声明的 `navigator-common`、`user-auth-module`、`launcher`、Observer BFF test-only surface、migration 和版本文档；用户的 `ClaudeWorkerView.vue` dirty change不在签核范围。
- test records:
  - `mvn test -pl navigator-common -Dgov001.mysql.integration=true -Dtest=AuthorizationContractTest,DeploymentIdentityResolverTest,AuthorizationDecisionAuditStoreImplTest,AuthorizationPersistenceMySqlIntegrationTest`：2026-07-19 reviewer 复跑，19 tests，0 failure/error/skip，`BUILD SUCCESS`；其中 MySQL 8.0.44 migration/rollback/`ddl-auto=validate` 3/3。
  - `mvn test -pl tools/navigator-chat-observer-bff -am -Dtest=ObserverBffRouteManifestCoverageTest,ObserverBffContextContinuityTest -Dsurefire.failIfNoSpecifiedTests=false`：2026-07-19 reviewer 复跑，BFF 3 tests，0 failure/error/skip，3-module reactor `BUILD SUCCESS`。
  - 实施记录中的 `mvn test -pl user-auth-module -am`、当前 HEAD 最近一次 `mvn test -pl launcher -am` 均通过；launcher 为 14-module reactor、exit 0。先前由尚未修复的 BUG-001 复现测试造成的失败已过时，BUG-001 已在当前 HEAD 独立提交修复。
- migration / compatibility evidence: source/evidence manifest 均为 415 entries，SHA-256 `d0360de638c47fbb9e88cb349aec8b92559894bed9554d7200ba10223d12efa9`；`/actuator` 200→404 是唯一批准的响应变化。

## Contract Conformance

| Item | Expected | Delivered | Evidence | Result |
|---|---|---|---|---|
| P1A-1 | 六类 aggregate additive schema、forward/rollback、MySQL/JPA/validate 证据 | 表、Entity、Repository、SQL 和测试齐全；不迁移 legacy secret/data | reviewer MySQL/JPA 定向 19/19；migration 文件与 schema contract | pass |
| P1A-2 | server-owned instance/environment，request 不可覆盖，production 缺配置 fail closed | deployment identity provider/guard 和 override observer 已实现 | `DeploymentIdentityResolverTest` 10/10；launcher production guard evidence | pass |
| P1A-3 | 每个 action 显式声明并校验最小 context sections；缺失/未知 section fail closed | manifest 无 required-section 声明；DTO 缺 authority、delegation/grant、platformGrant、tenantAuthority 等合同 section；以 action 前缀启发式代替 | `AuthorizationRouteManifestEntry.requiresCapability()`、`AuthorizationContextV1`、`AuthorizationShadowEvaluator` 静态审计 | **fail** |
| P1A-4 | 415 条 deployment-aware catalog；未注册入口 fail closed；BFF/launcher path 分离 | 415 条、checksum 一致，coverage/duplicate/unknown route tests 存在并通过 | manifest/hash；launcher/BFF route coverage | pass |
| P1A-5 | legacy lane 只映射当前 credential lane，不自动提升 root/platform/security | adapter 对 upstream-admin/control/runtime/task/Worker 分 lane；多来源 conflict | `LegacyAuthorizationContextAdapter` 及其 9 tests | pass |
| P1A-6 | legacy enforcement 唯一生效；canonical sidecar 计算并记录 decision diff | launcher sidecar 非 enforcement；Observer BFF 仅 catalog/test-scope dependency，无 runtime evaluator/audit wiring | launcher interceptor/advice tests；BFF `pom.xml` 和 `src/main` 静态审计 | **partial** |
| P1A-7 | append-only 脱敏 decision/diff，可按指定字段从 repository/service/test 查询 | entity/repository/store/query 与 redaction tests 已交付；无 HTTP/CLI 入口 | `AuthorizationDecisionAuditStoreImplTest`、draft/schema tests | pass |
| P1A-8 | 不 seed/签发，不做 P1B/P1C/CLI/external/Gateway/Worker/Codex route 变更 | scoped diff 未发现上述扩张；Worker/route 保持不变 | changed-path review、forbidden-surface review、secret scan | pass |

## Implementation Quality

- scope and changed surface: 核心 foundation/shadow 改动位于约定模块；未触碰 external/Gateway/Worker route。Observer BFF 只新增测试依赖，形成已披露但未批准的 runtime coverage 缺口。
- maintainability and duplication: source-controlled catalog 是合理单一入口；但 `canonicalAction.startsWith("runtime.")` 把 section policy重新编码为启发式第二来源，偏离冻结合同。
- error handling and edge cases: unknown action/route/version 可稳定 fail closed；required-section 的 action-specific unknown/missing 分类未实现。
- contract, data and compatibility: schema/migration 和 `/actuator` amendment 有证据；P1A-3 不满足，未来 cutover 会基于错误的 context completeness 信号。
- terminology and documentation: lane、instance 和 shadow/non-enforcing 术语基本一致；实施结果错误勾选 P1A-3/P1A-6，已由本签核纠正。

## Evidence Matrix

| Item | Risk | Unit | Integration | E2E | Playwright | Manual | Evidence | Coverage |
|---|---|---|---|---|---|---|---|---|
| P1A-1 | critical | schema/JPA | MySQL 3/3 | N/A | N/A | static SQL review | reviewer command + migrations | covered |
| P1A-2 | critical | identity 10/10 | launcher guard | N/A | N/A | config review | identity/guard tests | covered |
| P1A-3 | critical | existing 4 tests pass but omit required-section matrix | none | N/A | N/A | code/catalog audit | 20 `runtime.*`: 1 ask、19 non-ask；全部被 capability heuristic 命中 | **missing / failed** |
| P1A-4 | critical | catalog/unknown route | launcher+BFF coverage | N/A | N/A | hash/count | 415 entries + approved SHA-256 | covered |
| P1A-5 | critical | adapter 9 tests | interceptor context flow | N/A | N/A | lane mapping review | adapter tests/source | covered |
| P1A-6 | critical | launcher interceptor/advice | launcher route/Actuator；BFF continuity | N/A | N/A | BFF main-surface review | BFF common dependency is test scope only | **partial** |
| P1A-7 | critical | audit draft/store/query | MySQL schema | N/A | N/A | sensitive-field review | audit store/entity/repository tests | covered |
| P1A-8 | critical | config/guard tests | launcher reactor | N/A | N/A | scoped diff/secret/forbidden review | no seed/external/Worker/CLI change | covered |

## Failed Items

1. **P1A-3 failed:** the approved resolver contract requires every action manifest to declare its minimum context sections. The CSV/entry model has no such field. `requiresCapability()` instead returns true for Worker Gateway or every `runtime.*` action. The catalog contains 20 `runtime.*` ingress, but the contract adds capability intent only to `runtime.ask`; the other 19 actions, including token exchange, preflight, task/session/message and artifact reads, are therefore incorrectly classified and can emit `AUTHZ_LEGACY_CAPABILITY_UNVERIFIED`.
2. **P1A-6 is partial:** Observer BFF has independent catalog coverage and continuity tests, but `navigator-common` is test-scope only and `src/main` has no shadow evaluator/audit wiring. The implementation disclosed this deviation, but the approved P1A contract did not record a catalog-only amendment for that deployment.

## Risks / Follow-ups

- [BUG-002](../workitems/BUG-002-p1a-required-section-contract.md) records the acceptance-found P1A-3 defect as `DRAFT`; it must be approved, implemented and independently re-signed before P1B.
- Project Owner must decide P1A-6 before re-signoff: either approve a narrow P1A amendment making Observer BFF catalog/test-only, or authorize an independently designed runtime shadow audit path. BFF production hardening remains out of scope either way.
- No current legacy allow/deny is changed because P1A is shadow-only. The defect is nevertheless blocking: the foundation cannot be trusted for later enforcement cutover.
- `NAVIGATOR_EXTERNAL_ENABLED`, Worker Gateway strict/external, Worker external and production remain disabled/unapproved; no signoff statement changes those boundaries.

## Final Decision

- decision: `rejected`
- rationale: P1A-3 is a critical, confirmed contract failure; passing tests do not cover or compensate for the missing required-section model. P1A-6 also lacks full deployment coverage or an approved scope amendment.
- blocking_items: `BUG-002`; `P1A-6 Observer BFF runtime-shadow disposition`
- follow_up_owner_and_due: Project Owner + Navigator authorization owner；必须在 P1B 授权或 P1A re-signoff 前关闭，无日期豁免。

## Signoff Marker

- acceptance_status: rejected
- acceptance_decision: rejected
- signed_off_by: Independent Signoff Reviewer
- signed_off_at: 2026-07-19
- acceptance_record: `docs/version-tracker/1.4.3-SNAPSHOT/evidence/GOV-001-p1a-independent-signoff.md`
- blocking_items: `BUG-002`, `P1A-6 Observer BFF runtime-shadow disposition`
- follow_up_required: yes
