---
type: bug
bug_source: user-report
version: 1.3.0-SNAPSHOT
ticket: BUG-034
severity: major
status: ready-for-verification
reproduction_status: confirmed-from-local-logs
test_strategy: e2e-test
automation_decision: required
owner: biz-worker-runtime
related: BUG-033
---

# BUG-034: BizWorker Child Result Degraded By Parent Synthesis

## 文档作用

- doc_type: bug
- intended_for: execution-agent / reviewer / test-owner
- purpose: 记录 child frame 已产出可直接面向用户的等待输入或最终结果后，parent/root LLM 再加工导致用户可见结果退化的问题，并沉淀后续 direct result envelope 设计与测试要求。

## Background

2026-05-20 TMS 业务助手真实会话中，用户发送：

```text
你可以帮我提交工单吗
```

这次不是中断恢复场景。TMS child skill 已经进入工单信息收集流程，并通过 `submit_skill_result` 产出了合理的等待用户输入结果：

```text
已收到您的工单提交请求。为了帮您准确创建工单，请补充以下信息：
1. 工单类型
2. 工单标题
3. 问题说明
4. 运单号
```

但 root/parent 层最终展示给用户的是：

```text
已完成操作。
```

这个结果丢失了 child frame 的关键业务语义，用户无法知道下一步应补充哪些字段。

## Evidence

本机日志与 frame JSON 已确认：

- conversation/context: `20260520-7c33`
- root task: `lgt_50627c9587c74897`
- child frame: `frm_903273e7c55e`
- child skill: `tms-ticket-agent`
- child `submit_skill_result.summary` 包含完整等待用户补字段文案。
- child `structured_output.next_step=WAITING_FOR_USER_INPUT`，`status=PENDING_INFO`，`required_fields` 包含工单类型、标题、问题说明、运单号等字段。
- root visible result / report summary 退化为 `已完成操作。`

同时，代码中曾存在 Python 侧 `_guard_final_summary()` 保护逻辑，会把部分包含业务标识词的 summary 静默替换为安全摘要。该逻辑不应存在于 Worker runtime 的最终结果层：它既无法可靠理解业务语义，也会掩盖真实 LLM/tool 输出问题。当前已移除该层，`submit_skill_result.summary` 改为直接记录和提交。

### Smoke Evidence 2026-05-20: `20260520-566e`

用户清理 `tools/langgraph-biz-worker/data` 后，使用 TMS UI 重新执行了一轮真实链路 smoke。该轮覆盖等待用户澄清、用户带附件回复、root LLM 出错后继续、以及完成后追问附件情况。

关键对象：

- conversation/context: `20260520-566e`
- session: `c82dfb5c-0a11-43a5-9500-39046ed9213a`
- root frame: `frm_c171a7768c96`
- first ticket child: `frm_5a29f1a1daa3`
- duplicate ticket child: `frm_b9fc9af50fab`
- follow-up query child: `frm_48490a60d56b`

确认符合预期的部分：

1. 首轮 `帮我生成一个工单` 后，child frame 进入 `AWAITING_USER`，root 保持 `WAITING_CHILD`。
2. 用户第二轮带图片回复后，runtime 先恢复同一个 `frm_5a29f1a1daa3`，没有先进入 root LLM 决策。
3. `frm_5a29f1a1daa3` 能拿到附件上下文，并调用 `tms.ticket.createPlatformFeedback` 创建工单 `TKT20260520115128081580553`。
4. root LLM 后续异常后，用户点击 `继续` 能恢复 root 的 recoverable turn，没有再次调用业务创建函数。
5. 完成后的新问题 `工单中有我刚才提交的图片吗` 会进入 root，并新开 follow-up child 查询工单详情；这符合 completed frame 不原地 reopen 的设计。

本轮暴露的新问题：

1. `frm_5a29f1a1daa3` 已完成并产出用户可见结果后，root 仍继续运行 LLM。
2. root 随后调用了 `analyze_attachment`，又第二次 `invoke_business_skill` 打开 `frm_b9fc9af50fab`。
3. `frm_b9fc9af50fab` 创建了第二张工单 `TKT20260520115656625A7A498`，导致重复副作用。
4. 第二张工单路径中的附件信息退化为本地 path 形态，最终业务结果不包含图片；这大概率是重复编排和附件上下文降级共同造成的后果。

结论：`WAITING_FOR_USER_INPUT -> AWAITING_USER -> same-frame resume` 主链路已经成立，但 completed child 返回后缺少 runtime direct-return 判断。只靠 prompt 约束 root 不二次加工不够，必须在 runtime 层对 `FINAL_FOR_USER` 或等待用户恢复后的 legacy completed child 结果做短路。

