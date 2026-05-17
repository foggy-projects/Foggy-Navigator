# BizWorker Complex Task Plan Observer Design

## Purpose

记录 BizWorker 在复杂用户请求下的“先建计划、执行中保留计划、退出前检查是否偏离计划”的后续设计方向。本文只做设计落档，不进入本轮实现。

## Version

`1.3.0-SNAPSHOT`

## Status

`ACTIVE_PLAN_MVP_IMPLEMENTED; OBSERVER_DESIGN_ONLY`

## Priority

`P1 - follow-up design`

## Background

root skill 已经作为特殊常驻 skill 承担 task-level orchestration：它可以在同一任务 frame 中调用业务 skill、业务函数，并通过 `recoverable_focus_*` 在中断后支持“继续 / 搁置 / 澄清 / 无关新任务”的 intent resolution。

新的问题是：当用户一次输入包含多个问题、多个目标或多个依赖步骤时，root skill 仅依赖 LLM 自觉规划仍不够稳。典型复杂请求可能需要：

1. 先调用一个查询类 skill 获取上下文。
2. 再调用一个诊断类 skill 判断异常原因。
3. 再调用一个执行类 skill 或业务函数创建/更新业务对象。
4. 最后汇总给用户，并说明哪些步骤完成、哪些步骤失败或等待用户确认。

这类任务需要显式的 plan state 和退出前一致性检查，避免模型在复杂任务中遗漏步骤、跳过必要确认、或在 child skill 结果尚未完整回流时提前给出最终答复。

## Design Position

短期不引入独立 plan agent，也不让模型任意创建裸 frame。推荐先引入 root-owned `active_plan`，再设计一个受控的只读 `PLAN_REVIEW` / `PLAN_OBSERVER` 内部 frame，用于在关键 checkpoint 对计划执行情况做一致性检查。

这个 observer 的定位不是任务管理者，而是外部观察者：

1. 不直接执行业务函数。
2. 不调用业务 skill。
3. 不拥有全局任务生命周期。
4. 只读取计划、step 状态、promoted result、tool audit summary、evidence refs。
5. 输出结构化检查结果，由 parent root skill 决定继续执行、澄清、搁置或最终回复。

## Active Plan Model

复杂任务被识别后，root skill 应先创建 `active_plan`，再开始调用业务 skill/function。

建议字段：

```json
{
  "plan_id": "plan_xxx",
  "source_user_message_id": "msg_xxx",
  "complexity": "multi_intent | multi_step | cross_skill | high_risk_side_effect",
  "status": "PLANNED | RUNNING | NEEDS_CLARIFICATION | BLOCKED | NEEDS_REVIEW | REVIEWING | NEEDS_CORRECTION | COMPLETED | FAILED | SHELVED",
  "steps": [
    {
      "step_id": "step_1",
      "objective": "查询订单当前状态",
      "expected_capability": "business_skill | business_function | reasoning_only",
      "target_skill_id": "optional",
      "target_function_id": "optional",
      "dependencies": [],
      "status": "PENDING | RUNNING | COMPLETED | FAILED | BLOCKED | SKIPPED",
      "result_ref": "optional",
      "evidence_refs": [],
      "risk": "none | user_confirmation | approval_required | side_effect"
    }
  ],
  "review": {
    "required": true,
    "last_review_status": "NOT_REVIEWED",
    "last_review_frame_id": null,
    "correction_count": 0
  }
}
```

第一阶段可以把 `active_plan` 放在 root frame working state 中。后续如需要跨 task 或跨会话追踪，再考虑独立持久化表。

## Active Plan MVP Implementation

2026-05-17 已完成第一阶段最小实现：

