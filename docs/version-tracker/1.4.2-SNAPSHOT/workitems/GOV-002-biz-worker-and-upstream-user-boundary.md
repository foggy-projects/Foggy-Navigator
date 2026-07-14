---
type: governance
version: 1.4.2-SNAPSHOT
ticket: GOV-002
priority: high
status: in-progress
source: REQ-001
owner: business-agent-owner | clientapp-owner | provider-owner | security-owner
---

# Biz Worker、ClientApp 与 upstream user 边界治理

## 文档关联

- 版本索引：[1.4.2-SNAPSHOT](../README.md)
- 总需求：[REQ-001 平台治理与历史能力收口](../requirements/REQ-001-platform-governance-and-legacy-cleanup.md)
- 实施阶段：[P2 外部 Biz Worker 与 upstream user 边界治理](../implementation-plan.md#p2外部-biz-worker-与-upstream-user-边界治理)
- Owner 决策：[ODR-142-002 至 ODR-142-005、ODR-142-007 决策记录](../owner-decision-review.md)
- 状态与证据：[Progress](../progress.md)
- 上位边界：[GOV-001 内外部信任边界](./GOV-001-internal-external-trust-boundary.md)
- 相关归属：[GOV-003 Session/Task 资源归属](./GOV-003-session-task-resource-ownership.md)
- 代码与职责：[Code Inventory](../code-inventory.md)、[Module Responsibility](../module-responsibility.md)

## 当前状态

| 项目 | 状态 | 说明 |
|---|---|---|
| Workitem | in-progress | 2026-07-14 Owner 已冻结当前 dev/internal 方案与未来外部开放门禁；P2 第一批已完成 external gate、task capability、Worker credential schema/API、pool/identity route、definitive terminal tombstone 和 audit writer 隔离 |
| Implementation | partial | implementation_started: yes；`EXEC-142-012` 已实施 Worker credential v1 schema/API、owner-scoped pool/identity 与 LangGraph governed route、持久化 definitive terminal tombstone、Claude tenant 持久化及 best-effort audit 独立事务；Gateway principal/lease、pause/generation、可靠审计 outbox 和 L3 仍未完成 |
| Automated test | partial-passed | `EXEC-142-012` 最终 11/11 reactor clean test、2186 tests 通过；Business Agent integration TypeScript typecheck 通过；credential/terminal/Claude tenant 三组迁移在 MySQL 8.0.44/8.4.8 完成 forward×2、rollback×2、reapply；真实 L3 未运行 |
| Manual verification | not-run | 未执行双 tenant/ClientApp/upstream user/task/function 矩阵 |
| Experience verification | not-run | 未检查外部配置、错误提示或审批恢复体验 |
| Production routing | unchanged | production_routing_changed: no |
| External contract | scoped-change | 新增显式关闭/未就绪错误语义，并在当前工作树落地 capability/terminal fail-closed 与 Worker credential 管理契约；严格 Worker credential 尚未接入 Gateway principal，external-enabled 仍关闭且 unready，没有生产批准 |

本文已回写 `EXEC-142-008`、`EXEC-142-011` 和 `EXEC-142-012`。所有迁移脚本只在一次性 MySQL 8.0.44/8.4.8 容器执行，没有操作项目共享数据库；没有执行真实 L3、凭据流量、外部部署、浏览器验证或生产路由变更。

## 目标

建立可验证的外部调用链：

`credential principal -> tenant -> ClientApp -> upstream user mapping/grant -> Agent/Skill/Model/Workspace -> BusinessTask -> task-scoped token -> Worker Gateway -> allowed BusinessFunction -> audit`

具体目标：

1. 外部 LangBizWorker、CodexBizWorker、Worker Gateway 请求可追溯到 tenant、ClientApp、upstream user 和具体任务。
2. credential/token 具备明确 scope、有效期、撤销、轮换和终态失效语义。
3. task-scoped token 只能访问绑定任务以及该任务允许的 BusinessFunction，不能横向访问其他 task/session/function。
4. 审批、恢复、拒绝和取消同时绑定可信调用主体、任务归属、当前状态和允许动作。
5. 外部模式下非 loopback Worker 缺少必要凭据时 fail closed 或 unready。
6. 外部触发执行受服务端确定的 workspace、directory、tool、function、sandbox、approval 和 network 上限约束。
7. 调用、拒绝、暂停、审批、恢复、取消和失败形成不泄露明文 token 的审计链。

## 范围

- ClientApp runtime credential、control credential 和 upstream user grant。
- Claude Open API 中的 Agent、task、session、Business Agent 入口。
- BusinessTask/task-scoped token 的签发、绑定、解析、撤销、轮换、终态失效和运行时注入。
- Worker Gateway 的函数列表、schema、invoke、tool message、暂停和恢复。
- LangGraph Biz Worker、Codex Biz Worker 的认证、readiness、任务启动和执行策略。
- Open SDK 与本仓 PC、Mobile、CLI、L3、Worker 调用方的契约迁移和错误语义。
- 旧 LangGraph/Claude/Codex Provider API、deprecated SPI/DTO 的本仓消费者迁移与同版本删除。

## 非目标

1. 不实现通用 IAM、RBAC/ABAC 或全平台 Spring Security 重构。
2. 不一刀切关闭 loopback/internal-dev Worker。
3. 不在本工作项实现动态插件或多实例 SSE 事件总线。
4. 不为尚未投产的旧 Provider API、deprecated SPI/DTO 建立生产兼容窗口；但删除前仍须完成本仓 PC、Mobile、SDK、CLI、L3、Worker、canary 消费者迁移、安全语义复核和 clean build。
5. 不把所有外部任务强制成同一种 Provider、sandbox 或审批策略；只冻结服务端安全上限。
6. 不把隔离 smoke 等同于 production enablement。

## 目标不变量

| 对象/动作 | 必须满足的不变量 |
|---|---|
| runtime token | 由有效 ClientApp credential 签发；绑定 tenant/ClientApp/scope；有 TTL、状态、撤销与轮换语义 |
| upstream user | 当前 dev/internal 由已认证 ClientApp 代办，并以 tenant + ClientApp mapping/grant 解析；审计保证级别标记为 `client-app-delegated`；请求字段不能自行提升身份。未来真正 external-enabled 前必须补 signed assertion 或等强证明 |
| BusinessTask | 服务端绑定 tenant、ClientApp、upstream user、Agent/skill/model/workspace 和 session/task |
| task-scoped token | 版本化；绑定 task/session/worker pool；限定允许函数；过期、撤销、任务终态后不可用 |
| Worker Gateway | 每次调用从 token 解析上下文；不能用请求体替换 tenant/user/task；函数必须在 token 与 ClientApp 授权交集中 |
| 审批/恢复/取消 | principal、tenant、ClientApp、upstream user、task/session、function、当前状态均匹配；actor 从 credential 派生 |
| external Worker | 三类 Worker 已实施各自单一显式开关且默认 `false`；当前 external-enabled 因执行策略未就绪始终 unready，空 Token 叠加认证缺失 reason，业务 API fail closed |
| 执行策略 | workspace/allowed dirs/tools/functions/sandbox/network 上限由服务端可信上下文决定，caller 只能在上限内收窄 |
| 审计 | 成功、拒绝、暂停、审批、恢复、取消、失败可按 tenant/ClientApp/user/task/function 查询，不保存明文 token |

## 已确认事实

1. ClientApp runtime token 已采用短期访问凭据，解析器会检查 credential/token 的状态、撤销和过期，并以 token hash 进行持久化比对。
2. ClientApp control credential 已定义 scope 并从 credential 解析 principal，而不是直接采用审批请求体中的 actor。
3. upstream user grant、ClientApp skill grant、model grant 和函数 grant 已有服务实现；Open API 签发任务上下文时会执行多项 grant 检查。
4. BusinessTask token 已绑定 tenant、ClientApp、upstream user、skill、task、session、model 和 worker pool 等字段，数据库保存 token hash。
5. Worker Gateway 在函数 schema/invoke 前会解析 task token，并执行 ClientApp、upstream user、skill 和 function grant 校验。
6. 新审批/恢复服务持久化 suspension binding；`approvedBy` 从 control credential principal 派生，请求体中的同名字段不是最终可信 actor。
7. LangGraph 执行策略已有工作目录、允许目录和工具校验；Codex Biz launcher 已注入 tenant、ClientApp、upstream user、workspace、allowed dirs/tools 等服务端运行上下文。
8. BusinessFunction runtime audit 已覆盖 invoke、suspend、success、failed、tool message 和 resume 生命周期的多类事件。
9. 当前工作树已实现 task capability v2 首个切片：新签发 token 固定 `tokenVersion=2`、`generation=1`、`audience=WORKER_GATEWAY`、`identityAssurance=client-app-delegated`，并持久化结构化 `{functionId, version}` JSON 快照、签发/过期和撤销字段。
10. 新签发 token 使用 32 字节 `SecureRandom` 生成的 `btt_` Base64URL 形式，数据库继续只保存 SHA-256 hash；默认 TTL 30 分钟且实现硬上限 60 分钟。
11. `BusinessAgentTaskService` 已提供单 token 撤销和按 tenant/task 批量撤销服务，并按 hash 条件清理 business-task、worker-task、worker-session runtime aliases；definitive terminal 已通过持久化 tombstone 接线，pause/suspension、cancel 和 resume generation 仍未接入。
12. Codex Business Agent launcher 已改走 `CodexBizTaskProvider.createTaskDirect`，从而固定 `codex-biz-worker` Provider route，不再误入默认 `codex-worker` 创建端口。
13. Worker credential v1 已增加乐观版本、credential version、签发/过期/撤销/轮换时间；平台 `SUPER_ADMIN` 和 upstream `WORKER_MANAGE` 可 owner-scoped rotate/revoke，服务端只在 rotate 响应一次性返回 `bwc_` 明文，数据库只保存 SHA-256。该服务尚无 Gateway 调用方，不能表述为 Worker principal 已完成。
14. pool/identity 运行时不变量已收紧：全局 workerId 不得跨 owner/backend 重注册，pool 操作绑定 tenant + owner，成员和路由重验 enabled/healthy/backend/可见性；LangGraph Business Agent route 只采用受治理 identity endpoint，同名 legacy 不得覆盖。
15. `business_task_terminal_state` 已成为 definitive terminal 的持久化授权 tombstone；Provider 仅对显式 `recoverable=false` 事件写入，late-bind 会持久化 worker tuple、撤销 token 并补全 marker 后抛专用异常，Gateway resolve 即使面对错误物理重开也由 tombstone fail closed。
16. Claude task 已持久化可信 `tenantId`，并为 legacy 记录提供 entity、Session、dedicated Worker 的有界回填；tenant 仍无法解析时不发布空 tenant terminal 事件。
17. `BusinessFunctionRuntimeAuditWriter` 使用独立 bean 的 `REQUIRES_NEW + saveAndFlush`；外层捕获 commit/flush 失败，best-effort telemetry 不再回滚主业务。关键审批、拒绝和远程副作用仍未进入可靠 outbox。

## 静态搜索结论

### upstream user 证明

- `OpenApiController` 从外部请求头解析 upstream user，再通过 ClientApp grant 约束 token 签发。
- 当前语义更接近“已认证 ClientApp 代办一个已登记 upstream user”，而不是 upstream user 自身的加密签名认证。
- 2026-07-14 Owner 已冻结阶段性模型：当前 dev/internal 使用 ClientApp credential + tenant/ClientApp/upstream user mapping/grant，审计保证级别必须标记为 `client-app-delegated`；signed assertion 下放为未来真正外部开放的启用门禁，不阻塞当前 P2 的其他治理。
- 当前模型不应表述为“完全未校验”，也不能表述为“upstream user 已具备独立强证明”；显式外部开关和未就绪门禁已落地，signed assertion 仍未实施且不阻塞 dev/internal。

### Open API 资源隔离

- 通用 task status、diagnostics、evidence、messages 的静态校验主要是 `tenantId + agentId + taskId`，未统一绑定当前 ClientApp/upstream user。
- 通用 Agent session 列表和消息通过 Agent owner userId 查询；与按 tenant/ClientApp/upstream user 过滤的 Business Agent 专用 session 路径并存。
- 因此存在跨 ClientApp/upstream user 隔离谓词不一致，需要负向测试和统一门面；静态扫描本身不证明生产可利用。

### task-scoped token 与函数 scope

- `BusinessTaskScopedTokenEntity` 当前工作树已增加 v2 capability claims：`tokenVersion`、`generation`、`audience`、`identityAssurance`、`functionScopeJson`、`workerId`、`workerLeaseId`、`issuedAt`、`revokedAt`、`revokedBy`、`revokeReason`。
- `BusinessTaskScopedTokenPolicyService` 在签发时快照当时 ENABLED 的 ClientApp function grants，编码为排序后的结构化 `{functionId, version}` JSON 列表；Gateway list/schema/invoke 现在以精确字段对约束当前可见/可执行函数，并继续经过现有 ClientApp、user、skill、function grant 校验。
- Gateway 对 v1、错误 audience、错误 assurance、非法 generation 或缺失/畸形 function scope fail closed；迁移脚本把旧行回填为 `v1/LEGACY/legacy-unverified/[]`，因此旧 token 不会被 Gateway v2 接受。脚本已在一次性 MySQL 8.0.44/8.4.8 验证，但尚未部署到项目数据库。
- `BusinessFunctionAuthorizationService` 明确将 SkillFunctionAllowlist 作为 materialization/recommendation 提示，而不是运行时硬门禁。
- `workerId` 已能在 Business Agent launcher 成功后落到 token；Worker credential v1 已提供 owner-scoped rotate/revoke 和严格校验服务，但 `workerLeaseId` 仍只是 nullable schema 预留，Gateway 尚未消费独立 Worker principal/credential、lease 或 PoP，不能把 Worker 绑定标记完成。
- `generation` 当前固定为 1，尚无 resume/重调度轮换；definitive terminal 已通过持久化 tombstone fail closed，并以物理 `REVOKED` 作为可重试 materialization。pause/suspension、cancel 与 generation 轮换仍未闭合，不能把完整 token 生命周期标记完成。
- `BusinessAgentTaskScopedTokenRuntimeStore` 仍使用单 JVM 内存缓存保存运行时明文 token；v2 只允许 `tenant + session + task` 精确键，缺少 taskId 不再退化为 session token；hash-conditional removal 可避免撤销旧 token 时删除同 task 的新 token，但重启、多实例恢复仍未解决。

### 审批、恢复与旧接口

- 新 `BusinessFunctionApprovalController` + `BusinessFunctionSuspensionService` 会校验 control principal 和持久化 binding，并在执行前再次校验。
- 旧 `LanggraphTaskController` 没有相同的 credential/binding 语义：GET 接受 `userId` 参数；approve 仅按 taskId + form；`LanggraphTaskService` 使用 `form.reviewedBy` 写审批记录并异步恢复 Worker。
- 旧链路直接对应“不能只凭 taskId”和“不能信任 reviewedBy”的迁移需求。项目尚未生产、上游均在本机共同孵化，因此无需外部客户兼容期；完成本仓 PC、Mobile、SDK、CLI、L3、Worker、canary 消费者迁移、安全语义复核和 clean build 后，可在 1.4.2 同版本物理删除。

### Worker 与执行策略

- LangGraph Worker 默认 host 为 `0.0.0.0`、internal-dev Token 为空时跳过认证的开发行为保留；新 health 已显示 mode、external/auth readiness 及非敏感 reasons，但这不替代可信网络/ACL。
- Codex SDK/app-server Worker 亦保留 internal-dev 既有行为；显式 external-enabled 当前始终 unready，除精确 `/health` 外的业务 API 返回 `EXTERNAL_WORKER_UNREADY`。`/health/` 不是豁免路径。
- LangGraph hidden runtime policy 会优先保留服务端 workspace/allowed dirs；显式空 `allowed_tools=[]` 会拒绝全部，但缺省未提供/`None` 的语义可能允许全部工具，external-enabled 必须区分并固定。
- Codex Biz 默认 sandbox/approval 策略较宽，Open API form 允许 caller 传 sandbox、approval、network、web 等选项；external-enabled 需要服务端上限。

### credential 与审计生命周期

- task token 已增加单 token/按 task 批量撤销 service 与撤销字段，definitive terminal tombstone 已接入；pause/suspension、cancel、generation 轮换和跨实例恢复仍待实现。
- Worker credential v1 已有平台/upstream owner-scoped rotate/revoke API、TTL 和严格 credential 校验服务，但 Gateway 尚未解析该 principal，也没有 lease/preselect/prebind 或 credential 生命周期审计；这只是 schema/API 切片。
- `ClientAppUserGrantService` 会保存和解析 upstream user token；主源码中未见应用层加密包装，数据库/TDE/密钥管理需基础设施确认。
- runtime audit writer 已通过独立 `REQUIRES_NEW + saveAndFlush` 隔离 best-effort 写失败，避免回滚主业务；Worker Gateway 的部分授权拒绝仍发生在 invoke audit 之前，关键审批/拒绝/远程副作用没有可靠 outbox，不能宣称审计闭环。

## 运行态待证

| 待证项 | 验证方式 | 门禁 |
|---|---|---|
| ClientApp/upstream user mapping/grant 的唯一性和生命周期 | 本地配置、grant 数据、账号生命周期与负向测试 | 不阻塞 dev/internal；未形成证据前 `external-enabled` 保持 `false` |
| runtime/task token 的撤销、轮换、过期传播 | 单 token 与按 task 批量撤销及 hash-conditional alias cleanup 已有定向测试；仍需 API/DB/缓存演练，含重启、多实例和 generation 轮换 | 只登记首个撤销切片，不宣称 token 生命周期闭环 |
| task terminal/cancel 后 token 是否立即失效 | definitive terminal tombstone、late-bind 撤销和错误物理重开均有自动化证据；cancel、pause/suspension 和 generation 仍需创建任务后的重放矩阵 | definitive terminal 为 partial-passed；完整 AC-05 未通过 |
| 同 tenant/Agent 下跨 ClientApp/user 读取 | 双 ClientApp/user task/session 负向矩阵 | 未通过前外部隔离不可签收 |
| 旧 LangGraph/Claude/Codex API 本仓消费者 | PC、Mobile、SDK、CLI、L3、Worker、canary 的静态引用、契约测试和 clean build | 未完成迁移与验证前不得删除；不要求外部客户流量或静默窗口 |
| 非 loopback 与空 Token 的实际部署 | 环境变量、启动参数、监听地址、网络策略与 readiness 响应 | 本地契约门禁已通过，真实网络部署未验证；external enablement 保持未批准 |
| allowed dirs/tools/sandbox/network 的实际配置 | 任务运行上下文、Worker 日志、越界测试 | 未通过前不对外启用高权限模式 |
| 审计表、留存、拒绝事件和查询能力 | schema、索引、数据样本、失败注入、告警 | AC-02/03 不得只凭日志签收 |
| upstream user token 的静态/传输/存储保护 | DB/TDE、备份、日志脱敏、密钥轮换 | 未确认前登记安全风险 |

## 已批准决策与执行状态

| 决策 | 2026-07-14 已批准基线 | Owner | 当前执行状态 |
|---|---|---|---|
| upstream user 证明 | ODR-142-002 已批准：当前 dev/internal 使用 ClientApp credential + mapping/grant，审计标记 `client-app-delegated`；signed assertion 是未来真正外部开放门禁 | ClientApp/Upstream/Security | 不阻塞当前 P2；门禁未齐时 `external-enabled=false` |
| task function scope | ODR-142-003：Gateway capability + 明确 `BusinessFunctionId@version`/policy snapshot，并与 tenant/ClientApp、subject mapping/user、skill、function grant 及 task/session/lease 当前状态求交集 | Business Agent/Security | v2 首切片已快照 ClientApp ENABLED function grants，并在 list/schema/invoke 与当前授权求交；精确按任务最小能力选择、旧 token 迁移和 lease 交集仍待实施 |
| token 生命周期 | ODR-142-003：30 分钟租约、上限 60 分钟；暂停/终态失效；支持人工/批量撤销和 generation 轮换 | Business Agent/Operations | TTL、单 token/按 task 撤销、runtime alias cleanup 与 definitive terminal tombstone 已实施；pause/suspension、cancel、可信管理入口、generation 轮换和跨实例恢复未实施，external enablement 不批准 |
| Worker 绑定 | ODR-142-003：task token 绑定逻辑 lease，Gateway 还须校验独立 Worker principal/credential 或 PoP；重调度签发新 generation 并撤销旧 token | Worker/Platform | Worker credential v1 schema/API 与 owner-scoped rotate/revoke 已实施；Gateway 未消费 strict principal，lease/preselect/prebind/PoP 仍未实现，跨 Worker 重放风险保持开放 |
| 外部 Codex/LangGraph 安全上限 | ODR-142-004：双模式、默认拒绝、`workspace-write`、任务工具 egress 默认拒绝并保留控制面/LLM 基础 allowlist、非 loopback 缺凭据 unready/fail closed | Worker/Security | 显式开关和 unready/fail-closed 骨架已实施；完整 workspace/tool/sandbox/network 安全上限未实施，external 保持未启用 |
| 旧 Provider API、deprecated SPI/DTO | ODR-142-007 已批准：不设生产或外部兼容窗口；本仓消费者迁移、安全语义复核和 clean build 后在 1.4.2 同版本删除 | Provider/API/SDK owner | 删除前不扩大消费者；运行中任务状态按版本化迁移规则收口 |
| 审计保证级别 | ODR-142-005：本地关键状态事务 outbox；无状态拒绝可靠落档；远程调用意图/结果分段记录；高频遥测 best-effort | Security/Operations | best-effort writer 已完成事务隔离；关键拒绝、审批和外部副作用 outbox 未实现，不宣称审计完备 |

### 仍待实施级 Owner 决策

| 决策 | Owner | 最晚时间 | 安全默认值 |
|---|---|---|---|
| Open API 收敛采用“补 ClientApp/user binding”还是“仓内调用方迁移到 Business Agent 专用 API” | API/SDK owner | P2 identity 或 P6 旧 API 删除切片设计前 | `/api/v1/open` 保持默认关闭；不新增兼容层，不在现有谓词上扩大访问 |
| upstream user token 存储采用应用层加密、外部 secret store 或 DB/TDE 的具体组合 | Security/DB owner | 触碰 token 存储 schema/部署配置前 | 不改变现有存储，不宣称风险关闭；日志和文档不得暴露 token 明文 |

## 关键代码路径

### ClientApp、Open API 与 upstream user

- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/config/ExternalSurfaceProperties.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/filter/ExternalSurfaceGateFilter.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/health/ExternalSurfaceHealthController.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiController.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/model/form/OpenApiQueryForm.java`
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/ClientAppRuntimeCredentialResolver.java`
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/ClientAppControlCredentialService.java`
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/ClientAppUserGrantService.java`

### BusinessTask、Gateway、函数与审计

- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/config/BusinessTaskScopedTokenProperties.java`
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/model/dto/BusinessTaskScopedTokenDTO.java`
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/model/entity/BusinessTaskScopedTokenEntity.java`
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/repository/BusinessTaskScopedTokenRepository.java`
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskService.java`
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskScopedTokenRuntimeStore.java`
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessTaskScopedTokenLifecycleService.java`
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessTaskScopedTokenPolicyService.java`
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/model/entity/BizWorkerIdentityEntity.java`
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BizWorkerCredentialService.java`
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/controller/BizWorkerCredentialController.java`
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/controller/UpstreamAdminWorkerCredentialController.java`
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BizWorkerPoolService.java`
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/model/entity/BusinessTaskTerminalStateEntity.java`
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/event/BusinessTaskScopedTokenTerminalListener.java`
- `docs/migration/2026-07-14-business-task-token-v2.sql`
- `docs/migration/2026-07-14-business-task-token-v2-rollback.sql`
- `docs/migration/2026-07-14-biz-worker-credential-v1.sql`
- `docs/migration/2026-07-14-biz-worker-credential-v1-rollback.sql`
- `docs/migration/2026-07-14-business-task-terminal-state.sql`
- `docs/migration/2026-07-14-business-task-terminal-state-rollback.sql`
- `docs/migration/2026-07-14-claude-task-tenant.sql`
- `docs/migration/2026-07-14-claude-task-tenant-rollback.sql`
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/controller/WorkerGatewayController.java`
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/WorkerGatewayService.java`
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessFunctionAuthorizationService.java`
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/controller/BusinessFunctionApprovalController.java`
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessFunctionSuspensionService.java`
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessFunctionRuntimeAuditService.java`
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessFunctionRuntimeAuditWriter.java`
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/model/entity/BusinessFunctionRuntimeAuditEntity.java`

### LangGraph/Codex Biz Worker

- `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/controller/LanggraphTaskController.java`
- `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/model/form/ApproveTaskForm.java`
- `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphTaskService.java`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/config.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/auth.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/external_mode.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/routes/health.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/execution_policy.py`
- `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/model/dto/LanggraphWorkerHealthDTO.java`
- `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphWorkerService.java`
- `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphBusinessAgentWorkerTaskLauncher.java`
- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexBizTaskProvider.java`
- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexBusinessAgentWorkerTaskLauncher.java`
- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexSdkBackendConnectionTester.java`
- `tools/codex-agent-worker/src/config.ts`
- `tools/codex-agent-worker/src/auth.ts`
- `tools/codex-agent-worker/src/external-mode.ts`
- `tools/codex-agent-worker/src/routes/health.ts`
- `tools/codex-agent-worker/src/business-mcp/navigator-business-mcp-server.ts`
- `tools/codex-app-server-worker/src/config.ts`
- `tools/codex-app-server-worker/src/external-mode.ts`
- `tools/codex-app-server-worker/src/routes/health.ts`

## 本批实施 Check-in（`EXEC-142-008`）

### 已完成内容

1. 提交 `12cbe697` 实施平台 `NAVIGATOR_EXTERNAL_ENABLED`，默认 `false`；仅规范化后的 `/api/v1/open` 及其子路径受控，关闭时返回 HTTP 503 + `EXTERNAL_SURFACE_DISABLED`。matrix/context/encoded 回归曾发现路径规范化绕过，已修复并纳入自动化测试。
2. `/api/v1/health/external-surface` 对外提供不含敏感信息的路由面状态；`surfaceReady` 只说明 Open API routing gate 是否打开，不代表 Provider ready 或 production ready。
3. 提交 `5d62707b` 实施三类 Worker 的严格布尔开关：`BIZ_WORKER_EXTERNAL_ENABLED`、`CODEX_WORKER_EXTERNAL_ENABLED`、`CODEX_APP_SERVER_EXTERNAL_ENABLED`，仅接受 `true` / `false`，默认 `false`，mode 为 `internal-dev` / `external-enabled`。
4. external-enabled 当前因 `EXTERNAL_EXECUTION_POLICY_PENDING` 始终 unready；空 Token 时叠加 `EXTERNAL_AUTH_TOKEN_REQUIRED`。除精确 `/health` 外，业务 API 返回 HTTP 503 + `EXTERNAL_WORKER_UNREADY`；调用方应使用规范路径 `/health`，`/health/` 会进入外部业务门禁。
5. 提交 `cce75f1b` 让平台消费 Worker `ready=false`：LangGraph 转为 `OFFLINE`，Codex SDK connection tester 转为 unready；对没有 `ready` 字段的旧 Worker 保留 HTTP 200 兼容。

### 实施边界与剩余风险

- 平台开关不覆盖 upstream-admin、Worker Gateway 的 `/internal/worker-gateway/v1/**` 或其他内部 Controller；这些入口仍须按 task token、principal 和资源绑定独立治理。
- internal-dev 不是网络防火墙。LangGraph/Codex SDK 默认 `0.0.0.0` 且空 Token 跳过 bearer 的开发行为保留，必须使用 loopback 或可信网络/ACL 隔离。
- task-scoped token 函数 scope/TTL/终态失效/撤销/轮换、upstream identity authority、审批/恢复/取消主体绑定和审计 outbox 尚未实施。
- production routing 未变更，external-enabled 未启用；本批只提供显式关闭/未就绪契约，不是真正外部开放或生产批准。

### 自检与测试证据

- `EXEC-142-008` 当时的 Java 三模块定向矩阵：74 tests，10/10 reactor SUCCESS；平台门禁 8 项 + 当时 Open API mapping 40 项，平台批次合计 48 项。后续 capability 修复后的 Open API 43 项证据见下方 task capability check-in。
- Codex SDK Worker：163 passed / 1 skipped，type-check 和 build 通过。
- Codex app-server Worker：272 passed / 1 skipped，type-check 和 build 通过。
- LangGraph Biz Worker：766 passed，build 通过。
- self_check_decision: `continue-in-progress`；本批只达到实施 check-in，未执行真实外部网络、手工 ClientApp 链路、浏览器体验或正式质量闸门。

## Task capability v2 与 Codex Biz route 实施 Check-in（当前工作树）

### 已实施事实

1. `BusinessTaskScopedTokenPolicyService` 统一初始化新 token：版本 `2`、generation `1`、audience `WORKER_GATEWAY`、assurance `client-app-delegated`，并快照签发时 ENABLED 的 ClientApp function grants 为排序后的结构化 `{functionId, version}` JSON 列表，避免字符串拼接碰撞。
2. `BusinessAgentTaskService.createTask` 与 `issueOpenApiTaskScopedToken` 均改用 32 字节 `SecureRandom` + Base64URL 的 `btt_` token，并调用统一 policy initializer；数据库继续只保存 token hash。
3. `BusinessTaskScopedTokenLifecycleService` 以 `REQUIRES_NEW` 先提交 token 再启动 Worker，并从明文自行计算 hash；bind/revoke 使用悲观写锁与实体版本，task/session/worker 首次绑定后不可改写；runtime alias 只在事务提交后注册/移除。launcher、Open API submit/null task/bind 失败或外层任务事务回滚时，会在独立事务撤销已签发 token。
4. 已增加单 token 撤销和 `tenantId + taskId` 批量撤销 service，记录 `REVOKED`、`revokedAt`、`revokedBy`、`revokeReason`，并清理 business-task、worker-task、worker-session aliases；重复撤销保持幂等。
5. `WorkerGatewayService` 对 v2 version/generation/audience/assurance/function scope fail closed；list 过滤到 token snapshot，schema/invoke 要求函数同时存在于 token snapshot 和当前授权链。
6. launcher 配置新增 `NAVIGATOR_TASK_TOKEN_TTL`，默认 `PT30M`；`NAVIGATOR_TASK_TOKEN_MAX_TTL` 默认且硬封顶 `PT60M`，非正配置回退安全默认值。
7. `CodexBusinessAgentWorkerTaskLauncher` 改为调用 `CodexBizTaskProvider.createTaskDirect`，由 Provider 固定 `codex-biz-worker` route 并执行 Biz 参数规范化；不再调用固定默认 `codex-worker` 的 `CodexTaskService.createTaskDirect`。

### Schema 变化

| 字段 | 约束/语义 | 当前状态 |
|---|---|---|
| `rowVersion` | JPA `@Version` | 实现与悲观写锁共同保护 bind/revoke；现有 H2 JPA 用例只提供组合时序下的最终状态证据，不证明确定性的锁交错 |
| `tokenVersion`、`generation` | 非空；新签发分别为 `2`、`1` | 已写入并由 Gateway 校验；forward/rollback 脚本已在一次性 MySQL 8.0.44/8.4.8 容器验证（含幂等、旧行 fail-closed 回填和回滚），共享/项目数据库迁移和 launcher `ddl-auto=validate` 尚未执行；generation 轮换待办 |
| `audience`、`identityAssurance` | 非空，长度 64；当前为 `WORKER_GATEWAY`、`client-app-delegated` | 已写入并 fail closed 校验；不等于 upstream user 独立强证明 |
| `functionScopeJson` | 非空 LOB；JSON 对象数组，元素为 `{functionId, version}` | 已用于 list/schema/invoke 精确字段匹配；当前快照来源是签发时 ClientApp ENABLED grants，不是 per-intent 最小函数选择 |
| `workerId`、`workerLeaseId` | nullable，长度 128 | `workerId` 可在 launcher 成功后写入；`workerLeaseId` 仅预留，未签发/校验 |
| `issuedAt`、`expiresAt` | 非空 | 默认 30 分钟，硬上限 60 分钟 |
| `revokedAt`、`revokedBy`、`revokeReason` | nullable；actor 128、reason 512 | 单 token/按 task 批量撤销已写入；可信控制面入口与审计 outbox 待办 |

已登记 `docs/migration/2026-07-14-business-task-token-v2.sql` 与对应 rollback：前向脚本幂等增加 12 个字段，将旧行回填为 Gateway 必然拒绝的 legacy claims，并补齐非空约束；rollback 先撤销 ACTIVE token，再删除 v2 scope/lifecycle 字段，禁止旧代码把原 scoped token 当宽 token 接受。forward/rollback 脚本已在一次性 MySQL 8.0.44 与 8.4.8 容器验证（含第二次重复执行、旧行回填、撤销和回滚检查）；共享/项目数据库迁移和 launcher `ddl-auto=validate` 尚未执行。

### 定向测试证据

- task capability v2 首轮定向矩阵发现 4 处旧测试夹具与新 v2 claims/function snapshot 契约不一致；修正夹具后既有矩阵 73 tests 通过。该结论是测试基线修正，不把 4 个夹具问题记为生产缺陷。
- 随后新增单 token/按 task/按明文撤销、hash-conditional alias cleanup、安全随机 token、plain/hash 不变量、绑定 tuple 不可变、launcher/Open API/外层回滚补偿、无 taskId fail-closed，以及 bind/revoke 组合时序下最终不复活的测试。
- 最终 `mvn -B -pl business-agent-module -am test`：5/5 reactor SUCCESS，770 tests、0 failure/error/skip；其中 `business-agent-module` 510 tests。真实 H2 JPA 用例 2/2 提供 `REQUIRES_NEW` + rollback compensation 及 bind/revoke 组合时序下 token 最终不复活的证据；该用例不声称确定性复现或证明悲观锁交错。
- 修复后跨模块定向矩阵为 Open API mapping 43 + LangGraph Business Agent E2E 2 + Codex launcher/route、`CodexBizTaskProvider`、`CodexTaskService` 92，共 137 tests、10/10 reactor SUCCESS。Open API 子集覆盖 submit 已知/意外异常、空 task/taskId、bind 失败，以及补偿撤销自身失败不遮蔽原响应或异常。
- forward/rollback 脚本已在一次性 MySQL 8.0.44/8.4.8 容器验证（含幂等及回滚前撤销 ACTIVE token）；共享/项目数据库迁移、launcher `ddl-auto=validate`、真实 Worker、双 ClientApp/user 手工矩阵、浏览器体验、hosted CI 和正式验收均未执行。

### 明确保留的未完成项

- `workerLeaseId` 没有签发，Gateway 没有独立 Worker principal/credential、lease 或 PoP 校验。
- generation 没有在 resume/重调度时递增；旧 generation 没有自动撤销。
- definitive terminal 已由后续 `EXEC-142-012` tombstone 接线；单 token/批量撤销尚未接入 pause/suspension、cancel、resume generation 和审批恢复状态机，也没有可信管理 Controller。
- runtime store 仍是单 JVM 内存态；重启、多实例恢复和跨实例撤销传播未解决。
- tool message 只校验 v2 token 基础 claims，尚未形成与具体 `functionId@version` 一致的完整能力校验。
- upstream user runtime principal、Open API task/session ownership、关键状态 outbox/可靠拒绝审计和 P3 Session/Task ownership 均未在本批实现。
- Codex Biz route 修正只解决 Provider 路由正确性；其 `danger-full-access` / `never` 等 internal-dev 默认策略未因此成为 external-ready，外部执行策略 pending 门禁保持不变。

## P2 Worker Identity / Terminal 实施 Check-in（`EXEC-142-012`）

### 已完成内容

1. Worker credential v1 已落地 schema 与管理 API：`BizWorkerIdentityEntity` 增加乐观版本及 credential 版本、签发、过期、撤销、轮换字段；平台 `SUPER_ADMIN` 与 upstream `WORKER_MANAGE` 可在 owner scope 内 rotate/revoke。服务端仅在 rotate 响应一次性返回 `bwc_` 明文，持久化 SHA-256；严格校验拒绝 legacy v0、错误、过期、撤销、禁用或缺失 credential。`BizWorkerCredentialService.requireStrictCredential` 尚无 Gateway 调用方，因此本项不代表 Gateway Worker principal 已完成。
2. pool/identity 约束与 LangGraph governed route 已落地：全局 `workerId` 不得跨 owner/backend 重注册；pool 与成员操作绑定 tenant + owner，路由重验 pool/identity enabled、healthy、backend 与可见性。LangGraph Business Agent pool route 只使用受治理 identity endpoint，同名 legacy 不能覆盖；upstream identity 要求 exact owner，`PLATFORM/platform` 是显式共享例外，physical-only 仅允许平台 identity，`tokenHash` 不作为出站 Bearer。
3. definitive terminal 已落为持久化授权 tombstone：仅显式 `recoverable=false` 的 Provider 事件写入 `business_task_terminal_state`；物理 token `REVOKED` 是可重试 materialization。terminal 先于 bind 时，late-bind 在独立事务中保存 worker tuple、撤销 token 并补全 marker 后抛专用异常；marker 冲突不覆盖原归属，但仍撤销冲突 token；即使物理 token 行被错误重开，Gateway resolve 仍由 tombstone fail closed。
4. Claude task 已持久化可信 `tenantId`，并以 entity -> Session -> dedicated Worker 的有界顺序回填 legacy 记录；definitive terminal 无法解析 tenant 时不发布空 tenant 事件。Codex pre-acceptance 且无远端 task 的 `FAILED` 已明确为不可恢复，Claude、Codex、Gemini、LangGraph 的 definitive/recoverable 事件语义均有回归覆盖。
5. `BusinessFunctionRuntimeAuditWriter` 已作为独立 bean 使用 `REQUIRES_NEW + saveAndFlush`；外层捕获包括 commit/flush 在内的写失败，best-effort telemetry 不再回滚主业务。关键审批、拒绝和远程副作用仍没有可靠 outbox/强保证，不能据此把审计链标记完成。

### 主要代码与迁移面

- Worker credential/pool：`business-agent-module/src/main/java/com/foggy/navigator/business/agent/model/entity/BizWorkerIdentityEntity.java`、`service/BizWorkerCredentialService.java`、`service/BizWorkerPoolService.java`、`controller/BizWorkerCredentialController.java`、`controller/UpstreamAdminWorkerCredentialController.java`。
- terminal/audit：`business-agent-module/src/main/java/com/foggy/navigator/business/agent/model/entity/BusinessTaskTerminalStateEntity.java`、`event/BusinessTaskScopedTokenTerminalListener.java`、`service/BusinessFunctionRuntimeAuditWriter.java`。
- LangGraph route：`addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphWorkerService.java`、`LanggraphBusinessAgentWorkerTaskLauncher.java`。
- Claude tenant：`addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/model/entity/ClaudeTaskEntity.java`、`service/ClaudeTaskService.java`。
- 迁移：`docs/migration/2026-07-14-biz-worker-credential-v1*.sql`、`2026-07-14-business-task-terminal-state*.sql`、`2026-07-14-claude-task-tenant*.sql`；通配写法仅用于归组，实际 forward/rollback 文件均已登记在上方代码清单。

### 构建、测试与迁移证据

- 最终命令：`mvn -B -pl business-agent-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am clean test`。11/11 reactor `SUCCESS`，共 2186 tests、0 failure/error/skip：common 47、agent-framework 213、user-auth 72、session 348、business-agent 590、Claude 368、LangGraph 165、Codex 358、Gemini 25；navigator-spi 无测试，总时 03:25。
- 定向证据：terminal 66 tests；Claude + Codex 121 tests；Gemini 11 tests；LangGraph identity/pool 35 tests，且 LangGraph 全模块 165 tests 通过。上述结果均为本地自动化证据，不替代真实网络或外部调用验收。
- `business-agent-module/integration-tests` 在 Node `22.23.1`、pnpm `10.34.5` 下执行 `tsc --noEmit`，exit 0。因本机 `localhost:8112` 未启动，带数据写入的 L3 integration test 未运行。
- `2026-07-14-biz-worker-credential-v1`、`2026-07-14-business-task-terminal-state`、`2026-07-14-claude-task-tenant` 三组 forward/rollback 在一次性 MySQL 8.0.44、8.4.8 完成 forward×2、rollback×2、reapply。仅操作隔离容器；未操作项目共享数据库，未执行 launcher `ddl-auto=validate`。

### 实施自检与下一门禁

- self_check_decision: `continue-in-progress`。本批达成 local partial scope，不构成正式质量闸门、L3 验收或 production enablement。
- Gateway 仍未接入 strict Worker principal/credential header、credential propagation、logical lease、preselect/prebind 或 PoP；跨 Worker 重放拒绝仍待实现。
- task token definitive terminal 已闭合；pause/suspension、cancel、resume generation 轮换、跨实例 runtime secret 恢复和 tombstone 清理任务仍待实现。
- runtime audit 仍是 best-effort；审批/恢复/取消主体绑定、关键拒绝与远程副作用的 reliable audit/outbox 仍待实现。
- 真实 L3、双 ClientApp/upstream user 手工矩阵、non-loopback 网络、浏览器、hosted CI、共享数据库迁移和 launcher validate 均未执行。`production_routing_changed: no`，external-enabled 继续 disabled/unready。

## 实施步骤

### 1. 冻结 authority 与资源图

1. 列出 runtime/control/task credential 的签发者、principal、scope、TTL、存储、撤销、轮换和审计责任。
2. 冻结 tenant -> ClientApp -> upstream user grant -> Agent/skill/model/workspace 的权威关系。
3. 当前 dev/internal 明确以 ClientApp credential 为调用主体，以 mapping/grant 解析 upstream user；header/body 中的 identity 字段只能用于定位已授权映射，不能自行提升 tenant、ClientApp 或 user 身份。
4. 所有当前模式审计记录写入 `client-app-delegated` assurance，避免把 ClientApp 代办误报为 upstream user 独立强认证。

### 2. 冻结 upstream user 证明模型

1. 按 Owner 决策实现当前 dev/internal 的 ClientApp 代办模型，不等待 signed assertion 才推进 task token、资源绑定、审批恢复和审计治理。
2. 定义用户创建、禁用、tenant/ClientApp 迁移、重复映射和 token 轮换行为。
3. 对 upstream user token 的存储、日志、备份和脱敏形成证据。
4. 把 signed assertion 或等强 upstream user 独立证明登记为未来真正 external-enabled 的硬门禁；它不是当前 dev/internal 的身份阻塞项。

### 3. 演进 task-scoped token

1. 为 token 增加版本化契约和兼容读取；不一次性破坏旧 token。
2. 固化 task/session、worker pool/lease、skill、允许函数 scope、签发时间、过期时间和状态。
3. 定义 cancel、complete、expire、人工 revoke、ClientApp/user grant 撤销后的传播。
4. 替换或增强仅内存运行时注入方案，至少形成重启和多实例可验证的恢复策略。

当前进度：v2 新签发、ClientApp function grant 快照、30/60 分钟 TTL、单 token/按 task 批量撤销、hash-conditional runtime alias cleanup 与 definitive terminal 持久化 tombstone 已实现；旧行 fail-closed migration 仅在隔离 MySQL 8.0.44/8.4.8 验证，尚未部署到项目数据库。worker principal/lease、pause/suspension、cancel、resume generation 轮换和跨实例恢复仍未实现。

### 4. 收敛 Gateway 与 Open API 资源绑定

1. Gateway 函数列表、schema、invoke 使用“token function scope ∩ current ClientApp grant”。
2. task/status/messages/evidence/session 查询统一绑定当前 ClientApp/upstream user；旧 generic 路径建立迁移计划。
3. 取消、审批、恢复必须解析持久化任务绑定和可信 principal，拒绝只凭 taskId 的调用。

### 5. 隔离旧审批链路

1. 对 `/langgraph-tasks`、`/claude-tasks`、`/codex-tasks`、deprecated SPI/DTO 完成本仓 PC、Mobile、SDK、CLI、L3、Worker、canary 静态引用与契约清单。
2. 将仍在使用的本仓消费者迁移到可信 principal、持久化 binding 和当前 Provider/Business Agent 入口；不保留请求体 `reviewedBy` 作为可信 actor。
3. 完成安全语义复核、相关自动化测试和全仓 clean build 后，在 1.4.2 同版本物理删除旧入口与兼容类型；无需外部客户流量审计、静默窗口或生产兼容开关。
4. 若扫描发现共享环境、独立部署或生产资源，立即停止该切片并升级为 Owner 决策，不以“dev 阶段”推断可删除。

### 6. Worker external readiness 与执行策略

1. 已为 LangGraph Biz Worker、Codex SDK Worker、Codex app-server Worker 实施各自单一且默认 `false` 的 external-enabled 开关，不会由监听地址、空 Token 或其他配置隐式开启。
2. external-enabled 当前因 `EXTERNAL_EXECUTION_POLICY_PENDING` 始终 unready，空 Token 叠加 `EXTERNAL_AUTH_TOKEN_REQUIRED`；除精确 `/health` 外的业务 API fail closed。
3. health/readiness 已输出 mode、external/auth readiness 和非敏感 reasons；平台已消费 `ready=false`，同时对旧 Worker 缺少 `ready` 字段保留 HTTP 200 兼容。
4. 服务端固定 workspace、allowed dirs/tools/functions、sandbox、approval、network 上限；caller 只能收窄。
5. Codex/LangGraph 分别补路径逃逸、工具升级、网络升级和错误配置测试。

### 7. 审计与可观测性

1. 统一 correlation/audit 字段：tenant、ClientApp、upstream user、task/session、worker、skill/function/version、action、decision、reason、credential id。
2. 增加授权拒绝、token 过期/撤销、binding mismatch、取消和 readiness 拒绝事件。
3. 根据 Owner 决策实现关键事件强保证或清晰的 best-effort 降级与告警。

### 8. 兼容、灰度与签收

1. 本仓 Open SDK、PC、Mobile、CLI、L3、Worker、canary 先迁移到新契约，再删除旧 Provider API、deprecated SPI/DTO；不建立外部客户兼容窗口。
2. 对已经签发、仍在运行或暂停待恢复的 task token/state 使用版本化读取、排空或显式迁移，防止任务无法恢复；这是运行状态安全门禁，不是对外兼容期。
3. 禁止回滚到请求体身份信任；完成自动化、手工、体验、迁移和回滚演练后，进入 P7 覆盖审计与正式签收。

## 自动化测试计划

当前状态：`partial-passed`；external switch/readiness/fail-closed、平台消费、task capability v2、Worker credential v1 schema/API 与严格 service、pool/identity LangGraph route、definitive terminal tombstone、Claude tenant 和 best-effort audit writer 隔离已有本地自动化证据。Gateway principal/credential header 与 lease、pause/suspension/cancel 和 generation、跨实例、Open API ownership、reliable audit/outbox 及真实 L3 仍 `not-run`。

### Credential 与 identity

- runtime/control credential 正常、缺失、错误 scope、过期、撤销、轮换。
- 伪造或交换 `tenantId`、ClientApp、upstream user、`reviewedBy` 后必须拒绝。
- ClientApp 禁用、user grant 禁用、skill/model/function grant 撤销后的即时或约定传播。
- 当前 dev/internal 审计 assurance 必须为 `client-app-delegated`；不得标记为 upstream user 独立强认证。

### Task/token 隔离

- 双 tenant、双 ClientApp、双 upstream user、双 task、双 function 的正负矩阵。
- token 不能访问其他 task/session/function；只允许 scope 与当前 grant 交集。
- token 完成、取消、过期、人工撤销后重放失败。
- JVM 重启、多实例路由、任务恢复时 token 注入符合冻结契约。

### 审批/恢复/取消

- taskId 正确但 principal、tenant、ClientApp、user、session、function、version 或 input hash 任一不匹配即拒绝。
- 请求体 `approvedBy/reviewedBy/userId/tenantId` 不能覆盖 principal。
- 幂等重放、过期 suspension、并发审批和 terminal task 的状态机测试。

### Worker 与策略

- loopback internal-dev 显式无 Token 模式的允许用例。
- `external-enabled` 未显式设置时保持 `false`；不能由非 loopback、空 Token 或其他配置组合隐式开启。该契约已自动化验证。
- external-enabled 因执行策略未齐保持 unready，空 Token 叠加认证 reason，业务路由 503。该契约已自动化验证；真实 non-loopback 部署和完整 workspace/tool/sandbox/network 上限仍未验证。
- workdir traversal、allowed dirs 越界、未允许 tool/function、sandbox/network/web 升级拒绝。
- health/readiness 不泄露 token，且能区分 process-live 与 external-ready。

### 审计与契约

- 成功、拒绝、暂停、审批、恢复、取消、失败、过期、撤销事件字段完整。
- 审计持久化失败的告警/补偿符合 Owner 决策。
- DTO、日志和错误响应不包含明文 token、secret、完整敏感输入。
- 本仓 PC、Mobile、Open SDK、CLI、L3、Worker、canary contract test 迁移到新入口；旧 Provider API、deprecated SPI/DTO 删除后静态引用扫描与 clean build 均通过。
- 运行中/暂停任务的 token/state 版本化读取、排空或迁移测试通过，删除旧 API 不破坏已存在任务的恢复安全。

## 手工验证计划

当前状态：`not-run`。

1. 准备两个 tenant，各两个 ClientApp，各两个 upstream user，各创建两个任务和至少两个授权差异函数。
2. 逐项交换 tenant、ClientApp、upstream user、task token、taskId、sessionId、functionId，确认只有完整绑定匹配时成功。
3. 完成一次需要审批的函数调用，验证暂停、批准、拒绝、恢复、取消和审计查询；请求体伪造 reviewer 不生效。
4. 在任务运行、完成、取消和 token 过期后分别重放 Gateway 调用。
5. 分别启动 loopback internal-dev、non-loopback external-enabled 有 Token和无 Token配置，检查 readiness 与日志。
6. 检查 Codex/LangGraph 的工作目录、附加目录、工具、函数、sandbox 和网络边界。
7. 对旧 `/langgraph-tasks`、`/claude-tasks`、`/codex-tasks` 和 deprecated SPI/DTO 完成本仓 PC、Mobile、SDK、CLI、L3、Worker、canary 的静态引用、契约测试和 clean build；不以单次 `rg` 无命中代替组合证据。

## 体验验证计划

当前状态：`not-run`。

1. ClientApp 管理/集成界面能区分 credential 缺失、过期、撤销、scope 不足和 upstream user grant 不存在。
2. 审批者看到的 tenant、ClientApp、upstream user、任务、函数和输入摘要一致；拒绝或过期后不能误触发恢复。
3. Worker external readiness 提示包含可操作的配置项，不通过模糊 500 或“进程健康”掩盖认证未启用。
4. SDK/上游系统能区分可重试故障、凭据失效、权限拒绝和任务终态，避免无限重试。
5. 内部可信开发模式仍可按明确配置工作，但 UI/日志清楚显示其不是 external-ready。

## 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| ClientApp 代办被误报为用户独立强认证 | 审计主体可信度被高估 | 固定 `client-app-delegated` assurance；未来 external-enabled 前补 signed assertion 或等强证明 |
| token schema 一次切换 | 运行任务无法恢复 | 版本化、兼容读、灰度写、回滚演练 |
| 仅内存 token 注入 | 重启/多实例恢复失败 | 明确恢复权威和跨实例策略 |
| 函数 scope 只依赖 ClientApp grant | task token 可调用过多函数 | 引入 task scope 与 grant 交集 |
| 收紧 Codex 策略破坏开发流程 | 内部效率回归 | internal-dev 与 external-enabled 分离 |
| 本仓旧 API 消费者迁移遗漏 | 本地孵化链路或 clean build 失败 | PC/Mobile/SDK/CLI/L3/Worker/canary 组合扫描、契约测试、clean build；发现共享/生产资源立即停手 |
| best-effort 审计丢拒绝记录 | 无法追溯与签收 | 分级强保证、告警、失败注入 |
| 敏感 upstream token 存储不清晰 | 凭据泄露 | 基础设施审计、加密/secret store 决策 |

## 回滚方式

1. authority/mapping、token schema、Gateway enforcement、Worker readiness、审计和旧 API 迁移分开提交。
2. 新 token 先版本化读取旧状态并为运行中/暂停任务提供排空或显式迁移，再切换新签发；回滚写入策略时仍保留已撤销/过期状态，不恢复旧明文或宽 scope。该读取能力只服务任务状态安全，不构成外部兼容窗口。
3. Gateway scope enforcement 按 ClientApp/Worker allowlist 灰度；出现兼容问题可回滚灰度范围，但不得重新信任请求体身份。
4. Worker external readiness 使用版本化配置；回滚只能回到显式 internal-dev，不允许非 loopback 空 Token 静默 ready。
5. 旧 Provider API、deprecated SPI/DTO 在本仓消费者迁移和 clean build 后同版本物理删除；若需要回退，以对应提交/制品恢复整个安全一致切片并重新验证，不能只恢复请求体 actor 或宽松授权语义。
6. 数据库变更必须有向后兼容 migration、备份和恢复演练；token/secret 不进入回滚文档。

## 完成判据

- [ ] 外部请求可从 audit/correlation 追溯到 tenant、ClientApp、upstream user、task/session 和 Worker。
- [ ] 当前 dev/internal 的 ClientApp credential + mapping/grant 模型已实现并以 `client-app-delegated` assurance 审计；未来真正外部开放前的 signed assertion 门禁已明确登记。
- [ ] task-scoped token 有版本、scope、TTL、撤销/轮换和终态失效，且不能跨 task/session/function；function scope/TTL/人工与批量撤销/definitive terminal 已有证据，Worker principal/lease、pause/generation 与完整跨主体矩阵未完成。
- [ ] Gateway 执行函数是 task scope 与当前 ClientApp grant 的交集。
- [ ] 外部审批、恢复、拒绝、取消不能只凭 taskId，actor 不取自可伪造字段。
- [ ] generic Open API task/session 查询不跨 ClientApp/upstream user 泄露。
- [x] external-enabled 在执行策略未齐时 fail closed/unready，空 credential 叠加明确 reason；本地契约测试已通过，真实 non-loopback 部署验证仍待执行。
- [x] 三类 Worker 均使用单一显式、默认 `false` 的开关，不由其他配置隐式开启；平台 Open API 路由面亦默认关闭。
- [ ] workspace、目录、工具、函数、sandbox、approval、network 上限由服务端控制。
- [ ] 成功、拒绝、暂停、审批、恢复、取消、失败、过期和撤销审计可查询且不泄露明文凭据。
- [ ] 本仓 Open SDK、PC、Mobile、CLI、L3、Worker、canary 已迁移并通过安全语义复核与 clean build；旧 Provider API、deprecated SPI/DTO 已在同版本删除，不保留生产/外部兼容窗口。
- [ ] 已签发且仍在运行/暂停的任务完成 token/state 版本化读取、排空或迁移验证，旧 API 删除不破坏任务恢复安全。
- [ ] 自动化、手工、体验和回滚演练结果回写 Progress；AC-02 至 AC-06 有可定位证据。

## 生产路由与外部契约状态

- 当前：`production_routing_changed: no`，`production_enablement: not-applicable`；external contract 仅新增显式关闭/未就绪错误语义，没有启用 external-enabled。
- 实施影响：平台 `/api/v1/open` 路由面默认关闭，Worker 仅在显式 external-enabled 配置意图下新增 unready/503 语义；internal-dev 的监听、空 Token 与业务入口认证行为保留，但平台对 health 缺失或显式 `ready=false` 的路由判定已收紧，可能影响内部异常场景。task capability v2、Worker credential v1 schema/API、pool/identity LangGraph route、definitive terminal tombstone、Claude tenant 与 best-effort audit writer 隔离已在当前工作树实施；Gateway strict principal/lease、pause/generation、Open API ownership、reliable audit/outbox、真实 L3 和旧接口删除仍未完成。
- 启用门禁：`external-enabled` 必须显式且默认 `false`；signed assertion 或等强用户证明、SDK/调用方迁移、负向矩阵、审计、安全上限与回滚证据缺一不可。隔离测试通过不自动批准真正外部开放。
