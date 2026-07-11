# Codex App Server Worker

Independent Foggy Navigator runtime backed only by `codex app-server`. It does not import
`@openai/codex-sdk` and never falls back to the legacy SDK Worker.

## P0/P1 contract

- `POST /api/v1/tasks` requires `Idempotency-Key: <Navigator taskId>`.
- Identical retries return the same task; a changed normalized request returns HTTP `409`.
- Acceptance and request hash are fsync'd before execution. Sensitive request payloads are
  encrypted with AES-256-GCM using `CODEX_APP_SERVER_STATE_KEY`.
- Task phases are `accepted`, `starting`, `committed`, `running`, and `terminal`.
- `committed` is persisted before the JSON-RPC `turn/start` request is written.
- Restart recovery resumes `accepted`/`starting`; it never replays `committed`/`running`.
  P2 reconciles those tasks with `thread/read`. A proven terminal turn is restored; any missing,
  active, or ambiguous turn becomes terminal `APP_SERVER_RECOVERY_UNKNOWN` without replay.
- `GET /api/v1/tasks/:taskId/status`, `GET .../subscribe?ack_seq=N`, and
  `POST .../abort` preserve the stable task binding.
- Experimental Codex `item/tool/requestUserInput` requests are projected as sanitized
  `user_input_request` events and public `awaiting_input` status. The durable task phase remains
  `running` so `0.1.1` readers can still recover the journal safely.
- `POST /api/v1/tasks/:taskId/respond` accepts the exact pending `request_id` and one answer per
  question. Replies are once-only and bound to the same app-server instance, thread and turn;
  answer values are never persisted or emitted by the Worker.
- An SSE observer disconnect never starts or resumes a turn. Clients reconnect and read status;
  a second task for a nonterminal native thread is rejected with `APP_SERVER_THREAD_ACTIVE`.
- `DELETE /api/v1/tasks/:taskId` accepts only terminal tasks, purges encrypted request/event
  bodies and retains a permanent idempotency tombstone (`taskId + requestHash + outcome`).
- `GET /api/v1/capabilities` exposes the runtime/instance/revision, complete reasoning matrix,
  exact CLI/protocol version, feature flags, capacity, readiness, and schema digest.
- Working directories are checked against configured roots after filesystem `realpath`
  resolution, so a junction or symlink inside an allowed root cannot escape it. Worker state,
  `CODEX_HOME`, `CODEX_BIZ_HOME_ROOT`, and their ancestors remain invalid task directories even
  when a filesystem root is allowed.
