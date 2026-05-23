# BizWorker Upstream Execution Policy Phase 3D Progress

## 文档作用

- doc_type: execution-progress
- version: 1.1.4-SNAPSHOT
- status: implemented
- date: 2026-05-19
- intended_for: BizWorker maintainer | Python integration developer | Navigator reviewer
- purpose: 记录 Phase 3D 上游执行策略约束的实现范围、测试证据与剩余边界

## 实现摘要

Phase 3D 已完成最小闭环：

- 新增 `ExecutionPolicy`，统一解析 `workdir`、`allowed_dirs`、`allowed_tools` 及常见 alias。
- `workdir` 会被校验必须落在 `allowed_dirs` 内；未显式传 `allowed_dirs` 时默认收敛为 `[workdir]`。
- LLM 可见 provider/built-in tool schema 会按 `allowed_tools` 过滤。
- 实际 tool call 执行前会做二次校验，模型伪造未授权工具调用时返回 `TOOL_NOT_AUTHORIZED`。
- Provider tool context 注入标准化 `workdir`、`allowed_dirs`、`allowed_tools`、`execution_policy`。
- Standalone `SkillAgent` 会从可见 skill input 中剥离 execution policy，避免把运行治理字段交给 LLM 负责。
- `/api/v1/query` 会将 visible context 中的 policy 复制到 hidden runtime context，并从 root graph 可见 context 中剥离。

## 代码变更

| 文件 | 变更 |
| --- | --- |
| `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/execution_policy.py` | 新增执行策略解析、路径校验、context copy/strip helper |
| `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_tool_schemas.py` | 支持按 `enabled_tool_names` 过滤 LLM 绑定工具 |
| `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_agent.py` | 接入 `ExecutionPolicy`，过滤 provider specs，执行前二次校验，注入 provider context |
| `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/tool_provider.py` | 支持 reserved `tool_context` 参数，不暴露给 LLM schema |
| `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/skill_agent.py` | standalone ask 校验 policy，并从 visible skill input 剥离 policy |
| `tools/langgraph-biz-worker/src/langgraph_biz_worker/routes/query.py` | 将 context 中的 policy 复制到 runtime context，并剥离 visible context |
| `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py` | root graph 直调路径同样剥离 visible policy，并补 runtime policy copy |
| `tools/langgraph-biz-worker/docs/standalone.md` | 新增 execution policy 使用说明 |
| `tools/langgraph-biz-worker/tests/test_execution_policy.py` | 新增 policy 解析、路径和 helper 测试 |
| `tools/langgraph-biz-worker/tests/test_tool_provider.py` | 补 `tool_context` 注入测试 |
| `tools/langgraph-biz-worker/tests/test_skill_agent_facade.py` | 补工具授权过滤、伪造调用拒绝、provider context 测试 |
| `tools/langgraph-biz-worker/tests/test_standalone_service.py` | 补 standalone workdir 越权 400 测试 |

## Contract Notes

推荐上游通过 hidden runtime context 传入：

```json
{
  "execution_policy": {
    "workdir": "/project/demo",
    "allowed_dirs": ["/project/demo"],
    "allowed_tools": ["query_order"]
  }
}
```

Standalone service 仍通过 `context.execution_policy` 传入，因为 `POST /api/v1/ask` 当前只有 `context` 载体。Worker 会将该 policy 用于运行治理，并从 visible skill input 剥离。

`submit_skill_result`、`resume_recoverable_child_skill`、`shelve_interrupted_frame` 属于 runtime control tools，保持可用；业务/provider/file/resource 工具受 `allowed_tools` 控制。

## 测试证据

目标测试：

```powershell
cd tools/langgraph-biz-worker
.\.venv\Scripts\python.exe -m pytest `
  tests/test_execution_policy.py `
  tests/test_tool_provider.py `
  tests/test_skill_agent_facade.py `
  tests/test_standalone_service.py
```

结果：

```text
18 passed
```

全量兼容测试：

```powershell
cd tools/langgraph-biz-worker
.\.venv\Scripts\python.exe -m pytest
```

结果：

```text
513 passed, 6 skipped
```

## 自检

- requirement alignment: 已按确认方向移除热加载/sandbox 优先级，聚焦上游执行策略。
- authorization: 工具暴露和工具执行均做授权过滤。
- path boundary: `workdir` 与 `allowed_dirs` 通过 canonical path 校验。
- prompt hygiene: execution policy 不作为普通 skill input 暴露给 LLM。
- compatibility: BizWorker 全量测试通过。
- Java scope: 未修改 Navigator Java 控制面、ClientApp、task-scoped token 或 TMS 契约。

## 剩余边界

1. 本阶段是应用层策略校验，不是 OS sandbox。
2. Provider 函数内部如直接访问文件系统，应使用传入的 `tool_context` 或后续公共 guard helper；Worker 不拦截任意 Python 代码内部的文件访问。
3. 若上游需要更细粒度权限，后续可扩展为 per-tool policy 或 read/write path policy。
