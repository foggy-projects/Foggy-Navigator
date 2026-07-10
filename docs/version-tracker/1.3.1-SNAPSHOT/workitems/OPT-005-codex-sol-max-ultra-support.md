# OPT-005 Codex Sol Max / Ultra 支持

## 文档作用

- doc_type: requirement-and-implementation-record
- intended_for: implementation-agent | reviewer | release-owner
- owner: `tools/codex-agent-worker` | `addons/codex-worker-agent` | `packages/navigator-frontend`
- target_release: `codex-worker 1.0.11`（稳定 SDK Worker 已发布；旧混合 app-server lane 不在发布物中）
- status: retained-scope-accepted-runtime-architecture-superseded
- superseded_by: `docs/version-tracker/1.4.0-SNAPSHOT/workitems/OPT-001-independent-codex-app-server-worker-requirement.md`

## 2026-07-10 架构迁移声明

- 本记录继续承接并保留 Max / Ultra alias、模型授权、Java latest-state、Session SSE / snapshot / delete 语义和 PC native-subtask projection 的历史验收。
- 本记录中的旧 Codex Worker 混合 SDK / app-server lane、`CODEX_APP_SERVER_ULTRA_ENABLED`、bundled CLI 精确门控、`turn/start` 前 SDK fallback 以及同进程双引擎实现，已被 `1.4.0-SNAPSHOT` `OPT-001` 的独立 `codex-app-server-worker` 架构 supersede。
- 被 supersede 的实现和 smoke 不再是当前执行架构、Worker 发版或生产启用证据；保留它们是为了记录已验证的协议、隐私和防重放约束，不代表应继续发布旧 lane。
- 当前生产启用、任务幂等受理、Runtime Registry、Task / Session affinity、canary、回滚和 SDK Worker 退役均在 [1.4.0 OPT-001](../../1.4.0-SNAPSHOT/workitems/OPT-001-independent-codex-app-server-worker-requirement.md) 重新实现和验收。

## 目标

在 Navigator 的统一 Codex 模型选择链路中增加 GPT-5.6-Sol `max` 和 `ultra` 档位，并在显式启用、协议版本匹配时，将 Ultra 的 Codex 原生子任务状态安全投影到 Navigator PC 端。模型选择、执行结果和取消链路继续使用统一任务语义；原生子任务只是 Provider 运行态投影，不创建 Navigator 内部 Agent 或顶层 Task。

## 协议

| UI 名称 | 稳定 alias | Worker 实际模型 |
|---|---|---|
| Codex Max | `codex-max` | `gpt-5.6-sol:max` |
| Codex Ultra | `codex-ultra` | `gpt-5.6-sol:ultra` |

- `xhigh`、`max`、`ultra` 是独立档位，不做降级映射。
- `max` 表示最大推理深度。
- `ultra` 表示最大推理深度并允许 Codex 自动任务委派。
- 已验证 `@openai/codex-sdk` / Codex CLI `0.144.1` 可运行两档；这是 1.3.1 历史验证基线，不作为 1.4.0 app-server runtime 的兼容或发布门控。
- 显式模型后缀和 alias 解析出的档位优先于请求里的通用 `codexConfig.model_reasoning_effort`。

### Ultra 原生子任务投影契约

- Worker 事件：`native_subtask_update`，`contract_version=1`，每条事件携带完整的单子任务最新状态。
- Java 统一事件：`NATIVE_SUBTASK_UPDATE`；事件不进入聊天历史，只用于最新状态投影与会话 SSE。
- 查询恢复：`GET /api/v1/tasks/{taskId}/native-subtasks`，必须先按当前用户解析任务归属。
- PC 展示：Task Pane 内独立的“Codex 子任务”折叠条；按 `subtaskId + lastEventSeq` 合并快照和实时事件，多 Pane 状态隔离。
- 状态字段白名单：父子关系、深度、显示标签、角色、状态、活动类型、稳定失败码、时间和持续时长；失败时不得保留任意 Provider 原始错误文本。
- 隐私边界：不投影子任务 prompt、assistant 输出、reasoning、工具参数、工具输出、凭据或完整 app-server 原始事件；内部子任务 ID 只用于协议归并，不在 UI 展示。

## 范围

