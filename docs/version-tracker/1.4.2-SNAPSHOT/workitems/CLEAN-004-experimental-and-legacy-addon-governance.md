---
type: cleanup
version: 1.4.2-SNAPSHOT
ticket: CLEAN-004
priority: high
status: planned
source: REQ-001
owner: provider-and-integration-owners
---

# 实验性 Addon、Echo 与旧 Provider 契约治理

## 文档作用

- doc_type: workitem
- intended_for: project-root-session | provider-owner | integration-owner | reviewer | signoff-owner
- purpose: 分别治理 code-review-agent、Echo Agent 和旧 Provider API/SPI，禁止把不同风险切片一次性删除。

## 关联文档

- [版本索引](../README.md)
- [REQ-001](../requirements/REQ-001-platform-governance-and-legacy-cleanup.md)
- [实施计划](../implementation-plan.md)
- [ODR-142-006/007 Owner 决策评审稿](../owner-decision-review.md)
- [统一进度记录](../progress.md)

## 范围与非目标

本事项包含三个相互独立的决策切片：

1. `addons/code-review-agent` 的 GitLab webhook/独立部署去留；
2. `addons/echo-agent` 从生产 launcher 移向 dev/test fixture 的方案；
3. `/claude-tasks`、`/codex-tasks`、`/langgraph-tasks` 及 deprecated SPI/DTO 的渐进迁移。

明确不删除：