## Expected Vs Actual

Expected:

1. child frame 如果明确产出 `WAITING_FOR_USER_INPUT`，且结果本身已经是面向用户的下一步提示，runtime 应把该 user-facing result 作为本轮可见结果返回，但 child frame 保持 `AWAITING_USER`，不标记为 `COMPLETED`。
2. child frame 如果明确产出 `FINAL_FOR_USER`，且没有 parent 后续工作，runtime 也应直接返回该结果。
3. child frame 如果只是复杂任务的阶段性结果、还需要 parent 统筹、合并、追问或继续调其他 skill，则 parent LLM 应继续处理 promoted result。
4. Python runtime 不应通过启发式 guard 静默改写 child 或 parent 的最终 summary。

Actual:

1. child frame 已产生可直接展示的等待用户结果。
2. root/parent LLM 仍继续执行一次 synthesis，并把结果改写为过度泛化的 `已完成操作。`
3. 用户可见消息丢失 next step 和 missing fields。
4. 如果只依赖 prompt 约束，无法稳定避免 parent LLM 二次加工退化。

## Problem Statement

当前 `invoke_business_skill` 的返回结果默认作为 parent LLM 的 tool result 继续进入下一轮模型调用。这个模型适合复杂任务编排，但不适合所有 child 结果。

当 child result 已经表达了明确的用户等待态或最终用户结果时，parent LLM 再加工不是必要步骤，反而可能引入：

- 信息压缩过度；
- 业务字段丢失；
- 误判“操作已完成”；
- 重复调用 report 或重复委派 child skill；
- 与中断恢复后的 direct focus resume 语义不一致。

因此需要把 child promoted result 从“无类型文本/结构化输出”提升为 typed result envelope，让 runtime 能判断本轮是否需要 parent synthesis。

## Design Direction

### Implementation Slice Status

2026-05-20 已完成并验证 `WAITING_FOR_USER_INPUT` 路径：

- child skill 通过 `submit_skill_result` 声明等待用户输入时，child frame 进入 `AWAITING_USER`，root 保持 `WAITING_CHILD`。
- root 记录统一的 `active_focus_*`，下一轮同 `contextId` 用户消息在 root LLM 运行前恢复同一个 child frame。
- 恢复后的 child LLM prompt 包含上一轮等待用户消息、结构化 awaiting-user context 和当前用户回复。
- 该路径不重复调用 `invoke_business_skill`，也不调用 `read_frame_execution_report`。

本轮继续完成的后续项：

- `FINAL_FOR_USER + requires_parent_synthesis=false` 的 completed child 直返分支。
- 等待用户恢复后的 legacy completed child 过渡直返分支，用于兼容当前 TMS skill 尚未全部升级到 `FINAL_FOR_USER` envelope 的情况。

仍保留为后续增强项：

- `PARTIAL_RESULT/NEEDS_PARENT_DECISION` 等完整 typed envelope 决策矩阵和更细粒度测试。

### Typed Child Result Envelope

child skill promoted result 应支持以下字段，字段可以位于 `structured_output` 或 promoted result 的同构层：

```json
{
  "turn_status": "WAITING_FOR_USER_INPUT",
  "user_message": "请补充工单类型、标题、问题说明和运单号。",
  "requires_parent_synthesis": false,
  "remaining_work": [],
  "next_step": "WAITING_FOR_USER_INPUT",
  "missing_fields": ["ticket_type", "title", "summary", "orderIdentifier"],
  "status": "PENDING_INFO"
}
```

`turn_status` 建议枚举：

- `WAITING_FOR_USER_INPUT`
- `FINAL_FOR_USER`
- `PARTIAL_RESULT`
- `STEP_COMPLETED`
- `NEEDS_PARENT_DECISION`
- `ERROR`
- `RECOVERABLE_INTERRUPTION`

### Runtime Decision Rule

`invoke_business_skill` 完成 child frame 后，runtime 应先读取 promoted result envelope，再决定是否继续 parent LLM loop。

Awaiting-user pause:

1. `turn_status=WAITING_FOR_USER_INPUT` 或兼容字段 `next_step/status` 表达等待用户输入。
2. 存在 `user_message` 或可用的 `summary/result_summary`。
3. child frame 仍处于可继续执行的业务流程。

满足上述条件时：

