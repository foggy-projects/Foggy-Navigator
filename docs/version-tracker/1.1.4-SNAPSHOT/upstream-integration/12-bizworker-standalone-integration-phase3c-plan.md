# BizWorker Standalone Integration Phase 3C Plan

## 文档作用

- doc_type: requirement+implementation-plan
- version: 1.1.4-SNAPSHOT
- status: accepted-for-implementation
- date: 2026-05-19
- intended_for: BizWorker maintainer | Python integration developer | Navigator reviewer
- purpose: 将 standalone provider 能力包装成可交付给外部 Python 项目的集成形态

## 背景

Phase 3A 已提供 standalone service API，Phase 3B 已支持通过配置加载 skills root、data root、tool module 和 model provider。当前缺口是外部项目接入时仍缺少：

- 可照做的使用手册。
- 独立项目形态的 sample。
- 服务启动后确认 provider 是否真正加载的诊断接口。
- 诊断接口不泄露 token/API key 的测试保障。

## 目标

1. 提供 standalone 使用手册，覆盖 embedded 和 HTTP service 两种模式。
2. 提供 `samples/standalone-project`，模拟一个外部 Python 项目。
3. 新增只读诊断接口 `GET /api/v1/standalone/status`。
4. 诊断接口返回非敏感配置状态：
   - `skillsRoot`
   - `dataRoot`
   - `toolModules`
   - `loadedTools`
   - `modelProviderConfigured`
   - `llmProvider`
5. 补测试确认 sample、route 和诊断接口可用且不泄露 secret。

## 非目标

- 不实现插件热加载。
- 不实现多租户 sandbox。
- 不修改 Navigator Java 控制面。
- 不修改 `/api/v1/query` SSE 行为。
- 不把 `WORKER_TOKEN` 生产策略改成强制；只在文档中写清使用方式。

## API Contract

```http
GET /api/v1/standalone/status
```

示例响应：

```json
{
  "configured": true,
  "skillsRoot": ".../skills",
  "dataRoot": ".../data",
  "toolModules": ["samples.standalone_tools"],
  "loadedTools": ["query_order"],
  "modelProviderConfigured": false,
  "llmProvider": ""
}
```

诊断响应不得包含：

- `BIZ_WORKER_WORKER_TOKEN`
- `BIZ_WORKER_LLM_API_KEY`
- `BIZ_WORKER_SKILL_GIT_TOKEN`
- custom provider 对象 repr 中可能包含的 secret

## Ownership

| Area | Owner | Responsibility |
| --- | --- | --- |
| `tools/langgraph-biz-worker` | BizWorker | status route、standalone docs、samples、tests |
| `docs/version-tracker/1.1.4-SNAPSHOT` | Navigator workspace | Phase 3C plan/progress/evidence |
| Navigator Java modules | Navigator Java | read-only；本阶段不触碰 |

## Code Inventory

| Repo | Path | Role | Expected change | Notes |
| --- | --- | --- | --- | --- |
| Navigator | `tools/langgraph-biz-worker/src/langgraph_biz_worker/routes/standalone.py` | standalone HTTP route | update | 新增 status endpoint 与非敏感状态输出 |
| Navigator | `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/standalone_provider_config.py` | provider assembly | update | 暴露 tool module specs 与 llm provider 元数据 |
| Navigator | `tools/langgraph-biz-worker/src/langgraph_biz_worker/main.py` | startup wiring | update | 将 metadata 注入 standalone route |
| Navigator | `tools/langgraph-biz-worker/docs/standalone.md` | integration guide | create | embedded/service/config/status 使用手册 |
| Navigator | `tools/langgraph-biz-worker/samples/standalone-project` | external project sample | create | 独立项目目录形态 |
| Navigator | `tools/langgraph-biz-worker/tests/test_standalone_service.py` | route tests | update | status route、secret redaction、loaded tools |
| Navigator | `tools/langgraph-biz-worker/tests/test_standalone_project_sample.py` | sample tests | create | embedded sample 与 service smoke helper |
| Navigator | `docs/version-tracker/1.1.4-SNAPSHOT/upstream-integration` | version docs | update | Phase 3C progress 与 overview |

## 实施顺序

1. 扩展 standalone provider config 的 metadata。
2. 新增 `GET /api/v1/standalone/status`。
3. 补 standalone 使用手册。
4. 补 `samples/standalone-project`。
5. 补 route/sample 测试。
6. 跑目标测试与兼容测试。
7. 回写 progress 和 overview。

## 验收标准

- status route 可返回当前 standalone 装配状态。
- status route 不泄露 API key、token 或 custom provider repr。
- 外部项目 sample 可在测试中运行 embedded smoke。
- service smoke helper 能构造可用的 HTTP 调用脚本。
- 现有 standalone ask、provider config 和 root graph 兼容测试通过。
