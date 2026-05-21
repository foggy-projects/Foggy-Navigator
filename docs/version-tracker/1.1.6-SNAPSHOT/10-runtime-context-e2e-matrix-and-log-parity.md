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
| Root plain assistant final | `test_llm_agent_persistent_root_accepts_plain_assistant_final_answer`、`test_scripted_api_root_plain_final_commits_runtime_memory_without_retry_prompt` | root 可无 tool call 直接返回自然语言 final answer；API 级第二轮 LLM body 恢复上一轮 user / assistant；不追加伪 `human` retry instruction | root frame 仍完成 persistent turn commit，记录 `persistent_turn_completed` |
| BusinessFunction tool protocol | `test_scripted_llm_submission_log_captures_business_function_tool_protocol` | 第二次 model call 为 `system -> human -> ai -> tool`，包含 `invoke_business_function` tool_call 和业务函数 tool_result | root event JSONL 记录 `invoke_business_function -> submit_skill_result` 两个 assistant_tool_call、tool_result、`after_tool_call`、`persistent_turn_completed` |
| `AWAITING_USER` child resume | `test_scripted_llm_submission_log_captures_awaiting_child_resume_protocol` | 恢复 child 时为 `system -> human -> ai -> tool -> human`，重放上次 `submit_skill_result` tool protocol 后追加用户新回复 | first child event 记录 suspended checkpoint；second child event 初始 role 同为 `system -> user -> assistant -> tool -> user` |
| nested recoverable leaf direct resume | `test_scripted_llm_submission_log_directly_resumes_nested_interruption_leaf` | 下一条用户消息直达 deepest leaf，submission 不先进入 Root | leaf event JSONL 记录 `submit_skill_result`、`suspended`；同 task 下 Root frame 没有 runtime event |
| interrupted child resume | `test_scripted_root_skill_resumes_interrupted_child_frame` | child 恢复后必要时 Root synthesis，二者都有 submission | child event 记录 `frame_completed`；Root event 记录 synthesis 的 `submit_skill_result` 与 `persistent_turn_completed` |
| nested completion unwind | `test_scripted_nested_focus_completion_unwinds_to_parent_result` | leaf 和 parent 分别保存真实 submission；parent system 包含“刚完成的子技能提升结果” | leaf / parent event JSONL 均记录 `submit_skill_result`、`frame_completed`，parent 初始 system 同步包含子技能提升结果 |
| `AWAITING_USER` child cancel handoff | `test_scripted_awaiting_child_can_handoff_cancel_to_parent` | 恢复 child 时为 `system -> human -> ai -> tool -> human`，system 包含“子技能退出策略”；取消 direct result 不额外触发 Root LLM | second child event 记录 `handoff_to_parent`、`frame_completed`，Root active focus 被清理 |
| child handoff with Root synthesis | `test_scripted_child_handoff_can_request_root_synthesis` | child 和 Root 共享同一用户消息 cursor；child submission 记录 `handoff_to_parent`，Root submission 在同一 turn 继续 synthesis 并看到 handoff summary | child event 记录 `handoff_to_parent` / `frame_completed`；Root event 记录 `submit_skill_result` / `persistent_turn_completed` |
| recoverable ERROR/TIMEOUT leaf handoff | `test_scripted_nested_recoverable_leaf_can_handoff_to_parent_after_error` | `model_timeout` / `model_error` 后下一条用户消息直达 deepest leaf；leaf 与 parent 用同一 cursor 产生两次真实 submission | leaf event 记录 `handoff_to_parent` / `frame_completed`；parent event 记录 `submit_skill_result` / `frame_completed`；Root 不产生额外 runtime event |
| captured real smoke replay | `test_scripted_root_skill_real_smoke_fixture` | 真实 smoke trace 的 7 次 ChatModel 调用全部写入 `llm-submissions`；首轮 root/child 工具链、恢复继续、换题请求均可按 task/frame 复盘 | Root event 记录 `persistent_turn_completed`；child event 记录 `mock_get_order`、`mock_get_vehicle_status` 与 `frame_completed` |

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

## Live Smoke 对账工具

已新增：

```text
tools/langgraph-biz-worker/scripts/live_upstream_runtime_context_smoke.py
```

该脚本提供两类入口：

1. `openapi-live`：通过 Navigator OpenAPI 发送两轮同 `contextId` 请求，并抓取 task/session messages。
2. `validate-only`：只校验既有 BizWorker session 目录和可选的上游 messages JSON。

校验范围包括：

- `session.json.rootFrameId` 是否能直接定位 `frames/<rootFrameId>.json`。
- `logs/llm-submissions/*.json` 是否存在并包含真实 `body.messages`。
- `logs/runtime-message-events/*.jsonl` 是否存在。
- 附件引用是否能在 LLM body、task messages 或 session messages 中复盘。
- 重开会话消息是否泄漏 raw tool chatter。
- LLM body 是否仍暴露内部兼容名 `system.root`。
- recoverable 验收时是否存在 `suspended` / `frame_completed` checkpoint，并能找到指定 tool call。

详细命令见 [11-live-upstream-runtime-context-smoke.md](./11-live-upstream-runtime-context-smoke.md)。

## 当前测试记录

```powershell
cd tools/langgraph-biz-worker
.\.venv\Scripts\ruff.exe check tests/test_e2e_scripted_tool_call_streaming.py
```

结果：

```text
All checks passed!
```

2026-05-21 补充 root / child 完成契约后：

