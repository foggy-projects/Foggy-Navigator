# BUG-044 Public Skill QueryModel Schema Rendering

## 文档作用

- doc_type: workitem
- intended_for: execution-agent, reviewer, upstream-integrator
- purpose: 记录 TMS Navigator Agent SOA 财务查询分层验证中发现的 public skill / BusinessFunction schema 渲染问题，给 Navi 后续修复使用。

## Status

- Version: `1.3.0-SNAPSHOT`
- Source: TMS X6 Navigator Agent skill 分层重构联调
- Type: bug / platform contract
- Priority: high
- Status: fixed in Navigator, pending public skill resync / TMS E2E confirmation
- Owner: Navigator business-agent / BizWorker integration
- Date: 2026-05-31

## Background

TMS 正在把 `foggy-query-agent` 收敛为通用 Foggy dataset query DSL skill，并把 SOA 财务候选查询 recipe 迁回 `tms-pay-agent`。联调时需要真实 trace E2E 继续严格验证：

- `queryModel` 入参的 `payload.slice` 必须是对象数组。
- `OrderSettlementCandidateQuery` 场景必须出现精确 `$or` key。
- 不允许把 `$or` 对象序列化成字符串。

TMS 已对齐 Foggy Data MCP 引擎侧 schema / 描述：

- `D:\foggy-projects\foggy-data-mcp\foggy-data-mcp-bridge\foggy-dataset-mcp\src\main\resources\schemas\descriptions\compose_script_m2.md`
- `D:\foggy-projects\foggy-data-mcp\foggy-data-mcp-bridge\foggy-dataset-mcp\src\main\resources\schemas\descriptions\query_model_v3_basic.md`
- `D:\foggy-projects\foggy-data-mcp\foggy-data-mcp-bridge\foggy-dataset-mcp\src\main\resources\schemas\query_model_v3_schema.json`
- `D:\foggy-projects\foggy-data-mcp\foggy-data-mcp-bridge\foggy-dataset-mcp\src\main\resources\schemas\compose_query_schema.json`

当前 Navi materialized runtime 文件仍可观察到旧内容：

```text
D:\foggy-projects\Foggy-Navigator-wt-qd-win11-dev\tools\langgraph-biz-worker\skills\public\apps\capp_c958991d-bcf1-40b9-b54a-78248ccdc56c\foggy-query-agent\SKILL.md
```

该文件仍包含旧 SOA 查询示例，说明 skill sync / materialize 状态可能滞后。同时，Function Contracts 当前更像 markdown field list，不是标准 JSON Schema。对于 `queryModel` 这类深层嵌套、带 `$or` / `$and` 递归逻辑组的入参，field list 容易丢失结构约束，导致 LLM 生成字符串化 `$or` 或旧式 shorthand。

## Problem Statement

`SKILL.md` 本身不应该替换为纯 JSON Schema。它需要继续承载 routing、语义边界、业务取舍和少量高质量示例。

真正的问题是 Navi 在 public skill materialize 替换 `${@schema.<functionId>}` 时，把 BusinessFunction 的 JSON Schema 渲染成了 Markdown field list。这个格式和 LLM tools `parameters` 使用的结构化 JSON Schema 不一致，对复杂 DSL 的约束表达不足：

- 容易丢失 `payload.slice.items` 的对象结构。
- 容易丢失精确 key `$or` / `$and`。
- 难以表达 `$or` / `$and` 下仍然是过滤对象数组的递归结构。
- 难以表达 `payload.columns` / `groupBy` / `orderBy` / `limit` / `pivot` / `timeWindow` / `having` 等结构边界。
- 引擎 schema 兼容 legacy shorthand 的 `additionalProperties: true` 不应被渲染成 LLM 推荐写法。

本次修复口径：

- 保持 `SKILL.md` 为 Markdown，不把技能正文替换成 raw schema。
- `${@schema.<functionId>}` 替换出的 Function Contract 必须保留与 LLM tool `parameters` 一致的 JSON Schema 结构。
- 渲染结果可以包在 Markdown fenced JSON 中，但 fenced JSON 内部不能再被摊平成 `payload.slice[].field` 这类路径列表。
- LLM-facing schema 应优先表达推荐写法；legacy shorthand 只能作为兼容说明，不作为推荐结构。

## Target Outcome

Navi 侧建议采用 Markdown skill 与标准 JSON Schema 并存的契约：