- child frame `RUNNING -> AWAITING_USER`；
- root 保持 `WAITING_CHILD`；
- root working state 写入 `active_focus_*`；
- 本轮向用户发布 user-facing waiting message；
- 下一轮同 `contextId` 用户消息先恢复该 child frame，再进入 child LLM/tool loop。

Direct completed result:

1. `requires_parent_synthesis=false`
2. `remaining_work` 为空或缺省为空
3. `turn_status=FINAL_FOR_USER`
4. 存在 `user_message` 或可用的 `summary/result_summary`

满足上述条件时：

- persistent root 直接提交本轮 turn result；
- 不再调用 parent LLM 生成二次 summary；
- 不调用 `read_frame_execution_report`；
- 保留 frame report ref 作为审计入口。

Parent synthesis:

1. `requires_parent_synthesis=true`
2. `remaining_work` 非空
3. `turn_status in [PARTIAL_RESULT, STEP_COMPLETED, NEEDS_PARENT_DECISION]`
4. 用户问题本身需要跨多个 skill 合并、比较、决策或解释

满足上述条件时，维持当前 parent LLM loop：promoted result 作为 tool result 进入 parent，上层继续规划和输出。

Compatibility fallback:

1. 旧 child result 未携带 `requires_parent_synthesis` 或 `turn_status` 时，默认维持当前 parent synthesis 行为。
2. 对已知的 `next_step=WAITING_FOR_USER_INPUT` / `status=PENDING_INFO` 可作为兼容识别，但应在测试中明确这是过渡兼容，不替代 envelope 合同。

### Relation To Mainstream Agent Design

主流 agent 系统通常不会把完整 frame/tool report 直接展示给用户。用户看到的是最终 assistant message，frame result / tool result 是 agent 内部上下文和调试证据。

但这不代表所有结果都必须经过 parent LLM 二次改写。更常见的工程做法是引入 typed tool result 或 `return_direct` 类机制：

- 简单终态或等待用户态可以 direct return；
- 复杂任务阶段结果继续交给上层 agent；
- 是否 direct 不靠自然语言猜测，而靠结构化 envelope 或工具声明。

本项不采用全局 `return_direct=true`，因为 TMS 可能存在复杂问题，需要 parent 继续调度后续工作。采用 typed envelope 可以同时覆盖“等待用户直接返回”和“复杂任务继续编排”两类场景。

### Completed Frame Follow-up Contract

`WAITING_FOR_USER_INPUT` 与正常 `COMPLETED` follow-up 必须分开处理。

设计口径：

1. `WAITING_FOR_USER_INPUT`：child frame 未完成。runtime 保留同一个 child frame，下一轮同 `contextId` 用户消息直接注入该 frame。
2. `RECOVERABLE_INTERRUPTED`：frame 异常中断。runtime 恢复同一个 recoverable focus frame。
3. `COMPLETED` follow-up：旧 frame 已完成，不原地 reopen。用户下一条消息进入 root，由 root 基于 visible conversation、promoted result 和 root context summary 判断是否是上一业务对象的 follow-up。

当用户要求“改下刚才那个工单内容”时，目标不是复活旧 frame，而是：

```text
frame A: tms-ticket-agent 创建工单 -> COMPLETED
frame B: tms-ticket-agent 修改工单 -> RUNNING/COMPLETED
```

frame B 必须显式携带 frame A 的上下文：

```json
{
  "task_type": "FOLLOW_UP",
  "follow_up_of_frame_id": "frm_A",
  "follow_up_of_skill_id": "tms-ticket-agent",
  "business_object": {
    "type": "ticket",
    "id": "TK20260520001"
  },
  "previous_result_summary": "工单已创建，编号为 TK20260520001。",
  "user_request": "把刚才那个工单内容改成：客户非常着急"
}
```

这样体验上仍然是继续同一个子 agent / 业务会话，执行审计上则是新的 frame，避免破坏 completed frame 的不可逆生命周期和副作用边界。

### Active Focus Scope

BUG-034 主线只处理串行 child skill 调用。串行路径下，主 agent 一次只等待一个 child，最新 active focus 是唯一恢复依据：

```text
AWAITING_USER
AWAITING_APPROVAL
RECOVERABLE_INTERRUPTED
```

`active_focus_*` 应作为统一恢复入口，`recoverable_focus_*` 只作为历史字段兼容或迁移来源。不要让正常等待用户和异常中断分别长期维护两套 competing focus。

多 child 并行属于后续异步子 agent 设计，不纳入本 BUG 的实现范围。该场景下：

