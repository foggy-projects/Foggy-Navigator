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

- in_scope: `tools/codex-app-server-worker` 的 app-server runtime、JSON-RPC 路由、pool lease、executor 安全边界、配置/运行说明和自动化回归；补充一个可重复执行的 Navigator API → Java → Codex App Server Worker → 固定 Codex CLI → mock Responses API 全链路 E2E，并将任何真实凭据验证保留为人工、显式 opt-in。
- affected_modules: Codex App Server Worker、mock LLM service、Navigator E2E 编排与测试入口；Java 仅作为被测链路，除非 E2E 暴露真实契约缺陷，否则不改生产逻辑。
- external_dependencies: 固定 `@openai/codex` 0.144.3 app-server 协议与源码行为；本项不升级 CLI。

## Non-Goals

- out_of_scope: 不按 `configModel` 创建物理隔离的 `CODEX_HOME`；不改 Java 模型配置或传参；不改变 Codex Biz Worker 或 SDK Worker。
- e2e_out_of_scope: 不要求启动 Claude Worker；不把 mock LLM 结果表述为真实供应商工具可用性证明；不写入真实 KEY/JWT 到跟踪文件或持久测试证据；本扩展不部署、不重启已安装 Worker、不发布 OBS 或 latest metadata。
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
- [x] AC-8: mock LLM service 支持固定 CLI 0.144.3 实际使用的 `/v1/responses` SSE 与 function-call round trip，脚本 cursor 能跨 `function_call_output` 续接，debug 记录继续脱敏。
- [x] AC-9: 项目内提供默认无真实 KEY 的隔离 E2E 入口，启动 repo-local mock LLM、Codex App Server Worker 和 Java，通过真实 `POST /api/v1/tasks` 创建任务；临时状态、日志与证据位于 `temp/test-artifacts/bug007-navigator-e2e/`。
- [x] AC-10: Navigator E2E 证明无 resident child 时 rate-limit refresh 只返回不可用快照且不创建/锁定 child；首个真实任务建立 canonical lane 后，额度查询只复用同 lane resident child。两个不同 session/Thread 的任务可重叠、只创建一个 app-server child，且各自 command marker、completion 与 terminal 状态不串台。
- [x] AC-11: Navigator E2E 覆盖同一 session/Thread 的重叠提交仍安全拒绝或排队串行，并覆盖 targeted cancel/abort 与 `request_user_input`/respond 在并发另一 Thread 时不串台。
- [x] AC-12: 异 startup lane 请求被稳定拒绝且不替换健康 child；随后原 lane 仍可完成任务，drain/crash 既有 Worker 回归继续通过。
- [x] AC-13: 默认 runner 主动清除继承的真实供应商凭据，默认测试和 CI 不需要真实 LLM KEY；任何真实供应商验证仍须由 operator 通过受控环境显式执行，不把凭据写入仓库或持久证据。

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
| AC-8 | critical | mock Responses API contract tests | 真实固定 CLI 可消费的 SSE event、tool call、cursor continuation 与脱敏 debug evidence |
| AC-9/10 | critical | isolated Navigator API E2E | 真实 Java API 提交、Worker/CLI/mock 链路、并发采样、单 child 与独立 terminal marker evidence |
| AC-11/12 | critical | isolated lifecycle E2E + Worker regression | 同 Thread 边界、input/cancel affinity、异 lane 后 resident continuity；crash/drain 定向测试不退化 |
| AC-13 | major | runner/config review | 默认 mock、live 显式 opt-in、secret 不落盘 |
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

## Navigator E2E Extension Approval

- replanned_at: 2026-07-16
- approval_source: repository owner 在本会话明确要求补齐项目内 Codex App Server Worker、Java 与 mock/可选真实 LLM 的 E2E；该消息批准 AC-8 至 AC-13 的新增交付范围。
- execution_decision: 默认实现 mock Responses API profile；真实 KEY profile 为显式 opt-in。E2E 只管理自己启动且身份已记录的进程，不按端口杀进程，不接管现有安装目录或运行实例。