1. Worker 增加两个默认 alias，并放行、解析、透传 `max` / `ultra`。
2. 前端统一模型列表增加 Max / Ultra，任务下拉、设置页、Agent 默认模型和会话转发复用同一来源。
3. `/model` 命令改为消费当前 Provider 的候选模型，移除硬编码 Claude 模型列表；多 Pane 续接输入不暴露全局模型切换。
4. Java 任务入口在创建 Session 或持久化任务前校验模型配置访问权和 Max / Ultra 显式授权，阻止 REST、续接和 A2A 绕过前端白名单。
5. Worker 对 SDK 运行时产生的 `collab_tool_call` 写结构化诊断日志。
6. `[历史实现，已 superseded]` Ultra 曾在旧 Codex Worker 内增加 app-server 原生执行通道；该通道默认关闭，只在 `CODEX_APP_SERVER_ULTRA_ENABLED=true`、bundled CLI 可执行且版本精确等于已验证版本 `0.144.1` 时启用。
7. Worker 将原生子线程生命周期归一化为脱敏 `native_subtask_update`，Java 持久化最新状态并通过统一 session SSE 转发，PC 在 Task Pane 展示折叠摘要和层级明细。
8. 新增 `native_subtask_states` 显式生产迁移、任务归属查询、乱序/重复事件归并、重连快照恢复和任务删除清理。
9. 自动化测试、真实 Worker Ultra smoke、MySQL 8 迁移幂等 smoke 和 PC Playwright 覆盖本阶段。

## 非目标

- 不把 Codex 原生子任务注册为 Navigator Agent、Session、Task，也不接入 Navigator 内部 A2A Agent 调度或顶层并发计数。
- 不读取 Codex 本地 session JSONL 拼装子任务事件；状态来源仅限已验证 app-server 通知。
- 不展示或持久化子任务 prompt、正文输出、reasoning、工具调用参数与输出；也不把原生子任务事件写入聊天历史。
- 不改变 `codex-latest`、`codex-fast`、`codex-deep`、`codex-xhigh`、`codex-mini` 的现有映射。
- 转发弹窗继续使用独立模型下拉；已有会话转发不暴露 `/model`，避免选择不生效。
- 多 Pane 续接仍沿用现有全局模型上下文，但不显示 `/model`；pane 级异构 Provider 模型状态不在本项内重构。
- 旧混合 SDK / app-server lane 不打包或发布；`1.0.11` 仅发布稳定 SDK Worker，并对新 Ultra 请求 fail closed 到独立 App Server Worker。
- `[历史实现边界，已 superseded]` 旧 App-server lane 本阶段只支持 Ultra，固定 `approval_policy=never`，不接收 `additional_directories`，也不承诺处理 app-server 主动发起的交互式 server request；不满足时必须在 `turn/start` 前回到 SDK。当前边界以 1.4.0 独立 Worker 契约为准。

## 验收标准

- 前端可选择 `codex-max` 和 `codex-ultra`，已有显式 `availableModels` 白名单仍需主动授权新 alias。
- 平台模型配置存在时，后端必须校验 Worker 对该配置的访问权；受限白名单不得通过直接请求或模型后缀绕过。
- Worker 将两者分别解析为 `gpt-5.6-sol:max` 和 `gpt-5.6-sol:ultra`。
- 两档均通过真实 Worker SSE 调用；Ultra 自动委派时最终文本、完成事件和取消链路不受影响。
- `collab_tool_call` 不再完全静默，日志不得暴露 prompt、线程 ID 或凭据。
- `[历史验收项，已 superseded]` `CODEX_APP_SERVER_ULTRA_ENABLED` 默认值必须为 `false`；只有 CLI 版本精确匹配 `0.144.1` 才可报告 `native_subtasks_supported=true`，版本不匹配时 health 降级并在执行前安全使用 SDK 通道。
- `[历史验收项，约束继续有效]` 旧 lane 的失败回退以 `turn/start` 为提交边界：提交前可以改走 SDK；一旦 `turn/start` 已被接受，任何连接/协议错误都不得用同一 prompt 自动调用 SDK。1.4.0 以幂等受理和 committed 状态替代该 fallback 架构，但仍必须保证不重放。
- Worker 仅发送子任务状态白名单；失败 `message` 只能是稳定通用码，不能透传任意 Provider 错误文本。Java 和 PC 不得将原始 app-server 事件、子 prompt、输出、reasoning、工具参数/输出或凭据扩散到 SSE、持久化或 UI。
- Java 必须按 `taskId + subtaskId` 保存最新状态，以父 Codex 任务悲观锁串行化首次插入和更新，拒绝旧 seq，并阻止任务删除后迟到事件重建投影。
- `GET /api/v1/tasks/{taskId}/native-subtasks` 必须执行用户归属校验；原生子任务事件不得进入聊天历史。
- PC 必须在确认 SSE 订阅后拉取快照，并按 session、task、连接 epoch、`subtaskId` 和 seq 处理重连、续接、旧响应、重复回调及多 Pane 隔离；窄 Pane 不得横向溢出，不显示原始子任务 ID。
- 删除必须保持可恢复两阶段语义：Provider 任务及 Provider 侧原生状态先在独立事务完成；统一 session task projection 最后删除并作为失败重试的归属/路由标记。Provider 已不存在时，只有当前用户仍拥有统一投影才允许继续清理，禁止跨用户 fallback。
- 保留 Java / Session / PC projection 时仍须执行 `docs/migration/2026-07-10-native-subtask-states.sql` 并通过生产 `ddl-auto=validate`；执行架构的生产 canary 与扩大启用不再由本记录批准，统一转入 1.4.0 OPT-001。
- Worker 单测、typecheck、build 和前端测试、type-check、build 全部通过。
- Playwright 验证模型配置界面，以及单 Pane 下原生子任务快照恢复、折叠/展开、层级、状态、ID 隐藏和窄 Pane 布局；多 Pane 隔离、SSE seq 合并和重连 epoch 由 Vitest 覆盖，不把分层 mock 误写为真实浏览器重连证据。

