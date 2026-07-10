# OPT-001 独立 Codex App Server Worker 进度

## 文档作用

- doc_type: progress
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 按阶段记录开发、测试、体验、canary、回滚和验收证据，防止“代码完成”被误报为“可切生产”。

## 基本信息

- version: `1.4.0-SNAPSHOT`
- status: in-progress
- requirement: [requirement](./OPT-001-independent-codex-app-server-worker-requirement.md)
- plan: [implementation plan](./OPT-001-independent-codex-app-server-worker-plan.md)
- current_stage: P1-P2-local-checkpoint
- implementation_started_at: 2026-07-10
- last_updated_at: 2026-07-10

## 前置条件

| 检查项 | 状态 | 证据/说明 |
|---|---|---|
| 1.3.1 当前差异与测试基线可复现 | yes | 旧 SDK Worker 115/115；PC 前端全量 Vitest 159/159、type-check/build 通过；基线审计已完成 |
| 目标 CLI 版本和 app-server schema 已固定 | yes | CLI `0.144.1`；非 experimental JSON schema 267 files，tree SHA-256 见 P0 Decision Log |
| task accept/capability/affinity contract 已评审 | yes | `POST /api/v1/tasks` + manifest v1 + immutable binding |
| 1.3.1 未提交内容迁移分类已执行 | yes | native projection 保留；旧 Worker 混合 app-server lane 已迁出并回归 115/115 |
| 生产路由仍保持 SDK | yes | 规划阶段硬约束 |

## Development Progress

| Stage | 范围 | 状态 | Entry gate | Exit evidence |
|---|---|---|---|---|
| P0 | 契约冻结与基线拆分 | completed | baseline ready | canonical schema lock、task accept v1、registry/affinity、rollback 评审与测试通过 |
| P1 | 独立 Worker dark launch | in-progress | P0 pass | 新 Worker 87/87、type/build/schema 和 0.1.0 deterministic package/Windows install+update 通过；最终 provider/crash 复验被账户额度阻塞，POSIX 实装与运行中 drain/restart/rollback 待验 |
| P2 | app-server pool + 双 runtime 控制面 | in-progress | P1 local implementation pass | Java reactor、Session、PC、真实 MySQL 和分层契约通过；真实 Worker -> Java -> SSE -> PC、N-1、instance-aware 多副本与生产 migration 未跑 |
| P3 | 新 Ultra Session canary | blocked | P2 exit + thresholds signed | 无目标环境/release owner；0/50 task、0/72h、0/2 rotation，未改变生产路由 |
| P4 | Ultra default + legacy drain | blocked | P3 acceptance | P3 未签收，禁止进入 |
| P5 | 非 Ultra/功能 cohort | blocked | Ultra stable | P4 未签收；动态账号模型目录与完整功能 parity 仍开放 |
| P6 | app-server default | blocked | no critical parity gap | P5 未签收，SDK 仍是生产默认 lane |
| P7 | SDK retirement | blocked | zero active/resumable/exception | P6 未签收，active/resumable/exception 与保留期证据未建立 |

## P0 Decision Log

| 决策 | 状态 | 结论 | Reviewer/date |
|---|---|---|---|
| 精确 CLI version + schema digest | confirmed | CLI `0.144.1`；每个 JSON 递归 key-sort canonicalize 后计算 file SHA，再按相对路径排序生成 LF-terminated manifest；tree SHA-256=`6f2550bb528581f17c4c3a3857dca92c860406aa3274e314cfa726c32e395d8f` | root-controller / 2026-07-10 |
| `Idempotency-Key` create/accept v1 | confirmed | Navigator taskId；同 key 同规范化请求返回同 task，异请求 409 | root-controller / 2026-07-10 |
| Durable acceptance/task store | confirmed | 新 Worker 使用 AES-256-GCM 加密请求与 append-only task journal；event journal 独立；committed 后只恢复/对账不重放 | root-controller / 2026-07-10 |
| Runtime registry schema/API/credential encryption | confirmed | JPA revision registry；owner 校验；CredentialEncryptor；capability refresh；routing policy/epoch | root-controller / 2026-07-10 |
| Task/Session affinity schema | confirmed | Task 固定 runtime/revision/type/instance/routingEpoch/workerTaskId；Session provider state 同步 runtime 与 Codex account/thread affinity | root-controller / 2026-07-10 |
| Legacy Worker Ultra fail-closed error | confirmed | 新 Ultra 无 app-server 返回 `CODEX_ULTRA_APP_SERVER_REQUIRED`；已有 legacy Ultra thread 仅允许原 SDK runtime resume/drain | root-controller / 2026-07-10 |
| 新 Worker port/install/version | confirmed | 默认端口 `3062`，避开现有 SDK Worker `3051/3052`、LangGraph Biz Worker `3061` 与 Gemini Worker `3071`；独立目录 `tools/codex-app-server-worker`；首版 `0.1.0`，runtime revision 为正整数 | root-controller / 2026-07-10 |
| Cross-runtime resume policy | confirmed | 未经 live proof 禁止 | |

