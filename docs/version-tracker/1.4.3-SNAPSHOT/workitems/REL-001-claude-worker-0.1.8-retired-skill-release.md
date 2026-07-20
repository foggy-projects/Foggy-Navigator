---
title: Claude Agent Worker 0.1.9 retired cross-project skill release
version: 1.4.3-SNAPSHOT
status: READY_FOR_SIGNOFF
owner: release-owner
created: 2026-07-20
---

# REL-001 Claude Agent Worker 0.1.9 Release

## Approved Goal

Publish a Worker update to the existing `claude-worker` OBS channel and verify that Worker startup does not create `cross-project-task/SKILL.md`; a recognized historical Navigator-generated copy must be retired.

## Scope

- Published `0.1.8`, then superseded it with hotfix `0.1.9` after package runtime validation exposed an existing shutdown-path `NameError`.
- Kept the retired-skill policy unchanged: current platform skills are deployed, while only recognized historical `cross-project-task` content is removed.
- Published Linux, macOS, and Windows `0.1.9` archives, current bootstrap installers, and `latest.json`.

## Non-goals

- No Navigator backend or frontend rollout.
- No Worker restart or mutation of an existing installation.
- No deletion of unrecognized, user-owned skills that merely share the retired name.

## Acceptance Criteria and Evidence

1. Public `latest.json` reports `0.1.9` and contains Linux, macOS, and Windows archive paths: passed on 2026-07-20.
2. Published archive SHA-256 values match local packages: passed.
   - Linux/macOS: `30302ca6ff820947e29cfa76bf96871844b2dc304041f7dc89200982600cdf43`
   - Windows: `d132d518bf8f6a0e44956dce822e62eadac46610000ae33594d130d4c97af86c`
3. The packaged Linux `0.1.9` archive starts, answers `/health` with `version=0.1.9`, and shuts down cleanly: passed.
4. On that packaged runtime, a historical `cross-project-task/SKILL.md` carrying both `name: cross-project-task` and `/api/v1/cross-project-tasks` was removed on startup; no new such file was deployed: passed.

## Validation

- `cd tools/claude-agent-worker && .venv/bin/python -m pytest -q` -> `542 passed, 11 skipped`.
- `cd tools/claude-agent-worker && bash dist/package.sh all` -> all `0.1.9` platform archives generated.
- OBS upload used the existing operator-local OBS configuration without copying credentials into the repository; all archive, `latest.json`, `install.sh`, and `install.ps1` uploads returned HTTP 200.
- Public download and SHA-256 comparison passed for all three archives.
- Package lifecycle evidence is under `temp/test-artifacts/REL-001-claude-worker-0.1.8/`.

## Decision and Residual Risk

- A same-name file without the historical Navigator content signature is intentionally preserved, to avoid deleting user-owned skills. The Worker does not deploy `cross-project-task` on a clean installation.
- The public installer was fetched in an isolated test home and selected `0.1.8` before the hotfix; the harness ended during quiet dependency installation without a diagnostic. The final `0.1.9` archive, public installer metadata, and full package startup/stop path were verified separately. Target-environment confirmation remains the requested `curl -sSL .../install.sh | bash` update test.
