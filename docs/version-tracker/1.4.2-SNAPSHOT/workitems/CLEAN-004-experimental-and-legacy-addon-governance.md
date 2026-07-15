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
external_contract_changed: yes
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
| B：Echo Agent 默认制品收口 | `implementation-complete / local-and-hosted-passed / verification-partial` | production addon/reactor/launcher 已物理退出，test-only fixture 保留 A2A 回归；截至正式闸门的最新已验证实现 head 对应 Repository CI 已通过，版本签收已拒绝 | 补 PowerShell parser、专项体验与模块级签收 |
| C：旧 Provider API/SPI/DTO | `implementation-complete / local-and-hosted-passed / experience-partial` | 三组旧 HTTP route、Provider deprecated SPI bridge 及 Claude/Codex 旧 task DTO/form 已按小批次直接收口；版本签收已拒绝 | 补真实 Provider Task 浏览器/网络体验与模块级签收；不恢复双路由兼容窗口 |

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
- Echo 切片已完成实施：删除 addon 5 个 tracked files，移除 root reactor 与 launcher dependency，默认制品不再注册 Echo Agent；`UnifiedAgentResolverTest` 保留 test-only fixture。
- 旧 Provider 切片已完成实施：删除 `/api/v1/claude-tasks/**`、`/api/v1/codex-tasks/**`、`/api/v1/langgraph-tasks/**` 三组 Controller 与对应 SecurityConfig matcher；本仓可执行消费者已迁移到 `/api/v1/tasks/**`、`/api/v1/sessions/**` 或受 ownership 约束的统一 task 扩展路由。
- Provider SPI 仍保留当前有效的 `TaskCommandProvider`、`TaskListingProvider` 和 `WorkerSessionQueryProvider` typed port；本轮删除的是其 deprecated 默认方法、map/Object 桥接、`TaskQueryProvider` 及旧 registry/contract test，不是删除当前 Provider port。
- `production_routing_changed: no`：当前无生产环境。`external_contract_changed: yes`：即使项目处于 dev 阶段，三组旧 HTTP 入口也已从可构建制品中物理移除，不得以“未上生产”将契约变更记为 `no`。`launcher_default_agent_inventory_changed: yes` 单独记录 Echo 退出默认制品。

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

- 针对可执行源码、测试、scripts 与 Worker（排除 `docs/**`、`target/**`、`node_modules/**`）扫描三组旧 route，结果为 0；历史版本/需求文档仍保留当时路径作为历史证据，不属于运行时消费者。
- `ClaudeTaskController.java`、`CodexTaskController.java`、`LanggraphTaskController.java` 与 `ApproveTaskForm.java` 已删除；`SecurityConfig.java` 对三组旧 route 的 matcher 扫描为 0。
- PC/mobile 任务命令已迁到 `/api/v1/tasks/**`；Claude worker-session 与 conversation config 能力已收口到 `/api/v1/tasks/workers/**` 与 `/api/v1/sessions/**`；Codex file hints/generated image 已收口到 `CodexTaskExtensionController` 下的 `/api/v1/tasks/{taskId}/...`。
- LangGraph 审批已迁到 `POST /api/v1/tasks/{taskId}/respond`；统一 task 投影先按当前认证主体的 user + tenant 验证 ownership，Provider 再按同一 user 匹配 `PENDING` 审批，`reviewedBy`、`userId` 等请求字段不作为可信审核主体。
- `TaskQueryProvider.java`、`DefaultA2aAgentRegistry.java` 及旧 contract test 已删除；Provider 使用当前 typed port。指定 Provider 范围执行 `rg -n '@Deprecated' ...` 结果为 0。
- Claude 的 `TaskDTO.java` / `CreateTaskForm.java` 与 Codex 的 `CodexTaskDTO.java` / `CreateCodexTaskForm.java` 已删除；内部创建语义分别收口到 `ClaudeTaskCreateCommand` 和 `CodexTaskCreateCommand`，对外统一投影使用 `DispatchTaskDTO`。
- 不将上述 Provider 范围扫描扩大为“全仓 `@Deprecated` 清零”：`navigator-open-sdk/.../DirectoryApi.java` 与 `addons/task-assistant/.../TaskAssistantFacade.java` 仍有非 Provider 废弃契约，属于本切片之外。

### 未进行或需要运行态确认

