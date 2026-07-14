---
type: cleanup
version: 1.4.2-SNAPSHOT
ticket: CLEAN-004
priority: high
status: in-progress
source: REQ-001
owner: provider-and-integration-owners
decision_date: 2026-07-14
implementation_started: yes
production_routing_changed: no
launcher_default_agent_inventory_changed: yes
external_contract_changed: no
---

# 实验性 Addon、Echo 与旧 Provider 契约治理

## 文档作用

- doc_type: workitem
- intended_for: project-root-session | provider-owner | integration-owner | reviewer | signoff-owner
- purpose: 分切片跟踪 code-review-agent、Echo Agent 和旧 Provider API/SPI/DTO 的直接收口，记录 Owner 决策、真实执行证据与尚未验证的外部状态。

## 关联文档

- [版本索引](../README.md)
- [REQ-001](../requirements/REQ-001-platform-governance-and-legacy-cleanup.md)
- [实施计划](../implementation-plan.md)
- [ODR-142-006/007 Owner 决策记录](../owner-decision-review.md)
- [统一进度记录](../progress.md)

## Owner 决策与阶段假设

Project Owner 于 2026-07-14 确认：项目仍处于 dev 阶段，现有上游均在本机共同孵化，尚无生产发布或需要保留的外部客户兼容窗口。在此阶段假设成立的前提下：

1. 确认安全、没有本仓消费者的实验性能力可以物理移除；旧 dev 数据允许丢弃。
2. 旧 Provider API、deprecated SPI 与 DTO 不再设置跨版本兼容或流量观察窗口；本仓消费者迁移、安全语义补齐且 clean build 通过后，可在 1.4.2 同版本直接删除。
3. Echo 先迁移为 test-only fixture，保护普通 BusinessFunction Echo 适配器，再退出默认 launcher；不能把测试夹具与运行时 Agent 一起粗暴删除。
4. 上述结论是 Owner 提供的项目阶段假设，不是运行流量、GitLab 配置、数据库或独立部署的实测证据。
5. 若后续发现共享环境、生产资源、仓库外部署或外部消费者，立即暂停对应切片，以新证据重新决策；不得用本次 dev 阶段授权推定可删除外部资源。

## 范围、状态与非目标

三个切片保持独立实施、验证和回滚：

| 切片 | 当前状态 | 本轮结论 | 下一门禁 |
|---|---|---|---|
| A：`addons/code-review-agent` | `implementation-complete / verification-partial` | 22 个 tracked files 已物理移除；未进入默认构建或路由 | 补齐最终文档/差异检查；若发现外部资源则重新决策 |
| B：Echo Agent 默认制品收口 | `completed-local / verification-partial` | addon/reactor/launcher 已物理退出，test-only fixture 保留 A2A 回归 | 补 hosted CI、浏览器、PowerShell parser 和正式门禁 |
| C：旧 Provider API/SPI/DTO | `not-started` | 不设生产或外部兼容窗口，同版本直接收口 | 先迁移全部本仓消费者并补齐安全语义 |

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

- `addons/code-review-agent` 未进入根 `pom.xml` reactor、`launcher/pom.xml`、`.github/workflows/**` 或 `scripts/**`。
- 2026-07-14 已物理移除 `addons/code-review-agent` 下全部 22 个 tracked files，包括 Maven 描述、源码、Spring auto-configuration 登记和测试。
- 移除后执行 `mvn -B -pl launcher -am clean test`，clean reactor 测试通过。
- Echo 切片已完成本地实施：删除 addon 5 个 tracked files，移除 root reactor 与 launcher dependency，默认制品不再注册 Echo Agent。旧 Provider API/SPI/DTO 切片尚未实施。
- `production_routing_changed: no`、`external_contract_changed: no`：当前不存在生产环境或生产契约；同时必须单独记录 `launcher_default_agent_inventory_changed: yes`，不能将默认制品中 Echo 的移除模糊表述为“无路由变化”。

### 静态搜索结论

#### Code Review

- 删除前模块提供 `/api/v1/webhooks/gitlab/code-review`、`/api/v1/code-review/**`，并定义 `code_review_config`、`code_review_record`。
- 删除后扫描当前源码，未发现原 Java package `com.foggy.navigator.codereview`、上述 API 或两张表的模块外引用。
- 静态扫描只能证明本仓可见引用情况，不能证明 GitLab webhook、数据库、独立 jar/部署或仓库外调用不存在。

