# 1.1.6-SNAPSHOT

本目录用于跟踪 `1.1.6-SNAPSHOT` 阶段的 BizWorker 会话上下文、运行时记忆与上游契约调整。

## 版本目标

1. BizWorker 成为 LLM runtime context 的 source of truth；Java / 上游继续负责完整 UI transcript。
2. 普通多轮对话、Agent frame 完成、`AWAITING_USER` 续接、中断恢复都统一进入 `ContextRuntimeMemory` / focus stack control state 规则；Skill 不再默认进入 frame。
3. `recentConversation` 降级为 deprecated external compatibility input，只允许空 memory bootstrap，不允许覆盖 BizWorker memory。
4. Root frame 可见的 tool call / tool result 属于 LLM runtime protocol，应进入后续 bounded runtime context，直到压缩或裁剪；Agent frame 内部 messages、report/log/journal 作为 execution evidence 保留，不直接外泄到 Root。
5. 上下文压缩采用 head-tail + lazy LLM summarizer，并提供 deterministic fallback，避免 runtime context 无界增长。
6. 同一 `contextId` 的 Root frame / runtime memory 写入必须由 BizWorker 自身提供排他保护。
7. 真实提交给 LLM 的 `messages` 数组中，`system` 承载运行时治理上下文，BizWorker runtime-visible protocol 以独立 `user` / `assistant` / `tool` messages 注入，当前 `human` 以用户原文为主，最多追加当前请求时间这类极少量请求态信息。

## 版本验收基线

1. 没有 Java `recentConversation` 时，BizWorker 仍能维持普通多轮语义连续。
2. Root frame 在上一轮产生的 tool call / tool result 会随后续 prompt 保留；普通 Skill 调用作为当前 frame 的 tool protocol 保留；Agent frame 完成后，Root 只能看到 Agent promoted result，不展开 Agent frame 内部 tool trace。
3. 只要存在可恢复 focus stack，下一条普通用户消息默认直达 deepest leaf frame；`AWAITING_USER`、用户中止、TIMEOUT/ERROR 都遵循该规则。
4. recoverable interruption 通过 control state 恢复或后续显式丢弃，不伪造成普通 assistant turn。
5. 同一 `contextId` 不允许真正并发 LLM loop；Phase 1 未实现 queue 前必须有明确 busy / conflict / 上游串行契约，Phase 3 起进入 pending queue + checkpoint。
6. `AWAITING_USER` / interrupted leaf 支持由 LLM 调用 frame 退出工具完成 escape hatch；普通 stop/cancel 只暂停当前运行，不清除 focus stack。
7. UI transcript rollback / regenerate 不作为 Phase 1-4 默认能力；后续必须通过 revision / turnId / fork 契约单独设计，并作为真正丢弃旧 focus stack 的能力边界。
8. LLM submission 复盘日志能保存真实 ChatModel body，便于验证每次提交给 LLM 的完整参数，并对账 root-visible tool protocol 是否被保留。

## 文档收口口径

本版本文档按分层收口，不把早期设计全文重写为单篇巨文：

1. `01`-`07` 是运行时上下文治理与 Phase 1-5 实施基线。
2. `08` 是 `system.root` 退场和 Conversation Root Frame 身份收口。
3. `09` 是当前真实提交给 LLM 的 `messages` 数组契约。
4. `10` 是 scripted E2E 场景矩阵，以及 `llm-submissions` / `runtime-message-events` 对账验收口径。
5. `11` 是真实上游 OpenAPI smoke 与本地 runtime context 证据对账 runbook。
6. `12` 是 2026-05-22 收口后的 Agent / Skill / Frame 边界准绳：Skill 工具化，Agent Frame 化。
7. `13` 是子 Agent 默认系统提示词与 Skill discovery 授权口径：子 Agent 携带 shared platform contract，不继承 Root-specific context。
8. `14` 是 Account Workspace Resolver 与 delegated workspace 口径：account/upstream user 身份不再等同固定物理目录。
9. `15` 是受限 `shell_command` 工具设计口径：Linux 命令格式入口，但内部实现为 workspace 受限命令解释器，不开放真实宿主机 shell。
10. `16` 是 Navigator Upstream CLI 与配套 skill 的 1.1.6 runtime contract 对齐口径，并记录模型 token 预算 preset 字段落地方式。

