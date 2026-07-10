# OPT-005 Codex Sol Max / Ultra 支持

## 文档作用

- doc_type: requirement-and-implementation-record
- intended_for: implementation-agent | reviewer | release-owner
- owner: `tools/codex-agent-worker` | `addons/codex-worker-agent` | `packages/navigator-frontend`
- target_release: `codex-worker 1.0.11`
- status: implemented-awaiting-release

## 目标

在 Navigator 的统一 Codex 模型选择链路中增加 GPT-5.6-Sol `max` 和 `ultra` 档位，确保前端选择、稳定 alias、Worker 校验、SDK 配置透传和 SSE 最终结果一致。

## 协议

| UI 名称 | 稳定 alias | Worker 实际模型 |
|---|---|---|
| Codex Max | `codex-max` | `gpt-5.6-sol:max` |
| Codex Ultra | `codex-ultra` | `gpt-5.6-sol:ultra` |

- `xhigh`、`max`、`ultra` 是独立档位，不做降级映射。
- `max` 表示最大推理深度。
- `ultra` 表示最大推理深度并允许 Codex 自动任务委派。
- 已验证 `@openai/codex-sdk` / Codex CLI `0.144.1` 可运行两档，最低 SDK 版本保持 `0.144.1`。
- 显式模型后缀和 alias 解析出的档位优先于请求里的通用 `codexConfig.model_reasoning_effort`。

## 范围

1. Worker 增加两个默认 alias，并放行、解析、透传 `max` / `ultra`。
2. 前端统一模型列表增加 Max / Ultra，任务下拉、设置页、Agent 默认模型和会话转发复用同一来源。
3. `/model` 命令改为消费当前 Provider 的候选模型，移除硬编码 Claude 模型列表；多 Pane 续接输入不暴露全局模型切换。
4. Java 任务入口在创建 Session 或持久化任务前校验模型配置访问权和 Max / Ultra 显式授权，阻止 REST、续接和 A2A 绕过前端白名单。
5. Worker 对 SDK 运行时产生的 `collab_tool_call` 写结构化诊断日志。
6. 自动化测试和真实 SSE smoke 覆盖 Max / Ultra。

## 非目标

- 本项不实现 Ultra 子 Agent 拓扑、独立子会话或逐 Agent 进度 UI。
- 不读取 Codex 本地 session JSONL 拼装未公开的子 Agent 事件。
- 不改变 `codex-latest`、`codex-fast`、`codex-deep`、`codex-xhigh`、`codex-mini` 的现有映射。
- 转发弹窗继续使用独立模型下拉；已有会话转发不暴露 `/model`，避免选择不生效。
- 多 Pane 续接仍沿用现有全局模型上下文，但不显示 `/model`；pane 级异构 Provider 模型状态不在本项内重构。
- 1~6 阶段不打包或发布 `1.0.11`。

## 验收标准

- 前端可选择 `codex-max` 和 `codex-ultra`，已有显式 `availableModels` 白名单仍需主动授权新 alias。
- 平台模型配置存在时，后端必须校验 Worker 对该配置的访问权；受限白名单不得通过直接请求或模型后缀绕过。
- Worker 将两者分别解析为 `gpt-5.6-sol:max` 和 `gpt-5.6-sol:ultra`。
- 两档均通过真实 Worker SSE 调用；Ultra 自动委派时最终文本、完成事件和取消链路不受影响。
- `collab_tool_call` 不再完全静默，日志不得暴露 prompt、线程 ID 或凭据。
- Worker 单测、typecheck、build 和前端测试、type-check、build 全部通过。
- Playwright 验证模型配置界面和模型选项在桌面、移动视口可访问且文本不溢出。

## Progress Tracking

### Development

- [x] 需求、协议、非目标和验收口径固化
- [x] Worker alias、校验和 SDK config 透传
- [x] Worker 自动化测试
- [x] 前端模型选项和动态 `/model`
- [x] Ultra 基础诊断可观测

### Testing

