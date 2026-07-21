---
doc_type: test-record
version: 1.4.3-SNAPSHOT
related_workitem: ../workitems/GOV-001-dev-s1-s2-integration-mvp.md
scope: local-trusted-development-only
status: BLOCKED
executed_at: 2026-07-20
---

# GOV-001 开发期内部 runtime 联调记录（2026-07-20）

## Boundary

- Target: the local Navigator instance only; no SIM/TMS source repository, real account, business data, function call, Gateway external mode, strict Worker-principal mode, or production surface was used.
- Credential discipline: the request process retained only the intended ClientApp runtime lane. No admin/control credential, profile content, token, key, or secret is recorded here.
- Smoke payload: one static echo-style prompt with `--max-turns 1`, networking disabled and a narrow tool allowance. It explicitly forbade tools, files, directories, accounts, business functions, network access, and upstream routing.
- This record does not treat `NAVIGATOR_EXTERNAL_ENABLED`, `surfaceReady`, or a health response as Provider, Worker Gateway, or production readiness.

## Observed preflight

| Check | Result | Evidence / interpretation |
| --- | --- | --- |
| Navigator health | PASS | `GET /actuator/health` returned `UP`. |
| Open API surface status | PASS, development only | `surfaceReady=true`, `productionReady=false`. This is not a Gateway or production claim. |
| TMS runtime token / tuple inspection | PASS | Runtime-token exchange, explicit runtime tuple inspection, readiness and owner-smoke completed before the ask attempt. |
| TMS execution identity resolution | PASS | The configured route resolved as `LANGGRAPH_BIZ` / `BIZ_WORKER_IDENTITY`; it is not evidence for Codex Physical Worker routing. |
| SIM manifest | PASS, not ask-ready | The existing SIM manifest validates, but its configured Codex endpoint has no listener. Local Codex Worker `3051` was not substituted. |

## Safe ask result

- Result: **BLOCKED before task creation and before Worker dispatch**.
- The runtime-only request reached Navigator and completed Agent, model, directory and execution-resource resolution. At `2026-07-20 16:51:34 +08:00`, persistence of the prebound task-scoped token failed with MySQL error `1406` / `22001`: `Data too long for column 'function_scope_json'` in `business_task_scoped_token`.
- No task ID was returned. Therefore there is no terminal task status, static marker, Worker execution, provider result, Gateway result, or Codex-route result to claim.
- This is a database schema/capacity contract blocker, not an authorization denial and not a reason to broaden runtime permissions, change allowed tools, enable Gateway external, or create a Worker, BizWorkerIdentity, or WorkerPool member.

## Required follow-up before retry

1. Have the Navigator local-instance owner run a metadata-only schema check for `business_task_scoped_token.function_scope_json`; the recorded migration contract requires `LONGTEXT NOT NULL`.
2. If the live column differs, apply the existing versioned migration `docs/migration/2026-07-14-business-task-token-v2.sql` through the approved local DB change path and restart the owned local launcher.
3. Add a separate fail-closed startup preflight regression for an undersized `function_scope_json` type before treating the migration gap as fully remediated.
4. Repeat the identical runtime-only static safe ask. Only then check task terminal status and marker, followed by cross-ClientApp/tenant negative verification.
5. SIM needs its owner to restore its already-configured endpoint or provide the correct existing route; do not substitute a different local Worker.

## Acceptance truth

- Internal runtime integration: **not passed**.
- Upstream integration handoff: **not yet authorized**; it starts only after the TMS safe ask succeeds and its negative isolation test passes.
- Gateway external, provider readiness and production readiness: **not tested and not implied**.
