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
- production_enablement: not-applicable
- acceptance_status: not-started
- requirement: [REQ-001](./requirements/REQ-001-platform-governance-and-legacy-cleanup.md)
- module_responsibility: [Module Responsibility](./module-responsibility.md)
- code_inventory: [Code Inventory](./code-inventory.md)
- owner_decision_review: [Owner Decision Review](./owner-decision-review.md)
- execution_prompt: [Execution Prompt](./execution-prompt.md)
- progress: [Progress](./progress.md)

## 执行总则

1. 本计划是实施依据；`2026-07-14` Owner 决策后已经启动 P0、P1 与已核准的 dev-only 清理。计划描述不代表某项实现或测试已经完成，实际结果以 [Progress](./progress.md) 为准。
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
| E-005 | 静态搜索结论 | 旧 LangGraph approval 可按 taskId 调用并采用请求体 `reviewedBy` | static-only | P2 优先补负向测试、隔离和迁移 |
| E-006 | 已确认事实 | Codex/LangGraph 等 Worker 在空 Token 时可跳过认证；部分默认非 loopback | confirmed | 需明确 external-enabled 与 internal-dev 模式 |
| E-007 | 本地构建证据 | launcher 主干依赖链从 clean 状态编译测试通过 | passed-local | `2026-07-14` 执行 `mvn -B -pl launcher -am clean test`，16 个 reactor 项 SUCCESS；不是根 `clean verify` 或 hosted runner 证据 |
| E-008 | 本地构建证据 | Node/Vite/lockfile 不可复现基线已按 ODR-142-001 收口 | passed-local-partial-ci | Node `22.23.1`、pnpm `10.34.5`、单一根 frozen lockfile 与根 frontend matrix 已落地并本地通过；GitHub runner/nightly 未运行 |
| E-009 | 本地构建证据 | 两个 `ClaudeWorkerView.vue` TypeScript 基线错误已做最小修复 | passed-local | 有效 PC/mobile type-check、全 frontend tests/build exit 0；浏览器体验未运行 |
| E-010 | 静态搜索 + 实施证据 | Monitoring 与 code-review 已删除；metadata-query、Echo 和旧 API 尚未实施 | partial-implementation | 前两切片的仓内扫描和 Java/frontend 回归通过；未操作外部资源，其余切片仍按独立门禁执行 |

## 阶段与工作项映射

| 阶段 | 主工作项 | 目标 | 前置 | 默认是否可改生产路由 |
|---|---|---|---|---|
| P0 | [DOC-001](./workitems/DOC-001-documentation-alignment.md) + 全部 workitem | 冻结定位、术语、证据边界、代码清单和 Owner | 本计划批准 | no |
| P1 | [OPT-001](./workitems/OPT-001-build-and-ci-baseline.md) | 恢复 Java、前端和 Worker 可复现 clean build | P0 | no |
| P2 | [GOV-001](./workitems/GOV-001-internal-external-trust-boundary.md)、[GOV-002](./workitems/GOV-002-biz-worker-and-upstream-user-boundary.md) | 收敛外部 Biz Worker/upstream user 边界 | P0、P1 最小门禁 | no；启用前可改变外部错误/认证契约 |
| P3 | [GOV-003](./workitems/GOV-003-session-task-resource-ownership.md) | 统一内部 Session/Task ownership | P0、P1；复用 P2 术语 | no；越权请求响应会收紧 |
| P4 | [CLEAN-001](./workitems/CLEAN-001-low-risk-orphan-cleanup.md)、[DOC-001](./workitems/DOC-001-documentation-alignment.md) | 清理已核准孤儿和失效当前指引 | P0、P1 | no |
| P5 | [CLEAN-002](./workitems/CLEAN-002-monitoring-retirement.md)、[CLEAN-003](./workitems/CLEAN-003-metadata-query-retirement-audit.md)、[CLEAN-004](./workitems/CLEAN-004-experimental-and-legacy-addon-governance.md) | dev-only 独立移除 Monitoring、metadata-query、code-review；迁移 Echo fixture 后退出生产装配 | P0 清单、P1 最小基线；各切片引用扫描 | 当前无生产路由；发现共享/生产资源即停止 |
| P6 | [OPT-002](./workitems/OPT-002-core-code-maintainability.md)、[CLEAN-004](./workitems/CLEAN-004-experimental-and-legacy-addon-governance.md) | 渐进拆分、状态契约强化；仓内迁移后直接删除旧 Provider API/SPI/DTO | P1-P3；当前仓内消费者替代入口 | 当前无生产契约；统一入口的安全语义和 clean build 是硬门 |
| P7 | 全部工作项 | 质量、覆盖、体验、证据和正式签收 | P0-P6 完成或明确移出版本 | no |

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
| 完成判据 | 10 个工作项都有 Owner/边界/证据分类；代码清单路径可核对；八组 Owner 决策已同步；其余技术决策有截止阶段；版本状态为 in-progress。 |
| 生产路由/外部契约 | production_routing_changed: no；external_contract_changed: no。 |

