---
title: E2E scripted tool-call streaming bug
version: 1.1.3-SNAPSHOT
status: fixed
created: 2026-05-14
updated: 2026-05-14
owner: Navigator
related_issue: https://github.com/foggy-projects/Foggy-Navigator/issues/111
---

# E2E Scripted Tool-Call Streaming Bug

## 背景

TMS deterministic E2E 无工具 smoke 已可命中 `navigator-e2e-scripted`，但 scripted tool-call loop 首轮命中 mock LLM 后没有进入第二轮。mock debug request 显示首轮 `matched=true` 且 `toolCalls=true`，Worker 侧日志出现：

```text
Agentic routing failed: peer closed connection without sending complete message body (incomplete chunked read)
```

## 复现

TMS 脚本使用 LangChain 风格 tool call：

```json
{
  "response": {
    "tool_calls": [
      {
        "name": "invoke_business_skill",
        "args": {
          "skill_id": "foggy-query-agent",
          "instruction": "query and continue next:<traceId>:002"
        }
      }
    ]
  }
}
```

mock LLM streaming 分支原先只识别 OpenAI 风格 `function.name/function.arguments`。当上游脚本传入 `name/args` 时，mock service 已经记录 debug 命中，但 SSE body 生成阶段抛异常，导致 OpenAI client 看到 incomplete chunked read。

## 修复

1. 新增 tool call 归一化入口，统一支持：
   - LangChain 风格 `name + args`
   - OpenAI 风格 `function.name + function.arguments`
2. streaming 和 non-streaming OpenAI Chat Completions 响应共用归一化逻辑。
3. `response.content` 默认空字符串，tool-only turn 可省略。
4. streaming debug summary 从布尔值改为具体 tool name 列表，便于上游确认首轮命中。

## 验证

新增单测覆盖：

- 省略 `response.content` 的 tool-only scripted response 可注册。
- `stream=true` 时可消费 `name/args` 形式的 tool call。
- SSE 中包含 `invoke_business_skill`、拼接后的 arguments、`finish_reason=tool_calls` 和 `data: [DONE]`。
- debug requests 中 `responseSummary.toolCalls=["invoke_business_skill"]`。

新增真实集成测试覆盖：

- 启动真实 `mock-llm-service` ASGI 应用并通过本地 HTTP 端口访问。
- Worker `/api/v1/query` 使用真实 `ChatOpenAI` + OpenAI-compatible streaming 调用 mock LLM。
- 首轮 cursor `001` 返回 `invoke_business_skill`，Worker 创建 Skill Frame。
- `LlmSkillAgent` 第二轮命中 cursor `002` 并通过 `submit_skill_result` 完成 Frame。
- mock debug requests 至少包含 `001` 与 `002` 两轮记录。

## 追加修复：根路由工具噪声事件

TMS 后续验证发现 scripted tool-call loop 已完成，但 messages 中仍出现一条非阻塞噪声事件：

```text
role=tool type=TOOL_RESULT content={"ok": false, "error": "Unknown tool: invoke_business_skill"}
```

判断：`invoke_business_skill` 是 Root Graph 的路由委派工具，不是 Skill Agent 内部可执行的业务工具。这条事件不应对上游用户可见；更合适的修复点是 root routing 到 skill execution 的交接阶段，而不是只在 messages API 层过滤。

根因：Root Graph 已经从首轮 tool call 中提取 `skill_instruction`，但进入 `LlmSkillAgent` 时仍使用原始用户 prompt。deterministic E2E 的原始 prompt 中包含首轮 cursor `next:<traceId>:001`，导致 Skill Agent 再次命中首轮脚本，把 `invoke_business_skill` 当普通工具执行并产生 `Unknown tool`，随后才进入第二轮 `submit_skill_result`。

修复：

- `run_skill` 调用 `LlmSkillAgent` 时优先使用 `context.skill_instruction` 作为 Skill Agent prompt。
- 仅当没有有效 `skill_instruction` 时回退原始用户 prompt。
- attachment context 仍追加到最终 Skill Agent prompt，保持附件能力不变。

回归断言：

- Worker 事件流不再出现 `tool_result` 噪声。
- 不再出现 `Unknown tool: invoke_business_skill`。
- mock debug request cursor 严格为 `001 -> 002`，不重复首轮 cursor。

验证命令：

```powershell
cd tools/mock-llm-service
$env:PYTHONPATH='src'
python -m pytest tests/test_openai_api.py -q
```

```powershell
cd tools/langgraph-biz-worker
$env:PYTHONPATH='src'
.\.venv\Scripts\python.exe -m pytest tests/test_e2e_scripted_tool_call_streaming.py -q
```

```powershell
cd tools/langgraph-biz-worker
$env:PYTHONPATH='src'
.\.venv\Scripts\python.exe -m pytest tests/test_e2e_scripted_tool_call_streaming.py tests/test_llm_skill_agent.py -q
```
