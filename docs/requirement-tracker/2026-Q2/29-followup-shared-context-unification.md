# 29 Follow-up - Shared Context Unification

## Date

- 2026-04-01

## Background

在 `29-a2a-system-prompt-first-msg-semantics.md` 落地后，联调中又暴露出两类实现层问题：

1. shared ask 链路为了缓存 Navigator session，额外向 `agent_conversation_contexts` 写入了一条 `shared-nav:{sharingKeyId}:{contextId}` 伪 context 记录。
2. `firstMsg` 在特定情况下会重复注入，导致 prompt 中出现重复的 `[Initial Message]`。

这份文档记录本次实现层调整，供后续复盘。

## Decision

### 1. `contextId` 作为唯一真相

shared ask 不再维护 `shared-nav:*` 伪 context。

收敛后的原则：

- 真实 `contextId` 是唯一会话主键
- `contextAlias` 只用于解析到真实 `contextId`
- `navigatorSessionId` 和 `agentSessionRef` 都只挂在真实 `contextId` 对应的 `AgentConversationContextEntity` 上
- `agent_conversation_contexts` 不再承载 shared 内部 session cache

### 2. `contextId` 路径恢复完整上下文

为支撑上面的收敛，`contextId` 的恢复逻辑不再只读取 `agentSessionRef`，而是直接恢复完整的 `AgentConversationContextEntity`，至少包含：

- `agentSessionRef`
- `navigatorSessionId`
- `contextAlias`

这样 shared ask、普通 ask、openapi ask 都能走同一条恢复路径。

### 3. `firstMsg` 注入改为幂等

`prependFirstMsg(...)` 增加幂等保护：

- 如果消息文本已经以 `[Initial Message]` 开头，则不再重复拼接

### 4. dedup 命中时保留旧 context

Claude dedup 命中场景下，返回的 task metadata 可能不包含新的 `claudeSessionId/sessionId`。

调整后的回写规则：

- task metadata 有新值时，用新值
- task metadata 没有新值时，保留旧 context 中已有的 `agentSessionRef/navigatorSessionId`

避免因为 context 被覆写为空，导致下一轮再次被误判为首轮并重复注入 `firstMsg`。

## Scope

涉及模块：

- `session-module`
- `navigator-spi`
- `addons/claude-worker-agent`
- `addons/codex-worker-agent`

涉及关键类：

- `SharedAskController`
- `ContextResolvingA2aAgent`
- `AgentContextStore`
- `AgentContextStoreImpl`
- `ClaudeWorkerA2aAgentTest`
- `CodexWorkerA2aAgentTest`

## Expected Result

调整后预期：

1. shared ask 新请求不再新增 `shared-nav:*` 记录
2. 同一 shared 会话仅保留真实 `contextId` 对应的 context 记录
3. `contextId` 可直接恢复 `navigatorSessionId + agentSessionRef`
4. `[Initial Message]` 不会重复注入
5. dedup 命中后不会因为 context 丢失而再次触发首轮注入

## Follow-up

本轮仅记录与实现收敛，不继续扩展。后续可复盘：

- 是否需要清理历史遗留的 `shared-nav:*` 记录
- 是否为 `firstMsg` 增加显式“已注入”标记
- dedup 命中时是否应该统一补齐 task metadata 的 `agentSessionRef/sessionId`
