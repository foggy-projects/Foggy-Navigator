# OPT-001 独立 Codex App Server Worker 进度

## 基本信息

- doc_type: progress
- version: `1.4.0-SNAPSHOT`
- status: p0-p2-isolated-accepted-production-rollout-not-started
- requirement: [requirement](./OPT-001-independent-codex-app-server-worker-requirement.md)
- plan: [implementation plan](./OPT-001-independent-codex-app-server-worker-plan.md)
- current_stage: P0-P2-isolated-accepted-P3-not-approved
- last_updated_at: 2026-07-11
- production_routing_changed: no

## Development Progress

| Stage | 状态 | 已完成 | 未完成/边界 |
|---|---|---|---|
| P0 | completed | CLI/schema lock、task accept v1、durable task/event state、registry、capability、immutable task/session/instance affinity、rollback 语义 | 无代码 blocker |
| P1 | isolated-accepted | Worker 模型/幂等/abort/recovery、Canary evidence、进程树、bounded store/broadcast、跨 lane Pool、final full、可复现 v5 制品及 Windows/WSL exact-package 运维矩阵 | 无隔离 blocker；生产启用属于 P3 |
| P2 | isolated-accepted | Java registry/router/minimum availability/SSE、A/B affinity、MySQL 8.0/8.4、N-1、迁移/validate、PC `179/179`、真实 Ultra/历史刷新和 desktop/320px | 无隔离 blocker；生产拓扑与 release-owner 签收属于 P3 |
| P3 | implementation-ready-rollout-not-started | 10% cohort、50 task、72h、2 rotations、SLO 和零容忍指标已固化；本地 collector fail-closed | release owner/目标环境未签收；0/50、0/72h、0/2 |
| P4 | not-started | 设计完成 | 依赖 P3 生产签收 |
| P5 | not-started | 静态模型矩阵已验证 | 动态 catalog、approval/additional dirs/server requests/Biz/MCP 等逐 cohort parity 未关闭 |
| P6 | not-started | 设计完成 | 是否成为全量默认由后续产品和生产门禁决定 |
| P7 | N/A-deferred-by-product-decision | 旧 SDK Worker 保持 `1.0.11` 现有设计与发布/回滚能力 | 本版本不执行 retirement |

## P0 决策

| 决策 | 结论 |
|---|---|
| CLI/schema | `@openai/codex 0.144.1`；schema digest `6f2550bb528581f17c4c3a3857dca92c860406aa3274e314cfa726c32e395d8f` |
| task accept | `Idempotency-Key=Navigator taskId`；同 key 同请求复用，异请求 409 |
| durable state | AES-256-GCM 请求密文、append-only task/event journal、连续 ESN、committed 后不重放 |
| runtime control | revision registry、owner 校验、可选加密 token（空值=no-auth）、readiness/capability、CAS rollout/routing epoch |
| affinity | Task/Session 固定 runtime/revision/type/instance/workerTaskId；resume/status/subscribe/abort/delete 不重选 |
| old SDK boundary | 非 Ultra 保持原行为；新 Ultra 返回 `CODEX_ULTRA_APP_SERVER_REQUIRED`；已有 SDK Ultra 仅原地 drain |
| SDK lifecycle | 长期保留；退役决定延后，未来另立 workitem |

## Verification

