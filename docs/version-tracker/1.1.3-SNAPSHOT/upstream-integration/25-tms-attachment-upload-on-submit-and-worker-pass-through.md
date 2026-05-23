# TMS Attachment Upload On Submit and Worker Pass Through

- doc_type: requirement-design-implementation-plan
- source_issue: https://github.com/foggy-projects/Foggy-Navigator/issues/108#issuecomment-4432502004
- version: 1.1.3-SNAPSHOT
- status: implemented-verification-pass-doc-followups
- date: 2026-05-13
- priority: P1
- owner_scope: navigator-chat-widget | navigator-frontend | claude-worker-agent-openapi | session-module | worker-agents | upstream-tms-bff
- delivery_mode: single-root-delivery
- operation_mode: record

## 背景

TMS 已新增通用附件上传接口：

```http
POST /x3-web/tenant/attachment/upload
```

该接口返回统一的 `NavigatorAttachmentResult`，支持图片、PDF、文本、表格、文档、压缩包和保留类型文件。TMS 的产品交互要求是 **upload-on-submit**，即用户粘贴、选择或拖拽附件时，前端只保留本地 `File`；只有用户点击发送时，才调用 TMS 注入的上传 hook。

本需求的真实调用链是：

```text
TMS Frontend
  -> TMS attachment upload
  -> TMS BFF ask
  -> Navigator OpenAPI / Agent
  -> Worker
```

因此 Navigator 不负责实现 TMS 上传接口，也不在浏览器侧持有 TMS 上传凭证。Navigator 需要提供通用附件契约和透传链路，让 TMS BFF 可以把已上传附件的 URL 传给 Navigator，并最终传递到各 Worker。各 Worker 如何把附件 URL 注入 LLM、是否提供图片读取或 OCR 工具，由各 Worker 自行决定。

## 目标

1. 保留旧 `images` 字段和现有图片 base64 链路，不做破坏性替换。
2. 新增通用 `attachments` 字段，用于传递 TMS 上传返回的附件元数据和 URL。
3. `attachments` 从 TMS BFF ask 进入 Navigator 后，透传到所有 Worker 创建任务链路。
4. 前端组件支持 upload-on-submit：选择/粘贴阶段只保存 `File`，发送阶段调用宿主注入的 `uploadAttachment(file)`。
5. 当前阶段不要求 Navigator 或 Worker 读取附件内容，不要求 LLM 具备图片理解能力。

## 非目标

1. 不替 TMS 实现 `/x3-web/tenant/attachment/upload`。
2. 不把通用附件转换进旧 `images` 字段。
3. 不在 Navigator 控制面下载、缓存、OCR 或解析附件内容。
4. 不要求所有 Worker 在本阶段把附件注入 prompt 的方式完全一致。
5. 不改变现有 `images` 的 JSON 字符串格式、压缩策略和兼容行为。

## 附件契约

TMS BFF 调用 Navigator ask 时，优先使用顶层 `attachments`：

```json
{
  "question": "请分析这个 TMS 表单",
  "contextId": "ctx_optional",
  "attachments": [
    {
      "id": "att_001",
      "name": "回单照片.jpg",
      "mimeType": "image/jpeg",
      "size": 123456,
      "kind": "image",
      "url": "https://tms.example.com/attachments/att_001",
      "thumbnailUrl": "https://tms.example.com/attachments/att_001/thumb",
      "provider": "tms",
      "metadata": {
        "formId": "form_001"
      }
    }
  ],
  "metadata": {
    "modelConfigId": "model_001"
  },
  "clientContext": {
    "upstreamConversationId": "tms-chat-001"
  }
}
```

字段约束：

| 字段 | 要求 |
| --- | --- |
| `id` | 附件在上游或 provider 内的标识，允许为空但推荐必填 |
| `name` | 展示名，最终以后端上传响应为准 |
| `mimeType` | 上传响应确认的 MIME 类型 |
| `size` | 上传响应确认的字节数 |
| `kind` | `image` / `pdf` / `text` / `spreadsheet` / `document` / `archive` / `file` |
| `url` | Worker 可访问的附件 URL |
| `thumbnailUrl` | 图片或可预览文件的缩略图 URL，可为空 |
| `provider` | 推荐传 `tms` |
| `metadata` | 上游扩展字段，必须避免凭证、token、内部签名密钥 |

兼容规则：

1. 顶层 `attachments` 是推荐入口。
2. `metadata.attachments` 可作为 BFF 代理模式的兼容入口。
3. 若两者同时存在，顶层 `attachments` 为准；进入 A2A metadata 前统一覆盖为同一份 `attachments`。
4. `images` 与 `attachments` 可以同时存在，互不覆盖。