1. `SKILL.md` 保持 Markdown，用于路由、语义约束、业务边界和少量 canonical examples。
2. BusinessFunction / tool input schema 使用与 LLM tool `parameters` 同构的标准 JSON Schema，优先从上游 manifest 或引擎 schema 派生，保留 schema version / source / hash 便于排查漂移。
3. materialize 后的 Function Contracts 对复杂字段提供结构化 JSON Schema contract，尤其是 `payload.slice.items` 的过滤对象和逻辑组。
4. LLM-facing schema / contract 明确推荐标准过滤对象，不推荐 legacy shorthand。
5. `$or` / `$and` 必须作为对象 key 保留，不能变成字符串、自然语言字段名或 markdown-only 约定。

## Scope And Ownership

Navigator 负责：

- public skill schema placeholder / BusinessFunction contract renderer。
- langgraph-biz-worker materialized skill context 中的 function contract 注入方式。
- schema 渲染的敏感字段过滤、版本标识和测试覆盖。

TMS / upstream 负责：

- 提供已对齐引擎 schema 的 skill markdown 与业务 recipe。
- Navi 修复后重新执行 skill sync / materialize。
- 重新跑 SOA finance trace E2E，确认 `$or` 不再被字符串化。

## Acceptance Criteria

1. materialized `foggy-query-agent` 完成同步后，不再包含 TMS SOA 财务候选查询业务 recipe。
2. `tms.dataset.queryModel` 或等价 BusinessFunction 的 input contract 在 Worker runtime / materialized skill context 中可看到标准 JSON Schema 或等价的结构化 schema object，格式与 LLM tool `parameters` 保持一致。
3. schema 明确表达 `payload.slice` 是数组，数组元素是过滤对象或 `$or` / `$and` 逻辑组对象。
4. `$or` / `$and` 是精确 key，且 value 是过滤对象数组；不得仅以 markdown 文本说明或扁平字段路径代替。
5. LLM-facing contract 不把 legacy shorthand 渲染为推荐写法。
6. 针对 public skill materialize 增加回归测试，覆盖深层 queryModel schema 不被扁平化丢失。
7. TMS SOA finance trace E2E 能观察到 `OrderSettlementCandidateQuery` 且 `payload.slice` 中存在对象结构 `$or`，不是字符串。

## Non Goals

- 不要求把完整 `SKILL.md` 替换为 raw JSON Schema。
- 不把 SOA 财务查询 recipe 移回 `foggy-query-agent`。
- 不修改 Foggy query engine 的执行语义。
- 不暴露 adapter config、token、internal gateway path 等私有 runtime 字段。

## Progress Tracking

### Development Progress

- [x] Issue recorded with observed upstream / engine paths.
- [x] Confirm current public skill materialize path and schema rendering code path.
- [x] Design JSON Schema rendering / injection contract: schema placeholder output should be LLM tool parameters style JSON Schema, wrapped in Markdown only as transport.
- [x] Implement renderer or schema object passthrough.
- [x] Add regression tests for nested `queryModel` schema and `$or` / `$and`.
- [ ] Coordinate with TMS to resync public skills.

### Testing Progress

- [x] Navigator targeted unit tests for public skill schema rendering.
- [ ] Worker materialize smoke confirms runtime contract contains nested JSON Schema.
- [ ] TMS real trace E2E confirms SOA finance query keeps object `$or`.

### Implementation Notes

- Updated Navigator `SkillRegistryService` schema placeholder renderer to emit `Input JSON Schema` / `Output JSON Schema` fenced JSON blocks.
- Removed Markdown field-list flattening for BusinessFunction schemas, so nested JSON Schema constructs such as `$ref`, `payload.slice.items`, `oneOf`, `$or`, `$and`, and `additionalProperties` are preserved in the materialized Function Contract.
- Normalized rendered JSON Schema line endings to LF before sending to the worker, avoiding extra blank lines when Windows text-mode writers persist materialized `SKILL.md`.
- Added regression coverage in `SkillRegistryServiceTest` to assert queryModel schema materialization preserves nested JSON Schema and does not render flattened paths like `payload.slice[].field`.

### Verification

```powershell
mvn -pl business-agent-module -am -DskipTests compile
mvn -pl business-agent-module -am -Dtest=SkillRegistryServiceTest '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Result: both commands passed locally on 2026-05-31.

### Experience Progress

- N/A: backend / worker contract change, no direct UI workflow.

## Handoff Notes

TMS local skill refactor already keeps `foggy-query-agent` generic and moves SOA candidate query guidance into `tms-pay-agent`. After Navi fixes schema rendering and materializes current upstream skill content, TMS should rerun:

```powershell
npm --prefix tests exec vitest run tests/e2e/navigator-agent-finance-tool-trace.e2e.test.ts -- --no-file-parallelism
```

The key validation is not just model name selection. The trace must preserve `payload.slice` as an object array with exact `$or` key.