| Lane | 结果 | 证据 |
|---|---|---|
| App-server Worker | pass | `200 total / 193 passed / 7 platform-skipped / 0 failed`；typecheck/build/schema verify 通过；schema digest `6f2550bb528581f17c4c3a3857dca92c860406aa3274e314cfa726c32e395d8f` |
| Release `0.1.1` | pass-artifact | v5 SHA-256 `b6271e5a3220b0253d97b6d05c9fe5f5561331655e27faccd8ad254fbf6c31d9`；`1,500,249` bytes；`168` entries；双构建字节一致，路径扫描通过 |
| Release operations | pass-isolated | Windows/WSL v5 install/start/真实 Ultra/running update/stop、外部 state/CODEX_HOME 保留和 process-tree residue 0 均通过 |
| Release `0.3.3` OBS latest | pass-published | `efede2065ef0154d2604dee447787b34cbd0003d4e8458c432c32e40fed2a25b` / `1,802,355` bytes / `197` entries；OBS archive/checksum/bootstrap/latest 上传 200，发布器重新 GET 并校验 archive 与 bootstrap 字节；Windows `240 passed + 7 skipped`、WSL2/Linux `246 passed + 1 skipped` exact public repair 均 stopped、repeat no-op，缺失版本身份与失败事务证据均 fail-closed；`0.3.1/0.3.2` 的残缺安装判定缺陷在最终交付前已由 `0.3.3` 取代 |
| Release `0.3.4` Linux test launcher | pass-published | 修复旧 Node/Linux 将递归 glob 当作字面路径的安装阻断；`616a0ce7cf8c017bd16e215022c3dcb24f27c37051297eff9c9623f9d6e4b440` / `1,804,723` bytes / `198` entries，source `bbea6584`、`gitDirty=false`；发布流水线 `242 passed + 7 skipped`，WSL2 exact public install `248 passed + 1 skipped`，Linux Node 18 launcher `10/10`，schema/typecheck/build 与公网 archive/bootstrap 回读通过 |
| Release `0.3.5` Node 18 validation | pass-published | 修复 schema/clean build 使用 Node 18 不支持的 `import.meta.dirname`；`9b30dcffec603f55c5faa7ea322ac1f42e9e5fb094467f051e337ff9e2c90c73` / `1,805,254` bytes，source `cfc5f521`、`gitDirty=false`；发布流水线 `243 passed + 7 skipped`，Linux Node 18 公网精确归档 SHA-256/schema/clean build 与 release-tooling `11/11` 通过 |
| Release `0.3.6` zero-config endpoint runtime | superseded-before-final | OBS 真包与 Windows/Linux 公网首装均通过，但 bootstrap 末行仍误写“until .env is configured”；配置本身已完整且不影响运行，最终 latest 由修正文案的 `0.3.7` 取代 |
| Release `0.3.7` zero-config endpoint runtime | pass-published | `da8cb526fd619769b8f4389fca03ed047d22590c2d63f5bd670f890a9ef98ec5` / `1,808,569` bytes / `198` entries，source `4e9c1bdd`、`gitDirty=false`；首装自动生成并持久化 32-byte base64 state key，创建安装目录内独立 `CODEX_HOME`，Worker token 与 `OPENAI_API_KEY` 保持为空；发布流水线 `243 passed + 7 skipped`，Linux 公网真包 `249 passed + 1 skipped`、0600/0700、stopped 和 ready/start 文案通过；Windows/WSL `0.3.6` 配置/no-op 证据保持，最终 Windows bootstrap 字节已由发布器与公网回读校验 |
| Legacy SDK Worker | pass | `116/116`；typecheck/build；现有 SDK 设计保持，Ultra fail-closed；仅测试断言适配 Windows 上的 POSIX server-script 路径 |
| Codex Java reactor | pass-scoped | 当前 Codex reactor `301/301`；runtime lifecycle/auth 定向 `103/103`；历史 raw full reactor 的 Windows Surefire fork/path 基础设施限制不影响本次已执行结果 |
| Runtime revision/archive | pass-isolated | Java 定向 `103/103`，覆盖 owner 校验、CAS 归档/恢复、新路由排除归档 revision、历史 affinity 保留和可选 token；PC 定向 `37/37`、Playwright 桌面/窄屏 `2/2`、type-check 通过 |
| Worker optional HTTP auth | pass-isolated | 空 token 放行 capability/task/control 且 readiness 不降级，非空 token 保持 Bearer `401/403`；Worker full `250 total / 243 passed / 7 skipped`，typecheck/build/schema 通过；Java Runtime `103/103`、PC 定向 `37/37` |
| HTTP client ownership | pass | Metadata `13/13`；Launcher context `1/1`；Code Review context `1/1`，专用/默认 RestTemplate 无歧义 |
| Navigator PC | pass-isolated | Vitest `214/214`、Windows native `build:check`、availability/process/runtime lifecycle boundary 通过；Playwright runtime desktop/390px `2/2`，历史刷新与 native 证据保持 |
| Real Worker chain | pass-isolated | Task `20260711-8023` / Session `b2bc4a9c-3134-4d24-af50-5709ab9b91e6` COMPLETED；result=`FINAL_RESULT_OK`、文件=`FINAL_NATIVE_RESULT_OK`、native SSE=`5`、snapshot=`1`，prompt/Bearer/Worker token 暴露检查均为 false |
| MySQL migration | pass-isolated | MySQL 8.0.44/8.4.8；current `ddl-auto=validate`；N-1 legacy GET、迁移后 validate/CRUD/软删除 |
| Epoch compatibility | pass-isolated | 模拟旧 affinity schema 缺列，幂等补丁连续执行两次后 current prod validate 通过 |
| Multi-replica affinity | pass-isolated | A/B 同 physical Worker，DARK/default 路由、非目标实例 404、expected-instance mismatch fail-closed、恢复与 delete affinity 均通过 |

## Acceptance Defect Closure

- [BUG-014 Linux install test glob blocker](./BUG-014-app-worker-linux-test-glob-install-blocker.md): closed in published `0.3.4`; exact WSL2 public install passed.
- [BUG-015 Node 18 import.meta.dirname blocker](./BUG-015-app-worker-node18-import-meta-dirname.md): closed in published `0.3.5`; exact public archive schema/build passed on Linux Node 18.

