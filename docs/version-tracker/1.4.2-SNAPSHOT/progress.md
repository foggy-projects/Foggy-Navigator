# 1.4.2 平台治理与历史能力收口进度

## 文档作用

- doc_type: progress
- intended_for: root-controller | execution-agent | reviewer | signoff-owner
- purpose: 按 P0-P7 记录实现、测试、体验、证据、风险和后置评审状态。

## 基本信息

- version: `1.4.2-SNAPSHOT`
- status: planned
- requirement: [REQ-001](./requirements/REQ-001-platform-governance-and-legacy-cleanup.md)
- implementation_plan: [Implementation Plan](./implementation-plan.md)
- owner_decision_review: [Owner Decision Review](./owner-decision-review.md)
- implementation_started_at: not-started
- last_updated_at: `2026-07-13`
- production_routing_changed: no
- production_enablement: not-applicable
- formal_quality_gate: not-started
- coverage_audit: not-started
- acceptance_status: not-started

## 记录规则

1. 只有实际执行并获得可定位结果的命令、日志、截图、报告或运行态记录才能登记为 evidence。
2. 用户提供的现状和静态线索标记为 `planning-input` 或 `pending-verification`，不冒充本版本测试结果。
3. 未运行的测试必须写 `not-run` 和原因；不得用“已编写测试”替代“测试通过”。
4. 静态无引用不等于无运行消费者；第二档退役项必须补运行日志、部署、数据库和外部调用审计。
5. 隔离环境签收与生产批准分别记录；前者不得自动把 `production_enablement` 改为 approved。

## 规划包落档验证

以下只验证 1.4.2 规划文档包，不表示 P0-P7 实现已经开始，也不构成业务测试或生产批准。

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
| 产品定位和版本目标进入版本文档 | planned | 由 README 与 REQ-001 提供规划基线，尚未进入实现 |
| 内外部信任边界冻结 | not-started | P0 输出，需对应 Owner review |
| 模块职责和代码清单复核 | not-started | 需核对源码、依赖方向和实际 owner |
| 构建工具链决策 | pending-decision | ODR-142-001 已提出 Node `22.23.1`、pnpm `10.34.5`、单一根 lockfile 和 CI 分层建议，尚未批准 |
| 外部身份/token/Worker/审计决策 | pending-decision | ODR-142-002 至 ODR-142-005 已形成建议；P2 在批准前保持阻塞 |
| 第二档退役能力运行态 Owner 明确 | pending-runtime-audit | ODR-142-006 仅提出目标方向；Monitoring、metadata-query、code-review、Echo 均无生产退役授权 |
| 旧 API 与文档/Skill 治理策略 | pending-decision | ODR-142-007/008 已形成建议；消费者和引用证据仍未收集 |

## Development Progress

| Stage | 范围 | 状态 | 结果/证据 |
|---|---|---|---|
| P0 | 目标、边界、术语、ownership 和代码清单冻结 | not-started | not-collected |
| P1 | Node、lockfile、全仓 clean build 和 CI 基线 | not-started | not-collected |
| P2 | 外部 Biz Worker/upstream user 边界治理 | not-started | not-collected |
| P3 | Session/Task 定向 ownership 治理 | not-started | not-collected |
| P4 | 低风险孤儿代码和失效文档清理 | not-started | not-collected |
| P5 | Monitoring、metadata-query、code-review、echo 去留与获批项独立退役 | not-started | not-collected |
| P6 | 超大类、Provider state schema 和旧 API 渐进治理 | not-started | not-collected |
| P7 | 质量检查、覆盖审计、体验验证和正式签收 | not-started | not-collected |

## Workitem Progress

