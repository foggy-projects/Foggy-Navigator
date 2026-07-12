---
name: codex-worker-deploy
description: Package, validate, publish, or troubleshoot Foggy Navigator Codex SDK Worker and Codex app-server Worker releases. Use when working with tools/codex-agent-worker or tools/codex-app-server-worker for version bumps, release archives, OBS uploads, latest.json, install/update scripts, release evidence, tiered smoke tests, or deciding whether one or both Codex workers need publishing. Triggers include /codex-worker-deploy, Codex Worker 发版, app-server-worker 发版, package Codex worker, OBS 上传, and worker release.
---

# Codex Worker Deploy

Use the repository release scripts as the source of truth. Keep the SDK Worker and app-server Worker versions and OBS prefixes independent.

## Select the release target

Inspect the diff before changing versions:

- Changes under `tools/codex-agent-worker/` require evaluating the SDK Worker.
- Changes under `tools/codex-app-server-worker/` require evaluating the app-server Worker.
- Java routing or error-mapping changes do not by themselves require either Worker to be republished.
- Publish both only when both Worker directories contain runtime or packaging changes.

Do not republish an unchanged Worker merely to keep versions aligned.

## Select the smoke level

Use the least expensive level that still covers the release risk:

| Level | Use for | Required evidence |
|---|---|---|
| `skip` | Documentation, comments, tests, or metadata-only changes with no packaged/runtime effect | Normal unit/type/build gates still apply when packaging; no installed-candidate smoke |
| `basic` | Small production-code fixes that do not alter dependencies, lifecycle, installers, process ownership, resume behavior, or release tooling | Archive structure, forbidden-file scan, sizes, SHA-256, manifest and bootstrap validation |
| `full` | Dependency, installer/updater, release script, runtime startup, process lifecycle, session/resume, task ownership, auth, or compatibility changes | `basic` plus install dependencies from the packaged candidate and start it to verify `/health` and version |

Prefer `auto` for the SDK Worker. Its classifier maps documentation/test-only changes to `skip`, high-risk paths to `full`, and other changes to `basic`. Override explicitly when the semantic risk differs from the file path.

Live model queries, paid API calls, long canary windows, and production soak are never implicit. Run them only when the change requires them or the user explicitly requests them.

## Codex SDK Worker

Work in `tools/codex-agent-worker`.

1. Confirm `package.json` and `package-lock.json` use the intended version.
2. Package all platforms from Linux, macOS, or Windows:

```bash
npm run package:release -- --platform all --smoke auto
```

Equivalent wrappers:

```bash
bash release/package.sh --platform all --smoke basic
powershell -ExecutionPolicy Bypass -File release/package.ps1 -OS all -Smoke basic
```

3. Inspect `release/output/smoke-result.json`, archive checksum sidecars, and generated contents.
4. Commit and push the version and source changes before publishing.
5. Publish only from a clean Worker worktree whose `HEAD` matches its upstream:

```bash
npm run publish:obs
```

Or package and publish in one command after the commit is pushed:

```bash
npm run package:release -- --platform all --smoke auto --upload
```

Publishing reads `RELEASE_OBS_BUCKET` and `RELEASE_BASE_URL` from the environment or the Worker `.env`. It uploads immutable archives, checksum sidecars, release evidence, and bootstraps before uploading `latest.json` last. It then downloads and hashes every remote archive.

Use `--allow-same-version` only for byte-identical metadata repair. Use `--allow-dirty` or `--allow-unpushed` only for an explicitly approved recovery; report either exception.

## Codex app-server Worker

Work in `tools/codex-app-server-worker` and read its `README.md` release section when installer, updater, lifecycle, canary, or soak behavior is in scope.

Package and run its mandatory verification gates:

```bash
npm run package:release
```

Publish after committing and pushing:

```bash
npm run package:release -- --upload
```

For small changes, the package verification and deterministic archive checks are the basic smoke. Do not run the long canary/soak workflow automatically. For lifecycle, pool, affinity, privacy, routing, or Ultra execution changes, add the documented `local-smoke` canary and relevant install/update dry-run. Production soak remains a separate rollout gate.

## Release completion checks

Before reporting success, verify:

- Local version equals the intended version.
- Required tests, type checks, builds, and chosen smoke level passed.
- Commit is pushed and the relevant Worker worktree is clean.
- Remote `latest.json` reports the new product/version.
- Every referenced archive returns successfully and matches byte length and SHA-256.
- Installer bootstraps and release evidence match the generated local bytes.
- Temporary release directories are removed.
- Unchanged Workers are explicitly reported as not republished.

Do not treat a Worker upload as deployment of Java backend changes; call out any separate backend rollout required.