| Defect | 状态 | 静态/自动化证据 | Final live |
|---|---|---|---|
| [BUG-001](./BUG-001-app-server-delta-message-fragmentation.md) | closed-isolated | delta subtype、turn/start 同批缓冲、Java `TEXT_CHUNK` 与 Worker `200` 项回归通过 | PC 最终消息单卡片，刷新后仍为 `1` |
| [BUG-002](./BUG-002-app-worker-dotenv-state-dir.md) | closed | 最终真包 Windows/Ubuntu 外部目录和 identity 保留通过 | N/A |
| [BUG-003](./BUG-003-worker-view-mobile-layout.md) | closed-isolated | focused layout、full PC `179/179` 与 `build:check` 通过 | desktop/320px Playwright 无溢出、失败请求或控制台错误 |
| [BUG-004](./BUG-004-app-worker-operations-dotenv-run-dir.md) | closed | 最终真包 Windows/Ubuntu running update/fault rollback 通过 | N/A |
| [BUG-005](./BUG-005-app-worker-windows-install-path-spaces.md) | closed | 最终真包 Windows 含空格/`#` 路径矩阵通过 | N/A |
| [BUG-006](./BUG-006-app-worker-macos-update-candidate-discovery.md) | closed | Bash 3.2、唯一/多候选和 Ubuntu 真包矩阵通过 | N/A |
| [BUG-007](./BUG-007-app-server-final-result-aggregation.md) | closed-isolated | last canonical、recovered `assistant_text` 去重、Session `7/7`、reconciliation `10/10`、Worker `200` 项回归通过 | exact result=`FINAL_RESULT_OK`，刷新前后 final message 均为 `1` |
| [BUG-008](./BUG-008-canary-evidence-correctness.md) | fixed-isolated | requested model、denominator 隔离、sanitized affinity violation、lease reclaim claim 覆盖 | 生产证据属于 P3，当前 0 样本 |
| [BUG-009](./BUG-009-lifecycle-process-tree-and-stop-outcome.md) | closed-isolated | process-tree/nonce/runtime cleanup、不可读 evidence fail-closed、Worker `200` 项回归与 v5 制品通过 | Windows/WSL exact-package lifecycle/update 与 runtime residue 0 通过 |
| [BUG-010](./BUG-010-pc-app-server-boundary-and-shared-availability.md) | closed-isolated | Java `259/259`，frontend `179/179`，minimum DTO、模型级 availability 与 alias fail-closed 覆盖 | shared/owner availability、pool-managed boundary 与 PC live 通过 |
| [BUG-011](./BUG-011-terminal-broadcast-and-task-store-bounds.md) | fixed-isolated | terminal broadcast retirement、105 large histories、single ciphertext/legacy journal 和 final Worker full/package 覆盖 | P3 memory soak 待补 |
| [BUG-012](./BUG-012-pool-cross-lane-lru-retirement.md) | fixed-isolated | LRU idle replacement、busy exclusion、concurrent capacity、close failure 和 final Worker full/package 覆盖 | P3 fairness soak 待补 |
| [BUG-013](./BUG-013-windows-process-tree-termination-settle-race.md) | closed-isolated | Windows bounded exact-identity polling、delayed-disappearance regression 与 final Worker full 通过 | v5 Windows/WSL exact-package running update 与 zero-residue 通过 |

## Experience

| 检查项 | 状态 | 说明 |
|---|---|---|
| Runtime 配置/健康 | pass-isolated | PC 展示每个 runtime 的 readiness、routing policy、Ultra Default；token 不回显 |
| Runtime 修订/归档 | pass-isolated | 同 Runtime ID 可新建下一 revision；默认隐藏已归档项，可显式查看并恢复为 Disabled + Dark；归档确认明示历史 affinity 保留 |
| Runtime Worker token | pass-isolated | PC 字段改为“Worker 服务令牌（可选）”；空值可注册并刷新 Ready，非空值仍只提交不回显 |
| Ultra 可用性 | pass-isolated | 只有 Ready app-server runtime 时可新建 Ultra；不静默降级 Max/xhigh/SDK |
| Shared Worker availability | pass-isolated | minimum-disclosure API、ALL_CANARY@0、shared-user/owner availability 与 PC live 通过 |
| App-server process boundary | pass-isolated | managed Worker 跳过 legacy probes，真实 pool-managed PC 视图通过 |
| 原生子任务 | pass-isolated | 新任务 native SSE `5`、snapshot `1`、PC `1/1`，刷新后保持闭合 |
| Responsive | pass-isolated | desktop/320px 控件可达、无水平溢出、失败请求或控制台错误 |
| Settings 安装帮助 | pass | 新增 Codex App Server Tab 与无版本 OBS 命令；首装说明固定 state key、独立 CODEX_HOME、空 Worker token/API Key 和 ModelConfig 凭据边界；Worker 编辑弹窗使用基本信息/连接工具/Codex/Gemini Tabs；相关 Playwright `3/3`、PC Vitest `214/214`、`build:check` 通过 |
| Production account/permissions | not-run | 真实生产账号、网络和 cohort 未签收 |

