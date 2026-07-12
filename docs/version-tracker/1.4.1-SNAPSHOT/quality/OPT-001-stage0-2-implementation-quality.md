---
quality_scope: feature
quality_mode: pre-coverage-audit
version: v1.0
target: OPT-001 Stage 0-2 session message large payload tiered storage
status: reviewed
decision: ready-with-risks
reviewed_by: Codex
reviewed_at: 2026-07-12
follow_up_required: yes
---

# Implementation Quality Gate

## Background

This review covers only OPT-001 Stage 0/1/2 in `1.4.1-SNAPSHOT`: contract and
regression boundaries, Descriptor schema and filesystem Payload Store, and
oversized tool-output routing with durable ACK behavior. Stage 3 detail API/UI,
Stage 4 cleanup, and Stage 5 production rollout are explicitly out of scope.

## Check Basis

- [OPT-001 requirement](../workitems/OPT-001-session-message-large-payload-tiered-storage.md), [implementation plan](../workitems/OPT-001-session-message-large-payload-tiered-storage-plan.md), and [progress](../workitems/OPT-001-session-message-large-payload-tiered-storage-progress.md).
- Changed Java/Python/config/SQL files across `navigator-common`, `session-module`, Codex/Claude/Gemini/LangGraph workers, and launcher.
- Focused routing/store/provider replay tests, required Maven suite, launcher compile, isolated MySQL 8.0/8.4 idempotent SQL runs, and final `git diff --check` evidence recorded in progress.
- Diff review of error boundaries, public descriptor/SSE projection, stable message IDs, task terminal ACK updates, Descriptor transaction locking, cross-process Store locking, and absence of payload reads from list/history/SSE paths.

## Changed Surface

- Schema: `SessionMessagePayloadEntity`, status enum, repository, idempotent startup migration, and pre-start MySQL SQL migration. All active final result columns are explicitly widened.
- Session module: backend-neutral Store abstraction, persistent gzip filesystem Store with JVM/FileChannel publication locks, routing service with locked Descriptor lookup, durable coordinator, shared public-payload sanitizer, replay-safe `JpaSessionManager`, and `SessionEventListener` synchronous boundary.
- Workers: Codex, Claude, Gemini durable relay ordering and stable legacy tool IDs; LangGraph Java/Python event identity support. Provider task result fields are `MEDIUMTEXT` or greater.
- Launcher: Stage 0-2 configuration defaults and production guard requiring a directory only when filesystem Store is explicitly enabled.

## Quality Checklist

| Dimension | Result | Review evidence |
|---|---|---|
| Scope conformance | Pass | No detail endpoint, frontend, cleanup, deployment, worker publication, or production DB action was added. Stage 0/1/2 requirements map to the changed modules. |
| Code hygiene | Pass | No debug path, temporary switch, credential, absolute production path, or synthetic failing test remains. Expected test fault logs exercise error branches. |
| Duplication and consolidation | Pass with rationale | Shared routing, descriptor construction, public projection and Store abstraction are centralized in session-module. Provider relays retain small provider-specific identity/ACK adapters because their event contracts differ; extracting a premature common relay would hide those semantics. |
| Complexity and abstraction | Pass with follow-up | The Store/Router/Coordinator split keeps file I/O, metadata transformation and transaction coordination separate. Provider relay methods remain necessarily branchy; a future shared cursor policy is only warranted after LangGraph exposes a durable ACK contract. |
| Error handling and edge cases | Pass | Store faults become `UNAVAILABLE` + Preview and do not poison ACK; descriptor/MySQL faults propagate. A coordinator-transaction `PESSIMISTIC_WRITE` lookup locks an existing Descriptor for replay validation/reuse, while Store JVM/FileChannel locks protect the cross-process first-write critical section; same-ID divergent descriptors or orphan files become non-ACK replay conflicts. UTF-8/JSON escaping, max size, path traversal, atomic move, SHA mismatch, stable replay ID and terminal task failures are covered. |
| Readability and maintainability | Pass | Names follow the domain (`PayloadStore`, `RoutingService`, `DurablePersistenceCoordinator`); comments explain BUG-021 compatibility, file-before-DB orphan rationale and non-ACK behavior. |
| Critical logic documentation | Pass | Code comments and progress document why `PENDING` is reserved, why SQL must run before `ddl-auto=validate`, and why legacy fallback IDs have a boundary. |
| Contract and compatibility | Pass with risks | Existing inline messages remain readable; old 48 KiB guard remains. Descriptor/public event projection excludes `storageKey` and `storage_key` through entity JSON ignore plus shared recursive sanitization at the SessionEventListener SSE boundary, Router, and mapper. |
| Documentation and writeback | Pass | Progress has actual paths, field inventory, SQL/Store/config contracts, test evidence, unrun-test reasons, deferred stages, self-check and this quality record. |
| Test alignment | Pass | Tests directly exercise routing bytes limits, Store behavior and locks, MySQL migration idempotence, Descriptor lock annotation/invocation, replay identity/conflict, SSE redaction and ACK ordering—not unrelated only. Required module suite and launcher assembly passed. |
| Release readiness | Ready with risks | Code is suitable for a later coverage audit of Stage 0-2, but it is not production-rollout-ready because topology, capacity and pre-start validation remain unresolved. |

