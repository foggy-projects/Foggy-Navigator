# Live Upstream Runtime Context Smoke

## 文档作用

- doc_type: test-runbook
- intended_for: execution-agent | manual-acceptance-owner
- purpose: 提供真实上游 OpenAPI 联调和本地 runtime context 证据对账入口

版本：`1.1.6-SNAPSHOT`
状态：ready-for-manual-acceptance
类型：runtime context governance / live smoke / log parity

## 背景

scripted E2E 已经覆盖普通多轮、业务工具调用、`AWAITING_USER` 续接、nested recoverable leaf resume 和 handoff。但真实上游链路还需要一个可重复的 smoke 入口，用于同时确认：

1. OpenAPI 上游调用能触发同一个 `contextId` 下的多轮 BizWorker runtime。
2. 本地 session 目录存在 `session.json`，并能直接定位 `frames/<rootFrameId>.json`。
3. `logs/llm-submissions/*.json` 保存真实提交给 LLM 的 body。
4. `logs/runtime-message-events/*.jsonl` 保存可恢复 provider protocol events。
5. 两张附件等上游输入证据能在 LLM body、任务消息或会话消息中被复盘。
6. 重开会话后的消息不泄漏 `invoke_business_skill` / `invoke_business_function` / `submit_skill_result` 这类 raw tool chatter。
7. LLM body 不再暴露内部兼容 skill 名 `system.root`。

## 脚本入口

脚本：

```text
tools/langgraph-biz-worker/scripts/live_upstream_runtime_context_smoke.py
```

输出：

```text
docs/version-tracker/1.1.6-SNAPSHOT/test-records/live-upstream-runtime-context/<runId>/summary.json
```

脚本支持两种模式：

1. `openapi-live`：直接调用 Navigator OpenAPI，发送 `hi` 和一条带附件的工单请求，然后抓取 task/session messages 并校验本地 session 目录。
2. `validate-only`：不调用上游，只根据已有 `contextId` 对本地 session 目录和可选的消息 JSON 做对账。

## 环境前置

BizWorker 侧建议开启：

```powershell
$env:BIZ_WORKER_LLM_SUBMISSION_LOG_ENABLED = "true"
```

OpenAPI live 模式需要：

```powershell
$env:NAVI_BASE_URL = "http://<navigator-host>"
$env:NAVI_CLIENT_APP_KEY = "<client-app-key>"
$env:NAVI_CLIENT_APP_SECRET = "<client-app-secret>"
$env:NAVI_AGENT_CODE = "<agent-code-or-id>"
$env:NAVI_MODEL_CONFIG_ID = "<optional-model-config-id>"
```

如果已有 runtime token，也可以用：

```powershell
$env:NAVI_CLIENT_APP_ACCESS_TOKEN = "<runtime-token>"
```

## 真实上游 Smoke

```powershell
cd tools/langgraph-biz-worker
.\.venv\Scripts\python.exe scripts/live_upstream_runtime_context_smoke.py `
  --attachment-url "https://example.test/image-one.png" `
  --attachment-url "https://example.test/image-two.png" `
  --expect-attachments 2
```

脚本会执行：

1. `POST /api/v1/open/client-apps/runtime-token`
2. `POST /api/v1/open/agents/{agentId}/preflight`
3. `POST /api/v1/open/agents/{agentId}/ask`，消息为 `hi`
4. `POST /api/v1/open/agents/{agentId}/ask`，同一 `contextId` 下发送带附件工单请求
5. `GET /api/v1/open/agents/{agentId}/tasks/{taskId}`
6. `GET /api/v1/open/agents/{agentId}/tasks/{taskId}/messages`
7. `GET /api/v1/open/agents/{agentId}/sessions/{contextId}/messages`
8. 校验本地 `data/runtime/sessions/by-date/.../<contextId>` 目录

## Validate-Only

已有会话目录时，可以先只校验本地证据：

```powershell
cd tools/langgraph-biz-worker
.\.venv\Scripts\python.exe scripts/live_upstream_runtime_context_smoke.py `
  --validate-only `
  --context-id bctx_20260521_xx_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

