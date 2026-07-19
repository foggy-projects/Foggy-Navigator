---
acceptance_scope: feature
version: 1.4.3-SNAPSHOT
target: GOV-001-P1C-A
status: signed-off
decision: accepted
signed_off_by: Independent Signoff Reviewer (Codex)
signed_off_at: 2026-07-19
reviewed_by: Independent preflight reviewer
blocking_items: []
follow_up_required: yes
evidence_count: 13
---

# GOV-001 P1C-A Independent Signoff

## Document Purpose

- intended_for: Project Owner / future P1B-B-P4 decision owners / CLI operators
- purpose: 对 fixture-only typed-management CLI/SKILL permission-visibility slice 形成独立、证据驱动的签核结论。

## Background

- delivery_spec: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/GOV-001-p1c-a-cli-skill-operator-ux.md`
- target_outcome: 操作者可看到单一 typed-management credential 的服务器声明和本地非授权配置状态，同时不能把 legacy credential、profile、external flag 或 CLI guard 误当成授权或 production readiness。
- signoff_scope: 仅 GOV-001 P1C-A 的 SDK/CLI、provenance、SKILL/runbook/test 路径。共享脏工作树中的 P1A/P1B、frontend、Observer BFF、Worker/Gateway、external/production 等其他变更未被归入本签收。

## Acceptance Basis

- approved delivery spec: P1C-A `READY_FOR_SIGNOFF` canonical spec，AC-1 至 AC-7。
- changed surface: `ManagementAuthApi`、typed credential source guards、`auth whoami` / `inspect permissions --explain-auth`、canonical-manifest packaged input guard、CLI provenance、help/FAQ/runbook 与 HTTP-fixture tests。
- independent test: 2026-07-19 18:42:20 +08:00，reviewer executed `mvn test -pl navigator-open-sdk`; exit 0 / `BUILD SUCCESS` / 165 tests / 0 failures / 0 errors / 0 skipped, including `UpstreamCliTest` 124/124.
- integrity: reviewer verified the packaged JAR manifest and source manifest both SHA-256 to `55cd6b2f67c98ace16fcc58334d9dfccb8f36d7045cd9f5eb5f6bd5ba58231f2`. Current `git diff --check` exit 0; only shared-tree CRLF conversion warnings were emitted. Reviewer scoped high-confidence secret scan found no match.

## Contract Conformance

| Item | Expected | Delivered evidence | Result |
|---|---|---|---|
| AC-1 | Exactly one typed principal credential; only typed endpoints/headers; secret-safe distinct identity/action output | `ManagementAuthApi` constructs only `X-Navi-Principal-Credential`; isolated `HttpHelper` has no tenant/control/admin/runtime fields; `typedManagementWhoamiUsesOnlyPrincipalHeaderAndNeverEchoesSecrets` and explicit-source fixture assertions passed | pass |
| AC-2 | Registered typed route/action only; safe references; non-binding preflight | `TypedManagementExplainCatalog` derives the input guard from canonical manifest; `inspectPermissionsExplain` requires all-or-none validated references and checks `nonBinding`; registered/unregistered/incomplete/binding-response fixture tests passed | pass |
| AC-3 | Missing/ambiguous/legacy-only/mixed sources deny before dispatch | `UpstreamCliConfig` source state plus `resolveTypedManagementPrincipalCredential` deny before creating the API client; missing, legacy-only, mixed, runtime/task/Worker and ambiguous source fixture negatives passed | pass |
| AC-4 | Local tri-state only; no allow decision or secret echo | `config check` prints `VALID|INVALID|UNVERIFIED` and `authorization=UNVERIFIED`; absent/mismatched/multiple/compatible fixture cases and redaction assertions passed | pass |
| AC-5 | CLI/Skill/runbook retain four hard boundaries | root/help snapshot plus `p1cSkillAndRunbookPreserveTypedCredentialAndTrustBoundaryFaq` assert Open API-only, Gateway-not-network, existing Physical Worker-only, and legacy-admin-not-S1/S2 semantics | pass |
| AC-6 | Canonical manifest is sole policy provenance; artifact drift truthful | Maven packages canonical resource; `p1cExplainInputGuardIsGeneratedFromTheCanonicalManifestAndFailsClosedWhenInvalid` proves byte identity, exact five P1B-A pairs, checksum/malformed fail-closed behavior and absence of old CLI-local map; `CliProvenance` marks 1.0.21 versus published 1.0.18 as `SOURCE_NEWER_THAN_PUBLISHED` without release claim | pass |
| AC-7 | Fixture tests, full SDK test, documentation/provenance and hygiene checks pass without real access | reviewer exact `mvn test -pl navigator-open-sdk` 165/0/0/0; prior clean package/JAR evidence agrees; fixture server only; diff and scoped secret scan clean | pass |

## Evidence Matrix

| Item | Risk | Unit | Integration | E2E | Playwright | Manual | Evidence | Coverage |
|---|---|---|---|---|---|---|---|---|
| AC-1 / AC-3 | critical | typed header/source/redaction fixture tests passed | SDK test suite passed | not required by fixture-only CLI contract | not applicable | changed-surface review | no legacy/control/runtime header or source union | covered |
| AC-2 | critical | registered/unregistered/reference/non-binding fixture tests passed | SDK test suite passed | no live Navigator required | not applicable | canonical-manifest review | server remains authoritative and mutation re-authorizes | covered |
| AC-4 | critical | tri-state/mismatch/mixed/redaction cases passed | SDK test suite passed | not required | not applicable | stdout contract review | `authorization=UNVERIFIED`, no `ALLOW` decision | covered |
| AC-5 | major | help/Skill/runbook snapshot assertions passed | SDK test suite passed | not required | not applicable | text/boundary review | four hard boundaries explicit | covered |
| AC-6 | critical | packaged-byte/checksum/five-pair/malformed-resource tests passed | packaged SDK resource verified | not required | not applicable | source-to-JAR hash comparison | no local authorizing policy or published-artifact rewrite | covered |
| AC-7 | critical | 165 SDK tests passed | reviewer exact Maven invocation passed | fixture-only by contract | not applicable | diff/secret scan | no real profile, credential, upstream, flag or production action | covered |

## Implementation Quality

- scope and changed surface: P1C remains additive CLI/documentation provenance work. Review found no P1C change to legacy route enforcement, owner/grant/tenant resolution, Worker/Gateway, Codex topology, external flags, release publication or production configuration.
- maintainability and duplication: the former CLI-local typed route/action map is absent; the narrow guard is parsed from the build-time packaged canonical manifest and only rejects unregistered input. It cannot grant authorization because the server endpoint still evaluates every request and `nonBinding=true` is required.
- error handling and edge cases: missing, explicit-missing, ambiguous and mixed credential sources fail before HTTP; partial safe-reference sets and malformed/mismatched manifest resources fail closed.
- contract, data and compatibility: no data migration or real profile access; published 1.0.18 remains unchanged and source 1.0.21 is explicitly drift-only. The CLI must be rebuilt/released with future manifest changes, which is an intended fail-closed compatibility condition.
- terminology and documentation: the reviewed help/FAQ/runbook preserves the Open API/Gateway/Worker/production separation and the existing Physical Worker prohibition.

## Risks / Follow-ups

- P1B-A endpoints and this CLI slice remain fixture-only; no real S1/S2 principal, verifier, inventory, owner mapping, seed, credential lifecycle or route-family cutover is accepted here.
- `explain` remains a narrow non-binding preflight and cannot resolve legacy owner/grant/tenant predicates.
- The published 1.0.18 archive does not contain P1C-A. A later release requires a separately approved publish/release task; source provenance is not an archive claim.
- P1B-B, P2, P3 and P4 remain separately gated. Their owner-decision inputs, external/production readiness, Worker Gateway strictness and legacy retirement are not implied by this decision.

## Final Decision

- decision: accepted
- rationale: every critical acceptance criterion has independent source/diff review and executed evidence. The source-derived guard was specifically rechecked to be restrictive-only, manifest-aligned and incapable of locally authorizing a request. No blocking deviation or boundary expansion was found.
- blocking_items: none for P1C-A
- follow_up_owner_and_due: Project Owner and named P1B-B/P2/P3/P4 decision owners; before any real inventory action, Gateway strictness, external/production action or artifact publication.

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted
- signed_off_by: Independent Signoff Reviewer (Codex)
- signed_off_at: 2026-07-19
- acceptance_record: `docs/version-tracker/1.4.3-SNAPSHOT/evidence/GOV-001-p1c-a-independent-signoff.md`
- blocking_items: none
- follow_up_required: yes