## P1：构建环境、Node、lockfile 和全仓 CI 基线

| 要素 | 计划 |
|---|---|
| 输入和前置条件 | P0 清单；支持版本矩阵；干净 clone/容器；现有 Maven、npm/pnpm/pyproject 配置；ODR-142-001 已批准。 |
| 涉及模块 | 根 `pom.xml`、`launcher/pom.xml`、`package.json`、`pnpm-workspace.yaml`、lockfile、五个前端包、Node/Python Worker、`.github/workflows/`。 |
| 实施内容 | 按已批准的 [ODR-142-001](./owner-decision-review.md)冻结 Node `22.23.1`、pnpm `10.34.5`、单一根 lockfile 和 required/nightly/RC 三层 CI；跟踪根 lockfile并使用 frozen install；移除无独立消费者的 chat 嵌套 lockfile；修正 PC 有效 type-check 及两个现存错误；让根脚本覆盖 chat-core/chat/PC/widget/mobile；建立 Java、pnpm、Node Worker、Python Worker clean CI；记录工具版本和制品。 |
| 非目标 | 不借构建修复重构业务；不要求所有 E2E 每 PR 执行；不把 Codex Worker 发布流程当全仓门禁。 |
| 自动化测试 | `mvn -B clean test -pl launcher -am`；必要时根 reactor lane；`corepack pnpm install --frozen-lockfile`；各包有效 type-check/test/build；Node Worker `npm ci` 后 test/typecheck/build；Python Worker 新 venv 安装、pytest/build；命令以各包真实 script 为准并回写。 |
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
| 实施内容 | 先落单一显式、默认关闭的 `external-enabled` 模式和 readiness；internal-dev 继续以 ClientApp credential + upstream user mapping/grant 为现行身份基线；用 credential principal 确认 tenant/ClientApp，不直接信任请求体 actor；服务端固化 task/session/user/skill/workspace/model/function scope；task token 加版本、TTL、撤销/轮换和 terminal invalidation；Gateway 强制跨任务/跨函数拒绝；审批、恢复、取消绑定主体和任务；隔离旧 LangGraph taskId/reviewedBy 链路；external-enabled 非 loopback 缺凭据 fail closed/unready；服务端固定 workdir/tool/sandbox 上限；补调用、拒绝、暂停、审批、恢复、取消、失败审计与查询。signed assertion/JWK/jti 作为未来外部开放门禁保留设计，不阻塞当前 internal-dev 收口。 |
| 非目标 | 不在本版强制所有 internal-dev ClientApp 接入 signed assertion；不关闭显式 loopback internal-dev；不重写 Spring Security；不实现通用权限平台；不实现动态插件；不在未迁移仓内消费者前删旧 Provider API。 |
| 自动化测试 | external-enabled 默认关闭及显式开启；credential 过期/撤销/轮换；伪造 tenant/user/reviewer；跨 ClientApp/upstream user/task/function；token TTL/终态撤销/重启恢复；审批 binding mismatch；非 loopback 缺凭据；workdir traversal/tool escalation；审计成功与拒绝事件；SDK 契约。signed assertion 专属负向矩阵延后到外部开放前。 |
| 手工验证 | 用两个 tenant、两个 ClientApp、两个 upstream user 和两个任务做正负矩阵；检查 readiness/auth mode；审批暂停恢复全链路；核对审计可追溯字段且不泄露明文 token。 |
| 风险 | external 开关默认值或配置别名错误导致隐式暴露；现有调用方只传 upstream user header；多实例内存 token 注入恢复不一致；收紧 Codex sandbox 破坏开发工作流；best-effort 审计丢关键拒绝。 |
| 回滚方式 | 默认关闭 external-enabled；先以版本化配置和兼容读取落地；保留旧 token schema 只读期；发现回归关闭外部模式并回滚策略提交，不恢复请求体身份信任；旧 API 删除以独立提交 revert。 |
| 完成判据 | 每个外部请求可追溯 tenant/ClientApp/upstream user/task；task token 不能跨任务/函数；审批/恢复/取消不能只凭 taskId；非 loopback 空凭据 fail closed/unready；审计负向用例有证据。 |
| 生产路由/外部契约 | 默认 production_routing_changed: no；external_contract_changed: yes（认证失败、scope、readiness 和错误语义会收紧）。正式启用必须独立审批并回写状态。 |