1. child LLM 错误由主会话 agent 自己恢复、重试、忽略或询问用户是否重试。
2. child 需要 user input 时，优先由主 LLM 根据已有上下文自动回复 child。
3. 如果信息不足，应由主 LLM 统一询问用户，再把用户回复反馈给对应 child。
4. 代码可以复用 frame/journal/envelope/message replay，但用户输入路由语义不等同于串行 active focus。

当 `AWAITING_USER` 恢复后，如果用户回复已经进入一轮 child LLM/tool loop 后发生 timeout/cancel/error，应转入中断/失败恢复语义，而不是继续保留原等待用户状态。

## Code Inventory

- `tools/langgraph-biz-worker/src/langgraph_biz_worker/models.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_agent_prompts.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_child_recovery.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_agent.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/skill_runtime.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_business_function_adapter.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_tool_schemas.py`
- `tools/langgraph-biz-worker/tests/test_llm_skill_agent.py`
- `tools/langgraph-biz-worker/tests/test_e2e_scripted_tool_call_streaming.py`

## Fix Checklist

- [x] 移除 Python 侧最终 summary heuristic guard，不再静默把提交结果改写为 fallback 文案。
- [x] 为 summary pass-through 增加 targeted unit tests，确认 runtime 不再把业务 summary 改成 `已完成操作。`
- [x] 新增 `FrameStatus.AWAITING_USER`，把 `WAITING_FOR_USER_INPUT` 从 completed child 路径拆出。
- [x] child 声明等待用户输入时，保留同一个 child frame，root 写入 `active_focus_*` 并保持 `WAITING_CHILD`。
- [x] 下一轮同 `contextId` 用户消息先恢复 active focus child，再运行 child LLM。
- [x] 恢复 prompt 注入上一轮等待用户消息、结构化 awaiting-user context 和当前用户回复。
- [x] 调整工具描述，要求 child skill 使用 `turn_status/next_step=WAITING_FOR_USER_INPUT` 声明等待用户输入。
- [x] 补充 scripted LLM/tool response E2E，断言等待用户后继续同一个 child frame。
- [x] 为 completed child promoted result 增加 `FINAL_FOR_USER` direct result envelope 识别逻辑。
- [x] 在 `invoke_business_skill` 完成 child frame 后，若 envelope 表示 `FINAL_FOR_USER` 且不需要 parent synthesis，直接提交 persistent root turn result。
- [x] 在 active focus child 从 `AWAITING_USER` 恢复后完成时，对 legacy `COMPLETED/SUBMITTED` 结果执行过渡直返，避免 root 二次编排。
- [x] 保持复杂任务兼容：显式 `requires_parent_synthesis=true` 或 `remaining_work` 非空时不 direct；缺省旧格式在非 active-focus 路径仍进入 parent LLM。
- [ ] 用 TMS 真实链路 smoke 复测用户可见结果。

## Regression Coverage Plan

必须补自动化测试。建议覆盖：

1. `WAITING_FOR_USER_INPUT + requires_parent_synthesis=false`：child 返回补字段文案，child frame 进入 `AWAITING_USER`，root 不再发起第二次 parent LLM 调用，用户可见结果等于 child user message。
2. `FINAL_FOR_USER + requires_parent_synthesis=false`：child 返回最终完成文案，root 直接提交 turn result。
3. `PARTIAL_RESULT + requires_parent_synthesis=true`：root 继续 LLM loop，并可调其他工具或生成综合结果。
4. legacy result 未携带 envelope：保持现有 parent synthesis 行为，避免破坏旧 skill。
5. `next_step=WAITING_FOR_USER_INPUT/status=PENDING_INFO` 兼容路径：在旧 skill 未升级前仍可避免明显错误的 `已完成操作。`
6. awaiting-user 恢复路径不调用 `read_frame_execution_report`。
7. awaiting-user 恢复路径不重复调用 `invoke_business_skill`。
8. summary pass-through：`submit_skill_result.summary` 不被 Python runtime 的 heuristic guard 改写。

## Acceptance Criteria

1. TMS 工单技能要求用户补字段时，用户可见消息必须包含需要补充的字段，而不是 `已完成操作。`
2. child frame 的等待用户态不经过 parent LLM 二次加工，且 frame 保持 `AWAITING_USER` 可继续执行。
3. 复杂任务仍保留 parent synthesis 能力，不把所有 child result 都 direct return。
4. runtime 决策依赖 typed envelope，而不是靠 prompt 约束或自然语言关键词猜测。
5. `read_frame_execution_report` 仍只作为解释、审计、debug 或结果缺失降级工具，不进入普通 direct result 路径。
6. 自动化测试能区分 direct result 和 parent synthesis 两条路径。

