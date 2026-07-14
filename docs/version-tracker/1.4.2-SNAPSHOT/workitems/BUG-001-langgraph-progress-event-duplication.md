---
type: bug
bug_source: regression-found
version: 1.4.2-SNAPSHOT
ticket: BUG-001
severity: major
status: closed
reproduction_status: confirmed
test_strategy: unit-test
automation_decision: required
owner: LangGraph Biz Worker
---

# LangGraph 实时工具进度事件重复

## 文档作用

- doc_type: bug
- intended_for: project-root-session | reviewer
- purpose: 记录 P1 clean Worker 矩阵发现的 LangGraph SSE 工具进度重复问题、最小修复与回归证据。

## Background

`2026-07-14` 在独立 clean worktree 执行 LangGraph Biz Worker 测试时，现有回归用例
`test_query_generator_streams_tool_progress_before_graph_finishes` 稳定失败。该问题不是正式验收结论，
而是 1.4.2 P1 构建基线实跑发现的 `regression-found` 缺陷。

## Reproduction

环境：

- clean worktree：提交 `e4a59269`
- Python：`3.12.3`；项目声明支持 `>=3.10`
- pytest：`9.1.1`
- 目标文件：`tools/langgraph-biz-worker/tests/test_query.py`

命令：

```bash
python -m pytest \
  tests/test_query.py::test_query_generator_streams_tool_progress_before_graph_finishes
```

稳定结果：事件类型为 `['tool_use', 'tool_use', 'result']`，断言期望 `tool_use` 只出现一次。

## Expected vs Actual

- expected：同一 `QueryEvent` 经实时 progress sink 提前发送后，即使又出现在 graph 最终 `events` 中，也只向 SSE 客户端发送一次。
- actual：第一次序列化会原地写入 `event_id`；现有去重键包含 `event_id`，导致 graph 返回同一对象时生成不同键并再次发送。

## Impact Scope

- 影响 `tools/langgraph-biz-worker` 的 `/api/v1/query` SSE 事件流。
- 客户端可能重复显示工具调用进度，或对同一 `tool_call_id` 做两次状态处理。
- 不涉及任务结果计算、数据库迁移、生产路由或外部启用。

## Test Strategy

- 复用已经稳定失败的单元测试作为回归测试，不另造重复用例。
- 先运行目标测试，再运行 `python -m pytest -m "not e2e"` 全套。
- 本机代理变量造成的 loopback `httpx` setup error 与本缺陷分开记录；复跑时显式清除大小写代理变量。

## Code Inventory

| 路径 | 作用 | 预期修改 |
|---|---|---|
| `tools/langgraph-biz-worker/src/langgraph_biz_worker/routes/query.py` | progress queue、语义去重与 SSE 序列化 | 去重键排除传输层 `event_id` |
| `tools/langgraph-biz-worker/tests/test_query.py` | 已有失败回归用例 | read-only；用例已能覆盖问题 |

## Fix Checklist

- [x] 保留失败命令和实际结果。
- [x] 确认根因是去重键混入发送时才分配的 `event_id`。
- [x] 让语义去重键排除 `event_id`，不改变事件正文或排序。
- [x] 目标回归测试通过。
- [x] LangGraph `not e2e` 测试与构建通过。
- [x] 回写 [OPT-001](./OPT-001-build-and-ci-baseline.md) 与 [进度记录](../progress.md)。

## Verification

实际执行结果：

```bash
python -m pytest \
  tests/test_query.py::test_query_generator_streams_tool_progress_before_graph_finishes
python -m pytest -m "not e2e"
python -m build
```

- 目标测试：`1 passed`，exit `0`。
- 完整测试：显式清除本机大小写代理变量后 `758 passed, 3 warnings`，exit `0`。
- package build：wheel 与 sdist 均成功生成，exit `0`。
- 首轮全套中的 31 个 setup error 已确认来自本机小写 `all_proxy` 被 `httpx` 用于 loopback；这不是本缺陷根因，也未冒充通过证据。
- 修复后语义去重忽略发送时分配的 `event_id`；事件正文、顺序和 task-scoped event id 递增规则不变。

## References

- [REQ-001](../requirements/REQ-001-platform-governance-and-legacy-cleanup.md)
- [实施计划](../implementation-plan.md)
- [OPT-001 构建与 CI 基线](./OPT-001-build-and-ci-baseline.md)
- [进度记录](../progress.md)