1. `system.root` persistent frame 在用户提示中注入 root planning policy。
2. 当 root working state 存在 `active_plan` 时，下一轮 prompt 注入 `Active task plan` 与退出前对齐规则。
3. `submit_skill_result.structured_output.active_plan` 会同步到 root frame `private_working_state.active_plan`。
4. `root_context_summary.active_plan` 同步保留 compact plan，供 summary-visible child skill 和 function context snapshot 使用。
5. 当 `active_plan.status/state/plan_status` 为 `COMPLETED`、`DONE`、`CANCELLED`、`ABANDONED`、`SHELVED` 等终态时，runtime 会归档到 `root_context_summary.plan_history` 并清理当前 active plan。
6. 当 `intent_resolution` 为 `ABANDON_PREVIOUS` 或 `START_UNRELATED_NEW_TASK` 时，runtime 会把旧 `active_plan` 归档到 `plan_history`，避免用户切换任务后误恢复旧计划。

本阶段仍不做 deterministic complexity gate，也不实现 observer frame。复杂任务是否先创建 plan 仍由 root prompt 和 mock LLM E2E 固化。

## Frame And Prompt Integration

`active_plan` 第一阶段不是一个新 frame，也不是新的 LangGraph 节点。它是 `system.root` persistent frame 的 working state，由 root LLM 通过工具结果提交，由 runtime 再决定是否保存、归档或注入下一轮 prompt。

核心链路如下：

1. root frame 创建或复用：
   - 新任务进入 BizWorker 后，route 层会按会话/context 查找或创建常驻 `system.root` frame。
   - 该 frame 持续保持 `RUNNING`，每个用户任务只是一次 persistent turn，不会关闭 root frame。
2. LLM 生成或更新计划：
   - root LLM 不能直接写 frame。
   - 它只能通过 `submit_skill_result` 提交本轮结果。
   - 如果本轮需要保留复杂任务状态，LLM 在 `structured_output.active_plan` 中返回 compact plan。
3. runtime 接管状态：
   - `LlmSkillAgent` 收到 `submit_skill_result` tool call 后调用 `SkillRuntime.submit_persistent_turn_result`。
   - `SkillRuntime` 校验输出后调用 active plan 同步逻辑。
   - 非终态 plan 写入 `frame.private_working_state.active_plan`。
   - 同时镜像到 `frame.private_working_state.root_context_summary.active_plan`。
4. 下一轮 prompt 注入：
   - 下一次同一 root frame 运行时，`LlmSkillAgent.run(..., persistent_frame=True)` 会读取 `frame.private_working_state.active_plan`。
   - 如果存在，则放入内部 `runtime_context._active_plan`。
   - `_build_user_prompt` 会生成 `Active task plan:` 段落，作为 user message 的一部分提交给 LLM。
   - root prompt 还会注入 `Persistent root planning policy:`，提醒复杂任务要维护 `submit_skill_result.structured_output.active_plan`。
5. plan 结束或搁置：
   - 如果 `active_plan.status/state/plan_status` 是 `COMPLETED`、`DONE`、`CANCELLED`、`ABANDONED`、`SHELVED` 等终态，runtime 会把当前 plan 归档到 `root_context_summary.plan_history`，再清理 `active_plan`。
   - 如果本轮 `intent_resolution` 是 `ABANDON_PREVIOUS` 或 `START_UNRELATED_NEW_TASK`，runtime 同样会归档旧 plan 并清理当前 plan。

因此，`active_plan` 介入 frame 机制的位置是 root frame 的 `private_working_state`，介入 LLM 的位置是下一轮 root prompt 的 user message，不是 system prompt，不是长期 memory 文件，也不是 child frame 的完整上下文穿透。

### What The LLM Sees

root LLM 在 persistent frame 下会看到三类与计划相关的上下文：

1. `Persistent root planning policy`
   - 每次 root persistent turn 都会出现。
   - 作用是告诉模型：复杂、多意图、多 skill、外部协作任务要维护 `active_plan`。
2. `Active task plan`
   - 只有 root frame working state 中已有 `active_plan` 时才出现。
   - 内容是 JSON 化的 compact plan。
   - 作用是让模型知道当前复杂任务的目标、步骤、完成度和下一步。
3. `recoverable_focus` / `recoverable_focus_stack`
   - 只有发生可恢复中断时才出现。
   - 作用是告诉模型当前应该恢复哪个 frame 或者是否需要搁置。

