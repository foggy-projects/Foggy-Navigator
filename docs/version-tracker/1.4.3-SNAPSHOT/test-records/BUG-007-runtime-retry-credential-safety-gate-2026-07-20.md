---
doc_type: test-record
version: 1.4.3-SNAPSHOT
related_workitem: ../workitems/BUG-007-task-token-function-scope-schema-contract.md
scope: local-trusted-development-only
status: BLOCKED
executed_at: 2026-07-20
---

# BUG-007 TMS runtime retry：凭据安全门禁

## Result

本次没有发起 TMS runtime 请求。用于 retry 的候选私有 profile 均为 `0744`，对组/其他用户可读；runtime-only secret 的最小文件权限为 owner-only `0600`，因此 fail-closed 拒绝发生在读取 profile 内容、token exchange、task 创建和 Worker dispatch 之前。

这不是 schema、授权、Worker route 或 Gateway 的失败，也不能用 admin/control profile、额外 Worker、BizWorkerIdentity 或 Pool 成员绕过。

## Completed local evidence

| Check | Result | Evidence / boundary |
| --- | --- | --- |
| Source-matched CLI | PASS | 本仓 `navigator-open-sdk-1.0.21.jar` 已就绪；已安装 `1.0.18` wrapper 存在 artifact drift，未用于本次证据。 |
| Actual MySQL-compatible launcher | PASS, prior to retry gate | 已确认本仓 8112 launcher 的 cwd/JAR、`ddl-auto=validate`、startup migration disabled、Open API route gate enabled、Gateway external disabled；`/actuator/health=UP`，external surface 为 `surfaceReady=true, productionReady=false`。验证进程随后按 PID 归属清理。 |
| Runtime-profile preflight | BLOCKED as designed | 本地 gitignored wrapper 先检查 regular file、非 symlink、当前 owner、单硬链接、`0600` 和 gitignore，再解析；实际在 `profile-mode-not-0600` 退出，未读取内容。 |
| Credential projection design | PASS | 后续 child 仅可接收 `NAVI_BASE_URL`、`NAVI_TENANT_ID`、`NAVI_CLIENT_APP_ID`、`NAVI_CLIENT_APP_KEY`、`NAVI_CLIENT_APP_SECRET`；任何 control/admin/principal/upstream-user/task/worker/runtime-token/LLM 或其他字段一律拒绝。它使用 `env -i`、显式临时 profile、source-matched JAR、无 `--write-profile`，且过滤全部 CLI 原始输出。 |

`NAVIGATOR_EXTERNAL_ENABLED=true` 在上述本机验证中只表示 `/api/v1/open/**` 路由门禁开启；它不表示 Provider ready、Worker Gateway external 或 production ready。`NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED` 保持关闭。

## Current retry preflight

- 2026-07-20 本次重试再次运行本仓的 gitignored runner，仅使用占位 explicit tuple 与 `--preflight-only`。它在 profile 内容读取、runtime-token、网络请求、task 创建和 Worker dispatch 前以 `profile-preflight=FAIL reason=profile-mode-not-0600` 退出（exit `2`）。
- 因此当前没有合格的 runtime-only 输入可用于启动 live retry；该结果是凭据载体安全门，不能归因为 schema、授权、Gateway 或 Worker route。

## Runtime and isolation outcomes

| Step | Result | Reason |
| --- | --- | --- |
| `runtime-token` | NOT_RUN | 不能读取 mode 不合规的 secret profile。 |
| `verify-agent-readiness` | NOT_RUN | 依赖合法 runtime-only lane；不使用历史 tuple 或宽权限 profile 试探。 |
| `owner-smoke` | NOT_RUN | 同上。 |
| static safe `ask` | NOT_RUN | 前置未通过；没有 taskId、assistant body、业务访问、Worker dispatch 或 Gateway 调用。 |
| same-tenant cross-ClientApp deny | NOT_RUN | 未发现可确认且合规的现有第二 ClientApp fixture；不得用 `ensure-*` 创建。未来应在 readiness-only preflight 中得到 `ROOT_AGENT_BINDING / ROOT_AGENT_CLIENT_APP_MISMATCH`，exit `2`，且无 task/dispatch。 |
| cross-tenant deny | NOT_RUN | 未发现可确认且合规的现有跨 tenant fixture；不得创建。未来应在 readiness-only preflight 中得到 `ROOT_AGENT_BINDING / ROOT_AGENT_NOT_FOUND`，exit `2`，且无 task/dispatch。 |

注意：若 root 的 owner 为 `UPSTREAM_SYSTEM`，同 upstream ClientApp 的可见性不是 `CLIENT_APP` mismatch，不能把它误报为隔离失败。

## Required owner action before retry

1. 在 TMS owner 的授权边界内，准备一个独立 runtime-only profile：regular file、非 symlink、link count `1`、current-owner `0600`、gitignored，且只包含上述五个运行时字段；不得只对可能含 `NAVI_CONTROL_API_KEY` 的 bootstrap profile 做权限放宽或作为 tenant runtime profile 交付。
2. 单独提供当前、已确认的 explicit tuple（agent code、upstream user、model config、directory）。tuple 不写入该 runtime secret profile；runner 会作为显式 CLI 参数传入。
3. 仅复用已存在的 negative fixture；正向 `runtime-token → readiness → owner-smoke → static no-tool ask → diagnostics` 成功后，才执行两个 readiness-only 拒绝验证。

在 owner action 完成前，BUG-007 维持 `ULTRA_EXECUTING`；不得宣称 TMS internal integration passed，也不得向上游交接。

## Non-actions confirmed

- 未读取/回显/写入 profile、key、secret、token、账号、业务数据或 token table 行。
- 未修改 TMS sibling workspace、ClientApp、tenant、Agent、Directory、Worker、BizWorkerIdentity、WorkerPool 或路由。
- 未运行会创建资源的 provisioning self-test，未启用 Gateway external、strict mode、Worker external 或 production。
