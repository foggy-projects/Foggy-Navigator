---
workitem: NAVI-CORE-001-S4-03A3A
status: IMPLEMENTED_REVIEWED
date: 2026-08-04
baseline: dc57fd96
coordination_freeze: eef6f4f
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: DORMANT_TRUSTED_UI_A2A_TERMINATION_ADAPTER
---

# NAVI-CORE-001 S4-03A3A trusted termination adapter

This slice adds a dormant, route-scoped adapter for the existing canonical Task termination
coordinator. No Controller calls it yet. The UI method fixes the Direct Navigator UI ingress and
the Agent method fixes the external A2A ingress; neither method accepts caller-selected context,
owner, tenant, Provider, Worker, target, envelope, or authorization decision.

The Agent route now has one canonical A2A source definition. Its current Controller-local `UI`
source is a historical consequence of a shared context helper and remains unchanged until the
dedicated Controller wiring slice. The persisted logical Agent is resolved from the owner-qualified
immutable plan, while the path Agent is only an exact, non-routing security fence.

## Changed paths

- `session-module/src/main/java/com/foggy/navigator/session/service/TrustedNavigatorCommandIngressAuthority.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TrustedNavigatorTaskTerminationCommandAdapter.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/TrustedNavigatorTaskTerminationCommandAdapterTest.java`
- this work item

## Command order and identity

The fixed order is trusted MVC ingress proof, request-ID canonicalization, owner-qualified immutable
plan resolution, Task/owner/tenant and optional path-Agent fences, canonical plan binding, a
content-free TERMINATE envelope, the existing server authority decision, and the existing
coordinator. All caller and ambient identity conflicts fail before a receipt or Provider effect.

Absent and blank client request IDs each mint a fresh canonical UUID for compatibility. A supplied
UUID is normalized and binds client request, idempotency, and correlation identity. Only a caller
that repeats the same explicit header can request durable replay; no deterministic Task-derived ID
or hidden retry is introduced.

Fresh execution and recorded replay both map to the same public safe result. An accepted request
does not claim terminal state. Only canonical `COMPLETED`, `FAILED`, or `ABORTED` no-op outcomes carry
a terminal status. Provider, Worker, receipt, attempt, authority seal, and raw credentials are not
exposed.

## Validation evidence

- The affected session production compile passed in `15.529 s`.
- The final adapter focused class passed `7/7` with zero failure, error, or skip in `26.926 s`.
  It covers fixed UI/A2A source and credential lanes, UUID normalization and minting, Task/owner/
  tenant/path-Agent fences, force binding, known unsupported pre-receipt rejection, and identical
  fresh/recorded safe results. An initial `6/6` pass was strengthened with the missing tenant,
  requested-Task, blank path-Agent, and known-unsupported assertions before final review.
- Seven reused exact A1/A2 selectors passed `7/7` in `17.622 s`: trusted bearer/query/API-key and
  mixed-credential truth; recorded replay; started/ambiguous no-dispatch; terminal no-op; and exact
  plan binding.
- No Controller, Shared/OpenAPI, whole module/reactor, database, E2E, live Provider, or final joint
  full-validation cycle is authorized for this dormant slice.

## Independent final review

Three read-only reviewers independently accepted the strict four-path diff with no remaining
P1/P2. They confirmed fixed UI/A2A ingress truth, A1 credential and ambient-identity proof,
pre-receipt Task/owner/tenant/path-Agent fences, canonical request identity and envelope binding,
and identical content-free fresh/replay results. Reviewers did not modify files, run tests, inspect
historical data, or read the user-owned `BOOT-INF/` directory.

## Compatibility and residual risk

Task and Agent HTTP routes, bodies, headers, authentication, responses, and runtime behavior are
unchanged because there is no production caller. The following slice must wire the two Controllers,
preserve their public response wording and permission behavior, remove Controller-local terminal
and deadlock truth shortcuts, and add the optional request-ID header.

No service or Worker was started. No business/runtime or historical data was read or mutated, and
no repair, backfill, replay, reconciliation, cleanup, or deletion was performed. Shared, OpenAPI,
SIM, TMS, and the user-owned `BOOT-INF/` directory were not changed.
