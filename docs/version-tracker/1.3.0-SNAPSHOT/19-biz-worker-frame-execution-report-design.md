# BizWorker Frame Execution Report Design

## 文档作用

- doc_type: design
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 记录 BizWorker frame execution report / provenance markdown 的设计，用于人类评审、LLM 回溯和 active_plan step 证据挂接。

## Version

`1.3.0-SNAPSHOT`

## Status

`PHASE_1_IMPLEMENTED`

## Priority

`P1 - follow-up design`

## Background

当前 BizWorker frame journal 已经把 `SkillFrameState` 以 JSON 快照形式保存到本地，例如：

```text
tools/langgraph-biz-worker/data/frames/<task-id>/<frame-id>.json
tools/langgraph-biz-worker/data/frames/by-conversation/<conversation-id>/<frame-id>.json
```

这些 JSON 是事实来源，但不适合人类直接评审，也不适合 LLM 在后续回溯时直接通读。原因包括：

1. 原始 frame state 是运行时结构，字段面向恢复和状态机，不面向 review。
2. private messages、tool args、tool outputs 可能较长或包含敏感信息。
3. child frame、function frame、approval resume、active_plan step 之间的关系需要跨文件理解。
4. 如果只把 child frame 的最终 result 返回给 parent LLM，后续很难解释“这个结果是如何得到的”。

因此需要增加 deterministic `frame_execution_report`：由 runtime 读取 frame journal / frame store 中的事实数据，生成 Markdown 报告和 compact digest。LLM 可以在需要时读取报告，人类也可以直接打开审阅。

## Design Position

`frame_execution_report` 不是模型记忆，也不是模型 chain-of-thought。它是 runtime provenance 的人类可读投影。

核心原则：

1. 事实来源是 frame journal / runtime state，不是 LLM 自由回忆。
2. 默认生成 Markdown，必要时同时生成 JSON digest。
3. 报告记录可观察执行过程：frame 状态、输入摘要、tool call、child frame、approval、错误、最终输出、evidence refs。
4. 不记录 raw chain-of-thought。
5. tool 输入输出必须脱敏、截断、摘要化。
6. parent LLM 默认只拿 compact digest；完整 Markdown 通过读取工具按需加载。
7. report 是 review/provenance，不替代业务最终答复，不绕过 user grant、approvalRequired 或 function audit。

## Target Outcomes

1. 人类可以点击或打开 Markdown，快速理解某个 frame 做了什么。
2. LLM 在用户追问“刚才怎么执行的”“为什么这么判断”时，可以读取 report，而不是重新翻原始 JSON。
3. `active_plan.steps[*]` 可以挂 `execution_report_ref`，形成 plan step 到执行证据的链路。
4. child skill/function frame 完成、失败、取消、审批挂起时都有可审计报告。
5. root frame 的 persistent turn 也可以生成 turn-level report，辅助复杂会话回溯。

## Report Sources

第一阶段读取以下数据：

1. `SkillFrameState`
   - `frame_id`
   - `task_id`
   - `conversation_id`
   - `session_id`
   - `current_task_id`
   - `origin_task_id`
   - `skill_id`
   - `frame_kind`
   - `parent_frame_id`
   - `status`
   - `started_at`
   - `ended_at`
   - `input`
   - `output`
   - `result_summary`
   - `artifact_refs`
   - `evidence_refs`
   - `approval_request`
   - `tool_calls`
   - `child_frame_ids`
   - selected `private_working_state`
2. Related child frame snapshots from the same task/conversation journal.
3. Related function call frame snapshots.
4. Selected root context summary:
   - `active_plan`
   - `plan_history`
   - `interruption_history`
   - `focus_history`
   - recent promoted child results.

第一阶段不读取：

1. raw private messages full content。
2. full tool output if larger than configured threshold。
3. account files、memory files、artifact full content。
4. unrelated task/conversation frame state。

## Markdown Shape

建议 Markdown 输出结构：

