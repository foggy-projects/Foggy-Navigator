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
- last_updated_at: `2026-07-15`
- production_routing_changed: no
- launcher_default_agent_inventory_changed: yes
- external_contract_changed: yes
- external_enablement: no
- production_enablement: not-applicable
- formal_quality_gate: reviewed-ready-with-risks
- coverage_audit: reviewed-needs-more-tests
- acceptance_status: rejected

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
| 内外部信任边界冻结 | in-progress | ODR-142-002 至 ODR-142-005 已关闭方向决策；external gate、task capability v2、终态 tombstone、Worker credential v1、pool/identity 路由及 Gateway strict principal/lease 已实施；P3 首批 `userId + tenantId` ownership 已落地。开关组合、OS 隔离、Codex 安全转发、可靠审计和 ownership 全列表/系统主体闭环仍未完成 |
| 模块职责和代码清单复核 | in-progress | 已按 Owner 决策更新；随实际删除继续校正路径和状态 |
| 构建工具链决策 | approved-and-implemented-baseline | Node `22.23.1`、pnpm `10.34.5`、单一根 lockfile、根前端矩阵与 repository CI 已落地；首次全绿 head `9008c554` 与截至本次正式闸门的最新已验证实现 head `9d03bee9` 的 Repository CI 7 jobs 均已通过；main branch protection/required checks 当前未配置，修复后 nightly 尚未实跑 |
| 外部身份/token/Worker/审计决策 | approved-with-constraints | signed assertion 降为外部开放前置项；explicit external 默认关闭及其余边界仍是本版硬门 |
| dev-only 历史能力删除授权 | approved-with-constraints | Monitoring、metadata-query、code-review、Echo 可安全后物理收口，旧 dev 数据可丢弃；发现共享/生产资源即停止 |
| 旧 API 与文档/Skill 治理策略 | implementation-partial | 旧 Provider HTTP/SPI/DTO 已完成仓内迁移和物理收口；当前/历史文档与 Skill 仍在分级治理 |

## Development Progress

| Stage | 范围 | 状态 | 结果/证据 |
|---|---|---|---|
| P0 | 目标、边界、术语、ownership 和代码清单冻结 | in-progress | Owner 决策已落档；当前文档和代码清单同步中 |
| P1 | Node、lockfile、全仓 clean build 和 CI 基线 | in-progress | 精确 Node/pnpm、根 frozen lockfile、全前端矩阵和 repository CI 已落地；本机矩阵通过，首次全绿 head `9008c554` 与截至本次正式闸门的最新已验证实现 head `9d03bee9` 的 GitHub hosted Java launcher 依赖链/前端/五类 Worker 7 jobs 均 success。main required checks/branch protection 未配置，修复后 nightly 未实跑；见 `EXEC-142-017` |
| P2 | 外部 Biz Worker/upstream user 边界治理 | in-progress | external gate/readiness、task capability v2、持久化终态门禁、Worker credential v1、pool owner/identity 路由、Gateway strict principal/lease、Biz Provider preselect/prebind 与 best-effort audit 事务隔离已落地；Codex 安全转发、开关组合、OS 隔离、generation/pause、可靠 outbox 与 external execution policy 未完成；见 `EXEC-142-008`、`EXEC-142-011`、`EXEC-142-012`、`EXEC-142-013` |
| P3 | Session/Task 定向 ownership 治理 | in-progress | 统一 ownership 门面及 Session/Task/Agent/SSE/config/shared/forward/context/model-config 首批路径已落地；租户主体精确匹配，tenantless 主体只允许同 userId + tenant null/blank，并在认证和新建 Session 时规范为空值 null。定向 176 tests、launcher clean 15/15 reactor/2426 tests、hosted CI、隔离 H2 Session 双账号、BUG-003 clean 748 tests 及 BUG-004 clean 753 tests 均通过。两个 BUG 的 dev PC 复测、Task live Provider fixture、共享数据库、L3、全列表 tenant、metadata invariant、Provider taskId 与显式 admin/system 通路未完成；见 `EXEC-142-014`、`EXEC-142-017`、`EXEC-142-018`、`EXEC-142-019`、`EXEC-142-020` |
| P4 | 低风险孤儿代码和失效文档清理 | not-started | not-collected |
| P5 | Monitoring、metadata-query、code-review、echo dev-only 独立收口 | in-progress | Monitoring/code-review 已移除，metadata-query 已 completed-local；Echo 已 completed-local/verification-partial，5 个 addon tracked files 与 reactor/launcher 装配退出，test-only fixture 定向 16/16 及 launcher 定向 6/6 tests 通过；hosted CI 已通过，版本正式门禁已执行并拒绝，切片专项浏览器/PS parser/模块级签收未跑 |
| P6 | 超大类、Provider state schema 和旧 API 渐进治理 | in-progress | 旧 Provider HTTP/SPI/DTO 子切片已完成仓内迁移、物理删除、定向 clean matrix 与 hosted CI；Provider state schema 和超大类渐进治理未开始；见 `EXEC-142-016`、`EXEC-142-017` |
| P7 | 质量检查、覆盖审计、体验验证和正式签收 | completed-formal-review / rejected | [质量闸门](./quality/executed-governance-slices-implementation-quality.md) `ready-with-risks`；[覆盖审计](./coverage/1.4.2-coverage-audit.md) `needs-more-tests`；[正式签收](./acceptance/version-signoff.md) `rejected` |

## Workitem Progress

| Workitem | 状态 | Development | Testing | Experience | Evidence |
|---|---|---|---|---|---|
| [GOV-001](./workitems/GOV-001-internal-external-trust-boundary.md) | in-progress | partial：平台 Open API 默认关闭、三类 Worker external profile、readiness、task capability v2、持久化终态门禁、Worker identity/pool、Gateway strict principal/lease 与 P3 首批 ownership 已落地 | partial-passed-local-and-hosted：P2 launcher/Worker、P3 定向 176 tests、既有 migration 双 MySQL 版本及 Repository CI 7/7 均通过 | partial-passed-isolated：内部登录/Session ownership 错误反馈已验证；真实 external/network/Task 体验未验证 | `EXEC-142-008`、`EXEC-142-011`、`EXEC-142-012`、`EXEC-142-013`、`EXEC-142-014`、`EXEC-142-016`、`EXEC-142-017`、`EXEC-142-018`；开关组合、Codex 安全转发、OS 隔离、可靠 audit、ownership 全列表/显式系统主体仍未完成 |
| [GOV-002](./workitems/GOV-002-biz-worker-and-upstream-user-boundary.md) | in-progress | partial：显式开关、task function scope/TTL/revoke、持久化 terminal tombstone、Worker credential rotate/revoke、pool owner/identity、Gateway strict principal/lease、DB preselect/prebind 与 audit writer 事务隔离已落地；非 Biz Open API 不签发 Gateway capability | partial-passed-local-and-hosted：launcher 依赖链 15/15 clean reactor、2357 tests；LangGraph 780 pytest + ruff；Codex 175 tests 中 174 pass/1 Windows skip、typecheck；既有 MySQL migration；hosted 7/7 jobs | not-run：ClientApp 双主体、审批恢复、非 loopback 部署和 Worker 错误体验未验证 | `EXEC-142-008`、`EXEC-142-011`、`EXEC-142-012`、`EXEC-142-013`、`EXEC-142-017`；Codex credential 安全转发、generation/pause、outbox、L3 未完成 |
| [GOV-003](./workitems/GOV-003-session-task-resource-ownership.md) | in-progress | partial：租户主体 userId+tenantId 精确匹配；tenantless 仅同 userId+tenant null/blank，认证和 Session 新写入规范为 null；Session/Task/Agent/SSE/config/shared/forward 先授权；context 条件 claim/update；Provider sessionId 再授权；model config 与 quota 顺序收紧；LangGraph 审批迁入统一 respond 并绑定认证主体 | partial-passed：P3 定向 176 tests；launcher clean 15/15 reactor、2426 tests；hosted CI；隔离 H2 Session 双账号 live 1 passed；BUG-003 定向 27/clean 748；BUG-004 定向 66/clean 753 | partial-passed-isolated / dev-retest-pending：同 tenant 双账号真实登录与 Session ownership 已验证；tenantless dev PC Session 三入口和 Codex Task create/get/respond/cancel、共享 DB 与完整内部任务主链未运行 | `EXEC-142-014`、`EXEC-142-016`、`EXEC-142-017`、`EXEC-142-018`、`EXEC-142-019`、`EXEC-142-020`；全列表 tenant、SessionMetadata service invariant、model owner/grant、Provider taskId、显式 admin/system 通路仍缺失 |
| [OPT-001](./workitems/OPT-001-build-and-ci-baseline.md) | in-progress | baseline + required 候选/nightly workflow implemented；clean-runner Java 外部依赖已用 Navigator-owned wire-contract shim 收口 | passed-local-and-hosted：本机根 Java/frontend/五类 Worker clean 等价矩阵；hosted head `9008c554`、`9d03bee9` 均 7/7 jobs success | partial：隔离 ownership live 与全 mock Playwright 已运行；不覆盖所有真实业务体验 | `EXEC-142-003`、`EXEC-142-006`、`EXEC-142-009`、`EXEC-142-017`、`EXEC-142-018`；main branch protection/required checks 未配置，修复后 nightly 未实跑 |
| [BUG-001](./workitems/BUG-001-langgraph-progress-event-duplication.md) | closed | LangGraph SSE 语义去重已排除传输层 event_id | passed：目标用例 1；全套 758；wheel/sdist build | not-applicable：修复事件重复，无新增 UI | `EXEC-142-006` |
| [BUG-002](./workitems/BUG-002-open-sdk-clean-test-baseline.md) | closed | 修复 Open SDK 测试语法、JUnit 5 执行器与 WSL 用例宿主依赖 | passed：Open SDK 142；根 reactor 2304 tests、17/17 SUCCESS | not-applicable：仅构建和测试基线 | `EXEC-142-009`；未改运行时 API 或生产门禁 |
| [BUG-003](./workitems/BUG-003-tenantless-session-owner-access-regression.md) | ready-for-verification | tenantless exact-owner scope；config 只读批次过滤；写批次仍原子拒绝 | passed-local：test-first red；定向 27；clean 6/6 reactor、748 tests | not-run：dev PC 等待新令牌复测 configs/latest/SSE | `EXEC-142-019`；不构成 admin bypass，不改变生产路由或 external enablement |
| [BUG-004](./workitems/BUG-004-blank-tenant-task-ownership-regression.md) | ready-for-verification | JWT/API Key tenant 规范化；tenantless NULL/空白历史行兼容；同 userId 约束保持 | passed-local：test-first 3 red；定向 66；clean 6/6 reactor、753 tests | not-run：等待部署后以新令牌复测 Codex Task create/get/respond/cancel | `EXEC-142-020`；model config grant 为独立问题，不改生产路由或 external enablement |
| [OPT-002](./workitems/OPT-002-core-code-maintainability.md) | planned | not-started | not-run | not-run：涉及工作台渐进拆分时必须验证 | not-collected |
| [CLEAN-001](./workitems/CLEAN-001-low-risk-orphan-cleanup.md) | planned | not-started | not-run | not-run：UI/mobile 候选需按实际切片验证 | not-collected |
| [CLEAN-002](./workitems/CLEAN-002-monitoring-retirement.md) | in-progress | code-slice-removed；当前权威文档已同步 | passed-local-and-hosted：Java clean、frontend full matrix、shell syntax；Repository CI 7/7 jobs | not-run：静态无残留路由，专项浏览器/启动 smoke 尚未执行 | `EXEC-142-002`、`EXEC-142-003`、`EXEC-142-017` |
| [CLEAN-003](./workitems/CLEAN-003-metadata-query-retirement-audit.md) | completed-local | implementation-complete | partial-passed-local-and-hosted：删除后 15/15 clean test SUCCESS，59 tests 全通过；依赖树/clean target 无旧查询依赖；Repository CI 7/7 jobs | not-run：启动与浏览器主链尚未验证 | `EXEC-142-007`、`EXEC-142-017`；不是专项运行态或正式验收 |
| [CLEAN-004](./workitems/CLEAN-004-experimental-and-legacy-addon-governance.md) | in-progress | code-review removed；Echo completed-local/verification-partial；旧 Provider HTTP/SPI/DTO completed-local-and-hosted | Echo 定向 16/16、launcher 定向 6/6；旧契约 LangGraph/Claude/Codex clean 矩阵通过；hosted 7/7 jobs success | full mock Playwright 17 passed/1 skipped；Task live Provider fixture、Echo 专项浏览器和 PS parser not-run | `EXEC-142-004`、`EXEC-142-015`、`EXEC-142-016`、`EXEC-142-017`、`EXEC-142-018` |
| [DOC-001](./workitems/DOC-001-documentation-alignment.md) | in-progress | 版本包及当前系统/观测/安装指引同步中 | passed-local-current-worktree：26 个 Markdown、438 个相对链接，缺失目标 0；`git diff --check` exit 0；diff 敏感 token 扫描无命中 | not-applicable：文档对齐不直接改变交互 | Owner 决策、删除现状与执行边界已同步；历史快照不改写原结论，全量关键词分类仍未完成 |

