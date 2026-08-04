---
doc_type: implementation-record
workitem: FAP-001
status: P2C_BACKEND_IMPLEMENTED
canonical_boundary: /home/sa/ultra/sim-navi/docs/decisions/FAP-003-workbench-personal-canary-coexistence-boundary.md
external_enablement: no
production_enablement: no
last_updated: 2026-08-04
---

# FAP-001 Workbench personal-canary adapter

## Outcome

Navigator now has an optional, isolated backend adapter for the owner's Foggy Agent Platform
Workbench canary. The module is included only by the Maven profile `fap-workbench-canary`; runtime
mutation additionally requires `navigator.workbench.fap.enabled=true` and an exact owner-user
allowlist match.

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
  Worker conversation/ticket/producer-ticket refs are removed without changing provider payload.
- Reattach is explicit and user-driven. No background recovery loop was added to Navigator.
- Orchestration, SDK command compilation, and browser-safe projection/sanitization are separate
  classes; changes in one concern must not turn the adapter into a second Runtime or policy engine.

## Build and validation

- Platform client prerequisite: `com.foggy.agent:foggy-agent-platform-client:0.1.0-alpha.1`, built
  from `/home/sa/workspace/foggy-agent-platform` commit `874071a` for this personal canary.
- Adapter compile: PASS with `-Pfap-workbench-canary -pl workbench-fap-adapter -am -DskipTests compile`.
- `WorkbenchFapServiceTest`: 4 passed.
- `WorkbenchFapConversationBindingRepositoryTest`: 1 passed against disposable H2 schema.
- Default/company profile and old data were not modified. No repository-wide/full-provider/E2E test
  was run or authorized.

## Remaining before personal live canary

1. Add the separate frontend FAP pane transport; do not inject FAP branching into legacy
   `useTaskPane` or unified SSE.
2. Prepare a private Access principal/bootstrap entry for the owner and new disposable FAP data.
3. Package the personal launcher with `-Pfap-workbench-canary`, keep company deployment unchanged,
   then run one focused real Codex START/CONTINUE/reconnect lane.
4. Publish the SDK/protocol artifacts to the private package registry before any multi-user or clean
   runner promotion. Add a forward schema migration before production/shared enablement; no old-data
   backfill is planned.