```markdown
# Frame Execution Report: frm_xxx

## Summary

- skill_id:
- frame_kind:
- status:
- task_id:
- conversation_id:
- parent_frame_id:
- started_at:
- ended_at:
- duration:

## Instruction And Input

- user_instruction:
- skill_input_summary:

## Execution Timeline

| order | type | target | status | summary | evidence |
| --- | --- | --- | --- | --- | --- |
| 1 | tool_call | invoke_business_function:tms.vehicle.create@v1 | SUSPENDED | approval required | sus_xxx |
| 2 | approval | approved | COMPLETED | user approved | approval_event_ref |
| 3 | tool_result | tms.vehicle.create@v1 | SUCCESS | outputCode=200 | evidence_ref |

## Child Frames

| frame_id | skill_id | status | summary | report_ref |
| --- | --- | --- | --- | --- |

## Approval And Suspension

- approval_required:
- suspend_id:
- approval_result:
- post_approval_message_ref:
- business_function_result_message_ref:

## Result

- result_summary:
- structured_output_summary:
- artifact_refs:
- evidence_refs:

## Errors And Interruptions

- error:
- continuation_state:
- recoverable_focus:
- interruption_history:

## Active Plan Linkage

- plan_id:
- step_id:
- step_status:
- next_step:

## Source

- frame_source_path:
- generated_at:
- generator_version:
```

## Storage Design

建议新增 report 目录：

```text
tools/langgraph-biz-worker/data/frame-reports/
  <task-id>/
    <frame-id>.md
    <frame-id>.digest.json
  by-conversation/
    <conversation-id>/
      <frame-id>.md
      <frame-id>.digest.json
```

Markdown 面向人类和 LLM 按需读取；digest JSON 面向 prompt compact injection、active_plan step linkage 和 UI 列表。

示例 digest：

```json
{
  "report_ref": "frame-report://frm_xxx",
  "frame_id": "frm_xxx",
  "skill_id": "tms-fulfillment-agent",
  "frame_kind": "SKILL",
  "status": "COMPLETED",
  "summary": "Vehicle creation completed after approval.",
  "started_at": "...",
  "ended_at": "...",
  "tool_call_count": 1,
  "child_frame_count": 0,
  "approval_required": true,
  "error": null,
  "artifact_refs": [],
  "evidence_refs": ["business-function:tms.vehicle.create:v1"]
}
```

## Generation Timing

第一阶段建议覆盖以下时机：

1. `submit_result` 成功后：
   - 普通 skill frame `COMPLETED`。
2. `submit_persistent_turn_result` 成功后：
   - root persistent turn-level report。
3. `fail_frame` / `cancel_frame` 后：
   - 失败或取消报告。
4. function call frame 进入 `AWAITING_APPROVAL` 后：
   - approval pending 报告。
5. business function result message 写回后：
   - 更新或重新生成 function frame/root turn report。

注意：如果当前 close 逻辑会清理 private state，则 report 必须在清理前生成，或基于 journal 快照生成。

## LLM Access Pattern

不建议默认把完整 Markdown report 注入 root prompt。推荐分层：

1. 默认注入：
   - `frame_execution_report_digest`
   - `active_plan.steps[*].execution_report_ref`
   - compact evidence refs。
2. 按需读取：
   - 新增工具 `read_frame_execution_report`。
   - 参数：`frame_id`、`mode=summary|markdown|metadata`、`max_chars`。
3. 与 existing file tool 关系：
   - 若已有受控文件读取工具覆盖 report 目录，也可以先通过文件工具读取。
   - 但长期建议提供专用工具，方便权限、脱敏、截断和 report_ref 解析。

工具返回示例：

```json
{
  "ok": true,
  "report_ref": "frame-report://frm_xxx",
  "mode": "summary",
  "summary": "Vehicle creation completed after approval.",
  "markdown_excerpt": "...",
  "source_path": "data/frame-reports/<task-id>/frm_xxx.md"
}
```

## UI Access Pattern

UI 可以在以下位置展示“查看执行报告”：

1. skill frame timeline。
2. tool call / business function result message。
3. active plan step detail。
4. task debug panel。
5. approval detail drawer。

默认展示 digest；点击后加载 Markdown。Markdown 中的 sensitive sections 可以折叠或按权限隐藏。

## Active Plan Linkage

`active_plan` step 可以记录 report ref：

```json
{
  "step_id": "step_2",
  "objective": "创建车辆",
  "status": "COMPLETED",
  "execution_report_ref": "frame-report://frm_xxx",
  "evidence_refs": ["business-function:tms.vehicle.create:v1"]
}
```

