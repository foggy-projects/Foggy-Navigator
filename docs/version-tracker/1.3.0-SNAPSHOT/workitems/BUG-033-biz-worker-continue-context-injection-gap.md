---
type: bug
bug_source: user-report
version: 1.3.0-SNAPSHOT
ticket: BUG-033
severity: major
status: implemented-pending-smoke
reproduction_status: confirmed-from-local-logs
test_strategy: regression-required
automation_decision: required
owner: biz-worker-runtime + session-module
related: OPT-029
---

# BUG-033: BizWorker Continue After Interruption Context Injection Gap

## 文档作用

- doc_type: bug
- intended_for: execution-agent / reviewer / test-owner
- purpose: 记录 TMS 业务助手在 LLM timeout 后输入“继续”时，Root LLM 未获得足够执行历史上下文，导致恢复行为偏离预期的问题，作为后续设计和测试规划入口。

## Background

2026-05-19 TMS 业务助手真实会话中，用户发送：

```text
你可以帮我提交工单吗
```

原任务在 `system.root` 上因 LLM request timeout 中断。用户随后在同一会话输入：

```text
继续
```

系统确实复用了同一个 persistent root frame，并把 recoverable interruption 信息注入到了下一轮 Root LLM 调用；但 Root LLM 初始上下文没有包含上一轮 `private_messages` 中的关键执行事实，尤其是旧 child frame 已经返回 `WAITING_USER` 以及下一步需要用户补充工单字段。

## Evidence

- 原任务：`lgt_ffd00790e5a34bbb`
- Continue 任务：`lgt_731a772efc41471d`
- Session：`3f43a07d-f47b-4424-bb7e-eadf4172ee63`
- Context / conversation：`20260519-aad6`
- Root frame：`frm_22fa79248df5`
- 原任务终止状态：
  - `LLM_REQUEST_TIMEOUT`
  - `task_sub_status=INTERRUPTED`
  - `recoverable=true`
  - `interruption_reason=llm_retry_exhausted`
- Continue 任务复用了 root frame：
  - `origin_task_id=lgt_ffd00790e5a34bbb`
  - `current_task_id=lgt_731a772efc41471d`
- Continue 后 Root LLM 首个工具调用：

```json
{
  "skill_name": "tms-ticket-agent",
  "instruction": "提交工单"
}
```

这说明 Root LLM 判断为继续提交工单方向，但没有精确接续到旧 child report 的等待用户字段状态。

## Observed LLM Context On Continue

当前实现中，`LlmSkillAgent.run()` 每次 run 都重新构造：

```text
SystemMessage
HumanMessage
```

HumanMessage 中包含：

- `SKILL_AGENT_START system.root`
- 当前 runtime time context
- recoverable interruption 摘要
- persistent root planning policy
- visible recent conversation
- `User request: 继续`
- `Skill input` 中的 allowed skills 与 session/context 标识

其中 visible recent conversation 在 continue 创建时主要只有：

```text
user: 你可以帮我提交工单吗
```

## Missing Context

旧 root frame 的 `private_messages` 中实际存在更关键的事实：

- root 曾调用 `tms-ticket-agent`
- 旧 child frame 已完成执行报告读取
- child report 返回：

```json
{
  "status": "WAITING_USER",
  "next_step": "请回复工单类型、运单号（如适用）、标题及详细描述。"
}
```

- root 曾生成过面向用户的澄清请求，要求用户补充工单类型、运单号、标题和详细描述。

但这些内容没有作为下一轮 Root LLM 初始 message 注入。

## Expected Vs Actual

Expected:

1. 用户在 recoverable interruption 后输入“继续”时，Root LLM 应看到足够的上一轮执行摘要。
2. 如果上一轮已到 `WAITING_USER`，Root LLM 应优先恢复到等待用户补充信息的语义，而不是重新打开一个近似的新业务技能流程。
3. 恢复上下文应包含旧 child frame 的状态、next step、最后一次对用户可见的澄清请求、以及是否存在 pending recoverable child。
4. “继续”不应只依赖 recentConversation 和通用 interruption reason 来推断业务语义。

Actual:

