# BizWorker Complex Task Session Sample Audit

## Purpose

记录对本机 Codex / Claude 历史会话的脱敏抽样观察，用于校准 BizWorker 后续 `active_plan`、复杂任务恢复、退出前一致性检查和 mock E2E 设计。

本文只记录结构化观察和设计启发，不保存原始会话正文、图片、工具输出全文、凭证、业务数据或用户隐私内容。

## Version

`1.3.0-SNAPSHOT`

## Status

`ANALYSIS_ONLY`

## Sources

抽样路径：

1. `C:\Users\oldse\.codex\sessions\2026\05\16\*.jsonl`
2. `C:\Users\oldse\.codex\sessions\2026\05\17\*.jsonl`
3. `C:\Users\oldse\.claude\projects\D--foggy-projects-Foggy-Navigator-wt-qd-win11-dev\*.jsonl`

抽样方式：

1. 解析 JSONL 事件类型、消息角色、工具调用名称、上下文压缩事件。
2. 只读取少量消息预览用于判断任务类型。
3. 不把原文复制到本文。
4. 不把原始 session 文件纳入版本控制。

## Structural Findings

### Codex sessions

代表性样本：

| Session group | Lines | User events | Tool calls | Unique tools | Context compactions |
| --- | ---: | ---: | ---: | --- | ---: |
| 2026-05-16 largest sampled session | 2491 | 29 | 684 | `shell_command`, `apply_patch`, `update_plan` | 9 |
| 2026-05-16 recent sampled session | 372 | 10 | 94 | `shell_command`, `apply_patch` | 1 |
| 2026-05-17 recent sampled session | 164 | 3 | 50 | `shell_command`, `apply_patch`, `update_plan` | 0 |

观察：

1. 真实复杂任务经常跨越多个用户回合，而不是单轮完成。
2. 一个任务可能包含上游复验、问题复述、修复、测试、发布、再复验、文档收口等阶段。
3. 长会话中会发生多次 context compaction；如果没有显式 working state，模型只能依赖压缩摘要继续推理。
4. 工具链很长，且以文件搜索、测试、补丁、提交相关操作为主。
5. 用户经常输入“继续”“下一步计划”“生成提示词”等短指令，这些指令本身需要结合当前任务状态解析。

### Claude sessions

代表性样本：

| Session group | Lines | User events | Assistant events | Tool use | Tool result |
| --- | ---: | ---: | ---: | ---: | ---: |
| latest project session | 30 | 7 | 13 | 6 | 6 |
| medium project session | 161 | 36 | 78 | 33 | 33 |

观察：

1. Claude JSONL 中有些 tool result 会以 user-side event 形式出现，不能简单把所有 `user` 类型都当作真实用户意图。
2. 项目会话中常见“命令失败 -> 重新定位环境 -> 修改命令 -> 继续验证”的循环。
3. 会话可能包含很大的工具输出引用或 persisted output，需要用 artifact/evidence ref 管理，而不是直接灌入计划上下文。
4. `commit&push` 这类用户指令经常要求 agent 自动识别未跟踪文件、区分相关/无关改动、执行验证并提交。

## Task Pattern Findings

### Pattern 1: upstream feedback loop

流程：

1. 用户提供上游复验结果。
2. agent 判断是 Navi 侧缺陷、上游配置问题，还是产物未更新。
3. agent 建立或更新 BUG work item。
4. agent 修复代码并补测试。
5. agent 给上游生成复验提示词。
6. 上游再次反馈后，agent 关闭缺陷或继续 follow-up。

设计启发：

- `active_plan` 需要支持 external feedback step。
- step 不能只表示本地工具调用，也要表示“等待上游复验 / 消化上游反馈 / 生成交付提示词”。
- plan result 应能引用证据，例如 task id、suspend id、测试命令、文档路径。

### Pattern 2: long tool-call loop with compaction

流程：

1. agent 读取大量文件和测试输出。
2. 中途 context compaction 发生。
3. 用户输入“继续”。
4. agent 需要知道当前任务、已完成验证、剩余步骤和不能覆盖的用户改动。

设计启发：

- root working state 里的 `active_plan` 必须比普通对话摘要更结构化。
- compaction 后应保留 step status、evidence refs、owned files、blocked reasons。
- “继续”应优先恢复 active step，而不是重新解释原始用户请求。

