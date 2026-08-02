---
doc_type: delivery-spec
delivery_type: cross-module
version: 1.4.3-SNAPSHOT
ticket: BUG-040
status: READY_FOR_SIGNOFF
canonical: true
approved_by: repository-owner
approved_at: 2026-08-02
---

# BUG-040: Lifecycle Provider-Effect Model Canonicalization

## Goal and scope

Fix the bounded local-development activation path so reservation and
provider-effect admission use the same server-side Codex physical-model family
mapping. Keep exact provider, modelConfig, owner, Worker identity, proof and
binding-digest checks fail closed.

Also close a failed one-shot provider-effect admission without releasing it for
reuse, classify lifecycle pre-effect failures as definitive zero-dispatch
terminal transitions, revoke their task-scoped capability, and keep request and
task audit dispatch projections consistent.

## Non-goals and constraints

- No new Task, provider/model call, termination, TMS access, Worker change or
  binding/resource mutation during implementation validation.
- Do not rewrite or delete the retained failed Task audit.
- A failed one-shot target is quarantined, never reset to READY.
- Unknown aliases, model families, providers and modelConfig values remain
  rejected.

## Acceptance criteria

- [x] `gpt-5.6-luna` and granted `codex-luna:high` pass both reservation and
  canonical persisted-task admission.
- [x] Different/malformed model families and all other binding mismatches fail
  before provider effect and atomically leave zero enrollment writes.
- [x] Provider-effect admission failure quarantines the one-shot target/proof.
- [x] Lifecycle pre-effect FAILED is definitive, zero-dispatch and drives task
  token/tombstone closure; genuinely recoverable provider failures remain
  recoverable.
- [x] Request audit and task audit derive provider/model dispatch from
  server-observed providerTaskId/terminal facts rather than Task creation.
- [x] Focused affected tests, launcher package and restarted 8112 provenance/
  health pass.

## Implementation result

- changed_paths:
  - `session-module`: shared physical-model family comparison at provider-effect
    admission, plus one-shot quarantine on failure.
  - `addons/codex-worker-agent`: definitive lifecycle pre-effect failure and
    server-observed dispatch facts.
  - `agent-framework`, `business-agent-module`,
    `addons/claude-worker-agent`: terminal dispatch projection, token closure,
    and request/task audit alignment.
- tests_and_results:
  - lifecycle activation integration: 22 passed.
  - runtime request audit and terminal listener: 41 passed.
  - Codex task and stream relay: 199 passed.
  - OpenAPI mapping and runtime state audit: 73 passed.
  - launcher 14-module package: passed.
  - restarted 8112: health/database `UP`; actuator provenance matched the
    packaged clean `main` commit.
- residual_runtime_disposition: retain Task audit; formally reconcile the
  pre-effect terminal Task to revoke its existing capability, and quarantine
  target `-03` before creating a fresh immutable one-shot target. Restart-time
  controller drift has already quarantined `-03` and its proof without reusing
  the reservation; the retained Task token still requires the formal
  zero-provider-dispatch terminal republish path.
- readiness: READY_FOR_SIGNOFF
