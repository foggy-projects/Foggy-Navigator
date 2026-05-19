# BizWorker Standalone Integration

## Purpose

Use BizWorker as a standalone Python Skill Agent, either embedded in a Python process or exposed as a small HTTP service.

This mode does not require Navigator Java, ClientApp provisioning, or task-scoped tokens. Enterprise flows can still use the existing Navigator integration path.

## Skill Layout

```text
skills/
  order-assistant/
    SKILL.md
```

`skill_name` is the skill folder name. For example, `skills/order-assistant/SKILL.md` is called with `skill_name = "order-assistant"`.

Minimal `SKILL.md`:

```markdown
---
name: order-assistant
description: Order helper.
tools:
  - query_order
---

Use `query_order` when order details are needed, then submit a concise result.
```

## Embedded Mode

```python
from langgraph_biz_worker import SkillAgent
from langgraph_biz_worker.tools import LocalPythonToolProvider

provider = LocalPythonToolProvider()

@provider.tool(description="Fetch one order")
def query_order(order_id: str) -> dict:
    return {"order_id": order_id, "status": "OPEN"}

agent = SkillAgent(
    "skills",
    data_root=".runtime/data",
    tool_provider=provider,
    model_provider=create_model(),
)

result = await agent.ask(
    skill_name="order-assistant",
    message="Check order O-1001",
    context={"order_id": "O-1001"},
)
```

See `samples/standalone-project/run_embedded.py`.

## Service Mode

PowerShell example from `tools/langgraph-biz-worker/samples/standalone-project`:

```powershell
$env:PYTHONPATH = "..\..\src;."
$env:BIZ_WORKER_STANDALONE_SKILLS_ROOT = "$PWD\skills"
$env:BIZ_WORKER_STANDALONE_DATA_ROOT = "$PWD\.runtime\data"
$env:BIZ_WORKER_STANDALONE_TOOL_MODULES = "order_tools"
$env:BIZ_WORKER_STANDALONE_MODEL_PROVIDER = "order_model:create_model"
..\..\.venv\Scripts\python.exe -m uvicorn langgraph_biz_worker.main:app --host 127.0.0.1 --port 3061
```

Ask request:

```http
POST http://127.0.0.1:3061/api/v1/ask
Content-Type: application/json

{
  "skill_name": "order-assistant",
  "message": "Check order O-1001",
  "context": {
    "order_id": "O-1001"
  }
}
```

`prompt` is accepted as an alias for `message`.

## Execution Policy

Standalone callers can constrain each ask with an execution policy. This is an application-level guard, not OS sandboxing or plugin hot reload.

```json
{
  "skill_name": "order-assistant",
  "message": "Check order O-1001",
  "context": {
    "order_id": "O-1001",
    "execution_policy": {
      "workdir": "C:/projects/demo",
      "allowed_dirs": ["C:/projects/demo"],
      "allowed_tools": ["query_order"]
    }
  }
}
```

Rules:

- `workdir` must be inside `allowed_dirs`.
- If `workdir` is set and `allowed_dirs` is omitted, `allowed_dirs` defaults to `[workdir]`.
- If `allowed_tools` is set, provider tools outside that list are not exposed to the model and fabricated calls are rejected with `TOOL_NOT_AUTHORIZED`.
- `submit_skill_result` remains available because it is the runtime completion tool.
- Local Python tools can receive normalized policy data by declaring a reserved `tool_context` argument:

```python
@provider.tool(description="Fetch one order")
def query_order(order_id: str, tool_context: dict) -> dict:
    workdir = tool_context["workdir"]
    allowed_dirs = tool_context["allowed_dirs"]
    return {"order_id": order_id, "workdir": workdir, "allowed_dirs": allowed_dirs}
```

`tool_context` is not exposed as an LLM tool argument.

## Tool Module

Set `BIZ_WORKER_STANDALONE_TOOL_MODULES` to one or more comma/semicolon separated modules.

Recommended module shape:

```python
def register_tools(provider):
    @provider.tool(description="Fetch one order")
    def query_order(order_id: str) -> dict:
        return {"order_id": order_id, "status": "OPEN"}
```

You may also point directly to a function:

```powershell
$env:BIZ_WORKER_STANDALONE_TOOL_MODULES = "order_tools:install"
```

## Model Provider

For local samples, point to a factory:

```powershell
$env:BIZ_WORKER_STANDALONE_MODEL_PROVIDER = "order_model:create_model"
```

For real LLM use, omit `BIZ_WORKER_STANDALONE_MODEL_PROVIDER` and configure the existing LLM settings:

```powershell
$env:BIZ_WORKER_LLM_PROVIDER = "openai"
$env:BIZ_WORKER_LLM_MODEL = "gpt-4o"
$env:BIZ_WORKER_LLM_API_KEY = "<secret>"
```

Supported provider values are the same as the worker runtime: `openai` and `anthropic`.

## Service Configuration

| Env | Purpose |
| --- | --- |
| `BIZ_WORKER_STANDALONE_SKILLS_ROOT` | Skill root used by standalone routes |
| `BIZ_WORKER_STANDALONE_DATA_ROOT` | Data root used by standalone execution |
| `BIZ_WORKER_STANDALONE_TOOL_MODULES` | Tool modules loaded at startup |
| `BIZ_WORKER_STANDALONE_MODEL_PROVIDER` | Custom model provider import path |
| `BIZ_WORKER_LLM_PROVIDER` | LLM provider fallback |
| `BIZ_WORKER_LLM_MODEL` | LLM model fallback |
| `BIZ_WORKER_LLM_API_KEY` | LLM API key, never returned by status |
| `BIZ_WORKER_WORKER_TOKEN` | Optional bearer token for service routes |

When `BIZ_WORKER_WORKER_TOKEN` is empty, local dev mode skips auth. When set, send:

```http
Authorization: Bearer <token>
```

## Diagnostics

```http
GET http://127.0.0.1:3061/api/v1/standalone/status
```

The response includes non-sensitive information:

```json
{
  "configured": true,
  "skillsRoot": ".../skills",
  "dataRoot": ".../.runtime/data",
  "toolModules": ["order_tools"],
  "loadedTools": ["query_order"],
  "modelProviderConfigured": true,
  "llmProvider": ""
}
```

The status route does not return API keys, worker tokens, Git tokens, or model provider object details.
