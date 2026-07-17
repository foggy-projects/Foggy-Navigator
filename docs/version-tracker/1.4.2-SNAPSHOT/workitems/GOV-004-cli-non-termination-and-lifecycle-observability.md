---
type: governance
version: 1.4.2-SNAPSHOT
ticket: GOV-004
priority: critical
status: verification-blocked
source: user-confirmed-runtime-policy-2026-07-16
owner: platform-owner | worker-owner | provider-owner
acceptance_status: blocked
acceptance_decision: verification-blocked
production_routing_changed: no
external_enablement: no
---

# CLI 非主动终止与运行生命周期可观测性治理

## 文档作用

- doc_type: workitem
- intended_for: project-root-session | reviewer | signoff-owner
- purpose: 冻结 Java 控制面与 Claude、Codex SDK、Codex app-server Worker 的 CLI 非主动终止原则，并规划统一的生命周期诊断与审计闭环。

## 文档关联

- 版本索引：[1.4.2-SNAPSHOT](../README.md)
- 实施阶段：[P2 外部 Biz Worker 与 upstream user 边界治理](../implementation-plan.md)
- 上位边界：[GOV-001 内外部信任边界](./GOV-001-internal-external-trust-boundary.md)
- 相关任务治理：[GOV-002 Biz Worker、ClientApp 与 upstream user 边界](./GOV-002-biz-worker-and-upstream-user-boundary.md)、[GOV-003 Session/Task 资源归属](./GOV-003-session-task-resource-ownership.md)
- 状态与证据：[Progress](../progress.md)、[Code Inventory](../code-inventory.md)、[Module Responsibility](../module-responsibility.md)
- 本工作项质量/覆盖/验收：[Implementation Quality](../quality/GOV-004-cli-non-termination-and-lifecycle-observability-implementation-quality.md)、[Coverage Audit](../coverage/GOV-004-cli-non-termination-and-lifecycle-observability-coverage-audit.md)、[Acceptance](../acceptance/GOV-004-cli-non-termination-and-lifecycle-observability-acceptance.md)
- 迁移、回滚与安全操作：[GOV-004 Runbook](../runbooks/GOV-004-cli-non-termination-and-lifecycle-observability-runbook.md)

## 当前状态

| 项目 | 状态 | 说明 |
|---|---|---|
| Workitem | verification-blocked | Java 控制面与三类 Worker 的实现和本地针对性自动化已完成；正式验收被真实 CLI、目标环境和告警证据阻断，绝非 accepted。 |
| Incident input | confirmed-local-diagnostic | `2026-07-16T03:40:13.655Z`（北京时间 `11:40:13.655`）的 `CODEX_WORKER_REMOTE_ERROR` 与 Codex SDK Worker watchdog 误判链路相符：其在未可靠发现 CLI PID/线程关联时调用任务 abort；现有证据未显示 OOM、内核 kill 或人工 PID-kill 请求。该结论是后续实现的定位输入，不替代修复后运行态验证。 |
| Development | implementation-complete-local | Java `TerminationOperation` DB 审计账本、signed capability、三条 Worker durable receipt ledger、自动 attention 和观察后终态已落地。 |
| Testing | passed-local | Codex SDK 100、Codex app-server 30、Claude 16 的 operation/replay 矩阵通过；SDK 全量 207 passed/1 skipped + typecheck、app-server 全量 292 passed/1 skipped + typecheck、Claude 全量 542 passed/11 skipped；Java relevant reactor `BUILD SUCCESS`。隔离 Docker MySQL forward/index/assert/rollback 通过。 |
| Experience | verification-blocked | 尚未在真实隔离 CLI 中核对上游可见的待决策、显式取消、审计查询与五种退出来源。 |
| Production routing / external contract | unchanged | `production_routing_changed: no`、`external_enablement: no`；新增字段保持兼容，未启用自动 kill 或任何外部运行时。 |

## 已实施收口与证据边界（2026-07-16）

## 本机 Codex SDK Worker 修复部署（2026-07-17）

