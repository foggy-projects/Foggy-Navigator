# REQ-025 Public Skill Schema Placeholders

## 文档作用

- doc_type: workitem
- intended_for: execution-agent, reviewer, upstream-integrator
- purpose: 记录 GitHub issue #120 的平台约定、实现边界、验收标准与执行进度。

## Status

- Version: `1.3.0-SNAPSHOT`
- Source: https://github.com/foggy-projects/Foggy-Navigator/issues/120
- Type: requirement / platform contract
- Priority: high
- Status: implemented locally, targeted tests passed
- Owner: Navigator business-agent / BizWorker integration
- Date: 2026-05-17

## Background

TMS 开单 Agent 暴露过一次回归：已选中的 public skill 允许调用 `tms.order.createOpeningDraft`，但运行时 `SKILL.md` 没有函数入参契约。LLM 知道函数存在，却不知道可靠参数字段，最终在用户已经给出开单线索时仍创建了 `OPEN_EMPTY` 草稿。

目前完整函数 schema 已经存在于上游 BusinessFunction manifest。把 schema 手写到 `SKILL.md` 可以临时止血，但会造成 manifest 与 skill markdown 双源漂移，也增加上游排查成本。

## Accepted V1 Contract

Public skill markdown 可以在 `SKILL.md` 或 markdown resource 中使用 schema placeholder：

```md
${@schema.tms.order.createOpeningDraft}
```

该 placeholder 表示渲染已注册 BusinessFunction 的公开 LLM 契约，而不是 dump 原始 manifest。Navigator 在 skill sync / materialize 阶段解析 placeholder，并在原位置替换成紧凑 markdown contract。

## Rendering Rules

渲染内容允许包含：

- `functionId@version`
- 函数名称、描述、何时调用
- input fields 的名称、类型、required / enum / description
- output fields 的关键结构
- `llmVisibleSummary` / `schemaVisibleSummary` 中的公开提示

渲染内容禁止包含：

- `adapterConfigJson`
- adapter body mappings
- internal gateway paths
- auth headers / token
- 私有 runtime 字段
- raw `manifestJson` 中未明确公开的内部实现细节

## Scope And Ownership

- Java control plane owns placeholder resolution and sanitized contract rendering.
- Worker materialize endpoint receives already resolved `markdown_body` and resources.
- Existing skills without placeholders must remain unchanged.
- Placeholder resolution must be scoped by the skill bundle's allowed functions and ClientApp function grants.
- For v1, critical function contract should be placed in `SKILL.md`; `references/**/*.md` is supported as package resource, but runtime only reads references when skill instructions or the LLM explicitly request them.

## Failure Rules

- Unknown function id: fail sync / materialize with a clear error.
- Function not allowed by the current skill bundle: fail sync / materialize.
- Missing function schema: fail sync / materialize.
- Unresolved `${@schema.` after compilation: fail sync / materialize.
- Duplicate placeholders are allowed and render deterministically.

## Upstream Migration Rule

Upstream may prepare skill markdown changes now, but production public skills must keep the hand-written contract until Navigator support is merged and deployed. After support is available, upstream can replace the hand-written function contract block with:

```md
## Function Contracts

${@schema.tms.order.createOpeningDraft}
```

Do not put schema placeholders into short skill descriptions / frontmatter description fields. Keep those fields for routing and trigger semantics.

## Target Code Areas

- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/SkillRegistryService.java`
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessFunctionRegistryService.java`
- `business-agent-module/src/test/java/com/foggy/navigator/business/agent/service/SkillRegistryServiceTest.java`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/routes/skills.py` only if Worker-side resource validation needs a compatible adjustment.

## Acceptance Criteria

1. Public skill `markdownBody` can contain `${@schema.tms.order.createOpeningDraft}`.
2. Public skill `references/**/*.md` resource content can contain the same placeholder.
3. Materialized runtime skill context contains the rendered markdown contract at the original placeholder location.
4. Rendered contract includes public input/output fields from registered schema.
5. Rendered contract does not include adapter mappings, gateway paths, auth headers, tokens, or sensitive fields.
6. Unknown / missing / unauthorized placeholders fail closed.
7. Existing skills without placeholders keep current behavior.
8. Tests cover placeholder resolution in both `SKILL.md` and `references/`.

## Progress Tracking

### Development Progress

- [x] Requirement evaluated and accepted with v1 constraints.
- [x] Work item recorded before implementation.
- [x] Placeholder parser and renderer implemented.
- [x] Materialized markdown and markdown resources resolve placeholders.
- [x] Error paths fail closed with clear messages.

### Testing Progress

- [x] Unit tests for `SKILL.md` placeholder success.
- [x] Unit tests for `references/**/*.md` placeholder success.
- [x] Unit tests for unresolved / unknown / unauthorized placeholders.
- [x] Unit tests confirm sensitive manifest / adapter fields are not rendered.
- [x] Targeted Maven test command recorded.

Latest command:

```powershell
mvn -pl business-agent-module -am "-Dtest=SkillRegistryServiceTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Result: `17 passed`.

### Experience Progress

- N/A: this is a backend / runtime contract change with no direct UI workflow.

## Execution Checklist

- [x] Implementation follows existing materialize flow and does not introduce Worker runtime lazy fetch.
- [x] Function schema rendering uses sanitized public fields only.
- [x] Resource payload hashing / validation remains compatible after placeholder expansion.
- [x] Existing best-effort sync materialize behavior remains compatible except invalid placeholders are reported clearly.
- [x] Progress and test status are updated before handoff.

## Execution Check-in

- Completed work: added materialize-time schema placeholder validation, rendering, markdown/resource expansion, and resource sha256 recomputation.
- Touched code paths:
  - `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/SkillRegistryService.java`
  - `business-agent-module/src/test/java/com/foggy/navigator/business/agent/service/SkillRegistryServiceTest.java`
  - `docs/version-tracker/1.3.0-SNAPSHOT/README.md`
- Self-check: scope stayed within public skill bundle materialization; no Worker runtime lazy fetch added; private `manifestJson` / `adapterConfigJson` are not rendered.
- Test status: pass, targeted Maven command above.
- Residual risk: live TMS manifest should be smoke-tested after deployment to confirm the rendered contract includes the exact production fields such as `structured_output.query.aiDraftId`.
- Acceptance readiness: ready for review; formal quality gate not required for this narrow backend contract change unless release policy requires it.
