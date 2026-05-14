---
doc_type: bug-regression
version: 1.1.3-SNAPSHOT
status: implemented
date: 2026-05-14
source: tms-upstream-validation
severity: major
scope: navigator-upstream-cli | langgraph-biz-worker | business-agent-module
---

# Deterministic E2E Model Routing Bug

## 背景

TMS 在接入 Navigator deterministic E2E 流程时，`navi-e2e model ensure --standard biz-worker --set-default --write-profile`、`verify-agent-readiness --model-config-id` 均显示 E2E model grant 与 effective model 生效，但实际 `upstream ask --model-config-id` 仍返回真实模型自然语言，`mock-llm-service` 的 `debug requests` 为空。

同时，TMS 通过 `navi-e2e script register --file` 调官方 `foggy/mock-llm-service` 时遇到 HTTP 422，服务端提示 body missing；同一个 JSON 直接 `POST /__e2e/scripts` 可以注册成功。

## 影响

- 上游 E2E 自动化无法稳定使用 scripted response 验证多轮 tool-call loop。
- readiness 虽然能证明授权闭环，但无法证明 Worker 实际命中了 E2E mock model。
- 上游会误以为已经切换到 deterministic model，实际仍可能消耗真实模型并产生非确定性结果。

## 预期行为

1. `navi-e2e script register --file <script.json>` 应向 mock LLM 服务发送标准 JSON request body。
2. Java relay 在调用 LangGraph Biz Worker 时，不仅传递 `model_config_id`，还应传递 Navigator 已解析的模型配置，包括 provider、baseUrl、modelName 和内部解密后的 apiKey。
3. Python LangGraph Biz Worker 应按请求级 `llm_config` 构造本次 ChatModel；未提供时才回退到进程全局 `BIZ_WORKER_LLM_*` 配置。
4. `mock-llm-service` 的 debug requests 应能看到本次 scripted cursor 命中记录。

## 实际行为

- CLI 直接读取文件文本并发出 body，缺少对 BOM/编码等文件差异的规范化处理。
- Java 侧只向 Python Worker 发送 `model_config_id`，未发送可执行的模型连接信息。
- Python Worker 只在进程启动时从环境变量构造全局 ChatModel，请求级 `model_config_id` 对 LLM 路由没有实际作用。

## 复现与回归测试

- CLI：新增 `scriptRegisterNormalizesJsonFileBeforePosting`，覆盖带 UTF-8 BOM 的 JSON 文件，确认发往 mock LLM 的 body 是规范 JSON。
- Java relay/client：新增/扩展测试，确认 `llm_config` 被放入 `/api/v1/query` 请求体，并能从 `LlmModelConfigDTO` + decrypted apiKey 构建。
- Python Worker：新增请求级 `llm_config` 单测，确认 agentic routing 使用请求级 mock model，而不是进程全局模型。

## 修复清单

- [x] CLI `script register` 改为先用 Jackson 解析 JSON 文件，再序列化为标准 JSON body。
- [x] E2E model ensure 写入 OpenAI-compatible provider 标记和测试 apiKey。
- [x] LangGraph Java relay 从 `LlmModelManager` 解析 `modelConfigId`，下发请求级 `llm_config`。
- [x] LangGraph Python Worker 支持请求级 `llm_config` 构造 ChatModel。
- [x] 运行 Java 与 Python 回归测试。
- [x] 更新 CLI 发布包并上传 OBS。

## 验证记录

- `mvn -pl navigator-open-sdk,business-agent-module,addons/langgraph-biz-worker -am "-Dtest=E2eCliTest,E2eModelConfigEnsureServiceTest,LanggraphWorkerClientTest,LanggraphStreamRelayTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`：通过。
- `mvn -pl business-agent-module,addons/langgraph-biz-worker,navigator-open-sdk -am test`：通过。
- `tools/langgraph-biz-worker`: `PYTHONPATH=src .venv/Scripts/python.exe -m pytest -q`：343 passed, 6 skipped。
- `tools/navigator-upstream-cli/dist/package.ps1`：生成 `navigator-upstream-cli-1.0.0-SNAPSHOT-windows.zip`，SHA256 `d363698f9ae85cc9991c3ddc281f3018ff33620762606335f987be7287e53f46`。
- `tools/navigator-upstream-cli/dist/upload.ps1 -Version 1.0.0-SNAPSHOT -AllowSameVersion`：已上传 archive、`latest.json`、`install.ps1` 到 OBS。

## 验收标准

- `mvn -pl navigator-open-sdk,business-agent-module,addons/langgraph-biz-worker -am test` 通过。
- `tools/langgraph-biz-worker` 相关 pytest 通过。
- 上游重新安装 CLI 后，`navi-e2e script register --file` 能直接注册脚本。
- 使用 E2E modelConfigId 发起 ask 时，mock LLM `debug requests` 能看到命中记录。
