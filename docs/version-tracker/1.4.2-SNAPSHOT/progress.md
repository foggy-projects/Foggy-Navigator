# 1.4.2 平台治理与历史能力收口进度

## 文档作用

- doc_type: progress
- intended_for: root-controller | execution-agent | reviewer | signoff-owner
- purpose: 按 P0-P7 记录实现、测试、体验、证据、风险和后置评审状态。

## 基本信息

- version: `1.4.2-SNAPSHOT`
- status: in-progress
- requirement: [REQ-001](./requirements/REQ-001-platform-governance-and-legacy-cleanup.md)
- implementation_plan: [Implementation Plan](./implementation-plan.md)
- owner_decision_review: [Owner Decision Review](./owner-decision-review.md)
- implementation_started_at: `2026-07-14`
- last_updated_at: `2026-07-14`
- production_routing_changed: no
- production_enablement: not-applicable
- formal_quality_gate: not-started
- coverage_audit: not-started
- acceptance_status: not-started

## 记录规则

1. 只有实际执行并获得可定位结果的命令、日志、截图、报告或运行态记录才能登记为 evidence。
2. 用户提供的现状和静态线索标记为 `planning-input` 或 `pending-verification`，不冒充本版本测试结果。
3. 未运行的测试必须写 `not-run` 和原因；不得用“已编写测试”替代“测试通过”。
4. 静态无引用不等于已完成安全删除。Owner 已免除获批 dev-only 切片的生产流量/客户兼容等待，但仓内引用、精确环境、完整切片、测试和回滚仍须记录；发现共享/生产资源时停止并重审。
5. 隔离环境签收与生产批准分别记录；前者不得自动把 `production_enablement` 改为 approved。

## 规划包落档验证

以下是 `2026-07-13` 规划包初次落档的历史检查，不构成后续业务测试或生产批准；实施已于 `2026-07-14` 启动。

| 日期 | 检查 | 状态 | 结果 |
|---|---|---|---|
| 2026-07-13 | 全工作树 `git diff --check` | passed-with-warning | exit 0；无 whitespace error；范围外 `WorkerEvent.java` 有 CRLF -> LF working-copy warning，本任务未修改该文件 |
| 2026-07-13 | 18 个新增 Markdown 的 no-index whitespace check | passed | `new_markdown_whitespace_errors=0` |
| 2026-07-13 | 版本索引 + 1.4.2 全部 Markdown 相对文件链接与锚点 | passed | 共检查 19 个 Markdown，缺失目标 0 |
| 2026-07-13 | 反引号中的精确仓库路径存在性 | passed | 缺失精确路径 0；API 路由、通配符和未来 create 路径不计入 |
| 2026-07-13 | `git status --short` 范围检查 | scoped-pass-with-external-changes | 本任务变更限定为 `docs/version-tracker/README.md` 和 `docs/version-tracker/1.4.2-SNAPSHOT/`；工作树另有并行的 Codex/Worker 业务代码及 `1.4.1-SNAPSHOT` 文档改动，均未触碰 |

## 前置条件

| 条件 | 状态 | 说明 |
|---|---|---|
| 产品定位和版本目标进入版本文档 | in-progress | README、REQ-001 与 Owner 决策已落档；当前系统总览、功能架构、观测和安装指引已开始对齐，历史快照保留原结论并增加 superseded 标记 |
| 内外部信任边界冻结 | planned-reviewed | ODR-142-002 至 ODR-142-005 已关闭方向决策；实现与测试未开始 |
| 模块职责和代码清单复核 | in-progress | 已按 Owner 决策更新；随实际删除继续校正路径和状态 |
| 构建工具链决策 | approved-and-implemented-baseline | Node `22.23.1`、pnpm `10.34.5`、单一根 lockfile、根前端矩阵与 repository CI 已落地；GitHub runner/branch protection 尚未验证 |
| 外部身份/token/Worker/审计决策 | approved-with-constraints | signed assertion 降为外部开放前置项；explicit external 默认关闭及其余边界仍是本版硬门 |
| dev-only 历史能力删除授权 | approved-with-constraints | Monitoring、metadata-query、code-review、Echo 可安全后物理收口，旧 dev 数据可丢弃；发现共享/生产资源即停止 |
| 旧 API 与文档/Skill 治理策略 | approved-with-constraints | 旧契约仓内迁移后直接删除；当前/历史文档分级治理 |

