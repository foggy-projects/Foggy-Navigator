---
doc_type: runbook
version: 1.4.3-SNAPSHOT
scope: local-trusted-development-only
related_workitem: ../workitems/GOV-001-dev-s1-s2-integration-mvp.md
---

# SIM / TMS 本地联调最小手册

## Boundary

- 只适用于本地或受信开发环境。不得把成功的 CLI profile、readiness 或 ask 写成 production approval。
- `NAVIGATOR_EXTERNAL_ENABLED=true` 只开启 Navigator `/api/v1/open/**` 路由；它不是 Provider ready、Worker Gateway external 或 production ready。
- 保持 `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false`。该开关是完整 Worker principal 的 strict validation，不是网络暴露开关。
- 真实 key、secret、token、账号和业务数据只放在 gitignored profile 或 secret store；本手册只使用变量名和占位符。
- 每一个上游 profile 默认只绑定一个 `navigatorInstanceId`。上游自行选择多实例时，独立保管各实例 profile；Navigator 不聚合或复用它们。

## Credential lanes

| Lane | Holder | Use now | Never use for |
|---|---|---|---|
| `NAVI_ADMIN_API_KEY` | SIM/TMS platform provisioning owner | ClientApp bootstrap、WorkerHost、upstream-shared resources | runtime token、owner-smoke、ask |
| `NAVI_CONTROL_API_KEY` | ClientApp/platform private control profile | ClientApp model/Agent/directory/grant/binding | cross-ClientApp repair、tenant runtime delivery |
| ClientApp key/secret or runtime token | SIM runtime or TMS tenant runtime | runtime-token、readiness、owner-smoke、ask | bootstrap、WorkerHost、model/grant/binding |

`ask` is not inherited from any control-plane lane. It remains the intersection of runtime grant, Agent configuration, task capability, Worker route and execution policy.

## Common preflight

1. Use a source-matched upstream CLI. The installed wrapper is known to have lagged source (`1.0.18` vs source `1.0.21`). `1.0.18` can run `ensure-tenant`, but it cannot evidence the current help/credential-boundary text. To use the current checkout without publishing or changing an upstream repository, first build `mvn package -pl navigator-open-sdk -am -DskipTests`, then invoke its JAR with the local dependency directory:

   ```bash
   NAVI_ROOT=/home/sa/workspace/Foggy-Navigator
   java -cp "$NAVI_ROOT/navigator-open-sdk/target/navigator-open-sdk-1.0.21.jar:$NAVI_ROOT/tools/navigator-upstream/lib/*" \
     com.foggy.navigator.sdk.cli.UpstreamCli upstream client-app --help
   ```

   Record only CLI version/provenance, never credentials. A released or installed wrapper may be used only after its version has been verified to contain the intended contract.
2. Confirm the target profile path is gitignored with `navi upstream config check`. `VALID`/`UNVERIFIED` is local advisory, not server authorization.
3. For an existing Physical Worker, validate its gitignored manifest first:

   ```bash
   navi upstream worker-host verify --file <private-worker-host.json>
   navi upstream worker-host update --file <private-worker-host.json> --worker-id <existing-physical-worker-id> --write-profile
   ```

   For a genuinely new WorkerHost only, use `apply` instead of `update`. A Codex route stays on the existing Physical Worker via `claudeCode.codexConfig`; never create another Worker, `BizWorkerIdentity`, direct `OPENAI_CODEX` identity or WorkerPool member to repair it.
4. Do not start the first business/UI ask until `activationReady=true`, `verify-agent-readiness` reports `OK`, and `owner-smoke` succeeds.

## S1: foggy-world-sim dedicated Navigator instance

1. Use a SIM-owned, gitignored upstream-admin profile that points to its dedicated Navigator instance. Current development integration uses the legacy upstream-admin lane only for SIM-owned resources.
2. Create or bind SIM's ClientApp, model/Agent/directory and WorkerHost through the applicable admin/control lanes. Keep the control profile private; create a separate runtime profile for runtime smoke.
3. Exchange a short runtime token and verify the configured runtime tuple:

   ```bash
   navi upstream runtime-token --write-profile
   navi upstream verify-agent-readiness \
     --upstream-user-id <sim-user-id> --agent-code <agent-code> \
     --model-config-id <model-config-id> --directory-id <directory-id>
   navi upstream owner-smoke \
     --upstream-user-id <sim-user-id> --agent-code <agent-code> \
     --model-config-id <model-config-id> --directory-id <directory-id>
   ```

4. Only then perform one narrow safe ask with the runtime profile. Current success proves this development lane only. It does not prove or implement the final S1 `INSTANCE_ROOT` authority to manage arbitrary non-SIM-owned resources.

## S2: tms-x3 platform and tenant ClientApp

1. TMS platform uses its gitignored upstream-admin profile. First inspect its owned ClientApps; an unfiltered list now includes authorized `nav_tms-x3_<tenant>` ClientApps:

   ```bash
   navi upstream client-app list
   navi upstream client-app list --target-tenant-id nav_tms-x3_<source-tenant-id>
   ```

2. Bootstrap a tenant only from the platform-private profile. `ensure-tenant` requires `--write-profile` because credentials are one-time. They are never printed in plaintext; the CLI may emit only a masked prefix/suffix and short hash diagnostic:

   ```bash
   navi upstream client-app ensure-tenant \
     --source-system tms-x3 --source-tenant-id <source-tenant-id> \
     --physical-worker-id <existing-physical-worker-id> \
     --directory-id <directory-id> --tenant-profile <private-bootstrap.env> --write-profile
   ```

3. Treat `<private-bootstrap.env>` as platform-private: it contains both `NAVI_CONTROL_API_KEY` and runtime key/secret. Copy only non-control runtime fields to a separate gitignored tenant runtime profile through the platform's secret-delivery process; remove or omit `NAVI_CONTROL_API_KEY` before the tenant receives anything. This CLI does not yet automate that split.
4. From the runtime-only tenant profile, run `runtime-token`, `verify-agent-readiness` and `owner-smoke` with the tenant's explicit user/Agent/model/directory identifiers. A `READY` provisioning response alone is not sufficient.
5. A TMS platform credential may manage only its own upstream system/namespace. Cross-upstream or cross-namespace ClientApp, model, directory, Worker or grant operations must be treated as a failed isolation test, not repaired with broader credentials.

## Evidence and escalation

- Record only IDs, statuses, CLI provenance and pass/fail results under the version evidence directory; never copy profiles or secrets.
- If readiness reports `WORKER_HOST_ROLE_ROUTING`, use the existing WorkerHost update path. Do not create a replacement Codex identity or pool membership.
- If a live smoke needs a real SIM/TMS secret, upstream business data, cross-owner operation, external Gateway, or production network exposure, stop and obtain a separate owner-approved scope. Those are not MVP actions.
