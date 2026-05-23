---
type: requirement
version: 1.3.0-SNAPSHOT
ticket: REQ-035
severity: medium
priority: P1
status: in_progress
owner: biz-worker-runtime
source: 2026-05-20 用户讨论确认
delivery_mode: single-root-delivery
---

# REQ-035: BizWorker Spreadsheet Attachment Tool

## Document Purpose

- doc_type: workitem
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 记录 BizWorker 对 Excel/Spreadsheet 附件的原生工具化解析需求、工具边界、安全约束、任务拆分和验收标准。

## Background

REQ-030 已补齐按需图片解析能力，`analyze_attachment` 面向 image 类附件，通过视觉模型在用户明确需要时解析图片内容。

Excel/Spreadsheet 属于更高频、更结构化的业务附件类型。它不适合走图片识别路径，也不适合只靠 skill 提示词完成：skill 可以指导 LLM 何时读取、如何解释表格，但无法替代 Worker 对二进制 workbook 的确定性解析、分页、限流、脱敏和来源追踪。

本轮讨论决定先做工具，不先做 spreadsheet skill。工具作为平台通用能力沉淀，后续如出现稳定的业务表格处理套路，再补 skill 作为编排说明层。

## Problem Statement

当前 BizWorker 对非图片附件缺少原生内容读取能力。用户上传 Excel 后，LLM 只能看到附件元数据，无法可靠回答以下常见问题：

- 这个 Excel 里有哪些 sheet、每个 sheet 大概是什么结构。
- 预览某个 sheet 的前几行，判断表头、字段和数据类型。
- 按范围或行号读取一小段数据。
- 从表格中抽取结构化 rows，供后续业务函数创建工单、批量导入、校验异常或生成汇总。

如果把 `list_sheets`、`inspect_sheet`、`read_range`、`detect_table`、`extract_rows` 都做成顶层工具，会扩大工具面，增加 LLM 选错工具和协议维护成本。BizWorker 需要的是一个稳定的 spreadsheet 工具入口，用 operation 参数表达子能力。

## Target Outcome

新增一个 BizWorker spreadsheet 附件解析工具，推荐工具名为 `analyze_spreadsheet`。

目标行为：

- Excel 附件默认只作为原始附件传递，不自动解析。
- 当用户明确要求查看、统计、校验或基于 Excel 内容执行业务动作时，LLM 可调用 `analyze_spreadsheet`。
- 工具首期聚焦确定性读取和小批量结构化抽取，不让 LLM 直接处理整份 workbook。
- 工具输出带来源追踪，包括 `attachment_id`、`sheet`、`range`、行列坐标、截断状态和 warnings。
- 大结果必须分页或落 artifact，正常工具结果只返回可控大小摘要。
- 图片解析继续由 `analyze_attachment` 负责，spreadsheet 工具不复用视觉模型。

## Recommended Tool Contract

工具名：`analyze_spreadsheet`

输入建议：

```json
{
  "attachment_id": "att-1",
  "operation": "summary | preview | read_range | extract_rows",
  "sheet": "Sheet1",
  "range": "A1:F30",
  "offset": 0,
  "limit": 50,
  "header_row": 1,
  "options": {
    "include_formulas": false,
    "include_empty_rows": false
  }
}
```

输出建议：

```json
{
  "attachment_id": "att-1",
  "operation": "preview",
  "workbook": {
    "file_name": "orders.xlsx",
    "format": "xlsx",
    "sheet_count": 2
  },
  "sheet": {
    "name": "Sheet1",
    "max_row": 1200,
    "max_column": 18
  },
  "range": "A1:F20",
  "columns": ["order_no", "status", "amount"],
  "rows": [],
  "truncated": true,
  "warnings": []
}
```

`operation` 语义：

- `summary`: 返回 workbook/sheet 元数据、sheet 列表、可见/隐藏状态、尺寸、粗略非空区域。
- `preview`: 返回指定 sheet 的前 N 行或工具自动选择的预览范围，用于让 LLM 判断表头和数据含义。
- `read_range`: 读取明确范围，如 `A1:F30`，用于用户指定位置或二次追问。
- `extract_rows`: 按 header row 抽取结构化 rows，支持 offset/limit 分页，并返回字段名到单元格来源的映射。

`detect_table` 首期不作为单独顶层工具；如实现表格区域识别，应作为 `summary` 或 `preview` 的内部提示信息，或后续加入同一工具的 `operation=detect_table`。

## Scope

In scope:

- 首期必须支持 `.xlsx` 附件；`.csv` 可在同一工具内低成本支持。
- 通过附件 id 查找现有 canonical attachment，并由 Worker 侧安全下载/读取。
- 使用确定性 parser 读取 workbook，不依赖视觉模型或 LLM 猜测。
- 支持 sheet 列表、sheet 预览、指定 range 读取、分页 rows 抽取。
- 对输出做大小限制和截断标记，必要时写入 artifact 并返回 artifact 引用。
- 单元格值保留基本类型信息，日期、数字、布尔、空值可区分。
- 公式单元格不得执行公式；默认读取缓存值，并可在 warnings 或 cell metadata 中标记 formula 存在。
- 所有日志、prompt、tool result 不暴露签名 URL、token、API key 或本地临时路径。

Out of scope:

- 首期不做 spreadsheet skill。
- 首期不把 `list_sheets`、`inspect_sheet`、`read_range`、`detect_table`、`extract_rows` 暴露成多个顶层工具。
- 首期不支持宏执行，不执行 VBA，不信任 workbook 内部外链。
- 首期不要求完整支持 `.xls`、`.xlsm`、复杂透视表、图表、合并单元格语义还原。
- 首期不做整表批量导入业务动作；业务动作仍由对应 business function 或 skill 决定。

## Module Responsibility

