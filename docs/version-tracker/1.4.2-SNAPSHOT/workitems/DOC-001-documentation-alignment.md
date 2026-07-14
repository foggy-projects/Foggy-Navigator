---
type: documentation
version: 1.4.2-SNAPSHOT
ticket: DOC-001
priority: high
status: in-progress
source: REQ-001
owner: root-documentation
---

# 当前产品定位、架构文档与项目 Skills 对齐

## 文档作用

- doc_type: workitem
- intended_for: root-controller | documentation-owner | module-owner | skill-owner | reviewer | signoff-owner
- purpose: 统一当前有效指引中的 Foggy Navigator 产品定位与架构边界，同时保留历史证据并对失效描述作可追溯勘误。

## 基本信息

- version: `1.4.2-SNAPSHOT`
- ticket: `DOC-001`
- status: in-progress
- requirement: [REQ-001 平台治理与历史能力收口](../requirements/REQ-001-platform-governance-and-legacy-cleanup.md)
- implementation_plan: [1.4.2 实施计划](../implementation-plan.md)
- owner_decision_review: [ODR-142-008 Owner 决策评审稿](../owner-decision-review.md)
- module_responsibility: [模块职责](../module-responsibility.md)
- code_inventory: [代码清单](../code-inventory.md)
- progress_record: [1.4.2 进度](../progress.md)
- production_routing_changed: no
- external_contract_changed: no
- implementation_started: yes
- testing: in-progress
- acceptance_status: not-started

## 背景

Foggy Navigator 当前是内部多 Worker 远程编程工作台，主线是统一 Session、Task、A2A 和 Provider 治理，以及文件、Git、工作目录、终端、跨项目协作、Business Agent、Open SDK、ClientApp 和 upstream user 集成。当前不是语义层或数据分析平台。

历史迭代中曾出现 tutor、OpenHands addon、旧 chat-first 产品叙述和语义层主线描述；部分能力已删除、停用或不再代表当前方向。与此同时，版本记录、测试报告和验收材料仍需要保留当时的真实背景，不能为了统一当前叙述而改写历史证据。

本工作项因此采用三类处理策略：

1. 当前有效指引直接修正并统一链接到权威入口；
2. 历史证据保持原文，必要时增加有日期、可追溯的勘误或“已被取代”提示；
3. 对仍被当作当前主线的 tutor、OpenHands、旧 chat-first 和语义层描述，完成引用扫描后移除、降级为历史背景或明确标记失效。

## 目标

1. 让根 [README](../../../../README.md)、[CLAUDE.md](../../../../CLAUDE.md)、[系统总览](../../../00-system-overview.md) 和 [A2A 架构](../../../a2a-agent-architecture.md) 对当前产品定位给出一致表述。
2. 让当前模块文档准确描述真实模块职责、依赖方向和运行边界，不把编译期 addon 描述成动态插件，不把单 JVM SSE 描述成多实例事件总线。
3. 让项目级 [`.agents/skills`](../../../../.agents/skills/) 的触发条件、适用模块、当前能力和非目标与实际仓库一致。
4. 区分当前指引、历史证据、过渡设计和失效描述，避免新执行 Agent 从旧文档推导错误产品方向。
5. 建立文档链接、术语、路径和历史勘误的持续检查方式，并将证据回写到版本进度。

## 非目标

- 不在本工作项修改业务代码、生产配置、数据库、路由或外部 API。
- 不重写全部历史版本文档、验收报告、测试报告或 evidence 文件。
- 不因出现 `metadata`、`query`、`dataset` 等名称就删除仍有当前职责的模块或 Skill。
- 不在未完成引用和消费者审计前删除项目 Skill、模块文档或外部集成指南。
- 不把文案对齐本身当作 Monitoring、metadata-query、旧 Provider API 等能力的退役批准；各能力只按其 Owner 决策和独立 cleanup workitem 执行。
- 不用本次文档审阅替代实现质量检查、测试覆盖审计或正式验收。

## 对齐基线

### 当前产品主线

所有面向当前开发者、执行 Agent、部署人员和上游集成方的入口文档必须围绕以下主线组织：

