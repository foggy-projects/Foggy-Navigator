# BizWorker Standalone Provider Config Phase 3B Progress

## 文档作用

- doc_type: execution-progress
- version: 1.1.4-SNAPSHOT
- status: implemented
- date: 2026-05-19
- intended_for: BizWorker maintainer | Python integration developer | Navigator reviewer
- purpose: 记录 Phase 3B standalone provider 配置能力的实现范围、测试证据与后续项

## 实现摘要

Phase 3B 已补齐 standalone service 的 provider 装配入口，使其他 Python 项目可以在不修改 BizWorker 源码的情况下配置：

- standalone skills root
- standalone data root
- Python tool module
- custom model provider
- 既有 OpenAI/Anthropic LLM settings fallback

未配置 tool/model provider 时，Phase 3A 的 submit-only smoke 行为保持不变。

## 新增配置

| Settings field | Env | 行为 |
| --- | --- | --- |
| `standalone_skills_root` | `BIZ_WORKER_STANDALONE_SKILLS_ROOT` | standalone route 使用的 skill 根目录；空则使用默认 worker skills root |
| `standalone_data_root` | `BIZ_WORKER_STANDALONE_DATA_ROOT` | standalone route 使用的 data 根目录；空则 fallback 到 `BIZ_WORKER_DATA_ROOT`，再 fallback 到 `skills_root.parent / "data"` |
| `standalone_tool_modules` | `BIZ_WORKER_STANDALONE_TOOL_MODULES` | 逗号或分号分隔的 Python module/spec 列表 |
| `standalone_model_provider` | `BIZ_WORKER_STANDALONE_MODEL_PROVIDER` | custom model provider import path |

Tool module 支持：

- `package.module`，模块内暴露 `register_tools(provider)`。
- `package.module:install`，直接指向注册函数。

Model provider 支持：

- `package.module:create_model`
- `package.module.create_model`

若未配置 custom model provider，但配置了 `BIZ_WORKER_LLM_PROVIDER`，则复用既有 `create_chat_model(settings)`。

## 代码变更

| 文件 | 变更 |
| --- | --- |
| `tools/langgraph-biz-worker/src/langgraph_biz_worker/config.py` | 新增 standalone provider/root settings |
| `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/standalone_provider_config.py` | 新增 provider 装配模块 |
| `tools/langgraph-biz-worker/src/langgraph_biz_worker/main.py` | startup 中为 standalone route 注入装配后的 provider/root |
| `tools/langgraph-biz-worker/tests/test_standalone_provider_config.py` | 新增 provider config 单测 |
| `tools/langgraph-biz-worker/tests/test_standalone_service.py` | 新增 `/api/v1/ask` 调用动态 tool provider 的 route 测试 |
| `tools/langgraph-biz-worker/samples/standalone_tools.py` | 新增外部 tool module 样例 |
| `tools/langgraph-biz-worker/samples/standalone_service.http` | 补 standalone service 启动配置示例 |

## 行为边界

- `routes/standalone.py` 不直接读取 env，只接收 startup 注入的 provider。
- 原有 `/api/v1/query` SSE 行为未改动。
- 原有 enterprise `skills.py` sync/materialize/clear route 仍使用默认 worker skills root。
- standalone route 可使用独立 skills/data root，但不影响 Navigator Java 控制面。
- 本阶段不新增生产级 sandbox、热加载、插件市场或租户隔离。

## 测试证据

目标测试：

```powershell
$env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest `
  tests/test_standalone_provider_config.py `
  tests/test_standalone_service.py `
  tests/test_tool_provider.py `
  tests/test_skill_agent_facade.py `
  tests/test_skill_agent_governance.py `
  tests/test_skill_identity.py `
  tests/test_skill_registry_v2.py
```

结果：

```text
57 passed
```

兼容测试：

```powershell
$env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest `
  tests/test_standalone_provider_config.py `
  tests/test_standalone_service.py `
  tests/test_skill_identity.py `
  tests/test_tool_provider.py `
  tests/test_skill_agent_facade.py `
  tests/test_skill_agent_governance.py `
  tests/test_root_graph.py `
  tests/test_query.py `
  tests/test_skill_registry_v2.py `
  tests/test_llm_skill_agent.py `
  tests/test_account_skill_routing.py
```

结果：

```text
132 passed
```

Sample smoke：

```powershell
$env:PYTHONPATH='src'; .\.venv\Scripts\python.exe samples\standalone_embedding.py
```

结果：

```text
Order O-1001 is OPEN.
{'order_id': 'O-1001', 'status': 'OPEN'}
```

Standalone tool module smoke：

```powershell
$env:PYTHONPATH='src;.'; .\.venv\Scripts\python.exe -c "from langgraph_biz_worker.runtime.standalone_provider_config import load_tool_provider; p=load_tool_provider('samples.standalone_tools'); print([s.name for s in p.list_tools('order-assistant')]); print(p.call_tool('query_order', {'order_id':'O-1001'}))"
```

结果：

```text
['query_order']
{'order_id': 'O-1001', 'status': 'OPEN', 'owner': 'demo-account'}
```

## 自检

- requirement alignment: Phase 3B 的 provider 配置能力已完成。
- compatibility: root graph、query、LLM skill agent、account routing 测试通过。
- implementation scope: 未触碰 Navigator Java 控制面。
- security boundary: 新增能力只做 module import 与 provider 注入；生产 sandbox 和租户隔离仍是后续项。
- experience: N/A，本阶段无 UI。

## 后续项

1. 补 standalone 使用手册或 README，让外部 Python 项目能按步骤接入。
2. 如要进入生产服务形态，需要明确 `WORKER_TOKEN` 必填策略、审计日志和 provider import 白名单。
3. 如要支持长期运行插件生命周期，需要设计 reload/disable/error isolation。
