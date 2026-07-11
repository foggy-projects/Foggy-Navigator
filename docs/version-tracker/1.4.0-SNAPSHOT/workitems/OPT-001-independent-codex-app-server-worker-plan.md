# OPT-001 独立 Codex App Server Worker 实施计划

## 文档作用

- doc_type: implementation-plan
- intended_for: root-controller | execution-agent | reviewer
- purpose: 定义模块职责、代码触点、阶段顺序、测试矩阵、回滚和后置质量流程。

## 基本信息

- version: `1.4.0-SNAPSHOT`
- status: p0-p2-isolated-accepted-production-rollout-not-started
- requirement: [OPT-001 requirement](./OPT-001-independent-codex-app-server-worker-requirement.md)
- progress: [OPT-001 progress](./OPT-001-independent-codex-app-server-worker-progress.md)
- execution_mode: staged-multi-module

## 总体实施原则

1. 先解决“任务是否已被接受”的一致性，再讨论跨 Worker 路由。
2. 新 Worker 全档位实现与生产路由分离；dark 能力不等于可切流。
3. 独立部署但不新增 Provider，Java/Session/PC 继续消费统一 Codex 契约。
4. Runtime binding 一经接受不可变；回滚只改变新任务分配。
5. 新 Worker 内没有 SDK fallback；同一 prompt 不得被两个 runtime 执行。
6. 长期保留清晰的双 Worker 边界；新 Worker 稳定不自动触发旧 SDK 实现退役。

## 模块职责

| Owner | 职责 | 依赖边界 |
|---|---|---|
| `tools/codex-app-server-worker` | app-server JSON-RPC、任务接受状态机、进程树、进程池、事件转换、bounded durable task/ESN、Canary evidence、HTTP/SSE、health/capability、打包运维 | 独立 TypeScript 服务；不得 import 旧 Worker 私有源码 |
| `tools/codex-agent-worker` | 旧 SDK / `codex exec` 稳定 lane、SDK 版本 preflight、非 Ultra 现有行为、已有 affinity drain | 不承载 app-server；保持现有设计和独立发布能力 |
| `addons/codex-worker-agent` | Runtime registry、路由、最小 availability、幂等创建客户端、Task/Session affinity、requested model、Worker SSE 转统一事件、恢复/取消/删除 | 可依赖 common/session；session 不反向依赖本 addon |
| `navigator-common` | 只有确需跨模块共享的 DTO/枚举/持久化契约 | 不放 app-server 原始协议和执行器逻辑 |
| `addons/claude-worker-agent` | 既有物理 workspace Worker 管理与兼容配置读取；配合 readiness 展示 runtime 状态 | 不成为 Codex runtime 路由 owner |
| `session-module` | 保持统一 Task/Session/SSE/用户归属；持久化必要 provider state | 不新增 app-server Provider，不直接调用 Worker endpoint |
| `packages/navigator-frontend` | Runtime 配置/健康可见性、Ultra fail-closed 提示、现有子任务进度体验 | 不暴露 runtime endpoint/token，不拆第二套 Codex 页面 |
| root scripts/docs | 独立启动、停止、发布、升级、端口归属和版本文档 | 不覆盖用户已有脚本改动 |

### 依赖与归属结论

- 新 Worker 是 `tools` 下独立发布物，不进入 Maven reactor。
- Runtime registry 和路由属于 Codex addon，避免把 Codex 编排放进 `session-module` 后造成反向依赖。
- 共享 Task/Session DTO 仅在确有跨模块消费时进入 `navigator-common`；runtime credential、health cache 和 endpoint 仍由 Codex addon 私有持有。
- `launcher` 只做聚合启动和配置，不放 runtime service/controller。
- 如果执行中需要共享 TypeScript contract，只能创建不依赖任一执行器的中立包；禁止新 Worker直接跨目录 import `tools/codex-agent-worker/src/*`。

## Code Inventory

