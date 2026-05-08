# Artifact 与账号私有 Skill 创建实施计划

## 文档作用

- doc_type: implementation-plan
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 将 `1.1.1-SNAPSHOT` 的 Artifact 外部化、长参数上下文治理、账号私有 Skill 创建闭环拆解为可执行计划

## 上游依据

1. `docs/version-tracker/1.1.1-SNAPSHOT/README.md`
2. `docs/version-tracker/1.1.1-SNAPSHOT/01-biz-worker-artifact-externalization-design.md`
3. `docs/version-tracker/1.1.1-SNAPSHOT/02-account-skill-write-file-permission.md`
4. `docs/version-tracker/1.1.1-SNAPSHOT/04-account-file-tools-design.md`
5. `docs/version-tracker/1.1.0-SNAPSHOT/acceptance/version-signoff.md`

## 与版本目标的关系

本计划覆盖 `1.1.0-SNAPSHOT` 封版遗留的两个 follow-up：

1. Artifact 外部化与长参数上下文治理。
2. 账号私有 Skill 写入权限与创建闭环。
3. 账号目录文件工具族与统一路径权限模型。

本计划不扩展到真实外部业务工具、对象存储、二进制文件、跨账号共享或通用文件管理。

## Ownership

| Scope | Owner | Responsibility |
|---|---|---|
| Workspace root | root-controller | 维护版本目标、执行顺序、验收证据入口 |
| `tools/langgraph-biz-worker` | execution-agent | 实现 Artifact Runtime、LLM tool loop 接入、账号目录文件工具族 |
| `tools/langgraph-biz-worker/tests` | execution-agent | 补单元测试、权限拒绝测试、query-time 路由回归测试 |
| `docs/version-tracker/1.1.1-SNAPSHOT` | root-controller + reviewer | 记录 progress、quality、coverage、acceptance |

## 当前代码触点

```yaml
code_inventory:
  - repo: workspace
    path: docs/version-tracker/1.1.1-SNAPSHOT
    role: version tracking and acceptance evidence
    expected_change: update
    notes: 记录设计收口、执行进度、质量与验收结论

  - repo: workspace
    path: tools/langgraph-biz-worker/src/langgraph_biz_worker/models.py
    role: shared worker request/frame/event models
    expected_change: update
    notes: 如新增 Artifact metadata/result model，优先保持 Pydantic model 简洁

  - repo: workspace
    path: tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime
    role: runtime-owned state and storage logic
    expected_change: create/update
    notes: 适合新增 artifact store、account path guard、account file tools；避免把权限逻辑散到 tool loop

  - repo: workspace
    path: tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_agent.py
    role: LLM tool schema binding and tool-call dispatch
    expected_change: update
    notes: 接入 create_artifact/read_artifact/read_file/list_files/write_file/edit_file/str_replace/patch_file；工具仍由 runtime 执行，不让模型直接接触物理路径

  - repo: workspace
    path: tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/skill_registry.py
    role: Skill.md layered loading and account-private skill discovery
    expected_change: update
    notes: 已支持 account skill 只读加载；本轮主要补创建后 reload 回归，必要时只做小幅适配

  - repo: workspace
    path: tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py
    role: query-time runtime wiring
    expected_change: update
    notes: 如需要注入 artifact store 或 account/task context，从这里接入全局 runtime wiring

  - repo: workspace
    path: tools/langgraph-biz-worker/tests
    role: regression and acceptance tests
    expected_change: create/update
    notes: 覆盖 Artifact、路径权限、Skill Registry reload、query-time account routing
```

## 执行阶段

### Stage 0: 契约收口

目标：

1. 固定 `create_artifact` / `read_artifact` 输入输出 schema。
2. 固定账号目录文件工具族输入输出 schema。
3. 固定上下文治理落点：tool result 返回轻量引用，Frame 私有上下文不长期保留原始长 `content`。
4. 固定 Linux-only 路径安全口径：拒绝路径链路 symlink 与目标 symlink。
5. 固定 `patch_file` 为 unified diff、单文件、原子写入。

建议默认决策：