## Development Progress

| Stage | 范围 | 状态 | 结果/证据 |
|---|---|---|---|
| P0 | 目标、边界、术语、ownership 和代码清单冻结 | in-progress | Owner 决策已落档；当前文档和代码清单同步中 |
| P1 | Node、lockfile、全仓 clean build 和 CI 基线 | in-progress | 精确 Node/pnpm、根 frozen lockfile、全前端矩阵和 repository CI 已落地；本机 Java clean test 与前端 type/test/build 通过，GitHub runner/Worker lane/nightly 尚未闭合 |
| P2 | 外部 Biz Worker/upstream user 边界治理 | not-started | not-collected |
| P3 | Session/Task 定向 ownership 治理 | not-started | not-collected |
| P4 | 低风险孤儿代码和失效文档清理 | not-started | not-collected |
| P5 | Monitoring、metadata-query、code-review、echo dev-only 独立收口 | in-progress | Monitoring 与 code-review-agent 源码切片已物理移除；metadata-query 已 completed-local，删除后 clean test、依赖树与 clean target 扫描通过，启动/浏览器未跑；Echo 未开始 |
| P6 | 超大类、Provider state schema 和旧 API 渐进治理 | not-started | not-collected |
| P7 | 质量检查、覆盖审计、体验验证和正式签收 | not-started | not-collected |

## Workitem Progress

| Workitem | 状态 | Development | Testing | Experience | Evidence |
|---|---|---|---|---|---|
| [GOV-001](./workitems/GOV-001-internal-external-trust-boundary.md) | planned-reviewed | not-started | not-run | not-run：需验证内部 UI、外部配置与错误反馈 | Owner 决策已关闭；实现证据未收集 |
| [GOV-002](./workitems/GOV-002-biz-worker-and-upstream-user-boundary.md) | planned-reviewed | not-started | not-run | not-run：需验证 ClientApp、审批恢复和 Worker readiness 体验 | Owner 决策已关闭；实现证据未收集 |
| [GOV-003](./workitems/GOV-003-session-task-resource-ownership.md) | planned-reviewed | not-started | not-run | not-run：需验证内部 UI 工作流 | Owner 决策已关闭；实现证据未收集 |
| [OPT-001](./workitems/OPT-001-build-and-ci-baseline.md) | in-progress | baseline + required/nightly workflow implemented | passed-local：Java、frontend、五类 Worker clean 等价矩阵 | not-run：两个最小 TS 修复和 CI 配置尚未浏览器验证 | `EXEC-142-003`、`EXEC-142-006`；GitHub runner/branch protection/nightly 实跑未完成 |
| [BUG-001](./workitems/BUG-001-langgraph-progress-event-duplication.md) | closed | LangGraph SSE 语义去重已排除传输层 event_id | passed：目标用例 1；全套 758；wheel/sdist build | not-applicable：修复事件重复，无新增 UI | `EXEC-142-006` |
| [OPT-002](./workitems/OPT-002-core-code-maintainability.md) | planned | not-started | not-run | not-run：涉及工作台渐进拆分时必须验证 | not-collected |
| [CLEAN-001](./workitems/CLEAN-001-low-risk-orphan-cleanup.md) | planned | not-started | not-run | not-run：UI/mobile 候选需按实际切片验证 | not-collected |
| [CLEAN-002](./workitems/CLEAN-002-monitoring-retirement.md) | in-progress | code-slice-removed；当前权威文档已同步 | passed-local：Java clean、frontend full matrix、shell syntax；GitHub CI 未跑 | not-run：静态无残留路由，浏览器/启动 smoke 尚未执行 | `EXEC-142-002`、`EXEC-142-003` |
| [CLEAN-003](./workitems/CLEAN-003-metadata-query-retirement-audit.md) | completed-local | implementation-complete | partial-passed：删除后 15/15 clean test SUCCESS，59 tests 全通过；依赖树/clean target 无旧查询依赖 | not-run：启动与浏览器主链尚未验证 | `EXEC-142-007`；不是 hosted CI 或正式验收 |
| [CLEAN-004](./workitems/CLEAN-004-experimental-and-legacy-addon-governance.md) | in-progress | code-review slice removed；Echo/旧契约 not-started | partial：静态扫描与 Java clean passed；其余切片未跑 | not-run：code-review 无当前 UI；Echo/旧契约需后续体验验证 | `EXEC-142-004` |
| [DOC-001](./workitems/DOC-001-documentation-alignment.md) | in-progress | 版本包及当前系统/观测/安装指引同步中 | Markdown 链接与最终 diff 检查待本轮结束执行 | not-applicable：文档对齐不直接改变交互 | Owner 决策、删除现状与执行边界已同步；历史快照不改写原结论 |

