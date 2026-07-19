---
doc_type: decision-gate
delivery_type: cross-module
version: 1.4.3-SNAPSHOT
ticket: GOV-001-P3
status: DRAFT
gate_state: BLOCKED
canonical: true
canonical_slice: p3-production-boundary
parent_delivery_spec: GOV-001-upstream-permission-and-trust-boundary.md
execution_mode: normal-analysis
implementation_authorization: none
source_preflight_status: completed-static-no-runtime-or-production-claim
approved_by: pending-production-security-platform-and-release-owners
approved_at: pending
open_questions:
  - ingress TLS CORS and trusted-proxy model
  - production credential broker and KMS custody
  - Worker isolation and network execution policy
  - reliable immutable audit delivery and operational ownership
  - promotion, migration, rollback and release acceptance ownership
---

# Decision Gate: GOV-001 P3 Production Boundary

## Document Purpose

- intended_for: production security / SRE / Worker owners / audit owner / release owner / future implementation
- purpose: 定义 production promotion 必须先解决的基础设施与运行时边界；它不是“打开 external flag”的实施任务。
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/GOV-001-p3-production-boundary-decision-gate.md`
- current_state: `DRAFT` + `BLOCKED`. No production profile, ingress, Worker or credential configuration is changed by this document.

## Baseline Facts

- `ProductionConfigurationGuard` currently rejects several development-grade production settings, but it does not establish a complete ingress/trusted-proxy/TLS/CORS, KMS/broker, Worker sandbox or immutable-audit boundary.
- Current CORS sources contain wildcard origin patterns; production must not inherit that behavior without an explicitly designed trusted-origin/proxy model.
- Codex SDK, Codex app-server and LangGraph Worker external-mode implementations deliberately retain `EXTERNAL_EXECUTION_POLICY_PENDING`; a worker token alone never makes external execution ready.
- `NAVIGATOR_EXTERNAL_ENABLED` and `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED` remain independent routing/authentication controls, neither of which authorizes production promotion.

## Required Decisions and Evidence

| Domain | Must be decided before any production implementation | Required evidence / owner |
|---|---|---|
| Ingress and network | public/private exposure, TLS termination, trusted proxy chain/header handling, source ACL, DNS, rate limits, CORS origins/methods/credentials and CSRF/session model | SRE + security architecture; topology and spoof/negative tests |
| Credential custody | KMS/secret manager, broker/token exchange, key rotation/revocation, break-glass custody and least-privilege deployment identity | security + platform owner; no secret in repo/log/test evidence |
| Worker execution | OS/container isolation, workspace admission, tool allowlist, approval model, egress/network policy, resource limits and incident kill/revoke path | Worker owners + security; deny-by-default execution policy |
| Audit and evidence | reliable outbox, sink availability/degradation behavior, immutable/tamper-evident retention, redaction, correlation, access control and retention owner | audit/compliance + operations; fail-closed promotion decision |
| Release controls | production profile contract, artifact identity, migrations/`ddl-auto=validate`, rollback/compatibility, canary/monitoring and independent release signoff | release owner + DBA/SRE; signed release checklist |
| Third parties | S3 remains unprovisioned until a separate product/onboarding work item; external flags cannot create a route or credential | product + security owner; explicit absence assertion |

## Non-Goals and Hard Stops

- Do not change bind addresses, CORS, TLS, trusted proxy, secret-store, Worker sandbox, audit sink, production profile, deployment scripts or release configuration in this DRAFT.
- Do not remove `EXTERNAL_EXECUTION_POLICY_PENDING`, relax a 503/readiness response, or treat a configured worker token as proof of production policy.
- Do not issue production credential, seed an S1/S2 subject, enable Gateway strict or introduce S3 third-party onboarding.
- No production readiness is inferred from source tests, fixtures, a local `prod` profile, `NAVIGATOR_EXTERNAL_ENABLED=true`, or `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=true`.

## Approval-to-Implementation Exit Criteria

- [ ] An approved ingress/TLS/CORS/trusted-proxy design names the exact exposed surfaces and rejects spoofed forwarding headers, untrusted origins and non-approved networks.
- [ ] KMS/credential broker and rotation/revocation design are approved with named custodian, outage response and break-glass separation.
- [ ] Each Worker has a complete deny-by-default execution policy for workspace, tools, approval, sandbox and network; external readiness may only become true after it is implemented and tested.
- [ ] Audit delivery is reliable under sink outage/retry/order/deduplication, redacted and independently protected from business principals.
- [ ] The promotion process includes artifact identity, migration/rollback, observability, incident response and independent release owner/signoff.
- [ ] P2 identity/Gateway decisions and any applicable P1B-B factual activation are approved before a production-enabled workload is considered.

## Minimum Production-Gate Matrix for a Future Implementation

| Scenario | Expected result |
|---|---|
| Any required ingress/TLS/proxy/CORS/KMS/Worker/audit/migration/signoff prerequisite absent | startup or promotion deny; no partial production-ready claim |
| spoofed forwarding header, non-approved origin or TLS/proxy mismatch | deny |
| broker/KMS unavailable, credential revoked or rotation incomplete | deny affected authorization; never fallback to dev/default secret |
| Worker sandbox/tool/network policy absent or bypass attempt | external execution remains unready/deny |
| audit sink outage or redaction failure | follow approved fail-closed/degraded policy; never silently discard a promotion/security action |
| migration validation or rollback rehearsal fails | promotion deny |
| all platform/Gateway/Worker external flags enabled but S3 work item unapproved | no third-party usable path |

## Risks and Open Questions

- known_risks: current guard/tests prove only selected configuration checks. Network topology, secret custody, Worker isolation and immutable audit cannot be inferred from Java/TypeScript/Python source. Failing open during audit or broker outage would invalidate the authorization design.
- open_questions:
  - Which production environments, network zones and external clients are actually in scope?
  - Which KMS/broker/audit technologies and retention obligations are approved?
  - What is the availability versus fail-closed policy for each authorization/audit dependency?
  - Who owns promotion, on-call response, rollback authority and final release acceptance?

## Future Execution Contract

- A future `APPROVED` P3 spec must make one bounded production slice at a time, preserve local internal compatibility, and validate actual deployed evidence rather than source-only claims.
- It must maintain the separation among platform Open API routing, Gateway strictness, Worker external readiness and production approval.
- Any proposed shortcut through a local external flag, permanent deployment credential or unaudited Worker execution is `NEEDS_REPLAN`.

## References

- parent: [GOV-001 trust boundary](./GOV-001-upstream-permission-and-trust-boundary.md)
- current code facts: `launcher/src/main/java/com/foggy/navigator/launcher/ProductionConfigurationGuard.java`; `user-auth-module/src/main/java/com/foggy/navigator/auth/config/CorsConfig.java`; `user-auth-module/src/main/java/com/foggy/navigator/auth/config/WebMvcConfig.java`
- Worker evidence: `tools/codex-agent-worker/src/external-mode.ts`; `tools/codex-app-server-worker/src/external-mode.ts`; `tools/langgraph-biz-worker/tests/test_auth.py`
- static P3/P4 preflight: [GOV-001 P3/P4 Production and Legacy Source Preflight](../evidence/GOV-001-p3-p4-production-and-legacy-source-preflight.md)