## Implementation Self-Check

每阶段完成后必须更新：

- [x] requirement scope 已收口，未把 Runtime 误建成新 Provider。
- [x] 非目标未被扩张，其他 Worker 和聊天历史未受影响。
- [x] 幂等接受、execution committed 和跨 runtime 防重放逻辑无旁路。
- [x] active Task/Session affinity 不受 route/manifest 变化影响。
- [x] 凭据、路径、原始 app-server/子线程内容未进入日志、状态或 UI。
- [x] 没有临时代码、重复执行器、未受控 feature flag 或跨目录私有源码 import。
- [x] 代码路径、数据迁移、版本和本地发布物已记录；目标环境发布/回滚仍由 P1/P2 exit gate 约束。
- [x] 测试已实际执行；not-run 项有原因且阶段未误标完成。
- [x] progress、quality、coverage、acceptance 已按当前本地 gate 回写。

- self_check_decision: ready-with-risks-for-local-checkpoint
- formal_quality_gate_required: yes

## Testing Progress

| Test lane | P0 | P1 | P2 | P3/P4 | P5/P6 | P7 | Evidence |
|---|---|---|---|---|---|---|---|
| Worker unit/type/build | baseline-pass | 87/87 + pass | compatibility pass | not-run | regression | regression | 新 Worker 87/87；旧 SDK Worker 115/115；两者 typecheck/build 通过 |
| Protocol generated schema/fixture | generated | verified | not-run | not-run | not-run | N/A | canonical digest `6f2550bb...95d8f` 连续校验通过 |
| Idempotency fault injection | contract | unit + HTTP live pass | recovery contract pass | not-run | regression | regression | 最终 post-fix health 后实测 first=202、same=202、changed=409；committed no-replay 由 fault tests 覆盖 |
| Pool concurrency/soak | N/A | unit + historical short live | unit pass | not-run | regression | regression | 容量/复用/abort/崩溃隔离自动化覆盖；最终 post-fix provider live 被额度阻塞，长稳与生产轮换未跑 |
| Java registry/router/affinity | reviewed | N/A | reactor pass | not-run | regression | regression | Codex addon 214/214；Session 302/302；create/persist/subscribe/delete、manifest、immutable affinity |
| DB migration/backfill/N-1 | design | N/A | partial | not-run | regression | not-run | MySQL 8.4.8 干净 schema 单次 migration/backfill 通过；N-1、生产副本和 ddl validate 未跑 |
| SDK/app-server golden parity | design | not-run | not-run | not-run | not-run | regression | |
| Real all-model smoke | N/A | historical-pass/final-blocked | direct-worker only | Ultra production | all production cohorts | regression | 最终 post-fix 已验证固定 CLI/schema/静态 manifest，首个 provider call 因账户 usage limit 失败；此前逐档真实完成证据保留，必须在额度恢复后重验 |
| Worker -> Java -> SSE -> PC | N/A | Vitest + mocked contract Playwright | not-run | not-run | not-run | regression | 当前浏览器证据不包含真实 Worker/Java 或真实 SSE 重连 |
| Rollback/drain rehearsal | design | N/A | not-run | not-run | not-run | not-run | |
| Packaging/install/update | N/A | Windows local pass | target deployment not-run | regression | regression | not-run | 0.1.0 deterministic ZIP 三次 hash 一致；Windows temp install/in-place update 与保留状态通过；POSIX 仅 `bash -n`，运行中 drain/restart/rollback 未验 |

