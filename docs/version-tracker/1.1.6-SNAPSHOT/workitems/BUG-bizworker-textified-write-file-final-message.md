---
type: bug
bug_source: user-report
version: 1.1.6-SNAPSHOT
ticket: BUG-bizworker-textified-write-file-final-message
severity: major
status: ready-for-verification
reproduction_status: confirmed
test_strategy: unit-test
automation_decision: required
owner: langgraph-biz-worker-runtime
---

# BUG 工作项

## 背景

SIM rehearsal `v0.0.706` 报告：一个 BizWorker 任务在同一轮内写入多个长文件时，任务最终显示为 `COMPLETED`，但文件系统中只落盘了部分目标文件。后续最终消息中暴露出类似 `call:default_api:write_file{...}` 的文本。将任务拆成单文件 continuation 后，单文件写入可以稳定真实落盘。

## 复现方式

这个 runtime 分支不依赖真实模型即可复现：

1. 启动一个开启 account file tools 的 persistent root frame。
2. 让 fake model 先发出一个结构化 `write_file` tool call。
3. 让下一次模型响应只包含文本化的 `call:default_api:write_file{...}` 字符串，不包含结构化 `tool_calls`。

## 期望结果与实际结果

期望结果：runtime 不应把文本化的文件变更调用当作成功最终答复；它应该暴露一个可恢复的 model/tool-call 协议错误，让缺失的写入可以重试。

实际结果：persistent root 当前会把任何没有 `tool_calls` 的非空 assistant 文本接受为 `completion_mode=assistant_message`，这会让任务看起来已经完成，但后续本应执行的写文件操作并没有被 runtime 执行。

## 影响范围

影响依赖模型驱动 `write_file` / `patch_file` 写入多个 sidecar 文件或长结构化文件的 BizWorker root turn。

这个问题不表示底层 `AccountFileTools.write_file` 存在“只写入部分文件”的低层 bug；它是一个模型输出与 runtime 协议验收之间的 bug。

## 测试策略

已新增单元测试：

- `tools/langgraph-biz-worker/tests/test_llm_skill_agent.py::test_llm_agent_persistent_root_rejects_textified_write_file_final_message`

该测试先验证结构化 `write_file` 能真实落盘，再验证第二个文本化写入不会落盘，并且不能被 runtime 当作普通 assistant 最终答复接受。

## 代码清单

- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_agent.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_agent_prompts.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_tool_call_codec.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/account_file_tools.py`

## 修复清单

- [x] 检测包含文本化 mutation tool call 的 assistant 最终文本，例如 `call:default_api:write_file{...}`。
- [x] 拒绝或中断这类 turn，而不是记录为 `completion_mode=assistant_message`。
- [x] 保留正常纯文本最终答复能力。
- [x] 保持结构化 `tool_calls` 行为不变。
- [x] 在系统提示和 delegated workspace 文件契约中补充“写文件必须通过结构化 file tool，不要输出文本化调用”的约束。
- [x] 确认新增回归测试通过。

## 验证

回归复现已经建立，并已修复。

复现测试：

```powershell
cd tools/langgraph-biz-worker
$env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest tests/test_llm_skill_agent.py::test_llm_agent_persistent_root_rejects_textified_write_file_final_message -q
```

当前结果：通过。第一个结构化 `write_file` 会落盘到 delegated workspace；第二个文本化写入不会落盘；runtime 返回 `reason=textified_tool_call` / `TEXTIFIED_TOOL_CALL`，并且不会记录为 `completion_mode=assistant_message`。

相关回归：

```powershell
$env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest tests/test_llm_skill_agent.py -q
$env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest tests/test_llm_message_builder.py tests/test_llm_tool_schemas.py tests/test_llm_tool_dispatcher.py tests/test_account_file_tools.py -q
```

结果：

- `tests/test_llm_skill_agent.py`: `73 passed`
- message/tool/file-tools 相关子集：`62 passed, 2 skipped`

全量 `tests -q` 曾在 120 秒超时，未得到失败详情；该目录包含长 E2E / server 类用例，本次以相关稳定子集作为修复验证证据。

## 参考

- 来源 issue：`D:/foggy-projects/foggy-data-mcp/foggy-world-sim/docs/versions/v0.0.706/tms-real-rehearsal-m0/navi-issues/bizworker-multifile-write-final-message-20260624.md`