child skill 不默认看到 root 的完整 private context。只有当 child skill 的 `context_visibility=summary` 时，runtime 才会把 `root_context_summary` 放入 child prompt。此时 child 可能看到 compact `active_plan`，但不会看到 root private messages 或未暴露的完整执行历史。

business function frame 的 context snapshot 也只拿 root context summary，用于审计和恢复归属，不等于完整上下文穿透。

### What The LLM Does Not See

当前设计下，LLM 不会自动看到以下内容：

1. frame journal 中的完整历史。
2. 已被 compact 掉的完整 private messages。
3. 本机 Codex/Claude session JSONL 的原始工具调用列表。
4. 其他 task/context 的 root frame working state。
5. child skill 的 private messages，除非后续设计明确允许 summary 或 passthrough。

这意味着“下一步计划”“继续”“开始吧”这类短提示词必须依赖当前 root prompt 中显式注入的 `active_plan`、`recoverable_focus_*`、`root_context_summary` 来判断，而不能假设模型天然知道完整历史。

### Current Implementation Touchpoints

当前 MVP 的代码触点：

1. `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_agent.py`
   - `LlmSkillAgent.run`：从 root frame working state 读取 active plan 并放入 runtime context。
   - `_build_active_plan_prompt`：把 active plan 转为 `Active task plan:` prompt 段落。
   - `_build_root_planning_policy_prompt`：给 persistent root 注入规划策略。
   - `submit_skill_result` tool schema：说明 `structured_output` 可携带 `active_plan` 和 `intent_resolution`。
2. `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/skill_runtime.py`
   - `submit_persistent_turn_result`：root turn 不关闭 frame，只记录 turn result。
   - `_sync_active_plan_after_persistent_turn`：同步、清理或归档 active plan。
   - `_sync_active_plan_summary`：把 active plan 镜像到 `root_context_summary`。
   - `_archive_active_plan`：写入 `root_context_summary.plan_history`。

## Complexity Detection

root skill prompt 应明确要求模型在以下场景先建计划：

1. 用户输入中有多个目标或多个问题。
2. 任务需要跨多个 skill/function。
3. 后一步依赖前一步的业务结果。
4. 任务包含有副作用操作、审批、用户授权或业务风险。
5. 用户要求“先查再处理”“分析并创建”“对比后执行”等复合动作。

runtime 第一阶段不必做过重的确定性分类，只需在 prompt 和 mock E2E 中固化行为。后续可以补轻量 deterministic gate：当模型试图在复杂请求下直接调用第一个业务 skill/function 而没有 `active_plan` 时，root loop 可以要求模型先创建计划。

## Observer Checkpoints

建议设计以下 checkpoint：

1. `PRE_EXECUTION_PLAN`：复杂任务开始前，root 创建 `active_plan`。
2. `BEFORE_FINAL_RESPONSE`：root 或 child skill 准备提交最终答复前，若 `active_plan.review.required=true`，进入 observer frame 检查。
3. `AFTER_RECOVERABLE_RESUME`：用户输入“继续”恢复复杂任务后，可选进入 observer frame 确认当前 step 和 recoverable focus 是否一致。
4. `AFTER_FAILURE_OR_REJECTION`：业务函数失败、审批拒绝、用户取消后，observer 可辅助判断 plan 应进入 `BLOCKED`、`NEEDS_CLARIFICATION`、`SHELVED` 还是 `FAILED`。

第一阶段建议只设计并测试 `PRE_EXECUTION_PLAN` 和 `BEFORE_FINAL_RESPONSE`，避免一次性引入过多状态分支。

## PLAN_OBSERVER Frame

`PLAN_OBSERVER` 可以被建模为内部 frame，但它与普通 skill/agent frame 不同：

1. `frame_kind=PLAN_REVIEW` 或后续扩展 `FrameKind.PLAN_OBSERVER`。
2. read-only：不暴露 `invoke_business_skill`、`invoke_business_function`、`submit_skill_result` 等执行型工具。
3. 输入只包含：
   - `active_plan`
   - step status
   - root/child promoted result summary
   - tool audit summary
   - approval / failure / cancellation summary
   - evidence refs
