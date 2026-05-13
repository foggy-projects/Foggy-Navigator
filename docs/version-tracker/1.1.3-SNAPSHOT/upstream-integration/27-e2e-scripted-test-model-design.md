# Navigator E2E Scripted Test Model Design

## 文档作用

- doc_type: requirement-design-plan
- version: 1.1.3-SNAPSHOT
- status: recorded
- date: 2026-05-13
- priority: P1
- source_type: github-issue
- source: https://github.com/foggy-projects/Foggy-Navigator/issues/111
- intended_for: navigator-owner | e2e-cli-owner | mock-llm-owner | biz-worker-owner | upstream-llm-coding-agent | reviewer
- purpose: 记录 Navigator 为上游自动化 E2E 提供标准 scripted test model、`navi-e2e-cli`、多轮 tool-call loop 游标协议与 mock LLM 观测能力的设计方案

## 结论

本功能适合作为一个独立小迭代交付。

原因：

1. 目标是稳定上游 E2E 自动化，和真实上游接入、生产 CLI 操作、真实 LLM smoke 是不同测试 lane。
2. 交付物边界清晰：标准 E2E Test Model、script 注册/消费、debug requests、ClientApp model grant、文档与 skill 指南。
3. 风险可控：不要求上游自行维护 mock LLM 服务，不扩展租户级模型管理权限，不影响生产 `modelConfig` 默认选择。
4. 可分阶段验收：先跑通无附件多轮 tool-call loop，再接入 attachment 场景和结构化输出断言。

建议迭代名：

```text
Navigator E2E Scripted Test Model
```

## 背景

上游系统需要稳定的 Navigator E2E 测试能力，覆盖真实 Navigator OpenAPI、Business Agent task/session 持久化、Worker 路由、Skill/Function 执行、消息轮询、附件传递和结构化结果。

直接使用真实 LLM 作为回归门槛不稳定：路由、工具参数、重试和最终文本都可能漂移。真实 LLM smoke 仍然需要保留，但应作为独立小量 smoke lane，而不是主回归 gate。

已有 `tools/mock-llm-service` 可作为基础，但 #111 的目标不是让每个上游项目自建 mock LLM 或自行注册模型配置，而是由 Navigator 提供标准 E2E Test Model，并允许上游注册每个测试用例的 scripted response。

## 目标

1. Navigator 提供标准 OpenAI-compatible E2E Test Model，供上游 E2E 通过普通 `modelConfigId` 使用。
2. 上游默认不自建 mock LLM 实现，不直接注册租户级模型配置。
3. 上游可以为单个测试用例注册完整多轮 tool-call loop 脚本。
4. 每轮脚本通过稳定游标 `next:${e2eTraceId}:${turnIndex}` 推进。
5. mock LLM 能记录并脱敏暴露收到的 OpenAI request，便于断言模型可见输入。
6. CLI 将配置、注册、调试和清理封装为 E2E 专用命令，避免污染生产 upstream CLI 心智。

## 非目标

1. 不把 `LlmModelConfigEntity.isDefault=true` 设置为租户默认模型。
2. 不向上游开放通用租户级模型配置 CRUD。
3. 不要求上游 E2E 直接调用 `/internal/worker-gateway/v1/**`。
4. 不把真实 LLM smoke 替换为 deterministic E2E；两者是不同 lane。
5. 不把 keyword contains 作为唯一匹配机制；keyword 只能作为辅助。
6. 不在 debug endpoint 暴露 admin token、runtime token、ClientApp secret、task scoped token 或真实 API key。

## 核心设计

### 标准 E2E Test Model

Navigator 侧维护一个或多个标准模型配置：

```text
navigator-e2e-standard-v1
navigator-e2e-biz-worker-v1
```

底层仍复用现有 `LlmModelConfig` 存储，但使用边界必须收敛为：

```text
底层存储：tenant 下的 LLM model config
使用授权：ClientAppModelConfigGrant
E2E 默认：ClientApp default model grant
不是：租户默认模型
不是：Navigator userId 个人模型
```