### PC Evidence Boundary

- Vitest 覆盖：session/connection epoch、订阅失败退避、snapshot 失败退避、旧响应隔离、多 Pane 状态隔离、Runtime dirty draft/CAS、Worker 切换竞态和新建 Ultra readiness guard。
- `codex-runtime-management.spec.ts`：mocked API 下覆盖 Runtime 注册、一次性 token 不回显、相邻 rollout stage 和 Ultra 可用状态；不是 Java/Worker 真实链路。
- `codex-native-subtasks.spec.ts`：mocked snapshot 下覆盖单 Pane 折叠、层级、状态、内部 ID 隐藏和 320px 布局；没有发送真实 SSE event，也没有覆盖浏览器多 Pane/断线重连。
- 真实 `codex-app-server-worker -> Java -> unified SSE -> PC` 仍为 `not-run`，继续作为 P3 entry/exit 证据，不由上述分层测试替代。

### Critical Fault Cases

- [x] 请求未接受即断线：contract/fault test 证明可安全重试同 key。
- [x] 已接受但响应丢失：HTTP contract 与最终 post-fix `202/202` 证明返回同一 task，不产生第二次受理。
- [x] `turn/start` committed 后断线：恢复测试证明只对账/恢复/失败，不重放。
- [x] Java 在保存 workerTaskId 前崩溃：Java 幂等恢复契约测试通过。
- [ ] 多 Worker 副本下 subscribe/status/abort 命中绑定实例，或从共享 store 恢复，不因负载均衡丢失任务。
- [x] App-server instance 崩溃：pool/fault test 证明只淘汰受影响实例；生产 soak 未跑。
- [x] Worker 服务重启：46/46 focused recovery/durability tests 证明 durable task/ESN 恢复；最终真实硬杀复验被账户额度阻塞。
- [x] Runtime revision 或默认路由变化：Java affinity/CAS tests 证明 active task/session 仍命中原 binding。
- [x] 旧 Worker 无 manifest：旧 Worker与 Java contract tests 证明 Ultra fail closed，不降级。
- [x] 同一 key 不同请求：最终 post-fix HTTP live 返回 409 且未创建第二任务。

## Experience Progress

本需求会修改 Ultra 可用性、设置/健康状态和 Task Pane 原生子任务展示，因此 experience 不得标记 N/A。

| 体验维度 | 检查项 | 状态 | Playwright/evidence |
|---|---|---|---|
| 页面可达性 | Codex Worker/runtime 配置与健康状态可访问 | contract-pass | mocked API Playwright 可进入 Runtime 管理并读取不兼容/Ready 状态；真实服务可达性未跑 |
| 核心流程 | 新 Ultra 创建、执行、展开子任务、完成 | partial | Vitest 覆盖新建 Ultra readiness guard；mocked snapshot Playwright 覆盖单 Pane 展开，真实创建/执行/完成未跑 |
| 表单验证 | endpoint/token/能力缺失给出明确错误 | partial-contract-pass | Runtime Playwright 覆盖 token 必填、不兼容 capability 与 token 不回显；真实 endpoint 不可达未跑 |
| 异常状态 | runtime unavailable、stale manifest、断线/恢复 | layered-pass | Vitest 覆盖 unavailable/stale、订阅与 snapshot 失败退避；真实浏览器断线恢复未跑 |
| 权限可见性 | 未授权 Ultra 或无 runtime 时不提供可执行入口 | unit-pass | Model grant tests + Worker-scoped runtime readiness guard；未做真实账号权限 Playwright |
| 数据一致性 | 刷新、重连、多 Pane、resume/abort 后状态一致 | unit-pass | Vitest 覆盖 epoch、旧响应、多 Pane、dirty draft/CAS 和 Worker 切换；真实全链仍未跑 |
| 降级边界 | Ultra 不会静默显示为 Max/xhigh 或换 SDK | layered-pass | 前端 alias/guard 与 Worker/Java contract tests；真实路由 smoke 未跑 |

## Canary Thresholds

P3 数值门槛已定义，但仍需 release owner 在目标环境签收 cohort 和观测面；没有真实值时不得进入 P4：

