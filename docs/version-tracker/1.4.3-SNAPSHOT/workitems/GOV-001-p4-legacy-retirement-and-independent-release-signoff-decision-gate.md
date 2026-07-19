---
doc_type: decision-gate
delivery_type: cross-module
version: 1.4.3-SNAPSHOT
ticket: GOV-001-P4
status: DRAFT
gate_state: BLOCKED
canonical: true
canonical_slice: p4-legacy-retirement-and-independent-release-signoff
parent_delivery_spec: GOV-001-upstream-permission-and-trust-boundary.md
execution_mode: normal-analysis
implementation_authorization: none
source_preflight_status: completed-static-no-runtime-or-release-claim
approved_by: pending-release-owner-security-owner-and-client-owners
approved_at: pending
open_questions:
  - legacy surface inventory and live usage telemetry
  - compatibility window, deprecation notice and rollback thresholds
  - telemetry privacy and retention
  - release evidence and independent signoff ownership
---

# Decision Gate: GOV-001 P4 Legacy Retirement and Independent Release Signoff

## Document Purpose

- intended_for: release owner / security owner / client owners / independent signoff / future implementation
- purpose: 把 legacy compatibility 的可观测迁移、退役、回滚和正式 release signoff 变成显式 gate，防止“新授权模型已存在”被误解为可以静默删除旧入口或宣布整体上线。
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/GOV-001-p4-legacy-retirement-and-independent-release-signoff-decision-gate.md`
- current_state: `DRAFT` + `BLOCKED`; no legacy route/header/token behavior is changed by this document.

## Dependencies and Boundaries

- P2 must first produce a strict client propagation and strong-identity decision; P3 must first provide production telemetry/audit/release evidence. P4 cannot use a source-only test as a substitute for live migration evidence.
- Existing `NAVI_ADMIN_API_KEY` / `X-Navi-Admin-Key` semantics remain `UPSTREAM_SYSTEM_ADMIN + LEGACY_UPSTREAM_ADMIN`; they do not auto-upgrade to S1 root, S2 platform/security, runtime, task or Worker authority.
- Legacy compatibility may exist only for explicitly registered internal routes during a controlled window. It must never become an external, production or third-party fallback.
- No Worker/Codex topology change is a migration tool; existing Physical Worker routing remains intact.

## Required Decisions and Evidence

| Gate item | Required decision | Required evidence / owner |
|---|---|---|
| Legacy inventory | exact old headers, credential lanes, token-only paths, implicit route semantics, no-expiry exceptions, CLI/archive/help versions and callers | route/client owners; versioned sanitized inventory |
| Telemetry | privacy-safe usage counters/traces, route-family attribution, decision reason, client/build version, no secret/body/user-private leakage | observability + privacy owner; retention/access policy |
| Compatibility window | start/end dates, eligible trusted-internal callers, notices, migration support and explicit non-eligible external/production paths | product/release owner; published migration plan |
| Rollback | route-family-specific thresholds, kill switch ownership, data/credential compatibility, rollback evidence and incident communication | SRE + security owner; rehearsal record |
| Retirement | zero-use or named time-bounded exception criteria, exception owner/expiry, remove order and test deletion plan | client owners + release owner |
| Independent release signoff | AC-to-evidence matrix, changed paths, real command/deployment results, residual risk, approver separation and final release authority | independent reviewer + release owner |

## Non-Goals and Hard Stops

- Do not remove, relax or resurrect legacy behavior; do not accept deprecated `X-Navi-Admin-Api-Key` or construct a new compatibility fallback.
- Do not start P4 telemetry by logging credentials, token references, full request bodies, owner-private resource existence or upstream-user assertions.
- Do not treat zero calls in a local test, fixture, isolated environment or undocumented time window as retirement evidence.
- Do not mark a GOV-001 slice, external surface, Worker Gateway or production release `ACCEPTED` without independent acceptance evidence.

## Approval-to-Implementation Exit Criteria

- [ ] P2/P3 prerequisite decisions and production-safe telemetry design are approved.
- [ ] A complete legacy inventory identifies each caller, route family, header/token lane, artifact/help version and permitted compatibility scope.
- [ ] Usage telemetry has a named retention/privacy owner and can distinguish active compatible internal use from absent or malformed reporting.
- [ ] Compatibility dates, customer/operator notices, migration assistance, exception policy and rollback thresholds are approved.
- [ ] A route-family migration order can safely fail closed, roll back locally, and later delete code/tests/docs only after the agreed evidence window.
- [ ] An independent release signoff template maps every critical acceptance criterion to actual test/deployment/audit evidence and names the final release owner.

## Minimum Retirement and Signoff Matrix for a Future Implementation

| Scenario | Expected result |
|---|---|
| registered trusted-internal legacy caller inside approved window | compatible only with telemetry and explicit deprecation state |
| external, production or S3 caller using legacy header/token-only/implicit route | deny |
| legacy key attempts S1 root, S2 security-admin, Worker principal or production promotion | deny |
| observed legacy use after cutoff without approved exception | deny or rollback according to approved threshold; never silent extension |
| telemetry missing, ambiguous or secret-bearing | block retirement/signoff |
| migration failure for one route family | route-family-scoped rollback; do not re-enable unrelated legacy privilege |
| final release signoff lacks one critical AC evidence item | `blocked` or `rejected`, never accepted-with-unknowns |

## Risks and Open Questions

- known_risks: premature removal can break internal automation, while indefinite compatibility preserves over-broad authority. Telemetry that cannot attribute caller/version safely cannot support retirement. A signoff performed by the implementer is not independent.
- open_questions:
  - What legacy surfaces and installed CLI artifacts are actually in use, by which trusted internal callers?
  - What compatibility duration and exception expiration are acceptable?
  - What rollback SLO and telemetry/error thresholds trigger a pause or reversal?
  - Who is authorized to accept residual non-blocking risks and who owns final release promotion?

## Future Execution Contract

- A future `APPROVED` P4 spec must limit each migration to an observable route family, preserve P2/P3 boundaries, and record commands/results/evidence before changing status to `READY_FOR_SIGNOFF`.
- Independent signoff must follow the repository signoff workflow and can mark `ACCEPTED` only after all critical evidence exists.
- Any plan that uses a legacy credential as a bridge to root/platform/security authority, hides telemetry gaps, or widens a compatibility fallback is `NEEDS_REPLAN`.

## References

- parent: [GOV-001 trust boundary](./GOV-001-upstream-permission-and-trust-boundary.md)
- P2 prerequisite: [strong identity and Gateway strictness gate](./GOV-001-p2-strong-identity-and-gateway-strictness-decision-gate.md)
- P3 prerequisite: [production boundary gate](./GOV-001-p3-production-boundary-decision-gate.md)
- signoff process: `/mnt/c/Users/oldse/.agents/skills/foggy-delivery-signoff/SKILL.md`
- static P3/P4 preflight: [GOV-001 P3/P4 Production and Legacy Source Preflight](../evidence/GOV-001-p3-p4-production-and-legacy-source-preflight.md)
