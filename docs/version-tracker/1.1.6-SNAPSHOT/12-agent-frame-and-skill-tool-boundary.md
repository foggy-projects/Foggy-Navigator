# Agent Frame 与 Skill 工具边界设计

## 文档作用

- doc_type: design
- intended_for: architecture-discussion | execution-agent | reviewer
- purpose: 收口 BizWorker 中 Skill、Agent、Frame 的职责边界，作为 1.1.6 后续实现的当前准绳

版本：`1.1.6-SNAPSHOT`
状态：approved-for-implementation
日期：2026-05-22

## 设计结论

1. Skill 不再默认进入 frame。
2. Skill 是当前执行上下文中的普通能力材料或工具入口，可以被 Root Agent 或子 Agent 读取、理解和使用。
3. 只有当 LLM 明确决定使用 Agent，或用户明确要求使用 Agent 能力时，BizWorker 才打开 non-root frame。
4. Agent frame 是生命周期容器，负责独立 loop、等待用户、异常恢复、handoff、提交结构化结果和证据引用。
5. Agent 可以在自己的 frame 中继续打开 Skill 材料、调用业务函数、读取执行报告或调用更深层 Agent。
6. Frame 身份不再强制绑定 `skillId`。新 frame 应以 `frameKind=AGENT`、`agentId`、`frameName`、`parentFrameId` 等字段表达身份；`skillId` 只作为旧数据兼容字段保留。

## 术语边界

### Skill

Skill 是给 LLM 使用的能力说明和局部操作指南，通常由 `SKILL.md`、resource manifest、可调用业务函数列表和约束说明组成。

Skill 的默认行为：

1. 不创建 child frame。
2. 不拥有独立生命周期。
3. 不要求 `submit_skill_result` 或 `handoff_to_parent`。
4. 作为当前 frame 的 tool protocol 留存在当前 frame 的 runtime-visible messages 中。
5. 如果 Skill 内部说明建议调用业务函数，LLM 在同一个 frame 中继续调用 `invoke_business_function`。

### Agent

Agent 是可以拥有独立执行 loop 的委派执行体。它可以是业务 Agent、专项 Agent、排障 Agent 或未来的子 Agent。

Agent 的默认行为：

1. 通过 Agent 调用工具进入 non-root frame。
2. 拥有独立 system prompt、runtime messages、tool protocol、等待用户和错误恢复状态。
3. 需要在完成时向 parent 提交 promoted result，或在等待用户时保留 focus stack。
4. 可以在自己的 frame 内读取 Skill 材料并调用业务函数。
5. 可以继续委派更深层 Agent，形成 focus stack。

### Frame

Frame 是 BizWorker 的运行时生命周期容器，不再等价于 Skill。

Frame 的职责：

1. 保存当前执行体的 runtime protocol。
2. 保存 report、log、LLM submission、runtime message events。
3. 维护 `AWAITING_USER`、TIMEOUT/ERROR、approval、handoff 等控制态。
4. 为 parent 提供受控 promoted result 和证据引用。

## 工具契约

### `invoke_business_skill`

`invoke_business_skill` 调用 Skill，但不创建 frame。

模型调用后，BizWorker 返回当前可用的 Skill 材料，包括：

1. skill id / name / description。
2. `SKILL.md` 或等价指令摘要。
3. 允许的业务函数、资源或约束说明。
4. 与本次 input 相关的使用建议。

随后模型继续在同一个 frame 内完成工作，例如：

```text
U1
assistant.tool_call(invoke_business_skill)
tool_result(skill material)
assistant.tool_call(invoke_business_function)
tool_result(function result)
A1
```

这与 Codex / Claude Code 读取本地技能或项目说明后继续在当前对话中工作的模式一致：Skill 材料进入当前 runtime protocol，是否被后续压缩由 runtime context governance 决定。

### `invoke_business_agent`

`invoke_business_agent` 调用 Agent，并创建 Agent frame。

模型或用户在以下情况应使用 Agent：

1. 任务需要独立上下文和多轮推进。
2. 任务可能进入 `AWAITING_USER`。
3. 任务需要复杂工具链和单独 report。
4. 任务需要与 Root 会话隔离，完成后只提升摘要和引用。

Agent frame 内部可以调用 `invoke_business_skill`，但该 Skill 调用仍然只是 Agent frame 内的普通 tool protocol，不再创建下一层 Skill frame。

Agent 完成后，parent 看到的是：

```text
U1
assistant.tool_call(invoke_business_agent)
tool_result(promoted agent result)
A1
```

