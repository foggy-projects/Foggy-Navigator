---
doc_type: delivery-spec
delivery_type: cross-module
version: 1.4.3-SNAPSHOT
ticket: GOV-001-P1B-B0
status: ACCEPTED
canonical: true
canonical_slice: p1b-b0-preseed-inventory-and-owner-approval
parent_delivery_spec: GOV-001-upstream-permission-and-trust-boundary.md
execution_mode: ultra
approved_by: project-owner-user-confirmed
approved_at: 2026-07-19
open_questions: []
---

# Delivery Spec: GOV-001 P1B-B0 Pre-seed Inventory and Owner-Approval Gate

## Document Purpose

- intended_for: ultra-implementation / independent-signoff / future-P1B-B owner
- purpose: 在不读取真实 profile、凭据、数据库或上游系统的前提下，交付真实 S1/S2 seed 之前必需的离线脱敏 inventory 合同、fixture validator 与审批运行手册。
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/GOV-001-p1b-b0-preseed-inventory-and-owner-approval.md`
- parent_contract: `GOV-001-upstream-permission-and-trust-boundary.md`, P1B seed/legacy mapping gate and AC-1 … AC-22.

## Goal

- version_goal: 让任何未来的 P1B real seed 都先证明其输入完整、可审计、无 secret，且所有冲突都被隔离；不能把 fixture、历史 local profile 或 CLI help 误当成真实授权事实。
- target_outcome: 提供一个纯离线 `navi.authorization.preseed-inventory.v1` validator 和 synthetic fixtures。它只能输出 `VALID`、`INVALID` 或 `QUARANTINED` 加稳定 reason code；它不产生 allow、seed plan、SQL、API request、credential 或 approval 结论。

## Scope

- in_scope:
  - 在 `navigator-common` 实现无 Spring、无 JPA、无 HTTP、无配置/profile/env 读取的纯离线 pre-seed inventory schema/codec/validator，以及只使用 synthetic data 的单元测试。
  - 固定输入 envelope：`schemaVersion=navi.authorization.preseed-inventory.v1`、`mode=OFFLINE_VALIDATE_ONLY`、精确 deployment `navigatorInstanceId` 与 `environmentProfile`、`records` 和 canonical SHA-256 checksum。
  - 每条 record 只表达 opaque/sanitized reference、source kind、upstream/tenant/ClientApp/namespace/owner-conflict facts、status/expiry/revocation/fingerprint prefix-suffix、proposed `principalType` / `credentialLane` / `disposition`、quarantine reason 和 non-secret approval reference。
  - 强制 legacy record 只能被标记为 `REQUIRES_APPROVAL` 或 `QUARANTINED`；不得自动提升为 `INSTANCE_ROOT`、`SAAS_PLATFORM`、`SAAS_PROVISIONING`、`SAAS_SECURITY_ADMIN` 或任意 security lane。
  - 交付 synthetic valid/invalid/quarantine fixtures、fixture README，以及 P1B inventory/mapping/owner-approval runbook。
- affected_modules: `navigator-common`; `docs/version-tracker/1.4.3-SNAPSHOT/workitems`; `docs/version-tracker/1.4.3-SNAPSHOT/runbooks`; synthetic fixture resources only.
- external_dependencies: none. 真实 inventory、mapping 和 approval 均不在本切片内取得或写入。

## Non-Goals

- out_of_scope:
  - 不读取 `accounts/`、`.navigator/*.env`、真实 profile、环境变量、数据库、网络、KMS/secret store 或任何上游系统。
  - 不生成或执行 seed/migration/SQL/API request，不连接 `ManagementCredentialVerifier`，不签发 credential/token/grant/tenant authority，不修改 route enforcement。
  - 不接入 `navi upstream` CLI、`UpstreamCliConfig`、worker-host dry-run、runtime readiness、Open API、Worker Gateway 或任何 external/production flag。
  - 不把 validator 的 `VALID` 解释为 owner approval、production approval、real seed authorization、Provider readiness、Worker Gateway readiness 或 production readiness。
  - 不创建 Worker、BizWorkerIdentity 或 WorkerPool member；尤其不得用本事项处理 Codex Physical Worker 路由。
- do_not_touch:
  - `foggy-world-sim`、`tms-x3` 和其他 sibling workspace。
  - 真实 Navigator/上游数据、真实 credential/token/key/password、`packages/navigator-frontend/src/views/ClaudeWorkerView.vue`、Observer BFF runtime、legacy management route family。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| Offline validator is a precondition, not an authorization engine | A fixture check must never become a way to create authority | No network/DB/profile/env access; no Spring bean or HTTP endpoint |
| Canonical checksum is deterministic SHA-256 over canonicalized envelope excluding its checksum field | Reviewer can detect accidental input drift without preserving secret material | Stable object-key order; fixtures document the exact computation; mismatch is `INVALID` |
| Validator only classifies, never approves | Prevent `VALID` from being misused as a seed or security decision | Output is `VALID`, `INVALID`, or `QUARANTINED`; no `ALLOW`, `APPROVED`, or seed artifact |
| Legacy records require explicit review | P0.5 forbids automatic legacy promotion | Legacy upstream-admin / scope / tenant-list data cannot infer root, platform or security authority |
| Real facts stay outside the tracked repository | Avoid secret leakage and fake authority evidence | Future owner submits a secured source plus a redacted evidence/approval artifact; B0 fixtures remain synthetic |

## Acceptance Criteria

- [ ] AC-1: A hermetic validator accepts only `navi.authorization.preseed-inventory.v1` with `mode=OFFLINE_VALIDATE_ONLY`, one nonblank deployment instance/profile pair, records, and a matching canonical checksum; invalid shape/version/mode/deployment/checksum is `INVALID` with stable reason code.
- [ ] AC-2: A record may contain only the approved sanitized fields. Secret-like fields or values (credential material, raw verifier/hash, token, key, password, profile/env content, upstream user token, full request body) are rejected before any classification and are never echoed.
- [ ] AC-3: Owner/upstream/tenant/ClientApp conflicts, missing authority facts, revoked/expired/no-expiry credentials, duplicate/conflicting tenant authority, and ambiguous source mappings become `QUARANTINED` with a stable reason; they cannot be downgraded to `VALID`.
- [ ] AC-4: Legacy upstream-admin/scope/tenant-list input cannot auto-promote to any S1/S2 root/platform/security principal or credential lane; it is `REQUIRES_APPROVAL` or quarantined.
- [ ] AC-5: All repository fixtures are synthetic. The validator performs no network, database, profile, environment, secret-store or CLI configuration access, and tests prove this by construction.
- [ ] AC-6: The runbook distinguishes local fixture validation from real inventory, describes the mandatory secure-source/owner-approval/four-eyes handoff, records only IDs/fingerprints/statuses/reason codes in durable evidence, and explicitly prohibits real seed/cutover until a separately approved P1B-B contract.
- [ ] AC-7: `mvn test -pl navigator-common` passes, focused validator tests include valid S1/S2 candidates and every critical quarantine/secret/legacy/checksum negative, fixture docs are internally consistent, and `git diff --check` passes.

## Contract / Data / Security Constraints

- API or event contract: no runtime API, event, CLI command, Spring configuration property, DB schema, migration, or endpoint is introduced.
- data and migration: `navigatorInstanceId` and `environmentProfile` are input-bound facts only. The B0 model is not a seed source and does not persist any record. Real source files remain outside tracked paths and are never read during repository tests.
- compatibility and rollback: additive pure-library/test/documentation slice. Removing it leaves existing authorization, routes, credentials and configuration unchanged.
- permissions and secrets: log/error/result objects may return only classification, reason code, record alias/count and checksum; never input values. Inputs under `accounts/` must be rejected if a file adapter is added. Do not read or print `.navigator` profiles, environment variables or real credentials.

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| AC-1 | critical | schema/mode/deployment/checksum unit tests | executed Surefire report and deterministic fixture checksum |
| AC-2 | critical | recursive forbidden-field/value/redaction tests | source review plus negative tests with no echo assertion |
| AC-3 / AC-4 | critical | conflict, expiry/revocation, missing mapping, duplicate authority and legacy-promotion tests | stable reason-code matrix |
| AC-5 | critical | isolated parser/validator construction review; no Spring/JPA/HTTP/config dependencies | dependency/source scan and test report |
| AC-6 | major | runbook and fixture review | sanitized sample, approval template and explicit no-seed gate |
| AC-7 | critical | `mvn test -pl navigator-common`, fixture consistency check, `git diff --check` | exact command results and changed-path list |

## Risks and Open Questions

- known_risks: B0 does not establish that any S1/S2 record is true. A structurally valid synthetic/secured inventory can still be wrong; only the authority owning the source mapping and a named approval can resolve that.
- open_questions: none for this offline B0 slice. The following remain deliberate P1B-B gates: target `navigatorInstanceId`/profile; S1 subject/source mapping; exact `tms-x3` upstream identity; tenant owner authority; ClientApp/owner/operator mapping; credential fingerprint/status/lane from a secret-safe source; mapping authority, effective time, conflict disposition and four-eyes approval; KMS/verifier ownership; `UPSTREAM_OWNED` lifecycle/offboarding; Worker allocation facts.

## Ultra Execution Contract

- Read this work item, root `AGENTS.md`, `CLAUDE.md`, `navigator-runtime-provisioning` safety rules and the accepted P1B-A signoff before implementation.
- Choose reasonable class/package/test-fixture details inside the scope. Do not introduce a generalized IAM/ABAC engine or a runtime seed mechanism.
- Treat any need for real input, profile/env/DB/network access, approval acceptance, seed/credential issuance, route cutover, CLI release, Gateway/Worker/external/production change, or Codex topology change as `NEEDS_REPLAN` and stop that expansion.
- Record implementation summary, all changed paths, exact test output, validator limitations and residual risks here. Set only `READY_FOR_SIGNOFF`; do not self-accept.

## Implementation Result

- implementation_summary: Added the pure `navi.authorization.preseed-inventory.v1` JSON codec,
  canonical checksum implementation, schema, reason/classification/result models and validator. The
  validator accepts only `OFFLINE_VALIDATE_ONLY`, classifies only `VALID` / `INVALID` /
  `QUARANTINED`, and performs no seed, approval, credential, route, Worker, Gateway or external
  action. Its JSON boundary enables Jackson strict duplicate-field detection before constructing a
  tree, so no later duplicate key can overwrite a secret-like or forbidden earlier value. It rejects
  secret-like fields/values before classification, quarantines authority and credential conflicts,
  and prohibits legacy promotion. Expiry is strict (`expiresAt` must be after the validation instant);
  a package-visible fixed-UTC `Clock` seam keeps unit tests deterministic, while the public
  constructor retains UTC wall-clock classification without reading configuration.
- changed_paths:
  - `navigator-common/src/main/java/com/foggy/navigator/common/authorization/preseed/PreseedInventoryCanonicalizer.java`
  - `navigator-common/src/main/java/com/foggy/navigator/common/authorization/preseed/PreseedInventoryClassification.java`
  - `navigator-common/src/main/java/com/foggy/navigator/common/authorization/preseed/PreseedInventoryCodec.java`
  - `navigator-common/src/main/java/com/foggy/navigator/common/authorization/preseed/PreseedInventoryReasonCode.java`
  - `navigator-common/src/main/java/com/foggy/navigator/common/authorization/preseed/PreseedInventorySchemaV1.java`
  - `navigator-common/src/main/java/com/foggy/navigator/common/authorization/preseed/PreseedInventoryValidationResult.java`
  - `navigator-common/src/main/java/com/foggy/navigator/common/authorization/preseed/PreseedInventoryValidator.java`
  - `navigator-common/src/test/java/com/foggy/navigator/common/authorization/preseed/PreseedInventoryValidatorTest.java`
  - `navigator-common/src/test/resources/authorization/preseed-inventory/README.md`
  - `navigator-common/src/test/resources/authorization/preseed-inventory/valid-s1-s2-candidates.json`
  - `navigator-common/src/test/resources/authorization/preseed-inventory/quarantined-owner-conflict.json`
  - `navigator-common/src/test/resources/authorization/preseed-inventory/invalid-checksum.json`
  - `docs/version-tracker/1.4.3-SNAPSHOT/runbooks/GOV-001-p1b-preseed-inventory-and-owner-approval.md`
  - this canonical work item
- tests_and_results:
  - `mvn test -pl navigator-common -Dtest=PreseedInventoryValidatorTest` — 10 tests, 0 failures,
    0 errors, 0 skipped; `BUILD SUCCESS` (2026-07-19 17:45 CST). Covers valid S1/S2, checksum,
    recursive secret-like field/value redaction, duplicate allowed/forbidden JSON key rejection
    before tree construction (stable `PRESEED_DOCUMENT_MALFORMED`, no input echo),
    owner/source/ClientApp/tenant conflicts, revoked/expired/no-expiry credentials, exact-expiry
    boundary with fixed UTC clock, all three legacy source kinds including `LEGACY_TENANT_LIST`,
    disposition quarantine and hermetic dependency scan.
  - `mvn test -pl navigator-common` — 120 tests, 0 failures, 0 errors, 3 pre-existing skipped
    Testcontainers integration tests; `BUILD SUCCESS` (2026-07-19 17:45 CST).
  - `fixturesAndReadmeRemainSyntheticAndChecksumConsistent` recomputed matching canonical checksums:
    valid `428d140a6f31f353815de24bfdbfaa2b2f6ea5a0083e05be0e917f3d18519a07`; quarantined
    `3843b890cff39d0e07718ec6fbd8face109f360cdf0361c620dd4188d16936db`; the invalid fixture
    intentionally retains its all-zero mismatch checksum.
  - Final scoped source scan found no Spring/JPA/SQL/network/filesystem/HTTP/env/profile dependency
    or `Instant.now()` call; scoped high-confidence secret scan and trailing-whitespace scan had no
    matches. `git diff --check` exited 0; it reported only pre-existing CRLF conversion warnings in
    unrelated shared-worktree files.
- manual_or_experience_evidence: Reviewed the result model and runbook against the offline-only
  contract: results expose only classification, stable reason, safe alias/count and checksum; the
  runbook states explicitly that `VALID` is not owner approval, real seed authorization, external,
  Worker Gateway or production readiness. Jackson duplicate-field parsing errors are intentionally
  reduced to the safe `PRESEED_DOCUMENT_MALFORMED` reason rather than exposing parser diagnostics or
  input values. No real profile, credential, account, database, network, CLI or sibling workspace
  was read or modified.
- deviations: none. The package-visible `Clock` constructor is a deterministic test seam inside the
  pure validator; it is not a runtime configuration or authority source.
- residual_risks:
  - B0 establishes no real S1/S2 fact. Target `navigatorInstanceId`/profile, source mapping,
    tenant/ClientApp owner authority, credential/verifier/KMS ownership and four-eyes approval all
    remain unprovided.
  - B0 is not an input channel for a real inventory and cannot be used for seed, approval, credential
    issuance, route cutover, external/Gateway/Worker enablement or production release. Those require
    a separately approved P1B-B contract and independent review.
  - The public constructor classifies expiry against the current UTC instant. This preserves expiry
    semantics; deterministic tests use a fixed instant and the fixtures use a far-future synthetic
    expiry, so test outcomes do not drift with wall-clock time.
- readiness: ACCEPTED after independent signoff; this only accepts the offline B0 precondition.

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted
- signed_off_by: Independent Signoff Reviewer (root session)
- signed_off_at: 2026-07-19
- acceptance_record: `docs/version-tracker/1.4.3-SNAPSHOT/evidence/GOV-001-p1b-b0-independent-signoff.md`
- blocking_items: none
- follow_up_required: yes

## References

- parent: [GOV-001 trust boundary](./GOV-001-upstream-permission-and-trust-boundary.md)
- accepted prerequisite: [GOV-001 P1B-A signoff](../evidence/GOV-001-p1b-a-independent-signoff.md)
- P0.5 no-auto-promotion review: [seed/legacy mapping review](../evidence/GOV-001-p0.5-seed-legacy-mapping-review.md)
- provisioning safety: `.agents/skills/navigator-runtime-provisioning/SKILL.md`