1. Root frame 和 recoverable interruption 状态被正确复用。
2. Root LLM 初始上下文没有注入旧 `private_messages` 或 child execution report digest。
3. Root LLM 根据“用户之前想提交工单 + 当前说继续 + allowed skill 中有 tms-ticket-agent”重新调用 `tms-ticket-agent`。
4. 用户体验上表现为恢复了大方向，但没有精确恢复到上一轮等待补字段的业务状态。

## Problem Statement

OPT-029 已经解决“中断后同一 persistent root 可恢复”的主干链路，但当前恢复上下文粒度不足：

- recoverable interruption 记录的是错误、frame、task 和 root focus；
- visible recent conversation 只保留用户/assistant 可见消息，且 continue 创建时不包含当前“继续”之外的完整执行摘要；
- `private_messages`、旧 child report、active plan、最后澄清话术没有进入 Root LLM 的初始 prompt；
- 因此 LLM 只能粗粒度判断“继续原业务方向”，不能稳定判断“从哪个业务等待点继续”。

## Impact Scope

- 影响所有 persistent root 的 recoverable continuation，不限于 TMS 工单技能。
- 对 `WAITING_USER`、审批拒绝后继续、child timeout 后继续、tool/report 已产生但 root LLM timeout 的场景风险较高。
- 可能导致重复调用 child skill、重复读取报告、重复询问用户，或绕过上一轮已经形成的 next step。
- 本阶段不在 Worker 侧新增副作用幂等 hard guard；假设关键业务系统已自行保证幂等、重复操作会返回 repeat/duplicate 结果或明确错误，普通可重复操作允许重复。

## Target Outcome

后续设计需要明确 Root LLM 在下一轮 continue 时应获得的最小恢复事实集，使其能稳定区分：

- `CONTINUE_PREVIOUS`：继续上一业务等待点；
- `ASK_CLARIFICATION`：复述或收敛旧 child 的 `WAITING_USER next_step`；
- `RESUME_CHILD`：恢复 pending recoverable child frame；
- `ABANDON_PREVIOUS` / `START_UNRELATED_NEW_TASK`：用户明确放弃或切换任务。

2026-05-19 设计更新：上述“由 Root LLM 先区分用户意图，再决定是否恢复 child”的方向被收窄为过渡方案。新的目标是由 runtime 根据 recoverable focus deterministic 恢复中断时的 frame 上下文，把同一 `contextId` 下的下一条用户消息直接插入该 frame 继续执行。Root LLM 不再作为明确恢复路径的第一道意图分类器。

## Design Direction

### 2026-05-19 Revised Direction: Deterministic Recoverable Focus Resume

本项的主设计调整为：当同一 `contextId` 下存在 recoverable focus 时，下一条用户消息默认属于该中断 frame。runtime 应先恢复中断时的 focus frame，再把用户消息作为 resume prompt 注入恢复后的 frame context。

主路径：

```text
user message in same contextId
  -> runtime resolves recoverable root / focus
  -> reopen or resume recoverable_focus_frame_id
  -> append current user prompt to the recovered focus frame loop
  -> focus frame continues with original frame context
  -> completed child/focus result is promoted to parent/root
  -> root produces user-visible final result from promoted result
```

设计语义：

1. `contextId` 是恢复身份。只要同一 `contextId` 存在 recoverable focus，下一条消息优先进入该 focus frame。
2. runtime 不需要先询问 Root LLM “用户是不是想继续”；“继续”“补充字段”“修改参数”“其实改成平台反馈”等文本都由恢复后的业务 frame 自己理解。
3. `resume_recoverable_child_skill` 不再作为常规主路径依赖 LLM 自主调用；它可以保留为内部 runtime 能力、兼容 fallback 或显式调试工具。
4. 如果用户真实意图是放弃当前中断任务或切换新任务，本阶段不通过 LLM 在本轮自动判断；后续通过 UI/产品能力提供“回退到某条消息重新开始”“新建会话”“显式放弃当前中断任务”等入口处理。
5. 该方向与主流 agent/checkpoint 设计一致：编排层恢复 checkpoint/focus，LLM 在恢复后的 loop 中接收新的用户消息，而不是先让另一个 LLM loop 决定是否恢复。
6. 该方向也符合 LLM loop 可随时插入新 user message 的模型：中断恢复后，当前用户消息只是恢复后 frame 消息流里的下一条输入。

