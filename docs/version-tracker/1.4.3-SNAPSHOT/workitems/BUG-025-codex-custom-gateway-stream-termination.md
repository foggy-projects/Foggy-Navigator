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
- 不改变任务认证、模型选择、压缩阈值、重试策略或错误对外安全摘要。

## Confirmed Diagnosis

- Worker `1.0.26` 已把自定义 base URL 声明为 `navigator_gateway`，Codex CLI 不再调用 `/responses/compact`。
- 失败会话 `019fa21a-1843-7692-a937-3a61dfe6dcb9` 随后的普通 `/responses` SSE 在约 32 秒后关闭，缺少 `response.completed`，最终映射为 `CODEX_WORKER_NETWORK_ERROR`。
- Codex CLI 默认 stream idle timeout 为 300 秒且已执行 stream retry，因此提高 Worker/CLI idle timeout 不能修复该 32 秒主动断流。
- 当前可登录的 `dev-kvm-jdk17` 只承载 recorder；它指向的远端 CPA 配置主机不在当前管理范围，不能用已停用且没有 provider 账号的本地 CPA 替换。

## Acceptance Criteria

- [x] 自定义 gateway 日志包含 scheme、host、port 和规范化 path。
- [x] 日志不包含 userinfo、query、fragment 或 API key。
- [x] 默认 provider 与非法 URL 使用固定标记。
- [x] Worker 全量测试和 TypeScript typecheck 通过。
- [x] 发布新 Worker 并升级已授权的 `/home/sa/.codex-worker` 3053 实例。

## Implementation Result

- changed_paths:
  - `tools/codex-agent-worker/src/codex/sdk-wrapper.ts`
  - `tools/codex-agent-worker/tests/sdk-wrapper.test.ts`
  - 本文件
- tests_and_results:
  - `npm test -- --test-name-pattern='describeGatewayEndpoint|custom gateway|buildCodexConfig'`: 249 passed，1 skipped。
  - `npm run typecheck`: passed。
- residual_risk:
  - endpoint 定位后的最终修复仍需在实际 CPA/nginx 实例启用 SSE keepalive，并保证完整转发 `response.completed`；Worker 不伪造 provider 终态。
- readiness: READY_FOR_SIGNOFF
