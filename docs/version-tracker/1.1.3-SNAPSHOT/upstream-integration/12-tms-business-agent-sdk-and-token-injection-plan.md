# TMS Business Agent SDK And Token Injection Plan

## 文档作用

- doc_type: implementation-plan
- version: 1.1.3-SNAPSHOT
- status: draft
- date: 2026-05-04
- intended_for: navigator-sdk-owner | business-agent-owner | execution-agent | reviewer
- purpose: 将 TMS 接入 AI Agent 所需的 SDK、用户凭据注入、REST Adapter 上下文 header 与 E2E 验证拆成可执行阶段

## 安排结论

本计划继续纳入 `1.1.3-SNAPSHOT`，作为 Business Agent 上游可接入化收尾。

纳入 1.1.3：

1. `navigator-open-sdk` Business Agent 控制面封装。
2. 上游用户凭据的服务端受控存储。
3. REST Adapter 调用上游 HTTP API 时注入用户凭据 header。
4. REST Adapter 注入 Navigator 调用上下文 header。
5. TMS mock E2E 与 LLM-facing 安全边界验证。
6. upstream integration 文档补充。

不纳入 1.1.3：

1. token refresh / rotation / revoke。
2. 正式 secret store 接入。
3. 多 upstream credential provider 策略。
4. TMS 内部权限模型、业务审计与数据归属校验。

## 设计基线

TMS 不作为 Biz Worker。TMS 是外部业务系统和业务函数提供方，通过 HTTP 暴露受控业务 API。

Navigator 负责：

1. ClientApp 接入身份。
2. upstream user / Skill / Function / Model 授权。
3. Worker Gateway。
4. approval suspension/resume。
5. 平台审计。
6. 调用 TMS HTTP API 时注入服务端受控凭据与上下文 header。

TMS 负责：

1. 设计 BusinessObject 与 BusinessFunction。
2. 设计 input/output schema。
3. 暴露 HTTP API。
4. 校验 TMS 用户 token、租户、机构、业务权限。
5. 做最终业务归属校验。
6. 记录 TMS 业务审计。

## 命名与安全边界

1. 对外业务接入身份继续使用 `ClientApp`。
2. 外部业务用户继续使用 `upstreamUserId`。
3. 上游用户凭据使用 `upstream user credential` 语义，不混入 grant 语义。
4. TMS 运单 LLM-facing 标识必须使用 `orderIdentifier`，类型为 string。
5. `expressOrderId` 只能作为 TMS 内部字段，不允许进入 LLM-facing schema、BusinessFunction input schema 或前端可填参数。
6. 用户 token 不得进入 LLM prompt、tool schema、manifestJson、adapterConfigJson、前端 DTO、普通日志、runtime audit output。
7. 用户 token 不得由 LLM 参数、Manifest header、前端状态传入，只能由 Navigator 服务端按 `tenantId + clientAppId + upstreamUserId` 解析并注入。

## Stage 10A：navigator-open-sdk Business Agent Control Plane API

目标：让上游 LLM / TMS 后端可以用 SDK 完成 Business Agent 控制面初始化，避免长期手写 REST。

范围：

1. 增加 `client.businessAgent()` 入口。
2. 封装 ClientApp / provisioning credential / runtime credential API。
3. 封装 model config grant API。
4. 封装 Skill / Skill function allowlist / Skill grant API。
5. 封装 upstream user grant API。
6. 封装 BusinessObject API。
7. 封装 BusinessFunction manifest import / function grant API。
8. 封装 Business task create/query API。
9. 封装 approval resume API。

要求：

1. 以 controller 源码和 `08-rest-api-reference.md` 为准，不凭文档猜字段。
2. SDK 需要明确控制面鉴权模式：`X-API-Key` 适用于 `sk-*` API key；当前登录态/admin JWT 必须在 SDK builder 中显式建模为 Bearer token。
3. 如果 `X-API-Key` 不能形成 `CurrentUser` / `TENANT_ADMIN` 上下文，本阶段必须先给出鉴权结论，不得硬写无法运行的 SDK 示例；不得把 JWT 复用为 `apiKey(...)`。
4. SDK DTO/Form 不得新增 token 泄露字段。
5. 每组 API 至少有最小单元测试或 smoke test，验证 path、method、header、body。
6. 不封装 BizWorkerPool 管理 API。Worker Pool 属于 Navigator 侧预配置能力，TMS 上游 SDK 只消费创建 task 所需的 `workerPoolId`。

验收：

1. `mvn test -pl navigator-open-sdk -am` 通过。
2. `mvn compile -pl launcher -am -DskipTests` 通过。
3. SDK 示例能表达创建 ClientApp、注册 BusinessObject/BusinessFunction、授权 Function、创建 task、审批 resume 的最小链路。
4. 敏感字段搜索命中必须分类解释：内部实现允许，SDK public DTO / LLM-facing 文档 / frontend-facing DTO 不允许。
5. 完成后更新 `08-implementation-plan.md` 的 Stage 10A 状态；若鉴权模式无法确认，必须标记 blocker，不得标为 completed。

