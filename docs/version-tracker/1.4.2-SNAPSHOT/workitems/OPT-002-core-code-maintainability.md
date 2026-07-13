---
type: optimization
version: 1.4.2-SNAPSHOT
ticket: OPT-002
priority: medium
status: planned
source: REQ-001
owner: platform-and-provider-owners
---

# 核心代码可维护性与状态契约渐进治理

## 文档作用

- doc_type: workitem
- intended_for: execution-agent | reviewer | module-owner | signoff-owner
- purpose: 在行为测试保护下渐进收敛超大类、模块边界与 Provider 状态契约，不进行一次性重写。

## 基本信息

- version_index: [1.4.2-SNAPSHOT](../README.md)
- requirement: [REQ-001](../requirements/REQ-001-platform-governance-and-legacy-cleanup.md)
- implementation_plan: [Implementation Plan](../implementation-plan.md)
- progress: [Progress](../progress.md)
- primary_stage: P6
- development: not-started
- testing: not-run
- experience: not-run
- production_routing_changed: no
- external_contract_changed: no

## 目标

1. 为职责较重的 Java/Vue 类建立可验证的拆分顺序、目标边界和停止条件。
2. 以 1.3.1 已完成的 facade/registry/router/schema v1 为起点，只治理仍存在的职责和契约缺口。
3. 使 `providerStateJson`、`taskStateJson` 的版本、Provider 类型、迁移和错误行为可验证、可观测。
4. 让旧 Provider API 的迁移按“统一 API 能力补齐 -> 消费者迁移 -> 静默窗口 -> 独立删除”推进。

## 范围与准确触点

| 触点 | 静态规模/现状 | 渐进目标 |
|---|---|---|
| `packages/navigator-frontend/src/views/ClaudeWorkerView.vue` | 约 10,369 行 | 先提 API adapter、状态 composable、独立面板和纯展示组件；保持路由与用户流程 |
| `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiController.java` | 约 3,171 行 | 按 credential/principal、query、command、diagnostics/evidence facade 减薄 Controller |
| `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java` | 约 3,244 行 | 按生命周期、流式、恢复/审批和持久化边界提取 |
| `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java` | 约 3,001 行 | 按 task/thread、stream、resume/reconnect、artifact/file hint 职责提取 |
| `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java` | 约 1,389 行；已有 registry/projection/operation router | 只收敛剩余 target resolution、query/command 协调和 ownership 接入，不重做已提取结构 |
| `navigator-common/src/main/java/com/foggy/navigator/common/util/ProviderStateCodec.java` | 已有 `schemaVersion=1`、`providerType` envelope；仍使用通用 Map | 加严格版本策略、typed adapter、迁移链、解析失败可观测性 |
| `navigator-common/src/main/java/com/foggy/navigator/common/entity/SessionEntity.java`、`navigator-common/src/main/java/com/foggy/navigator/common/entity/SessionTaskEntity.java` | 持有 `providerStateJson` / `taskStateJson` | 新写入统一走 codec/adapter，旧数据保持兼容读取 |
| `navigator-common/src/main/java/com/foggy/navigator/common/repository/SessionEntityRepository.java` | 存在 JSON `LIKE` 查询 | 先做数据/性能审计，再设计显式字段或索引迁移 |
| `/api/v1/claude-tasks`、`/api/v1/codex-tasks`、`/api/v1/langgraph-tasks` | 仍有 PC/Worker/L3/外部兼容消费者 | 逐路由建立 parity 和弃用门禁，不能按 Controller 整体删除 |

## 证据边界

| ID | 分类 | 结论 | 状态/限制 |
|---|---|---|---|
| E-OPT2-01 | 已确认事实 | Provider state envelope v1 已实现并在多 Provider 使用 | confirmed；不能再写“完全没有 schema” |
| E-OPT2-02 | 已确认事实 | 1.3.1 已完成多个 facade/registry/projection/router 阶段 | confirmed；本工作项只处理剩余增量 |
| E-OPT2-03 | 静态搜索结论 | codec 对未知/损坏 JSON 的处理、通用 Map 和 JSON LIKE 查询仍有治理空间 | static-only；需测试和数据样本确认 |
| E-OPT2-04 | 静态搜索结论 | PC 使用 Codex file-hints、LangGraph approve；Codex app-server/canary 与 stream URL 使用旧路径 | static-only；不代表全部消费者 |
| E-OPT2-05 | 需要运行态确认 | 每个旧路由的访问量、外部客户、数据版本分布、恢复失败率 | not-collected；删除/迁移前阻塞 |
| D-OPT2-01 | 决策项 | 每个大类的第一条拆分 seam 和成功指标 | pending-decision |
| D-OPT2-02 | 决策项 | 状态未知版本是拒绝、只读降级还是 Provider 专用恢复 | pending-decision |
| D-OPT2-03 | 决策项 | 旧 API 兼容窗口与响应弃用信号 | pending-decision |

## 明确非目标

- 不以行数达标为目的，不一次性重写任何大类。
- 不重做 schema v1、TaskQueryProviderRegistry、TaskCreateTargetResolver、projection service 或 TaskOperationRouter。
- 不在没有迁移/回滚方案时改变状态数据 schema 或批量重写历史 JSON。
- 不在统一 API 缺少 file hints、artifact/generated-image、approval 等能力时删除旧 API。
- 不实现动态插件加载，不实现多实例 SSE 事件总线。
- 不借拆分改变外部授权模型；安全边界由 GOV-001/GOV-002/GOV-003 负责。