- `CodingAgentEntity` 与 `/api/v1/coding-agents`；
- `ProfileView.vue`；
- `/c/:id` 深链；
- `navigator-chat-widget` 与 mobile `uni_modules`；
- keystore（必须先迁移、备份和轮换）；
- `metadata-config-module`；
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/adapter/LocalEchoBusinessFunctionAdapterInvoker.java`，它与 Echo Agent addon 不是同一能力。

不在本版本实现动态插件加载，也不把 Addon 当前编译期模块化单体误写为运行时插件体系。

## 证据分类

### 已确认事实

- 三个切片必须独立审计、独立决定、独立验证和独立回滚。
- 规划阶段不删除 addon、生产 launcher dependency、Controller、SPI 或 DTO。
- 旧 API 删除前必须审计 PC、Mobile、Open SDK、CLI、Worker/canary 和外部客户。

### 静态搜索结论

#### Code Review

- `addons/code-review-agent` 有完整源码、JPA entity、配置 API 和 GitLab MR webhook。
- 它不在根 reactor 和 launcher，默认 launcher 不暴露其 Controller。
- API 为 `/api/v1/webhooks/gitlab/code-review` 和 `/api/v1/code-review/**`。
- 表为 `code_review_config`、`code_review_record`。
- 模块外未发现上述精确 API 的静态消费者，但不能排除 GitLab 或独立部署。

#### Echo

- `addons/echo-agent` 同时在根 reactor 和 `launcher/pom.xml`。
- auto-configuration 无 profile/condition，Agent `echo-agent-default` 随 launcher 注册并对所有用户可见。
- `tests/integration/test_unified_task_dispatch.sh` 依赖 Echo discovery/A2A。
- `tests/migration/test-codex-runtime-affinity.ps1` 使用 `agentId=echo-agent`。
- 从生产 launcher 移除会改变 Agent discovery 契约，不能当普通孤儿文件处理。

#### 旧 Provider 契约

- Controller：
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/ClaudeTaskController.java` → `/api/v1/claude-tasks`；
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/controller/CodexTaskController.java` → `/api/v1/codex-tasks`；
  - `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/controller/LanggraphTaskController.java` → `/api/v1/langgraph-tasks`。
- `SecurityConfig.java` 仍放行三组旧路径。
- PC 仍调用 `/codex-tasks/{taskId}/file-hints` 和 `/langgraph-tasks/{taskId}/approve`。
- Business Agent L3 仍读取 LangGraph task；Codex App Server canary/soak 默认使用 `/api/v1/codex-tasks`；`CodexStreamRelay` 仍生成旧 URL。
- Claude 主任务流已迁向 `/api/v1/tasks`，但旧 Controller 仍包含 task、worker session 和 conversation config 兼容端点。
- `navigator-spi` 中 `TaskCommandProvider`、`TaskListingProvider`、`WorkerSessionQueryProvider` 仍保留 deprecated 兼容方法，各 Provider 有对应桥接实现和 DTO。

### 需要运行态确认

- GitLab 项目 webhook、独立 jar/部署、两张 code-review 表和外部 MR 审核消费者。
- Echo Agent 在 session/task/audit 数据、A2A discovery 客户端、自动化和演示环境中的使用量。
- 三组旧 API 在所有环境的路径级流量、调用方版本和最后调用时间。
- PC/Mobile 历史版本、Open SDK/CLI 版本、Worker/canary 配置和外部客户升级能力。
- deprecated SPI 是否被仓库外 Provider 实现或二进制插件编译依赖。

### 决策项

| 决策 | Owner | 未决处理 |
|---|---|---|
| code-review retain/archive/retire | GitLab/integration owner | ODR-142-006-CR 建议 `archive/freeze`；查清 webhook/独立部署前保留源码，不纳入默认 launcher |
| Echo fixture 形态 | provider/test owner | ODR-142-006-ECHO 建议 dev/test retain、production retire；fixture 和双 profile contract 先行 |
| Echo discovery 弃用窗口 | release owner | 当前不隐藏/删除 Agent；迁移测试和运行态消费者后独立批准 |
| 统一 API 补齐范围 | Session + Provider owners | 不迁移消费者 |
| 旧 API 静默窗口 | PC/Mobile/SDK/CLI/external owners | ODR-142-007 建议“两版本 + 90 天 + 30 天全请求归零或逐笔归属”，未知/无法归属请求阻塞删除；1.4.2 不删除 Controller |
| deprecated SPI 二进制兼容期 | SPI owner | 建议至少两个 artifact 版本且不少于 180 天；当前保持 `forRemoval=false` |

## 切片 A：code-review-agent

### 精确 inventory

- `addons/code-review-agent/pom.xml`
- `addons/code-review-agent/src/main/java/com/foggy/navigator/codereview/**`
- `addons/code-review-agent/src/main/resources/META-INF/spring/**`
- `addons/code-review-agent/src/test/**`
- API `/api/v1/webhooks/gitlab/code-review`、`/api/v1/code-review/**`
- tables `code_review_config`、`code_review_record`
- GitLab webhook/token、Git provider credential、独立 deployment 和文档

### 执行门禁

~~~bash
rg -n --hidden 'code-review-agent|/api/v1/webhooks/gitlab/code-review|/api/v1/code-review|code_review_config|code_review_record' \
  . -g '!**/.git/**' -g '!**/target/**' -g '!docs/version-tracker/1.4.2-SNAPSHOT/**'
rg -n 'addons/code-review-agent|code-review-agent' pom.xml launcher/pom.xml scripts docker deploy .github
~~~

运行态必须检查 GitLab webhook 配置、access log、独立部署、数据库行数/最后写入和数据保留。结论可为：

- retain：正式纳入构建/认证/测试前另立实施项；
- archive：保留可恢复源码/数据说明，不进入 launcher；
- retire：先解除 GitLab webhook、备份表、观察静默，再成组删除；
- defer：Owner/流量不明时保持现状。

自动验证：模块独立 test（退役前）、根 clean verify（退役后）、GitLab webhook 替代 smoke、Markdown 链接。当前均 `not-run`。

手工验证：由 GitLab Owner 检查项目 integrations 和最近 delivery；没有 GitLab 权限时标 blocked，不能写“无消费者”。

回滚：源码/文档通过 `git revert`；webhook、token 和两张表按独立快照恢复，Git revert 不恢复外部配置/数据。

完成判据：去留决定、GitLab/部署/表证据、替代/迁移、测试和回滚全部可定位。

## 切片 B：Echo Agent 生产隔离

### 精确 inventory

- `pom.xml` 和 `launcher/pom.xml` 的 Echo module/dependency
- `addons/echo-agent/**`
- `tests/integration/test_unified_task_dispatch.sh`
- `tests/migration/test-codex-runtime-affinity.ps1`
- Agent discovery/A2A 文档与 fixture
- 与 Echo 同名但不属于 addon 的 `LocalEchoBusinessFunctionAdapterInvoker`（do-not-touch）

### 实施步骤

1. 查询 task/session/audit 中 `providerType=echo-agent`、`agentId=echo-agent-default/echo` 的使用量。
2. 盘点 A2A discovery、demo、测试和外部调用方。
3. 先建立 dev/test fixture 装配方式，保持现有集成测试可执行。
4. 为 production launcher 增加明确排除边界；不得用一次性删除让测试失效。
5. 发布 discovery 弃用说明并观察静默窗口。
6. 经 release owner 批准后，才从生产 launcher 移除；是否保留根 reactor 作为 test fixture 单独决定。

参考扫描：

~~~bash
rg -n --hidden 'echo-agent-default|providerType.*echo-agent|agentId.*echo-agent|addons/echo-agent' \
  . -g '!**/.git/**' -g '!**/target/**' -g '!docs/version-tracker/1.4.2-SNAPSHOT/**'
rg -n 'LocalEchoBusinessFunctionAdapterInvoker|type.*echo' business-agent-module
~~~

自动验证：

- dev/test fixture 下 Echo discovery、ask、status 回归；
- production profile/launcher context 不发现 Echo；
- `launcher -am clean test` 和根 clean verify；
- 迁移测试不再硬依赖生产 Echo，或显式启用 fixture。

手工验证：对比 production 与 dev Agent card 列表；生产不显示 Echo，dev/test 可按说明启用；确认普通 BusinessFunction echo adapter 未受影响。

风险：外部 discovery 客户、测试 fixture 丢失、profile 装配漂移。回滚通过恢复 launcher dependency/profile 和 Agent card；数据库数据不删除，便于重新启用。

完成判据：fixture 先于生产移除可用，消费者静默，生产/开发差异有 readiness/文档，测试与回滚演练完成。

## 切片 C：旧 Provider API、SPI 与 DTO

### 路由迁移矩阵

| 旧能力 | 当前已知消费者/差距 | 删除前替代门 |
|---|---|---|
| `/claude-tasks` task CRUD/resume/respond/rewind | 统一 `/tasks` 已覆盖主任务流；外部/历史客户端未知 | 路由逐项 parity、历史客户端流量静默 |
| Claude worker sessions/conversation config 子路由 | 旧 Controller 仍承载多个兼容入口 | 迁到统一 Session/专属配置 API，消费者逐项切换 |
| `/codex-tasks/{id}/file-hints` | PC 直接调用 | 统一 API 增加并迁移 PC |
| Codex generated image route | Provider Controller 专属 | 冻结安全、ownership 和统一/专属替代契约 |
| Codex list/get/abort/reconnect | canary/soak、外部版本可能调用 | canary 配置迁移、流量静默 |
| `/langgraph-tasks/{id}` | Business Agent L3 | 统一 task get 或受控专属 API |
| `/langgraph-tasks/{id}/approve` | PC 直接调用，form 含兼容身份字段 | 统一审批 API，从可信主体派生 reviewer 并迁移 PC |
| deprecated `TaskCommandProvider` | Provider 兼容实现可能依赖 | typed/direct replacement 全实现，二进制兼容窗口 |
| deprecated `TaskListingProvider` / `WorkerSessionQueryProvider` | map/Object 兼容桥 | typed DTO/分页/session contract 全迁移 |
| Provider 旧 DTO/forms | Controller/SPI 兼容 | 无序列化或 SDK 消费后再删 |

### 实施步骤

1. 生成每个 HTTP method + path 的静态消费者和多环境流量矩阵。
2. 冻结统一 API 缺口及 ownership/可信主体约束；先增新契约和 contract tests。
3. 迁移 PC、Mobile、SDK、CLI、Business Agent tests、Worker/canary 和外部客户。
4. 旧路由增加可观测弃用信息，不记录 token/用户敏感数据。
5. 观察 Owner 批准的静默窗口；按路由而非按 Controller 粗粒度删除。
6. HTTP 迁移稳定后再治理 deprecated SPI/DTO；仓库外实现未知时保持兼容。
7. SecurityConfig 放行项与 Controller 同步收口，不能先删放行导致隐式 401/403。

扫描命令：

~~~bash
rg -n --hidden '/api/v1/(claude-tasks|codex-tasks|langgraph-tasks)|/(claude-tasks|codex-tasks|langgraph-tasks)' \
  . -g '!**/.git/**' -g '!**/target/**' -g '!**/node_modules/**' -g '!docs/version-tracker/1.4.2-SNAPSHOT/**'
rg -n '@Deprecated' navigator-spi navigator-common session-module \
  addons/claude-worker-agent addons/codex-worker-agent addons/gemini-worker-agent addons/langgraph-biz-worker \
  -g '*.java' -g '!**/target/**'
~~~

自动化测试：

- 新旧 API parity 与 ownership negative tests；
- PC/mobile API tests、Vitest、type-check、build、Playwright；
- Open SDK/CLI contract tests；
- Codex canary/soak 配置测试；
- Provider create/resume/reconnect/approval/file-hints/generated-image；
- SPI typed/legacy contract tests和 `launcher -am clean test`。

手工验证：

1. PC/Mobile 创建、继续、取消、审批、file hints、图片和历史会话。
2. SDK/CLI 在新路径执行 smoke，并确认旧版本得到约定弃用响应。
3. 跨用户/跨任务审批、恢复、取消被拒绝，reviewer 不取自请求体。
4. release owner 查看静默指标后逐路由批准删除。

回滚：新 API 先增后迁，旧路由按独立开关/提交保留；删除后通过 `git revert` 或路由开关恢复。SPI/DTO 删除必须独立于 HTTP 路由，保留兼容 artifact 直至二进制窗口结束。

## 总体自动化验证

所有命令当前 `not-run`，实际按切片补充：

~~~bash
mvn -B -pl launcher -am clean test
mvn -B clean verify
corepack pnpm --filter @foggy/navigator-frontend type-check
corepack pnpm --filter @foggy/navigator-frontend test
corepack pnpm --filter @foggy/navigator-frontend build
corepack pnpm --filter @foggy/mobile test
corepack pnpm --filter @foggy/mobile build:h5
git diff --check
~~~

不得以其中一条通过代表三个切片都完成；每个切片必须有独立 test/experience/evidence。

## 风险与总体回滚

| 风险 | 控制 | 回滚 |
|---|---|---|
| GitLab/外部客户未被静态搜索发现 | 运行态、多环境和 Owner 审计 | 恢复对应 module/route，延后退役 |
| Echo 生产移除破坏测试或 discovery | fixture 先行、分 profile contract | 恢复 launcher dependency/profile |
| 统一 API 未覆盖专属能力 | 路由级 parity matrix，先增后迁 | 客户端切回旧路径 |
| 先删 SecurityConfig/DTO 造成隐式破坏 | 与 route/SPI 分阶段收口 | revert 对应小提交 |
| 仓库外 SPI 二进制实现失效 | 兼容窗口和 artifact consumer 调查 | 发布兼容 SPI artifact，延后 removal |
| 不同切片同提交难以回滚 | A/B/C 独立提交、独立签收 | 只 revert 失败切片 |

## 完成判据

- [ ] A/B/C 三个切片各有 Owner、运行态证据、决策和独立进度。
- [ ] Code Review 的 GitLab webhook、独立部署和两张表已确认。
- [ ] Echo dev/test fixture 已先行，生产 discovery 变更有静默与回滚。
- [ ] 旧 API 每个 method/path 都有消费者、替代、迁移和静默记录。
- [ ] PC/Mobile/SDK/CLI/Worker/canary/外部客户均有明确状态。
- [ ] deprecated SPI/DTO 的仓库内外实现和二进制窗口已确认。
- [ ] SecurityConfig、Controller、DTO、tests 和 docs 按对应切片同步收口。
- [ ] 自动化、手工体验、clean build 和回滚演练有真实证据。
- [ ] 结果回写 [Progress](../progress.md)，并完成正式质量检查、覆盖审计和签收。
- [ ] 未触碰明确“暂时不要删除”的能力。

## 生产路由与外部契约状态

- audit_production_routing_changed: no
- audit_external_contract_changed: no
- code_review_retirement_change: possible
- echo_production_removal_change: yes
- legacy_api_removal_change: yes
- production_approval_required: yes-per-slice

当前状态只表示规划不改生产。Echo 从 launcher 移除或旧 API/SPI 删除时必须更新为真实 `yes`，且三个切片分别批准，不能以一次隔离验收打包放行。
