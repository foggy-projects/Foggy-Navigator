# REQ-030 BizWorker 按需附件解析与视觉模型配置

## 文档作用

- doc_type: workitem
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 记录 BizWorker 图片/附件按需解析能力、视觉模型配置要求、模块边界与推进清单。

## 基本信息

- version: 1.3.0-SNAPSHOT
- type: requirement
- priority: P1
- status: IN_PROGRESS
- source: 2026-05-18 用户讨论确认
- delivery_mode: single-root-delivery

## 背景

当前上游传给 BizWorker 的图片主要以附件元数据和 URL 形式进入 `attachments`。Python LangGraph BizWorker 只把附件整理成文本上下文供 LLM 看到，没有把图片内容交给视觉模型，也没有图片 OCR/识别工具。

业务上不能默认解析所有图片。典型场景里，“帮我提交一个异常工单并附上图片”只需要把图片作为工单附件转交业务函数；“根据这张图判断异常原因并提交工单”才需要先解析图片。

## 目标行为

1. 附件默认作为证据/引用保留，不自动触发视觉解析。
2. 给 BizWorker 补充显式的附件解析工具，推荐工具名为 `analyze_attachment`，首期支持 image 类附件。
3. LLM 仅在用户意图、Skill 指令、业务函数入参缺口或合规策略需要时调用解析工具。
4. 解析结果作为派生上下文进入后续推理和业务函数调用，原始附件仍保留并继续随工单/业务动作传递。
5. 图片识别使用的模型必须能在 ConfigModel 中配置，且不强绑定通用推理模型。

## 视觉模型配置要求

现有 `LlmModelCategory` 已包含 `VISION`，但 ClientApp 模型授权和默认模型解析当前只有单一 `isDefault` 语义。需要补齐 category-aware 的视觉模型选择能力：

- ConfigModel 继续复用 `LlmModelConfig`，通过 `category=VISION` 表达视觉模型。
- ClientApp 可以授权一个或多个 `VISION` 模型。
- 通用推理模型和视觉解析模型要分开解析：通用任务继续使用现有 effective `modelConfigId`，`analyze_attachment` 工具按需解析视觉模型。
- 视觉模型默认值不能覆盖通用默认模型。默认模型解析需要按 `LlmModelCategory` 区分，至少支持 `GENERAL` 与 `VISION` 分别默认。
- 未配置或未授权视觉模型时，工具可回退使用本次推理模型配置；如果推理模型不支持图片，返回可理解的模型调用错误，不把图片 URL 伪装成已解析内容。
- API Key、baseUrl 等敏感信息只进入 Worker runtime/tool 层，不进入 LLM 可见 prompt。

## 推荐工具契约

工具名：`analyze_attachment`

输入：

```json
{
  "attachment_id": "att-1",
  "purpose": "summary | ocr | extract_fields | verify_condition",
  "schema": {}
}
```

输出：

```json
{
  "attachment_id": "att-1",
  "summary": "...",
  "extracted_text": "...",
  "extracted_fields": {},
  "confidence": 0.0,
  "warnings": []
}
```

## 触发规则

- 不触发：用户只要求提交/创建/流转业务对象，并要求“附上图片”。
- 触发：用户明确要求“看图”“识别图片”“根据图片内容”“图片里是什么”。
- 触发：Skill 或业务函数 schema 要求的字段缺失，且附件可能包含这些字段。
- 触发：业务规则要求图片审核、票据 OCR、异常照片判定等。

## 模块责任

- `navigator-common`: 保持或扩展 `LlmModelConfig` 对视觉模型的表达能力，必要时补充 DTO/Form 字段。
- `metadata-config-module`: 保存、更新、查询 `category=VISION` 模型配置，并保证 DTO 回传完整。
- `business-agent-module`: ClientApp 模型授权默认值需要支持按 category 解析；任务上下文需要能让 BizWorker 解析视觉模型。
- `addons/langgraph-biz-worker`: 在启动 Python Worker 查询时解析并传递视觉模型 runtime config。
- `tools/langgraph-biz-worker`: 增加 `analyze_attachment` 工具、附件查找、视觉模型调用和按需触发提示。
- `packages/navigator-frontend` / `navigator-open-sdk`: 如控制面需要创建或展示视觉模型，补齐类型和交互。

## 验收标准

1. 用户说“提交异常工单并附图”时，不调用视觉解析工具，业务函数仍收到原始附件。
2. 用户说“根据图片内容提交异常工单”时，LLM 可调用 `analyze_attachment`，并将解析结果用于工单字段。
3. `category=VISION` 模型可配置、可授权给 ClientApp，且默认视觉模型不影响通用默认模型。
4. 未配置视觉模型时，图片解析请求回退使用推理模型；若推理模型不支持图片或没有模型配置，返回明确错误。
5. 视觉模型密钥不会出现在 LLM prompt、会话消息或普通工具结果里。