| 指标 | 阈值 | 当前值 | 状态 |
|---|---|---|---|
| Canary cohort/比例 | 1 台明确指定的 canary 物理 Worker；只含新建、非 Biz、无未支持 feature 的 Ultra Session；稳定 hash 10% | not-run | defined-awaiting-production-signoff |
| 最小任务数 | 至少 50 个 terminal Ultra task；成功/失败/abort 均计入分母，人工重复 smoke 不重复计数 | 0 | pending-production-evidence |
| 最小观察时间 | 连续 72 小时，且至少发生 2 次 app-server instance 生命周期/任务数轮换 | 0h | pending-production-evidence |
| 成功率/error budget | 成功率 >= 98%；runtime/transport 内部错误 <= 1%；相对 dark baseline 下降不超过 2 个百分点 | not-run | pending-production-evidence |
| 首事件/完成延迟回归 | p95 首个用户可见事件 <= dark baseline 1.25x；p95 完成时间 <= baseline 1.30x | not-run | pending-production-evidence |
| 进程崩溃/恢复 | crash <= 1/100 task；已提交任务可证明恢复率 100%；`APP_SERVER_RECOVERY_UNKNOWN`=0 | not-run | pending-production-evidence |
| RSS/CPU/队列阈值 | 单实例 RSS p95 <= 1.5 GiB；pool acquire p95 <= 5s；timeout/reject <= 0.5%；持续无界增长=0 | not-run | pending-production-evidence |
| duplicate side effect | 0 | not-run | pending-production-evidence |
| affinity mismatch | 0 | not-run | pending-production-evidence |
| credential/raw child content leak | 0 | not-run | pending-production-evidence |

## Acceptance Criteria Mapping

| Requirement | Planned evidence | 状态 |
|---|---|---|
| 全模型/reasoning 能力 | unit + generated schema + live matrix | historical local live pass；最终硬化后复验被账户额度阻塞；生产 cohort pending |
| 幂等 create/accept | integration + fault side-effect probe | local-pass；生产观测 pending |
| Immutable runtime affinity | Java/Worker restart + revision change tests | layered-pass；真实全链/N-1 pending |
| Ultra native PC | real full-chain + Playwright | pending |
| 安全/隐私隔离 | unit + integration + sanitized live event audit | local-pass；生产审计 pending |
| Ultra canary/default | metrics + rollback rehearsal + signoff | pending |
| 全模型 default | per-cohort live + soak + signoff | pending |
| SDK retirement | zero-count evidence + N-1 + rollback artifact | pending |

## 计划外变更

| 日期 | 变更 | 原因 | 影响/审批 |
|---|---|---|---|
| | | | |

## 阻塞项

| Blocker | Owner | Resolution gate | 状态 |
|---|---|---|---|
| POSIX 实际安装更新、运行中 drain/restart 和故障回滚未验收 | Worker release owner | P1 exit | local Windows artifact pass；target operations open |
| 真实 Worker -> Java -> unified SSE -> PC 与真实浏览器重连未跑 | Codex addon/Session/PC | P2 exit/P3 entry | open |
| N-1、instance-aware 多副本、生产 migration/ddl validate 未跑 | Runtime/DB owner | P2 exit | open |
| 账号动态 model catalog refresh 未实现 | New Worker/Java | P5 entry | open；当前只发布固定 CLI 的静态可路由矩阵 |
| approval/additional dirs/server requests parity | New Worker | P5/P6 | open |
| Canary cohort/阈值尚未由目标环境 release owner 签收 | Release owner | P3 entry | thresholds-defined-signoff-open |
| 最终硬化后真实 provider/crash smoke 被 ChatGPT usage limit 阻塞 | Test account owner | P1 exit evidence refresh | retry-after 2026-07-10 17:37 Asia/Shanghai；不得用失败前 health/HTTP 证据代替执行通过 |

## 后置评审状态