#### Echo

- `addons/echo-agent` 的 POM、4 个 Java/资源文件（合计 5 个 tracked files）已删除，根 `pom.xml` 和 `launcher/pom.xml` 已移除装配。
- `UnifiedAgentResolverTest` 增加 test-only 内存 fixture，覆盖 discovery、resolve、send、query 和 cancel，不注册到 launcher。
- `tests/integration/test_unified_task_dispatch.sh` 已去除 Echo 运行依赖；`tests/migration/test-codex-runtime-affinity.ps1` 仅将旧 `agentId` literal 替换为迁移 fixture literal。静态运行引用扫描为 0。
- `LocalEchoBusinessFunctionAdapterInvoker` 属于普通 BusinessFunction 本地适配能力，不得随 Echo addon 删除。

#### 旧 Provider 契约

- 当前仍存在三组旧 Controller：
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/ClaudeTaskController.java` → `/api/v1/claude-tasks`；
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/controller/CodexTaskController.java` → `/api/v1/codex-tasks`；
  - `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/controller/LanggraphTaskController.java` → `/api/v1/langgraph-tasks`。
- `SecurityConfig.java` 仍包含三组旧路径；删除 Controller 时必须同步收口对应放行项。
- PC 仍调用 `/codex-tasks/{taskId}/file-hints` 和 `/langgraph-tasks/{taskId}/approve`；Business Agent L3 仍读取 LangGraph task。
- Codex App Server canary/soak 默认使用 `/api/v1/codex-tasks`，`CodexStreamRelay` 仍生成旧 URL。
- `navigator-spi` 的 `TaskCommandProvider`、`TaskListingProvider`、`WorkerSessionQueryProvider` 仍保留 deprecated 兼容方法，各 Provider 仍有桥接实现或兼容 DTO。

### 未进行或需要运行态确认

- 本轮未查询或修改任何 GitLab project webhook。
- 本轮未查询、备份、迁移或删除 `code_review_config`、`code_review_record` 数据。
- 本轮未检查或停止独立 jar、容器、服务或其他 deployment。
- 本轮未采集 Echo 或旧 Provider API 的运行流量；Echo 删除依据是 Owner 确认的 dev-only 边界、仓内静态引用扫描和本地回归，不是“零调用”运行态证据。
- 本轮未运行 hosted CI、真实浏览器或 PowerShell parser；当前宿主不可用 `pwsh`。
- 本轮未验证仓库外 SPI 二进制实现；当前按 Owner 的“所有上游本机孵化”阶段假设推进，而非把仓库外不存在写成事实。

### 已批准决策

| 决策 | 状态 | 约束 |
|---|---|---|
| Code Review 物理移除 | `approved / executed` | 静态安全门已满足；若发现 GitLab、数据库或独立部署资源，暂停收口并重新决策 |
| Echo test fixture retain、默认制品 retire | `approved / completed-local / verification-partial` | test-only fixture 已先行，addon/reactor/launcher 已退出，`LocalEchoBusinessFunctionAdapterInvoker` 无 diff |
| 旧 Provider API/SPI/DTO 同版本直接删除 | `approved / not-started` | 本仓消费者迁移、安全语义补齐、目标测试与 clean build 均通过后才删除 |

## 切片 A：code-review-agent

### 删除清单

本轮删除范围为 `addons/code-review-agent/**` 下全部 22 个 tracked files：

- `addons/code-review-agent/pom.xml`；
- `addons/code-review-agent/src/main/java/com/foggy/navigator/codereview/**`；
- `addons/code-review-agent/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`；
- `addons/code-review-agent/src/test/java/com/foggy/navigator/codereview/service/GitLabMrClientContextTest.java`。

对应历史能力为 GitLab MR webhook、Code Review 配置/记录 API、`code_review_config` 与 `code_review_record` 模型。这里只删除本仓源码，没有操作 GitLab、数据库或部署资源。

### 实际检查与结果

静态引用检查：

