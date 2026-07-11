# 1.4.0-SNAPSHOT

## 文档作用

- doc_type: version-index
- intended_for: root-controller | execution-agent | reviewer | release-owner
- purpose: 管理独立 Codex App Server Worker 的实现、隔离验收和 Ultra 生产门禁。

## 版本状态

- status: p0-p2-and-opt003-opt004-isolated-accepted-production-rollout-not-started
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
| P5 非 Ultra/功能 cohort | partial-isolated-rollout-not-started | `request_user_input` 与账号额度可观测已由 OPT-003/004 隔离签收；动态 catalog、approval/additional dirs/Biz/MCP 等 parity 仍需逐 cohort 证明 |
| P6 App-server Default | not-started | 是否扩大为默认由后续产品与生产门禁决定 |
| P7 SDK Retirement | N/A-deferred-by-product-decision | 旧 SDK Worker 保持现状，不删除、不迁移、不弱化发布与回滚能力 |

## 发布与回归摘要

- App-server Worker `0.1.1`: `200 total / 193 passed / 7 platform-skipped / 0 failed`；typecheck/build/schema verify 通过，schema digest 为 `6f2550bb528581f17c4c3a3857dca92c860406aa3274e314cfa726c32e395d8f`。
- App-server Worker `0.2.0` 新增原生交互输入：`215 total / 208 passed / 7 platform-skipped / 0 failed`；release SHA-256/bytes/entries=`03949845DE8C405E1CC679D5DE5FB7F2AE86734C13C16E63102BC150A003343E` / `1,634,838` / `176`，schema/typecheck/build/package 通过。
- App-server Worker `0.3.0` 新增 advisory-only 账号额度可观测并全面退役 Mini，thread 固定隐藏 rate-limit model nudge：`232 total / 225 passed / 7 platform-skipped / 0 failed`；release SHA-256/bytes/entries=`8C8CB446C861F5AD0AF04DDAAA37EA98670B515B435A6BA881AFC675CF3FA5C5` / `1,724,854` / `190`，schema/typecheck/build/package 通过。SDK Worker `121/121`，Navigator PC `208/208`，Java Codex reactor `283/283`，相关构建通过。
- App-server Worker `0.3.3` 补齐 OBS `latest.json`、Windows/Linux 稳定 bootstrap、SHA-256/bytes 强校验，以及完整同版本 no-op、可证明残缺安装 repair、失败事务证据 fail-closed 和降级拒绝：公网制品 SHA-256/bytes/entries=`efede2065ef0154d2604dee447787b34cbd0003d4e8458c432c32e40fed2a25b` / `1,802,355` / `197`；Windows exact public repair `247 total / 240 passed / 7 skipped`，WSL2/Linux exact public repair `247 total / 246 passed / 1 skipped`，均保持 stopped，重复执行 no-op，缺失 `VERSION` 或存在 lifecycle/update 失败证据时拒绝。`0.3.1` 因同版本残缺目录可误报 no-op、`0.3.2` 因 Linux repair 比较分支误拒绝而在最终交付前被 `0.3.3` 取代。Navigator PC `210/210`、相关 Playwright `2/2`、`build:check` 通过。OBS manifest 如实记录发布时 `gitDirty=true`，待后续提交闭合源码追溯，不改变生产路由门禁。
- App-server Worker `0.3.4` 已发布并修复 Linux/旧 Node 将 `tests/**/*.test.ts` 当作字面路径导致安装前校验中止的问题：改由 Node 脚本显式递归枚举测试入口；归档 SHA-256/bytes/entries=`616a0ce7cf8c017bd16e215022c3dcb24f27c37051297eff9c9623f9d6e4b440` / `1,804,723` / `198`，manifest source commit=`bbea65843d16a367e73b9d6d68fcca6768b9edc3`、`gitDirty=false`。发布流水线 `249 total / 242 passed / 7 skipped / 0 failed`，WSL2 exact public install `249 total / 248 passed / 1 skipped / 0 failed`，schema/typecheck/build 与公网 archive/bootstrap 回读校验通过。
- App-server Worker `0.3.5` 已发布并修复 Node 18 不支持 `import.meta.dirname` 导致 schema/clean build 阻断的问题；归档 SHA-256/bytes=`9b30dcffec603f55c5faa7ea322ac1f42e9e5fb094467f051e337ff9e2c90c73` / `1,805,254`，manifest source commit=`cfc5f5217c2adc72701fc44a65c908aed4329a46`、`gitDirty=false`。发布流水线 `250 total / 243 passed / 7 skipped / 0 failed`；Linux Node 18 对公网精确归档的 SHA-256、schema 与 clean build 验证通过。
- App-server Worker `0.3.6` 将 Runtime 配置收敛为 endpoint-only，OBS 真包及 Windows/Linux 公网首装、权限与 no-op 均通过；因 bootstrap 末行仍误写需要配置 `.env`，最终交付由仅修正文案的 `0.3.7` 取代。首装实际会自动生成并保留 32-byte base64 state key，创建安装目录内独立 `CODEX_HOME`，Worker token 与 `OPENAI_API_KEY` 默认留空，模型凭据继续由 Navigator ModelConfig 按任务下发。
- App-server Worker `0.3.7` 已发布为 OBS latest：归档 SHA-256/bytes/entries=`da8cb526fd619769b8f4389fca03ed047d22590c2d63f5bd670f890a9ef98ec5` / `1,808,569` / `198`，manifest source commit=`4e9c1bdd26dc1a1dcbd8d562a5686cb608624c6e`、`gitDirty=false`。发布流水线 `243 passed + 7 skipped`，Linux 公网精确真包 `249 passed + 1 skipped`，自动 state key/CODEX_HOME、空 token/API Key、0600/0700、默认停止和 ready/start 文案均通过；两端 bootstrap 和归档已由发布器回读校验。
- Runtime 控制面已加入同 ID 新建不可变 revision 和可逆的退役/归档：归档使用 routing epoch CAS，原子转为 Disabled + Dark 并排除新路由，历史 affinity 仍保留；Java reactor `301/301`、PC `214/214`、Playwright 桌面/窄屏 `2/2`、Windows native `build:check`、MySQL 8.0/8.4 迁移通过。
- App Server Worker HTTP token 改为可选：空值表示关闭 Worker HTTP 认证并放行 capability/task/control API，不再影响 readiness；非空时仍强制 Bearer `401/403`。Runtime 注册和 PC 字段同步改为可选，空值模式仅适用于 loopback 或可信网络。
- v5 release SHA-256/bytes/entries: `b6271e5a3220b0253d97b6d05c9fe5f5561331655e27faccd8ad254fbf6c31d9` / `1,500,249` / `168`；双构建字节一致、路径扫描通过，Windows/WSL exact-package install/start/real Ultra/running update/stop 与 zero-residue 均通过；旧 `642121...`、`4ebb...` 与 Windows update 被阻断的 v4 `71a0...` 均已淘汰。
- Codex Java addon: `259/259`；Session focused: `7/7`；reconciliation: `10/10`；Metadata Query: `13/13`；Launcher ownership context: `1/1`；Code Review client context: `1/1`。raw full reactor 在 Windows 被 Surefire fork/path 基础设施问题阻断，已执行的受影响定向测试无断言失败。
- Legacy SDK Worker 保持现有 SDK 设计并拒绝 Ultra，`116/116`、typecheck/build 通过；本次仅修正 Windows 上 POSIX server-script 路径的测试断言。Navigator PC: `179/179`，`build:check` 通过。
- MySQL migration: 8.0.44/8.4.8 通过；N-1 严格 legacy GET、迁移后 validate/CRUD/软删除通过。
- 已执行旧版 affinity SQL 的环境必须再执行幂等补丁 `docs/migration/2026-07-10-codex-task-created-at-epoch-ms.sql`。
- 隔离真实链路任务 `20260711-8023` / Session `b2bc4a9c-3134-4d24-af50-5709ab9b91e6` 完成：result 精确为 `FINAL_RESULT_OK`、文件精确为 `FINAL_NATIVE_RESULT_OK`、native SSE `5`、snapshot `1`，敏感信息暴露检查均为 false；PC 刷新前后最终消息均为 `1`，native 进度 `1/1`，desktop/320px 无溢出、请求或控制台错误。
- OPT-003 隔离全链路：单选任务 `20260711-0847`、多问题/重连任务 `20260711-b814` 均在原 turn 完成；活动态误发 `continue` 被拒绝且任务数保持 `1`，SSE/浏览器断流只恢复 pending interaction；desktop/320px 无溢出或错误。
- OPT-004 隔离全链路：revision 4 真实读取两个 ChatGPT quota bucket，Worker/Java owner endpoint 均为 200，rev1-3 规范化为 `UNSUPPORTED`，未认证 Java 请求为 401；PC desktop/320px 显示 5 小时/7 天窗口且无 Mini/切模入口。revision 4 保持 `DARK`，未改变生产路由。