| Repo/Module | Path | Role | Expected change | Notes |
|---|---|---|---|---|
| root | `tools/codex-app-server-worker/` | 新目标 Worker | create | package、src、tests、fixtures、docs、release、start/stop/update |
| root | `tools/codex-app-server-worker/src/app-server/` | JSON-RPC host、通知路由、pool | create | 使用 CLI 生成的版本化 schema/types |
| root | `tools/codex-app-server-worker/src/routes/` | task accept/subscribe/status/abort、health/capability | create | 生产使用两阶段接受协议 |
| root | `tools/codex-app-server-worker/src/persistence/` | durable idempotency/task/event state | create | 必须支持崩溃恢复和请求摘要冲突检测 |
| root | `tools/codex-agent-worker/` | 旧 SDK Worker | retain | Ultra fail closed；保留 Max 及以下、SDK preflight、发布与回滚能力 |
| root | `addons/codex-worker-agent/` | Java runtime 控制面 | update | registry、router、capability、幂等 client、affinity、事件/recovery |
| root | `addons/codex-worker-agent/.../CodexTaskEntity` 及 repository | Task binding | update | 显式 runtime/revision/routing binding；需 expand/backfill migration |
| root | `navigator-common/` | 共享 contract | update-if-needed | 保留 native DTO/entity；只增加跨模块必需类型 |
| root | `agent-framework/` | 统一消息类型 | retain | 保留 `NATIVE_SUBTASK_UPDATE`，不增加 runtime 私有事件 |
| root | `session-module/` | Session provider state/query/SSE | update | runtime + thread/account affinity、legacy dual-read；不直接路由 endpoint |
| root | `addons/claude-worker-agent/` | Worker 管理/readiness | update | 展示 registry readiness；兼容 legacy CodexConfig，不简单塞第二个裸 URL 作为最终控制面 |
| root | `packages/navigator-frontend/` | 设置、能力状态、Task Pane | update | Ultra 可用性、错误状态、现有子任务 E2E；不展示凭据 |
| root | `docs/migration/` | 数据库 expand/backfill | create/update | runtime registry、task binding；prod validate 前执行 |
| root | `scripts/`、`CLAUDE.md` | 本地栈和开发说明 | update-late | 新 Worker 独立入口；合并而非覆盖已有 dirty 脚本 |
| root | `docs/version-tracker/1.3.1-SNAPSHOT/` | 未发布基线重分类 | update | 混合 Worker验收重新打开；保留平台投影证据，迁出 app-server runtime 结论 |
| root | LangGraph/Gemini/Claude execution | 非目标 | do-not-touch | 不改变其他 Worker 的任务协议 |
| root | `ProviderRouteRegistry` 与 Provider beans | Provider 身份 | do-not-touch | 不新增 app-server backend/providerType |
| root | `@foggy/chat` 消息历史 | 聊天契约 | do-not-touch | native state 继续独立于消息历史 |

## 当前 1.3.1 未提交改动迁移分类

| 当前内容 | 1.4.0 处理 |
|---|---|
| Worker app-server runtime、event bridge、native tracker、fixture/tests | 迁入新 Worker并重新验证 |
| `sdk-wrapper` 中 Ultra 双执行器、pre-turn SDK fallback、shared state | 从旧 Worker撤出；新 Worker严格 app-server，不做跨 runtime fallback |
| 旧 Worker app-server flag、CLI health 字段和对应文档 | 从旧 Worker撤出；新 Worker改为固有 runtime/capability manifest |
| 5.6 alias、Max reasoning、SDK 版本 preflight、现有非 app-server 诊断 | 保留旧 Worker |
| Java native projection、父任务锁、Session snapshot/SSE/delete | 原位保留并作为新 Worker公共消费契约 |
| PC native reducer/API/UI、SSE epoch、Playwright | 原位保留并重跑真实新 Worker链路 |
| `native_subtask_states` migration | 保留；与 1.4 runtime migrations 组合验证 |
| 1.3.1 quality/coverage/acceptance | 重新打开并拆分结论，不能沿用“旧 Worker混合 lane 已交付”的签收 |

## 阶段计划