### Pattern 3: user command is short but context-heavy

典型短指令：

1. “继续”
2. “下一步计划”
3. “生成提示词”
4. “commit&push”
5. “开始推进吧”

设计启发：

- intent resolution 不能只看当前用户文本。
- root prompt 应同时注入 `active_plan`、`recoverable_focus_*` 和最近 checkpoint。
- observer 检查时应判断最终回复是否满足当前 active step，而不是只回答短文本表面含义。

### Pattern 4: tool output is not user intent

Claude 样本显示 tool result 可能作为 user-side event 存在。

设计启发：

- BizWorker message ingestion 需要区分 human user input、tool result、system event、local command output、artifact reference。
- 复杂任务 plan 的 intent detection 只应对真实用户输入触发，不应把工具输出当作新任务。

### Pattern 5: dirty worktree and ownership boundary

真实样本中经常存在未跟踪文件或并行改动。

设计启发：

- `active_plan` 可以记录 `owned_paths` / `observed_unrelated_paths`。
- plan observer 在退出前可以检查是否有预期文件未更新、测试未跑、或无关文件被错误纳入。
- commit/push 类任务需要显式 staging plan，而不是简单 `git add .`。

## Implications For active_plan

建议 `active_plan` 第一阶段至少支持：

1. `goal_summary`：当前复杂任务目标。
2. `steps[]`：每步 objective、status、owner capability、dependencies。
3. `active_step_id`：当前正在推进的步骤。
4. `evidence_refs[]`：测试命令、文档路径、上游反馈摘要、artifact refs。
5. `owned_paths[]`：本任务允许修改/提交的文件边界。
6. `external_waits[]`：等待上游、等待审批、等待用户输入等状态。
7. `last_checkpoint`：最近一次完成的稳定点。
8. `resume_policy`：用户输入“继续”时应恢复哪个 step/frame。

## Implications For PLAN_OBSERVER

`PLAN_OBSERVER` 第一阶段保持只读，并重点检查：

1. 是否遗漏了 plan 中未完成的 step。
2. 是否把失败/阻塞步骤误报为完成。
3. 是否在未验证时声称测试通过。
4. 是否应该等待上游反馈却提前关闭任务。
5. 是否把工具输出当成用户新意图。
6. 是否误纳入无关工作区文件。
7. 是否在 context compaction 后丢失 active step。

这些检查都可以先用 mock LLM E2E 固化，不需要马上实现真实 observer frame。

## Suggested Mock E2E Cases

1. `active_plan_handles_upstream_feedback_loop`
   - 用户提供上游失败报告。
   - root 创建计划：记录缺陷、补测试、修复、生成复验提示词。
   - 上游再次反馈通过后，root 关闭计划。
2. `active_plan_survives_context_compaction`
   - 长工具链后模拟 context compaction。
   - 用户输入“继续”。
   - root 从 `active_plan.active_step_id` 恢复，而不是重新规划。
3. `tool_result_does_not_start_new_user_task`
   - 注入 tool-result-like message。
   - root 不把它识别成用户新任务。
4. `commit_plan_stages_only_owned_paths`
   - 工作区同时有相关和无关改动。
   - root 只提交 active_plan 的 owned paths。
5. `plan_observer_detects_unverified_final_claim`
   - root 准备最终回复但测试 step 未完成。
   - observer 返回 `NEEDS_CORRECTION`。
6. `plan_observer_detects_missing_external_feedback_step`
   - plan 中有等待上游复验 step。
   - root 不应在未收到反馈时关闭。

## Recommendation

下一阶段不直接实现 plan agent。推荐顺序：

1. 先实现 root-owned `active_plan` 最小模型。
2. 补 mock LLM E2E，覆盖多轮上游反馈、context compaction 后继续、tool result 非用户意图、dirty worktree ownership。
3. 再设计只读 `PLAN_OBSERVER` 的 prompt 和输出 contract。
4. 真实 observer frame 最后实现，且只作为退出前一致性检查，不接管任务生命周期。

## Non-goals

1. 不保存或提交原始 Codex/Claude 会话。
2. 不把历史会话直接转换为 mock fixture。
3. 不用真实会话中的业务细节训练规则。
4. 不在本阶段实现 `active_plan`、`PLAN_OBSERVER` 或 plan agent。