1. 首版使用账号目录受控文件工具，不暴露不受账号根目录与路径白名单约束的裸文件系统工具。
2. 首版 artifact 只支持文本与 JSON。
3. `account_id`、`task_id` 只由 runtime 注入，模型不得传入。
4. `read_artifact(mode=content)` 必须显式请求全文。
5. `write_file(mode=overwrite)` 纳入 1.1.1 首版范围，必须显式传入并覆盖权限、runtime audit record 测试。
6. `patch_file` 纳入 1.1.1 必做范围，使用 unified diff，完成冲突处理和原子写入测试后暴露给模型。
7. `create_artifact` / `write_file` 首版单次内容上限 1MB。
8. `read_file` 默认最多返回 200 行或 64KB。
9. `.fsscript` 仅作为文本资源允许写入 `references/**` 或 `assets/**`。

完成门槛：

1. 设计文档已标注契约和非目标。
2. 如调整工具命名或权限范围，先回写本文档和 `01` / `02` / `04`。
3. 契约已明确 Worker 自己持久化或继续传给 LLM 的历史消息必须 scrub 原始 `content`。

### Stage 1: Artifact Runtime 文件后端

目标：

1. 在账号目录下创建 task/account scope artifact。
2. 写入 metadata 与 content。
3. 支持按 `artifact_id` 读取 metadata、summary、content。
4. 校验账号和任务访问边界。

测试要求：

1. 创建 task-scope artifact 成功。
2. 创建 account-scope artifact 成功。
3. `mode=metadata|summary|content` 返回边界正确。
4. 跨账号读取被拒绝。
5. task-scope 跨任务读取被拒绝。
6. metadata 中 `size`、`sha256`、`content_ref` 正确，且 `content_ref` 不返回给模型。
7. 超过 1MB 的 `create_artifact` 被拒绝。

### Stage 2: LLM Tool Loop 接入与上下文治理

目标：

1. 在 LLM tool schema 中暴露 `create_artifact` / `read_artifact`。
2. 执行 tool call 时由 runtime 注入账号、任务、Frame 上下文。
3. `create_artifact` 的 tool result 只返回轻量引用，不回传原始 `content`。
4. Frame 的 `artifact_refs` 能接收并上浮到最终 Skill result。

测试要求：

1. LLM tool call 能创建 artifact 并拿到 `artifact_id`。
2. 后续 tool message / private message / Frame state / provider message history 不长期保留原始长 `content`，而是替换为轻量占位。
3. `submit_skill_result(artifact_refs=[...])` 后 Frame close 仍只上浮引用。

### Stage 3: 账号目录文件工具族

目标：

1. 暴露受控 `read_file`、`list_files`、`write_file`、`edit_file`、`str_replace`、`patch_file`。
2. 所有工具共用 AccountPathGuard，不接受真实文件系统路径。
3. 默认只允许访问当前账号目录下的白名单路径。
4. Skill 写入只允许：
   - `skills/<skill-name>/SKILL.md`
   - `skills/<skill-name>/references/**`
   - `skills/<skill-name>/assets/**`
5. 禁止访问公共 Skill、内建 Skill、其他账号目录、绝对路径、`../`。
6. 禁止访问 symlink 父目录和 symlink 目标。
7. `list_files` 可列 `skills/` 与 `skills/<skill-name>/`；根部只允许 `SKILL.md`，`references/**` 和 `assets/**` 允许 `.md/.txt/.json/.yaml/.yml/.fsscript` 文本资源。

实现优先级：

1. P0：`list_files`、`read_file`、`write_file`。
2. P1：`str_replace`、`edit_file`。
3. P2：`patch_file`，本版本确认实现，必须在 unified diff、单文件、原子写入和冲突处理测试收口后暴露。

测试要求：

