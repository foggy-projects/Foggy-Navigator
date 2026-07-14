# 1.4.2 Owner 决策记录

## 文档作用

- doc_type: decision-review
- intended_for: root-controller | decision-owner | reviewer | signoff-owner
- purpose: 记录 1.4.2 八组治理决策、Owner 约束和实施门禁，作为实施计划与进度回写的权威决策输入。

## 基本信息

- version: `1.4.2-SNAPSHOT`
- status: review-complete
- decision_status: review-complete
- proposal_date: `2026-07-13`
- decision_date: `2026-07-14`
- decision_authority: Project Owner（当前项目会话中的明确确认）
- implementation_started: yes
- production_routing_changed: no
- production_enablement: not-applicable
- acceptance_status: not-started
- requirement: [REQ-001 平台治理与历史能力收口](./requirements/REQ-001-platform-governance-and-legacy-cleanup.md)
- implementation_plan: [1.4.2 实施计划](./implementation-plan.md)
- progress_record: [1.4.2 进度](./progress.md)

## 评审规则

1. 本文中的 `approved` 与 `approved-with-constraints` 是设计和 dev 阶段实施授权，不代表实现完成、验收通过、生产启用或外部开放。
2. 本轮决策基于已确认的项目阶段：当前仍为 dev，未进入生产，所有上游均在本机共同孵化，旧开发数据允许丢弃；如静态审计发现与该前提冲突的活跃部署或仓外消费者，必须停止对应删除并重新请示 Owner。
3. 已确认事实、静态搜索结论、运行态待证和 Owner 决策继续分开记录。对本轮已授权 dev 删除项，不再把生产流量观察期、客户兼容窗口或旧数据备份作为前置条件，但仓内引用迁移、构建测试和独立回滚证据仍是硬门禁。
4. 外部运行面尚未开放。任何外部能力必须由单一、显式、默认关闭的 `external-enabled` 模式开关启用；不能通过空 Token、非 loopback 地址或多个宽松布尔组合隐式进入外部模式。
5. 安全边界类决策批准后仍需完成负向测试、readiness 和回滚验证；隔离环境验证、CI 绿色或正式验收均不自动批准生产路由或外部开放。
6. 模块 Owner、安全 Owner 和构建 Owner 负责实施复核与证据签收；Project Owner 已在本轮关闭产品方向决策，不再把多人签字作为启动 dev 实施的阻塞条件。

## 决策摘要

| ID | 决策主题 | Owner 结论 | 生效阶段 | 当前状态 |
|---|---|---|---|---|
| ODR-142-001 | Node、pnpm、lockfile 与 CI 分层 | Node `22.23.1`、pnpm `10.34.5`、单一根 lockfile、required/nightly/RC 三层 CI | P1 | approved |
| ODR-142-002 | upstream user 身份证明 | 本版降低优先级；dev/internal 保留 ClientApp 代办设计；signed assertion 延后至外部开放里程碑，外部模式必须显式开启且默认关闭 | P2 / 外部开放前 | approved-with-constraints |
| ODR-142-003 | task-scoped token | 服务端权威 opaque token；30 分钟租约；完整授权交集；task token + Worker principal/lease 双重校验；终态和暂停失效 | P2 | approved |
| ODR-142-004 | external-enabled Worker 上限 | 明确双模式；外部模式显式且默认关闭；目录/工具默认拒绝；`workspace-write`；任务工具 egress 默认拒绝；缺凭据 unready/fail closed | P2 | approved-with-constraints |
| ODR-142-005 | 关键审计保证级别 | 本地关键状态事务 outbox；无状态拒绝可靠安全事件；远程调用意图/结果分段记录；遥测 best-effort | P2/P7 | approved |
| ODR-142-006 | 四类历史能力去留 | dev 阶段允许安全后物理移除；Monitoring、metadata-query、code-review 分切片删除，Echo 保留/迁移测试 fixture 并退出生产装配 | P5（可与 P1 后并行） | approved-with-constraints |
| ODR-142-007 | 旧 Provider API、SPI、DTO 窗口 | 1.4.2 直接移除；无需生产兼容窗口或上游流量审计，先迁移全部仓内消费者并保持 clean build | P6（可与 P5 并行） | approved-with-constraints |
| ODR-142-008 | 失效 Skills 与文档 | 当前指引修正、历史证据标记、活跃 Skill 修正、确认失效 Skill 退出活跃发现 | P0/P4 | approved |

## 状态与实施复核

- 文档整体只使用 `pending-owner-review`、`review-complete` 或 `revise-required`。
- 单项只使用 `pending-decision`、`approved`、`approved-with-constraints`、`revise-required`、`deferred` 或 `rejected`。
- Project Owner 对产品阶段、兼容要求和删除范围的明确确认关闭本轮方向决策；下表角色改为实施复核人，不再是 dev 开工的法定人数。
- `approved-with-constraints` 的约束是实施门禁；约束未满足时不得把批准理解为已安全删除、可上线或可外部开放。

| ID | 实施前/合并前复核角色 |
|---|---|
| ODR-142-001 | root build owner、frontend build owner；各 Worker lane owner 对自身 required/nightly/RC 归属确认 |
| ODR-142-002 | ClientApp/upstream identity owner、Security、Platform |
| ODR-142-003 | Business Agent、Worker Gateway、Security、Operations |
| ODR-142-004 | Codex/LangGraph Worker owners、Platform、Security |
| ODR-142-005 | Security、Operations/SRE、Business Agent |
| ODR-142-006 | Platform、各切片 Owner；负责确认引用扫描、测试范围和独立回滚，不再要求生产流量或数据保留审批 |
| ODR-142-007 | API/SDK、Provider 与全部仓内消费者 Owner；负责确认调用迁移和契约测试，不要求仓外客户兼容签署 |
| ODR-142-008 | Product/Architecture、Documentation；具体 Skill 删除另需对应 Skill/module owner |

## 工作项与阶段映射

