---
doc_type: delivery-spec
delivery_type: cross-module
version: 1.4.3-SNAPSHOT
ticket: GOV-003
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
approved_by: project-owner-user-confirmed
approved_at: 2026-07-22
execution_started_at: 2026-07-22
open_questions: []
---

# Delivery Spec: S1 system-admin ClientApp scope 管理

## Goal

- 为仅含 `NAVI_BASE_URL`、`NAVI_UPSTREAM_SYSTEM_ID`、`NAVI_ADMIN_API_KEY` 的 S1 system-admin profile，提供显式 `target clientAppId` 的 ClientApp-owned 管理路径。
- 支持同一 upstream system 内的 ClientApp-owned Agent、modelConfig grant/default、Directory/effective Directory、upstream-user grant 与 Agent model/workspace/Worker bindings；不要求或读取 `NAVI_CONTROL_API_KEY`。

## Scope

- in_scope:
  - 新增受 `X-Navi-Admin-Key` 认证、显式 target ClientApp 验证的 server-side scope facade/API，重用 ClientApp 资源服务但不放宽既有 control API。
  - 新增明确的 `navi upstream platform app-scope ... --client-app-id <id>` CLI 命令、脱敏 owner/scope/authorization diagnostics、help 语义与 SDK route 支持。
  - 修复 `platform tenant list` 的 ClientApp list 命名说明，及 `platform app list --help` 不应执行请求的问题。
  - 回归、打包、发布新的 backend build 与 upstream CLI；提交、推送并记录 8112 restart 判断。
- affected_modules:
  - `business-agent-module`
  - `addons/claude-worker-agent`
  - `navigator-open-sdk`
  - `tools/navigator-upstream-cli/dist`
  - `docs/version-tracker/1.4.3-SNAPSHOT`

## Non-Goals

- 不实现 typed `INSTANCE_ROOT` / `SAAS_PLATFORM` lifecycle，不创建或读取真实 credential/profile，不改 Worker update、真实 ask、Gateway/external/production 开关。
- 不允许跨 upstream system、tenant、ClientApp 的资源访问，不将 system-admin 与 control/runtime credential 混合，不修改现有 control-only endpoint 的鉴权语义。

## Confirmed Decisions

| Decision | Constraint |
|---|---|
| 使用独立 system-admin scoped ClientApp 路径 | `--client-app-id` 为必填 target；server 验证 active ClientApp、tenant authorization、upstreamSystemId 与 namespace，任何不匹配 fail closed |
| 复用已有 ClientApp resource service | 调用前由 server-side scope facade 解析 tenant、actor、target；不伪造或要求 `NAVI_CONTROL_API_KEY` |
| CLI 使用 `platform app-scope` | 只接受 platform legacy admin lane，显式输出 credential lane、target owner/scope 与授权诊断；app/runtime 现有语义不变 |
| 后端变更需要 8112 restart | 新 controller/service 仅在重新构建并部署 launcher 后生效；不重启 Worker |

## Acceptance Criteria

- [x] AC-1: system-admin 可以只用 `NAVI_ADMIN_API_KEY` 对明确同-upstream ClientApp 执行目标资源的支持操作；不再遇到错误 owner/control-lane 判定。
- [x] AC-2: 每个请求同时验证 target ClientApp 的 active/tenant/upstream/namespace 边界；跨 upstream、未授权 tenant、非 ClientApp-owned target 一律 fail closed。
- [x] AC-3: CLI 不混用 profile，`platform app-scope` help 与实际命令明确；输出不泄露 secrets 且含 owner/scope/authorization diagnostics。
- [x] AC-4: `platform tenant list` 明确为 ClientApp list；`platform app list --help` 只显示帮助、不访问服务端。
- [x] AC-5: 相关模块测试、CLI package/install smoke 通过；发布 version/buildId/gitCommit/8112 restart 结论和复测入口已记录。

## Validation and Risks

- required: controller/service/CLI contract regression；negative cross-upstream and mixed-lane tests；SDK reactor；backend affected-module tests；CLI package and installer smoke。
- residual: 不使用真实 SIM/TMS credential 或执行 Worker update/ask；SIM 仅重跑 Step 2，仍需以其 system-admin profile 做目标资源复测。

## Implementation Result

### Implementation summary

