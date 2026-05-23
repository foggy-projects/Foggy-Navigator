# BizWorker Local Skill Governance Phase 2 Progress

## 文档作用

- doc_type: progress | execution-checkin
- version: 1.1.4-SNAPSHOT
- status: implemented
- date: 2026-05-19
- source: [06-bizworker-local-skill-governance-phase2-plan.md](./06-bizworker-local-skill-governance-phase2-plan.md)
- intended_for: BizWorker implementer | Navigator reviewer | Python integration developer
- purpose: 跟踪 Phase 2 本地 Skill 治理 facade 的实现、测试、自检与后续边界

## Scope

本阶段已实现：

- `SkillAgent.list_skills()`
- `SkillAgent.get_skill(skill_name)`
- `SkillAgent.register_skill(skill_name, ...)`
- `SkillAgent.delete_skill(skill_name)`
- `SkillAgent.validate_skill(skill_name)`
- `SkillAgent.reload_skills()`

本阶段仍不覆盖：

- FastAPI service mode CRUD route.
- Navigator Java 控制面或 DB 字段重命名.
- package publishing / CLI.
- enable/disable 状态文件.
- Python 内部 `skill_id` 全局重命名.

## Development Progress

| Area | Status | Notes |
| --- | --- | --- |
| Docs | completed | Phase 2 plan、overview、standalone plan 已对齐 facade-first 边界 |
| Code | completed | `SkillAgent` 本地治理 facade、路径保护、public export 已补齐 |
| Tests | completed | 新增治理测试并跑通 Phase 1/2 兼容套件 |

## Execution Check-in

Completed work:

- Added `SkillAgent` governance methods for local skill list/get/register/delete/validate/reload.
- Added write/delete path guard so all targets resolve under configured `skills_root`.
- Added resource path guard for absolute path、drive separator、`..`、empty/repeated separators and direct `SKILL.md` overwrite.
- Kept `manifest_id != skill_name` as validation warning for compatibility with existing `SKILL.md`.
- Exported `SkillAgent` from `langgraph_biz_worker`.

Touched code paths:

- `tools/langgraph-biz-worker/src/langgraph_biz_worker/__init__.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/skill_agent.py`
- `tools/langgraph-biz-worker/tests/test_skill_agent_governance.py`

## Testing Progress

| Test | Status | Notes |
| --- | --- | --- |
| `python -m pytest tests/test_skill_agent_governance.py tests/test_skill_agent_facade.py tests/test_skill_identity.py tests/test_skill_registry_v2.py` | passed | 43 passed |
| `python -m pytest tests/test_skill_identity.py tests/test_tool_provider.py tests/test_skill_agent_facade.py tests/test_skill_agent_governance.py tests/test_root_graph.py tests/test_query.py tests/test_skill_registry_v2.py tests/test_llm_skill_agent.py tests/test_account_skill_routing.py` | passed | 120 passed |

Command environment:

```powershell
$env:PYTHONPATH='src'
.\.venv\Scripts\python.exe -m pytest ...
```

## Self-Check

- `skill_name` remains the public external identity.
- The facade can manage simple local Python project skills without Navigator Java.
- Existing ask/root graph/LLM skill tests did not regress.
- Local governance protects the filesystem boundary before FastAPI CRUD is introduced.

## Remaining Risks

- Service mode route still needs a thin HTTP layer over the facade.
- Runtime/event/report internals still expose `skill_id`; this is intentionally outside Phase 2.
- Resource validation is path-safety focused; richer semantic validation for referenced resources can be added later.