| ID | 主要阶段 | 直接受影响工作项 |
|---|---|---|
| ODR-142-001 | P1 | [OPT-001 构建与 CI 基线](./workitems/OPT-001-build-and-ci-baseline.md) |
| ODR-142-002 | P2 | [GOV-001 内外部信任边界](./workitems/GOV-001-internal-external-trust-boundary.md)、[GOV-002 Biz Worker/upstream user](./workitems/GOV-002-biz-worker-and-upstream-user-boundary.md) |
| ODR-142-003 | P2 | [GOV-002](./workitems/GOV-002-biz-worker-and-upstream-user-boundary.md)、[GOV-003 Session/Task ownership](./workitems/GOV-003-session-task-resource-ownership.md) |
| ODR-142-004 | P2 | [GOV-001](./workitems/GOV-001-internal-external-trust-boundary.md)、[GOV-002](./workitems/GOV-002-biz-worker-and-upstream-user-boundary.md) |
| ODR-142-005 | P2/P7 | [GOV-002](./workitems/GOV-002-biz-worker-and-upstream-user-boundary.md) |
| ODR-142-006 | P5 | [CLEAN-002 Monitoring](./workitems/CLEAN-002-monitoring-retirement.md)、[CLEAN-003 metadata-query](./workitems/CLEAN-003-metadata-query-retirement-audit.md)、[CLEAN-004 实验/兼容能力](./workitems/CLEAN-004-experimental-and-legacy-addon-governance.md) |
| ODR-142-007 | P6 | [CLEAN-004](./workitems/CLEAN-004-experimental-and-legacy-addon-governance.md)、[OPT-002 可维护性治理](./workitems/OPT-002-core-code-maintainability.md) |
| ODR-142-008 | P0/P4 | [DOC-001 文档对齐](./workitems/DOC-001-documentation-alignment.md)、[CLEAN-001 低风险清理](./workitems/CLEAN-001-low-risk-orphan-cleanup.md) |

## 主要替代方案与取舍

| ID | 未推荐方案 | 未作为默认建议的原因 |
|---|---|---|
| ODR-142-001 | 同时切 Node 24、pnpm 11；或继续 Node 18 | 前者把构建恢复扩大为两次 major 迁移，后者不满足当前 Vite 7 工具链；建议先冻结最小可验证支持线 |
| ODR-142-002 | 立即强制所有 dev/internal 调用 signed assertion；或未来外部开放仍永久只用 ClientApp 代办 | 前者在当前阶段收益不足，后者在外部场景证明强度不足；Owner 选择本版保留代办、显式关闭外部模式，并把 assertion 设为外部开放门禁 |
| ODR-142-003 | 完全自包含 JWT；或长期不轮换 token | JWT 的即时撤销和任务状态传播更复杂，长期 token 会放大泄漏与重放窗口；Gateway 已有服务端校验点，opaque 更合适 |
| ODR-142-004 | 继续用多个布尔开关推断“是否外部安全”；或全面关闭内部开发模式 | 布尔组合容易产生隐式放宽，全面关闭会破坏内部开发；显式双模式能同时保留开发效率和外部门禁 |
| ODR-142-005 | 所有事件 best-effort；或所有事件同步强保证 | 前者无法保证关键审批/撤销可追溯，后者会让高频 SSE/心跳耦合主事务；分级 outbox 兼顾一致性和可用性 |
| ODR-142-006 | 四项在一个不可回滚提交中一起删除；或因不确定而无限期全部保留 | Owner 已授权 dev 物理清理，但四项装配和测试价值不同；仍须独立盘点、提交和回滚 |
| ODR-142-007 | 未迁移仓内消费者就删 Controller/SPI/DTO；或继续保留生产级兼容窗口 | 前者会直接破坏本仓，后者与当前未生产阶段不匹配；选择仓内迁移后同版本直接删除 |
| ODR-142-008 | 批量重写历史文档；或把失效 Skill 留在活跃发现目录作“归档” | 前者破坏历史证据，后者继续误导 Agent 路由；当前基线、历史证据和活跃发现应分开治理 |

## ODR-142-001：Node、pnpm、lockfile 与 CI 分层

### 决策问题

当前 README 仍声明 Node 18，而已安装 Vite 7 工具链要求更高的 Node 版本；根 `pnpm-lock.yaml` 存在但被忽略，`packages/foggy-chat/pnpm-lock.yaml` 又形成第二套旧依赖解析结果；根构建和 GitHub Actions 均未覆盖完整前端、Worker 和 clean build 基线。

### 证据分类

- 已确认事实：1.4.2 必须选择一个与当前工具链兼容、可机器校验的明确 Node 版本，不继续维持笼统的 Node 18+ 声明；JDK 17 和 Maven reactor/launcher 是 Java 基线。Node 22 是 ODR-142-001 的建议方向，不是已批准事实。
- 静态搜索结论：五个前端包处于同一 pnpm workspace；根 lockfile 未跟踪；chat 嵌套 lockfile 为旧格式；当前 CI 主要是 Codex Worker 发布流程。
- 本地诊断：主前端有效 app type-check 已发现 `ClaudeWorkerView.vue` 两个错误；这不是通过证据。
- 外部版本资料：截至 `2026-07-13`，Node v22 归档列出的最新 patch 为 `22.23.1`；Vite 7 支持 Node `20.19+` 或 `22.12+`；pnpm release 列表中的当前 10.x patch 为 `10.34.5`。精确版本在真正实施 P1 时仍需由 build owner 再核对一次。

参考：