4. 不默认穿透完整 child private context。
5. 输出固定结构：

```json
{
  "review_status": "ALIGNED | NEEDS_CORRECTION | NEEDS_CLARIFICATION | UNSAFE_TO_FINALIZE",
  "missing_steps": ["step_2"],
  "drifted_steps": [],
  "blocked_steps": [],
  "correction_instruction": "继续执行 step_2，然后再汇总",
  "user_message_policy": "finalize | ask_clarification | explain_blocker | continue_loop"
}
```

如果 observer 返回 `ALIGNED`，root skill 可以提交最终答复。如果返回 `NEEDS_CORRECTION`，root skill 不应立即结束 frame，而是将 correction instruction 写回 root prompt context，让 LLM 继续执行缺失步骤。

## Correction Flow

推荐状态流：

1. root skill 判断请求复杂，创建 `active_plan`。
2. root skill 按计划串行或嵌套调用业务 skill/function。
3. 每个 child result 回流后，root 更新对应 step 状态和 result refs。
4. root 准备最终回复时，runtime 创建 `PLAN_OBSERVER` frame。
5. observer 检查计划与执行记录：
   - `ALIGNED`：允许最终回复。
   - `NEEDS_CORRECTION`：root 继续执行缺失或偏离步骤。
   - `NEEDS_CLARIFICATION`：root 向用户澄清，保留 `active_plan` 和 `recoverable_focus_*`。
   - `UNSAFE_TO_FINALIZE`：root 解释阻塞原因，必要时进入 approval/user-confirmation。
6. 为避免循环，`active_plan.review.correction_count` 应设置上限，例如 2 次。

## Relationship With Recoverable Focus

`active_plan` 和 `recoverable_focus_*` 不是同一层语义：

1. `active_plan` 描述复杂任务的目标、步骤和完成度。
2. `recoverable_focus_*` 描述当前最值得恢复的中断 frame。
3. 当复杂任务中断时，root working state 应同时保留：
   - `active_plan`
   - 当前 running/blocked step
   - `recoverable_focus_frame_id`
   - `recoverable_focus_stack`
4. 用户输入“继续”时，root 先按 intent resolution 判断是否恢复旧任务，再根据 `active_plan` 找到应继续的 step。
5. 用户输入无关任务或明确中止时，应同时 shelve `active_plan` 和 `recoverable_focus_stack`，避免后续误恢复。

## Difference From Plan Agent

本文中的 observer 不是 plan agent。

| 能力 | root-owned active_plan | PLAN_OBSERVER | future plan agent |
| --- | --- | --- | --- |
| 是否管理任务生命周期 | 是，由 root 管理 | 否 | 可能是 |
| 是否能调用业务 skill/function | 通过 root 执行 | 否 | 未来需严格受控 |
| 是否可并发运行 | 否 | 一般否 | 未来可能 |
| 是否拥有独立会话 | 否 | 否，内部 review frame | 未来可能 detached |
| 主要用途 | 计划状态 | 一致性检查 | 复杂规划/监督/分派 |

只有当复杂任务规划本身变得很重，例如需要跨多个长期子任务、并发观察、多轮重排、或跨会话调度时，才考虑把 observer 升级成真正的 plan agent / delegation run。

## Test Plan

后续进入实现前，至少补以下 mock LLM E2E：

1. `complex_multi_intent_creates_active_plan`
   - 用户一句话包含多个目标。
   - root 先创建 `active_plan`，再调用业务 skill。
2. `complex_plan_executes_serial_skills`
   - step 1 调用查询 skill。
   - step 2 依赖 step 1 result ref 调用诊断/执行 skill。
   - root 最终汇总所有 step。
3. `plan_observer_allows_aligned_final_response`
   - 所有 step 已完成。
   - observer 返回 `ALIGNED`。
   - root 才提交最终回复。
