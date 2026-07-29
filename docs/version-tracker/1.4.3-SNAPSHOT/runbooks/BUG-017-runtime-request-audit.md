# Runtime request audit（无 taskId）

本能力用于诊断 `runtime-token` / `safe-ask` 请求链，即使客户端没有拿到 taskId、响应丢失或 token 交换失败，也可以用预先打印的非敏感 UUID 查询服务端已观察到的阶段。

## safe-ask 与 ask

| 项目 | `runtime safe-ask` | `runtime ask` |
|---|---|---|
| 目的 | 验证 runtime credential、Agent/model 授权和零执行面安全合同 | 创建真实业务任务并执行用户请求 |
| task | 只创建 terminal synthetic evidence ID，不可按 Worker task 轮询 | 创建真实 task/session 并支持后续轮询与消息读取 |
| tool/function scope | 强制 `allowedTools=[]`、`allowedFunctions=[]` | 使用请求范围与 grant 的有效交集 |
| token | 创建空 function scope 的 task token并立即 revoke | task token 随任务生命周期管理 |
| Worker/model/BusinessFunction | 不 dispatch | 可能 dispatch |
| retry/fallback | 不自动重试，不回退普通 ask | 按普通 ask 合同处理 |

safe-ask 已有实现是独立的小型合成终态链路，不需要 Worker/model 调用，因此本次保留它；它不能替代真实 ask 的业务验证，普通 ask 也不能替代 safe-ask 的严格零执行面证明。

## Correlation

CLI 在首次网络请求前输出：

```text
clientRequestId=<uuid>
```

同一值通过 `X-Navigator-Client-Request-Id` 发送到 runtime-token 和随后的 safe-smoke。该 UUID 只用于观测关联，不是幂等键、重试授权或 bearer capability。safe-ask 失败时 CLI 仍输出该 ID，并只返回稳定的 `sanitizedErrorCode`，不打印原始 HTTP body。

## 查询命令

精确查询不要求 taskId、contextId、providerTaskId 或 runtime access token：

```bash
navi upstream runtime audit \
  --request-id "<client-request-id>"
```

按短时间窗口查询：

```bash
navi upstream runtime audit \
  --since "2026-07-23T14:29:30+08:00" \
  --until "2026-07-23T14:31:00+08:00" \
  --operation safe-ask \
  --agent-code "world-sim-order-clerk-v2-dev-20260716-a" \
  --upstream-user-id "sim-upstream-user-local" \
  --limit 20
```

`--request-id` 与时间窗口二选一。时间窗口必须同时提供 `--since` / `--until`，服务端硬上限为 15 分钟；默认 limit 为 20，硬上限为 100。`--operation` 只接受 `runtime-token` 或 `safe-ask`。增加 `--json` 可返回结构化 JSON；默认输出稳定的 `key=value`。

注意：审计只记录功能部署后的请求，不能追溯生成 2026-07-23 14:30:09 当时尚未存在的服务端记录。上述窗口命令可用于部署后的复现请求；对旧请求返回空结果或 `AUDIT_RECORD_EXPIRED_OR_NOT_FOUND` 不表示其从未发生。

## Endpoint

SDK/CLI 调用：

```text
GET /api/v1/open/runtime-audits
```

查询参数：`requestId`，或 `since` + `until`；可选 `operation`、`agentCode`、`upstreamUserId`、`limit`。认证只接受 ClientApp long-term runtime key/secret header。服务端从该 credential 反推 tenant、upstream system、ClientApp，接口不接受 owner scope override。

以下材料会被拒绝：admin/control/platform/typed-management credential、runtime access token、task token、Worker credential、Authorization/API key、tenant/clientApp/upstream-system target 参数，以及 taskId/contextId/providerTaskId。

## 阶段与结果判读

服务端记录以下脱敏阶段：

```text
CLIENT_REQUEST_RECEIVED
RUNTIME_TOKEN_REQUEST_RECEIVED
RUNTIME_TOKEN_ISSUED
RUNTIME_TOKEN_REJECTED
SAFE_SMOKE_REQUEST_RECEIVED
SYNTHETIC_EVIDENCE_CREATED
TASK_TOKEN_REVOKED
REQUEST_COMPLETED
REQUEST_FAILED
```