- 多 Worker 远程编程工作台；
- Session、Task、A2A 与 Provider 统一治理；
- Claude、Codex、Gemini、LangGraph 等 Provider 接入；
- 文件、Git、工作目录、终端和跨项目协作；
- Business Agent、Open SDK、ClientApp 与 upstream user 集成；
- 内部控制面保持轻量认证，外部运行面重点治理身份、tenant、ClientApp、upstream user、task-scoped token、资源作用域和审计。

### 当前架构边界

- `UnifiedSseEmitter` 当前是单 JVM 内存态；多实例事件总线不属于 1.4.2。
- Addon 当前仍直接依赖 `session-module`，属于编译期模块化单体，不是真正动态插件。
- `providerStateJson`、`taskStateJson` 已有 `ProviderStateCodec` envelope v1；当前增量是严格版本校验、Provider typed schema、迁移和失败可观测性，不得描述成“完全无 schema”或“全部治理完成”。
- `TaskDispatchFacade`、`OpenApiController`、`ClaudeTaskService`、`CodexTaskService` 和 `ClaudeWorkerView.vue` 等重职责代码采用小步治理，不宣称一次性重写。
- 内部控制面与外部运行面的信任假设不同；文档不得以“内部可用”推导“可安全对外开放”。

## 文档处理分类

### A. 当前指引：直接修正

下列文档会影响当前开发、运行、集成或 Agent 行为，应在发现冲突时直接更新，并保留必要的迁移说明：

| 路径 | 当前角色 | 必须对齐的内容 | 处理方式 |
|---|---|---|---|
| [`README.md`](../../../../README.md) | 项目入口、能力和快速开始 | 当前产品主线、支持的 Provider/Worker、构建环境和权威文档入口 | update |
| [`CLAUDE.md`](../../../../CLAUDE.md) | 仓库级 Agent/开发约束 | 产品边界、安全非目标、文档/代码职责和适用 Skill | update |
| [`docs/00-system-overview.md`](../../../00-system-overview.md) | 当前系统总览 | 内外部平面、模块化单体、SSE、ClientApp/upstream user 和治理边界 | update |
| [`docs/a2a-agent-architecture.md`](../../../a2a-agent-architecture.md) | 当前 A2A 架构 | Agent/Provider/Worker/Session/Task、BusinessFunction/BusinessTask 和审批恢复链路 | update |
| [`docs/README.md`](../../../README.md) | 当前文档导航 | 权威入口、当前/历史分类和推荐阅读顺序 | update |
| `docs/01-overview/*.md` | 业务、系统和 roadmap 说明 | 去除失效主线，标注当前基线或历史状态 | review/update |
| `docs/02-modules/*.md` | 当前模块说明 | 实际职责、依赖、运行边界和去留状态 | review/update |
| 活跃模块与工具的 `README.md` | 模块使用和运行指南 | 当前路径、命令、Provider/Worker 身份和配置模式 | review/update |
| `docs/skills/**` | 对上游用户公开的 Skill 指引 | 当前产品/CLI/API 边界及有效路径 | review/update |
| `.agents/skills/**` | 项目 Agent 的执行技能 | 触发范围、模块职责、当前路径、非目标和后置验证 | review/update |

当前指引修正必须优先更新权威文档，再更新引用它的摘要；禁止在多个文档分别发明不一致的产品定义。

### B. 历史证据：保留原文，必要时勘误

下列材料默认不改写其历史结论：

- `docs/version-tracker/<历史版本>/` 下的 requirement、workitem、progress、quality、coverage、acceptance 和 evidence；
- `docs/requirement-tracker/` 历史季度归档；
- `docs/test-reports/`、历史手工验收、迁移记录和发布证据；
- 已签收版本中对当时实现、模型、Provider 或产品阶段的描述。

发现历史文档会误导当前使用时，按以下顺序处理：

1. 优先在当前权威文档说明“当前基线”和替代关系，并链接历史记录；
2. 如必须修改历史 Markdown，只追加日期化勘误块，说明哪些前向设计已被哪个版本或 workitem 取代；
3. 不删除原始结论、测试数字、签收决定或当时的限制；
4. evidence 的 JSON、图片、日志和签收结果保持不变；
5. 勘误不得把隔离验收改写为生产批准。

推荐勘误格式：

```markdown
> 当前基线勘误（YYYY-MM-DD）：本文保留当时的设计与证据；其中“……”已被“新基线标题”（填写实际相对链接）取代。历史测试与签收结论不变。
```

