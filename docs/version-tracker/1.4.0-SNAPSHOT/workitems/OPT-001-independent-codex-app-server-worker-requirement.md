# OPT-001 独立 Codex App Server Worker

## 文档作用

- doc_type: requirement
- intended_for: root-controller | execution-agent | reviewer | release-owner
- purpose: 固化独立 app-server Worker、双 runtime 控制面、Ultra 首批切流和旧 SDK Worker 长期保留的需求与验收边界。

## 基本信息

- version: `1.4.0-SNAPSHOT`
- priority: P0
- status: p0-p2-isolated-accepted-production-rollout-not-started
- source_type: architecture-optimization
- owner: Codex Worker runtime | Codex Java addon | Session | Navigator PC

## 背景

> 2026-07-12 演进说明：本文保留 OPT-001 当时的 Worker/Runtime 实施基线；其中“统一 `OPENAI_CODEX` / `codex-worker` Provider”的前向设计已被 [OPT-005](./OPT-005-codex-app-server-independent-provider.md) 取代。当前 SDK 固定使用 `OPENAI_CODEX` / `codex-worker`，App Server 固定使用 `OPENAI_CODEX_APP_SERVER` / `codex-app-server-worker`，不得互相 fallback。

现有 `tools/codex-agent-worker` 通过 TypeScript SDK 启动一次性 `codex exec`。1.3.1 已验证 app-server 能暴露 Ultra 原生子任务状态，但把 SDK 与 app-server 放入同一 Worker 会同时承担两套执行生命周期、事件映射、版本门控和回退逻辑。

本版本采用独立运行时演进：稳定 Worker 保持现有 SDK lane，新建 app-server Worker 作为 Ultra 和后续可选 cohort 的独立主线。新 Worker 技术上支持全部模型和 reasoning，平台初期只把新 Ultra 会话路由过去；旧 Worker 的 SDK 实现、非 Ultra 行为、版本 preflight 和既有 affinity drain 不因新 Worker 上线而退役。

规划时识别出的 P0 核心问题是：旧 `POST /api/v1/query` 在 Worker 内生成 taskId 并直接打开 SSE，接受响应丢失后 Java 无法安全判断是否重试。该问题现已通过 `POST /api/v1/tasks`、稳定 `Idempotency-Key`、durable acceptance/status 和 immutable runtime binding 关闭；兼容 `/query` 不作为新 Worker 的生产切流主协议。

## 术语

| 术语 | 含义 |
|---|---|
| Provider | Navigator 逻辑执行类型，继续使用 `codex-worker` / `codex-biz-worker` |
| Runtime | Provider 下面的具体执行实现，`SDK_EXEC` 或 `APP_SERVER` |
| Runtime registry | 服务端维护的受控 endpoint、版本、能力、健康和 rollout 信息 |
| Runtime affinity | Task/Session 创建后绑定的 runtime，后续 resume/reconnect/abort/delete 不得重选 |
| App-server instance | 新 Worker 内部常驻的一个 Codex app-server 子进程 |
| Capability manifest | Runtime 对外声明的协议版本、模型、reasoning、功能和事件契约摘要 |
| Execution committed | `turn/start` 已开始提交，之后不得自动重放同一 prompt |

## 已确认目标

