# 05 Codex Alias-Only 发布说明

## 文档作用

- doc_type: release-note
- intended_for: release-owner | reviewer | ops
- purpose: 对 1.0.4 中 Codex alias-only 重构给出发布口径、影响范围、运维注意事项与后续补验要求

## 发布结论

- release_scope: `Codex alias-only structural refactor`
- release_status: `releasable_with_follow_up`
- related_acceptance: [acceptance/04-codex-worker-alias-only-acceptance.md](./acceptance/04-codex-worker-alias-only-acceptance.md)

本次发布可对外说明为：Codex 模型选择已完成 alias-only 收口，前端、移动端、Java 后端与真实模型版本正式解耦，后续模型升级只需要调整 Worker 侧 alias 映射并重启 Worker。

## 本次包含内容

- Worker 新增 `CODEX_MODEL_ALIASES` 配置与 `resolveModelAlias` 解析逻辑
- Worker 默认模型从真实模型串切换为 alias `codex-latest`
- 前端与移动端 Codex 选项收敛为 4 个 alias：`codex-latest`、`codex-fast`、`codex-deep`、`codex-mini`
- 历史 `availableModels` 为真实模型名的配置自动兼容，不需要做数据库迁移
- Worker 日志新增 `requested_model`、`alias_hit`、`resolved_model`、`effective_model`、`reasoning`，便于运维定位

## 用户与运维影响

- 用户侧：
  - 看到的是稳定 alias，而不是 `gpt-5.5`、`gpt-5.6` 这类易变真实模型名
  - 老配置不需要手工修复，系统会自动兼容
- 运维侧：
  - 未来模型升级只需要更新 `CODEX_MODEL_ALIASES`
  - 前端、Java 后端、数据库不需要跟着改版本号
  - 如果要验证真实调用链，需要先恢复 Worker 主机 Codex 鉴权状态

## 已确认结果

- Worker `npm test`: `53/53 PASS`
- Worker `npm run typecheck`: clean
- Frontend `vitest llmModelOptions`: `8/8 PASS`
- `bash scripts/build-frontend.sh`: clean
- Playwright 目视验收：设置页与任务页均仅展示 4 个 alias
- Worker 日志验收：alias 解析 4 个分支全部给出有效证据

## 已知遗留项

- `2026-04-25` Worker 主机 Codex CLI 订阅 token 失效，真实 Codex live task 无法完成最新一轮 smoke
- 该问题不影响 alias-only 代码结论，但影响“当前环境可立即跑通真实 Codex 任务”的运维状态
- 后续补验请执行 [06-codex-auth-smoke-checklist.md](./06-codex-auth-smoke-checklist.md)

## 升级口径

当 OpenAI 后续发布新模型时，仅需在 Worker 主机调整 `CODEX_MODEL_ALIASES`，例如把 `codex-latest` 从当前映射切到新模型，再重启 Worker。该流程不需要同步修改前端选项、Java 透传逻辑或数据库中存量配置。