## P3：Session/Task 定向 ownership 治理

| 要素 | 计划 |
|---|---|
| 输入和前置条件 | P0 资源模型、P1 测试门禁；复用 P2 principal 术语；列出可信内网管理员例外。 |
| 涉及模块 | `session-module` 的 Controller/service/facade/repository/SSE；Provider operation adapters；必要的 auth context。 |
| 实施内容 | 建立统一 `requireOwnedSession/Task` 或等价窄门面；Session get/messages/send/parent、Task list/get/respond/reconnect/resync/rewind/resume/cancel 先校验归属；任务归属从 task->session/user 可信关系解析；管理员/系统例外显式命名并审计；SSE 已有 session owner 校验保持一致。 |
| 非目标 | 不要求全部内部 Controller 迁移到新鉴权框架；不重写 SecurityConfig；不实现多实例 SSE；不改变正常用户的数据模型。 |
| 自动化测试 | 两用户 sessionId/taskId 枚举负向测试；跨用户 messages/list/operation/cancel/resume；parent session；管理员例外；正常 UI 回归；Provider 各操作路由的参数传播。 |
| 手工验证 | 两账号执行创建、对话、任务操作、刷新/重连、深链 `/c/:id`；确认正常用户工作流不退化，越权响应一致且不泄露资源存在性。 |
| 风险 | 历史系统任务没有 userId；Provider 内部恢复依赖系统身份；重复校验造成性能或错误码变化。 |
| 回滚方式 | 归属门面与各调用点分步提交；数据回填/兼容规则版本化；若正常流量回归，回滚调用点但保留负向测试和问题记录，禁止改回无校验默认。 |
| 完成判据 | 只凭 sessionId/taskId 不能跨用户读写；所有列出的操作进入统一 invariant；内部 UI/可信内网主流程通过自动和手工验证；管理员例外有清单与审计。 |
| 生产路由/外部契约 | production_routing_changed: no；external_contract_changed: no。越权请求的响应行为会定向收紧。 |

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
| 涉及模块 | Monitoring 全切片、metadata-query 全切片、code-review addon、echo addon/测试装配、相关 launcher/root pom/security/script/UI/docs。 |
| 实施内容 | Monitoring 按 API/UI/RabbitMQ collector/script/security/docs 完整切片删除；metadata-query 按 root reactor/launcher/module/config/docs/test 删除并保护 metadata-config；code-review 整个未装配 addon 独立删除；Echo 先把仍有价值的 discovery/A2A smoke 迁入 dev/test fixture，再移除生产 launcher 装配。每个切片独立提交，不合并成一次大删除。 |
| 非目标 | 不因授权而跳过仓内引用扫描、测试或独立回滚；不操作未确认的共享/生产 RabbitMQ、数据库、webhook 或 credential；不把 `LocalEchoBusinessFunctionAdapterInvoker` 与 Echo addon 混删；不误删 metadata-config。 |
| 自动化测试 | 每个切片删除后的 root/launcher clean test；受影响前端 type/test/build；Echo 替代 fixture 的 discovery/A2A smoke；当前文档和 Skill 链接检查；资源清单静态检查。 |
| 手工验证 | 核对目标环境确为 dev；检查 RabbitMQ/数据库/GitLab/独立部署配置是否出现与 dev-only 前提冲突的证据；核对基础日志/健康观测仍在；按独立提交演练代码回滚。 |
| 风险 | 隐藏仓内消费者；误操作共享资源；launcher discovery 变化；多个同名模块误删；示例 Provider 仍承载测试。 |
| 回滚方式 | 每切片独立 `git revert` 并保留删除前资源定义/路径清单；旧 dev 数据明确允许丢弃，不承诺数据恢复；如发现共享/生产资源，在执行外部资源动作前停止。 |
| 完成判据 | Monitoring、metadata-query、code-review 各按完整切片退出；Echo production 装配退出且 test fixture 可复现；每项有引用扫描、迁移/替代、验证和回滚证据；禁止触碰项无变化。 |
| 生产路由/外部契约 | production_routing_changed: no（当前 dev 前提）；external_contract_changed: no production contract。若发现实际生产/共享环境，立即停止并更新决策。 |

