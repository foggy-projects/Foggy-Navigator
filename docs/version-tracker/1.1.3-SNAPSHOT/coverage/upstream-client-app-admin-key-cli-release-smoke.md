---
doc_type: release-smoke
version: 1.1.3-SNAPSHOT
target: navigator-upstream-cli-admin-key-bootstrap
status: passed
smoke_date: 2026-05-18
environment: local launcher + OBS released CLI
launcher_base_url: http://localhost:8112
cli_version: 1.0.4
---

# Navigator Upstream CLI Release Smoke

## Scope

本记录验证已发布到 OBS 的 Navigator Upstream CLI 是否可交付给上游系统，用于 issue #121 的多租户 ClientApp bootstrap 链路。

验证目标：

- 上游项目从 OBS `install.ps1` 安装最新 CLI。
- CLI `version` 显示为已发布版本。
- `upstream admin-key` 与 `upstream client-app` 命令可用。
- 真实 HTTP 链路完成 `request -> approve -> claim -> ensure ClientApp -> issue control key`。
- 上游系统级 profile 写入 `NAVI_ADMIN_API_KEY`，租户级 profile 写入 `NAVI_CONTROL_API_KEY`，不在日志或文档中暴露明文 key。

## Release Under Test

- OBS latest version: `1.0.4`
- Release date: `2026-05-18`
- Build time UTC: `2026-05-18T05:11:40Z`
- Build id: `1.0.4+354ba23aed93.dirty`
- Git commit: `354ba23aed93bb894c332a5268850ec0555f00c1`
- gitDirty: `true`
- Windows archive: `1.0.4/navigator-upstream-cli-1.0.4-windows.zip`
- SHA256: `3b7737d28a1ab9654fe07e76f4c6821f417a21432a8fb786502298aab7286113`
- Release root: `https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/navigator-upstream-cli`

`latest.json` features 已包含：

- `admin-key-bootstrap`
- `client-app-bootstrap`
- `function-import`
- `function-grant`
- `function-grant-status`
- `function-visible`

## Smoke Environment

- Launcher: existing local launcher on `http://localhost:8112`
- Launcher PID: `61032`
- Install command:

```powershell
irm https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/navigator-upstream-cli/install.ps1 | iex
```

- Final smoke run path: `temp\navi-obs-release-smoke\run-20260518131735`
- Operator credential source: local ignored file `temp\navi-admin-smoke\operator.key`
- Secret handling: command stdout/stderr captured to temp files; summary records only non-secret IDs and profile key names.

## Executed Steps

All steps passed:

```text
00-version
01-admin-help
02-client-app-help
03-request
04-status-pending
05-list-pending
06-approve
07-status-approved
08-claim
09-ensure
10-list-client-app
11-issue-control-key
```

Smoke identifiers:

- upstreamSystemId: `tms-obs-release-smoke-20260518131735`
- tenantId: `tenant-obs-release-smoke-20260518131735`
- upstreamRef: `tenant-a-20260518131735`
- clientAppId: `capp_fc341e84-e4e0-49fb-bb7e-4ed31cdbb808`

Validated upstream profile keys:

```text
NAVI_ADMIN_API_KEY
NAVI_ADMIN_KEY_CLAIM_TOKEN
NAVI_ADMIN_KEY_REQUEST_CODE
NAVI_BASE_URL
NAVI_REQUESTED_TENANT_ID
NAVI_UPSTREAM_MULTI_TENANT
NAVI_UPSTREAM_SYSTEM_ID
```

Validated tenant profile keys:

```text
NAVI_BASE_URL
NAVI_CLIENT_APP_ID
NAVI_CONTROL_API_KEY
NAVI_TENANT_ID
NAVI_UPSTREAM_REF
NAVI_UPSTREAM_SYSTEM_ID
```

## Notes

- `NAVI_OPERATOR_API_KEY` 只在 Navigator admin/ops 验证环境中作为进程环境变量临时注入，用于 `admin-key list` 和 `admin-key approve`；未写入上游 profile。
- `NAVI_ADMIN_API_KEY` 只写入上游系统级 `.navigator/upstream.env`。
- `NAVI_CONTROL_API_KEY` 只写入租户级 `.navigator/tenants/<upstreamRef>.env`。
- 13:15 的第一次验证脚本出现假阴性，原因是 PowerShell 函数参数命名为 `$Args` 与自动变量冲突，导致子进程未收到命令参数；13:17 修正脚本后重跑通过。

## Result

Result: `passed`.

OBS latest CLI `1.0.4` 可以支持上游多租户系统完成 `NAVI_ADMIN_API_KEY` 申请、审批后 claim、按租户创建/复用 ClientApp，并签发各自的 `NAVI_CONTROL_API_KEY`。
