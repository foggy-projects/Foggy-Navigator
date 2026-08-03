---
workitem: NAVI-CORE-001-S4-TA-01
status: IMPLEMENTED_REVIEWED
date: 2026-08-04
baseline: 54da5557
coordination_freeze: b5d3be3
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: DIRECTORY_AWARE_UNTRACKED_UTILITY_INFERENCE
---

# NAVI-CORE-001 S4-TA-01 directory-aware untracked utility inference

TaskAssistant event notifications and daily summaries are utility inference, not Task CREATE.
They now use an explicit directory-aware synchronous seam that preserves Claude provider session
continuity and directory-bound auth/environment, but creates no durable Task, Foggy Session,
transcript or lifecycle receipt.

## Exact paths

1. `navigator-spi/src/main/java/com/foggy/navigator/spi/claude/ClaudeWorkerFacade.java`
2. `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/spi/ClaudeWorkerFacadeImpl.java`
3. `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/spi/ClaudeWorkerFacadeImplTest.java`
4. `addons/task-assistant/src/main/java/com/foggy/navigator/task/assistant/service/TaskAssistantService.java`
5. `addons/task-assistant/src/main/java/com/foggy/navigator/task/assistant/spi/TaskAssistantFacade.java`
6. `addons/task-assistant/src/test/java/com/foggy/navigator/task/assistant/service/TaskAssistantServiceTest.java`
7. This work item.

## Behavior and compatibility

- `syncQueryUntracked` is additive and defaults to the stable fail-closed code
  `DIRECTORY_AWARE_UNTRACKED_SYNC_NOT_SUPPORTED`.
- Claude implementation preserves exact Worker ownership, resolves the directory model auth and
  merged environment, and uses the existing bounded synchronous query path without touching
  `ClaudeTaskService`.
- TaskAssistant no longer injects `AgentTaskManager` or `SessionManager`, creates or completes
  AgentTask rows, creates or repairs Foggy Sessions, or lazily repairs/rebinds existing config.
- Explicit create/update still initializes the selected directory and binds the selected model.
  `claudeSessionId` remains the provider conversation cursor. Existing `foggySessionId` is inert
  and read-only; it is neither migrated nor cleared.
- Controller/event bridge, `SessionSummaryService`, schemas, routes, POMs and clients are unchanged.

## Focused validation

- Affected production compile for Claude Worker and TaskAssistant: PASS (`40.323s`).
- Final combined focused lane after source-format normalization: PASS (`1:26`). The exact Claude
  provider selector passed `1/1`; `TaskAssistantServiceTest*` passed `34/34`; failures, errors and
  skips were zero. It proves directory auth/environment with zero tracked-task writes, untracked
  TaskAssistant routing, no implicit repair, create/update compatibility, provider-session
  continuity and daily-summary failure isolation.
- Static scan found no AgentTask/Session/repair/tracked call in `TaskAssistantService`; exact-path
  and CRLF-aware whitespace checks passed.
- Three independent final read-only reviews: PASS (`3/3 ACCEPT`, no P1/P2), covering data and
  compatibility boundaries, provider auth/zero-write semantics, and TaskAssistant behavior/tests.
- No module/reactor, database, E2E, live provider or final joint full validation is included. The
  authorized final joint validation budget remains `0/3 consumed`.

## Data and stop conditions

Tests use mocks only. No historical/existing config or runtime data may be repaired, backfilled,
reconciled, replayed, deleted or synthesized. Stop and replan if the seam requires a durable Task,
Foggy Session, transcript or receipt; tenant-level Worker access; schema/POM/client/route changes;
TaskAssistant Controller/event bridge/summary changes; or any path outside the frozen seven.