### C. 失效描述：移除当前指引身份或标记历史

#### tutor

- 当前入口、架构、模块文档和项目 Skills 不得继续把 tutor 或 tutor-agent 写成产品主线或默认示例。
- 若相关模块/Skill 已删除且无引用，按 [CLEAN-001](./CLEAN-001-low-risk-orphan-cleanup.md) 记录扫描、替代和回滚后清理残留。
- 历史版本文档中的 tutor 证据保留；当前文档只在说明历史演进时引用。

#### OpenHands

- 不把已删除的 OpenHands addon 描述成当前默认 Coding Agent 实现。
- `CodingAgentEntity` 和 `/api/v1/coding-agents` 仍是通用 Agent 注册能力，不得因 OpenHands 历史而误删。
- 旧 OpenHands Skill、路径和操作命令只有在确认目标不存在且无消费者后才进入 CLEAN-001。

#### 旧 chat-first

- 当前产品描述不得把单一聊天入口、chat-first 页面或旧会话 UI 当作 Navigator 的完整产品边界。
- `packages/foggy-chat`、`navigator-chat-widget` 等当前交付物仍可保留；需要修正的是“产品主线”叙述，不是按名称删除 chat 组件。
- `/c/:id` 仍有深链使用，不因旧 chat-first 描述失效而删除。

#### 语义层与数据分析主线

- 当前根 README、系统总览、A2A 文档、模块导航和项目 Skills 不得把 Foggy Dataset、FSScript 或语义分析写成 Navigator 的产品主线。
- `metadata-config-module` 仍有独立配置职责，不得因名称相似而删除。
- `metadata-query-module` 已由 Owner 批准按 dev-only 完整切片退役；物理删除、`metadata-config-module` 保护和删除后测试由 [CLEAN-003](./CLEAN-003-metadata-query-retirement-audit.md) 独立执行，DOC-001 只同步当前事实和历史边界。
- 历史语义层文档应保留其当时背景，必要时加当前基线勘误或移动到明确归档导航，而不是抹除历史。

## 项目 Skills 对齐要求

### 范围

项目级 Skills 指仓库内 `.agents/skills/**` 及对外文档化的 `docs/skills/**`。本工作项不修改用户全局或工作区外部 Skills。

### 每个 Skill 的检查项

| 检查项 | 要求 |
|---|---|
| 触发条件 | 对应当前仍存在的模块、能力和用户表达；不因泛化关键词误触发 |
| 路径 | `SKILL.md`、reference、脚本和模块路径实际存在 |
| 产品定位 | 不把 tutor、OpenHands、旧 chat-first 或语义层写成 Navigator 当前主线 |
| 模块边界 | 与当前 Maven/前端/Worker 依赖方向一致，不宣称动态插件或不存在的隔离边界 |
| 安全边界 | 继承内部控制面与外部运行面的差异，不把内部开发模式推广到外部模式 |
| 验证命令 | 命令可定位到当前模块，未运行时不得声称通过 |
| 后置流程 | 需要时指向 progress、自检、质量检查、覆盖审计和验收 |
| 生命周期 | active、legacy、candidate-for-removal 或 archived 状态明确 |

对仍活跃的 `metadata-config-module` Skill 只修正定位；`metadata-query-module` Skill 在 CLEAN-003 物理删除批次中退出活跃发现；对已经删除且无引用的 tutor/OpenHands Skill 按 CLEAN-001 门禁处理。

## 证据分类

| Evidence ID | 分类 | 需要回答的问题 | 允许的结论 |
|---|---|---|---|
| DOC-E01 | 已确认事实 | 当前产品定位和 1.4.2 非目标是什么 | 可直接修正当前入口文档 |
| DOC-E02 | 静态搜索 | 哪些当前文档仍含 tutor、OpenHands、chat-first、语义层主线描述 | 只能形成候选清单，不能单独删除 |
| DOC-E03 | 静态搜索 | 哪些链接、路径、Skill trigger 指向已不存在目标 | 可形成修复项；删除仍需引用扫描和回滚 |
| DOC-E04 | 结构核对 | 当前模块、Maven 依赖、前端包和 Worker 路径是什么 | 可纠正文档架构描述，不证明运行流量 |
| DOC-E05 | 运行态确认 | 旧 API、模块文档或 Skill 是否仍被外部消费者使用 | 未确认前不得宣布退役 |
| DOC-E06 | Owner 决策 | 冲突文档哪个是权威源、历史材料是否追加勘误 | 形成有日期的决策记录 |
| DOC-E07 | 验证证据 | 相对链接、Markdown、Skill 路径和手工审阅是否通过 | 仅实际执行并记录结果后可标 passed |

