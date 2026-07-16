---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.2-SNAPSHOT
ticket: BUG-007
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
approved_by: repository owner
approved_at: 2026-07-16
open_questions: []
---

# Delivery Spec: App Server 单 Child 多 Thread 隔离验证

## Document Purpose

- intended_for: ultra-implementation / independent-signoff
- purpose: 冻结 `codex-app-server-worker` 在固定 Codex CLI 0.144.3 下的单 child、多 Thread 并发与同 Thread 串行交付契约。
- canonical_path: `docs/version-tracker/1.4.2-SNAPSHOT/workitems/BUG-007-app-server-single-instance-containment.md`
- supersedes: 本文件此前“单 child + 全局 exclusive lease 串行”的确认结论；该实现导致所有任务全局串行，已于 2026-07-16 先标记 `NEEDS_REPLAN` 后由 owner 批准本契约。

## Goal

- version_goal: 在一个 Worker 内任何时刻最多运行一个 app-server child，排除多个 child 并发共享同一 `CODEX_HOME` 的变量，同时保留不同 Codex Thread 的并发能力。
- target_outcome: startup lane 完全相同的任务共享一个健康 child；不同 Thread 可并发执行 root turn，同一 Thread 的完整任务生命周期仍严格串行。

## Scope

- in_scope: `tools/codex-app-server-worker` 的 app-server runtime、JSON-RPC 路由、pool lease、executor 安全边界、配置/运行说明和自动化回归。
- affected_modules: Codex App Server Worker。
- external_dependencies: 固定 `@openai/codex` 0.144.3 app-server 协议与源码行为；本项不升级 CLI。

## Non-Goals

- out_of_scope: 不按 `configModel` 创建物理隔离的 `CODEX_HOME`；不改 Java 模型配置或传参；不改变 Codex Biz Worker 或 SDK Worker。
- implementation_boundary: 原实现阶段不修改或部署到 `~/.codex-app-server-worker`，不修改其中 `.env`，不停止、重启或发布任何运行进程/服务。owner 于 2026-07-16 在实现完成后另行明确授权本机部署、重启和多 Thread 凭据实测；该授权不包含 OBS 发布或生产 soak。
- validation_boundary: 自动化证明 Worker 路由和隔离契约；本机更新/重启后的双 Thread 凭据 smoke 仅证明本次固定 CLI、当前账户与当前 startup lane 下终端工具可用，不替代更长时间生产观测。

## Protocol Evidence and Confirmed Decisions

| Decision | Evidence / Rationale | Compatibility / Constraint |
|---|---|---|
| 固定版本允许不同 Thread 共用一个连接并发处理 | Codex `rust-v0.144.3` commit `78ad6e6bfd1d3b6a209acd3ef82172a96b25179c` 将 `turn/start`、`turn/steer`、`turn/interrupt` 按 `thread_id` 分键串行，源码测试验证不同 key 并发 | 官方公开文档没有作跨版本并发保证；升级 CLI 前必须重新核对协议/源码并运行回归 |
| 同一 Thread 由 Worker 在完整任务周期串行 | 固定源码的第二个 `turn/start` 可能向普通 active turn steer，而非稳定拒绝；review/compact 等非 steer turn 才报错 | 保留 executor 的 `thread:${session_id}` keyed lock；不得把此策略表述为官方 app-server 限制 |
| runtime 状态按 turn context 隔离 | turn terminal 使用 `threadId + turn.id`；item/delta/error 使用 `threadId + turnId`；user-input 使用 `threadId + turnId + itemId + request id`；interrupt 使用 `threadId + turnId`；resolved 使用 `threadId + requestId` | 任何缺失/冲突 affinity 的 turn-scoped 事件或请求必须 fail-closed，不得猜测归属 |
| Worker 内任何时刻最多一个 child | 本项要排除多个 child 共享一个 `CODEX_HOME` 的并发变量，同时避免全局 root-turn 串行 | `POOL_MAX_INSTANCES` 等旧容量参数不得重新允许第二个 child；不保留“关闭单实例即可回弹性池”的承诺 |
| resident child 固定 startup lane | KEY/auth fingerprint、base URL、`CODEX_HOME`、CLI、进程环境共同决定启动环境 | 同 lane 共享；不同 lane 返回稳定 mismatch 错误，不替换健康 child，也不得以错误环境执行 |
| transport/process failure 仍为 child 级 fail-closed | 一个 child crash、连接 fatal、进程树安全失败会影响其全部 active turns | 所有相关 task 都必须得到各自带 affinity 的失败；普通 retire/drain 不得关闭仍有 active 或 unverified turn 的 child |
| 手工 PID kill 不扩大单任务授权 | 多个任务可能共享同一 PID，进程级终止会同时影响其他任务 | child 有多个 active task/turn 时必须安全拒绝单任务 PID kill；仅在可证明唯一 owner 时沿用既有授权终止语义 |