- 针对本机已安装 SDK Worker 执行 `npm run package:release -- --platform all --smoke full`。结果为 208 tests：207 passed、1 skipped、0 failed；`typecheck`、`build` 和归档候选 `/health` smoke 均通过。Linux 归档 `codex-worker-1.0.16-linux.tar.gz` 的 SHA-256 为 `1faff3d591baf7c42c63ebc4d950e8eea23ec710b8600ae4dd33e38fdf812a03`。
- 使用归档内 `install.sh --upgrade` 安装到 `~/.codex-worker`，安装器已备份并恢复既有 `.env`，保留 `logs/`（包括终止 operation ledger）。既有本机配置端口为 3053；安装后在该端口启动，`/health` 返回 `status=ok`、`ready=true`、`version=1.0.16`、`active_tasks=0`、Codex SDK `0.144.1` compatible。未执行模型请求或任何外部/生产路由操作。
- 原先运行在 3051 的当前仓库开发 Worker 已按其安全 quiescence gate 重启，`/health` 同样返回 `ready=true`、`version=1.0.16`、`active_tasks=0` 和 compatible SDK；它与已安装的 3053 Worker 保持原有的独立端口配置。
- 已检查已安装的 `dist/codex/thread-process-watchdog.js`：其记录 `reconciled lifecycle observations` 和 `PROCESS_UNVERIFIED`，不含 watchdog `abortTask(...)` 调用。此前日志中原生任务 `8893e658-3ba5-47aa-b459-625925d328ce` 的旧版 `reconciled stale execution state` / 自动 abort 记录是本次修复的直接本机证据。
- 历史 Navigator 任务 `20260717-d30f` 没有可用的原生 Worker 终态记录；本次部署不得把它伪造为 `ABORTED`。其最终展示状态仍需由具备任务审计权限的控制面按对账流程单独裁定。本机部署不改变 `verification-blocked`、不构成真实 CLI 五态或生产验收。

## Java 404 终止结果分类修复（2026-07-17）

- 现场任务 `20260717-d03c` 在显式取消后仍显示 `RUNNING / TERMINATION_REJECTED`。控制面绑定的 Worker 原生任务为 `8893e658-3ba5-47aa-b459-625925d328ce`；该原生任务来自 Worker 重启前的旧 registry，当前 Worker abort 路由返回 `404 TASK_NOT_FOUND`。取消接口仍返回 HTTP 200 和 `Task cancelled`，但随后查询可见任务被恢复为 `RUNNING`，因此该成功文案不能作为已取消或已退出证据。
- 根因是 Java 将除 408/429 外的全部 Worker 4xx 都归类为 definitive rejection。对 404 而言，这只能证明当前路由到的 Worker 没有对应原生任务状态，可能来自重启、路由漂移或内存 registry 丢失；它不能证明 CLI 已退出，也不是鉴权或 capability 契约拒绝。
- 修复后 404 进入未确认分支：任务保持 `CANCEL_REQUESTED`，attention/error 为 `TERMINATION_UNCONFIRMED`，operation 标记为 unconfirmed，等待可关联的 Provider 终态或人工对账；不得恢复 `RUNNING`，也不得伪造 `ABORTED`。400/401/403 等明确的请求、鉴权或 capability 拒绝仍保留 `TERMINATION_REJECTED` 并恢复请求前状态。
- 回归先在旧实现上复现 `expected CANCEL_REQUESTED but was RUNNING`，修复后 404 未确认与 403 明确拒绝两条定向测试均通过；完整 `CodexTaskServiceTest` 为 90 tests、0 failure/error/skip，`mvn -pl addons/codex-worker-agent -am -Dsurefire.failIfNoSpecifiedTests=false test` 为 `BUILD SUCCESS`，Codex addon 400 tests、0 failure/error/skip。该修复属于 Java 控制面，不需要重新发布未变化的 Codex Worker；目标环境必须部署/重启 Java 后，现场行为才会改变。

### `dev-kvm-jdk17-2` 部署与现场复测

- `2026-07-17` 将目标机 `/home/sa/Foggy-Navigator` 从 `b07ad012` fast-forward 到 `2b6ff748`；仅保留目标机既有未跟踪 `.codex/`，源码 worktree 无其他脏改动。目标机使用 JDK 17 与 Maven 3.9.9 执行 `mvn package -pl launcher -am -DskipTests`，结果为 `BUILD SUCCESS`；生成 Jar 的 `git.branch=main`、`git.commit.id.abbrev=2b6ff74`、`git.dirty=false`。
- 已确认旧 Java PID `3917196` 的 cwd 为 `/home/sa/Foggy-Navigator`，先发送 `SIGTERM` 并在 2 秒内观察到退出，再通过仓库 `scripts/start-launcher.sh --skip-build` 启动新 PID `4013667`。重启未停止或更新任何 Codex Worker；`http://127.0.0.1:8112/actuator/health` 返回 application 与 DB `UP`。
- 修复部署前，`GET /api/v1/tasks/20260717-d03c` 为 `RUNNING / TERMINATION_REJECTED`。部署后再次调用显式取消，接口返回 HTTP 200 与 `Task cancelled`，任务立即变为 `CANCEL_REQUESTED / TERMINATION_UNCONFIRMED`；在 0、2、5、10 秒及后续复查中均保持该状态，没有恢复 `RUNNING`，也没有伪造 `ABORTED`。
- 重启前正在活动的 app-server 任务 `20260717-508a` 在重启后继续为 `RUNNING` 且 `updatedAt` 继续推进，说明本次 Java 部署没有把该 Worker 任务改成终态。以上只证明该 404 取消分类缺陷在目标环境修复，不解除 GOV-004 的真实 CLI 五态、目标 DB migration/rollback、告警送达和多实例 replay 验收 blocker。