## 前端 upload-on-submit 规则

`@foggy/navigator-chat-widget` 应支持宿主注入：

```ts
type UploadAttachmentHook = (file: File) => Promise<NavigatorAttachmentResult>
```

组件行为：

1. 粘贴、选择、拖拽时只保存 pending `File` 和前端预览元数据。
2. 前端类型识别优先使用 `file.type`；为空时按 `file.name` 后缀兜底。
3. 前端识别只用于预览、图标和 UX 校验，最终 `kind/mimeType/size/name` 以后端上传响应为准。
4. 点击发送时，组件先调用 `uploadAttachment(file)` 上传全部 pending 文件。
5. 任一上传失败时，不发送 ask，保留 pending 附件供用户重试或删除。
6. 上传全部成功后，将 `NavigatorAttachmentResult[]` 放入 ask 请求。

## Navigator 透传链路

计划链路：

```text
OpenApiQueryForm.attachments
  -> A2aMessage.metadata.attachments
  -> Worker-specific A2A adapter / direct dispatch params
  -> Create*TaskForm.attachments
  -> providerConfig.attachments
  -> Worker HTTP body attachments
```

通道规则：

1. Public API 使用 `attachments` 数组。
2. A2A metadata 使用 `attachments` 数组，不降级成 `images`。
3. Worker task form 使用通用 DTO 或 JSON-compatible list，避免每个 Worker 自定义字段结构。
4. providerConfig 如需落库序列化，可以内部保存 JSON 字符串，但对 Worker HTTP body 仍发送 `attachments` 数组。
5. 日志只允许记录附件数量、kind、provider、name 和 size，避免记录长期可访问 URL 或 metadata 中的敏感扩展。

## 代码触点

### 前端组件

- `packages/navigator-chat-widget/src/types.ts`
  - 新增 `NavigatorAttachmentResult`、pending attachment、上传 hook 类型。
  - `NavigatorSendOptions` 增加 `attachments` 或 internal uploaded attachments 字段。
  - `NavigatorChatConfig` 增加 `uploadAttachment` 和附件限制配置。
- `packages/navigator-chat-widget/src/api/navigatorApi.ts`
  - `ask()` 请求体支持 `attachments`。
- `packages/navigator-chat-widget/src/composables/useNavigatorChat.ts`
  - `send()` 支持上传后再 ask。
  - 用户消息展示本地 pending 附件或上传结果摘要。
- `packages/navigator-chat-widget/src/components/NavigatorChat.vue`
  - 添加文件选择、粘贴、拖拽、删除、上传中和失败状态。
  - 根节点提供 `data-upload-hook` 和 `data-attachments-enabled`，用于浏览器侧排查宿主注入状态。
- `packages/navigator-chat-widget/index.html`
- `packages/navigator-chat-widget/src/dev-main.ts`
- `packages/navigator-chat-widget/src/dev/WidgetObservabilityDemo.vue`
  - 提供本地观测页，模拟“已注入 / 未注入”两种宿主配置，并展示上传调用和最近 ask body。
- `packages/navigator-frontend/src/composables/useAttachments.ts`
  - 保留现有 Navigator 主前端老链路。
  - 如复用该 composable，需要新增 pending File 模式，避免影响旧 `images` 语义。

### OpenAPI 和 A2A

- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/model/form/OpenApiQueryForm.java`
  - 增加顶层 `attachments`。
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiController.java`
  - 合并顶层 `attachments` 与 `metadata.attachments`。
  - 注入到 A2A metadata。
- `navigator-common`
  - 建议新增共享 DTO：`NavigatorAttachmentResultDTO`。

### Session 分派

- `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchRequest.java`
  - 增加通用 `attachments`。
- `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`
  - `toCommonParams()` 透传 `attachments`。
  - 保持 `images` 旧逻辑不变。
- `session-module/src/main/java/com/foggy/navigator/session/dto/SessionForwardCreateRequest.java`
  - 如 forward 场景需要附件，补齐同一契约。

### Worker Agent

- Claude Worker：
  - `CreateTaskForm` / `ResumeTaskForm` 增加 `attachments`。
  - `ClaudeWorkerInnerA2aAgent` 从 metadata 读取 `attachments`。
  - `ClaudeTaskService` 写入 providerConfig。
  - `WorkerStreamRelay` 从 providerConfig 读取并传给 `ClaudeWorkerClient`。
  - `ClaudeWorkerClient` HTTP body 增加 `attachments` 数组。
