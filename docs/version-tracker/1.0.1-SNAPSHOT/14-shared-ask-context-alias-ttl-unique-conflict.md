---
type: bug
bug_source: user-report
version: 1.0.1-SNAPSHOT
ticket: BUG-014
severity: critical
status: ready-for-verification
reproduction_status: confirmed
test_strategy: integration-test
automation_decision: required
owner: session-module
---

# 14 Shared Ask Context Alias TTL Unique Conflict

## Date

- 2026-04-06

## Background

用户在 2026-04-06 报告：通过共享 API

- `POST /api/v1/shared/ask`

并携带相同 `contextAlias` 的定时任务，在 2026-04-05、2026-04-06 连续两天失败，接口返回 HTTP 500：

```json
{"timestamp":"2026-04-06T12:00:01.745+00:00","status":500,"error":"Internal Server Error","path":"/api/v1/shared/ask"}
```

后端日志显示数据库唯一索引冲突：

```text
Duplicate entry 'tms-x3-strategic-audit-v4-040a457b-b4e9-44d4-8d8a-4b2d12143045-7'
for key 'agent_conversation_contexts.idx_acc_alias_user_agent'
```

用户初步判断为“每次都 INSERT，没有复用已有 alias 记录”。

## Reproduction

### Environment

- API endpoint: `POST /api/v1/shared/ask`
- auth: `X-Sharing-Key`
- input: 相同 `sharingKey + contextAlias`
- persistence: `agent_conversation_contexts` 唯一索引 `idx_acc_alias_user_agent`

### Steps

1. 首次调用共享 API，传入一个新的 `contextAlias`，成功创建上下文记录。
2. 等待该记录的 `last_accessed_at` 超过 24 小时 TTL。
3. 使用同一个 `contextAlias`、同一个 `ownerUserId`、同一个 `targetAgentId` 再次调用共享 API。
4. 观察 alias 解析与落库过程。

### Observed Result

- `ContextResolvingA2aAgent` 按 `contextAlias` 查找旧记录时，因为 TTL 过期返回空。
- 请求继续生成新的 `contextId` 并创建任务。
- `saveSessionRefFull(...)` 仅按新 `contextId` 查主键，不按 alias 复用旧记录。
- 数据库在保存时命中 `(contextAlias, userId, targetAgentId)` 唯一索引，抛出 `DataIntegrityViolationException`。
- 异常向上冒泡，最终表现为共享 API 返回 HTTP 500。

### Resolution Implemented

- 已移除 `contextId` / `contextAlias` 解析路径上的 24 小时 TTL 设计。
- `AgentContextStore` SPI 与 `AgentContextStoreImpl` 改为无 TTL 查询签名。
- `ContextResolvingA2aAgent` 现在会直接复用已存在的 alias/context 记录，不再因为跨过 24 小时边界而将旧 alias 视为不存在。

## Expected vs Actual

### Expected

- 当 `(contextAlias, userId, targetAgentId)` 已存在时，应复用既有上下文记录，或以 upsert 方式更新 `last_accessed_at` / `navigator_session_id` / `agent_session_ref`。
- 对于每天执行一次的定时任务，`contextAlias` 不应因为刚好跨过 24 小时边界而触发永久性唯一索引冲突。

### Actual Before Fix

- 当前实现确实会先按 alias 查询，但该查询带 24 小时 TTL。
- alias 一旦因 TTL 被视为不存在，保存阶段又只按 `contextId` 判断新旧。
- 结果不是“每次都 insert”，而是“alias 查空时会按新 contextId insert，最后由数据库唯一索引兜底失败”。

### Actual After Fix

- alias / context 解析不再受 24 小时 TTL 影响。
- 对于同一 `(contextAlias, userId, targetAgentId)` 的跨天定时任务，旧上下文会继续被解析并复用。
- 本次修复解决的是 TTL 导致的稳定复现故障；首次创建时的并发写入风险仍应按独立问题跟踪。

## Impact Scope

直接影响：

- 所有依赖 `POST /api/v1/shared/ask` + `contextAlias` 复用上下文的定时任务。
- 特别是按日执行、与上次访问时间接近或超过 24 小时的任务。

潜在影响：

- 任何通过 `ContextResolvingA2aAgent` 走 alias 解析的 Claude / Codex A2A 链路，都存在相同模式风险。
- 即使不是并发，也会在 TTL 过期后稳定触发。

## Test Strategy

- 主策略：`integration-test`
- 当前已补两层自动化特征测试，先稳定锁定根因路径：
- `session-module` 单元测试：模拟 alias 查空后保存时唯一索引冲突。
- `claude-worker-agent` 单元测试：模拟共享 ask/A2A 链路中任务已创建，随后保存 alias 记录时报唯一索引异常并向上冒泡。

### Automation Decision

- `required`

原因：

- 共享 ask 是定时任务和外部系统调用的核心入口。
- 该问题已在生产节奏上连续两天造成失败。
- 当前问题可通过稳定测试覆盖，不应只依赖人工回归。

## Code Inventory