### 已实施行为

1. Java 在派发任何显式终止前以独立事务写入 `TerminationOperation v1`。记录 operation ID、task/session/provider task、owner、worker、kind/origin、actor、授权决定、reason、correlation、预期 PID/process identity、dispatch 与 observed result；查询接口按 task owner 过滤。`CANCEL_REQUESTED` 或 Worker ACK 仅说明请求已接受，不能把任务改为 `ABORTED`。
2. Claude、Codex SDK 和 Codex app-server 只接受目标 task、stable Navigator PhysicalWorker ID、kind/origin、时效、签名、operation ID 与（manual PID 时）PID/process identity 都精确匹配的 signed operation。自动 timeout、watchdog、PID/thread 不匹配、stream 断开、扫描异常、stall、drain 或 close 不具备签发 operation 的身份，只能记录 attention/diagnostics。
3. 三个 Worker 都在实际副作用前消耗 durable receipt。receipt key 是 `SHA-256(worker_id + NUL + operation_id)`，独占创建和 fsync 后才 dispatch；重放为 `409`，损坏、满、I/O 或配置不可用为 fail-closed `503`。receipt 仅含 worker/operation/expiry 元数据，绝不持久 capability、签名、prompt、token 或 credential。
4. Codex SDK ledger 使用 `CODEX_TERMINATION_OPERATION_LEDGER_DIR`（覆盖值必须为绝对路径，默认 `logs/termination-operations/`）；Claude 使用 `AGENT_WORKER_TERMINATION_OPERATION_LEDGER_DIR`（同样要求绝对路径，默认 `logs/termination-operations/`）；Codex app-server 使用 `${CODEX_APP_SERVER_STATE_DIR}/termination-operations/receipts`（state dir 默认 `logs/state/`）。三个目录必须位于私有、持久、重启后复用的存储上。
5. Java 正向 migration [2026-07-16-termination-operations.sql](../../../migration/2026-07-16-termination-operations.sql) 创建 `termination_operations` 和审计查询索引；[rollback](../../../migration/2026-07-16-termination-operations-rollback.sql) 是需先停止新请求、保留/导出审计记录并经批准的破坏性 table drop。Worker receipt 是文件账本，不参与 SQL rollback；回退 Worker 代码时必须保留 receipt 目录，旧版本忽略它会降低 replay 防护。

### 多实例/物理 Worker 限制

Worker receipt 是本地防重放围栏而不是分布式 ledger。它只在同一 stable PhysicalWorker ID 的所有进程共享同一个持久 receipt 卷时，才能对这些进程提供原子重放保护。不得在两个独立主机、容器 writable layer 或不同本地卷上复用同一 `navigatorWorkerId` / Worker secret，并假定 Java DB operation 行会替代 Worker receipt。横向扩展须采用以下任一模型，并另行运行验证：

1. 每个物理 Worker 使用独立 stable ID 和精确路由；
2. 同一物理 Worker 的所有副本共享经过 durability/locking 评审的原子 claim 存储；或
3. 用集中式、可用性和故障语义均已验证的 receipt/operation claim 服务替代本地 ledger。

尚未完成上述部署证明，因此这项限制本身是正式验收 blocker，不是可忽略的性能说明。

### 本地自动化与隔离证据