## Canary Thresholds

| 指标 | 门槛 | 外部生产当前值 |
|---|---|---|
| cohort | 指定物理 Worker，仅新建非 Biz Ultra，稳定 hash 10% | not-started |
| terminal tasks | >= 50，成功/失败/abort 均计入，不重复计人工 smoke | 0/50 |
| observation | 连续 >= 72h，至少 2 次 app-server instance 轮换 | 0/72h；0/2 |
| success/error | 成功率 >= 98%；runtime/transport 内部错误 <= 1% | no samples |
| latency | 首事件 p95 <= baseline 1.25x；完成 p95 <= baseline 1.30x | no samples |
| recovery | crash <= 1/100 task；可恢复 committed task 恢复率 100%；unknown=0 | no samples |
| resource | instance RSS p95 <= 1.5 GiB；pool acquire p95 <= 5s；timeout/reject <= 0.5% | no samples |
| duplicate side effect | 0 | no samples |
| affinity mismatch | 0 | no samples |
| credential/raw child leak | 0 | no samples |

## Blockers And Follow-ups

- P3 release owner 尚未签收目标环境、cohort、监控和回滚窗口；生产启用保持禁止。
- P3 需要真实 50 task/72h/2 rotations，隔离 smoke 不得计入。
- 固定 CLI 静态 catalog 不等于账号动态 catalog；未知未来模型不得自动路由。
- P5 功能 parity 仍有 approval、additional directories、interactive server request、Biz/MCP 等开放项。
- 已执行旧版 `2026-07-10-codex-runtime-affinity.sql` 的环境须执行 `2026-07-10-codex-task-created-at-epoch-ms.sql`，不能重跑整份一次性脚本。
- 已部署 runtime-affinity schema 的环境在启动本版 `ddl-auto=validate` 前，须额外执行 `docs/migration/2026-07-11-codex-runtime-archive.sql`。
- Raw full reactor 在 Windows Surefire fork/path 基础设施阶段被阻断；没有已执行测试的断言失败，本次相关模块和上下文测试均通过。
- P0-P2 隔离验收证据不得计入 P3 的生产 task、观察窗口或实例轮换；v4 Windows update BLOCKED 证据只保留在 BUG-013 作为历史复现。
- OBS `0.3.3` manifest 记录 source commit `61204f83748a7def103f7624b4ccc3d2d6c4517d` 且 `gitDirty=true`；当前公网制品已按字节验收，但下一次代码提交必须包含对应发布源码并复核可复现哈希，不能把 dirty manifest 当作生产 provenance 已闭环。

## Execution Check-in

- completed_work: 独立 Worker、双 runtime 控制面、runtime 新建修订与可逆归档、可选 HTTP token、首装自动 state key/CODEX_HOME、生命周期/release、真实 Ultra/SSE/native、PC 刷新与 responsive 隔离验收完成；`0.3.7` 已发布 OBS latest 并完成公网 provenance/bootstrap/Linux exact-package 验证
- touched_areas: `tools/codex-app-server-worker`、Codex Java addon、Session、Navigator PC、安装指南、migration 与版本文档
- self_check: formal quality gate、coverage audit 与 isolated acceptance 已回写；未扩张到生产 rollout 或 SDK retirement
- test_status: isolated-pass-public-published；Worker release `250 total / 243 passed / 7 skipped`、Linux public exact-package `250 total / 249 passed / 1 skipped`、Java `301/301`、PC `214/214`、Playwright `3/3`、Worker/PC build 与 schema 通过；Windows public `0.3.6` fresh/no-op 与 Linux final `0.3.7` 32-byte key/0600 env/0700 home/stopped smoke 通过；MySQL 8.0/8.4 本批此前通过，本次复跑被本机 Docker Linux Engine `500` 阻断
- raw_reactor_caveat: Windows Surefire fork/path 基础设施阻断 raw full reactor；受影响定向测试无断言失败，不声明 `1342/1342`
- implementation_decision: p0-p2-isolated-accepted
- production_enablement: not-approved
- P3_entry: not-approved
- old_sdk_worker: retained-unchanged-design
- isolated_experience: accepted
- acceptance_readiness: isolated-accepted-production-blocked
- remaining_risks: P3 生产账号、网络、监控、release owner、50 task、72h、2 rotations 和 P5 parity 均无生产证据；OBS manifest 的 dirty-source provenance 待后续 commit/reproducibility 复核
- next_action: release owner 完成目标环境与回滚窗口签收后，另行批准并启动 P3；P4-P6 在 P3 独立签收前不得开始