| Gate | Quality | Coverage | Acceptance | 说明 |
|---|---|---|---|---|
| Code | reviewed: ready-with-risks | reviewed: needs-more-tests | blocked | 本地 P0-P2 检查点通过实现质量；生产验收证据不完整，详见 quality/coverage/acceptance |
| Ultra canary enablement | not-started | not-started | not-started | 首次生产流量 |
| Ultra default | not-started | not-started | not-started | 新 Ultra 100% |
| App-server default | not-started | not-started | not-started | 所有新 Codex 任务 |
| SDK retirement | not-started | not-started | not-started | 独立最终签收 |

## Execution Check-in

- completed_work: P0 完成；P1/P2 本地实现检查点完成，但因 release/full-chain/N-1/生产证据未关闭而保持 in-progress；P3-P7 未改变生产状态
- touched_code_paths: `tools/codex-app-server-worker/**`、`addons/codex-worker-agent/**`、`navigator-common/**`、`session-module/**`、`tools/codex-agent-worker/**`、`packages/navigator-frontend/**`、`docs/migration/**`
- tests: 新 Worker 87/87 + type/build/schema，恢复/持久化 focused 46/46；0.1.0 deterministic ZIP 三次 SHA-256=`59cf633a5781ee8adde28c3363342920f71131def1bfdde288d63233300ef5ea`，Windows temp install/in-place update 通过；旧 Worker 115/115 + type/build；Java reactor BUILD SUCCESS（Codex addon 214/214、Session 302/302）；PC Vitest 159/159 + type-check/build；mocked contract Playwright 2/2；MySQL 8.4.8 migration/backfill；此前隔离 app-server 模型/native/abort smoke；最终 post-fix health/matrix/HTTP 幂等通过但 provider/crash smoke 被账户额度阻塞
- experience: mocked contract Playwright 2/2；真实 Worker/Java/SSE 全链、真实浏览器重连与目标环境流程仍为 not-run
- remaining_risks: see blockers；生产 duplicate/affinity/leak 指标仍为 0 条样本而不是已证明为零
- acceptance_readiness: local-code-checkpoint-reviewed; production-not-ready
- next_action: 额度恢复后重跑 post-fix provider/crash smoke，并完成 POSIX 与运行中 drain/rollback；随后在目标环境完成 P2 真实全链、N-1、多副本和迁移验证，再由 release owner 签收 P3 cohort 并启动 50 task/72h canary

### 2026-07-10 Main Merge / SDK Worker Release Checkpoint

- scope: 独立 app-server P0-P2 代码合入；SDK Worker `1.0.11` 稳定发布边界
- changed_code_paths: `tools/codex-agent-worker/**`、`tools/codex-app-server-worker/**`、`addons/codex-worker-agent/**`、`navigator-common/**`、`agent-framework/**`、`session-module/**`、`user-auth-module/**`、`packages/navigator-frontend/**`、`docs/migration/**`
- self_check_summary: 新 Ultra 在 SDK Worker 创建入口 fail closed，只有携带既有 `session_id` 的 SDK Ultra thread 可按 affinity drain；独立 app-server runtime 新注册默认 `DARK + disabled + 0%`，本次合入不改变生产默认路由
- verification: SDK Worker 115/115 + typecheck/build；app-server Worker 101/101 + typecheck/build/schema verify；Codex addon 227/227；Session 302/302；User Auth 71/71；Navigator Common 37/37；Agent Framework 213/213；PC Vitest 169/169 + type-check/build；launcher `CommonRepositoryOwnershipContextTest` passed
- baseline_note: `mvn test -pl launcher -am` 仍有未改动 `addons/claude-worker-agent` 的 3 个 Windows 路径归一化测试失败；本次实际变更模块的完整 Maven 测试均通过，不在本事项内扩大修复范围
- obvious_risks_or_followups: 真实 Worker-Java-SSE-PC、N-1/多副本、生产迁移及 P3 canary 仍未完成；App Server 生产启用继续禁止
- self_check_decision: ready-with-risks-for-main-merge; production-enablement-not-approved
- sdk_worker_release: `1.0.11` published for Windows / Linux / macOS；OBS `latest.json` 已切换到 `1.0.11`，三平台下载归档 SHA-256 与本地发布物一致
- sdk_worker_boundary: GPT-5.6 / Max 正常执行；新 Ultra 返回 `CODEX_ULTRA_APP_SERVER_REQUIRED`；既有 SDK Ultra thread 仅按 affinity drain