| 证据 | 结果 | 范围边界 |
|---|---|---|
| Codex SDK operation/replay 定向矩阵 | 100 passed | 覆盖 signed cancel/manual PID、错 binding、replay、restart/corrupt/full ledger 与 lifecycle 语义；不是真实 CLI 五态。 |
| Codex app-server operation/replay 定向矩阵 | 30 passed | 覆盖 state-dir receipt、route/task contract、手工 PID 观察和 replay；safe init/idle-close smoke 使用 `codex-cli 0.144.4` 且没有模型请求。 |
| Claude operation/replay 定向矩阵 | 16 passed | 覆盖 capability、receipt、restart 和 fail-closed；当前环境没有 Claude CLI。 |
| Java final reactor | `mvn -pl addons/codex-worker-agent,addons/claude-worker-agent -am -Dsurefire.failIfNoSpecifiedTests=false test` 为 `BUILD SUCCESS` | 这是本地 Java 回归；真实目标部署、真实 CLI、告警和生产批准不在命令范围。 |
| `CodexTaskServiceTest` | 90 tests，0 failure/error/skip | 覆盖 Java service 的 operation/终态关联回归，包括 Worker 404 保持 `CANCEL_REQUESTED / TERMINATION_UNCONFIRMED` 与 403 明确拒绝恢复原状态；不是跨 Worker 运行态观察。 |
| Codex Worker Agent reactor（2026-07-17） | `mvn -pl addons/codex-worker-agent -am -Dsurefire.failIfNoSpecifiedTests=false test` 为 `BUILD SUCCESS`；Codex addon 400 tests，0 failure/error/skip | 覆盖本次 Java 修复及其依赖模块本地回归；目标环境部署与现场复测仍是独立步骤。 |
| Isolated Docker MySQL migration | forward、索引/结构断言、rollback 均通过 | 未连接目标数据库，未运行目标环境 `ddl-auto=validate`。 |

### 尚未得到、且必须补齐的证据

- 真实隔离 CLI 的五态：自然完成、CLI 自身异常退出、授权显式取消、授权人工 PID kill、未确认/超时待决策；每态均需采集 status/SSE、attention、operation audit、实际 PID/Provider 结果和脱敏日志。
- 目标环境 DB 的 forward migration、启动 `ddl-auto=validate`、查询/索引检查与受批准 rollback 演练。
- attention、unconfirmed operation、receipt unavailable/full/replay、migration failure、stale operation 的告警规则部署、触发、路由和送达证据。
- 多实例/重启/故障切换下同一 worker identity、receipt volume 与路由的一致性演练。

## 已确认原则

1. Java 控制面、Claude Worker、Codex SDK Worker、Codex app-server Worker 均不得因为超时、心跳缺失、SSE 中断、PID/线程匹配失败、重试耗尽、watchdog 扫描异常或本地诊断失败而主动终止 CLI。
2. 上述自动情形最多在会话/任务上写入可恢复的关注标记，例如 `TIMEOUT_PENDING_DECISION` 或 `PROCESS_UNVERIFIED`，并保留证据；是否中止由上游或用户自行决定。
3. 唯一允许请求 CLI 终止的业务路径是经过认证、授权且可审计的显式上游/用户取消。低层 PID kill 仅可作为显式人工运维动作，不能被 watchdog、超时器或异常补偿自动调用。
4. 任何显式终止都必须记录发起来源、授权主体、任务/会话/线程/进程关联、请求时间、原因、实际执行结果与失败原因；不得只记录“task aborted”。
5. 观测链路失败不得反向改变执行结论：无法确认 CLI 是否存活时，状态为“未确认/待决策”，而不是推断“已退出”并中止任务或释放会话所有权。

## 实施前审查结论与冻结的 v1 契约

审查发现原计划缺少活动执行、空闲运行时和 Worker 排空之间的硬边界，也没有能够防止来源伪造或把“取消已请求”误记为“已终止”的协议。以下规则自本工作项实施开始生效；它们是实现与验收的共同依据，而不是待补充建议。

### 生命周期边界

| 运行状态 | 自动动作 | 禁止动作 | 可推进条件 |
|---|---|---|---|
| `ACTIVE_TASK_EXECUTION` | 记录 activity、attention、诊断和排空意图；保持 task/PID/thread/reservation 关联 | 任何 `abort`、`turn/interrupt`、`runtime.close`、`SIGTERM`、`SIGKILL`、terminal/release | 只由自然/Provider 终态或带有效 operation 的实际退出推进 |
| `IDLE_RUNTIME` | 在已证明没有 `RUNNING`、`CANCEL_REQUESTED` 或 `PROCESS_UNVERIFIED` 绑定时回收 runtime | 不得以“预估空闲”回收 busy lane | 持久化/内存任务绑定均为空，并经过一次一致性检查 |
| `WORKER_DRAINING` / deploy | 停止接收新任务、写入 `WORKER_DRAINING_PENDING_DECISION` 并暴露诊断 | 不得为了关停、升级、pool drain 或 watchdog 关闭活动 CLI | 所有活动任务已观察到终态，或操作员另行发起受控终止 operation |