## Evidence Register

| Evidence ID | 分类 | 结论或待证事实 | 来源/计划验证 | 状态 | 限制 |
|---|---|---|---|---|---|
| INPUT-001 | 已确认事实 | Navigator 是内部多 Worker 远程编程工作台，不是语义层主线 | REQ-001 与用户确认输入 | planning-input | 不代表文档已全部完成对齐 |
| INPUT-002 | 已确认事实 | `UnifiedSseEmitter` 使用 JVM 内本地 map 和调度器 | 当前源码只读审计 | confirmed-static | 不推断生产部署实例数或运行流量 |
| INPUT-003 | 已确认事实 | Claude、Codex、Gemini、LangGraph Addon 直接依赖 session-module，为编译期模块化单体 | Maven POM 只读审计 | confirmed-static | 不代表未来不能演进，只说明当前事实 |
| INPUT-004 | 本地诊断证据 | `ClaudeWorkerView.vue` 为 10,369 行；显式 app type-check 发现 2 个 TypeScript 错误 | `vue-tsc -p tsconfig.app.json --noEmit` 只读基线诊断 | failed-baseline | 不是 P1 修复后的通过证据 |
| INPUT-005 | 本地构建证据 | launcher 主干依赖链从 `clean` 状态编译并测试通过 | `mvn -B -pl launcher -am clean test` | passed | 16 个 reactor 项 SUCCESS、0 failure；不是 `clean verify` 或 GitHub hosted runner 证据 |
| INPUT-006 | 已修复基线 | Node 18/Vite 7 与忽略根 lockfile 的不可复现基线 | `.nvmrc`、root `packageManager/engines`、`.gitignore`、`pnpm-lock.yaml` 和 frozen install | passed-local | 精确 Node/pnpm 下 lockfile-only frozen 校验通过；GitHub runner 尚未运行 |
| INPUT-007 | 已修复基线 | 根前端脚本覆盖 chat-core/chat/widget/PC/mobile 的 type/test/build | `pnpm run typecheck:frontend`、`pnpm run ci:frontend`、`pnpm run build:frontend` | passed-local | 存在既有测试 stderr/构建 chunk warning；命令 exit 0，浏览器体验未运行 |
| INPUT-008 | Owner 阶段假设 | Monitoring 等候选处于 dev-only、本机孵化范围，旧数据可丢弃 | `2026-07-14` Owner 明确确认 + 每切片静态复核 | approved-assumption | 不替代共享/生产资源防误删；发现冲突证据即停止 |
| INPUT-009 | Owner 阶段假设 | 旧 Provider API 无生产/外部兼容义务，所有上游仍在本机孵化 | `2026-07-14` Owner 明确确认 + 仓内消费者扫描 | approved-assumption | 取消仓外窗口，不取消仓内迁移、安全语义和 clean build |
| DEC-001 | 决策项 | 明确受支持的 Node/pnpm/Corepack 版本 | ODR-142-001 + P1 | approved-and-applied | Node `22.23.1`、pnpm `10.34.5`；Corepack 只负责激活；hosted CI 实跑仍待完成 |
| DEC-002 | 决策项 | 外部 credential 与 task token 的签发、轮换和撤销权威 | ODR-142-002 至 ODR-142-005 + P2 | partially-decided | token/Worker/audit方向获批；具体 credential authority 仍需实施级确认 |
| DEC-003 | 决策项 | envelope v1 的 typed schema 演进、未知版本策略与兼容窗口 | P6 provider owners 决策 | pending-decision | 不允许无迁移链切换 |
| ODR-142-001 | Owner 决策 + 实施 | Node/pnpm、lockfile 与 CI 分层 | [Owner 决策记录](./owner-decision-review.md) | in-progress | 本地 frozen/frontend/Worker 证据通过；required/nightly workflow 已建立，GitHub runner 与分支保护未执行 |
| ODR-142-002 | Owner 决策 | internal-dev 保留 ClientApp 代办；signed assertion 延后到外部开放 | [Owner 决策记录](./owner-decision-review.md) | approved-with-constraints | explicit external 必须默认关闭；请求体 actor 仍不可信 |
| ODR-142-003 | Owner 决策 | task token scope、TTL、失效、撤销、轮换和 Worker lease | [Owner 决策记录](./owner-decision-review.md) | approved | 未发布新 token schema，implementation not-started |
| ODR-142-004 | Owner 决策 | external-enabled 目录、工具、sandbox、approval、network 和 readiness 上限 | [Owner 决策记录](./owner-decision-review.md) | approved-with-constraints | 门禁未完成前 external 保持 disabled |
| ODR-142-005 | Owner 决策 | 本地关键状态事务 outbox、拒绝可靠事件、远程调用分段审计、遥测 best-effort | [Owner 决策记录](./owner-decision-review.md) | approved | implementation not-started；不宣称审计已完备 |
| ODR-142-006 | Owner 决策 | 四个历史切片 dev-only 安全后物理收口 | [Owner 决策记录](./owner-decision-review.md) | approved-with-constraints | 数据可丢弃；发现共享/生产资源停止 |
| ODR-142-006-MON | Owner 决策 + 实施 | Monitoring 完整切片移除 | [Owner 决策记录](./owner-decision-review.md) | implementation-complete-verification-partial | 代码/PC/auth/script 和当前指引已收口；Java/frontend/shell 通过，浏览器/启动 smoke/GitHub CI 未跑；未操作外部资源 |
| ODR-142-006-MQ | Owner 决策 + 实施 | metadata-query 完整切片移除 | [CLEAN-003](./workitems/CLEAN-003-metadata-query-retirement-audit.md) | completed-local | metadata-config 23 个 tracked files 保留、业务树 diff 为 0；启动/浏览器、hosted CI 和正式验收未运行 |
| ODR-142-006-CR | Owner 决策 + 实施 | code-review-agent 物理移除 | [Owner 决策记录](./owner-decision-review.md) | implementation-complete-verification-partial | 22 个 tracked files 已删除，仓内精确引用扫描与 Java clean 通过；未操作 GitLab、DB 或独立部署资源 |
| ODR-142-006-ECHO | Owner 决策 | Echo production 退出、dev/test fixture 保留/迁移 | [Owner 决策记录](./owner-decision-review.md) | approved-with-constraints | fixture 迁移前不删 launcher 消费链 |
| ODR-142-007 | Owner 决策 | 旧 Provider API/SPI/DTO 仓内迁移后直接删除 | [Owner 决策记录](./owner-decision-review.md) | approved-with-constraints | 无外部窗口；implementation not-started |
| ODR-142-008 | Owner 决策 | 当前/历史文档和 Skill 分级治理 | [Owner 决策记录](./owner-decision-review.md) | approved | 文档对齐 in-progress |
| EXEC-142-001 | 决策证据 | Owner 明确 dev/internal 阶段、external 显式开关、dev 数据可丢弃和旧契约可直接移除 | 当前项目会话，`2026-07-14` | recorded | 只授权本项目 dev 范围，不是生产启用授权 |
| EXEC-142-002 | 实施证据 | Monitoring 10 个 Java/module 文件、5 个 Python tool 文件、PC View/API、Security 放行与启动脚本已移除；repo-local ignored `target/.venv/.pytest_cache` 已清除 | 工作树 diff + 精确 `rg` + 本地目录检查 + `bash -n scripts/start-all.sh` | passed-local-partial-experience | 仓内切片闭合；未操作 RabbitMQ/DB/部署，浏览器与启动 smoke 未运行 |
| EXEC-142-003 | 构建证据 | 精确 Node/pnpm、单根 lockfile、frontend matrix、repository CI 配置和 Java clean 基线 | Node `v22.23.1` + pnpm `10.34.5` frozen 校验；frontend type/test/build；Maven clean test | passed-local | Maven 16 reactor SUCCESS；frontend commands exit 0；Worker 见 EXEC-142-006；GitHub hosted CI/nightly 未运行 |
| EXEC-142-004 | 实施证据 | 未装配的 `addons/code-review-agent` 22 个 tracked files 已物理移除 | root/launcher/CI/scripts/源码精确 `rg` + 工作树 diff + Maven clean test | passed-local-partial-external | 无当前仓内 package/API/table 引用；没有 GitLab/DB/独立部署运行态证据，也未执行外部资源动作 |
| EXEC-142-005 | 文档与配置验证 | 本轮 Markdown、shell、JSON/YAML、清理残留和 lockfile 跟踪状态 | Node 相对链接/锚点检查、`bash -n`、Node JSON/YAML parse、精确 `rg`、`git check-ignore`、`git diff --check` | passed-local | 32 个 Markdown、411 个相对文件目标和 3 个锚点均存在；hosted CI 与手工审阅不在此证据内 |
| EXEC-142-006 | clean Worker 矩阵与缺陷回归 | Codex SDK/app-server、Gemini、Claude、LangGraph 的 install/type/test/build；修复 LangGraph progress 事件重复 | 独立 clean worktree；Node `22.23.1`；Python `3.12.3`；[BUG-001](./workitems/BUG-001-langgraph-progress-event-duplication.md) | passed-local-with-hosted-gap | Node 三 lane 通过；Claude 495 pass/11 deselect；LangGraph 758 pass；本机无 Python 3.11，GitHub runner 未执行；Gemini audit 有 1 low/4 moderate |
| EXEC-142-007 | metadata-query 删除后实施与验证 | 模块/装配/断言/Skill/当前文档退出，metadata-config 保留 | `mvn -B -pl metadata-config-module,launcher -am clean test`、dependency tree、clean target 与 tracked/diff 检查 | completed-local | 15/15 reactor project SUCCESS，总时 `05:23`；metadata-config 4 suites/52 tests、launcher 3 suites/7 tests，均 0 failure/error/skipped；启动/浏览器、hosted CI、外部资源和正式验收未运行 |