## 2026-07-17 Rate-Limit Control-Plane Amendment

- replan_reason: 运行态任务 `20260717-5258`、`20260717-3c28`、`20260717-43b7` 暴露出额度查询可在无 resident child 时使用 Worker 默认 `OPENAI_API_KEY` 主动创建唯一 child；随后 Java 任务携带不同的 modelConfig LLM KEY，被正确但非预期地拒绝为 `APP_SERVER_POOL_SINGLE_INSTANCE_LANE_MISMATCH`。
- evidence: Worker 于 2026-07-17 13:44:46 +08:00 启动，app-server child 于 14:07:50 创建，当时没有任务 journal；失败任务分别于 14:38:51、14:39:47、14:58:13 到达。匿名 lane 对比确认仅 auth fingerprint 不同，CLI、base URL、`CODEX_HOME` 与进程环境一致。
- approved_direction: repository owner 于 2026-07-17 明确批准修复并验证。额度/用量读取属于只读控制面观察，不得创建 app-server child、不得决定或锁定 startup lane；无同 lane 健康 resident child 时返回 `RATE_LIMITS_SOURCE_UNAVAILABLE`。首个真实任务仍负责建立 canonical lane，异 lane 任务继续 fail-closed 且不得替换健康 child。
- regression_obligation: Worker pool 单测必须覆盖 pre-task quota read 不调用 runtime factory；Navigator E2E 必须在首个任务前调用额度接口并确认 pool 仍为零实例，随后真实任务正常建立唯一 child。既有 resident-lane quota cache、invalidation、crash、drain 与异 lane 拒绝测试不得退化。
- non_goals: 不修改 Java modelConfig、不修改已安装 Worker `.env`、不弱化 lane mismatch 安全边界、不允许额度查询临时切换或替换 child。

## Implementation Result