与旧方案相比：

1. 删除 Root LLM 的恢复意图分类作为默认前置步骤。
2. 避免 `root -> resume_recoverable_child_skill -> child` 这层额外工具决策。
3. 避免 Root LLM 为了理解 child 结果而读取 `read_frame_execution_report`。
4. 恢复后的业务语义由原 focus frame 负责，降低重复创建 child skill、重复读 report、误判新任务的概率。

### Primary Contract: `invoke_business_skill`

`invoke_business_skill` 是父子 skill 调用的主合同，必须在工具描述和返回值约定里明确：

1. child skill 的 promoted structured result 是父 agent 继续业务决策的主要输入。
2. promoted result 必须包含父 agent 不读执行报告也能继续推进的最小业务语义，例如：
   - `status`
   - `intent_resolution`
   - `next_step`
   - `missing_fields`
   - `structured_output`
   - 必要的 `active_plan` / `awaiting_user_input` 摘要
3. `execution_report_ref` 是审计和排障引用，不是父 agent 理解正常 child 结果的默认路径。
4. 父 agent 在普通业务流程中不应为了理解 child 结果而默认调用 `read_frame_execution_report`。
5. 如果 promoted result 缺少继续决策所需字段，才允许把读取 execution report 作为降级补救，并应记录为 child result contract gap。

### Secondary Contract: `read_frame_execution_report`

`read_frame_execution_report` 应在工具描述中收窄用途：

1. 用户要求解释、审计、查看执行过程或排障时使用。
2. debug/detail 模式需要复盘 prior frame work 时使用。
3. runtime 生成 continuation summary 或恢复摘要时可把 report 作为数据源。
4. 普通父子 skill 调用完成后，不应默认读取完整 report 来决定下一步。

### Continuation Context Parity

中断后恢复上下文必须与正常 `invoke_business_skill` 返回给父 agent 的上下文保持同构，而不是引入另一套只存在于 execution report 里的语义。

要求：

1. 正常调用时父 agent 看到的 child promoted result，必须被持久化为可恢复的 compact continuation summary。
2. timeout / interruption 后下一轮 continue prompt 注入的恢复摘要，应使用与 promoted result 一致的字段结构。
3. 对同一个 child 等待点，正常路径和恢复路径应给 Root LLM 等价信息；差别只在多了 interruption reason、last task 和 recoverability 元数据。
4. 如果上一轮已经到 `WAITING_USER`，continue prompt 应直接包含该等待点的 `status`、`next_step`、`missing_fields` 等字段，不要求 LLM 再调用 `read_frame_execution_report` 找回这些事实。
5. report digest 可以作为补充证据，但不应成为 continuation context 的唯一事实来源。

在 revised direction 下，continuation context parity 的重点从“Root LLM 看到等价摘要”进一步收敛为“恢复后的 focus frame 看到它原本的 frame context，并把当前用户消息作为下一条输入”。Root 只在 focus 完成或需要向用户输出 final result 时接收 promoted result。

### Java / Session Relay Contract

Java/session/BFF 层不应承担 Worker frame 恢复判定，也不要求复用同一个 `taskId`。本项合同明确为：

1. Java 可以在用户每一轮输入时创建新的 platform/worker `taskId`；`taskId` 表示单轮执行记录，不是恢复身份。
2. `contextId` 是跨轮会话与 recoverable root 选择的稳定身份；同一用户会话的“继续”必须透传同一个 `contextId`。
3. Java 只需把解析后的 `contextId` 放入 `CreateLanggraphTaskForm.contextId`，并进入 Worker request `context.contextId` / `context.context_id`；Worker 侧根据 `contextId/context_id/conversationId/session_id` 选择或重绑 recoverable root frame。
4. Java 不应检查 Worker frame 状态、选择 frame、或为了“继续”强制复用旧 `taskId`。
5. 如果上游未传或误换 `contextId`，Worker 会把请求视作新的 conversation，不能可靠恢复旧 frame；这是上游调用合同错误。

## Initial Code Inventory

- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_agent.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_agent_prompts.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/skill_runtime.py`
- `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphTaskService.java`
- `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphStreamRelay.java`
- `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/adapter/LanggraphWorkerInnerA2aAgent.java`
- `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/client/LanggraphWorkerClient.java`

## Candidate Design Topics

待后续单独规划，不在本文直接定方案：

1. 如何在 task 入口基于 `contextId` deterministic 选择 recoverable root / focus frame。
2. 如何定义 direct focus resume 与 approval resume、nested child resume、root-level interruption resume 的统一状态机。
3. 如何把当前用户消息安全插入恢复后的 focus frame loop，避免重复 system prompt 或污染 frame-local history。
4. focus 完成后如何清理 active recoverable 状态，同时保留 interruption history / audit history。
5. 是否仍保留 `resume_recoverable_child_skill`，以及它作为内部 API、debug fallback 或兼容工具时的暴露边界。
6. 如何实现“回退到某条消息重新开始 / 显式放弃当前中断任务”的产品和状态回滚能力。
7. 如何保证摘要不泄露附件签名 URL、API key、完整 prompt 或 provider 原始错误栈。
8. 如何调整 `invoke_business_skill` 和 `read_frame_execution_report` 的工具描述，约束 LLM 不把 report 读取当作正常父子调用的默认步骤。
9. 如何确保正常 promoted result 与中断恢复 continuation summary 的字段结构一致。

## Regression Coverage Plan

自动化是必需的，因为这是核心恢复语义问题。

待设计测试至少覆盖：

1. Root LLM timeout 发生在 child report 已经生成、root 尚未完成用户回复之后。
2. 下一轮用户输入“继续”时，runtime 不先进入 Root LLM 意图分类 loop，而是直接恢复 recoverable focus frame。
3. 下一轮用户输入补充字段或参数修正时，该消息作为恢复后 focus frame 的下一条 user message 注入。
4. 系统不应新建重复 child frame；应复用原 recoverable focus frame 或其原 child frame。
5. 真实 TMS 工单会话应验证用户可见文案：恢复后直接询问/处理缺失字段，而不是重新进入泛化提工单流程。
6. 正常 child skill 完成并返回完整 promoted result 时，Root LLM 不调用 `read_frame_execution_report`，直接根据 promoted result 决策。
7. 用户明确询问“刚才怎么处理的 / 查看执行报告 / 为什么这么做”时，Root LLM 允许调用 `read_frame_execution_report`。
8. promoted result 缺少必要业务字段但带有 `execution_report_ref` 时，允许降级读取 report，并将该场景标记为 contract gap。
9. 副作用幂等 hard guard 本阶段不纳入 Worker 回归范围；只记录外部业务系统幂等假设。
10. Java/session relay 允许第二轮生成新的 `taskId`，但必须断言同一个 `contextId` 被写入 task entity、provider context 和 Worker request context。
11. 用户如果确实要放弃当前中断任务，本阶段通过显式 abandon / rewind / new conversation 入口处理；普通同 `contextId` 下一条消息不作为新任务自动分流。

## Acceptance Criteria

1. 同 `contextId` 存在 recoverable focus 时，下一条用户消息不先走 Root LLM 恢复意图分类，而是直接恢复 focus frame。
2. 对本会话复现场景，用户输入“继续”后不再重新以 `instruction=提交工单` 新建业务流程，而是接续到原 `tms-ticket-agent` frame 的等待点。
3. 对“继续，类型是平台反馈 / 标题是 xxx / 问题是 xxx”等补充消息，恢复后的 focus frame 能把该消息作为业务输入继续处理。
4. 恢复摘要字段有脱敏规则，不泄露敏感附件 URL、API key、provider 原始错误栈或完整 prompt。
5. Python targeted regression、Worker scripted E2E、必要的 Java/session relay 测试和 TMS 真实链路 smoke 均有记录。
6. `invoke_business_skill` 的工具描述明确 promoted result 是父 agent 的主决策输入，`execution_report_ref` 只是审计引用。
7. `read_frame_execution_report` 的工具描述明确其非默认用途，只在解释、审计、debug、恢复摘要补救或结果缺失降级时使用。
8. 正常父子调用上下文与中断恢复上下文在业务字段上保持一致，测试能断言两条路径给 Root LLM 的 `status/next_step/missing_fields` 等关键字段等价。
9. 第二轮 continue 即使由 Java 创建新 `taskId`，只要 `contextId` 相同，Worker 仍能选择同一 conversation 下的 recoverable root frame。
10. 如果用户要放弃或切换任务，应通过显式 abandon / rewind / new conversation 能力处理；本项不要求 LLM 在 recoverable focus 存在时自动猜测新任务意图。

## Implementation Notes

2026-05-19 已完成 targeted implementation：

1. Worker 持久化 child promoted result 的 compact continuation summary，并在 recoverable continue prompt 中注入同构业务字段。
2. `invoke_business_skill` 与 `read_frame_execution_report` 工具描述已收窄：父 agent 默认使用 promoted result，只有解释、审计、debug 或结果缺字段时读取 report。
3. Java/session relay 合同明确为“新 `taskId` 可以，同 `contextId` 必须保持”，frame 选择仍由 Worker 负责。
4. Widget/BFF 已存在 `cancelTask` 链路，可用于真实链路 smoke 人工制造 `user_cancelled` 中断。
5. LangGraph A2A adapter 的 cancel/abort 已改为调用 `LanggraphTaskService.cancelTask`，确保真实 UI 取消会记录 recoverable interruption，而不是普通 `failTask`。

2026-05-19 后续设计修订：

1. 上述 implementation 仍属于“Root LLM 先判断 continue，再调用恢复工具”的过渡实现。
2. 最新决策要求把恢复主路径下沉到 runtime：同 `contextId` 存在 recoverable focus 时，下一条用户消息直接注入该 focus frame。
3. `resume_recoverable_child_skill` 后续应降级为内部恢复能力或 fallback，不再作为明确恢复路径上依赖 LLM 自主选择的工具。
4. 后续实现必须重新补充 scripted E2E：断言 continue 后没有 Root LLM 的 `resume_recoverable_child_skill` 工具调用，也没有重复 `invoke_business_skill` / `read_frame_execution_report`。

真实 smoke 注意事项：

1. 点击“取消/中止”后，Java 端 `recordRecoverableInterruption(...).subscribe()` 是异步写 Worker frame journal，观察前应等待 `/frames/interruption` 完成或稍等 1-2 秒。
2. 用 cancel smoke 验证的是 `user_cancelled` recoverable path，不完全等价于 `LLM_REQUEST_TIMEOUT`，但可覆盖同 `contextId` 恢复、root frame 重绑、continuation summary 注入和“继续”行为。
3. Worker frame journal 是 JSON 文件，不是 JSONL：优先查看 `<data_root>/frames/by-conversation/<contextId>/*.json`；LLM prompt 和工具调用审计才分别在 `logs/llm-conversations`、`logs/skill-tool-calls` 的 JSONL 下。

## Constraints / Non-goals

- 不要求 Java、SDK、BFF 或 Widget 选择具体 frame；frame recovery 仍应由 Worker 内部负责。
- 不要求 Java、SDK、BFF 或 Widget 复用旧 `taskId`；但要求同一会话的后续输入必须保持同一个 `contextId`。
- 不追求 deterministic replay LLM 历史；目标是注入足够的持久化事实摘要。
- 不在本项中重新设计 OPT-029 的 timeout/deadline/cancel 主合同。
- 不原样暴露 `private_messages` 给用户或上游，只允许进入受控的 LLM 恢复摘要。
- 不在本项中设计 Worker 侧副作用幂等账本或 Java/TMS 幂等 key 传递；关键操作由业务系统保证幂等或返回 repeat/duplicate 语义。

## References

- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/OPT-029-upstream-timeout-governance.md`
- `tools/langgraph-biz-worker/data/frames/lgt_ffd00790e5a34bbb/frm_22fa79248df5.json`
- `tools/langgraph-biz-worker/data/frames/lgt_731a772efc41471d/frm_22fa79248df5.json`

## Progress Tracking

### Development Progress

- [x] 已根据本机日志确认原任务 timeout 后 root frame 可恢复，且 continue 任务复用了同一 root frame。
- [x] 已确认 Root LLM 初始上下文包含 recoverable interruption，但缺少旧 `private_messages` / child report digest。
- [x] 已确认 continue 后实际重新调用 `tms-ticket-agent`，没有精确接续旧 `WAITING_USER next_step`。
- [x] 已明确 `invoke_business_skill` 是父子 skill 主合同，正常 promoted result 与中断恢复 continuation summary 必须保持字段同构。
- [x] 已明确 `read_frame_execution_report` 不应作为普通业务决策默认路径，只作为解释、审计、debug、恢复摘要补救或结果缺失降级工具。
- [x] 已设计恢复摘要的数据来源、字段结构和注入位置：由 child promoted result 生成 `latest_child_result_summary`，写入 parent private working state 与 `root_context_summary`。
- [x] 已设计并实现 `invoke_business_skill` / `read_frame_execution_report` 工具描述收敛。
- [x] 已实现恢复摘要注入：recoverable interruption prompt 注入 `Continuation summary from promoted child result`。
- [x] 已明确本阶段暂不设计 Worker 侧副作用幂等 hard guard，依赖业务系统自身幂等 / repeat / duplicate 语义。
- [x] 已完成恢复摘要脱敏自检：continuation summary 生成层会裁剪/脱敏 http(s) URL、signed/download URL、token/API key、raw prompt、provider traceback/stack 等敏感内容，同时保留 `status/next_step/missing_fields` 等恢复语义。
- [x] 已明确 Java/session relay 分层：Java 可以新建 `taskId`，但必须保持同一 `contextId`；recoverable frame 选择和重绑由 Worker 依据 conversation identity 负责。
- [x] 2026-05-19 设计修订已确认：同 `contextId` 存在 recoverable focus 时，下一条用户消息直接恢复并注入 focus frame，不再由 Root LLM 先判断是否调用 `resume_recoverable_child_skill`。
- [x] runtime direct focus resume 主路径已实现：同 `contextId` 存在 immediate recoverable child focus 时，Worker 在 Root LLM 运行前直接恢复该 focus frame，并把当前用户消息注入该 frame。
- [x] focus frame 完成后已刷新 root 可见上下文摘要，再由 Root LLM 基于 promoted child result 生成最终用户可见结果。
- [ ] abandon / rewind / new conversation 的用户放弃路径待后续产品设计。

### Testing Progress

- [x] 本机日志复盘完成，问题可从 frame JSON 与 tool call 记录确认。
- [x] Python targeted regression 已补充并通过。
- [x] Worker scripted E2E 已补充并通过：覆盖 child promoted `WAITING_USER/next_step/missing_fields` 在 root 中断后进入 continue prompt，且 continue 不重复打开 child skill、不调用 `read_frame_execution_report`。
- [x] Java/session relay 测试评估并通过：现有 `LanggraphTaskServiceTest` 覆盖 `contextId/context_id/session_id` 进入 provider context；`BusinessAgentLanggraphLaunchE2ETest` 覆盖第二轮新 `taskId` 但复用同一 `contextId` 并透传 recent conversation；`LanggraphWorkerClientTest` 覆盖 interruption API 透传 `context_id`。
- [x] direct focus resume scripted E2E 已补充并通过：同 `contextId` 下一条消息直接恢复 focus frame，不产生 Root LLM 的 `resume_recoverable_child_skill` 选择回合，也不重复 `invoke_business_skill` / `read_frame_execution_report`。
- [x] 已调整 scripted smoke fixture：已完成 child frame 后的 root-level continue 应沿用已有 promoted result，不重复委派子技能或重新取证。
- [x] 已修正 mock LLM scripted cursor 提取：同一条消息内存在旧摘要 cursor 与当前用户 cursor 时，选择靠后的当前 cursor，避免旧 continuation summary 污染脚本步骤。
- [ ] TMS 真实链路 smoke 待设计。

### Experience Progress

- [x] 已从用户截图和会话行为确认体验问题：用户点击“继续”后恢复方向不够精确。
- [ ] Playwright 或真实 TMS 浏览器复测待后续测试规划。
- [ ] 用户可见文案验收待实现后补充。

### Execution Check-in

- completed_work_summary: 已实现 child promoted result 到 continuation summary 的持久化与 recoverable prompt 注入；已收紧 `invoke_business_skill` 与 `read_frame_execution_report` 工具契约，明确 report 不是正常业务决策默认路径；已为 continuation summary 增加恢复摘要专用脱敏。
- touched_code_paths:
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/skill_runtime.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_agent_prompts.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_tool_schemas.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py`
  - `tools/langgraph-biz-worker/tests/test_e2e_scripted_tool_call_streaming.py`
  - `tools/langgraph-biz-worker/tests/test_frame_lifecycle.py`
  - `tools/langgraph-biz-worker/tests/test_llm_skill_agent.py`
  - `tools/langgraph-biz-worker/tests/test_llm_tool_schemas.py`
- self_check:
  - [x] 问题归属为 `1.3.0-SNAPSHOT` workitem。
  - [x] development / testing / experience progress 已记录。
  - [x] 正常 child promoted result 与恢复 prompt 注入使用同一 compact summary 结构。
  - [x] 工具描述已明确 report 读取的非默认用途。
  - [x] 恢复摘要不原样注入 signed URL、token/API key、raw prompt、provider stack/traceback。
  - [x] Java/session relay 边界已记录为“新 taskId 可接受，同 contextId 必须保持”。
- test_execution_status: passed Worker scripted E2E + targeted regression:
  - `$env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_root_skill_continues_after_recoverable_model_loop_failure tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_continue_prompt_includes_promoted_child_waiting_summary tests/test_frame_lifecycle.py tests/test_frame_interruption.py tests/test_llm_skill_agent.py tests/test_llm_tool_schemas.py`
  - `mvn -pl addons/langgraph-biz-worker -am "-Dtest=LanggraphTaskServiceTest,BusinessAgentLanggraphLaunchE2ETest,LanggraphWorkerClientTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`：25 tests passed。
- acceptance_readiness: partial；Python targeted regression、Worker scripted E2E、恢复摘要脱敏自检与 Java/session relay 测试评估已完成，仍需 TMS 真实链路 smoke。

### Execution Check-in 2026-05-19 Direct Focus Resume

- completed_work_summary: 已把 recoverable continuation 主路径下沉到 runtime。Root frame 进入 `run_skill` 后会先检查非 root 的 `recoverable_focus_frame_id`；若为 immediate child focus，则重绑新 `taskId`、恢复该 child frame、把当前用户 prompt 注入 child LLM loop。child 完成后 promoted result 写回 root，并刷新 root 可见上下文摘要，再由 root 输出最终 result。
- touched_code_paths:
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/skill_runtime.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py`
  - `tools/langgraph-biz-worker/tests/test_frame_lifecycle.py`
  - `tools/langgraph-biz-worker/tests/test_e2e_scripted_tool_call_streaming.py`
  - `tools/langgraph-biz-worker/tests/fixtures/llm_scripts/root_skill_real_smoke.json`
  - `tools/mock-llm-service/src/mock_llm/store/script_store.py`
  - `tools/mock-llm-service/tests/test_openai_api.py`
- self_check:
  - [x] continue 后 direct child focus 先于 Root LLM 恢复。
  - [x] continue 后没有 `resume_recoverable_child_skill` 工具选择回合。
  - [x] continue 后没有重复 `invoke_business_skill`。
  - [x] continue 后没有为了普通业务决策调用 `read_frame_execution_report`。
  - [x] child promoted result 被 root 作为最终输出前的业务上下文，而不是直接展示 frame result。
- test_execution_status: passed:
  - `$env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest tests/test_frame_lifecycle.py tests/test_frame_interruption.py tests/test_llm_skill_agent.py tests/test_e2e_scripted_tool_call_streaming.py -q`：88 passed。
  - `$env:PYTHONPATH='src'; ..\langgraph-biz-worker\.venv\Scripts\python.exe -m pytest tests/test_openai_api.py::test_scripted_cursor_uses_last_cursor_inside_message -q`：1 passed。
- acceptance_readiness: implemented-pending-smoke；自动化回归已覆盖本阶段核心语义，仍需 TMS 真实链路 smoke 验证 UI cancel/timeout 后的用户可见行为。