~~~bash
rg -n --hidden 'com\.foggy\.navigator\.codereview|/api/v1/webhooks/gitlab/code-review|/api/v1/code-review|code_review_config|code_review_record' \
  . -g '!**/.git/**' -g '!**/target/**' -g '!docs/**' -g '!addons/code-review-agent/**'
rg -n 'addons/code-review-agent|code-review-agent' pom.xml launcher/pom.xml scripts .github
git diff --name-only -- addons/code-review-agent
~~~

2026-07-14 结果：

- 两组引用扫描均未发现当前源码、根 reactor、launcher、CI 或 scripts 消费；
- `git diff --name-only -- addons/code-review-agent` 显示 22 个 tracked files 删除；
- `mvn -B -pl launcher -am clean test` 通过；
- 未把上述结果延伸为“GitLab、数据库或独立部署不存在”。

### Execution check-in

- completed work summary：物理移除未接入默认构建/运行链路的 Code Review addon 源码切片。
- changed surface：仅 `addons/code-review-agent/**` 及本版本治理文档；未修改 GitLab、数据库或部署。
- implementation self-check：删除范围完整；当前源码与构建接线扫描无残留；未触碰其他 addon。
- testing：Java launcher clean reactor `passed`；模块独立测试不再适用，因为该模块已删除且从未进入根 reactor。
- experience：`N/A`；该 addon 未进入默认 launcher，本轮没有可用 UI/默认路由体验面。
- remaining risk：静态仓库之外的 webhook、数据和独立 deployment 未验证。
- self-check decision：`needs-formal-quality-gate`，随 1.4.2 统一质量检查处理，不在本工作项冒充正式验收。
- acceptance readiness：`partial`；实现完成，但需由顶层 progress 汇总最终 diff、文档链接与版本门禁。

### 回滚

源码可通过只恢复该切片的版本提交回滚。由于本轮没有修改 GitLab、数据库或 deployment，不存在需要本次回滚恢复的外部状态；如果之后另行清理外部资源，必须在对应执行记录中单列备份和恢复方式。

## 切片 B：Echo Agent 默认制品收口

### 精确 inventory

- 根 `pom.xml` 和 `launcher/pom.xml` 的 Echo module/dependency；
- `addons/echo-agent/**`；
- `tests/integration/test_unified_task_dispatch.sh`；
- `tests/migration/test-codex-runtime-affinity.ps1`；
- Agent discovery/A2A 文档与 fixture；
- `business-agent-module/.../LocalEchoBusinessFunctionAdapterInvoker.java`（`do-not-touch`）。

### 实施结果

1. 已扫描 Echo agentId/providerType/addon 仓内运行引用，删除后静态运行引用为 0。
2. `UnifiedAgentResolverTest` 内建 test-only fixture，可复现 Agent card discovery/resolve 及 task send/query/cancel，不依赖生产 Spring auto-configuration。
3. `tests/integration/test_unified_task_dispatch.sh` 改为检查统一 discovery/task API，不再要求 Echo 存在；PowerShell migration 脚本仅替换 agentId literal。
4. 已物理删除 `addons/echo-agent` 的 5 个 tracked files，并从 root reactor 和 `launcher` 移除装配。
5. `LocalEchoBusinessFunctionAdapterInvoker` 无 diff，未随本切片变更。

参考扫描：

~~~bash
rg -n --hidden 'echo-agent-default|providerType.*echo-agent|agentId.*echo-agent|addons/echo-agent' \
  . -g '!**/.git/**' -g '!**/target/**' -g '!docs/version-tracker/1.4.2-SNAPSHOT/**'
rg -n 'LocalEchoBusinessFunctionAdapterInvoker|type.*echo' business-agent-module
~~~

### 验证与回滚

- 定向 fixture 测试：16/16 tests 通过，覆盖 discovery/resolve/send/query/cancel。
- launcher 定向验证：14 个 reactor modules `SUCCESS`，6/6 tests，`BUILD SUCCESS`，exit 0。该证据验证默认制品可在无 Echo module/dependency 时编译和测试，不是真实启动/浏览器证据。
- `bash -n tests/integration/test_unified_task_dispatch.sh` 通过。当前宿主无 `pwsh`，因此 PowerShell 脚本未做 parser 验证，仅核对 agentId literal diff。
- 手工/体验：`not-run`；未做真实 launcher 启动、Agent card 浏览器对比或 BusinessFunction echo 调用。`LocalEchoBusinessFunctionAdapterInvoker` 无 diff 只证明本切片未修改它，不代表已做运行态验证。
- 回滚：独立 revert Echo 切片提交，恢复 5 个 addon 文件及 root/launcher 装配；测试 fixture 可随同一切片回滚。本切片未删除业务数据。
- 当前状态：`completed-local / verification-partial`；hosted CI、浏览器、PowerShell parser、正式质量/覆盖/验收门禁未执行。

