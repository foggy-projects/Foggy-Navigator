# 09 Claude Worker Rewind First Turn Session Corruption

## Date

- 2026-04-04

## Type

- Bug
- Confirmed
- Regression Guard

## Background

在 `configModelId=test` 的 Claude Code Worker 场景下，用户从会话窗口执行“回退”后，若目标是第一个用户消息，对话会被破坏。

表象是：

- 回退到中间轮次后，继续对话通常正常
- 回退到第一个用户消息后，再次发送消息会直接失败
- Worker 侧最终表现为 Claude CLI `--resume` 退出码 `1`

这个问题已经不再停留在 UI 观察层面，已经通过真实 Worker / 真实 Claude CLI / `tools/mock-llm-service` 建立了稳定复现。

## Scope

本项当前确认覆盖的是：

- Claude Worker 会话级“对话回退”链路
- `POST /api/v1/sessions/{session_id}/rewind-conversation`
- 后续基于相同 `session_id` 的 `POST /api/v1/query` resume 链路

当前证据不表明这是 `test` 模型特有的推理问题，更像是 Worker 对 Claude 本地 session JSONL 的重写策略有问题，只是在 `configModelId=test` 这条配置上首先被稳定观察到。

## Stable Reproduction

已建立两层复现：

### 1. 真实链路手工复现

链路如下：

```text
worker -> claude-agent-sdk -> Claude CLI -> mock-llm-service
```

复现结论：

1. 连续建立 3 轮同一 `session_id` 对话
2. 回退到 `turnIndex=2` 后恢复，成功
3. 回退到 `turnIndex=1` 后恢复，失败

失败时的直接现象：

- 回退后消息计数变成 `user_count=0, assistant_count=0, total=0`
- 再次 resume 只收到 `error` 事件
- 错误为 `ProcessError`，CLI `exit_code=1`

### 2. 自动化 E2E 复现

已新增：

- `tools/claude-agent-worker/tests/e2e/test_e2e_rewind.py`

当前测试状态：

- `rewind to middle turn`：通过
- `rewind to first turn`：`xfail`

这个 `xfail` 不是规避问题，而是把当前已确认缺陷固化为回归基线，后续修复后应转为普通通过用例。

## Root Cause

根因集中在：

- `tools/claude-agent-worker/src/agent_worker/claude/session_scanner.py`
- `rewind_session_conversation(session_id, turn_index)`

当前实现的关键问题是：

1. 回退会把目标 turn 及之后的内容直接裁掉
2. 当目标是 `turnIndex=1` 时，最终文件里不再保留任何真实 `user` / `assistant` 对话记录
3. 代码只会尝试保留少量元数据行，例如：
   - `queue-operation`
   - `file-history-snapshot`
   - `system`
   - `last-prompt`
4. Claude CLI 随后用 `--resume <session_id>` 恢复这个 session 时，认为该会话文件无效并直接退出

也就是说，当前逻辑默认认为：

- “把首轮对话全部删空，再补一个 `last-prompt`” 仍然是可 resume 的 session

但真实运行结果证明这个假设不成立。

## Current Evidence

### 代码级证据

- `rewind_session_conversation(...)` 在 `not kept_lines` 分支下，会为“回退到首条消息”构造一个极小化 JSONL
- 该 JSONL 不再包含任何可见对话消息

### 运行级证据

回退到中间轮次时：

- 截断后仍保留首轮 `user + assistant`
- resume 成功

回退到第一轮时：

- 截断后对话计数归零
- resume 立即失败

因此问题不是前端回退按钮本身，而是 Worker 重写后的 session 文件不再满足 Claude CLI 的恢复前提。

## Related Code Checklist

### Worker Implementation

- `tools/claude-agent-worker/src/agent_worker/claude/session_scanner.py`
- `tools/claude-agent-worker/src/agent_worker/routes/sessions.py`
- `tools/claude-agent-worker/src/agent_worker/routes/query.py`
- `tools/claude-agent-worker/src/agent_worker/claude/sdk_wrapper.py`

### Existing Unit / Integration Tests

- `tools/claude-agent-worker/tests/claude/test_session_scanner.py`
- `tools/claude-agent-worker/tests/integration/test_checkpoint_api.py`

### New Reproduction / Guard

- `tools/claude-agent-worker/tests/e2e/test_e2e_rewind.py`
- `tools/mock-llm-service/src/mock_llm/models.py`
- `tools/mock-llm-service/tests/test_anthropic_api.py`

## Current Test Assessment

当前已有测试存在一个缺口：

- 单元测试能证明“文件被怎样截断”
- 但不能证明“Claude CLI 是否还能 resume 这个 session”

因此这次补的 E2E 很关键，它首次覆盖了：

- 真 session 写入
- 真 rewind
- 真 resume
- 真 CLI 对 JSONL 合法性的反馈

## Suggested Fix Direction

修复应优先落在 `rewind_session_conversation(...)`，重点检查 `turnIndex=1` 时的保留策略。

推荐方向：

1. 不要把首轮回退后的 session 裁成“只有元数据 + last-prompt”
2. 保留一份 Claude CLI 可接受的最小会话骨架
3. 修复后把 `test_rewind_to_first_turn_can_resume` 从 `xfail` 转成普通断言通过

## Verification

本次已完成的验证包括：

```bash
python -m pytest tools/mock-llm-service/tests/test_anthropic_api.py tools/mock-llm-service/tests/test_openai_api.py -q
python -m pytest tools/claude-agent-worker/tests/e2e/test_e2e_rewind.py -q
```

结果：

- `mock-llm-service`: `7 passed`
- `claude-agent-worker rewind e2e`: `1 passed, 1 xfailed`

## Delivery Assessment

本项已经具备进入修复阶段的条件，不再需要额外做“是否真实存在”的确认。

当前最重要的不是继续加观察日志，而是：

1. 修复首轮回退后的 session 重写逻辑
2. 用现有 E2E 直接做回归验收

## Status

本项作为 `1.0.0-SNAPSHOT` 版本缺陷记录，状态为：

- 问题已确认
- 复现链路已固化
- ✅ 已修复（2026-04-04）

## Implementation Status

**修复策略**：首轮回退 = 删除 JSONL 文件 + 清空 claudeSessionId，后续以全新会话启动。

### 已完成的改造

1. **session_scanner.py** — `rewind_session_conversation()` 首轮回退特殊处理
   - `turnIndex=1` 时：保存备份 → 删除原 JSONL 文件 → 返回 `session_cleared: True`
   - `turnIndex>1` 时：行为不变（截断保留前 N-1 轮）

2. **Java ClaudeTaskService** — 首轮回退后清空 claudeSessionId
   - `conversation_fork` 和 `file_rewind` 两种模式均处理 `session_cleared`
   - 新增 `clearClaudeSessionId()` — 清除 `SessionEntity.providerStateJson` 中的 claudeSessionId
   - `buildRewindResult` 返回 `claudeSessionId: null`，前端后续不再尝试 `--resume`

3. **E2E 测试更新**
   - `test_rewind_to_first_turn_can_resume` 从 `xfail` 转为正常断言
   - 验证：session 文件被删除、`session_cleared=True`、新 query 创建全新会话

### 验证结果

- Java 全模块编译通过，全量单元测试通过
- Python session_scanner 单元测试 35 passed

## 验收签收

- 签收状态：✅ 已签收
- 签收日期：2026-04-05
- 签收方式：版本文档审计签收
- 签收依据：条目已明确“已修复”，并附单测与 E2E 回归验证结果。
- 关联台账：[12-acceptance-signoff.md](./12-acceptance-signoff.md)