若旧文档中仍出现“runtime context 拼入 user prompt”的早期表述，以 `09` 的 system / human 边界为当前实现口径。

若旧文档中仍出现“Skill frame”“`invoke_business_skill` 打开 child frame”等早期表述，以 `12` 的 Agent / Skill / Frame 边界为当前实现口径。

若旧文档中仍出现“子 Agent 继承 Root 完整上下文”或“Root 预注入全部 Skill 目录给子 Agent”的早期倾向，以 `13` 的 isolated handoff + 子 Agent 自主 Skill discovery 为当前实现口径。

若旧文档中仍只描述“按条数保留最近 messages”，以 `09` 的 Prompt window 裁剪边界为当前口径：裁剪必须同时保护 provider tool protocol 和 user/assistant 语义 turn，后续参数设计见 `OPT-runtime-prompt-window-turn-aware-pruning`。

若旧文档中仍把 upstream user 记忆目录固定描述为 `<data_root>/accounts/<accountId>`，以 `14` 的 managed account mode + delegated workspace resolver 为当前设计口径。

若后续文档讨论 `shell_command`，以 `15` 的 restricted shell 口径为准：命令格式向 Linux 对齐，但必须通过 allowlist、resolver/path guard 和输出预算治理，不直接执行任意系统 shell。

若旧 CLI / skill 文档仍暗示上游自行生成 `contextId`、把 `clientContext` 当成 LLM prompt 配置、或把模型上下文窗口塞入用户消息，以 `16` 为当前口径：新会话由 BizWorker 生成 `contextId`，上游只复用返回值；`clientContext` 只保存会话元数据；模型 token 预算通过 `runtimeBudgetPresetKey` / `runtimeBudgetOverrideJson` 后端一等字段配置。

## 当前条目