## Progress Tracking

### Development

- [x] 需求、协议、非目标和验收口径固化
- [x] Worker alias、校验和 SDK config 透传
- [x] Worker 自动化测试
- [x] 前端模型选项和动态 `/model`
- [x] Ultra 基础诊断可观测
- [x] `[历史实现，已 superseded]` Ultra app-server opt-in 通道、精确 CLI 协议门控和 health 能力字段
- [x] `[历史约束证据]` `turn/start` 前后故障分类与提交后禁止 SDK 重放
- [x] 原生子任务事件归一化、隐私字段收窄和 Worker SSE
- [x] Java 最新状态投影、用户归属查询、统一 SSE 和聊天历史隔离
- [x] PC Task Pane 折叠条、快照恢复、乱序/重复归并与多 Pane 隔离
- [x] 两阶段可恢复删除和显式 MySQL 迁移

### Testing

| 用例 | 状态 |
|---|---|
| Worker unit / typecheck / build | passed: 128 / 128，typecheck、build 通过 |
| Frontend Vitest / type-check / build | passed: 133 / 133，type-check、统一构建通过 |
| Java Session targeted regression | passed: `TaskDispatchFacadeTest,NativeSubtaskQueryServiceTest,TaskControllerTest,SessionEventListenerTest` 93 / 93 |
| Java Codex targeted regression | passed: `CodexNativeSubtaskServiceTest,CodexStreamRelayTest,CodexTaskServiceTest` 47 / 47 |
| Max / Ultra Worker SSE smoke | passed: Max / Ultra 均返回预期最终文本 |
| Ultra delegation / resume / abort smoke | passed: 自动委派诊断、同 session 续接、abort 均通过 |
| Ultra app-server real Worker smoke | historical evidence: 39 条事件，6 条 native update，1 个原生子任务，`running -> completed`，0 error，seq 严格递增且字段白名单通过；不作为当前 Worker 发布证据 |
| MySQL 8 migration smoke | passed: 同一临时库连续执行迁移两次；表、20 列、`message VARCHAR(64)` 和索引符合预期 |
| `git diff --check` | passed: 仅工作区既有 CRLF conversion warning |

### Experience

| 检查项 | 状态 |
|---|---|
| 设置页可达性与模型授权交互 | passed |
| 任务模型选择和 `/model` 一致性 | passed: helper + 组件级 provider 更新测试；续接 Pane 隐藏全局模型切换 |
| 无权限、空白名单和显式白名单可见性 | passed: 模型选项单测 |
| 桌面与移动视口文本、弹层和选项布局 | passed: 1440x900、390x844 |
| Playwright evidence | `temp/test-artifacts/opt-005/ui-desktop.png`、`ui-mobile.png` |
| 原生子任务折叠/展开、状态和层级 | passed: 4 条状态行，覆盖 running / failed / interrupted、父子缩进和摘要计数 |
| 原生子任务隐私与窄 Pane | passed: 不显示内部 child ID；320px Pane 无横向溢出，窄布局隐藏次要 role/activity |
| 原生子任务 Playwright | passed: `packages/navigator-frontend/e2e/codex-native-subtasks.spec.ts` 1 / 1；mocked snapshot contract、单 Pane，未模拟真实 SSE 重连 |

## Execution Check-in

- date: 2026-07-10
- implementation: completed for Max / Ultra mapping and Ultra native-subtask projection
- readiness: alias / authorization / Java / Session / PC projection 保留范围已验收；执行架构和生产启用转入 1.4.0 OPT-001
- release_state: `codex-worker 1.0.11` published to OBS for Windows / Linux / macOS
- current_package_version: `1.0.11`

### 代码落点