`2026-07-14` 已开始执行当前文档对齐：根 README/CLAUDE、系统总览、功能架构、观测/通知文档、安装说明和 testing-guide 的明确失效条目已更新；`module-review-2026-05-31.md` 只追加历史快照提示，未改写原结论。全量关键词分类、Markdown 链接/锚点检查和 Owner 手工审阅仍未完成。

## 实施步骤

### Step 1：冻结权威源与术语

1. 以 [REQ-001](../requirements/REQ-001-platform-governance-and-legacy-cleanup.md) 的产品定位、信任边界和非目标作为本版本上游基线。
2. 建立“术语/权威文档/允许摘要文档”映射，至少覆盖 Navigator、Provider、Worker、Biz Worker、ClientApp、upstream user、Session、Task、A2A、BusinessFunction、BusinessTask、内部控制面和外部运行面。
3. 记录冲突文档的 Owner，不由执行 Agent自行选择有利版本。

完成判据：权威源、术语和冲突处理规则经 root documentation owner 与架构 Owner 确认。

### Step 2：建立文档与 Skill 清单

1. 扫描根 README、CLAUDE、`docs/00-system-overview.md`、`docs/a2a-agent-architecture.md`、`docs/01-overview`、`docs/02-modules`、活跃模块 README、`docs/skills` 和 `.agents/skills`。
2. 为每一项标记 `current-authoritative`、`current-derived`、`historical-evidence`、`legacy-needs-errata`、`candidate-for-removal` 或 `do-not-touch`。
3. 对候选删除项记录反向链接、Skill trigger、脚本引用、模块引用和外部消费者缺口。

完成判据：清单包含路径、分类、Owner、预期动作、证据来源和回滚方式。

### Step 3：修正当前有效指引

1. 先更新根 README、CLAUDE、系统总览和 A2A 架构，再更新导航、模块摘要与操作指南。
2. 统一产品主线、内部/外部边界、编译期模块化单体、单 JVM SSE 和 Provider/Worker 术语。
3. 当前构建说明与 [OPT-001 构建基线](./OPT-001-build-and-ci-baseline.md) 保持一致；只把本地实际通过的 Node、pnpm、frozen install、frontend matrix 和 Java clean test 写为本地证据，hosted CI/nightly 继续标记未运行。
4. 当前安全说明与 GOV-001/002/003 保持一致，不扩大到全平台 Spring Security 重写。

完成判据：当前入口间不存在相互冲突的主线、信任边界和架构声明。

### Step 4：处理历史证据与失效描述

1. 历史材料默认不改；对确实会误导当前执行的文档追加日期化勘误和新基线链接。
2. 将 tutor、OpenHands、旧 chat-first 和语义层描述按“当前失效/历史保留/仍有技术消费者”分类处理。
3. 删除候选转交 CLEAN-001；metadata-query 去留转交 CLEAN-003；旧 Provider API 去留转交 CLEAN-004。
4. 不在 DOC-001 中直接执行模块、API、Skill 或 evidence 删除。

完成判据：当前失效描述不再作为入口指引，历史事实和证据仍可追溯。

### Step 5：对齐项目 Skills

1. 逐个核对 `.agents/skills/**/SKILL.md` 的 trigger、路径、模块职责、安全边界和验证命令。
2. 核对 `docs/skills/**` 对外指南与当前 CLI、API 和上游集成边界。
3. 为 legacy/candidate Skill 增加清晰状态与替代指引；只有在 CLEAN-001 门禁满足后才删除。
4. 复查 Skill 间的模块分工，避免同一关键词将任务错误路由到语义层、旧 addon 或不存在模块。

完成判据：活跃 Skill 均指向存在的模块和当前工作流，失效 Skill 有状态、替代和清理 Owner。

### Step 6：验证、回写与审阅

