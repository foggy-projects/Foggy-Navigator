---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-025
status: READY_FOR_SIGNOFF
canonical: true
approved_by: Project Owner
approved_at: 2026-07-27
---

# Delivery Spec: Codex 自定义网关流提前终止诊断

## Goal and Scope

- 保持 Codex CLI 本地压缩路径，不恢复 `/responses/compact`。
- 自定义 Responses provider 请求失败时，在 Worker 本地日志记录不含凭据、query 或 fragment 的网关 endpoint，能够精确定位需修复的网关实例。
- 不改变任务认证、模型选择、压缩阈值或重试策略。
- 将没有 `response.completed` 的 provider stream 归类为待核验流错误，不再误报为 Worker 网络故障。

## Confirmed Diagnosis

- Worker `1.0.26` 已把自定义 base URL 声明为 `navigator_gateway`，Codex CLI 不再调用 `/responses/compact`。
- 失败会话 `019fa21a-1843-7692-a937-3a61dfe6dcb9` 的本地压缩请求约为 728 KB。网关每次均返回 HTTP 200，并在 1–5 秒内明确发送 SSE `error`，而非发生连接超时。
- 结构化且不记录正文的诊断确认上游错误为 `invalid_prompt`；消息长度和 SHA-256 精确对应 `Request blocked.`。Codex CLI 重试六次后仅上报 `stream closed before response.completed`，丢失了原始 provider 错误码。
- CPA `7.2.80` 升级至 `7.2.102`、开启 15 秒 keepalive，以及 nginx 的关闭 buffering、7200 秒 timeout 均不能改变结果，排除 Worker/CPA/nginx 网络超时。
- 临时 SSE 诊断探针已删除，8443 nginx 已恢复直接转发 `cli-proxy-api:8317`；CPA 保持 `7.2.102`。

## Acceptance Criteria

- [x] 自定义 gateway 日志包含 scheme、host、port 和规范化 path。
- [x] 日志不包含 userinfo、query、fragment 或 API key。
- [x] 默认 provider 与非法 URL 使用固定标记。
- [x] Worker 全量测试和 TypeScript typecheck 通过。
- [x] 发布新 Worker 并升级已授权的 `/home/sa/.codex-worker` 3053 实例。
- [x] 不完整 provider stream 使用 `CODEX_STREAM_UNCONFIRMED`，不再误报 `CODEX_WORKER_NETWORK_ERROR`。

## Implementation Result

- changed_paths:
  - `tools/codex-agent-worker/src/codex/sdk-wrapper.ts`
  - `tools/codex-agent-worker/src/diagnostics.ts`
  - `tools/codex-agent-worker/tests/sdk-wrapper.test.ts`
  - `tools/codex-agent-worker/tests/diagnostics.test.ts`
  - 本文件
- tests_and_results:
  - `npm test`: 250 passed，1 skipped。
  - `npm run typecheck`: passed。
  - `npm run build`: passed。
- residual_risk:
  - 当前旧会话的完整历史已被上游内容策略拒绝，不能通过网络参数恢复；需开启新会话，或由上游 provider 调整拦截策略。
  - Worker 只能看到 Codex CLI 降级后的 stream-close 文本，因此对外只能安全表达为 `CODEX_STREAM_UNCONFIRMED`，不能声称获知 `invalid_prompt`。
- readiness: READY_FOR_SIGNOFF