- implementation_summary: `AppServerRuntime` 改为每个 active turn 独立 context，并按 `threadId`/`turnId`/JSON-RPC request id 路由 notification、terminal、abort 与 `request_user_input`；共享 transport fatal 对所有相关 context fail-closed。`AppServerPool` 改为固定单 child、同 lane 共享 lease、异 lane 稳定拒绝，且以 active lease 计数保护 crash/retire/drain。`AppServerExecutor` 保留同 Thread 全任务 keyed lock，并为共享 PID 的单任务终止增加拒绝边界。2026-07-16 部署后复现进一步发现默认 rate-limit 查询遗漏 Worker 配置的 API key/base URL，会抢先创建错误 startup lane 的唯一 child；`0.3.18` 已让默认限额查询与任务执行使用同一 canonical lane，并将确定性的 pre-turn lane mismatch 以稳定错误码单次终止，避免 `PROCESS_UNVERIFIED` 每秒恢复放大。随后新增项目内 Navigator 公共 HTTP API 全链路 E2E；该 E2E 实际发现 Java 未接受 Worker 权威 `abort_status=aborted` 回执，会把已终止任务错误落入 `TERMINATION_UNCONFIRMED`，现已按稳定回执枚举修复并保持未知值 fail-closed。2026-07-17 运行态再次证明，即使默认限额 lane 字段完整，只读额度查询仍可能在首个任务前使用 Worker 默认 KEY 创建并锁定唯一 child；`0.3.21` 因此彻底禁止 rate-limit 控制面读取创建 runtime，只有真实任务能够建立 canonical startup lane。
- changed_paths: `tools/codex-app-server-worker/src/app-server/runtime.ts`; `src/app-server/pool.ts`; `src/app-server/executor.ts`; `src/task-manager.ts`; `src/config.ts`; `src/runtime-capabilities.ts`; `src/version.ts`; `package.json`; `package-lock.json`; `.env.example`; `README.md`; `scripts/run-navigator-e2e.sh`; `tests/app-server-runtime.test.ts`; `tests/app-server-pool.test.ts`; `tests/executor-concurrency.test.ts`; `tests/managed-process-snapshot.test.ts`; `tests/rate-limits-pool.test.ts`; `tests/rate-limits-executor.test.ts`; `tests/reconciliation.test.ts`; `tests/config-instance.test.ts`; `tests/helpers.ts`; `tools/mock-llm-service/README.md`; `src/mock_llm/routes/openai.py`; `src/mock_llm/store/script_store.py`; `tests/test_openai_api.py`; `launcher/src/test/java/com/foggy/navigator/launcher/CodexAppServerNavigatorE2ETest.java`; `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java`; `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexTaskServiceTest.java`; 本 canonical workitem。
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
  - mock Responses 红/绿回归：新增测试在实现前对 `POST /v1/responses` 得到 HTTP 404；实现后 `cd tools/mock-llm-service && .venv/bin/pytest -q tests/test_openai_api.py -k 'responses_api'`，exit 0，2 passed、17 deselected。完整 `cd tools/mock-llm-service && .venv/bin/pytest -q`，exit 0，29 passed、1 个 Pydantic deprecation warning。
  - 固定 CLI 真实协议探针：repo-local Worker 使用 `@openai/codex 0.144.3`、隔离 `CODEX_HOME` 和 mock `/v1/responses` 完成 function call → `function_call_output` → final response round trip；未使用 SDK fallback。
  - Java 取消确认红/绿回归：Navigator 全链路首次暴露 Worker 已返回权威 `abort_status=aborted`、目标 turn 已 ABORTED，但 Java 仍记录 `TERMINATION_UNCONFIRMED`。新增 `CodexTaskServiceTest#abortTaskAcceptsAppServerAuthoritativeAbortedResponse` 在修复前失败；修复后定向命令 `mvn -pl addons/codex-worker-agent -am -Dtest=CodexTaskServiceTest#abortTaskAcceptsAppServerAuthoritativeAbortedResponse -Dsurefire.failIfNoSpecifiedTests=false test`，exit 0，1/1 passed。完整 `CodexTaskServiceTest` 同形式执行，exit 0，85/85 passed，BUILD SUCCESS。
  - Navigator 公共 API E2E：`cd tools/codex-app-server-worker && npm run test:e2e:navigator`，exit 0；runner 实际启动隔离 mock LLM、repo-local Worker 0.3.18、固定 CLI 0.144.3 与 Spring Boot/H2，通过 `/api/v1/auth/login`、`POST /api/v1/tasks`、resume、cancel、respond 和 rate-limit route 完成 1 个聚合 E2E（Failures 0、Errors 0、Skipped 0，Maven BUILD SUCCESS）。覆盖不同 Thread 重叠、同 Thread 拒绝重叠后续接、targeted cancel、并发 user-input、异 lane 拒绝后原 lane 连续可用及 rate-limit refresh。
  - Navigator E2E 证据：`temp/test-artifacts/bug007-navigator-e2e/20260716T143608Z/` 保存 Maven、mock、Worker、build 日志、Surefire XML/TXT 和前后 health。结束 health 为 `ready=true`、`version=0.3.18`、`active_tasks=0`、pool `instances=1, idle=1, created_total=1, reused_total=8, rejected_total=1, retired_total=0, crashes_total=0`；runner 退出后 13062/18200 无 listener。
  - `0.3.19` 提交前复跑：`cd tools/codex-app-server-worker && npm run test:e2e:navigator`，exit 0；固定 CLI 0.144.3，BUG-007 聚合 E2E 通过、0 failures/errors/skips，Maven reactor `BUILD SUCCESS`，证据目录 `temp/test-artifacts/bug007-navigator-e2e/20260717T051217Z/`。该次仍使用 repo-local 隔离 Worker/CODEX_HOME/mock/H2，不接触已安装 3071 实例；同一 dirty worktree 中另有独立 BUG-010 test 随 Maven 一并执行，但未纳入本项提交。
  - E2E 扩展后的 Worker 完整回归：`cd tools/codex-app-server-worker && npm test`，exit 0，302 tests、301 passed、1 skipped、0 failed，duration 117484.901103 ms。定向 crash/drain 命令再次 exit 0，3 passed、59 skipped；`npm run typecheck` 与 `npm run build` 均 exit 0。
  - E2E 调试过程未伪造为通过：先后修正 mock Responses 404、Java HttpClient h2c/uvicorn 422、过早 cancel、不可可靠中断的延迟 response、Worker token、并发采样竞态、Worker 注册瞬时 404、Maven `-rf` 复用陈旧 snapshot，以及 numeric request id 被测试错误编码为 string 后由生产代码以 RX code 600 fail-closed。正式 runner 始终使用完整 `mvn -pl launcher -am` reactor。
  - 外部 dev Navigator + 真实供应商 smoke 未伪造为本次执行：本次新增自动化使用项目内 Java/H2 和 mock LLM，不写入或读取真实 KEY；它证明 Navigator 后端链路和生命周期隔离，但不证明外部供应商的工具丢失已经消失。
  - `0.3.21` rate-limit 红测：`node --import tsx --test tests/rate-limits-pool.test.ts`，生产修复前 exit 1，2 个新增用例分别观察到 `AVAILABLE` / `LIMIT_REACHED`，证明额度读取仍调用 runtime factory 并建立 child。
  - `0.3.21` 定向回归：`node --import tsx --test tests/rate-limits-pool.test.ts tests/rate-limits-executor.test.ts tests/app-server-pool.test.ts`，exit 0，27/27 passed；覆盖空池额度读取不创建、真实任务建 lane 后复用、异 lane 不替换 resident，以及既有 crash/drain/single-child 语义。
  - `0.3.21` Worker 全量测试：`npm test`，exit 0，312 tests、311 passed、1 skipped、0 failed，duration 120567 ms；`npm run typecheck`、`npm run build` 与 `git diff --check` 均 exit 0。
  - `0.3.21` Navigator E2E：第一次 `npm run test:e2e:navigator` 因 runner 仍硬编码要求 0.3.19 而在功能测试前 exit 1，未伪造为通过；版本护栏对齐 0.3.21 后重跑 exit 0，2/2 passed、Failures 0、Errors 0、Skipped 0，Maven reactor `BUILD SUCCESS`，证据目录 `temp/test-artifacts/bug007-navigator-e2e/20260717T072310Z/`。新增前置断言确认 rate-limit refresh 后 pool 仍为零实例，随后真实任务建立唯一 child并完成原有并发、同 Thread、abort、user-input、异 lane 与 continuity 场景。
  - `0.3.21` 发布包：`npm run package:release`，exit 0；312 tests、311 passed、1 skipped，schema digest `6f2550bb528581f17c4c3a3857dca92c860406aa3274e314cfa726c32e395d8f`，typecheck/build 通过；产物 `release/output/codex-app-server-worker-0.3.21.zip`，SHA-256 `0407e15173d2dcf6da8505ce3c6ff6189cd1b0566759829a91303c3bd7b08da9`。
  - `0.3.21` 本机更新：`bash "$HOME/.codex-app-server-worker/update.sh" --package "/home/sa/workspace/Foggy-Navigator/tools/codex-app-server-worker/release/output/codex-app-server-worker-0.3.21.zip" --install-dir "$HOME/.codex-app-server-worker"`，exit 0；安装器再次通过 312 tests、schema/typecheck/build，优雅停止 0.3.20 并报告配置/状态保留。外层执行环境在 updater 返回时清理其后台后代，留下已停止 PID 2217554 的 lifecycle snapshot；精确绑定当前 `dist/index.js` 的 `process-tree verify` 返回 `clean,count=0`，runtime evidence 为空后，仅解除 snapshot、PID 和固定码 `existing_worker_identity_evidence` latch，再由官方 `start.sh --no-build` 在独立 tmux 中启动。
  - `0.3.21` 部署后控制面 smoke：`/health` 为 ready、version `0.3.21`、CLI `0.144.3` compatible；rate-limit refresh 携带当前 instance affinity 返回 `UNKNOWN / RATE_LIMITS_SOURCE_UNAVAILABLE`，调用前后 pool 均为 `instances=0, lanes=0, created_total=0`，进程树只有 Worker、没有 app-server child。`.env` 更新前后 SHA-256 均为 `4c3c377cb0a6279bcfb421d0ae0d980c230ab691cead49511ec826c7528408c9`，无 lifecycle/update/stop failure latch。