- Worker：默认 alias、共享 reasoning 规范化、请求校验和 SDK config 优先级属于保留证据；旧 Ultra app-server runtime、事件 bridge 和 tracker 属于已 supersede 的历史实现输入。
- Java 后端：Worker 模型配置访问校验、Max / Ultra 显式 grant、`NATIVE_SUBTASK_UPDATE` 协议、最新状态表/服务、统一 SSE、用户归属快照 API 和可恢复删除。
- 前端：统一模型选项、设置页授权、任务模型下拉、Provider 动态 `/model`、Task Pane 原生子任务独立 reducer/API/UI、连接 epoch 与重连快照恢复。
- 文档与迁移：接入示例、health/开关/回退边界、隐私契约及 `2026-07-10-native-subtask-states.sql`。

### 验证证据

- `CODEX_ALLOWED_CWDS=<系统临时目录> npm test`：Worker 128 / 128 passed。
- `npm run typecheck`、`npm run build`：Worker passed。
- `pnpm test`：Navigator Frontend 133 / 133 passed。
- `pnpm type-check`：Navigator Frontend passed。
- Session focused Maven tests：93 / 93 passed；Codex focused Maven tests：47 / 47 passed。
- `bash scripts/build-frontend.sh`：`foggy-chat-core`、`foggy-chat`、Navigator Frontend 全部构建成功。
- Worker SSE：Max 返回 `WORKER_MAX_OK`；Ultra 返回 `WORKER_ULTRA_OK 42 5`；续接保持同一 session；abort 状态为 `aborted`。
- Ultra 日志：脚本断言 `resolved_model=gpt-5.6-sol:ultra`、`reasoning=ultra` 和脱敏 `collab_tool_*` 诊断存在。
- Playwright：桌面和移动视口均可勾选 Max / Ultra，模型说明可见，弹窗位于视口内，页面与后端选项无横向溢出。
- Flag-on real Worker：health 报告 Worker `1.0.10`、SDK/CLI `0.144.1`、protocol compatible 和 native supported；只读 Ultra prompt 产生 1 个原生 child，6 条状态事件、1 条 result、0 error，工作区 Git 状态不变。
- 原生子任务 Playwright：在 mocked API/订阅契约下调用快照 API，验证单 Pane 折叠摘要、4 行层级、状态、内部 ID 隐藏和 320px Pane 无溢出，1 / 1 passed；多 Pane 与重连由 Vitest 覆盖。
- 临时 MySQL 8：迁移脚本连续执行两次成功；临时容器已清理。
- `1.0.11` 发布物复核：三平台远端归档与本地 SHA-256 一致；`latest.json.version=1.0.11`；归档锁定 `@openai/codex-sdk 0.144.1`，包含 GPT-5.6 / Max 和 `CODEX_ULTRA_APP_SERVER_REQUIRED`，不包含旧混合 app-server lane。

### 剩余边界

- 原计划 `codex-worker 1.0.11` 混合 lane 发版不再推进；同版本号现仅用于稳定 SDK Worker 发布。独立 app-server Worker 的版本、产物和 rollout 仍由 1.4.0 OPT-001 管理。
- 实际生产数据库尚未执行迁移，也未完成生产 profile `ddl-auto=validate` 启动；这是部署门禁，不是代码验收缺陷。
- 尚无真实独立 app-server Worker -> Java -> unified SSE -> PC 的单条全栈自动化；旧 Worker 外部协议 smoke 仅为历史证据，当前全链路 canary 必须在 1.4.0 重新完成。
- 既有多 Pane 全局模型上下文未改为 pane 级 Provider 状态；本次通过隐藏续接 Pane 的 `/model` 避免新增错配入口。

## 风险

- SDK `0.144.1` 的 TypeScript `ModelReasoningEffort` 类型尚未声明 `max` / `ultra`；实现使用 SDK 已公开的通用 `config` 通道，避免修改第三方类型。
- 旧版真实模型白名单仅迁移普通 alias；`gpt-5.6-sol:max` / `gpt-5.6-sol:ultra` 与稳定 alias 双向等价，其他未来 `:max` / `:ultra` 模型只接受精确授权。
- Ultra 内部子任务不计入 Navigator 顶层任务并发数或 `maxTurns`，可能增加账号使用量；PC 展示是 Codex 原生线程投影，不表示 Navigator 已接管调度。
- `[历史实现]` 旧 lane 采用 CLI 精确版本门控；当前兼容性、schema digest 和 capability readiness 由 1.4.0 Runtime Registry 契约重新管理。
- 重复执行不是 Ultra 固有副作用；旧实现以 `turn/start` 为提交边界。1.4.0 改为幂等受理和 committed 状态，但“不在已提交后重放同一 prompt”仍是强制约束。
