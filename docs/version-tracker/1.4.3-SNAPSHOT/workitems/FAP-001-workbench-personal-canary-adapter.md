---
doc_type: implementation-record
workitem: FAP-001
status: P2C_PERSONAL_PANE_IMPLEMENTED
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
- Default/company profile and old data were not modified. No repository-wide/full-provider/E2E test
  was run or authorized.

## Remaining before personal live canary

1. Prepare a private Access principal/bootstrap entry for the owner and new disposable FAP data.
2. Package the personal launcher with `-Pfap-workbench-canary`, keep company deployment unchanged,
   then run one focused real Codex START/CONTINUE/reconnect lane.
3. Publish the SDK/protocol artifacts to the private package registry before any multi-user or clean
   runner promotion. Add a forward schema migration before production/shared enablement; no old-data
   backfill is planned.
