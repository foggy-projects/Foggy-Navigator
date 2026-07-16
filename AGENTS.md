# Codex Repository Guidance

## Project Sources of Truth

- Read the root `CLAUDE.md` before repository work; it contains the current workspace topology, module map, startup commands, architecture constraints, and detailed development rules.
- Treat `pom.xml`, `pnpm-workspace.yaml`, package manifests, module-local guidance, and current code as authoritative when documentation is stale.
- Prefer the current architecture docs linked from `docs/README.md`; treat explicitly historical docs as background only.

## Scope and Workspace Safety

- Modify only this Foggy Navigator repository unless the user explicitly authorizes changes in a sibling workspace.
- Preserve the existing dirty worktree. Do not revert, overwrite, reformat, or include unrelated user changes.
- Identify Worker ownership by its command line and workspace path before stopping, restarting, upgrading, or publishing it; a port alone is not proof of ownership.
- Never write plaintext API keys, admin keys, credentials, or local secrets into tracked files.
- Put temporary test artifacts under `temp/test-artifacts/<task-or-date>/`. Put durable acceptance evidence under the applicable version directory.

## Delivery Workflow

- For analysis or planning requests, investigate and compare options without implementing until the user confirms the direction.
- After confirmation, maintain one canonical project-level delivery spec containing the approved goal, scope, non-goals, decisions, acceptance criteria, validation obligations, and risks.
- Let the Ultra implementation session choose reasonable file-, class-, and function-level details inside the approved contract. Do not over-specify local implementation in the planning phase.
- If implementation requires changing the approved goal, scope, compatibility policy, data migration, security boundary, or architecture decision, stop that expansion and mark the work `NEEDS_REPLAN`.
- During implementation, record changed paths, exact verification commands and results, deviations, and residual risks in the canonical work item.
- Implementation may finish at `READY_FOR_SIGNOFF`; the implementing session must not self-assign `ACCEPTED`.

## Implementation Rules

- Follow existing module boundaries and dependency direction. Do not place business Controllers, Services, or orchestration logic in deployment-shell modules.
- For external SDKs, CLIs, Workers, or provider integrations, inspect the real supported mechanisms and data contracts before designing changes; do not guess provider behavior.
- Keep domain terminology consistent across requirements, Java/TypeScript/Python code, database objects, API paths, events, tests, and acceptance records.
- Follow the backend conventions in `CLAUDE.md`, including JPA composition, `RX<T>` responses, Form/DTO boundaries, HTTP authorization updates, and exception/transaction governance.
- Keep changes scoped and maintainable. Avoid speculative abstractions, duplicate adapters, compatibility layers without an exit condition, and broad unrelated cleanup.
- Add comments only where they explain non-obvious business rules, compatibility constraints, regression prevention, or temporary exit conditions.

## Verification

- Run validation that matches the changed surface. Use module-local commands and documentation rather than copying commands from another project.
- For backend changes, run the relevant Maven module tests with dependencies when practical; use root or launcher-wide tests when the change crosses module boundaries.
- For frontend changes, run `bash scripts/build-frontend.sh` at minimum; run targeted tests or Playwright flows when required by the delivery spec or affected behavior.
- For Worker changes, run that Worker's unit/integration tests and any contract tests affected by cross-runtime changes.
- Distinguish test code existence from a test that was actually executed and passed. Never claim an unrun or failing check as successful.
- If an environment-dependent check cannot run, record the exact reason, affected acceptance items, and remaining risk.
- For reproducible BUGs with meaningful regression risk, prefer a failing automated test before the fix, then run it successfully after the fix. Document justified automation waivers.

## Documentation and Evidence

- Record new requirements, defects, refactors, and deferred work under the current `docs/version-tracker/<version>/` structure; do not add new items to historical `docs/requirement-tracker/`.
- Keep one canonical work item per change. Express multi-module ownership, stages, changed paths, tests, and evidence inside it; do not create competing module copies.
- Keep delivery documents concise: preserve decisions, constraints, evidence, risks, and next actions; remove repeated background and generic engineering advice.
- Update relevant migration, API, configuration, architecture, progress, and acceptance documentation when the implementation changes those contracts.

## Legacy Workflow References

- Historical version documents may mention retired personal skills. Preserve those names when they describe work already performed; do not rewrite historical evidence only to modernize terminology.
- For new work, map `plan-evaluator`, `foggy-plan-execution-docs`, `foggy-versioned-doc-tracking`, and `foggy-bug-regression-workflow` to `foggy-delivery-spec` at the approved-plan handoff boundary.
- Map `foggy-implementation-quality-gate`, `foggy-test-coverage-audit`, and `foggy-acceptance-signoff` to the single independent `foggy-delivery-signoff` step.
- `code-simplify` has no replacement; perform scoped implementation review natively. Use the standard system `skill-creator` instead of the retired personal `skill-writer`.

## Definition of Done

A material implementation is ready for signoff only when:

- the approved scope is implemented or every deviation is disclosed;
- relevant build, test, lint, E2E, and experience checks have recorded results;
- changed contracts, migration requirements, configuration, and documentation are updated;
- blockers and residual risks are explicit;
- the canonical delivery spec is updated to `READY_FOR_SIGNOFF`;
- a separate signoff step can map each critical acceptance criterion to reviewable evidence.