1. 创建独立 `tools/codex-app-server-worker`，不依赖 `@openai/codex-sdk`，直接精确固定并启动已验证的 `@openai/codex` CLI。
2. 新 Worker 支持 app-server 当前验证可用的全部模型和 reasoning 档位，包括 `low`、`medium`、`high`、`xhigh`、`max`、`ultra`。
3. 新 Worker 保持 Navigator Worker 的任务、事件和鉴权语义；原生 app-server JSON-RPC 不直接暴露给 Java 或 PC。
4. 新 Worker 内部可按认证和运行环境动态启动多套常驻 app-server，并在不同 Thread 间并发复用。
5. 平台建立 runtime registry、capability handshake、幂等任务接受和不可变 affinity。
6. 新 Ultra 会话先 canary，再默认走新 Worker；既有 SDK Task/Session 留在原 runtime drain。
7. 非 Ultra 可按模型、认证和功能 cohort 独立评估迁移；本版本不要求 app-server 成为全量默认，也不退役 SDK lane。
8. P3 evidence 必须保持 requested model、有效 terminal denominator 和零容忍 affinity violation 的独立语义，并以独占 lease/reclaim claim 串行写 checkpoint。
9. Worker/start-stop-update/runtime pool 必须以精确进程树和 nonce-bound outcome 证明全部 descendants 已清理后才能释放 ownership 或 swap。
10. PC 只通过最小权限 availability 接口判断共享 Worker 的 app-server/Ultra 可用性，不读取 owner-only runtime 详情，也不探测 app-server pool 内部进程。
11. Terminal broadcast、TaskStore resident state 和大 payload journal 写入必须有明确上界；已终态历史不得随 Worker 存活时间无限驻留。
12. Pool 全局容量被其他 lane 的 idle instance 占满时，允许 LRU idle 跨 lane 退役，但不得退役 busy instance 或突破全局上限。
13. App Server Worker 必须提供不含版本号的 OBS 稳定安装入口；`latest.json` 只在版本化制品、校验文件和 bootstrap 可用后提交，客户端执行任何包内代码前必须校验 product/schema/path/bytes/SHA-256。
14. Runtime revision 必须支持新建修订和可逆的退役/归档；不物理删除已被 Task/Session affinity 引用的 revision。
15. Worker HTTP token 为可选：非空时对除 `/health` 外的端点强制 Bearer 校验；空值表示显式关闭 HTTP 认证，所有调用者拥有 Worker API 访问权限。
16. Fresh installer 必须一次性生成并持久化 32-byte base64 `CODEX_APP_SERVER_STATE_KEY`，创建 `<install-dir>/codex-home` 并写入 `CODEX_HOME`，保持 Worker token 和 `OPENAI_API_KEY` 为空；后续升级不得改写已有 `.env`。

## 能力与放量必须分离

| 维度 | 新 Worker 初始能力目标 | 初始生产路由 |
|---|---|---|
| 模型/reasoning | 全部支持并在 manifest 中声明 | 仅新 Ultra 会话 |
| 普通 Codex 任务 | direct/dark smoke 可运行 | 继续 SDK |
| Ultra 原生子任务 | contract v1，PC 可恢复展示 | canary 后启用 |
| Codex Biz | 只有完成 task token、Codex Home、MCP 和功能 parity 后才声明支持 | 初期继续 SDK |
| 既有 SDK Session | 不承诺跨 runtime resume | 固定 SDK |

“支持全部模型”不等于“第一阶段已覆盖旧 Worker 的全部产品功能”。Runtime manifest 必须分别声明模型矩阵和功能矩阵，平台按两者交集路由。

## 目标架构

```text
Navigator Java
  -> Codex runtime registry / router
      -> SDK runtime endpoint (legacy, drain)
      -> App Server Worker endpoint (target)
          -> AppServerPool
              -> app-server instance A: auth/home lane A, multiple threads
              -> app-server instance B: auth/home lane B, multiple threads
              -> overflow instance: same lane at capacity
```

- Provider 和上层 Session/Task API 不变。
- Java 选择 Worker runtime；新 Worker 选择内部 app-server instance。
- app-server 通知在新 Worker 内按 `threadId + turnId` 路由并转换为 Worker event。
- 新 Worker 的多个实例不能共享未隔离的 API Key、`CODEX_HOME`、baseUrl 或 task-scoped token。

## 幂等任务接受协议

新 Worker 必须提供生产使用的两阶段控制协议：

1. `POST /api/v1/tasks`
   - Header `Idempotency-Key` 必填，值为 Navigator taskId；它不是凭据，不得复用其他业务随机值。
   - Worker 在启动 Codex 前原子持久化 key、规范化请求摘要和初始 `accepted` 状态。
   - 首次请求返回 `202` 和稳定 `task_id`；同一 key、同一请求重试返回同一任务；同一 key、不同请求返回 `409`。