`git`、`npm`、process-tree 等与受管任务 CLI 无关的短命辅助子进程不在本工作项的受管 CLI 范围内；矩阵必须显式标注，不能以它们的合理清理掩盖任务 CLI 的自动终止。

### 受控终止 operation

1. Java 在完成资源归属与调用者授权后，先以独立事务持久化 `TerminationOperation v1`，再向 Worker 派发一次性 capability；审计写入失败时不得派发。
2. operation 至少包含 `operationId`、task/session/execution 关联、`kind`（`REMOTE_CANCEL` 或 `MANUAL_PID_KILL`）、`origin`、actor、授权决策 ID、reason、correlation ID、预期进程身份、请求/过期时间、请求结果和实际退出结果。
3. capability 必须由 Worker 既有私有凭据签名，绑定目标 Worker、任务/进程、operation ID 与短时效；Worker 拒绝缺失、签名错误、过期、错任务/进程和重放的请求。请求体或 HTTP header 自报的 `origin` 不是信任来源。
4. `UPSTREAM_SYSTEM` 仅表示有具体授权决策 ID 的显式上游业务动作；timer、reconciler、watchdog、重试器和部署流程永远不能使用该来源。`ADMIN_MANUAL` 仅可用于人工 PID kill。

### 状态、关注与兼容性

- 执行状态使用 `ACCEPTED`、`RUNNING`、`CANCEL_REQUESTED`、`COMPLETED`、`FAILED`、`ABORTED`。`CANCEL_REQUESTED` 和 Worker 的 abort ACK 都不是终态；只有观察到 Provider terminal event 或校验过的进程退出才能置 `ABORTED` 并释放 ownership/reservation。
- `attention[]` 独立承载 `TIMEOUT_PENDING_DECISION`、`PROCESS_UNVERIFIED`、`WORKER_DRAINING_PENDING_DECISION`、`TERMINATION_UNCONFIRMED` 等可恢复状态；自动来源只能新增/更新 attention。
- 新 SSE/status v2 字段为可选的 `attention`、`termination_operation` 摘要和 `available_actions=[CONTINUE_WAIT,QUERY_DIAGNOSTICS,CANCEL]`。旧调用方继续读取既有 `RUNNING`/terminal 字段，不能因新增 attention 被伪造为失败或中止。

### 初始终止路径矩阵（2026-07-16）

| 执行面 | 路径 | 当前分类 | 收敛目标 |
|---|---|---|---|
| Codex SDK Worker | `thread-process-watchdog` PID 缺失 grace 后 `abortTask` + release | 禁止的自动终止 | 改为 `PROCESS_UNVERIFIED`，保留 reservation/关联 |
| Codex SDK Worker | `sdk-wrapper` max turns `AbortController.abort` | 禁止的自动终止 | 改为 `TIMEOUT_PENDING_DECISION`，不向 SDK 传 abort signal |
| Codex SDK Worker | task abort route | 允许但无 provenance | 仅接受 signed `REMOTE_CANCEL` operation；ACK 为 `CANCEL_REQUESTED` |
| Codex SDK Worker | process kill route | 允许但无 provenance，且会隐式 abort task | 仅接受 signed `ADMIN_MANUAL` operation；以观察结果更新 task |
| Codex app-server Worker | stall/unexpected-image `turn/interrupt` | 禁止的自动终止 | attention + 可恢复诊断，不 interrupt |
| Codex app-server Worker | pool drain / runtime close 的 SIGTERM/SIGKILL | 活动 lane 上禁止 | 仅回收确定 idle lane；draining 任务保持 pending-decision |
| Claude Worker | query abort / process kill route | 允许但无 provenance | 仅接受 signed operation；不把请求 ACK 记为终态 |
| Claude Java reconciler | missing CLI / Worker status 查询失败后 complete | 禁止的推断终态 | 记录 `PROCESS_UNVERIFIED`，保持活跃 ownership |
| Codex / Claude Java | remote abort 失败仍记 aborted / abort 后立即终态 | 禁止的推断终态 | 写 `CANCEL_REQUESTED` / `TERMINATION_UNCONFIRMED`，等待观察结果 |
| 辅助进程 | git/npm/process-tree cleanup | 非受管任务 CLI | 保留其原有资源清理，矩阵/测试单独注明 |

