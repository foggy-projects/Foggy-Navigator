---
type: governance
version: 1.4.2-SNAPSHOT
ticket: GOV-003
priority: high
status: planned-reviewed
source: REQ-001
owner: session-owner | provider-owner | internal-ui-owner
---

# Session/Task 资源归属治理

## 文档关联

- 版本索引：[1.4.2-SNAPSHOT](../README.md)
- 总需求：[REQ-001 平台治理与历史能力收口](../requirements/REQ-001-platform-governance-and-legacy-cleanup.md)
- 实施阶段：[P3 Session/Task 定向 ownership 治理](../implementation-plan.md#p3sessiontask-定向-ownership-治理)
- 状态与证据：[Progress](../progress.md)
- 上位边界：[GOV-001 内外部信任边界](./GOV-001-internal-external-trust-boundary.md)
- 外部链路：[GOV-002 Biz Worker 与 upstream user 边界](./GOV-002-biz-worker-and-upstream-user-boundary.md)
- 代码与职责：[Code Inventory](../code-inventory.md)、[Module Responsibility](../module-responsibility.md)

## 当前状态

| 项目 | 状态 | 说明 |
|---|---|---|
| Workitem | planned-reviewed | ownership 方向和边界已评审，invariant 尚未统一落地 |
| Implementation | not-started | implementation_started: no |
| Automated test | not-run | 未执行双用户枚举、操作或 Provider 回归测试 |
| Manual verification | not-run | 未执行两账号内部 UI/API 主链验证 |
| Experience verification | not-run | 未验证深链、刷新、重连和越权错误体验 |
| Production routing | unchanged | production_routing_changed: no |
| External contract | unchanged | external_contract_changed: no；越权请求行为计划定向收紧 |

本文没有修改 Controller、service、repository、数据或 UI；静态调用链结论不等同已复现越权。

## 目标

1. 建立统一 Session/Task ownership 不变量，禁止只凭 `sessionId` 或 `taskId` 跨用户读取、发送消息、操作、审批、恢复、取消或修改。
2. ownership 优先在统一 service/facade/resource resolver 层执行，避免 Controller、Provider 和异步处理器各自解释归属。
3. 任务归属从可信 `task -> session -> owner` 或明确的 task owner 关系解析；请求参数中的 `userId` 不得决定归属。
4. 为管理员、系统任务、A2A 父子任务和兼容数据建立具名、最小范围、可审计的例外。
5. 保持内部 UI 和可信内网主流程可用，避免以全局 SecurityConfig 重构造成大面积回归。

## 范围

### Session

- 获取、列表、删除、消息读取、最新消息、发送消息、父子 Session、转发和深链。
- `AgentConversationContext` 与 Navigator Session 的映射。
- SSE 订阅、刷新和重连前的资源归属。

### Task

- 单任务、按 Session 列表、活跃任务和父子 AgentTask。
- respond、reconnect、resync、rewind、resume、cancel 等统一任务操作。
- Provider task 与统一 `SessionTaskEntity` 的归属传播。
- A2A 委派任务、系统恢复任务和 legacy task 的兼容规则。

### 调用面

- 内部 UI/可信内网 Controller。
- `TaskDispatchFacade`、Task operation router/query provider、Session service/repository。
- 与 GOV-002 相交的 Open API、审批、恢复和取消只共享资源不变量，外部 credential 由 GOV-002 负责。

## 非目标

1. 不要求所有内部 Controller 迁移到新的认证框架。
2. 不重写 Spring Security、`SecurityConfig` 或引入通用 RBAC/ABAC。
3. 不实现多实例 SSE 事件总线。
4. 不改变正常用户的 Session/Task 业务语义、Provider 状态机或 UI 信息架构。
5. 不用一个全局 `isAdmin` bypass 解决系统任务和历史数据问题。
6. 不在本工作项删除旧 Provider API；外部兼容由 GOV-002/CLEAN-004 治理。

## Ownership 不变量

### 普通用户

1. 读取或操作 Session 前，服务层必须确认 `session.ownerUserId == principal.userId`，或通过唯一可信上下文关系得出等价结论。
2. 读取或操作 Task 前，必须确认 task owner 与 principal 匹配，并校验 task 关联 Session 的归属一致性。
3. 按 sessionId 列 Task、消息或 Agent 子任务时，先授权 Session，再执行子资源查询。
4. 找不到和无权访问对普通用户返回一致的外部错误语义，避免泄露资源存在性。

### 管理员与系统主体

1. 管理员访问必须通过具名 role/scope 和明确 use case，不接受请求参数冒充用户。
2. 系统/A2A/Provider 恢复使用具名 system principal 或可信任务上下文，不复用宽泛用户 bypass。
3. 每个例外记录 actor、reason、resource、action 和结果；未登记例外默认拒绝。

### 数据一致性

1. `SessionTaskEntity.userId`、Session owner、Provider task userId 出现冲突时 fail closed，并记录可观测错误，不静默选择请求参数。
2. 历史缺 owner 数据必须有盘点、回填或受限兼容策略；不允许以 nullable owner 永久绕过。
3. parent/child session、AgentTask 和 A2A 委派必须沿可信关系传播 owner，不以父/子 ID 本身作为授权。

## 已确认事实

1. 当前内部认证是 `SecurityConfig`、`AuthInterceptor`、`@RequireAuth` 和 `UserContext` 的组合，本工作项只补资源归属，不替换整套认证框架。
2. `SessionController` 的删除会话路径已经传递当前用户给 `SessionMetadataService`，说明代码中已有 ownership-aware 的正向模式可复用。
3. `UnifiedSseController` 在订阅目标 Session 前已经校验当前用户归属；该检查应与统一门面保持一致。
4. `SessionTaskRepository` 已存在 `findByTaskIdAndUserId`、`findBySessionIdAndUserId...` 等带用户谓词的方法，不需要以全局安全重构才能实现定向治理。
5. `TaskOperationRouter` 的部分路径已经使用 `taskId + userId` 查询，说明统一操作路由已有可收敛基础。
6. `SessionMetadataService` 已在若干查询中使用 sessionId/userId 或 sessionId 集合/userId 的组合谓词。

## 静态搜索结论

### Session 路径不一致

- `SessionController` 的 get、messages、latest messages、send message 等调用链在 Controller 层只传入 sessionId，未像 delete 一样显式携带当前用户。
- `JpaSessionManager` 的消息查询方法主要按 sessionId 查询；如果上游未先完成 ownership，会形成不一致边界。
- 这是静态调用签名缺口，需用两账号负向测试确认是否存在可达利用路径。

### Task 列表与子资源

- `TaskController` 的单任务查询会取得当前用户上下文，但 `/sessions/{sessionId}/tasks` 调用 `TaskDispatchFacade.listTasksBySession(sessionId)`。
- `TaskDispatchFacade.listTasksBySession` 聚合统一 repository 和 Provider query，方法签名不含 principal/userId；部分 Provider 的 `listTasksBySession` 也只按 sessionId 查询。
- `AgentTaskController` 使用 `AgentTaskRepository.findByParentSessionId(sessionId)`，静态签名不带用户。
- 因此应采用“先授权父 Session，再查询子 Task/AgentTask”，而不是给每个 Provider 临时添加不同规则。

### 已有局部防线

- `SessionTaskRepository` 同时存在带 userId 和不带 userId 的查询；治理重点是限制不带 userId 的方法只能在已授权上下文内部使用。
- `UnifiedSseController` 已有 Session owner 检查，不能因其他路径缺口而误写成“SSE 无鉴权”。
- `TaskOperationRouter` 与部分 Provider service 已有 `findByTaskIdAndUserId`，实施时应复用并补齐遗漏，不进行一次性重写。

### 与外部 API 的交叉点

- `OpenApiController` 的部分 task/session 查询按 tenant + agent 验证，不等同内部 user ownership，也未统一绑定 ClientApp/upstream user。
- GOV-003 应提供可复用资源解析结果；GOV-002 再叠加外部 principal 与 ClientApp/user grant，避免两套 task 归属算法。

## 运行态待证

| 待证项 | 所需证据 | 未证实前限制 |
|---|---|---|
| sessionId 跨用户读取/发送消息是否可达 | 两账号 API 集成测试、日志、实际响应 | 不宣称漏洞已复现 |
| taskId 跨用户 get/respond/reconnect/resync/rewind/resume/cancel | Provider 全矩阵负向测试 | AC-01/GOV-003 未签收 |
| 按 sessionId 列 Task/AgentTask 是否泄露 | 两账号父子资源测试 | 不仅凭方法签名定性生产影响 |
| 历史 Session/Task 缺失或冲突 owner 的数量 | 数据库只读统计、迁移报告 | 未盘点前不启用严格全量 enforcement |
| 系统/A2A/定时恢复所需例外 | 调用链、任务样本、Owner 签字 | 不允许通用 bypass |
| 管理员真实使用场景 | 内网 API/UI、运维手册、审计样本 | 未确认前只定义最小候选范围 |
| UI 深链 `/c/:id`、刷新、重连依赖 | Playwright/手工体验与路由调用 | 不删除或禁用深链 |
| ownership 校验的性能影响 | 查询计划、批量列表基准、N+1 检查 | 性能未验证前不做全量强制发布 |

## 决策项

| 决策 | Owner | 最晚时间 | 未决处理 |
|---|---|---|---|
| Session canonical owner 字段和 context 映射权威 | Session owner | P3 设计前 | 不实现多套 owner resolver |
| Task owner 以 task.userId 还是 session owner 为主，冲突如何处理 | Session/Provider owners | P3 设计前 | 冲突默认 fail closed |
| 404/403 等越权错误语义与审计策略 | API/Security | API 契约测试前 | 使用不泄露存在性的临时统一语义 |
| 管理员访问的具名 role/scope 与允许动作 | Security/Operations | 管理员例外实现前 | 默认无管理员 bypass |
| 系统/A2A/恢复 principal 模型 | A2A/Provider owners | Provider 操作迁移前 | 旧系统路径不扩大权限 |
| 历史无 owner 数据的回填、隔离或只读兼容 | Data/Session owner | enforcement 灰度前 | 仅在明确 allowlist 上兼容 |
| parent/child Session 和 AgentTask 的 owner 传播规则 | Session/A2A owner | 子资源测试前 | 先授权父资源再查询 |
| 是否增加 owner-aware repository API 并限制旧方法可见性 | Session owner | 实现评审时 | 优先窄门面，不做无收益大改 |

## 关键代码路径

### Controller 与统一门面

- `session-module/src/main/java/com/foggy/navigator/session/controller/SessionController.java`
- `session-module/src/main/java/com/foggy/navigator/session/controller/TaskController.java`
- `session-module/src/main/java/com/foggy/navigator/session/controller/AgentTaskController.java`
- `session-module/src/main/java/com/foggy/navigator/session/controller/UnifiedSseController.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TaskOperationRouter.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/SessionMetadataService.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/JpaSessionManager.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/OpenApiSessionQueryService.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/AgentTaskService.java`

### 实体与 Repository

- `navigator-common/src/main/java/com/foggy/navigator/common/entity/SessionEntity.java`
- `navigator-common/src/main/java/com/foggy/navigator/common/entity/SessionTaskEntity.java`
- `navigator-common/src/main/java/com/foggy/navigator/common/entity/AgentConversationContextEntity.java`
- `navigator-common/src/main/java/com/foggy/navigator/common/repository/SessionEntityRepository.java`
- `navigator-common/src/main/java/com/foggy/navigator/common/repository/SessionTaskRepository.java`
- `session-module/src/main/java/com/foggy/navigator/session/repository/SessionMessageRepository.java`
- `session-module/src/main/java/com/foggy/navigator/session/repository/AgentConversationContextRepository.java`
- `session-module/src/main/java/com/foggy/navigator/session/repository/AgentTaskRepository.java`

### 外部与 Provider 交叉路径

- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiController.java`
- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java`
- `addons/gemini-worker-agent/src/main/java/com/foggy/navigator/gemini/worker/service/GeminiTaskService.java`
- `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphTaskService.java`

## 实施步骤

### 1. 资源与调用清单冻结

1. 枚举 Session/Task/Message/AgentTask 的读、写、操作、订阅和父子关系入口。
2. 标记每个入口当前 principal、查询谓词、Provider 路由、管理员/系统例外和外部契约。
3. 将历史无 owner、owner 冲突和系统任务样本纳入数据盘点。

### 2. 建立统一 resource resolver/invariant

1. 提供 `requireOwnedSession`、`requireOwnedTask` 或等价窄门面，返回已授权资源/上下文，避免先查询再由 Controller 自行比较。
2. Task resolver 同时校验 task owner 与关联 Session owner；冲突按冻结策略处理。
3. parent/child、AgentTask、A2A 委派从已授权父资源派生，不接受裸 ID 作为授权依据。

### 3. 收敛 Session 路径

1. get、messages、latest messages、send、delete、parent/child 和转发统一使用 owner-aware 门面。
2. 保留并复核 `UnifiedSseController` 的现有校验，使 subscribe、历史消息和重连使用一致语义。
3. 对批量列表避免 N+1，使用授权后的 ID 集合与 owner-aware repository 查询。

### 4. 收敛 Task 与 Provider 操作

1. 单任务、按 Session 列表、AgentTask 列表先执行统一归属校验。
2. respond、reconnect、resync、rewind、resume、cancel 经统一 TaskOperation context 传递 principal，不由 Provider 再解析请求参数身份。
3. Provider 只接收已经授权的 operation context；不带 userId 的 repository/query 方法限制在已授权内部调用。

### 5. 管理员、系统与历史兼容

1. 为每类例外定义具名 principal、允许动作、资源范围、原因和审计。
2. 历史无 owner 数据先统计，再选择回填、只读隔离或显式兼容 allowlist。
3. 禁止 `null owner => allow`、`admin => all` 或“内部请求默认可信”等宽泛规则。

### 6. 外部链路复用

1. 向 GOV-002 暴露统一的 task/session resource binding，不暴露绕过 ownership 的裸 repository。
2. 外部调用在资源 binding 之上叠加 tenant、ClientApp、upstream user 和 token scope。
3. 取消、审批和恢复共享相同 task/session 归属，不另建仅按 taskId 的路径。

### 7. 灰度、观测与签收

1. 先记录 would-deny 指标和历史数据冲突，再对普通用户入口逐步 enforcement。
2. 管理员/系统例外单独灰度，任何 bypass 命中必须可审计。
3. 完成内部 UI、Provider 操作和外部交叉矩阵后，回写 Progress 并进入 P7。

## 自动化测试计划

当前状态：`not-run`。

### Session

- 用户 A/B 交叉 get、messages、latest messages、send、delete、parent/child、forward。
- 不存在 ID 与无权 ID 的响应不泄露差异。
- SSE subscribe、刷新、重连与历史消息使用相同 ownership。
- 批量列表和 contextId/sessionId 映射不能跨用户。

### Task

- 用户 A/B 交叉单任务、按 Session 列表、AgentTask 列表、活跃任务。
- respond、reconnect、resync、rewind、resume、cancel 的跨用户负向矩阵。
- Claude、Codex、Gemini、LangGraph Provider operation context 均携带可信 user/resource binding。
- task owner 与 session owner 冲突、owner 缺失、parent/child owner 不一致时 fail closed 或进入批准的兼容策略。

### 管理员与系统

- 具名管理员 scope 的允许/拒绝矩阵，普通用户不能伪造角色或目标 userId。
- A2A/系统恢复使用具名 system principal，超出登记资源范围拒绝。
- 所有例外记录 actor、reason、resource、action 和 outcome。

### 回归与性能

- `SessionController`、`TaskController`、`TaskDispatchFacade`、`TaskOperationRouter` 定向单元/集成测试。
- 主前端相关 API contract、深链和任务操作测试。
- 批量 Session/Task 列表查询数量与延迟基线，避免 owner 校验引入 N+1。

具体 Maven/前端命令由 P1 冻结后的真实测试矩阵决定；当前不记录任何通过结果。

## 手工验证计划

当前状态：`not-run`。

1. 使用内部账号 A/B 分别创建 Session、发送消息、创建多 Provider Task 和 A2A 子任务。
2. 交换 sessionId、taskId、contextId、parentSessionId，验证读取、消息、列表和所有操作均被拒绝。
3. 使用正确账号完成新建、继续、流式输出、刷新、重连、resume、cancel 和深链 `/c/:id`。
4. 对历史 Session/Task 样本验证回填/只读/兼容策略，不允许静默放行。
5. 使用批准的管理员和系统 principal 验证最小范围操作及审计；未批准动作必须拒绝。
6. 与 GOV-002 联动，用同 tenant 不同 ClientApp/upstream user 验证统一资源 binding 不被外部入口绕过。

## 体验验证计划

当前状态：`not-run`。

1. 内部 UI 的会话列表、会话页、Task Pane、审批、恢复、取消、刷新和深链不大面积回归。
2. 无权资源显示稳定的“不可访问/不存在”体验，不暴露标题、消息摘要、任务状态或 owner 信息。
3. 过期页面、旧书签和已删除资源不会无限重试、卡死或弹出泄露性错误。
4. 管理员能力在 UI 上明确其管理上下文，不让普通用户误以为可以切换任意 userId。
5. A2A 父子任务和 Provider 重连仍能看到正确进度，不因 owner resolver 产生孤儿任务。

### 建议 Playwright 场景

| 场景 | 账号 | 预期 |
|---|---|---|
| A 打开自己的 `/c/:id` 并继续任务 | A | 正常流式、刷新后历史一致 |
| B 打开 A 的 `/c/:id` | B | 不显示会话内容或资源元数据 |
| B 调用 A 的 Task cancel/resume | B | 拒绝，A 的任务状态不变 |
| A 查看 A2A 子任务并重连 | A | 父子归属和进度正常 |
| 管理员执行批准的诊断操作 | 管理员 | 仅允许登记动作并产生审计 |

## 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 历史数据缺 owner | 严格校验导致旧任务不可读 | 先盘点、回填或只读 allowlist |
| task/session owner 冲突 | 错误授权或任务中断 | fail closed、记录冲突、Owner 决策 |
| Provider 各自实现规则 | 语义漂移和绕过 | 统一 resolver/context，Provider 只消费结果 |
| 系统/A2A 需要特殊访问 | 用宽 bypass 形成新漏洞 | 具名 system principal 与最小 scope |
| owner 校验引入 N+1 | 列表和 UI 性能下降 | 批量 owner-aware 查询与查询基线 |
| 403/404 变化破坏 UI | 空白页或无限重试 | 稳定错误契约和 Playwright 回归 |
| 外部 API 绕过内部门面 | 同一 task 两套归属 | GOV-002 复用统一 resource binding |

## 回滚方式

1. resource resolver、Session 调用点、Task 查询、Provider operation、管理员/系统例外和数据回填分别提交。
2. enforcement 按入口/用户群灰度；出现正常流量回归时可回滚具体调用点，但保留负向测试、would-deny 指标和缺陷记录。
3. 不允许回滚到“只要有 sessionId/taskId 即放行”；紧急兼容必须是有时限、有 Owner、有审计的精确 allowlist。
4. 数据回填必须可逆并保留变更清单；不得覆盖已确认 owner，冲突记录单独处理。
5. 错误契约和 UI 适配分开发布；回滚 UI 不自动关闭后端安全校验。
6. 生产路由不因本工作项改变；如需旧入口路由切换，转 GOV-002/CLEAN-004 独立审批。

## 完成判据

- [ ] Session/Task/Message/AgentTask 的入口、principal、资源关系和例外清单完整。
- [ ] 普通用户所有读取与操作均经过统一 ownership invariant，不能只凭 sessionId/taskId。
- [ ] 按 Session 查询 Task/AgentTask 前先授权父 Session；Provider query 不形成绕过。
- [ ] task owner、session owner 和 Provider task owner 的冲突/缺失策略已实现并有数据证据。
- [ ] respond、reconnect、resync、rewind、resume、cancel 使用可信 operation context。
- [ ] 管理员、系统和 A2A 例外具名、最小授权、可审计，未使用全局 bypass。
- [ ] SSE subscribe、历史消息、刷新和重连使用一致归属语义。
- [ ] 两用户负向矩阵通过；无权请求不泄露资源存在性或内容。
- [ ] 内部 UI、可信内网和四类 Provider 主流程完成自动化与手工/体验回归，无大面积回归。
- [ ] 外部 Open API/Business Agent 复用统一资源 binding，并由 GOV-002 叠加 ClientApp/upstream user scope。
- [ ] 性能、历史数据、灰度和回滚证据回写 Progress，AC-01 和内部 ownership 门禁可定位。

## 生产路由与外部契约状态

- 当前：`production_routing_changed: no`，`external_contract_changed: no`。
- 规划影响：不改变正常生产路由或正常用户契约；无权访问的响应、旧内部调用和未登记系统例外会定向收紧。
- 启用门禁：必须先完成历史数据盘点、两用户负向矩阵、内部 UI/Provider 回归、性能基线和灰度回滚方案。隔离验证通过不自动批准生产全量 enforcement。