| Workitem | 状态 | Development | Testing | Experience | Evidence |
|---|---|---|---|---|---|
| [GOV-001](./workitems/GOV-001-internal-external-trust-boundary.md) | planned | not-started | not-run | not-run：需验证内部 UI、外部配置与错误反馈 | not-collected |
| [GOV-002](./workitems/GOV-002-biz-worker-and-upstream-user-boundary.md) | planned | not-started | not-run | not-run：需验证 ClientApp、审批恢复和 Worker readiness 体验 | not-collected |
| [GOV-003](./workitems/GOV-003-session-task-resource-ownership.md) | planned | not-started | not-run | not-run：需验证内部 UI 工作流 | not-collected |
| [OPT-001](./workitems/OPT-001-build-and-ci-baseline.md) | planned | not-started | not-run | not-applicable：构建基础设施；若类型修复影响可见行为则改为 not-run | not-collected |
| [OPT-002](./workitems/OPT-002-core-code-maintainability.md) | planned | not-started | not-run | not-run：涉及工作台渐进拆分时必须验证 | not-collected |
| [CLEAN-001](./workitems/CLEAN-001-low-risk-orphan-cleanup.md) | planned | not-started | not-run | not-run：UI/mobile 候选需按实际切片验证 | not-collected |
| [CLEAN-002](./workitems/CLEAN-002-monitoring-retirement.md) | planned | not-started | not-run | not-run：如退役 MonitoringView 必须验证导航与替代体验 | not-collected |
| [CLEAN-003](./workitems/CLEAN-003-metadata-query-retirement-audit.md) | planned | not-started | not-run | not-applicable：仅审计阶段；若执行退役则改为 not-run | not-collected |
| [CLEAN-004](./workitems/CLEAN-004-experimental-and-legacy-addon-governance.md) | planned | not-started | not-run | not-run：PC/Mobile/SDK/CLI 兼容面需要分别验证 | not-collected |
| [DOC-001](./workitems/DOC-001-documentation-alignment.md) | planned | not-started | not-run | not-applicable：文档对齐不直接改变交互 | not-collected |

## Evidence Register

| Evidence ID | 分类 | 结论或待证事实 | 来源/计划验证 | 状态 | 限制 |
|---|---|---|---|---|---|
| INPUT-001 | 已确认事实 | Navigator 是内部多 Worker 远程编程工作台，不是语义层主线 | REQ-001 与用户确认输入 | planning-input | 不代表文档已全部完成对齐 |
| INPUT-002 | 已确认事实 | `UnifiedSseEmitter` 使用 JVM 内本地 map 和调度器 | 当前源码只读审计 | confirmed-static | 不推断生产部署实例数或运行流量 |
| INPUT-003 | 已确认事实 | Claude、Codex、Gemini、LangGraph Addon 直接依赖 session-module，为编译期模块化单体 | Maven POM 只读审计 | confirmed-static | 不代表未来不能演进，只说明当前事实 |
| INPUT-004 | 本地诊断证据 | `ClaudeWorkerView.vue` 为 10,369 行；显式 app type-check 发现 2 个 TypeScript 错误 | `vue-tsc -p tsconfig.app.json --noEmit` 只读基线诊断 | failed-baseline | 不是 P1 修复后的通过证据 |
| INPUT-005 | 构建输入 | launcher 主干依赖链 clean test 当前可通过 | P1 clean environment 重跑 | pending-verification | 本轮未执行 Maven |
| INPUT-006 | 已确认事实 | 当前 Node 18.19.1 低于已安装 Vite 7.3.1 的引擎要求；根 lockfile 存在但被全局忽略 | 工具版本、package engine、`.gitignore` 与 Git 跟踪只读审计 | confirmed-static | 精确 Node/pnpm 版本尚未由 Owner 冻结 |
| INPUT-007 | 已确认事实 | 根 `build:frontend` 只直接构建 chat 与主前端，聚合脚本仍未覆盖 widget/mobile | package scripts 只读审计 | confirmed-static | 本轮未执行全包构建 |
| INPUT-008 | 运行态缺口 | Monitoring 等第二档能力的真实流量和外部依赖未知 | 日志、部署、DB、第三方调用审计 | not-collected | 未完成前禁止退役 |
| INPUT-009 | 运行态缺口 | 旧 Provider API 的 PC、Mobile、SDK、CLI 和外部客户调用未知 | 调用方矩阵与访问日志 | not-collected | 静态搜索不能关闭该缺口 |
| DEC-001 | 决策项 | 明确受支持的 Node/pnpm/Corepack 版本 | ODR-142-001 + P1 build owner 决策 | pending-decision | 当前提案为 Node `22.23.1`、pnpm `10.34.5`，未批准前不宣称可复现 |
| DEC-002 | 决策项 | 外部 credential 与 task token 的签发、轮换和撤销权威 | ODR-142-002 至 ODR-142-005 + P2 Owner 决策 | pending-decision | 评审稿细化了身份/token/Worker/审计，但 credential authority 仍需实施级确认 |
| DEC-003 | 决策项 | envelope v1 的 typed schema 演进、未知版本策略与兼容窗口 | P6 provider owners 决策 | pending-decision | 不允许无迁移链切换 |
| ODR-142-001 | 决策提案 | Node/pnpm、lockfile 与 CI 分层 | [Owner 决策评审稿](./owner-decision-review.md) | pending-decision | 建议不等于批准；P1 未开始 |
| ODR-142-002 | 决策提案 | upstream user 采用 signed assertion 目标模式和受限代办兼容 | [Owner 决策评审稿](./owner-decision-review.md) | pending-decision | P2 identity enforcement blocked |
| ODR-142-003 | 决策提案 | task token scope、TTL、失效、撤销、轮换和 Worker lease | [Owner 决策评审稿](./owner-decision-review.md) | pending-decision | 未发布新 token schema |
| ODR-142-004 | 决策提案 | external-enabled 目录、工具、sandbox、approval、network 和 readiness 上限 | [Owner 决策评审稿](./owner-decision-review.md) | pending-decision | external enablement 保持 disabled |
| ODR-142-005 | 决策提案 | 本地关键状态事务 outbox、拒绝可靠事件、远程调用分段审计、遥测 best-effort | [Owner 决策评审稿](./owner-decision-review.md) | pending-decision | 当前不宣称关键审计或外部副作用原子性完备 |
| ODR-142-006 | 决策提案 | Monitoring、metadata-query、code-review、Echo 目标态 | [Owner 决策评审稿](./owner-decision-review.md) | pending-decision | 四个子决策均 pending；无生产退役授权 |
| ODR-142-006-MON | 决策提案 | Monitoring 完整切片 retire 目标 | [Owner 决策评审稿](./owner-decision-review.md) | pending-decision | pending-runtime-audit；production retirement authorization: no |
| ODR-142-006-MQ | 决策提案 | metadata-query 本版 defer 删除、以 migrate/retire 为方向 | [Owner 决策评审稿](./owner-decision-review.md) | pending-decision | pending-runtime-audit；production retirement authorization: no |
| ODR-142-006-CR | 决策提案 | code-review archive/freeze | [Owner 决策评审稿](./owner-decision-review.md) | pending-decision | pending-runtime-audit；production retirement authorization: no |
| ODR-142-006-ECHO | 决策提案 | Echo dev/test retain、production retire | [Owner 决策评审稿](./owner-decision-review.md) | pending-decision | pending-runtime-audit；production retirement authorization: no |
| ODR-142-007 | 决策提案 | 旧 Provider API/SPI/DTO 兼容窗口和消费者登记 | [Owner 决策评审稿](./owner-decision-review.md) | pending-decision | 1.4.2 不删除；流量未收集 |
| ODR-142-008 | 决策提案 | 当前/历史文档和 Skill 分级治理 | [Owner 决策评审稿](./owner-decision-review.md) | pending-decision | 删除仍需引用扫描和 Owner 确认 |

