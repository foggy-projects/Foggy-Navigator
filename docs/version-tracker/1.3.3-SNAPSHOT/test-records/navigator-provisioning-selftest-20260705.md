# Navigator Provisioning Selftest - 2026-07-05

## Scope

- version: `1.3.3-SNAPSHOT`
- workitem: `OPT-001-dev-operator-key-provisioning-boundary`
- goal: move platform provisioning smoke ahead of SIM / TMS upstream dependency.

## Boundary

- Do not access real TMS.
- Do not read `accounts/`.
- Do not print or persist token, secret, cookie, real account, password, admin key, control key, runtime token, or claim token in tracked files.
- Prepare-only selftest must not require upstream admin credentials.

## Added Artifact

- `tools/navigator-upstream/scripts/navigator-provisioning-selftest.ps1`
- `tools/navigator-upstream/fixtures/provisioning-selftest/README.md`
- `tools/navigator-upstream/fixtures/provisioning-selftest/system-profile.example.env`
- `tools/navigator-upstream/fixtures/provisioning-selftest/tenant-profile.example.env`

## Planned Execution Order

1. Run prepare-only selftest and local `worker-host verify`.
2. Run Java CLI regression if source changes are present.
3. When an LLM resource is provided, run live selftest with gitignored selftest profiles.
4. After local selftest passes, issue scoped SIM / TMS dev provisioning credentials.
5. Re-run SIM / TMS smoke without using them as the first proof of Navigator provisioning correctness.

## Evidence

- prepare-only run passed:
  - command: `.\tools\navigator-upstream\scripts\navigator-provisioning-selftest.ps1 -PrepareOnly`
  - result: generated local worker-host, directory, and Agent manifests under `temp/navigator-provisioning-selftest`.
  - local verify result included `workerRole role=biz ... source=BIZ_WORKER_IDENTITY`.
  - no SIM / TMS upstream credential was required.
- CLI regression passed:
  - command: `mvn -pl navigator-open-sdk -am "-Dtest=UpstreamCliTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
  - result: 98 tests run, 0 failures, 0 errors, 0 skipped.
- secret leakage scan passed:
  - scanned 1.3.3 docs and selftest script / README for real key prefixes and cookie/password assignments.
  - result: no matches.
- live-mode boundary hardening passed:
  - selftest live mode now requires an explicit worker token environment variable by default.
  - `-GenerateEphemeralWorkerToken` is available only for disposable local registration.
  - prepare-only run was re-executed after the change and still passed with `BIZ_WORKER_IDENTITY`.
- live provisioning selftest passed:
  - command family: `.\tools\navigator-upstream\scripts\navigator-provisioning-selftest.ps1` with gitignored selftest profiles.
  - tenant: `navi-codex-biz-smoke-local`.
  - clientAppId: `capp_1d1b426a-92cb-4865-bed7-339c29c5d1ac`.
  - modelConfigId: `7f43fd7c-4f36-4196-8ee8-b66c12aad2ea`.
  - directoryId: `20260705-c00b`.
  - agentCode: `navigator-provisioning-selftest-agent-a`.
  - readiness result: `WORKER_HOST_ROLE_ROUTING=OK`, `workerRole role=biz ... source=BIZ_WORKER_IDENTITY`.
  - owner-smoke result: readiness OK and resources OK.
  - route alignment: directory creation used the Claude anchor worker id; LangBiz execution used the Biz worker identity.
- cross-ClientApp isolation smoke passed:
  - command family: live selftest with `-RunIsolationChecks`.
  - Tenant A clientAppId: `capp_1d1b426a-92cb-4865-bed7-339c29c5d1ac`.
  - Tenant B clientAppId: `capp_1330e956-a0b2-40fc-95fa-1f2a92e2b67c`.
  - result: Tenant A profile attempting to list Tenant B model grants failed as expected.
- CLI regression re-run passed:
  - command: `mvn -pl navigator-open-sdk -am "-Dtest=UpstreamCliTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
  - result: 98 tests run, 0 failures, 0 errors, 0 skipped.
- post-live prepare-only re-run passed:
  - command: `.\tools\navigator-upstream\scripts\navigator-provisioning-selftest.ps1 -PrepareOnly`
  - result: generated fixtures remained valid and local verify still resolved Biz role from `BIZ_WORKER_IDENTITY`.
- post-live CLI regression re-run passed:
  - command: `mvn -pl navigator-open-sdk -am "-Dtest=UpstreamCliTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
  - result: 98 tests run, 0 failures, 0 errors, 0 skipped.
- post-live diff check passed:
  - command family: `git diff --check` over selftest script, fixtures, 1.3.3 docs, and CLI source/test files.
  - result: no whitespace errors; Windows CRLF conversion warnings only.
- final secret leakage scan passed:
  - scanned 1.3.3 docs, selftest script, and provisioning selftest fixtures.
  - result: no matches.
- post-live secret leakage scan passed:
  - scanned selftest script, provisioning selftest fixtures, 1.3.3 docs, and CLI source/test files.
  - result: no real secret-like values found in scoped files.
- TMS provisioning smoke passed:
  - profile: `.navigator/upstream.env` (gitignored).
  - ClientApp: `capp_2852124a-48f7-4098-9d5e-33eb736c4375`.
  - modelConfigId: `a8ed6f14-949c-4003-b108-99b78de65ff5`.
  - directoryId: `20260705-228b`.
  - bizWorkerId: `tms-ui-experience-reviewer-biz`.
  - readiness result: `WORKER_HOST_ROLE_ROUTING=OK`, `workerRole role=biz ... source=BIZ_WORKER_IDENTITY`, `effectiveDirectoryId=20260705-228b`.
  - owner-smoke result: readiness OK and resources OK.
  - Actor Home live smoke task: `lgt_5f997dcdb8834d51`, completed with effective directory `20260705-228b`.
  - run record: `docs/scopes/tms/tms-ltl-ui-qa/rehearsals/ui-experience-reviewer-navi-provisioning-run-20260705.md`.
- cross-upstream CLI isolation smoke passed:
  - SIM profile attempting to list the TMS ClientApp grants failed with `HTTP 403: control-plane credential clientAppId mismatch`.
  - TMS profile attempting to list the SIM ClientApp grants failed with `HTTP 403: control-plane credential clientAppId mismatch`.
- provisioning knowledge capture completed:
  - project skill: `.agents/skills/navigator-runtime-provisioning/SKILL.md`.
  - runbook: `docs/version-tracker/1.3.3-SNAPSHOT/runbooks/navigator-runtime-provisioning-sop.md`.
  - rule: real keys stay only in gitignored local profiles or platform secrets; tracked files contain IDs, placeholders, commands, and smoke outcomes only.

## Follow-up

- Before formal handoff, run the same selftest with a non-ephemeral worker token instead of `-GenerateEphemeralWorkerToken`.
- Add production credential expiry pre-warning and stop/restart runtime-token exchange smoke before final acceptance.
