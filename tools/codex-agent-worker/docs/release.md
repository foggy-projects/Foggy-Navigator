# Codex Agent Worker release

The release implementation is cross-platform Node.js. Bash and PowerShell files under `release/`
are thin compatibility wrappers and must not contain a second packaging implementation.

## Package a candidate

```bash
npm run package:release -- --platform all --smoke auto
```

This runs unit tests, type checking, a clean build, deterministic archive generation, SHA-256
sidecars, and the selected smoke level. Candidate files are written to `release/output/`.

Use `--skip-verify` only while debugging the packager. A candidate built with skipped verification
must not be published without rebuilding through the normal gates.

## Smoke levels

| Level | Behavior | Typical use |
|---|---|---|
| `skip` | No packaged-candidate smoke | Documentation/tests-only changes; packaging gates still run |
| `basic` | Verify all archive structures, required files, forbidden files, sizes, and SHA-256 | Small isolated runtime changes |
| `full` | Basic checks plus install production dependencies from the current-host archive and start `/health` | Dependencies, lifecycle, resume/process ownership, installer/updater, auth, runtime compatibility |
| `auto` | Classify changed paths into one of the levels above | Default |

`full` does not make a live model request. Paid/live query smoke remains an explicit separate step:

```bash
npm run test:resume-shell
```

Only run the live query smoke when model execution or resume behavior requires it and credentials
and a test Worker are intentionally provided.

## Publish to OBS

Configure `RELEASE_OBS_BUCKET` and `RELEASE_BASE_URL` in the environment or the ignored Worker
`.env`, then commit and push the version/source changes. Publishing fails unless the Worker
worktree is clean and `HEAD` matches its upstream.

```bash
npm run package:release -- --platform all --smoke auto --upload
```

Or publish a previously built candidate:

```bash
npm run publish:obs
```

The publisher uploads archives, checksum sidecars, `release-evidence.json`, and bootstrap installers
before rechecking the remote version and uploading `latest.json` last. It then downloads and hashes
every referenced platform archive and verifies the exact bootstrap and evidence bytes.

Remote `latest.json` read failures stop the release. Same-version replacement is rejected unless
`--allow-same-version` is used and every archive hash and size remains identical.

## CI boundary

`.github/workflows/codex-worker-release-candidate.yml` builds and retains release candidates. It
does not receive OBS production credentials and does not publish. Perform the final OBS commit from
an approved release environment after reviewing the candidate evidence.