## Stage 10B：Upstream User Credential + REST Adapter Header Injection

目标：Navigator 在调用 TMS 业务函数时，按当前 task 上下文解析对应 upstream user credential，并注入到上游 HTTP 请求。

落地模型：

1. 首版复用 `ClientAppUpstreamUserGrantEntity`，将 upstream user token 绑定在授权关系上。
2. 唯一维度仍为 `tenantId + clientAppId + upstreamUserId`。
3. 控制面 Form 可提交 token；控制面 DTO 不返回 token。
4. 开发阶段先简化 token 有效性，后续可将该字段迁移到 secret store / 加密存储。

REST Adapter 注入规则：

1. 默认 token header 使用配置项控制，例如：
   - `foggy.navigator.business.agent.upstreams.<upstream-ref>.user-token-header=X-TMS-Agent-Token`
2. token header 由服务端注入，不允许 Manifest 任意声明或覆盖。
3. Navigator 上下文 header 由服务端注入，不允许 LLM 或 Manifest 伪造：
   - `X-Navigator-Tenant-Id`
   - `X-Navigator-Client-App-Id`
   - `X-Navigator-Upstream-User-Id`
   - `X-Navigator-Task-Id`
   - `X-Navigator-Session-Id`
   - `X-Navigator-Function-Id`
   - `X-Navigator-Function-Version`
4. 若未来选择 `Authorization: Bearer <token>`，只能由服务端受控 credential injection 注入；Manifest 仍不得写 Authorization。

实现建议：

1. 引入内部 invocation context，将 Function runtime context 与 task scoped token context 合并后传给 adapter。
2. 不要把 `BusinessTaskScopedTokenDTO` 暴露给 LLM-facing DTO。
3. adapter 记录日志时不得打印 token 值。

验收：

1. TMS mock API 能收到 `X-TMS-Agent-Token` 或配置化 token header。
2. TMS mock API 能收到 Navigator 上下文 header。
3. schema/list/invoke response 不包含用户 token、`adapterConfigJson`、`manifestJson`。
4. audit 表不保存用户 token。
5. `mvn test -pl business-agent-module -am` 通过。

## Stage 10C：TMS Mock E2E + LLM-Facing Safety Verification

目标：用最小 TMS mock 服务验证 SDK 初始化、业务函数注册、task 创建、REST Adapter 调用和安全边界。

范围：

1. 新增或扩展 TMS mock E2E。
2. 使用 `orderIdentifier` 作为 LLM-facing 运单字段。
3. 验证 `expressOrderId` 不出现在 manifest input schema、tool schema、前端可填参数文档。
4. 验证上游 token 只出现在 mock HTTP request header 中，不出现在响应、日志、audit DTO。
5. 补充 upstream integration 文档，说明 TMS 类业务系统推荐接入方式。

验收：

1. `mvn test -pl navigator-open-sdk -am` 通过。
2. `mvn test -pl business-agent-module -am` 通过。
3. `mvn test -pl addons/langgraph-biz-worker -am` 通过。
4. `mvn compile -pl launcher -am -DskipTests` 通过。
5. 敏感字段检查通过：

```powershell
rg -n "task_scoped_token|adapterConfigJson|manifestJson|X-TMS-Agent-Token|Authorization|expressOrderId" business-agent-module navigator-open-sdk addons/langgraph-biz-worker docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration
```

命中项必须逐条解释：内部实现允许，LLM-facing / frontend-facing / ordinary DTO / audit output 不允许。

实现记录（2026-05-04）：

1. 新增 TMS 最小接入样例文档：[13-tms-minimal-onboarding-sample.md](./13-tms-minimal-onboarding-sample.md)。
2. SDK smoke test 已覆盖 TMS onboarding sequence，验证 BusinessObject、Function import、Skill/User/Function grant 与 task 创建请求。
3. Business Agent E2E 已使用真实本地 mock TMS HTTP 服务验证 REST Adapter 出站调用。
4. `orderIdentifier` 已作为 LLM-facing 运单字段进入 input schema、schema summary、adapter body 和 mock response。
5. `expressOrderId` 只允许出现在安全说明与搜索校验中，不进入样例 payload、Worker schema 或前端可填参数。
6. 验收记录：[stage-10c-tms-mock-e2e-and-safety-acceptance.md](../acceptance/stage-10c-tms-mock-e2e-and-safety-acceptance.md)。

## Stage 10D：Upstream Auto Bootstrap Contract

目标：让上游 LLM 不再手工复制 REST 或 SDK 调用，而是通过非敏感 manifest、本地 secret env 和 SDK bootstrap runner 自动完成接入初始化。