## 推进清单

- [x] 梳理现有附件链路与模型配置链路。
- [x] 设计并实现 ClientApp 视觉模型默认解析。
- [x] Java relay 传递 `vision_llm_config` 到 Python BizWorker。
- [x] Python BizWorker 注册 `analyze_attachment` 工具。
- [x] 补充按需触发 prompt 和工具调用测试。
- [x] 补充 Java 模型授权/默认值解析测试。
- [x] 如前端配置入口受影响，补齐体验验证记录：LLM 配置弹窗支持 `VISION` 类别保存 `LANGGRAPH_BIZ` Worker 后端，已通过前端类型检查与相关单测。
- [x] 质量复核修复：`analyze_attachment` 支持仅 URL 的常见图片后缀识别，模型异常返回脱敏摘要；root skill 只传原始 prompt，附件上下文统一由 LLM agent 注入；已配置但不可解析的视觉模型在 Java relay 显式失败，不静默回退。
- [x] 业务级脚本冒烟：覆盖“提交工单并附图”不调用 `analyze_attachment` 且附件原样进入业务函数 payload；覆盖“根据图片内容提交工单”先调用 `analyze_attachment`，视觉模型收到 OpenAI `image_url` 多模态消息，解析字段再进入业务函数 payload。

## 测试计划

- Java 单元测试：`ClientAppModelConfigGrantService` 按 category 解析默认模型；`LanggraphStreamRelay` 传递视觉模型配置。
- Python 单元测试：附件存在/不存在、无视觉模型配置、工具结果结构、默认不解析附件。
- 前端校验：`SettingsView` 类型检查；模型选项单测。
- 集成冒烟：带图片创建工单不解析；要求看图时解析；视觉模型缺配置时错误文案可理解。

## 验证记录

- 2026-05-18 Python 目标测试：`tools/langgraph-biz-worker/.venv/Scripts/python.exe -m pytest tests/test_attachment_analysis.py ... -q`，12 passed。
- 2026-05-18 Java 目标测试：`mvn -pl addons/langgraph-biz-worker -am -Dtest=LanggraphStreamRelayTest,LanggraphWorkerClientTest,LanggraphBusinessAgentWorkerTaskLauncherTest -Dsurefire.failIfNoSpecifiedTests=false test`，15 tests passed。
- 2026-05-18 BizWorker 业务级 E2E：`tools/langgraph-biz-worker/.venv/Scripts/python.exe -m pytest tests/test_attachment_analysis.py tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_tms_ticket_child_receives_attachment_context tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_ticket_with_attachment_does_not_analyze_image_by_default tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_ticket_from_image_content_analyzes_then_uses_result -q`，7 passed。
- 2026-05-18 Mock LLM 回归：`PYTHONPATH=src python -m pytest tests/test_openai_api.py tests/test_strategies.py -q`，22 passed；同时补齐 mock OpenAI ChatMessage 对多模态 content list 的接收、cursor 匹配和 token 估算。
- 2026-05-18 Java relay 复跑：`mvn -pl addons/langgraph-biz-worker -am -Dtest=LanggraphStreamRelayTest,LanggraphWorkerClientTest,LanggraphBusinessAgentWorkerTaskLauncherTest -Dsurefire.failIfNoSpecifiedTests=false test`，15 tests passed。前端本轮未改动，未复跑前端 typecheck。
- 2026-05-18 真实视觉模型冒烟：`qwen3.5-plus` 通过 `http://test.synthoflow.com:3061/v1` OpenAI 兼容接口支持 `image_url` 多模态协议；BizWorker `analyze_attachment` 使用公网图片 URL 调用成功，记录见 `test-records/real-llm-attachment-ticket/20260518-qwen35-vision-smoke.md`。API Key 未落档。
- 2026-05-18 OBS 签名 URL 冒烟：本地生成异常包裹 PNG，使用 obsutil 上传到 `obs://sd-files/images/biz-worker-damaged-package-20260518.png`；签名 URL GET 验证 HTTP 200；BizWorker `analyze_attachment` 识别出 `DAMAGED BOX`、`BROKEN PACKAGE`、`damage_visible=true`。签名参数未落档。

## 非目标

- 首期不要求自动解析所有图片。
- 首期不改变业务函数附件入参结构。
- 首期不把所有 Worker 改造成原生多模态消息输入；优先用显式工具保证成本、审计和触发边界可控。