- `session-module/src/main/java/com/foggy/navigator/session/controller/SharedAskController.java`
- `session-module/src/main/java/com/foggy/navigator/session/agent/ContextResolvingA2aAgent.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/AgentContextStoreImpl.java`
- `session-module/src/main/java/com/foggy/navigator/session/repository/AgentConversationContextRepository.java`
- `navigator-common/src/main/java/com/foggy/navigator/common/entity/AgentConversationContextEntity.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/AgentContextStoreImplTest.java`
- `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/adapter/ClaudeWorkerA2aAgentTest.java`

## Root Cause

修复前根因分两段：

1. `ContextResolvingA2aAgent` 对 `contextAlias` 的解析使用固定 `CONTEXT_TTL_HOURS = 24`。
2. `AgentContextStoreImpl.saveSessionRefFull(...)` 保存时只执行 `repository.findById(contextId)`，没有在 alias 已存在时回收旧记录，也没有 upsert。

因此当前故障链路是：

1. 首次运行创建 alias 记录。
2. 第二天再次运行时，如果距离上次 `last_accessed_at` 超过 24 小时，`findByAlias(...)` 返回空。
3. 代码生成新的 `contextId`，并继续调用 inner agent 创建任务。
4. 任务返回后执行 `saveSessionRefFull(...)`。
5. 因为 `saveSessionRefFull(...)` 仅按新 `contextId` 判断是否新建，数据库保存时命中 alias 唯一索引。
6. 异常未被转为业务语义，最终表现为 500。

结论：

- 用户报告中的“没有复用已有 alias 记录”方向是对的。
- 但更精确的触发条件不是“每次后续调用都 insert”，而是“alias 因 TTL 查空后，会尝试以新 contextId 写入旧 alias”。

## Fix Implemented

1. 删除 `AgentContextStore` 接口中所有 `ttlHours` 查询参数。
2. 删除 `AgentContextStoreImpl` 中基于 `last_accessed_at` 的 TTL 过滤逻辑。
3. 删除 `ContextResolvingA2aAgent` 中硬编码的 `CONTEXT_TTL_HOURS = 24`，改为无 TTL 的 context / alias 复用。
4. 同步更新 `claude-worker-agent`、`codex-worker-agent` 测试中的调用签名和行为断言。

当前策略：

- `contextAlias` 的语义回归为“稳定复用同一业务会话”。
- `last_accessed_at` 仍会更新，但不再参与是否可复用的判定。
- 若后续需要会话清理，应通过后台清理策略处理，而不是在请求路径上把 alias 判死。

## Fix Checklist

- [x] 记录来源、环境、现象、影响范围。
- [x] 确认问题可稳定复现。
- [x] 更正根因判断：不是单纯重复 INSERT，而是 TTL 查空 + save 按 contextId 新建。
- [x] 补充自动化测试还原当前失败路径。
- [x] 去掉 `contextAlias` / `contextId` 解析路径上的 24 小时 TTL 设计。
- [x] 同步调整 SPI、session-module、Claude/Codex A2A 测试。
- [ ] 增加共享 API / session-module 的集成回归，验证同 alias 跨天调用不会再返回 500。
- [ ] 在开发环境使用真实 `sharingKey + contextAlias` 回归 TMS-X3 定时任务链路。
- [ ] 作为独立问题评估首次创建场景下的 alias 并发写入风险，必要时补 alias-aware upsert。

## Verification

### Automated

已执行：

```bash
cmd /c "mvn -pl session-module -am -Dtest=AgentContextStoreImplTest -Dsurefire.failIfNoSpecifiedTests=false test"
cmd /c "mvn -pl addons/claude-worker-agent -am -Dtest=ClaudeWorkerA2aAgentTest -Dsurefire.failIfNoSpecifiedTests=false test"
cmd /c "mvn -pl addons/codex-worker-agent -am -Dtest=CodexWorkerA2aAgentTest -Dsurefire.failIfNoSpecifiedTests=false test"
```

结果：

- `AgentContextStoreImplTest` 通过，包含 `saveSessionRefFull_aliasLookupMissWithNewContextId_bubblesUniqueConstraint`
- `ClaudeWorkerA2aAgentTest` 通过，包含 `contextAlias_lookupMiss_thenSaveDuplicateAlias_bubblesUniqueConstraint`
- `CodexWorkerA2aAgentTest` 通过，确认无 TTL 签名改动未破坏 Codex A2A 上下文复用链路
- 总计 `57` 个测试通过，`0` failures，`0` errors

### Manual

待验证：

1. 在开发环境创建一个共享 ask 定时任务，固定 `contextAlias`。
2. 连续两次调用，第二次将旧记录 `last_accessed_at` 调整为超过 24 小时，或直接复用历史 alias 记录。
3. 确认修复后接口返回成功，且继续复用已有 alias 记录而不是触发 500。
4. 确认 `agent_conversation_contexts` 中同一 `(contextAlias, userId, targetAgentId)` 未出现重复新建。

## References

- `docs/version-tracker/1.0.1-SNAPSHOT/08-shared-ask-context-concurrency-risk.md`
- `docs/requirement-tracker/2026-Q2/28-a2a-context-pipeline-refactor.md`
- `docs/requirement-tracker/2026-Q2/29-followup-shared-context-unification.md`
- `/home/sa/Foggy-Navigator/logs/backend.log`
- `session-module/src/main/java/com/foggy/navigator/session/agent/ContextResolvingA2aAgent.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/AgentContextStoreImpl.java`