这样 root LLM 在用户输入“下一步计划”“继续”“刚才为什么这么做”时，先看 compact active plan；如果需要细节，再读取对应 frame report。

## Redaction And Compaction

必须实现统一脱敏与压缩策略：

1. Secret fields：
   - key、token、password、secret、authorization、cookie、credential 等字段默认替换为 `<redacted>`。
2. Business PII：
   - 第一阶段可以只截断，不做复杂 PII 分类。
   - 后续可接入字段级 classification。
3. Large payload：
   - 单字段超过阈值时输出摘要、大小、keys。
   - full content 放 artifact，不直接放 report。
4. Tool args/output：
   - 默认只保留 input summary、output summary、status、target id。
   - 需要调试时由权限控制读取完整 raw source。
5. Markdown escaping：
   - 防止 tool output 注入伪标题、伪链接、伪命令。

## Industry Alignment

本设计与主流 agent observability / memory 方向基本对齐：

1. OpenAI Agents SDK tracing
   - 官方 tracing 记录 agent run 过程中的 LLM generations、tool calls、handoffs、guardrails 和 custom events。
   - 对齐点：我们也把 frame 执行过程当成可观测 trace/provenance，而不是只保留最终答复。
   - 差异点：OpenAI tracing 偏在线 trace 后端；我们的 MVP 先生成本地 Markdown + digest，方便离线审计和 LLM 按需读取。
   - Reference: https://openai.github.io/openai-agents-python/tracing/
2. LangSmith Observability / RunTree
   - LangSmith traces 记录 agent 从输入到最终回复的每一步，包括 tool calls、model interactions、decision points；RunTree 支持 trace/root run 与 child span。
   - 对齐点：BizWorker frame/child frame/function frame 天然类似 trace/span tree，report 是这棵树的人类可读投影。
   - 差异点：LangSmith 是通用观测平台；我们的 report 与业务审批、active_plan、frame journal 强绑定。
   - References:
     - https://docs.langchain.com/oss/python/langchain/observability
     - https://reference.langchain.com/javascript/langsmith/index/RunTree
3. LangGraph Persistence
   - LangGraph persistence 使用 thread/checkpoint 保存图状态，支持跨交互恢复和状态回放。
   - 对齐点：BizWorker frame journal 与 root persistent frame 承担类似的状态保存职责；report 不替代 checkpoint，而是 checkpoint/frame state 的 review projection。
   - Reference: https://docs.langchain.com/oss/python/langgraph/persistence
4. Claude Code Memory / CLAUDE.md
   - Claude Code 使用 project/user memory 文件为后续会话提供规则和长期上下文。
   - 对齐点：Markdown 是 LLM 友好的 context artifact，人类和模型都能读取。
   - 差异点：frame report 是事实性 execution provenance，不是项目规则或偏好 memory；它应按需读取，不应长期默认注入。
   - Reference: https://docs.anthropic.com/en/docs/claude-code/memory

结论：主流做法不是让 LLM 自己凭记忆写过程，而是保留 machine trace，再提供可读 summary/report。BizWorker 应采用“deterministic trace/report + LLM optional narrative”的组合。

## Better Practices To Borrow

1. Span tree model
   - 把 root frame、child skill frame、function frame 映射为 trace/span tree。
   - report 中保留 parent/child 关系和 execution order。
2. Distributed trace metadata
   - 后续可以为 Java task、Worker frame、business execution、approval suspension 统一 trace id。
3. Attachments / artifacts
   - 大 payload 不进 report，转 artifact ref。
4. Queryable digest
   - Markdown 适合读，JSON digest 适合查。
   - 后续 UI/API 不应解析 Markdown，而应读 digest 或索引表。
5. Deterministic report first, LLM narrative second
   - deterministic 部分是事实。
   - LLM narrative 只能作为补充字段，并标记 generated_by_model。
6. Permission-aware report access
   - report 可能包含业务信息，读取工具必须按 account/client/task 权限校验。
7. Retention policy
   - report 和 raw frame journal 应有保留期、压缩、清理策略。

## Non-goals