`--set-default` 只表示设置当前 ClientApp 的默认 model grant，不修改租户/category 默认。

### 独立 `navi-e2e-cli`

建议提供独立命令入口，底层可复用 `navigator-open-sdk` 和 upstream 本地 profile：

```powershell
navi-e2e model ensure --standard biz-worker --set-default --write-profile
navi-e2e script register --file .navigator/e2e/tms-create-order.json
navi-e2e debug requests --trace-id <e2eTraceId>
navi-e2e script cleanup --trace-id <e2eTraceId>
```

命令职责区分：

| CLI | 职责 |
| --- | --- |
| `navi` / `navigator-upstream-cli` | 真实上游接入、agent/skill/function 注册、readiness、ask、session/messages |
| `navi-e2e` | deterministic E2E model grant、script 注册、debug requests、测试数据清理 |

### Scripted Turn Cursor 协议

默认游标格式：

```text
next:${e2eTraceId}:${turnIndex}
```

示例：

```text
e2eTraceId=4f6c0a7e-7d7b-4f1d-91af-7c7f60d0b2d1
next:4f6c0a7e-7d7b-4f1d-91af-7c7f60d0b2d1:001
next:4f6c0a7e-7d7b-4f1d-91af-7c7f60d0b2d1:002
```

规则：

1. 首轮 user message 必须包含 `e2eTraceId` 和 `next:${e2eTraceId}:001`。
2. mock LLM 返回 tool_call 时，应把下一轮 cursor 放入 tool_call arguments 或 response content。
3. BizWorker 执行工具后，下一次发给 LLM 的 messages 会包含上一轮 assistant tool_call 或 tool result，mock LLM 从尾部解析最新 cursor。
4. `turnIndex` 从 `001` 开始递增，三位补零，便于日志排查和脚本 diff。
5. 同一 `traceId + cursor` 默认只消费一次；重复请求按 request hash 返回同一响应，支持 Worker 重试幂等。

### Cursor 提取优先级

mock LLM 不应简单全文扫描全部历史消息。推荐从消息尾部按优先级提取：

1. 最新 `tool` message content 中的 `next:${traceId}:${idx}`。
2. 最新 assistant `tool_calls[].function.arguments` 中的 `next:${traceId}:${idx}`。
3. 最新 user message content 中的 `next:${traceId}:${idx}`。
4. ask metadata / attachment metadata 中的 `e2eTraceId`，仅作为 trace 归属，不单独推进轮次。

如果同一个 request 中出现多个 cursor，必须选择距离消息尾部最近、优先级最高的 cursor。

### Script 注册数据

上游注册的是脚本数据，不是自建 mock 服务：

```json
{
  "traceId": "4f6c0a7e-7d7b-4f1d-91af-7c7f60d0b2d1",
  "scenarioId": "tms-create-order-draft-v1",
  "expiresInSeconds": 3600,
  "turns": [
    {
      "cursor": "next:4f6c0a7e-7d7b-4f1d-91af-7c7f60d0b2d1:001",
      "response": {
        "tool_calls": [
          {
            "name": "tms.order.createOpeningDraft",
            "arguments": {
              "e2eTraceId": "4f6c0a7e-7d7b-4f1d-91af-7c7f60d0b2d1",
              "next": "next:4f6c0a7e-7d7b-4f1d-91af-7c7f60d0b2d1:002",
              "consignorName": "上海发货方",
              "consigneeName": "杭州收货方",
              "freightAmountYuan": 260
            }
          }
        ]
      }
    },
    {
      "cursor": "next:4f6c0a7e-7d7b-4f1d-91af-7c7f60d0b2d1:002",
      "response": {
        "content": "{\"summary\":\"已生成开单草稿\",\"structured_output\":{\"type\":\"OPEN_TMS_PAGE\",\"label\":\"去下单\",\"routeName\":\"OrderWorkbench\"}}"
      }
    }
  ]
}
```

## API / CLI 草案

### E2E Model Ensure

```powershell
navi-e2e model ensure `
  --standard biz-worker `
  --client-app-id <clientAppId> `
  --set-default `
  --write-profile
```