| 用例 | 状态 |
|---|---|
| Worker unit / typecheck / build | passed: 112 / 112，typecheck、build 通过 |
| Frontend Vitest / type-check / build | passed: 117 / 117，type-check、统一构建通过 |
| Java Codex model grant regression | passed: `CodexTaskServiceTest` 35 / 35 |
| Max / Ultra Worker SSE smoke | passed: Max / Ultra 均返回预期最终文本 |
| Ultra delegation / resume / abort smoke | passed: 自动委派诊断、同 session 续接、abort 均通过 |

### Experience

| 检查项 | 状态 |
|---|---|
| 设置页可达性与模型授权交互 | passed |
| 任务模型选择和 `/model` 一致性 | passed: helper + 组件级 provider 更新测试；续接 Pane 隐藏全局模型切换 |
| 无权限、空白名单和显式白名单可见性 | passed: 模型选项单测 |
| 桌面与移动视口文本、弹层和选项布局 | passed: 1440x900、390x844 |
| Playwright evidence | `temp/test-artifacts/opt-005/ui-desktop.png`、`ui-mobile.png` |

## Execution Check-in

- date: 2026-07-10
- implementation: completed for steps 1~6
- readiness: ready for `codex-worker 1.0.11` release work
- release_state: not packaged, not uploaded, not published
- current_package_version: `1.0.10`

### 代码落点

- Worker：默认 alias、共享 reasoning 规范化、请求校验、SDK config 优先级和 `collab_tool_call` 脱敏日志。
- Java 后端：Worker 模型配置访问校验、Max / Ultra 显式 grant 校验和持久化前 fail-closed。
- 前端：统一模型选项、设置页授权、任务模型下拉、Provider 动态 `/model`、移动端设置弹窗布局。
- 文档：接入示例改为 alias-first，补充 Max / Ultra 和 Ultra 自动委派说明。

### 验证证据

- `CODEX_ALLOWED_CWDS=<系统临时目录> npm test`：Worker 112 / 112 passed。
- `npm run typecheck`、`npm run build`：Worker passed。
- `pnpm test`：Navigator Frontend 117 / 117 passed。
- `pnpm type-check`：Navigator Frontend passed。
- `mvn -pl addons/codex-worker-agent -am "-Dtest=CodexTaskServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`：35 / 35 passed。
- `bash scripts/build-frontend.sh`：`foggy-chat-core`、`foggy-chat`、Navigator Frontend 全部构建成功。
- Worker SSE：Max 返回 `WORKER_MAX_OK`；Ultra 返回 `WORKER_ULTRA_OK 42 5`；续接保持同一 session；abort 状态为 `aborted`。
- Ultra 日志：脚本断言 `resolved_model=gpt-5.6-sol:ultra`、`reasoning=ultra` 和脱敏 `collab_tool_*` 诊断存在。
- Playwright：桌面和移动视口均可勾选 Max / Ultra，模型说明可见，弹窗位于视口内，页面与后端选项无横向溢出。

### 剩余边界

- 第 7 步发版尚未执行，版本号、打包产物和 OBS 不在本次改动内。
- Ultra 仍只提供父任务结果和粗粒度协作日志，不提供子 Agent 拓扑 UI。
- 既有多 Pane 全局模型上下文未改为 pane 级 Provider 状态；本次通过隐藏续接 Pane 的 `/model` 避免新增错配入口。

## 风险

- SDK `0.144.1` 的 TypeScript `ModelReasoningEffort` 类型尚未声明 `max` / `ultra`；实现使用 SDK 已公开的通用 `config` 通道，避免修改第三方类型。
- 旧版真实模型白名单仅迁移普通 alias；`gpt-5.6-sol:max` / `gpt-5.6-sol:ultra` 与稳定 alias 双向等价，其他未来 `:max` / `:ultra` 模型只接受精确授权。
- Ultra 内部子 Agent 不计入 Navigator 顶层任务并发数或 `maxTurns`，可能增加账号使用量。
- SDK 当前仅提供粗粒度 `collab_tool_call`，本期只做脱敏诊断日志，不承诺完整子 Agent 可视化。