- The cwd allowlist is an admission check, not a filesystem sandbox. In particular,
  `danger-full-access` tasks can access absolute paths outside their cwd, including Worker-private
  paths and, on Windows, `C:\` even when it is absent from the allowlist. Run broad defaults only
  for trusted tasks on a dedicated OS account/host, or enforce a separate container/OS boundary.

P2 uses a long-lived exclusive-lease pool. A process serves only one root turn at a time; parallel
turns scale to separate instances in the same lane. Lanes are keyed by exact CLI, CODEX_HOME,
authentication fingerprint, base URL, and process-environment fingerprint. Metrics expose only
digests and counts, never credentials or home paths.

The public `instance_id` is a random state-store generation identity persisted atomically inside
the state directory. Identity schema v2 is backed by matching generation sentinels in both the
`tasks` and `events` directories, so clearing either journal directory rotates the identity instead
of silently serving old affinity bindings from an empty store. Existing schema v1 markers receive
one compatibility migration without changing their identity. A pre-marker store with journals
adopts its legacy configured/calculated identity once: header-safe legacy values are retained
exactly; older values that were legal but unsafe in an HTTP header are recorded losslessly only in
the private marker and exposed as a deterministic `codex-legacy-<sha256>` identity.

The state root is resolved to its physical path before identity, lease, and journal access. A
single-writer owner lease prevents concurrent local JSONL writers; its same-host dead-PID recovery
lock also carries owner metadata, while cross-host ownership fails closed. Multiple replicas must
use distinct state directories unless they use a separately validated shared store with
instance-aware routing.
Runtime IDs are limited to 64 characters and instance IDs to 128 characters to match the
Navigator registry persistence contract.

Native child-agent events expose lifecycle state only. Child output, reasoning, provider errors,
and unsafe display metadata are not projected; labels and roles that resemble paths, URLs,
credentials, tokens, or other secrets are discarded at the Worker boundary.

## Local setup

1. Copy values from `.env.example` into `.env`.
2. Set a 32-byte base64 `CODEX_APP_SERVER_STATE_KEY`.
3. Set a strong `CODEX_APP_SERVER_WORKER_TOKEN`; all endpoints except `/health` require it.
4. Configure at least one absolute root in `CODEX_APP_SERVER_ALLOWED_CWDS`. Release installers
   populate this only for a fresh `.env`; source-based setup remains explicit.
5. Set `CODEX_HOME` to an isolated service directory that is not shared with the SDK Worker.
6. Run `npm install`, `npm test`, `npm run typecheck`, and `npm run build`.
7. Run `npm run verify:schema` whenever the pinned CLI or app-server protocol changes.

Default port: `3062`. The service reports degraded readiness and rejects task creation when the
encryption key, Worker token, usable cwd allowlist, or isolated `CODEX_HOME` is absent, the CLI is
unavailable, or its version differs from `0.144.1`. An allowlist whose roots are missing, offline,
or wholly inside a Worker-private directory is not usable. The default bind address is loopback;
remote exposure requires an explicit host and a network boundary in addition to the Worker token.

## Operations

- Windows background start/stop: `./start.ps1` and `./stop.ps1`.
- Linux background start/stop: `./start.sh` and `./stop.sh`. macOS lifecycle operations are not
  enabled: the runtime cannot obtain the exact argv and boot-scoped process creation identity
  required for destructive process-tree verification, so process identity commands fail closed.
- Stop writes a nonce-bound request in the local run directory so the Worker drains itself. A
  graceful stop is accepted only when the matching `shutdown.success` marker is present and the
  exact snapshotted Worker process tree has no verified descendants. Otherwise the tree is killed,
  the operation fails, and `stop.failed` latches both start and update.
- The writer lease is removed only after active tasks, accepts, recovery, and task operations are
  quiescent. A shutdown deadline miss retains the lease until process exit for dead-PID recovery.
- Pass `-NoBuild` or `--no-build` only when `dist` was already built from current source.
- PID, stdout, stderr and durable state live under this package's `logs/` tree by default.
- `CODEX_APP_SERVER_RUN_DIR`, `CODEX_APP_SERVER_LOG_DIR` and `CODEX_APP_SERVER_STATE_DIR`
  use process environment > `.env` > package default precedence in every lifecycle script. External
  values must be absolute paths; quote values containing spaces or `#` in `.env`.
- An update installs the exact lockfile (`npm ci`), runs tests/schema verification/build, drains the
  service, replaces the package and starts it with the same state key/runtime identity.
- `lifecycle.lock` is the installation-wide, nonce-owned lock shared by start, stop and update. The
  external operation creates it atomically and holds it through its complete success or handled
  failure path. Updater-owned nested stop/start calls verify the same nonce without acquiring or
  releasing the lock. There is no PID-, age- or TTL-based lock reclamation; an abrupt process crash
  intentionally leaves the lock fail-closed for operator recovery. Linux `HUP`, `INT` and `TERM`
  interruptions preserve the owned lock under the same recovery rule.
- `update.in-progress` is an exclusive, nonce-owned, fsync-backed transaction record. Start and stop
  fail closed while it exists unless invoked by that updater with the matching internal nonce. A
  crash or mixed swap preserves the marker, staging path and per-file progress for operator recovery.
- After a failed stop or candidate startup, verify that no Worker descendants remain before
  explicitly removing `stop.failed`. Lifecycle scripts never clear this operator-review latch.
- `lifecycle.failed` and non-empty `runtime-process-trees/` are state-directory fallback evidence
  for failures that could not be recorded in the run directory. Start and update refuse this
  evidence and never remove it. Prove zero residue against the retained snapshots before explicitly
  clearing either evidence path.
- Never delete `update.in-progress` merely to retry. Compare its nonce, phase, staging path,
  `backed_up` and `installed` progress with the target and sibling staging backup, complete an
  external recovery to one coherent version, and prove zero process residue first. The updater
  does not automatically resume a crash transaction.
- Recover a crash-held lifecycle lock in this order: stop or isolate the crashed lifecycle command;
  prove that no updater, Worker or captured descendant remains; reconcile `update.in-progress`, its
  staging backup and retained process-tree snapshots to one coherent installation; review and clear
  `stop.failed` or state fallback evidence only after zero residue is proven; then remove
  `lifecycle.lock` last. Verify that the lock is a regular non-linked file before manual removal and
  never remove it merely because its timestamp is old.