- 本轮未查询或修改任何 GitLab project webhook。
- 本轮未查询、备份、迁移或删除 `code_review_config`、`code_review_record` 数据。
- 本轮未检查或停止独立 jar、容器、服务或其他 deployment。
- 本轮未采集 Echo 或旧 Provider API 的历史运行流量；删除依据是 Owner 确认的 dev-only 边界、仓内静态引用扫描和回归，不是“零调用”运行态证据。
- Repository CI 已对截至正式闸门的最新已验证实现 head 执行，但旧 Provider 真实 Task/Worker 的浏览器、凭据网络和当前本机上游 smoke 仍未运行；无安全的隔离 Provider Task fixture 时不使用共享 Worker/数据冒充证据。
- 当前宿主不可用 `pwsh`，Echo 相关 PowerShell migration 脚本仅做 literal diff 核对，parser 仍为 `not-run`。
- 本轮未验证仓库外 SPI 二进制实现；当前按 Owner 的“所有上游本机孵化”阶段假设推进，而非把仓库外不存在写成事实。

### 已批准决策

| 决策 | 状态 | 约束 |
|---|---|---|
| Code Review 物理移除 | `approved / executed` | 静态安全门已满足；若发现 GitLab、数据库或独立部署资源，暂停收口并重新决策 |
| Echo test fixture retain、默认制品 retire | `approved / implemented / local-and-hosted-passed` | test-only fixture 已先行，addon/reactor/launcher 已退出，`LocalEchoBusinessFunctionAdapterInvoker` 无 diff；版本正式门禁已执行并拒绝，专项模块签收仍待执行 |
| 旧 Provider API/SPI/DTO 同版本直接删除 | `approved / implemented / local-and-hosted-passed` | 本仓消费者已迁移，三组旧 route 与 Provider legacy bridge/DTO 已按独立提交删除；不设双路由窗口 |

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
- hosted 证据：Repository CI [run 29323068427](https://github.com/foggy-projects/Foggy-Navigator/actions/runs/29323068427) 首次在包含 Echo 移除的 head 上 7/7 jobs 通过；更新的 [run 29324741945](https://github.com/foggy-projects/Foggy-Navigator/actions/runs/29324741945) 于 head `9d03bee9` 再次 7/7 jobs `success`。这是 clean build/test 证据，不是 Echo 运行体验。
- 当前状态：`implementation-complete / local-and-hosted-passed / verification-partial`；版本正式质量/覆盖/签收门禁已执行，结论为 `ready-with-risks / needs-more-tests / rejected`；PowerShell parser、专项体验与模块级签收未执行。

## 切片 C：旧 Provider API、SPI 与 DTO

### 实施后迁移矩阵

| 旧能力 | 已落地替代 | 当前结果 |
|---|---|---|
| `/api/v1/claude-tasks/**` task lifecycle | `/api/v1/tasks/**` 的 create/get/list/page/search/cancel/respond/reconnect/resync/rewind/resume/delete/scan-checkpoints | 旧 Controller 与 controller test 已删除；PC/mobile 使用 unified task client |
| Claude worker sessions / conversation config | `/api/v1/tasks/workers/**` 与 `/api/v1/sessions/{sessionId}/config/**` | 旧 Controller 不再承载这些兼容子路由 |
| `/api/v1/codex-tasks/**` lifecycle | `/api/v1/tasks/**` typed task port | 旧 Controller 与 controller test 已删除 |
| Codex file hints / generated image | `/api/v1/tasks/{taskId}/file-hints` 与 `/api/v1/tasks/{taskId}/generated-images/{artifactId}` | `CodexTaskExtensionController` 先验证统一 task ownership/provider/runtime binding，再读取 Provider 私有记录 |
| Codex canary/soak / relay | `/api/v1/tasks/operations/codex-canary` 与 Worker `/api/v1/tasks/**` | 脚本、README、relay 与测试已迁移 |
| `/api/v1/langgraph-tasks/{taskId}` | `GET /api/v1/tasks/{taskId}` | Business Agent L3 已迁移 |
| `/api/v1/langgraph-tasks/{taskId}/approve` | `POST /api/v1/tasks/{taskId}/respond` | PC、Business Agent L3 与 bootstrap 已迁移；认证主体成为 reviewer，请求体身份字段被忽略 |
| Provider deprecated SPI bridge | 当前 typed `TaskLookupProvider` / `TaskCommandProvider` / `TaskListingProvider` / `WorkerSessionQueryProvider` | deprecated default/map/Object bridge 及 `TaskQueryProvider` 已删除，typed port 本身保留 |
| Claude/Codex 旧 task DTO/forms | `DispatchTaskDTO` + 内部 `ClaudeTaskCreateCommand` / `CodexTaskCreateCommand` | `TaskDTO`、`CreateTaskForm`、`CodexTaskDTO`、`CreateCodexTaskForm` 已删除 |

LangGraph 当前内部使用的 `LanggraphTaskDTO` 与 `CreateLanggraphTaskForm` 不是 deprecated 兼容模型，仍被 A2A/Business launcher 调用，不在本次物理删除范围。

### 提交、删除面与回滚锚点

| 提交 | 切片 | 主要删除/迁移面 | 回滚要求 |
|---|---|---|---|
| `d5ab80ee` | Echo production removal | 删除 `addons/echo-agent/**`，移除 root/launcher 装配，新增 test-only resolver fixture | 独立 revert 可恢复 addon 与默认 inventory；不涉及数据恢复 |
| `50351ada` | Provider SPI | 删 deprecated bridge、`TaskQueryProvider`、`DefaultA2aAgentRegistry` 与旧 contract tests；Provider 实现改用 typed port | 若 typed port 回归失败，在 HTTP/DTO 回滚前按依赖顺序恢复该提交 |
| `73d31a19` | Claude HTTP | 删 `ClaudeTaskController` 及旧 controller test，补齐 unified task/session route 并移除 SecurityConfig matcher | 只在 unified route 出现阻断缺口时 revert；恢复旧 route 后需重做 ownership 审查 |
| `97240642` + `fb11137d` | Codex HTTP | 先建 `CodexTaskExtensionController` 与迁移 canary/relay/PC，再删 `CodexTaskController` 及 SecurityConfig matcher | 按“删除提交→扩展路由提交”逆序 revert，避免同时丢失 file/image 能力 |
| `9f3f1422` | LangGraph HTTP/approval | 删 `LanggraphTaskController` / `ApproveTaskForm`，迁到 unified respond 并从可信主体派生 reviewer | 恢复旧 approve route 会重新引入请求体身份风险，回滚前必须重做安全决策 |
| `edee0fc4` | Claude DTO/form | 删 `TaskDTO` / `CreateTaskForm`，新增 `ClaudeTaskCreateCommand` | 与 Claude HTTP/SPI 提交按依赖顺序回滚 |
| `9008c554` | Codex DTO/form | 删 `CodexTaskDTO` / `CreateCodexTaskForm`，新增 `CodexTaskCreateCommand` | 与 Codex HTTP/SPI 提交按依赖顺序回滚 |

不设长期双路由兼容窗口。回滚只通过上述 Git 提交恢复已验证版本，不在当前源码中保留暗藏 legacy switch。

### 静态扫描结果

~~~bash
rg -n --hidden '/api/v1/(claude-tasks|codex-tasks|langgraph-tasks)|/(claude-tasks|codex-tasks|langgraph-tasks)' \
  . -g '!**/.git/**' -g '!**/target/**' -g '!**/node_modules/**' -g '!docs/**'
rg -n '@Deprecated' navigator-spi navigator-common session-module \
  addons/claude-worker-agent addons/codex-worker-agent addons/gemini-worker-agent addons/langgraph-biz-worker \
  -g '*.java' -g '!**/target/**'
~~~

两组扫描在上述限定范围内均为 0。第一组不扫描历史文档；第二组只证明 Provider 契约切片内无 `@Deprecated`，不代表全仓废弃 API 清零。

### 已执行验证与证据边界

- LangGraph 批次：8/8 reactor `SUCCESS`，定向 Java 68 tests 通过；主前端 type-check、`langgraphWorker.test.ts` 1/1 和 Business Agent L3 TypeScript `tsc --noEmit` 通过。审批负例覆盖 spoofed `reviewedBy` / `userId`、非 owner 和无 pending approval 拒绝。
- Claude DTO/form 批次：8/8 reactor `SUCCESS`，Claude 相关 367 tests 通过。
- Codex DTO/form 最终批次：8/8 reactor `SUCCESS`，依赖链共 1757 tests 通过，其中 Codex 371 tests。
- hosted：[Repository CI run 29323068427](https://github.com/foggy-projects/Foggy-Navigator/actions/runs/29323068427) 在 `9008c554` head 上 7/7 jobs `success`；最新 [run 29324741945](https://github.com/foggy-projects/Foggy-Navigator/actions/runs/29324741945) 在 `9d03bee9` head 上再次 7/7 jobs `success`，覆盖 Java clean test、全前端 clean checks、3 组 Node Worker 和 2 组 Python Worker。
- 手工/体验：真实 Provider Task 创建、恢复、取消、审批、file hints、generated image 与凭据网络 smoke 仍为 `not-run`；hosted CI 不等于这些体验通过。
- 当前状态：`implementation-complete / local-and-hosted-passed / experience-partial`；顶层正式质量/覆盖/签收已执行并给出 `ready-with-risks / needs-more-tests / rejected`，待真实 Provider Task 体验与模块级签收。

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
- Echo：定向 16/16 与 launcher 定向 6/6 tests 通过，`bash -n` 通过；两次 Repository CI 7/7 jobs 通过；版本 formal gate 已执行并拒绝，PowerShell parser 与模块级签收 `not-run`。
- 旧 Provider API/SPI/DTO：LangGraph、Claude、Codex 分批本地测试通过，可执行路由与 Provider `@Deprecated` 限定扫描为 0，截至正式闸门的最新已验证实现 head 对应 Repository CI 7/7 jobs 通过；真实 Provider Task 体验 `not-run`。
- `mvn -B clean verify`：本工作项尚未记录执行结果，不得以 launcher clean test 替代。

## 风险与总体回滚

| 风险 | 控制 | 回滚 |
|---|---|---|
| Owner 阶段假设与实际外部状态不一致 | 一旦发现共享/生产资源或仓库外消费者，暂停对应切片并重新决策 | 恢复对应 module/route，另立外部迁移记录 |
| Code Review 外部 webhook/数据/deployment 未被静态搜索发现 | 明确记为未检查，不宣称运行态零使用 | 恢复源码；外部资源按其独立变更记录恢复 |
| Echo 退出 launcher 破坏测试或 discovery | test-only fixture 先行、默认制品定向构建测试；真实启动/浏览器差异留待后续补证 | 恢复 launcher dependency/装配条件 |
| 直接删除旧 API 时遗漏安全语义 | 消费者矩阵、ownership/可信主体负例、按路由小批次收口；LangGraph reviewer 已从认证主体派生 | 仅恢复失败 route 批次；回滚旧 approve 前重做安全决策 |
| SPI/DTO 删除造成本仓 Provider 编译失败 | typed replacement 先行、本地定向 clean test 与 hosted Java clean test 通过 | 按 `50351ada`、`edee0fc4`、`9008c554` 的依赖顺序恢复 |
| 不同切片同提交难以回滚 | A/B/C 独立提交、独立 check-in | 只 revert 失败切片 |

## 完成判据

- [x] Code Review 22 个 tracked files 已物理移除，本仓源码/构建接线扫描无引用，launcher clean reactor 通过。
- [x] Code Review 未检查的 GitLab、数据库和独立 deployment 状态已显式记录，没有虚构流量证据。
- [x] Code Review 切片已纳入版本质量/覆盖/签收，版本结论为 `rejected`；最终 diff/链接结果由 Progress 记录。
- [x] Echo test-only fixture 已先行，默认 launcher 不再注册 Echo，`LocalEchoBusinessFunctionAdapterInvoker` 无 diff。
- [x] 旧 API method/path 及 Provider deprecated SPI/DTO 已有本仓消费者、替代、迁移与提交回滚记录。
- [x] LangGraph 审批已通过统一 task ownership 与可信主体负例；通用 task 恢复/取消 ownership 证据由 GOV-003 记录，不在本工作项重复宣称。
- [x] 三组旧 SecurityConfig matcher、Controller、Provider legacy SPI/DTO、本仓 tests/scripts 已按切片同步收口。
- [x] 自动化与 clean build 已有本地/hosted 证据，版本正式门禁已执行并拒绝。
- [ ] 真实 Provider Task 手工体验和模块级签收尚未完成。
- [x] 结果已回写 [Progress](../progress.md)，并完成版本要求的质量检查、覆盖审计和签收流程；拒绝结论保留。
- [x] 未触碰明确“暂时不要删除”的能力。

## 生产路由与外部契约状态

- production_routing_changed: no
- launcher_default_agent_inventory_changed: yes
- external_contract_changed: yes
- code_review_slice: implementation-complete / verification-partial
- echo_slice: implementation-complete / local-and-hosted-passed / verification-partial
- legacy_provider_contract_slice: implementation-complete / local-and-hosted-passed / experience-partial
- production_enablement: not-applicable-at-current-dev-stage

当前无生产环境，因此 `production_routing_changed: no` 保留；但 Echo 已从默认 launcher 清单退出，三组旧 Provider HTTP 契约也已物理移除，必须同时读取 `launcher_default_agent_inventory_changed: yes` 与 `external_contract_changed: yes`。local/hosted 通过不等于真实 Provider 体验、验收通过或生产批准；当前正式签收结论为 `rejected`。
