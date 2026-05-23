# BizWorker Standalone Service Phase 3A Plan

## 文档作用

- doc_type: execution-plan
- version: 1.1.4-SNAPSHOT
- status: accepted-for-implementation
- date: 2026-05-19
- intended_for: BizWorker maintainer | Python integration developer | Navigator reviewer
- purpose: 将 Phase 2 的 `SkillAgent` Python facade 暴露为最小 FastAPI service，使 BizWorker 可被简单 Python 项目当作独立 skill agent 使用

## 背景

Phase 1 已将 Worker 对外身份收敛为 `skill_name`，Phase 2 已提供本地 Python facade：

- `SkillAgent.list_skills/get_skill/register_skill/delete_skill/validate_skill`
- `SkillAgent.ask(skill_name=..., message=..., context=...)`
- `LocalPythonToolProvider` 与 `MockToolProvider`

Phase 3A 只做 service 化封装，不改变 Navigator Java 企业链路，也不改变现有 `/api/v1/query` SSE 行为。

## 目标

1. 提供最小 standalone service API，面向简单 Python 项目和轻量 HTTP 集成。
2. API 以 `skill_name` 为唯一外部 skill identity，不暴露内部 `skill_id`。
3. 复用 `SkillAgent` 的路径保护、资源写入保护和 ask 执行逻辑。
4. 提供可运行 sample，展示嵌入式 Python 调用和 HTTP service 调用两种方式。
5. 补 route 层测试，确认 service route 不依赖 Navigator Java。

## 非目标

- 不实现企业租户、ClientApp、task-scoped token 或 Java 控制面适配。
- 不改变 `skills.py` 里已有的 Git sync/materialize/clear 管理接口。
- 不引入新的模型配置中心；standalone 默认可使用 submit-only model 做本地 smoke。
- 不在本阶段实现长期多租户隔离，后续如需要再抽象 store/provider。

## Service API

| Method | Path | 用途 |
| --- | --- | --- |
| GET | `/api/v1/skills` | 列出本地 standalone skill |
| POST | `/api/v1/skills` | 创建或覆盖本地 standalone skill |
| GET | `/api/v1/skills/{skill_name}` | 查看 skill 内容、manifest 和校验结果 |
| DELETE | `/api/v1/skills/{skill_name}` | 删除本地 standalone skill |
| POST | `/api/v1/skills/{skill_name}/validate` | 校验 skill |
| POST | `/api/v1/ask` | 使用 `skill_name` 执行一次 ask |

所有接口仍复用 Worker token 机制；当本地 dev 未配置 `WORKER_TOKEN` 时保持现有免鉴权行为。

## Request Contract

### POST /api/v1/skills

```json
{
  "skill_name": "order-assistant",
  "description": "Order helper",
  "instructions": "Use query_order when order details are needed.",
  "tools": ["query_order"],
  "resources": {
    "examples/order.json": "{\"id\":\"O-1001\"}"
  },
  "overwrite": false
}
```

也允许直接传入完整 `content` 或 `markdown_body`，但二者不能同时出现。

### POST /api/v1/ask

```json
{
  "skill_name": "order-assistant",
  "message": "Check order O-1001",
  "context": {
    "account_id": "demo-account",
    "order_id": "O-1001"
  }
}
```

为了兼容常见 agent SDK，也接受 `prompt` 作为 `message` alias。

## 安全边界

- `skill_name` 必须是 folder basename，不允许 `/`、`\`、`.`、`..`、空白和保留名。
- resources 只能写入 skill 目录下的相对 POSIX path，不允许覆盖根部 `SKILL.md`。
- route 层只把请求转给 `SkillAgent`，不绕过 facade 的路径保护。
- service route 不读取 Navigator Java 租户、ClientApp 或 task token 状态。

## 测试计划

1. CRUD route 覆盖创建、列表、详情、validate、删除。
2. path traversal 覆盖非法 `skill_name`、非法 resource path 和保留名删除。
3. ask route 覆盖 `skill_name` 执行与 `prompt` alias。
4. service route 测试只配置临时 skills/data root，确认不依赖 Navigator Java。
5. 跑现有 compatibility suite，确认 Phase 1/2 与 root graph 行为不回退。

## 交付物

- `routes/standalone.py`
- `main.py` 注册 standalone router
- `tests/test_standalone_service.py`
- `samples/standalone_embedding.py`
- `samples/standalone_service.http`
- `samples/skills/order-assistant/SKILL.md`
- Phase 3A progress 文档与测试证据
