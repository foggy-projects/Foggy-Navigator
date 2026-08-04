---
workitem: NAVI-CORE-001-S4-03B2A1
status: REVIEWED_READY_TO_COMMIT
date: 2026-08-04
baseline: 0f7bba4d
coordination_freeze: e711604
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: RUNTIME_ACCESS_AGENT_TERMINATION_ENFORCED_CORE
---

# NAVI-CORE-001 S4-03B2A1 runtime-access Agent termination core

This slice admits the existing OpenAPI Agent-cancel identity into the B1 lifecycle termination
owner without wiring a Controller or public SDK caller. Only a fresh ClientApp runtime-access
credential, a currently visible canonical Agent resource, an exact durable Task binding, an
ENFORCED Provider admission, and the existing lifecycle writer proof may reach one Provider effect.
The lifecycle intent/outbox remains the sole durable delivery and idempotency authority for this
lane; no synthetic runtime-secret receipt, second outbox, direct Provider fallback, or historical
data repair is introduced.

## Exact changed paths

- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/RuntimeStateAuditService.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/RuntimeTaskClosureService.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/RuntimeTerminationAcceptanceCoordinator.java`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/RuntimeStateAuditServiceTest.java`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/RuntimeTaskTypedContractServiceTest.java`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/RuntimeTerminationAcceptanceCoordinatorTest.java`
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/A2AgentResourceResolver.java`
- `business-agent-module/src/test/java/com/foggy/navigator/business/agent/service/A2AgentResourceResolverTest.java`
- `navigator-spi/src/main/java/com/foggy/navigator/spi/lifecycle/RuntimeTerminationIntentPort.java`
- `session-module/src/main/java/com/foggy/navigator/session/lifecycle/TaskTerminationIntentRecorder.java`
- `session-module/src/test/java/com/foggy/navigator/session/lifecycle/TaskTerminationIntentRecorderIntegrationTest.java`
- this work item

## Frozen boundary and implementation

- Every initial resolve, transactional acceptance, and fresh HTTP effect attempt resolves the raw
  app key/access token again. The opaque runtime-access-token ID must be present as credential
  authority evidence and is then discarded; neither raw token nor token ID enters authorization,
  outbox, logs, errors, or results.
- `A2AgentResourceResolver` proves current Agent visibility and canonical Agent identity. Durable
  Task/token/business-session facts independently bind tenant, ClientApp, upstream user, Task
  owner, logical Agent, Provider, and selected physical Worker. The Agent's current Worker/Pool
  route is deliberately not treated as an old Task fact: a legitimate rebind must not make an
  active Task impossible to cancel. Task Provider/Worker drift is instead caught by authorization
  revalidation and the ENFORCED lifecycle admission/writer proof. Repository identity, durable
  Provider/Worker, ownership, or Agent drift still fails closed before mutation.
- A terminal tombstone supplies canonical terminal status when the mutable Task row is stale.
  Unknown terminal status fails closed and is never guessed as `FAILED`.
- Agent authorization is fixed to `OPENAPI / NAVIGATOR_OPEN_API /
  /api/v1/open/agents/{agentId}/tasks/{taskId}/cancel` and
  `CLIENT_APP / CLIENT_APP_RUNTIME_ACCESS`. Missing request ID is minted as a canonical UUID;
  malformed explicit values are rejected with a stable code.
- The full stable command binding is reduced to a lowercase SHA-256 claim. New Agent intents store
  and replay-compare this claim in the existing `effect_claim` column. Raw credential, reason, and
  random decision metadata are excluded.
- Existing typed `/runtime/task-terminate` acceptance continues to write the legacy
  `TERMINATION_PROVIDER_CALL` claim, so pre-upgrade typed PREPARED replay remains compatible.
  Old intent/delivery constructors retain their descriptors and legacy claim; no old row is
  rewritten or interpreted beyond its stored value.
- After durable PREPARED acceptance, access-token expiry or response loss returns conservative
  `CANCEL_REQUESTED / reconcileRequired` without claiming replay or starting a second effect.
  Restart recovery continues only from the frozen outbox principal and writer proof.
- `OpenApiController`, management cancellation, SDK/CLI, Provider, dispatcher, schema/POM, SIM,
  TMS, and public response mapping remain out of scope until B2A2/B2A3/B2B.

## Focused validation evidence

- Affected production compilation passed before the final review amendments:
  `mvn -pl addons/claude-worker-agent -am -DskipTests compile` (eight reactor projects,
  `BUILD SUCCESS`, 36.429 seconds). After the visibility-only micro-replan, the same affected
  compile passed again across all eight projects (`BUILD SUCCESS`, 12.163 seconds).
- Two exact `A2AgentResourceResolverTest` selectors passed 2/2, proving the new visibility-only
  method retains read-only/no-rollback readiness semantics and accepts an otherwise visible Agent
  whose current Worker route has been removed without consulting Worker/Pool authorities.
- Seven exact access-token Agent ownership selectors passed 7/7 after the resolver change,
  covering fresh authority, tombstone status, durable pool/Worker facts, absence of a current Agent
  route projection, identity/provider/Worker drift rejection, and authority-unavailable mapping.
- Five exact State ownership/read-only selectors passed 5/5. Two additional exact authority and
  Task/provider/Worker drift selectors passed 2/2 after a test-fixture type correction.
- Seven exact Agent/typed authorization and service-flow selectors plus six exact acceptance
  selectors passed 13/13. Four additional revocation/authority selectors passed 4/4.
- The explicit production/legacy coordinator constructor compatibility selector passed, including
  the five-argument `@Autowired` constructor and retained public four-argument constructor.
- Four exact session integration selectors passed 4/4, covering full-claim persistence, same-ID
  same-claim replay, claim drift rejection before effect, invalid-claim rollback, legacy recovery,
  and separation from the child Worker command claim.
- One attempted pair of focused Maven commands was incorrectly launched concurrently against the
  same `target/` directories. One process failed with a transient `NoSuchFileException`; the other
  rebuilt successfully. All affected selectors were then run serially and passed. This was build
  orchestration noise, not a product or test failure, and no broader rerun was added.
- `git diff --check` passed. No whole class/module/reactor test set, Controller/SDK test, target
  database, E2E/live Provider, or final joint full-validation cycle has run. Final-cycle usage
  remains 0/3.

## Review result

Three revised-diff read-only reviews independently returned `ACCEPT` with no P1/P2. They confirmed
the former current-route false blocker is removed without weakening Agent visibility, the original
full resolver remains compatible for task creation/binding audit, and credential isolation,
ENFORCED admission, writer proof, same-ID claim fencing, PREPARED recovery, and typed legacy claim
compatibility remain intact.

One reviewer recorded a non-blocking P3 coverage suggestion for an Agent-path-specific
known-but-unimplemented Provider assertion with zero coordinator/dispatcher interaction. The code
already rejects this condition in `provider(owned)` before acceptance/intent, and the stable
unsupported-provider reason is covered in existing typed service selectors. No additional test was
added because it would not change the B2A1 go/no-go decision and would expand validation after three
independent accepts. The separate constructor compatibility concern mentioned in the same review
is already covered by the exact reflection selector above.

## Data boundary

Implementation and validation use mocks, in-memory values, and new disposable H2 fixtures only.
No historical or existing business/runtime data was read for repair or mutated, and no backfill,
replay, reconcile, cleanup, delete, or fact synthesis was performed. The user-owned `BOOT-INF/`
directory was not inspected or touched.