## 问题与背景

本地 Codex SDK Worker 日志显示，发生于 `2026-07-16T03:40:13.655Z` 的错误与 watchdog 触发的 stale execution reconciliation 同时出现。现有实现中，`thread.runStreamed()` 返回惰性事件流后立即探测 CLI PID；CLI 实际可能在首次迭代事件流时才启动，导致 PID 尚不可见。随后 watchdog 又无法依靠新命令行可靠匹配 thread id，超过 grace window 后调用 `abortTask`。该调用虽未直接执行 `process.kill`，但 abort signal 会传入 SDK 子进程创建链，因此具有实际终止仍在运行 CLI 的风险。

现有 Codex Worker 还保留显式 `POST /api/v1/processes/{pid}/kill` 的低层运维入口；Java 控制面可转发该显式调用，也可转发任务 abort。当前缺少能够区分“用户/上游显式取消”“人工运维 PID kill”“Worker 自动 abort”“CLI 自然退出/崩溃”的统一审计字段与日志关联。因此，现有 `Codex CLI process disappeared: no matching process` 不能可靠回答 CLI 是自行退出还是被本系统中止。

## 目标结果

建立一条不依赖猜测的生命周期链：

`CLI spawn/attach -> stream/event activity -> timeout or process uncertainty marker -> upstream/user decision -> explicit cancel or manual kill -> observed exit -> task terminal result`

目标是让任一任务都能回答以下问题：

1. CLI 是否真实启动、由哪个 Worker 管理、关联到哪个 task/session/thread/PID。
2. CLI 的退出是自然退出、异常退出、显式上游/用户取消、显式人工 PID kill，还是尚未确认。
3. 系统是否曾发起终止请求；若发起，谁以何种授权、在什么时间、通过哪个入口、得到什么执行结果。
4. 超时与进程未确认是否只形成待决策标记，而没有隐式改变 CLI 进程或任务的真实执行状态。

## 范围

- Java 控制面中的 task cancel、remote abort、PID kill 管理入口及其调用审计。
- `tools/claude-agent-worker`、`tools/codex-agent-worker`、`tools/codex-app-server-worker` 的超时、watchdog、流断开、异常恢复、子进程/SDK abort 与 PID kill 路径。
- Worker 到 Java 的任务生命周期、错误事件、超时/未确认标记与结构化审计契约。
- Codex SDK Worker 的 PID/线程发现时序修正，但仅用于观测和关联，不得恢复自动终止行为。
- 统一错误/关注分类、日志字段、测试矩阵、发布检查与历史排障 runbook。

## 非目标

1. 不在本工作项重新设计自动 kill 策略；如未来确有需求，必须以新的需求、风险评估、授权语义和独立验收进入版本计划。
2. 不禁止用户、上游已授权取消或受控人工运维；本事项要求其显式、可审计且与自动路径严格隔离。
3. 不把“超时待决策”伪装为 CLI 已失败，也不自动释放仍可能对应活动 CLI 的资源占用。
4. 不把本地日志诊断直接外推为生产、所有 Provider 或所有版本的既成事实。

## 责任与代码触点

| 责任面 | Owner | 主要路径/触点 | 计划职责 |
|---|---|---|---|
| Java 控制面 | `addons/codex-worker-agent` 与统一 Task/Session owner | `CodexWorkerClient`、`CodexWorkerController`、`CodexTaskService`、`CodexStreamRelay`、任务取消 facade | 仅转发显式取消；为 cancel/kill 记录 `origin`、actor、授权、关联 ID 和结果；不得将超时/流异常转为 remote abort。 |
| Codex SDK Worker | `tools/codex-agent-worker` | `src/codex/thread-process-watchdog.ts`、`sdk-wrapper.ts`、`processes.ts`、`routes/processes.ts`、task abort route | 去除 watchdog 自动 abort/隐式资源释放；在真正 spawn/attach 后记录 PID 关联；输出结构化生命周期和待决策事件。 |
| Codex app-server Worker | `tools/codex-app-server-worker` | runtime/session process、stream/watchdog、task abort/kill routes | 按同一不主动终止规则盘点并收敛；对齐诊断字段和测试。 |
| Claude Worker | `tools/claude-agent-worker` | subprocess、timeout/watchdog、WebSocket/SSE 断开、abort/kill 管理路径 | 按同一规则盘点并收敛；不得因本地守护判断直接结束 Claude CLI。 |
| 协议与可观测性 | 平台/Provider owner | Worker error event、Task/SSE 状态、日志与审计存储 | 定义跨 Worker 可比字段、脱敏规则、兼容策略和查询/告警口径。 |

