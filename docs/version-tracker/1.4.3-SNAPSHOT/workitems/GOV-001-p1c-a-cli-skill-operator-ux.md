---
doc_type: delivery-spec
delivery_type: cross-module
version: 1.4.3-SNAPSHOT
ticket: GOV-001-P1C-A
status: ACCEPTED
canonical: true
canonical_slice: p1c-a-cli-skill-operator-ux
parent_delivery_spec: GOV-001-upstream-permission-and-trust-boundary.md
execution_mode: ultra
approved_by: project-owner-user-confirmed
approved_at: 2026-07-19
open_questions: []
---

# Delivery Spec: GOV-001 P1C-A CLI, SKILL, and Operator Permission UX

## Document Purpose

- intended_for: ultra-implementation / independent-signoff / CLI operators
- purpose: 将已 accepted 的 typed-management introspection 以 fail-closed、无 secret 的方式接入上游 CLI，并固定 CLI/Skill/runbook 对权限范围、credential lane 和外部开关的解释。
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/GOV-001-p1c-a-cli-skill-operator-ux.md`
- parent_contract: `GOV-001-upstream-permission-and-trust-boundary.md`, P1C operator UX backlog；P1A 和 P1B-A accepted 是唯一后端前提。

## Goal

- version_goal: 让使用 `navi upstream` 的 S1/S2 操作者在任何 mutation 前能看见“当前 credential 实际代表什么”，并且不会把本地 profile 检查、legacy admin key、Open API 或 Gateway 开关误认为服务端授权或 production readiness。
- target_outcome: 提供 typed-management-only 的 `auth whoami`、`inspect permissions` 与受限 `--explain-auth` preflight，连同清晰的 help/FAQ/runbook 和 source-to-help provenance 检查；所有 ambiguous、mixed-lane 或非 typed-management 情况均 fail closed。

## Scope

- in_scope:
  - 在 `navigator-open-sdk` CLI 适配现有五条 canonical typed-management auth endpoint 中只读的 `whoami`、`permissions`、`explain`；调用必须使用 exactly one `NAVI_PRINCIPAL_CREDENTIAL`（或等价显式 typed credential source），不得从 legacy/admin/control/runtime 值 fallback。
  - 增加 canonical `auth whoami`、`inspect permissions`，以及仅为 typed-management endpoint 的受限 `--explain-auth` 入口。它必须要求 registered `routeId`、`actionId` 和安全引用形式的目标信息；不得将任意 legacy mutation 自动改写为 explain，或尝试解析真实 owner/grant/tenant predicate。
  - 将 typed profile 的本地 metadata 检查限制为 `NAVI_NAVIGATOR_INSTANCE_ID`、`NAVI_ENVIRONMENT_PROFILE`、`NAVI_EXPECTED_PRINCIPAL_TYPE`、`NAVI_EXPECTED_CREDENTIAL_LANE` 的一致性提示。它们永远不是服务端授权事实。
  - 重构 `config check` 的输出合同：只返回/显示 `VALID`、`INVALID` 或 `UNVERIFIED` 的本地配置结论，明确 `authorization=UNVERIFIED`；不得输出 `ALLOW`、有效 secret、完整 credential、token、key 或未掩码 profile 值。
  - 对 profile 中同时出现 typed principal credential 与 legacy admin/control/runtime/user/token credential，要求明确 credential-lane/source 选择或逐 lane 独立检查；不得合并、静默优先级选择、fallback 或从主体 authority ceiling 推导当前 credential 的 action 权限。
  - 顶层及有关 command help、`.agents/skills/navigator-runtime-provisioning/SKILL.md` FAQ、1.4.3 runbook 说明：`NAVIGATOR_EXTERNAL_ENABLED` 只控制 `/api/v1/open/**` 路由；Gateway strict 是 Worker-principal 要求、不是网络开关且当前不可开启；Codex 只能走 existing Physical Worker 的 `worker-host verify` + `worker-host update`/`claudeCode.codexConfig`，不得创建 BizWorkerIdentity 或 WorkerPool member；legacy `NAVI_ADMIN_API_KEY` 不是 S1 root 或 S2 platform/security-admin credential。
  - 增加 CLI source version、help snapshot 与 canonical `route-manifest-v1.csv` 的 provenance/compatibility 校验。canonical manifest 是唯一 policy source；CLI 可携带生成的 version/checksum metadata，但不得维护第二份可授权的 policy。已发布 `tools/navigator-upstream` 1.0.18 metadata 与源码 1.0.21 的差异必须显式标识为 artifact drift，而不是伪造新发布物。
  - 使用 HTTP fixture/unit tests 验证请求、响应、redaction、mixed-lane 拒绝、help snapshot 和 provenance；不连接真实 Navigator、上游、profile、数据库或 credential verifier。
- affected_modules: `navigator-open-sdk`; `.agents/skills/navigator-runtime-provisioning`; `docs/version-tracker/1.4.3-SNAPSHOT/workitems`、`runbooks`，以及仅为 provenance 的受控 source/test resource。
- external_dependencies: P1B-A existing typed-management endpoint contract and canonical route manifest only. No real S1/S2 subject, credential, tenant, ClientApp or upstream environment is required.

## Non-Goals

- out_of_scope:
  - 不 seed、签发、轮换、撤销或读取任何真实 principal/credential/grant/tenant authority；不读 `.navigator/**`、`accounts/**`、环境变量中的真实 secret 或真实 profile。
  - 不修改 legacy route-family authorization、target/owner/grant/tenant resolver、shadow/cutover mode、Gateway client/server、Worker external、Open API external、Provider 或 production configuration。
  - 不把 `explain` 的 `allowed` 展示为某个 legacy resource 的最终 allow；当前 endpoint 不解析真实 target predicate，结果只能是 `PREFLIGHT`/`nonBinding=true`，mutation 必须重新由服务端授权。
  - 不创建 Worker、BizWorkerIdentity 或 WorkerPool member；不得以任何 CLI/help 变更修复 Codex Physical Worker 路由。
  - 不发布 archive/package、上传 artifact、修改已发布 release checksum/URL，或把 `NAVIGATOR_EXTERNAL_ENABLED=true`、Gateway strict、Worker readiness 误写成 production ready。
- do_not_touch:
  - `foggy-world-sim`、`tms-x3` 与所有 sibling workspace；真实上游/生产数据、KMS/secret store。
  - `packages/navigator-frontend/src/views/ClaudeWorkerView.vue`、Observer BFF runtime、P1A/P1B accepted implementation、legacy Worker/Codex routing and external/Gateway settings。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| Typed management is an explicit, separate CLI lane | A legacy key has different scope and cannot be upgraded by naming or profile co-location | Typed introspection never falls back to `NAVI_ADMIN_API_KEY`, `NAVI_CONTROL_API_KEY`, runtime credentials, user token, or admin login token |
| Local config state is not authorization | Client-side metadata and loopback URL cannot prove server-side owner/grant/policy | `config check` has only `VALID|INVALID|UNVERIFIED`; it never returns `ALLOW` |
| Authority ceiling and current credential actions are separate | S1 subject-wide authority must not silently become control/security lane authority | whoami/permissions preserve both server fields and do not union multiple credentials |
| Explain is non-binding and narrow | Existing P1B-A explain is not a legacy target resolver | Require registered route/action and redacted references; show `nonBinding=true`, never reuse decision ID for mutation |
| Canonical route manifest owns policy provenance | Prevent stale help or CLI-local policy from becoming an authorization source | Build packages the canonical manifest as the CLI's narrow fail-closed explain input guard; checksum/count verify that package copy and server enforcement remains authoritative |
| Published artifact history remains truthful | Changing a release manifest without creating its archive would be misleading | Source 1.0.21 vs published 1.0.18 drift is surfaced; release/publish is a later separately approved action |

## Acceptance Criteria

- [x] AC-1: `auth whoami` and `inspect permissions` call only the typed-management canonical endpoints with exactly one typed principal credential source, redact all secret-bearing data, and print schema/principal/lane/instance/profile/status/expiry/fingerprint plus authority ceiling and effective actions as distinct fields.
- [x] AC-2: A supported `--explain-auth` path accepts only registered typed-management route/action identifiers and redacted target/impact/reason references, sends a non-mutating request to `/api/v1/management/v1/auth/explain`, prints `PREFLIGHT` and `nonBinding=true`, and states that target owner/grant/tenant enforcement remains server-side and unresolved for legacy routes.
- [x] AC-3: Missing, ambiguous, conflicting, legacy-only, or mixed typed/legacy credential sources fail closed before HTTP dispatch with a stable error/reason; no source union, fallback, lane escalation, or implicit `NAVI_ADMIN_API_KEY` use occurs.
- [x] AC-4: `config check` reports only `VALID|INVALID|UNVERIFIED` local configuration state and `authorization=UNVERIFIED`; it masks/omits secret values and cannot be read as an allow decision. Tests cover absent metadata, mismatch, multiple lane source, and compatible typed fixture cases.
- [x] AC-5: Top-level and related CLI help, Skill FAQ and runbook explicitly preserve all four hard boundaries: Open API gate only; strict Gateway is not network exposure and remains unavailable; Codex existing Physical Worker path only; legacy admin credential is not S1/S2 typed authority.
- [x] AC-6: Source CLI version, help snapshot and canonical route manifest provenance are validated by automated tests/checks; a stale published artifact is called out as drift without altering archive/release claims or publishing a package.
- [x] AC-7: Focused CLI HTTP-fixture tests and `mvn test -pl navigator-open-sdk` pass; documentation/provenance checks and `git diff --check` pass. No test reads real profile, prints credentials, calls a live upstream, or enables external/production behavior.

## Contract / Data / Security Constraints

- API or event contract: consume only P1B-A `GET /api/v1/management/v1/auth/whoami`, `GET /permissions`, and `POST /explain`; no backend endpoint, schema, route enforcement, token issuance or legacy API contract change is introduced.
- data and migration: no persisted data or migration. Typed profile metadata is client-local advisory input and must never be accepted as server identity/scope proof.
- compatibility and rollback: additive CLI/help/docs slice. Existing legacy commands retain behavior but cannot implicitly gain typed-management permissions; removing the new commands restores current behavior without backend migration.
- permissions and secrets: all secrets are transport-only and never printed, persisted by this slice, put in snapshots, fixtures, or evidence. Tests use synthetic opaque values only. A caller must explicitly choose a single typed credential source; server response remains the only authority decision source.

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| AC-1/AC-3 | critical | CLI HTTP fixture tests for headers, endpoint paths, redaction and credential conflict rejection | exact Surefire command/result and request assertions |
| AC-2 | critical | fixture tests for registered and unregistered route/action plus non-binding output | response/body redaction and no-mutation assertion |
| AC-4 | critical | isolated config tests for each state and no `ALLOW` vocabulary | stdout/stderr assertions with synthetic secrets |
| AC-5 | major | help/FAQ/runbook snapshot review | test assertions and changed paths |
| AC-6 | critical | source-to-packaged canonical manifest byte check, exact typed route/action set, invalid-resource fail-closed validation, checksum/version provenance and artifact-drift assertion | no second CLI allowlist or release artifact claim |
| AC-7 | critical | `mvn test -pl navigator-open-sdk`, scoped documentation/provenance checks, `git diff --check` | exact commands, results, unrun reason, changed-file secret scan |

## Risks and Open Questions

- known_risks: P1B-A endpoint data is fixture-only until a separately approved P1B-B real inventory/seed establishes actual typed S1/S2 principals. Explain currently cannot resolve legacy resource owner/grant/tenant predicates. Existing 1.0.18 archive remains published and cannot contain this UX slice until a separate release task.
- open_questions: none for this bounded implementation. P1C route-family enforcement/cutover, P1B-B real seed, P2 signed upstream-user/strict Gateway, P3 production boundary and P4 telemetry/deprecation remain separate gated work.

## Ultra Execution Contract

- Read this work item, root `AGENTS.md`, `CLAUDE.md`, `navigator-runtime-provisioning` skill, accepted P1B-A signoff and current CLI test conventions before implementation.
- Choose file/class/function details within this contract. Do not introduce a second authorization engine, CLI-local policy evaluator, credential lifecycle API, runtime profile reader, package publish flow, or an unapproved route cutover.
- Treat any need for real profile/credential/upstream access, target resolver, legacy mutation adaptation, Gateway/Worker/external/production change, release manifest rewrite, or Codex topology alteration as `NEEDS_REPLAN` and stop that expansion.
- Record implementation summary, changed paths, exact tests, documentation checks, drift limitations and residual risks here. Set only `READY_FOR_SIGNOFF`; do not self-accept.

## Implementation Result

- implementation_summary: Added a strictly typed-management CLI lane for `auth whoami`,
  `inspect permissions`, and a narrow non-binding `--explain-auth` preflight. The
  lane resolves exactly one direct or explicitly named `NAVI_PRINCIPAL_CREDENTIAL`,
  constructs an isolated HTTP client, and sends only `X-Navi-Principal-Credential`
  to the three P1B-A read/preflight endpoints. It rejects missing, ambiguous,
  legacy-only, and mixed typed/legacy sources before dispatch; this includes
  ClientApp runtime, generic runtime, task-scoped, and Worker credential
  environment sources. `config check` now produces only the local
  `VALID|INVALID|UNVERIFIED` tri-state plus `authorization=UNVERIFIED`. Output
  keeps authority ceiling and current credential actions separate, omits
  credential/reference/decision material, and reports the P1B-A omission of
  schema version and credential fingerprint as `NOT_SUPPLIED_BY_SERVER` rather
  than deriving either locally. CLI help, the provisioning SKILL, and the
  runbook pin the Open API, Gateway, Codex Physical Worker, and legacy-admin
  credential boundaries. The explain input guard is parsed from the build-time
  packaged canonical manifest rather than a CLI-local route/action map; checksum
  and count verify the packaged copy, fail closed on an invalid resource, and never
  make a local authorization decision. Source/published CLI drift remains
  informational only.
- changed_paths:
  - `navigator-open-sdk/pom.xml`
  - `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/api/ManagementAuthApi.java`
  - `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/cli/CliArguments.java`
  - `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/cli/CliProvenance.java`
  - `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/cli/TypedManagementExplainCatalog.java`
  - `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/cli/UpstreamCli.java`
  - `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/cli/UpstreamCliConfig.java`
  - `navigator-open-sdk/src/main/resources/com/foggy/navigator/sdk/cli/authorization-provenance.properties`
  - `navigator-open-sdk/src/test/java/com/foggy/navigator/sdk/cli/UpstreamCliTest.java`
  - `navigator-open-sdk/src/test/resources/com/foggy/navigator/sdk/cli/p1c-hard-boundary-help-snapshot.txt`
  - `.agents/skills/navigator-runtime-provisioning/SKILL.md`
  - `docs/version-tracker/1.4.3-SNAPSHOT/runbooks/GOV-001-p1c-typed-management-cli-operator-ux.md`
  - this work item and `docs/version-tracker/1.4.3-SNAPSHOT/README.md` (only the
    P1C-A state handoff; inherited P1A/P1B content remains outside this slice).
- tests_and_results:
  - 2026-07-19: `mvn clean test -pl navigator-open-sdk -Dtest=UpstreamCliTest` —
    `BUILD SUCCESS`, 124 tests, 0 failures/errors/skips. It covers isolated
    typed headers, no tenant/legacy header propagation, output redaction,
    source conflicts, tri-state config output, preflight constraints, help
    snapshot, source-to-packaged manifest equality, exact five P1B-A typed
    route/action pairs, invalid-resource fail-closed behavior, provenance,
    artifact drift, and SKILL/runbook text.
  - 2026-07-19: `mvn clean package -pl navigator-open-sdk` — `BUILD SUCCESS`,
    165 tests, 0 failures/errors/skips; the resulting
    `navigator-open-sdk-1.0.21.jar` was produced locally only and was not
    published.
  - 2026-07-19: `jar tf navigator-open-sdk/target/navigator-open-sdk-1.0.21.jar
    | rg '^authorization/route-manifest-v1\.csv$'` — exit 0; the build artifact
    contains exactly the canonical manifest resource. `unzip -p
    navigator-open-sdk/target/navigator-open-sdk-1.0.21.jar
    authorization/route-manifest-v1.csv | sha256sum` —
    `55cd6b2f67c98ace16fcc58334d9dfccb8f36d7045cd9f5eb5f6bd5ba58231f2`.
  - 2026-07-19: `git diff --check` — exit 0 (only pre-existing CRLF conversion
    warnings in the shared dirty tree); scoped high-confidence secret scan with
    `rg -n --pcre2 '(?i)(AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]+PRIVATE KEY-----|sk-[A-Za-z0-9_-]{20,})'`
    over P1C SDK/docs/SKILL paths — exit 1 with no matches.
- manual_or_experience_evidence: Fixture-only HTTP requests reached only the local
  test server. The provenance test verifies the source `1.0.21`, published
  `1.0.18`, `SOURCE_NEWER_THAN_PUBLISHED`, and canonical manifest identity of
  420 entries / 421 physical lines including header / SHA-256
  `55cd6b2f67c98ace16fcc58334d9dfccb8f36d7045cd9f5eb5f6bd5ba58231f2`.
  The CLI test verifies that the resource packaged into the SDK is byte-identical
  to that source manifest, derives precisely the five P1B-A typed-management
  route/action pairs from it, and rejects checksum-mismatched or malformed input.
  `auth whoami --help` and `inspect permissions --help` are explicitly tested
  without credential resolution or HTTP dispatch. Tests use synthetic opaque
  values only; they do not read real profiles, credentials, databases, or
  upstream services.
- deviations: No approved-contract deviation. The new config tests exposed a
  null-metadata `List.of` failure and the review identified four legacy
  runtime/task/Worker environment names absent from the CLI load allowlist.
  Independent pre-signoff also identified the original CLI-local explain
  route/action map as an AC-6 policy-provenance risk. All three were corrected
  inside P1C-A before the final test run by deriving the input guard from the
  packaged canonical manifest; no API, policy, route, external, Gateway, Worker,
  or production behavior changed.
- residual_risks: P1B-A remains fixture-only and does not supply `schemaVersion`
  or credential fingerprint, so the CLI intentionally prints
  `NOT_SUPPLIED_BY_SERVER`; no real S1/S2 inventory, verifier, seed, mapping,
  or owner approval exists. Explain remains a non-binding preflight and cannot
  resolve legacy target owner/grant/tenant predicates. The published `1.0.18`
  archive does not contain this slice; source `1.0.21` provenance is not a
  release claim. The packaged catalog intentionally fails closed if a future
  server manifest changes, so a matching SDK build/release is required before
  any new typed-management route can be preflighted. P1C route cutover, P1B-B
  factual gate, P2 Gateway work, P3 production boundary, and P4
  telemetry/deprecation remain out of scope.
- readiness: ACCEPTED by independent signoff; this does not authorize any route
  cutover, credential issuance, external/Gateway/Worker enablement, artifact
  publication, or production use.

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted
- signed_off_by: Independent Signoff Reviewer (Codex)
- signed_off_at: 2026-07-19
- acceptance_record: `docs/version-tracker/1.4.3-SNAPSHOT/evidence/GOV-001-p1c-a-independent-signoff.md`
- blocking_items: none
- follow_up_required: yes (P1B-B factual inventory gate; P2-P4 owner decisions)

## References

- parent: [GOV-001 trust boundary](./GOV-001-upstream-permission-and-trust-boundary.md)
- accepted prerequisite: [P1B-A signoff](../evidence/GOV-001-p1b-a-independent-signoff.md)
- typed endpoint contract: `business-agent-module/.../ManagementAuthEndpointService.java`
- canonical policy provenance: `navigator-common/src/main/resources/authorization/route-manifest-v1.csv`
- provisioning safety: `.agents/skills/navigator-runtime-provisioning/SKILL.md`
