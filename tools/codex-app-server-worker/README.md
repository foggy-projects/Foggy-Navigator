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
- `DELETE /api/v1/tasks/:taskId` accepts only terminal tasks, purges encrypted request/event
  bodies and retains a permanent idempotency tombstone (`taskId + requestHash + outcome`).
- `GET /api/v1/capabilities` exposes the runtime/instance/revision, complete reasoning matrix,
  exact CLI/protocol version, feature flags, capacity, readiness, and schema digest.
- Working directories are checked against configured roots after filesystem `realpath`
  resolution, so a junction or symlink inside an allowed root cannot escape it.

P2 uses a long-lived exclusive-lease pool. A process serves only one root turn at a time; parallel
turns scale to separate instances in the same lane. Lanes are keyed by exact CLI, CODEX_HOME,
authentication fingerprint, base URL, and process-environment fingerprint. Metrics expose only
digests and counts, never credentials or home paths.

The default `instance_id` is a stable digest of hostname plus normalized state directory, not a
process ID. Multiple replicas must use distinct state directories and instance IDs unless they
use a separately validated shared store with instance-aware routing.
Runtime IDs are limited to 64 characters and instance IDs to 128 characters to match the
Navigator registry persistence contract.

Native child-agent events expose lifecycle state only. Child output, reasoning, provider errors,
and unsafe display metadata are not projected; labels and roles that resemble paths, URLs,
credentials, tokens, or other secrets are discarded at the Worker boundary.

## Local setup

1. Copy values from `.env.example` into `.env`.
2. Set a 32-byte base64 `CODEX_APP_SERVER_STATE_KEY`.
3. Set a strong `CODEX_APP_SERVER_WORKER_TOKEN`; all endpoints except `/health` require it.
4. Configure at least one controlled absolute root in `CODEX_APP_SERVER_ALLOWED_CWDS`.
5. Set `CODEX_HOME` to an isolated service directory that is not shared with the SDK Worker.
6. Run `npm install`, `npm test`, `npm run typecheck`, and `npm run build`.
7. Run `npm run verify:schema` whenever the pinned CLI or app-server protocol changes.

Default port: `3062`. The service reports degraded readiness and rejects task creation when the
encryption key, Worker token, cwd allowlist or isolated `CODEX_HOME` is absent, the CLI is unavailable, or its version differs from
`0.144.1`. The default bind address is loopback; remote exposure requires an explicit host and a
network boundary in addition to the Worker token.

## Operations

- Windows background start/stop: `./start.ps1` and `./stop.ps1`.
- Linux/macOS background start/stop: `./start.sh` and `./stop.sh`.
- Stop writes a local run-directory request so the Worker drains itself. The script waits the
  configured shutdown timeout plus five seconds before a final forced kill.
- Pass `-NoBuild` or `--no-build` only when `dist` was already built from current source.
- PID, stdout, stderr and durable state live under this package's `logs/` tree by default.
- An update installs the exact lockfile (`npm ci`), runs tests/schema verification/build, drains the
  service, replaces the package and starts it with the same state key/runtime identity.
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

After extracting the ZIP, install it with `./install.ps1 -InstallDir <path>` on Windows or
`./install.sh --install-dir <path>` on Linux/macOS. Installation intentionally does not start an
unconfigured service; it creates `.env` from `.env.example` when no existing configuration exists.

To update an existing install, run its current update script and pass the downloaded archive:

```powershell
./update.ps1 -Package ./codex-app-server-worker-0.1.0.zip
```

```bash
./update.sh --package ./codex-app-server-worker-0.1.0.zip
```

Both update paths expand into a sibling staging directory and run exact-lockfile `npm ci`, tests,
schema verification, type checking and build before requesting a drain. Only managed application
files and the validated `node_modules` tree are swapped. `.env`, `logs/`, durable state and any
external CODEX_HOME remain in place. A swap or readiness failure restores the prior application
and restarts it when it was running. Use `-DryRun` or `--dry-run` to validate an archive without
draining or changing the current install; use `-NoRestart` or `--no-restart` only for controlled
maintenance.
Each validation command has a five-minute watchdog by default. Set
`CODEX_APP_SERVER_UPDATE_STEP_TIMEOUT_SEC` to a positive integer for slower controlled hosts.

## Schema lock

`contracts/app-server-schema-lock.json` records the non-experimental schema tree generated by
Codex CLI `0.144.1`. The digest algorithm sorts relative paths, emits one LF-terminated
`<relative-path> <canonical-file-sha256>` line per file, then hashes that UTF-8 manifest with
SHA-256. Canonicalization recursively sorts JSON object keys because the CLI's aggregate schema
bundle can emit equivalent maps in a different key order across runs.

## Current feature boundary

The first dark-launch contract can directly pass through arbitrary model strings, but platform
routing is limited to the explicit per-model reasoning matrix in the capability manifest.
`gpt-5.6-sol` is declared through Ultra; `gpt-5.4-mini` is declared only for low through xhigh.
Dynamic passthrough is not evidence that an unlisted model/reasoning combination is production-ready.
The initial matrix is a pinned CLI `model/list` dark-launch snapshot. Account-specific dynamic catalog
refresh remains a P5 routing gate and is not inferred from direct passthrough.
Images, output schema, developer instructions, sandbox, network, and web settings are mapped.
Approval is restricted to `never`. URL attachments, additional directories, Biz MCP and
`max_turns` parity remain disabled and are declared as unsupported in the manifest.
`codex_config` is restricted to positive integer overrides for `model_context_window`,
`model_auto_compact_token_limit`, and `tool_output_token_limit`; process, provider, MCP,
sandbox, approval, and directory configuration cannot be injected through this field.