## Testing Progress

| Test lane | 状态 | Evidence / 原因 |
|---|---|---|
| Java clean compile/test | passed | 删除后执行 `mvn -B -pl metadata-config-module,launcher -am clean test`：15/15 reactor project SUCCESS，59 tests 的 failure/error 为 0，exit 0 |
| Launcher assembly/package | partial | Maven clean test 已完成 launcher 编译和测试；`package`/`clean verify` 未运行 |
| Navigator PC type-check/test/build | passed | 两个已知 TS 错误做最小修复后，根 frontend type/test/build matrix exit 0 |
| chat-core/chat/widget build | passed | 根 `ci:frontend` / `build:frontend` 已覆盖；chat 测试 105、widget 测试 31 均通过 |
| Mobile type-check/test/build | passed | mobile type-check、59 个测试与 H5 build 通过；非 H5 目标未运行 |
| Claude/Codex/Gemini/LangGraph Worker tests/build | passed-local | 独立 clean worktree 五类 Worker install/type/test/build 通过；Python 本机为 3.12.3，GitHub 3.11 与 hosted runner 仍未运行；见 `EXEC-142-006` |
| Ownership negative-path tests | not-run | P2/P3 尚未实施 |
| task-scoped token 越权测试 | not-run | P2 尚未实施 |
| Non-loopback missing credential readiness | not-run | P2 尚未实施 |
| 删除项引用与 clean build 回归 | passed-local-partial-scope | Monitoring/code-review 精确静态扫描、shell syntax、Java clean 与前端 full matrix passed；metadata-query 删除后 clean test 15/15 SUCCESS，59 tests 通过，依赖树/clean target 无旧查询依赖；外部资源、启动和浏览器 smoke 未检查 |
| GitHub Actions full-repository matrix | configured-not-run | `.github/workflows/repository-ci.yml` 已覆盖 Java、frontend、Node Worker、Python Worker；尚未由 GitHub runner 执行 |
| 文档、配置与全工作树检查 | passed-local | 32 个 Markdown/411 个相对目标/3 个锚点缺失为 0；修改 shell `bash -n`、JSON/YAML parse、lockfile 跟踪检查通过；`git diff --check` exit 0 |