## 切片 C：旧 Provider API、SPI 与 DTO

### 本仓迁移矩阵

| 旧能力 | 当前已知本仓消费者/差距 | 同版本删除门 |
|---|---|---|
| `/claude-tasks` task CRUD/resume/respond/rewind | 统一 `/tasks` 已覆盖主任务流，旧 Controller 仍有兼容入口 | 逐 method parity、安全负例和本仓消费者迁移 |
| Claude worker sessions/conversation config 子路由 | 旧 Controller 仍承载多个兼容入口 | 迁至统一 Session 或专属配置 API 并迁移调用方 |
| `/codex-tasks/{id}/file-hints` | PC 直接调用 | 统一 API 补齐并迁移 PC |
| Codex generated image route | Provider Controller 专属 | 明确 ownership、路径/文件边界及替代契约 |
| Codex list/get/abort/reconnect | canary/soak 与 relay 仍有旧路径 | 迁移脚本、relay 与测试后删除 |
| `/langgraph-tasks/{id}` | Business Agent L3 | 迁至统一 task get 或受控专属 API |
| `/langgraph-tasks/{id}/approve` | PC 直接调用，form 含兼容身份字段 | 从可信主体派生 reviewer，绑定任务归属后迁移 PC |
| deprecated `TaskCommandProvider` | Provider 兼容实现仍可能依赖 | typed/direct replacement 全实现并完成本仓编译迁移 |
| deprecated `TaskListingProvider` / `WorkerSessionQueryProvider` | map/Object 兼容桥 | typed DTO、分页和 session contract 全迁移 |
| Provider 旧 DTO/forms | Controller/SPI 兼容 | 当前源码无序列化/构建消费者后删除 |

### 实施顺序

1. 生成每个 HTTP method/path、deprecated method 和 DTO 的本仓消费者矩阵，不再等待外部客户清单或生产流量窗口。
2. 补齐统一 API 的能力缺口与安全语义：资源 ownership、可信调用主体、审批/恢复/取消绑定、工作目录/文件边界等不能因直接删除而退化。
3. 迁移 PC、Mobile、Open SDK/CLI、Business Agent tests、Worker、canary/soak、relay 和当前本机上游。
4. 对新契约执行成功路径与越权负例；确认请求体中的 `userId`、`reviewedBy`、`tenantId` 等字段不会成为可信身份来源。
5. 按 HTTP route、SPI、DTO 三类小批次删除旧实现，同步删除 `SecurityConfig` 放行项、桥接实现、mock、测试和当前文档残留。
6. 每批执行目标模块测试和 `mvn -B -pl launcher -am clean test`；本仓迁移、安全测试、clean build 全部通过后，可在 1.4.2 直接完成，不设跨版本兼容窗口。

扫描命令：

~~~bash
rg -n --hidden '/api/v1/(claude-tasks|codex-tasks|langgraph-tasks)|/(claude-tasks|codex-tasks|langgraph-tasks)' \
  . -g '!**/.git/**' -g '!**/target/**' -g '!**/node_modules/**' -g '!docs/version-tracker/1.4.2-SNAPSHOT/**'
rg -n '@Deprecated' navigator-spi navigator-common session-module \
  addons/claude-worker-agent addons/codex-worker-agent addons/gemini-worker-agent addons/langgraph-biz-worker \
  -g '*.java' -g '!**/target/**'
~~~

### 验证与回滚