2. Java 先持久化返回的 `workerTaskId + runtime binding`，再调用现有 task subscribe/status/abort 接口。
3. Worker 状态至少可区分 `accepted`、`starting`、`committed`、`running` 和 terminal。
4. 接受响应丢失时，Java 重试同一 POST，而不是生成新 key 或向其他 runtime 重放 prompt。
5. Worker 重启后必须从 durable acceptance/task store 恢复；若已 committed 但结果未知，先与 thread/turn 状态对账，禁止自动重放。

旧 `/api/v1/query` 可作为兼容接口保留，但不得作为新 Worker 的生产切流主协议。

## Runtime Registry 与能力握手

Runtime registry 由服务端控制，客户端请求不得携带任意 endpoint 或 token。每条 runtime 至少包含：

- `runtimeId`、`revision`、`kind`、逻辑 `instanceId`；
- 关联的物理 workspace Worker/owner 范围；
- 加密 endpoint credential；
- health/readiness、最后探测时间和 rollout cohort；
- capability digest 和协议版本。

App Server Worker manifest 至少包含：

- Worker contract、app-server protocol、runtime 和实例版本；
- 精确 CLI 版本及生成 schema digest；
- model、alias、reasoning matrix；
- task accept、resume、abort、attachments、output schema、sandbox、approval、network、web、MCP、Biz、file hints 等 feature flags；
- `native_subtask_update` contract versions；
- capacity、active task/thread 和 degraded 原因。

旧 Worker 没有 manifest 时只按 legacy SDK runtime 处理，`supportsUltra=false`。能力判断必须是平台授权、物理 Worker/目录访问和 runtime manifest 的交集；manifest 不能替代用户授权。

Capability 过期只阻止新任务分配。已接受任务必须继续按持久化 binding 访问原 runtime，或返回明确的 runtime unavailable，不得换 runtime 重放。

### Runtime 修订与归档

- `runtimeId + revision` 为不可变身份；endpoint 和认证令牌的变更通过同一 `runtimeId` 新建下一 revision 完成，不就地覆盖旧修订。
- 新 revision 必须重新提供 endpoint，并显式选择空 token 开放模式或提供与 Worker 一致的 token；新修订固定从 `enabled=false + DARK + rollout=0` 开始，经 capability refresh 后才能显式放量。
- 归档使用 `routingEpoch` CAS，原子设置 `enabled=false + DARK + rollout=0 + archivedAt`，并递增 epoch。
- 已归档 revision 不得参与新任务路由、availability、Ultra 判断或 alias 候选集；历史 Task/Session 仍可按已持久化 binding 解析原 revision。
- 恢复同样使用 CAS，清除 `archivedAt`，但只恢复为 Disabled + Dark，不自动恢复归档前的流量。
- Runtime 详细列表继续 owner-only；默认隐藏已归档 revision，管理者显式选择后才展示，且 token 永不回显。

## Task 与 Session Affinity

- Task 持久化 `runtimeId + runtimeRevision + routingEpoch + workerTaskId`；运行中的 endpoint 由 registry 解析，不持久化明文 token。
- Session provider state 持久化 `runtimeId + codexThreadId + CodexHome/account affinity`。
- 新 Worker 内部持久化 `taskId -> appServerInstanceId/threadId/turnId`。
- resume、subscribe、status、abort、delete 和迟到事件必须沿用原 binding。
- registry 修改、canary 比例调整或默认 runtime 切换只影响尚未接受的新任务。
- 未经真实兼容验证，不得把 SDK 创建的 `codexThreadId` 透明迁到 app-server。

## App Server Pool