## Progress Tracking

### Development Progress

- [x] 已根据本机真实日志确认 child result 与 root final result 不一致。
- [x] 已确认 Python final summary guard 是不合理的 runtime 层业务启发式，已移除。
- [x] 已明确 frame result 默认是内部上下文和调试证据，不应把完整 frame report 直接展示给用户。
- [x] 已明确 child result 需要 typed envelope 决定是否 direct return。
- [x] 已实现 `WAITING_FOR_USER_INPUT` -> `AWAITING_USER` active focus。
- [x] 已实现下一轮同 `contextId` 用户消息恢复同一 child frame，并注入当前用户回复。
- [x] 已补充 `invoke_business_skill` / `submit_skill_result` 工具描述中的等待用户语义。
- [x] 已实现 `FINAL_FOR_USER` completed child direct result envelope runtime 分支。
- [x] 已实现 active focus child 完成后的 legacy completed/submitted 直返兼容分支。

### Testing Progress

- [x] 已通过本机 frame JSON 和 LLM/tool log 复盘确认现象。
- [x] `test_llm_skill_agent.py` 已覆盖 summary pass-through，不再由 Python guard 改写。
- [x] 已运行 targeted awaiting-user tests：`4 passed, 3 warnings`。
- [x] 已运行相关回归集合：`93 passed, 3 warnings`。
- [x] 已补 scripted E2E，覆盖 `WAITING_FOR_USER_INPUT` 后继续同一 child frame。
- [x] 已补 `FINAL_FOR_USER` direct result envelope unit test。
- [x] 已补 scripted E2E 复现 `AWAITING_USER -> COMPLETED` 后 root 二次加工为 `已完成操作。`，修复后断言不再进入 root 第四次 LLM 调用。
- [x] 已运行 focused regression：`6 passed, 3 warnings`。
- [x] 已运行 Worker 全量测试：`536 passed, 6 skipped, 10 warnings`。
- [ ] TMS 真实链路 smoke 待重启后执行。

### Experience Progress

- [x] 已从 TMS UI 截图确认用户可见结果退化为 `已完成操作。`
- [ ] 真实链路复测需确认用户看到的是 child 的补字段提示。
- [ ] 如 UI 仍显示 root frame `RUNNING` 但 turn result 已产生，需要另行记录 turn-level status 展示问题。

### Execution Check-in

- completed_work_summary: 已移除 Python runtime 的最终 summary heuristic guard；已实现 `WAITING_FOR_USER_INPUT` 的 `AWAITING_USER` active focus 路径和同 frame 恢复；已实现 completed child 的 `FINAL_FOR_USER` direct result 分支，并补充 active focus legacy completed/submitted 直返兼容。
- touched_code_paths:
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_business_function_adapter.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_agent.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/skill_runtime.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_child_recovery.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_agent_prompts.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_tool_schemas.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/models.py`
  - `tools/langgraph-biz-worker/tests/test_llm_skill_agent.py`
  - `tools/langgraph-biz-worker/tests/test_e2e_scripted_tool_call_streaming.py`
- self_check:
  - [x] 问题归属为 `1.3.0-SNAPSHOT` workitem。
  - [x] development / testing / experience progress 已记录。
  - [x] 已区分 BUG-033 的中断恢复问题和本 BUG 的 child result 二次加工退化问题。
  - [x] 已补齐 waiting-user active focus 的自动化回归测试。
  - [x] 已记录非目标：不展示完整 frame report，不做全局 return direct。
- test_execution_status: pass；focused regression 与 Worker 全量测试均已通过。
- acceptance_readiness: ready-for-verification；需要重启 Python Worker 后执行 TMS 真实链路 smoke，确认不再重复创建第二张工单且附件上下文不再降级。

## Constraints / Non-goals

- 不把完整 frame execution report 作为普通用户消息展示。
- 不把所有 child result 都设为 direct return。
- 不依赖 parent LLM prompt 自觉避免二次加工。
- 不恢复 Python 侧 summary heuristic guard。
- 不在本项中新增 Worker 侧副作用幂等账本。
- 不要求 Java/session 层理解 child envelope；该决策属于 Python Worker runtime。

## References

- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/BUG-033-biz-worker-continue-context-injection-gap.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/BUG-034-waiting-user-input-context-walkthrough.md`
- `tools/langgraph-biz-worker/data/frames/by-conversation/20260520-7c33/frm_903273e7c55e.json`