## Experience Progress

| 体验维度 | 检查项 | 状态 | Evidence |
|---|---|---|---|
| 页面可达性 | 内部工作台、Session/Task、Profile；Monitoring 无残留导航或断链 | not-run | MonitoringView 静态无路由引用，尚未做浏览器验证 |
| 核心交互 | 新建/继续任务、审批、恢复、取消、文件和终端主链不大面积回归 | not-run | not-collected |
| 表单验证 | ClientApp、upstream mapping/grant、外部 Worker 配置的必填与错误状态 | not-run | not-collected |
| 异常状态 | 缺凭据、越权、token 过期/撤销、Worker unready 的反馈可理解 | not-run | not-collected |
| 权限可见性 | 内部管理员能力与外部用户能力的可见范围符合边界 | not-run | not-collected |
| 数据一致性 | Session/Task ownership、审批与恢复前后状态一致 | not-run | not-collected |

### Playwright 状态

| 用例 | 覆盖维度 | 状态 |
|---|---|---|
| 可信内网现有任务主链回归 | 页面可达性、核心交互、数据一致性 | not-run |
| 跨用户 Session/Task 操作拒绝 | 权限可见性、异常状态 | not-run |
| 审批/恢复/取消主体不匹配拒绝 | 核心交互、权限可见性 | not-run |
| 外部 Worker 缺凭据配置反馈 | 表单验证、异常状态 | not-run |
| 退役能力导航和替代路径 | 页面可达性、核心交互 | not-run |