## P6：超大类、Provider 状态 schema 与旧 API 渐进治理

| 要素 | 计划 |
|---|---|
| 输入和前置条件 | P1 可靠测试；P2/P3 稳定授权入口；1.3.1 已完成的 facade/SPI/schema v1 证据；ODR-142-007 直接删除授权；旧 API 仓内消费者清单和替代差距。 |
| 涉及模块 | `ClaudeWorkerView.vue`、`OpenApiController`、Claude/Codex TaskService、`TaskDispatchFacade`、ProviderStateCodec/实体/repository、旧 Provider Controller/SPI/DTO、PC/Mobile/SDK/CLI/Worker。 |
| 实施内容 | 先特征测试和职责图，再按窄接口提取；页面优先拆 API adapter/composable/panel，不一次重写；状态 envelope v1 增加严格版本验证、typed Provider adapter、迁移链和解析失败可观测性，替换 JSON LIKE 查询需单独数据计划；补齐或确认统一 API 的 file hints/generated image/approval/task detail 等当前仓内必需能力，迁移 PC、L3、bootstrap、SDK/CLI、canary/soak 和 Worker 引用，随后在 1.4.2 独立删除旧路由、deprecated SPI bridge 和无剩余用途 DTO。 |
| 非目标 | 不重做已完成的 schema v1/registry/projection/router；不以行数作为唯一拆分目标；不误删仍服务 typed/unified 契约的 DTO；不动态加载 Addon；不为不存在的仓外客户保留兼容层。 |
| 自动化测试 | characterization/unit/contract；状态 v0/v1/未知版本/损坏 JSON/迁移幂等；Provider resume/reconnect；PC type/Vitest/Playwright；统一入口当前语义；SDK/CLI/canary；旧路由和 legacy SPI 无当前源码引用。 |
| 手工验证 | Claude/Codex/Gemini/LangGraph 创建、流式、恢复、审批、生成图片/file hints；页面主流程和深链；升级前数据副本迁移演练；性能对比。 |
| 风险 | 超大类隐式状态耦合；Provider 恢复兼容破坏；JSON 查询迁移影响数据；仓内 canary、UI 或测试仍依赖旧响应语义；并行改动冲突。 |
| 回滚方式 | 提取与行为变更分提交；保留旧 envelope reader 和双读/回退窗口；统一 API 先增后迁；旧 route/SPI/DTO 分批删除并可独立 revert。旧 dev 数据和历史图片链接无需恢复。 |
| 完成判据 | 每次拆分有可读职责和等价测试；新写入遵守版本化 typed contract；未知/损坏状态行为明确；旧 API 每个路由有仓内消费者迁移/删除、验证和回滚记录；deprecated SPI/DTO 无当前调用且 clean build 通过。 |
| 生产路由/外部契约 | production_routing_changed: no（当前 dev 前提）；external_contract_changed: no production contract。未来外部开放不提供旧契约。 |