## 文档清单

- [需求](./workitems/OPT-001-independent-codex-app-server-worker-requirement.md)
- [实施计划与代码清单](./workitems/OPT-001-independent-codex-app-server-worker-plan.md)
- [实施与门禁进度](./workitems/OPT-001-independent-codex-app-server-worker-progress.md)
- [Codex GPT-5.6 分组模型目录与 Runtime 边界](./workitems/OPT-002-codex-model-catalog-boundary.md)
- [OPT-002 实施进度](./workitems/OPT-002-codex-model-catalog-progress.md)
- [Codex App Server 原生交互输入](./workitems/OPT-003-codex-app-server-interactive-input.md)
- [Codex 额度感知与 Mini 下线](./workitems/OPT-004-codex-rate-limit-awareness-no-fallback.md)
- [OPT-004 实现质量检查](./quality/OPT-004-rate-limit-awareness-implementation-quality.md)
- [OPT-004 测试证据覆盖审计](./coverage/OPT-004-rate-limit-awareness-coverage-audit.md)
- [OPT-004 隔离验收记录](./acceptance/OPT-004-rate-limit-awareness-acceptance.md)
- [OPT-004 额度与 PC 证据](./evidence/OPT-004-rate-limit-awareness-v1.json)
- [OPT-004 PC desktop 证据](./evidence/OPT-004-pc-desktop-1280.png)
- [OPT-004 PC 320px 证据](./evidence/OPT-004-pc-mobile-320.png)
- [OPT-003 实现质量检查](./quality/OPT-003-interactive-input-implementation-quality.md)
- [OPT-003 测试证据覆盖审计](./coverage/OPT-003-interactive-input-coverage-audit.md)
- [OPT-003 隔离验收记录](./acceptance/OPT-003-interactive-input-acceptance.md)
- [OPT-003 Worker/原生输入证据](./evidence/OPT-003-ultra-native-input-v1.json)
- [OPT-003 PC 交互证据](./evidence/OPT-003-pc-interactive-input-v1.json)
- [BUG-001 Codex Resume 后 Shell 工具丢失](./workitems/BUG-001-codex-resume-shell-tool-loss.md)
- [P0-P2 实现质量检查](./quality/OPT-001-p0-p2-implementation-quality.md)
- [OPT-002 Codex 分组模型目录实现质量检查](./quality/OPT-002-codex-model-catalog-implementation-quality.md)
- [BUG-001 修复实现质量检查](./quality/BUG-001-codex-resume-shell-fix-quality-review.md)
- [P0-P2 测试证据覆盖审计](./coverage/OPT-001-p0-p2-coverage-audit.md)
- [OPT-002 Codex 分组模型目录测试证据覆盖审计](./coverage/OPT-002-codex-model-catalog-coverage-audit.md)
- [P0-P2 隔离验收与 P3-P7 边界记录](./acceptance/OPT-001-p0-p7-acceptance.md)
- [Windows v5 exact-package evidence](./evidence/OPT-001-exact-package-windows-v5.json)
- [WSL v5 exact-package evidence](./evidence/OPT-001-exact-package-wsl-v5.json)
- [Navigator Ultra task evidence](./evidence/OPT-001-navigator-ultra-task-v5.json)
- [PC final acceptance evidence](./evidence/OPT-001-pc-final-acceptance-v5.json)
- [OBS latest / public bootstrap 0.3.3 evidence](./evidence/OPT-001-obs-release-0.3.3.json)
- [OBS latest / public bootstrap 0.3.4 evidence](./evidence/OPT-001-obs-release-0.3.4.json)
- [OBS latest / public bootstrap 0.3.5 evidence](./evidence/OPT-001-obs-release-0.3.5.json)
- [OBS latest / zero-config endpoint runtime 0.3.7 evidence](./evidence/OPT-001-obs-release-0.3.7.json)
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
- [BUG-014 Linux 安装测试 glob 阻断](./workitems/BUG-014-app-worker-linux-test-glob-install-blocker.md)
- [BUG-015 Node 18 `import.meta.dirname` 阻断](./workitems/BUG-015-app-worker-node18-import-meta-dirname.md)
- [BUG-016 Codex Runtime list Java 签名兼容](./workitems/BUG-016-codex-runtime-list-source-compatibility.md)