行为：

1. 创建或复用 Navigator 管理的标准 E2E model config。
2. 给当前 ClientApp 授权该 model config。
3. 可选设置为当前 ClientApp 默认 model grant。
4. `--write-profile` 时把 `NAVI_MODEL_CONFIG_ID` 写入 gitignored `.navigator/upstream.env`。
5. 输出必须脱敏。

建议输出：

```text
e2e model ensure ok
standard=biz-worker
modelConfigId=...
clientAppId=...
grantStatus=ENABLED
clientAppDefault=true
profileUpdated=.navigator/upstream.env
stored=NAVI_MODEL_CONFIG_ID
```

### Script Register

```powershell
navi-e2e script register --file .navigator/e2e/tms-create-order.json
```

行为：

1. 校验 `traceId` 唯一且非空。
2. 校验所有 `turn.cursor` 符合 `next:${traceId}:${idx}`。
3. 校验 turn index 连续或显式声明允许跳号。
4. 将脚本注册到 mock LLM script store。
5. 返回已注册 turn 数、过期时间和 debug 查询入口。

### Debug Requests

```powershell
navi-e2e debug requests --trace-id <e2eTraceId>
```

对应 mock LLM endpoint：

```http
GET /__debug/requests?traceId=<e2eTraceId>
```

返回内容：

1. mock LLM 收到的 OpenAI-compatible request。
2. 匹配到的 `traceId`、`cursor`、`scenarioId`、`turnIndex`。
3. 返回的 response 摘要。
4. 脱敏后的 headers 与 payload。

## BizWorker 多轮 Tool-Call Loop

mock LLM 每轮基于完整 OpenAI messages 决策，不依赖单纯服务端状态推进。

推荐状态来源：

```text
currentCursor = parseLatestCursor(openAiMessages)
scriptTurn = scriptStore.find(traceId, currentCursor)
response = scriptTurn.response
```

这种方式对以下场景更稳：

1. Worker 重试同一个 LLM request。
2. 上游 CI 并发执行多个 E2E 用例。
3. Tool result 延迟返回。
4. 多 tool_call 或结构化输出需要跨轮断言。

## 权限与安全

1. `navi-e2e` 使用 ClientApp-scoped 控制面凭证或受控 E2E provisioning 能力，不要求上游持有全局 admin token。
2. E2E model grant 只绑定到当前 ClientApp，不修改租户默认模型。
3. script 注册必须带 TTL，避免测试数据无限增长。
4. `traceId` 必须足够随机，推荐 UUID v4。
5. debug endpoint 必须脱敏 Authorization、API key、ClientApp secret、runtime token、task scoped token。
6. 上游测试不得直接调用 Worker Gateway。

## 与现有能力关系

| 能力 | 当前状态 | 本迭代处理 |
| --- | --- | --- |
| `tools/mock-llm-service` | 已支持 OpenAI `/v1/chat/completions`、tool_calls、基础 YAML 规则 | 扩展 scripted cursor、request debug、TTL script store |
| ClientApp model grant | 已支持 grant/default | 增加 E2E standard model ensure 封装 |
| Business Agent sessions/messages | 已支持按 `tenantId + clientAppId + upstreamUserId` 读取 | 作为 E2E 断言数据源 |
| Attachment pass-through | 由 #108 / 附件设计继续推进 | 本迭代先定义 debug 断言要求，附件场景可作为第二阶段 |
| navigator-upstream-cli skill | 已指导真实上游接入 | 已增加 E2E 专用章节，链接安装/使用文档，不把长文塞进 `SKILL.md` |

## 实施拆分

### Stage 1: 设计与文档

- [x] 明确 scripted cursor 协议。
- [x] 明确 `navi-e2e` 与 `navi` 的边界。
- [x] 明确 E2E model grant 不污染租户默认模型。
- [x] 更新上游 CLI skill 使用手册，推荐 `next:${e2eTraceId}:${turnIndex}`。

### Stage 2: Mock LLM Script Engine

