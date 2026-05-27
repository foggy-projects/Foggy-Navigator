# Upstream CLI 1.0.6 Fresh Bootstrap Acceptance

## 文档作用

- doc_type: acceptance-record
- date: 2026-05-24
- status: passed
- scope: navigator-upstream-cli 1.0.6 fresh ClientApp bootstrap
- purpose: 记录 owner-aware upstream CLI `issue-runtime-key` 修复后的干净 profile 验收结果

## 验收环境

- Navi branch: `qd-win11/dev`
- Navi code package commit: `097ddbeb7c6d42a8f51521e96258ca8319a9447d`
- CLI version: `navigator-upstream-cli 1.0.6`
- CLI package SHA256: `216f309d42356e9e66ebce71c4a45dede4d57e000634b630c355568d286fbda8`
- `BUILD_INFO.gitCommit`: `097ddbeb7c6d42a8f51521e96258ca8319a9447d`
- upstream project mode: project-local `tools/navigator-upstream`

## 验收结论

通过。

1. package / commit 对齐：通过。
2. 覆盖安装后旧 SDK jar 清理：通过，`lib` 下仅剩 `navigator-open-sdk-1.0.6.jar`。
3. `issue-runtime-key` 命令可见：通过。
4. 旧 upstream admin key 兼容桥：通过，旧 key 可完成 ClientApp runtime credential 签发。
5. clean profile bootstrap：通过。
6. owner-aware readiness / resource smoke：通过。
7. 真实 `ask/messages`：通过，任务终态 `COMPLETED`。

## Clean Profile 证据

- profile: `.navigator/tenants/school-owner-smoke-107.env`
- profile safety: 位于 `.navigator/` 下且 gitignored。
- ClientApp: `capp_ee98641a-e91f-4538-97d2-97e24c7c1cc9`
- upstreamSystemId: `foggy-world-sim`
- upstreamUserId: `sim-upstream-user-local`
- default model: `9311f5b4-81a8-4619-9dfc-58712a8da12b`
- skillId: `school-sim.developer.codex.v1`

## 命令链结果

1. `client-app ensure`: passed
2. `client-app issue-runtime-key`: passed
3. `client-app issue-control-key`: passed
4. `runtime-token`: passed
5. `model grant`: passed
6. `skill sync`: passed
7. `ensure-grant`: passed
8. `owner-smoke`: `readiness OK` / `resources OK` / `ready`
9. `verify-agent-readiness`: OK
10. `ask/messages`: passed

## 真实 Ask 证据

- taskId: `lgt_4c5dc5f6fdd5446d`
- contextId: `bctx_20260524_b3_b3349fad68e44e5d9bd0397699a2442e`
- terminal status: `COMPLETED`
- assistant response: `Hello! How can I assist you today?`

## 遗留项

`SLF4J no-provider` warning 仍会在 CLI 输出中出现。该问题不影响功能、不泄露密钥，作为后续低优先级 CLI 日志噪声治理项单独跟踪。

## 安全说明

本记录不包含任何真实 token、secret、api key 或 profile 内容。