如果已经从上游导出了重开会话后的消息 JSON，可追加：

```powershell
  --session-messages-json D:\tmp\session-messages.json `
  --task-messages-json D:\tmp\task-messages.json
```

附件可以通过 JSON 文件声明：

```json
{
  "attachments": [
    {
      "id": "att-1",
      "name": "image-one.png",
      "url": "https://example.test/image-one.png"
    },
    {
      "id": "att-2",
      "name": "image-two.png",
      "url": "https://example.test/image-two.png"
    }
  ]
}
```

命令：

```powershell
.\.venv\Scripts\python.exe scripts/live_upstream_runtime_context_smoke.py `
  --validate-only `
  --context-id bctx_20260521_xx_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx `
  --attachment-json D:\tmp\attachments.json `
  --expect-attachments 2
```

## Recoverable ERROR/TIMEOUT 验收

脚本本身不制造 provider timeout。真实或 scripted recoverable 场景完成后，用同一个 validator 对账即可：

```powershell
cd tools/langgraph-biz-worker
.\.venv\Scripts\python.exe scripts/live_upstream_runtime_context_smoke.py `
  --validate-only `
  --context-id bctx_20260521_xx_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx `
  --require-recoverable-checkpoint `
  --expected-tool-call handoff_to_parent
```

该模式要求 `runtime-message-events` 中出现 `suspended` 或 `frame_completed` checkpoint，并能找到指定 tool call。它用于人工验收 `TIMEOUT` / `ERROR` 后下一条用户消息是否仍直达 deepest leaf，并允许 leaf 通过 `handoff_to_parent` 受控退出。

## 关键校验项

| check | 含义 |
| --- | --- |
| `session_dir_exists` | 标准 `contextId` 可直接推导 session 目录 |
| `session_json_exists` | session 目录存在 `session.json` |
| `root_frame_id_recorded` | `session.json.rootFrameId` 已写入 |
| `root_frame_file_exists` | 可直接打开 `frames/<rootFrameId>.json` |
| `llm_submission_logs_exist` | 已开启并写入真实 LLM body 快照 |
| `runtime_message_events_exist` | 已写入 provider protocol JSONL |
| `llm_submission_numeric_file_names` | LLM submission 文件名有可排序数字前缀 |
| `llm_submission_body_messages_exist` | 每次 LLM 快照都有 `body.messages` |
| `expected_user_prompts_present` | live 模式下两轮用户消息能在 LLM body 中复盘 |
| `system_root_not_exposed_to_llm` | LLM body 不暴露 `system.root` |
| `expected_attachment_refs_present` | 期望附件引用能在证据中匹配 |
| `reopen_messages_hide_raw_tools` | 重开会话消息不泄漏 raw tool chatter |
| `expected_tool_calls_present` | 指定工具调用能在 LLM body 或 runtime events 中找到 |
| `recoverable_checkpoint_present` | recoverable 验收时存在可恢复 checkpoint |

## 与已知前端/上游问题的边界

该脚本用于记录和检测下列现象，但不在本轮修复它们：

1. 用户上传两张图，但 TMS 工单侧只看到一张图。
2. 刷新页面、重开会话后，图片在会话消息上丢失。
3. 简洁模式下重开会话后仍出现 `invoke_business_skill` 等 raw tool 消息。

如果这些 check 失败，应把 `summary.json` 作为对应独立 BUG 的复现证据。

## 当前状态

- 2026-05-21: 已新增脚本与 validator 单元测试。
- 2026-05-21: 本地 validate-only 曾对旧会话执行，旧会话没有 `logs/llm-submissions`，因此校验失败；该结果符合预期，说明旧数据或未开日志的会话无法用于 LLM body 复盘验收。
- 真实 OpenAPI live 验收等待手动提供可访问的 Navigator 地址、ClientApp 凭证和附件 URL 后执行。