### P0 契约冻结与基线拆分

**Entry**

- 当前 SDK 和 app-server 真实 smoke、1.3.1 差异和外部协议版本已可复现。

**执行**

1. 固化 glossary、task accept v1、capability manifest v1、runtime registry 和 affinity schema。
2. 使用目标 CLI 生成 TypeScript/JSON schema，记录精确版本和 digest。
3. 确定 durable acceptance/task store、credential 加密、runtime 管理 API 和迁移策略。
4. 将 1.3.1 未提交代码/文档按迁移分类重设基线。
5. 定义旧 Worker Ultra fail-closed 错误码和新 Worker兼容范围。

**Exit gate**

- 架构、API、schema、数据迁移、N-1 和 rollback 评审通过。
- 已明确首个 SSE 前断线、接受后断线和 committed 后断线的处理。
- 没有“失败时换 Worker再试”的模糊路径。

**Rollback**

- 无生产变化；只撤销未实施契约草案。

### P1 独立 Worker Dark Launch

**Entry**

- P0 完成；新 Worker版本、端口、安装目录和精确 CLI 已确定。

**执行**

1. 创建独立 package、认证、配置、路径保护、health/capability 和发布脚本。
2. 实现 task accept/status/subscribe/abort 与 durable task/ESN。
3. 实现 app-server executor、全模型/reasoning、resume/interrupt 和 Worker event 转换。
4. 迁入并硬化 native subtask tracker/bridge，保持固定失败码和内容隔离。
5. 补齐核心 attachments、output schema、developer instructions、sandbox、network、web 等直接可映射能力。

**Exit gate**

- 零生产流量运行；unit/typecheck/build、contract fixture 和真实全档位 smoke 通过。
- 接受响应丢失和三类断线 fault test 证明最多执行一次。
- CLI/manifest 不匹配时 Worker readiness fail closed。

**Rollback**

- 停止新 Worker；平台仍全量 SDK。

### P2 App Server Pool 与双 Runtime 控制面

**Entry**

- P1 dark Worker 稳定；P0 registry/affinity migration 可执行。

**执行**

1. 新 Worker增加多实例常驻池、池键、容量、TTL、drain、轮换、背压和崩溃隔离。
2. 将单任务监听器改为 process-level request correlator 和 `threadId + turnId` 通知路由。
3. Java 增加 runtime registry、manifest 拉取/缓存、健康门控和 rollout policy。
4. Task/Session 增加不可变 affinity，完成 legacy dual-read/backfill。
5. Java client 实现幂等 create 后持久化 binding，再 subscribe；恢复、取消、删除均走 binding。
6. PC/管理端展示 runtime readiness，Ultra 无可用 runtime 时给出可操作错误。
7. 若启用多 Worker 副本，先完成 `instanceId` 感知路由或共享 task/event store，禁止把有本地状态的 Worker 直接置于无状态负载均衡后。

**Exit gate**

- 生产路由仍 100% SDK。
- 并发、隔离、Worker/Java 重启、多副本定向恢复、revision 变化、stale capability、N-1 和 migration rollback 测试通过。
- 同 Thread 串行、跨 Thread 并发、同 cwd 冲突策略均有证据。

**Rollback**

- 关闭新 runtime registry entry；不影响 legacy SDK 读取。

### P3 新 Ultra Session Canary

**Entry**

- P2 完成；DB migration、Java、PC 和新 Worker先部署。
- canary cohort、比例、最小样本、观察时间、SLO 和 owner 已在 progress 中签收。

**执行**

1. 只允许新建 Ultra Session 进入 allowlist/小比例 app-server。
2. 监控路由数、接受状态、重复副作用、affinity mismatch、错误率、延迟、内存、进程轮换和费用。
3. 演练 Worker/Java 断线、重启、abort、runtime drain 和停止新分配。
4. 完成真实 Worker -> Java -> unified SSE -> PC 全链路。

**Exit gate**