常见判读：

| 结果 | 关键字段/阶段 |
|---|---|
| 请求从未到达，或记录已过期 | 精确查询返回 `AUDIT_RECORD_EXPIRED_OR_NOT_FOUND`；时间查询为空 |
| runtime-token 到达但失败 | `runtimeTokenRequestReceived=true`、`runtimeTokenIssued=false`、`RUNTIME_TOKEN_REJECTED` |
| token 已签发，safe-smoke 未到达 | `runtimeTokenIssued=true`、`safeSmokeRequestReceived=false`、`status=WAITING_FOR_SAFE_SMOKE` |
| safe-smoke 到达但 evidence 未创建 | `safeSmokeRequestReceived=true`、`syntheticEvidenceCreated=false`、终态错误码或处理中状态 |
| evidence 已创建但客户端丢失响应 | `SYNTHETIC_EVIDENCE_CREATED`、`TASK_TOKEN_REVOKED`、`REQUEST_COMPLETED` 均存在；本地 CLI exit 仍可能为 1 |
| 完整成功 | `terminal=true`、`taskTokenStatus=REVOKED`、`runtimeDispatched=false`、tool/function count 均为 0 |

布尔字段在 JSON 中保持布尔或 `null`；默认文本输出把 `null` 显示为 `UNKNOWN`。未知不会折叠成 `false`。不存在的 taskId 显示为 `null`。

## 安全、retention 与容量

审计表只保存 owner scope、非敏感 correlation、时间、稳定阶段、布尔/计数、sanitized error code 和安全摘要。它不保存或输出 key/secret、access/runtime/task token、Authorization/API-key/header 集合、prompt/message、环境、workspace/ActorHome、Worker Gateway/provider/model payload 或响应、业务文件/数据、原始异常 body/stack。

termination request receipt 默认 retention 为 7 天，可通过
`navigator.runtime-audit.termination-receipt-retention` 配置；其他 runtime audit
继续使用原来的 24 小时默认值，可通过 `navigator.runtime-audit.retention` 配置。
查询始终排除 expired 行；普通写入路径不执行物理清理，服务端默认按 Spring 六段
cron 在每天 `02:00` 执行有界批量清理。索引覆盖精确 request ID、tenant +
upstream system + ClientApp + received time，以及 expiry。

`navigator.runtime-audit.termination-receipt-enabled=false` 只关闭
`task-terminate` 的 request receipt，不关闭 ask、safe-smoke 等其他 runtime audit。
关闭后单次 termination 仍可执行，但 Navigator 不再提供 receipt-backed 同 request-ID
幂等和权威 request reconciliation；typed response 会显式返回 receipt/reconciliation
不可用，响应丢失后禁止自动重发。

可配置项：

```properties
navigator.runtime-audit.termination-receipt-enabled=true
navigator.runtime-audit.termination-receipt-retention=7d
navigator.runtime-audit.retention=24h
navigator.runtime-audit.max-query-window=15m
navigator.runtime-audit.default-limit=20
navigator.runtime-audit.max-limit=100
navigator.runtime-audit.cleanup-batch-size=200
navigator.runtime-audit.cleanup-max-batches=100
navigator.runtime-audit.cleanup-cron=0 0 2 * * *
```

cron 使用 Navigator JVM 时区；容器默认通过 `TZ=Asia/Shanghai` 运行，其他部署应按
自身时区调整 cron。查询配置只能收紧查询范围；代码硬上限仍是 15 分钟和 100 条。

## 安装本地 clean package

Linux package 生成后，在项目根目录执行：

```bash
tar -xzf tools/navigator-upstream-cli/dist/output/navigator-upstream-cli-1.0.25-linux.tar.gz
bash navigator-upstream/install.sh --project-root "$PWD"
./tools/navigator-upstream/navi version
./tools/navigator-upstream/navi upstream runtime --help
```

审计查询自身是严格只读的：不会签发 runtime/task token，不会创建 task、context 或 session，不会调用 Worker/model/BusinessFunction，也不会改变任何 grant、binding 或运行资源。
