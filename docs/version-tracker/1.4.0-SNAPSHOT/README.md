# 1.4.0-SNAPSHOT

## 文档作用

- doc_type: version-index
- intended_for: root-controller | execution-agent | reviewer | release-owner
- purpose: 管理独立 Codex App Server Worker 的实现、隔离验收和 Ultra 生产门禁。

## 版本状态

- status: p0-p2-isolated-accepted-production-rollout-not-started
- primary_workitem: `OPT-001`
- implementation_started: yes
- production_routing_changed: no
- production_enablement: not-approved

## 版本目标

新增独立部署的 `codex-app-server-worker`，以 Codex app-server 作为执行引擎并支持固定 CLI `0.144.1` 已验证的全部模型与 reasoning 档位。现有 `codex-agent-worker` 保持 SDK / `codex exec` 稳定路径和非 Ultra 行为、最高支持 Max，并拒绝所有 Ultra 请求；它不承载 app-server。平台先完成幂等任务接受、受控 runtime registry、不可变任务/会话 affinity 和能力握手，再将 Ultra 会话灰度到新 Worker。P7 SDK retirement 已延后，不属于 `1.4.0-SNAPSHOT` 的交付范围。

## 已确认决策

1. 新 Worker 是独立进程、发布物、端口、日志、运行目录和状态域。
2. 新 Worker 技术上支持全部已声明模型/reasoning；Ultra 是首批计划生产路由，不代表已经批准生产切流。
3. 外部仍使用 `workerBackend=OPENAI_CODEX` 和 `providerType=codex-worker|codex-biz-worker`，不新增 Provider、Task、Session 或 PC 页面。
4. Task/Session/runtime instance affinity 在接受后不可变；回滚只停止新分配，禁止跨 runtime 重放 prompt。
5. PC 继续使用统一 SSE/snapshot 展示 app-server 原生子任务；不暴露 endpoint、token、Codex Home 或原始子线程内容。
6. SDK retirement 已按产品决定延后，不属于本版本目标；未来改变该决定必须另建 workitem。

## 阶段状态

| Gate | 当前状态 | 证据边界 |
|---|---|---|
| P0 契约 | completed | 幂等接受、durable state/ESN、capability、registry、immutable affinity、rollback 语义已冻结并回归 |
| P1 Dark Worker | isolated-accepted | Worker `200` 项回归、Canary、持久化、Pool、生命周期、可复现 v5 制品和 Windows/WSL exact-package 运维矩阵均通过 |
| P2 双 Runtime 控制面 | isolated-accepted | 双实例 affinity、Java/Session/PC、MySQL 8.0.44/8.4.8、N-1、`ddl-auto=validate`、共享 availability、真实 Ultra/SSE/刷新和 desktop/320px 体验均通过隔离验收 |
| P3 Ultra Canary | implementation-ready-rollout-not-started | 外部生产证据仍为 0/50 terminal task、0/72h、0/2 rotation；release owner 未签收 |
| P4 Ultra Default | not-started | 依赖 P3 独立生产签收 |
| P5 非 Ultra/功能 cohort | not-started | 动态 catalog 与 approval/additional dirs/server requests/Biz 等 parity 仍需逐 cohort 证明 |
| P6 App-server Default | not-started | 是否扩大为默认由后续产品与生产门禁决定 |
| P7 SDK Retirement | N/A-deferred-by-product-decision | 旧 SDK Worker 保持现状，不删除、不迁移、不弱化发布与回滚能力 |

## 发布与回归摘要

- App-server Worker `0.1.1`: `200 total / 193 passed / 7 platform-skipped / 0 failed`；typecheck/build/schema verify 通过，schema digest 为 `6f2550bb528581f17c4c3a3857dca92c860406aa3274e314cfa726c32e395d8f`。
- v5 release SHA-256/bytes/entries: `b6271e5a3220b0253d97b6d05c9fe5f5561331655e27faccd8ad254fbf6c31d9` / `1,500,249` / `168`；双构建字节一致、路径扫描通过，Windows/WSL exact-package install/start/real Ultra/running update/stop 与 zero-residue 均通过；旧 `642121...`、`4ebb...` 与 Windows update 被阻断的 v4 `71a0...` 均已淘汰。
- Codex Java addon: `259/259`；Session focused: `7/7`；reconciliation: `10/10`；Metadata Query: `13/13`；Launcher ownership context: `1/1`；Code Review client context: `1/1`。raw full reactor 在 Windows 被 Surefire fork/path 基础设施问题阻断，已执行的受影响定向测试无断言失败。
- Legacy SDK Worker 保持现有 SDK 设计并拒绝 Ultra，`116/116`、typecheck/build 通过；本次仅修正 Windows 上 POSIX server-script 路径的测试断言。Navigator PC: `179/179`，`build:check` 通过。
- MySQL migration: 8.0.44/8.4.8 通过；N-1 严格 legacy GET、迁移后 validate/CRUD/软删除通过。
- 已执行旧版 affinity SQL 的环境必须再执行幂等补丁 `docs/migration/2026-07-10-codex-task-created-at-epoch-ms.sql`。
- 隔离真实链路任务 `20260711-8023` / Session `b2bc4a9c-3134-4d24-af50-5709ab9b91e6` 完成：result 精确为 `FINAL_RESULT_OK`、文件精确为 `FINAL_NATIVE_RESULT_OK`、native SSE `5`、snapshot `1`，敏感信息暴露检查均为 false；PC 刷新前后最终消息均为 `1`，native 进度 `1/1`，desktop/320px 无溢出、请求或控制台错误。