- 新增 `/api/v1/upstream-admin/client-apps/{clientAppId}/scope/**` system-admin facade；每次先验证 active ClientApp、tenant authorization、exact upstreamSystemId 与 namespace，再以 server-side resolved ClientApp control principal 调用既有资源服务。
- 新路径覆盖 ClientApp-owned Agent、model grant/default 与 owned modelConfig、upstream-user grant、model/workspace/Worker bindings、Directory；owner type/id 与 tenant 在底层资源服务继续复核，grant 不会绕过 ClientApp owner。
- CLI 新增明确 `navi upstream platform app-scope ... --client-app-id <id>`；缺 target 或混入 `--target-tenant-id` 直接失败，不读取 `NAVI_CONTROL_API_KEY`。scope inspect 输出脱敏 credential lane、owner/scope 和 authorization checks。
- 修正 `platform tenant list` 的 ClientApp list 说明；`platform app list --help` 在本地返回 help，不执行 HTTP 请求。

### Changed paths

- backend: `business-agent-module/**/UpstreamAdminClientAppScope*`、`BusinessAgentBundleService`、`ClientAppOwnedModelConfigService`、`ClientAppUserGrantService`、`UpstreamClientAppManagementService`、Claude working-directory admin facade。
- SDK/CLI: `navigator-open-sdk` APIs/DTO/`UpstreamCli`；CLI version `1.0.23` 与 package feature manifest。
- authorization: frozen route catalog 增加 system-admin facade 入口，并补齐两条既有 Codex task ingress registration；entry count `455`，SHA-256 `53f95c98521f31d1ea693259239a8dc257c17328647ff1dffee7dbfda29f1ee2`。

### Tests and results

- `mvn -pl business-agent-module -am -Dtest=ClientAppOwnedModelConfigServiceTest,UpstreamClientAppManagementServiceTest,UpstreamAdminClientAppScopeServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` — PASS, 22 tests.
- `mvn -pl navigator-open-sdk -Dtest=UpstreamCliTest test` — PASS, 131 tests.
- `mvn -pl launcher -am -Dtest=AuthorizationRouteManifestCoverageTest -Dsurefire.failIfNoSpecifiedTests=false test` — PASS, 3 tests.
- clean clone: `mvn -pl launcher -am -DskipTests package` — PASS; launcher metadata `git.commit.id.abbrev=aa4a944`, `git.dirty=false`.
- CLI archive package — PASS: `navigator-upstream-cli` `1.0.23`, source commit `aa4a944e7f2510fe1cfff623d92732df573fb9cf`, deterministic buildId `1.0.23+aa4a944e7f25`.

### Manual / release evidence

- 2026-07-22：确认 `8112` 原 listener 的 Java command 和 cwd 均指向当前 `/home/sa/workspace/Foggy-Navigator`，部署 clean launcher artifact 后重启。新 PID 监听 `127.0.0.1:8112`，`GET /actuator/health` 返回 `UP`（含 MySQL `UP`）。未重启任何 Worker。
- 发布成功：`navigator-upstream-cli` `1.0.23`，buildId `1.0.23+aa4a944e7f25`，gitCommit `aa4a944e7f2510fe1cfff623d92732df573fb9cf`；Windows SHA-256 `125ad58a4c8bdd1c0ff4cb25590be07875612a8fe0da98211b27578e1422beb0`，Linux SHA-256 `8d487493632c22cea4cb693afaac69fe6fa0e7b6d3bfa613f8b72ee3cd20e5ea`。
- 使用 standard `upload.sh` 上传 Windows ZIP、Linux TAR.GZ、`latest.json`、`install.ps1`、`install.sh`，五项均为 OBS HTTP `200`；内建远端 Linux installer smoke exit `0`。随后读取远端 `latest.json`，version/buildId/gitCommit/SHA-256 与本地 release manifest 一致。
- 凭据来源位置（仅位置与可用性，未读取、输出或提交任何值）：实际发布使用宿主配置 `/mnt/c/Users/oldse/.obsutilconfig`；`/home/sa/.obsutilconfig` 是不可用于发布的占位模板；发布目标配置位于 gitignored `tools/navigator-upstream-cli/.env`，只提供 release base/bucket 路由配置。此记录用于后续发布复用，不得将任何 credential value 写入仓库。

### Deviations

- The frozen ingress coverage check exposed two pre-existing but unregistered Codex task routes (`termination-inspection`, `termination-retry`). Their catalog entries were added without changing route behavior.

### Residual risks / next action

- 未以真实 SIM/TMS credential 执行资源变更、Worker update 或 ask；这符合本次边界。SIM 现在可只用 system-admin profile 重跑 Step 2，并显式传入 `--client-app-id`。
- readiness: READY_FOR_SIGNOFF
