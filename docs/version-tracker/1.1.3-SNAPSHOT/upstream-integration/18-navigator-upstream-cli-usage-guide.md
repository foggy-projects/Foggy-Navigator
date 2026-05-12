# Navigator Upstream CLI Usage Guide

## 文档作用

- doc_type: integration-guide
- version: 1.1.3-SNAPSHOT
- status: implemented
- date: 2026-05-12
- intended_for: upstream-llm-coding-agent | upstream-backend-developer | reviewer
- purpose: 指导上游 Agent 和开发者使用 Navigator Upstream CLI 完成本地联调与安全检查，避免手写 curl 或误调 internal API

## 适用范围

CLI 首版用于本地联调和上游 BFF/前端消息流复现。它不是平台全量管理工具，也不替代正式运维发布体系。

推荐上游项目先按独立安装篇安装到本项目目录：

- [19-navigator-upstream-cli-install-update.md](./19-navigator-upstream-cli-install-update.md)

当前支持：

```text
config check
runtime-token
ensure-grant
ask
messages --poll
sessions
session-messages
verify-agent-readiness
verify-agent-grant
skill tree
skill read
skill sync
```

TMS test-only helper 当前保持可选延期；如果命令不可用，CLI 会返回非敏感错误，不会打印 token。

上游 Agent 应在 live actor smoke 前优先执行 `upstream verify-agent-readiness`，确认 agent 注册、ClientApp skill grant、upstream user grant 与 model config grant 均可用；需要查看已授权 skill 交付内容时，再通过 `upstream skill tree` 和 `upstream skill read` 读取文件树与指定文本切片。能力设计见 [20-navigator-upstream-cli-readiness-and-skill-artifact.md](./20-navigator-upstream-cli-readiness-and-skill-artifact.md)。

## 启动方式

上游项目安装后，在上游项目根目录执行：

```powershell
.\tools\navigator-upstream\navi.ps1 upstream config check
```

Navigator 仓库内开发调试仍可使用 Maven 入口：

```powershell
mvn -q -pl navigator-open-sdk exec:java '-Dexec.args=upstream config check'
```

## 配置来源

读取优先级：

1. CLI 参数。
2. 环境变量。
3. 显式 `--profile` 文件，或项目本地 `.navigator/upstream.env`。
4. 本地默认值。

默认值：

```properties
NAVI_BASE_URL=http://localhost:8112
TMS_WEB_BASE_URL=http://localhost:12580
BASIC_BASE_URL=http://localhost:10001
NAVI_TENANT_ID=88800
NAVI_CLIENT_APP_ID=capp_2852124a-48f7-4098-9d5e-33eb736c4375
NAVI_AGENT_CODE=tms-agent-v305
NAVI_MODEL_CONFIG_ID=
NAVI_POLL_INTERVAL_SECONDS=4
```

敏感值只能来自环境变量或 gitignored profile：

```properties
NAVI_CLIENT_APP_SECRET=
NAVI_CLIENT_APP_ACCESS_TOKEN=
NAVI_ADMIN_TOKEN=
NAVI_ADMIN_API_KEY=
TMS_STAFF_SESSION_TOKEN=
```

为了兼容本机 sandbox runbook，CLI 也会把以下 profile/env 字段映射到 `NAVI_*` 标准字段：

```properties
NAVIGATOR_BASE_URL -> NAVI_BASE_URL
NAVIGATOR_TENANT_ID -> NAVI_TENANT_ID
CLIENT_APP_ID -> NAVI_CLIENT_APP_ID
CLIENT_APP_KEY -> NAVI_CLIENT_APP_KEY
CLIENT_APP_SECRET -> NAVI_CLIENT_APP_SECRET
CLIENT_APP_RUNTIME_TOKEN -> NAVI_CLIENT_APP_ACCESS_TOKEN
NAVIGATOR_ADMIN_TOKEN -> NAVI_ADMIN_TOKEN
NAVIGATOR_ADMIN_API_KEY -> NAVI_ADMIN_API_KEY
```

## Profile 安全

上游项目安装后默认使用：

```text
.navigator/upstream.env
```

安全规则：

1. 安装脚本会把 `.navigator/upstream.env` 与 `.navi-upstream.env` 写入上游项目 `.gitignore`。
2. `config check --profile <file>` 会检查 profile 是否被 git ignore 覆盖；未覆盖时拒绝通过。
3. `temp/**` 下的临时 profile 允许用于本地调试，该目录也被仓库 ignore。
4. CLI 输出只显示字段名、非敏感值和敏感值脱敏摘要。
5. 不要用 `echo $env:...`、截图、日志或文档打印真实 token。
6. 多个上游项目不要共用全局 profile；每个项目维护自己的 `.navigator/upstream.env`。

## 最小联调流程

### 1. 检查配置

```powershell
.\tools\navigator-upstream\navi.ps1 upstream config check
```

如果 profile 不在 `.gitignore` 覆盖范围内，先移动到 `.navigator/upstream.env` 或 `temp/**`。

### 2. 交换 Runtime Access Token

```powershell
.\tools\navigator-upstream\navi.ps1 upstream runtime-token --write-profile
```

输出会脱敏显示 access token，并把完整 `NAVI_CLIENT_APP_ACCESS_TOKEN` 写回当前项目的 gitignored `.navigator/upstream.env`。CLI 不会把完整 token 打印到终端。