## Testing Progress

| Test lane | 状态 | Evidence / 原因 |
|---|---|---|
| Java clean compile/test | not-run | 规划阶段未执行；P1 必须从 clean 环境形成证据 |
| Launcher assembly/package | not-run | 规划阶段未执行 |
| Navigator PC type-check/test/build | not-run | 规划阶段未执行；必须覆盖现有 TypeScript 错误 |
| chat-core/chat/widget build | not-run | 规划阶段未执行；P1 纳入根矩阵 |
| Mobile type-check/test/build | not-run | 规划阶段未执行；P1 明确交付包范围 |
| Claude/Codex/Gemini/LangGraph Worker tests/build | not-run | 规划阶段未执行；按变更范围建立矩阵 |
| Ownership negative-path tests | not-run | P2/P3 尚未实施 |
| task-scoped token 越权测试 | not-run | P2 尚未实施 |
| Non-loopback missing credential readiness | not-run | P2 尚未实施 |
| 删除项引用与 clean build 回归 | not-run | P4/P5 尚未执行删除 |
| GitHub Actions full-repository matrix | not-run | P1 尚未实现 |
| 规划文档 `git diff --check` / 新文件 whitespace check | passed | 见“规划包落档验证”；业务实现 diff 尚不存在 |

## Experience Progress

