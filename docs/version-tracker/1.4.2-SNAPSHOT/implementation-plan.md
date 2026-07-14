# 1.4.2 平台治理与历史能力收口实施计划

## 文档作用

- doc_type: implementation-plan
- intended_for: root-controller | execution-agent | reviewer | signoff-owner
- purpose: 将 [REQ-001](./requirements/REQ-001-platform-governance-and-legacy-cleanup.md) 拆成 P0-P7 可执行阶段，定义输入、验证、回滚和完成门禁。

## 基本信息

- version: `1.4.2-SNAPSHOT`
- status: in-progress
- operation_mode: single-root-delivery
- implementation_started: yes
- implementation_started_at: `2026-07-14`
- production_routing_changed: no
- launcher_default_agent_inventory_changed: yes
- external_contract_changed: yes
- external_enablement: no
- production_enablement: not-applicable
- acceptance_status: rejected
- requirement: [REQ-001](./requirements/REQ-001-platform-governance-and-legacy-cleanup.md)
- module_responsibility: [Module Responsibility](./module-responsibility.md)
- code_inventory: [Code Inventory](./code-inventory.md)
- owner_decision_review: [Owner Decision Review](./owner-decision-review.md)
- execution_prompt: [Execution Prompt](./execution-prompt.md)
- progress: [Progress](./progress.md)

## 执行总则

1. 本计划是实施依据；`2026-07-14` Owner 决策后已经启动 P0、P1、P2 首批模式门禁与已核准的 dev-only 清理。计划描述不代表某项实现或测试已经完成，实际结果以 [Progress](./progress.md) 为准。
2. 每次只启动一个可独立回滚的阶段或工作项；跨阶段并行必须先冻结共享契约，并分别回写进度。
3. 每个删除候选先完成静态引用扫描、仓内消费者迁移和独立回滚设计。Owner 已免除获批 dev-only 切片的生产流量观察、客户兼容窗口和旧数据备份；若扫描发现共享/生产资源或活跃独立部署，立即停止对应删除并重新决策。
4. 外部治理采用“可信 principal -> 资源归属 -> 作用域 -> 执行 -> 审计”的同一链路；请求体身份字段只可作为业务输入或比对值。
5. 内部治理在 service/facade 建立 ownership 不变量；保留可信内网能力，但必须形成接口清单、部署约束和负向测试。
6. 每阶段完成后先做 implementation self-check；跨模块/高风险阶段再依次执行正式实现质量闸门、测试覆盖审计、正式验收。
7. 隔离环境 smoke、历史版本证据或单模块测试均不等于生产批准；当前不存在生产启用授权，外部模式必须显式开启且默认关闭。

## 证据基线