- 预设观察窗口和 SLO 达标。
- duplicate side effect、affinity mismatch、凭据泄漏、原始子线程内容泄漏均为 0。
- 回滚演练不改变任何已接受 Task/Session binding。

**Rollback**

- 停止新的 Ultra 分配；新 Worker上已接受任务继续完成/恢复/终止，旧 SDK会话不变。

### P4 Ultra Default 与旧会话 Drain

**Entry**

- P3 canary enablement 独立签收。

**执行**

1. 新 Ultra Session 100% 走 app-server。
2. 旧 Worker拒绝新的 Ultra；已有 Ultra/SDK affinity 继续原地服务。
3. 记录旧会话数量、最后活动和保留期，验证恢复/删除。

**Exit gate**

- 新 Ultra 路由无 SDK fallback；旧会话无误迁移。
- Ultra default routing 验收签收。

**Rollback**

- 回到 P3 cohort；只影响新会话。

### P5 非 Ultra 与功能 Cohort 迁移

**Entry**

- Ultra 长稳；目标 cohort 所需 feature flags 均为 supported。

**执行**

1. 按 `max -> xhigh/high -> medium/low` 和显式模型逐批 canary。
2. 按 API key、Codex login、baseUrl、scoped home、Biz MCP 等认证/功能 lane 分批验证。
3. 补齐或正式退役 approval、additional directories、interactive server request、maxTurns、file hints、process/session API 等差异。
4. 对每个 cohort 记录真实成功、失败、恢复、资源和成本证据。

**Exit gate**

- 全模型与所有保留功能没有 critical parity gap。
- P6 所需真实链路和长稳证据齐全。

**Rollback**

- 单独停止失败 cohort 的新分配；已绑定会话不迁移。

### P6 App-server 成为默认 Runtime

**Entry**

- P5 完成，默认路由独立质量、覆盖和验收门通过。

**执行**

1. 所有新 Codex 任务默认 app-server。
2. SDK 只处理已有 affinity 或登记的 capability exception。
3. 冻结 SDK 新功能，统计 active/resumable/exception 数量和最后使用时间。

**Exit gate**

- 默认路由观察窗口达标；SDK drain 计划和回滚包验证通过。

**Rollback**

- 新任务路由退回已签收 cohort；不迁移已有 app-server affinity。

### P7 SDK Retirement（产品决定延后）

**状态**

- `N/A-deferred-by-product-decision`。
- `1.4.0-SNAPSHOT` 不进入该阶段，不删除旧 SDK 执行路径、版本 preflight、发布物、配置或运维入口。
- 旧 Worker 继续服务非 Ultra 现有路径和已有 SDK affinity；新 Ultra 仍 fail closed 并由 app-server runtime 承接。
- 未来若决定退役，必须新建 workitem，重新定义 active/resumable/exception、保留期、N-1 和回滚 gate，不能沿用本版本的隔离环境证据直接签收。

## 测试矩阵

| 维度 | 必测内容 |
|---|---|
| Model | 全 alias、显式模型、全部 reasoning、无效组合、动态 model catalog |
| Contract | SDK/app-server 请求解析、delta/completed 事件、最后 canonical 最终结果、恢复去重、`SESSION_END` 非持久化和错误 golden parity |
| Idempotency | 接受前、接受响应丢失、接受后、committed 后断网；同 key 同任务、异 payload 409 |
| Affinity | create/resume/reconnect/abort/delete、Java/Worker 重启、runtime revision/route policy 变化 |
| Pool | 同 thread 串行、跨 thread 并发、容量/背压、跨 lane LRU idle 退役、busy exclusion、TTL、drain、进程树清理、崩溃隔离、长稳/内存 |
| Security | 跨用户、账户、API key、baseUrl、CODEX_HOME、task token、cwd 和 env 隔离 |
| Feature | images/attachments/output schema/MCP/Biz/developer instructions/config/sandbox/approval/network/web/additional dirs/maxTurns/file hints/process/session API |
| State | ESN 重复/乱序/缺口、terminal broadcast residency、resident TaskStore summary、大 payload 单次 ciphertext、durable recovery、native snapshot、late event、terminal reconciliation |
| Compatibility | legacy Worker无 manifest、CLI/schema mismatch、Java/PC/Worker N-1、migration expand/backfill/rollback |
| E2E | 真实 Worker -> Java -> unified SSE -> PC，共享 Worker minimum availability、app-server process boundary、Ultra 与至少一个非 Ultra cohort |
| Operations | dark launch、canary denominator/lease claim、nonce outcome、stop-new-routing、drain、完整进程树、legacy first-hop、发布/升级/回滚包 |