## Acceptance Criteria

- [x] AC-1: Worker 任意时刻最多创建/持有一个 app-server child；两个不同 Thread 同时运行只创建一个 child，且两个 turn 实际重叠执行。
- [x] AC-2: 相同 startup lane 复用 resident child；不同 startup lane 得到稳定 lane-mismatch 错误，健康 resident child 不被替换或关闭。
- [x] AC-3: 同一 Thread 的重叠任务在完整执行周期串行，不依赖 app-server 对重叠 `turn/start` 的行为。
- [x] AC-4: 两个并发 turn 的 notification、terminal completion、targeted abort、`request_user_input`/resolution 按 affinity 隔离，不串台。
- [x] AC-5: runtime 的 active、terminal fence、attention/unverified、stall/abort timer 与 user-input routing 不再是全局单 turn 状态；transport fatal 可一致终止全部相关 contexts。
- [x] AC-6: drain、crash、health failure、retirement、进程树清理与有未验证 turn 时拒绝关闭的既有 fail-closed 语义不退化。
- [x] AC-7: README、`.env.example` 和配置测试移除“默认全局串行/可关闭单实例模式”的错误契约，并说明固定 CLI 版本边界与 lane 拒绝语义。

## Contract / Data / Security Constraints

- API or event contract: 不改 HTTP path、Task/SSE payload 或持久化 schema；保留稳定错误码 `APP_SERVER_POOL_SINGLE_INSTANCE_LANE_MISMATCH`，必要时为共享 PID 安全拒绝新增稳定内部错误码。
- data and migration: 无数据库或状态迁移。
- compatibility: 上游 Task API、Thread/session 续接和 rate-limit 查询契约保持不变；同 Thread 排队继续受现有 Worker 有界任务队列约束。
- permissions and secrets: lane 仅比较既有 digest key；错误、指标、日志、测试和文档不得输出 API key、原始 `CODEX_HOME` 或其他 secret。

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| AC-1/2 | critical | pool concurrent lease tests | 不同 Thread/lease 并发复用一个 runtime；异 lane 拒绝且 resident 未 retire |
| AC-3 | critical | executor keyed-lock regression | 同 session 的第二任务在第一任务 terminal/release 前不进入 `turn/start` |
| AC-4/5 | critical | runtime protocol tests | 交错事件、独立 completion、单 turn abort、并发 user-input request/response/resolved 不串台 |
| AC-6 | critical | runtime/pool crash and drain suites | crash fan-out、attention fence、drain deadline、process-tree safety 既有测试继续通过并补共享 runtime 场景 |
| AC-7 | major | config/docs review and tests | 不再暴露错误的全局串行开关语义，固定版本与未部署边界可复核 |
| full worker | major | module validation | `npm test`、`npm run typecheck`、`npm run build` 的真实命令、计数和 exit code |

## Bug Context

- bug_source: user-report
- severity: major
- environment: `codex-app-server-worker` 0.3.16 运行态曾发现多个 app-server child 共用同一 `CODEX_HOME`；owner 当前只有一个 KEY。
- current_behavior: 第一轮 containment 新增 `CODEX_APP_SERVER_POOL_SINGLE_INSTANCE_MODE=true`，虽然限制为一个 child，却保留 exclusive lease，导致所有 Thread 全局串行。
- expected_behavior: 单 child 与 startup lane 固定；不同 Thread 并发；同一 Thread 串行；所有事件、终止和交互严格按 Thread/Turn 归属。
- reproduction_status: confirmed
- existing_evidence: 运行态曾观察 `instances=2` 且 child 共享 `CODEX_HOME`；第一轮自动化只验证了一 child + queue，未验证单 child 多 Thread multiplexing。
- regression_protection: required; 先写/改会失败的自动化，再实现。
- waiver_reason_and_risk: 真实 Codex 工具丢失需要 owner 凭据和受控运行窗口，不能用 mock 测试宣称已修复。

## Risks and Open Questions

