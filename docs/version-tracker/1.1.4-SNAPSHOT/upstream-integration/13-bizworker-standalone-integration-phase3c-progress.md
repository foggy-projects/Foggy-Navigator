# BizWorker Standalone Integration Phase 3C Progress

## 文档作用

- doc_type: execution-progress
- version: 1.1.4-SNAPSHOT
- status: implemented
- date: 2026-05-19
- intended_for: BizWorker maintainer | Python integration developer | Navigator reviewer
- purpose: 记录 Phase 3C standalone 对外集成可用化的实现范围、测试证据与后续项

## 实现摘要

Phase 3C 已完成 standalone 对外集成可用化：

- 新增只读诊断接口 `GET /api/v1/standalone/status`。
- 新增 `tools/langgraph-biz-worker/docs/standalone.md` 使用手册。
- 新增 `samples/standalone-project`，模拟外部 Python 项目目录。
- 补 route 与 sample 测试，确认 status 不泄露 secret，embedded sample 可运行。

## 代码变更

| 文件 | 变更 |
| --- | --- |
| `tools/langgraph-biz-worker/src/langgraph_biz_worker/routes/standalone.py` | 新增 standalone status route 与非敏感诊断输出 |
| `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/standalone_provider_config.py` | 为 service config 增加 tool module、model provider、LLM provider metadata |
| `tools/langgraph-biz-worker/src/langgraph_biz_worker/main.py` | 将 standalone metadata 注入 route |
| `tools/langgraph-biz-worker/docs/standalone.md` | 新增 standalone 集成手册 |
| `tools/langgraph-biz-worker/samples/standalone-project` | 新增独立项目形态 sample |
| `tools/langgraph-biz-worker/tests/test_standalone_service.py` | 新增 status route 与 secret redaction 测试 |
| `tools/langgraph-biz-worker/tests/test_standalone_project_sample.py` | 新增 standalone-project sample 测试 |
| `tools/langgraph-biz-worker/tests/test_standalone_provider_config.py` | 补 provider metadata 断言 |

## Status Contract

```http
GET /api/v1/standalone/status
```

返回字段：

- `configured`
- `skillsRoot`
- `dataRoot`
- `toolModules`
- `loadedTools`
- `modelProviderConfigured`
- `llmProvider`

该接口不返回 API key、worker token、Git token 或 model provider 对象详情。

## Sample

新增 sample：

```text
tools/langgraph-biz-worker/samples/standalone-project/
  .env.example
  order_model.py
  order_tools.py
  run_embedded.py
  service_smoke.py
  skills/order-assistant/SKILL.md
```

其中：

- `run_embedded.py` 演示 Python 进程内使用 `SkillAgent`。
- `service_smoke.py` 演示 HTTP service 模式下调用 status 与 ask。
- `order_tools.py` 演示 `register_tools(provider)`。
- `order_model.py` 使用 deterministic model，便于本地 smoke。

## 测试证据

目标测试：

```powershell
$env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest `
  tests/test_standalone_provider_config.py `
  tests/test_standalone_service.py `
  tests/test_standalone_project_sample.py `
  tests/test_tool_provider.py `
  tests/test_skill_agent_facade.py `
  tests/test_skill_agent_governance.py `
  tests/test_skill_identity.py `
  tests/test_skill_registry_v2.py
```

结果：

```text
60 passed
```

兼容测试：

```powershell
$env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest `
  tests/test_standalone_provider_config.py `
  tests/test_standalone_service.py `
  tests/test_standalone_project_sample.py `
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
135 passed
```

## 自检

- requirement alignment: Phase 3C 的文档、sample、status route、测试均已完成。
- compatibility: root graph、query、LLM skill agent、account routing 测试通过。
- security boundary: status route 只返回非敏感 metadata，并有 secret redaction 测试。
- implementation scope: 未触碰 Navigator Java 控制面，未改变 `/api/v1/query`。
- experience: N/A，本阶段无 UI。

## 后续项

1. 若准备正式对外发布，需要补 package README 和安装说明。
2. 若进入生产服务形态，需要评估 `WORKER_TOKEN` 必填、provider import 白名单、审计日志。
3. 已确认近期不推进插件热加载或多租户 sandbox；下一阶段优先收敛为上游传入的 `workdir`、`allowed_dirs`、`allowed_tools` 执行策略。
