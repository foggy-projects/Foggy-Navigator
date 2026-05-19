# BizWorker Standalone Provider Config Phase 3B Plan

## 文档作用

- doc_type: requirement+implementation-plan
- version: 1.1.4-SNAPSHOT
- status: accepted-for-implementation
- date: 2026-05-19
- intended_for: BizWorker maintainer | Python integration developer | Navigator reviewer
- purpose: 将 Phase 3A 的 standalone service 从 hard-coded runtime 推进到可由外部 Python 项目配置 tool/model provider

## 背景

Phase 3A 已提供最小 standalone HTTP API，但启动时仍只配置 skills/data root，未提供面向外部项目的 provider 装配入口。其他 Python 项目如果想把 BizWorker 当作独立 skill agent 使用，需要能在不修改 BizWorker 源码的前提下：

- 指定本地 skills root 与 data root。
- 加载本项目的 Python tool module。
- 使用已有 OpenAI/Anthropic LLM 配置或自定义 model provider。

## 目标

1. 新增 standalone provider 装配层，集中处理 env/settings 到 `SkillAgent` 依赖的转换。
2. 支持从 Python module 动态注册 tools，推荐约定为 `register_tools(provider)`。
3. 支持 model provider 配置：
   - 优先使用 custom import path。
   - 其次复用既有 `llm_provider`/`llm_model`/`llm_api_key` 配置创建 LangChain chat model。
   - 未配置时保持 Phase 3A 的 submit-only smoke 行为。
4. 支持 standalone skills/data root 覆盖，便于外部项目独立目录运行。
5. 补 route 层测试，覆盖动态 tool provider 真正参与 `/api/v1/ask`。

## 非目标

- 不实现插件市场或热加载生命周期。
- 不设计多租户 tool sandbox。
- 不改变 Navigator Java 企业链路。
- 不改变 `/api/v1/query` SSE 入口。
- 不在本阶段强制生产鉴权策略；仍复用现有 Worker token 行为。

## 配置契约

新增 settings 字段，遵循现有 `BIZ_WORKER_` env prefix：

| Settings field | Env | 说明 |
| --- | --- | --- |
| `standalone_skills_root` | `BIZ_WORKER_STANDALONE_SKILLS_ROOT` | standalone skill 根目录；空则使用现有默认 skills root |
| `standalone_data_root` | `BIZ_WORKER_STANDALONE_DATA_ROOT` | standalone data 根目录；空则使用 `skills_root.parent / "data"` |
| `standalone_tool_modules` | `BIZ_WORKER_STANDALONE_TOOL_MODULES` | 逗号或分号分隔的 Python module 列表 |
| `standalone_model_provider` | `BIZ_WORKER_STANDALONE_MODEL_PROVIDER` | 自定义 model provider import path，格式 `package.module:factory` 或 `package.module.factory` |

Tool module 推荐形态：

```python
def register_tools(provider):
    @provider.tool(description="Fetch order")
    def query_order(order_id: str) -> dict:
        return {"order_id": order_id}
```

Model provider 推荐形态：

```python
def create_model():
    return MyChatModel()
```

## Ownership

| Area | Owner | Responsibility |
| --- | --- | --- |
| `tools/langgraph-biz-worker` | BizWorker | provider config、service startup、tests、samples |
| `docs/version-tracker/1.1.4-SNAPSHOT` | Navigator workspace | 版本规划、进展与测试证据 |
| Navigator Java modules | Navigator Java | read-only；本阶段不触碰 |

## Code Inventory

| Repo | Path | Role | Expected change | Notes |
| --- | --- | --- | --- | --- |
| Navigator | `tools/langgraph-biz-worker/src/langgraph_biz_worker/config.py` | settings | update | 增加 standalone provider/root 配置 |
| Navigator | `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/standalone_provider_config.py` | provider assembly | create | 装配 tool/model provider |
| Navigator | `tools/langgraph-biz-worker/src/langgraph_biz_worker/main.py` | startup wiring | update | 使用 provider config 配置 standalone route |
| Navigator | `tools/langgraph-biz-worker/tests/test_standalone_provider_config.py` | unit tests | create | 覆盖 module import、model provider、root resolution |
| Navigator | `tools/langgraph-biz-worker/tests/test_standalone_service.py` | route tests | update | 覆盖 `/ask` 调用动态 tool |
| Navigator | `tools/langgraph-biz-worker/samples/standalone_tools.py` | sample tools | create | 展示外部项目 tool module 写法 |
| Navigator | `docs/version-tracker/1.1.4-SNAPSHOT/upstream-integration` | version docs | update | 记录 plan/progress/evidence |

## 实施顺序

1. 新增 settings 字段。
2. 新增 provider config 模块：
   - 解析 skills/data root。
   - 加载 tool modules 并调用 `register_tools(provider)`。
   - 加载 custom model provider import path。
   - 未配置 custom provider 时复用 `create_chat_model(settings)`。
3. 在 `main.py` startup 中使用装配结果配置 standalone route。
4. 补单测与 route 测试。
5. 更新 sample、overview 和 progress。

## 验收标准

- 外部项目可通过 env 指定 tool module，无需修改 BizWorker 源码。
- `/api/v1/ask` 能调用动态注册的 local Python tool。
- 未配置 provider 时 Phase 3A submit-only 行为不回退。
- OpenAI/Anthropic 仍通过既有 `llm_provider` 配置创建，不新增重复 LLM 配置体系。
- 目标测试和兼容测试通过。