- known_risks: 公开文档没有跨版本并发保证；固定 0.144.3 的 server request/notification affinity 若未来变化会破坏路由；一个 child crash 会同时影响多个 Thread；不同 startup lane 在 resident 存活期间会被拒绝。
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md`、`CLAUDE.md`、相关 Worker 实现/测试与固定 CLI 0.144.3 协议源码。
- 测试先行；在 scope 内自主决定具体类、函数和数据结构。
- 如需改变目标、非目标、startup lane、安全拒绝策略、上游兼容或部署边界，设置 `NEEDS_REPLAN` 并停止扩展。
- 运行与改动面匹配的验证，不得声称未实际运行或失败的检查通过。
- 完成后填写 `Implementation Result`，逐项更新验收证据，并将状态改为 `READY_FOR_SIGNOFF`；实现会话不得自行设置 `ACCEPTED`。

## Implementation Result

- implementation_summary: `AppServerRuntime` 改为每个 active turn 独立 context，并按 `threadId`/`turnId`/JSON-RPC request id 路由 notification、terminal、abort 与 `request_user_input`；共享 transport fatal 对所有相关 context fail-closed。`AppServerPool` 改为固定单 child、同 lane 共享 lease、异 lane 稳定拒绝，且以 active lease 计数保护 crash/retire/drain。`AppServerExecutor` 保留同 Thread 全任务 keyed lock，并为共享 PID 的单任务终止增加拒绝边界。2026-07-16 部署后复现进一步发现默认 rate-limit 查询遗漏 Worker 配置的 API key/base URL，会抢先创建错误 startup lane 的唯一 child；`0.3.18` 已让默认限额查询与任务执行使用同一 canonical lane，并将确定性的 pre-turn lane mismatch 以稳定错误码单次终止，避免 `PROCESS_UNVERIFIED` 每秒恢复放大。Java 仍按既有契约传递 modelConfig 凭据，无需改动。
- changed_paths: `tools/codex-app-server-worker/src/app-server/runtime.ts`; `src/app-server/pool.ts`; `src/app-server/executor.ts`; `src/task-manager.ts`; `src/config.ts`; `src/runtime-capabilities.ts`; `src/version.ts`; `package.json`; `package-lock.json`; `.env.example`; `README.md`; `tests/app-server-runtime.test.ts`; `tests/app-server-pool.test.ts`; `tests/executor-concurrency.test.ts`; `tests/managed-process-snapshot.test.ts`; `tests/rate-limits-pool.test.ts`; `tests/rate-limits-executor.test.ts`; `tests/reconciliation.test.ts`; `tests/config-instance.test.ts`; `tests/helpers.ts`; 本 canonical workitem。
- tests_and_results:
  - 测试先行（runtime，修复前）：`node --import tsx --test --test-name-pattern='persistent runtime multiplexes|concurrent turns isolate' tests/app-server-runtime.test.ts`，exit 1，2 个新增回归失败，均被旧的 `Codex app-server instance already has an active root turn` 全局限制阻断。
  - 测试先行（executor/process safety，修复前）：`node --import tsx --test --test-name-pattern='single app-server child shares|different write threads|different read-only threads|manual PID termination is rejected' tests/app-server-pool.test.ts tests/executor-concurrency.test.ts tests/managed-process-snapshot.test.ts`，exit 1；不同 Thread 并发用例观察到创建 2 个 runtime，且共享 PID 的单任务终止未在 signal 前拒绝。该命令中的初版 pool 断言设计有误，不作为 pool 红测证据。
  - 定向回归：`node --import tsx --test tests/app-server-pool.test.ts tests/app-server-runtime.test.ts tests/rate-limits-pool.test.ts tests/config-instance.test.ts tests/executor-concurrency.test.ts tests/managed-process-snapshot.test.ts`，exit 0，85/85 passed。
  - 共享 child fail-closed 定向回归：`node --import tsx --test --test-name-pattern='shared transport fatal|shared child crash|drain waits for all shared leases' tests/app-server-runtime.test.ts tests/app-server-pool.test.ts`，exit 0，3 passed、59 skipped、0 failed。
  - Worker 全量测试：`npm test`，exit 0，301 tests、300 passed、1 skipped、0 failed，duration 116567.472502 ms。
  - 类型检查：`npm run typecheck`，exit 0。
  - 构建：`npm run build`，exit 0。
  - 差异格式检查：`git diff --check`，exit 0。
  - 发布包校验：`npm run package:release`，exit 0；301 tests、300 passed、1 skipped、0 failed，schema digest `6f2550bb528581f17c4c3a3857dca92c860406aa3274e314cfa726c32e395d8f`，typecheck/build 通过；产物 `release/output/codex-app-server-worker-0.3.17.zip`，SHA-256 `3608c5d0f90d11c8b6dba49ab85e21fb4154ff6fc10323bed7d036705fe994b7`。
  - 安装前 dry-run：`bash "$HOME/.codex-app-server-worker/update.sh" --package "/home/sa/workspace/Foggy-Navigator/tools/codex-app-server-worker/release/output/codex-app-server-worker-0.3.17.zip" --install-dir "$HOME/.codex-app-server-worker" --dry-run`，exit 0；301 tests、300 passed、1 skipped、0 failed，schema/typecheck/build 通过，明确报告 current installation was not modified。
  - 本机更新：`bash "$HOME/.codex-app-server-worker/update.sh" --package "/home/sa/workspace/Foggy-Navigator/tools/codex-app-server-worker/release/output/codex-app-server-worker-0.3.17.zip" --install-dir "$HOME/.codex-app-server-worker"`，exit 0；更新器再次完成 301 tests、schema/typecheck/build，优雅停止 0.3.16，安装并启动 0.3.17，报告 runtime configuration and state preserved。
  - 部署后健康：安装版本与 `/health` 均为 `0.3.17`，ready=true，固定 CLI `0.144.3` compatible=true；`.env` 更新前后 SHA-256 均为 `ef8c0e4925524fa6d0f2811f817ef6128880e1a5914517fe75ea562cd424aeb5`；无 `lifecycle.lock`、`update.in-progress`、`stop.failed` 或 `lifecycle.failed`。
  - 双 Thread 真实工具 smoke：并发接受 `bug007-deploy-a-1784201281303` 与 `bug007-deploy-b-1784201281303`，观察到 `active_tasks=2`、pool `instances=1`；两个不同 Thread `019f6aaf-0017-7ed1-bdee-f7be5196f961` / `019f6aaf-0017-7ed1-bdee-f7d55c8fc983` 共享 app-server instance `ecc3acff-8c39-41c0-9bc6-7057412f1276`，均实际产生 `command_execution`、各自唯一 `result` 和正确 marker，无对方 marker、无 error。完成后 pool 为 `instances=1, idle=1, created_total=1, reused_total=1`，Worker 只有一个直接 app-server launcher child。
  - 部署后复现：Navigator task `20260716-e7e9` 于 `2026-07-16T12:02:56.773Z` 接受后，在获得 app-server instance/thread/turn affinity 前约 15 ms 进入 `PROCESS_UNVERIFIED`，并被每秒恢复重试。安全解密比对只输出 digest/equality，确认请求 API key、base URL、默认 `CODEX_HOME` 与已安装 Worker `.env` 完全一致；Java 请求构造链确实按既有契约发送这两个字段。源码定位到 `StrictAppServerExecutor.readDefaultRateLimits()` 构造 lane 时遗漏 `config.openaiApiKey/openaiBaseUrl`，因此限额轮询可先占用错误 resident lane；根因在 Worker，不需要 Java 变更。
  - `0.3.18` 红测：`node --import tsx --test tests/rate-limits-executor.test.ts tests/reconciliation.test.ts`，exit 1；默认限额 lane key 不等于任务 lane，确定性 mismatch 未 terminal 且超时，证明两个新增回归均先失败。
  - `0.3.18` 定向回归：`node --import tsx --test tests/rate-limits-executor.test.ts tests/reconciliation.test.ts tests/app-server-pool.test.ts`，exit 0，36/36 passed。
  - `0.3.18` Worker 全量测试：`npm test`，exit 0，302 tests、301 passed、1 skipped、0 failed，duration 116513.028097 ms。
  - `0.3.18` 类型检查：`npm run typecheck`，exit 0。
  - `0.3.18` 构建：`npm run build`，exit 0。
  - `0.3.18` 发布包校验：`npm run package:release`，exit 0；302 tests、301 passed、1 skipped、0 failed，schema digest `6f2550bb528581f17c4c3a3857dca92c860406aa3274e314cfa726c32e395d8f`，typecheck/build 通过；产物 `release/output/codex-app-server-worker-0.3.18.zip`，SHA-256 `c3f3c76651bbfe61060d93f70379173fc80ee75ff5bae9fcb499400d09383a7b`。
  - `0.3.18` 安装前 dry-run：`bash "$HOME/.codex-app-server-worker/update.sh" --package "/home/sa/workspace/Foggy-Navigator/tools/codex-app-server-worker/release/output/codex-app-server-worker-0.3.18.zip" --install-dir "$HOME/.codex-app-server-worker" --dry-run`，exit 0；302 tests、301 passed、1 skipped、0 failed，schema/typecheck/build 通过，并明确报告 current installation was not modified。
  - `0.3.18` 本机更新尝试：`bash "$HOME/.codex-app-server-worker/update.sh" --package "/home/sa/workspace/Foggy-Navigator/tools/codex-app-server-worker/release/output/codex-app-server-worker-0.3.18.zip" --install-dir "$HOME/.codex-app-server-worker"`，exit 1；候选包验证通过后，旧 `PROCESS_UNVERIFIED` 任务使 0.3.17 无法证明优雅 drain。更新器没有终止 Worker/child、没有替换安装文件，并按 fail-closed 契约保留 `logs/run/worker.process-tree.json`、`logs/run/update.process-tree.json` 与 `logs/run/stop.failed`。安装版本仍为 0.3.17，更新前后 `.env` SHA-256 均为 `4c3c377cb0a6279bcfb421d0ae0d980c230ab691cead49511ec826c7528408c9`；因 `stop.failed` latch，`/health.ready=false`。未执行显式签名终止或人工清理。
  - owner 授权后的显式人工恢复：精确绑定旧 Worker PID `4044296`，先发送 `SIGTERM` 并等待 10 秒；因未退出，使用安装包自带 process-tree helper 对已持久化身份执行终止与校验。`worker.process-tree.json` 和 `update.process-tree.json` 均返回 `{"status":"clean","count":0}`；再次确认 Worker/app-server residue、state lifecycle fallback、runtime process-tree fallback、lifecycle lock 和 update transaction 均为 0 后，仅 unlink 已验证的 lifecycle evidence。
  - `0.3.18` 本机更新重试：`bash "$HOME/.codex-app-server-worker/update.sh" --package "/home/sa/workspace/Foggy-Navigator/tools/codex-app-server-worker/release/output/codex-app-server-worker-0.3.18.zip" --install-dir "$HOME/.codex-app-server-worker"`，exit 0；安装器实际执行 302 tests、301 passed、1 skipped、0 failed，schema digest `6f2550bb528581f17c4c3a3857dca92c860406aa3274e314cfa726c32e395d8f`，typecheck/build 通过，并报告 runtime configuration and state preserved。
  - 部署后健康与配置保持：安装目录 `VERSION=0.3.18`，`/health.status=ok`、`ready=true`、CLI `0.144.3` compatible=true；Worker process=1、3071 listener=1、resident app-server child=1。`.env` SHA-256 仍为 `4c3c377cb0a6279bcfb421d0ae0d980c230ab691cead49511ec826c7528408c9`；无 `lifecycle.lock`、`update.in-progress`、`stop.failed` 或 runtime lifecycle failure。
  - 历史恢复任务：0.3.18 启动后，一个 child 同时承载 3 个历史恢复 Thread，health 一度显示 `active_tasks=3, instances=1, created_total=1, reused_total=2, reserved_threads=3`；`20260716-00a5`、`20260716-83de`、`20260716-e7e9` 均恢复为 terminal/completed。旧任务 `20260716-e492` 仍按原 fail-closed 决策保留为非活动 `PROCESS_UNVERIFIED`，没有被重放或伪造 terminal。
  - canonical rate-limit lane 实测：`GET /api/v1/runtime/rate-limits?refresh=true` 携带当前 instance affinity，返回稳定 `RATE_LIMITS_UNSUPPORTED`；调用前后 pool 均为 `instances=1, created_total=1, retired_total=0, crashes_total=0`，未创建错误 lane child，也未替换健康 child。
  - 部署后双 Thread 真实工具 smoke：并发任务 `bug007-live-a-20260716T123523Z` / `bug007-live-b-20260716T123523Z` 均 HTTP 202，采样观察 `max_active=2`、`max_children=1`；两者 terminal/completed，turn id 分别为 `019f6aec-ae94-7c50-a451-d84f24711449` / `019f6aec-aef8-72a3-adc2-badba0ff692e`。每条 SSE 各 35 个事件并实际包含 `tool_use`/`tool_result`；A 流只含 A marker，B 流只含 B marker，独立 session 与 terminal result，无串台。完成后 pool `instances=1, created_total=1, reused_total=4`。
  - 同 Thread 真实串行 smoke：在同一 native session 上，`bug007-same-thread-c-20260716T123523Z` 运行期间重叠接受 D 返回 HTTP 409 `APP_SERVER_THREAD_ACTIVE`；C terminal 后以同一 task id 重试 D 返回 HTTP 202，C/D 再按顺序分别 terminal/completed，child 未替换，pool `reused_total=6`。
  - `request_user_input` 并发隔离 smoke：E Thread `bug007-input-e-20260716T123523Z` 独立进入 `awaiting_input`，F Thread `bug007-input-f-20260716T123523Z` 同时完成。E 的 request id `0` 通过同 task/instance/thread/turn 绑定的 `/respond` 返回 HTTP 200；E 流有 1 个 `user_input_request`、1 个 `user_input_resolved` 和 E terminal result，F 流二者均为 0 且只有 F marker，最终 `active_tasks=0`、`pending_user_inputs=0`、child=1。
  - 外部 Navigator 多 Thread smoke 未伪造为已执行：当前进程环境没有安全注入的 `NAVIGATOR_TOKEN`，且从本机对 `http://dev-kvm-jdk17-2.foggysource.com/api/v1/tasks` 的无凭据 10 秒探测无响应。聊天中粘贴的 JWT 未写入 shell、仓库或测试产物；需 owner 以环境变量/安全凭据通道注入后再执行 Java/Navigator 入口的双 Thread 测试。