## Findings

1. No blocking implementation defect found in the Stage 0-2 changed surface after review fixes.
2. Review corrected recursive public-payload redaction: it now removes both `storageKey` and `storage_key` before persistence and raw SSE serialization, rather than relying on the mapper alone.
3. Review hardened concurrent first-write behavior: `FileSystemSessionMessagePayloadStore` protects the exists/SHA-check/move critical section with intra-JVM stripes and cooperative cross-process `FileChannel` locks; Router locks the Descriptor identity before Store access and divergent same-ID replay remains non-ACK.
4. Claude/Gemini negative ACK tests use `anyBoolean()` for the primitive final argument and their final Surefire XML has `errors=0`. Structured LangGraph output is fully represented in the schema inventory: both `resultText` and `structuredOutput` are capacity-safe.
5. The Payload Store is never read by message list/history/SSE code. Its `read` contract is intentionally unused until Stage 3's authorized detail endpoint.

## Risks / Follow-ups

- Production must run `docs/migration/2026-07-12-session-message-payload-storage.sql` before a `ddl-auto=validate` launch. The runtime migration runs after readiness and is only an idempotent safety net; a full production-profile Spring/MySQL validation launch was not run here.
- Filesystem Store is not a multi-instance topology decision. Its FileChannel locking is a cooperative shared-filesystem safeguard, not proof that an arbitrary production shared volume preserves locking/atomic-move semantics. Stage 5 must choose and validate shared storage/object storage, capacity, compression cost and safe rollout before enabling it in production.
- `PENDING`, expiry, cleanup, quota and metrics are intentionally not implemented. Stage 4 must define retention and recovery without deleting existing data prematurely.
- LangGraph only has a per-generator `event_id` and no Provider ACK cursor; a fresh generator can restart numbering. Old no-ESN worker events without `tool_use_id` use a content-hash fallback that cannot distinguish independently emitted identical outputs.
- Detail endpoint authorization, owner/session checks and UI treatment of `UNAVAILABLE`/`EXPIRED` remain Stage 3 work. These risks are acceptable for Stage 0-2 coverage audit, not for production activation.

## Recommended Next Skills

1. `foggy-test-coverage-audit` for explicit requirement-to-test evidence review once this Stage 0-2 package is ready for its next gate.
2. `frontend-design` / module frontend skill only when Stage 3 begins, to implement the authorized detail experience without changing default list loading.
3. `foggy-acceptance-signoff` only after Stage 3-5 evidence and production-readiness decisions exist.

## Decision

`ready-with-risks` for OPT-001 Stage 0-2. The scope is implemented, cross-module review and targeted/required tests are aligned, and no blocking code-quality issue remains. The listed topology, capacity, lifecycle, authorized-detail and LangGraph replay-boundary risks must remain visible; this decision does not authorize deployment, worker release, service restart, production DB changes, or a claim that the full OPT-001 is accepted.