- [x] 增加 script store：register/cleanup/TTL。
- [x] 增加 cursor parser。
- [x] 增加 OpenAI request debug store。
- [x] 增加 `GET /__debug/requests?traceId=...`。
- [x] 补单测：首轮 user、assistant tool_call、tool result content、重复请求幂等、并发 trace 隔离。

### Stage 3: Navigator E2E CLI

- [x] 增加 `navi-e2e model ensure`。
- [x] 增加 `navi-e2e script register`。
- [x] 增加 `navi-e2e debug requests`。
- [x] 增加 `navi-e2e script cleanup`。
- [x] profile 写入只更新 gitignored `.navigator/upstream.env`。

### Stage 4: End-to-End Evidence

- [ ] 用标准 BizWorker scenario 跑通 ask -> tool_call -> tool result -> final result。
- [ ] 用 session/messages 验证 USER、tool call、RESULT 消息。
- [ ] 用 debug requests 验证模型可见输入包含预期 cursor 和附件稳定表示。
- [x] 输出上游 E2E lane 使用说明。

## 验收标准

- [x] 上游可以通过 `navi-e2e model ensure --standard biz-worker --set-default --write-profile` 获得可用 `NAVI_MODEL_CONFIG_ID`。
- [x] 上游可以注册包含多个 turn 的 scripted response。
- [x] 首轮 user message 中的 `next:${traceId}:001` 能触发第一轮 response。
- [x] 后续轮次能从 assistant tool_call arguments 或 tool result 中解析最新 cursor。
- [x] 同一 `traceId + cursor` 重复请求幂等返回同一 response。
- [x] 并发不同 `traceId` 不串场。
- [x] `GET /__debug/requests?traceId=...` 能返回脱敏后的模型请求记录。
- [ ] Business Agent session/messages 能作为上游断言数据源。
- [x] 文档和 skill 手册明确推荐 `next:${e2eTraceId}:${turnIndex}`。

## Progress Tracking

### Development Progress

| Item | Status | Notes |
| --- | --- | --- |
| Requirement/design recorded | done | 本文档记录 #111 独立小迭代方案 |
| Mock LLM scripted cursor | done | 已实现内存 script store、cursor parser、debug requests |
| `navi-e2e-cli` | done | 已实现独立 `navi-e2e` wrapper、model ensure、script register、debug requests、script cleanup |
| E2E model grant ensure | done | 已实现 ClientApp-specific 标准 E2E model config 创建/更新、grant、可选 default 与 profile 写回 |
| Skill/manual update | done | 已补 upstream CLI 使用指南、安装/更新文档与 skill 提示 |

### Testing Progress

| Layer | Status | Required Evidence |
| --- | --- | --- |
| Mock LLM unit tests | pass | `PYTHONPATH=src pytest`，22 passed |
| CLI unit tests | pass | `mvn -pl navigator-open-sdk test`，42 passed，覆盖 config/script/debug/cleanup/model ensure |
| Business Agent unit tests | pass | `mvn -pl business-agent-module test`，229 passed |
| CLI package test | pass | `tools\navigator-upstream-cli\dist\package.ps1` 成功，ZIP 内包含 root/bin 双位置 `navi-e2e.ps1` |
| Navigator integration tests | pending | ClientApp model grant、ask 使用 E2E modelConfigId |
| End-to-end test | pending | BizWorker 多轮 tool-call loop |

### Experience Progress

- status: N/A
- reason: 本迭代首阶段为 CLI/API/测试基础设施能力，无 Navigator UI 变更；如后续增加管理台 E2E script 页面，则必须补体验验证与 Playwright evidence。

## 后续评审点

1. `navi-e2e` 已采用和 `navigator-upstream-cli` 同 ZIP 内双入口发布；后续只需确认远程安装包发布节奏。
2. 标准 E2E model config 已采用 ClientApp-specific 名称与 grant 生命周期，避免改租户默认模型。
3. script store 使用内存、文件、数据库还是 mock LLM 本地存储。
4. debug request 保留时间和最大条数。
5. attachment 稳定表示由 Navigator Worker 侧统一生成，还是由 mock LLM 只观测已有 messages。
