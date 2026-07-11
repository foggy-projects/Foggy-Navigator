---
type: bug
bug_source: user-report
version: 1.4.0-SNAPSHOT
ticket: BUG-014
severity: major
status: closed
reproduction_status: confirmed
test_strategy: unit-test
automation_decision: required
owner: codex-app-server-worker
---

# Linux install validation treats the recursive test glob as a literal path

## Background

The public `0.3.3` Linux bootstrap downloads and extracts the release successfully, but the candidate validation in `install.sh` can stop at `npm test` before the install directory is switched. The user report was captured on 2026-07-11 from `curl .../codex-app-server-worker/install.sh | bash`.

## Reproduction

1. Use a Linux environment whose shell and Node test runner do not expand `tests/**/*.test.ts`.
2. Run the public `0.3.3` bootstrap.
3. Allow candidate validation to invoke `npm test`.
4. Observe `Could not find '.../tests/**/*.test.ts'` and a nonzero installer exit.

The repository package script used the same recursive shell glob, while all current test entrypoints are directly below `tests/`. The failure is deterministic under the stated runtime condition.

## Expected vs Actual

- Expected: candidate validation discovers the packaged `*.test.ts` entrypoints independently of shell and Node glob behavior, then continues with schema, typecheck, build, and installation.
- Actual: the unmatched recursive glob is passed to Node as a literal path, so validation stops before installation.

## Impact Scope

- Public Linux fresh install and update validation for Worker `0.3.3` on affected Node/shell combinations.
- The bootstrap fails before drain/switch, so it does not falsely report a successful install and does not replace a running installation.
- Windows or newer Node environments that expand the glob can pass, which allowed the compatibility gap to escape prior exact-package checks.

## Test Strategy

Automated regression is required because this is a stable release-path failure. Release-tooling tests now pin `npm test` to a platform-neutral launcher and verify recursive discovery includes only `*.test.ts` entrypoints. The complete Worker suite must also run through that launcher.

## Code Inventory

- `tools/codex-app-server-worker/package.json`: replace the shell glob test command and advance the immutable release version to `0.3.4`.
- `tools/codex-app-server-worker/package-lock.json`: synchronize the release version.
- `tools/codex-app-server-worker/src/version.ts`: synchronize runtime version identity.
- `tools/codex-app-server-worker/scripts/run-tests.mjs`: discover explicit test paths and invoke the Node test runner without shell glob expansion.
- `tools/codex-app-server-worker/tests/release-tooling.test.ts`: guard the launcher contract and discovery behavior.

## Fix Checklist

- [x] Reproduce the old package-script contract with a failing regression assertion.
- [x] Add the cross-platform recursive test launcher.
- [x] Route `npm test` through the launcher.
- [x] Advance source, package, and lock identities to `0.3.4` because the published `0.3.3` artifact is immutable.
- [x] Run release-tooling tests and the complete Worker suite.
- [x] Run schema verification, typecheck, and build.
- [x] Build a local `0.3.4` archive and verify it contains the launcher.
- [x] Publish `0.3.4` to OBS and repeat the exact public Linux install command in an isolated WSL2 Linux environment.

## Verification

- Before: `node --import tsx --test tests/release-tooling.test.ts` reported `8 passed / 1 failed`; the new launcher contract exposed the old `tests/**/*.test.ts` command.
- After targeted: release-tooling `10/10` passed.
- Linux compatibility: `node:18-bookworm-slim` with a clean `npm ci` launched release-tooling through `scripts/run-tests.mjs`; `10/10` passed without shell glob expansion.
- After full: `npm test` through `scripts/run-tests.mjs` reported `249 total / 242 passed / 7 skipped / 0 failed`.
- `npm run verify:schema`, `npm run typecheck`, and `npm run build` passed; schema digest remains `6f2550bb528581f17c4c3a3857dca92c860406aa3274e314cfa726c32e395d8f`.
- Local release candidate: `codex-app-server-worker-0.3.4.zip`, SHA-256 `616a0ce7cf8c017bd16e215022c3dcb24f27c37051297eff9c9623f9d6e4b440`, `198` entries, including `scripts/run-tests.mjs`.
- Public OBS: `latest.json` reports `0.3.4`, `1,804,723` bytes, source commit `bbea65843d16a367e73b9d6d68fcca6768b9edc3`, and `gitDirty=false`; publisher remote archive/hash/bootstrap verification passed.
- Exact WSL2 public install through the stable URL reported `249 total / 248 passed / 1 skipped / 0 failed`, then passed schema/typecheck/build and wrote `VERSION=0.3.4`; temporary install residue was `0` after cleanup.
- A Docker Node 18 exact-public probe passed the launcher and reached the full suite, but one unrelated process-tree fixture timed out under the container PID namespace (`248` other tests passed). A separate clean Node 18 launcher/release-tooling probe passed `10/10`; the reported literal-glob failure is closed.

## References

- [OPT-001 progress](./OPT-001-independent-codex-app-server-worker-progress.md)
- [OBS 0.3.4 release evidence](../evidence/OPT-001-obs-release-0.3.4.json)