## Acceptance Criteria Tracking

| Requirement | 状态 | Evidence |
|---|---|---|
| 1. 内部 UI 和可信内网工作流不大面积回归 | not-started | not-collected |
| 2. 外部 Biz Worker 请求可追溯到 tenant、ClientApp、upstream user 和任务 | not-started | not-collected |
| 3. 外部审批、恢复、取消不能只凭 taskId | not-started | not-collected |
| 4. 外部身份不直接取自可伪造请求字段 | not-started | not-collected |
| 5. task-scoped token 不越权访问其他任务或函数 | not-started | not-collected |
| 6. 非 loopback 外部 Worker 缺凭据时 fail closed 或 unready | not-started | not-collected |
| 7. Java clean 构建测试通过 | passed-current-batch | `EXEC-142-003`、`EXEC-142-007`；metadata-query 删除后已重跑，后续删除批次仍须重跑 |
| 8. 纳入范围的前端类型检查、测试和构建通过 | passed-current-batch | `EXEC-142-003`；浏览器体验与非 H5 mobile 不在本证据内 |
| 9. Node、包管理器和 lockfile 可复现 | partial | 精确版本与本地 frozen lockfile 校验通过；clean checkout/GitHub runner 待证 |
| 10. 所有删除项有扫描、迁移/替代和回滚证据 | partial | Monitoring/code-review/metadata-query 已记录；Echo、旧契约尚未实施 |
| 11. 获批退役项按完整功能切片退出；retain/migrate/defer 有 Owner 记录 | partial | Monitoring/code-review 代码切片已退出；metadata-query completed-local；Echo 尚未实施 |
| 12. 当前文档不再把 tutor、旧 chat-first 或语义层写成主线 | in-progress | 当前总览/功能架构/观测/安装指引已对齐；失效 Skill 和早期设计文档分级仍待继续 |
| 13. 隔离验收不等同于生产批准 | not-started | not-collected |