## 实施阶段

### Step 1：终止路径盘点与临时冻结

1. 在 Java 和三类 Worker 搜索并归类 `process.kill`、`terminate`、`SIGTERM`、`SIGKILL`、`AbortController.abort`、SDK signal、timeout callback、watchdog、stale/reconcile、自动资源释放等路径。
2. 对每条路径标明触发条件、是否可能间接终止 CLI、是否已要求显式调用者、当前日志与测试覆盖。
3. 将非显式路径标记为待移除/改写；在完成前不得新增任何自动 abort/kill 分支。
4. 输出一份单一的路径矩阵，注明 Java、Claude、Codex SDK、Codex app-server 的 owner 和差异。

### Step 2：状态与审计契约冻结

1. 分离执行终态与关注状态：执行终态只使用实际观察到的 `RUNNING`、`COMPLETED`、`FAILED`、`ABORTED`；关注状态可使用 `TIMEOUT_PENDING_DECISION`、`PROCESS_UNVERIFIED`、`CLI_EXITED_UNEXPECTEDLY`。
2. 对每个终止请求强制记录 `terminationOrigin`：`UPSTREAM_USER`、`UPSTREAM_SYSTEM`、`ADMIN_MANUAL`；自动路径不得生成终止请求。若没有受信任来源，拒绝请求并记录拒绝审计。
3. 定义统一生命周期日志字段：`eventName`、`timestamp`、`taskId`、`sessionId`、`threadId`、`workerType`、`workerInstanceId`、`pid`、`processStartIdentity`、`correlationId`、`activityEvidence`、`attentionStatus`、`terminationOrigin`、`actor`、`reasonCode`、`requestedAt`、`observedExit`、`result`。
4. 明确脱敏：不得记录 prompt、API key、credential、完整命令参数中的敏感值或未脱敏环境变量。

### Step 3：Worker 行为收敛与观测补强

1. 将 watchdog 的职责收敛为发现、关联、记录、标记和通知；任何 PID/线程不匹配、扫描失败或超时均不得调用 task abort 或低层 kill。
2. Codex SDK Worker 必须在流实际开始/CLI 已 spawn 后再采集 PID，或采用等价的非侵入关联方式；PID 不可得时输出 `PROCESS_UNVERIFIED`，不据此推断 CLI 消失。
3. Claude、Codex SDK、Codex app-server Worker 对齐 stream 断开、子进程 exit、异常、显式 abort、显式 manual kill 的事件名和最小字段。
4. 自动标记不能释放 PID/thread 关联、任务 reservation 或 session ownership；只有观察到真实退出或收到并记录的显式终止结果后，才能推进执行终态。

### Step 4：Java 转发、审计与上游决策

1. Java 仅在经过认证授权的取消请求中调用 remote abort，并透传不可伪造的 origin、actor、reason 和 correlation id。
2. 保留 PID kill 作为显式人工运维能力时，限制权限、记录成功和失败审计，并返回可关联的操作 ID；不允许 timeout/reconciler 调用该接口。
3. Java 接收 Worker 的 `TIMEOUT_PENDING_DECISION` / `PROCESS_UNVERIFIED` 后，仅更新会话可见状态、日志和通知；不升级为 cancel。
4. 为上游提供明确的继续等待、查询诊断、显式中止三种语义，避免将网络/心跳异常误作为终止命令。

### Step 5：回归、发布与运行态证据

1. 静态负向扫描：自动路径不得抵达 `abort`、SDK signal 或 `process.kill`。
2. 单元/集成：模拟无 PID、晚 spawn、线程不可匹配、watchdog scan error、超时、SSE 断开和 SDK 异常，确认仅产生关注标记与日志。
3. 显式取消与人工 PID kill：验证授权、origin、审计、实际结果和错误传播；验证未经授权或 origin 缺失时拒绝。
4. 真实 CLI smoke：分别确认自然完成、CLI 自身异常退出、用户取消、人工 kill、未确认/超时待决策的生命周期日志可区分。
5. 发布前检查：四个执行面均有版本、配置、日志样例、告警规则和回滚说明；不得仅以单个 Codex Worker 用例宣布跨 Worker 完成。

## 验收标准