## P7：覆盖审计、体验验证和正式签收

| 要素 | 计划 |
|---|---|
| 输入和前置条件 | P0-P6 进度、测试日志、手工体验、运行态审计、迁移/回滚演练、所有 Owner 决策；未完成项必须明确移出版本或阻塞。 |
| 涉及模块 | 全部本版本触点与文档门禁。 |
| 实施内容 | 更新 [Progress](./progress.md)；先做 implementation self-check；跨模块改动执行 `foggy-implementation-quality-gate` 正式质量闸门；随后执行 `foggy-test-coverage-audit` 映射 requirement/workitem/acceptance 到证据；最后执行 `foggy-acceptance-signoff` 输出 accepted/rejected/blocked；单独记录 production enablement 决定。 |
| 非目标 | 不以文档完整代替实现；不以测试数量代替风险覆盖；不把 isolated smoke 视为 production approval；不在验收时顺手修复未评审的新问题。 |
| 自动化测试 | Java clean、全部纳入前端/Worker lane、授权负向矩阵、兼容/迁移、Markdown 链接和 `git diff --check`；具体命令以 P1 固化矩阵为准。 |
| 手工验证 | 内部 UI/可信内网；外部 ClientApp/upstream user 全链路；审批/恢复/取消；readiness；PC/widget/mobile 关键体验；退役/回滚演练。 |
| 风险 | 证据来自不同提交；测试环境与生产配置差异；未决 Owner 项被隐藏；隔离通过被误当生产批准。 |
| 回滚方式 | 验收不直接改变生产；失败项回到对应阶段，保留 rejected/blocked 证据；生产启用有独立审批、灰度和回滚 runbook。 |
| 完成判据 | 13 项版本门禁都有可定位证据；质量闸门通过；覆盖审计允许进入验收；正式签收明确 accepted/rejected/blocked；production enablement 单独决策。 |
| 生产路由/外部契约 | production_routing_changed: no（验收动作）；production_enablement 不得由验收结果自动推导。 |

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
| ODR-142-001 | Node `22.23.1`、pnpm `10.34.5`、单一根 lockfile；核心构建 required，完整 E2E/cross-platform nightly，真实凭据 smoke 进入 RC | P1 | in-progress；本地 clean/frozen/frontend 通过，hosted CI 与 nightly 待执行 |
| ODR-142-002 | internal-dev 保留 ClientApp 代办；signed assertion 降为未来外部开放门禁 | P2 / future external | approved-with-constraints；external 必须显式且默认关闭 |
| ODR-142-003 | 服务端权威 opaque task token，30 分钟 TTL，完整授权交集，并与 Worker principal/lease 双重校验，暂停/终态失效 | P2 | approved |
| ODR-142-004 | 双运行模式；external-enabled 目录/工具默认拒绝、`workspace-write`、任务工具 egress 默认拒绝、缺凭据 unready/fail closed | P2 | approved-with-constraints；门禁未齐不得打开 |
| ODR-142-005 | 本地关键状态事务 outbox；无状态拒绝可靠落档；远程调用意图/结果分段记录；高频遥测 best-effort | P2/P7 | approved |
| ODR-142-006 | dev-only 安全后物理移除 Monitoring、metadata-query、code-review；Echo fixture 迁移后退出生产装配，旧数据可丢弃 | P5 | in-progress；Monitoring/code-review 已移除，metadata-query/Echo 未开始；发现共享/生产资源即停止 |
| ODR-142-007 | 仓内消费者迁移后在 1.4.2 直接删除旧 Provider API/SPI/DTO | P6 | approved-with-constraints；无外部窗口，clean build 仍是硬门 |
| ODR-142-008 | 当前指引修正、历史证据标记、活跃 Skill 修正、确认失效 Skill 退出活跃发现 | P0/P4 | approved |

既有 `DEC-003` Provider state envelope 演进、credential authority、mapping/grant 权威数据源和超大类拆分顺序不因上述评审稿自动关闭，仍按各自 workitem 保持 `pending-decision`。
