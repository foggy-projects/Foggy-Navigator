# BizWorker Standalone Plan Review

## 文档作用

- doc_type: plan-review
- version: 1.1.4-SNAPSHOT
- status: completed-with-actions
- date: 2026-05-19
- reviewed_docs:
  - [00-overview.md](./00-overview.md)
  - [01-worker-skill-name-boundary-decision.md](./01-worker-skill-name-boundary-decision.md)
  - [02-bizworker-standalone-skill-agent-plan.md](./02-bizworker-standalone-skill-agent-plan.md)
- intended_for: Navigator maintainer | BizWorker maintainer | reviewer
- purpose: 对 BizWorker standalone Skill Agent 规划做轻量架构评审，确认是否可进入 Phase 1

## Review Scope

评审对象类型：总规划文档。

评审重点：

- 目标是否清晰，是否与 1.1.4 迭代边界一致。
- `skill_name` 术语是否足以降低 LLM 与 Java `skillId` 耦合。
- standalone runtime 是否避免把 Navigator 企业控制面塞入 Worker core。
- Phase 1 是否可控，是否存在过早大重构风险。
- 需要在实现前补充的验收和安全边界。

## Verdict

条件通过，可以进入 Phase 1。

前提是实现阶段严格遵守 `02-bizworker-standalone-skill-agent-plan.md` 中新增的 scope guard：

- Phase 1 只做 `SkillAgent` facade、provider 边界、最小 ask contract 和兼容包装。
- 不在 Phase 1 做 Python 内部 `skill_id` 全局改名。
- 不在 Phase 1 做 Java 启动链路迁移。
- 不在 Phase 1 做 service CRUD、packaging、CLI 或 HTTP provider。
- 新增外部契约字段统一使用 `skill_name`，`skillName` 仅作为 Java 属性名或兼容输入。

## Findings

### F1: Phase 1 范围必须强约束

Severity: medium

原计划同时提到 `SkillAgent`、`ToolProvider`、`ModelProvider`、standalone ask contract、root graph 兼容。如果没有 scope guard，很容易演变成 runtime、service、Java adapter 同时改。已在 02 文档中补充 Phase 1 scope guard 与 completion gate。

### F2: 现有 Worker 内部 `skill_id` 分布很广

Severity: medium

代码搜索显示 `tools/langgraph-biz-worker` 中 `skill_id` 已覆盖：

- Pydantic models：`QueryRequest`、`SkillManifest`
- root graph：dynamic manifest、LLM tool schema、routing prompt
- routes：skill materialize/delete
- runtime：`SkillRuntime`、`LlmSkillAgent`、tool dispatcher、resource tools
- 大量 frame、routing、context isolation、E2E tests

因此 `skill_name` 应先作为新增外部契约和语义映射落地，内部 `skill_id` 保持 legacy alias。全局字段重命名应放到后续专门迁移，不作为 1.1.4 Phase 1 成功条件。

### F3: 术语需要固定，否则后续实现会再次耦合

Severity: medium

`skill_name`、`skillName`、`skill_id`、Java `skillId`、`displayName`、`SkillAgent`、`SkillRuntime`、`ToolProvider`、`ModelProvider` 的边界必须固定。已在 02 文档补充 glossary 和 compatibility aliases。

### F4: Skill CRUD 的文件安全必须作为 Phase 2 验收门槛

Severity: medium

standalone service mode 会引入本地文件写入和删除能力。路径穿越、删除 skills root、绝对路径、重复分隔符、空 `skill_name` 都必须有失败测试。已在 Phase 2 completion gate 中补充。

### F5: `ModelProvider` 不应过早抽象

Severity: low

Phase 1 可以只明确 chat model 注入方式，避免在模型配置、租户模型授权、Navigator model config 之间过早抽象。`ToolProvider` 是更紧急的边界，因为它直接决定 BizWorker 是否能脱离 Java 调用本地能力。

### F6: 01 与 02 的 Phase 命名容易混淆

Severity: low

01 文档中的 Phase 1 是跨 Java/Worker 的 protocol compatibility，02 文档中的 Phase 1 是 BizWorker standalone core contract extraction。二者不是同一批实现。已在 01 与 02 中补充 phase naming / sequencing note。

### F7: 字段命名应最终收敛到 Python 友好的 `skill_name`

Severity: low

BizWorker 是 Python/FastAPI runtime，外部 JSON 与 Python embedding API 使用 `skill_name` 更自然。Java 可以保留 `skillName` 属性，但跨语言 wire contract 应发送 `skill_name`。已在 01/02 和 Phase 1 执行计划中补充。

## Required Actions Before Coding

- 以 `skill_name` 作为新增 API 和文档字段。
- 保留 `skill_id` 作为内部兼容 alias，不做全局 rename。
- Phase 1 新增 targeted Python tests，覆盖 facade、provider、alias 映射和路径段校验。
- 若需要修改 frame/runtime 主模型，先更新设计文档再编码。
- Java 侧迁移只作为后续 Phase 5，不进入 standalone Phase 1。

## Decision

本轮评审结论为“条件通过”。1.1.4 可以从 Phase 1 开始，但第一批实现应保持小步：

```text
SkillAgent facade
-> safe skill_name validation
-> local/mock ToolProvider
-> minimal ask contract
-> compatibility tests
```

完成这条线后，再评估是否进入 Phase 2 的 skill governance service API。