1. 执行 Markdown 相对链接和文件目标检查。
2. 执行关键词残留扫描，并逐条判断当前语义，禁止机械地以零匹配作为唯一目标。
3. 检查所有当前文档到权威源的链接以及历史勘误到新基线的反向链接。
4. 由产品、架构、模块、Skill 和 release Owner 完成手工审阅。
5. 把实际命令、结果、例外、计划外变更和风险回写到 [进度记录](../progress.md)。

完成判据：自动检查和手工审阅均有实际记录，DOC-001 验收项逐条关闭。

## 计划验证

### 静态扫描

以下命令仅为计划，当前状态均为 `not-run`：

```bash
rg -n -i "tutor|openhands|chat-first|semantic layer|语义层|dataset|fsscript" \
  README.md CLAUDE.md docs .agents/skills \
  -g '*.md'

rg -n "UnifiedSseEmitter|dynamic plugin|动态插件|多实例|Spring Security|RBAC|ABAC" \
  README.md CLAUDE.md docs .agents/skills \
  -g '*.md'

rg --files .agents/skills docs/skills docs/01-overview docs/02-modules \
  -g '*.md'
```

扫描结果必须逐项分类。历史证据中的关键词允许保留；当前文档中的 `metadata-config-module`、`packages/foggy-chat` 等有效名称不能因关键词命中而误删。

### 相对链接检查

必须检查：

- Markdown 相对文件目标存在；
- 带锚点链接对应目标标题；
- 大小写与 Linux 文件系统一致；
- Skill 引用的 `reference.md`、脚本和模块路径存在；
- 当前文档和历史勘误能双向定位；
- 不提交指向本机绝对路径的文档链接。

执行完成后记录实际工具、命令、检查范围、失败项和修复结果，不得只写“链接已检查”。

### 文档质量检查

- `git diff --check`
- 当前文档关键词残留复核
- Markdown 链接与锚点检查
- YAML metadata 与状态字段一致性检查
- 版本 README、REQ-001、DOC-001 与 progress 的相互链接检查

当前结果：`passed-local / workitem-still-in-progress`。本批次检查 32 个 Markdown、411 个相对文件目标和 3 个锚点，缺失均为 0；`git diff --check` exit 0。全量历史/Skill 分类和 Owner 手工审阅仍未完成，不能据此关闭 DOC-001。

## 手工审阅清单

| 审阅角色 | 必须确认的内容 | 状态 |
|---|---|---|
| 产品 Owner | 当前产品主线、明确非目标、旧 chat-first/语义层不再作为主线 | not-run |
| 架构 Owner | 模块化单体、SSE、Provider/Worker、内外部平面描述准确 | not-run |
| Session/Task/A2A Owner | Session、Task、A2A 与审批恢复术语和边界一致 | not-run |
| Biz Worker/ClientApp Owner | LangBizWorker、CodexBizWorker、upstream user 和外部模式描述准确 | not-run |
| Frontend Owner | chat 组件、`/c/:id`、Profile 与当前工作台关系没有被误删或误述 | not-run |
| Metadata Owner | metadata-config 保留边界和 metadata-query dev-only 退役状态准确 | not-run |
| Skill Owner | 项目 Skills trigger、路径、状态和模块分工准确 | not-run |
| Release/Signoff Owner | 历史证据未被改写，隔离验收未被表述为生产批准 | not-run |

## 风险与回滚

| 风险 | 预防措施 | 回滚方式 |
|---|---|---|
| 改写历史证据，破坏可追溯性 | 历史材料默认 do-not-touch，只追加日期化勘误 | 回退当前勘误提交，恢复原历史文件；evidence 不参与修改 |
| 关键词清理误删当前能力 | 每个命中项人工分类，metadata-config、chat 交付物和深链单独保护 | 恢复删除文件/段落并回写误判原因；删除动作由 CLEAN-001 独立执行 |
| 多个入口继续相互矛盾 | 冻结权威源，摘要只链接不另造定义 | 回退派生文档修改，以权威源重新生成变更清单 |
| Skill trigger 或路径修改导致 Agent 误路由 | 核对触发词、目标模块和 reference，保留旧状态说明 | 恢复上一版 Skill，并将冲突记录为独立 workitem |
| 历史勘误被误读为验收升级 | 固定“历史结论不变、隔离不等于生产批准”模板 | 移除歧义勘误，改由当前文档说明替代关系 |
| 链接调整造成文档不可达 | 修改前记录反向链接，执行文件与锚点检查 | 恢复原路径或增加兼容导航；不静默丢失入口 |
| 文档提前宣称未完成治理 | 所有未执行项使用 planned/not-run/pending-verification | 回退过度声明并在 progress 记录证据缺口 |