| 体验维度 | 检查项 | 状态 | Evidence |
|---|---|---|---|
| 页面可达性 | 内部工作台、Session/Task、Profile、Monitoring 相关导航按范围可达 | not-run | not-collected |
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
| 7. Java clean 构建测试通过 | not-started | not-collected |
| 8. 纳入范围的前端类型检查、测试和构建通过 | not-started | not-collected |
| 9. Node、包管理器和 lockfile 可复现 | not-started | not-collected |
| 10. 所有删除项有扫描、迁移/替代和回滚证据 | not-started | not-collected |
| 11. 获批退役项按完整功能切片退出；retain/migrate/defer 有 Owner 记录 | not-started | not-collected |
| 12. 当前文档不再把 tutor、旧 chat-first 或语义层写成主线 | not-started | not-collected |
| 13. 隔离验收不等同于生产批准 | not-started | not-collected |

## Implementation Self-Check

- [ ] requirement 与当前阶段 scope 已收口。
- [ ] 非目标没有被意外扩张。
- [ ] 代码路径、配置、迁移和文档触点已回写。
- [ ] 静态结论与运行态证据已明确区分。
- [ ] 测试均记录 pass、fail、not-run 或 not-applicable 及原因。
- [ ] UI 改动已完成体验清单和 Playwright。
- [ ] 删除项已有替代、回滚和完整功能切片证据。
- [ ] 计划外变更、风险和阻塞已记录。
- [ ] 已判断是否需要正式 `foggy-implementation-quality-gate`。
- [ ] 进度与最终报告已回写。

- self_check_summary: not-started
- self_check_decision: not-started
- formal_quality_gate_required: yes-cross-module-shared-contract-and-cleanup
- formal_quality_gate_status: not-started

## 计划外变更

- 当前：none；实现尚未开始。

## 阻塞项与待确认项

| Item | 状态 | Owner/Decision |
|---|---|---|
| ODR-142-001：精确 Node/pnpm/Corepack 版本和根 lockfile/CI 策略 | pending-decision | root build owner / frontend owner |
| credential authority、轮换与撤销传播 | pending-decision | Biz Worker / gateway / platform owner |
| upstream user mapping/grant 权威数据源 | pending-decision | ClientApp / upstream integration owner |
| ODR-142-002：upstream user 身份证明模式 | pending-decision | ClientApp / upstream / Security owner |
| ODR-142-003：task-scoped token 有效期、函数授权和 Worker lease | pending-decision | BusinessTask / BusinessFunction / Worker Gateway owner |
| ODR-142-004：external-enabled Worker 安全上限 | pending-decision | Worker / Platform / Security owner |
| ODR-142-005：关键审计保证级别 | pending-decision | Security / Operations / Business Agent owner |
| Monitoring 真实流量和部署 Owner | pending-runtime-audit | monitoring / deployment owner |
| metadata-query 外部消费者、DB 与第三方调用 | pending-runtime-audit | metadata-query / launcher owner |
| code-review-agent GitLab webhook 消费者 | pending-runtime-audit | integration owner |
| ODR-142-006：四类能力目标态和各自生产退役授权 | pending-decision + pending-runtime-audit | Product / Platform / Operations / slice owners |
| ODR-142-007：旧 Provider API 调用方与兼容窗口 | pending-decision + pending-runtime-audit | PC / Mobile / SDK / CLI / external owners |
| ODR-142-008：文档和 Skill 分类、修正、归档与删除规则 | pending-decision | Product / documentation / skill owners |
| Provider state envelope v1 的 typed schema 演进、未知版本策略和迁移窗口 | pending-decision | provider owners |

## 后续衔接

P0 开工前必须：

1. 阅读 [REQ-001](./requirements/REQ-001-platform-governance-and-legacy-cleanup.md)、[模块职责](./module-responsibility.md)、[代码清单](./code-inventory.md)、[Owner 决策评审稿](./owner-decision-review.md) 与 [实施计划](./implementation-plan.md)。
2. 复核用户提供的静态线索，不把规划输入直接升级为运行证据。
3. 为 10 个工作项确认实际 owner、前置条件和阶段归属。
4. 在任何代码或删除操作前记录当前 clean build、引用和运行态证据边界。
5. 每完成一个阶段先执行 implementation self-check；跨模块、共享契约或删除阶段必须再执行正式质量检查。
6. P7 按 `foggy-implementation-quality-gate`、`foggy-test-coverage-audit`、`foggy-acceptance-signoff` 顺序收口；任何隔离签收仍需单独的生产批准。