- 池键至少包含精确 CLI 版本、`CODEX_HOME`、认证指纹、baseUrl 和进程级环境指纹；日志和指标只能记录非敏感摘要。
- model、reasoning、cwd 和 sandbox 优先按 thread/turn 配置，不默认进入池键。
- 同一 Thread 只允许一个 active turn；追加输入使用 steer 或排队。
- 不同 Thread 可并发，所有通知按 thread/turn 分发。
- 同一 cwd 的并发写任务必须由 Worker 级冲突策略保护。
- 不同 task-scoped token、账户或未证明可共享的 Biz lane 不得复用同一进程。
- 池必须有容量、背压、idle TTL、最大存活/任务数、drain、崩溃隔离和轮换机制。
- 一个 app-server 子进程崩溃只能影响其绑定任务，不能导致其他池实例被重启或跨 runtime 重放。
- 在 task/event store 未共享且没有 instance-aware routing 前，新 Worker endpoint 不得指向无状态负载均衡；多副本必须按 `instanceId` 粘性路由，或使用经过恢复测试的共享持久化 store。

## 路由与回滚

生产路由顺序固定为：

1. 新 Worker dark launch，零生产流量。
2. 仅新建 Ultra Session 进入 allowlist/小比例 canary。
3. 新 Ultra 100% app-server；旧 SDK Task/Session 原地 drain。
4. 非 Ultra 按模型、认证和功能 cohort canary。
5. 是否让 app-server 承接更多新 Codex 任务，由后续 cohort 独立签收。
6. SDK runtime 保持可部署、可回滚和可处理现有非 Ultra/既有 affinity；本版本不执行删除。

回滚只执行以下动作：停止新分配、保持已有 binding、允许原 runtime 完成/恢复/终止。禁止把已接受或 committed 的任务切到另一个 runtime，禁止 Ultra 静默改为 Max/xhigh。

## 安全与隐私约束

- 新旧 Worker 使用独立安装目录、日志、event store 和服务级 `CODEX_HOME`；共享认证必须通过受控播种或独立登录，不能共写临时运行态。
- API Key、auth token、task-scoped token、Codex Home 真实路径和请求正文不得进入 runtime key、health、日志或 capability manifest。
- Worker token 空值是显式的 no-auth 模式，不得导致 readiness 降级；它只应用于 loopback 或可信网络。对外暴露时必须使用非空强 token 与网络边界。
- app-server 原始事件、子 prompt、输出、reasoning 和工具参数/输出不得跨出新 Worker。
- 子任务失败只允许稳定码 `NATIVE_SUBTASK_FAILED`；Java 和 PC 继续二次收窄。
- cwd/additional directory 使用现有 allowlist 语义；新 Worker 不得放宽路径边界。
- CLI 与协议 schema 随 Worker release 精确固定，禁止独立自动升级越过协议 gate。
- OBS bootstrap 首次安装必须自动生成固定 state key 和隔离 `CODEX_HOME`，但仍保持 stopped 以便操作者检查 `.env`；Worker token 和 `OPENAI_API_KEY` 默认为空，模型凭据由 Navigator ModelConfig 按任务下发。仅身份一致且文件完整、无 lifecycle/update 失败证据的同版本重跑允许 no-op，可证明身份一致的残缺安装进入 repair，身份不完整或存在失败证据必须 fail-closed，本地版本高于 latest 时必须拒绝降级。

## 非目标

以下“非目标”是 OPT-001 实施阶段的历史边界；涉及 Backend/Provider 的条目由 OPT-005 覆盖，不再代表当前架构。

- 不新增 `OPENAI_CODEX_APP_SERVER` workerBackend、第二套 providerType、Java addon 或 PC 会话页面。
- 不在本阶段让浏览器或 Java 直接连接 app-server JSON-RPC/WebSocket。
- 不用一个全局 app-server 进程承载所有用户、账户和 Biz token。
- 不承诺已有 SDK Thread 自动迁移到 app-server。
- 不在 Ultra canary 之前默认切换任何生产流量。
- 不因一键安装而自动生成 secrets、自动启动未配置 Worker，或把 OBS 发布成功等同于 Ultra 生产切流批准。
- 不删除 SDK、SDK 最低版本检查、旧 Worker 发布链路或回滚包；该决定已从本版本退役 gate 中移出。
- 本规划不修改 LangGraph、Gemini、Claude Worker 执行语义。