```powershell
cd tools/langgraph-biz-worker
.\.venv\Scripts\ruff.exe check `
  src/langgraph_biz_worker/runtime/llm_agent_prompts.py `
  src/langgraph_biz_worker/runtime/llm_skill_agent.py `
  tests/test_llm_skill_agent.py `
  tests/test_e2e_scripted_tool_call_streaming.py
```

结果：

```text
All checks passed!
```

```powershell
cd tools/langgraph-biz-worker
.\.venv\Scripts\python.exe -m pytest `
  tests/test_llm_skill_agent.py `
  tests/test_llm_message_builder.py `
  tests/test_llm_submission_log.py -q
```

结果：

```text
62 passed
```

```powershell
cd tools/langgraph-biz-worker
.\.venv\Scripts\python.exe -m pytest -q
```

结果：

```text
608 passed, 6 skipped, 11 warnings
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
27 passed, 3 warnings
```

```powershell
cd tools/langgraph-biz-worker
.\.venv\Scripts\python.exe -m pytest `
  tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_awaiting_child_can_handoff_cancel_to_parent -q
```

结果：

```text
1 passed, 3 warnings
```

```powershell
cd tools/langgraph-biz-worker
.\.venv\Scripts\python.exe -m pytest `
  tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_child_handoff_can_request_root_synthesis -q
```

结果：

```text
1 passed, 3 warnings
```

```powershell
cd tools/langgraph-biz-worker
.\.venv\Scripts\python.exe -m pytest `
  tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_nested_recoverable_leaf_can_handoff_to_parent_after_error -q
```

结果：

```text
2 passed, 3 warnings
```

```powershell
cd tools/langgraph-biz-worker
.\.venv\Scripts\python.exe -m pytest `
  tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_root_skill_real_smoke_fixture -q
```

结果：

```text
1 passed, 3 warnings
```

```powershell
cd tools/langgraph-biz-worker
.\.venv\Scripts\python.exe -m pytest -q
```

结果：

```text
602 passed, 6 skipped, 11 warnings
```

```powershell
cd tools/langgraph-biz-worker
.\.venv\Scripts\ruff.exe check scripts/live_upstream_runtime_context_smoke.py tests/test_live_upstream_runtime_context_smoke.py
```

结果：

```text
All checks passed!
```

```powershell
cd tools/langgraph-biz-worker
.\.venv\Scripts\python.exe -m pytest tests/test_live_upstream_runtime_context_smoke.py -q
```

结果：

```text
4 passed
```

```powershell
cd tools/langgraph-biz-worker
.\.venv\Scripts\python.exe -m pytest -q
```

结果：

```text
606 passed, 6 skipped, 11 warnings
```

2026-05-21 补充 API 级 Root 普通自然语言完成 E2E 与本机 HTTP smoke：

```powershell
cd tools/langgraph-biz-worker
$env:PYTHONPATH='src'
.\.venv\Scripts\python.exe -m pytest `
  tests/test_e2e_scripted_tool_call_streaming.py::test_scripted_api_root_plain_final_commits_runtime_memory_without_retry_prompt -q
```

结果：

```text
1 passed, 3 warnings
```

```powershell
cd tools/langgraph-biz-worker
$env:PYTHONPATH='src'
.\.venv\Scripts\python.exe -m pytest `
  tests/test_llm_skill_agent.py `
  tests/test_llm_message_builder.py `
  tests/test_llm_submission_log.py `
  tests/test_e2e_scripted_tool_call_streaming.py -q
```

结果：

```text
90 passed, 3 warnings
```

```powershell
cd tools/langgraph-biz-worker
.\.venv\Scripts\ruff.exe check `
  src/langgraph_biz_worker/runtime/llm_agent_prompts.py `
  src/langgraph_biz_worker/runtime/llm_skill_agent.py `
  tests/test_llm_skill_agent.py `
  tests/test_e2e_scripted_tool_call_streaming.py
```

结果：

```text
All checks passed!
```

```powershell
cd tools/langgraph-biz-worker
.\.venv\Scripts\python.exe -m pytest -q
```

结果：

```text
609 passed, 6 skipped, 11 warnings
```

## 本机 HTTP Smoke 证据

2026-05-21 已用本机 BizWorker HTTP API + mock LLM 走通 Root 普通多轮和 child/frame 工具链：

| 场景 | contextId | 关键证据 |
| --- | --- | --- |
| Root 普通多轮 | `bctx_20260521_23_2372853b5366440cb39d3268e3ec1eab` | `llm-submissions` role 序列为 `["system","human"]`、`["system","human","ai","human"]`；第二轮结果为 `root second turn context ok` |
| child/frame 工具链 | `bctx_20260521_2a_2a7d5bd415dd47adb18c7cf7305a6763` | `llm-submissions` role 序列为 `["system","human"]`、`["system","human"]`、`["system","human","ai","tool"]`；runtime events / tool audit 记录 `invoke_business_skill=1`、`submit_skill_result=2` |

另尝试真实 qwen3.5-plus provider smoke，session `bctx_20260521_b1_b1fc1510f49742b5ad541c8903ce100c` 在 provider 请求阶段触发 `LLM_REQUEST_TIMEOUT`，记录为外部 provider 超时阻塞，不作为本轮验收通过证据。

## 后续缺口

1. 本机 HTTP BizWorker + mock LLM smoke 已通过；真实 Navigator OpenAPI / TMS 前端联调仍需手动用可用地址、ClientApp 凭证和附件 URL 跑一次 live 验收，并把 `summary.json` 作为验收证据。
2. recoverable ERROR/TIMEOUT 已有 scripted E2E 和 validate-only 对账入口；真实 provider timeout / error 场景仍建议在手动验收中补一条证据。
