# BizWorker Local Skill Governance Phase 2 Plan

## 文档作用

- doc_type: workitem | implementation-plan
- version: 1.1.4-SNAPSHOT
- status: ready-for-implementation
- date: 2026-05-19
- source: [02-bizworker-standalone-skill-agent-plan.md](./02-bizworker-standalone-skill-agent-plan.md)
- intended_for: BizWorker implementer | Navigator reviewer | Python integration developer
- purpose: 将 Phase 2 拆成可执行的本地 Skill 治理 facade，先服务 Python standalone 集成

## Background

Phase 1 已建立 `skill_name = skill folder basename` 的外部契约，并补齐 `SkillAgent.ask(...)`、`ToolProvider`、alias normalization 和 targeted tests。

Phase 2 继续围绕 BizWorker standalone 目标推进，但本阶段先不展开 Navigator Java 控制面，也不立即实现完整 FastAPI CRUD。当前优先级是让普通 Python 项目可以通过 `SkillAgent` 完成本地 skill 的注册、读取、校验、删除和重新加载。

## Scope

本阶段实现 Python facade 本地治理能力：

- `SkillAgent.list_skills()`
- `SkillAgent.get_skill(skill_name=...)`
- `SkillAgent.register_skill(skill_name=..., ...)`
- `SkillAgent.delete_skill(skill_name=...)`
- `SkillAgent.validate_skill(skill_name=...)`
- `SkillAgent.reload_skills()`

本阶段不实现：

- Navigator Java DB/entity/control-plane rename.
- Navigator/TMS OpenAPI 行为调整.
- FastAPI service mode CRUD route.
- CLI command.
- package publishing.
- enable/disable 状态文件.
- Python 内部 `skill_id` 全局重命名.

## Contract

### Directory Layout

Facade 只治理 configured `skills_root` 下的直接子目录：

```text
skills_root/
  <skill_name>/
    SKILL.md
    resources/
    examples/
```

`skill_name` 必须是安全单级路径段，语义仍为 folder basename。

### Register

`register_skill` 支持两种输入：

1. 传入完整 `SKILL.md` 内容。
2. 传入 `description`、`instructions`、`tools`，由 facade 生成最小 frontmatter。

写入规则：

- 目标路径必须解析在 configured `skills_root` 内。
- 默认不覆盖已有 skill。
- 显式 `overwrite=True` 时只允许替换同名 skill 目录。
- 可选 resources 必须是相对路径，且解析后仍在同名 skill 目录内。

### Validate

`validate_skill` 返回结构化结果：

```json
{
  "ok": true,
  "skill_name": "order-assistant",
  "manifest_id": "order-assistant",
  "warnings": []
}
```

校验项：

- `skill_name` 安全.
- `SKILL.md` 存在.
- frontmatter 可被 `SkillRegistry` 解析为 manifest.
- folder basename 与 manifest id 不一致时返回 warning，暂不作为 hard error。

### Delete

`delete_skill` 只删除一个明确的 `<skills_root>/<skill_name>` 目录。

禁止行为：

- 删除 `skills_root`.
- 删除父目录.
- 删除绝对路径.
- 删除含路径穿越或分隔符的名称.
- 通过通配符或空名称删除多个目录.

## Code Inventory

| Path | Role | Change |
| --- | --- | --- |
| `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/skill_agent.py` | Python standalone facade | 增加本地 skill 治理方法与路径保护 |
| `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/skill_identity.py` | `skill_name` 校验 | 复用 Phase 1 单级路径段校验 |
| `tools/langgraph-biz-worker/tests/test_skill_agent_governance.py` | Phase 2 tests | 新增 register/list/get/validate/delete/path traversal 测试 |
| `docs/version-tracker/1.1.4-SNAPSHOT/upstream-integration/07-bizworker-local-skill-governance-phase2-progress.md` | progress | 实现完成后回写测试证据与自检 |

## Implementation Steps

1. 在 `SkillAgent` 中补本地治理 facade。
2. 增加 path guard：所有目标路径必须可 `relative_to(skills_root.resolve())`。
3. 增加 resource relative path guard：禁止 absolute、drive separator、`..`、空 segment。
4. 新增 targeted tests 覆盖成功路径和失败路径。
5. 运行 Phase 1 + Phase 2 目标测试，避免 standalone ask 能力回退。
6. 回写 Phase 2 progress 与 overview 状态。

## Required Tests

Python targeted tests:

```powershell
python -m pytest tests/test_skill_agent_governance.py tests/test_skill_agent_facade.py tests/test_skill_identity.py tests/test_skill_registry_v2.py
```

Compatibility suite:

```powershell
python -m pytest tests/test_skill_identity.py tests/test_tool_provider.py tests/test_skill_agent_facade.py tests/test_skill_agent_governance.py tests/test_root_graph.py tests/test_query.py tests/test_skill_registry_v2.py tests/test_llm_skill_agent.py tests/test_account_skill_routing.py
```

## Acceptance Criteria

- 普通 Python 项目可通过 `SkillAgent` 创建、读取、校验、删除本地 skill。
- 所有治理入口使用 `skill_name`，不要求调用方理解 Java `skillId`。
- path traversal、绝对路径、空名称、分隔符、重复 alias 等危险输入有失败测试。
- Phase 1 `SkillAgent.ask(...)` 和 provider tool 调用能力不回退。
- 本阶段没有引入 Navigator Java 控制面耦合。

## Review Notes

- 本阶段是 facade-first，不代表最终 service mode 不需要 CRUD API。FastAPI route 可在下一步复用同一组 path guard 和 registry 语义。
- `manifest_id != skill_name` 暂时 warning，是为了兼容已有 `SKILL.md`；后续可在 strict mode 或新项目模板中提升为 hard error。
- `skill_id` 仍是内部 frame/runtime 字段，Phase 2 不做全局 rename。