## 验收标准

### 契约与代码验收

- 幂等 create/accept 在接受响应丢失、接受后断线和 committed 后断线时均证明副作用最多一次。
- 新 Worker 对全部目标模型/reasoning 通过 direct contract、真实 smoke 和无效组合测试。
- Runtime registry、manifest、Task/Session affinity、legacy dual-read/backfill 和 N-1 组合测试通过。
- 新 Worker 不依赖 SDK，不存在跨 runtime 自动 fallback。
- Worker/Java/PC 事件、结果、错误、原生子任务和隐私契约通过 golden/contract tests。
- Delta 只作为 `TEXT_CHUNK` 实时传输，不持久化为历史消息；completed item 形成 canonical `TEXT_COMPLETE`，任务结果只取最后一条 canonical assistant message，恢复态 `assistant_text` 不得重复累加。
- `SESSION_END isResult` 只用于终态/SSE，不得重复持久化为 assistant 历史消息。
- APP_SERVER progress/completion 不得覆盖 requested model；无有效 runtime instance 的记录进入独立 sanitized affinity violation 集合，不计 terminal denominator。
- Canary checkpoint reclaim 必须先获得与 observed lease 绑定的独占 claim；live local、cross-host 或已有 claim 均 fail closed。
- Shutdown success 必须匹配当前 stop nonce，Worker/runtime process tree 清理及二次 verify 完成后才能释放 state/cwd ownership。
- Terminal broadcast 按需重放后退役，terminal TaskStore 仅保留 resident summary；请求 ciphertext 只在 durable journal 首次持久化一次。
- Availability 对共享用户只返回 `appServerManaged`、`ultraAvailable`、`blockReason`；详细 runtime API 继续 owner-only，`ALL_CANARY@0` 对 Ultra 仍为 available。
- 新建 revision、归档和恢复必须经 owner 校验与 `routingEpoch` CAS；归档后新路由 fail closed，历史 affinity 可解析，恢复后不自动放量。
- Worker token 空值时 capability 和 task/control API 无 Bearer 可用，readiness 不含 `WORKER_TOKEN_MISSING`；非空时缺失或错误 Bearer 仍返回 `401/403`。
- Windows/Linux fresh install 后 `.env` 必须含可解码为 32 bytes 的稳定 state key、安装目录内的绝对 `CODEX_HOME`、空 Worker token 与空 `OPENAI_API_KEY`；重复安装/升级字节保留已有 `.env`。
- PC 必须支持新建修订、退役归档、显示已归档和恢复，并覆盖桌面与窄屏 Playwright 验收。
- Windows `irm .../install.ps1 | iex` 与 Linux `curl .../install.sh | bash` 必须从同一 `latest.json` 安装最新版且不要求版本参数；公网 fresh/残缺 repair、完整重复 no-op、身份或失败证据拒绝、降级拒绝和完整性失败关闭均有自动化或 exact-package 证据。

### Ultra 生产启用验收

- 只影响新 Ultra Session；旧 Session 和 active task 的 runtime binding 未变化。
- 真实 Worker -> Java -> unified SSE -> PC 覆盖成功、失败、中断、刷新、重连、删除。
- canary 观察窗口和 SLO 在切流前写入 progress 并签收。
- 重复副作用、affinity mismatch、跨账户/凭据泄漏和原始子线程内容泄漏均为 0。
- Worker/Java/PC 重启、endpoint revision 变化和 rollback 演练通过。

### 后续 Cohort 与 SDK 保留验收

