---
doc_type: delivery-spec
delivery_type: cross-module
version: 1.4.3-SNAPSHOT
ticket: GOV-003
status: ULTRA_EXECUTING
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

- [ ] AC-1: system-admin 可以只用 `NAVI_ADMIN_API_KEY` 对明确同-upstream ClientApp 执行目标资源的支持操作；不再遇到错误 owner/control-lane 判定。
- [ ] AC-2: 每个请求同时验证 target ClientApp 的 active/tenant/upstream/namespace 边界；跨 upstream、未授权 tenant、非 ClientApp-owned target 一律 fail closed。
- [ ] AC-3: CLI 不混用 profile，`platform app-scope` help 与实际命令明确；输出不泄露 secrets 且含 owner/scope/authorization diagnostics。
- [ ] AC-4: `platform tenant list` 明确为 ClientApp list；`platform app list --help` 只显示帮助、不访问服务端。
- [ ] AC-5: 相关模块测试、CLI package/install smoke 通过；发布 version/buildId/gitCommit/8112 restart 结论和复测入口已记录。

## Validation and Risks

- required: controller/service/CLI contract regression；negative cross-upstream and mixed-lane tests；SDK reactor；backend affected-module tests；CLI package and installer smoke。
- residual: 不使用真实 SIM/TMS credential 或执行 Worker update/ask；真实 8112 的重启仅在发布 artifact 部署后由该实例 owner 执行。

## Implementation Result

> To be completed by the implementation and release session.

- implementation_summary:
- changed_paths:
- tests_and_results:
- manual_or_experience_evidence:
- deviations: none
- residual_risks:
- readiness: READY_FOR_SIGNOFF | NEEDS_REPLAN | BLOCKED
