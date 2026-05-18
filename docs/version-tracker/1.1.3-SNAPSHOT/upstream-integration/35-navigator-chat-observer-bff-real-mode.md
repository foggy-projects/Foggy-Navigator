# Navigator Chat Observer BFF Real Mode

## 目标

给 `packages/navigator-chat-widget` 的本地观测页提供一个真实上游 BFF，浏览器只访问 BFF，ClientApp runtime credential、runtime token 交换、OpenAPI ask/轮询/取消都由 BFF 通过 `navigator-open-sdk` 完成。

## 模块

- Maven module: `tools/navigator-chat-observer-bff`
- 默认端口: `5181`
- 默认监听: `0.0.0.0`
- 观测页: `http://127.0.0.1:5179/`

## 启动

推荐只配置 Navigator 地址和默认调试参数，授权在观测页里完成：

```powershell
$env:NAVIGATOR_BASE_URL = "http://127.0.0.1:8112"
$env:NAVI_AGENT_ID = "<agent-id>"
$env:NAVI_MODEL_CONFIG_ID = "<model-config-id>"
$env:NAVIGATOR_OBSERVER_PUBLIC_BASE_URL = "http://127.0.0.1:5181"
mvn -pl tools/navigator-chat-observer-bff -am spring-boot:run
```

在观测页选择 `Real BFF` 后，填写 Navi 租户管理员账号/密码并点击 `生成调试授权`。如果用 `root` 这类没有 `tenantId` 的 SUPER_ADMIN 账号登录，需要同时填写 `Target Tenant ID`；本地 TMS 调试库通常是 `tenant-tms-e3cf`。BFF 会在服务端完成：

- 登录 Navi 获取当前用户 JWT。
- 如果登录用户是 SUPER_ADMIN 且没有 `tenantId`，通过 `Target Tenant ID` 找到该租户的 TENANT_ADMIN，临时签发 API Key 完成调试初始化，并在初始化后撤销该临时 Key。
- 创建或复用 `Navigator Chat Observer Debug BFF` ClientApp。
- 签发 1 天有效的 runtime credential，并只保存在 BFF 内存中。
- 给当前 Agent/Skill、Upstream User 和可选 Model Config 写入调试授权。

浏览器不会保存密码、ClientApp secret 或 runtime access token。密码只提交到本地/上游 BFF 的 `/api/v1/observer/auth/login`。

也可以继续用显式 ClientApp runtime 环境变量启动：

```powershell
$env:NAVIGATOR_BASE_URL = "http://127.0.0.1:8112"
$env:NAVI_CLIENT_APP_KEY = "<client-app-key>"
$env:NAVI_CLIENT_APP_SECRET = "<client-app-secret>"
$env:NAVI_UPSTREAM_USER_ID = "tms-local-user"
$env:NAVI_AGENT_ID = "<agent-id>"
$env:NAVIGATOR_OBSERVER_PUBLIC_BASE_URL = "http://127.0.0.1:5181"
mvn -pl tools/navigator-chat-observer-bff -am spring-boot:run
```

如果 Worker 不在本机，`NAVIGATOR_OBSERVER_PUBLIC_BASE_URL` 必须改成 Worker 能访问的地址，例如 `http://192.168.31.119:5181`，否则附件 URL 对 Worker 不可读。

## 观测页接入

```powershell
pnpm --dir packages/navigator-chat-widget dev:observe
```

在页面选择 `Real BFF`，确认：

- `BFF URL` 指向 `http://127.0.0.1:5181` 或局域网地址。
- `Navi Base URL` 指向真实 Navigator 后端；本仓 launcher 默认是 `http://127.0.0.1:8112`。
- `Target Tenant ID` 仅在使用 SUPER_ADMIN/root 登录时需要；租户管理员登录可留空。
- `Agent/Skill ID` 与 `NAVI_AGENT_ID` 一致；Real BFF 里它必须是目标租户已注册的 OpenAPI Agent/Business Skill ID，不是观测页 mock 占位的 `observer-agent`。TMS X3 部署脚本默认通常是 `tms.navigator.agent`，但本地 Navigator 必须先同步或 materialize 对应 BizWorker skill bundle。
- `Upstream User ID` 默认可用 `observer-local-user`；需要模拟某个上游账号时改成稳定的业务用户 ID。
- `Model Config ID` 可填可不填；真实 Real Navigator 模式通常需要一个已存在的 `LANGGRAPH_BIZ` 模型配置。
- 需要附件测试时启用 `附件走 BFF 上传`。
- 未配置 ClientApp 环境变量时，填写 Navi 租户管理员账号/密码并点击 `生成调试授权`。
- `最近 ask body` 中应该出现顶层 `attachments` 数组。

如果 `/api/v1/observer/auth/login` 返回 `Skill not found`，说明 Navi 登录、租户选择、ClientApp/runtime credential 生成已经通过，失败点是目标租户没有这个业务 Skill。此时应先同步上游 BizWorker skill bundle，或把观测页的 `Agent/Skill ID` 改成当前租户真实存在的入口。

## BFF 接口

- `GET /api/v1/observer/config`
- `POST /api/v1/observer/auth/login`
- `POST /api/v1/observer/attachments`
- `GET /api/v1/observer/attachments/{id}/{fileName}`
- `POST /api/v1/open/agents/{agentId}/ask`
- `GET /api/v1/open/agents/{agentId}/tasks/{taskId}`
- `GET /api/v1/open/agents/{agentId}/tasks/{taskId}/messages`
- `POST /api/v1/open/agents/{agentId}/tasks/{taskId}/cancel`
- `GET /api/v1/open/agents/{agentId}/sessions`
- `GET /api/v1/open/agents/{agentId}/sessions/{contextId}/messages`

## 安全边界

- 浏览器不持有 `NAVI_CLIENT_APP_SECRET`、runtime access token、`NAVI_API_KEY`。
- BFF 不直连 Worker Gateway，只通过 Navigator OpenAPI 和 `navigator-open-sdk`。
- 本地附件存储仅用于联调观测，不作为生产文件服务方案。