| Evidence ID | 分类 | 当前结论 | 状态 | 限制/后续 |
|---|---|---|---|---|
| E-001 | 已确认事实 | `UnifiedSseEmitter` 使用单 JVM 内存结构；多实例事件总线延后 | confirmed | 1.4.2 只记录部署边界和重连/readiness，不实现总线 |
| E-002 | 已确认事实 | Provider 状态已有 `ProviderStateCodec` envelope v1 | confirmed | 目标是版本验证、typed adapter、迁移和可观测性，不重复建设 v1 |
| E-003 | 静态搜索结论 | 多个 Session/Task 调用链没有一致传入当前用户 ownership 谓词 | static-only | 需跨用户负向集成测试确认真实暴露面 |
| E-004 | 已确认事实 | ClientApp runtime credential、user grant、task token、审批绑定和审计已有基础实现 | confirmed | 不能描述为从零建设；需补 task 函数 scope、生命周期和拒绝审计 |
| E-005 | 实施前静态结论 + 当前收口证据 | 旧 LangGraph approval 可按 taskId 调用并采用请求体 `reviewedBy`；`9f3f1422` 已移除该路由 | resolved-current-dev-tree | 当前统一使用 `/api/v1/tasks/{taskId}/respond`；Task 先按已认证 `userId + tenantId` 授权，LangGraph 按同一 user 查 pending approval，`reviewedBy` 强制取认证主体。真实 Provider Task/L3 矩阵仍待执行 |
| E-006 | 已确认事实 | Codex/LangGraph 等 Worker 在空 Token 时可跳过认证；部分默认非 loopback | confirmed | 需明确 external-enabled 与 internal-dev 模式 |
| E-007 | 本地构建证据 | launcher 主干依赖链从 clean 状态编译测试通过 | passed-local | `2026-07-14` 执行 `mvn -B -pl launcher -am clean test`，16 个 reactor 项 SUCCESS；不是根 `clean verify` 或 hosted runner 证据 |
| E-008 | 本地 + hosted 构建证据 | Node/Vite/lockfile 不可复现基线已按 ODR-142-001 收口 | passed-local-and-hosted-repository-matrix | Node `22.23.1`、pnpm `10.34.5`、单一根 frozen lockfile 与根 frontend matrix 已落地；截至本次正式闸门的最新已验证实现 HEAD `9d03bee9` 的 Repository CI [`29324741945`](https://github.com/foggy-projects/Foggy-Navigator/actions/runs/29324741945) 7/7 jobs 成功。main branch protection/required checks 当前未配置；修复后 nightly 与完整 Playwright 分层仍待执行 |
| E-009 | 本地构建证据 | 两个 `ClaudeWorkerView.vue` TypeScript 基线错误已做最小修复 | passed-local | 有效 PC/mobile type-check、全 frontend tests/build exit 0；浏览器体验未运行 |
| E-010 | 静态搜索 + 实施证据 | Monitoring、code-review、metadata-query、Echo 及旧 Provider API/SPI/DTO 子切片已物理收口 | partial-implementation | Echo test-only fixture 覆盖 A2A lifecycle，旧 Provider 仓内消费端已迁移到统一 route/typed ports；最新 hosted CI 通过。PowerShell parser、真实 Provider Task/人工体验、共享资源与正式门禁的限制仍保留 |
| E-011 | 本地实施证据 | P2 首批默认关闭门禁和 readiness 已由 `12cbe697`、`5d62707b` 落地 | partial-implementation | 平台只门禁 `/api/v1/open` 路径根及子路径；三类 Worker 显式 external-enabled 仍因执行策略未齐保持 unready。task token、identity、审批/恢复绑定、审计和 ownership 不在该首批结论内 |
| E-012 | 本地测试证据 | P2 首批 Java 与三类 Worker 定向矩阵通过 | passed-local | Java 74 tests、10/10 reactor；Codex SDK 163 passed/1 skipped，app-server 272 passed/1 skipped，LangGraph 766 passed；Node type-check/build 与 Python build 通过。覆盖 matrix parameter、context path、encoded path，并修复实际门禁绕过；不是生产或正式验收证据 |
| E-013 | 本地实施 + 测试证据 | `cce75f1b` 使平台消费端尊重显式 `ready=false` | passed-local | unready Worker 不再被误标为可路由；缺少 `ready` 的旧 Worker保留兼容。兼容不适用于显式 unready，也不构成 external enablement |
| E-014 | 本地构建证据 | `a2317ae2` 关闭 Open SDK clean 基线缺陷，根 Java clean test 通过 | passed-local-with-warning | Open SDK 142 tests；根 17/17 reactor、2304 tests、0 failure/error/skipped，exit 0。launcher 有 Surefire fork JVM 退出超时告警；hosted CI 与 `clean verify` 未执行，见 [BUG-002](./workitems/BUG-002-open-sdk-clean-test-baseline.md) |
| E-015 | 本地实施 + 测试证据 | `EXEC-142-013` 接通 Gateway strict Worker principal/lease、Biz Provider DB preselect/prebind、LangGraph credential 传播/secret boundary，并让 Codex credential-configured 路径 fail closed/unready | passed-local-partial-scope | launcher 15/15 clean reactor、2357 tests；LangGraph 780 pytest + ruff；Codex 174 pass/1 Windows skip + typecheck。平台/Gateway 开关组合、OS 隔离、Codex 安全转发、P3/L3/真实网络/hosted CI 仍未完成 |
| E-016 | 本地实施 + 测试证据 | `EXEC-142-014` 建立 `userId + tenantId` Session/Task 归属窄门面，并收紧 Session/Task/Agent/SSE/config/shared/forward/context/model-config 首批路径 | passed-local-partial-scope | P3 定向 Maven 176 tests 通过；launcher 依赖链 clean test 15/15 reactor、2426 tests、0 failure/error/skipped、exit 0。日志有测试 JVM 退出后 30 秒 fork kill 非失败提示。后续 hosted 与隔离 Session 浏览器证据见 E-008/E-019；Provider Task L3、共享 DB、全列表 tenant、service-level metadata invariant、Provider taskId 与显式 admin/system 通路仍未完成；不是正式质量门禁或验收 |
| E-017 | 本地实施 + hosted 测试证据 | `50351ada`、`73d31a19`、`97240642`、`fb11137d`、`9f3f1422`、`edee0fc4`、`9008c554` 完成旧 Provider API/SPI/DTO 子切片的仓内迁移和物理删除 | passed-local-and-hosted-partial-scope | LangGraph 8/8 reactor、68 Java tests + frontend type-check/1 Vitest + L3 TypeScript；Claude 8/8、367 tests；Codex 8/8、1757 total/371 Codex tests；最新 Repository CI 7/7 jobs 成功。仍活跃的 LangGraph 内部 `LanggraphTaskDTO`/`CreateLanggraphTaskForm` 已保留；巨类拆分和 state schema 强化未完成 |
| E-018 | 构建可复现性证据 | `2a859336` 将仓内实际使用的 RX/异常 wire contract 收入 `navigator-common/src/main/java/com/foggyframework/core/ex/` | passed-local-and-hosted | 移除无法从 clean runner 获取的 `com.foggysource:foggy-core:8.1.10.beta`；`RXContractTest` 和 `GlobalExceptionHandlerTest` 保护序列化/异常契约，截至正式闸门的最新已验证实现 hosted Java job 成功。该 shim 只覆盖当前仓内契约，不声称兼容原库所有 API |
| E-019 | 隔离运行态/浏览器证据 | `9d03bee9` 增加 `packages/navigator-frontend/e2e/ownership-live.spec.ts`，在一次性 H2 上用两个真实登录用户验证 Session ownership | passed-isolated-partial-scope | owner list/深链/history 成功，non-owner list 隐藏且 history/SSE/direct read 均 403，拒绝体不泄露资源/owner 信息。未使用真实 Provider Task 或共享 DB；不等同人工验证、正式验收或生产批准 |

## 阶段与工作项映射

| 阶段 | 主工作项 | 目标 | 前置 | 默认是否可改生产路由 |
|---|---|---|---|---|
| P0 | [DOC-001](./workitems/DOC-001-documentation-alignment.md) + 全部 workitem | 冻结定位、术语、证据边界、代码清单和 Owner | 本计划批准 | no |
| P1 | [OPT-001](./workitems/OPT-001-build-and-ci-baseline.md) | 恢复 Java、前端和 Worker 可复现 clean build | P0 | no |
| P2 | [GOV-001](./workitems/GOV-001-internal-external-trust-boundary.md)、[GOV-002](./workitems/GOV-002-biz-worker-and-upstream-user-boundary.md) | 收敛外部 Biz Worker/upstream user 边界；模式门禁、task capability/terminal、Gateway principal/lease 与 Biz prebind 已部分实施 | P0、P1 最小门禁 | no production routing；external 未启用，开发树的错误/readiness 与 Gateway principal 契约已收紧 |
| P3 | [GOV-003](./workitems/GOV-003-session-task-resource-ownership.md) | 统一内部 Session/Task ownership | P0、P1；复用 P2 术语 | no；越权请求响应会收紧 |
| P4 | [CLEAN-001](./workitems/CLEAN-001-low-risk-orphan-cleanup.md)、[DOC-001](./workitems/DOC-001-documentation-alignment.md) | 清理已核准孤儿和失效当前指引 | P0、P1 | no |
| P5 | [CLEAN-002](./workitems/CLEAN-002-monitoring-retirement.md)、[CLEAN-003](./workitems/CLEAN-003-metadata-query-retirement-audit.md)、[CLEAN-004](./workitems/CLEAN-004-experimental-and-legacy-addon-governance.md) | dev-only 独立移除 Monitoring、metadata-query、code-review；迁移 Echo fixture 后退出生产装配 | P0 清单、P1 最小基线；各切片引用扫描 | 当前无生产路由；发现共享/生产资源即停止 |
| P6 | [OPT-002](./workitems/OPT-002-core-code-maintainability.md)、[CLEAN-004](./workitems/CLEAN-004-experimental-and-legacy-addon-governance.md) | 旧 Provider API/SPI/DTO 子切片已物理收口；继续渐进拆分与状态契约强化 | P1-P3；旧契约仓内消费者已迁移 | 当前无生产契约；统一入口的安全语义和 clean build 是硬门 |
| P7 | 全部工作项 | hosted CI 和隔离 Session 浏览器已有证据；正式质量/覆盖/签收已执行并拒绝，后续补体验、共享 DB 与阻断项后重验 | P0-P6 完成或明确移出版本 | no |

## P0：目标、边界、术语和代码清单冻结

| 要素 | 计划 |
|---|---|
| 输入和前置条件 | 本版本 [Requirement](./requirements/REQ-001-platform-governance-and-legacy-cleanup.md)、现有架构文档、根/launcher Maven 装配、pnpm workspace、历史 1.3.3/1.4.0/1.4.1 证据；由 Product、Platform、Security、各 Module Owner 确认范围。 |
| 涉及模块 | 全仓只读盘点；重点为 `session-module`、`business-agent-module`、四类 Provider Addon、Worker、前端、launcher 和 docs。 |
| 实施内容 | 冻结产品定位；建立 internal-control-plane、external-runtime-plane、tenant、ClientApp、upstream user、effective user、task-scoped token、ownership、readiness 等词汇；核准 [Module Responsibility](./module-responsibility.md) 与 [Code Inventory](./code-inventory.md)；逐项登记已确认事实、静态结论、运行态待证和决策项；指定 Owner 与证据存放位置。 |
| 非目标 | 不修改业务行为；不以规划结论删除文件；不把历史验收复制为 1.4.2 证据；不设计通用 RBAC/ABAC。 |
| 自动化测试 | 文档相对链接检查、`git diff --check`、路径存在性检查；代码测试为 `not-run`。 |
| 手工验证 | Owner 逐项审阅信任矩阵、保留/清理清单、Launcher 装配边界、外部消费者范围和待决策表。 |
| 风险 | 术语未统一导致 P2/P3 各自实现授权；把静态未命中误判为无运行流量；重复规划 1.3.1 已完成能力。 |
| 回滚方式 | 文档独立提交；发现事实错误时回滚该提交或以勘误提交更新，不篡改历史版本证据。 |
| 完成判据 | 10 个计划工作项及实施期缺陷记录都有 Owner/边界/证据分类；代码清单路径可核对；八组 Owner 决策已同步；其余技术决策有截止阶段；版本状态为 in-progress。 |
| 生产路由/外部契约 | production_routing_changed: no；external_contract_changed: no。 |

## P1：构建环境、Node、lockfile 和全仓 CI 基线

| 要素 | 计划 |
|---|---|
| 输入和前置条件 | P0 清单；支持版本矩阵；干净 clone/容器；现有 Maven、npm/pnpm/pyproject 配置；ODR-142-001 已批准。 |
| 涉及模块 | 根 `pom.xml`、`launcher/pom.xml`、`package.json`、`pnpm-workspace.yaml`、lockfile、五个前端包、Node/Python Worker、`.github/workflows/`。 |
| 当前实施状态 | `in-progress / repository-hosted-matrix-passed`。`2a859336` 以 Navigator-owned RX shim 取代 clean runner 不可获取的 `foggy-core` 依赖；截至本次正式闸门的最新已验证实现 HEAD `9d03bee9` 的 Repository CI `29324741945` 7/7 jobs 成功，其中 Java lane 只覆盖 launcher 依赖链。main branch protection/required checks 当前未配置；修复后 nightly、根 `clean verify` 和完整浏览器分层尚未执行。 |
| 实施内容 | 按已批准的 [ODR-142-001](./owner-decision-review.md)冻结 Node `22.23.1`、pnpm `10.34.5`、单一根 lockfile 和 required/nightly/RC 三层 CI；跟踪根 lockfile并使用 frozen install；移除无独立消费者的 chat 嵌套 lockfile；修正 PC 有效 type-check 及两个现存错误；让根脚本覆盖 chat-core/chat/PC/widget/mobile；建立 Java、pnpm、Node Worker、Python Worker clean CI；记录工具版本和制品。 |
| 非目标 | 不借构建修复重构业务；不要求所有 E2E 每 PR 执行；不把 Codex Worker 发布流程当全仓门禁。 |
| 自动化测试 | 本地 Java/frozen frontend/五类 Worker 矩阵已通过；截至正式闸门的最新已验证实现对应 Repository CI 的 Java launcher 依赖链 clean test、Frontend clean checks、3 类 Node Worker 和 2 类 Python Worker 7/7 jobs 成功；Java lane 不含 Open SDK 与 chat observer BFF。main branch protection/required checks 已核对为未配置；修复后 nightly、根 `clean verify` 与完整 Playwright/cross-platform 分层待执行。 |
| 手工验证 | 从无缓存环境复跑；核对制品可启动、CI 矩阵覆盖表、Node/pnpm 版本报错是否清晰；PC/widget/mobile 做目标页面 smoke。 |
| 风险 | lockfile 重建带来大范围依赖漂移；mobile/浏览器 lane 过慢；当前 `vue-tsc --noEmit` 空通过造成假绿。 |
| 回滚方式 | 环境声明、lockfile、脚本和 CI 分提交；依赖升级与门禁修复分离；通过 revert 恢复上一基线，保留失败日志。 |
| 完成判据 | clean Java 通过；根 frozen install 可复现；有效 PC type-check 无错误；纳入范围的前端和 Worker lane 都有真实结果；CI 不依赖本地缓存。 |
| 生产路由/外部契约 | production_routing_changed: no；external_contract_changed: no。仅构建支持版本声明改变。 |

## P2：外部 Biz Worker 与 upstream user 边界治理

| 要素 | 计划 |
|---|---|
| 输入和前置条件 | P0 信任矩阵、P1 最小测试门禁；现有 ClientApp runtime/control credential、upstream user grant、BusinessTask、task token、Gateway、审批绑定与审计；ODR-142-002 至 ODR-142-005 已批准。 |
| 涉及模块 | `business-agent-module`、Claude Open API、Codex/LangGraph Biz Addon、`navigator-open-sdk`、Claude/Codex/Gemini/LangGraph Worker、配置/readiness。 |
| 当前实施状态 | `in-progress`。既有平台/Worker external gate、task capability v2、definitive terminal、Worker credential/pool 已落地；`EXEC-142-013` 新增 `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false`，Gateway 严格要求 `X-Navigator-Worker-Id/Credential/Lease-Id`，partial/blank/legacy fail closed，并校验 exact worker/lease/tenant/ClientApp/pool/member/backend/owner/route。Biz Provider 已 DB preselect/prebind，非 Biz Open API 不获 Gateway capability；LangGraph 已传播 credential 并收紧子进程 secret boundary；Codex 配置长期 credential 后保持 unready、Business MCP preflight 503。external 未启用，P2 仍未完成。 |
| 实施内容 | 已落显式默认关闭模式、Worker readiness、task capability/terminal、credential/pool、Gateway strict principal/lease 与 Biz prebind。后续继续以 ClientApp credential + upstream user mapping/grant 为 internal-dev 身份基线；补平台/Gateway 开关组合 invariant、Codex 安全转发与 OS 隔离、pause/generation、审批/恢复/取消 ownership、可靠 outbox、execution policy 与真实 L3。signed assertion/JWK/jti 作为未来外部开放门禁，不阻塞当前 internal-dev 收口。 |
| 非目标 | 不在本版强制所有 internal-dev ClientApp 接入 signed assertion；不关闭显式 loopback internal-dev；不重写 Spring Security；不实现通用权限平台；不实现动态插件；不在未迁移仓内消费者前删旧 Provider API。 |
| 自动化测试 | `EXEC-142-013`：`mvn -B -pl launcher -am clean test` 15/15 reactor、2357 tests；LangGraph 780 pytest + ruff；Codex 175 tests 中 174 pass/1 Windows skip、typecheck 通过。既有 gate/capability/credential/terminal/migration 证据保持。待执行：双 ClientApp/upstream user/task/function 真实矩阵、pause/generation、审批 binding mismatch、OS 隔离、Codex 安全转发、workdir/tool escalation、reliable audit/outbox、L3/真实网络/hosted CI。 |
| 手工验证 | 当前 `not-run`。后续用两个 tenant、两个 ClientApp、两个 upstream user 和两个任务做正负矩阵；检查 readiness/auth mode；审批暂停恢复全链路；核对审计可追溯字段且不泄露明文 token。还需确认平台 `/api/v1/health/external-surface` 与三类 Worker 精确 `GET /health` 的运维探针配置。 |
| 风险 | 平台与 Gateway external 开关独立且无组合 startup/readiness invariant；`internal-dev` 不是网络防火墙；LangGraph/Codex 主进程持有长期 credential，缺少独立 UID/container/受限 `/proc` 或 credential broker；Codex 严格转发仍 unready，Java LangGraph client 仍 headerless；Open API bind 失败可能遗留远端任务；pool/worker 存量/并发冲突与 routeKind/schema 待办；多实例 token 恢复不一致；best-effort 审计丢关键拒绝。 |
| 回滚方式 | external 当前保持默认关闭；配置/门禁、Worker readiness、平台消费分别位于 `12cbe697`、`5d62707b`、`cce75f1b`，可按依赖逆序独立 revert。发现回归先关闭显式 external 开关，再回滚对应策略提交，不恢复请求体身份信任；后续 token schema 保留只读迁移期；旧 API 删除继续使用独立提交 revert。 |
| 完成判据 | 每个外部请求可追溯 tenant/ClientApp/upstream user/task；task token 不能跨任务/函数；审批/恢复/取消不能只凭 taskId；非 loopback 空凭据 fail closed/unready；审计负向用例有证据。当前已增加本机 Gateway strict principal/lease 与 Biz prebind 证据，但 Codex 安全转发、OS 隔离、开关组合、pause/generation、ownership、outbox、L3/真实网络仍未完成，P2 不得标记完成。 |
| 生产路由/外部契约 | production_routing_changed: no；external_enablement: no；external_contract_changed: yes（开发树的默认关闭、503、readiness 和错误语义已收紧）。平台 `surfaceReady` 仅代表 routing gate；正式启用必须等待其余 P2 门禁、独立审批并回写状态。 |

## P3：Session/Task 定向 ownership 治理

| 要素 | 计划 |
|---|---|
| 输入和前置条件 | P0 资源模型、P1 测试门禁；复用 P2 principal 术语；列出可信内网管理员例外。 |
| 当前实施状态 | `in-progress / partial-implemented-local-and-isolated-verified`。`2a705e09` 已落统一 `userId + tenantId` 门面和首批 Session/Task/Agent/SSE/config/shared/forward/context/model-config 校验；`9f3f1422` 将 LangGraph 审批收口到统一 respond 并使用可信主体；`9d03bee9` 增加隔离 H2 真实双用户 Session 浏览器证据。P3 仍未完成，production routing 未改变，external 未启用。 |
| 涉及模块 | `session-module` 的 Controller/service/facade/repository/SSE、`navigator-common` 的 SessionTask repository；Provider operation adapters；必要的 auth context。 |
| 实施内容 | 已建立统一 `requireOwnedSession/Task` 窄门面；Session get/messages/send/parent/forward/config/shared/SSE 与 Task list/get/respond/reconnect/resync/rewind/resume/cancel 的首批路径先校验归属；Task route 不信任请求体 agent 字段；context assigned-ID 使用独立事务 insert 与 owner 条件更新；Provider 返回 sessionId 后重新授权；显式 model config 校验 enabled/tenant/owner metadata/Worker grant；shared quota 在授权/readiness 后原子消费。LangGraph approval 现由 `/api/v1/tasks/{taskId}/respond` 先做 user/tenant ownership，Provider 再用已认证 user 查 pending approval 并强制写入 `reviewedBy`，不采信请求体身份。后续补全所有列表 tenant 贯穿、service-level metadata invariant、Provider taskId 可信绑定及具名管理员/系统路径。 |
| 非目标 | 不要求全部内部 Controller 迁移到新鉴权框架；不重写 SecurityConfig；不实现多实例 SSE；不改变正常用户的数据模型。 |
| 自动化测试 | 已通过 176 个 P3 定向 Maven tests；随后 launcher 依赖链 clean test 15/15 reactor、2426 tests 全通过。`ownership-live.spec.ts` 在隔离 H2 上验证 owner list/深链/history 正向与 non-owner list 隐藏、history/SSE/direct read 403、通用错误不泄露资源/owner；最新 Repository CI 7/7 jobs 成功。待补：真实 Provider Task/L3、共享 DB、`active/page/search/directory` 全列表、管理员/系统例外。 |
| 手工验证 | `not-run`（自动化隔离浏览器为 partial-passed）。仍需人工两账号执行创建、对话、多 Provider 任务操作、刷新/重连、深链 `/c/:id`；确认正常用户工作流不退化。不将自动化 UI 证据冒充人工体验。 |
| 风险 | 历史系统任务没有 userId/tenantId；Provider 内部恢复依赖系统身份；列表 tenant 未完全贯穿；`SessionMetadataService` service-level tenant invariant 未统一；model config owner/grant 语义未冻结；Provider 返回 taskId 仍可能形成可信边界；重复校验可能造成 N+1、性能或错误码变化。 |
| 回滚方式 | 归属门面与各调用点分步提交；数据回填/兼容规则版本化；若正常流量回归，回滚调用点但保留负向测试和问题记录，禁止改回无校验默认。 |
| 完成判据 | 只凭 sessionId/taskId 不能跨用户读写；所有列出的单资源与列表操作进入统一 invariant；内部 UI/可信内网主流程通过自动和手工验证；Provider 返回 ID 不绕过归属；管理员/系统例外有具名通路、清单与审计。当前已增加 Session 隔离运行态与 hosted CI 证据，但 Task/共享 DB/人工/系统主体等门禁未满足，不得标记完成。 |
| 生产路由/外部契约 | production_routing_changed: no；external_contract_changed: yes（development tree 的旧 LangGraph approval route 已移除）；external_enablement: no。越权请求的响应行为已定向收紧。 |

## P4：低风险孤儿代码和失效文档清理

| 要素 | 计划 |
|---|---|
| 输入和前置条件 | P0 精确清单、P1 构建基线；[CLEAN-001](./workitems/CLEAN-001-low-risk-orphan-cleanup.md) 每项引用扫描和替代说明已完成。 |
| 涉及模块 | 根旧脚本、coding-agent 孤立 lockfile、PC 手工测试/截图、mobile 未引用组件、Python egg-info、精确 API export/mock、当前技能和文档。 |
| 实施内容 | 逐项记录 `git ls-tree` 和全仓 `rg`；确认自动发现/脚本/CI/文档/技能无有效消费者；一个逻辑切片一个提交删除；同步更新当前指引，但不改写历史版本证据；运行对应最小和全局门禁。 |
| 非目标 | 不处理 Monitoring/metadata-query/code-review/echo/旧 Provider API；不泛化删除所有未引用符号；不删除通用 Coding Agent 注册、Profile、深链、widget、mobile uni_modules、keystore、metadata-config。 |
| 自动化测试 | Markdown/skill 链接；PC 有效 type-check/Vitest/build/相关 Playwright；mobile test/H5 build；Claude Worker pytest/package；根 clean lanes；按候选表执行。 |
| 手工验证 | 主前端 tooltip/Workers、mobile Task 卡片替代、Worker 安装打包、技能发现与文档导航；纯生成物可说明无 UX。 |
| 风险 | 动态 import、外部脚本或技能仍引用；删除的手工脚本实际是唯一回归路径；历史证据被误删。 |
| 回滚方式 | 每个切片独立提交并记录 blob/基线；失败时 `git revert` 对应提交；不通过手工复制恢复。 |
| 完成判据 | 每一删除项有引用命令、结果、替代/迁移、验证、提交和回滚证据；所有门禁通过；禁止触碰项无变化。 |
| 生产路由/外部契约 | production_routing_changed: no；external_contract_changed: no。 |

## P5：Monitoring、metadata-query、code-review、echo 的 dev-only 独立收口

| 要素 | 计划 |
|---|---|
| 输入和前置条件 | P0 功能切片清单、P1 最小可复现基线；ODR-142-006 dev-only 物理删除授权；每个切片的仓内引用、装配、资源和测试清单。 |
| 当前实施状态 | `in-progress`。Monitoring/code-review 已移除，metadata-query 已 `completed-local`；Echo 已 `completed-local / verification-partial`：5 个 addon tracked files 与 reactor/launcher 装配退出，test-only fixture 覆盖 discovery/resolve/send/query/cancel，定向 16/16 和 launcher 定向 6/6 tests 通过。 |
| 涉及模块 | Monitoring 全切片、metadata-query 全切片、code-review addon、echo addon/测试装配、相关 launcher/root pom/security/script/UI/docs。 |
| 实施内容 | Monitoring 按 API/UI/RabbitMQ collector/script/security/docs 完整切片删除；metadata-query 按 root reactor/launcher/module/config/docs/test 删除并保护 metadata-config；code-review 整个未装配 addon 独立删除；Echo 先把仍有价值的 discovery/A2A smoke 迁入 dev/test fixture，再移除生产 launcher 装配。每个切片独立提交，不合并成一次大删除。 |
| 非目标 | 不因授权而跳过仓内引用扫描、测试或独立回滚；不操作未确认的共享/生产 RabbitMQ、数据库、webhook 或 credential；不把 `LocalEchoBusinessFunctionAdapterInvoker` 与 Echo addon 混删；不误删 metadata-config。 |
| 自动化测试 | 每个切片删除后的 root/launcher clean test；受影响前端 type/test/build；Echo 替代 fixture 的 discovery/A2A smoke；当前文档和 Skill 链接检查；资源清单静态检查。 |
| 手工验证 | 核对目标环境确为 dev；检查 RabbitMQ/数据库/GitLab/独立部署配置是否出现与 dev-only 前提冲突的证据；核对基础日志/健康观测仍在；按独立提交演练代码回滚。 |
| 风险 | 隐藏仓内消费者；误操作共享资源；launcher discovery 变化；多个同名模块误删；示例 Provider 仍承载测试。 |
| 回滚方式 | 每切片独立 `git revert` 并保留删除前资源定义/路径清单；旧 dev 数据明确允许丢弃，不承诺数据恢复；如发现共享/生产资源，在执行外部资源动作前停止。 |
| 完成判据 | Monitoring、metadata-query、code-review 各按完整切片退出；Echo production 装配退出且 test fixture 可复现；每项有引用扫描、迁移/替代、验证和回滚证据；禁止触碰项无变化。 |
| 生产路由/外部契约 | production_routing_changed: no（当前不存在生产环境）；external_contract_changed: no production contract；launcher_default_agent_inventory_changed: yes，默认制品不再注册 Echo Agent。若发现实际生产/共享环境，立即停止并更新决策。 |

## P6：超大类、Provider 状态 schema 与旧 API 渐进治理

| 要素 | 计划 |
|---|---|
| 输入和前置条件 | P1 可靠测试；P2/P3 稳定授权入口；1.3.1 已完成的 facade/SPI/schema v1 证据；ODR-142-007 直接删除授权；旧 API 仓内消费者清单和替代差距。 |
| 当前实施状态 | `in-progress / legacy-contract-slice-completed`。仓内消费者已迁移，三组旧 Provider HTTP Controller、deprecated SPI bridge 及无剩余用途的 Claude/Codex DTO/form 已分提交物理删除；LangGraph 旧 approval form/controller 也已退出。巨类拆分和 Provider state schema/version 强化仍未开始收口，因此 P6 整体不得标记 completed。 |
| 涉及模块 | 已实施子切片：`session-module/.../TaskController.java`、`navigator-spi/.../TaskCommandProvider.java`、`TaskListingProvider.java`、`WorkerSessionQueryProvider.java`、`addons/codex-worker-agent/.../CodexTaskExtensionController.java`、Claude/Codex 内部 create command、PC/Mobile/L3/bootstrap/Worker canary；待续范围：`ClaudeWorkerView.vue`、`OpenApiController`、Claude/Codex TaskService、`TaskDispatchFacade`、ProviderStateCodec/实体/repository。 |
| 实施内容 | 已完成：统一 API 承接 task detail/respond/rewind/resume 等通用操作，Codex extension route 承接 file hints/generated image/canary，PC、Mobile、Business Agent L3、bootstrap、Worker canary/soak 等仓内引用迁移；删除 `TaskQueryProvider`、`DefaultA2aAgentRegistry`、三组旧 HTTP Controller、`ApproveTaskForm`、Claude `TaskDTO/CreateTaskForm` 与 Codex `CodexTaskDTO/CreateCodexTaskForm`；新增 Claude/Codex 内部 create command。活跃 `LanggraphTaskDTO`/`CreateLanggraphTaskForm` 仍被 A2A、Business launcher 与 service 使用，明确保留。待续：先特征测试和职责图，再渐进拆分页面/大类；为 state envelope 增加严格版本验证、typed adapter、迁移链和解析失败可观测性，JSON LIKE 迁移保持独立数据计划。 |
| 非目标 | 不重做已完成的 schema v1/registry/projection/router；不以行数作为唯一拆分目标；不误删仍服务 typed/unified 契约的 DTO；不动态加载 Addon；不为不存在的仓外客户保留兼容层。 |
| 自动化测试 | 旧契约子切片已通过：LangGraph 8/8 reactor、68 Java tests、主前端 type-check + 1 Vitest、Business Agent L3 TypeScript；Claude 8/8 reactor、367 tests；Codex 8/8 reactor、1757 total/371 Codex tests；最新 Repository CI 7/7 jobs 成功；旧路由/已删 class 对当前业务源码无引用。待续巨类/state schema 需执行 characterization、v0/v1/未知版本/损坏 JSON/迁移幂等、resume/reconnect、性能与数据迁移测试。 |
| 手工验证 | `not-run`。仍需 Claude/Codex/Gemini/LangGraph 真实 Provider 创建、流式、恢复、审批、生成图片/file hints；页面主流程和深链；升级前数据副本迁移演练；性能对比。Session ownership 隔离浏览器不替代这些 Provider 体验。 |
| 风险 | 超大类隐式状态耦合；Provider 恢复兼容破坏；JSON 查询迁移影响数据；仓内 canary、UI 或测试仍依赖旧响应语义；并行改动冲突。 |
| 回滚方式 | 提取与行为变更分提交；保留旧 envelope reader 和双读/回退窗口；统一 API 先增后迁；旧 route/SPI/DTO 分批删除并可独立 revert。旧 dev 数据和历史图片链接无需恢复。 |
| 完成判据 | 旧 API 子判据已满足：每个旧路由有仓内消费者迁移/删除、对应提交和验证，deprecated SPI/无用 DTO 无当前调用且 clean/hosted build 通过。P6 整体完成仍要求每次巨类拆分有可读职责和等价测试，新写入遵守版本化 typed contract，未知/损坏状态行为明确。后两项尚未满足。 |
| 生产路由/外部契约 | production_routing_changed: no（当前 dev 前提）；external_contract_changed: yes（development tree 旧 Provider route/SPI/DTO 已移除）；当前无 production contract，external_enablement: no。未来外部开放不提供旧契约。 |

## P7：覆盖审计、体验验证和正式签收

| 要素 | 计划 |
|---|---|
| 输入和前置条件 | P0-P6 进度、测试日志、手工体验、运行态审计、迁移/回滚演练、所有 Owner 决策；未完成项必须明确移出版本或阻塞。 |
| 当前实施状态 | `completed-formal-review / exit-failed`。截至本次正式闸门的最新已验证实现 Repository CI 已 7/7 jobs 成功，Session ownership 已有隔离 H2 真实双用户浏览器证据；[质量闸门](./quality/executed-governance-slices-implementation-quality.md) 为 `ready-with-risks`，[覆盖审计](./coverage/1.4.2-coverage-audit.md) 为 `needs-more-tests`，[正式签收](./acceptance/version-signoff.md) 为 `rejected`。共享 DB、真实 Provider Task/L3 和完整人工体验仍未执行。 |
| 涉及模块 | 全部本版本触点与文档门禁。 |
| 实施内容 | 更新 [Progress](./progress.md)；先做 implementation self-check；跨模块改动执行 `foggy-implementation-quality-gate` 正式质量闸门；随后执行 `foggy-test-coverage-audit` 映射 requirement/workitem/acceptance 到证据；最后执行 `foggy-acceptance-signoff` 输出 accepted/rejected/blocked；单独记录 production enablement 决定。 |
| 非目标 | 不以文档完整代替实现；不以测试数量代替风险覆盖；不把 isolated smoke 视为 production approval；不在验收时顺手修复未评审的新问题。 |
| 自动化测试 | 已有：HEAD `9d03bee9` 的 Repository CI [`29324741945`](https://github.com/foggy-projects/Foggy-Navigator/actions/runs/29324741945) Java/frontend/3 类 Node Worker/2 类 Python Worker 7/7 jobs 成功；隔离 H2 的 `ownership-live.spec.ts` 覆盖 Session owner/non-owner 正负向。待执行：真实 Provider Task 授权矩阵、共享 DB/迁移、完整 Playwright/nightly/cross-platform；本轮 Markdown 相对链接与 `git diff --check` 结果回写到 Progress。 |
| 手工验证 | `not-run`。仍需内部 UI/可信内网；外部 ClientApp/upstream user 全链路；真实 Provider 审批/恢复/取消；readiness；PC/widget/mobile 关键体验；退役/回滚演练。已有 Playwright 自动化证据不冒充人工体验。 |
| 风险 | 证据来自不同提交；测试环境与生产配置差异；未决 Owner 项被隐藏；隔离通过被误当生产批准。 |
| 回滚方式 | 验收不直接改变生产；失败项回到对应阶段，保留 rejected/blocked 证据；生产启用有独立审批、灰度和回滚 runbook。 |
| 完成判据 | 13 项版本门禁都有可定位证据；质量闸门通过；覆盖审计允许进入正向验收；正式签收明确 accepted/rejected/blocked；production enablement 单独决策。当前正式流程已完成但结论为 `rejected`，P7 出口未通过；关闭签收记录中的 blocker 后重跑。 |
| 生产路由/外部契约 | production_routing_changed: no（验收动作）；production_enablement 不得由验收结果自动推导。 |

## Acceptance Status

- acceptance_status: rejected
- acceptance_decision: rejected
- signed_off_by: root-controller
- signed_off_at: 2026-07-14
- acceptance_record: [Version Signoff](./acceptance/version-signoff.md)
- blocking_items: external-runtime-boundary-incomplete, task-ownership-live-matrix-incomplete, p4-and-p6-scope-incomplete, coverage-audit-needs-more-tests
- follow_up_required: yes

## 阶段出口记录要求

每个阶段必须在 [Progress](./progress.md) 回写：

- 实际提交/PR 与精确路径；
- 自动化命令、环境版本、退出码和日志/制品位置；
- 手工体验步骤、账号/租户矩阵和结果；
- 与 requirement/acceptance 的映射；
- 新发现范围、计划外变更和阻塞项；
- 生产路由、外部契约、数据迁移是否变化；
- 回滚演练或可执行回滚说明。

## 最终验收门禁

| Gate | 判定标准 | 主阶段 |
|---|---|---|
| AC-01 | 内部 UI 和可信内网主工作流无大面积回归 | P3/P7 |
| AC-02 | 外部 LangBizWorker/CodexBizWorker 请求追溯到 tenant、ClientApp、upstream user、任务 | P2 |
| AC-03 | 外部审批、恢复、取消不能只凭 taskId | P2 |
| AC-04 | 外部身份不取自可伪造请求字段 | P2 |
| AC-05 | task token 不得访问其他任务或未允许函数 | P2 |
| AC-06 | 非 loopback external Worker 缺凭据 fail closed 或 unready | P2 |
| AC-07 | Java clean 环境构建测试通过 | P1/P7 |
| AC-08 | 主前端及纳入包 type/test/build 通过 | P1/P7 |
| AC-09 | Node/包管理器明确，lockfile frozen 可复现 | P1 |
| AC-10 | 所有删除项有扫描、迁移/替代和回滚证据 | P4/P5/P6 |
| AC-11 | 凡获批退役的 Monitoring、metadata-query 等能力按完整切片退出；保留、迁移或延后项有 Owner 记录 | P5 |
| AC-12 | 当前文档不再把 tutor、旧 chat-first 或语义层作为主线 | P0/P4 |
| AC-13 | 隔离验收与生产批准分离 | P7 |

## Owner 决策落实表

完整理由、替代方案、验证门禁、回滚和授权记录见 [Owner 决策记录](./owner-decision-review.md)。以下决策于 `2026-07-14` 生效；批准不等于实现或测试完成。

| ID | Owner 决策 | 落实阶段 | 状态/实施约束 |
|---|---|---|---|
| ODR-142-001 | Node `22.23.1`、pnpm `10.34.5`、单一根 lockfile；核心构建 required，完整 E2E/cross-platform nightly，真实凭据 smoke 进入 RC | P1 | in-progress；本地 clean/frozen/frontend 与截至正式闸门的最新已验证实现 Repository CI 7/7 jobs 通过；main required checks/branch protection 未配置，修复后 nightly 与完整 E2E/cross-platform 待执行 |
| ODR-142-002 | internal-dev 保留 ClientApp 代办；signed assertion 降为未来外部开放门禁 | P2 / future external | in-progress；显式、默认关闭的首批平台/Worker 开关已落地，ClientApp/upstream identity 链路尚未收口 |
| ODR-142-003 | 服务端权威 opaque task token，30 分钟 TTL，完整授权交集，并与 Worker principal/lease 双重校验，暂停/终态失效 | P2 | implementation-partial；v2/TTL/撤销/definitive terminal、DB preselect/prebind 与 Gateway strict principal/lease 已落地；pause/generation、Codex 安全转发和运行态矩阵待办 |
| ODR-142-004 | 双运行模式；external-enabled 目录/工具默认拒绝、`workspace-write`、任务工具 egress 默认拒绝、缺凭据 unready/fail closed | P2 | in-progress；模式/readiness 第一批已实施，执行策略尚未完成，因此显式 external 仍强制 unready、不得打开业务流量 |
| ODR-142-005 | 本地关键状态事务 outbox；无状态拒绝可靠落档；远程调用意图/结果分段记录；高频遥测 best-effort | P2/P7 | approved |
| ODR-142-006 | dev-only 安全后物理移除 Monitoring、metadata-query、code-review；Echo fixture 迁移后退出默认装配，旧数据可丢弃 | P5 | in-progress；Monitoring/code-review 已移除，metadata-query 已 completed-local；Echo 已 completed-local/verification-partial，默认 launcher inventory 已改变，hosted 与版本正式门禁已执行且签收拒绝，专项 browser/PS parser/模块签收待补 |
| ODR-142-007 | 仓内消费者迁移后在 1.4.2 直接删除旧 Provider API/SPI/DTO | P6 | legacy-contract-slice-completed；仓内迁移和物理删除已分提交完成，分 Provider clean/前端/L3 与截至正式闸门的最新已验证实现 hosted CI 通过；版本正式门禁已执行且签收拒绝，真实 Provider 体验仍待执行，P6 巨类/state schema 仍未完成 |
| ODR-142-008 | 当前指引修正、历史证据标记、活跃 Skill 修正、确认失效 Skill 退出活跃发现 | P0/P4 | approved |

既有 `DEC-003` Provider state envelope 演进、credential authority、mapping/grant 权威数据源和超大类拆分顺序不因上述评审稿自动关闭，仍按各自 workitem 保持 `pending-decision`。
