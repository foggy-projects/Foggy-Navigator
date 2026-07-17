---
doc_type: delivery-spec
delivery_type: optimization
version: 1.4.2-SNAPSHOT
ticket: OPT-003
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
approved_by: user
approved_at: 2026-07-16
open_questions: []
---

# Delivery Spec: WSL Worker Source Sync by Default

## Document Purpose

- intended_for: implementation / independent-signoff
- purpose: Make the Linux/WSL local-stack restart apply the current repository's Worker sources to the separately installed WSL Biz Worker by default.
- canonical_path: docs/version-tracker/1.4.2-SNAPSHOT/workitems/OPT-003-wsl-worker-source-sync-default.md

## Goal

- target_outcome: `scripts/local-dev-stack.sh start|restart` normally deploys the current LangGraph Biz Worker source and its declared dependencies to the configured WSL Worker before starting it.

## Scope

- in_scope: the Linux local-stack default, the WSL Biz Worker sync/restart helper, and the local-stack runbook.
- affected_modules: `scripts/`, `tools/langgraph-biz-worker/`, `docs/dev-specs/`.
- external_dependencies: the configured WSL distro, Worker directory, Python virtual environment, and its existing `.env`.

## Non-Goals

- out_of_scope: publishing OBS release archives, upgrading global Claude/Codex CLIs, or modifying the Windows local-stack script.
- do_not_touch: Worker secrets, installed `.env`, public Skills, Worker state/log directories, and other WSL distributions or workspaces.

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| Sync the WSL Biz Worker by default on `start` and `restart`. | These commands ordinarily intend to use the latest current-project Worker. | `stop` and `status` remain non-mutating. |
| Retain an explicit opt-out and the old opt-in option. | Developers need a quick restart of the installed source and old commands must remain valid. | `--no-sync-wsl-biz-source` skips sync; `--sync-wsl-biz-source` remains accepted. |
| Sync the runtime source, dependency declaration, bundled Skills and documentation, then run editable install with the Worker interpreter. | A source-only copy can omit bundled runtime material or dependencies newly declared by the project. | Do not use a blanket dependency upgrade or overwrite `.env` / public Skills. |
| Claude and Codex are restarted from this repository's source tree. | They are not copied into a separate WSL installation by this script. | `codex-biz-worker` remains a route on Codex Worker, not a second process. |

## Acceptance Criteria

- [ ] AC-1: Default `start` and `restart` pass source sync to the WSL Biz Worker; `stop` and `status` do not sync.
- [ ] AC-2: The helper deploys the current runtime source, project metadata and bundled Skills without overwriting `.env`, public Skills, logs or state.
- [ ] AC-3: The helper refreshes the target editable install before starting and fails rather than starting an incompletely updated Worker.
- [ ] AC-4: Operators can skip the default sync explicitly, and existing `--sync-wsl-biz-source` invocations remain valid.
- [ ] AC-5: Documentation distinguishes source synchronization from global/published Worker or CLI upgrades.

## Contract / Data / Security Constraints

- API or event contract: none.
- data and migration: none.
- compatibility and rollback: use `--no-sync-wsl-biz-source` to restart the installed Worker without synchronization; restore the prior source from version control or a release package if needed.
- permissions and secrets: retain the target Worker user and `.env`; never copy credentials from the repository.

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| Script syntax and option routing | major | `bash -n`; help output checks | exact command and exit status |
| Sync semantics | major | static inspection of paths, mutating-action gate and interpreter install command | implementation diff review |
| Live WSL Worker update | environment-dependent | only against an identified configured Worker and when its credentials/runtime are available | health response or documented not-run reason |

## Risks and Open Questions

- known_risks: dependency installation can require the target WSL's package index/network; live deployment must not be attempted without confirming Worker ownership and target configuration.
- open_questions: none

## Ultra Execution Contract

- Implement only the approved scope. If target ownership or a required runtime path is ambiguous, do not perform a live update.
- Record changed paths, exact validation, deviations and residual risks below.
- Set status to `READY_FOR_SIGNOFF` when implementation and required non-environmental checks are complete; do not set `ACCEPTED`.

## Implementation Result

- implementation_summary: `local-dev-stack.sh` now syncs the WSL Biz Worker by default on `start`/`restart`, with `--no-sync-wsl-biz-source` as opt-out. The helper deploys source, metadata, docs and bundled Skills, preserves local configuration/state, refreshes the editable install, and stops a remote Worker before replacing its files during `restart`.
- changed_paths: `scripts/local-dev-stack.sh`; `tools/langgraph-biz-worker/restart-wsl-3161.sh`; `docs/dev-specs/local-upstream-collaboration.md`; this work item.
- tests_and_results:
  - `bash -n scripts/local-dev-stack.sh tools/langgraph-biz-worker/restart-wsl-3161.sh` — passed.
  - `bash scripts/local-dev-stack.sh --help` and `bash tools/langgraph-biz-worker/restart-wsl-3161.sh --help` — passed; default and opt-out flags are documented.
  - Isolated helper exercise against `temp/test-artifacts/opt-003-wsl-worker-source-sync/worker` on port `39161` — passed: source sync, editable install, `/health` returned `status=ok` and `ready=true`, then helper stop succeeded.
  - `PYTHONPATH=src .venv/bin/python -m pytest tests/test_health.py tests/test_skill_registry.py -q` — passed, 8 tests.
  - `git diff --check` — passed.
- manual_or_experience_evidence: no configured 3161 Worker was updated because its ownership/runtime configuration was not established for this implementation session.
- deviations: none.
- residual_risks: the target editable install can require network/package-index access when the declared dependencies are absent or incompatible; the actual target WSL distro still needs one owner-authorized smoke after deployment.
- readiness: READY_FOR_SIGNOFF

## References

- `CLAUDE.md` local-stack and Worker ownership guidance
- `docs/dev-specs/local-upstream-collaboration.md`
- `tools/langgraph-biz-worker/restart-wsl-3161.sh`