文档变更应按主题拆分为可独立回退的提交或文件组。回滚文档不等于回滚已经发生的业务迁移；本工作项本身不授权业务迁移。

## 验收标准

1. 根 README、CLAUDE、系统总览和 A2A 架构对当前产品主线、内部/外部边界与非目标表述一致。
2. 当前模块文档不把 addon 描述成动态插件，不把单 JVM SSE 描述成已支持多实例事件总线。
3. 当前入口不再把 tutor、OpenHands、旧 chat-first 或语义层写成 Foggy Navigator 产品主线。
4. `CodingAgentEntity`、`/api/v1/coding-agents`、`/c/:id`、chat widget、mobile `uni_modules`、metadata-config 等保留项没有因文案清理被误删或误标退役。
5. 历史版本、测试和验收证据保持可追溯；必要勘误注明日期、替代文档和“历史结论不变”。
6. 项目 `.agents/skills` 与 `docs/skills` 的触发、路径、模块职责和状态与当前仓库一致。
7. tutor/OpenHands 等失效 Skill 或文档候选都有引用扫描、替代说明、Owner 和回滚记录，再由 CLEAN-001 决定是否删除。
8. metadata-query 如实标记为 Owner 已批准、但物理删除尚未开始；metadata-config 明确保留且不由 DOC-001 修改。
9. 所有新增或修改的相对链接、文件路径和锚点检查通过，并留下实际命令与结果。
10. 产品、架构、模块、Skill 和 release Owner 的手工审阅有记录，未审阅项不得标记完成。
11. [进度记录](../progress.md) 已回写 development、testing、experience、self-check、风险与证据状态。
12. 文档验收不被表述为业务实现完成、正式验收或生产批准。

## 完成判据

DOC-001 只有同时满足以下条件才可从 `in-progress` 转为完成态：

- 当前文档清单、历史文档清单和项目 Skill 清单均已分类；
- 当前权威入口完成对齐，派生文档不存在已知冲突；
- 历史证据未被改写，勘误和替代关系可追溯；
- tutor、OpenHands、旧 chat-first 和语义层失效描述完成分类处置；
- Markdown 文件链接、锚点、Skill 路径和 metadata 检查实际运行通过；
- 手工审阅清单全部关闭或有明确、已接受的例外；
- progress 已回写真实变更、命令、结果、未运行项和风险；
- 完成 implementation self-check，并根据跨模块影响决定是否进入正式质量检查；
- DOC-001 自身没有修改业务代码、生产路由、数据库或外部契约；同一版本其他独立 workitem 的代码变更必须分别记证。

## Progress Tracking

### Development

- status: partial-passed
- current: 已对齐 P1 工具链、Monitoring/code-review 删除现状和外部模式默认关闭等当前入口；历史/Skill 全量分类及手工审阅待完成。

### Testing

- status: in-progress
- automation: required-for-links-paths-and-metadata
- evidence: 32 Markdown / 411 relative targets / 3 anchors missing 0；`git diff --check` exit 0；关键词按 current/historical 语义人工抽查

### Experience

- status: not-applicable
- reason: 本工作项不直接修改页面、表单、路由或交互；若执行中出现 UI 变更，必须拆分到对应工作项并补体验清单与 Playwright。

### Acceptance

- status: not-started
- production_enablement: not-applicable

## 相关工作项

- [CLEAN-001 低风险孤儿清理](./CLEAN-001-low-risk-orphan-cleanup.md)
- [CLEAN-003 metadata-query 退役审计](./CLEAN-003-metadata-query-retirement-audit.md)
- [CLEAN-004 实验性与旧兼容能力治理](./CLEAN-004-experimental-and-legacy-addon-governance.md)
- [OPT-001 构建与 CI 基线](./OPT-001-build-and-ci-baseline.md)
- [版本 README](../README.md)