Agent 内部完整 tool trace 保存在该 Agent frame 的 report/log/runtime message events 中。

### `submit_skill_result` 与 `handoff_to_parent`

这两个工具的语义收口为 non-root frame 的完成 / handoff 工具。它们不再意味着“只有 Skill frame 才能调用”。

当前命名保留是为了兼容旧工具名和前端展示；实现和文档中应逐步转向：

1. `submit_frame_result`
2. `handoff_to_parent`

Phase 内不强制一次性重命名，但提示词、文档和错误信息应避免把它们描述成 Skill 专属能力。

## Runtime Context 规则

### Root 普通回合

Root frame 是会话级持久 frame。普通用户消息进入 Root 后：

1. Root LLM 可以直接回复。
2. Root LLM 可以调用 `invoke_business_skill` 获取技能材料，继续在 Root frame 内处理。
3. Root LLM 可以调用 `invoke_business_function`。
4. Root LLM 可以调用 `invoke_business_agent` 打开 Agent frame。

Root 产生的 tool call / tool result 默认属于 Root-visible protocol，后续回合继续带回 LLM，直到被压缩或裁剪。

### Agent 回合

Agent frame 是 non-root frame。它的内部 messages、tool protocol 和 Skill 材料默认属于该 Agent frame。

Agent 完成后：

1. Parent 只接收 promoted result、status、refs、summary、awaiting-user prompt 等受控结果。
2. Agent 内部完整 trace 继续保存在 report/log/runtime message events 中。
3. 如果后续需要排障，LLM 或开发者通过 refs 按需读取，而不是默认展开到 parent。

### 恢复与中断

`AWAITING_USER`、TIMEOUT、ERROR、用户控制面 cancel 后的下一条普通输入，默认恢复 deepest active Agent frame。

这条规则只针对有生命周期的 Agent frame。普通 Skill 调用没有独立 frame，因此不存在 Skill-level focus 恢复。

如果用户真正想丢弃当前 Agent 任务，应由上游 UI 的 rollback / regenerate / explicit abandon 机制表达；普通 cancel 只暂停执行，不清除 focus stack。

## 存储与落档

### 会话目录

所有 Root、Agent frame、report、log、LLM submission 都应落在同一个 `contextId` 会话目录下。

Plain Skill 调用不会新增 `frames/frm_*.json`。

Agent 调用会新增 Agent frame：

```text
sessions/by-date/YYYY/MM/DD/<hash>/bctx_.../
  frames/
    frm_root.json
    frm_<agent>.json
  reports/
  logs/
    llm-submissions/
    runtime-message-events/
```

### 旧数据兼容

旧版本中 `FrameKind.SKILL`、`skill_id`、`skill_frame_open` 事件可能仍存在。读取旧数据时保持兼容，不做迁移要求。

新写入规则：

1. Root 使用 `FrameKind.ROOT`。
2. Agent 使用 `FrameKind.AGENT`。
3. BusinessFunction 使用 `FrameKind.FUNCTION_CALL`。
4. Plain Skill 不写 frame。
5. `skill_id` 不再作为 frame 必填身份；如短期内模型类仍保留该字段，只能视为兼容字段，不作为新设计语义。

## 与旧文档的关系

本文件覆盖 2026-05-21 文档中所有“Skill frame”默认假设。

旧文档中的以下表述应按本文件重新解释：

1. “Skill frame” 默认改为 “Agent frame”。
2. “Skill internal messages” 默认改为 “Agent frame internal messages”。
3. “Skill completed promoted result” 默认改为 “Agent completed promoted result”。
4. “`invoke_business_skill` 打开 child frame” 废弃，改为 “`invoke_business_agent` 打开 Agent frame；`invoke_business_skill` 返回 Skill 材料”。

## 实施计划

1. 文档收口：README、03、04、09、10、11 增加当前准绳说明。
2. Runtime model：新增 `FrameKind.AGENT`，frame identity 不强制 `skillId`。
3. Tool schema：调整 `invoke_business_skill` 描述，新增 `invoke_business_agent`。
4. Tool runtime：`invoke_business_skill` 改为 no-frame Skill 材料读取；Agent 调用复用现有 child frame / focus stack / recovery 能力。
5. Prompt：Root 和 Agent system prompt 使用 Agent / Skill 新边界，不再把 Skill 等同 frame。
6. Tests：覆盖 plain Skill 不建 frame、Agent 建 frame、Agent frame 内可读 Skill、等待用户和异常恢复仍按 deepest Agent frame 续接。
