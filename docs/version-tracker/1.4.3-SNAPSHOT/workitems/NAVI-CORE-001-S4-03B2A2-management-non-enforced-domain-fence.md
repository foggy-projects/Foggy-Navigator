---
workitem: NAVI-CORE-001-S4-03B2A2
status: REVIEWED_READY_TO_COMMIT
date: 2026-08-04
baseline: 9608dcf1
coordination_freeze: 18ee91d
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: OPEN_API_MANAGEMENT_NON_ENFORCED_TERMINATION_FENCE
---

# NAVI-CORE-001 S4-03B2A2 management NON_ENFORCED termination fence

This slice adds the dormant Navigator-management adapter and the transaction-local fence needed
before B2A3 may wire the shared OpenAPI Agent-cancel route. It preserves same-tenant cross-owner
management semantics by separating the authenticated management actor from the Task's durable
owner, while preventing a generic once receipt from entering an ENFORCED lifecycle domain.

## Exact changed paths

- `session-module/src/main/java/com/foggy/navigator/session/command/CommandReceiptTransactionFence.java`
- `session-module/src/main/java/com/foggy/navigator/session/command/CommandOnceReceiptService.java`
- `session-module/src/main/java/com/foggy/navigator/session/lifecycle/LifecycleEnrollmentService.java`
- `session-module/src/main/java/com/foggy/navigator/session/lifecycle/TaskLifecycleOwnerService.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/OpenApiManagementTerminationDomainFence.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/ScopedOpenApiManagementTaskTerminationCommandAdapter.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/SessionTaskResourceAccessService.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TaskTerminationCommandCoordinator.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TrustedNavigatorCommandIngressAuthority.java`
- `session-module/src/test/java/com/foggy/navigator/session/command/CommandOnceReceiptServiceTest.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/OpenApiManagementTerminationDomainFenceTest.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/ScopedOpenApiManagementTaskTerminationCommandAdapterTest.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/SessionTaskResourceAccessServiceTest.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/TrustedNavigatorCommandIngressAuthorityTest.java`
- this work item

## Frozen behavior

- The new adapter fixes `OPENAPI / NAVIGATOR_OPEN_API / OPEN_API` and the existing Agent-cancel
  route. Bearer/query token stays in `NAVIGATOR_JWT`; `X-API-Key` stays in
  `NAVIGATOR_API_KEY`. The actor is the current management user, while receipt ownership and the
  Provider plan use the durable same-tenant Task owner and its live Session. Force is always false.
- The adapter accepts no caller-supplied owner, tenant, route, Provider, Worker, context, force,
  envelope, or authorization decision. It is a dormant bean in this slice: Controller role AOP,
  dual-lane selection, response compatibility, and SDK correction remain B2A3 work.
- The once-receipt service observes an immutable receipt state in a separate read transaction,
  then performs the claimed domain lock and any receipt row lock in a fresh write transaction.
  New or PREPARED receipts require the fence at both prepare and begin-effect. Recorded results
  replay without current-domain reinterpretation; EFFECT_STARTED and AMBIGUOUS never re-dispatch.
- The management fence first makes a non-locking lifecycle observation. A pre-existing ENFORCED,
  null, or unknown mode rejects before the canonical Task lock, avoiding the inverse lock chain
  used by existing ENFORCED termination authorization. Only absent/SHADOW observations proceed to
  canonical Task then lifecycle Task locks and an authoritative final recheck. It revalidates Task,
  Session, owner, tenant, logical Agent, Provider/Worker/model target, and permits only an absent
  snapshot or exact SHADOW. A production SHADOW projection may omit `sessionId`; a non-null value
  must match the durable Task Session. Malformed identity, foreign actor/lane, or a missing claiming
  fence rejects before receipt creation or PREPARED-to-EFFECT_STARTED.
- `TaskLifecycleOwnerService` now locks the canonical Task before first snapshot creation.
  `LifecycleEnrollmentService` enters that owner step before Worker/proof/session locks. Together
  with the existing production-admission Task-first path, this makes an absent snapshot a safe
  UNENROLLED observation without relying on database gap locks.
- No lifecycle row, receipt history, Task, Session, or old data is repaired by the fence. A prior
  PREPARED receipt that now belongs to ENFORCED remains PREPARED and is rejected; it is not
  reconciled, deleted, or marked ambiguous.

## Focused validation evidence

- Affected production compilation passed across the six-project session reactor:
  `mvn -pl session-module -am -DskipTests compile` (`BUILD SUCCESS`, 16.405 seconds).
- The first implementation-focused run passed 17/17 exact tests: four receipt transaction cases,
  four domain cases, four tenant-resource cases, one ingress case, and four adapter cases.
- A reuse run passed the real isolated enrollment fixture, Task owner vertical integration, and
  three coordinator replay/pre-permit selectors. Its legacy concurrent receipt selector exposed a
  JPA stale-version error caused by upgrading a previously non-locking managed entity to a
  pessimistic lock. The implementation was corrected to carry only immutable observed state from
  a separate read transaction into the write transaction.
- After that correction, the legacy concurrent once-receipt selector and three management
  receipt selectors passed 4/4. Both generic and management duplicate-key races create one row,
  and concurrent begin-effect grants one permit without a stale persistence-context upgrade.
- The final affected lane passed 65/65 tests across the receipt, management domain, dormant
  adapter, resource access, trusted ingress, lifecycle owner, and coordinator classes
  (`BUILD SUCCESS`, 27.298 seconds). It includes two real H2 transactions proving that an existing
  ENFORCED task rejects without joining a held canonical-Task lock chain and that an enrollment
  winning SHADOW-to-ENFORCED serialization is rejected by the locked management recheck.
- The first final-lane attempt passed 64/65. Its only failure was a test-only assertion that assumed
  a duplicate insert must occur; the database validly allowed the second request to observe the
  committed receipt, producing two rather than three fence calls. The disposable fixture was made
  deterministic for the duplicate-recovery selector; that selector then passed 1/1 and the complete
  affected lane passed 65/65. No production retry or behavior change was needed for this failure.
- Three independent revised-diff reviews accepted the actor/owner/tenant and B2A3 boundaries, the
  receipt/replay pipeline including the production-shaped null-session SHADOW, and the lifecycle
  lock ordering/concurrency evidence with no P1/P2 remaining. A non-blocking P3 notes that explicit
  management-state selectors for EFFECT_STARTED/AMBIGUOUS could supplement the already-covered
  generic no-second-effect behavior later.
- No full module/reactor, Controller/SDK, external database, E2E/live Provider, or final joint full
  validation has run. Final-cycle usage remains 0/3.

## Data and compatibility boundary

Validation uses mocks, new in-memory receipt rows, disposable H2 lifecycle fixtures, and a local
repo-owned Worker fixture only. No existing or historical business/runtime data was repaired,
backfilled, replayed, reconciled, cleaned, deleted, or otherwise mutated. No Controller, SDK/CLI,
Provider, dispatcher, schema, POM, SIM, or TMS path changed, and `BOOT-INF/` was not inspected or
touched.