- Release packaging includes `dist`, `package*.json`, `contracts`, scripts, README and
  `.env.example`; it excludes `.env`, logs, state journals, auth files and CODEX_HOME.

### Release package and install

Run `npm run package:release` from a clean source checkout. Packaging first runs tests, schema
verification, type checking and a clean build. It writes a deterministic cross-platform ZIP plus
its SHA-256 file under `release/output/`:

```text
release/output/codex-app-server-worker-<version>.zip
release/output/codex-app-server-worker-<version>.zip.sha256
```

Publish the package, checksum, bootstrap installers, and `latest.json` to the canonical OBS prefix
without passing a version explicitly:

```powershell
powershell -ExecutionPolicy Bypass -File release/package.ps1 -Upload
```

```bash
npm run package:release -- --upload
```

The version is resolved from `package.json`, `package-lock.json`, and `src/version.ts`, which must
match. Publishing rejects downgrades and same-version replacement. `-AllowSameVersion` /
`--allow-same-version` is limited to repairing metadata for byte-identical archives. Override the
canonical destination only with `CODEX_APP_SERVER_RELEASE_OBS_BUCKET`,
`CODEX_APP_SERVER_RELEASE_BASE_URL`, or the equivalent publisher arguments.

End users install the latest published release without knowing its version:

```powershell
irm https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/codex-app-server-worker/install.ps1 | iex
```

```bash
curl -fsSL https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/codex-app-server-worker/install.sh | bash
```

The bootstrap validates product/schema, relative artifact path, byte length, and SHA-256 before it
runs any package code. A fresh installation remains stopped so required secrets and isolated
`CODEX_HOME` can be configured. Re-running the same command upgrades an existing installation by
calling its already-installed updater, preserving lifecycle and rollback protections. The shell
bootstrap supports Linux only; macOS remains unsupported.

