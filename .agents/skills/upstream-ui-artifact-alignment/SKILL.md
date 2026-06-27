---
name: upstream-ui-artifact-alignment
description: 审查或生成上游业务系统返回给 NAVI 的 UI Artifact / AG-UI 对齐协议。当用户要求 TMS 或其他上游系统接入页面预览、业务动作、structured_output、OPEN_ARTIFACT、AG-UI 对齐时使用。
---

# Upstream UI Artifact Alignment

审查或生成上游业务系统返回给 NAVI 的 UI action 协议，使其对齐 `Navigator UI Artifact Protocol v1` 和 AG-UI 的工具结果承载思路。

## 使用场景

当用户需要以下操作时使用：
- 将 TMS 或其他上游系统的 `structured_output` 转换为标准 `OPEN_ARTIFACT`
- 设计业务函数返回的页面动作协议
- 审查 skill、manifest、DTO、Controller 测试是否符合 NAVI artifact 协议
- 为 iframe 预览、弹窗、侧栏、新页签打开能力生成联调要求

## 执行流程

1. 定位现有上游输出：查找 `structured_output`、`structuredOutput`、`OPEN_TMS_PAGE`、`PRINT_TEMPLATE_PREVIEW`、`artifact`、`previewUrl`、`openUrl`。
2. 判断目标 UI 的归属：
   - 上游业务系统托管页面 → 使用 `artifact.kind=iframe` 或 `link`
   - NAVI 内部路由 → 使用 `artifact.kind=route`
   - 外部可信页面 → 使用 `artifact.kind=link`
3. 将旧 action 转换为 `OPEN_ARTIFACT`：
   - `type` 固定为 `OPEN_ARTIFACT`
   - `label` 使用上游显式给出的最终用户中文按钮文案
   - `previewUrl`、`url` 或 `uri` 映射到 `artifact.uri`
   - `openUrl` 映射到 `artifact.fallbackUrl`
   - 业务 id 写入 `context`，并用于生成稳定 `artifact.id`
4. 明确迁移策略：
   - 过渡期可以在同一个结果里保留 `previewUrl`、`openUrl`、`routeName`、`query` 等旧字段
   - 标准动作必须以 `OPEN_ARTIFACT` 的 `artifact` 和 `context` 为准
   - 不允许继续新增业务专用 action 类型
   - 等 NAVI 已部署并验证 `structured_output` 识别后，再安排旧字段清理
5. 检查函数 schema 是否声明 `structured_output.type`、`label`、`artifact`、`context`。
6. 检查 skill 文案是否要求 LLM 复用函数返回的 `structured_output`，且禁止自行拼 URL 或声称草稿已发布。
7. 输出需要修改的文件、目标 JSON 样例、测试断言和联调验收步骤。

## 输出格式

使用以下结构输出：

````markdown
**结论**
{是否已对齐；如果未对齐，说明最小差距}

**目标协议**
```json
{OPEN_ARTIFACT structured_output 示例}
```

**改动点**
- {文件或模块}: {需要调整的内容}

**测试**
- {需要新增或更新的单元测试/联调测试}

**联调注意**
- {allowlist、iframe headers、sandbox、fallback、鉴权}
````

## 约束条件

- 不新增业务专用 action 类型，例如 `PRINT_TEMPLATE_PREVIEW`、`OPEN_X_PAGE`。
- 过渡期允许保留旧字段做兼容，但 NAVI 主路径必须读取 `OPEN_ARTIFACT`。
- 不让 NAVI 执行业务 HTML 或脚本。
- 不让 LLM 自行拼接业务 URL，如果函数结果已经返回 URL。
- 不要求上游第一阶段实现完整 AG-UI 事件流；先把 tool result 中的 UI intent 标准化。
- 不把 TMS 专用组件合入 NAVI 主工程。

## 标准 Action 模板

```json
{
  "type": "OPEN_ARTIFACT",
  "label": "查看模板预览",
  "artifact": {
    "kind": "iframe",
    "id": "print-template-preview:tpl_123",
    "title": "面单模板预览",
    "uri": "/print-template-preview?templateId=tpl_123",
    "openMode": "side_panel",
    "fallbackUrl": "/print-templates?templateId=tpl_123"
  },
  "context": {
    "businessDomain": "tms.print",
    "templateId": "tpl_123"
  }
}
```

## 决策规则

- 如果旧协议只有 `routeName` 且该路由属于 TMS 前端 → 改为 `iframe` 或 `link`，不要用 NAVI `route`。
- 如果页面需要在聊天上下文旁预览 → 默认 `openMode=side_panel`。
- 如果页面需要大面积操作 → 使用 `dialog` 或将 `fallbackUrl` 指向完整配置页。
- 如果 iframe 可能被 CSP 或登录态阻塞 → 必须提供 `fallbackUrl`。
- 如果 action 缺少业务域或业务 id → 要求补充 `context.businessDomain` 和对应业务 id。
- 如果上游要求兼容期 → 允许保留旧字段，但输出中必须包含完整 `OPEN_ARTIFACT`，且测试要证明 NAVI 能从 `structured_output` 识别按钮。