- 每个模型、reasoning、认证和功能 cohort 都有真实链路证据；P6 及未来另立的 SDK retirement workitem 不接受只有分层 mock 的证明。
- 任何迁入 app-server 的 cohort 都必须覆盖该 cohort 实际依赖的 attachments、output schema、MCP/Biz、Codex Home、sandbox、approval、network、web、additional directories、maxTurns、file hints、process/session API 等契约；未迁入能力继续由 SDK lane 保持。
- 后续若扩大非 Ultra cohort，仍须逐模型、认证和功能给出真实链路证据。
- 旧 SDK Worker 保持 `1.0.11` 现有设计：新 Ultra fail closed，非 Ultra 行为不变，已有 SDK Ultra thread 只按原 affinity drain。
- SDK 删除不属于 `1.4.0-SNAPSHOT` 验收范围；未来如改变产品决定，必须另建 workitem 和独立迁移/回滚门禁。

## 已决策项与后续决策门

| 决策 | 当前结论 | 状态/最晚完成时间 |
|---|---|---|
| 首个精确 CLI 版本与生成 schema digest | `@openai/codex 0.144.1`；`6f2550bb528581f17c4c3a3857dca92c860406aa3274e314cfa726c32e395d8f` | confirmed，P0 accepted |
| Runtime registry 的存储、credential 加密和管理 API 形态 | 持久化 revision registry、可选加密 runtime token（空值=no-auth）、owner-only detail/management 与 access-validated minimum availability API | confirmed，P0-P2 accepted |
| Runtime revision 生命周期 | 同 ID 新建不可变 revision；CAS 归档/恢复；不硬删除历史 affinity | confirmed，P2 accepted |
| Durable acceptance/task store 技术选型 | 稳定 idempotency key、AES-256-GCM request、append-only task/event journal、连续 ESN、writer/recovery lease | confirmed，P0-P1 accepted |
| 新 Worker 本地端口、安装目录和首版版本号 | `0.1.1`；host/port/install/run/state 独立且可配置，外部 state/CODEX_HOME 跨 update/rollback 保留 | confirmed，P1 accepted |
| 单实例并发、池容量、TTL、轮换和资源阈值 | 有界 Pool、跨 lane LRU、busy exclusion、TTL/drain、close failure fail-closed；部署值由受控配置提供 | confirmed，P1-P2 accepted |
| Ultra canary cohort、比例、最小任务数、观察时间与 SLO | 稳定 hash 10%；至少 50 terminal task、连续 72h、至少 2 rotations，并执行既定 SLO/零容忍指标 | contract confirmed；P3 entry not-approved，当前 0/50、0/72h、0/2 |
| Codex Biz 和交互式 server request 纳入哪个迁移 cohort | 继续由旧 SDK lane 保持，迁移前须逐 cohort 证明 parity | pending，P5 entry 前决定 |
| SDK 长期保留策略 | 本版本不退役，未来变更另立 workitem | confirmed |

P0-P2 的 release、控制面、真实 Ultra/SSE/native 和 PC 体验已完成 isolated acceptance。该结论不改变生产路由；`production_enablement` 仍为 `not-approved`，P4-P6 在 P3 独立生产签收前不得开始。

## 参考

- [版本索引](../README.md)
- [实施计划](./OPT-001-independent-codex-app-server-worker-plan.md)
- [进度模板](./OPT-001-independent-codex-app-server-worker-progress.md)
- [1.3.1 OPT-005](../../1.3.1-SNAPSHOT/workitems/OPT-005-codex-sol-max-ultra-support.md)
- [验收缺陷闭环](./OPT-001-independent-codex-app-server-worker-progress.md#acceptance-defect-closure)
- [BUG-008 Canary evidence correctness](./BUG-008-canary-evidence-correctness.md)
- [BUG-009 lifecycle process tree/outcome](./BUG-009-lifecycle-process-tree-and-stop-outcome.md)
- [BUG-010 PC/shared availability boundary](./BUG-010-pc-app-server-boundary-and-shared-availability.md)
- [BUG-011 terminal state bounds](./BUG-011-terminal-broadcast-and-task-store-bounds.md)
- [BUG-012 cross-lane Pool LRU](./BUG-012-pool-cross-lane-lru-retirement.md)
