# 06 Codex Auth Smoke 执行清单

## 文档作用

- doc_type: execution-checklist
- intended_for: ops | reviewer | release-owner
- purpose: 在 alias-only 结构改动已签收后，补齐真实 Codex 鉴权与端到端任务执行证据，确认 Worker 主机当前环境可实际跑通真实任务

## 背景

- `2026-04-25` 验证 alias-only 链路时，Worker 主机返回鉴权错误：`Your access token could not be refreshed because your refresh token was already used. Please log out and sign in again.`
- 该问题说明当前主机环境需要重新登录 Codex CLI 或改走有效 API Key，但不否定 alias 解析和模型透传链路
- 本清单的目标是把“结构正确”补全为“结构正确且真实任务可跑通”

## 执行前提

- Worker 主机可访问当前配置的 OpenAI / Codex 服务地址
- 已准备新的、未暴露的有效凭据
- 不要继续使用任何已在聊天、日志或临时文档中暴露过的测试 Key；如存在泄露，应先废弃并重新签发
- Worker 进程启动参数中已包含当前 alias 配置，且日志级别足以记录 `requested_model`、`resolved_model`、`effective_model`

## 最小执行矩阵

| 场景 | 认证模式 | 请求模型 | 目标 |
|---|---|---|---|
| Case A | Codex CLI / subscription | `codex-latest` | 验证默认 alias + 登录态可成功执行 |
| Case B | Codex CLI / subscription | `codex-deep` | 验证 alias + reasoning 的真实任务执行 |
| Case C | API Key | `codex-latest` | 验证 API 模式下 alias 映射与真实执行 |
| Case D | API Key | `gpt-5.5` | 验证真实模型名透传兼容性 |

如果当前环境只计划支持其中一种认证模式，另一种可标记为 `N/A`，但必须在验收记录中写明原因与适用范围。

## 执行步骤

1. 恢复凭据
   - 订阅模式：在 Worker 主机执行 `codex login`
   - API 模式：在安全配置中注入新的有效 API Key，不要复用已暴露凭据
2. 确认 Worker 配置
   - 检查 `CODEX_DEFAULT_MODEL` 是否为 `codex-latest`
   - 检查 `CODEX_MODEL_ALIASES` 是否为当前预期映射
   - 重启 Worker，确保新凭据与新环境变量生效
3. 执行 Case A
   - 发起不显式指定真实模型的任务，使用 `codex-latest`
   - 记录任务是否成功、耗时、返回摘要、日志中的 `alias_hit` 和 `effective_model`
4. 执行 Case B
   - 发起 `codex-deep` 任务
   - 记录日志中是否出现 `resolved_model=<real-model>:high`
5. 执行 Case C
   - 切换到 API Key 模式，发起 `codex-latest` 任务
   - 记录任务是否成功，以及结果事件中的 `model` 字段
6. 执行 Case D
   - 直接发起 `gpt-5.5` 任务
   - 记录 Worker 是否原样透传真实模型名并成功完成任务
7. 归档证据
   - 保存 Worker 日志摘录
   - 保存任务结果页面截图或接口返回摘要
   - 把结果回写到 `04` 主文档或新增补验记录

## 必须采集的证据

- 每个成功案例的任务 ID
- Worker 启动日志中的 `requested_model`、`alias_hit`、`resolved_model`、`effective_model`、`reasoning`
- 每个案例的最终任务状态
- 至少一条成功任务的 `result.model` 或等价返回字段
- 如果有失败，必须记录失败发生日期、认证模式、错误原文、是否与凭据或网络有关

## 验收标准

- 订阅模式至少 1 个 alias 任务成功，或明确记录该模式本次 `N/A`
- API 模式至少 1 个 alias 任务成功，或明确记录该模式本次 `N/A`
- alias 请求时，Worker 日志能证明 alias 已解析到真实模型
- 真实模型名请求时，Worker 日志能证明透传兼容仍成立
- 至少采集到 1 条真实成功任务的结果证据，证明不是只停留在日志启动阶段

## 失败判定与处置

- 若错误与 `codex login`、refresh token、API Key 无效有关，归类为鉴权问题，优先修复凭据再重试
- 若错误与 `base_url`、网络连通性、TLS、代理相关，归类为环境问题，优先修复网络或网关配置
- 若日志显示 alias 解析错误、`effective_model` 非预期或任务参数串错位，才归类为本次 alias-only 改动相关问题
- 若仅某一种认证模式失败，应在补验记录中明确标记“模式差异”，不要直接下结论为全链路失败

## 完成定义

满足验收标准后，在 [04-codex-worker-gpt55-upgrade-and-model-alias-plan.md](./04-codex-worker-gpt55-upgrade-and-model-alias-plan.md) 回写最新状态，并新增一个紧凑状态块：

```markdown
## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted
- signed_off_by: release-owner
- signed_off_at: YYYY-MM-DD
- acceptance_record: docs/version-tracker/1.0.4-SNAPSHOT/acceptance/04-codex-worker-alias-only-acceptance.md
- blocking_items: none
- follow_up_required: no
```