如果 `.navigator/upstream.env` 中已有 `NAVI_CLIENT_APP_KEY` 与 `NAVI_CLIENT_APP_SECRET`，`verify-agent-readiness`、`ask`、`messages`、`sessions`、`session-messages`、`skill tree`、`skill read`、`account-private skill sync` 会在内存中自动交换新的 runtime access token；因此常规 smoke 不必手工复制 token。`NAVI_CLIENT_APP_ACCESS_TOKEN` 主要用于缺少 secret 的环境或需要显式缓存 token 的场景。

可选模型配置可放入 profile：

```properties
NAVI_MODEL_CONFIG_ID=<modelConfigId>
```

命令行 `--model-config-id` 优先级高于 profile/env 中的 `NAVI_MODEL_CONFIG_ID`。

### 3. 确保当前上游用户授权

```powershell
.\tools\navigator-upstream\navi.ps1 upstream ensure-grant --upstream-user-id <id>
```

约束：

- `ensure-grant` 必须使用 `NAVI_ADMIN_TOKEN` 或 `NAVI_ADMIN_API_KEY` 这类控制面凭据。
- 不允许使用 ClientApp runtime access token 做授权。
- 只处理当前指定 `upstreamUserId`，不得枚举或批量授权上游全部用户。

### 4. 发起 Ask

```powershell
.\tools\navigator-upstream\navi.ps1 upstream ask --upstream-user-id <id> --message "帮我检查订单状态"
```

`ask` 只发送 runtime access token 和 `X-Upstream-User-Id`，不会发送 ClientApp secret。

续聊与上游会话扩展数据：

```powershell
.\tools\navigator-upstream\navi.ps1 upstream ask `
  --upstream-user-id <id> `
  --context-id <contextId> `
  --message "继续分析这个订单" `
  --client-context-json '{"upstreamConversationId":"tms-ai-10001","bizObjectType":"order","bizObjectId":"SO-10001"}'
```

也可以把 JSON 对象放到文件中：

```powershell
.\tools\navigator-upstream\navi.ps1 upstream ask `
  --upstream-user-id <id> `
  --message "查询订单状态" `
  --client-context-file .\navigator-client-context.json
```

`clientContext` 是 `POST /ask` 的顶层字段，只写入 Navigator 会话摘要，不进入 Worker metadata 或 LLM prompt。

### 5. 轮询 Task Messages

```powershell
.\tools\navigator-upstream\navi.ps1 upstream messages --task-id <taskId> --poll --interval 4
```

停止条件：

- `COMPLETED`
- `FAILED`
- `CANCELED`
- `CANCELLED`

如果后续 message API 提供 `terminal=true`，它只能作为补充信号，不能替代 task status 终态判断。

### 6. 查询会话

```powershell
.\tools\navigator-upstream\navi.ps1 upstream sessions
.\tools\navigator-upstream\navi.ps1 upstream session-messages --context-id <contextId>
```

## 安全红线

CLI、上游 Agent 和 BFF 均不得：

- 调用 `/internal/worker-gateway/v1/**`。
- 在输出、日志、文档或 LLM prompt 中打印 token、secret、runtime access token、upstream user token。
- 将 `task_scoped_token`、`adapterConfigJson`、`manifestJson` 暴露给前端或 LLM。
- 把 `ask` 改成自动创建未知 upstream-user grant。
- 将 ClientApp secret 发送到 `/api/v1/open/agents/{agentId}/ask`。
- 把 `runtime-token --write-profile` 指向未被 gitignore 覆盖的 profile。

## 验证命令

Navigator 工作区内至少运行：

```powershell
mvn test -pl navigator-open-sdk
powershell -ExecutionPolicy Bypass -File tools\navigator-upstream-cli\dist\package.ps1
mvn test -pl business-agent-module -Dtest=ClientAppRuntimeCredentialResolverTest
mvn test -pl addons/claude-worker-agent -am
mvn -q -pl navigator-open-sdk exec:java '-Dexec.args=upstream config check'
rg -n "/internal/worker-gateway|task_scoped_token|adapterConfigJson|manifestJson" navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/cli navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/api/AgentApi.java navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/internal/HttpHelper.java
```

真实联调时再补：

```text
install into upstream project -> runtime-token --write-profile -> verify-agent-readiness -> ensure-grant -> ask -> messages --poll
```

结果记录到 [17-navigator-upstream-cli-requirement.md](./17-navigator-upstream-cli-requirement.md) 的 Progress Tracking。

## 常见阻塞

- `HTTP 401: 未登录，请先登录`：本地 Navigator 仍在运行旧包，需重启到包含 OpenAPI runtime auth 修正的 launcher。
- `HTTP 400: client app credential expired`：ClientApp key/secret 已过期，需在 Navigator 管理侧刷新 sandbox 凭证后再执行 `runtime-token`。
- `runtime-token --write-profile` 报 `Profile path is not git-ignored`：确认上游项目 `.gitignore` 包含 `.navigator/upstream.env`，不要把 token 写到源码目录下未忽略文件。
- `HTTP 400: Client App is not granted access to this skill`：ClientApp 尚未授权当前 agent/skill，先用控制面凭据完成 skill grant。
- `HTTP 400: Agent not found: <agentId>`：当前本地环境没有注册该 OpenAPI agent；先确认 agent 清单，或在命令中通过 `--agent` 指定已注册 agent。
- `echo-agent-default` 可用于 runtime auth 与 ask smoke，但不能替代真实 messages polling 验收；Echo provider 每次 resolve 会创建新实例，且不落 session task 表，后续 `messages --poll` 可能返回 `Task not found`。
