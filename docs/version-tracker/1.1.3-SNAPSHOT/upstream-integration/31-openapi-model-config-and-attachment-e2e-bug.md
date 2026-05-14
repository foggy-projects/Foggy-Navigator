---
doc_type: bug-regression
version: 1.1.3-SNAPSHOT
status: implemented
date: 2026-05-14
source: github-issue-111 | tms-upstream-validation
severity: major
scope: navigator-open-sdk | navigator-upstream-cli | claude-worker-agent | langgraph-biz-worker
---

# OpenAPI Model Config and Attachment E2E Bug

## 文档作用

- doc_type: bug-regression
- intended_for: navigator-owner | worker-owner | upstream-e2e-owner | reviewer
- purpose: 记录 GitHub issue #111 中 TMS deterministic E2E 后续发现的 modelConfigId 与 attachments 透传问题、回归测试、修复范围和验收状态。

## 背景

TMS 在 deterministic E2E 复测中确认 `navi-e2e script register --file` 已可用，但继续验证标准 E2E model 与附件链路时发现：

1. `navi upstream ask` / SDK `AgentApi.askWithClientAppAccessToken` 没有把 `NAVI_MODEL_CONFIG_ID` 放进 ask payload。
2. OpenAPI controller 只读取 `metadata.modelConfigId`，没有支持 top-level `modelConfigId`。
3. Java addon 已向 Python worker 下发 `model_config_id` 和 `attachments`，但 Python worker 未把它们放入执行 state。
4. 附件 E2E 的 mock debug 请求看不到附件信息，无法证明上游附件已进入 Worker/LLM 上下文。

这些问题会导致上游 readiness 看起来通过，但真实 ask 可能没有命中指定的 deterministic model，附件自动化也无法做稳定断言。

## 合同澄清

- OpenAPI ask 的主合同字段为 top-level `modelConfigId`。
- SDK/CLI 在发送 `modelConfigId` 时，同时写入 `metadata.modelConfigId`，用于短期兼容和链路观测。
- OpenAPI controller 解析规则：top-level `modelConfigId` 优先，缺省时才回退到 `metadata.modelConfigId`。
- Python worker 需要保留请求级 `model_config_id` 和 `attachments` 到 `RootState`。
- 传给 LLM 的附件上下文只能包含脱敏摘要，不包含 URL query、fragment、token、secret、password、credential、api key 等敏感信息。

## 修复清单

- [x] SDK `AgentApi.askWithClientAppAccessToken` 增加 `modelConfigId` overload，并在 ask body 写入 top-level `modelConfigId` 与 `metadata.modelConfigId`。
- [x] CLI `upstream ask` 默认读取 `NAVI_MODEL_CONFIG_ID` / `--model-config-id` 并传给 SDK。
- [x] OpenAPI `OpenApiQueryForm` 增加 top-level `modelConfigId` 字段。
- [x] OpenAPI `askAgent` 解析 top-level `modelConfigId`，并向 Agent resolver 与 A2A message metadata 透传。
- [x] Python worker `/api/v1/query` initial state 保存 `model_config_id` 和 `attachments`。
- [x] Python root graph 在 LLM routing 与 LLM skill agent prompt 中注入脱敏附件摘要。
- [x] 建立并通过 Java/Python 回归测试。

## 回归测试

- SDK：`BusinessAgentApiSmokeTest.testAskWithClientAppAccessTokenSendsModelConfigId`
- CLI：`UpstreamCliTest.askSendsModelConfigIdFromEnvInTopLevelAndMetadata`
- OpenAPI：`OpenApiControllerMessageMappingTest.askAgent_topLevelModelConfigIdOverridesMetadataAndIsForwarded`
- Python worker：
  - `test_query_generator_preserves_model_config_id_and_attachments_in_state`
  - `test_attachment_context_prompt_sanitizes_url_and_keeps_metadata`

## 验证记录

2026-05-14 本地验证通过：

```bash
mvn -pl navigator-open-sdk,addons/claude-worker-agent -am "-Dtest=UpstreamCliTest,BusinessAgentApiSmokeTest,OpenApiControllerMessageMappingTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：`BUILD SUCCESS`，`OpenApiControllerMessageMappingTest` 10 个、`BusinessAgentApiSmokeTest` 19 个、`UpstreamCliTest` 24 个测试通过。

```bash
mvn -pl navigator-open-sdk,addons/claude-worker-agent -am test
```

结果：`BUILD SUCCESS`，相关 reactor 全量测试通过。

```bash
cd tools/langgraph-biz-worker
PYTHONPATH=src .venv/Scripts/python.exe -m pytest tests/test_query.py
```

结果：12 passed。

```bash
cd tools/langgraph-biz-worker
PYTHONPATH=src .venv/Scripts/python.exe -m pytest -q
```

结果：345 passed, 6 skipped。

Navigator Upstream CLI 已重新打包并上传 OBS：

```text
version=1.0.0-SNAPSHOT
sha256=1a288efc8321dab78eedee478f84f64dbe8a2783d268603a5d41a58920127d43
install=irm https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/navigator-upstream-cli/install.ps1 | iex
```

## Follow-up：E2E Mock Base URL

2026-05-14 TMS 在本地验证中确认：标准 E2E model config 若写入 `base_url=http://localhost:8200`，OpenAI client 会请求 `/chat/completions`，mock LLM 返回 404；写入 `base_url=http://localhost:8200/v1` 后，请求路径为 `/v1/chat/completions`，scripted cursor 能命中。

本次修复将 `navi-e2e model ensure --standard biz-worker` 的服务端 `mockBaseUrl` 规范化为 OpenAI-compatible base URL：

- 输入 `http://localhost:8200` 或 `http://localhost:8200/` 时，model config 保存为 `http://localhost:8200/v1`。
- 输入已带 `/v1` 的 URL 时，只去除末尾 `/`，不重复追加。
- 已存在但缺少 `/v1` 的 E2E model config 会在下一次 `model ensure` 时被更新。

## Progress Tracking

### Development Progress

- status: completed
- touched_code:
  - `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/api/AgentApi.java`
  - `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/cli/UpstreamCli.java`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiController.java`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/model/form/OpenApiQueryForm.java`
  - `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/E2eModelConfigEnsureService.java`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/routes/query.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py`

### Testing Progress

- status: passed
- java_targeted: passed
- python_query_tests: passed
- automation_decision: required and completed

### Experience Progress

- status: N/A
- reason: 本次修复为 SDK/CLI/OpenAPI/Worker API 链路，无前端 UI 或交互变更。

## 验收标准

- 上游使用最新 CLI 后，`navi upstream ask --model-config-id <e2eModelConfigId>` 或本地 `NAVI_MODEL_CONFIG_ID` 能进入 OpenAPI ask payload。
- OpenAPI controller 能按 top-level `modelConfigId` 解析模型配置，并兼容 `metadata.modelConfigId`。
- LangGraph Biz Worker 的 LLM request messages 能包含脱敏附件摘要，用于 mock debug / scripted cursor 断言。
- 附件摘要不得暴露 URL query、fragment 或 metadata 中的凭证类字段。

## Remaining Risk

- 当前实现只向 LLM prompt 注入附件摘要，不拉取附件正文。需要读取附件内容时，应另起需求设计受控下载、类型限制、大小限制与审计策略。
- 服务端代码变更需要通过当前项目的 `start-launcher.ps1` 重启后才会进入本地联调服务。