- 自动验证：统一 API contract 与 ownership negative tests；PC/mobile type-check、test、build；SDK/CLI/Worker/canary contract；Provider create/resume/reconnect/approval/file-hints/generated-image；SPI typed contract；launcher clean test。
- 手工验证：PC/Mobile 创建、继续、取消、审批、file hints、图片和历史会话；当前本机上游以新路径 smoke；跨用户/跨任务操作被拒绝。
- 回滚：HTTP route、SPI 和 DTO 分小批次提交；失败时仅恢复对应批次。因为没有生产/外部兼容承诺，不保留双路由时间窗口，但回滚版本必须可由 Git 历史恢复。
- 当前状态：`not-started`；旧 Controller、SPI、DTO、消费者和 SecurityConfig 尚未变更。

## 总体验证门禁

各切片分别记录测试结果，不得以 Code Review 的 clean reactor 通过代表 Echo 或旧 Provider 契约已经完成：

~~~bash
mvn -B -pl launcher -am clean test
mvn -B clean verify
pnpm run typecheck:frontend
pnpm run test:frontend
pnpm run build:frontend
git diff --check
~~~

- Code Review：launcher clean reactor 已通过；最终 `git diff --check`、Markdown 链接与顶层状态仍由版本收口统一记录。
- Echo：定向 16/16 与 launcher 定向 6/6 tests 通过，`bash -n` 通过；hosted/browser/PS parser/formal gate `not-run`。
- 旧 Provider API/SPI/DTO：`not-run`，因为尚未实施。
- `mvn -B clean verify`：本工作项尚未记录执行结果，不得以 launcher clean test 替代。

## 风险与总体回滚

| 风险 | 控制 | 回滚 |
|---|---|---|
| Owner 阶段假设与实际外部状态不一致 | 一旦发现共享/生产资源或仓库外消费者，暂停对应切片并重新决策 | 恢复对应 module/route，另立外部迁移记录 |
| Code Review 外部 webhook/数据/deployment 未被静态搜索发现 | 明确记为未检查，不宣称运行态零使用 | 恢复源码；外部资源按其独立变更记录恢复 |
| Echo 退出 launcher 破坏测试或 discovery | test-only fixture 先行、默认制品定向构建测试；真实启动/浏览器差异留待后续补证 | 恢复 launcher dependency/装配条件 |
| 直接删除旧 API 时遗漏安全语义 | 消费者矩阵、ownership/可信主体负例、按路由小批次收口 | 仅恢复失败 route 批次 |
| SPI/DTO 删除造成本仓 Provider 编译失败 | typed replacement 先行、全仓 clean build | 恢复对应 SPI/DTO 小批次 |
| 不同切片同提交难以回滚 | A/B/C 独立提交、独立 check-in | 只 revert 失败切片 |

## 完成判据

- [x] Code Review 22 个 tracked files 已物理移除，本仓源码/构建接线扫描无引用，launcher clean reactor 通过。
- [x] Code Review 未检查的 GitLab、数据库和独立 deployment 状态已显式记录，没有虚构流量证据。
- [ ] Code Review 切片随版本执行最终 diff、链接和质量门禁收口。
- [x] Echo test-only fixture 已先行，默认 launcher 不再注册 Echo，`LocalEchoBusinessFunctionAdapterInvoker` 无 diff。
- [ ] 旧 API 每个 method/path 及 deprecated SPI/DTO 都有本仓消费者、替代与迁移记录。
- [ ] 统一契约的 ownership、可信主体、审批/恢复/取消负例测试通过。
- [ ] SecurityConfig、Controller、SPI/DTO、tests、scripts 和当前文档按对应切片同步收口。
- [ ] 自动化、手工体验、clean build 和回滚方式有真实证据。
- [ ] 结果回写 [Progress](../progress.md)，并完成版本要求的质量检查、覆盖审计和签收。
- [x] 未触碰明确“暂时不要删除”的能力。

## 生产路由与外部契约状态

- production_routing_changed: no
- launcher_default_agent_inventory_changed: yes
- external_contract_changed: no
- code_review_slice: implementation-complete / verification-partial
- echo_slice: completed-local / verification-partial
- legacy_provider_contract_slice: not-started
- production_enablement: not-applicable-at-current-dev-stage

当前无生产环境，因此 `production_routing_changed: no` 保留；但 Echo 已从默认 launcher 清单退出，必须同时读取 `launcher_default_agent_inventory_changed: yes`。该本机结论不等于 hosted CI、浏览器验证、正式验收或生产批准。
