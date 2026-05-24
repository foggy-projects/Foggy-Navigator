# Skill Bundle Sync Design

## 文档作用

- doc_type: workitem
- version: 1.1.3-SNAPSHOT
- status: implemented
- date: 2026-05-12
- priority: P1
- source_type: github-issue-102-design
- intended_for: navigator-owner | upstream-backend-developer | upstream-llm-coding-agent | skill-owner | reviewer
- purpose: 统一 ClientApp 公共 Skill 与 accountId 私有 Skill 的注册模型，并给 SDK、CLI 和 runtime-token 入口定义一致的同步能力

## 背景

上游 TMS 当前通过 `navigator-open-sdk` 的 BusinessAgent API 注册公共 Skill、Business Function、ClientApp grant 和 Skill function allowlist。LangGraph Biz Worker 已经支持 accountId 私有 Skill 目录和加载优先级，但 Navi Java 控制面与 CLI 还没有给上游提供“注册账号私有 Skill”的正式入口。

两类 Skill 的主体参数高度一致：

1. `skillId`、`name`、`description`、`markdownBody`、`contextVisibility`。
2. `resources`，包含 `references/**` 和 `assets/**`。
3. `functions`，表示 Skill 可见和可调用的 Business Function 集合。
4. `status` 和物化到 worker 的需求。

因此本轮不再继续扩展两套概念，而是新增统一的 `SkillBundle` 同步模型。

## 目标

1. 统一描述 ClientApp 公共 Skill 和 accountId 私有 Skill。
2. 保留控制面入口给上游后端或平台管理员同步公共 Skill。
3. 增加 runtime-token 入口，允许当前 upstream user 只同步自己的 account private Skill。
4. CLI 支持从 manifest 同步 Skill Bundle，便于上游项目本地维护和交付。
5. worker 仍只接收 Navi Java 内部物化请求；上游不能直接调用 worker。

## 统一模型

建议新增持久化模型：

```text
SkillBundle
  tenantId
  clientAppId
  scope = CLIENT_APP_PUBLIC | ACCOUNT_PRIVATE
  accountId
  skillId
  name
  description
  markdownBody
  contextVisibility = isolated | summary
  resourcesJson
  functionsJson
  status = ENABLED | DISABLED
  createdBy
  createdAt
  updatedAt
```

唯一键：

```text
tenantId + clientAppId + scope + accountId + skillId
```

其中 `CLIENT_APP_PUBLIC` 的 `accountId` 规范化为空字符串，避免数据库 nullable unique 语义差异。

## Scope 语义

### CLIENT_APP_PUBLIC

1. 属于一个 ClientApp。
2. 对该 ClientApp 已授权的上游用户可见。
3. worker 物化到：

```text
<skills_root>/public/apps/<client_app_id>/<skill_id>
```

4. 只能通过控制面 credential 同步。

### ACCOUNT_PRIVATE

1. 属于一个 ClientApp 下的某个 upstream user / accountId。
2. 只对当前 accountId 可见。
3. 加载优先级高于 app public skill。
4. worker 物化到：

```text
<data_root>/accounts/<account_id>/agent/skills/<skill_id>
```

5. runtime-token 入口只能同步当前 `X-Upstream-User-Id` 对应的 account private skill。

## API 设计

### 控制面同步

```http
POST /api/v1/business-agent/skill-bundles/sync
```

认证：

1. `TENANT_ADMIN`。
2. 可由 `navigator-open-sdk` 使用 admin token 或 provisioning API key 调用。

请求核心字段：

```json
{
  "clientAppId": "tms-x3",
  "scope": "CLIENT_APP_PUBLIC",
  "accountId": null,
  "skillId": "order-agent",
  "name": "Order Agent",
  "description": "Order operation assistant",
  "contextVisibility": "summary",
  "markdownBody": "# Order Agent\n...",
  "resources": [],
  "functions": [
    {"functionId": "order.close.apply", "status": "ENABLED"}
  ],
  "status": "ENABLED",
  "materialize": true
}
```

控制面允许：

1. 同步 `CLIENT_APP_PUBLIC`。
2. 同步指定 `ACCOUNT_PRIVATE`，用于管理员代管或批量初始化。
3. 校验 ClientApp、Function 和 ClientApp Function Grant。

### Runtime 同步

```http
POST /api/v1/open/accounts/me/skill-bundles/sync
```

认证：

1. `X-Client-App-Key`。
2. `X-Client-App-Access-Token`。
3. `X-Upstream-User-Id`。

约束：

1. 固定 `scope=ACCOUNT_PRIVATE`。
2. `accountId` 固定为当前 `X-Upstream-User-Id`，请求体不能覆盖。
3. 不允许同步 public skill。
4. 不允许给未授权给当前 ClientApp 的 Business Function 建 allowlist。
5. 仍由 Navi Java 调 worker 完成物化。

## SDK / CLI

SDK 新增：

```java
client.businessAgent().syncSkillBundle(SyncSkillBundleForm form);
client.agents().syncMyAccountSkillBundleWithClientAppAccessToken(form, appKey, accessToken, upstreamUserId);
```

`contextVisibility` 为可选字段，默认由服务端按 `isolated` 处理。普通业务 skill 首版只允许 `isolated` 或 `summary`；`passthrough` 是平台内置 root/function frame 保留策略，SDK/CLI 可传但服务端会降级或拒绝，具体以控制面 policy 为准。

