---
title: Claude Agent Worker 0.1.8 retired cross-project skill release
version: 1.4.3-SNAPSHOT
status: BLOCKED_RELEASE_CREDENTIALS
owner: release-owner
created: 2026-07-20
---

# REL-001 Claude Agent Worker 0.1.8 Release

## Approved Goal

Publish a patch release of `tools/claude-agent-worker` to the existing `claude-worker` OBS channel, then verify that a clean Linux installation from the public `install.sh` does not create `cross-project-task/SKILL.md`.

## Scope

- Bump the Worker release version from `0.1.7` to `0.1.8`.
- Run the Worker test suite and package Linux, macOS, and Windows archives.
- Publish versioned artifacts, bootstrap installers, and `latest.json` to the existing OBS release channel.
- Install the public Linux release in an isolated temporary home and inspect the three retired-skill roots.

## Non-goals

- No Navigator backend or frontend rollout.
- No Worker restart or mutation of an existing installation.
- No changes to the retired-skill policy beyond packaging the already committed behavior.

## Acceptance Criteria

1. OBS `latest.json` reports `0.1.8` and points to all three platform archives.
2. Published archives are retrievable and match the locally generated SHA-256 values.
3. A clean `curl -sSL https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/claude-worker/install.sh | bash`-equivalent isolated installation succeeds.
4. `~/.agents/skills/cross-project-task/SKILL.md`, `~/.agent/skills/cross-project-task/SKILL.md`, and `~/.claude/skills/cross-project-task/SKILL.md` are absent after the regression check.

## Risks

- The release changes public installer discovery immediately through `latest.json`.
- The isolated installer verifies installation layout and packaged policy; it does not start a Worker or prove runtime startup behavior.

## Evidence

- Pre-release validation: `cd tools/claude-agent-worker && .venv/bin/python -m pytest -q` → `542 passed, 11 skipped` on 2026-07-20.
- Package validation: Linux and macOS archives SHA-256 `cb6bfdd3c03ec0c71a66b411f53385376442c709e0488adcadc206fb8f2cec70`; Windows archive SHA-256 `53099b4d0bf49026fd44104605b172c47bd0005cf99ce9a085fdf5db8e14c5a0`.
- Archive inspection: all three archives contain `VERSION=0.1.8`, omit `cross-project-task` artifacts, and retain the retired-skill reconciliation policy.
- Publication attempt: `bash dist/upload.sh 0.1.8` was blocked before the first archive upload because local `obsutil` has no AK/SK/endpoint configuration (`obsutil ls` exit `3`). Public verification immediately afterward still returned `latest.json.version=0.1.7`, and all three `0.1.8` archive URLs returned HTTP `404`.
- Isolated public installer verification is pending publication.