- manual_or_experience_evidence: owner 已授权并完成保留 lifecycle evidence 的精确身份恢复，将本地安装更新并启动为 0.3.18。当前 Worker ready=true，固定 CLI 0.144.3 compatible=true，单 child 的不同 Thread 并发、同 Thread 拒绝重叠后顺序执行、终端工具事件、completion 与 `request_user_input` 隔离均通过本机真实凭据 smoke。Java 继续使用既有 modelConfig API key/base URL 传参，无需代码更新。外部 Navigator 入口 smoke 尚未执行，原因是没有安全注入凭据且目标地址从本机探测超时。未上传 OBS、未发布 latest metadata、未运行生产 soak，未修改 `.env` 内容。
- deviations: 实现保持新批准的单 child、多 Thread 并发、同 Thread 串行和异 lane 拒绝边界。自动化命令所属执行环境会在命令返回时清理其后代进程，因此首次直接运行 `start.sh --no-build` 后 Worker 已通过 readiness、但随后被托管环境清理；精确 process-tree verify 返回 `clean,count=0` 后，仅移除对应 stale evidence。最终仍由官方 `start.sh --no-build` 完成启动，但外层放入独立 tmux session `codex-app-server-worker-0318` 以保持生命周期；Worker PID `4148080` 稳定运行。state identity 继续使用既有持久 marker `codex-store-5ea69ced-19e1-4c85-bb68-f8854a81d455`，未手工篡改 identity。公开官方文档未提供跨版本并发保证，结论仅绑定固定 CLI 0.144.3 的真实源码与协议测试。
- residual_risks: CLI 升级前必须重新验证 keyed request serialization 与 affinity；单 child crash/transport fatal 会影响所有共享 Thread；resident child 存活时不同 startup lane 会按设计拒绝；旧任务 `20260716-e492` 仍以非活动 `PROCESS_UNVERIFIED` 保留并需要独立业务决策；Java/Navigator 入口的真实双 Thread smoke 尚需安全注入 token 且需要目标地址可达，完成前不能宣称 Navigator 端工具丢失已消失；部署后的 Worker instance ID 可能变化，依赖旧 instance affinity 的上游记录必须按现有注册/重绑定机制处理。
- readiness: READY_FOR_SIGNOFF；未自行标记 `ACCEPTED`。

## References

- related work items: `docs/version-tracker/1.4.0-SNAPSHOT/workitems/BUG-022-app-server-unexpected-image-generation-tool-loss.md`
- implementation: `tools/codex-app-server-worker/src/app-server/runtime.ts`; `pool.ts`; `executor.ts`
- fixed upstream source: OpenAI Codex tag `rust-v0.144.3`, commit `78ad6e6bfd1d3b6a209acd3ef82172a96b25179c`
