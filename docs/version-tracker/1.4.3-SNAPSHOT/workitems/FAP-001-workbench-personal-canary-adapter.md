---
doc_type: implementation-record
workitem: FAP-001
status: P2E_PERSONAL_CANARY_ACTIVE
canonical_boundary: /home/sa/ultra/sim-navi/docs/decisions/FAP-003-workbench-personal-canary-coexistence-boundary.md
external_enablement: no
production_enablement: no
last_updated: 2026-08-05
---

# FAP-001 Workbench personal-canary adapter

## Outcome

Navigator now has an optional, isolated backend adapter for the owner's Foggy Agent Platform
Workbench canary. The module is included only by the Maven profile `fap-workbench-canary`; runtime
mutation additionally requires `navigator.workbench.fap.enabled=true` and an exact owner-user
allowlist match.

The frontend now has an independent `/workers/fap` pane and typed transport. The menu item is
discovered through the safe availability endpoint and remains absent for stable/company builds,
disabled instances, and users outside the personal allowlist. The existing `/` Workers page,
`ClaudeWorkerView`, `useTaskPane`, and legacy Session/SSE transport were not changed or branched.

Default/company builds do not resolve the FAP SDK or package this module. Existing
`TaskController`, legacy Session/message persistence, provider addons, unified SSE, and deployed
company behavior are unchanged.

The owner's isolated canary is now running beside the legacy deployment. The new Navigator uses
8122, its frontend uses 5175, and its Host/CodexWorker/Runtime/Access use 4700/4720/4850/4860;
every endpoint is loopback-only. The legacy Navigator remained on its original PID and 8112, and
the legacy 303x Workers were not restarted. Other users continue to use the unchanged stable
deployment.

## Boundary implemented

- New conversations receive an immutable `FAP_V1` lane and have no legacy Session ID.
- The new table stores owner, safe resource refs, caller request IDs, Runtime execution/task refs,
  effective scopes, and coarse binding state only.
- It does not store transcripts, Worker tickets, routes, credentials, Access grants, provider raw
  facts, or old Navigator data.
- Ambiguous START transport failure is persisted as `START_OUTCOME_UNKNOWN`; it never triggers an
  automatic legacy retry/fallback.
- CONTINUE uses the binding's frozen effective scope and exact Runtime execution. Scope/permission
  changes require cancelling and starting a new task/conversation under new admission.
- Browser transport receives a product projection plus sanitized Worker events/resources; internal
  Worker conversation/ticket/producer-ticket, receipt, provider resume, and resource capability
  refs are removed without changing ordinary provider output payload.
- The Worker-owned event ledger now contains a safe `worker.operation.input.accepted` fact, so the
  new pane can reconstruct START/CONTINUE prompts without adding a Workbench transcript store.
  Resource locator capabilities are removed before that event is persisted.
- Reattach is explicit and user-driven. No background recovery loop was added to Navigator.
- Browser polling is bounded to the active page/current conversation, stops on definitive terminal,
  and pauses after three consecutive failures. START outcome unknown is shown to the user and is
  never automatically replayed.
- Orchestration, SDK command compilation, and browser-safe projection/sanitization are separate
  classes; changes in one concern must not turn the adapter into a second Runtime or policy engine.
- The canary launcher promotes H2 from test-only to runtime only under `fap-workbench-canary`; its
  database, root login, JWT key, Access/Runtime credentials, logs, and process records are private
  new state below gitignored canary roots.
- The profile-local FAP security filter only hands `/api/v1/workbench/fap/**` to Navigator's
  canonical MVC JWT interceptor. It grants no authority: missing identity or a non-owner identity
  still fails the exact backend owner allowlist. The global stable security chain is unchanged.
- Start/status/stop validates PID, Linux start ticks, process group, cwd, and exact argv. It never
  kills by port and cannot target legacy/company processes.

## Build and validation

- Platform client prerequisite: `com.foggy.agent:foggy-agent-platform-client:0.1.0-alpha.1`, built
  from `/home/sa/workspace/foggy-agent-platform`; safe Worker input facts are commit `4720d06` on
  top of client commit `874071a`.
- Adapter compile: PASS with `-Pfap-workbench-canary -pl workbench-fap-adapter -am -DskipTests compile`.
- `WorkbenchFapServiceTest`: 6 passed, including canonical resource-page and recovery sanitization.
- `WorkbenchFapConversationBindingRepositoryTest`: 1 passed against disposable H2 schema.
- Frontend focused tests: 10 passed across the FAP API, bounded polling, canary menu discovery, and
  route isolation.
- Frontend `vue-tsc` type-check passed; the Vite production bundle generated the lazy FAP page.
- `SessionForwardTargetSessionReservationServiceTest`: 9 passed after the live candidate exposed
  that a plain insert-only `@Repository` was absent from the production component scan.
- Canary-profile package with H2 runtime and FAP adapter: PASS using
  `-Pfap-workbench-canary -pl launcher -am -Dmaven.test.skip=true package`. Test compilation was
  deliberately skipped because an unrelated existing launcher integration test still targets an
  older `RuntimeTerminationAcceptanceCoordinator.accept(...)` signature; it was not repaired or
  promoted to a canary blocker.
- Personal Workbench managed lane: PASS. A new disposable Codex Conversation completed START and
  CONTINUE as canonical `SUCCEEDED`, exposed `worker.operation.input.accepted`, loaded recovery,
  accepted reattach, and returned two resource references.
- The actual Vite proxy returned `packaged=true`, `enabled=true`, `eligible=true`, lane `FAP_V1`;
  the same catalog route without identity returned 403.
- Default/company profile and old data were not modified. No repository-wide/full-provider/E2E test
  was run or authorized.

## Operations and remaining stabilization

The composite controller is `scripts/workbench-fap-personal-canary.py` with `start`, `status`,
`smoke`, and `stop`. Login data is generated once in the private canary state root and is never
printed or committed. `smoke` creates new provider facts, so it is an explicit focused validation
operation rather than part of ordinary startup.

1. Use the personal instance for ordinary Workbench tasks and record concrete defects; do not
   repeatedly run the smoke after documentation-only changes.
2. Keep company/stable unchanged until the owner explicitly declares the personal candidate stable.
3. Publish SDK/protocol artifacts and add a forward-only schema migration before any clean-runner or
   multi-user promotion. No old-data backfill or legacy Conversation import is planned.