## 当前边界

- P0-P2 已完成隔离验收；该结论只覆盖本地/隔离 release、控制面、真实 Ultra 链路和 PC 体验，不批准外部生产路由，也不得计入 P3 样本。
- P3 必须在目标环境采集至少 50 个 terminal Ultra task、连续 72 小时和至少 2 次实例轮换；本地重复 smoke 不计入。
- 生产 duplicate side effect、affinity mismatch、credential/raw child leak 当前是 0 个生产样本，不能表述为已证明为零。
- 旧 SDK Worker 保持既有设计；本版本后续工作仅围绕 app-server Worker 的生产 canary 与可选 cohort。
- OPT-003 的 `request_user_input` 子集已隔离签收；SSE 断流只允许重连/status/snapshot 同步，活动 turn 的 resume/`continue` 必须被 Java 和 Worker 拒绝。该结论不批准生产路由。
- OPT-004 的额度状态仅用于 owner 控制面展示，不进入 task SSE、路由、队列或自动切模；Mini 已从活动支持面移除。该结论不批准生产路由，也不覆盖 per-task/Biz Codex Home。
- BUG-001、BUG-003、BUG-007、BUG-009、BUG-010 和 BUG-013 已完成隔离闭环；先前失败任务与 v4 制品只保留为复现证据。
- BUG-008/011/012 的隔离自动化和 final Worker full/package 已通过，其 production canary、memory soak 和 fairness soak 证据仍属于未开始的 P3。
