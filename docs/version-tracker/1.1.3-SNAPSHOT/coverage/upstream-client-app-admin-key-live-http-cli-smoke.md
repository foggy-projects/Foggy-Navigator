---
evidence_scope: live-http-cli-smoke
version: 1.1.3-SNAPSHOT
target: upstream-client-app-admin-key
status: passed
executed_by: Codex
executed_at: 2026-05-18
---

# Live HTTP/CLI Smoke Evidence

## Scope

本证据覆盖 issue #121 的真实进程级链路：启动 `launcher`，通过 `navigator-open-sdk` CLI 对本地 HTTP 服务执行 admin key 申请、operator 审批、claim、ClientApp ensure 和 control key 签发。

该 smoke 不使用 mock HTTP server；CLI 命令实际访问 `http://localhost:8112`。

## Environment

- Launcher: `java -Dfile.encoding=UTF-8 -jar launcher/target/launcher-1.0.0-SNAPSHOT.jar`
- Launcher PID: `61032`
- HTTP port: `8112`
- MySQL: `foggy-navigator-mysql`, `localhost:13309`, healthy
- RabbitMQ: `foggy-navigator-rabbitmq`, healthy
- JPA schema policy: `spring.jpa.hibernate.ddl-auto: update`
- Runtime logs: `temp\navi-admin-smoke\launcher.out.log`, `temp\navi-admin-smoke\launcher.err.log`
- Smoke run: `temp\navi-admin-smoke\run-20260518123552`

Secrets were stored only under ignored `temp/` profiles and are not copied into this document.

## Operator Credential Setup

The launcher was restarted with a local operator key for this smoke. The raw `NAVI_OPERATOR_API_KEY` was stored under `temp\navi-admin-smoke\operator.key`; the launcher received only the service-side hash through `foggy.navigator.operator.api-key-hash`.

Important config detail found during smoke: `NaviOperatorCredentialService` uses `SecretTokenSupport.sha256`, whose stored value is SHA-256 bytes encoded as URL-safe Base64 without padding, not hex. A first smoke attempt using hex hash correctly failed with `HTTP 401`; after switching to the service hash format, operator list/approve succeeded.

## Command Chain

All commands were run through Maven exec against the SDK CLI:

```powershell
mvn -q -pl navigator-open-sdk exec:java "-Dexec.args=upstream --profile <run>\upstream.env config check"
mvn -q -pl navigator-open-sdk exec:java "-Dexec.args=upstream --profile <run>\upstream.env admin-key request --base-url http://localhost:8112 --upstream-system-id tms-live-smoke-20260518123552 --requested-tenant-id tenant-live-smoke-20260518123552 --multi-tenant --reason live-http-cli-smoke --write-profile"
mvn -q -pl navigator-open-sdk exec:java "-Dexec.args=upstream --profile <run>\upstream.env admin-key status"
mvn -q -pl navigator-open-sdk exec:java "-Dexec.args=upstream --profile <run>\upstream.env admin-key list --status PENDING"
mvn -q -pl navigator-open-sdk exec:java "-Dexec.args=upstream --profile <run>\upstream.env admin-key approve --authorized-tenant-ids tenant-live-smoke-20260518123552 --namespace tms-live-smoke-20260518123552 --scopes CLIENT_APP_ADMIN,CONTROL_KEY_ISSUE --claim-ttl-minutes 60"
mvn -q -pl navigator-open-sdk exec:java "-Dexec.args=upstream --profile <run>\upstream.env admin-key claim --write-profile"
mvn -q -pl navigator-open-sdk exec:java "-Dexec.args=upstream --profile <run>\upstream.env client-app ensure --target-tenant-id tenant-live-smoke-20260518123552 --upstream-ref tenant-a-20260518123552 --name LiveSmoke-20260518123552 --tenant-profile <run>\tenant-a.env --write-profile"
mvn -q -pl navigator-open-sdk exec:java "-Dexec.args=upstream --profile <run>\upstream.env client-app list --target-tenant-id tenant-live-smoke-20260518123552"
mvn -q -pl navigator-open-sdk exec:java "-Dexec.args=upstream --profile <run>\upstream.env client-app issue-control-key --client-app-id <clientAppId> --tenant-profile <run>\tenant-a.env --write-profile"
```

## Result Summary

| Step | Result | Evidence file | Notes |
| --- | --- | --- | --- |
| `config check` | passed | `00-config-check.out.txt` | Profile path under ignored `temp/` |
| `admin-key request` | passed | `01-admin-key-request.out.txt` | Request code suffix `5ginFMy0`; claim token stored in profile only |
| `admin-key status` before approval | passed | `02-admin-key-status-pending.out.txt` | Public status endpoint reachable |
| `admin-key list --status PENDING` | passed | `03-admin-key-list-pending.out.txt` | Operator credential accepted |
| `admin-key approve` | passed | `04-admin-key-approve.out.txt` | Scope aliases normalized to `CLIENT_APP_MANAGE,CLIENT_APP_CONTROL_KEY_ISSUE` |
| `admin-key status` after approval | passed | `05-admin-key-status-approved.out.txt` | Approved status reachable |
| `admin-key claim --write-profile` | passed | `06-admin-key-claim.out.txt` | `NAVI_ADMIN_API_KEY` stored in upstream profile only |
| `client-app ensure` | passed | `07-client-app-ensure.out.txt` | Tenant profile received `NAVI_CLIENT_APP_ID` |
| `client-app list` | passed | `08-client-app-list.out.txt` | `clientAppCount=1` |
| `client-app issue-control-key` | passed | `09-client-app-issue-control-key.out.txt` | `NAVI_CONTROL_API_KEY` stored in tenant profile only |

Final profile key check:

- Upstream profile keys: `NAVI_ADMIN_KEY_REQUEST_CODE`, `NAVI_ADMIN_KEY_CLAIM_TOKEN`, `NAVI_BASE_URL`, `NAVI_UPSTREAM_SYSTEM_ID`, `NAVI_REQUESTED_TENANT_ID`, `NAVI_UPSTREAM_MULTI_TENANT`, `NAVI_ADMIN_API_KEY`
- Tenant profile keys: `NAVI_BASE_URL`, `NAVI_TENANT_ID`, `NAVI_CLIENT_APP_ID`, `NAVI_UPSTREAM_SYSTEM_ID`, `NAVI_UPSTREAM_REF`, `NAVI_CONTROL_API_KEY`

## Notes

- `/actuator/health` was not used as readiness evidence because this launcher profile returns 404 for that path. Readiness was confirmed by process, TCP listen on `8112`, and startup logs.
- Startup logs confirm MySQL connection through Hikari and Tomcat startup on `8112`; Hibernate auto DDL ran as part of JPA initialization without schema errors.
- Existing worker health DNS/timeout warnings appeared in logs and are unrelated to this smoke.

## Conclusion

Conclusion: `passed`.

The full live HTTP/CLI bootstrap path succeeded against a restarted local launcher: request -> operator approve -> claim `NAVI_ADMIN_API_KEY` -> ensure ClientApp -> issue `NAVI_CONTROL_API_KEY`.