## 实施步骤

### Step 0：职责和特征测试冻结

- 对每个目标类绘制输入、状态、副作用、调用方和失败语义。
- 记录现有公开方法/路由、Provider 状态样本和核心 UI 流程。
- 补 characterization tests，确认当前正确行为和已知缺陷，不将缺陷固化成目标契约。
- 每次只批准一个 seam；拆分与行为变更分开提交。

### Step 1：Provider 状态契约增量

- 为 envelope v1 建立明确的版本校验和 Provider 类型校验。
- 引入 Provider 专用 typed adapter/record，同时保留旧 Map 的受控兼容读取。
- 对损坏 JSON、未知版本、Provider 不匹配记录可观测错误；禁止静默写回空状态覆盖原值。
- 为未来 v2 建迁移注册和幂等测试，不因有框架就立即升级所有数据。
- 对 JSON `LIKE` 查询先采样、性能和数据迁移设计，再决定显式列/索引。

### Step 2：Java 大类渐进提取

- `OpenApiController` 先提取 principal/ownership policy 与 query/command facade；Controller 只做协议映射。
- Claude/Codex TaskService 先提取无状态规则或单一副作用协调器，再处理共享状态。
- `TaskDispatchFacade` 复用已有 router/registry，仅把剩余职责移到窄端口；不制造 facade 链。
- 每次提取保持调用图单向，禁止 launcher 或 lower-level module 反向依赖 Addon。

### Step 3：Vue 页面渐进拆分

- 先修复 [OPT-001](./OPT-001-build-and-ci-baseline.md) 的有效类型门禁和现存错误。
- 按 API、状态、事件、面板拆分；每次保持页面路由、深链、快捷操作和 SSE 行为。
- 为可测试逻辑提 composable/utility，为 UI 面板补 Vitest/Playwright；不做全页面重写。

### Step 4：旧 API 能力补齐和迁移

- 为每个旧路由建立“定义位置—消费者—统一 API 缺口—替代版本—流量指标”清单。
- 先在统一 API/SDK 补 file hints、generated image/artifact、LangGraph 受控审批等缺口。
- 迁移 PC、Mobile、SDK、CLI、L3、canary/soak 和外部客户；发出版本化弃用信号。
- 满足静默窗口和 [CLEAN-004](./CLEAN-004-experimental-and-legacy-addon-governance.md) 门禁后，逐路由独立退役。

## 验证计划

### 自动化测试

- Provider state：旧裸状态/envelope v1/未知版本/损坏 JSON/Provider 不匹配/迁移幂等/恢复兼容。
- Java：被提取职责的 unit/contract/characterization，四 Provider create/stream/resume/reconnect/cancel。
- API：统一与旧路由 parity、弃用 header/error、授权负向用例。
- PC：有效 `vue-tsc -p tsconfig.app.json --noEmit`、Vitest、build、相关 Playwright。
- SDK/Worker：file hints、artifact/generated image、approval、canary/soak 迁移测试。

### 手工与体验验证

| 场景 | 检查项 | 状态 |
|---|---|---|
| Claude/Codex 主页面 | 创建、流式、停止、恢复、文件提示、artifact、刷新/重连 | not-run |
| LangGraph | 暂停、受控审批、恢复、拒绝、旧入口提示 | not-run |
| Gemini | 状态保存与恢复兼容 | not-run |
| 深链/会话 | `/c/:id`、历史消息、任务列表和 SSE | not-run |
| 数据迁移演练 | 生产副本上的版本分布、迁移、回退和性能 | not-run |

## 风险与回滚

| 风险 | 缓解 | 回滚 |
|---|---|---|
| 提取破坏隐式状态时序 | 先特征测试；一次一个 seam；保持同步点 | revert 单次提取提交，保留新增测试 |
| 新 codec 无法读取历史状态 | 双读、兼容 adapter、数据副本演练 | 切回旧 reader；禁止覆盖原始 JSON |
| UI 拆分造成响应式状态丢失 | 小步组件化，Playwright 覆盖交互/刷新 | revert 当前面板/composable 提取 |
| 统一 API 缺能力 | parity 清单先行，先增后迁 | 调用方按路由开关回到旧接口 |
| 外部客户未迁移 | access log、弃用指标、客户清单和静默窗口 | 保持旧路由只读/兼容，不物理删除 |

## 完成判据

- [ ] 每个已拆分目标都有职责图、行为测试、前后调用图和独立回滚提交。
- [ ] 不再新增绕过 codec/typed adapter 的 Provider 状态写入。
- [ ] 未知版本、损坏 JSON 和 Provider 不匹配的行为有测试和可观测证据。
- [ ] 历史数据迁移有副本演练、幂等和回退证据；未演练则不执行生产迁移。
- [ ] 每个旧 API 路由都有精确消费者、统一替代、迁移版本、静默窗口和回滚方式。
- [ ] `ClaudeWorkerView.vue` 的拆分通过有效类型检查、单测、构建和关键体验验证。
- [ ] 未重复建设 1.3.1 已完成能力，未一次性重写大类。
- [ ] production routing/external contract 的实际变化已单独批准并回写；默认保持 no。