- Codex Worker：
  - `CreateCodexTaskForm`、A2A adapter、service providerConfig、client body 增加透传。
- Gemini Worker：
  - `CreateGeminiTaskForm`、A2A adapter、service providerConfig、client body 增加透传。
- LangGraph Biz Worker：
  - create task form、task service、worker client body 增加透传。

## 实施计划

### Stage A - 契约与 DTO

- [ ] 新增共享附件 DTO。
- [x] OpenAPI ask 支持顶层 `attachments`。
- [x] 兼容 `metadata.attachments`。
- [x] 明确顶层优先级和 metadata 归一化规则。

### Stage B - Worker 透传

- [x] Session direct dispatch 透传 `attachments`。
- [x] Claude Worker 透传到 Worker HTTP body。
- [x] Codex Worker 透传到 Worker HTTP body。
- [x] Gemini Worker 透传到 Worker HTTP body。
- [x] LangGraph Biz Worker 透传到 Worker HTTP body。
- [x] 确认 `images` 与 `attachments` 同时存在时互不影响。

### Stage C - Widget upload-on-submit

- [x] Widget 增加 pending attachment 状态。
- [x] Widget 增加 `uploadAttachment(file)` hook。
- [x] Widget 在 send 前上传附件。
- [x] Widget 上传失败时阻止 ask 并保留 pending 文件。
- [x] Widget ask 请求带 `attachments`。
- [x] 前端类型识别支持 MIME 与扩展名兜底。

### Stage D - 文档与上游接入说明

- [x] 更新前端组件快速上手文档。
- [x] 提供本地 widget 观测页用于排查 `uploadAttachment` 注入状态。
- [ ] 更新 OpenAPI / SDK ask 请求文档。
- [ ] 给 TMS BFF 明确 upload-on-submit 和 ask 转发样例。
- [ ] 标注 `images` 旧字段继续保留。

## 测试计划

### 单元测试

- [x] OpenAPI：顶层 `attachments` 被写入 A2A metadata。
- [x] OpenAPI：仅传 `metadata.attachments` 时仍能透传。
- [x] OpenAPI：顶层和 metadata 同时存在时顶层优先。
- [ ] Session dispatch：`images` 仍为旧字符串逻辑。
- [x] Session dispatch：`attachments` 作为独立字段透传。
- [ ] Worker adapters：各 Worker 从 A2A metadata 读取 `attachments`。
- [x] Worker clients：HTTP body 包含 `attachments` 数组。
- [ ] Widget：选择文件不触发 upload hook。
- [x] Widget：send 时触发 upload hook 后再 ask。
- [ ] Widget：上传失败不调用 ask。

### 集成测试

- [ ] TMS BFF 模拟 ask，携带 `attachments`，Navigator 接收并透传到目标 Worker。
- [ ] `images` + `attachments` 同时存在，Worker body 两个字段都存在。
- [ ] 非图片附件 `pdf/text/spreadsheet/document/archive/file` 至少覆盖 DTO round-trip。

### 手工验收

- [ ] 在 TMS 表单带图片的场景中，发送前不上传。
- [ ] 点击发送后先上传 TMS 附件，再提交 ask。
- [ ] Worker 侧能看到附件 URL、kind、name、mimeType。

## 验收标准

1. Navigator OpenAPI 可接收顶层 `attachments`。
2. A2A metadata 中包含规范化后的 `attachments` 数组。
3. Claude、Codex、Gemini、LangGraph Biz Worker 创建任务链路均能收到同一附件数组。
4. 旧 `images` 字段行为不变。
5. Widget 实现 upload-on-submit，不在粘贴、选择或拖拽时上传。
6. 上传失败时不发送 ask，用户可重试或删除附件。
7. 文档给出 TMS BFF 的请求样例和字段边界。

## 风险与待确认

1. URL 可访问性：TMS 返回的 URL 必须能被实际 Worker 运行环境访问。若 URL 需要短期签名，TMS BFF 应在 ask 前生成足够有效期的 URL。
2. URL 泄漏：日志、审计和错误消息不能完整输出附件 URL，尤其是带签名参数的 URL。
3. Worker 内部处理差异：本阶段只保证透传，不保证所有 Worker 都自动把附件注入 LLM prompt。
4. providerConfig 序列化：不同 Worker 模块可能对 providerConfig 的结构支持不同，实施时需统一数组透传或内部 JSON 字符串存储策略。
5. 前端包兼容性：`navigator-frontend` 现有老图片附件链路不能被 Widget 新通用附件状态误改。

## 执行进度

