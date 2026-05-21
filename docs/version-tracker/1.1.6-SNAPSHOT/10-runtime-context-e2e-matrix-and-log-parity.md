# Runtime Context E2E Matrix And Log Parity

## 文档作用

- doc_type: test-design
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 固化 BizWorker runtime context 真实场景 E2E 覆盖矩阵，并要求每个关键场景同时校验 `llm-submissions` 与 `runtime-message-events`

版本：`1.1.6-SNAPSHOT`
状态：实施中
类型：runtime context governance / E2E test matrix

## 核心原则

BizWorker runtime context 的验收不能只看最终 SSE result。关键路径必须同时回答三个问题：

1. 本轮真实提交给 LLM 的 `messages` 是否符合 [09-llm-submission-message-contract.md](./09-llm-submission-message-contract.md)。
2. 未完成 frame 的 provider protocol messages 是否已写入 `logs/runtime-message-events/*.jsonl`，并可用于恢复。
3. 完成后的下一轮普通 semantic conversation 是否只保留受控的 user / assistant visible messages，而不是把 raw tool trace 泄漏进普通上下文。

因此 scripted E2E 的最低断言不再只是“业务结果正确”，还必须覆盖：

- `logs/llm-submissions/*.json` 的 role sequence、tool call、tool result、system governance context。
- `logs/runtime-message-events/*.jsonl` 的 initial messages、assistant response、assistant_tool_call、tool_result、checkpoint。
- 二者在同一 task / frame 上的关键 messages 语义一致。

## 当前 E2E 矩阵

| 场景 | 测试 | LLM submission 断言 | runtime event 断言 |
| --- | --- | --- | --- |
| 普通多轮 Root turn | `test_scripted_llm_submission_log_matches_root_recent_conversation` | 第二轮为 `system -> human -> ai -> human`，历史 user / assistant 以独立 role messages 注入 | 第二轮 root event 初始 role 同为 `system -> user -> assistant -> user`，并记录 `persistent_turn_completed` |
| BusinessFunction tool protocol | `test_scripted_llm_submission_log_captures_business_function_tool_protocol` | 第二次 model call 为 `system -> human -> ai -> tool`，包含 `invoke_business_function` tool_call 和业务函数 tool_result | root event JSONL 记录 `invoke_business_function -> submit_skill_result` 两个 assistant_tool_call、tool_result、`after_tool_call`、`persistent_turn_completed` |
| `AWAITING_USER` child resume | `test_scripted_llm_submission_log_captures_awaiting_child_resume_protocol` | 恢复 child 时为 `system -> human -> ai -> tool -> human`，重放上次 `submit_skill_result` tool protocol 后追加用户新回复 | first child event 记录 suspended checkpoint；second child event 初始 role 同为 `system -> user -> assistant -> tool -> user` |
| nested recoverable leaf direct resume | `test_scripted_llm_submission_log_directly_resumes_nested_interruption_leaf` | 下一条用户消息直达 deepest leaf，submission 不先进入 Root | leaf event JSONL 记录 `submit_skill_result`、`suspended`；同 task 下 Root frame 没有 runtime event |
| interrupted child resume | `test_scripted_root_skill_resumes_interrupted_child_frame` | child 恢复后必要时 Root synthesis，二者都有 submission | child event 记录 `frame_completed`；Root event 记录 synthesis 的 `submit_skill_result` 与 `persistent_turn_completed` |
| nested completion unwind | `test_scripted_nested_focus_completion_unwinds_to_parent_result` | leaf 和 parent 分别保存真实 submission；parent system 包含“刚完成的子技能提升结果” | leaf / parent event JSONL 均记录 `submit_skill_result`、`frame_completed`，parent 初始 system 同步包含子技能提升结果 |

## 断言边界

### 普通完成后的下一轮

普通下一轮只应看到：

```text
system
历史 user
历史 assistant
当前 user
```

不应重放 raw tool call / tool result。raw protocol 留在 execution evidence：

```text
logs/runtime-message-events/*.jsonl
logs/llm-submissions/*.json
frame report / journal / tool log
```

### 未完成 frame 恢复

`AWAITING_USER`、TIMEOUT、ERROR、用户 stop/cancel 后的恢复，需要从同一套 `runtime-message-events` 恢复 provider protocol messages：

```text
system
已恢复 user
已恢复 assistant tool_call
已恢复 tool_result
当前 user
```

恢复时不能只传孤立 tool_result，也不能丢掉 assistant tool_call。

### nested completion unwind

deepest leaf 完成后，parent continuation 不是普通下一轮 semantic conversation。它属于当前 frame stack 内部继续执行，因此 parent system 可以看到：

```text
刚完成的子技能提升结果:
...
```

该信息只用于当前 parent 续跑，不写入下一轮普通 runtime-visible conversation 的 raw trace。

## 已补充的测试工具

`tools/langgraph-biz-worker/tests/test_e2e_scripted_tool_call_streaming.py` 新增测试 helper：

- `_runtime_message_events(context_id, task_id=None, frame_id=None)`
- `_runtime_initial_roles(events)`
- `_runtime_tool_call_names(events)`
- `_runtime_checkpoints(events)`

这些 helper 用于把 `runtime-message-events` 和 `llm-submissions` 放在同一测试中对账。

## 当前测试记录

```powershell
cd tools/langgraph-biz-worker
.\.venv\Scripts\ruff.exe check tests/test_e2e_scripted_tool_call_streaming.py
```

结果：

```text
All checks passed!
```

```powershell
cd tools/langgraph-biz-worker
.\.venv\Scripts\python.exe -m pytest `
  tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_llm_submission_log_matches_root_recent_conversation `
  tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_llm_submission_log_captures_awaiting_child_resume_protocol `
  tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_llm_submission_log_captures_business_function_tool_protocol `
  tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_nested_focus_completion_unwinds_to_parent_result -q
```

结果：

```text
4 passed, 3 warnings
```

```powershell
cd tools/langgraph-biz-worker
.\.venv\Scripts\python.exe -m pytest `
  tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_llm_submission_log_directly_resumes_nested_interruption_leaf `
  tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_root_skill_resumes_interrupted_child_frame -q
```

结果：

```text
2 passed, 3 warnings
```

```powershell
cd tools/langgraph-biz-worker
.\.venv\Scripts\python.exe -m pytest tests/test_e2e_scripted_tool_call_streaming.py -q
```

结果：

```text
23 passed, 3 warnings
```

```powershell
cd tools/langgraph-biz-worker
.\.venv\Scripts\python.exe -m pytest -q
```

结果：

```text
597 passed, 6 skipped, 11 warnings
```

## 后续缺口

1. 引入真实上游联调 smoke 脚本，覆盖 TMS 工单、附件、简洁模式重开会话。
2. 设计并实现 frame 退出/交还 parent 控制工具后，补“用户说取消/换题/回主对话”的 E2E。