## Evidence Register

本表每条 `EXEC-*` 保留证据形成时的事实、命令和限制；早期条目中的 `not-run`/未完成不覆盖后续补证。当前状态以 Development Progress、Workitem Progress、Acceptance Criteria Tracking、Formal Gate Results 以及编号更晚的执行证据为准。

| Evidence ID | 分类 | 结论或待证事实 | 来源/计划验证 | 状态 | 限制 |
|---|---|---|---|---|---|
| INPUT-001 | 已确认事实 | Navigator 是内部多 Worker 远程编程工作台，不是语义层主线 | REQ-001 与用户确认输入 | planning-input | 不代表文档已全部完成对齐 |
| INPUT-002 | 已确认事实 | `UnifiedSseEmitter` 使用 JVM 内本地 map 和调度器 | 当前源码只读审计 | confirmed-static | 不推断生产部署实例数或运行流量 |
| INPUT-003 | 已确认事实 | Claude、Codex、Gemini、LangGraph Addon 直接依赖 session-module，为编译期模块化单体 | Maven POM 只读审计 | confirmed-static | 不代表未来不能演进，只说明当前事实 |
| INPUT-004 | 本地诊断证据 | `ClaudeWorkerView.vue` 为 10,369 行；显式 app type-check 发现 2 个 TypeScript 错误 | `vue-tsc -p tsconfig.app.json --noEmit` 只读基线诊断 | failed-baseline | 不是 P1 修复后的通过证据 |
| INPUT-005 | 本地构建证据 | launcher 主干依赖链从 `clean` 状态编译并测试通过 | `mvn -B -pl launcher -am clean test` | passed | 16 个 reactor 项 SUCCESS、0 failure；不是 `clean verify` 或 GitHub hosted runner 证据 |
| INPUT-006 | 已修复基线 | Node 18/Vite 7 与忽略根 lockfile 的不可复现基线 | `.nvmrc`、root `packageManager/engines`、`.gitignore`、`pnpm-lock.yaml` 和 frozen install | passed-local | 本条形成时精确 Node/pnpm 下 lockfile-only frozen 校验通过、GitHub runner 尚未运行；后续 hosted 证据见 `EXEC-142-017` |
| INPUT-007 | 已修复基线 | 根前端脚本覆盖 chat-core/chat/widget/PC/mobile 的 type/test/build | `pnpm run typecheck:frontend`、`pnpm run ci:frontend`、`pnpm run build:frontend` | passed-local | 存在既有测试 stderr/构建 chunk warning；命令 exit 0，浏览器体验未运行 |
| INPUT-008 | Owner 阶段假设 | Monitoring 等候选处于 dev-only、本机孵化范围，旧数据可丢弃 | `2026-07-14` Owner 明确确认 + 每切片静态复核 | approved-assumption | 不替代共享/生产资源防误删；发现冲突证据即停止 |
| INPUT-009 | Owner 阶段假设 | 旧 Provider API 无生产/外部兼容义务，所有上游仍在本机孵化 | `2026-07-14` Owner 明确确认 + 仓内消费者扫描 | approved-assumption | 取消仓外窗口，不取消仓内迁移、安全语义和 clean build |
| DEC-001 | 决策项 | 明确受支持的 Node/pnpm/Corepack 版本 | ODR-142-001 + P1 | approved-and-applied | Node `22.23.1`、pnpm `10.34.5`；Corepack 只负责激活；Repository CI 7-job 矩阵已通过；main required checks/branch protection 未配置，修复后 nightly 未实跑 |
| DEC-002 | 决策项 | 外部 credential 与 task token 的签发、轮换和撤销权威 | ODR-142-002 至 ODR-142-005 + P2 | implementation-partial | Worker credential v1 的 owner-scoped rotate/revoke/schema、Gateway strict principal/lease 与 LangGraph 调用方传播已落地；Codex 安全转发、pause/generation、开关组合与可靠撤销传播仍待实施 |
| DEC-003 | 决策项 | envelope v1 的 typed schema 演进、未知版本策略与兼容窗口 | P6 provider owners 决策 | pending-decision | 不允许无迁移链切换 |
| ODR-142-001 | Owner 决策 + 实施 | Node/pnpm、lockfile 与 CI 分层 | [Owner 决策记录](./owner-decision-review.md) | hosted-baseline-passed | 本地 frozen/frontend/Worker 与 hosted heads `9008c554`、`9d03bee9` 的 Repository CI 7 jobs 均通过；main required checks/分支保护未配置，修复后 nightly 未实跑 |
| ODR-142-002 | Owner 决策 | internal-dev 保留 ClientApp 代办；signed assertion 延后到外部开放 | [Owner 决策记录](./owner-decision-review.md) | approved-with-constraints | explicit external 必须默认关闭；请求体 actor 仍不可信 |
| ODR-142-003 | Owner 决策 + 实施 | task token scope、TTL、失效、撤销、轮换和 Worker lease | [Owner 决策记录](./owner-decision-review.md) | implementation-partial | v2 schema/function snapshot/TTL/撤销、definitive terminal tombstone、DB preselect/prebind 与 Gateway Worker principal/lease 双重校验已实现；pause、generation 轮换、Codex 安全转发与运行态矩阵未实现 |
| ODR-142-004 | Owner 决策 | external-enabled 目录、工具、sandbox、approval、network 和 readiness 上限 | [Owner 决策记录](./owner-decision-review.md) | approved-with-constraints | 门禁未完成前 external 保持 disabled |
| ODR-142-005 | Owner 决策 + 实施 | 本地关键状态事务 outbox、拒绝可靠事件、远程调用分段审计、遥测 best-effort | [Owner 决策记录](./owner-decision-review.md) | implementation-partial | runtime audit writer 已用独立 bean + `REQUIRES_NEW/saveAndFlush` 隔离主事务；仍是 best-effort telemetry，关键拒绝/outbox 未实现 |
| ODR-142-006 | Owner 决策 | 四个历史切片 dev-only 安全后物理收口 | [Owner 决策记录](./owner-decision-review.md) | approved-with-constraints | 数据可丢弃；发现共享/生产资源停止 |
| ODR-142-006-MON | Owner 决策 + 实施 | Monitoring 完整切片移除 | [Owner 决策记录](./owner-decision-review.md) | implementation-complete-verification-partial | 代码/PC/auth/script 和当前指引已收口；Java/frontend/shell 与 Repository CI 7-job 矩阵通过，专项浏览器/启动 smoke 未跑；未操作外部资源 |
| ODR-142-006-MQ | Owner 决策 + 实施 | metadata-query 完整切片移除 | [CLEAN-003](./workitems/CLEAN-003-metadata-query-retirement-audit.md) | completed-local | metadata-config 23 个 tracked files 保留、业务树 diff 为 0；Repository CI 7-job 矩阵通过，版本签收已执行并拒绝，专项启动/浏览器和模块级签收未运行 |
| ODR-142-006-CR | Owner 决策 + 实施 | code-review-agent 物理移除 | [Owner 决策记录](./owner-decision-review.md) | implementation-complete-verification-partial | 22 个 tracked files 已删除，仓内精确引用扫描与 Java clean 通过；未操作 GitLab、DB 或独立部署资源 |
| ODR-142-006-ECHO | Owner 决策 + 实施 | Echo 默认制品退出、test fixture 保留/迁移 | [CLEAN-004](./workitems/CLEAN-004-experimental-and-legacy-addon-governance.md) | completed-local / verification-partial | addon/reactor/launcher 已退出，Repository CI 7-job 矩阵已通过；版本正式门禁已执行并拒绝，Echo 专项浏览器、PS parser 和模块级签收未执行 |
| ODR-142-007 | Owner 决策 + 实施 | 旧 Provider API/SPI/DTO 仓内迁移后直接删除 | [Owner 决策记录](./owner-decision-review.md) | implementation-complete-verification-partial | 无外部窗口；Claude/Codex/LangGraph 仓内消费者已迁移并物理收口，定向 clean 与 hosted CI 通过；版本正式门禁已执行并拒绝，Task live Provider fixture 未执行 |
| ODR-142-008 | Owner 决策 | 当前/历史文档和 Skill 分级治理 | [Owner 决策记录](./owner-decision-review.md) | approved | 文档对齐 in-progress |
| EXEC-142-001 | 决策证据 | Owner 明确 dev/internal 阶段、external 显式开关、dev 数据可丢弃和旧契约可直接移除 | 当前项目会话，`2026-07-14` | recorded | 只授权本项目 dev 范围，不是生产启用授权 |
| EXEC-142-002 | 实施证据 | Monitoring 10 个 Java/module 文件、5 个 Python tool 文件、PC View/API、Security 放行与启动脚本已移除；repo-local ignored `target/.venv/.pytest_cache` 已清除 | 工作树 diff + 精确 `rg` + 本地目录检查 + `bash -n scripts/start-all.sh` | passed-local-partial-experience | 仓内切片闭合；未操作 RabbitMQ/DB/部署，浏览器与启动 smoke 未运行 |
| EXEC-142-003 | 构建证据 | 精确 Node/pnpm、单根 lockfile、frontend matrix、repository CI 配置和 Java clean 基线 | Node `v22.23.1` + pnpm `10.34.5` frozen 校验；frontend type/test/build；Maven clean test | passed-local | Maven 16 reactor SUCCESS；frontend commands exit 0；Worker 见 EXEC-142-006；GitHub hosted CI/nightly 未运行 |
| EXEC-142-004 | 实施证据 | 未装配的 `addons/code-review-agent` 22 个 tracked files 已物理移除 | root/launcher/CI/scripts/源码精确 `rg` + 工作树 diff + Maven clean test | passed-local-partial-external | 无当前仓内 package/API/table 引用；没有 GitLab/DB/独立部署运行态证据，也未执行外部资源动作 |
| EXEC-142-005 | 文档与配置验证 | 本轮 Markdown、shell、JSON/YAML、清理残留和 lockfile 跟踪状态 | Node 相对链接/锚点检查、`bash -n`、Node JSON/YAML parse、精确 `rg`、`git check-ignore`、`git diff --check` | passed-local | 32 个 Markdown、411 个相对文件目标和 3 个锚点均存在；hosted CI 与手工审阅不在此证据内 |
| EXEC-142-006 | clean Worker 矩阵与缺陷回归 | Codex SDK/app-server、Gemini、Claude、LangGraph 的 install/type/test/build；修复 LangGraph progress 事件重复 | 独立 clean worktree；Node `22.23.1`；Python `3.12.3`；[BUG-001](./workitems/BUG-001-langgraph-progress-event-duplication.md) | passed-local-with-hosted-gap-at-time | 本条当时 Node 三 lane 通过；Claude 495 pass/11 deselect；LangGraph 758 pass；本机无 Python 3.11 且 GitHub runner 未执行；Gemini audit 有 1 low/4 moderate。后续 hosted 证据见 `EXEC-142-017` |
| EXEC-142-007 | metadata-query 删除后实施与验证 | 模块/装配/断言/Skill/当前文档退出，metadata-config 保留 | `mvn -B -pl metadata-config-module,launcher -am clean test`、dependency tree、clean target 与 tracked/diff 检查 | completed-local | 15/15 reactor project SUCCESS，总时 `05:23`；metadata-config 4 suites/52 tests、launcher 3 suites/7 tests，均 0 failure/error/skipped。本条形成时启动/浏览器、hosted CI、外部资源和正式验收未运行；后续 hosted 已通过、版本签收已拒绝，专项启动/浏览器与外部资源仍未运行 |
| EXEC-142-008 | P2 显式外部门禁与 readiness 实施 | 平台 `/api/v1/open` 默认关闭、三类 Worker external profile/fail-closed、平台消费 Worker `ready=false` | 提交 `12cbe697`、`5d62707b`、`cce75f1b`；Java 定向矩阵；Codex SDK/app-server 与 LangGraph 测试/type/build | passed-local-partial-scope | Java 74 tests、10/10 reactor；Codex SDK 163 pass/1 skip，app-server 272 pass/1 skip，LangGraph 766 pass，构建通过。路径 canonicalization 绕过已发现并修复；未启用 external，未覆盖 token/identity/audit/ownership、真实网络部署或生产批准 |
| EXEC-142-009 | 根 Java clean test 与 Open SDK 缺陷回归 | 修复 Open SDK 测试编译、显式 JUnit Platform 和 WSL 用例跨平台隔离；复跑根 reactor | 提交 `a2317ae2`；`mvn -B -pl navigator-open-sdk clean test`；`mvn -B clean test`；Surefire XML 汇总 | passed-local-with-warning | Open SDK 142 tests；根 17/17 reactor SUCCESS、2304 tests、0 failure/error/skipped、exit 0、总时 05:43。launcher 有 fork JVM 退出超时告警；hosted CI 与 `clean verify` 未运行；见 [BUG-002](./workitems/BUG-002-open-sdk-clean-test-baseline.md) |
| EXEC-142-010 | 文档一致性与轻量实现自检 | P1/P2/BUG-002 回写、Owner 决策时态、工作项状态、external 模式术语和代码路径 | 两轮独立只读文档审计；版本目录 + 总索引 Markdown 相对链接/锚点检查；Code Inventory 具体路径复核；`git diff --check`、`git status --short` | passed-local | 21 个范围内 Markdown、337 个相对目标、5 个锚点缺失为 0；53 个应存在的关键具体路径均存在；12 个待提交路径均在 `docs/version-tracker/1.4.2-SNAPSHOT`；无业务代码改动。本条形成时正式质量门禁未执行，后续已执行并为 `ready-with-risks` |
| EXEC-142-011 | P2 task capability v2 与 Codex Biz route | 结构化函数快照、TTL、结构化精确 runtime key、不可变 bind tuple、独立事务签发/补偿、Open API 失败撤销、SQL migration/安全 rollback、Codex Biz route | `mvn -B -pl business-agent-module -am test`；Open API 与跨 Provider 定向 Maven；H2 JPA 回滚与 bind/revoke 组合时序；一次性 MySQL 8.0.44/8.4.8 | passed-local-partial-scope | 5/5 reactor、770 tests；business-agent 510；Open API 43；跨 Provider 94；forward/rollback 脚本容器验证含幂等及 rollback 前撤销 ACTIVE token。JPA 用例只提供组合时序下观测到的最终状态证据，不证明确定性锁交错；共享/项目数据库迁移、launcher `ddl-auto=validate`、真实 Worker/浏览器/hosted CI 未执行；principal/lease、终态轮换、outbox/ownership 未完成 |
| EXEC-142-012 | P2 Worker identity、终态与路由治理 | owner-scoped Worker credential v1；pool/identity owner 不变量；LangGraph identity-only pool route；definitive terminal tombstone、late-bind 撤销；Claude tenant 持久化；Codex pre-acceptance 终态；audit writer 独立事务 | 最终 11 reactor clean test；BA integration TypeScript；H2 JPA/定向测试；三组 SQL 在一次性 MySQL 8.0.44/8.4.8 forward×2/rollback×2/reapply | passed-local-partial-scope | 2186 tests、0 failure/error/skip；Node 22.23.1/pnpm 10.34.5 typecheck exit 0。external 仍 disabled/unready；Gateway 尚未消费 strict Worker principal/lease，关键审计 outbox、共享 DB migration、launcher validate、真实网络/浏览器/hosted CI 未执行 |
| EXEC-142-013 | P2 Gateway principal/lease 与 Worker secret 边界 | strict Worker headers；external 默认关闭及 partial/legacy fail closed；DB preselect/prebind；exact worker/lease/tenant/ClientApp/pool/member/backend/owner/route 校验；非 Biz Open API 无 Gateway capability；LangGraph credential 传播与子进程 allowlist/askpass；Codex credential 配置后 unready/Business MCP 503；pool/worker 双向 collision guard | `mvn -B -pl launcher -am clean test`；LangGraph 全量 pytest + ruff；Codex 全量 npm test + typecheck；定向 Gateway/Open API/pool/launcher 测试包含在上述范围 | passed-local-partial-scope | launcher 依赖链 15/15 reactor、2357 tests、0 failure/error/skip；LangGraph 780 passed、ruff 通过；Codex 175 tests 中 174 passed、1 Windows-only skipped，typecheck 通过。未执行真实 L3、non-loopback、浏览器、hosted CI 或共享数据库；开关组合、OS 隔离、Codex 安全转发、Java LangGraph headerless client、远端孤儿补偿、pause/generation/outbox/P3 与 routeKind/schema/存量冲突扫描仍待办 |
| EXEC-142-014 | P3 Session/Task ownership 首批 | 统一 `userId + tenantId` 资源门面；Session/Task/Agent/SSE/config/shared/forward 先授权；task route 不信任请求体；context assigned-ID 独立事务 claim + 条件更新；Provider sessionId 再授权；model config credential 门禁；shared quota 授权/readiness 后原子消费；软删除 fail closed | P3 定向 Maven；`mvn -B -pl launcher -am clean test`；Surefire XML 汇总 | passed-local-partial-scope | 定向 176 tests 通过；clean 15/15 reactor `SUCCESS`，2426 tests、0 failure/error/skipped，launcher 7 tests，05:24，exit 0；日志有测试 JVM 退出后 30 秒 fork kill 非失败提示。本条当时未执行真实双账号 API/浏览器、hosted CI/L3、全列表 tenant、历史数据/性能或正式门禁；后续 hosted 与隔离 Session live 见 `EXEC-142-017/018`。metadata invariant、model owner/grant、Provider taskId 与 admin/system 通路仍待完成 |
| EXEC-142-015 | Echo 默认制品收口 | 删除 addon 5 个 tracked files、root reactor 与 launcher dependency；test-only fixture 替代 A2A lifecycle；移除 L3 Shell 的 Echo 运行依赖并替换 PS1 agentId literal | 静态引用扫描；fixture 定向 tests；launcher 定向 Maven；`bash -n`；diff 核对 | passed-local-partial-scope | 运行引用 0；16/16 tests；14 reactor modules、6/6 tests、BUILD SUCCESS、exit 0；bash syntax passed；`LocalEchoBusinessFunctionAdapterInvoker` 无 diff。本条当时无 `pwsh` 且 hosted/browser/PS parser/formal gate 未执行；后续 hosted 见 `EXEC-142-017`，其余仍待补；`launcher_default_agent_inventory_changed: yes` |
| EXEC-142-016 | 旧 Provider HTTP/SPI/DTO 收口 | Claude/Codex/LangGraph 仓内调用迁入统一 Task API 与统一扩展入口；LangGraph 审批绑定认证主体；旧 HTTP Controller/form、deprecated SPI/DTO 物理移除 | 提交 `50351ada`、`73d31a19`、`97240642`、`fb11137d`、`9f3f1422`、`edee0fc4`、`9008c554`；精确静态扫描；LangGraph/Claude/Codex clean reactor；前端 typecheck/Vitest；Business Agent L3 TypeScript typecheck | passed-local-and-hosted-head | LangGraph 8/8 reactor、68 tests；Claude 8/8 reactor、Claude 367 tests；Codex 8/8 reactor、依赖链 1757 tests（Codex 371）；前端 typecheck、1 Vitest 与 L3 TypeScript 通过。目标旧路由/Controller/已标记 deprecated SPI/DTO 引用归零；active 且未 deprecated 的内部 LangGraph DTO 不在删除范围。`external_contract_changed: yes`，但无生产路由变更；本条形成时 Task live Provider fixture 和正式门禁未执行，后续正式门禁已执行并拒绝，Task live 仍未执行 |
| EXEC-142-017 | Java clean-runner 可复现与 hosted Repository CI | Navigator-owned clean-room `RX` wire-contract shim 替代 clean runner 无法解析的外部 `foggy-core` Maven 依赖；repository CI 在首次全绿 head `9008c554` 和截至本次正式闸门的最新已验证实现 head `9d03bee9` 完成 7-job 矩阵 | 提交 `2a859336`；[run 29323068427](https://github.com/foggy-projects/Foggy-Navigator/actions/runs/29323068427)；[run 29324741945](https://github.com/foggy-projects/Foggy-Navigator/actions/runs/29324741945) | passed-hosted | 两个 run 的 Java launcher 依赖链、Frontend、Node Worker codex-sdk、Node Worker codex-app-server、Node Worker gemini、Python Worker claude、Python Worker langgraph-biz 共 7 jobs 均全部 `success`；Java lane 不含 `navigator-open-sdk` 和 `tools/navigator-chat-observer-bff`。只证明对应 heads 的 hosted workflow；main required checks/branch protection 未配置，修复后 nightly 未实跑，生产批准不在证据内 |
| EXEC-142-018 | 隔离浏览器 ownership 与前端 mock 回归 | 新增显式 guard 的 Session ownership live Playwright；同 tenant 双用户经真实登录 UI，owner 创建/列表/深链/history/删除成功，非 owner 列表不可见且 history/SSE/direct read 被 403 拒绝并显示通用无权提示 | 提交 `9d03bee9`；隔离 H2 + loopback launcher/Vite；全量 Chromium mock Playwright | passed-isolated-partial-scope | live 1 passed（Playwright `2.9s`，编排总时 `3.9s`）；mock 17 passed、1 skipped（`35.2s`）。mock suite 不是运行态 ownership 证据；共享数据库因无明确隔离目标/授权而 `not-run`，Task live Provider fixture 因无安全隔离 fixture 而 `not-run`；隔离验证不等于生产批准 |
| EXEC-142-019 | BUG-003 tenantless ownership 回归 | tenantless exact-owner Session/Task 查询；config 只读批次安全过滤；写批次保持全量 fail closed | 用户 dev PC 报告（凭据未落档/未复用）；test-first 定向 Maven；`mvn -B -pl session-module -am clean test` | ready-for-dev-verification | test-first 先因缺少 null-tenant repository 方法失败；实现后定向 27 tests、clean 6/6 reactor/748 tests 均 0 failure/error/skipped。Spring JPA 上下文成功解析新增查询；尚未部署或使用新令牌复测 dev PC 的 configs/latest/SSE，不得标记 closed |
| EXEC-142-020 | BUG-004 blank-tenant Task ownership 回归 | JWT/API Key tenant 入口规范化；Session 新写入规范化；tenantless Session/Task 查询兼容 NULL/空白并保持 exact userId | 用户 dev PC 报告（凭据未落档/未复用）；test-first 定向 Maven；`mvn -B -pl session-module -am clean test` | ready-for-dev-verification | test-first 3 个预期失败；实现后定向 6/6 reactor、66 tests，clean 6/6 reactor、753 tests，均 0 failure/error/skipped。H2 真实空字符串行同 owner 可读、跨 user 拒绝；未部署、未连接共享 DB、未用新令牌复测 Codex create/get/respond/cancel，不得标记 closed。Codex model config `availableModels` grant 拒绝是独立结果 |

## P2 Execution Check-in（`EXEC-142-008`）

### Completed Work Summary

1. `12cbe697` 增加 `NAVIGATOR_EXTERNAL_ENABLED=false`，仅门禁规范化后的 `/api/v1/open` 及其子路径；关闭返回 HTTP 503 + `EXTERNAL_SURFACE_DISABLED`。`/api/v1/health/external-surface` 中的 `surfaceReady` 只表示 routing gate，不表示 Provider/production ready。matrix/context/encoded 回归曾发现并修复路径规范化绕过。
2. `5d62707b` 为 LangGraph Biz Worker、Codex SDK Worker、Codex app-server Worker 分别增加严格布尔开关 `BIZ_WORKER_EXTERNAL_ENABLED`、`CODEX_WORKER_EXTERNAL_ENABLED`、`CODEX_APP_SERVER_EXTERNAL_ENABLED`，仅接受 `true` / `false`，默认 `false`，mode 统一为 `internal-dev` / `external-enabled`。
3. external-enabled 当前因 `EXTERNAL_EXECUTION_POLICY_PENDING` 始终 unready；空 Token 时叠加 `EXTERNAL_AUTH_TOKEN_REQUIRED`。除精确 `/health` 外，业务 API 返回 HTTP 503 + `EXTERNAL_WORKER_UNREADY`；`/health/` 不在豁免契约内。
4. `cce75f1b` 让平台消费 Worker `ready=false`：LangGraph 标记 `OFFLINE`，Codex SDK connection tester 判定 unready；对未输出 `ready` 字段的旧 Worker 保留 HTTP 200 兼容。

### Changed Surfaces

- 平台 Open API 门禁：`addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/config/ExternalSurfaceProperties.java`、`addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/filter/ExternalSurfaceGateFilter.java`、`addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/health/ExternalSurfaceHealthController.java`、`launcher/src/main/resources/application.yml`。
- Worker external profile：`tools/langgraph-biz-worker/src/langgraph_biz_worker/external_mode.py`、`tools/codex-agent-worker/src/external-mode.ts`、`tools/codex-app-server-worker/src/external-mode.ts` 及各自 config/health/入口/契约测试。
- 平台 readiness 消费：`addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/model/dto/LanggraphWorkerHealthDTO.java`、`addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphWorkerService.java`、`addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexSdkBackendConnectionTester.java` 及对应测试。

### Build / Test / Experience

- Java：`EXEC-142-008` 当时的三模块定向为 74 tests、10/10 reactor SUCCESS；其中平台门禁 8 项 + 当时 Open API mapping 40 项，平台批次 48 项。后续 capability 修复后的 Open API 43 项证据见 `EXEC-142-011`。
- Codex SDK Worker：163 passed / 1 skipped，type-check/build 通过。Codex app-server Worker：272 passed / 1 skipped，type-check/build 通过。LangGraph Biz Worker：766 passed，build 通过。
- Manual/Experience：`not-run`；没有执行真实非 loopback 部署、手工 ClientApp/upstream user 链路、浏览器错误反馈或内部 UI 回归。

### Remaining Risks / Next Gate

- internal-dev 不是网络防火墙；LangGraph/Codex SDK 默认 `0.0.0.0` + 空 Token 的既有开发行为保留，须使用 loopback 或可信网络/ACL。
- 平台 `NAVIGATOR_EXTERNAL_ENABLED` 不覆盖 upstream-admin、`/internal/worker-gateway/v1/**` 或其他内部 Controller。
- 此批次当时尚未实施 task token；后续 `EXEC-142-011` 已补函数快照/TTL/撤销/事务首切片，终态失效/轮换、Worker principal/lease、upstream identity authority、可靠审批/恢复/取消审计和 Session/Task ownership 仍未实施。
- 旧 Provider API/SPI/DTO 按 Owner 决策在本仓消费者迁移、安全语义复核和 clean build 后可同版物理删除，无仓外兼容窗口；本批未执行删除。
- self_check_decision: `continue-in-progress`；本 check-in 形成时 formal quality gate 为 `not-started`，后续已执行并记录为 `ready-with-risks`；本地契约测试仍不等于生产启用或正式验收。

## P2 Task Capability v2 Execution Check-in（`EXEC-142-011`）

### Completed Work Summary

1. 新 task token 使用版本 `2`、generation `1`、audience `WORKER_GATEWAY`、assurance `client-app-delegated`，默认 TTL 30 分钟且硬上限 60 分钟；函数 scope 是签发时 ENABLED ClientApp grants 的结构化 `{functionId, version}` 快照。
2. Gateway list/schema/invoke 同时要求 token snapshot 与当前 ClientApp/user/skill/function 授权成立；v1、错误 audience/assurance/generation 或畸形 scope 均 fail closed。
3. runtime store 只接受结构化 `tenant + session + task` record key；缺 taskId 不再退化为 session token，含冒号 identity 不会发生拼接碰撞。单 token 和 tenant/task 批量撤销按 hash 清理精确 aliases。
4. lifecycle 以 `REQUIRES_NEW` 在 Worker dispatch 前提交 token，自行建立 plain/hash 不变量；bind/revoke 使用悲观写锁与 `@Version`，task/session/worker 首次绑定后不可改写，runtime alias 在事务提交后更新。dispatch、Open API submit/null task/bind 失败或外层 task 事务回滚会在独立事务撤销 token。
5. 前向/回滚 SQL 已登记；旧行回填为 `v1/LEGACY/legacy-unverified/[]`，因此不会被 Gateway v2 接受；rollback 在删 v2 scope 字段前先撤销 ACTIVE token。两份脚本已在一次性 MySQL 8.0.44/8.4.8 容器验证；共享/项目数据库迁移和 launcher `ddl-auto=validate` 尚未执行。
6. Codex Business Agent launcher 改走 `CodexBizTaskProvider`，固定 `codex-biz-worker` route，不再误入默认 `codex-worker`。

### Build / Test / Migration Evidence

- `mvn -B -pl business-agent-module -am test`：5/5 reactor SUCCESS，770 tests、0 failure/error/skip；其中 `business-agent-module` 510 tests。
- 修复后跨模块定向矩阵：Open API mapping 43 + LangGraph E2E 2 + Codex launcher/provider/service 92，共 137 tests、10/10 reactor SUCCESS；其中 Open API 覆盖 submit 已知/意外异常、空 task/taskId、bind 失败及撤销自身失败不遮蔽原结果。
- H2 真实 JPA：2 tests，提供外层 task transaction rollback compensation，以及 bind/revoke 组合时序下 token 最终不复活的证据；不声称确定性复现或证明悲观锁交错。
- 跨 Provider 子集：LangGraph Business Agent E2E 2 tests；Codex launcher/provider/service 92 tests；合计 94 tests，包含在上述 137 项矩阵中。
- MySQL：forward/rollback 脚本已在一次性 8.0.44 与 8.4.8 容器验证，包含第二次重复执行、12 个字段/legacy 回填检查，以及 rollback 撤销 ACTIVE token、删除 v2 字段并保留旧行的检查。
- 首轮定向测试曾有 4 个旧夹具不满足 v2 claims/snapshot；修正夹具后以上最终矩阵全通过，不把夹具偏差冒充生产缺陷。

### Remaining Risks / Next Gate

- `workerLeaseId` 仍为预留字段；Gateway 尚未校验独立 Worker principal/credential/lease 或 PoP。
- generation 尚未轮换，pause/terminal/cancel 尚未自动触发批量撤销；远端 Worker 在 task 外层事务后续失败时也没有取消补偿。
- runtime store 仍为单 JVM 内存态；重启、多实例恢复与撤销传播未解决。
- 共享/项目数据库迁移和 launcher `ddl-auto=validate` 尚未执行。
- tool-message 精确 function/suspension scope、可靠拒绝/关键状态 outbox 与 Open API ownership 未完成；后续 `EXEC-142-014` 已完成 P3 首批内部 ownership，但全列表 tenant、Provider taskId 和显式 system/admin 通路仍未闭环。
- external-enabled 保持默认关闭且 unready；本 check-in 不是生产批准或正式验收。

后续 `EXEC-142-012` 已补 definitive terminal tombstone、Worker credential v1、pool/identity 路由和 audit writer 事务隔离；本段保留 `EXEC-142-011` 当时的证据边界，不回写成当时已经完成。

## P2 Worker Identity / Terminal Execution Check-in（`EXEC-142-012`）

### Completed Work Summary

1. `BizWorkerIdentityEntity` 增加乐观版本和 credential lifecycle 字段。平台 `SUPER_ADMIN` 与 upstream `WORKER_MANAGE` 提供 owner-scoped rotate/revoke；服务端只在 rotate 响应一次性返回 `bwc_` 明文，持久化 SHA-256，严格认证拒绝 legacy v0、错误、过期、撤销、禁用或缺失 credential。该严格 principal 尚未接入 Gateway，不能据此宣称外部 Worker 身份链闭环。
2. 全局 `workerId` 重注册必须保持原 owner/backend；pool 创建、查询、状态和成员操作绑定 tenant + owner，成员加入和运行时路由均重验 pool/identity enabled、healthy、backend 与可见性。LangGraph Business Agent pool route 只采用 `BizWorkerIdentity` endpoint，同名 legacy Worker 不得覆盖；`PLATFORM/platform` 作为显式共享例外，upstream identity 必须 exact owner，physical-only 仅允许平台 identity。`tokenHash` 不作为出站 Bearer。
3. `business_task_terminal_state` 是 task capability 的授权权威。Provider 只在显式 `recoverable=false` 的 definitive terminal 事件写 tombstone；物理 token `REVOKED` 是可重试 materialization。terminal 先于 bind 时，late-bind 会在独立事务中持久化 worker tuple、撤销 token、补全 marker 后再抛专用异常；marker correlation 冲突不覆盖原归属，但仍先撤销冲突 token。即使错误补偿把 token 行重开，Gateway resolve 仍由 business/worker tombstone fail closed。
4. Claude task 新增可信 `tenantId` 持久化和 legacy entity→Session→dedicated Worker 回填；definitive terminal 仍无法解析 tenant 时拒绝提交 null 事件。Codex acceptance 尚未开始且无远端 task 的 `FAILED` 明确为不可恢复；可 resync 的普通 `FAILED` 仍保持 recoverable。Gemini/LangGraph 的 definitive/recoverable 事件字段纳入回归。
5. `BusinessFunctionRuntimeAuditWriter` 作为独立 bean 使用 `REQUIRES_NEW + saveAndFlush`，外层 facade 捕获包括 commit/flush 在内的写失败，避免 best-effort telemetry 回滚主业务。关键审批、拒绝和远程副作用仍没有 outbox/强保证，本批不把 audit 标记完成。

### Build / Test / Migration Evidence

- 最终命令：`mvn -B -pl business-agent-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am clean test`。11/11 reactor `SUCCESS`，总计 2186 tests、0 failure/error/skip：common 47、agent-framework 213、user-auth 72、session 348、business-agent 590、Claude 368、LangGraph 165、Codex 358、Gemini 25；navigator-spi 无测试。总时 03:25。
- clean 首轮真实发现 `ClaudeTaskServiceCheckpointTest` 两个旧夹具缺 tenant；补齐测试实体后单类 7/7 通过，再从头执行上述最终 clean，而不是用 resume-from 结果替代。
- terminal 定向 66 tests：JPA 8、Gateway/TaskService 29、lifecycle unit 29；包含 unbound late-bind、marker mismatch、错误物理重开和两类 tombstone 拒绝。
- Provider/route 定向：Claude 43 + Codex 78 共 121；Gemini 11；LangGraph identity/pool 35。LangGraph 全模块另有 business-agent 590 + LangGraph 165 全通过。
- `business-agent-module/integration-tests` 在 Node `22.23.1`、pnpm `10.34.5` 下执行 `tsc --noEmit`，exit 0；因本机 `localhost:8112` 未启动，带数据写入的 L3 integration test 未运行。
- `2026-07-14-biz-worker-credential-v1`、`2026-07-14-business-task-terminal-state`、`2026-07-14-claude-task-tenant` 三组 forward/rollback 在一次性 MySQL 8.0.44、8.4.8 验证 forward×2、rollback×2、reapply。只操作隔离容器；未操作项目共享数据库，未运行 launcher `ddl-auto=validate`。

### Remaining Risks / Next Gate

- `BizWorkerCredentialService.requireStrictCredential` 当前无 Gateway 调用方；Worker principal header、credential propagation、logical lease、preselect/prebind 和跨 Worker 重放拒绝仍是下一子切片。
- task token definitive terminal 已闭合；pause/suspension、resume generation 轮换、人工 credential 审计、跨实例 runtime secret 恢复与 tombstone 清理任务尚未实现。
- LangGraph identity registry 只持有 hash，不能提供出站 Worker Bearer；internal-dev 无认证兼容保持不变，external-enabled 继续 unready。
- runtime audit 仍是 best-effort；审批/恢复/取消主体绑定和关键拒绝/outbox 未闭合。
- 真实 non-loopback、浏览器、hosted CI、共享 DB migration 和生产 enablement 全部未运行；`production_routing_changed: no`、`external_enablement: no` 保持不变。

后续 `EXEC-142-013` 已接通 Gateway strict Worker principal/lease、Biz Provider preselect/prebind 和 LangGraph 调用方 header；本段保留 `EXEC-142-012` 当时的边界，不改写为当时已经完成。

## P2 Gateway Principal / Lease / Secret Boundary Execution Check-in（`EXEC-142-013`）

### Completed Work Summary

1. Worker Gateway 四类 HTTP 路由统一先经 `WorkerGatewayRequestAuthorizationService` 授权，再进入 DTO/业务服务。严格调用使用 `X-Navigator-Worker-Id`、`X-Navigator-Worker-Credential`、`X-Navigator-Worker-Lease-Id`；legacy `X-Worker-Id`、任一 partial/blank 组合均 fail closed。`navigator.worker-gateway.external-enabled` / `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED` 默认 `false`；仅在该开关关闭且三项 Worker header 全部缺失时保留 internal-dev token-only 兼容，出现任一新 header 就必须完整通过严格认证。
2. 严格路径先校验 Worker credential 与 task capability，再校验 exact worker/lease、token tenant、active ClientApp、pool/member/backend/owner；physical route 只允许 `poolId == workerId` 的精确受治理 route。Gateway health snapshot 不作为 credential authority。poolId 与 workerId 的双向命名碰撞已在新建/注册 service 路径阻断；存量冲突、跨表并发竞态和显式 `routeKind`/schema 仍待后续治理。
3. BusinessTask 与 Open API 的 Biz Provider 在签发 token 前通过 launcher 解析 Worker，生成 `bwl_` logical lease，并以独立事务预绑定 exact worker/lease；真实启动结果必须回报同一 Worker，否则 fail closed 并撤销 capability。Open API 的非 Biz Provider 明确不签发 Worker Gateway capability，不向 runtime context 注入 task token、worker 或 lease。
4. LangGraph Biz Worker 使用成对配置的 `BIZ_WORKER_NAVIGATOR_WORKER_ID` / `BIZ_WORKER_NAVIGATOR_WORKER_CREDENTIAL`，只在可信 runtime `worker_id`/`worker_lease_id` 与本地 Worker 匹配时发送三项严格 header。模型/集成可控子进程环境改为 allowlist；shell 使用 `--noprofile --norc`；Skill Git token 仅通过临时 askpass 进程环境提供，clone/fetch URL 与 argv 不携带 token，临时目录在成功/失败后清理并对错误脱敏。
5. Codex SDK Worker 接受成对的 `CODEX_NAVIGATOR_WORKER_ID` / `CODEX_NAVIGATOR_WORKER_CREDENTIAL` 配置，但当前 CLI、Shell 与 MCP 子进程共享环境，无法在不暴露长期 credential 的前提下安全转发。因此一旦配置长期 Worker credential，health 增加 `CODEX_NAVIGATOR_WORKER_CREDENTIAL_FORWARDING_UNREADY` 并保持 `ready=false`；Business MCP 请求在 SSE、线程预留、任务注册和广播前返回 503。普通非 Business MCP 与未配置该 credential 的 internal token-only 路径保持原行为；通用 task 环境还会移除 ambient `NAVIGATOR_TASK_SCOPED_TOKEN`，仅按当前任务重新注入 MCP token。

### Build / Test Evidence

- `mvn -B -pl launcher -am clean test`：15/15 reactor `SUCCESS`，launcher 依赖链共 2357 tests、0 failure/error/skip。该结果是本地 clean test，不是 `clean verify` 或 hosted CI；launcher 保留已知 Surefire fork JVM 退出超时告警，但命令 exit 0。
- LangGraph Biz Worker：全量 `pytest` 780 passed；`ruff check .` 通过。定向 secret boundary 测试覆盖 command subprocess 环境、无 profile shell、Skill Git askpass/URL/argv/清理/脱敏和 Gateway header 传播。
- Codex SDK Worker：`npm test` 共 175 tests，174 passed、1 个 Windows-only skipped、0 failed；`npm run typecheck` 通过。覆盖 health unready、Business MCP preflight 503、无任务副作用、task env secret stripping 与既有非 Business/internal 路径。
- 上述命令没有建立真实 Worker Gateway 网络链路、双 ClientApp/upstream user 数据矩阵、浏览器体验、共享数据库 migration、launcher `ddl-auto=validate` 或 hosted runner 证据。

### Remaining Risks / Next Gate

- 平台 `NAVIGATOR_EXTERNAL_ENABLED` 与 Gateway `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED` 仍是独立开关，尚无组合 startup/readiness invariant；只开启其中之一不能视为 external-ready。
- LangGraph Worker 主进程仍持有长期 credential，临时 askpass 也不能抵御同 UID `/proc`、ptrace 或等价主机级观察；Codex 父进程同样持有 credential。完成独立 UID/container、受限 `/proc` 或 credential broker 前，不具备 OS 级 secret 隔离，external enablement 保持 `no`。
- Java `LanggraphWorkerClient` 仍是 internal/headerless Gateway 路径；Codex 的严格 credential 转发刻意保持 unready，不能记为已完成客户端接线。
- Open API 在远端 submit 成功、后续本地 bind 失败时会撤销 token，但尚未取消可能已创建的远端任务，存在孤儿任务/成本风险。
- 本 check-in 当时 pause/suspension、resume generation、关键拒绝与状态 outbox、P3 ownership、真实 L3/网络/浏览器/hosted CI 尚未完成；后续 `EXEC-142-014` 已启动 P3 首批实现，但未关闭其运行态和剩余代码面。`production_routing_changed: no`、`external_enablement: no`、P2 `in-progress` 保持不变。

## P3 Session/Task Ownership Execution Check-in（`EXEC-142-014`）

### Completed Work Summary

1. 新增统一 `SessionTaskResourceAccessService`：普通用户访问 Session/Task 必须携带非空 `userId + tenantId`，Task 还会校验关联 Session；owner 缺失/冲突、不存在和软删除资源统一 fail closed。门面不提供空主体、管理员或系统隐式 bypass。
2. Session/Task/AgentTask/AgentDiscovery、SSE、Session config、Sharing Key ask/task、Session forward/relation 的首批入口均在读取子资源、订阅或产生副作用前授权；批量 SSE/config 先验证整个 ID 集合，避免先执行一部分再因后续 ID 拒绝。
3. `TaskDispatchFacade` 与 `TaskOperationRouter` 的 get/list/respond/reconnect/resync/rewind/resume/cancel/delete/scan 首批路径使用已授权 Task；cancel 的 Provider route 取自持久化 Task 投影而非请求体 agent。context 续接和 Provider 返回 sessionId 后均再次授权 Session，避免解析结果成为裸 ID 旁路。
4. `AgentConversationContext` 的 assigned-ID 初始 owner/agent claim 使用 `REQUIRES_NEW persist + flush`；并发 insert 冲突后重读胜出记录，后续写入使用 owner/agent 条件更新，避免 compare 后无条件 `merge/save` 覆盖其他主体。
5. 显式 model config 只有在存在、enabled、tenant 与 Session 一致、owner metadata 完整且 Worker grant 校验通过后才解析订阅或解密凭据。Sharing Key 调用先解析 key owner 的 user/tenant、通过资源授权和 Agent readiness，再以事务内 operation 校验原子消费 quota。
6. 本批次没有修改业务 UI、生产路由或 external 开关；本 check-in 形成时 `acceptance_status: not-started`。后续版本签收已执行并为 `rejected`，`production_routing_changed: no` 与 `external_enablement: no` 保持不变。

### Build / Test Evidence

- P3 定向 Maven 测试矩阵：共计 176 tests，命令通过；覆盖统一资源门面、Session/Task/Agent/SSE/config/shared/forward、context owner claim/条件更新、model config credential guard 与 quota 顺序。
- `mvn -B -pl launcher -am clean test`：15/15 reactor `SUCCESS`，全 reactor 2426 tests、0 failure/error/skipped，launcher 7 tests，总耗时 05:24，exit 0。Surefire XML 分模块为 common 47、SPI 0、framework 213、auth 72、metadata-config 52、session 417、business-agent 641、Claude 369、LangGraph 167、Codex 361、Gemini 25、Echo 0、task-assistant 55、launcher 7。日志存在测试 JVM 退出后 30 秒 fork kill 提示，属于非失败诊断提示，不改变 exit 0 与 reactor/test 结果。
- 真实双账号 API/浏览器、hosted CI、Provider L3、共享数据库、历史数据扫描、查询计划/性能均未执行。
- 本节是 execution check-in，不是 `foggy-implementation-quality-gate`、测试覆盖审计或正式验收。

### Remaining Risks / Next Gate

- `active/page/search/directory` 等列表调用面尚未完成 tenant 贯穿，不能声称所有枚举入口已收口。
- `SessionMetadataService` 仍需建立 service-level tenant invariant；当前 model config 只执行 enabled/tenant/owner metadata/Worker grant 的保守门禁，owner/grant 的最终授权语义尚待 Owner 收敛。
- Provider 返回 sessionId 已重新授权；Provider 返回 taskId 的可信绑定和落库一致性仍未闭环。
- 管理员、system、A2A/恢复需要具名显式通路；当前实现不会以空 user/tenant 或全局 admin bypass 放行。
- 必须补真实双账号 API/浏览器、内部 UI 深链/刷新/重连、Provider L3、hosted CI、历史数据/性能和最终 clean 证据后，才能评估 P3 完成；当前状态保持 `in-progress / partial`。

后续 `EXEC-142-017` 已补 hosted Repository CI 7-job 矩阵，`EXEC-142-018` 已补隔离 H2 的 Session 双账号真实 UI/API/SSE；本段保留 `EXEC-142-014` 当时的证据边界。Task live Provider fixture、共享数据库、L3、全列表 tenant、Provider taskId 和显式 admin/system 通路仍未闭环。

## Legacy Provider Contract Execution Check-in（`EXEC-142-016`）

### Completed Work Summary

1. `50351ada` 先移除 legacy Provider bridge、明确统一 Task SPI 边界，随后按 Provider 独立提交迁移仓内消费者并删除旧入口：Claude HTTP 为 `73d31a19`，Codex 统一扩展入口为 `97240642`、旧 HTTP 为 `fb11137d`，LangGraph approval 为 `9f3f1422`，Claude/Codex deprecated model 分别为 `edee0fc4`、`9008c554`。各切片保持独立提交，可按切片回滚，不把一次性大删除当作迁移证据。
2. Claude、Codex、LangGraph 的旧 `/api/v1/*-tasks` Controller/form 与仓内调用已迁入 `/api/v1/tasks` 统一任务入口或统一 Provider 扩展；Claude/Codex 任务创建内部命令与统一 `DispatchTaskDTO` 取代已废弃的公开 task DTO/form。
3. LangGraph 审批统一由 `/api/v1/tasks/{id}/respond` 进入，先校验认证用户与 tenant 的任务 ownership，再校验待审批主体；`reviewedBy`、`userId` 等请求字段不再成为可信身份来源，审阅者取自认证主体。审批恢复/取消的更广泛外部运行态矩阵仍属于 P2/P3 后续门禁。
4. 精确静态扫描中，目标旧路由、Controller 名称和目标模块内已标记 `@Deprecated` 的 SPI/DTO 引用归零。active 且没有 deprecated 标记、仍由 A2A/Business launcher 使用的内部 LangGraph DTO 不在本切片删除范围，避免把“旧命名”误判为“已废弃契约”。
5. 本切片改变 dev 阶段外部契约，因此 `external_contract_changed: yes`；项目尚未生产，`production_routing_changed: no`、`external_enablement: no`、`production_enablement: not-applicable` 保持不变。

### Build / Test Evidence

- LangGraph clean reactor：8/8 modules `SUCCESS`，68 tests；审批统一入口、ownership 与请求体伪造主体忽略均有定向覆盖。
- Claude clean reactor：8/8 modules `SUCCESS`，Claude 模块 367 tests；旧 HTTP/DTO 移除后统一任务创建与现有服务路径通过。
- Codex clean reactor：8/8 modules `SUCCESS`，依赖链共 1757 tests，其中 Codex 371 tests；统一扩展与旧 HTTP/DTO 移除后的服务、A2A 和 launcher 路径通过。
- Navigator 前端 typecheck、LangGraph Worker API 1 个 Vitest、Business Agent L3 TypeScript `tsc --noEmit` 通过。它们是编译/单测证据，不替代真实 Provider 网络或浏览器任务 fixture。
- hosted Repository CI 结果不重复记入本条，统一见后续 `EXEC-142-017`。

### Remaining Risks / Next Gate

- Task live Provider fixture 未运行，不能用 Session ownership live 或 mock 前端替代审批、恢复、取消的真实任务链证据。
- Provider state envelope/schema、未知版本策略、超大类职责拆分仍未实施，因此 P6 只把 legacy contract 子切片标记完成，阶段整体保持 `in-progress`。
- 本条不是正式质量门禁、覆盖审计或签收，不构成 production/external enablement 批准。

## Hosted CI and Java Reproducibility Check-in（`EXEC-142-017`）

### Completed Work Summary

1. hosted Java job 发现 `com.foggysource:foggy-core:8.1.10.beta` 无法从 clean runner 的公开依赖源解析。`2a859336` 在 `navigator-common` 以 Navigator 自有 clean-room 兼容实现固定仓内实际使用的 `com.foggyframework.core.ex.RX`、`ExRuntimeException` 和 `ExRuntimeExceptionImpl` wire contract，并移除相关模块对外部 `foggy-core` 的构建依赖。
2. 兼容层保留当前响应语义并以 contract tests 锁定；本变更解决的是 clean build 可复现性，不扩展 REST 业务能力，也不声称与未使用的外部库 API 全量兼容。
3. head `9008c554` 的 [GitHub Repository CI run 29323068427](https://github.com/foggy-projects/Foggy-Navigator/actions/runs/29323068427) 首次全绿；浏览器测试提交后的 head `9d03bee9` 又由 [run 29324741945](https://github.com/foggy-projects/Foggy-Navigator/actions/runs/29324741945) 复验。两个 run 的 7 个 jobs 均全部 `success`：Java clean test、Frontend clean checks、Node Worker codex-sdk、Node Worker codex-app-server、Node Worker gemini、Python Worker claude、Python Worker langgraph-biz。

### Evidence Boundary

- 两个 run 是对应 heads 在 hosted clean runner 的 Repository CI 7-job workflow 证据，关闭此前“GitHub runner 从未执行”的缺口，也验证 `foggy-core` 依赖收口后 Java launcher 依赖链可在 clean runner 启动和通过；Java lane 不含 `navigator-open-sdk` 与 `tools/navigator-chat-observer-bff`，`9d03bee9` 是截至正式闸门的最新已验证实现 head。
- main 当前未配置 required checks/branch protection；旧 nightly workflow 曾在校验阶段失败且没有启动 job，修复语法与 clean Java 依赖后的 nightly 矩阵仍未实际执行。单次 hosted success 不等于这些仓库治理门禁已经闭合。
- run 不包含真实 non-loopback Worker 网络、共享数据库 migration、Task live Provider fixture 或生产 enablement；该 run 形成时 `acceptance_status: not-started`，后续版本签收已执行并为 `rejected`。

## Isolated Browser Ownership Check-in（`EXEC-142-018`）

### Completed Work Summary

1. `9d03bee9` 新增 `packages/navigator-frontend/e2e/ownership-live.spec.ts`，默认 skip，只有同时显式设置 live 与 isolated guard、提供测试密码且 base URL 为 loopback 时才执行；trace、截图和视频关闭，测试凭据不进入失败制品。
2. 用一次性 H2、loopback launcher 和 Vite 启动真实 UI/API/SSE。测试通过注册 API建立同一非空 tenant 的两个随机用户，并在两个独立浏览器 context 中通过真实登录页认证；A 创建 Session 后可从列表、深链和 history 读取，最后删除。
3. B 的 Session 列表不包含 A 的资源或 marker；B 深链触发的 history 与 SSE subscribe、直接 Session read 均返回 403，响应使用通用 `Resource access denied`，页面显示“无权限访问”，响应不泄露 sessionId、owner username 或 Session marker。
4. live 用例 1 passed，Playwright 报告 `2.9s`，双 server 编排总时 `3.9s`。同一隔离 Vite 下全量 mock Chromium suite 为 17 passed、1 skipped，`35.2s`；mock suite 只证明前端 mock 回归，不是后端 ownership 运行态证据。

### Not-run / Evidence Boundary

- 共享数据库：`not-run`。当前没有明确的隔离目标和授权，未连接或写入已知共享 MySQL；已有 disposable MySQL migration 证据仍按其原范围使用，不冒充共享数据库验证。
- Task live Provider fixture：`not-run`。Echo 已退出默认 launcher，当前没有安全、可复现且不触碰共享 Worker/数据的隔离 Provider task fixture；因此本条只证明 Session ownership，不证明 Task 审批/恢复/取消 live 主链。
- 真实外部 Worker、non-loopback、生产数据库和生产路由：`not-run`。隔离 H2 验证不是生产批准；该证据形成时 `acceptance_status: not-started`，后续版本签收已执行并为 `rejected`，`production_routing_changed: no` 与 `external_enablement: no` 保持不变。

## Blank Tenant Task Ownership Check-in（`EXEC-142-020`）

### Completed Work Summary

1. 用户报告中的 Codex model config grant 拒绝与 Task ownership 拒绝已拆分诊断。前者由 `availableModels` 不包含请求模型触发，可以编辑或重建配置并显式授权；它不会造成、也不能修复 Task get 的 ownership 拒绝。
2. JWT 新签发、Bearer/query token 与 API Key 进入 `CurrentUser` 时，空字符串和纯空白 tenant 统一规范为 null；`JpaSessionManager` 对新 Session 做相同规范化。
3. tenantless Session/Task repository 查询兼容 `tenant_id IS NULL` 和历史空白行，但始终携带精确 userId；非空 tenant 仍使用原有 userId + tenantId 精确查询，不提供角色或管理员旁路。
4. 无 schema migration、无共享数据库写入、无生产路由或 external enablement 变化。现有 blank-tenant 行可直接通过兼容查询恢复 owner 访问，不要求删除 Task/Session 数据。

### Build / Test Evidence

- test-first 首轮定向命令有 3 个预期失败，分别定位 JWT 签发、Bearer 认证和 API Key 认证的空白 tenant 未规范化；随后实现修复并从头执行最终命令。
- 定向命令覆盖 `JwtUtilTest`、`AuthInterceptorTest`、`SessionTaskResourceAccessServiceTest` 与 `JpaSessionManagerTest`：6/6 reactor `SUCCESS`，66 tests、0 failure/error/skipped。
- `mvn -B -pl session-module -am clean test`：6/6 reactor `SUCCESS`，753 tests、0 failure/error/skipped；common 51、agent-framework 213、user-auth 76、session 413，navigator-spi 无测试，总耗时 01:25。
- H2/JPA 真实持久化空字符串 tenant 的 Session 与 Task；同 userId tenantless 主体读取成功，其他 userId 被统一拒绝。该证据不替代共享 MySQL 扫描、真实 Codex Worker 或 dev PC 运行态复测。

### Remaining Verification

- 部署后废止用户消息中暴露的旧 token，重新登录取得新令牌，再验证 Codex Task create/get/respond/cancel；不得复用已暴露凭据。
- model config 如需支持 `codex-latest:low`，在对应配置的 `availableModels` 中显式加入该模型并单独复测；删除重建只是配置修复手段，不是 ownership 修复步骤。
- BUG-004 保持 `ready-for-verification`；本地通过不追溯改变既有正式签收 `rejected`，也不构成 hosted、共享数据库或生产批准。

## Testing Progress

| Test lane | 状态 | Evidence / 原因 |
|---|---|---|
| Java clean compile/test | passed-local-and-hosted | `EXEC-142-014` 的 launcher clean 为 15/15 reactor、2426 tests、0 failure/error/skipped、exit 0；`EXEC-142-016` 的 LangGraph/Claude/Codex clean reactors 通过；`EXEC-142-017` 的 hosted Java job 在 heads `9008c554`、`9d03bee9` 均 success。根 `clean verify` 未运行 |
| Launcher assembly/package | partial | Maven clean test 已完成 launcher 编译和测试；`package`/`clean verify` 未运行 |
| Navigator PC type-check/test/build | passed | 两个已知 TS 错误做最小修复后，根 frontend type/test/build matrix exit 0 |
| chat-core/chat/widget build | passed | 根 `ci:frontend` / `build:frontend` 已覆盖；chat 测试 105、widget 测试 31 均通过 |
| Mobile type-check/test/build | passed | mobile type-check、59 个测试与 H5 build 通过；非 H5 目标未运行 |
| Claude/Codex/Gemini/LangGraph Worker tests/build | passed-local-and-hosted | 独立 clean worktree 五类 Worker install/type/test/build 通过；`EXEC-142-017` 中 hosted Codex SDK/app-server、Gemini、Claude、LangGraph 5 个 Worker jobs 全部 success；nightly 实跑仍未完成 |
| Ownership negative-path tests | partial-passed-isolated | `EXEC-142-014` 的统一门面及 Session/Task/Agent/SSE/config/shared/forward/context/model-config 定向矩阵 176 tests 通过；`EXEC-142-018` 的同 tenant 双账号真实 UI/API/SSE Session live 1 passed；`EXEC-142-019/020` 覆盖 null/blank tenantless exact-owner，BUG-004 定向 66 与 clean 753 tests 通过。Task live Provider、Open API identity/resource、全列表 tenant、Provider L3、共享 DB 与显式 admin/system 矩阵未运行 |
| task-scoped token 越权测试 | partial-passed-local | v1/错误 claims、跨函数 snapshot、缺 taskId、secret mismatch、撤销、definitive terminal tombstone、late-bind/mismatch 物理重开，以及 strict Worker credential + exact lease/tenant/ClientApp/pool/member/backend/owner/route 均覆盖；pause/generation、跨 task 手工 API 和真实网络未覆盖，见 `EXEC-142-011`、`EXEC-142-012`、`EXEC-142-013` |
| External Worker switch/readiness contract | passed-local | 三类 Worker 默认开关、严格布尔、external unready/空 Token reason、精确 `/health` 豁免和业务 API 503 契约通过；真实 non-loopback 部署未运行，见 `EXEC-142-008` |
| Platform external gate/readiness consumption | partial-passed-local | 路径 canonicalization 绕过已修复；平台门禁、Open API mapping、LangGraph/Codex ready=false 消费已有回归。Gateway external 默认关闭、partial/legacy header fail closed 与 Codex credential-configured unready 已覆盖；平台/Gateway 双开关组合 invariant 与真实部署未覆盖 |
| 删除项引用与 clean build 回归 | passed-local-and-hosted-partial-scope | Monitoring/code-review/metadata-query/Echo 与旧 Provider 契约均有精确静态扫描和本机 clean 证据；旧契约定向 LangGraph/Claude/Codex 矩阵通过，heads `9008c554`、`9d03bee9` 对应 Repository CI 均 7/7 success。外部资源、专项启动/浏览器与 PS parser 仍有缺口 |
| GitHub Actions Repository CI 7-job matrix | passed-hosted-verified-implementation-head | 首次全绿 [run 29323068427](https://github.com/foggy-projects/Foggy-Navigator/actions/runs/29323068427)（head `9008c554`）与截至本次正式闸门的最新已验证实现 [run 29324741945](https://github.com/foggy-projects/Foggy-Navigator/actions/runs/29324741945)（head `9d03bee9`）的 Java launcher 依赖链/Frontend/五类 Worker共 7 jobs 均全部 success；Java lane 不含 Open SDK 和 chat observer BFF；main required checks/branch protection 未配置，修复后 nightly 未实跑 |
| 文档、配置与全工作树检查 | passed-local | `EXEC-142-005` 的 shell/JSON/YAML/lockfile 检查保持有效；本轮 `EXEC-142-010` 复核 21 个范围内 Markdown、337 个相对目标、5 个锚点，缺失为 0；`git diff --check` exit 0，工作树仅含 12 个 1.4.2 文档路径 |

## Experience Progress

| 体验维度 | 检查项 | 状态 | Evidence |
|---|---|---|---|
| 页面可达性 | 内部工作台、Session/Task、Profile；Monitoring 无残留导航或断链 | partial-passed-isolated | `EXEC-142-018` 已验证登录与 Session 深链；Task/Profile/Monitoring 退役专项和完整工作台未做真实运行态验证 |
| 核心交互 | 新建/继续任务、审批、恢复、取消、文件和终端主链不大面积回归 | partial | 隔离 live 已验证 Session 创建、列表、history、删除；Task/审批/恢复/取消、文件、终端真实主链未运行 |
| 表单验证 | ClientApp、upstream mapping/grant、外部 Worker 配置的必填与错误状态 | not-run | not-collected |
| 异常状态 | 缺凭据、越权、token 过期/撤销、Worker unready 的反馈可理解 | partial-passed-isolated | 非 owner Session history/SSE/read 返回通用 403，页面显示“无权限访问”且不泄露 owner/marker；Worker/token 错误体验未运行 |
| 权限可见性 | 内部管理员能力与外部用户能力的可见范围符合边界 | partial-passed-isolated | 同 tenant 非 owner 列表不可见且深链不可读；管理员、system 和外部用户矩阵未运行 |
| 数据一致性 | Session/Task ownership、审批与恢复前后状态一致 | partial-passed-isolated | owner Session 创建/读取/删除与非 owner 拒绝已验证；Task/审批/恢复 live 未运行 |

### Playwright 状态

| 用例 | 覆盖维度 | 状态 |
|---|---|---|
| 可信内网现有任务主链回归 | 页面可达性、核心交互、数据一致性 | passed-mock-only / live-partial：全量 mock Chromium 17 passed、1 skipped；live 只覆盖 Session，不代表现有 Task/Worker 主链 |
| 跨用户 Session/Task 操作拒绝 | 权限可见性、异常状态 | Session passed-isolated / Task not-run：Session 双账号 live 1 passed；Task 缺安全隔离 Provider fixture |
| 审批/恢复/取消主体不匹配拒绝 | 核心交互、权限可见性 | not-run |
| 外部 Worker 缺凭据配置反馈 | 表单验证、异常状态 | not-run |
| 退役能力导航和替代路径 | 页面可达性、核心交互 | not-run |

## Acceptance Criteria Tracking

| Requirement | 状态 | Evidence |
|---|---|---|
| 1. 内部 UI 和可信内网工作流不大面积回归 | partial-passed-isolated | 全量 mock Chromium 17 passed/1 skipped；隔离 H2 Session live 1 passed。Task/Worker、文件、终端真实主链未运行，不能据此签收 |
| 2. 外部 Biz Worker 请求可追溯到 tenant、ClientApp、upstream user 和任务 | partial-passed-local | `EXEC-142-013`：严格 Gateway 路径从 task capability 与 Worker credential 解析并校验 tenant、active ClientApp、upstream user 非空、exact worker/lease、pool/member/backend/owner/route；真实 L3、双主体与可查询审计链未完成 |
| 3. 外部审批、恢复、取消不能只凭 taskId | partial-passed-local | `EXEC-142-016`：LangGraph 审批迁入统一 Task respond，并先校验认证主体、tenant、任务 ownership 与待审批主体；恢复/取消及真实 Task Provider live 未完成 |
| 4. 外部身份不直接取自可伪造请求字段 | partial-passed-local | `EXEC-142-016`：LangGraph respond 的审阅主体来自认证上下文，请求体 `reviewedBy`/`userId` 不作为可信身份；其余外部入口与运行态矩阵未完全闭合 |
| 5. task-scoped token 不越权访问其他任务或函数 | partial-passed-local | `EXEC-142-011/012/013`：结构化函数快照、当前授权交集、缺 taskId、撤销、definitive terminal tombstone/late-bind，以及 exact Worker principal/lease 绑定均通过；pause/generation 和跨 task 手工矩阵未完成 |
| 6. 非 loopback 外部 Worker 缺凭据时 fail closed 或 unready | partial-passed-local-contract | Worker external-enabled 当前因执行策略未就绪始终 unready；Gateway external-enabled 缺完整 Worker principal header fail closed；Codex 配置长期 Worker credential 后因安全转发未就绪而 health unready、Business MCP preflight 503。真实 non-loopback 部署与平台/Gateway组合开关未运行；`EXEC-142-008/013` |
| 7. Java clean 构建测试通过 | passed-local-and-hosted-verified-implementation-head | `EXEC-142-014/016/017`：launcher 与 Provider clean matrices 通过；已验证实现 heads `9008c554`、`9d03bee9` hosted Java jobs 均 success。根 `clean verify` 未运行 |
| 8. 纳入范围的前端类型检查、测试和构建通过 | passed-local-and-hosted-verified-implementation-head | `EXEC-142-003/016/017`：本机前端矩阵和 hosted Frontend job 通过；`EXEC-142-018` 另有 mock/live Playwright。非 H5 mobile 仍不在证据内 |
| 9. Node、包管理器和 lockfile 可复现 | passed-local-and-hosted-verified-implementation-head | 精确 Node/pnpm、本地 frozen lockfile 与 hosted Frontend/Node Worker jobs 通过；required checks/branch protection/nightly 实跑属于仓库治理剩余项 |
| 10. 所有删除项有扫描、迁移/替代和回滚证据 | partial | Monitoring/code-review/metadata-query/Echo 与旧 Provider HTTP/SPI/DTO 均已记录扫描、替代/迁移和独立提交；专项运行态、PS parser 和正式门禁仍有缺口 |
| 11. 获批退役项按完整功能切片退出；retain/migrate/defer 有 Owner 记录 | partial | Monitoring/code-review 代码切片已退出；metadata-query completed-local；Echo completed-local/verification-partial |
| 12. 当前文档不再把 tutor、旧 chat-first 或语义层写成主线 | in-progress | 当前总览/功能架构/观测/安装指引已对齐；失效 Skill 和早期设计文档分级仍待继续 |
| 13. 隔离验收不等同于生产批准 | process-boundary-recorded | `EXEC-142-018` 明确限定一次性 H2/loopback，共享数据库与 Task Provider live 为 not-run；正式签收虽为 `rejected`，`production_enablement: not-applicable`、`external_enablement: no` 和生产路由均未被提升 |

## Formal Gate Results

| Gate | 范围 | 结论 | 记录 | 含义 |
|---|---|---|---|---|
| Implementation Quality Gate | 已执行治理切片至 `9d03bee9` | ready-with-risks | [quality record](./quality/executed-governance-slices-implementation-quality.md) | 未发现必须先返工的新增实现缺陷；token lifecycle、RX shim、Worker 契约漂移和未完成范围需跟进；只允许进入证据覆盖审计 |
| Test Coverage Audit | 1.4.2 整个版本、13 个 AC、2 个 BUG | needs-more-tests | [coverage audit](./coverage/1.4.2-coverage-audit.md) | AC-02 至 AC-06、Task live、P4/P6 和体验证据仍有关键缺口，不能进入正向签收 |
| Version Acceptance | 1.4.2 整个版本 | rejected | [version signoff](./acceptance/version-signoff.md) | 证据足以确认关键标准未满足；已完成切片保留为下一轮基线，不启用 external、不改变生产路由 |

## Acceptance Status

- acceptance_status: rejected
- acceptance_decision: rejected
- signed_off_by: root-controller
- signed_off_at: 2026-07-14
- acceptance_record: [Version Signoff](./acceptance/version-signoff.md)
- blocking_items: external-runtime-boundary-incomplete, task-ownership-live-matrix-incomplete, p4-and-p6-scope-incomplete, coverage-audit-needs-more-tests
- follow_up_required: yes

## Final Documentation Validation

- workspace scope：`git status --short --untracked-files=all` 共 19 个路径，全部位于 `docs/version-tracker/1.4.2-SNAPSHOT/`；本轮正式闸门落档没有业务代码变更。
- whitespace：`git diff --check` exit 0。
- Markdown references：检查版本索引和版本目录共 24 个 Markdown；420 个相对链接、其中 32 个锚点，缺失目标/锚点为 0。
- Markdown structure：119 张表、1273 行列数一致；16 份 frontmatter 均可解析；三份正式报告的字段、枚举和章节顺序有效；coverage matrix 精确覆盖 13 个 AC 与 2 个 BUG，signoff 的 `evidence_count: 20` 与 E-01 至 E-20 一致。
- code boundary scans：非文档源码中三组旧 Provider 路由、已删 Controller 和 Provider 限定 `@Deprecated` 命中均为 0；生产配置中 external 默认 `true/1` 命中为 0；根 reactor 当前为 15 modules。
- keep/remove boundary：`metadata-config-module`、Profile、chat widget、mobile `src/uni_modules`、通用 Coding Agent 注册和 `/c/:id` 深链仍存在；Monitoring、foggy-monitor、metadata-query、code-review-agent、Echo production addon 路径已不存在。
- repository governance：GitHub 查询返回 main `protected=false`、repository rulesets 为空；因此 Repository CI 只能称为 required 候选，不能称为已生效门禁。修复后的 nightly matrix 仍为 `not-run`。

## Implementation Self-Check

- quality_mode: lightweight-self-check
- quality_scope: `GOV-001/GOV-002 P2 external boundary + GOV-003 P3 Session/Task ownership 首批 + P6 legacy Provider contract slice + BUG-002`
- changed_code_paths: P2 平台/Worker external gate、capability/credential/pool/Gateway/terminal/audit/Worker secret boundary；P3 Session/Task ownership 门面、Session/Task/Agent/SSE/config/shared/forward/context/model-config/repository 与 live Playwright；Claude/Codex/LangGraph 统一 Task 入口和旧 HTTP/SPI/DTO 删除；Navigator-owned RX wire-contract shim；Open SDK POM 与测试
- self_check_summary: P2 的默认关闭/unready、capability/credential/Gateway 边界以及 P3 首批 `userId + tenantId` ownership 均有自动化证据；旧 Provider HTTP/SPI/DTO 子切片完成仓内迁移和物理收口。P3 定向 176 tests、launcher clean 15/15 reactor/2426 tests、Provider clean matrices、两次 hosted Repository CI 7/7 jobs 和隔离 Session 双账号 live 均通过。P2/P3/P6 整体保持 in-progress，不把 Codex 安全转发、OS 隔离、开关组合、pause/generation、ownership 全列表/系统主体、Task live Provider、共享 DB、L3 或可靠 outbox 表述为完成
- obvious_risks_or_follow_ups: P2 仍有开关组合、secret OS 隔离、Codex 安全转发、远端孤儿、pause/generation/outbox/L3 缺口；P3 仍有全列表 tenant、SessionMetadata service invariant、model owner/grant 语义、Provider taskId、具名 admin/system、Task live Provider/共享 DB/性能缺口；migration 未部署/launcher validate 未跑，真实 non-loopback 网络未运行；P6 Provider state schema 和超大类治理未实施
- self_check_decision: needs-formal-quality-gate
- formal_gate_timing: 已于 `2026-07-14` 执行；正式报告见 [quality record](./quality/executed-governance-slices-implementation-quality.md)

- [x] requirement 与当前阶段 scope 已收口。
- [x] 非目标没有被意外扩张。
- [x] 本批次代码路径、配置、迁移和文档触点已回写。
- [x] 静态结论与运行态证据已明确区分。
- [x] 本批次测试均记录 pass、fail、not-run 或 not-applicable 及原因。
- [ ] UI 改动已完成体验清单和 Playwright。
- [ ] 删除项已有替代、回滚和完整功能切片证据。
- [x] 计划外变更、风险和阻塞已记录。
- [x] 已执行正式 `foggy-implementation-quality-gate`、`foggy-test-coverage-audit` 和 `foggy-acceptance-signoff`，并保留 `rejected` 结论，不把 execution check-in 或隔离验证冒充验收通过。
- [x] 当前批次进度与证据已回写。

- execution_checkin_summary: partial-passed-for-current-batch；P2 外部门禁/credential/Gateway、P3 首批 ownership 和 P6 legacy contract 子切片已有本地自动化证据；P3 定向 176 tests、launcher clean 15/15 reactor/2426 tests、Provider clean matrices、两次 hosted Repository CI 7/7 jobs、隔离 Session live 1 passed 与 mock 17 passed/1 skipped。真实 non-loopback、Task live Provider、共享 DB、Codex 安全转发、OS 隔离、开关组合、pause/generation、可靠 audit、ownership 全列表/系统主体、Provider schema/超大类仍未完成；正式门禁已执行并因这些缺口拒绝版本签收
- execution_decision: continue-in-progress
- formal_quality_gate_required: yes-cross-module-shared-contract-and-cleanup
- formal_quality_gate_status: reviewed-ready-with-risks

## 计划外变更

- P1 clean Worker 矩阵发现并关闭 [BUG-001](./workitems/BUG-001-langgraph-progress-event-duplication.md)：只修复 LangGraph SSE progress 语义去重，不扩张为图执行或事件协议重构。
- P1 根 Java clean test 发现并关闭 [BUG-002](./workitems/BUG-002-open-sdk-clean-test-baseline.md)：只修复 Open SDK 测试编译、JUnit 5 执行器和跨平台 WSL 测试隔离，不改变 SDK 运行时 API 或放宽生产平台门禁。
- 正式签收后用户报告 [BUG-003](./workitems/BUG-003-tenantless-session-owner-access-regression.md)：最小修复 tenantless exact-owner 与 config 只读批次语义，未引入 admin bypass；本地自动化已通过，dev PC 复测未完成。该缺陷不回写为既有 coverage/signoff 当时已覆盖，版本结论继续保持 `rejected`，关闭剩余 blocker 后仍须按原顺序重验。
- BUG-003 部署前又由同类 dev PC 主链发现 [BUG-004](./workitems/BUG-004-blank-tenant-task-ownership-regression.md)：legacy token 的 `tenantId: ""` 会写出空字符串 Task/Session，而 null-only 查询导致新资源自锁。修复在认证边界规范化空白 tenant，并兼容历史 NULL/空白行；本地定向与 clean 已通过，部署复测未完成。用户同批报告的 Codex `availableModels` grant 拒绝独立处理，不以删除 model config 或业务数据替代 ownership 修复；正式签收结论不追溯改变。

## 后续实施待确认项

| Item | 状态 | Owner/Decision |
|---|---|---|
| GitHub repository CI required check、branch protection 与 nightly workflow 实跑 | pending-execution | 首次全绿 run `29323068427` 与截至正式闸门的最新已验证实现 run `29324741945` 已 7/7 success；main 当前未配置 required checks/branch protection，修复后的 nightly 未实跑，root build owner / repository owner 仍需实施仓库治理配置 |
| Worker credential authority、轮换与撤销传播 | implementation-partial | owner-scoped v1 schema/API/strict verifier、Gateway strict principal/header/lease/prebind 与 LangGraph 客户端传播已落地；Codex 安全转发、OS 隔离、审计/outbox 与撤销传播待办 |
| upstream user mapping/grant 的权威数据源细节 | pending-implementation-design | ClientApp / upstream integration owner；signed assertion 不阻塞 internal-dev |
| task-scoped token 的 per-intent 最小函数 scope、generation 轮换、Worker principal/lease/PoP | pending-implementation | BusinessTask / BusinessFunction / Worker Gateway owner；v2 grant snapshot、TTL、撤销、definitive terminal tombstone、DB preselect/prebind 与 strict principal/lease 已落地；pause/generation、per-intent 最小 scope、Codex 安全转发和真实跨 Worker 负向验证仍缺失 |
| 平台/Gateway external 开关组合与 Worker secret OS 隔离 | pending-implementation-design | Platform / Worker / Security owner；当前两个开关均默认关闭但相互独立，LangGraph/Codex 主进程持有长期 credential；需冻结组合 readiness 与独立 UID/container/受限 `/proc`/credential broker 路径 |
| poolId/workerId route 判别与冲突治理 | pending-implementation-design | Business Agent / DB owner；新写入已有双向 service guard，仍需存量扫描、并发唯一性及显式 `routeKind`/schema 决策 |
| external execution policy、workspace/tool/sandbox/network 上限与真实网络部署验证 | pending-implementation | Worker / Platform / Security owner；显式默认关闭的开关、unready 骨架与平台 ready=false 消费已落地，安全上限未齐前 external 保持未启用 |
| 权威 audit sink、outbox schema、拒绝事件可靠落档实现 | pending-implementation-design | Security / Operations / Business Agent owner |
| metadata-query 启动/浏览器体验与后置重验 | pending-experience-and-signoff | metadata-query / launcher owner；本地代码、clean 与 hosted Repository CI 7-job 矩阵已通过，不等于专项运行态；版本签收已拒绝，补证后参加重验 |
| Echo 浏览器/PowerShell parser 与模块级签收 | pending-verification | provider/test owner；test-only fixture、默认 launcher 退出与 hosted Repository CI 7-job 矩阵已完成，`LocalEchoBusinessFunctionAdapterInvoker` 保留；版本签收已拒绝 |
| 旧 Provider API/SPI/DTO 的逐路由仓内消费者替代矩阵 | completed-local-and-hosted / live-partial | PC / Mobile / SDK / CLI / Provider 仓内迁移和物理删除已完成；无需外部兼容窗口。版本正式门禁已执行并拒绝，Task live Provider fixture 仍待补 |
| 失效 Skills、早期设计文档的 current/historical/candidate 分类 | pending-execution | Product / documentation / skill owners |
| Provider state envelope v1 typed schema、未知版本策略和迁移链 | pending-decision | provider owners |

## 后续衔接

下一批按以下顺序衔接：

1. 本轮 Markdown 链接、`git diff --check` 和工作树范围检查已经完成；workflow/shell/JSON/YAML/lockfile 证据沿用 `EXEC-142-005`，未运行项继续保留。
2. repository CI 已在首次全绿 head `9008c554` 和截至正式闸门的最新已验证实现 head `9d03bee9` 两次 7/7 success；main 当前未配置 required checks/branch protection，下一步由 repository owner 实施这些配置，并让修复后的 nightly 与 release/RC 分层实际运行后单独落档。
3. metadata-query 与 Echo 保持 completed-local/verification-partial，在适用隔离环境补启动、专项浏览器和 PowerShell parser；旧 Provider 契约已完成仓内迁移/保护扫描、物理删除、clean 与 hosted 验证，后续只补 Task live Provider 和正式门禁，不把 Owner 的 dev-only 授权扩大到未知共享资源。
4. P2 已完成 explicit external gate/readiness、task capability v2、definitive terminal、Worker credential v1、pool identity route、Gateway strict principal/lease、Biz Provider preselect/prebind 与 LangGraph header 传播；Codex 安全转发、OS 隔离、开关组合、pause/generation、可靠审计和执行策略上限保留为后续 P2 门禁。signed assertion 仍是未来真正外部开放门禁。
5. P3 Session/Task ownership 已完成首批实现和隔离 Session 双账号 UI/API/SSE；继续补齐全列表 tenant、metadata invariant、Provider taskId、显式 admin/system、Task live Provider、共享 DB 与 L3 证据。P2 的真实网络/L3 缺口继续登记，不以 hosted 或阶段切换冒充 P2 完成。
6. 本轮已按 implementation quality、coverage audit、version signoff 顺序完成正式门禁，结论依次为 `ready-with-risks`、`needs-more-tests`、`rejected`；关闭 blocker 后按同一顺序重验，隔离验证仍不等于生产批准。