## Implementation Self-Check

- [x] requirement 与当前阶段 scope 已收口。
- [x] 非目标没有被意外扩张。
- [x] 本批次代码路径、配置、迁移和文档触点已回写。
- [x] 静态结论与运行态证据已明确区分。
- [x] 本批次测试均记录 pass、fail、not-run 或 not-applicable 及原因。
- [ ] UI 改动已完成体验清单和 Playwright。
- [ ] 删除项已有替代、回滚和完整功能切片证据。
- [x] 计划外变更、风险和阻塞已记录。
- [x] 已判断需要在跨模块阶段收口时执行正式 `foggy-implementation-quality-gate`，本轮不把执行 check-in 变成正式验收。
- [x] 当前批次进度与证据已回写。

- self_check_summary: partial-passed-for-current-batch；本机 Worker matrix 已通过，hosted CI/branch protection/nightly 实跑、浏览器体验、其余 P2-P6 和正式门禁未完成
- self_check_decision: continue-in-progress
- formal_quality_gate_required: yes-cross-module-shared-contract-and-cleanup
- formal_quality_gate_status: not-started

## 计划外变更

- P1 clean Worker 矩阵发现并关闭 [BUG-001](./workitems/BUG-001-langgraph-progress-event-duplication.md)：只修复 LangGraph SSE progress 语义去重，不扩张为图执行或事件协议重构。

## 后续实施待确认项

| Item | 状态 | Owner/Decision |
|---|---|---|
| GitHub repository CI 首次 hosted run、required check 与 nightly workflow | pending-execution | root build owner / repository owner |
| credential authority、轮换与撤销传播的具体 schema/事务边界 | pending-implementation-design | Biz Worker / gateway / platform owner |
| upstream user mapping/grant 的权威数据源细节 | pending-implementation-design | ClientApp / upstream integration owner；signed assertion 不阻塞 internal-dev |
| task-scoped token 字段、函数 allowlist、generation/lease 并发语义 | pending-implementation-design | BusinessTask / BusinessFunction / Worker Gateway owner |
| `external-enabled` 配置名、默认值、readiness 与 Worker 上限的代码落点 | pending-implementation-design | Worker / Platform / Security owner；方向已批准，默认必须关闭 |
| 权威 audit sink、outbox schema、拒绝事件可靠落档实现 | pending-implementation-design | Security / Operations / Business Agent owner |
| metadata-query 启动/浏览器体验与后置正式门禁 | pending-experience-and-signoff | metadata-query / launcher owner；本地代码与自动化门禁已完成，不等于正式验收 |
| Echo dev/test fixture 形态和 production launcher 退出方式 | pending-implementation-design | provider/test owner；不得误删 LocalEcho adapter |
| 旧 Provider API/SPI/DTO 的逐路由仓内消费者替代矩阵 | pending-execution | PC / Mobile / SDK / CLI / Provider owners；无需外部兼容窗口 |
| 失效 Skills、早期设计文档的 current/historical/candidate 分类 | pending-execution | Product / documentation / skill owners |
| Provider state envelope v1 typed schema、未知版本策略和迁移链 | pending-decision | provider owners |

## 后续衔接

下一批按以下顺序衔接：

1. 先完成本轮 Markdown 链接、workflow 语法、shell 语法、`git diff --check` 和工作树范围检查，保留未运行项。
2. 让 repository CI 在 hosted runner 首次执行并决定 required checks；nightly 与 release/RC 分层单独落档。
3. metadata-query 保持 completed-local 并在适用环境补启动/浏览器体验；后续逐切片推进 Echo 和旧 Provider 契约，每批先做仓内迁移/保护清单，再删除并重跑 clean test，不把 Owner 的 dev-only 授权扩大到未知共享资源。
4. P2 优先实现显式、默认关闭的 `external-enabled` 与 readiness，再补 task token、调用主体和审计；signed assertion 保留为未来真正外部开放门禁。
5. 每完成一个跨模块或删除阶段先执行 implementation self-check，再按需要执行正式质量检查；P7 仍按质量检查、覆盖审计、正式签收顺序收口，隔离验证不等于生产批准。