- `tools/langgraph-biz-worker`: 新增 spreadsheet parser、工具 schema、工具注册、附件读取、输出截断、artifact 写入和 Python 测试。
- `addons/langgraph-biz-worker`: 复核 Java relay 对 spreadsheet 附件 metadata 的透传；如已有 canonical attachment 足够，原则上不新增 Java 合同。
- `addons/claude-worker-agent` / OpenAPI boundary: 保持 top-level `attachments` 为 canonical 输入；仅在发现 media type/filename 识别缺口时补兼容测试。
- `business-agent-module` / SDK: 不新增业务函数协议；后续如上游 SDK 需要声明 spreadsheet 附件类型，再单独落项。
- `packages/navigator-frontend`: 本工作项不要求 UI 改动；附件上传体验已有能力复用。

## Implementation Notes

推荐实现策略：

- `.xlsx`: 优先使用 `openpyxl` 的 `read_only=True`、`data_only=True` 模式读取，避免加载整份 workbook 到内存。
- `.csv`: 使用 Python 标准库 `csv`，统一包装成单 sheet 语义。
- 文件类型识别同时参考 attachment `mime_type`、文件名后缀和必要的 magic/zip 检查。
- 设置硬限制：文件大小、sheet 数、最大行列、单次返回行数、单 cell 最大字符数、总输出字符数。
- 对 zip bomb、异常 workbook、损坏文件、密码保护文件返回明确错误，不把 parser traceback 暴露给 LLM。
- 解析结果只记录安全 evidence：附件 id、文件名、media type、sheet 名、range、digest 或截断标记。

## Acceptance Criteria

- 用户上传 `.xlsx` 并要求“看看这个 Excel 有哪些表”时，BizWorker 可调用 `analyze_spreadsheet(operation=summary)` 返回 sheet 列表和尺寸摘要。
- 用户要求“预览第一张表”时，工具返回有限行数的表格预览，结果包含 sheet/range 来源和 `truncated` 状态。
- 用户指定范围如 “读 Sheet1 的 A1:F30” 时，工具只读取该范围，不返回整份 workbook。
- 用户要求“按表头抽取前 50 行”时，工具返回结构化 rows，并保留行号/列来源。
- 图片附件解析仍走 `analyze_attachment`；Excel 工具不调用视觉模型。
- 未支持的附件类型、过大文件、密码保护文件、损坏 workbook 均返回可理解错误。
- 工具结果、日志和会话消息不包含原始签名 URL、token、临时文件路径或密钥。
- 工具面保持一个顶层 spreadsheet 工具，不新增多个 spreadsheet 顶层工具。

## Progress Tracking

Development progress:

- [x] 决策落档：Excel/Spreadsheet 作为高频通用能力，先做工具，不先做 skill。
- [x] 设计 `analyze_spreadsheet` 工具 schema、operation 枚举和结果结构。
- [x] 新增 `.xlsx` parser 与安全限制。
- [x] 接入附件查找、base64/local path/http(s) 附件读取和错误脱敏。
- [x] 接入工具注册、LLM tool schema 和按需触发 prompt。
- [x] 处理大结果 artifact 输出。

Testing progress:

- [x] Python 单元测试：summary、preview、read_range、extract_rows。
- [x] Python 单元测试：非 spreadsheet 类型、本地附件读取失败。
- [x] Python 单元测试：formula 标记、隐藏 sheet 元数据、签名 URL 不回显。
- [ ] Python 单元测试：不存在 attachment、损坏 workbook、密码保护或 parser 异常。
- [ ] Python 单元测试：大 workbook 截断、单元格长文本截断。
- [ ] BizWorker scripted E2E：上传 Excel 后按需调用 spreadsheet 工具，且默认直传附件时不解析。
- [x] 安全测试：签名 URL/token 不进入 tool result；本地路径读取失败不回显路径。

Experience progress:

- N/A。该工作项是 Worker 后端工具能力，不引入新 UI；体验验收以后端会话/tool 调用证据为准。

## Implementation References

- `tools/langgraph-biz-worker/src/langgraph_biz_worker/tools/spreadsheet_analysis.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_tool_dispatcher.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_tool_schemas.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py`
- `tools/langgraph-biz-worker/tests/test_spreadsheet_analysis.py`
- `tools/langgraph-biz-worker/tests/test_llm_tool_schemas.py`

## Validation Evidence

- `cd tools/langgraph-biz-worker; $env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest tests/test_spreadsheet_analysis.py tests/test_llm_tool_schemas.py -q` -> `9 passed in 0.20s`
- `cd tools/langgraph-biz-worker; $env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest tests/test_attachment_analysis.py tests/test_llm_tool_dispatcher.py tests/test_root_graph.py -q` -> `14 passed in 0.14s`
- `cd tools/langgraph-biz-worker; $env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest tests/test_spreadsheet_analysis.py tests/test_llm_tool_schemas.py tests/test_attachment_analysis.py tests/test_llm_tool_dispatcher.py tests/test_root_graph.py tests/test_llm_skill_agent.py -q` -> `68 passed in 2.10s`

## Review And Acceptance Workflow

- 实现完成后先执行目标 Python 测试和相关 Java relay 测试。
- 对工具 schema、错误返回、日志字段做实现质量自检，重点检查工具面是否膨胀、是否泄漏敏感 URL、是否存在整表无限读取。
- 如改动 OpenAPI 附件归一化或 Java relay 合同，需要补充对应 Java 单测并记录验证命令。
- 验收记录应包含至少一个 `.xlsx` summary/preview/extract_rows 的 scripted E2E 证据。

## References

- `REQ-030-biz-worker-on-demand-attachment-analysis-and-vision-model-config.md`
- `OPT-032-attachment-preprocessing-governance-follow-up.md`
