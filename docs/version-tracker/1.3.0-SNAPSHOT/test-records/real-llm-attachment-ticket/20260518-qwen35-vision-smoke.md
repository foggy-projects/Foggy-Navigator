# 2026-05-18 qwen3.5-plus 视觉模型真实冒烟

## 目标

验证 `qwen3.5-plus` 在 `http://test.synthoflow.com:3061/v1` OpenAI 兼容接口下是否支持 BizWorker `analyze_attachment` 所需的 `image_url` 多模态协议。

## 凭据处理

- 使用临时环境变量注入 `OPENAI_BASE_URL` 与 `OPENAI_API_KEY`。
- API Key 未写入代码、测试记录或命令输出归档。

## 验证结果

1. `/models` 可用，返回模型列表包含 `qwen3.5-plus`。
2. 文本 Chat Completions 调用成功，说明 base URL、模型名和凭据可用。
3. `image_url` data URL 调用支持多模态协议：
   - 1x1 PNG 返回 400，错误为图片宽高不满足模型限制，说明请求进入视觉校验链路。
   - 32x32 红色 PNG 返回成功，内容识别为 red。
4. 公网图片 URL 调用成功：
   - 图片：`https://dashscope.oss-cn-beijing.aliyuncs.com/images/dog_and_girl.jpeg`
   - 模型返回中文图片摘要。
5. BizWorker `analyze_attachment` 工具真实调用成功：
   - `ok=true`
   - `model_source=vision`
   - `summary=一名女子坐在沙滩上与一只狗握手，背景为海洋和日落光线。`
   - `confidence=0.95`
   - `warnings=["未检测到业务文档内容", "图片中无可见文本"]`
6. 本地生成异常包裹测试图并通过 obsutil 上传到华为云 OBS 后，BizWorker 使用 OBS 签名 URL 识别成功：
   - 本地文件：`.tmp/vision-smoke/biz-worker-damaged-package.png`
   - OBS 对象：`obs://sd-files/images/biz-worker-damaged-package-20260518.png`
   - GET 验证：HTTP 200，`Content-Type=image/png`，`Bytes=11132`
   - HEAD 验证：HTTP 403；OBS 签名 URL 对 GET 可用，但 HEAD 不在本次签名方法允许范围内
   - BizWorker `analyze_attachment` 返回：
     - `ok=true`
     - `model_source=vision`
     - `summary=The image is a graphic indicator for a package damage exception, displaying a box with a red cross and explicit text labels.`
     - `extracted_text=["EXCEPTION PHOTO","DAMAGED BOX","BROKEN PACKAGE"]`
     - `extracted_fields.exception_type=Packaging Damage`
     - `extracted_fields.damage_visible=true`
     - `confidence=0.95`

## 观察

- `qwen3.5-plus` 可以作为 `VISION` 配置用于 BizWorker 首期图片解析。
- 外部图片 URL 的可达性会影响耗时和成功率；同轮验证中 Wikimedia 图片 URL 曾出现 60 秒超时，而 DashScope OSS 图片可正常识别。
- 华为云 OBS 签名 URL 的验证建议使用 GET；HEAD 可能因签名方法不匹配返回 403，但不影响模型侧按 GET 抓取图片。
- 后续真实业务联调应优先使用上游附件系统生成的实际图片链接，验证签名 URL、过期时间、跨网访问和模型侧抓取能力。
