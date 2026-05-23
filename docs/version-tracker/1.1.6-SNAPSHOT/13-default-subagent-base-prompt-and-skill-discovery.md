# Default SubAgent Base Prompt 与 Skill Discovery 设计

## 文档作用

- doc_type: design
- intended_for: architecture-discussion | execution-agent | reviewer
- purpose: 收口 BizWorker 子 Agent 默认系统提示词、Root 上下文隔离边界，以及子 Agent 内部 Skill 发现/读取能力

版本：`1.1.6-SNAPSHOT`
状态：approved-for-implementation
日期：2026-05-22

## 背景

`invoke_business_agent` 打开的 non-root frame 已经从旧的 “Skill frame” 收口为 “Agent frame”。Agent frame 默认采用 isolated handoff，不再 fork Root 完整 messages，也不继承 Root 的 `allowed_skills` 目录。

这带来一个新的提示词和工具授权问题：

1. 子 Agent 仍需要通用平台运行契约，例如内部 ID 治理、业务函数调用规则、附件/日期上下文、完成/等待用户/交还父级的状态契约。
2. 子 Agent 不应继承 Root 专属上下文，例如 Root 完整可见历史、Root `allowed_skills` Markdown 目录、Root planning callback 或 Root memory commit 规则。
3. 子 Agent 如果需要业务 Skill，应在自己的 frame 内自行发现并读取 Skill 材料，而不是依赖 Root 预先注入。
4. 如果执行策略只允许 `invoke_business_skill` 或 `invoke_business_agent`，运行时也必须同步允许 `list_skill_resources` / `read_skill_resource`，否则提示词说“可以读取 Skill”但模型实际看不到工具。

## 设计结论

### 1. 子 Agent 继承 shared platform contract

Root 与子 Agent 共享以下通用系统契约：

1. 只能使用已提供工具。
2. Navigator 内部 tracing id 不得当成业务单据号。
3. 业务函数 id / version 的调用规则。
4. Skill 与 Agent 的边界：Skill 是当前 frame 材料，Agent 才创建 frame。
5. 附件、日期、frame result、report ref、artifact ref 的治理规则。
6. plain assistant 最终消息可被运行时归一化，但结构化状态优先用 frame 完成/交还工具提交。

这些规则是平台运行时契约，不属于 Root 私有上下文，子 Agent 必须携带。

### 2. 子 Agent 不继承 Root-specific context

默认 isolated Agent frame 不接收：

1. Root 完整 `user -> assistant -> tool_call -> tool_result` 历史。
2. Root `allowed_skills` 业务目录 Markdown。
3. Root runtime memory 的 checkpoint / finalizing callback。
4. Parent frame 的 raw tool chain。
5. Root 只为当前用户回合准备的 active planning / memory bootstrap 细节。

子 Agent 的首次 LLM body 应保持：

```text
system(shared platform contract + child Agent identity + child handoff context)
human(parent handoff instruction)
```

恢复时只恢复该 Agent frame 自己的 `runtime-message-events`：

```text
system(shared platform contract + child Agent identity + child recovery context)
child frame provider protocol messages
human(new user input or resume instruction)
```

### 3. 子 Agent 默认工作方式

即使没有专门的 Agent manifest，BizWorker 也为 Agent frame 提供默认 SubAgent Base Prompt：

1. 你是被委派的子 Agent，只处理父级交给你的任务。
2. 你默认看不到 Root 完整历史；以上下文中的 handoff instruction、附件、refs 和必要摘要为准。
3. 如需业务 Skill，先调用 `list_skill_resources` 查看当前 ClientApp 可见技能，再调用 `read_skill_resource` 或 `invoke_business_skill` 读取 Skill 材料。
4. `invoke_business_skill` 只在当前 Agent frame 内加载 Skill 材料，不会再创建新的 frame。
5. 只有当任务确实需要更深层独立生命周期或用户明确要求子 Agent 时，才调用 `invoke_business_agent`。
6. 完成、等待用户补充或需要交还父级时，优先调用 `submit_skill_result` 或 `handoff_to_parent`，以提交结构化状态、refs 和退出意图。
7. 如果只输出自然语言，运行时会按子 Agent 风格归一化为完成或等待用户，但这不应替代需要 refs/状态的结构化结果。

### 4. Skill discovery 工具授权分组

`execution_policy.allowed_tools` 是上游安全边界，不应随意放宽。但 Skill/Agent 能力存在一组必要辅助工具：

```text
Skill material tools:
- invoke_business_skill
- invoke_business_agent

Skill discovery tools:
- list_skill_resources
- read_skill_resource
```

规则：

1. 如果 `allowed_tools` 为空或未配置，保持原有全量工具可见策略。
2. 如果 `allowed_tools` 显式允许 `invoke_business_skill`，则同时允许 `list_skill_resources`、`read_skill_resource` 和兼容性的 `invoke_business_agent`。
3. 如果 `allowed_tools` 显式允许 `invoke_business_agent`，则同时允许 `list_skill_resources` 和 `read_skill_resource`。
4. 该规则只放行当前 ClientApp 的公开 Skill discovery/read 能力，不绕过 Skill registry、ClientApp scope 或业务函数 allowlist。
5. 其他工具仍严格按 `allowed_tools` 判断。

这样可以避免子 Agent 系统提示词说“可读取 Skill 材料”，但模型实际 body 中缺少发现/读取工具。

### 5. 与 Codex / Claude Code 对齐点

当前对齐口径：

1. 普通 Skill 读取类似 Codex / Claude Code 的项目 Skill 或说明文件读取，材料进入当前 frame 的 provider protocol。
2. 显式子 Agent 委派是隔离执行体，默认不 fork Root 完整历史，只向 parent 提升摘要和 refs。
3. 子 Agent 如果需要更多材料，应在自己的执行体内主动读取，而不是由 Root 预注入所有 Skill 目录。
4. 未来可增加显式 `parent_fork` 或“大上下文全量继承”模式，但必须通过工具参数或 Agent manifest 明确表达，不能作为默认行为。

## 验收口径

1. Root 普通回合的 system prompt 继续包含可用业务技能 Markdown，human message 只保留用户原文和允许的请求态时间。
2. 子 Agent system prompt 包含 shared platform contract 和默认 SubAgent 工作方式。
3. 子 Agent system prompt 不包含 Root `allowed_skills` 目录，不包含 Root 完整历史。
4. 当 Agent manifest / execution policy 允许 `invoke_business_skill` 或 `invoke_business_agent` 时，LLM body 的 tool schema 中包含 `list_skill_resources` 和 `read_skill_resource`。
5. 当 execution policy 只允许无关业务工具时，Skill discovery 工具不被额外放行。
6. 子 Agent 仍可用 `submit_skill_result` / `handoff_to_parent` 退出；自然语言最终消息仍可被运行时归一化。

## 实施项

1. Prompt：拆清 shared platform contract 与 Root-specific context；补充子 Agent 默认工作方式。
2. Generic Agent manifest：默认 markdown_body 明确 isolated handoff 与 Skill discovery 流程。
3. Tool schema filtering：`llm_tool_schemas._tool_enabled` 支持 Skill material -> discovery tool 分组放行。
4. Execution policy：`ExecutionPolicy.allows_tool` 使用同一分组规则，保证运行时授权与提交给 LLM 的工具列表一致。
5. Tests：覆盖子 Agent prompt、tool schema filtering 和 execution policy 分组授权。