- manual_or_experience_evidence: owner 先前已授权并完成保留 lifecycle evidence 的精确身份恢复，将本地安装更新并启动为 0.3.18，并完成真实凭据 Worker smoke。本次扩展又通过项目内 Java 的公开 Navigator HTTP API 完成可重复 mock E2E，并实际发现、修复 Java 对 Worker 权威 abort 回执的兼容缺陷。2026-07-17 已将本地安装更新并启动为 0.3.21，未修改安装目录 `.env`；部署后只执行无 LLM 请求的空池额度控制面 smoke，真实 Navigator 首任务和续接体验等待 owner 手工验证。未启动 Claude Worker，因为它不在 Codex App Server 执行链路中。
- deviations: 实现保持新批准的单 child、多 Thread 并发、同 Thread 串行和异 lane 拒绝边界。自动化命令所属执行环境会在命令返回时清理其后代进程；本次 updater 首次启动的 0.3.21 也因此被清理，精确 process-tree verify 返回 `clean,count=0` 后仅移除对应 stale evidence，最终仍由官方 `start.sh --no-build` 完成启动并放入既有独立 tmux 托管方式。state identity 继续由脚本生成，未手工篡改 identity。公开官方文档未提供跨版本并发保证，结论仅绑定固定 CLI 0.144.3 的真实源码与协议测试。没有增加自动化真实供应商 profile：owner 已允许采用 mock 方案，真实凭据验证继续作为人工受控步骤；默认 runner 明确清除继承凭据。
- residual_risks: CLI 升级前必须重新验证 keyed request serialization 与 affinity；单 child crash/transport fatal 会影响所有共享 Thread；resident child 存活时不同 startup lane 会按设计拒绝；已安装 Worker 默认 `OPENAI_API_KEY` 与当前 Java modelConfig LLM KEY 的匿名指纹仍不同，0.3.21 已保证额度控制面不会用默认 KEY 抢占 child，但首个真实任务建立 lane 后，后续携带另一 KEY 的任务仍会按设计拒绝，配置一致性仍应由 owner 后续确认；旧任务 `20260716-e492` 仍以非活动 `PROCESS_UNVERIFIED` 保留并需要独立业务决策；本地 mock E2E 不覆盖真实供应商的工具注册/可用性，也不覆盖浏览器错误卡片的视觉渲染，因此仍不能仅凭该测试宣称部署后的 Navigator 工具丢失彻底消失；部署后的 Worker instance ID 可能变化，依赖旧 instance affinity 的上游记录必须按现有注册/重绑定机制处理。
- readiness: READY_FOR_SIGNOFF；未自行标记 `ACCEPTED`。

## References

- related work items: `docs/version-tracker/1.4.0-SNAPSHOT/workitems/BUG-022-app-server-unexpected-image-generation-tool-loss.md`
- implementation: `tools/codex-app-server-worker/src/app-server/runtime.ts`; `pool.ts`; `executor.ts`
- fixed upstream source: OpenAI Codex tag `rust-v0.144.3`, commit `78ad6e6bfd1d3b6a209acd3ef82172a96b25179c`