After extracting the ZIP, install it with `./install.ps1 -InstallDir <path>` on Windows or
`./install.sh --install-dir <path>` on Linux. Installation intentionally does not start an
unconfigured service. When no `.env` exists, it creates one from `.env.example`; Linux defaults
`CODEX_APP_SERVER_ALLOWED_CWDS` to `/`, while Windows snapshots every ready drive-letter root
except `C:\` (for example `D:\,E:\`). A Windows machine with no ready non-C drive keeps the value
empty and emits a warning. These broad defaults are intended for a dedicated local Worker; narrow
them when the host requires tighter workspace scope.

Install and update never rewrite an existing `.env`. Drives attached after installation are not
added automatically, and `C:\` is available as a task cwd only through explicit configuration.

For an offline update of an existing 0.1.1-or-newer install, run its current update script and pass
the downloaded archive:

```powershell
./update.ps1 -Package ./codex-app-server-worker-<version>.zip
```

```bash
./update.sh --package ./codex-app-server-worker-<version>.zip
```

In-place update of a `0.1.0` installation is explicitly unsupported, whether it is running or
stopped. Install `0.1.1` or newer into a new empty directory, or use an external OS-level migration
that independently proves zero process residue before moving configuration and state. The updater
never captures a fresh identity for a legacy PID and never drains or modifies a `0.1.0` target.

Both update paths expand into a sibling staging directory and run exact-lockfile `npm ci`, tests,
schema verification, type checking and build before requesting a drain. Only managed application
files and the validated `node_modules` tree are swapped. `.env`, `logs/`, durable state and any
external CODEX_HOME remain in place. A swap failure before candidate startup restores the prior
application and restarts it when it was running. Once candidate startup has been attempted, any
failure preserves the candidate files, `update.in-progress`, and the sibling staging backup; it
leaves `stop.failed` latched when possible and does not auto-restart either version. Inspect the
transaction phase and progress before explicit operator recovery. Use `-DryRun` or `--dry-run` to validate an archive without draining or changing the
current install; use `-NoRestart` or `--no-restart` only for controlled maintenance.
Each validation command has a five-minute watchdog by default. Set
`CODEX_APP_SERVER_UPDATE_STEP_TIMEOUT_SEC` to a positive integer for slower controlled hosts.

### Canary and soak evidence

Build the Worker, copy `scripts/canary-soak.example.json` to an environment-owned location, and
set the environment variables named by `navigator.tokenEnv` and `privacyMarkerEnvNames`. Secret
values are accepted only from environment variables. They are never written to the checkpoint or
included in CLI output.

The production profile also requires an exact `runtimeRevision`, `routingEpoch`, physical
`workerId`, Ultra `model`, `providerType=codex-worker`, and `cohortMarkerEnv`. A terminal task is
counted only when all of those fields match, its prompt contains the current cohort marker, its
runtime type is `APP_SERVER`, and its runtime instance was observed in the same complete health
cycle. Production time filtering requires the numeric `createdAtEpochMs` persisted by Navigator
from the task-creation instant; legacy rows remain null and are not inferred from `LocalDateTime`.
The legacy `createdAt` text is never interpreted for production evidence. Production HTTP is
permitted only for loopback endpoints; remote endpoints must use HTTPS.
Missing or unrecognized app-server instance IDs are excluded from the terminal denominator and
recorded as deduplicated, digest-keyed affinity violations, which fail the zero-tolerance gate.

Use `--once` from cron, Task Scheduler, or another supervisor. Each due run polls the Navigator
`/api/v1/codex-tasks` API, samples every configured Worker `/health` endpoint, atomically replaces
the checkpoint, and exits. Invocations before `next_due_at` perform no network requests. `--report`
reads the checkpoint without requiring credentials or making requests:

```text
npm run build
node scripts/canary-soak.mjs --config <config.json> --once
node scripts/canary-soak.mjs --config <config.json> --report
node scripts/canary-soak.mjs --config <config.json> --report --require-pass
```

`--require-pass` returns a non-zero exit code while any gate is pending. If a previous checkpoint
uses an incompatible schema or belongs to a different configuration, the CLI fails with
`CANARY_STATE_RESET_REQUIRED`; reset it explicitly and atomically with `--reset`. A per-state-file
lease rejects overlapping samplers. A dead PID from the same host may be reclaimed, while an active
local owner, a cross-host owner, malformed lease metadata, or an existing reclamation claim fails
closed. A leftover reclamation claim requires operator verification before the lease is removed.

Without `--once` or `--report`, the command remains active and samples on the configured interval.
Task IDs, Worker instance IDs, and health endpoints are stored only as SHA-256 digests. Raw task
results, prompts, errors, URLs, tokens, and privacy markers are not persisted. A production window
resets when the interval between successful complete samples exceeds `maxSampleGapSeconds`.

The production gate cannot be weakened by configuration: it requires at least 50 distinct terminal
tasks, 72 continuous hours, two pool retirements/rotations, 98% success, at most 1% internal errors,
zero affinity mismatches, and zero privacy-marker leakage. A `local-smoke` profile may lower the
sample/time/rotation thresholds, but both its checkpoint and every report are permanently and
prominently marked as ineligible for production evidence.

This command is only the automated collection foundation for the 50-task / 72-hour / two-rotation
window. Production reports deliberately include the hard check
`external_production_evidence=false`, so they remain `PENDING` and can never authorize rollout by
themselves. Baseline comparison, p95 latency, RSS/CPU, pool-acquire behavior, duplicate execution,
and raw child-output/privacy evidence must be supplied and reviewed externally before production
sign-off. No external-evidence schema is inferred or synthesized by this tool.

## Schema lock

`contracts/app-server-schema-lock.json` records the non-experimental schema tree generated by
Codex CLI `0.144.1`. The digest algorithm sorts relative paths, emits one LF-terminated
`<relative-path> <canonical-file-sha256>` line per file, then hashes that UTF-8 manifest with
SHA-256. Canonicalization recursively sorts JSON object keys because the CLI's aggregate schema
bundle can emit equivalent maps in a different key order across runs.

## Current feature boundary

The first dark-launch contract can directly pass through arbitrary model strings, but platform
routing is limited to the explicit per-model reasoning matrix in the capability manifest.
`gpt-5.6-sol` is declared through Ultra. Retired `gpt-5.4-mini` requests are rejected after alias
resolution with stable code `UNSUPPORTED_CODEX_MODEL` and are absent from the capability manifest.
Dynamic passthrough is not evidence that an unlisted model/reasoning combination is production-ready.
The initial matrix is a pinned CLI `model/list` dark-launch snapshot. Account-specific dynamic catalog
refresh remains a P5 routing gate and is not inferred from direct passthrough.
Images, output schema, developer instructions, sandbox, network, and web settings are mapped.
Approval is restricted to `never`. URL attachments, additional directories, Biz MCP and
`max_turns` parity remain disabled and are declared as unsupported in the manifest.
`codex_config` is restricted to positive integer overrides for `model_context_window`,
`model_auto_compact_token_limit`, and `tool_output_token_limit`; process, provider, MCP,
sandbox, approval, and directory configuration cannot be injected through this field.