## 验证命令基线

实际命令在 package 创建后写入 progress，至少包括：

- 新旧 Worker：install、unit、typecheck、build、package、真实 app-server smoke。
- Java：`navigator-common`、`session-module`、`addons/codex-worker-agent`、`addons/claude-worker-agent` 的 targeted + reactor tests。
- PC：Vitest、`vue-tsc`、production build、Playwright。
- DB：在干净 schema 上单次执行一次性 MySQL migration 并核对 backfill；另行验证 N-1 启动、生产副本迁移和 `ddl-auto=validate`。一次性 `ALTER` 脚本不以重复执行作为幂等验收。
- Fault/live：幂等副作用探针、runtime affinity、全链路 canary、rollback rehearsal。

编写测试不等于完成；每阶段必须实际运行并通过。依赖外部环境而未运行的测试必须在 progress 标记 `not-run` 和原因，不能把阶段标为完成。

## 观测与 Canary Evidence

至少记录：

- 路由总数和按 runtime/model/reasoning/cohort 分布；
- accepted/committed/terminal 状态数量与不一致数；
- duplicate side effect、affinity mismatch 和 unknown committed 数量；
- task success/error/abort、首事件与完成延迟；
- app-server 进程数、active threads、队列、轮换、崩溃、RSS/CPU；
- native subtask 数量、终态闭合和 snapshot/SSE 恢复；
- 认证/路径/隐私拒绝与泄漏审计；
- 费用/token 相对 SDK 基线。

## Review Defect Tracking

| Defect | Plan impact | Current gate |
|---|---|---|
| [BUG-008](./BUG-008-canary-evidence-correctness.md) | P3 model/denominator/lease evidence contract | fixed-isolated；P3 生产验证待开始 |
| [BUG-009](./BUG-009-lifecycle-process-tree-and-stop-outcome.md) | P1 start/stop/update/runtime child ownership | closed-isolated；v5 Windows/WSL exact-package 与 zero-residue 通过 |
| [BUG-010](./BUG-010-pc-app-server-boundary-and-shared-availability.md) | P2 shared Worker minimum availability 与 PC process boundary | closed-isolated；shared/owner availability 与 PC live 通过 |
| [BUG-011](./BUG-011-terminal-broadcast-and-task-store-bounds.md) | P1 resident memory 与 journal write bound | fixed-isolated；final full/package 通过，P3 memory soak 待开始 |
| [BUG-012](./BUG-012-pool-cross-lane-lru-retirement.md) | P1 global capacity fairness | fixed-isolated；final full/package 通过，P3 fairness soak 待开始 |
| [BUG-013](./BUG-013-windows-process-tree-termination-settle-race.md) | P1 Windows forced termination settle 与 running update | closed-isolated；bounded polling 回归和 v5 Windows/WSL exact-package 通过 |

P0-P2 已按本计划完成 isolated acceptance。P3 仍为 0/50 terminal task、0/72h、0/2 rotations 且 `production_enablement=not-approved`；P4-P6 在 P3 独立生产签收前不得开始。

## 后置质量流程

每个生产 gate 都必须依次执行：

1. execution check-in 和实现自检；
2. `foggy-implementation-quality-gate`；
3. `foggy-test-coverage-audit`；
4. `foggy-acceptance-signoff`。

代码验收、Ultra canary enablement、Ultra default 和任何后续全模型 default 必须分别签收，不能用一次总验收覆盖后续生产门禁；SDK retirement 已延后，不属于本版本签收项。
