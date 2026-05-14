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
account-context list
account-context read
account-context write-policy
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
NAVI_CONTROL_API_KEY=
NAVI_UPSTREAM_USER_TOKEN=
# NAVI_ADMIN_TOKEN=        # Navigator maintainer fallback only
# NAVI_ADMIN_API_KEY=      # Navigator maintainer fallback only
# TMS_STAFF_SESSION_TOKEN= # legacy TMS sandbox alias for NAVI_UPSTREAM_USER_TOKEN
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
NAVIGATOR_CONTROL_API_KEY -> NAVI_CONTROL_API_KEY
TMS_STAFF_SESSION_TOKEN -> NAVI_UPSTREAM_USER_TOKEN
UPSTREAM_USER_ID -> NAVI_UPSTREAM_USER_ID
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

如果 `.navigator/upstream.env` 中已有 `NAVI_CLIENT_APP_KEY` 与 `NAVI_CLIENT_APP_SECRET`，`verify-agent-readiness`、`ask`、`messages`、`sessions`、`session-messages`、`skill tree`、`skill read`、`account-private skill sync`、`account-context list/read/write-policy` 会在内存中自动交换新的 runtime access token；因此常规 smoke 不必手工复制 token。`NAVI_CLIENT_APP_ACCESS_TOKEN` 主要用于缺少 secret 的环境或需要显式缓存 token 的场景。

可选模型配置可放入 profile：

```properties
NAVI_UPSTREAM_USER_ID=<upstreamUserId>
NAVI_MODEL_CONFIG_ID=<modelConfigId>
```

常规命令可从 profile 读取 `NAVI_UPSTREAM_USER_ID`；只有诊断切换用户时才需要显式传 `--upstream-user-id`。

命令行 `--model-config-id` 优先级高于 profile/env 中的 `NAVI_MODEL_CONFIG_ID`。

### 3. 确保当前上游用户授权

```powershell
.\tools\navigator-upstream\navi.ps1 upstream ensure-grant --upstream-user-id <id>
```

约束：

- `ensure-grant` 必须使用 `NAVI_CONTROL_API_KEY` 这类 ClientApp-scoped 控制面凭据；`NAVI_ADMIN_TOKEN` 或 `NAVI_ADMIN_API_KEY` 仅作为 Navigator 内部 fallback。
- `NAVI_UPSTREAM_USER_TOKEN` 是可选项；如果上游 Worker 需要回调上游系统，可放入当前上游用户 token；如果只是 SIM/E2E 或纯 Navi 会话授权，可省略。
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

`sessions` / `session-messages` 使用 `/api/v1/open/business-agent/sessions` 读模型，只返回当前 ClientApp + upstream user 归属的会话。`ask --context-id` 续聊也会在发任务前校验同一归属，不能复用其他 upstream user 的 `contextId`。

### 7. 维护 ClientApp 模型授权

正式业务模型由 `navi upstream model` 命令组维护，使用 ClientApp-scoped `NAVI_CONTROL_API_KEY`，不会要求上游持有全局 admin token。

```powershell
.\tools\navigator-upstream\navi.ps1 upstream model grants
.\tools\navigator-upstream\navi.ps1 upstream model grant --model-config-id <modelConfigId> --set-default --write-profile
.\tools\navigator-upstream\navi.ps1 upstream model set-default --model-config-id <modelConfigId> --write-profile
```

如果上游项目有自己的 LLM key，可以先放到当前 shell 的环境变量，再创建当前 ClientApp 自有模型：

```powershell
$env:NAVI_LLM_API_KEY="<llm-api-key>"
.\tools\navigator-upstream\navi.ps1 upstream model create `
  --name "tms-owned-gpt" `
  --model-base-url "https://llm.example/v1" `
  --model-name "gpt-4.1-mini" `
  --provider openai `
  --api-key-env NAVI_LLM_API_KEY `
  --set-default `
  --write-profile
```

也可以直接用 grant id 设置默认模型：

```powershell
.\tools\navigator-upstream\navi.ps1 upstream model set-default --grant-id <grantId>
```

维护自有模型：

```powershell
.\tools\navigator-upstream\navi.ps1 upstream model update `
  --model-config-id <modelConfigId> `
  --model-base-url "https://llm.example/v1" `
  --model-name "gpt-4.1-mini"

$env:NAVI_LLM_API_KEY="<new-llm-api-key>"
.\tools\navigator-upstream\navi.ps1 upstream model rotate-key `
  --model-config-id <modelConfigId> `
  --api-key-env NAVI_LLM_API_KEY
```