4. `plan_observer_catches_missing_step_and_corrects`
   - root 准备提前结束。
   - observer 返回 `NEEDS_CORRECTION` 和 missing step。
   - root 继续调用缺失 skill/function。
5. `complex_plan_resume_after_interruption`
   - child skill 或 function frame 中断。
   - 用户输入“继续”后，root 同时恢复 `active_plan` 当前 step 和 `recoverable_focus_stack`。
6. `complex_plan_shelved_for_unrelated_task`
   - 用户输入无关新任务。
   - root shelve `active_plan` 和整条 focus stack。
7. `plan_observer_is_read_only`
   - mock LLM 在 observer frame 中尝试调用业务函数时，runtime 拒绝或不暴露该工具。
8. `plan_review_correction_loop_limited`
   - observer 连续返回 correction 时，超过上限后 root 进入澄清或失败解释，避免无限循环。

## Acceptance Criteria

设计进入实现前需要满足：

1. 明确 `active_plan` 的最小 schema 和存储位置。
2. 明确 root prompt 中何时要求模型先建计划。
3. 明确 observer frame 的只读工具边界。
4. 明确 observer 输出 contract。
5. 明确 `active_plan` 与 `recoverable_focus_*` 的交互规则。
6. 先补 mock LLM E2E，再接真实 LLM smoke。
7. 不引入独立 plan agent、不改变现有 business function 授权/审批链路。

## MVP Validation

2026-05-17 active_plan MVP 已补并通过以下回归：

```bash
cd tools/langgraph-biz-worker
.\.venv\Scripts\python.exe -m pytest tests\test_llm_skill_agent.py::test_llm_agent_persistent_frame_submit_persists_active_plan tests\test_llm_skill_agent.py::test_llm_agent_persistent_frame_prompt_includes_active_plan tests\test_llm_skill_agent.py::test_llm_agent_persistent_root_exposes_shelve_interrupted_frame_tool tests\test_frame_lifecycle.py::TestPersistentTurnResult::test_persistent_turn_result_persists_active_plan tests\test_frame_lifecycle.py::TestPersistentTurnResult::test_persistent_turn_result_archives_terminal_active_plan -q
```

结果：`5 passed`。

```bash
cd tools/langgraph-biz-worker
.\.venv\Scripts\python.exe -m pytest tests\test_e2e_scripted_tool_call_streaming.py::test_scripted_root_skill_active_plan_survives_across_tasks -q
```

结果：`1 passed`。该用例使用 mock LLM 验证第一轮写入 `active_plan` 后，第二轮复用 `system.root` frame 且 prompt 注入 `Active task plan`。

```bash
cd tools/langgraph-biz-worker
.\.venv\Scripts\python.exe -m pytest tests\test_llm_skill_agent.py tests\test_frame_lifecycle.py tests\test_frame_interruption.py tests\test_e2e_scripted_tool_call_streaming.py -q
```

结果：`67 passed, 3 warnings`。warnings 来自现有 pydantic/websockets deprecation，不影响本次行为。

## Open Questions

1. `active_plan` 第一阶段是否只存 root working state，还是同步写入 Java 侧 task/context metadata。
2. 复杂任务识别是完全交给 root LLM，还是增加 deterministic pre-check。
3. observer 是否使用同一个模型配置，还是允许单独配置更小/更稳的 reviewer model。
4. 哪些业务函数或 skill 必须强制 review，哪些只做 best-effort review。
5. observer 发现偏离后，是否允许直接生成 correction instruction，还是必须由 runtime 转换成固定提示词。
6. UI 是否需要展示 active plan 进度，还是第一阶段只保存在 debug/journal 中。

## Non-goals

1. 本轮只实现 `active_plan` MVP，不实现 deterministic complexity gate。
2. 本轮不实现 `PLAN_OBSERVER` frame。
3. 本轮不实现 plan agent。
4. 本轮不实现并发 agent delegation。
5. 不允许 observer 或未来 plan agent 绕过 user grant、function allowlist、approvalRequired、business audit。
6. 不把 child private context 默认穿透给 observer。