1. 不记录 raw chain-of-thought。
2. 不让 LLM 直接修改 frame journal。
3. 不把完整 report 默认塞进 prompt。
4. 不把 report 当成业务最终结果。
5. 不在第一阶段实现跨服务统一 trace backend。
6. 不在第一阶段替换现有 frame journal。

## Implementation Plan

### Phase 1 - Offline Generator

1. 新增 `FrameExecutionReportGenerator`。
2. 输入：`task_id + frame_id` 或 frame JSON path。
3. 输出：Markdown report + digest JSON。
4. 支持 child frame 递归摘要。
5. 支持 redaction、payload truncation。
6. 增加 CLI 或测试 helper，能对示例目录生成报告。

Implementation status on 2026-05-17:

1. 已新增 `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/frame_execution_report.py`。
2. 已支持从 `FileFrameJournal` 按 `task_id + frame_id` 生成报告。
3. 已支持从 frame JSON path 直接生成报告。
4. 已输出：
   - `frame-reports/<task-id>/<frame-id>.md`
   - `frame-reports/<task-id>/<frame-id>.digest.json`
   - `frame-reports/by-conversation/<conversation-id>/...`
5. `report_ref` 已采用 `frame-report://<task-id>/<frame-id>`，避免只有 frame_id 时跨任务歧义。
6. 已覆盖 child frame recursive report、approval pending、redaction、large payload truncation。
7. `read_frame_execution_report` 工具、runtime hook、active_plan 写回仍留在 Phase 2/3，不纳入 Phase 1 完成口径。

### Phase 2 - Runtime Hooks

1. frame 完成、失败、取消时自动生成 report。
2. function frame AWAITING_APPROVAL 时生成 pending report。
3. business function result 写回后更新 report。
4. promoted child result 中附加 `execution_report_ref`。
5. root `active_plan.steps[*]` 可写入 `execution_report_ref`。

### Phase 3 - Read Tool And UI

1. 新增 `read_frame_execution_report`。
2. 支持 `summary|metadata|markdown`。
3. UI 增加 report 链接。
4. 对 report 读取做权限和脱敏。

### Phase 4 - Optional LLM Narrative

1. 在 deterministic report 生成后，可选调用 reviewer/summarizer LLM 补一段 narrative。
2. narrative 必须引用 deterministic sections，不能成为唯一事实来源。
3. narrative 应标记模型、时间、输入 digest。

## Test Plan

第一阶段至少补：

1. `test_generate_report_from_completed_skill_frame`
   - 输入 completed skill frame JSON。
   - 输出包含 summary、input、result、evidence refs。
2. `test_generate_report_includes_child_frame_digest`
   - parent frame 有 child_frame_ids。
   - report 包含 child frame 表格和 report ref。
3. `test_generate_report_redacts_sensitive_tool_args`
   - tool args 包含 token/password。
   - Markdown 中只能出现 `<redacted>`。
4. `test_generate_report_truncates_large_payload`
   - output 超阈值。
   - report 保留 size/keys/summary。
5. `test_read_frame_execution_report_summary_mode`
   - 工具 summary mode 不返回 full Markdown。
6. `test_active_plan_step_links_execution_report_ref`
   - child frame 完成后 promoted result 或 active_plan step 包含 report ref。
7. `test_report_generation_before_private_state_cleanup`
   - close frame 后 report 仍包含必要 digest。

## Acceptance Criteria

1. 给定本地 frame journal 目录，可生成 Markdown report。
2. report 不包含敏感字段原文。
3. report 能展示 parent/child/function/approval 关键链路。
4. digest JSON 可被 root prompt 或 active_plan step 引用。
5. LLM 可通过工具按需读取 report。
6. UI 或本地文件系统可直接打开 Markdown。
7. 相关 mock / unit tests 覆盖 completed、failed、approval、child frame、redaction。

## Open Questions

1. report 是否要在 Python Worker 生成，还是 Java 侧也需要镜像一份索引。
2. report ref 使用 `frame-report://<frame_id>` 是否足够，还是需要带 task/context。
3. full Markdown 是否应进入 artifact store，而不是普通 data 目录。
4. UI 权限是否按 task/account/client app 继承，还是单独做 report grant。
5. report retention 是否跟 frame journal 一致。
6. 是否需要把 report digest 写回 Java `langgraph_tasks.structured_output` 或 message metadata。