约定：

1. `model grants` 列出当前 `NAVI_CLIENT_APP_ID` 下已授权的模型、状态和默认标记。
2. `model grant` 为当前 ClientApp 授权已有 `modelConfigId`；`--set-default` 同时设为默认。
3. `model set-default --model-config-id` 会先查当前 ClientApp 的 grant，再设置默认。
4. `model create` 创建 `LANGGRAPH_BIZ` 模型配置，并以 `CLIENT_APP_OWNED` grant 绑定到当前 ClientApp。
5. `model update` / `model rotate-key` 只允许维护 `CLIENT_APP_OWNED` 自有模型，不能修改管理员授权的共享模型。
6. `--model-base-url` 是上游 LLM/OpenAI-compatible 地址；`--base-url` 仍然是 Navi 服务地址。
7. `--api-key-env` 从环境变量读取 LLM key，避免 key 进入命令历史或 CLI 输出。
8. `--write-profile` 只把最终 `NAVI_MODEL_CONFIG_ID` 写回 gitignored `.navigator/upstream.env`。
9. `NAVI_MODEL_CONFIG_ID` 是上游项目本地默认模型，不代表租户全局默认模型。

## Deterministic E2E Test Model

真实 LLM smoke 不应作为上游主回归 gate。上游自动化 E2E 应优先使用 Navigator 标准 E2E Test Model 与 scripted response，完整设计见 [27-e2e-scripted-test-model-design.md](./27-e2e-scripted-test-model-design.md)。

推荐 scripted cursor 格式：

```text
next:${e2eTraceId}:${turnIndex}
```

示例：

```text
e2eTraceId=4f6c0a7e-7d7b-4f1d-91af-7c7f60d0b2d1
next:4f6c0a7e-7d7b-4f1d-91af-7c7f60d0b2d1:001
next:4f6c0a7e-7d7b-4f1d-91af-7c7f60d0b2d1:002
```

约定：

1. 首轮 user message 放入 `e2eTraceId` 和 `next:${e2eTraceId}:001`。
2. 每轮 mock LLM 返回的 tool call arguments 或 content 放入下一轮 cursor。
3. `turnIndex` 从 `001` 开始递增，三位补零。
4. 同一 `traceId + cursor` 默认幂等；Worker 重试时应返回同一 scripted response。
5. E2E Test Model 的默认绑定只应修改 ClientApp model grant，不应修改租户默认 model config。

已安装的 CLI 包含独立 E2E 入口：

```powershell
.\tools\navigator-upstream\navi-e2e.ps1 config check
.\tools\navigator-upstream\navi-e2e.ps1 model ensure --standard biz-worker --set-default --write-profile
.\tools\navigator-upstream\navi-e2e.ps1 script register --file .\.navigator\e2e-script.json
.\tools\navigator-upstream\navi-e2e.ps1 debug requests --trace-id <e2eTraceId>
.\tools\navigator-upstream\navi-e2e.ps1 script cleanup --trace-id <e2eTraceId>
```

`navi-e2e` 默认读取同一个 project-local `.navigator/upstream.env`，其中 `NAVI_E2E_MOCK_LLM_URL` 默认指向 `http://localhost:8200`，也可用 `--mock-url` 临时覆盖。`model ensure` 需要 `NAVI_CONTROL_API_KEY`，只创建/更新当前 ClientApp 专属的标准 E2E model config 与 ClientApp model grant；`NAVI_ADMIN_TOKEN` 或 `NAVI_ADMIN_API_KEY` 仅作为 Navigator 内部 fallback；`--write-profile` 只把 `NAVI_MODEL_CONFIG_ID` 写回 gitignored `.navigator/upstream.env`。

### 8. 查询或维护账号上下文文件

```powershell
.\tools\navigator-upstream\navi.ps1 upstream account-context list --upstream-user-id <id>
.\tools\navigator-upstream\navi.ps1 upstream account-context read --upstream-user-id <id> --file ACCOUNT_POLICY.md
.\tools\navigator-upstream\navi.ps1 upstream account-context write-policy --upstream-user-id <id> --from .\ACCOUNT_POLICY.md --expected-sha256 <sha256>
```

`account-context` 命令使用 ClientApp runtime access token 和 `X-Upstream-User-Id`，Navigator 服务端校验当前 upstream user grant。首段只支持写 `ACCOUNT_POLICY.md`；`AGENT.md` / `MEMORY.md` 可以读取但不能通过 CLI 写入。

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