1. `list_files` 不泄漏物理路径。
2. `read_file` 默认最多返回 200 行或 64KB，避免长文件直接进入上下文。
3. `write_file(mode=create)` 正常创建 `<data_root>/accounts/user-001/skills/my-skill/SKILL.md`。
4. `write_file(mode=create)` 遇到已存在文件被拒绝。
5. `write_file(mode=overwrite)` 必须显式传入，未显式传入时不能覆盖已有文件。
6. `write_file(mode=overwrite)` 正常覆盖允许路径内的已有文件，并记录 runtime audit record。
7. `str_replace` 对唯一匹配内容替换成功，多匹配或零匹配被拒绝。
8. `edit_file` 必须基于明确旧内容或版本信息，避免盲改。
9. `patch_file` unified diff 冲突时被拒绝且不产生半写入。
10. 写入 `<data_root>/accounts/user-002/...` 被拒绝。
11. 写入 `skills/public/...` 被拒绝。
12. 写入 `../outside.txt` 被拒绝。
13. 写入绝对路径被拒绝。
14. symlink 父目录和 symlink 目标被拒绝。
15. `.fsscript` 可写入 `references/**` 或 `assets/**`，不可替代根部 `SKILL.md`。
16. 超过 1MB 的 `write_file` 被拒绝。
17. `expected_sha256` 不匹配时 `overwrite` / `patch_file` 被拒绝。

### Stage 4: 账号私有 Skill 创建闭环

目标：

1. Skill Writer 指导 LLM 生成 Skill 内容和文件路径。
2. LLM 通过 `write_file` 写入账号私有 Skill 目录。
3. 写入成功后 `SkillRegistry.load(account_id)` 能发现新 Skill。
4. query-time 使用 `userId/accountId` 能路由到新 Skill。

测试要求：

1. 正常创建 `skills/my-skill/SKILL.md` 成功。
2. 写入 `references/**` 与 `assets/**` 成功。
3. registry reload 能发现新 Skill。
4. query-time 使用 `userId=user-001` 能路由到新 Skill。
5. 写入 `skills/my-skill/references/example.fsscript` 成功，但 `.fsscript` 不触发执行语义。

### Stage 5: 回归、质量与验收

必须执行：

1. `tools/langgraph-biz-worker`: `.venv/Scripts/python.exe -m pytest tests`
2. Artifact 定向测试。
3. Skill 创建与路由定向测试。
4. 实现自检。
5. `foggy-implementation-quality-gate`。
6. `foggy-test-coverage-audit`。
7. `foggy-acceptance-signoff`。

如测试依赖真实 LLM 或外部服务不可用，progress 必须写明未运行原因，不能标记为完成。

## 风险与控制

| Risk | Impact | Control |
|---|---|---|
| 裸文件系统写入权限面过大 | 模型可能写入非预期目录 | 首版只做受控 `write_file`，服务端强校验 resolved path |
| artifact 物理路径泄漏给模型 | 后续存储迁移困难，越权风险上升 | 只暴露 `artifact_id`，`content_ref` 内部使用 |
| 长内容仍进入 private messages | 上下文治理目标落空 | `create_artifact` tool result 返回轻量引用，并补测试断言 |
| 长内容仍残留在历史 tool-call args | 上下文治理目标落空 | Worker 后续持久化或继续传给 LLM 的消息中 scrub 原始 `content` |
| task-scope artifact 跨任务读取 | 数据隔离失效 | read 时校验 `account_id + task_id + scope` |
| Skill 创建后 registry 未 reload | 创建闭环不可用 | 补 registry reload 和 query-time routing 回归 |
| symlink 绕过账号目录边界 | 模型越权读写 | Linux-only 首版拒绝路径链路和目标 symlink |
| 并发覆盖导致丢更新 | Skill 内容被旧上下文覆盖 | 同一文件写串行化，支持 `expected_sha256` 乐观并发校验 |

## Progress 回写要求

实现开始后，建议新增：

```text
docs/version-tracker/1.1.1-SNAPSHOT/progress/artifact-and-account-skill-progress.md
```

progress 至少记录：

1. 每个 Stage 的状态。
2. 计划外变更。
3. 实现自检结论。
4. 测试命令与结果。
5. 未运行测试及原因。
6. 剩余风险。

## 决策检查点

如执行中出现以下变化，需要先暂停并评审：

1. 需要把 `write_file` 扩展为超出账号根目录或路径白名单的通用文件工具。
2. 需要支持二进制或大文件上传。
3. 需要让模型看到真实文件路径。
4. 需要跨账号或跨任务读取 artifact。
5. 需要修改 Java 侧 A2A / session-module 契约。
6. 需要让 `.fsscript` 具备执行语义或替代 `SKILL.md`。