CLI 新增：

```powershell
navi upstream skill sync --scope client-app-public --manifest .navigator/skill-bundle.json
navi upstream skill sync --scope account-private --manifest .navigator/account-skill.json --upstream-user-id <id>
```

`client-app-public` 使用 `NAVI_CONTROL_API_KEY`，并由服务端强制绑定到该 credential 的 `clientAppId`；`NAVI_ADMIN_TOKEN` 仅作为 Navigator 内部 fallback。`NAVI_ADMIN_API_KEY` 不再作为普通 `X-API-Key` fallback。`account-private` 使用项目本地 `.navigator/upstream.env` 中的 runtime credential，并自动交换 runtime access token。

## 函数权限边界

Skill Bundle 的 `functions` 不创建 Business Function，也不扩大 ClientApp 权限。同步时必须满足：

1. Business Function 已存在且启用。
2. ClientApp 已被授予对应 Function。
3. Skill Bundle function status 只能是 `ENABLED` 或 `DISABLED`。
4. runtime 入口只能引用当前 ClientApp 已授权 Function。

首版实现会把 bundle functions 同步回现有 `skill_function_allowlist`，让现有 BusinessFunctionAuthorizationService 的 `checkSkillFunctionAccess` 不需要新增权限边界即可继续工作。account private skill 如果使用与 public skill 相同的 `skillId`，当前 function allowlist 仍按 `tenantId + skillId + functionId` 生效；后续如需不同账号同名 skill 拥有不同 function 集合，再升级为 bundle-scoped allowlist。

## 评审结论

当前代码与该方案匹配：

1. worker 已支持 `scope=account` 物化，并已有 account private 最高优先级加载逻辑。
2. Navi Java 已有公共 Skill、Function Grant、User Grant、runtime-token 和 skill artifact 读取链路。
3. 数据库当前使用 `ddl-auto:update`，新增实体可随版本自动建表；测试路径可通过 mock repository 覆盖。
4. 旧 `createSkill` / `grantSkillToClientApp` API 可保留，新的 Skill Bundle API 作为推荐入口，不阻断 TMS 现有接入。

可直接推进开发。首版不处理跨账号同名 private skill 的差异化 function allowlist，避免提前扩大 Business Function 授权模型。

## 实施计划

1. `business-agent-module` 新增 `SkillBundleEntity`、repository、DTO/form，并在 `SkillRegistryService` 中实现 sync 和 worker materialize。
2. `SkillRegistryController` 增加控制面 `POST /skill-bundles/sync`。
3. `OpenApiController` 增加 runtime `POST /accounts/me/skill-bundles/sync`。
4. `navigator-open-sdk` 增加 DTO/form/API 封装。
5. `UpstreamCli` 增加 `skill sync` 命令，读取 manifest 并按 scope 选择控制面或 runtime 入口。
6. 更新 `navigator-upstream-cli` skill，安装篇继续链接独立文档，不把安装内容塞进 `SKILL.md`。

## 验收标准

1. 控制面可以同步并物化 ClientApp public skill。
2. runtime-token 可以同步并物化当前 upstream user 的 account private skill。
3. runtime 入口未传 `X-Upstream-User-Id` 或引用未授权 Function 时 fail-closed。
4. CLI `skill sync` 不打印 secret/token，错误输出走脱敏。
5. 现有 `createSkill`、`grantSkillToClientApp`、`ask`、`skill tree/read` 测试不回归。
6. 新增单元测试覆盖 public sync、account private sync、CLI manifest sync。

## Progress

- [x] 设计落档
- [x] 架构评审
- [x] 后端模型与 API
- [x] SDK / CLI 封装
- [x] skill 文档更新
- [x] 测试验证

## Verification Evidence

- `mvn -pl business-agent-module test`：通过，214 tests。
- `mvn -pl navigator-open-sdk test`：通过，32 tests。
- `mvn -pl addons/langgraph-biz-worker -Dtest=LanggraphBusinessAgentWorkerTaskLauncherTest test`：通过，4 tests。
- `tools/langgraph-biz-worker/.venv/Scripts/pytest.exe tests/test_account_skill_routing.py`：通过，9 tests。
- `powershell -ExecutionPolicy Bypass -File tools\navigator-upstream-cli\dist\package.ps1`：通过，生成 `navigator-upstream-cli-1.0.0-SNAPSHOT-windows.zip`。
- `powershell -ExecutionPolicy Bypass -File tools\navigator-upstream-cli\dist\upload.ps1 -Version 1.0.0-SNAPSHOT -AllowSameVersion`：通过，OBS `latest.json` 已刷新到 2026-05-12，SHA256 为 `a398b947898da2d7be757338056e3f5947f9aeb7dd45fc88ccbb126504db443b`。
- 临时目录远程安装 smoke：通过，`navi.ps1 version` 和 `upstream config check` 成功。

## Implementation Notes

- LangGraph Biz Worker Java launcher 已把 `upstreamUserId` 同步写入 worker context 的 `upstreamUserId`、`accountId` 和 `account_id`。
- Python root graph 在路由和执行 skill 时优先使用 context 中的 upstream account id，再回退到任务 `user_id`，避免 account private skill 被 Navigator 任务 owner 误路由。
- `tools/langgraph-biz-worker/tests/test_account_skill_routing.py` 已补 account context 优先级测试，并通过项目 `.venv` 执行。
