---
name: claude-worker-release
description: Route explicit developer releases for the Claude Code Proxy or Claude Agent Worker, including version changes, cross-platform packaging, OBS publication, latest.json, installer assets, and release troubleshooting. Use only when the user explicitly invokes $claude-worker-release or clearly requests one of these two releases.
---

# Claude Worker Release

Use this developer-only skill as the release router for the Claude proxy and Claude Agent Worker. Do not trigger it for ordinary builds or unrelated Worker development.

## Route the Release

- `tools/claude-code-proxy`, Proxy packages, `/proxy-deploy`, or Proxy OBS artifacts: read `references/claude-proxy-release.md`.
- `tools/claude-agent-worker`, `/cw-deploy`, `/worker-deploy`, Worker packages, or Worker OBS artifacts: read `references/claude-agent-worker-release.md`.
- Codex SDK Worker or Codex app-server Worker releases are out of scope; use `$codex-worker-deploy`.

## Release Gate

- Inspect the current version source, package scripts, installer scripts, and release manifest before changing anything; do not rely on copied version examples.
- Confirm whether the request authorizes version bump, package creation, OBS upload, and `latest.json` publication. Packaging alone does not authorize publication.
- Never print or commit OBS AK/SK, `.env` values, tokens, or credential files.
- Preserve unrelated dirty worktree changes and existing release artifacts.
- Run the target's documented package/tests and validate archive contents before upload. After upload, verify the versioned artifacts and current manifest point to the intended release.
- Report exact commands, target/version, artifact hashes or paths, publication result, and residual risks.
