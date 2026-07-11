---
type: bug
bug_source: user-report
version: 1.4.0-SNAPSHOT
ticket: BUG-015
severity: major
status: closed
reproduction_status: confirmed
test_strategy: unit-test
automation_decision: required
owner: codex-app-server-worker
---

# Node 18 candidate validation fails on `import.meta.dirname`

## Background

After public `0.3.4` fixed shell-dependent test discovery, the same user host completed all `249` tests but stopped at `npm run verify:schema` on Node `18.19.1`. The schema verifier passed `undefined` from `import.meta.dirname` into `path.resolve`. A source sweep found the same unsupported API in the subsequent clean-build script.

## Reproduction

1. Run the public Linux bootstrap with Node `18.19.1`.
2. Allow the candidate suite to finish (`248` passed, `1` skipped, `0` failed).
3. Observe `verify-app-server-schema.mjs:7` fail with `ERR_INVALID_ARG_TYPE` because `paths[0]` is `undefined`.

## Expected vs Actual

- Expected: every pre-install validation script works on the supported Node 18 runtime.
- Actual: `verify-app-server-schema.mjs` and, if reached, `clean.mjs` depend on `import.meta.dirname`, which is unavailable on the reported runtime.

## Impact Scope

- Linux fresh install and update validation on Node 18.
- The failure occurs before drain/switch, so the existing installation is preserved and the candidate is not falsely committed.

## Test Strategy

Automated regression is required because the failure is stable and blocks the release path. Release-tooling now rejects candidate validation scripts that use `import.meta.dirname` or `import.meta.filename` and requires the Node 18-compatible `fileURLToPath(import.meta.url)` pattern.

## Code Inventory

- `tools/codex-app-server-worker/scripts/verify-app-server-schema.mjs`
- `tools/codex-app-server-worker/scripts/clean.mjs`
- `tools/codex-app-server-worker/tests/release-tooling.test.ts`
- Worker version identities in `package.json`, `package-lock.json`, and `src/version.ts`

## Fix Checklist

- [x] Add a failing regression assertion for unsupported `import.meta` path APIs.
- [x] Replace both occurrences with `path.dirname(fileURLToPath(import.meta.url))`.
- [x] Advance immutable release identity to `0.3.5`.
- [x] Pass release-tooling, schema, and build locally.
- [x] Pass release-tooling, schema, and build in Linux Node 18.20.8.
- [x] Publish `0.3.5` and verify the exact public archive's schema/build path on Linux Node 18.

## Verification

- Before: release-tooling `10 passed / 1 failed`, identifying `clean.mjs` first; source sweep confirmed both affected scripts.
- After local: release-tooling `11/11`, schema digest `6f2550bb528581f17c4c3a3857dca92c860406aa3274e314cfa726c32e395d8f`, and build passed.
- After Linux Node 18.20.8 clean `npm ci`: schema, clean build, and release-tooling `11/11` passed.
- Published release pipeline: `250 total / 243 passed / 7 skipped / 0 failed`; schema/typecheck/build and publisher remote archive/bootstrap verification passed.
- Exact public `0.3.5` archive on Linux Node 18.20.8: SHA-256 passed, followed by clean `npm ci`, schema verification, and clean build.
- Public manifest: `1,805,254` bytes, source `cfc5f5217c2adc72701fc44a65c908aed4329a46`, `gitDirty=false`.

## References

- [BUG-014 Linux test glob blocker](./BUG-014-app-worker-linux-test-glob-install-blocker.md)
- [OPT-001 progress](./OPT-001-independent-codex-app-server-worker-progress.md)
- [OBS 0.3.5 release evidence](../evidence/OPT-001-obs-release-0.3.5.json)
