# Navigator Chat Observer BFF Real Mode

## 目标

给 `packages/navigator-chat-widget` 的本地观测页提供一个真实上游 BFF，浏览器只访问 BFF，ClientApp runtime credential、runtime token 交换、OpenAPI ask/轮询/取消都由 BFF 通过 `navigator-open-sdk` 完成。

## 模块

- Maven module: `tools/navigator-chat-observer-bff`
- 默认端口: `5181`
- 默认监听: `0.0.0.0`
- 观测页: `http://127.0.0.1:5179/`

## 启动

```powershell
$env:NAVIGATOR_BASE_URL = "http://127.0.0.1:8080"
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
- `Agent ID` 与 `NAVI_AGENT_ID` 一致。
- 需要附件测试时启用 `附件走 BFF 上传`。
- `最近 ask body` 中应该出现顶层 `attachments` 数组。

## BFF 接口

- `GET /api/v1/observer/config`
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