## 文档清单

- [需求](./workitems/OPT-001-independent-codex-app-server-worker-requirement.md)
- [实施计划与代码清单](./workitems/OPT-001-independent-codex-app-server-worker-plan.md)
- [实施与门禁进度](./workitems/OPT-001-independent-codex-app-server-worker-progress.md)
- [Codex GPT-5.6 模型目录与 Runtime 边界](./workitems/OPT-002-codex-model-catalog-boundary.md)
- [BUG-001 Codex Resume 后 Shell 工具丢失](./workitems/BUG-001-codex-resume-shell-tool-loss.md)
- [P0-P2 实现质量检查](./quality/OPT-001-p0-p2-implementation-quality.md)
- [BUG-001 修复实现质量检查](./quality/BUG-001-codex-resume-shell-fix-quality-review.md)
- [P0-P2 测试证据覆盖审计](./coverage/OPT-001-p0-p2-coverage-audit.md)
- [P0-P2 隔离验收与 P3-P7 边界记录](./acceptance/OPT-001-p0-p7-acceptance.md)
- [Windows v5 exact-package evidence](./evidence/OPT-001-exact-package-windows-v5.json)
- [WSL v5 exact-package evidence](./evidence/OPT-001-exact-package-wsl-v5.json)
- [Navigator Ultra task evidence](./evidence/OPT-001-navigator-ultra-task-v5.json)
- [PC final acceptance evidence](./evidence/OPT-001-pc-final-acceptance-v5.json)
- [BUG-001 App-server delta 消息碎片](./workitems/BUG-001-app-server-delta-message-fragmentation.md)
- [BUG-002 `.env` 外部状态目录](./workitems/BUG-002-app-worker-dotenv-state-dir.md)
- [BUG-003 Worker View 移动布局](./workitems/BUG-003-worker-view-mobile-layout.md)
- [BUG-004 stop/update 外部运行目录](./workitems/BUG-004-app-worker-operations-dotenv-run-dir.md)
- [BUG-005 Windows 安装路径空格](./workitems/BUG-005-app-worker-windows-install-path-spaces.md)
- [BUG-006 macOS Bash 更新候选发现](./workitems/BUG-006-app-worker-macos-update-candidate-discovery.md)
- [BUG-007 最终结果聚合](./workitems/BUG-007-app-server-final-result-aggregation.md)
- [BUG-008 Canary 证据正确性](./workitems/BUG-008-canary-evidence-correctness.md)
- [BUG-009 生命周期进程树与 stop outcome](./workitems/BUG-009-lifecycle-process-tree-and-stop-outcome.md)
- [BUG-010 PC app-server 边界与共享 availability](./workitems/BUG-010-pc-app-server-boundary-and-shared-availability.md)
- [BUG-011 terminal broadcast 与 TaskStore 上界](./workitems/BUG-011-terminal-broadcast-and-task-store-bounds.md)
- [BUG-012 Pool 跨 lane LRU 退役](./workitems/BUG-012-pool-cross-lane-lru-retirement.md)
- [BUG-013 Windows 进程树终止等待竞态](./workitems/BUG-013-windows-process-tree-termination-settle-race.md)

## 当前边界

- P0-P2 已完成隔离验收；该结论只覆盖本地/隔离 release、控制面、真实 Ultra 链路和 PC 体验，不批准外部生产路由，也不得计入 P3 样本。
- P3 必须在目标环境采集至少 50 个 terminal Ultra task、连续 72 小时和至少 2 次实例轮换；本地重复 smoke 不计入。
- 生产 duplicate side effect、affinity mismatch、credential/raw child leak 当前是 0 个生产样本，不能表述为已证明为零。
- 旧 SDK Worker 保持既有设计；本版本后续工作仅围绕 app-server Worker 的生产 canary 与可选 cohort。
- BUG-001、BUG-003、BUG-007、BUG-009、BUG-010 和 BUG-013 已完成隔离闭环；先前失败任务与 v4 制品只保留为复现证据。
- BUG-008/011/012 的隔离自动化和 final Worker full/package 已通过，其 production canary、memory soak 和 fairness soak 证据仍属于未开始的 P3。