| ID | 验收条件 |
|---|---|
| NT-AC-01 | Java 与三类 Worker 中，超时、watchdog、PID/线程不匹配、流断开、扫描错误和重试耗尽均不会直接或间接调用 CLI abort/kill。 |
| NT-AC-02 | 仅显式且已授权的上游/用户取消可请求 remote abort；人工 PID kill 为独立受控操作，二者均带可查询的来源、actor、原因和结果。 |
| NT-AC-03 | Codex SDK Worker 在 CLI 晚 spawn 或 PID 暂不可见时只记录 `PROCESS_UNVERIFIED`，不会产生 `Codex CLI process disappeared` 后自动 abort 的链路。 |
| NT-AC-04 | 超时会话显示/推送 `TIMEOUT_PENDING_DECISION`，上游可选择继续等待、查询诊断或显式中止；未选择中止时 CLI 不被本系统终止。 |
| NT-AC-05 | 日志可关联 task/session/thread/worker/PID/终止来源和真实退出结果，且不泄露 prompt、token、credential 或敏感命令参数。 |
| NT-AC-06 | 自动化覆盖 Java、Claude、Codex SDK、Codex app-server 的正负矩阵；真实 CLI smoke 能区分自然退出、CLI 异常、显式取消、人工 kill 与未确认状态。 |
| NT-AC-07 | 文档、告警和发布说明明确：本版本没有自动 kill 策略；未来若需自动终止必须新建独立决策和验收。 |

## 风险、依赖与回滚

| 类别 | 内容 |
|---|---|
| 主要风险 | 移除自动 abort 后，真实失联 CLI 可能持续占用资源；该风险必须通过可见待决策状态、上游 SLA、告警和后续独立策略设计处理，不能以隐藏 kill 规避。 |
| 兼容风险 | Task/SSE 增加 attention 状态和审计字段时，旧客户端可能只识别执行终态；必须保持旧终态字段语义，新增字段可选且版本化。 |
| 依赖 | 统一 Task/SSE 状态契约、Worker 生命周期事件、Java 授权上下文与日志/审计存储；三类 Worker 的现有实现差异须在 Step 1 实测。 |
| 回滚 | 代码按 Worker/Java 分提交。若新的观测逻辑有回归，可回滚该观测提交，但不得恢复任何未经新需求批准的自动 abort/kill 行为。 |

## 进度与下一门禁

- Development: `implementation-complete-local`；Java operation audit、三条 Worker signed capability/receipt ledger、自动 attention、严格 observed-exit 关联与相应文档已落地。
- Testing: `passed-local-targeted`；SDK 100、app-server 30、Claude 16 operation/replay 定向矩阵通过；SDK 全量 `207 passed / 1 skipped` + typecheck、app-server 全量 `292 passed / 1 skipped` + typecheck、Claude 全量 `542 passed / 11 skipped` 通过；Java 最终 reactor 命令为 `BUILD SUCCESS`，其中 Claude addon 383、Codex addon 384 tests，`CodexTaskServiceTest` 84/0/0/0。全部均是本地自动化，不是 live 五态。
- Experience: `verification-blocked`；尚未验证上游的待决策、显式取消和 audit query 在真实隔离 CLI 中的可见语义。
- Deviations: `none-known`；没有恢复任何自动 abort/kill 策略。app-server receipt 固定随 state dir 保存而非单独环境变量；这已在 runbook 中明确。
- Blockers: `isolated-cli-five-state-matrix-not-run`、`target-environment-migration-not-run`、`alert-deployment-and-delivery-not-evidenced`、`multi-instance-receipt-ledger-boundary-unverified`。当前无 Claude CLI；app-server smoke 无模型请求，均不替代 blocker。
- Next Gate: 按 [runbook](../runbooks/GOV-004-cli-non-termination-and-lifecycle-observability-runbook.md) 在授权隔离环境完成五态、目标 DB forward/validate/rollback、告警 trigger/delivery 和多实例/持久卷 replay 演练，然后独立重跑 [quality](../quality/GOV-004-cli-non-termination-and-lifecycle-observability-implementation-quality.md)、[coverage](../coverage/GOV-004-cli-non-termination-and-lifecycle-observability-coverage-audit.md) 与 [acceptance](../acceptance/GOV-004-cli-non-termination-and-lifecycle-observability-acceptance.md)。
- self_check_decision: `verification-blocked`；不自签 accepted。
- acceptance_readiness: `blocked`；正式验收 status 为 `blocked / verification-blocked`，`accepted_by: none`。
