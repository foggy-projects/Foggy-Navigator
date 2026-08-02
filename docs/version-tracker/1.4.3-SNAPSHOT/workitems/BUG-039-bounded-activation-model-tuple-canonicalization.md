# BUG-039 — Bounded activation model tuple canonicalization

status: READY_FOR_SIGNOFF

## Approved goal

Allow a bounded lifecycle activation manifest to pin the canonical physical
Codex model family while the owner-aware runtime request continues to use its
granted logical model variant. The exact provider, tenant, user, physical
Worker, modelConfigId, workdir and static prompt fences remain unchanged.

## Scope and decisions

- `exactTuple.model` is the canonical physical model family executed by the
  Worker, for example `gpt-5.6-luna`.
- The resolved task model remains the logical granted variant, for example
  `codex-luna:high`; reasoning effort is governed by the separately exact
  `modelConfigId` and its enabled grant before lifecycle reservation.
- Production admission compares these two values only through a closed mapping
  of known Codex logical/physical families.
- Bounded local-development manifests must contain the canonical physical base
  model. Unknown aliases, malformed effort suffixes, different model families,
  different providers and different modelConfig IDs fail closed.

## Non-goals

- No client-reported lifecycle authority or relaxed grant validation.
- No Worker, Agent, binding, modelConfig or credential mutation.
- No Task, Session, provider call, termination or enrollment consumption during
  implementation verification.

## Acceptance and validation obligations

- `codex-luna:high` matches manifest model `gpt-5.6-luna` for `codex-worker`.
- other provider/model families, unknown suffixes and modelConfig mismatch are
  rejected before reservation.
- focused common and production-admission integration tests pass.
- launcher package and restarted 8112 provenance/health pass.
- runtime readiness and owner-smoke pass without calling ask.
- activation authority is re-established on an unconsumed bounded target whose
  manifest binds the new commit and launcher digest.

## Implementation record

- changed paths: common Codex model canonicalizer and tests; lifecycle manifest
  validation; production admission comparison; focused lifecycle integration
  tests; this work item.
- regression evidence: the new logical-to-physical admission test failed first
  with `LIFECYCLE_CANARY_TUPLE_NOT_ALLOWLISTED`, before any Task/outbox write.
- focused tests: 3 common canonicalizer tests plus 26 lifecycle
  authority/admission tests passed with zero failures.
- package: `mvn -pl launcher -am -DskipTests package` completed with
  `BUILD SUCCESS`.
- runtime handoff: the unconsumed prior target is retired before replacing the
  launcher artifact; a new immutable target generation binds the final commit
  and launcher SHA after restart.
- residual risk: future Codex physical families require an explicit closed-map
  update and tests; unknown families intentionally remain fail closed.