- [x] 需求澄清：TMS 上传先到 TMS BFF ask，再到 Navigator。
- [x] 需求澄清：当前需要把文件 URL 传递给 LLM/Worker，后续读取图片或 OCR 工具延期。
- [x] 需求澄清：旧 `images` 字段保留，新增 `attachments` 独立透传到各 Worker。
- [x] 建立 1.1.3 计划文档。
- [x] Stage A 开发完成（共享 DTO 暂未引入，当前按 JSON-compatible list 透传）。
- [x] Stage B 开发完成。
- [x] Stage C 开发完成（选择与粘贴已实现，拖拽交互后续可单独补齐）。
- [ ] Stage D 文档同步完成。
- [x] 测试补强完成。
- [ ] 文档同步与最终验收完成。

## 实施记录

- 2026-05-13：完成 OpenAPI 顶层 `attachments` 接收，并归一化写入 A2A metadata。
- 2026-05-13：完成 Session dispatch 到 Claude、Codex、Gemini、LangGraph Biz Worker 的 `attachments` 透传。
- 2026-05-13：完成 Worker HTTP body 的 `attachments` 数组透传，旧 `images` 字段继续保留。
- 2026-05-13：完成 `@foggy/navigator-chat-widget` upload-on-submit：发送前上传、失败阻断 ask、成功后把附件元数据放入顶层 `attachments`。
- 2026-05-13：补充 Session dispatch、OpenAPI ask、Claude/Codex/Gemini/LangGraph Java Worker client 请求体回归测试。
- 2026-05-13：补充 Codex/Gemini TS Worker 请求校验，以及 Claude/LangGraph Python Worker 请求模型校验。
- 2026-05-18：核对上游反馈后补齐当前工作树中缺失的 `@foggy/navigator-chat-widget` 选择、粘贴、拖拽、发送前上传和顶层 `attachments` ask 请求体实现，并补充快速接入文档中的 `uploadAttachment` 用法。
- 2026-05-18：新增 `packages/navigator-chat-widget` 本地观测页和组件根节点诊断标记，用于判断上游是否注入 `uploadAttachment` 以及 ask body 是否带顶层 `attachments`。

## 验证记录

- [x] `mvn -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am "-Dtest=TaskDispatchFacadeTest,OpenApiControllerMessageMappingTest,ClaudeWorkerClientTest,CodexWorkerClientTest,CodexWorkerFacadeImplTest,GeminiWorkerClientTest,LanggraphWorkerClientTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
- [x] `pnpm --dir packages/navigator-chat-widget test src/composables/useNavigatorChat.ux.test.ts`
- [x] `pnpm --dir packages/navigator-chat-widget build`
- [x] `pnpm --dir packages/navigator-chat-widget test src/composables/useNavigatorChat.ux.test.ts`（2026-05-18，6 tests passed，覆盖 ask 顶层 `attachments`）
- [x] `pnpm --dir packages/navigator-chat-widget test`（2026-05-18，11 tests passed）
- [x] `pnpm --dir packages/navigator-chat-widget build`（2026-05-18，Vite build + declaration emit passed）
- [x] `pnpm --dir packages/navigator-chat-widget dev:observe`（2026-05-18，绑定 `0.0.0.0:5179`，`http://127.0.0.1:5179/` 返回 200）
- [x] Playwright smoke（2026-05-18，本地观测页已验证：已注入模式显示附件按钮，未注入模式隐藏；发送附件后最近 ask body 包含顶层 `attachments`）
- [x] `pnpm --dir tools/codex-agent-worker test`
- [x] `pnpm --dir tools/codex-agent-worker typecheck`
- [x] `pnpm --dir tools/gemini-agent-worker exec node --import tsx --test tests/**/*.test.ts`
- [x] `pnpm --dir tools/gemini-agent-worker typecheck`
- [x] `$env:PYTHONPATH='tools/claude-agent-worker/src'; python -m pytest tools/claude-agent-worker/tests/test_models.py -q`
- [x] `$env:PYTHONPATH='tools/langgraph-biz-worker/src'; python -c "from langgraph_biz_worker.models import QueryRequest; a=[{'name':'pod-photo.png','url':'https://tms.example.com/files/pod-photo.png','kind':'image'}]; r=QueryRequest(prompt='describe', attachments=a); assert r.attachments == a"`
- [ ] `$env:PYTHONPATH='tools/langgraph-biz-worker/src'; python -m pytest tools/langgraph-biz-worker/tests/test_query.py -q`：本地环境缺少 `langgraph` Python 依赖，pytest conftest 加载应用时阻断；已用模型级脚本验证 `QueryRequest.attachments`。