范围：

1. 定义上游可提交的 manifest 模板。
2. 定义本地 secret env 模板。
3. 定义上游 bootstrap runner 的职责与禁止事项。
4. 更新 personal `navigator-upstream-llm-integration` skill，使上游 LLM 优先读取自动化 runbook。
5. 明确 Worker Gateway 仍是 Navigator 内部 API，上游 bootstrap 不直接调用。

实现记录（2026-05-05）：

1. 新增自动化契约：[14-upstream-auto-bootstrap-contract.md](./14-upstream-auto-bootstrap-contract.md)。
2. 更新 TMS 最小接入样例，链接自动化契约与 personal skill runbook。
3. personal skill 新增 TMS auto bootstrap runbook、manifest template 与 env template。
4. 验收记录：[stage-10d-upstream-auto-bootstrap-contract-acceptance.md](../acceptance/stage-10d-upstream-auto-bootstrap-contract-acceptance.md)。

## Stage 10A 开发会话提示词

```markdown
# Navigator Stage 10A：补齐 navigator-open-sdk Business Agent 控制面 API

你正在处理 Navigator 自研项目：

- 工作区：D:\foggy-projects\Foggy-Navigator-wt-qd-win11-dev
- 版本：1.1.3-SNAPSHOT
- 目标：为上游 LLM / TMS 后端补齐 navigator-open-sdk 的 Business Agent 控制面 API，避免上游长期手写 REST。

请先阅读：

- docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/00-overview.md
- docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/08-rest-api-reference.md
- docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/09-security-boundaries.md
- docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/11-llm-sdk-usage-guide.md
- docs/version-tracker/1.1.3-SNAPSHOT/upstream-integration/12-tms-business-agent-sdk-and-token-injection-plan.md
- navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/NavigatorClient.java
- navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/api/
- business-agent-module/src/main/java/com/foggy/navigator/business/agent/controller/
- business-agent-module/src/main/java/com/foggy/navigator/business/agent/model/form/
- business-agent-module/src/main/java/com/foggy/navigator/business/agent/model/dto/

任务范围：

1. 在 `navigator-open-sdk` 新增 `client.businessAgent()` 聚合入口。
2. 基于 controller 源码与 Form/DTO 实际字段封装以下 API：
   - ClientApp / provisioning credential / runtime credential
   - Model Config Grant
   - Skill / Skill Function allowlist / Skill Grant
   - Upstream User Grant
   - BusinessObject
   - BusinessFunction manifest import / Function Grant
   - Business Task create/query
   - Approval Resume
3. 明确 SDK 鉴权模式：
   - `apiKey("sk-*")` 发送 `X-API-Key`，要求解析出的用户具备 `TENANT_ADMIN`。
   - `adminToken(jwt)` / `bearerToken(jwt)` 发送 `Authorization: Bearer <jwt>`，用于本地 sandbox 或控制面 admin JWT。
   - 不允许把 JWT 复用为 `apiKey(...)`。
   - 如果 `X-API-Key` 不能形成 `CurrentUser` / `TENANT_ADMIN` 上下文，本阶段必须先给出鉴权结论，不得硬写无法运行的 SDK 示例。
4. 增加最小测试或 smoke test，至少验证每组 API 的 HTTP method、path、auth header、body 序列化。
5. 更新 upstream integration 文档中的 SDK 使用说明。
6. 完成后更新 `08-implementation-plan.md` 的 Stage 10A 状态；若鉴权模式无法确认，标记 blocker，不得标为 completed。

边界：

1. 本阶段不实现 TMS 用户 token 存储。
2. 本阶段不修改 REST Adapter token/header 注入。
3. 本阶段不实现 token refresh / revoke / rotation。
4. 不允许在 SDK DTO/Form 中引入会泄露用户 token 的字段。
5. 不封装 BizWorkerPool 管理 API；TMS 上游 SDK 只消费已配置的 `workerPoolId`。

安全要求：

1. token 不进入 LLM prompt。
2. token 不进入 tool schema。
3. token 不进入 manifestJson / adapterConfigJson 对外 DTO。
4. token 不进入前端状态或普通日志。

验证命令：

```powershell
mvn test -pl navigator-open-sdk -am
mvn compile -pl launcher -am -DskipTests
rg -n "task_scoped_token|adapterConfigJson|manifestJson|X-TMS-Agent-Token|Authorization" navigator-open-sdk business-agent-module
```

敏感字段搜索如有命中，必须逐条分类说明：内部实现允许，SDK public DTO / LLM-facing 文档 / frontend-facing DTO 不允许。

交付报告请包含：

1. SDK 新增 API 列表。
2. 新增/修改文件。
3. 鉴权模式结论。
4. 测试命令和结果。
5. 敏感字段搜索命中解释。
6. 未完成或需要 Stage 10B 继续处理的事项。
```