- [01-biz-worker-context-owned-runtime-memory.md](./01-biz-worker-context-owned-runtime-memory.md) - BizWorker 按 `contextId` 自主管理运行时上下文与执行摘要，降低对 Java 会话层 `recentConversation` 的依赖
- [02-runtime-context-governance-framework-comparison.md](./02-runtime-context-governance-framework-comparison.md) - 对比 Claude Code / Codex / OpenAI Agents SDK / LangGraph / AutoGen / OpenHands 的上下文与压缩策略，收口 BizWorker 运行时上下文治理基线
- [03-skill-internal-context-isolation-and-promotion.md](./03-skill-internal-context-isolation-and-promotion.md) - 明确 Skill 内部上下文隔离、`AWAITING_USER` 自动恢复、完整证据保留、受控结果提升与 Parent runtime context 的边界
- [04-runtime-visible-conversation-and-recovery-design.md](./04-runtime-visible-conversation-and-recovery-design.md) - 统一定义 `runtimeVisibleConversation`、tool call 可见性、Skill 完成投影与中断恢复控制态
- [05-business-function-upstream-ref-error-feedback-bug.md](./05-business-function-upstream-ref-error-feedback-bug.md) - 记录 BusinessFunction adapter `upstream_ref` 配置错误被误当成 LLM 可修复入参并折叠为 max-iterations 的 BUG
- [06-normal-turn-runtime-context-design.md](./06-normal-turn-runtime-context-design.md) - 明确普通用户消息在 BizWorker 内写入、恢复、压缩和组装为下一轮 LLM runtime context 的设计
- [07-normal-turn-runtime-context-implementation-plan.md](./07-normal-turn-runtime-context-implementation-plan.md) - 将普通消息 `ContextRuntimeMemory` 设计拆解为分阶段开发任务、测试清单和验收闸门
- [08-system-root-retirement-and-root-frame-design.md](./08-system-root-retirement-and-root-frame-design.md) - 将 `system.root` 从业务 Skill 身份退场，改为内部 Conversation Root Frame 语义并保留旧数据兼容
- [09-llm-submission-message-contract.md](./09-llm-submission-message-contract.md) - 收口真实提交给 LLM 的 `messages` 数组契约：system 承载治理上下文，runtime-visible conversation 使用独立 role messages
- [10-runtime-context-e2e-matrix-and-log-parity.md](./10-runtime-context-e2e-matrix-and-log-parity.md) - 固化 runtime context scripted E2E 矩阵，并要求关键场景同时校验 `llm-submissions` 与 `runtime-message-events`
- [11-live-upstream-runtime-context-smoke.md](./11-live-upstream-runtime-context-smoke.md) - 提供真实上游 OpenAPI smoke 与 validate-only 对账脚本，覆盖 session root 定位、LLM body 快照、runtime events、附件引用和重开 UI/task 消息 raw tool 泄漏检查
- [12-agent-frame-and-skill-tool-boundary.md](./12-agent-frame-and-skill-tool-boundary.md) - 收口 Agent / Skill / Frame 新边界：Skill 不再默认进入 frame，只有 Agent 调用才创建 non-root frame
- [13-default-subagent-base-prompt-and-skill-discovery.md](./13-default-subagent-base-prompt-and-skill-discovery.md) - 收口子 Agent 默认提示词、Root 上下文隔离，以及允许 Skill/Agent 时同步放行 Skill discovery 工具的口径
- [14-account-workspace-resolver-and-delegated-mode.md](./14-account-workspace-resolver-and-delegated-mode.md) - 收口 Account Workspace Resolver、managed account mode 和 delegated workspace mode 的目录解析契约
- [15-restricted-shell-command-tool-design.md](./15-restricted-shell-command-tool-design.md) - 记录受限 `shell_command` 工具设计：Linux 命令格式、有限命令子集、workspace path guard 与默认暴露策略
- [16-upstream-cli-skill-runtime-contract-alignment.md](./16-upstream-cli-skill-runtime-contract-alignment.md) - 收口 Navigator Upstream CLI / 配套 skill 与 1.1.6 runtime context 的对齐口径，并记录模型 token 预算 preset 字段落地方式
- [workitems/BUG-runtime-context-phase2-5-review-fixes.md](./workitems/BUG-runtime-context-phase2-5-review-fixes.md) - 记录并修复 Phase 2-5 评审发现的排队终止窗口、JSON 脱敏和 commit 清理缺陷
- [workitems/BUG-root-account-memory-and-runtime-session-directory-governance.md](./workitems/BUG-root-account-memory-and-runtime-session-directory-governance.md) - 记录 Root Prompt upstream user 记忆文件注入疑点，以及非 `bctx_` runtime session 目录的 fallback 来源与治理建议
- [workitems/BUG-client-app-public-skill-manifest-resolution.md](./workitems/BUG-client-app-public-skill-manifest-resolution.md) - 记录并修复 ClientApp public skill 资源可见但 `invoke_business_skill` 执行 manifest 缺失的问题
- [workitems/OPT-runtime-prompt-window-turn-aware-pruning.md](./workitems/OPT-runtime-prompt-window-turn-aware-pruning.md) - 跟踪 prompt window 按 turn / tool protocol 裁剪、大工具结果预算和压缩触发边界参数设计

## 当前签收记录

- [quality/runtime-context-phase1-implementation-quality.md](./quality/runtime-context-phase1-implementation-quality.md) - BizWorker Runtime Context Phase 1 实现质量检查
- [acceptance/runtime-context-phase1-acceptance.md](./acceptance/runtime-context-phase1-acceptance.md) - BizWorker Runtime Context Phase 1 功能验收签收
- [quality/runtime-context-phase2-5-implementation-quality.md](./quality/runtime-context-phase2-5-implementation-quality.md) - BizWorker Runtime Context Phase 2-5 实现质量检查
- [acceptance/runtime-context-phase2-5-acceptance.md](./acceptance/runtime-context-phase2-5-acceptance.md) - BizWorker Runtime Context Phase 2-5 功能验收签收
