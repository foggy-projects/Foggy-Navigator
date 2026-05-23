# BizWorker Standalone Service Phase 3A Progress

## 文档作用

- doc_type: execution-progress
- version: 1.1.4-SNAPSHOT
- status: implemented
- date: 2026-05-19
- intended_for: BizWorker maintainer | Python integration developer | Navigator reviewer
- purpose: 记录 Phase 3A standalone service API 的实现范围、测试证据与后续风险

## 实现摘要

Phase 3A 已将 Phase 2 的 `SkillAgent` facade 暴露为最小 FastAPI service：

- `GET /api/v1/skills`
- `POST /api/v1/skills`
- `GET /api/v1/skills/{skill_name}`
- `DELETE /api/v1/skills/{skill_name}`
- `POST /api/v1/skills/{skill_name}/validate`
- `POST /api/v1/ask`

接口只接受 `skill_name`，不向外暴露内部 `skill_id`。`/api/v1/ask` 使用 `message`，并兼容 `prompt` alias。

## 代码变更

| 文件 | 变更 |
| --- | --- |
| `tools/langgraph-biz-worker/src/langgraph_biz_worker/routes/standalone.py` | 新增 standalone service route，复用 `SkillAgent` 执行 CRUD、validate、ask |
| `tools/langgraph-biz-worker/src/langgraph_biz_worker/main.py` | 注册 standalone router，并在启动时配置 skills/data root |
| `tools/langgraph-biz-worker/tests/test_standalone_service.py` | 新增 route 层测试 |
| `tools/langgraph-biz-worker/samples/standalone_embedding.py` | 新增嵌入式 Python 调用样例 |
| `tools/langgraph-biz-worker/samples/standalone_service.http` | 新增 HTTP service 调用样例 |
| `tools/langgraph-biz-worker/samples/skills/order-assistant/SKILL.md` | 新增最小 sample skill |

## 行为边界

- service route 只做 HTTP 适配，路径保护、资源写入保护和执行逻辑仍由 `SkillAgent` 负责。
- existing `/api/v1/query` SSE route 未改动。
- existing `/api/v1/skills/materialize|clear|sync|webhook` 管理接口未改动。
- standalone service 仍复用 Worker token 校验；本地未配置 `WORKER_TOKEN` 时沿用现有免鉴权行为。
- route 测试使用临时 `skills_root/data_root`，不依赖 Navigator Java、ClientApp 或 task-scoped token。

## 测试证据

目标测试：

```powershell
$env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest `
  tests/test_standalone_service.py `
  tests/test_skill_agent_governance.py `
  tests/test_skill_agent_facade.py `
  tests/test_skill_identity.py `
  tests/test_skill_registry_v2.py
```

结果：

```text
47 passed
```

兼容测试：

```powershell
$env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m pytest `
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
124 passed
```

sample smoke：

```powershell
$env:PYTHONPATH='src'; .\.venv\Scripts\python.exe samples\standalone_embedding.py
```

结果：

```text
Order O-1001 is OPEN.
{'order_id': 'O-1001', 'status': 'OPEN'}
```

## 自检

- requirement alignment: 1~4 已完成，覆盖文档、service API、sample、route 测试。
- compatibility: 现有 root graph、query、registry、LLM skill agent、account routing 测试通过。
- security boundary: path traversal、resource traversal、保留名删除均有 route 层覆盖。
- experience: N/A，本阶段无 UI。

## 后续项

1. 如需生产级 standalone service，需要继续补真实 model provider 配置、tool provider 插件装载和长期运行配置。
2. 如需给其他 Python 项目直接依赖包使用，需要补 package-level README/API reference。
3. 如需和 Navigator 企业链路并行部署，需要再评估 Worker token、租户隔离和审计日志格式。