- [Node.js 发布状态](https://nodejs.org/en/about/previous-releases)
- [Node.js v22 归档](https://nodejs.org/en/download/archive/v22)
- [Vite 7 Migration Guide](https://v7.vite.dev/guide/migration)
- [pnpm Releases](https://github.com/pnpm/pnpm/releases)

### 建议决策

1. CI、开发版本文件和 clean build 镜像固定 Node `22.23.1`；根 `engines.node` 声明 `>=22.23.1 <23`。
2. 根 `packageManager` 固定为 `pnpm@10.34.5`，所有生成 lockfile 和 frozen install 的流程只使用该版本。
3. Corepack 只作为 pnpm bootstrap，不作为隐式版本来源；CI 必须打印 Node、Corepack 和 pnpm 实际版本。若 CI 需要单独安装 Corepack，其精确版本由 build owner 在 P1 首次提交中记录。
4. 根 `pnpm-lock.yaml` 成为五个 workspace 包的唯一权威 lockfile并纳入 Git。
5. 在确认不存在独立安装/发布消费者后移除 `packages/foggy-chat/pnpm-lock.yaml`；chat 的 pack/publish 从根 workspace 执行。若 Owner 提供独立发布证据，则保留前必须补独立 frozen install 和发布 lane。
6. 1.4.2 不同时升级 Node 24、pnpm 11、Vite、Vue 或 TypeScript；依赖升级另立事项，避免把恢复基线扩大为工具链迁移。

### CI 分层建议

| 层级 | 必须覆盖 | 失败语义 |
|---|---|---|
| PR required | `launcher -am clean test`；根 frozen install；chat-core/chat/widget/PC/mobile 各自真实存在且有效的检查入口与交付 build；Worker 无凭据 unit/type/build；文档链接、lockfile 和安全负向测试 | 任一 required lane 失败即不得合并；不存在的命令不得伪造，缺少某类检查时须补入口或由 Owner 明确 `not-applicable`、理由和替代证据 |
| Nightly | 全 reactor `clean verify`；完整 L3/L4/Playwright；Worker 跨平台和安装包；mobile 非 H5 目标；长任务恢复、并发和稳定性 | 失败产生阻塞缺陷；不得用 nightly 替代已要求的 PR 核心门禁 |
| RC/受控环境 | 真实 LLM、Git、OBS、upstream 联调；凭据轮换；canary/soak；生产相似配置 | 需要显式授权和受控密钥；通过不自动批准生产启用 |

公共 lockfile、POM、workflow 或共享契约变化必须扩大矩阵，不能只按 changed path 跳过相关包。普通 PR 不运行必须依赖真实生产凭据的测试，但无真实凭据即可完成的过期、撤销、越权和错误身份测试必须 required。

### 风险与回滚

- 风险：新 lockfile 引入依赖漂移；控制：固定 pnpm、双 clean checkout frozen install 和依赖 diff。
- 风险：嵌套 lockfile 确有独立消费者；控制：删除前取得 chat owner 和发布配置证据。
- 回滚：工具版本、lockfile、脚本和 workflow 分提交并使用 `git revert`；不得通过恢复未跟踪 lockfile 或无效 type-check 掩盖失败。

### Owner 评审

- decision_owner: Project Owner；实施复核为 root build owner + frontend owner + Worker owners
- proposed_result: approve
- review_result: approved
- review_date: `2026-07-14`
- rationale_or_constraints: 按建议版本与单根 lockfile/CI 分层实施；真实 clean 环境验证结果仍须在 P1 记录。

## ODR-142-002：upstream user 身份证明

### 决策问题

ClientApp 代办 grant 能证明“某个 ClientApp 被允许代表某个 upstream user”，但不能独立证明当前请求确由该用户触发；signed assertion 能提供短期、可验证、可防重放的用户主体证明。两者分别属于应用身份和用户身份，不应被混为一个凭据。

当前静态事实是 Navigator 已有 ClientApp runtime/control credential、upstream user mapping/grant 和 readiness 校验基础，并非从零建设。现有链路在 ClientApp 已认证后接收 `upstreamUserId` 并检查 grant，因此能够证明应用代办授权，但还没有形成独立的终端用户加密证明。运行态上哪些 ClientApp 实际需要用户级强证明仍待消费者和风险分级确认。

### Owner 决策

1. 1.4.2 不把 upstream user 的独立加密身份证明作为近期交付阻塞项。dev/internal 阶段继续采用已设计的 ClientApp credential + upstream user mapping/grant 代办模式，优先完成构建、资源归属和外部模式显式关闭。
2. `external-enabled` 必须是单一、显式、默认关闭的运行模式；没有显式打开时，即使绑定非 loopback 地址、Token 为空或其他开发开关开启，也不得被解释为已经允许外部调用。
3. signed assertion 保留为未来外部开放里程碑的目标身份模式；ClientApp credential 仍然必需，assertion 不替代应用认证。真正允许非可信外部调用前，必须重新启用本节的 assertion 协议、负向测试和 readiness 门禁。
4. 最终外部有效权限取以下交集：

   `ClientApp credential ∩ signed subject ∩ upstream user mapping/grant ∩ task/resource policy`

5. internal-dev 代办模式只适用于可信内网/本机孵化链路，仍必须校验 ClientApp、tenant、mapping/grant、任务归属和函数 scope；降低身份证明优先级不等于允许直接信任请求体 actor。
6. 外部审批、拒绝、恢复、取消和高风险 BusinessFunction 在未来开放时必须使用 signed subject 或平台控制凭据，不允许只靠代办 userId。
7. 请求体中的 `userId`、`upstreamUserId`、`reviewedBy` 和 `tenantId` 只能作为业务输入、assertion 输入或比对值，不能成为 verified principal。
8. 审计必须记录 `identity_assurance`，至少区分 `client-app-delegated`、`client-app-signed-delegated`、`independent-idp-signed-subject` 和 `platform-control`。只有签名密钥由独立 upstream 身份权威控制时，才使用 `independent-idp-signed-subject`；与 ClientApp credential 同一主体/信任域控制的 assertion 仍属于增强的代办证明，不能虚标为独立终端用户证明。

未来 external-ready 协议基线冻结为短期 JWT/JWS assertion（本版可只落设计，不要求启用）：

- 固定 claims：`iss`、`aud`、`sub`、`tenant_id`、`client_app_id`、`iat`、`nbf`、`exp`、`jti`、`assertion_version`；`sub` 必须通过权威 mapping 解析为 Navigator effective user，不能直接当内部 userId 使用。
- 有效期不超过 5 分钟，建议 clock skew 上限 60 秒；`jti` 在服务端防重放存储中保留至少到 `exp + skew`。
- issuer、tenant、ClientApp、允许算法和 JWK/public-key 地址必须在 Navigator 侧预注册；建议算法 allowlist 为经库和 Owner 验证的 `ES256`/`RS256` 等非对称算法，明确拒绝 `none`，不复用 ClientApp API secret 作为 assertion 对称签名密钥。
- 禁止根据 assertion header 中未经信任的 `jku`、`x5u` 或任意 URL 动态建立信任；只接受预注册 key source 和已知 `kid`。
- JWK 轮换必须支持新旧 key 有界重叠、缓存和撤销。刷新失败时可以在配置的缓存有效期内继续使用已验证旧 key，但未知 `kid`、过期缓存或签名失败必须 fail closed。

### 本版与未来门禁

- 1.4.2 当前门禁：实现并验证显式 `external-enabled` 开关默认关闭；internal-dev 模式可继续使用 `client-app-delegated`，但必须保留 tenant、ClientApp、mapping/grant、task/resource ownership 校验。
- 外部开放门禁：按 ClientApp 启用 signed assertion，并覆盖错误 issuer/audience/algorithm/kid、动态 `jku`、过期、超出 clock skew、未来签发、重复 jti、tenant/ClientApp 不匹配、mapping/grant 缺失和请求体 actor 欺骗。
- 外部开放门禁：覆盖 JWK 正常轮换、未知 key、缓存过期、key source 暂时不可用和撤销传播；未取得可复现证据前不得把 signed assertion 标记为 external-ready。
- internal-dev 兼容不能因配置继承或环境误判扩散到 external-enabled；外部模式缺少所需身份证明时必须 unready/fail closed。
- 回滚可以关闭 external-enabled 并恢复内部开发链路，但不得回滚为直接信任请求体身份，也不得让外部模式静默降级为 ClientApp 代办。

### Owner 评审

- decision_owner: Project Owner；实施复核为 ClientApp/upstream owner + Security + Platform
- proposed_result: approve-with-deferred-external-proof
- review_result: approved-with-constraints
- review_date: `2026-07-14`
- rationale_or_constraints: upstream user 独立身份证明下放优先级；dev/internal 可沿用现有代办设计，但外部模式必须显式、默认关闭，signed assertion 是未来外部开放的 readiness 硬门禁。

## ODR-142-003：task-scoped token 契约

### 决策问题

task token 需要同时解决任务隔离、函数范围、生命周期、即时撤销、Worker 重放和长任务续期。完全自包含 JWT 不利于即时撤销；单纯依赖 JVM 内存又不利于恢复和多实例一致性。

当前静态实现已经使用随机 token、数据库 hash 和任务/tenant/ClientApp/upstream user 等绑定，默认有效期为 2 小时；明文 token 还依赖运行时内存存储完成部分注入/恢复。当前缺口主要是任务级 BusinessFunction allowlist、显式撤销/轮换状态、暂停/终态 generation 语义以及重启/多实例下的权威恢复。上述结论仍需 P2 在真实 schema、缓存和调用链上复核。

### 建议决策

1. 使用服务端权威的 opaque random token；数据库仅保存安全 hash/HMAC 和状态，明文只在签发和受控传输时短暂出现。
2. token 记录至少绑定：tenant、ClientApp、verified upstream subject、`navigatorEffectiveUserId` 或等价内部主体、subject mapping/version、task、session、skill/version、workspace policy、worker pool、worker principal、worker lease、token audience、token version/generation、签发/过期时间和撤销状态。
3. 函数授权拆成两层：
   - Gateway capability，例如 schema、invoke、status；
   - 明确的 `BusinessFunctionId@version` 集合，或不可变 policy snapshot ID/digest。
4. `business.functions.invoke` 只表示允许调用 invoke 通道，不表示允许所有 BusinessFunction；最终权限至少取以下交集，不得因引入 token snapshot 而跳过现有硬门禁：

   `token scope ∩ tenant/ClientApp 当前状态 ∩ subject mapping/user grant ∩ skill grant ∩ function grant/version 状态 ∩ task/session/worker lease 当前状态`
5. external 模式默认不允许函数通配符；批量授权必须引用有版本的函数集合。
6. 单个 token TTL 建议为 30 分钟，可配置上限 60 分钟。长任务每次首次派发、续签、恢复或重绑都由 Navigator 生成新 generation，Worker 不持有长期 refresh token。
7. 成功、失败、取消、拒绝、超时等终态立即失效；进入暂停/待审批时旧 token 失效，恢复时签发新 generation。
8. 支持按 task、ClientApp/credential、upstream subject/grant 人工或批量撤销，并记录原因、主体和时间。
9. token audience 固定为 Navigator Worker Gateway，不得作为通用 Navigator API token；传递路径限定为“已认证调度面 -> 受控 TLS 通道 -> 目标 Worker”，不返回上游浏览器/ClientApp，不写日志或任务状态正文。
10. token 绑定逻辑 Worker lease，而不是 IP 或永久物理机器。Gateway 必须同时校验 task token 与独立 Worker service credential，或使用 mTLS/DPoP 等 proof-of-possession；认证得到的 Worker principal 必须与 lease 一致。仅在 token 记录中写 `workerLeaseId` 只能作为审计标签，不能单独构成防重放边界。
11. 续签、恢复、重绑和重启补发只能由已认证调度面发起，并校验 Worker principal、lease 与当前任务状态。新 token 通过同一受控通道投递；旧 generation 默认在新 generation 生效时失效。

### 验证门禁

- 跨 task、session、function、tenant、ClientApp、subject、effective user、skill grant、Worker principal 和 Worker lease 全部拒绝。
- 旧 generation、过期、人工撤销、grant 撤销、终态和暂停状态全部拒绝。
- 重启后仍能从服务端权威状态判断有效性，不能把单 JVM cache 作为唯一依据。
- 续签响应丢失、Navigator/Worker 重启、双 generation、重绑和撤销并发必须有确定顺序和审计；允许的短暂 overlap 如确有必要，必须由 Owner 给出上限，建议默认 0、最高 30 秒。
- 只有 task token、没有匹配 Worker credential/PoP 的请求必须拒绝；Worker credential 正确但 task token 的 lease 不匹配也必须拒绝。

### Owner 评审

- decision_owner: Project Owner；实施复核为 Business Agent + Worker Gateway + Security + Operations
- proposed_result: approve
- review_result: approved
- review_date: `2026-07-14`
- rationale_or_constraints: 按建议的 opaque token、授权交集、TTL、终态失效、撤销、轮换和 Worker principal/lease 约束实施。

## ODR-142-004：external-enabled Worker 能力上限

### 决策问题

当前开发模式、空 token、非 loopback 监听、工作目录、工具 allowlist、Codex sandbox/approval 和网络参数的组合可能产生隐式放宽。需要用显式运行模式和服务端 policy 建立不可由请求方扩大的上限。

当前静态证据包括：部分 Codex/LangGraph Worker 在 token 为空时可跳过认证且默认可能监听非 loopback；Codex SDK/App Server 任务默认 sandbox 为 `danger-full-access`；请求模型可携带 sandbox、approval 和 network 字段；LangGraph 对缺失/`None` 的工具策略与显式空列表存在不同语义，缺失时部分命令工具仍可能可用。这些行为可保留给显式内部开发 profile，但不能直接继承为 external-enabled 默认值。

### 建议决策

建立互斥的 `internal-dev` 与 `external-enabled` 模式。`external-enabled` 必须由一个权威配置项显式开启且默认值为 `false`；其他监听、Token、profile 或请求参数均不能隐式打开该模式。固定以下约束：

| 维度 | `external-enabled` 建议上限 | `internal-dev` 允许范围 |
|---|---|---|
| 监听与凭据 | 非 loopback 缺 credential/token 时启动失败或 unready；不接任务 | 空 token 只允许显式启用且绑定 loopback |
| 工作目录 | 请求传 workspace 标识；服务端映射 canonical root；拒绝 `..`、symlink escape、home/root 和其他项目 | 可显式扩大，但必须显示为 non-external-ready |
| 工具 | 默认拒绝；缺失 policy 为配置错误；显式 `[]` 为 deny-all；请求只能缩小服务端 allowlist | 可显式启用开发工具，不能继承为外部默认值 |
| Codex sandbox | 默认 `workspace-write`，分析任务可 `read-only`；通用 external pool 禁止 `danger-full-access` | 可在 loopback、显式高风险配置下使用 |
| approval | 自动 Worker 可固定 `never`，其含义是不能请求越过 sandbox；业务审批仍走 Navigator 暂停/审批/恢复 | 可选择开发模式，但不得把请求字段当作平台审批 |
| 网络 | 任务工具 egress 默认拒绝；控制面和 LLM 必需连接使用基础 allowlist；Git/包仓、命令网络、web search 分别引用服务端命名策略 | 可显式开启并显示具体网络平面和风险状态 |
| readiness | 输出 mode、auth、policy version、sandbox、workspace 和 network readiness，不泄露 secret | 明确输出 `external_ready=false` |

网络策略必须区分四个平面：Worker -> Navigator/Gateway 控制面、Worker -> LLM Provider 必需连接、Git/npm/Maven 等构建依赖、Agent 命令和 web search 的用户工具 egress。前两类以最小基础 allowlist 进入 readiness；构建依赖使用任务/ClientApp 可引用的命名策略；用户工具 egress 默认拒绝。不能把“网络默认关闭”实现成切断 Worker 控制面或 LLM 必需连接。

如确需 external 特权任务，应使用独立 privileged Worker pool、独立 ClientApp grant、独立网络和审计策略，不允许调用方通过 `sandboxMode`、`networkAccessEnabled`、allowed dirs/tools 临时扩大普通 external pool。

### 验证与回滚门禁

- 覆盖非 loopback 空 token、目录穿越和 symlink、缺失/空工具策略、sandbox/network 放宽、控制面/LLM 基础连接、错误 policy version 和 readiness 脱敏。
- caller 提交的目录和工具与服务端 allowlist 求集合交集；sandbox 按服务端权限序只允许收紧；approval 由服务端固定或验证为兼容值；网络只能引用服务端命名策略。四者不能用一个笼统的“求交集”实现。
- 回滚只允许关闭 external-enabled 或恢复内部开发 profile；不得回滚为非 loopback 空凭据可用。

### Owner 评审

- decision_owner: Project Owner；实施复核为 Codex/LangGraph Worker owners + Platform + Security
- proposed_result: approve
- review_result: approved-with-constraints
- review_date: `2026-07-14`
- rationale_or_constraints: external-enabled 必须显式、默认关闭；本轮优先实现模式与 readiness 门禁，未完成全部外部约束前不得打开。

## ODR-142-005：关键审计保证级别

### 决策问题

当前部分运行审计采用独立事务并在写失败时记录 warn，属于 best-effort；部分授权拒绝又发生在 invoke audit 之前。全量同步强保证会拖累 SSE、心跳和进度链路，但关键审批或撤销成功后丢失审计不可接受。

### 建议决策

采用“本地关键状态 + 事务 outbox、无状态拒绝可靠落档、远程调用意图/结果分段记录、高频遥测 best-effort”的分级方案。outbox 是可靠投递机制，不天然等于可查询、满足留存的权威审计库；1.4.2 必须明确权威 audit sink、查询入口、留存和数据 Owner。建议以现有数据库中的规范化安全/运行审计记录为权威事实源，并在同一事务写 outbox 供异步索引或外部汇聚。

### A. 本地状态变化

以下本地状态与对应 audit record/outbox 必须同事务提交，写失败时回滚并 fail closed：

- credential/task token 签发、轮换和撤销；
- task 与 Worker lease 绑定、重绑和失效；
- 暂停、审批请求、批准、拒绝、恢复和取消；
- 本地授权通过后形成的 invocation/dispatch intent；
- 任务终态触发的 token 失效。

### B. 无状态授权拒绝

tenant、ClientApp、subject、task、scope、Worker 等不匹配通常发生在没有业务状态事务之前。此类拒绝写独立的持久安全事件；审计存储故障时仍必须拒绝请求，并写入独立可靠安全日志、触发告警/readiness degraded，但不得宣称该事件已经持久审计。只有存在可重放的本地来源时才能承诺补偿，不能笼统承诺从普通 warn 日志恢复全部记录。

### C. 远程 BusinessFunction 调用

在派发前先持久化 invocation/dispatch intent；远程返回、回调、超时或失败后，在后续事务记录 observed result。外部系统已经发生的副作用无法与 Navigator 本地数据库事务原子提交，因此 1.4.2 只承诺“本地授权决策、派发意图和观察到的结果可靠记录”。如果后续要实现端到端 exactly-once，需要 BusinessFunction 幂等键、外部协议和独立方案，不在本文中虚构强原子性。

### D. best-effort 遥测

SSE 文本 chunk、token usage、心跳、细粒度进度、debug trace 和非关键性能指标继续 best-effort，不阻塞主链路。

outbox 可使用现有数据库，不要求 1.4.2 同时引入 Kafka，也不等同于多实例 SSE 事件总线。事件至少包含 eventId、verified actor、identity assurance、tenant、ClientApp、subject、task/function、decision、reason code、policy version、trace/correlation ID 和时间；token、secret、完整 prompt 与敏感业务参数必须脱敏。Publisher 采用至少一次投递，consumer 按 eventId 幂等，失败有重试、告警和死信/隔离记录。

### 验证门禁

- 本地状态/audit/outbox 原子性、数据库写失败、重复投递、consumer 幂等、脱敏和重试测试。
- 覆盖在 `INVOKE_STARTED` 之前发生的身份、scope 和任务归属拒绝。
- 覆盖安全事件存储失败时“继续拒绝 + degraded/告警 + 不虚报持久证据”，以及远程调用 intent、响应丢失、超时、重复回调和结果幂等。
- 明确 authoritative audit sink 与 outbox backlog、最后成功投递时间、死信和留存的 readiness/运维检查。
- 审计留存期由 Security/Operations 拍板；建议关键安全事件 180 天、普通运行遥测 30 天，并允许按法规和存储成本调整。

### Owner 评审

- decision_owner: Project Owner；实施复核为 Security + Operations/SRE + Business Agent
- proposed_result: approve-hybrid-outbox
- review_result: approved
- review_date: `2026-07-14`
- rationale_or_constraints: 按关键状态事务 outbox、可靠拒绝事件、远程意图/结果分段和遥测 best-effort 的分级方案实施。

## ODR-142-006：Monitoring、metadata-query、code-review 与 Echo 去留

### Owner 决策

四个切片继续独立盘点、独立提交、独立回滚和独立签收，但当前 dev 阶段已经授权在满足仓内安全门禁后物理移除。授权前提是项目尚未进入生产、上游仍在本机共同孵化、旧开发数据允许丢弃；若审计发现共享/生产资源、活跃独立部署或与此前提冲突的消费者，立即停止对应切片并重新请示。

| 能力 | 1.4.2 目标态 | 物理删除授权 | 数据处理 | 实施硬门禁 |
|---|---|---|---|---|
| Monitoring | 删除自研 RabbitMQ Monitoring 完整切片 | dev-only: yes | 旧 dev 队列、表和数据可丢弃 | 完整资源清单、仓内引用扫描、替代基础日志/健康观测、受影响 clean build/test |
| metadata-query | 删除 `metadata-query-module` 专属切片 | dev-only: yes | 旧 dev 查询数据可丢弃 | 保护 `metadata-config-module`、迁移/删除仓内引用、launcher/reactor clean build |
| code-review-agent | 删除未装配的历史实验 addon | dev-only: yes | 旧 dev 配置/记录可丢弃 | 扫描 GitLab/webhook/独立部署配置；发现实际活跃外部资源则停手 |
| Echo Agent | 退出生产装配/发现；把必要价值迁为 dev/test fixture | dev-only: yes | 示例数据可丢弃 | 先迁移统一分派、测试、探针所需 fixture；不得删除 `LocalEchoBusinessFunctionAdapterInvoker` |

| 子决策 | Owner 结论 | environment_scope | data_discard_authorized | physical_deletion_authorized |
|---|---|---|---|---|
| ODR-142-006-MON | approved-with-constraints | development-only | yes | yes |
| ODR-142-006-MQ | approved-with-constraints | development-only | yes | yes |
| ODR-142-006-CR | approved-with-constraints | development-only | yes | yes |
| ODR-142-006-ECHO | approved-with-constraints | development-only | yes | yes，完成 fixture 迁移后 |

上述授权免除生产流量观察期、客户兼容窗口和旧 dev 数据备份要求，不免除精确环境确认、仓内依赖迁移、测试、回滚和完整功能切片治理。删除代码与删除 RabbitMQ topology、数据库对象、webhook/credential 等外部资源仍分开记录；本轮可丢弃数据不等于允许对未确认环境执行破坏性操作。

### Monitoring

- 退役范围是 RabbitMQ 日志采集、事件持久化、告警、Monitoring 页面/API、SecurityConfig 残留、启动安装步骤和相关部署文档，不是取消应用日志、指标和安全审计。
- 盘点 `/api/v1/monitoring/**`、RabbitMQ exchange/queue/binding、publisher/consumer、`monitoring_events`、独立 jar/镜像、dashboard、告警、启动脚本和文档，按完整切片删除。
- 删除前保留应用日志、健康检查等最低替代观测；不把自研 Monitoring 退役误写为取消安全审计或所有运行观测。
- 不要求 30 天静默或旧 dev 数据备份；回滚以独立代码提交、资源定义清单和必要的重建脚本/说明为准，不承诺恢复已明确允许丢弃的旧数据。

### metadata-query

- 当前仍在根 reactor 和 launcher，且依赖旧语义层能力；仓内未发现明显消费者只是静态结论。
- 静态扫描 `/api/metadata/query/**`、`foggy.api.base-url`、TM/QM、datasource、独立部署配置和仓内 SDK 引用；本轮不要求 60 天运行流量观察或仓外客户清单。
- 退役只能精确移除 `metadata-query-module` 专属切片，必须对 `metadata-config-module` 做完整回归，不按相邻 package 前缀批量删除；根 reactor、launcher、配置、文档和测试必须同切片收口。
- 若扫描意外发现实际活跃的共享部署或仓外集成，记录为“与 dev-only 假设冲突”并停止，不自行扩张删除授权。

### code-review-agent

- 当前源码包含 GitLab webhook、配置/记录表、credential 和 MR 评论逻辑，但不在默认 reactor/launcher，更接近历史实验能力。
- 静态扫描 GitLab project webhook/delivery、独立 jar/容器/反向代理、`code_review_config`、`code_review_record`、MR 评论、Git Provider credential 和 CI 外部调用。没有实际活跃资源时直接物理移除，不再只做 archive/freeze。
- dev 数据可丢弃，不要求导出记录或静默窗口；发现真实 webhook、共享 credential 或独立部署时先停手，避免对未确认外部资源产生副作用。
- 如 Owner 决定恢复产品能力，应另立需求并按当前 ClientApp、BusinessTask/Function、task token 和审计边界重新设计，不能原样加回 launcher。

### Echo Agent

- Echo 有统一任务分派和迁移测试价值，但当前默认进入生产 discovery 不合适。
- 先把仍有价值的统一任务分派、迁移测试、演示或探针依赖迁入明确的 dev/test fixture；生产装配和 discovery 必须显式关闭或删除。
- fixture 迁移和引用扫描完成后，可从 launcher/runtime dependency 与生产发现中物理移除 Echo addon；是否保留根 reactor 中的测试 fixture 由最小可维护结构决定。
- 不删除 `LocalEchoBusinessFunctionAdapterInvoker`，它不是 Echo Agent addon。

### Owner 评审

- decision_owner: Project Owner；实施复核为 Platform + 各切片 Owner
- proposed_result: approve-dev-physical-removal
- environment_scope: development-only
- production_retirement_authorization: not-applicable
- data_discard_authorized: yes
- review_result: approved-with-constraints
- review_date: `2026-07-14`
- rationale_or_constraints: 满足精确环境确认、仓内引用迁移、完整切片、测试和独立回滚后可直接物理移除；发现共享/生产资源时停止并重审。

## ODR-142-007：旧 Provider API、deprecated SPI 与 DTO 窗口

### Owner 决策

1. 1.4.2 直接移除 `/api/v1/claude-tasks`、`/api/v1/codex-tasks`、`/api/v1/langgraph-tasks`、deprecated SPI 和仅服务这些入口的兼容 DTO；项目尚未进入生产，所有上游仍在本机共同孵化，不设置外部客户兼容窗口。
2. 取消“两版本 + 90 天 + 30 天零流量”、sunset header、仓外消费者清单和旧 Provider 二进制兼容期要求；旧 dev 数据、历史生成图片链接和过渡记录允许丢弃。
3. 直接删除不等于先删 Controller 再修编译。必须先按 method/route 盘点并迁移或删除全部仓内消费者、测试、canary/soak、SDK/CLI 和前端调用，再在同一受控批次移除契约。
4. 统一 `/api/v1/tasks`、当前 Provider 能力、`TaskDispatchRequest.providerType`、通用 Agent 注册和本版明确保留的集成资产不属于删除对象。
5. LangGraph 审批、恢复、取消等身份语义即使随旧路由删除，也必须在替代入口使用可信 principal/token context，不得把请求体 `userId`、`reviewedBy` 或 `tenantId` 迁入新契约继续信任。

### 仓内迁移与专属门禁

- Claude：逐方法确认 worker session、conversation config、分页和过滤是否已有统一入口；只迁移当前仓内仍需语义，不为未使用方法新建一套兼容 facade。
- Codex：PC `file-hints`、App Server canary/soak root GET、`CodexStreamRelay` 的 generated-image URL 必须迁移或随失效场景删除；历史 dev 会话中的旧图片链接无需保留。
- LangGraph：仓内 GET 和 approve 调用必须迁移到统一入口；审批 actor 改为可信 principal/token context，并校验 tenant、ClientApp、subject 与 task/approval 归属。

### 消费者清单

当前静态已知消费者包括 PC -> Codex file-hints、PC -> LangGraph approve、Business Agent L3 与 dev bootstrap -> LangGraph GET、Codex App Server canary/soak -> Codex root GET、Codex 历史消息 -> generated-image URL。实施清单至少记录仓内路径、调用 method/route、替代或删除动作、验证命令和回滚提交；不要求补生产流量指标、已发布客户版本或仓外联系人。

### SPI 与 DTO

- 让框架内部和全部仓内 Provider 只调用 typed API，随后在 1.4.2 同批删除 legacy default bridge、deprecated SPI 和无剩余用途的兼容 DTO/Form。
- 不保留两个 artifact 版本、180 天或额外 minor 的二进制兼容窗口；仓内 Provider 样例、launcher reactor 和受影响 Worker 编译测试必须通过。
- DTO/Form 只有在仍服务当前 typed/unified 契约时保留；不得因名称相似批量删除当前请求模型。
- `TaskDispatchRequest.providerType` 仍服务统一 OpenAPI/独立执行，不属于本轮删除对象。

### 回滚与否决条件

每组路由/SPI/DTO 采用独立可 revert 提交；删除前保存引用扫描和迁移动作清单。clean build/test 失败、仓内调用未迁完、统一入口缺少当前必需语义或误删非兼容能力时回滚对应提交。旧 dev 图片和数据丢失不是回滚触发条件；LangGraph 身份硬化不得回滚为信任请求体 actor。

### Owner 评审

- decision_owner: Project Owner；实施复核为 API/SDK + Provider + 仓内消费者 owners
- proposed_result: approve-direct-dev-removal
- environment_scope: development-only
- external_compatibility_window: not-required
- data_discard_authorized: yes
- review_result: approved-with-constraints
- review_date: `2026-07-14`
- rationale_or_constraints: 所有上游仍在本机孵化，可直接移除旧契约；先完成仓内消费者迁移、可信 actor 语义和 clean build/test，不保留生产兼容窗口。

## ODR-142-008：失效 Skills 与文档处理

### 建议决策

采用分级处理，不统一全删：

| 分类 | 建议动作 | 示例/约束 |
|---|---|---|
| `current-authoritative` | 直接修正 | 根 README、CLAUDE、system overview、A2A 架构和当前接入入口 |
| `current-derived` | 与权威源同步 | 模块 README、部署和操作指南；不得形成第二套产品定位 |
| `legacy-needs-errata` | 增加日期化勘误和新基线链接 | 仍可能被当前读者检索但内容已经过时的设计文档 |
| `historical-evidence` | 保留原文，必要时只加历史标记 | 旧版本 requirement、test、evidence、acceptance，不改写历史事实 |
| `candidate-for-removal` | 引用扫描、Owner 确认、替代和回滚后退出活跃树 | 指向已删除 tutor/OpenHands addon 的 Skill 或失效当前指南 |
| `do-not-touch` | 明确保留 | 仍在交付或有当前消费者的文档、Skill 和集成资产 |

活跃但内容过时的 Skill 应修正 trigger、路径、模块边界和验证命令；只有确认目标模块/流程已删除、无引用且有替代时，才从 `.agents/skills` 活跃发现目录移除。需要保留历史时优先依赖 Git history 或明确的 docs archive，不把已失效 Skill 留在自动发现目录中。

特别约束：

- 不因旧 OpenHands/tutor 文档删除当前通用 `CodingAgentEntity` 和 `/api/v1/coding-agents`。
- metadata-query Skill 在模块正式退役前只能标记 legacy/candidate；`metadata-config-module` 和对应 Skill 保留。
- 旧 chat-first 文档可标记历史，但不能据此删除 chat package、widget、mobile 或 `/c/:id` 深链。
- 先做分类、勘误和索引，再做物理移动；移动前必须建立链接映射或兼容入口。
- 术语表必须从 Navigator 视角定义 upstream user，并区分 Navigator user、ClientApp、external subject、Worker、LLM Provider、Agent Provider、BusinessFunction 和 Skill。

### 验证与回滚门禁

- Markdown 相对链接、锚点、Skill reference/script/path 全部存在。
- 当前文档关键词命中逐项人工分类；历史证据允许保留旧术语，不能追求机械零命中。
- 删除 Skill 前检查触发词、脚本、README、CI 和外部交付引用；回滚通过独立提交恢复，并修正导航/索引。

### Owner 评审

- decision_owner: Project Owner；实施复核为 Documentation + Product/Architecture + Skill/module owners
- proposed_result: approve-classification-policy
- review_result: approved
- review_date: `2026-07-14`
- rationale_or_constraints: 按当前/派生/历史证据/删除候选分级治理；物理删除仍保留引用扫描、替代和独立回滚。

## Owner 决策记录

Project Owner 已在当前项目会话中明确确认产品阶段、优先级、删除授权和其余建议。下表只记录该正式授权及实施约束；不虚构模块、安全或构建 Owner 的姓名。后续角色复核写入各 workitem 的 execution check-in 和 evidence。

| ID | review_result | 实施复核 | Recorded authorization（主体 / 角色 / 日期） | 约束或记录 |
|---|---|---|---|---|
| ODR-142-001 | approved | Build、Frontend、相关 Worker lane | Project Owner / decision authority / `2026-07-14` | 精确工具版本、单根 lockfile、CI 分层 |
| ODR-142-002 | approved-with-constraints | ClientApp/Upstream、Security、Platform | Project Owner / decision authority / `2026-07-14` | assertion 降优先级；external 显式、默认关闭 |
| ODR-142-003 | approved | Business Agent、Worker Gateway、Security、Operations | Project Owner / decision authority / `2026-07-14` | token scope、TTL、撤销、轮换、Worker lease |
| ODR-142-004 | approved-with-constraints | Codex/LangGraph Worker、Platform、Security | Project Owner / decision authority / `2026-07-14` | 外部模式门禁未齐前不得打开 |
| ODR-142-005 | approved | Security、Operations/SRE、Business Agent | Project Owner / decision authority / `2026-07-14` | 分级可靠审计 |
| ODR-142-006 | approved-with-constraints | Platform、各切片 owner | Project Owner / decision authority / `2026-07-14` | dev-only 安全后物理清理，数据可丢弃 |
| ODR-142-006-MON | approved-with-constraints | Platform、Observability | Project Owner / decision authority / `2026-07-14` | 完整切片、dev-only |
| ODR-142-006-MQ | approved-with-constraints | Platform、Metadata | Project Owner / decision authority / `2026-07-14` | 保护 metadata-config、dev-only |
| ODR-142-006-CR | approved-with-constraints | Platform、GitLab/Integration | Project Owner / decision authority / `2026-07-14` | 发现活跃外部资源则停止 |
| ODR-142-006-ECHO | approved-with-constraints | Provider、Test、Platform | Project Owner / decision authority / `2026-07-14` | 先迁移 fixture；保留同名非 addon adapter |
| ODR-142-007 | approved-with-constraints | API/SDK、Provider、仓内消费者 | Project Owner / decision authority / `2026-07-14` | 无外部窗口；仓内迁移和 clean build 是硬门 |
| ODR-142-008 | approved | Product/Architecture、Documentation、相关 Skill owner | Project Owner / decision authority / `2026-07-14` | 引用扫描和独立回滚仍保留 |

## 评审后回写规则

1. 已同步本文 `review_result` 和决策表；继续同步 [Implementation Plan](./implementation-plan.md) 的阶段门禁与 [Progress](./progress.md) 的 Decision Register。
2. 批准只解除相应设计门禁，不把 workitem、测试或验收状态改为完成。
3. ODR-142-006 的物理删除授权仅适用于已确认的 dev 环境；外部资源动作必须记录精确目标，发现共享/生产资源时停止并重新决策。
4. ODR-142-007 的物理删除按 route/SPI/DTO 独立记录；Owner 取消外部兼容窗口，但没有取消仓内消费者迁移和构建测试。
5. 实施中发现新消费者、契约差距或风险时，将对应 ODR 状态改为 `revise-required` 或 `deferred`，并保留原评审记录。
